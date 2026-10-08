package dev.amps.backend.service

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import dev.amps.backend.config.AppConfig
import dev.amps.backend.model.ApiResponse
import dev.amps.backend.model.ApiResponse.Companion.ok
import dev.amps.backend.model.LoginRequest
import dev.amps.backend.model.PasswordResetConfirmRequest
import dev.amps.backend.model.PasswordResetRequest
import dev.amps.backend.model.RefreshRequest
import dev.amps.backend.model.RefreshTokenEntity
import dev.amps.backend.model.RefreshTokenTable
import dev.amps.backend.model.RegisterRequest
import dev.amps.backend.model.TokenResponse
import dev.amps.backend.model.UnitResult
import dev.amps.backend.model.UserEntity
import dev.amps.backend.model.UserInfo
import dev.amps.backend.model.UserTable
import dev.amps.backend.model.VerificationCodeEntity
import dev.amps.backend.model.VerificationCodeTable
import dev.amps.backend.model.info
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.mindrot.jbcrypt.BCrypt
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Date

/**
 * 1.1.0: реєстрація, вхід, коди підтвердження, скидання пароля, токени.
 *
 * Access-токен — JWT (HS256) на 30 хвилин, носій ідентифікатора в
 * claim "uid". Refresh-токен — випадкові 256 біт; у базі лишається лише
 * хеш SHA-256, і при кожному оновленні токен ротується: старий
 * вибуває одразу після видачі нового.
 *
 * Коди підтвердження також зберігаються хешем SHA-256 і живуть
 * 10 хвилин. Password reset за невідомим email відповідає тим самим
 * «успіхом», що й за відомим: перебір emailів по відповідях сервера
 * нічого не дає.
 */
