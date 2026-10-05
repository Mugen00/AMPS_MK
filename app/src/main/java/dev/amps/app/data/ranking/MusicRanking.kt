package dev.amps.app.data.ranking

import dev.amps.app.data.model.FreeTrack
import dev.amps.app.data.model.LicenceBadge
import dev.amps.app.data.model.LicenceSummary
import dev.amps.app.data.model.MatchTier
import dev.amps.app.data.model.MusicHit
import dev.amps.app.data.model.MusicHitKind
import dev.amps.app.data.model.MusicLicense
import dev.amps.app.data.model.MusicSearchResult
import dev.amps.app.data.model.MusicSource
import dev.amps.app.data.model.musicNormalize
import java.util.Locale

/**
 * Разобранный поисковый запрос.
 *
 * Человек пишет «Daft Punk — One More Time» и «One More Time» одинаково часто, а
 * иногда называет только группу. Одна строка поэтому разбирается на два смысла
 * сразу — название и исполнителя, — и сверяется с обоими. Это честнее, чем
 * угадывать по позиции слова: ошибка в разборе убрала бы из выдачи именно тот
 * трек, который человек искал.
 */
class MusicQuery private constructor(
    val raw: String,
    /** Нормализованное название; пусто, если в запросе названия нет. */
    private val titleKey: String,
    /** Нормализованный исполнитель; пусто, если человек его не назвал. */
    private val artistKey: String,
    private val artistGiven: Boolean,
) {
    /**
     * Что запрос значит «в целом». При виде «исполнитель — название» это
     * название, иначе — вся строка. Именно с ним сравнивается точное совпадение,
     * поэтому «Daft Punk One More Time» находит «One More Time».
     */
    private val wholeKey: String = titleKey.ifEmpty { artistKey }
    private val queryTokens: Set<String> = tokensOf(wholeKey)

    /**
     * Насколько строка соответствует запросу.
     *
     * Порядок проверок и есть ответ на вопрос «почему сверху именно это»:
     * сначала точное название — человек назвал то, что искал; затем исполнитель —
     * возможно, он назвал группу вместо трека; и только потом частичное
     * совпадение, где совпали отдельные слова, а не смысл.
     */
    fun tierFor(title: String?, artist: String?, genre: String? = null): MatchTier {
        if (wholeKey.isEmpty()) return MatchTier.NONE
        val t = musicNormalize(title.orEmpty())
        val a = musicNormalize(artist.orEmpty())
        val g = musicNormalize(genre.orEmpty())

        // «содержит запрос целиком» вместо строгого равенства: названия у одной
        // песни различаются суффиксами (Remix, Edit, Live), и это всё тот же трек.
        val titleWhole = t.isNotEmpty() && (t == wholeKey || wholeKey in t)
        val artistWhole = a.isNotEmpty() && (a == wholeKey || wholeKey in a)
        val artistParsed = artistKey.isNotEmpty() && (a == artistKey || artistKey in a)

        if (titleWhole && (artistWhole || artistParsed)) return MatchTier.EXACT_TITLE_AND_ARTIST

        if (titleWhole) {
            return when {
                t == wholeKey -> MatchTier.EXACT_TITLE
                t.startsWith(wholeKey) -> MatchTier.TITLE_PREFIX
                else -> MatchTier.TITLE_CONTAINS
            }
        }

        if (artistWhole) {
            return if (a == wholeKey) MatchTier.ARTIST_EXACT else MatchTier.PARTIAL_ARTIST
        }

        // Запрос вида «исполнитель — название»: половины сравниваются отдельно.
        if (artistGiven && artistParsed) {
            // «Daft Punk feat. Pharrell Williams» — это всё тот же исполнитель,
            // поэтому начало имени засчитывается как точное совпадение.
            return if (a == artistKey || a.startsWith(artistKey)) MatchTier.ARTIST_EXACT
            else MatchTier.PARTIAL_ARTIST
        }

        // Последний довод: совпали отдельные слова. Исполнитель проверяется на
        // тех же словах запроса, потому что «Daft Punk» — это тоже запрос, и
        // иначе группа никогда не нашлась бы по своему имени. Жанр идёт после
        // названия и исполнителя: он находит «подходящее», а не «то самое».
        return when {
            overlap(t) -> MatchTier.PARTIAL_TITLE
            overlap(a) -> MatchTier.PARTIAL_ARTIST
            overlap(g) -> MatchTier.GENRE_MATCH
            else -> MatchTier.NONE
        }
    }

    override fun toString(): String = raw

    private fun overlap(haystack: String): Boolean =
        queryTokens.isNotEmpty() && haystack.isNotEmpty() &&
            tokensOf(haystack).any { it in queryTokens }

    companion object {
        fun of(raw: String): MusicQuery {
            val clean = raw.trim()
            val parts = clean.split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
            return when {
                parts.size >= 2 -> MusicQuery(clean, titleKey = parts[1], artistKey = parts[0], artistGiven = true)
                else -> MusicQuery(clean, titleKey = clean, artistKey = "", artistGiven = false)
            }
        }

        /**
         * Тире обязательно окружено пробелами — иначе «Spider-Man» и «AC-DC»
         * распались бы пополам и поиск превращался бы в мусор.
         */
        private val SPLIT = Regex("\\s+[\\-–—~]+\\s+")

        /** Слова короче трёх букв в частичном совпадении не участвуют. */
        private const val MIN_TOKEN_LEN = 3

        private val LETTERS = Regex("[\\p{L}\\p{N}]+")

        private fun tokensOf(key: String): Set<String> =
            if (key.isEmpty()) emptySet() else key.split(LETTERS).filter { it.length >= MIN_TOKEN_LEN }.toSet()
    }
}

