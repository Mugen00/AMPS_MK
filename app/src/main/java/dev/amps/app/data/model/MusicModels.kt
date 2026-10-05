package dev.amps.app.data.model

import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * Every music row carries the source it came from, because the licence rules
 * differ per source and the UI has to be able to say "метаданные, файла нет"
 * instead of implying that a streaming catalogue hands out audio.
 */
enum class MusicSource(val label: String, val offersFile: Boolean) {
    ITUNES("iTunes", false),
    MUSICBRAINZ("MusicBrainz", false),
    /**
     * 1.0.3: площадка закрылась окончательно, API не отвечает. Значение
     * остаётся в enum, потому что в истории и в уже скачанных файлах записано
     * именно это имя — переименовывать его значило бы ломать старые записи.
     */
    @Deprecated("Площадка закрыта")
    CCMIXTER("ccMixter", true),
    INTERNET_ARCHIVE("Internet Archive", true),
    /** Единственный источник полных треков с открытой лицензией. */
    JAMENDO("Jamendo", true),
    /** Агрегатор: сам файл лежит у источника, лицензия и авторство приходят отсюда. */
    OPENVERSE("Openverse", true),
    DEEZER("Deezer", false),
    LOCAL("Свой файл", true),

    /** Not a source: the bucket for a failure raised by the app itself. */
    AMPS("AMPS", false),
    ;

    companion object {
        fun fromName(raw: String?): MusicSource? {
            val clean = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.name.equals(clean, true) || it.label.equals(clean, true) }
        }
    }
}

/** An outbound link rendered in the "источники" block of the wiki page. */
@Serializable
data class MusicLink(val label: String, val url: String? = null, val note: String? = null)

/** A single source that failed during one search, shown as a non-fatal banner. */
data class MusicSourceError(val source: MusicSource, val message: String)

data class SearchResults(
    val results: List<MusicSearchResult> = emptyList(),
    val errors: List<MusicSourceError> = emptyList(),
) {
    val isEmpty: Boolean get() = results.isEmpty()
}

data class FreeTrackResults(
    val results: List<FreeTrack> = emptyList(),
    val errors: List<MusicSourceError> = emptyList(),
) {
    val isEmpty: Boolean get() = results.isEmpty()
}

/**
 * A Creative Commons / public domain licence, reduced to the four things the
 * user actually has to comply with. [isKnown] is the single gate the UI uses
 * before it ever enables a download: an unknown licence is treated as closed.
 */
