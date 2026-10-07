package dev.amps.backend.service

import dev.amps.backend.model.ApiResponse
import dev.amps.backend.model.ApiResponse.Companion.ok
import dev.amps.backend.model.TwoFactorSetupResponse
import dev.amps.backend.model.TwoFactorStatusResponse
import dev.amps.backend.model.UserEntity
import dev.amps.backend.model.UserTable
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 1.1.0: RFC 6238 (TOTP) — серверна копія реалізації з застосунку
 * (dev.amps.app.security.Totp), яка в 1.0.9 пройшла всі шість тестових
 * векторів RFC 6238. Спільний алгоритм і константи: застосунок-аутентифікатор
 * (Google Authenticator тощо) бачить однакові коди обабіч.
 */
object Totp {

    /** Довжина кроку в секундах — як у Google Authenticator. */
    const val STEP_SECONDS = 30L

    /** Скільки цифр у коді. */
    const val DIGITS = 6

    /** Наскільки кроків у кожен бік приймається код. */
    private const val WINDOW = 1

    private const val BITS_PER_BYTE = 8
    private const val BITS_PER_CHAR = 5
    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val ALGORITHM = "HmacSHA1"

    /** Рекомендований RFC 4226 розмір секрета. */
    private const val SECRET_BYTES = 20

    private val random = SecureRandom()

    /** Свіжий секрет Base32, готовий для введення в аутентифікатор. */
    fun newSecret(): String {
        val bytes = ByteArray(SECRET_BYTES).also { random.nextBytes(it) }
        return base32Encode(bytes)
    }

    /**
     * Перевіряє код, уведений людиною.
     *
     * Вікно ±1 крок — через розсинхронізацію годинників: той, хто вводить
     * код, часто дивиться на вже наступне вікно.
     */
    fun verify(secret: String, code: String, atMillis: Long = System.currentTimeMillis()): Boolean {
        val normalized = code.filter { it.isDigit() }
        if (normalized.length != DIGITS) return false
        val step = atMillis / 1000 / STEP_SECONDS
        for (offset in -WINDOW..WINDOW) {
            // Порівняння постійного часу: інакше за часом відповіді можна
            // підбирати код по одній цифрі за раз.
            if (secureEquals(generate(secret, step + offset), normalized)) return true
        }
        return false
    }

    /** Посилання для додавання в аутентифікатор одним дотиком. */
    fun otpauthUri(secret: String, account: String, issuer: String): String =
        "otpauth://totp/${encodeUriPart(issuer)}:${encodeUriPart(account)}" +
            "?secret=$secret&issuer=${encodeUriPart(issuer)}&algorithm=SHA1" +
            "&digits=$DIGITS&period=$STEP_SECONDS"

    private fun generate(secret: String, step: Long): String {
        val key = base32Decode(secret) ?: return ""
        val counter = ByteArray(8)
        var value = step
        // 64-бітний лічильник, старші байти першими — як вимагає RFC.
        for (index in 7 downTo 0) {
            counter[index] = (value and 0xFF).toByte()
            value = value shr 8
        }

        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(key, ALGORITHM))
        val hash = mac.doFinal(counter)

        // Динамічне зміщення: молодший біт останнього байта каже,
        // які чотири байти хеша рахувати числом.
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)

        var modulus = 1
        repeat(DIGITS) { modulus *= 10 }
        return (binary % modulus).toString().padStart(DIGITS, '0')
    }

    private fun secureEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (index in a.indices) {
            diff = diff or (a[index].code xor b[index].code)
        }
        return diff == 0
    }

    private fun base32Encode(bytes: ByteArray): String {
        val builder = StringBuilder()
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bits += BITS_PER_BYTE
            while (bits >= BITS_PER_CHAR) {
                builder.append(BASE32_ALPHABET[(buffer shr (bits - BITS_PER_CHAR)) and 0x1F])
                bits -= BITS_PER_CHAR
            }
        }
        if (bits > 0) {
            builder.append(BASE32_ALPHABET[(buffer shl (BITS_PER_CHAR - bits)) and 0x1F])
        }
        return builder.toString()
    }

    private fun base32Decode(text: String): ByteArray? {
        val clean = text.trim().uppercase().replace(" ", "").replace("-", "")
        if (clean.isEmpty()) return null
        val bytes = ByteArray(clean.length * BITS_PER_CHAR / BITS_PER_BYTE)
        var buffer = 0
        var bits = 0
        var index = 0
        for (char in clean) {
            val position = BASE32_ALPHABET.indexOf(char)
            if (position < 0) return null
            buffer = (buffer shl BITS_PER_CHAR) or position
            bits += BITS_PER_CHAR
            if (bits >= BITS_PER_BYTE) {
                bits -= BITS_PER_BYTE
                bytes[index++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return if (index == bytes.size) bytes else null
    }

    private fun encodeUriPart(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/**
 * 2FA на сервері. Секрет зберігається в базі; увімкнення — лише після
 * першого вдалого коду, тож просто згенерований секрет ще не блокує
 * вхід: поки код не введено правильно, twoFactorEnabled лишається false.
 */
class TwoFactorService {

    suspend fun status(userId: Int): ApiResponse<TwoFactorStatusResponse> = newSuspendedTransaction {
        val user = UserEntity.find { UserTable.id eq userId }.firstOrNull()
            ?: return@newSuspendedTransaction ApiResponse.fail<TwoFactorStatusResponse>(
                "Користувача не знайдено", "NO_USER")
        ok(TwoFactorStatusResponse(user.twoFactorEnabled))
    }

    suspend fun setup(userId: Int): ApiResponse<TwoFactorSetupResponse> = newSuspendedTransaction {
        val user = UserEntity.find { UserTable.id eq userId }.firstOrNull()
            ?: return@newSuspendedTransaction ApiResponse.fail<TwoFactorSetupResponse>(
                "Користувача не знайдено", "NO_USER")
        val secret = Totp.newSecret()
        user.twoFactorSecret = secret
        user.updatedAt = System.currentTimeMillis()
        ok(TwoFactorSetupResponse(secret, Totp.otpauthUri(secret, user.login, "AMPS")))
    }

    suspend fun verify(userId: Int, code: String): ApiResponse<TwoFactorStatusResponse> = newSuspendedTransaction {
        val user = UserEntity.find { UserTable.id eq userId }.firstOrNull()
            ?: return@newSuspendedTransaction ApiResponse.fail<TwoFactorStatusResponse>(
                "Користувача не знайдено", "NO_USER")
        val secret = user.twoFactorSecret
            ?: return@newSuspendedTransaction ApiResponse.fail<TwoFactorStatusResponse>(
                "Спочатку створіть секрет 2FA", "NO_2FA")
        if (!Totp.verify(secret, code)) {
            return@newSuspendedTransaction ApiResponse.fail<TwoFactorStatusResponse>(
                "Невірний код", "BAD_CODE")
        }
        user.twoFactorEnabled = true
        user.updatedAt = System.currentTimeMillis()
        ok(TwoFactorStatusResponse(true))
    }

    suspend fun disable(userId: Int): ApiResponse<TwoFactorStatusResponse> = newSuspendedTransaction {
        val user = UserEntity.find { UserTable.id eq userId }.firstOrNull()
            ?: return@newSuspendedTransaction ApiResponse.fail<TwoFactorStatusResponse>(
                "Користувача не знайдено", "NO_USER")
        user.twoFactorEnabled = false
        user.twoFactorSecret = null
        user.updatedAt = System.currentTimeMillis()
        ok(TwoFactorStatusResponse(false))
    }
}
