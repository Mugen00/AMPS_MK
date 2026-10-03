package dev.amps.app.data.repo

import dev.amps.app.data.local.HistoryEntry
import dev.amps.app.data.local.HistoryStore
import dev.amps.app.data.local.StoredFrame
import dev.amps.app.data.model.AnimeCharacter
import dev.amps.app.data.model.AnimeMedia
import dev.amps.app.data.model.AnimeWikiPage
import dev.amps.app.data.model.BridgeAnime
import dev.amps.app.data.model.CharacterGuess
import dev.amps.app.data.model.ContentLabel
import dev.amps.app.data.model.EmptyResult
import dev.amps.app.data.model.ExternalArt
import dev.amps.app.data.model.ExternalLink
import dev.amps.app.data.model.FrameContent
import dev.amps.app.data.model.FrameHit
import dev.amps.app.data.model.FrameVerdict
import dev.amps.app.data.model.IndexMatch
import dev.amps.app.data.model.RankedCandidate
import dev.amps.app.data.model.RosterEntry
import dev.amps.app.data.model.SauceResult
import dev.amps.app.data.model.SourceRef
import dev.amps.app.data.model.Titles
import dev.amps.app.data.model.TraceResult
import dev.amps.app.data.model.WikiReference
import dev.amps.app.data.remote.AniListClient
import dev.amps.app.data.remote.BridgeClient
import dev.amps.app.data.remote.BridgeUnreachableException
import dev.amps.app.data.remote.DirectTraceClient
import dev.amps.app.data.remote.FrameIndexClient
import dev.amps.app.data.remote.IndexSignal
import dev.amps.app.data.remote.RankClient
import dev.amps.app.data.remote.RankRequest
import dev.amps.app.data.remote.SauceSignal
import dev.amps.app.data.remote.TraceSignal
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
 * "who, what, why you should care"; SauceNAO (through the same bridge) answers
 * "which artwork and which character tags", which is what makes the character
 * pick reliable instead of a guess from a face.
 *
 * Since 1.0.2 none of those is trusted on its own. The repository collects four
 * independent signals — trace.moe, SauceNAO, the bridge's own frame index and the
 * on-device content model — and the bridge weighs them into one ranked, explained
 * answer. When the sources disagree the page is still shown, but marked uncertain
 * with the alternatives listed, because quietly naming the wrong anime is worse
 * than admitting doubt.
 */
