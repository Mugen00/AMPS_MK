package dev.amps.app.data.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 1.1.3: AI-аналіз фото через Google Gemini API (безкоштовний тариф).
 *
 * Реалізація — звичайний REST-запит на OkHttp, без офіційного SDK:
 * той самий API, нуль зайвих залежностей, офлайн-збірка не тягне
 * нових транзитивних пакетів. Запит — картинка + промпт, відповідь —
 * Markdown-текст, який екран рендерить через Markwon.
 *
 * Ключ НЕ вшивається в APK: репозиторій публічний, і ключ із нього
 * знімають боти за лічені хвилини, спаливши безкоштовний ліміт.
 * Ключ задається користувачем у Налаштуваннях (DataStore) — як колись
 * було з ключем SauceNAO, з тих самих причин.
 */
object GeminiVision {

    /** Адреса Generative Language API; модель перевірена живим викликом. */
    private const val API_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s"

    /** 2026: gemini-2.5-flash більше не видається новим користувачам — API підказав 3.8. */
    const val DEFAULT_MODEL = "gemini-3.8-flash"

    private val json = Json { ignoreUnknownKeys = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    /**
     * Інструкція моделі: структурована відповідь у Markdown. У посиланнях
     * дозволені лише головні сторінки реальних баз і пошукові запити —
     * глибокі шляхи модель схильна вигадувати, тому заборонені.
     */
    val ANALYZE_PROMPT: String = """
        Проаналізуй зображення. Відповідай українською, СТРОГО у Markdown, без вступних слів, за шаблоном:

        ### [Ім'я об'єкта або персонажа]
        **Коротке визначення:** хто чи що це — одним реченням.

        **Контекст і деталі:** звідки це (аніме/манґа/гра/фільм), коротко про сюжет і роль персонажа або про сам об'єкт; якщо впевненості немає — перелічи 2-3 найімовірніші версії і поясни чому.

        **Де шукати подробиці:**
        * Лише головні сторінки реальних сайтів (без вигаданих шляхів): [Shikimori](https://shikimori.one), [AniList](https://anilist.co), [MyAnimeList](https://myanimelist.net), [Fandom](https://www.fandom.com), а також пошукове посилання виду [Google](https://www.google.com/search?q=...) з коротким запитом.

        Не вигадуй точних глибоких посилань — тільки домени і пошукові посилання. Якщо на зображенні не персонаж, а об'єкт чи сцена — опиши саме його. Якщо впізнати неможливо, чесно напиши це і опиши зовнішність для ручного пошуку.
    """.trimIndent()

    /**
     * Аналіз: JPEG-байти + ключ → Markdown-текст або виняток з людською
     * причиною. Ключ віддається при кожному виклику — застосунок читає
     * його з налаштувань.
     */
    suspend fun analyze(
        imageBytes: ByteArray,
        apiKey: String,
        model: String = DEFAULT_MODEL,
    ): String = withContext(Dispatchers.IO) {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) {
            throw IllegalStateException("Ключ Gemini не заданий — додайте його в Налаштуваннях")
        }
        val compact = toJpegForApi(imageBytes)
            ?: throw IllegalStateException("Не вдалося обробити зображення для AI")
        val base64 = Base64.encodeToString(compact, Base64.NO_WRAP)
        val payload = buildJsonObject {
            put(
                "contents",
                JsonArray(listOf(
                    buildJsonObject {
                        put(
                            "parts",
                            JsonArray(listOf(
                                buildJsonObject {
                                    put(
                                        "inline_data",
                                        buildJsonObject {
                                            put("mime_type", "image/jpeg")
                                            put("data", base64)
                                        },
                                    )
                                },
                                buildJsonObject { put("text", ANALYZE_PROMPT) },
                            )),
                        )
                    },
                )),
            )
        }.toString()

        val request = Request.Builder()
            .url(API_URL.format(model, URLEncoder.encode(trimmed, "UTF-8")))
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = parseErrorMessage(body)
                throw IllegalStateException(
                    if (message != null) "Gemini: $message" else "Gemini: HTTP ${response.code}",
                )
            }
            extractText(body) ?: throw IllegalStateException("Gemini повернув порожню відповідь")
        }
    }

    /** Стискає будь-який формат до JPEG ≤1024 px — швидкість і ліміт тарифу. */
    fun toJpegForApi(bytes: ByteArray, maxSide: Int = 1024, quality: Int = 82): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decode = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decode) ?: return null
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
        bitmap.recycle()
        return output.toByteArray()
    }

    /** Злитий текст candidates[*].content.parts[*].text; немає — null. */
    private fun extractText(payload: String): String? = runCatching {
        val root = json.parseToJsonElement(payload).jsonObject
        val candidates = root["candidates"] as? JsonArray ?: return null
        val builder = StringBuilder()
        for (candidate in candidates) {
            val content = (candidate as? JsonObject)?.get("content")?.jsonObject ?: continue
            val parts = content["parts"] as? JsonArray ?: continue
            for (part in parts) {
                val text = (part as? JsonObject)?.get("text")?.jsonPrimitive?.content
                if (text != null) builder.append(text)
            }
        }
        builder.toString().takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Людиночитна причина від API: error.message з конверта помилки. */
    private fun parseErrorMessage(body: String): String? = runCatching {
        val error = json.parseToJsonElement(body).jsonObject["error"] as? JsonObject
        error?.get("message")?.jsonPrimitive?.content
    }.getOrNull()
}
