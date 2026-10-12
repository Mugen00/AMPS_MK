package dev.amps.backend.service

import dev.amps.backend.model.ApiResponse
import dev.amps.backend.model.WatchUrlResponse
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * 1.4.0: резолвер «Дивитись» — перетворює назву тайтла в адресу його
 * сторінки на old.yummyani.me. Сервер лише знаходить ПОСИЛАННЯ на
 * сторінку тайтла через їхній відкритий пошуковий API: плеєр і серії
 * залишаються на їхньому сайті, застосунок відкриває звичайну сторінку
 * у вбудованому WebView. Нічого з їхнього контента не витягується.
 *
 * Кеш у пам'яті (назва → url): пошук у них повільний, а назва тайтла
 * стабільна — повторні відкриття тієї ж сторінки не б'ють по їхньому
 * серверу. Записи живуть 6 годин, сховище обмежене 200 ключами.
 */
class AnimeWatchService {

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private data class CacheEntry(val url: String, val foundTitle: String?, val at: Long)
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val cacheOrder = ConcurrentLinkedQueue<String>()

    private data class ApiItem(
        val animeUrl: String? = null,
        val title: String? = null,
    )

    private data class ApiSearch(
        val response: List<ApiItem> = emptyList()
    )

    /**
     * Знайти сторінку тайтла:ApiResponse із посиланням на
     * /catalog/item/<slug> або помилка з людською причиною.
     */
    fun resolve(query: String): ApiResponse<WatchUrlResponse> {
        val q = query.trim()
        if (q.isEmpty()) {
            return ApiResponse.fail("Порожня назва тайтла", "EMPTY_TITLE")
        }
        val key = q.lowercase().trim()
        cache[key]?.let { cached ->
            if (System.currentTimeMillis() - cached.at < TTL_MS) {
                return ApiResponse.ok(WatchUrlResponse(cached.url, cached.foundTitle))
            }
            cache.remove(key)
        }

        // Кілька варіантів запиту: повна назва, частина до «:»/«(», перші слова.
        val queries = linkedSetOf(q)
        q.split(Regex("[:\\-–—(]")).firstOrNull()?.trim()?.takeIf { it.length >= 3 }
            ?.let { queries.add(it) }
        q.split(" ").take(4).joinToString(" ").takeIf { it.length >= 3 && it != q }
            ?.let { queries.add(it) }

        for (candidate in queries) {
            val hit = runCatching { searchOne(candidate) }.getOrNull()
            if (hit != null) {
                val url = "$BASE/catalog/item/${hit.first}"
                val entry = CacheEntry(url, hit.second, System.currentTimeMillis())
                remember(key, entry)
                return ApiResponse.ok(WatchUrlResponse(url = url, foundTitle = entry.foundTitle))
            }
        }
        return ApiResponse.fail(
            "Тайтл не знайдено на yummyani.me — спробуйте пошук на сайті",
            "NOT_FOUND",
        )
    }

    /** Один запит до їхнього пошукового API; повертає (slug, назва). */
    private fun searchOne(query: String): Pair<String, String>? {
        val url = "$BASE/api/search?q=${URLEncoder.encode(query, "UTF-8")}&limit=5&offset=0"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            // Обов'язковий header їхнього API: без нього віддає HTML-заглушку.
            .header("X-Requested-With", "XMLHttpRequest")
            .header("User-Agent", "AMPS-App/1.4 (https://github.com/Mugen00/AMPS_MK)")
            .build()
        http.newCall(request).execute().use { resp ->
            if (resp.code != 200) return null
            val body = resp.body?.string() ?: return null
            val parsed = runCatching { json.decodeFromString<ApiSearch>(body) }.getOrNull() ?: return null
            val first = parsed.response.firstOrNull { !it.animeUrl.isNullOrBlank() } ?: return null
            return first.animeUrl!! to (first.title ?: "")
        }
    }

    private fun remember(key: String, entry: CacheEntry) {
        if (cache.size >= MAX_CACHE) {
            val oldest = cacheOrder.poll()
            if (oldest != null) cache.remove(oldest)
        }
        cache[key] = entry
        cacheOrder.add(key)
    }

    companion object {
        private const val BASE = "https://old.yummyani.me"
        private const val TTL_MS = 6L * 60 * 60 * 1000
        private const val MAX_CACHE = 200
    }
}
