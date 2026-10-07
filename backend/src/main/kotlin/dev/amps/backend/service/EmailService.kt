package dev.amps.backend.service

import dev.amps.backend.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory

/**
 * 1.1.0: листи через REST API Resend (https://resend.com).
 *
 * Без RESEND_API_KEY або EMAIL_FROM сервіс не падає: текст листа
 * виводиться в журнал Railway, щоб розгортання без пошти не блокувало
 * реєстрацію. Це чесний компроміс особистого проєкту — коди видно
 * адміністратору, поки пошта не підключена.
 */
class EmailService(private val config: AppConfig) {

    private val logger = LoggerFactory.getLogger(EmailService::class.java)
    private val client = OkHttpClient()
    private val json = Json
    private val jsonMediaType = "application/json".toMediaType()

    /** Тіло запиту Resend: серіалізація класом, а не ручний рядок. */
    @Serializable
    private data class ResendEmail(
        val from: String,
        val to: String,
        val subject: String,
        val text: String,
    )

    suspend fun sendVerificationCode(to: String, code: String) =
        send(to, "AMPS — код підтвердження",
            "Ваш код підтвердження: $code\n\nКод дійсний 10 хвилин.")

    suspend fun sendPasswordResetCode(to: String, code: String) =
        send(to, "AMPS — скидання пароля",
            "Код для скидання пароля: $code\n\nКод дійсний 10 хвилин. " +
                "Якщо ви не просили скидання — проігноруйте цей лист.")

    private suspend fun send(to: String, subject: String, text: String) {
        val apiKey = config.resendApiKey
        val from = config.emailFrom
        if (apiKey.isNullOrBlank() || from.isNullOrBlank()) {
            logger.warn("RESEND_API_KEY або EMAIL_FROM не задані — лист не надіслано. [$to] $text")
            return
        }
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = json.encodeToString(
                    ResendEmail.serializer(),
                    ResendEmail(from = from, to = to, subject = subject, text = text),
                )
                val request = Request.Builder()
                    .url("https://api.resend.com/emails")
                    .header("Authorization", "Bearer $apiKey")
                    .post(payload.toRequestBody(jsonMediaType))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        logger.warn("Resend відповів {}: {}", response.code, response.body?.string())
                    }
                }
            }.onFailure { logger.warn("Resend недоступний: {}", it.message) }
        }
    }
}