/**
 * Собирает из нескольких источников один список и раскладывает его по полезности.
 *
 * **Порядок задаёт не «кто первый ответил», а что человек сможет с этим
 * сделать.** Раньше строки склеивались в порядке ответа, и Jamendo с полным
 * треком оказывался ниже двадцати строк iTunes, из которых скачать нельзя
 * ничего. Теперь сверху всегда то, что можно забрать.
 *
 * Сравнение идёт по [MusicHit.score], где веса разнесены по непересекающимся
 * полосам, поэтому порядок читается с одного взгляда:
 *
 * ```
 * 1000  файл, а не описание          <- главное: пользователь хочет музыку
 *  100  точное совпадение названия   <- второе: чужой трек тоже бесполезен
 *    5  лицензия (CC0 выше CC BY-NC)
 *    4  площадка (надёжность выдачи)
 * ```
 *
 * Бонус за лицензию и площадку зажат так, чтобы суммарно не дотянуть до
 * [MatchTier.MIN_TIER_GAP]: редкая лицензия не должна перепрыгивать ступень
 * совпадения, потому что точное название важнее красивой метки.
 *
 * Сортировка стабильная, поэтому при равных весах строки сохраняют порядок
 * источников — список не «прыгает» между двумя одинаковыми запусками поиска.
 */
object MusicRanker {

    /** Полоса «можно скачать». Больше максимума любой другой полосы. */
    private const val FILE_BAND = 1_000

    /** Ступени ниже этого порога — шум, а не совпадение; в выдачу они не идут. */
    private val MIN_TIER = MatchTier.PARTIAL_ARTIST

    fun rank(query: MusicQuery, hits: List<MusicHit>, limit: Int): List<MusicHit> {
        if (hits.isEmpty()) return emptyList()
        return hits
            .map { hit ->
                val tier = query.tierFor(hit.title, hit.artist, hit.genre)
                hit.copy(match = tier, score = scoreOf(hit, tier))
            }
            .filter { it.match.weight >= MIN_TIER.weight }
            .sortedByDescending { it.score }
            .take(limit.coerceAtLeast(0))
    }

    private fun scoreOf(hit: MusicHit, tier: MatchTier): Int {
        val kindBonus = if (hit.kind == MusicHitKind.FILE) FILE_BAND else 0
        return kindBonus + tier.weight + licenceBonus(hit.license) + sourceBonus(hit.source)
    }

