package dev.amps.app.data.remote.anilist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 1.2.2: клієнт каталогу аніме — публічний GraphQL AniList (без ключа).
 *
 * Запити лише на читання: топ усіх часів, сезонні новинки, жанри, пошук
 * і nextAiringEpisode для нагадувань про нові серії. Метадані й обкладинки
 * віддає сам AniList — застосунок лише показує каталог.
 */
class AniListClient(private val client: OkHttpClient) {

    /** Картка каталогу: усе, що треба списку й кнопці «у мій список». */
    data class AnimeCard(
        val anilistId: Int,
        val title: String,
        val coverUrl: String,
        val genres: List<String> = emptyList(),
        val episodes: Int? = null,
        val averageScore: Int? = null,
        val seasonYear: Int? = null,
        val status: String? = null,
        /** Наступна серія: номер і час виходу (unix, секунд) — для нагадувань. */
        val nextEpisode: Int? = null,
        val nextAiringAt: Long? = null,
    )

    private val jsonParser = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json".toMediaType()

    /** Топ-24 за популярністю (усі часи); null — AniList недоступний. */
    suspend fun top(): List<AnimeCard>? = find("")

    /** Пошук за назвою (ромаджі/англійська); порожній рядок = топ усіх часів. */
    suspend fun search(query: String): List<AnimeCard>? = find(query)

    /**
     * Пошук або топ. 1.2.2 (патч 28): AniList на search: "" відповідає
     * НУЛЬОВИМ списком (порожній рядок = пошук порожнього імені), тому
     * для топу search-параметр взагалі не передається. isAdult: false —
     * без 18+ у каталогу.
     */
    suspend fun find(query: String): List<AnimeCard>? {
        val useSearch = query.isNotBlank()
        val q = if (useSearch) {
            """
            query (${'$'}page: Int, ${'$'}perPage: Int, ${'$'}search: String) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                media(type: ANIME, search: ${'$'}search, sort: SEARCH_MATCH, isAdult: false) {
                  id
                  title { romaji english }
                  coverImage { large }
                  genres
                  format
                  episodes
                  averageScore
                  seasonYear
                  status
                  nextAiringEpisode { episode airingAt }
                }
              }
            }
            """.trimIndent()
        } else {
            """
            query (${'$'}page: Int, ${'$'}perPage: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                media(type: ANIME, sort: POPULARITY_DESC, isAdult: false) {
                  id
                  title { romaji english }
                  coverImage { large }
                  genres
                  format
                  episodes
                  averageScore
                  seasonYear
                  status
                  nextAiringEpisode { episode airingAt }
                }
              }
            }
            """.trimIndent()
        }
        return mediaList(q, buildJsonObject {
            put("page", 1)
            put("perPage", 24)
            if (useSearch) put("search", query)
        })
    }

    /** Сезонні новинки: поточний сезон і рік за UTC. */
    suspend fun season(): List<AnimeCard>? {
        val now = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
        val season = when (now.monthValue) {
            1, 2, 3 -> "WINTER"
            4, 5, 6 -> "SPRING"
            7, 8, 9 -> "SUMMER"
            else -> "FALL"
        }
        val q = """
            query (${'$'}page: Int, ${'$'}perPage: Int, ${'$'}season: MediaSeason, ${'$'}year: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                media(type: ANIME, season: ${'$'}season, seasonYear: ${'$'}year, sort: POPULARITY_DESC, isAdult: false) {
                  id
                  title { romaji english }
                  coverImage { large }
                  genres
                  format
                  episodes
                  averageScore
                  seasonYear
                  status
                  nextAiringEpisode { episode airingAt }
                }
              }
            }
        """.trimIndent()
        return mediaList(q, buildJsonObject {
            put("page", 1)
            put("perPage", 24)
            put("season", season)
            put("year", now.year)
        })
    }

