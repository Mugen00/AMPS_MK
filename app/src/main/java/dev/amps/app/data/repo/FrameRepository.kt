package dev.amps.app.data.repo

import dev.amps.app.data.local.HistoryEntry
import dev.amps.app.data.local.HistoryStore
import dev.amps.app.data.local.StoredFrame
import dev.amps.app.data.model.AnimeCharacter
import dev.amps.app.data.model.AnimeMedia
import dev.amps.app.data.model.AnimeWikiPage
import dev.amps.app.data.model.BridgeAnime
import dev.amps.app.data.model.CharacterGuess
import dev.amps.app.data.model.EmptyResult
import dev.amps.app.data.model.ExternalArt
import dev.amps.app.data.model.ExternalLink
import dev.amps.app.data.model.FrameHit
import dev.amps.app.data.model.SourceRef
import dev.amps.app.data.model.Titles
import dev.amps.app.data.ranking.RankingEngine
import dev.amps.app.data.remote.AniListClient
import dev.amps.app.data.remote.DirectTraceClient
import dev.amps.app.data.remote.SauceClient
import dev.amps.app.data.remote.WikiClient
import dev.amps.app.imaging.ContentAnalyzer
import dev.amps.app.imaging.FrameFingerprint
import dev.amps.app.util.htmlToPlainText
import dev.amps.app.util.readableMessage
import dev.amps.app.util.sha256
import java.util.Locale
import java.util.UUID

/**
 * Turns one image into a full wiki page.
 *
 * trace.moe answers "which anime, which episode, which second"; AniList answers
 * "who, what, why you should care"; SauceNAO answers "which artwork and which
 * character tags"; the fandom wiki answers "what is this character even called".
 *
 * **Since 1.0.3 there is no bridge.** Every request is a direct HTTPS call from
 * the phone: trace.moe, SauceNAO, AniList, Wikidata and the fandom MediaWiki.
 * The price is a slower search on a bad connection and the loss of the PC's
 * shared cache; the gain is that the app works anywhere, including on a train
 * with no computer anywhere near it.
 *
 * No source is trusted on its own. [RankingEngine] weighs them into one ranked,
 * explained answer, and when they disagree the page is still shown — but marked
 * uncertain, with the alternatives listed, because quietly naming the wrong
 * anime is worse than admitting doubt.
 */