@Serializable
data class MusicLicense(
    val name: String? = null,
    val url: String? = null,
    val requiresAttribution: Boolean = false,
    val nonCommercial: Boolean = false,
    val shareAlike: Boolean = false,
    val noDerivatives: Boolean = false,
) {
    val isKnown: Boolean get() = !name.isNullOrBlank() && !url.isNullOrBlank()

    val isPublicDomain: Boolean
        get() {
            val haystack = "${name.orEmpty()} ${url.orEmpty()}".lowercase(Locale.ROOT)
            return "publicdomain" in haystack || "public domain" in haystack ||
                haystack.contains("/zero/") || haystack.contains("cc0")
        }

    /** Short badge text: `CC BY-NC-SA 4.0`, `CC0 1.0`, or null when unknown. */
    val badgeLabel: String?
        get() {
            if (!isKnown) return null
            val codes = codeSet()
            val body = when {
                isPublicDomain && ("zero" in codes || "cc0" in (name?.lowercase(Locale.ROOT) ?: "")) -> "CC0"
                isPublicDomain -> "PD"
                codes.isEmpty() -> name
                else -> "CC " + codes.joinToString("-").uppercase(Locale.ROOT)
            }
            val version = versionOf(url) ?: versionOf(name)
            return if (body.isNullOrBlank()) null else listOfNotNull(body, version).joinToString(" ")
        }

    /** The obligations as plain sentences; this block is a licence notice, not decoration. */
    val obligations: List<String>
        get() = buildList {
            if (!isKnown) {
                add("Лицензия не указана — считать файл закрытым")
                return@buildList
            }
            if (requiresAttribution) add("Требуется указание автора")
            if (nonCommercial) add("Только некоммерческое использование")
            if (shareAlike) add("При перепубликации — те же условия (share-alike)")
            if (noDerivatives) add("Производные работы запрещены (no-derivatives)")
            if (isEmpty()) add("Свободное использование без дополнительных условий")
        }

    private fun codeSet(): List<String> {
        val out = mutableListOf<String>()
        val path = url?.substringAfter("creativecommons.org/", "")?.trim('/').orEmpty()
        val parts = path.split('/').filter { it.isNotEmpty() }
        val scope = when (parts.firstOrNull()) {
            "licenses", "publicdomain" -> parts.drop(1)
            else -> emptyList()
        }
        scope.filterNot { it.firstOrNull()?.isDigit() == true }
            .forEach { segment -> segment.split('-', '+').forEach { out += it.trim().lowercase(Locale.ROOT) } }
        if (out.isEmpty()) {
            val text = name.orEmpty().lowercase(Locale.ROOT)
            if ("noncommercial" in text) out += "nc"
            if ("attribution" in text) out += "by"
            if ("sharealike" in text || "share alike" in text) out += "sa"
            if ("noderiv" in text) out += "nd"
        }
        return out.filter { it in KNOWN_CODES }
    }

    private fun versionOf(raw: String?): String? = raw
        ?.trim()
        ?.takeIf { VERSION.matches(it) }

    companion object {
        private val VERSION = Regex("\\d+(\\.\\d+)*")
        private val KNOWN_CODES = setOf("by", "nc", "sa", "nd", "pd", "zero", "splus", "s")

        /** The only honest value for "the source did not tell us". */
        val UNKNOWN = MusicLicense()

        fun localFile() = MusicLicense(
            name = "Свой файл",
            url = null,
        )

        /**
         * Builds a licence from whatever the source published. `license_url` is
         * the authoritative field on both ccMixter and archive.org; the name is
         * only a label, so it never upgrades an unknown licence into a known one.
         */
        fun fromUrl(url: String?, name: String? = null): MusicLicense {
            val cleanUrl = url?.trim()?.takeIf { it.isNotEmpty() }
            val cleanName = name?.trim()?.takeIf { it.isNotEmpty() } ?: cleanUrl?.let { guessName(it) }
            if (cleanUrl == null || cleanName == null) return UNKNOWN
            val codes = codesOf(cleanUrl, cleanName)
            return MusicLicense(
                name = cleanName,
                url = cleanUrl,
                requiresAttribution = "by" in codes || isPublicDomainUrl(cleanUrl),
                nonCommercial = "nc" in codes,
                shareAlike = "sa" in codes,
                noDerivatives = "nd" in codes,
            )
        }

        private fun codesOf(url: String, name: String): Set<String> {
            val out = mutableSetOf<String>()
            val path = url.substringAfter("creativecommons.org/", "").trim('/')
            val parts = path.split('/').filter { it.isNotEmpty() }
            val scope = when (parts.firstOrNull()) {
                "licenses", "publicdomain" -> parts.drop(1)
                else -> emptyList()
            }
            scope.filterNot { it.firstOrNull()?.isDigit() == true }
                .forEach { segment -> segment.split('-', '+').forEach { out += it.trim().lowercase(Locale.ROOT) } }
            val text = name.lowercase(Locale.ROOT)
            if ("noncommercial" in text) out += "nc"
            if ("attribution" in text) out += "by"
            if ("sharealike" in text || "share alike" in text) out += "sa"
            if ("noderiv" in text) out += "nd"
            if ("public domain" in text) out += "pd"
            if ("zero" in text) out += "zero"
            if ("sampling" in text) out += "splus"
            return out
        }

        private fun isPublicDomainUrl(url: String) =
            "publicdomain" in url.lowercase(Locale.ROOT)

        private fun guessName(url: String): String? {
            val parts = url.trimEnd('/').split('/')
            val version = parts.lastOrNull()?.takeIf { VERSION.matches(it) }
            val code = parts.dropLast(if (version != null) 1 else 0).lastOrNull() ?: return null
            return if (version != null) "$code ($version)" else code
        }
    }
}

