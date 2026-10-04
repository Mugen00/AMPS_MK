package dev.amps.app.data.repo

import dev.amps.app.data.local.HistoryEntry
import dev.amps.app.data.local.HistoryStore
import dev.amps.app.data.local.StoredFrame
import dev.amps.app.data.model.AnimeCharacter
import dev.amps.app.data.model.AnimeMedia
import dev.amps.app.data.model.AnimeWikiPage
import dev.amps.app.data.model.AppearanceLexicon
import dev.amps.app.data.model.CharacterGuess
import dev.amps.app.data.model.CharacterTagName
import dev.amps.app.data.model.EmptyResult
import dev.amps.app.data.model.ExternalArt
import dev.amps.app.data.model.FrameContent
import dev.amps.app.data.model.FrameHit
import dev.amps.app.data.model.SearchLink
import dev.amps.app.data.model.SourceRef
import dev.amps.app.data.ranking.RankingEngine
import dev.amps.app.data.remote.AniListClient
import dev.amps.app.data.remote.AppearanceSearchClient
import dev.amps.app.data.remote.IqdbClient
import dev.amps.app.data.remote.TraceMoeClient
import dev.amps.app.data.remote.WikiClient
import dev.amps.app.imaging.ContentAnalyzer
import dev.amps.app.imaging.FrameFingerprint
import dev.amps.app.util.readableMessage
import dev.amps.app.util.sha256
import java.util.UUID

