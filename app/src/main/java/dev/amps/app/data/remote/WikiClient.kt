package dev.amps.app.data.remote

import dev.amps.app.data.model.RosterEntry
import dev.amps.app.data.model.WikiReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Вики серии и её персонажи — напрямую с телефона, без моста.
 *
 * **Порядок поиска вики такой же, как был на мосте, и он не случаен.**
 * Сначала P8080 в Wikidata: это прямая ссылка «у этого тайтла есть вики на
 * фандоме», заданная редакторами вручную. Если ссылки нет, название
 * превращается в slug и вики проверяется по адресу. Проверка — один запрос
 * `action=query&meta=siteinfo`; если домен не отвечает, значит вики нет.
 *
 * **Персонажи приходят из категорий вики, а не из Wikidata.** Это не выбор
 * вкуса, а факт: у аниме-записей в Wikidata утверждения «персонаж» нет вовсе
 * (проверено на нескольких тайтлах — всегда ноль), поэтому оттуда им взяться
 * не может. Свойство P175 у аниме встречается крайне редко и в тех единичных
 * случаях содержит одного-двух авторов, а не состав.
 */
class WikiClient(private val client: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    data class WikiInfo(
        val wiki: WikiReference?,
        val characters: List<RosterEntry>,
        val places: List<RosterEntry>,
        val note: String? = null,
    )

    /** Полная справка по серии: вики, персонажи и места. Любая часть может быть пустой. */
    suspend fun lookup(title: String, aliases: List<String> = emptyList()): WikiInfo =
        withContext(Dispatchers.IO) {
            val names = (listOf(title) + aliases).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val slug = resolveSlug(names) ?: return@withContext WikiInfo(null, emptyList(), emptyList())
            val page = fetchPage(slug, title)
            val roster = readRoster(slug, title)
            WikiInfo(
                wiki = WikiReference(
                    slug = slug,
                    url = page?.url ?: "https://$slug.fandom.com/wiki/${encodeTitle(title)}",
                    intro = page?.intro,
                    images = page?.images.orEmpty(),
                ),
                characters = roster.characters,
                places = roster.places,
            )
        }

    // --- поиск вики ---------------------------------------------------------

    private suspend fun resolveSlug(names: List<String>): String? {
        for (name in names) {
            wikidataSlug(name)?.let { return it }
        }
        for (name in names) {
            val slug = slugify(name) ?: continue
            if (wikiExists(slug)) return slug
        }
        return null
    }

    /** P8080 — «вики по теме», заданная вручную редакторами Wikidata. */
    private fun wikidataSlug(title: String): String? {
        val query = """
            SELECT ?wiki WHERE {
              ?item rdfs:label "${escape(title)}"@en .
              ?item wdt:P8080 ?wiki .
            } LIMIT 3
        """.trimIndent()
        val url = "https://query.wikidata.org/sparql".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("format", "json")
            .build()
        val text = fetch(url.toString(), accept = "application/sparql-results+json") ?: return null
        val bindings = (runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject)
            ?.get("results")?.let { it as? JsonObject }?.get("bindings") as? JsonArray ?: return null
        return bindings.firstNotNullOfOrNull { binding ->
            (binding as? JsonObject)?.get("wiki")?.let { it as? JsonObject }
                ?.get("value")?.jsonPrimitive?.contentOrNull()
                ?.let(::slugFromUrl)
        }
    }

    /** `https://steins-gate.fandom.com/wiki/Steins;Gate` → `steins-gate` */
    private fun slugFromUrl(url: String): String? =
        runCatching {
            val host = url.toHttpUrl().host
            if (!host.endsWith(".fandom.com")) null else host.removeSuffix(".fandom.com")
        }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun slugify(title: String): String? {
        val base = title.lowercase()
            .replace("’", "'")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        if (base.isBlank()) return null
        return base
    }

    private fun wikiExists(slug: String): Boolean =
        runCatching {
            val url = "https://$slug.fandom.com/api.php".toHttpUrl().newBuilder()
                .addQueryParameter("action", "query")
                .addQueryParameter("meta", "siteinfo")
                .addQueryParameter("format", "json")
                .build()
            fetch(url.toString(), accept = "application/json") != null
        }.getOrDefault(false)

    // --- содержимое вики ----------------------------------------------------

    private data class Page(val url: String, val intro: String?, val images: List<String>)

    /**
     * Краткая сводка статьи.
     *
     * **`prop=extracts` есть не на каждой вики** — расширение TextExtracts
     * установлено не везде, и запрос на вики без него возвращает не текст, а
     * предупреждение `Unrecognized value for parameter "prop"`. Поэтому
     * основной путь — `extracts`, а запасной — `action=parse` с wikitext
     * первой секции, который есть в самом MediaWiki и потому доступен везде.
     *
     * Даже когда `extracts` работает, текст бывает пустым: статья может быть
     * целиком на шаблонах и инфобоксах. Пустой результат — не ошибка, а «вики
     * молчит», и показывать его пользователю незачем.
     */
    private fun fetchPage(slug: String, title: String): Page? = runCatching {
        val base = "https://$slug.fandom.com/api.php"
        val fullUrl = "https://$slug.fandom.com/wiki/${encodeTitle(title)}"
        val thumb = extractThumbnail(base, title)
        val intro = extractIntro(base, title) ?: wikitextLead(base, title)
        if (intro.isNullOrBlank() && thumb.isNullOrBlank()) {
            Page(fullUrl, null, emptyList())
        } else {
            Page(fullUrl, intro?.takeIf { it.isNotBlank() }, listOfNotNull(thumb))
        }
    }.getOrNull()

    private fun extractIntro(base: String, title: String): String? {
        val text = fetch(
            base.toHttpUrl().newBuilder()
                .addQueryParameter("action", "query")
                .addQueryParameter("prop", "extracts")
                .addQueryParameter("titles", title)
                .addQueryParameter("exintro", "1")
                .addQueryParameter("explaintext", "1")
                .addQueryParameter("redirects", "1")
                .addQueryParameter("format", "json")
                .build().toString(),
            accept = "application/json",
        ) ?: return null
        val pages = (runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject)
            ?.get("query")?.let { it as? JsonObject }?.get("pages") as? JsonObject ?: return null
        // На вики без TextExtracts сюда попадает объект с warnings, а не текст.
        val page = pages.values.firstOrNull() as? JsonObject ?: return null
        if (page["missing"] != null || page["extract"] == null) return null
        return (page["extract"] as? JsonPrimitive)?.contentOrNull()?.trim()
    }

    /** Запасной путь для вики без расширения TextExtracts. */
    private fun wikitextLead(base: String, title: String): String? {
        val text = fetch(
            base.toHttpUrl().newBuilder()
                .addQueryParameter("action", "parse")
                .addQueryParameter("page", title)
                .addQueryParameter("prop", "wikitext")
                .addQueryParameter("section", "0")
                .addQueryParameter("redirects", "1")
                .addQueryParameter("format", "json")
                .build().toString(),
            accept = "application/json",
        ) ?: return null
        val wikitext = ((runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject)
            ?.get("parse") as? JsonObject)
            ?.get("wikitext")
            ?.let { (it as? JsonObject)?.get("*") }
            ?.let { (it as? JsonPrimitive)?.contentOrNull() }
            ?: return null
        return stripWikitext(wikitext)
    }

    /**
     * Грубая, но достаточная зачистка разметки вики: сноски, шаблоны, таблицы
     * и ссылки. Полноценный парсер вики-разметки здесь был бы сотней строк
     * ради абзаца текста, который читатель видит мельком.
     */
    private fun stripWikitext(source: String): String? {
        var text = source
        text = Regex("<ref[^>]*?/>|<ref[^>]*?>.*?</ref>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).replace(text, "")
        text = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL).replace(text, "")
        text = Regex("\\{\\{[^{}]*\\}\\}").replace(text, "")
        while (Regex("\\{\\{").containsMatchIn(text)) {
            text = Regex("\\{\\{[^{}]*\\}\\}").replace(text, "")
        }
        text = Regex("\\[\\[(?:[^\\]|]*\\|)?([^\\]]+)\\]\\]").replace(text) { it.groupValues[1] }
        text = Regex("\\[https?://\\S+\\s+([^\\]]+)\\]").replace(text) { it.groupValues[1] }
        text = Regex("'{2,}").replace(text, "")
        text = Regex("^[*:#;].*$", RegexOption.MULTILINE).replace(text, "")
        return text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "|" && !it.startsWith("|") }
            .joinToString(" ")
            .trim()
            .takeIf { it.length > 40 }
    }

    private fun extractThumbnail(base: String, title: String): String? {
        val text = fetch(
            base.toHttpUrl().newBuilder()
                .addQueryParameter("action", "query")
                .addQueryParameter("prop", "pageimages")
                .addQueryParameter("titles", title)
                .addQueryParameter("piprop", "thumbnail")
                .addQueryParameter("pithumbsize", "640")
                .addQueryParameter("redirects", "1")
                .addQueryParameter("format", "json")
                .build().toString(),
            accept = "application/json",
        ) ?: return null
        val pages = (runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject)
            ?.get("query")?.let { it as? JsonObject }?.get("pages") as? JsonObject ?: return null
        val page = pages.values.firstOrNull() as? JsonObject ?: return null
        return ((page["thumbnail"] as? JsonObject)?.get("source") as? JsonPrimitive)?.contentOrNull()
    }

    // --- категории: персонажи и места ---------------------------------------

    private val CHARACTER_CATEGORY = Regex("character|main|supporting|cast", RegexOption.IGNORE_CASE)
    private val PLACE_CATEGORY = Regex(
        "location|place|setting|world|town|city|country|school|geography",
        RegexOption.IGNORE_CASE,
    )
    /** Подстраницы вида «Кудо Ичиро/Gallery» — это раздел внутри страницы, а не персонаж. */
    private val SUBPAGE = Regex(
        "/(gallery|plot|images?|trivia|quotes?|history|references?|behind the scenes|screenshots?|" +
            "episodes?|appearances?|voice|staff|development|themes?|music|relations?|family|" +
            "background|notes?|spoilers?|timeline|manga|novel)$",
        RegexOption.IGNORE_CASE,
    )

    private class Roster(val characters: List<RosterEntry>, val places: List<RosterEntry>)

    private fun readRoster(slug: String, title: String): Roster = runCatching {
        val categories = listCategories(slug)
        val characters = LinkedHashMap<String, RosterEntry>()
        val places = LinkedHashMap<String, RosterEntry>()

        for ((name, _) in categories) {
            val bucket: LinkedHashMap<String, RosterEntry> = when {
                CHARACTER_CATEGORY.containsMatchIn(name) -> characters
                PLACE_CATEGORY.containsMatchIn(name) -> places
                else -> continue
            }
            for (member in categoryMembers(slug, name)) {
                val clean = member.trim()
                if (clean.isEmpty()) continue
                // Категории и служебные страницы персонажами не являются.
                if (clean.startsWith("Category:", ignoreCase = true)) continue
                // «Кудо Ичиро/Gallery» — это раздел внутри страницы персонажа.
                // Но «Amadeus System/Kurisu» — законное имя, поэтому режем
                // только известные служебные хвосты, а не любой слэш.
                if (SUBPAGE.containsMatchIn(clean)) continue
                bucket.getOrPut(clean.lowercase()) {
                    RosterEntry(
                        name = clean,
                        url = "https://$slug.fandom.com/wiki/${encodeTitle(clean)}",
                        source = "fandom",
                    )
                }
            }
        }
        Roster(characters.values.toList(), places.values.toList())
    }.getOrDefault(Roster(emptyList(), emptyList()))

    private fun listCategories(slug: String): List<Pair<String, Int>> {
        val text = fetch(
            "https://$slug.fandom.com/api.php".toHttpUrl().newBuilder()
                .addQueryParameter("action", "query")
                .addQueryParameter("list", "allcategories")
                .addQueryParameter("aclimit", "200")
                .addQueryParameter("acprop", "size")
                .addQueryParameter("format", "json")
                .build().toString(),
            accept = "application/json",
        ) ?: return emptyList()
        val array = (runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject)
            ?.get("query")?.let { it as? JsonObject }?.get("allcategories") as? JsonArray ?: return emptyList()
        return array.mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            val name = (obj["*"] as? JsonPrimitive)?.contentOrNull() ?: return@mapNotNull null
            name to (obj["size"]?.jsonPrimitive?.intOrNull ?: 0)
        }.sortedByDescending { it.second }
    }

    private fun categoryMembers(slug: String, category: String): List<String> {
        val text = fetch(
            "https://$slug.fandom.com/api.php".toHttpUrl().newBuilder()
                .addQueryParameter("action", "query")
                .addQueryParameter("list", "categorymembers")
                .addQueryParameter("cmtitle", "Category:$category")
                .addQueryParameter("cmlimit", "120")
                .addQueryParameter("cmnamespace", "0")
                .addQueryParameter("format", "json")
                .build().toString(),
            accept = "application/json",
        ) ?: return emptyList()
        val array = (runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject)
            ?.get("query")?.let { it as? JsonObject }?.get("categorymembers") as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonObject)?.get("title")?.jsonPrimitive?.contentOrNull() }
    }

    // --- HTTP ---------------------------------------------------------------

    private fun fetch(url: String, accept: String): String? = try {
        val request = Request.Builder().url(url)
            .header("Accept", accept)
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    } catch (error: IOException) {
        null
    }

    private fun JsonPrimitive.contentOrNull(): String? =
        if (isString) content.takeIf { it != "null" && it.isNotBlank() } else null

    private fun encodeTitle(title: String): String =
        java.net.URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "_")

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    companion object {
        private const val USER_AGENT = "AMPS/1.0 (android)"
    }
}