class FrameRepository(
    private val bridge: BridgeClient,
    private val direct: DirectTraceClient,
    private val aniList: AniListClient,
    private val history: HistoryStore,
    private val frameIndex: FrameIndexClient? = null,
    private val ranker: RankClient? = null,
    private val contentAnalyzer: ContentAnalyzer? = null,
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
        // Отпечаток считается до обращения к сети: он дешёвый и нужен индексу
        // кадров, который отвечает и когда trace.moe не знает этот кадр.
        val hash = runCatching { FrameFingerprint.dHash(bytes) }.getOrNull()

        val response = runCatching { bridge.identify(bytes, fileName, mime) }
            // 1.0.1: no bridge on the network is not a failure. trace.moe answers
            // over plain HTTPS on its own, we only lose the SauceNAO tags.
            .recoverCatching { error ->
                if (error is BridgeUnreachableException) {
                    direct.identify(bytes, fileName, mime)
                } else {
                    throw error
                }
            }
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
        val reachedBridge = response.sauce?.configured != null

        // Три независимых сигнала считаются параллельно: индекс кадров и модель
        // содержимого не зависят друг от друга, а ждать их по очереди незачем.
        val indexLookup = if (hash != null) {
            asyncOrNull { frameIndex?.lookup(hash) }
        } else null
        val content = contentAnalyzer?.let { analyzer ->
            runCatching { analyzer.analyze(bytes) }.getOrNull()
        }
        val indexMatch = indexLookup?.entry

        val indexAnilistId = indexMatch?.anilistId
        val traceAnilistId = trace?.anilist?.id

        // trace.moe может не знать кадр, а наш индекс — знать: тогда кадр опознан
        // всё равно, и это ровно тот случай, ради которого индекс существует.
        val resolvedId = when {
            traceAnilistId != null -> traceAnilistId
            indexAnilistId != null -> indexAnilistId
            else -> null
        }
        val indexTookTheAnswer = traceAnilistId == null && indexAnilistId != null

        if (resolvedId == null || trace?.matched != true && !indexTookTheAnswer) {
            return Outcome.Miss(
                EmptyResult(
                    reason = if (content?.hasPerson == false) {
                        "trace.moe не узнал этот кадр, и на нём не видно людей — скорее всего, это не кадр аниме"
                    } else {
                        "trace.moe не узнал этот кадр"
                    },
                    raw = trace?.raw,
                    searchedImage = previewDataUrl,
                    searchedImageSha256 = digest,
                )
            )
        }

        val media = runCatching { aniList.media(resolvedId) }
            .getOrElse { error -> trace?.anilist?.toFallbackMedia(error.readableMessage()) ?: mediaStub(resolvedId) }

        val sauce = response.sauce
        val verdict = decide(
            traceId = traceAnilistId,
            indexMatch = indexMatch,
            sauce = sauce,
            labels = content?.labels.orEmpty(),
            trace = trace,
            reachedBridge = reachedBridge,
        )
        val guess = pickCharacter(media.characters, sauce?.characters.orEmpty(), sauce?.tags.orEmpty())
        val frameHit = FrameHit(
            engine = when {
                indexTookTheAnswer -> "индекс кадров AMPS"
                else -> trace?.engine ?: "trace.moe"
            },
            episode = trace?.episode ?: indexMatch?.episode,
            frame = trace?.frame,
            timestamp = trace?.timestamp ?: indexMatch?.timestampSec?.toDouble(),
            similarity = trace?.similarity,
            sceneUrl = trace?.video?.url,
        )

        val similarArt = buildList {
            if (sauce?.matched == true) {
                // Every SauceNAO hit becomes a "similar image" row, best first.
                val candidates = sauce.results.ifEmpty {
                    listOf(
                        dev.amps.app.data.model.SauceCandidate(
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
            sources = buildSources(frameHit.engine, media, sauce?.source, indexTookTheAnswer),
            rawEngineText = trace?.raw,
            searchedImage = previewDataUrl,
            searchedImageSha256 = digest,
            verdict = verdict,
            content = content,
            wiki = loadWiki(resolvedId),
            rosterCharacters = loadRoster(resolvedId, "characters"),
            rosterPlaces = loadRoster(resolvedId, "places"),
            frameHash = hash,
        )

        history.add(
            HistoryEntry(
                id = "frame-$resolvedId-${System.currentTimeMillis()}",
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
                    anilistId = resolvedId,
                    episode = frameHit.episode,
                    timestamp = frameHit.timestamp,
                    similarity = trace?.similarity,
                    characterName = guess?.character?.displayName,
                    engine = frameHit.engine,
                ),
            )
        )

        // Кадр, который trace.moe подтвердил, отдаём в общий индекс. Это единственный
        // источник кадров, доступный нам легально: пользователь сам принёс файл
        // и сам увидел, что это за серия.
        if (trace?.matched == true && hash != null) {
            contributeFrame(
                hash = hash,
                anilistId = resolvedId,
                title = media.title.best ?: media.title.romaji.orEmpty(),
                episode = frameHit.episode,
                timestamp = frameHit.timestamp,
                source = "trace.moe",
                imageUrl = frameHit.previewImageUrl,
            )
        }

        return Outcome.Found(page)
    }

    /**
     * 1.0.2: отдаёт мосту только что опознанный кадр, чтобы индекс кадров рос.
     *
     * Автоматически наполнить индекс нечем: на фандомах у картинок нет лицензий,
     * а в Internet Archive нет AniList id. Зато у пользователя есть кадры, и он
     * сам решает, что это — его кадры и есть тот самый корпус, которого
     * trace.moe не знает. Ошибка здесь не важна: индекс — это ускорение, а не
     * условие работы поиска.
     */
    private suspend fun contributeFrame(
        hash: String?,
        anilistId: Int,
        title: String,
        episode: Int?,
        timestamp: Double?,
        source: String,
        imageUrl: String?,
    ) {
        if (hash == null) return
        runCatching {
            frameIndex?.remember(
                hash = hash,
                anilistId = anilistId,
                seriesTitle = title,
                episode = episode,
                timestampSec = timestamp?.toFloat(),
                source = source,
                imageUrl = imageUrl,
            )
        }
    }

    /**
     * Отправляет сигналы мосту на взвешивание. Мост недоступен — вердикт строится
     * локально из одного источника: тогда уверенность намеренно низкая, потому
     * что подтвердить догадку нечем.
     */
    private suspend fun decide(
        traceId: Int?,
        indexMatch: IndexMatch?,
        sauce: SauceResult?,
        labels: List<ContentLabel>,
        trace: TraceResult?,
        reachedBridge: Boolean,
    ): FrameVerdict {
        val request = RankRequest(
            trace = trace?.let {
                TraceSignal(
                    matched = it.matched == true,
                    anilistId = it.anilist?.id,
                    episode = it.episode,
                    timestamp = it.timestamp?.toFloat(),
                    similarity = it.similarity?.toFloat(),
                )
            },
            sauce = sauce?.let {
                SauceSignal(
                    configured = it.configured == true,
                    similarity = it.similarity?.toFloat(),
                    source = it.source,
                    characters = it.characters,
                    tags = it.tags,
                )
            },
            index = indexMatch?.let {
                IndexSignal(
                    matched = true,
                    anilistId = it.anilistId,
                    episode = it.episode,
                    timestamp = it.timestampSec,
                    distance = it.distance,
                )
            },
            labels = labels,
        )

        val result = ranker?.rank(request)
        if (result != null) {
            return FrameVerdict(
                decision = result.decision,
                confidence = result.confidence,
                reasons = result.reasons,
                warnings = result.warnings,
                candidates = result.candidates.map {
                    RankedCandidate(
                        anilistId = it.anilistId,
                        title = it.title,
                        score = it.score,
                        sources = it.sources,
                        why = it.why,
                    )
                },
                agreedSources = result.primary?.agreement.orEmpty(),
            )
        }

        // Мост недоступен: один источник не может дать уверенный ответ.
        val id = traceId ?: indexMatch?.anilistId
        if (id == null) return FrameVerdict(decision = "rejected")
        val similarity = trace?.similarity?.toFloat() ?: 0f
        return FrameVerdict(
            decision = "uncertain",
            confidence = (similarity * 0.55f).coerceIn(0f, 1f),
            reasons = listOf(
                if (reachedBridge) {
                    "Мост не ответил на запрос ранжирования — оценка собрана локально из одного источника."
                } else {
                    "Мост не найден в сети — оценка собрана из одного источника trace.moe."
                }
            ),
            warnings = listOf("Подтвердить результат нечем: доступен только один источник."),
            candidates = listOf(RankedCandidate(anilistId = id, title = "", score = similarity)),
            agreedSources = listOf("trace"),
        )
    }

    /** Вики серии с моста. Отсутствие вики — обычное дело, а не ошибка. */
    private suspend fun loadWiki(anilistId: Int): WikiReference? {
        val detail = runCatching { bridge.catalogAnime(anilistId) }.getOrNull()
        val wiki = detail?.wiki ?: return null
        if (wiki.url == null && wiki.slug == null) return null
        return WikiReference(
            slug = wiki.slug,
            url = wiki.url,
            intro = wiki.intro,
            images = wiki.images,
        )
    }

    private suspend fun loadRoster(anilistId: Int, kind: String): List<RosterEntry> {
        val detail = runCatching { bridge.catalogAnime(anilistId) }.getOrNull() ?: return emptyList()
        val rows = if (kind == "places") detail.places else detail.characters
        return rows.mapNotNull { row ->
            val name = row.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RosterEntry(name = name, url = row.url, qid = row.qid, source = row.source)
        }
    }

    /** Оборачивает подозрительный вызов так, чтобы его провал не ронял весь поиск. */
    private suspend fun <T> asyncOrNull(block: suspend () -> T): T? =
        runCatching { block() }.getOrNull()

    /** Re-opens a stored entry: only the AniList part has to be fetched again. */
    suspend fun reload(anilistId: Int, stored: StoredFrame): AnimeWikiPage {
        val media = aniList.media(anilistId)
        return AnimeWikiPage(
            media = media,
            frame = stored.toFrameHit(),
            candidates = media.characters,
            sources = buildSources(stored.engine, media, null, false),
        )
    }

    private fun buildSources(
        engine: String,
        media: AnimeMedia,
        sauceSource: String?,
        fromIndex: Boolean,
    ): List<SourceRef> = buildList {
        add(SourceRef(if (fromIndex) "индекс кадров AMPS" else engine, "https://trace.moe", "поиск кадра по изображению"))
        add(SourceRef("AniList", media.anilistUrl, "описание серии и персонажей"))
        media.malUrl?.let { add(SourceRef("MyAnimeList", it, "справочник серии")) }
        media.anidbUrl?.let { add(SourceRef("AniDB", it, "таймкоды и эпизоды")) }
        if (sauceSource != null) {
            add(SourceRef(sauceSource, null, "художественные совпадения по тегам"))
        }
    }

    /** Заглушка на случай, когда AniList недоступен и trace.moe не отдал название. */
    private fun mediaStub(anilistId: Int): AnimeMedia = AnimeMedia(id = anilistId)

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
