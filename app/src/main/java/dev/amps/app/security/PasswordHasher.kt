package dev.amps.app.security

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 1.0.9: хранение паролей.
 *
 * Пароль никогда не лежит текстом и не кодируется «для вида» в Base64 —
 * Base64 это кодирование, а не защита. Хранится PBKDF2 с индивидуальной
 * солью, и проверка идёт сравнением хешей за постоянное время.
 *
 * **Почему PBKDF2, а не Argon2.** Argon2 лучше, но библиотеки для него в
 * проекте нет, а сборка идёт с `--offline` и не скачает новую зависимость.
 * PBKDF2 входит в сам Android (`javax.crypto`), поэтому выбор — не
 * предпочтение, а единственное доступное. Число итераций подобрано так,
 * чтобы на телефоне проверка занимала доли секунды, а не секунды.
 *
 * **Почему сравнение постоянного времени.** Обычное `==` на строках
 * останавливается на первом различии, и по времени ответа можно
 * подобрать хеш побайтно. Здесь сравнение идёт накопительно через
 * [MessageDigest.isEqual], которое заканчивает работу целиком.
 */
object PasswordHasher {

    /** Сколько раз повторяется хеширование. */
    const val ITERATIONS = 210_000

    /** Длина соли в байтах. */
    private const val SALT_BYTES = 16

    /** Длина хеша в байтах. */
    private const val HASH_BYTES = 32

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    private val random = SecureRandom()

    /**
     * Всё, что нужно сохранить в базе: соль и хеш в Base64.
     * Число итераций тоже хранится — при смене настройки старые пароли
     * должны продолжать проверяться, иначе все залогины сломаются разом.
     */
    data class Digest(val salt: String, val hash: String, val iterations: Int)

    fun create(password: String): Digest {
        require(password.isNotEmpty()) { "пустой пароль" }
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val hash = derive(password, salt, ITERATIONS)
        return Digest(
            salt = encode(salt),
            hash = encode(hash),
            iterations = ITERATIONS,
        )
    }

    /**
     * Сверяет пароль с сохранённым хешем.
     *
     * Возвращает `false` и на неверном пароле, и на битой записи: отличить
     * «пароль не тот» от «строка в базе испорчена» через один и тот же ответ
     * безопаснее, а диагностика всё равно бесполезна пользователю.
     */
    fun verify(password: String, digest: Digest): Boolean {
        if (password.isEmpty()) return false
        val salt = decode(digest.salt) ?: return false
        val expected = decode(digest.hash) ?: return false
        val iterations = digest.iterations.takeIf { it > 0 } ?: ITERATIONS
        return MessageDigest.isEqual(derive(password, salt, iterations), expected)
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            // Спецификация хранит пароль в памяти до сборки мусора.
            // `clearPassword` стирает его раньше.
            spec.clearPassword()
        }
    }

    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String): ByteArray? = try {
        Base64.decode(text, Base64.NO_WRAP)
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * Проверка сложности при регистрации и смене пароля.
     *
     * Требования намеренно умеренные: длинный пароль надёжнее сложного из
     * четырёх символов, и требование «спецсимвол и цифра» заставляет людей
     * писать `Password1!` — то есть переиспользовать один и тот же простой
     * пароль по всей сети. Поэтому длина, а не набор символов.
     */
    fun validate(password: String): String? = when {
        password.length < 8 -> "минимум 8 символов"
        password.all { it.isDigit() } -> "не только цифры"
        password.all { !it.isLetterOrDigit() } -> "нужны буквы или цифры"
        password.lowercase() == password || password.uppercase() == password ->
            "пароль без различия регистра слишком прост"
        else -> null
    }
}