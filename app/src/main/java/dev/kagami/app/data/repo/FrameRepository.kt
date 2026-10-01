package dev.kagami.app.data.repo

import dev.kagami.app.data.local.HistoryEntry
import dev.kagami.app.data.local.HistoryStore
import dev.kagami.app.data.local.StoredFrame
import dev.kagami.app.data.model.AnimeCharacter
import dev.kagami.app.data.model.AnimeMedia
import dev.kagami.app.data.model.AnimeWikiPage
import dev.kagami.app.data.model.BridgeAnime
import dev.kagami.app.data.model.CharacterGuess
import dev.kagami.app.data.model.EmptyResult
import dev.kagami.app.data.model.ExternalArt
import dev.kagami.app.data.model.ExternalLink
import dev.kagami.app.data.model.FrameHit
import dev.kagami.app.data.model.SourceRef
import dev.kagami.app.data.model.Titles
import dev.kagami.app.data.remote.AniListClient
import dev.kagami.app.data.remote.BridgeClient
import dev.kagami.app.util.htmlToPlainText
import dev.kagami.app.util.readableMessage
import dev.kagami.app.util.sha256
import java.util.Locale
import java.util.UUID

/**
 * Turns one image into a full wiki page.
 *
 * trace.moe answers "which anime, which episode, which second"; AniList answers
 * "who, what, why you should care"; SauceNAO (through the same bridge) answers
 * "which artwork and which character tags", which is what makes the character
 * pick reliable instead of a guess from a face.
 */
class FrameRepository(
    private val bridge: BridgeClient,
    private val aniList: AniListClient,
    private val history: HistoryStore,
) {

    sealed interface Outcome {
        data class Found(val page: AnimeWikiPage) : Outcome
        data class Miss(val result: EmptyResult) : Outcome
    }

    suspend fun identify(
        bytes: ByteArray,
        fileName: String,
        mime: String,
        previewDataUrl: String? = null,
    ): Outcome {
        val digest = bytes.sha256()
        val response = runCatching { bridge.identify(bytes, fileName, mime) }
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

        response.error?.let { message ->
            return Outcome.Miss(
                EmptyResult(
                    reason = message,
                    raw = response.message,
                    searchedImage = previewDataUrl,
                    searchedImageSha256 = digest,
                    bridgeError = message,
                )
            )
        }

        val trace = response.trace
        val anilistId = trace?.anilist?.id
        if (trace == null || !trace.matched || anilistId == null) {
            return Outcome.Miss(
                EmptyResult(
                    reason = "trace.moe не узнал этот кадр",
                    raw = trace?.raw,
                    searchedImage = previewDataUrl,
                    searchedImageSha256 = digest,
                )
            )
        }

        val media = runCatching { aniList.media(anilistId) }
            .getOrElse { error -> trace.anilist.toFallbackMedia(error.readableMessage()) }

        val sauce = response.sauce
        val guess = pickCharacter(media.characters, sauce?.characters.orEmpty(), sauce?.tags.orEmpty())
        val frameHit = FrameHit(
            engine = trace.engine,
            episode = trace.episode,
            frame = trace.frame,
            timestamp = trace.timestamp,
            similarity = trace.similarity,
            sceneUrl = trace.video?.url,
        )

        val similarArt = buildList {
            if (sauce?.matched == true) {
                // Every SauceNAO hit becomes a "similar image" row, best first.
                val candidates = sauce.results.ifEmpty {
                    listOf(
                        dev.kagami.app.data.model.SauceCandidate(
                            similarity = sauce.similarity,
                            title = htmlToPlainText(sauce.title),
                            author = sauce.author,
                            url = sauce.url,
                        )
                    )
                }
                candidates.forEach { candidate ->
                    candidate.url?.let { link ->
                        add(
                            ExternalArt(
                                source = sauce.source ?: "SauceNAO",
                                title = htmlToPlainText(candidate.title),
                                author = candidate.author,
                                url = link,
                                similarity = candidate.similarity?.let { (it * 100).toInt() },
                            )
                        )
                    }
                }
            }
        }

        val page = AnimeWikiPage(
            media = media,
            frame = frameHit,
            guess = guess,
            candidates = media.characters,
            similarArt = similarArt,
            sources = buildSources(trace.engine, media, sauce?.source),
            rawEngineText = trace.raw,
            searchedImage = previewDataUrl,
            searchedImageSha256 = digest,
        )

        history.add(
            HistoryEntry(
                id = "frame-$anilistId-${System.currentTimeMillis()}",
                kind = "FRAME",
                title = media.title.best ?: "Без названия",
                subtitle = buildString {
                    append("кадр")
                    trace.episode?.let { append(" · серия $it") }
                    trace.timestamp?.let { append(" · %d:%02d".format(it.toLong() / 60, it.toLong() % 60)) }
                },
                imageUrl = media.cover,
                createdAt = System.currentTimeMillis(),
                frame = StoredFrame(
                    anilistId = anilistId,
                    episode = trace.episode,
                    timestamp = trace.timestamp,
                    similarity = trace.similarity,
                    characterName = guess?.character?.displayName,
                    engine = trace.engine,
                ),
            )
        )

        return Outcome.Found(page)
    }

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

    private fun buildSources(engine: String, media: AnimeMedia, sauceSource: String?): List<SourceRef> = buildList {
        add(SourceRef("trace.moe", "https://trace.moe", "поиск кадра по изображению"))
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