/** Technical facts about an audio file, no matter which source they came from. */
@Serializable
data class AudioFormat(
    val durationSec: Int? = null,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val channels: String? = null,
    val mimeType: String? = null,
    val fileBytes: Long? = null,
    val bitrateKind: String? = null,
)

/**
 * A metadata-only hit (iTunes or MusicBrainz). Neither of those sources ever
 * offers a file, and the model has no audio field at all so that cannot be
 * forgotten in the UI.
 */
@Serializable
data class MusicSearchResult(
    val source: MusicSource,
    val sourceId: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val durationSec: Int? = null,
    val coverUrl: String? = null,
    val versionHint: String? = null,
    val recordingMbid: String? = null,
    val releaseMbid: String? = null,
    val artistMbid: String? = null,
    val releaseStatus: String? = null,
    val trackNo: Int? = null,
    val links: List<MusicLink> = emptyList(),
) {
    val key: String get() = "${source.name}:$sourceId"

    /** Normalised `artist + title`; the merge in the repository de-duplicates on it. */
    val dedupeKey: String get() = musicDedupeKey(artist, title)

    val hasFile: Boolean get() = false
}

/**
 * A freely licensed track that a source actually publishes a file URL for. The
 * licence is mandatory: without it the row is still shown, but
 * [downloadable] stays false and the UI says «лицензия не указана».
 */
@Serializable
data class FreeTrack(
    val source: MusicSource,
    val sourceId: String,
    val title: String,
    val artistName: String? = null,
    val album: String? = null,
    val year: Int? = null,
    val license: MusicLicense = MusicLicense.UNKNOWN,
    val audioUrl: String? = null,
    val pageUrl: String? = null,
    val authorUrl: String? = null,
    val coverUrl: String? = null,
    val format: AudioFormat = AudioFormat(),
    val sha1: String? = null,
    val md5: String? = null,
    val tags: List<String> = emptyList(),
) {
    val key: String get() = "${source.name}:$sourceId"

    val dedupeKey: String get() = musicDedupeKey(artistName, title)

    /** The one gate in front of every download. */
    val downloadable: Boolean get() = license.isKnown && !audioUrl.isNullOrBlank()

    val licenceNote: String?
        get() = if (license.isKnown) null else "Лицензия не указана — файл закрыт для скачивания"
}

/** State of one in-flight (or finished) download, surfaced to the list row. */
@Serializable
enum class DownloadState { RUNNING, DONE, FAILED }

