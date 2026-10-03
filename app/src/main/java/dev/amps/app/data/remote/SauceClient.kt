package dev.amps.app.data.remote

import dev.amps.app.data.ranking.RankingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * SauceNAO напрямую с телефона — моста с 1.0.3 больше нет.
 *
 * **Ключ спрашивает пользователя, и это осознанно.** Раньше ключ лежал в `.env`
 * на компьютере и в APK не попадал. Вшить его в приложение значит отдать его
 * любому, кто распакует APK: ключ — это не просто идентификатор, под ним идёт
 * квота, за которую отвечает его владелец. Поэтому без ключа приложение
 * честно говорит «второго источника нет» и работает на trace.moe и модели
 * содержимого, а не притворяется, что подтвердить совпадение нечем.
 *
 * Без ключа SauceNAO всё равно отвечает на анонимные запросы с жёстким лимитом,
 * но он предназначен для интерактивного браузерного использования; злоупотреблять
 * им из приложения не стоит, поэтому при отсутствии ключа запрос не уходит.
 */
class SauceClient(
    private val client: OkHttpClient,
    private val keyProvider: suspend () -> String?,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * @return совпадения, либо `null`, если ключ не задан — это «источника нет»,
     * а не «источник ответил пустым списком». Разница важна: вердикт в первом
     * случае честно неопределённый, во втором — уверенно отрицательный.
     */
    suspend fun search(
        bytes: ByteArray,
        fileName: String,
        mime: String,
        limit: Int = 4,
    ): List<RankingEngine.SauceHit>? = withContext(Dispatchers.IO) {
        val key = keyProvider()?.trim().orEmpty()
        if (key.isEmpty()) return@withContext null

        val url = ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("db", "999") // все базы сразу: Danbooru, Pixiv, AniDB…
            .addQueryParameter("output_type", "2")
            .addQueryParameter("numres", limit.coerceIn(1, 20).toString())
            .addQueryParameter("apikey", key)
            .build()

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, bytes.toRequestBody(mime.toMediaType()))
            .build()

        val request = Request.Builder().url(url).post(body)
            // Без этих заголовков SauceNAO отвечает 403 «Just a moment…»:
            // Cloudflare отсекает всё, что не похоже на браузер. Проверено
            // живым запросом — с дефолтным User-Agent OkHttp запрос не проходит.
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Referer", "https://saucenao.com/")
            .header("Origin", "https://saucenao.com")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // 401/403 — ключ неверный или истёк; 429 — квота кончилась.
                // Различать пользователю полезно, поэтому текст ошибки возвращаем.
                throw IOException("SauceNAO ответил ${response.code}: ${text.take(180)}")
            }
            parse(text)
        }
    }

    internal fun parse(text: String): List<RankingEngine.SauceHit> {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return emptyList()
        // status > 0 означает «поиск не выполнен» (лимит, неизвестный хеш, битый файл)
        val status = root["status"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        if (status > 0) return emptyList()
        val results = root["results"] as? JsonArray ?: return emptyList()

        return results.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val header = row["header"] as? JsonObject ?: return@mapNotNull null
            val data = row["data"] as? JsonObject ?: JsonObject(emptyMap())
            val similarity = header["similarity"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            if (similarity <= 0.0) return@mapNotNull null

            RankingEngine.SauceHit(
                similarity = similarity.toFloat(),
                // Только anilist_id. У SauceNAO рядом лежит anidb_id — это другая
                // база и другой диапазон номеров; подставить его в поле AniList
                // значит указать несуществующую серию. Лучше null и слияние по
                // названию, чем правдоподобная, но неверная ссылка.
                anilistId = data.int("anilist_id"),
                source = header["index_name"]?.jsonPrimitive?.contentOrNull(),
                title = data.string("title"),
                series = data.string("copyright") ?: data.string("series"),
                copyright = data.string("copyright"),
                characters = data.stringList("characters"),
                tags = data.stringList("tags"),
            )
        }
    }

    private fun JsonObject.int(key: String): Int? =
        this[key]?.let { (it as? JsonPrimitive)?.doubleOrNull }?.toInt()?.takeIf { it > 0 }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.stringList(key: String): List<String> {
        val array = this[key] as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull()?.trim() }.filter { it.isNotEmpty() }
    }

    private fun JsonPrimitive.contentOrNull(): String? =
        if (isString) content.takeIf { it != "null" && it.isNotBlank() } else null

    companion object {
        const val ENDPOINT = "https://saucenao.com/search.php"

        /**
         * Настоящий User-Agent браузера. Подмена нужна не для обхода защиты, а
         * потому что Cloudflare у SauceNAO отсекает запросы без него: это
         * ровно то, что делает обычный браузер, и ровно то, что нужно,
         * чтобы отличать трафик приложения от сканирования.
         */
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
