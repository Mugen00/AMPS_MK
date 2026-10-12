package dev.amps.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * 1.4.0: клієнтський резолвер «Дивитись». Питає відкритий пошуковий API
 * yummyani.me (тільки назва тайтла і slug сторінки — ніякого контента),
 * повертає адресу сторінки тайтла, яку застосунок відкриє у WebView.
 *
 * Запит іде напряму з телефона (резидентна IP + браузерні headers):
 * Cloudflare відсікає запити з датацентрів — серверний резолвер часто
 * не проходить. Плеєр і серії лишаються на їхньому сайті.
 */
class YummyWatchResolver(private val http: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    private data class ApiItem(val animeUrl: String? = null)
    private data class ApiSearch(val response: List<ApiItem> = emptyList())

    suspend fun resolve(title: String): String? = withContext(Dispatchers.IO) {
        val q = title.trim()
        if (q.isEmpty()) return@withContext null
        // Кілька варіантів: повна назва, частина до «:»/«(», перші слова.
        val queries = linkedSetOf(q)
        q.split(Regex("[:\\-–—(]")).firstOrNull()?.trim()?.takeIf { it.length >= 3 }
            ?.let { queries.add(it) }
        q.split(" ").take(4).joinToString(" ").takeIf { it.length >= 3 && it != q }
            ?.let { queries.add(it) }
        for (candidate in queries) {
            val url = searchOne(candidate)
            if (url != null) return@withContext url
        }
        null
    }

    private suspend fun searchOne(query: String): String? = withContext(Dispatchers.IO) {
        val url = "https://old.yummyani.me/api/search?q=" +
            URLEncoder.encode(query, "UTF-8") + "&limit=5&offset=0"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "https://old.yummyani.me/")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/131.0.0.0 Mobile Safari/537.36",
            )
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = resp.body?.string() ?: return@use null
            val parsed = runCatching { json.decodeFromString<ApiSearch>(body) }.getOrNull() ?: return@use null
            val slug = parsed.response.firstOrNull { !it.animeUrl.isNullOrBlank() }?.animeUrl ?: return@use null
            "https://old.yummyani.me/catalog/item/$slug"
        }
    }
}
