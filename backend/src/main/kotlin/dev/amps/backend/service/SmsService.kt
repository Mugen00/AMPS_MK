package dev.amps.backend.service

import dev.amps.backend.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory

/**
 * 1.1.0: SMS-коди через REST API Twilio — без важкого офіційного SDK.
 *
 * Без TWILIO_* змінних код виводиться в журнал: та сама політика, що й
 * з поштою — розгортання без сторонніх сервісів має працювати.
 */
class SmsService(private val config: AppConfig) {

    private val logger = LoggerFactory.getLogger(SmsService::class.java)
    private val client = OkHttpClient()

    suspend fun sendVerificationCode(to: String, code: String) =
        send(to, "AMPS: код підтвердження $code (дійсний 10 хвилин)")

    private suspend fun send(to: String, body: String) {
        val sid = config.twilioAccountSid
        val token = config.twilioAuthToken
        val from = config.twilioFromNumber
        if (sid.isNullOrBlank() || token.isNullOrBlank() || from.isNullOrBlank()) {
            logger.warn("TWILIO_* не задані — SMS не надіслано. [$to] $body")
            return
        }
        withContext(Dispatchers.IO) {
            runCatching {
                val form = FormBody.Builder()
                    .add("To", to)
                    .add("From", from)
                    .add("Body", body)
                    .build()
                val request = Request.Builder()
                    .url("https://api.twilio.com/2010-04-01/Accounts/$sid/Messages.json")
                    .header("Authorization", Credentials.basic(sid, token))
                    .post(form)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        logger.warn("Twilio відповів {}: {}", response.code, response.body?.string())
                    }
                }
            }.onFailure { logger.warn("Twilio недоступний: {}", it.message) }
        }
    }
}
