package dev.amps.app.data.remote

import dev.amps.app.data.model.AnimeCharacter
import dev.amps.app.data.model.AnimeMedia
import dev.amps.app.data.model.AnimeTag
import dev.amps.app.data.model.CharacterName
import dev.amps.app.data.model.ExternalLink
import dev.amps.app.data.model.FuzzyDate
import dev.amps.app.data.model.Relation
import dev.amps.app.data.model.Studio
import dev.amps.app.data.model.Titles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * AniList GraphQL is free, needs no key and is the only source that returns a
 * structured, citable description of a series, its staff-free cast and their
 * artwork, so it does the "wiki" half of the job.
 *
 * С 1.0.5 клиент отвечает ещё и за нахождение **серии**: обратный поиск IQDB
 * отдаёт бо́ру-теги (`belgium_(hetalia)`, `hetalia:_axis_powers`), а превратить
 * их в серию можно только здесь — по персонажу или по названию. Поэтому
 * появились [searchCharacters] и [searchMedia].
 */
class AniListClient(private val client: OkHttpClient) {

    private val endpoint = "https://graphql.anilist.co"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun media(id: Int): AnimeMedia = withContext(Dispatchers.IO) {
        val payload = query(MEDIA_QUERY, buildJsonObject { put("id", id) })
        val media = payload.obj("data", "Media")
            ?: throw IllegalStateException("AniList не вернул серию $id")
        media.toAnimeMedia()
    }

    /**
     * Ищет персонажа по имени, полученному из бо́ру-тегов IQDB (`belgium`).
     *
     * Запрос отсортирован по `SEARCH_MATCH`, а вложенные `media` — по
     * `POPULARITY_DESC`, поэтому первый элемент `appearances` — самая
     * популярная серия, где персонаж появляется. Раньше серию называл
     * trace.moe по кадру видео; с 1.0.5 этот путь служит запасным.
     */
    suspend fun searchCharacters(name: String, limit: Int = 8): List<AnimeCharacter> = withContext(Dispatchers.IO) {
        val payload = query(
            CHARACTER_SEARCH_QUERY,
            buildJsonObject { put("q", name); put("perPage", limit) },
        )
        payload.rows("data", "Page", "characters").map { it.toCharacter("BACKGROUND") }
    }

    /**
     * Ищет серию по названию — второй путь от бо́ру-тега IQDB к произведению.
     *
     * Тег серии у IQDB выглядит как `axis powers`, и это название серии,
     * только без приставы. Точного совпадения AniList не обещает, поэтому
     * выбирать приходится на стороне вызова — по популярности и избранному.
     */
    suspend fun searchMedia(name: String, limit: Int = 6): List<AnimeMedia> = withContext(Dispatchers.IO) {
        val payload = query(
            MEDIA_SEARCH_QUERY,
            buildJsonObject { put("q", name); put("perPage", limit) },
        )
        payload.rows("data", "Page", "media").map { it.toAnimeMedia() }
    }

    /**
     * Читает список объектов по пути, принимая **оба** вида ответа.
     *
     * AniList отдаёт `Page.media` и `Page.characters` готовым массивом, а
     * вложенные связи (`Media.characters`, `Character.media`) — объектом с
     * `nodes`. Форма менялся прямо во время работы над 1.0.5: старый разбор
     * `obj(…)?.arr("nodes")` на новом ответе давал `null`, и метод молча
     * возвращал пустой список — то есть «персонажа не нашлось» вместо «схема
     * изменилась». Здесь оба вида принимаются, и лишняя обёртка больше не нужна.
     */
    private fun JsonObject.rows(vararg path: String): List<JsonObject> =
        when (val element = at(*path)) {
            is JsonArray -> element.objects()
            is JsonObject -> element.arr("nodes").objects()
            else -> emptyList()
        }

