package dev.amps.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * IQDB — обратный поиск по картинке, который заменяет и trace.moe, и SauceNAO.
 *
 * Требует neither ключа, ни регистрации: форма на `iqdb.org` принимает файл и
 * отвечает HTML со списком совпадений. Сервис сам опрашивает Danbooru,
 * Konachan, yande.re, Gelbooru, Sankaku Channel, e-shuushuu, Zerochan и
 * Anime-Pictures, то есть собрание нескольких бо́ру-баз в одном запросе.
 *
 * Почему это замена обоим предыдущим движкам:
 *
 * - **вместо trace.moe** — ищет по иллюстрациям, скриншотам и фото, а не по
 *   кадрам видеозаписей; trace.moe физически не умеет искать то, чего нет в его
 *   индексе, и молча отвечает «не найдено»;
 * - **вместо SauceNAO** — у части источников атрибут `alt` картинки содержит
 *   теги, среди которых теги персонажа и серии вида `belgium_(hetalia)` и
 *   `hetalia:_axis_powers`. Это ровно то, что нужно, чтобы назвать персонажа,
 *   и без единого ключа.
 *
 * Честное ограничение: совпадение находится только если картинка уже лежит в
 * одной из этих баз. Человеческое фото или скриншот из видеоигры, которых в
 * индексах нет, не найдутся — и приложение обязано сказать об этом прямо,
 * а не изображать уверенную неудачу.
 */