    /** За жанром — для вкладки «Жанри». */
    suspend fun byGenre(genre: String): List<AnimeCard>? {
        val q = """
            query (${'$'}page: Int, ${'$'}perPage: Int, ${'$'}genre: String) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                media(type: ANIME, genre: ${'$'}genre, sort: POPULARITY_DESC, isAdult: false) {
                  id
                  title { romaji english }
                  coverImage { large }
                  genres
                  format
                  episodes
                  averageScore
                  seasonYear
                  status
                  nextAiringEpisode { episode airingAt }
                }
              }
            }
        """.trimIndent()
        return mediaList(q, buildJsonObject {
            put("page", 1)
            put("perPage", 24)
            put("genre", genre)
        })
    }

    /**
     * Наступна серія тайтла — для нагадувань. (episode, airingAt-сек) або
     * null: тайтл завершився чи серії ще не анонсовані.
     */
    suspend fun nextAiring(anilistId: Int): Pair<Int, Long>? {
        val q = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                nextAiringEpisode { episode airingAt }
              }
            }
        """.trimIndent()
        val data = runQuery(q, buildJsonObject { put("id", anilistId) }) ?: return null
        val nae = (data["Media"] as? JsonObject)?.get("nextAiringEpisode") as? JsonObject
            ?: return null
        val episode = nae["episode"]?.jsonPrimitive?.intOrNull ?: return null
        val at = nae["airingAt"]?.jsonPrimitive?.longOrNull ?: return null
        return episode to at
    }

    // --- внутрішнє ----------------------------------------------------------

    /** Виконує GraphQL-запит у IO-потоку; повертає об'єкт data або null. */
    private suspend fun runQuery(query: String, variables: JsonObject): JsonObject? =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("query", query)
                put("variables", variables)
            }.toString()
            val request = Request.Builder()
                .url("https://graphql.anilist.co")
                .post(payload.toRequestBody(jsonMediaType))
                .header("Content-Type", "application/json")
                // Без UA частина Cloudflare-кордонів віддає 403 навіть легітимному
                // запиту — ідентифікуємось застосунком, це коштує нічого.
                .header("User-Agent", "AMPS/1.2.2 (Android; anime catalog)")
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val text = response.body?.string() ?: return@withContext null
                    (jsonParser.parseToJsonElement(text).jsonObject["data"] as? JsonObject)
                }
            } catch (_: Exception) {
                // Мережа або парсинг — клієнт повідомить «AniList недоступний».
                null
            }
        }

    /** data.Page.media → список карток; null — сервер недоступний/помилка. */
    private suspend fun mediaList(query: String, variables: JsonObject): List<AnimeCard>? {
        val data = runQuery(query, variables) ?: return null
        val media = (data["Page"] as? JsonObject)?.get("media") as? JsonArray ?: return null
        return media.mapNotNull { el ->
            val m = el as? JsonObject ?: return@mapNotNull null
            val id = m["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val title = run {
                val t = m["title"] as? JsonObject
                t?.get("english")?.jsonPrimitive?.contentOrNull
                    ?: t?.get("romaji")?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null
            }
            val nae = m["nextAiringEpisode"] as? JsonObject
            AnimeCard(
                anilistId = id,
                title = title,
                coverUrl = ((m["coverImage"] as? JsonObject)?.get("large"))
                    ?.jsonPrimitive?.contentOrNull ?: "",
                genres = (m["genres"] as? JsonArray)
                    ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
                    ?: emptyList(),
                episodes = m["episodes"]?.jsonPrimitive?.intOrNull,
                averageScore = m["averageScore"]?.jsonPrimitive?.intOrNull,
                seasonYear = m["seasonYear"]?.jsonPrimitive?.intOrNull,
                status = (m["status"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull,
                nextEpisode = nae?.get("episode")?.jsonPrimitive?.intOrNull,
                nextAiringAt = nae?.get("airingAt")?.jsonPrimitive?.longOrNull,
            )
        }
    }
}