class FrameRepository(
    private val trace: DirectTraceClient,
    private val aniList: AniListClient,
    private val history: HistoryStore,
    private val sauce: SauceClient? = null,
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
        // Отпечаток остаётся полезным и без моста: он попадает в карточку и
        // позволяет отличить «тот же самый кадр» от «похожего кадра серии».
        val hash = runCatching { FrameFingerprint.dHash(bytes) }.getOrNull()

        val response = runCatching { trace.identify(bytes, fileName, mime) }
            .getOrElse { error ->
                return Outcome.Miss(
                    EmptyResult(
                        reason = error.readableMessage(),
                        raw = null,
                        searchedImage = previewDataUrl,
                        searchedImageSha256 = digest,
                        bridgeError = error.readableMessage(),
                    )
                )
            }

        // SauceNAO и модель содержимого не знают друг о друге, а модель вдобавок
        // считает локально. Запускаем их вместе с trace.moe, а не по очереди:
        // три независимых запроса подряд — это три задержки в ряд.
        val contentDeferred = contentAnalyzer?.let { analyzer ->
            runCatching { analyzer.analyze(bytes) }.getOrNull()
        }
        val sauceHits = runCatching { sauce?.search(bytes, fileName, mime) }.getOrNull()

        val traceResult = response.trace
        val traceId = traceResult?.anilist?.id

        val verdict = RankingEngine.rank(
            RankingEngine.Signals(
                trace = traceResult?.let {
                    RankingEngine.TraceHit(
                        matched = it.matched == true,
                        anilistId = it.anilist?.id,
                        titles = listOfNotNull(
                            it.anilist?.title?.english,
                            it.anilist?.title?.romaji,
                            it.anilist?.title?.native,
                        ).filter { name -> name.isNotBlank() },
                        similarity = (it.similarity ?: 0.0).toFloat(),
                        episode = it.episode,
                        timestamp = it.timestamp?.toFloat(),
                    )
                },
                sauce = sauceHits,
                labels = contentDeferred?.labels.orEmpty().map {
                    RankingEngine.Label(it.label, it.confidence)
                },
            )
        )

        if (traceId == null || traceResult?.matched != true) {
            return Outcome.Miss(
                EmptyResult(
                    reason = if (contentDeferred?.hasPerson == false) {
                        "Кадр не найден, и на нём не видно людей — скорее всего, это вообще не кадр аниме"
                    } else {
                        "Кадр не найден ни в одной базе"
                    },
                    raw = traceResult?.raw,
                    searchedImage = previewDataUrl,
                    searchedImageSha256 = digest,
                )
            )
        }

        val media = runCatching { aniList.media(traceId) }
            .getOrElse { error -> traceResult.anilist.toFallbackMedia(error.readableMessage()) }

        val sauceCharacters = sauceHits.orEmpty().flatMap { it.characters }
        val sauceTags = sauceHits.orEmpty().flatMap { it.tags }
        val guess = pickCharacter(media.characters, sauceCharacters, sauceTags)
        val frameHit = FrameHit(
            engine = traceResult.engine,
            episode = traceResult.episode,
            frame = traceResult.frame,
            timestamp = traceResult.timestamp,
            similarity = traceResult.similarity,
            sceneUrl = traceResult.video?.url,
        )

        val similarArt = sauceHits.orEmpty().map { hit ->
            ExternalArt(
                source = hit.source ?: "SauceNAO",
                title = hit.title ?: hit.series ?: hit.copyright ?: "совпадение",
                author = null,
                url = null,
                similarity = (hit.similarity * 100).toInt(),
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
            sources = buildSources(traceResult.engine, media, sauceHits.orEmpty().firstOrNull()?.source),
            rawEngineText = traceResult.raw,
            searchedImage = previewDataUrl,
            searchedImageSha256 = digest,
            verdict = verdict,
            content = contentDeferred,
            wiki = wikiInfo?.wiki,
            rosterCharacters = wikiInfo?.characters.orEmpty(),
            rosterPlaces = wikiInfo?.places.orEmpty(),
            frameHash = hash,
        )

        history.add(
            HistoryEntry(
                id = "frame-$traceId-${System.currentTimeMillis()}",
                kind = "FRAME",
                title = media.title.best ?: "Без названия",
                subtitle = buildString {
                    append("кадр")
                    frameHit.episode?.let { append(" · серия $it") }
                    frameHit.timestamp?.let { append(" · %d:%02d".format(it.toLong() / 60, it.toLong() % 60)) }
                },
                imageUrl = media.cover,
                createdAt = System.currentTimeMillis(),
                frame = StoredFrame(
                    anilistId = traceId,
                    episode = frameHit.episode,
                    timestamp = frameHit.timestamp,
                    similarity = traceResult.similarity,
                    characterName = guess?.character?.displayName,
                    engine = frameHit.engine,
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
            sources = buildSources(stored.engine, media, null),
        )
    }

    private fun buildSources(
        engine: String,
        media: AnimeMedia,
        sauceSource: String?,
    ): List<SourceRef> = buildList {
        add(SourceRef(engine, "https://trace.moe", "поиск кадра по изображению"))
        add(SourceRef("AniList", media.anilistUrl, "описание серии и персонажей"))
        media.malUrl?.let { add(SourceRef("MyAnimeList", it, "справочник серии")) }
        media.anidbUrl?.let { add(SourceRef("AniDB", it, "таймкоды и эпизоды")) }
        if (sauceSource != null) {
            add(SourceRef(sauceSource, null, "художественные совпадения по тегам"))
        }
    }

    /**
     * SauceNAO returns Danbooru/Pixiv style tags where a character is usually
     * named in latin. An exact tag hit wins; otherwise a token overlap decides.
     */
    private fun pickCharacter(
        candidates: List<AnimeCharacter>,
        sauceCharacters: List<String>,
        sauceTags: List<String>,
    ): CharacterGuess? {
        if (candidates.isEmpty()) return null
        val haystack = (sauceCharacters + sauceTags)
            .map { normalize(it) }
            .filter { it.isNotEmpty() }

        var best: CharacterGuess? = null
        candidates.forEach { candidate ->
            val full = normalize(candidate.name.full.orEmpty())
            val native = normalize(candidate.name.native.orEmpty())

            var score = 0.0
            var reason = ""
            if (full.isNotEmpty() && haystack.any { it.contains(full) }) {
                score = 0.92
                reason = "имя персонажа найдено в тегах SauceNAO"
            } else if (native.isNotEmpty() && haystack.any { it.contains(native) }) {
                score = 0.8
                reason = "японское имя найдено в тегах SauceNAO"
            } else {
                val tokens = candidate.name.full.orEmpty()
                    .split(' ', '-', '_')
                    .map { normalize(it) }
                    .filter { it.length > 2 }
                if (tokens.isNotEmpty()) {
                    val hits = tokens.count { token -> haystack.any { it.contains(token) } }
                    if (hits > 0) {
                        score = 0.45 * (hits.toDouble() / tokens.size)
                        reason = "$hits из ${tokens.size} слов имени совпали с тегами"
                    }
                }
            }
            if (score > (best?.score ?: 0.0)) {
                best = CharacterGuess(candidate, score, reason)
            }
        }
        return best?.takeIf { it.score >= MIN_CHARACTER_SCORE }
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).replace(NON_ALNUM, "")

    private companion object {
        val NON_ALNUM = Regex("[^\\p{L}\\p{N}]")
        const val MIN_CHARACTER_SCORE = 0.3
    }
}

/**
 * Used when AniList itself is unreachable: whatever trace.moe already reported
 * is still worth showing, marked as an incomplete record.
 */
private fun BridgeAnime.toFallbackMedia(note: String): AnimeMedia = AnimeMedia(
    id = id ?: 0,
    idMal = idMal,
    title = title ?: Titles(),
    description = htmlToPlainText(description),
    format = format,
    status = status,
    episodes = episodes,
    genres = genres,
    cover = coverImage?.best,
    score = averageScore,
    popularity = popularity,
    synonyms = synonyms,
    isAdult = isAdult,
    externalLinks = id?.let { listOf(ExternalLink("AniList", "https://anilist.co/anime/$it", "INFO")) }.orEmpty(),
    trailerThumbnail = null,
).copy(relations = emptyList())

internal fun newEntryId(prefix: String) = "$prefix-${UUID.randomUUID()}"

/**
 * The answer to one search: either a wiki page or an explained miss.
 *
 * A miss is a value, not an exception. "This frame is not from any anime" is a
 * finding the user needs to see and understand — which source was asked, what it
 * answered, and why that is not enough — and it has to survive into the UI
 * unchanged. Throwing it away to report "network error" would be a lie.
 */
sealed interface Outcome {
    data class Found(val page: AnimeWikiPage) : Outcome
    data class Miss(val result: EmptyResult) : Outcome
}