class IqdbClient(
    private val client: OkHttpClient,
) {

    private companion object {
        const val ENDPOINT = "https://iqdb.org/"
        const val DEFAULT_FILE_NAME = "amps.png"
        const val DEFAULT_MIME = "image/png"

        /** IQDB отдаёт бо́ру-превью шириной 99 px; больше не нужно. */
        const val MAX_HTML_CHARS = 4_000_000

        /** Ниже этого процента «дополнительные» совпадения бесполезны. */
        const val MIN_SIMILARITY = 60

        /**
         * IQDB сознательно не рассчитан на программный доступ: при запросе без
         * кук он помечает ответ `botcheck=failed`. Нам нужны заголовки, под которые
         * движок отдаёт нормальный ответ.
         */
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

        /** Страница говорит, что размер картинки не должен превышать 8 МБ. */
        const val MAX_UPLOAD_BYTES = 8 * 1024 * 1024
    }

    /** Один результат поиска: источник, процент, ссылка и, если повезло, теги. */
    data class Hit(
        val isBest: Boolean,
        val source: String,
        val url: String?,
        val similarity: Int,
        val width: Int?,
        val height: Int?,
        val rating: String?,
        val tags: List<String>,
    ) {
        /** `belgium_(hetalia)` → `belgium`: имя персонажа без указания серии. */
        val characterTags: List<String>
            get() = tags.mapNotNull { tag ->
                val open = tag.indexOf("_(")
                if (open <= 0 || !tag.endsWith(")")) return@mapNotNull null
                tag.substring(0, open).replace('_', ' ').trim()
            }.filter { it.isNotEmpty() && !it.contains(':') }

        /** `hetalia:_axis_powers` → `axis powers`; `_` → пробел. */
        val seriesTags: List<String>
            get() = tags.filter { it.contains(':') && it.substringAfter(':').isNotBlank() }
                .map { it.substringAfter(':').replace('_', ' ').trim() }
                .filter { it.isNotEmpty() }
    }

    data class SearchResult(
        val hits: List<Hit>,
        val scannedImages: Int?,
        /** IQDB спрятал точный результат в блоке «Other results». */
        val exactMatchFound: Boolean,
    ) {
        val best: Hit? get() = hits.firstOrNull()
    }

    /**
     * Ищет совпадения. Если первый проход ничего не дал, повторяет с
     * `forcegray` — сервис так ищет по форме, игнорируя цвет, и это заметно
     * поднимает число находок на перекрашенных и зашумлённых картинках.
     */
    suspend fun search(bytes: ByteArray, fileName: String, mimeType: String): SearchResult {
        if (bytes.size > MAX_UPLOAD_BYTES) {
            throw IOException("Картинка больше 8 МБ — IQDB такие не принимает")
        }
        val first = runSearch(bytes, fileName, mimeType, ignoreColors = false)
        if (first.hits.isNotEmpty()) return first

        val grey = runSearch(bytes, fileName, mimeType, ignoreColors = true)
        // Серый проход заменяет первый только если он что-то нашёл: вернуть
        // после непустого первого пустой результат значило бы потерять удачную
        // находку ради неудачной.
        return if (grey.hits.isNotEmpty()) grey else first
    }

    private suspend fun runSearch(
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        ignoreColors: Boolean,
    ): SearchResult = withContext(Dispatchers.IO) {
        val image = bytes.toRequestBody(mimeType.toMediaTypeOrNull() ?: "$DEFAULT_MIME".toMediaTypeOrNull())
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName.ifBlank { DEFAULT_FILE_NAME }, image)
            .apply { if (ignoreColors) addFormDataPart("forcegray", "on") }
            .build()

        val request = Request.Builder()
            .url(ENDPOINT)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "https://iqdb.org/")
            .post(body)
            .build()

        val html = client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("IQDB ответил HTTP ${response.code}")
            }
            text.take(MAX_HTML_CHARS)
        }
        parse(html)
    }

    /**
     * Разбирает страницу результатов.
     *
     * Каждое совпадение — блок `<div><table>…</table></div>` с предыдущей
     * страницы, из которого нужны пять независимых кусочков: вид совпадения
     * («Best match» или «Additional match»), источник из подписи рядом с
     * иконкой, размеры и рейтинг, процент и — главное — теги из `alt` картинки.
     *
     * Порядок важен: сортировка по проценту убирает совпадения ниже
     * [MIN_SIMILARITY], которые для пользователя лишь шум.
     */
    internal fun parse(html: String): SearchResult {
        val blocks = BLOCK.findAll(html).map { it.groupValues[1] }.toList()
        val scanned = SCANNED.find(html)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
        val saysNoMatch = NO_RELEVANT.containsMatchIn(html)

        val hits = blocks.mapNotNull { block ->
            // Блок с собственной картинкой пользователя совпадением не является.
            if (block.contains("Your image")) return@mapNotNull null

            val similarity = block.intOrNull(SIMILARITY) ?: return@mapNotNull null
            if (similarity < MIN_SIMILARITY) return@mapNotNull null

            val source = block.findTrimmed(SERVICE_ICON).ifBlank {
                block.findTrimmed(SOURCE_FALLBACK).ifBlank { return@mapNotNull null }
            }
            val dims = DIMENSIONS.find(block)
            Hit(
                isBest = block.contains(">Best match<"),
                source = source,
                url = block.findTrimmed(LINK)?.let { if (it.startsWith("//")) "https:$it" else it },
                similarity = similarity,
                width = dims?.groupValues?.get(1)?.toIntOrNull(),
                height = dims?.groupValues?.get(2)?.toIntOrNull(),
                rating = dims?.groupValues?.get(3),
                tags = block.findTrimmed(TAGS)
                    .split(' ', '\t', '\n')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
            )
        }

        // Точное совпадение сервис выделяет заголовком «Best match». Признак берём
        // из уже отфильтрованных совпадений, а не из сырых блоков: блок с этим
        // заголовком может не пройти порог, и тогда «точное найдено» означало
        // бы, что найти нечего.
        val exact = hits.any { it.isBest }
        return SearchResult(
            hits = hits.sortedByDescending { it.similarity },
            scannedImages = scanned,
            exactMatchFound = exact && !saysNoMatch,
        )
    }

    private fun String.findTrimmed(pattern: Regex): String =
        pattern.find(this)?.groupValues?.get(1)?.trim().orEmpty()

    private fun String.intOrNull(pattern: Regex): Int? =
        pattern.find(this)?.groupValues?.get(1)?.toIntOrNull()

    // --- шаблоны страницы -----------------------------------------------------

    private val BLOCK = Regex("<div><table>(.*?)</table></div>", RegexOption.DOT_MATCHES_ALL)
    private val SIMILARITY = Regex("<td>(\\d+)% similarity</td>")
    private val SERVICE_ICON = Regex("service-icon\"?>([^<]+)</td>")
    private val SOURCE_FALLBACK = Regex("<td>([A-Za-z][A-Za-z0-9 .]{2,24})</td>")
    /**
     * Размеры и рейтинг: `682×1030 [Safe]`. Знак разделения на странице —
     * типографский `×` (U+00D7), а не буква `x`, хотя в разных блоках
     * встречается и обычный; принимаем оба.
     */
    private val DIMENSIONS = Regex("<td>(\\d+)[x×](\\d+) \\[([A-Za-z]+)\\]</td>")
    private val LINK = Regex("<a href=\"([^\"]+)\"")
    private val TAGS = Regex("Tags: ([^\"]+)\"")
    private val SCANNED = Regex("Searched ([\\d,]+) images")
    private val NO_RELEVANT = Regex("No relevant matches")
}