    private fun query(document: String, variables: JsonObject): JsonObject {
        val body = buildJsonObject {
            put("query", document)
            put("variables", variables)
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .post(body)
            .build()
        val response = client.newCall(request).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw IllegalStateException("AniList HTTP ${it.code}")
            val parsed = json.parseToJsonElement(text) as? JsonObject
                ?: throw IllegalStateException("AniList вернул не-JSON ответ")
            val errors = parsed.arr("errors")
            if (errors.isNotEmpty()) {
                val message = errors.objects().firstOrNull()?.str("message").orEmpty()
                throw IllegalStateException("AniList: ${message.ifBlank { "запрос отклонён" }}")
            }
            return parsed
        }
    }

    private fun JsonObject.toAnimeMedia(): AnimeMedia {
        val relations = arr("relations").objects().map { edge ->
            val node = edge.obj("node") ?: return@map null
            Relation(
                type = edge.str("relationType").orEmpty().lowercase(),
                media = AnimeMedia(
                    id = node.int("id") ?: 0,
                    idMal = node.int("idMal"),
                    title = node.toTitles(),
                    format = node.str("format"),
                    cover = node.str("coverImage", "medium"),
                    episodes = node.int("episodes"),
                ),
            )
        }.filterNotNull()

        val recommendations = arr("recommendations").objects().mapNotNull { entry ->
            val node = entry.obj("mediaRecommendation") ?: return@mapNotNull null
            AnimeMedia(
                id = node.int("id") ?: return@mapNotNull null,
                title = node.toTitles(),
                cover = node.str("coverImage", "medium"),
                score = node.int("averageScore"),
                genres = node.strings("genres"),
            )
        }

        return AnimeMedia(
            id = int("id") ?: 0,
            idMal = int("idMal"),
            title = toTitles(),
            description = str("description"),
            synonyms = strings("synonyms"),
            format = str("format"),
            status = str("status"),
            episodes = int("episodes"),
            duration = int("duration"),
            season = str("season"),
            seasonYear = int("seasonYear"),
            startDate = obj("startDate")?.toFuzzyDate(),
            genres = strings("genres"),
            tags = arr("tags").objects().mapNotNull { tag ->
                val name = tag.str("name") ?: return@mapNotNull null
                AnimeTag(id = tag.int("id") ?: 0, name = name, rank = tag.int("rank") ?: 0)
            }.sortedByDescending { it.rank }.take(14),
            cover = str("coverImage", "extraLarge") ?: str("coverImage", "large"),
            banner = str("bannerImage"),
            coverColor = str("coverImage", "color"),
            score = int("averageScore"),
            popularity = int("popularity"),
            favourites = int("favourites"),
            trending = int("trending"),
            isAdult = bool("isAdult") ?: false,
            studios = arr("studios").objects().flatMap { it.arr("edges").objects() }.mapNotNull { edge ->
                val name = edge.obj("node")?.str("name") ?: return@mapNotNull null
                Studio(name = name, isMain = edge.bool("isMain") ?: false)
            },
            characters = arr("characters").objects().mapNotNull { edge ->
                val node = edge.obj("node") ?: return@mapNotNull null
                node.toCharacter(edge.str("role") ?: "BACKGROUND")
            },
            relations = relations,
            recommendations = recommendations,
            externalLinks = arr("externalLinks").objects().mapNotNull { link ->
                val url = link.str("url") ?: return@mapNotNull null
                ExternalLink(site = link.str("site") ?: "site", url = url, type = link.str("type"))
            },
            trailerThumbnail = str("trailer", "thumbnail"),
        )
    }

    private fun JsonObject.toCharacter(role: String): AnimeCharacter = AnimeCharacter(
        id = int("id") ?: 0,
        name = CharacterName(full = str("name", "full"), native = str("name", "native")),
        role = role,
        image = str("image", "large"),
        description = str("description"),
        age = str("age"),
        gender = str("gender"),
        bloodType = str("bloodType"),
        favourites = int("favourites") ?: 0,
        appearances = arr("media").objects().mapNotNull { node ->
            AnimeMedia(
                id = node.int("id") ?: return@mapNotNull null,
                idMal = node.int("idMal"),
                title = node.toTitles(),
                cover = node.str("coverImage", "medium"),
                format = node.str("format"),
                episodes = node.int("episodes"),
                startDate = node.obj("startDate")?.toFuzzyDate(),
            )
        },
    )

