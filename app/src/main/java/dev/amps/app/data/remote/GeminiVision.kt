package dev.amps.app.data.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import java.io.IOException
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

    /**
     * 1.2.0 (патч 23): ланцюжок моделей за живою перевіркою ключа
     * (2026: 3.8/3.7/3.6/3.5-flash відповідають 200, flash-latest — 503
     * «high demand», 2.5-flash — 404 для нових ключів). Порядок — від
     * найстабільнішої; 404/503 ведуть до наступної моделі.
     */
    private val FALLBACK_MODELS = listOf(
        "gemini-3.8-flash",
        "gemini-3.7-flash",
        "gemini-3.6-flash",
        "gemini-3.5-flash",
        "gemini-flash-latest",
        "gemini-2.0-flash",
    )

    /** 1.1.3: модель за замовчуванням — перша у ланцюжку фолбеку. */
    const val DEFAULT_MODEL = "gemini-flash-latest"

    private val json = Json { ignoreUnknownKeys = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
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
     *
     * 1.2.0 (патч 22) — стійкість:
     *  * модель відкликана (404 / "not found") → наступна у ланцюжку;
     *  * 429/503 (ліміт тарифу) → один ретрай через паузу;
     *  * safety-фільтри (часто хибно спрацьовують на аніме) — вимикаються
     *    BLOCK_NONE; якщо API відмовив — відкат на BLOCK_ONLY_HIGH,
     *    потім на запит без safetySettings взагалі;
     *  * 200 без тексту — читаємо blockReason/finishReason і кажемо чесно
     *    чому замість «порожня відповідь».
     */
    suspend fun analyze(
        imageBytes: ByteArray,
        apiKey: String,
        model: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) {
            throw IllegalStateException("Ключ Gemini не заданий — додайте його в Налаштуваннях")
        }
        val compact = toJpegForApi(imageBytes)
            ?: throw IllegalStateException("Не вдалося обробити зображення для AI")
        val base64 = Base64.encodeToString(compact, Base64.NO_WRAP)
        val contents = buildJsonObject {
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
        }

        val models = if (model.isNullOrBlank()) FALLBACK_MODELS else listOf(model)
        var lastError: Exception? = null

        for (candidate in models) {
            // Три варіанти safetySettings: BLOCK_NONE → BLOCK_ONLY_HIGH → без них.
            val safetyVariants: List<Pair<List<String>, String?>> = listOf(
                SAFETY_CATEGORIES to "BLOCK_NONE",
                SAFETY_CATEGORIES to "BLOCK_ONLY_HIGH",
                emptyList<String>() to null,
            )
            for ((categories, threshold) in safetyVariants) {
                val payload = contents.addSafetySettings(categories, threshold).toString()
                var (code, body) = postOnce(candidate, payload, trimmed)
                // Ліміт тарифу (429) або перевантаження (503): пауза й один ретрай.
                if (code == 429 || code == 503) {
                    delay(RETRY_PAUSE_MS)
                    val retry = postOnce(candidate, payload, trimmed)
                    code = retry.first
                    body = retry.second
                }

                if (code == 404 || code == 503 || isModelNotFound(code, body)) {
                    // Модель не існує (404) або перевантажена (503 «high
                    // demand») — наступна у ланцюжку, часто вона вільна.
                    lastError = IllegalStateException("Модель $candidate недоступна (${describe(code, body)})")
                    break // наступна модель у ланцюжку
                }
                if (code == 400 && body.contains("BLOCK_NONE", ignoreCase = true)) {
                    continue // цей поріг не дозволений — нижчий
                }
                if (code != 200) {
                    throw IllegalStateException("Gemini: ${describe(code, body)}")
                }
                val text = extractText(body)
                if (text != null) return@withContext text
                // 200, але тексту немає — фільтри або обрізання.
                throw IllegalStateException(emptyResponseReason(body))
            }
        }
        throw lastError ?: IllegalStateException("Gemini: жодна модель не відповіла")
    }

    /**
     * Один HTTP-дзвінок. Мережеві збої (DNS, таймаут, обрив) — один ретрай:
     * мобільна мережа часто дає короткий обрив, який другий спроба долає.
     * SocketTimeout повідомляється чесно як «повільна мережа».
     */
    private fun postOnce(model: String, payload: String, apiKey: String): Pair<Int, String> {
        val request = Request.Builder()
            .url(API_URL.format(model, URLEncoder.encode(apiKey, "UTF-8")))
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        repeat(2) { attempt ->
            try {
                http.newCall(request).execute().use { response ->
                    return response.code to response.body?.string().orEmpty()
                }
            } catch (error: java.net.SocketTimeoutException) {
                if (attempt == 1) {
                    throw IllegalStateException(
                        "Gemini не відповів вчасно — мережа повільна або сервер навантажений. Спробуйте ще раз",
                        error,
                    )
                }
            } catch (error: IOException) {
                if (attempt == 1) {
                    throw IllegalStateException(
                        "Немає зв'язку з Gemini: ${error.message ?: "мережева помилка"}", error,
                    )
                }
            }
        }
        // Сюди не дійдемо: repeat(2) або повертає відповідь, або кидає.
        return 599 to ""
    }

    /** 404 або 400 з «model … not found» — модель не існує/більше не видається. */
    private fun isModelNotFound(code: Int, body: String): Boolean =
        code == 404 ||
            (code == 400 &&
                body.contains("model", ignoreCase = true) &&
                body.contains("not found", ignoreCase = true))

    /** Людська причина від API: error.message, інакше — код. */
    private fun describe(code: Int, body: String): String =
        parseErrorMessage(body) ?: "HTTP $code"

    /**
     * 200 без тексту — читаємо promptFeedback.blockReason або
     * candidates[0].finishReason і пояснюємо це по-людськи.
     */
    private fun emptyResponseReason(body: String): String {
        val reason = runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val block = (root["promptFeedback"] as? JsonObject)
                ?.get("blockReason")?.jsonPrimitive?.content
            block ?: ((root["candidates"] as? JsonArray)
                ?.firstOrNull() as? JsonObject)
                ?.get("finishReason")?.jsonPrimitive?.content
        }.getOrNull()
        return when (reason) {
            "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST" ->
                "Gemini відмовився аналізувати це фото через фільтр безпеки ($reason) — спробуйте інший кадр"
            "RECITATION" ->
                "Gemini припинив відповідь через підозру на цитування (RECITATION) — спробуйте ще раз"
            "MAX_TOKENS" ->
                "Відповідь обрізалась через ліміт довжини (MAX_TOKENS) — спробуйте ще раз"
            null -> "Gemini повернув порожню відповідь"
            else -> "Gemini не дав текст відповіді (reason: $reason)"
        }
    }

    /** Категорії safety для вимкнення блокувань аналізу. */
    private val SAFETY_CATEGORIES = listOf(
        "HARM_CATEGORY_HARASSMENT",
        "HARM_CATEGORY_HATE_SPEECH",
        "HARM_CATEGORY_SEXUALLY_EXPLICIT",
        "HARM_CATEGORY_DANGEROUS_CONTENT",
    )

    private const val RETRY_PAUSE_MS = 1500L

    /** Додає safetySettings до payload: [категорія to поріг]; поріг null — без них. */
    private fun JsonObject.addSafetySettings(
        categories: List<String>,
        threshold: String?,
    ): JsonObject {
        if (threshold == null || categories.isEmpty()) return this
        val source = this
        return buildJsonObject {
            for ((key, value) in source) put(key, value)
            put(
                "safetySettings",
                JsonArray(categories.map { category ->
                    buildJsonObject {
                        put("category", category)
                        put("threshold", threshold)
                    }
                }),
            )
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