data class DownloadProgress(
    val trackKey: String,
    val label: String,
    val bytesRead: Long = 0L,
    val totalBytes: Long? = null,
    val state: DownloadState = DownloadState.RUNNING,
    val message: String? = null,
) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let { (bytesRead.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
}

/**
 * What the user picked, so the wiki screen can be opened from a search row, a
 * free-track row, the local library or the search history.
 */
sealed interface TrackTarget {
    val id: String

    data class Metadata(val result: MusicSearchResult) : TrackTarget {
        override val id: String get() = "meta:${result.key}"
    }

    data class Free(val track: FreeTrack) : TrackTarget {
        override val id: String get() = "free:${track.key}"
    }

    data class Local(val entry: AudioLibraryEntry) : TrackTarget {
        override val id: String get() = "local:${entry.id}"
    }
}

/**
 * A file that lives in the app: either downloaded from a CC source (with the
 * licence it was published under) or imported by the user with the Storage
 * Access Framework. Persisted as `filesDir/audio/library.json`, which is also
 * where the user-editable licence note lives.
 */
@Serializable
data class AudioLibraryEntry(
    val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val fileName: String,
    val sha256: String,
    val fileBytes: Long = 0L,
    val mimeType: String? = null,
    val format: AudioFormat = AudioFormat(),
    val source: MusicSource = MusicSource.LOCAL,
    val sourceId: String? = null,
    val licenseName: String? = null,
    val licenseUrl: String? = null,
    val attributionNote: String? = null,
    val pageUrl: String? = null,
    val authorUrl: String? = null,
    val sourceSha1: String? = null,
    val sourceMd5: String? = null,
    val coverFile: String? = null,
    val addedAt: Long = 0L,
) {
    val license: MusicLicense
        get() = when {
            !licenseUrl.isNullOrBlank() -> MusicLicense.fromUrl(licenseUrl, licenseName)
            source == MusicSource.LOCAL -> MusicLicense.localFile()
            else -> MusicLicense.UNKNOWN
        }

    /** True only when the file may be used and the terms are actually known. */
    val licenceKnown: Boolean get() = !licenseUrl.isNullOrBlank()
}

/** Everything the Track Wiki screen renders. */
data class TrackWikiPage(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val versionHint: String? = null,
    val source: MusicSource = MusicSource.LOCAL,
    val coverUrl: String? = null,
    val coverFile: String? = null,
    val license: MusicLicense = MusicLicense.UNKNOWN,
    val attributionNote: String? = null,
    val format: AudioFormat = AudioFormat(),
    val fileName: String? = null,
    val localPath: String? = null,
    val sha256: String? = null,
    val entryId: String? = null,
    val links: List<MusicLink> = emptyList(),
    val similar: List<MusicSearchResult> = emptyList(),
    val freeTrack: FreeTrack? = null,
    val metadataResult: MusicSearchResult? = null,
    val canEditLicence: Boolean = false,
) {
    /** A local file is the only thing MediaPlayer can be pointed at. */
    val playableFile: String? get() = localPath
    val downloadable: Boolean get() = freeTrack?.downloadable == true
    val similarFileKey: String get() = entryId ?: "remote:${freeTrack?.key ?: metadataResult?.key.orEmpty()}"
}

/** Which free-licence families the "свободные треки" tab offers. */
enum class FreeTrackFilter(
    val label: String,
    val ccMixterLic: String?,
    val archiveLicenseClause: String,
) {
    OPEN("Свободные (без NC)", "open", "*creativecommons.org*"),
    CREATIVE_COMMONS("Любая CC", null, "*creativecommons.org*"),
    PUBLIC_DOMAIN("Общественное достояние", "pd", "*publicdomain*"),
    ;

    /**
     * Second gate on top of the server side filter. archive.org cannot express
     * "CC that is not NonCommercial" in its query language, so the distinction is
     * enforced here instead of being quietly ignored.
     */
    fun accepts(license: MusicLicense): Boolean = when (this) {
        OPEN -> license.isKnown && !license.nonCommercial
        CREATIVE_COMMONS -> license.isKnown
        PUBLIC_DOMAIN -> license.isKnown && license.isPublicDomain
    }
}

/** Lowercase, punctuation-free `artist + title`; the cross-source merge key. */
fun musicDedupeKey(artist: String?, title: String): String =
    (musicNormalize(artist.orEmpty()) + "|" + musicNormalize(title)).trim('|')
        .ifEmpty { musicNormalize(title) }

/**
 * Приводит строку к сравнимому виду: нижний регистр, без знаков препинания.
 *
 * Вынесено наружу не для красоты, а потому что теперь тем же правилом
 * пользуется ранжирование: дедупликация отвечает на вопрос «это один и тот же
 * трек?», а ранжирование — «насколько он похож на запрос». Если правила
 * разойдутся, одинаковые треки окажутся в разных местах списка, и это будет
 * выглядеть как ошибка поиска.
 *
 * `\p{L}` и `\p{N}` вместо `a-z0-9` — обязательны: кириллица в названиях
 * встречается постоянно, и латинская маска стёрла бы её в пустую строку.
 */
fun musicNormalize(value: String): String = value
    .lowercase(Locale.ROOT)
    .replace(NON_ALNUM, "")
    .trim()

private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]")

// --- Ранжирование и сводка по лицензиям ----------------------------------------

/**
 * Что человек ожидает получить от поиска. Выбирается **до** нажатия «найти».
 *
 * Отдельная настройка, а не украшение, потому что ответы двух режимов
 * принципиально разные: в [ANY] выдача смешанная, и пользователь должен видеть,
 * где кончается то, что можно забрать, и начинается то, что можно только
 * посмотреть. В [DOWNLOADABLE] строки без файла в выдачу не попадают вовсе, но
 * поиск всё равно выполняется по метаданным — чтобы в случае провала сказать
 * «треки найдены, скачать нельзя», а не показать пустоту.
 */
