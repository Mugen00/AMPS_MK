package dev.amps.app.data.repo

import dev.amps.app.data.local.HistoryEntry
import dev.amps.app.data.local.HistoryStore
import dev.amps.app.data.local.StoredFrame
import dev.amps.app.data.model.AnimeCharacter
import dev.amps.app.data.model.AnimeMedia
import dev.amps.app.data.model.AnimeWikiPage
import dev.amps.app.data.model.CharacterGuess
import dev.amps.app.data.model.EmptyResult
import dev.amps.app.data.model.ExternalArt
import dev.amps.app.data.model.FrameHit
import dev.amps.app.data.model.SourceRef
import dev.amps.app.data.ranking.RankingEngine
import dev.amps.app.data.remote.AniListClient
import dev.amps.app.data.remote.IqdbClient
import dev.amps.app.data.remote.WikiClient
import dev.amps.app.imaging.ContentAnalyzer
import dev.amps.app.imaging.FrameFingerprint
import dev.amps.app.util.readableMessage
import dev.amps.app.util.sha256
import java.util.UUID

/**
 * Превращает одну картинку в вики-страницу.
 *
 * **С 1.0.5 источник ровно один — IQDB.** Он ищет по иллюстрациям, скриншотам
 * и фотографиям: то есть по тому, что реально имеет смысл искать, а не по
 * кадрам видеозаписей. AniList отвечает на второй вопрос — «кто это и что это
 * за произведение»: по бо́ру-тегам `belgium_(hetalia)` он находит персонажа, а
 * по персонажу — серию. Вики фандома отвечает на третий — «что этот персонаж
 * вообще собой представляет».
 *
 * **Почему цепочка именно такая.** Раньше серию называл trace.moe по совпадению
 * кадра в видеозаписи. У IQDB нет номеров эпизодов, но зато есть теги, а теги
 * персонажа почти всегда уникальны для произведения: `belgium` в AniList
 * находится ровно один, и его появления дают серию. Это и есть новый способ
 * получить произведение, и он работает там, где кадр видео не нужен вовсе.
 *
 * Ни один источник не доверяется сам себе. [RankingEngine] сводит находки в
 * одно взвешенное, объяснённое решение, а когда базы внутри IQDB спорят,
 * страница всё равно показывается — но помеченная спорной, с альтернативами:
 * тихо назвать не то аниме хуже, чем признать сомнение.
 */
