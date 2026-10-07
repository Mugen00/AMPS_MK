package dev.amps.backend.config

import java.net.URI

/**
 * 1.1.0: конфігурація бекенду зі змінних оточення Railway.
 *
 * application.conf у resources лишається документацією — код читає
 * середовище напряму, без HOCON-парсера: зайва залежність ні до чого.
 *
 * Обов'язкові змінні: DATABASE_URL (автоматично від Railway PostgreSQL)
 * і JWT_SECRET. Пошта та SMS — необов'язкові: без ключів коди
 * підтвердження виводяться в журнал Railway, щоб розгортання без
 * сторонніх сервісів не блокувало реєстрацію.
 */
class AppConfig(
    val port: Int = envInt("PORT", 8080),
    val databaseUrl: String = requireEnv("DATABASE_URL"),
    val jwtSecret: String = requireEnv("JWT_SECRET"),
    val resendApiKey: String? = env("RESEND_API_KEY"),
    val emailFrom: String? = env("EMAIL_FROM"),
    val twilioAccountSid: String? = env("TWILIO_ACCOUNT_SID"),
    val twilioAuthToken: String? = env("TWILIO_AUTH_TOKEN"),
    val twilioFromNumber: String? = env("TWILIO_FROM_NUMBER"),
    val frontendBaseUrl: String = env("FRONTEND_BASE_URL") ?: "",
) {

    /** Термін життя access-токена: 30 хвилин. */
    val accessTokenExpiryMillis: Long = 30L * 60 * 1000

    /** Термін життя refresh-токена: 30 днів. */
    val refreshTokenExpiryMillis: Long = 30L * 24 * 60 * 60 * 1000

    /** Термін життя кодів підтвердження: 10 хвилин. */
    val codeExpiryMillis: Long = 10L * 60 * 1000

    /** JDBC-адреса, зібрана з Railway-рядка postgresql://user:pass@host:port/db */
    val jdbcUrl: String
    val dbUser: String
    val dbPassword: String

    init {
        val uri = URI(databaseUrl)
        val port = if (uri.port > 0) uri.port else 5432
        jdbcUrl = "jdbc:postgresql://${uri.host}:$port${uri.path}"
        val userInfo = uri.userInfo ?: error("DATABASE_URL не містить user:password")
        dbUser = userInfo.substringBefore(':')
        dbPassword = userInfo.substringAfter(':')
    }

    companion object {
        const val ISSUER = "amps-backend"

        private fun env(name: String): String? =
            System.getenv(name)?.takeIf { it.isNotBlank() }

        private fun envInt(name: String, default: Int): Int =
            env(name)?.toIntOrNull() ?: default

        private fun requireEnv(name: String): String =
            env(name) ?: error("Змінна оточення $name обов'язкова. Додайте її у налаштуваннях сервісу Railway.")
    }
}