class AuthService(
    private val config: AppConfig,
    private val emailService: EmailService,
    private val smsService: SmsService,
) {

    private val random = SecureRandom()
    private val algorithm: Algorithm by lazy { Algorithm.HMAC256(config.jwtSecret) }

    suspend fun register(request: RegisterRequest): ApiResponse<TokenResponse> = newSuspendedTransaction {
        if (UserEntity.find { UserTable.login eq request.login }.any()) {
            return@newSuspendedTransaction ApiResponse.fail<TokenResponse>(
                "Логін уже зайнятий", "LOGIN_TAKEN")
        }
        if (UserEntity.find { UserTable.email eq request.email }.any()) {
            return@newSuspendedTransaction ApiResponse.fail<TokenResponse>(
                "Email уже зайнятий", "EMAIL_TAKEN")
        }
        val now = System.currentTimeMillis()
        val user = UserEntity.new {
            login = request.login
            email = request.email
            phone = request.phone?.takeIf { it.isNotBlank() }
            passwordHash = BCrypt.hashpw(request.password, BCrypt.gensalt())
            emailVerified = false
            phoneVerified = false
            twoFactorEnabled = false
            twoFactorSecret = null
            createdAt = now
            updatedAt = now
        }
        // Коди підтвердження: email — завжди, телефон — якщо вказаний.
        // Листи/SMS — best-effort: їх невдача не ламає реєстрацію.
        val emailCode = issueCode(user.id.value, "EMAIL_VERIFY")
        emailService.sendVerificationCode(user.email, emailCode)
        user.phone?.let { phone ->
            val smsCode = issueCode(user.id.value, "PHONE_VERIFY")
            smsService.sendVerificationCode(phone, smsCode)
        }
        ok(tokens(user))
    }

    suspend fun login(request: LoginRequest): ApiResponse<TokenResponse> = newSuspendedTransaction {
        val user = UserEntity.find {
            (UserTable.login eq request.login) or (UserTable.email eq request.login)
        }.firstOrNull()
            ?: return@newSuspendedTransaction ApiResponse.fail<TokenResponse>(
                "Невірний логін або пароль", "INVALID_CREDENTIALS")
        if (!BCrypt.checkpw(request.password, user.passwordHash)) {
            return@newSuspendedTransaction ApiResponse.fail<TokenResponse>(
                "Невірний логін або пароль", "INVALID_CREDENTIALS")
        }
        if (user.twoFactorEnabled) {
            // 1.1.2: повний ланцюжок «логін → код з аутентифікатора».
            // Без кода — NEED_2FA, клієнт показує крок введення; з кодом —
            // перевірка тим самим TOTP, що працює на /2fa/verify.
            val secret = user.twoFactorSecret
                ?: return@newSuspendedTransaction ApiResponse.fail<TokenResponse>(
                    "2FA увімкнена, але секрет не знайдено — вимкніть 2FA через підтримку", "NEED_2FA")
            val code = request.totp
                ?: return@newSuspendedTransaction ApiResponse.fail<TokenResponse>(
                    "Потрібен код з аутентифікатора", "NEED_2FA")
            if (!Totp.verify(secret, code)) {
                return@newSuspendedTransaction ApiResponse.fail<TokenResponse>(
                    "Невірний код з аутентифікатора", "BAD_TOTP")
            }
        }
        if (!user.emailVerified) {
            // Повторний вхід = свіжий код підтвердження: це заміна кнопки
            // «надіслати лист ще раз», яку не змогло зробити чесно без
            // окремого ендпоінту.
            val code = issueCode(user.id.value, "EMAIL_VERIFY")
            emailService.sendVerificationCode(user.email, code)
        }
        ok(tokens(user))
    }

    suspend fun verifyCode(request: dev.amps.backend.model.VerifyCodeRequest): ApiResponse<TokenResponse> =
        newSuspendedTransaction {
            val user = UserEntity.find { UserTable.login eq request.login }.firstOrNull()
                ?: return@newSuspendedTransaction ApiResponse.fail<TokenResponse>(
                    "Користувача не знайдено", "NO_USER")
            if (!consumeCode(user.id.value, request.code, request.type)) {
                return@newSuspendedTransaction ApiResponse.fail<TokenResponse>(
                    "Невірний або прострочений код", "BAD_CODE")
            }
            when (request.type) {
                "EMAIL_VERIFY" -> {
                    user.emailVerified = true
                    user.updatedAt = System.currentTimeMillis()
                }
                "PHONE_VERIFY" -> {
                    user.phoneVerified = true
                    user.updatedAt = System.currentTimeMillis()
                }
                else -> return@newSuspendedTransaction ApiResponse.fail<TokenResponse>(
                    "Невідомий тип коду", "BAD_TYPE")
            }
            ok(tokens(user))
        }

    suspend fun requestPasswordReset(request: PasswordResetRequest): ApiResponse<UnitResult> {
        // Пошук і код — у транзакції; лист — поза нею, щоб не тримати
        // з'єднання з базою під HTTP-виклик до Resend.
        val pending = newSuspendedTransaction {
            val user = UserEntity.find { UserTable.email eq request.email }.firstOrNull()
            user?.let {
                val code = issueCode(it.id.value, "PASSWORD_RESET")
                it.email to code
            }
        }
        // Однакова відповідь для будь-якого email: перебір нічого не дає.
        pending?.let { (email, code) -> emailService.sendPasswordResetCode(email, code) }
        return ok(UnitResult(true))
    }

    suspend fun confirmPasswordReset(request: PasswordResetConfirmRequest): ApiResponse<TokenResponse> =
        newSuspendedTransaction {
            val user = UserEntity.find { UserTable.email eq request.email }.firstOrNull()
                ?: return@newSuspendedTransaction ApiResponse.fail<TokenResponse>(
                    "Користувача не знайдено", "NO_USER")
            if (!consumeCode(user.id.value, request.code, "PASSWORD_RESET")) {
                return@newSuspendedTransaction ApiResponse.fail<TokenResponse>(
                    "Невірний або прострочений код", "BAD_CODE")
            }
            user.passwordHash = BCrypt.hashpw(request.newPassword, BCrypt.gensalt())
            user.updatedAt = System.currentTimeMillis()
            ok(tokens(user))
        }

    suspend fun refresh(request: RefreshRequest): ApiResponse<TokenResponse> = newSuspendedTransaction {
        val stored = RefreshTokenEntity.find {
            RefreshTokenTable.tokenHash eq sha256(request.refreshToken)
        }.firstOrNull()
            ?: return@newSuspendedTransaction ApiResponse.fail<TokenResponse>(
                "Токен недійсний", "BAD_REFRESH")
        if (stored.expiresAt < System.currentTimeMillis()) {
            stored.delete()
            return@newSuspendedTransaction ApiResponse.fail<TokenResponse>(
                "Токен прострочений", "EXPIRED_REFRESH")
        }
        val user = UserEntity.find { UserTable.id eq stored.userId }.firstOrNull()
            ?: return@newSuspendedTransaction ApiResponse.fail<TokenResponse>(
                "Користувача не знайдено", "NO_USER")
        // Ротація: старий refresh вибуває одразу.
        stored.delete()
        ok(tokens(user))
    }

    suspend fun profile(userId: Int): ApiResponse<UserInfo> = newSuspendedTransaction {
        val user = UserEntity.find { UserTable.id eq userId }.firstOrNull()
            ?: return@newSuspendedTransaction ApiResponse.fail<UserInfo>(
                "Користувача не знайдено", "NO_USER")
        ok(user.info())
    }

    /**
     * 1.2.0: вхід/реєстрація через Google. ID-токен від Credential Manager
     * перевіряється через офіційний tokeninfo-ендпоінт Google — той
     * валідує підпис JWK сам; залишається звірити "aud" (чиїй заявці
     * довіряємо), email_verified і строк дії.
     *
     * Акаунт за Google-поштою знаходиться або створюється одразу
     * підтвердженим: email вже підтвердив сам Google. Пароль такому
     * акаунту не потрібен — у хеш записується випадкове значення,
     * яке ніхто не знає; вхід можливий лише через Google або скидання
     * пароля на цей email.
     */
    suspend fun googleLogin(request: dev.amps.backend.model.GoogleAuthRequest): ApiResponse<TokenResponse> {
        if (config.googleClientIds.isEmpty()) {
            return ApiResponse.fail<TokenResponse>(
                "Вхід через Google не налаштований на сервері (GOOGLE_CLIENT_IDS)", "GOOGLE_NOT_CONFIGURED")
        }
        val payload = googleTokenInfo(request.idToken)
            ?: return ApiResponse.fail<TokenResponse>("Токен Google недійсний", "BAD_GOOGLE_TOKEN")
        val aud = payload["aud"] ?: return ApiResponse.fail<TokenResponse>(
            "Токен без aud", "BAD_GOOGLE_TOKEN")
        if (aud !in config.googleClientIds) {
            return ApiResponse.fail<TokenResponse>(
                "Токен видано для іншого застосунку", "BAD_AUDIENCE")
        }
        if (payload["email_verified"] != "true") {
            return ApiResponse.fail<TokenResponse>(
                "Email у Google не підтверджений", "EMAIL_NOT_VERIFIED")
        }
        val email = payload["email"] ?: return ApiResponse.fail<TokenResponse>(
            "Токен без email", "NO_EMAIL")
        val expSeconds = payload["exp"]?.toLongOrNull()
            ?: return ApiResponse.fail<TokenResponse>("Токен без строку дії", "BAD_GOOGLE_TOKEN")
        if (expSeconds * 1000 < System.currentTimeMillis()) {
            return ApiResponse.fail<TokenResponse>("Токен прострочений", "EXPIRED_TOKEN")
        }
        val now = System.currentTimeMillis()
        return newSuspendedTransaction {
            val existing = UserEntity.find { UserTable.email eq email }.firstOrNull()
            if (existing != null) {
                if (!existing.emailVerified) {
                    // Email підтверджений самим Google — ставимо прапорець.
                    existing.emailVerified = true
                    existing.updatedAt = now
                }
                ok(tokens(existing))
            } else {
                // Новий користувач: логін із префікса пошти; якщо зайнятий —
                // додаємо випадкове число. Пароль — випадкові 256 біт:
                // увійти за паролем такий акаунт не може, лише через Google.
                var candidate = email.substringBefore('@')
                    .filter { it.isLetterOrDigit() }.take(40).ifBlank { "google-user" }
                while (UserEntity.find { UserTable.login eq candidate }.any()) {
                    candidate = candidate.take(34) + "-" + random.nextInt(1000)
                }
                val user = UserEntity.new {
                    login = candidate
                    this.email = email
                    phone = null
                    passwordHash = BCrypt.hashpw(randomToken(), BCrypt.gensalt())
                    emailVerified = true
                    phoneVerified = false
                    twoFactorEnabled = false
                    twoFactorSecret = null
                    createdAt = now
                    updatedAt = now
                }
                ok(tokens(user))
            }
        }
    }

    // ===== Внутрішнє =====

    private fun tokens(user: UserEntity): TokenResponse {
        val access = JWT.create()
            .withIssuer(AppConfig.ISSUER)
            .withClaim("uid", user.id.value)
            .withExpiresAt(Date(System.currentTimeMillis() + config.accessTokenExpiryMillis))
            .sign(algorithm)
        val refresh = randomToken()
        RefreshTokenEntity.new {
            userId = user.id.value
            tokenHash = sha256(refresh)
            expiresAt = System.currentTimeMillis() + config.refreshTokenExpiryMillis
            createdAt = System.currentTimeMillis()
        }
        return TokenResponse(accessToken = access, refreshToken = refresh, user = user.info())
    }

    private fun issueCode(userId: Int, type: String): String {
        val code = "%06d".format(random.nextInt(1_000_000))
        VerificationCodeEntity.new {
            this.userId = userId
            codeHash = sha256(code)
            this.type = type
            expiresAt = System.currentTimeMillis() + config.codeExpiryMillis
            used = false
            createdAt = System.currentTimeMillis()
        }
        return code
    }

    private fun consumeCode(userId: Int, code: String, type: String): Boolean {
        val now = System.currentTimeMillis()
        val match = VerificationCodeEntity.find {
            ((VerificationCodeTable.userId eq userId) and
                (VerificationCodeTable.type eq type)) and
                (VerificationCodeTable.codeHash eq sha256(code))
        }.firstOrNull { !it.used && it.expiresAt > now } ?: return false
        match.used = true
        return true
    }

    private fun randomToken(): String =
        ByteArray(32).also { random.nextBytes(it) }.toHexString()

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).toHexString()

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }

    /**
     * 1.2.0: звертається до офіційного tokeninfo Google. Успіх — мапа
     * полів токена (aud, email, email_verified, exp, sub); будь-яка
     * невдача (мережа, код != 200, кривий JSON) — null, і клієнт
     * отримує чесну «Токен Google недійсний».
     */
    private fun googleTokenInfo(idToken: String): Map<String, String>? = runCatching {
        val encoded = java.net.URLEncoder.encode(idToken, "UTF-8")
        val conn = java.net.URI("https://oauth2.googleapis.com/tokeninfo?id_token=$encoded")
            .toURL()
            .openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.requestMethod = "GET"
        if (conn.responseCode != 200) {
            conn.disconnect()
            return null
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
        obj.entries.associate { (k, v) ->
            k to (v as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        }
    }.getOrNull()
}