    /**
     * Насколько лицензия удобна, а не насколько строга.
     *
     * Порядок такой: CC0 не требует ничего, CC BY требует подписи, CC BY-NC
     * запрещает коммерческое использование. Неизвестная лицензия стоит ниже всех,
     * даже ниже NC, — по правилам приложения она закрывает файл вовсе, и ставить
     * её выше рабочей лицензии значило бы показывать заведомо мёртвую строку.
     */
    private fun licenceBonus(license: MusicLicense): Int = when {
        !license.isKnown -> 0
        license.isPublicDomain -> 5
        license.nonCommercial -> 1
        license.noDerivatives -> 2
        else -> 3
    }

    /**
     * Надёжность площадки: сколько раз источник подтвердил, что отдаёт то,
     * что обещает.
     *
     * Jamendo получает максимум как единственный живой источник полных треков;
     * ccMixter почти обнулён, потому что площадка не отвечает, но не обнулён
     * полностью — его строки, если они всё же пришли, настоящие.
     */
    @Suppress("DEPRECATION")
    private fun sourceBonus(source: MusicSource): Int = when (source) {
        MusicSource.JAMENDO -> 4
        MusicSource.INTERNET_ARCHIVE, MusicSource.OPENVERSE -> 3
        MusicSource.MUSICBRAINZ, MusicSource.CCMIXTER -> 2
        MusicSource.ITUNES -> 1
        else -> 0
    }

    // --- построение строк ----------------------------------------------------

    /**
     * Свободный трек → строка выдачи.
     *
     * Теги источника идут в [MusicHit.genre] одной строкой: площадки ищут по тегам
     * («ambient», «chiptune»), и без них запрос по жанру не нашёл бы на
     * свободной музыке вообще ничего.
     */
    fun fromFreeTrack(track: FreeTrack): MusicHit = MusicHit(
        source = track.source,
        kind = MusicHitKind.FILE,
        sourceId = track.sourceId,
        title = track.title,
        artist = track.artistName,
        album = track.album,
        year = track.year,
        genre = track.tags.takeIf { it.isNotEmpty() }?.joinToString(", "),
        durationSec = track.format.durationSec,
        coverUrl = track.coverUrl,
        license = track.license,
        pageUrl = track.pageUrl,
        freeTrack = track,
    )

    /** Запись из iTunes или MusicBrainz → строка выдачи. */
    fun fromMetadata(result: MusicSearchResult): MusicHit = MusicHit(
        source = result.source,
        kind = MusicHitKind.METADATA,
        sourceId = result.sourceId,
        title = result.title,
        artist = result.artist,
        album = result.album,
        year = result.year,
        genre = result.genre,
        durationSec = result.durationSec,
        coverUrl = result.coverUrl,
        pageUrl = result.links.firstOrNull()?.url,
        metadata = result,
    )

    // --- сводка по лицензиям --------------------------------------------------

    /**
     * Считает выдачу вместо того, чтобы описывать её вручную.
     *
     * Считается в рантайме, а не пишется текстом, потому что текст врёт: уже
     * было, что источник молчал, а подпись под списком обещала результат.
     */
    fun summarise(hits: List<MusicHit>): LicenceSummary {
        val files = hits.filter { it.kind == MusicHitKind.FILE }
        val counters = LinkedHashMap<String, Int>()
        var unknown = 0
        var nc = 0
        var sa = 0
        var downloadable = 0

        files.forEach { hit ->
            val license = hit.license
            if (license.isKnown) {
                // Одна лицензия, записанная по-разному, — это одна лицензия.
                // Считаем по бейджу, иначе сводка размножится на почти одинаковые
                // строки и запутает сильнее, чем поможет.
                val key = license.badgeLabel?.uppercase(Locale.ROOT) ?: license.name.orEmpty()
                if (key.isNotBlank()) counters[key] = (counters[key] ?: 0) + 1
            } else {
                unknown++
            }
            if (license.nonCommercial) nc++
            if (license.shareAlike) sa++
            if (hit.downloadable) downloadable++
        }

        val badges = counters.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { LicenceBadge(label = it.key, count = it.value) }

        return LicenceSummary(
            fileRows = files.size,
            metadataRows = hits.size - files.size,
            downloadableRows = downloadable,
            blockedRows = unknown,
            nonCommercialRows = nc,
            shareAlikeRows = sa,
            unknownLicenceRows = unknown,
            badges = badges,
        )
    }
}