enum class MusicSearchScope(val label: String, val blurb: String) {
    ANY(
        label = "Любая музыка",
        blurb = "Сначала то, что реально скачивается, затем ссылки на запись. " +
            "У каждой строки написано, что с ней можно сделать.",
    ),
    DOWNLOADABLE(
        label = "Только скачиваемое",
        blurb = "Только источники с открытой лицензией и прямой ссылкой на файл. " +
            "Если таких не нашлось — приложение так и скажет, молчать об этом нельзя.",
    ),
}

/**
 * Насколько строка подходит под запрос, по убыванию уверенности.
 *
 * Числа в [weight] намеренно разнесены с зазором не меньше [MIN_TIER_GAP]:
 * ранжирование складывает вес совпадения с весом лицензии и площадки, и при
 * меньшем зазоре бонус за «CC0 вместо CC BY» смог бы перепрыгнуть целую ступень
 * совпадения. Совпадение по названию должно быть сильнее любых бонусов.
 */
enum class MatchTier(val weight: Int, val label: String) {
    EXACT_TITLE_AND_ARTIST(112, "название и исполнитель совпали точно"),
    EXACT_TITLE(100, "название совпало точно"),
    TITLE_PREFIX(88, "название начинается с запроса"),
    TITLE_CONTAINS(76, "название содержит запрос целиком"),
    ARTIST_EXACT(64, "совпал исполнитель"),
    PARTIAL_TITLE(52, "частичное совпадение по названию"),

    /**
     * Жанр стоит ниже названия намеренно: «punk» находит и панк, и любой альбом
     * с тегом «punk» в описании. Это признак того, что трек примерно подходит,
     * а не того, что он тот самый. Но он всё равно выше «ничего не нашлось»:
     * запрос «jazz» — это законный запрос, и выбрасывать под него найденное
     * нельзя, иначе «любой жанр» просто не работает.
     */
    GENRE_MATCH(40, "совпал жанр"),

    PARTIAL_ARTIST(28, "частичное совпадение по исполнителю"),
    NONE(0, "совпадения нет"),
    ;

    companion object {
        const val MIN_TIER_GAP = 12
    }
}

/** Что лежит в строке выдачи: файл или только описание записи. */
enum class MusicHitKind(val label: String, val shortLabel: String) {
    /** Источник публикует прямую ссылку на аудиофайл под открытой лицензией. */
    FILE("Файл с открытой лицензией", "файл"),

    /** iTunes / MusicBrainz / Deezer: описание существует, аудиофайла у нас нет. */
    METADATA("Только метаданные — скачать нельзя", "метаданные"),
}

/**
 * Одна строка объединённой выдачи.
 *
 * Раньше музыкальные результаты жили в двух разных списках и в двух вкладках, и
 * человек узнавал о лицензии только постфактум — уже нажав на строку. Здесь у
 * строки есть ровно тот минимум, который нужен для честного решения «качать или
 * смотреть», прямо в списке: [kind], [license] и [downloadable].
 */
data class MusicHit(
    val source: MusicSource,
    val kind: MusicHitKind,
    val sourceId: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val durationSec: Int? = null,
    val coverUrl: String? = null,
    val license: MusicLicense = MusicLicense.UNKNOWN,
    val pageUrl: String? = null,
    val match: MatchTier = MatchTier.NONE,
    val score: Int = 0,
    val freeTrack: FreeTrack? = null,
    val metadata: MusicSearchResult? = null,
) {
    val key: String get() = "${source.name}:$sourceId"

    /**
     * Единственный признак, по которому кнопка скачивания вообще может быть
     * активна: лицензия прочитана **и** источник даёт прямую ссылку на файл.
     * Всё остальное — описание, а не музыка.
     */
    val downloadable: Boolean get() = freeTrack?.downloadable == true

    /** Куда ведёт нажатие на строку. `null` возможен только для битой строки. */
    val target: TrackTarget?
        get() = freeTrack?.let { TrackTarget.Free(it) }
            ?: metadata?.let { TrackTarget.Metadata(it) }
}

