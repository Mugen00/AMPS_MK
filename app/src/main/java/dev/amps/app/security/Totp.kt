package dev.amps.app.security

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 1.0.9: коды Google Authenticator.
 *
 * Реализация RFC 6238 целиком внутри приложения. Внешний сервис для
 * генерации кодов не нужен: телефон и приложение считают их одинаково по
 * общему секрету и текущему времени.
 *
 * **Почему это не «проверка через интернет».** Обычный вход с кодом из
 * письма требует сервера, который слышит и код, и пароль. Здесь секрет
 * хранится только на телефоне, а код — производная от времени: перехват
 * одного кода не даёт доступа, потому что он живёт 30 секунд и сменится.
 */
object Totp {

    /** Длина шага в секундах — как в Google Authenticator. */
    const val STEP_SECONDS = 30L

    /** Сколько цифр в коде. */
    const val DIGITS = 6

    /** Насколько шагов в каждую сторону принимается. */
    private const val WINDOW = 1

    /** Байт в октете Base32. */
    private const val BITS_PER_BYTE = 8

    /** Сколько бит занимает один знак Base32. */
    private const val BITS_PER_CHAR = 5

    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    private const val ALGORITHM = "HmacSHA1"

    /** Рекомендованный RFC 4226 размер секрета. */
    private const val SECRET_BYTES = 20

    private val random = SecureRandom()

    /** Свежий секрет в виде строки Base32, готовой для ввода на телефоне. */
    fun newSecret(): String {
        val bytes = ByteArray(SECRET_BYTES).also { random.nextBytes(it) }
        return base32Encode(bytes)
    }

    /**
     * Проверяет код, введённый человеком.
     *
     * [window] нужен из-за рассинхронизации часов. Телефон и компьютер с
     * приложением-аутентификатором могут отличаться на секунды, и человек,
     * вводящий код, смотрит на уже следующее окно. Без запаса проверка
     * отказывала бы в верном коде.
     */
    fun verify(secret: String, code: String, atMillis: Long = System.currentTimeMillis()): Boolean {
        val normalized = code.filter { it.isDigit() }
        if (normalized.length != DIGITS) return false
        val step = atMillis / 1000 / STEP_SECONDS
        for (offset in -WINDOW..WINDOW) {
            val expected = generate(secret, step + offset)
            // Сравнение постоянного времени: иначе по времени ответа можно
            // было бы подбирать код по одной цифре за раз.
            if (secureEquals(expected, normalized)) return true
        }
        return false
    }

    /** Код для текущего окна — нужен самому приложению для показа в тестах. */
    fun currentCode(secret: String, atMillis: Long = System.currentTimeMillis()): String =
        generate(secret, atMillis / 1000 / STEP_SECONDS)

    /** Ссылка для добавления в приложение-аутентификатор одним касанием. */
    fun otpauthUri(secret: String, account: String, issuer: String): String =
        "otpauth://totp/${encodeUriPart(issuer)}:${encodeUriPart(account)}" +
            "?secret=$secret&issuer=${encodeUriPart(issuer)}&algorithm=SHA1" +
            "&digits=$DIGITS&period=$STEP_SECONDS"

    private fun generate(secret: String, step: Long): String {
        val key = base32Decode(secret) ?: return ""
        val counter = ByteArray(8)
        var value = step
        // 64-битный счётчик, старшие байты идут первыми — как требует RFC.
        for (index in 7 downTo 0) {
            counter[index] = (value and 0xFF).toByte()
            value = value shr 8
        }

        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(key, ALGORITHM))
        val hash = mac.doFinal(counter)

        // Динамическое смещение: младший бит последнего байта говорит,
        // какие четыре байта хеша считать числом.
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)

        val modulus = tenTo(DIGITS)
        return (binary % modulus).toString().padStart(DIGITS, '0')
    }

    private fun tenTo(power: Int): Int {
        var value = 1
        repeat(power) { value *= 10 }
        return value
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