class FrameRepository(
    private val iqdb: IqdbClient? = null,
    private val aniList: AniListClient,
    private val history: HistoryStore,
    private val wiki: WikiClient? = null,
    private val contentAnalyzer: ContentAnalyzer? = null,
) {

    suspend fun identify(
        bytes: ByteArray,
        fileName: String,
        mime: String,
        previewDataUrl: String? = null,
    ): Outcome {
        val digest = bytes.sha256()
        // Отпечаток остаётся полезным и без trace.moe: он попадает в карточку и
        // позволяет отличить «ту же самую картинку» от «похожей на неё».
        val hash = runCatching { FrameFingerprint.dHash(bytes) }.getOrNull()

        // Модель содержимого считает локально и ни о чём внешнем не знает,
        // поэтому запускаем её вместе с IQDB, а не по очереди: два запроса
        // подряд — это две задержки в ряд.
        val content = contentAnalyzer?.let { analyzer ->
            runCatching { analyzer.analyze(bytes) }.getOrNull()
        }

        val iqdbCall = runCatching { iqdb?.search(bytes, fileName, mime) }
        val found = iqdbCall.getOrNull()
        val iqdbError = iqdbCall.exceptionOrNull()?.readableMessage()?.takeIf { iqdb != null }

        val labels = content?.labels.orEmpty().map { RankingEngine.Label(it.label, it.confidence) }

        // Отказ IQDB не должен ронять весь поиск, но и молчать о нём нельзя:
        // «сервис не ответил» и «совпадений нет» — это разные вещи, и пользователю
        // нужно знать, какая из них случилась.
        if (found == null) {
            return Outcome.Miss(
                EmptyResult(
                    reason = iqdbError?.let { "IQDB не ответил: $it" }
                        ?: "Источник поиска не настроен",
                    raw = null,
                    searchedImage = previewDataUrl,
                    searchedImageSha256 = digest,
                )
            )
        }

        // `best` — вычисляемое свойство, поэтому компилятор не может сузить его тип
        // после проверки на null. Кладём в локальную переменную: дальше она
        // неизменна, и все умолчания Kotlin работают как обычно.
        val hit = found.best
        if (hit == null) {
            return Outcome.Miss(
                EmptyResult(
                    reason = missReason(found, content?.hasPerson == false),
                    raw = describe(found),
                    searchedImage = previewDataUrl,
                    searchedImageSha256 = digest,
                )
            )
        }

        // Персонажа и серию ищем по тегам. Это запросы к AniList, и каждый стоит
        // времени, поэтому берём только самое необходимое: персонажа по первому
        // подходящему тегу, серию — по самой популярной из его появлений.
        val resolved = resolve(hit, found.hits)
        val media = resolved?.media
        if (media == null) {
            return Outcome.Miss(
                EmptyResult(
                    reason = "Совпадения нашлись (лучшее — ${hit.similarity} % в базе «${hit.source}»), " +
                        "но серию по ним назвать не вышло: у совпадения нет бо́ру-тегов персонажа или серии, " +
                        "а по одному проценту AniList серию не подберёт. Это не ошибка приложения — " +
                        "найдено просто слишком мало данных.",
                    raw = describe(found),
                    searchedImage = previewDataUrl,
                    searchedImageSha256 = digest,
                )
            )
        }

        val verdict = RankingEngine.rank(
            RankingEngine.Signals(
                iqdb = RankingEngine.IqdbResult(
                    hits = found.hits.map { it.toRankingHit() },
                    exactMatchFound = found.exactMatchFound,
                    scannedImages = found.scannedImages,
                    resolvedMediaId = media.id,
                    resolvedMediaTitle = media.title.best,
                    resolvedVia = resolved.via,
                    resolvedViaTag = resolved.viaTag,
                    confirmedCharacter = resolved.character?.displayName,
                ),
                labels = labels,
            )
        )

        val guess = resolved.character?.let {
            CharacterGuess(
                character = it,
                score = 1.0,
                reason = "имя «${resolved.characterTag}» взято из тегов источника и найдено в AniList; " +
                    "серия взята из его появлений",
            )
        }

        val frameHit = FrameHit(
            source = hit.source,
            similarityPercent = hit.similarity,
            url = hit.url,
            exactMatch = hit.isBest,
        )

        val similarArt = found.hits.map { art ->
            ExternalArt(
                source = art.source,
                title = art.seriesTags.firstOrNull()
                    ?: art.characterTags.firstOrNull()
                    ?: "совпадение",
                author = null,
                url = art.url,
                similarity = art.similarity,
            )
        }

        // Вики — это несколько запросов подряд, и она не должна задерживать
        // карточку: пользователь и так уже видит серию, имена подгрузятся следом.
        val wikiInfo = wiki?.let { client ->
            runCatching { client.lookup(media.title.best.orEmpty(), titlesOf(media)) }.getOrNull()
        }

        val page = AnimeWikiPage(
            media = media,
            frame = frameHit,
            guess = guess,
            candidates = media.characters,
            similarArt = similarArt,
            sources = buildSources(media, found),
            rawEngineText = describe(found),
            searchedImage = previewDataUrl,
            searchedImageSha256 = digest,
            verdict = verdict,
            content = content,
            wiki = wikiInfo?.wiki,
            rosterCharacters = wikiInfo?.characters.orEmpty(),
            rosterPlaces = wikiInfo?.places.orEmpty(),
            frameHash = hash,
        )

        history.add(
            HistoryEntry(
                id = "frame-${media.id}-${System.currentTimeMillis()}",
                kind = "FRAME",
                title = media.title.best ?: "Без названия",
                subtitle = buildString {
                    append("найдено по картинке")
                    frameHit.similarityPercent?.let { append(" · совпадение $it %") }
                    guess?.character?.displayName?.let { append(" · $it") }
                },
                imageUrl = media.cover,
                createdAt = System.currentTimeMillis(),
                frame = StoredFrame(
                    anilistId = media.id,
                    similarity = frameHit.similarityPercent?.let { it / 100.0 },
                    characterName = guess?.character?.displayName,
                    engine = IQDB_LABEL,
                ),
            )
        )

        return Outcome.Found(page)
    }

    private fun titlesOf(media: AnimeMedia): List<String> =
        listOfNotNull(media.title.romaji, media.title.english, media.title.native)
            .filter { it.isNotBlank() }

    /** Re-opens a stored entry: only the AniList part has to be fetched again. */
    suspend fun reload(anilistId: Int, stored: StoredFrame): AnimeWikiPage {
        val media = aniList.media(anilistId)
        return AnimeWikiPage(
            media = media,
            frame = stored.toFrameHit(),
            candidates = media.characters,
            sources = buildSources(media, null),
        )
    }

    private fun buildSources(media: AnimeMedia, found: IqdbClient.SearchResult?): List<SourceRef> = buildList {
        add(SourceRef(IQDB_LABEL, "https://iqdb.org/", "обратный поиск по картинке: Danbooru, Konachan, Gelbooru, Sankaku и другие"))
        add(SourceRef("AniList", media.anilistUrl, "описание серии и персонажей"))
        media.malUrl?.let { add(SourceRef("MyAnimeList", it, "справочник серии")) }
        media.anidbUrl?.let { add(SourceRef("AniDB", it, "таймкоды и эпизоды")) }
        found?.best?.let { add(SourceRef(it.source, it.url, "страница найденной картинки")) }
    }

    /**
     * Ищет серию и персонажа по бо́ру-тегам IQDB.
     *
     * Порядок неслучаен: тег персонажа уникальнее тега серии, а серия, взятая
     * из появлений персонажа, точнее, чем поиск по названию. Сначала пробуем
     * персонажа, и только если он не нашёлся — название серии.
     */
    private suspend fun resolve(
        best: IqdbClient.Hit,
        hits: List<IqdbClient.Hit>,
    ): Resolved? {
        // Теги берём со всех совпадений, а не только с лучшего: у лучшего
        // источника теги иногда обрезаны, а у второго-третьего они есть.
        val characterTags = tagsOf(hits) { it.characterTags }
        val seriesTags = tagsOf(hits) { it.seriesTags }

        for (tag in characterTags) {
            val found = runCatching { aniList.searchCharacters(tag, CHARACTER_LIMIT) }.getOrNull().orEmpty()
            // `favourites` — единственная честная мера «настоящести» персонажа:
            // строка поиска AniList может вернуть однофамильца из другой серии.
            val character = found.maxByOrNull { it.favourites } ?: continue
            val mediaRef = character.appearances.firstOrNull() ?: continue
            val media = runCatching { aniList.media(mediaRef.id) }.getOrNull() ?: continue
            return Resolved(
                media = media,
                character = character,
                via = "тегу персонажа «$tag»",
                viaTag = tag,
                characterTag = tag,
            )
        }

        for (tag in seriesTags) {
            val found = runCatching { aniList.searchMedia(tag, MEDIA_LIMIT) }.getOrNull().orEmpty()
            val pick = found.maxByOrNull { (it.favourites ?: 0) + (it.popularity ?: 0) } ?: continue
            val media = runCatching { aniList.media(pick.id) }.getOrNull() ?: continue
            return Resolved(
                media = media,
                character = null,
                via = "тегу серии «$tag»",
                viaTag = tag,
                characterTag = null,
            )
        }
        return null
    }

    private fun tagsOf(hits: List<IqdbClient.Hit>, selector: (IqdbClient.Hit) -> List<String>): List<String> =
        hits.flatMap(selector)
            .map { it.trim() }
            .filter { it.length >= MIN_TAG_LENGTH }
            .distinct()
            .take(TAG_LIMIT)

    private fun IqdbClient.Hit.toRankingHit() = RankingEngine.IqdbHit(
        source = source,
        similarity = similarity / 100f,
        url = url,
        isBest = isBest,
        characterTags = characterTags,
        seriesTags = seriesTags,
    )

    /**
     * Честный текст для пустого ответа IQDB.
     *
     * Два разных случая, которые нельзя смешивать: на картинке нет людей (то
     * есть это, скорее всего, вообще не кадр аниме) и люди есть, но картинки
     * нет в индексе. Пользователь должен понимать, куда смотреть.
     */
    private fun missReason(found: IqdbClient.SearchResult, noPerson: Boolean): String = buildString {
        append("IQDB просмотрел ")
        append(found.scannedImages?.toString() ?: "несколько миллионов")
        append(" изображений и не нашёл совпадений")
        if (noPerson) {
            append(". На картинке при этом нет ни одного человека — скорее всего, это вообще не кадр аниме")
        }
        append(". ")
        append(
            "IQDB ищет по иллюстрациям и скриншотам, которые уже лежат в бо́ру-базах; " +
                "обычное фото или скриншот из видеоигры там не лежат."
        )
    }

    /** Текстовый слепок ответа: показывается пользователю целиком, а не выдумывается. */
    private fun describe(found: IqdbClient.SearchResult): String = buildString {
        append("IQDB, просмотрено изображений: ")
        append(found.scannedImages?.toString() ?: "не указано")
        append(", точное совпадение: ")
        append(if (found.exactMatchFound) "да" else "нет")
        append(", совпадений: ")
        append(found.hits.size)
        found.hits.forEach { hit ->
            append("\n")
            append(if (hit.isBest) "★ " else "· ")
            append("${hit.similarity}% ${hit.source}")
            hit.width?.let { append(" ${it}×${hit.height}") }
            if (hit.tags.isNotEmpty()) append(" · ${hit.tags.joinToString(" ")}")
            hit.url?.let { append("\n  $it") }
        }
    }

    private companion object {
        const val IQDB_LABEL = "IQDB"
        const val MIN_TAG_LENGTH = 3
        const val TAG_LIMIT = 4
        const val CHARACTER_LIMIT = 6
        const val MEDIA_LIMIT = 6

        /** Теги, из которых серию не вывести: служебные слова бо́ру-разметки. */
        const val ONLY_MARKERS = "|||"
    }

    /** Что удалось вытащить из тегов IQDB и подтвердить в AniList. */
    private data class Resolved(
        val media: AnimeMedia,
        val character: AnimeCharacter?,
        val via: String,
        val viaTag: String,
        val characterTag: String?,
    )
}

/**
 * The answer to one search: either a wiki page or an explained miss.
 *
 * A miss is a value, not an exception. "This picture is not from any anime" is a
 * finding the user needs to see and understand — which source was asked, what it
 * answered, and why that is not enough — and it has to survive into the UI
 * unchanged. Throwing it away to report "network error" would be a lie.
 */
sealed interface Outcome {
    data class Found(val page: AnimeWikiPage) : Outcome
    data class Miss(val result: EmptyResult) : Outcome
}

internal fun newEntryId(prefix: String) = "$prefix-${UUID.randomUUID()}"