/** Одна лицензия в сводке: сколько строк под ней. */
data class LicenceBadge(val label: String, val count: Int)

/**
 * Что получилось в выдаче, если посчитать это честно, а не «просто отдадим
 * первые N строк».
 *
 * Именно этой сводкой закрывается требование «человек должен видеть, что
 * именно он получит»: одно число «скачиваемых строк» и перечень лицензий
 * говорят больше, чем любой текст в описании источника.
 */
data class LicenceSummary(
    val fileRows: Int = 0,
    val metadataRows: Int = 0,
    val downloadableRows: Int = 0,
    /** Строки с файлом, но без читаемой лицензии: скачать их нельзя. */
    val blockedRows: Int = 0,
    val nonCommercialRows: Int = 0,
    val shareAlikeRows: Int = 0,
    val unknownLicenceRows: Int = 0,
    val badges: List<LicenceBadge> = emptyList(),
) {
    val hasFiles: Boolean get() = fileRows > 0
    val hasMetadata: Boolean get() = metadataRows > 0

    /** Смешанная выдача: в списке есть и скачиваемое, и то, что скачать нельзя. */
    val mixed: Boolean get() = hasFiles && hasMetadata

    /**
     * Одна строка для баннера над списком.
     *
     * Отдельная ветка для «файлов нет вообще» — самый важный случай: именно там
     * раньше была пустота, и пустота читалась как «музыки не существует».
     */
    val headline: String
        get() = when {
            fileRows == 0 && metadataRows > 0 ->
                "Скачать нечего: найдено $metadataRows ${russianRecords(metadataRows)}, это только метаданные"
            mixed ->
                "Скачиваемых строк: $downloadableRows из ${fileRows + metadataRows}. " +
                    "Остальные — только метаданные"
            hasFiles ->
                "Все $fileRows ${russianFiles(fileRows)} — скачиваемые файлы с открытой лицензией"
            else -> ""
        }

    /** Перечень лицензий; смешивание видно, а не спрятано за «смешанная лицензия». */
    val licenceLine: String
        get() {
            val parts = badges.map { "${it.label} — ${it.count}" }.toMutableList()
            if (unknownLicenceRows > 0) parts += "лицензия не указана — $unknownLicenceRows"
            if (parts.isEmpty()) return ""
            val head = parts.joinToString(" · ")
            return when {
                nonCommercialRows > 0 -> "$head. NC: только некоммерческое использование"
                shareAlikeRows > 0 -> "$head. SA: при перепубликации — те же условия"
                else -> head
            }
        }

    private companion object {
        /** Русские окончания считаются по последним двум цифрам: 11 уже не «запись». */
        fun russianRecords(count: Int): String {
            val tail = count % 100
            if (tail in 11..14) return "записей"
            return when (count % 10) {
                1 -> "запись"
                2, 3, 4 -> "записи"
                else -> "записей"
            }
        }

        fun russianFiles(count: Int): String {
            val tail = count % 100
            if (tail in 11..14) return "строк"
            return when (count % 10) {
                1 -> "строка"
                2, 3, 4 -> "строки"
                else -> "строк"
            }
        }
    }
}

/** Итог объединённого поиска: строки уже отсортированы и объяснены. */
data class UnifiedSearchResults(
    val query: String = "",
    val hits: List<MusicHit> = emptyList(),
    val errors: List<MusicSourceError> = emptyList(),
    /**
     * Сколько записей нашли источники метаданных — **в том числе те, что не
     * попали в выдачу**.
     *
     * Отдельное поле, потому что режим «только скачиваемое» не показывает
     * метаданные вовсе, и без этого числа провал выглядел бы как «такой музыки
     * не существует». С ним можно сказать прямо: треки найдены, скачать их нельзя.
     */
    val metadataFound: Int = 0,
) {
    val isEmpty: Boolean get() = hits.isEmpty()
}