    private fun JsonObject.toTitles() = Titles(
        romaji = str("title", "romaji"),
        english = str("title", "english"),
        native = str("title", "native"),
    )

    private fun JsonObject.toFuzzyDate() = FuzzyDate(
        year = int("year"),
        month = int("month"),
        day = int("day"),
    )

    private companion object {
        const val MEDIA_QUERY = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                id
                idMal
                title { romaji english native }
                description(asHtml: false)
                synonyms
                format
                status
                episodes
                duration
                season
                seasonYear
                startDate { year month day }
                averageScore
                popularity
                favourites
                trending
                isAdult
                genres
                tags { id name rank }
                coverImage { extraLarge large color }
                bannerImage
                studios { edges { isMain node { name } } }
                characters(perPage: 25, sort: [ROLE, RELEVANCE, FAVOURITES_DESC]) {
                  edges {
                    role
                    node {
                      id
                      name { full native }
                      image { large medium }
                      description(asHtml: false)
                      age
                      gender
                      bloodType
                      favourites
                      media(perPage: 30, type: ANIME, sort: [START_DATE]) {
                        nodes {
                          id
                          idMal
                          title { romaji english }
                          format
                          episodes
                          coverImage { medium }
                          startDate { year }
                        }
                      }
                    }
                  }
                }
                relations {
                  edges {
                    relationType
                    node { id idMal type format episodes title { romaji english } coverImage { medium } }
                  }
                }
                recommendations(sort: RATING_DESC, perPage: 8) {
                  nodes { mediaRecommendation { id title { romaji english } coverImage { medium } averageScore genres } }
                }
                externalLinks { site url type }
                trailer { thumbnail }
              }
            }
        """

        /**
         * 1.0.5: поиск персонажа по бо́ру-тегу IQDB.
         *
         * Списки `Page.characters` и `Page.media` AniList отдаёт **простым
         * массивом** без обёртки `nodes` — проверено живым запросом к
         * `graphql.anilist.co`. Связь с `nodes` на верхнем уровне страницы
         * больше не существует: такой запрос отвечает ошибкой
         * `Cannot query field "nodes" on type "Character"`. Вложенные связи
         * (`Media.characters`, `Character.media`) остались как были.
         */
        const val CHARACTER_SEARCH_QUERY = """
            query (${'$'}q: String, ${'$'}perPage: Int) {
              Page(page: 1, perPage: ${'$'}perPage) {
                characters(search: ${'$'}q, sort: [SEARCH_MATCH]) {
                  id
                  name { full native }
                  image { large medium }
                  description(asHtml: false)
                  age
                  gender
                  bloodType
                  favourites
                  media(perPage: 10, type: ANIME, sort: [POPULARITY_DESC]) {
                    nodes { id idMal format episodes title { romaji english } coverImage { medium } startDate { year } }
                  }
                }
              }
            }
        """

        /**
         * 1.0.5: поиск самой серии по бо́ру-тегу IQDB. Запрашивается ровно то же,
         * что и в [MEDIA_QUERY], кроме связей и рекомендаций: по названию они
         * всё равно ставят в лишний заголовок, а страница успевает показать
         * обложку раньше, чем они докачаются.
         */
        const val MEDIA_SEARCH_QUERY = """
            query (${'$'}q: String, ${'$'}perPage: Int) {
              Page(page: 1, perPage: ${'$'}perPage) {
                media(search: ${'$'}q, type: ANIME, sort: [SEARCH_MATCH]) {
                  id
                  idMal
                  title { romaji english native }
                  description(asHtml: false)
                  synonyms
                  format
                  status
                  episodes
                  duration
                  season
                  seasonYear
                  startDate { year month day }
                  averageScore
                  popularity
                  favourites
                  trending
                  isAdult
                  genres
                  tags { id name rank }
                  coverImage { extraLarge large color }
                  bannerImage
                  studios { edges { isMain node { name } } }
                  externalLinks { site url type }
                  trailer { thumbnail }
                }
              }
            }
        """
    }
}