/**
 * Превращает одну картинку в вики-страницу.
 *
 * **С 1.0.5 внешний источник ровно один — IQDB.** Он ищет по иллюстрациям,
 * скриншотам и фотографиям: то есть по тому, что реально имеет смысл искать, а
 * не по кадрам видеозаписей. AniList отвечает на второй вопрос — «кто это и
 * что это за произведение»: по бо́ру-тегам `belgium_(hetalia)` он находит
 * персонажа, а по персонажу — серию. Вики фандома отвечает на третий — «что
 * этот персонаж вообще собой представляет».
 *
 * **С 1.0.6 добавился второй источник — локальный.** Аниме-тегер на телефоне
 * даёт собственные бо́ру-теги, и на них работает **та же** цепочка, что и на
 * тегах IQDB: `AniListClient.searchCharacters` → лучший по `favourites` → его
 * `appearances[0]` (сортировка `POPULARITY_DESC`) → `media(id)` = серия.
 * Тегер не заменяет IQDB, а расширяет покрытие: IQDB находит точное совпадение
 * с источником, тегер работает с любой иллюстрацией, даже не лежащей в индексе.
 *
 * **Почему цепочка именно такая.** Раньше серию называл trace.moe по совпадению
 * кадра в видеозаписи. У IQDB нет номеров эпизодов, но зато есть теги, а теги
 * персонажа почти всегда уникальны для произведения: `belgium` в AniList
 * находится ровно один, и его появления дают серию.
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
    /**
     * 1.0.6b: точный поиск кадра в базе серий.
     *
     * На скриншотах аниме IQDB отвечает «no relevant matches» — он индексирует
     * бо́ру-арты. trace.moe сравнивает хеши кадров и потому единственный, кто
     * отвечает на вопрос «это кадр из серии, из какой».
     */
    private val traceMoe: TraceMoeClient? = null,
    /** 1.0.6b: собирает поисковый запрос по описанию внешности. */
    private val appearanceSearch: AppearanceSearchClient? = null,
) {

    suspend fun identify(
        bytes: ByteArray,
        fileName: String,
        mime: String,
        previewDataUrl: String? = null,
    ): Outcome {
        val digest = bytes.sha256()
        // Отпечаток остаётся полезным: он попадает в карточку и позволяет
        // отличить «ту же самую картинку» от «похожей на неё».
        val hash = runCatching { FrameFingerprint.dHash(bytes) }.getOrNull()

        // Модель содержимого считает локально и ни о чём внешнем не знает,
        // поэтому запускаем её вместе с IQDB, а не по очереди: два запроса
        // подряд — это две задержки в ряд. В первый запуск модель копируется
        // из assets в файлы приложения, это дольше обычного.
        val content = contentAnalyzer?.let { analyzer ->
            runCatching { analyzer.analyze(bytes) }.getOrNull()
        }

        val iqdbCall = runCatching { iqdb?.search(bytes, fileName, mime) }
        val found = iqdbCall.getOrNull()
        val iqdbError = iqdbCall.exceptionOrNull()?.readableMessage()?.takeIf { iqdb != null }

        // trace.moe и IQDB идут вместе, а не по очереди: оба смотрят на ту же
        // картинку, оба ходят в сеть, и последовательный вызов удвоил бы
        // ожидание. trace.moe для этого и нужен — на скриншотах аниме IQDB
        // не находит ничего.
        val traceCall = runCatching { traceMoe?.search(bytes) }
        val frameMatch = traceCall.getOrNull()?.takeIf {
            it.confidence != TraceMoeClient.Confidence.UNUSABLE
        }

        // Пока тегер не спросил AniList, у гипотез персонажей нет ни канонического
        // имени, ни портрета. Уточняем их здесь же, последовательно: запросы
        // к AniList идут по первых трём тегам, остальные остаются такими, какие
        // есть, — и это честнее, чем молча подставлять перевод вместо имени.
        val tagged = content?.let { resolveHypotheses(it) }

        // 1.0.6b: имя персонажа от тегера показывается только если его
        // подтвердил кто-то снаружи.
        //
        // Замеры на восьми скриншотах аниме: IQDB на них отвечает «no relevant
        // matches», а тегер выдаёт чужих персонажей с уверенностью выше
        // порога — то есть говорит уверенно и неверно. Это хуже, чем молчать.
        // Теперь имена остаются только когда серию назвал внешний источник:
        // тогда гипотезу можно сверить с найденным и она перестаёт быть
        // выдумкой. Само описание внешности — можно всегда: там модель не
        // ошибается.
        val confirmedExternally = frameMatch != null || found?.hits?.isNotEmpty() == true
        val vetted = tagged?.let { if (confirmedExternally) it else it.withoutCharacters() }

        // Подсказка для поиска собирается всегда, независимо от того, названа
        // серия или нет: при промахе это единственное, что остаётся в руках
        // у человека.
        val searchHint = vetted?.let { attachSearch(it) } ?: vetted

        // Порядок источников неслучаен: теги IQDB описывают найденную в базе
        // картинку и потому надёжнее, а теги тегера — предположение о той
        // картинке, которую приложение получило. Поэтому тегер идёт последним
        // и только тогда, когда по тегам источника серия не назвалась.
        //
        // 1.0.6b: тегеру отказано в праве называть серию, если картинку не нашёл
        // внешний источник. Просто скрыть имена недостаточно: по тем же
        // выдуманным тегам AniList вернул бы серию, и карточка открылась бы с
        // уверенным видом. То же враньё, только без подписи.
        val seriesTags = if (found?.hits?.isNotEmpty() == true) tagged else null
        val resolved = resolve(found, seriesTags, frameMatch)

        if (resolved?.media == null) {
            return Outcome.Miss(
                EmptyResult(
                    reason = missReason(found, iqdbError, tagged),
                    raw = found?.let { describe(it) },
                    searchedImage = previewDataUrl,
                    searchedImageSha256 = digest,
                    // Промах не должен быть тупиком: отдаём описание внешности
                    // и собранный из него запрос.
                    content = searchHint,
                )
            )
        }

        val media = resolved.media
        val fromIqdb = resolved.via == Via.Iqdb

        // 1.0.6b: серия, названная по кадру, не достаётся ни IQDB, ни тегеру.
        //
        // Если оставить как было, движок рейтинга увидит `fromIqdb == false` и
        // запишет, что серию назвал тегер на телефоне. Тегер этого не делал и
        // ничего об этой серии не знает — объяснение оказалось бы выдуманным,
        // а именно за такое приложение и ругали.
        val creditedElsewhere = fromIqdb || resolved.via == Via.TraceMoe

        val verdict = RankingEngine.rank(
            RankingEngine.Signals(
                iqdb = found?.let { result ->
                    RankingEngine.IqdbResult(
                        hits = result.hits.map { it.toRankingHit() },
                        exactMatchFound = result.exactMatchFound,
                        scannedImages = result.scannedImages,
                        // Серия названа не по IQDB — вклад тут нулевой, и
                        // выдавать чужой вывод за его было бы враньём.
                        resolvedMediaId = media.id.takeIf { fromIqdb },
                        resolvedMediaTitle = media.title.best.takeIf { fromIqdb },
                        resolvedVia = resolved.via.label.takeIf { fromIqdb },
                        resolvedViaTag = resolved.viaTag.takeIf { fromIqdb },
                        confirmedCharacter = resolved.character?.displayName.takeIf { fromIqdb },
                    )
                },
                iqdbError = iqdbError,
                tagger = RankingEngine.TaggerResult(
                    available = tagged?.analyzed == true,
                    hasPerson = tagged?.hasPerson == true,
                    characters = tagged?.characters.orEmpty().map {
                        RankingEngine.TaggerTag(it.tag, it.probability)
                    },
                    resolvedMediaId = media.id.takeUnless { creditedElsewhere },
                    resolvedMediaTitle = media.title.best.takeUnless { creditedElsewhere },
                    resolvedViaTag = resolved.viaTag.takeUnless { creditedElsewhere },
                    confirmedCharacter = resolved.character?.displayName.takeUnless { creditedElsewhere },
                ),
            )
        )

        val guess = resolved.character?.let {
            CharacterGuess(
                character = it,
                score = 1.0,
                reason = if (fromIqdb) {
                    "имя «${resolved.viaTag}» взято из тегов источника и найдено в AniList; " +
                        "серия взята из его появлений"
                } else {
                    "имя «${resolved.viaTag}» распознала модель на телефоне, AniList его подтвердил; " +
                        "серия взята из его появлений, но совпадения картинки в базах не было"
                },
            )
        }

        val frameHit = found?.best?.let { hit ->
            FrameHit(
                source = hit.source,
                similarityPercent = hit.similarity,
                url = hit.url,
                exactMatch = hit.isBest,
            )
        }

        val similarArt = found?.hits.orEmpty().map { art ->
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
            sources = buildSources(media, found, resolved.frameMatch),
            rawEngineText = found?.let { describe(it) },
            searchedImage = previewDataUrl,
            searchedImageSha256 = digest,
            verdict = verdict,
            content = searchHint,
            wiki = wikiInfo?.wiki,
            rosterCharacters = wikiInfo?.characters.orEmpty(),
            rosterPlaces = wikiInfo?.places.orEmpty(),
            frameHash = hash,
            // 1.0.6b: найденный кадр едет в страницу вместе с серией. Без него
            // интерфейс не может показать доказательство, по которому серия вообще
            // названа: имя файла, эпизод, секунду и сходство.
            frameMatch = resolved.frameMatch,
        )

        history.add(
            HistoryEntry(
                id = "frame-${media.id}-${System.currentTimeMillis()}",
                kind = "FRAME",
                title = media.title.best ?: "Без названия",
                subtitle = buildString {
                    append("найдено по картинке")
                    frameHit?.similarityPercent?.let { append(" · совпадение $it %") }
                    guess?.character?.displayName?.let { append(" · $it") }
                },
                imageUrl = media.cover,
                createdAt = System.currentTimeMillis(),
                frame = StoredFrame(
                    anilistId = media.id,
                    similarity = frameHit?.similarityPercent?.let { it / 100.0 },
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

    private fun buildSources(
        media: AnimeMedia,
        found: IqdbClient.SearchResult?,
        frameMatch: TraceMoeClient.Match? = null,
    ): List<SourceRef> = buildList {
        add(SourceRef(IQDB_LABEL, "https://iqdb.org/", "обратный поиск по картинке: Danbooru, Konachan, Gelbooru, Sankaku и другие"))
        // 1.0.6b: trace.moe указан источником, когда серия названа по кадру, —
        // иначе в списке источников не видно, откуда взялось название.
        if (frameMatch != null) {
            add(
                SourceRef(
                    "trace.moe",
                    "https://trace.moe/?anilist=" + frameMatch.anilistId,
                    "поиск кадра в базе серий; сходство %.0f %%".format(frameMatch.similarity * 100f),
                )
            )
        }
        add(SourceRef("AniList", media.anilistUrl, "описание серии и персонажей"))
        media.malUrl?.let { add(SourceRef("MyAnimeList", it, "справочник серии")) }
        media.anidbUrl?.let { add(SourceRef("AniDB", it, "таймкоды и эпизоды")) }
        found?.best?.let { add(SourceRef(it.source, it.url, "страница найденной картинки")) }
    }

    /**
     * Ищет серию и персонажа по бо́ру-тегам — сначала IQDB, потом тегера.
     *
     * Порядок внутри IQDB неслучаен: тег персонажа уникальнее тега серии, а
     * серия, взятая из появлений персонажа, точнее, чем поиск по названию.
     * Сначала пробуем персонажа, и только если он не нашёлся — название серии.
     *
     * Теги тегера идут **последними** и только когда по IQDB не вышло: их
     * описывает модель, а не база, по которой что-то нашлось. Проверка идёт
     * по первым [TAGGER_CHARACTER_QUERIES] тегам — по замерам остальные обычно
     * мусорные, а каждый запрос к AniList стоит времени.
     */
    private suspend fun resolve(
        found: IqdbClient.SearchResult?,
        tagged: FrameContent?,
        frameMatch: TraceMoeClient.Match? = null,
    ): Resolved? {
        val hits = found?.hits.orEmpty()

        // 1.0.6b: trace.moe проверяется первым, раньше IQDB и раньше тегера.
        //
        // Причина — в том, что он сравнивает. IQDB ищет похожую картинку в
        // бо́ру-базах; тегер угадывает персонажа по нарисованному. Оба
        // смотрят на рисунок и оба проходят мимо скриншота из серии.
        // trace.moe сравнивает хеши кадров, и поэтому на скриншоте отвечает
        // там, где остальные молчат.
        if (frameMatch != null) {
            val media = runCatching { aniList.media(frameMatch.anilistId) }.getOrNull()
            if (media != null) {
                return Resolved(
                    media = media,
                    character = null,
                    // Доверие к источнику ниже, чем у точного совпадения
                    // IQDB: см. Confidence в TraceMoeClient.
                    via = Via.TraceMoe,
                    viaTag = frameMatch.sourceFilename,
                    frameMatch = frameMatch,
                )
            }
        }

        // Теги берём со всех совпадений, а не только с лучшего: у лучшего
        // источника теги иногда обрезаны, а у второго-третьего они есть.
        val characterTags = tagsOf(hits) { it.characterTags }
        val seriesTags = tagsOf(hits) { it.seriesTags }

        for (tag in characterTags) {
            val character = searchCharacter(tag) ?: continue
            val mediaRef = character.appearances.firstOrNull() ?: continue
            val media = runCatching { aniList.media(mediaRef.id) }.getOrNull() ?: continue
            return Resolved(media = media, character = character, via = Via.Iqdb, viaTag = tag)
        }

        for (tag in seriesTags) {
            val found = runCatching { aniList.searchMedia(tag, MEDIA_LIMIT) }.getOrNull().orEmpty()
            val pick = found.maxByOrNull { (it.favourites ?: 0) + (it.popularity ?: 0) } ?: continue
            val media = runCatching { aniList.media(pick.id) }.getOrNull() ?: continue
            return Resolved(media = media, character = null, via = Via.Iqdb, viaTag = tag)
        }

        // Тегера спрашиваем только теперь. Имя бо́ру-тега приводится к виду,
        // который понимает поиск AniList, — см. [CharacterTagName].
        for (tag in tagged?.characterQueryTags().orEmpty()) {
            val character = searchCharacter(tag) ?: continue
            val mediaRef = character.appearances.firstOrNull() ?: continue
            val media = runCatching { aniList.media(mediaRef.id) }.getOrNull() ?: continue
            return Resolved(media = media, character = character, via = Via.Tagger, viaTag = tag)
        }
        return null
    }

    /**
     * Уточняет гипотезы персонажей именами из AniList.
     *
     * Модель отдаёт бо́ру-строки, а пользователю показывать `rem_(re:zero)`
     * вместо «Rem» — значит отдать ему служебную разметку базы. Поэтому по
     * первым [TAGGER_CHARACTER_QUERIES] тегам идёт тот же запрос, что и при
     * поиске серии, и найденный персонаж заменяет гипотезу именем, портретом
     * и серией.
     *
     * Остальные гипотезы остаются неуточнёнными — и подписываются так и в
     * интерфейсе. Дописывать к ним перевод значило бы выдавать догадку за имя.
     */
    /**
     * 1.0.6b: дописывает к содержимому поисковый запрос по внешности.
     *
     * Собирается из тех же фраз, что показывает описание, но в порядке от
     * редких признаков к частым: сочетание одежды и цвета глаз встречается в
     * десятки раз реже, чем просто «зелёные глаза», а поисковики весят слова
     * слева направо. Если словарь не дал ни одной подходящей фразы, запрос не
     * собирается вовсе — пустая кнопка «искать» хуже, чем её отсутствие.
     */
    private fun attachSearch(content: FrameContent): FrameContent {
        val builder = appearanceSearch ?: return content
        val tags = content.appearance.map { it.tag to it.probability }
        val clauses = AppearanceLexicon.clauses(tags)
        val query = builder.buildQuery(clauses.map { it.group.title to it.items }) ?: return content
        return content.copy(
            searchQuery = query,
            searchLinks = builder.links(query).map { SearchLink(it.engine, it.url) },
        )
    }

    /**
     * 1.0.6b: убирает имена персонажей, оставляя описание внешности.
     *
     * Описание модель определяет верно — это единственное, в чём тегеру можно
     * верить на скриншотах аниме. Имена же на скриншотах он называет
     * уверенно и неверно, поэтому наружу они не идут.
     */
    private fun FrameContent.withoutCharacters(): FrameContent =
        copy(characters = emptyList())

    private suspend fun resolveHypotheses(content: FrameContent): FrameContent {
        val resolved = content.characters
            .take(TAGGER_CHARACTER_QUERIES)
            .mapNotNull { hypothesis ->
                val query = CharacterTagName.normalize(hypothesis.tag) ?: return@mapNotNull null
                val character = searchCharacter(query) ?: return@mapNotNull null
                hypothesis.copy(
                    name = character.displayName ?: hypothesis.name,
                    anilistId = character.id.takeIf { it > 0 },
                    image = character.image,
                    mediaTitle = character.appearances.firstOrNull()?.title?.best,
                )
            }
            .associateBy { it.tag }

        if (resolved.isEmpty()) return content
        return content.copy(characters = content.characters.map { resolved[it.tag] ?: it })
    }

    /**
     * Один запрос к AniList за персонажем.
     *
     * `favourites` — единственная честная мера «настоящести»: строка поиска
     * AniList по короткому имени охотно отдаёт однофамильцев из других серий,
     * и без этой сортировки приложение называло бы не того.
     */
    private suspend fun searchCharacter(query: String): AnimeCharacter? =
        runCatching { aniList.searchCharacters(query, CHARACTER_LIMIT) }.getOrNull()
            .orEmpty()
            .maxByOrNull { it.favourites }

    /**
     * Имена для запросов по гипотезам тегера: первые
     * [TAGGER_CHARACTER_QUERIES] нормализованных имён, без повторов.
     */
    private fun FrameContent.characterQueryTags(): List<String> =
        characters
            .mapNotNull { CharacterTagName.normalize(it.tag) }
            .distinct()
            .take(TAGGER_CHARACTER_QUERIES)

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
     * Честный текст для пустого ответа.
     *
     * Тут три разных случая, и смешивать их нельзя: **IQDB не ответил**,
     * **IQDB ответил, но совпадений нет**, и **модель на телефоне тоже ничего
     * не нашла**. Третий — самый честный и самый полезный вывод: на не-иллюстрациях
     * оценки модели держатся у 0,50–0,53, настоящая находка даёт 0,73, порог
     * стоит на 0,60. Пользователь должен понимать, куда смотреть, а не гадать,
     * что именно сломалось.
     */
    private fun missReason(
        found: IqdbClient.SearchResult?,
        iqdbError: String?,
        tagged: FrameContent?,
    ): String = buildString {
        when {
            iqdbError != null -> append("IQDB не ответил: $iqdbError")
            found == null -> append("Источник поиска не настроен")
            else -> {
                append("IQDB просмотрел ")
                append(found.scannedImages?.toString() ?: "несколько миллионов")
                append(" изображений и не нашёл совпадений")
            }
        }

        if (iqdbError == null) {
            append(
                ". IQDB ищет по иллюстрациям и скриншотам, которые уже лежат в бо́ру-базах; " +
                    "обычное фото или скриншот из видеоигры там не лежат."
            )
        }

        when {
            tagged == null || !tagged.analyzed ->
                append(". Модель на телефоне не отработала, поэтому второго мнения о картинке не было.")
            tagged.characters.isEmpty() ->
                append(". Модель на телефоне персонажа на картинке тоже не нашла — по её замерам " +
                    "на не-иллюстрациях оценки держатся у 0,50–0,53, а настоящая находка даёт 0,73. " +
                    "Скорее всего, это вообще не иллюстрация аниме.")
            else ->
                append(". Модель на телефоне предложила персонажей (${tagged.characters.take(3).joinToString(", ") { it.name }}), " +
                    "но AniList не смог назвать по их именам ни персонажа, ни серию.")
        }
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

        /**
         * Сколько гипотез тегера уточняются именами через AniList.
         *
         * Три, а не пять: по замерам правильный персонаж стоит первым в 55,6 %
         * случаев и входит в пятёрку в 100 %, то есть третий тег почти всегда
         * уже мусорный. Пятый и четвёртый показываются такими, какие есть, с
         * честной пометкой, что имя для них не уточнялось.
         */
        const val TAGGER_CHARACTER_QUERIES = 3
    }

    /** Откуда взялась серия: от тегов источника или от тегов модели. */
    private enum class Via(val label: String) {
        Iqdb("тегам источника"),
        Tagger("тегу, распознанному моделью на телефоне"),

        /** 1.0.6b: кадр найден в базе серий, серия названа по совпадению кадра. */
        TraceMoe("кадру из базы серий"),
    }

    /** Что удалось вытащить из бо́ру-тегов и подтвердить в AniList. */
    private data class Resolved(
        val media: AnimeMedia,
        val character: AnimeCharacter?,
        val via: Via,
        /** Бо́ру-тег или нормализованное имя, по которому AniList назвал серию. */
        val viaTag: String,
        /** 1.0.6b: находка trace.moe, если серия названа по ней. */
        val frameMatch: TraceMoeClient.Match? = null,
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