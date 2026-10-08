package dev.amps.app.data.repo

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import dev.amps.app.data.local.HistoryEntry
import dev.amps.app.data.local.HistoryStore
import dev.amps.app.data.local.StoredTrack
import dev.amps.app.data.model.AudioFormat
import dev.amps.app.data.model.AudioLibraryEntry
import dev.amps.app.data.model.DownloadProgress
import dev.amps.app.data.model.DownloadState
import dev.amps.app.data.model.FreeTrack
import dev.amps.app.data.model.FreeTrackFilter
import dev.amps.app.data.model.FreeTrackResults
import dev.amps.app.data.model.MusicLicense
import dev.amps.app.data.model.MusicLink
import dev.amps.app.data.model.MusicSearchResult
import dev.amps.app.data.model.MusicSearchScope
import dev.amps.app.data.model.MusicSource
import dev.amps.app.data.model.MusicSourceError
import dev.amps.app.data.model.SearchResults
import dev.amps.app.data.model.TrackTarget
import dev.amps.app.data.model.TrackWikiPage
import dev.amps.app.data.model.UnifiedSearchResults
import dev.amps.app.data.model.musicDedupeKey
import dev.amps.app.data.ranking.MusicQuery
import dev.amps.app.data.ranking.MusicRanker
import dev.amps.app.data.remote.CcMixterClient
import dev.amps.app.data.remote.CoverArtClient
import dev.amps.app.data.remote.InternetArchiveClient
import dev.amps.app.data.remote.ItunesClient
import dev.amps.app.data.remote.JamendoClient
import dev.amps.app.data.remote.MusicBrainzClient
import dev.amps.app.data.remote.OpenverseClient
import dev.amps.app.media.Id3Writer
import dev.amps.app.media.MediaStoreImporter
import dev.amps.app.util.audioFileName
import dev.amps.app.util.guessExtension
import dev.amps.app.util.guessMimeType
import dev.amps.app.util.hex
import dev.amps.app.util.htmlToPlainText
import dev.amps.app.util.readableMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The music half of the app: identify a track, find a freely licensed file for
 * it, or import one the user already owns.
 *
 * **The licensing rule that shapes every method here.** iTunes and MusicBrainz
 * are metadata only — neither of them ever yields a file, and `MusicSearchResult`
 * has no audio field so a download button cannot be attached to one by accident.
 * A `FreeTrack` is only downloadable when its `license_url` was readable
 * (`MusicLicense.isKnown`); anything else is shown with «лицензия не указана»
 * and the download stays disabled. Nothing here touches a streaming service.
 *
 * **Where state lives.** Downloaded and imported files are copied into
 * `filesDir/audio/`, and the index that carries the licence, the attribution
 * note and the checksums is a single JSON file at `filesDir/audio/library.json`
 * (with a `<file>.amps.json` provenance sidecar next to every download). A
 * DataStore preference was not used on purpose: one entry per file with a
 * mutable free-text note is a document, not a setting, and it has to survive
 * being read on a background thread while a download is running.
 */
class MusicRepository(
    private val itunes: ItunesClient,
    private val musicBrainz: MusicBrainzClient,
    private val coverArt: CoverArtClient,
    private val ccMixter: CcMixterClient,
    private val internetArchive: InternetArchiveClient,
    private val jamendo: JamendoClient? = null,
    /**
     * 1.1.1: агрегатор открытых аудио — Free Music Archive, freesound,
     * Wikimedia и другие, все с CC-лицензией. Падает баннером отдельно от
     * остальных источников — см. [withSource].
     */
    private val openverse: OpenverseClient,
    context: Context,
    private val history: HistoryStore,
    private val importer: MediaStoreImporter? = null,
    /**
     * 1.1.2: сповіщення «завантаження завершено». Опційний, щоб тести й
     * офлайн-прогони репозиторія не вимагали Android-контекст сповіщень.
     */
    private val notifier: dev.amps.app.core.AmpsNotifier? = null,
) {

    private val appContext: Context = context.applicationContext

    /**
     * The container hands out a 20 s read timeout, which a 75 MB netlabel
     * release will not survive. Downloads get their own client with no read
     * timeout at all.
     */
    private val downloadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    // --- local library -----------------------------------------------------

    private val libraryMutex = Mutex()
    private var libraryLoaded = false
    private val _library = MutableStateFlow<List<AudioLibraryEntry>>(emptyList())

    /** Imported and downloaded files with their licence and attribution note. */
    val library: StateFlow<List<AudioLibraryEntry>> = _library.asStateFlow()

    private val _downloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())

    /** Live progress for every download started through [download]. */
    val downloads: StateFlow<Map<String, DownloadProgress>> = _downloads.asStateFlow()

    // --- search targets ----------------------------------------------------

    /**
     * Navigation needs a string argument, and a `MusicSearchResult` is too big
     * to put in a route. Targets are therefore memoised here for the life of the
     * process; anything that must survive a restart goes through the history
     * store instead.
     */
    private val targets = LinkedHashMap<String, TrackTarget>()

    fun register(target: TrackTarget): String = synchronized(targets) {
        targets[target.id] = target
        if (targets.size > MAX_TARGETS) {
            targets.keys.firstOrNull()?.let { targets.remove(it) }
        }
        target.id
    }

    fun targetOf(id: String): TrackTarget? = synchronized(targets) { targets[id] }

    private val _currentTargetId = MutableStateFlow<String?>(null)

    /**
     * Which track the wiki screen is showing. The nav graph stays flat
     * (`music` / `music/track`), so the page is addressed through this holder
     * instead of a route argument.
     */
    val currentTargetId: StateFlow<String?> = _currentTargetId.asStateFlow()

    /** Registers [target] and makes it the one the wiki screen will open. */
    fun openTarget(target: TrackTarget): String = register(target).also { _currentTargetId.value = it }

    /** Re-opens a row from the search history. Returns null when its file is gone. */
    fun openFromHistory(entry: HistoryEntry): String? {
        val target = targetFromHistory(entry) ?: return null
        _currentTargetId.value = target.id
        return target.id
    }

    fun showTargetId(id: String?) {
        if (id != null) _currentTargetId.value = id
    }

    // --- A. поиск по названию ----------------------------------------------

    /**
     * iTunes and MusicBrainz in parallel, merged on a normalised
     * `artist + title` key. iTunes wins a collision because its catalogue is
     * cleaner and its artwork is already sized; the MusicBrainz row is only
     * used when it adds an MBID the iTunes row did not have.
     */
    suspend fun searchTracks(query: String, limit: Int = 25): SearchResults = withContext(Dispatchers.IO) {
        val term = query.trim()
        if (term.isEmpty()) return@withContext SearchResults()

        val itunesCall = async { withSource(MusicSource.ITUNES, "iTunes") { itunes.searchSongs(term, limit) } }
        val brainzCall = async { withSource(MusicSource.MUSICBRAINZ, "MusicBrainz") { musicBrainz.searchRecordings(term, limit) } }
        val fromItunes = itunesCall.await()
        val fromBrainz = brainzCall.await()

        val merged = mergeMetadata(fromItunes.value.orEmpty(), fromBrainz.value.orEmpty())
        val errors = listOfNotNull(fromItunes.error, fromBrainz.error)

        // Сортировка нужна и здесь: даже когда открыта вкладка «Поиск», строки
        // идут в порядке полезности, а не в порядке ответа сервера.
        val ranked = MusicRanker.rank(MusicQuery.of(term), merged.map(MusicRanker::fromMetadata), limit)
        val byKey = ranked.associateBy { it.key }
        SearchResults(
            results = merged.filter { byKey.containsKey("${it.source.name}:${it.sourceId}") },
            errors = errors,
        )
    }

    /**
     * Главная точка входа поиска: одна выдача из метаданных и свободных треков.
     *
     * **Почему один список, а не две вкладки.** Пользователь пришёл за музыкой,
     * а не за описанием записи. Когда скачиваемый трек и строка iTunes лежали в
     * разных списках, выбор «что взять» был невозможен: сравнивать приходилось
     * в уме. Здесь обе половины в одной выдаче, но помечены по-разному, и
     * [MusicRanker] ставит скачиваемое выше всего остального.
     *
     * Метаданные опрашиваются в обоих режимах, даже когда нужен только файл:
     * если скачиваемого не нашлось, именно метаданные позволяют сказать
     * «треки найдены, скачать их нельзя» вместо молчаливой пустоты.
     */
    suspend fun searchAll(
        query: String,
        scope: MusicSearchScope,
        limit: Int = 30,
    ): UnifiedSearchResults = withContext(Dispatchers.IO) {
        val term = query.trim()
        if (term.isEmpty()) return@withContext UnifiedSearchResults()

        // В режиме «любая музыка» метаданные и файлы делят место. В режиме
        // «только скачиваемое» файлов просим заметно больше — иначе выдача
        // вырождается в несколько строк на запрос.
        val wantFiles = scope == MusicSearchScope.DOWNLOADABLE
        val fileLimit = if (wantFiles) limit else (limit * 2 / 3).coerceAtLeast(8)
        val metaLimit = if (wantFiles) HONEST_METADATA_LIMIT else limit

        // Любая прочитанная лицензия подходит: NC-трек скачать можно, просто
        // не для коммерции. Скрывать его молча было бы враньём — вместо этого
        // он получает метку NC и понижается в порядке.
        val filter = FreeTrackFilter.CREATIVE_COMMONS

        val itunesCall = async {
            withSource(MusicSource.ITUNES, "iTunes", METADATA_TIMEOUT_MS) { itunes.searchSongs(term, metaLimit) }
        }
        val brainzCall = async {
            withSource(MusicSource.MUSICBRAINZ, "MusicBrainz", METADATA_TIMEOUT_MS) {
                musicBrainz.searchRecordings(term, metaLimit)
            }
        }
        val jamendoCall = async {
            val client = jamendo
            val outcome: SourceOutcome<List<FreeTrack>> = if (client == null) {
                // Молча пропустить источник нельзя: Jamendo — единственный живой
                // источник полных треков, и его отсутствие выглядело бы как
                // «свободной музыки нет», хотя поиск просто не настроен.
                SourceOutcome(emptyList(), MusicSourceError(MusicSource.JAMENDO, "Jamendo не настроен: не задан client_id"))
            } else {
                val call = withSource(MusicSource.JAMENDO, "Jamendo", FILE_TIMEOUT_MS) {
                    client.search(term, fileLimit)
                }
                // Jamendo отвечает HTTP 200, а ошибку кладёт в тело ответа. Без
                // этой проверки «неверный client_id» выглядел бы как «свободной
                // музыки по этому запросу нет».
                val note = call.value?.note
                when {
                    !note.isNullOrBlank() ->
                        SourceOutcome(call.value?.tracks.orEmpty(), MusicSourceError(MusicSource.JAMENDO, "Jamendo: $note"))
                    call.error != null -> SourceOutcome(emptyList<FreeTrack>(), call.error)
                    else -> SourceOutcome(call.value?.tracks.orEmpty(), null)
                }
            }
            outcome
        }
        val archiveCall = async {
            withSource(MusicSource.INTERNET_ARCHIVE, "Internet Archive", FILE_TIMEOUT_MS) {
                internetArchive.search(term, filter, fileLimit)
            }
        }
        @Suppress("DEPRECATION")
        val ccMixterCall = async {
            withSource(MusicSource.CCMIXTER, "ccMixter", FILE_TIMEOUT_MS) {
                ccMixter.search(term, filter, fileLimit)
            }
        }

        val fromItunes = itunesCall.await()
        val fromBrainz = brainzCall.await()
        val fromJamendo = jamendoCall.await()
        val fromArchive = archiveCall.await()
        val fromCcMixter = ccMixterCall.await()

        val metadata = mergeMetadata(fromItunes.value.orEmpty(), fromBrainz.value.orEmpty())
        val fileRows = (fromJamendo.value.orEmpty() + fromArchive.value.orEmpty() + fromCcMixter.value.orEmpty())
            .filter { filter.accepts(it.license) }

        val hits = fileRows
            .distinctBy { it.key }
            .map(MusicRanker::fromFreeTrack)
            .plus(metadata.map(MusicRanker::fromMetadata))

        val errors = listOfNotNull(
            fromItunes.error,
            fromBrainz.error,
            fromJamendo.error,
            fromArchive.error,
            fromCcMixter.error,
        ).distinctBy { it.source }

        UnifiedSearchResults(
            query = term,
            hits = MusicRanker.rank(MusicQuery.of(term), hits, limit),
            errors = errors,
            // Считается до ранжирования и до обрезки: именно эти записи нужны,
            // чтобы сказать «треки найдены, скачать их нельзя», когда свободных
            // треков не нашлось вовсе.
            metadataFound = metadata.size,
        )
    }

    /**
     * Склейка метаданных по одному и тому же треку.
     *
     * iTunes побеждает при коллизии: у него чище каталог и уже готовый размер
     * обложки. Строка MusicBrainz добавляется, только если принесла MBID — без
     * него вики-страница не дотянется до Cover Art Archive.
     */
    private fun mergeMetadata(
        fromItunes: List<MusicSearchResult>,
        fromBrainz: List<MusicSearchResult>,
    ): List<MusicSearchResult> {
        val merged = LinkedHashMap<String, MusicSearchResult>()
        fromItunes.forEach { row -> merged.putIfAbsent(row.dedupeKey, row) }
        fromBrainz.forEach { row ->
            val existing = merged[row.dedupeKey]
            if (existing == null) {
                merged[row.dedupeKey] = row
            } else if (existing.releaseMbid == null) {
                merged[row.dedupeKey] = existing.copy(
                    releaseMbid = row.releaseMbid,
                    recordingMbid = row.recordingMbid,
                    artistMbid = row.artistMbid,
                    versionHint = existing.versionHint ?: row.versionHint,
                    links = (existing.links + row.links).distinctBy { it.url },
                )
            }
        }
        return merged.values.toList()
    }

    // --- B. свободные треки ------------------------------------------------

    /**
     * 1.0.3: Jamendo добавляется третьим источником.
     *
     * ccMixter и Internet Archive остаются, хотя ccMixter уже не отвечает — его
     * ошибка показывается пользователю честно, а не прячется. Jamendo встаёт
     * вперёд, потому что это единственный из живых источников, который отдаёт
     * полный трек: у остальных в поле аудио либо ничего, либо 30 секунд.
     */
    suspend fun freeTracks(
        query: String,
        filter: FreeTrackFilter,
        limit: Int = 20,
    ): FreeTrackResults = withContext(Dispatchers.IO) {
        val term = query.trim()
        // 1.1.1: файловых источников четыре, и лимит делится на всех.
        val perSource = ((limit + 3) / 4).coerceAtLeast(1)
        val jamendoCall = async {
            val client = jamendo
            val outcome: SourceOutcome<List<FreeTrack>> = if (client == null) {
                SourceOutcome(emptyList(), MusicSourceError(MusicSource.JAMENDO, "Jamendo не настроен: не задан client_id"))
            } else {
                val call = withSource(MusicSource.JAMENDO, "Jamendo", FILE_TIMEOUT_MS) {
                    client.search(term, perSource)
                }
                val note = call.value?.note
                when {
                    !note.isNullOrBlank() ->
                        SourceOutcome(call.value?.tracks.orEmpty(), MusicSourceError(MusicSource.JAMENDO, "Jamendo: $note"))
                    call.error != null -> SourceOutcome(emptyList<FreeTrack>(), call.error)
                    else -> SourceOutcome(call.value?.tracks.orEmpty(), null)
                }
            }
            outcome
        }
        @Suppress("DEPRECATION")
        val ccCall = async {
            withSource(MusicSource.CCMIXTER, "ccMixter", FILE_TIMEOUT_MS) { ccMixter.search(term, filter, perSource) }
        }
        val archiveCall = async {
            withSource(MusicSource.INTERNET_ARCHIVE, "Internet Archive", FILE_TIMEOUT_MS) {
                internetArchive.search(term, filter, perSource)
            }
        }
        // 1.1.1: Openverse опрашивается тем же порядком, что и остальные
        // файловые источники: параллельно, со своим таймаутом и своим баннером
        // на случай отказа. Джамендо из его выдачи отфильтрован самим клиентом.
        val openverseCall = async {
            withSource(MusicSource.OPENVERSE, "Openverse", FILE_TIMEOUT_MS) {
                openverse.search(term, perSource)
            }
        }
        val fromJamendo = jamendoCall.await()
        val fromCc = ccCall.await()
        val fromArchive = archiveCall.await()
        val fromOpenverse = openverseCall.await()

        val errors = listOfNotNull(fromJamendo.error, fromCc.error, fromArchive.error, fromOpenverse.error)

        // Jamendo умеет фильтровать по лицензии только на своей стороне, поэтому
        // вторая проверка — наша: `accepts` отсекает NC-записи, если пользователь
        // выбрал «свободные».
        val rows = (fromJamendo.value.orEmpty() + fromArchive.value.orEmpty() + fromCc.value.orEmpty() + fromOpenverse.value.orEmpty())
            .filter { filter.accepts(it.license) }
            .distinctBy { it.key }

        // Список не обрезается до `limit` до ранжирования: иначе отсечение
        // побирало бы половину настоящих совпадений ради строк, которым всё
        // равно не найтись в первых двадцати.
        val ranked = MusicRanker.rank(
            MusicQuery.of(term),
            rows.map(MusicRanker::fromFreeTrack),
            limit,
        )
        val keys = ranked.map { it.key }.toSet()
        FreeTrackResults(
            results = ranked.mapNotNull { it.freeTrack }.filter { it.key in keys },
            errors = errors,
        )
    }

    // --- устойчивость к отказам источников ---------------------------------

    /** Что вернул источник: либо значение, либо текст ошибки для баннера. */
    private data class SourceOutcome<T>(
        val value: T? = null,
        val error: MusicSourceError? = null,
    )

    /**
     * Запускает один источник так, чтобы он не мог уронить весь поиск.
     *
     * **Здесь три отдельных случая, и их нельзя смешивать.**
     *
     *  * `TimeoutCancellationException` — источник не ответил вовсе. Это его
     *    собственная поломка, и она превращается в строку баннера: остальные
     *    источники должны показать результат. Перехватывать её надо **первой**,
     *    потому что она является подвидом `CancellationException` — поймав
     *    не ту ветку, мы бы превратили тихо ушедший в молчание источник в
     *    «отменённый поиск».
     *  * `CancellationException` — отменили сам поиск (пользователь допечатал
     *    запрос, или экран закрыт). Это не ошибка источника: исключение
     *    пробрасывается наружу, иначе отменённый запрос продолжил бы писать
     *    результат поверх уже показанного нового.
     *  * Всё остальное — настоящая ошибка источника, показывается и не роняет
     *    остальные.
     *
     * Раньше здесь стоял голый `runCatching`, который ловил и то, и другое:
     * падение одного источника было терпимо, но отмена поиска тоже становилась
     * «пустым результатом» — и он затирал собой более свежий ответ.
     */
    private suspend fun <T> withSource(
        source: MusicSource,
        label: String,
        timeoutMs: Long = METADATA_TIMEOUT_MS,
        block: suspend () -> T,
    ): SourceOutcome<T> = try {
        SourceOutcome(value = withTimeout(timeoutMs) { block() })
    } catch (timeout: TimeoutCancellationException) {
        SourceOutcome(error = MusicSourceError(source, "$label не ответил за ${timeoutMs / 1000} с"))
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Throwable) {
        SourceOutcome(error = MusicSourceError(source, "$label: ${error.readableMessage()}"))
    }

    // --- D. трек-вики ------------------------------------------------------

    /**
     * Everything the wiki page renders. The metadata half and the file half are
     * looked up independently: a dead Cover Art Archive must not hide a licence,
     * and a dead MusicBrainz must not hide a downloaded file.
     */
    suspend fun wikiPage(target: TrackTarget): TrackWikiPage = withContext(Dispatchers.IO) {
        when (target) {
            is TrackTarget.Local -> localPage(target.entry)
            is TrackTarget.Free -> freePage(target.track)
            is TrackTarget.Metadata -> metadataPage(target.result)
        }
    }

    /** Cover Art Archive lookup, exposed because a row may not have a release yet. */
    suspend fun coverFor(result: MusicSearchResult): String? =
        runCatching { coverArt.frontCover(result.releaseMbid) }.getOrNull() ?: result.coverUrl

    private suspend fun metadataPage(result: MusicSearchResult): TrackWikiPage {
        val cover = result.coverUrl ?: runCatching { coverArt.frontCover(result.releaseMbid) }.getOrNull()
        val similar = runCatching {
            musicBrainz.otherRecordingsByArtist(result.artistMbid, result.artist)
        }.getOrDefault(emptyList())
        return TrackWikiPage(
            title = result.title,
            artist = result.artist,
            album = result.album,
            year = result.year,
            genre = result.genre,
            versionHint = result.versionHint,
            source = result.source,
            coverUrl = cover,
            license = MusicLicense.UNKNOWN,
            format = AudioFormat(durationSec = result.durationSec),
            links = (result.links + discogsLink(result.artist, result.title)).distinctBy { it.url },
            similar = similar.filterNot { it.dedupeKey == result.dedupeKey },
            metadataResult = result,
        )
    }

    private suspend fun freePage(track: FreeTrack): TrackWikiPage {
        val stored = entryFor(track)
        val similar = runCatching {
            musicBrainz.otherRecordingsByArtist(null, track.artistName)
        }.getOrDefault(emptyList())
        val file = stored?.let { File(audioDir, it.fileName) }?.takeIf { it.exists() }
        return TrackWikiPage(
            title = track.title,
            artist = track.artistName,
            album = track.album,
            year = track.year,
            source = track.source,
            coverUrl = track.coverUrl,
            license = stored?.license ?: track.license,
            attributionNote = stored?.attributionNote,
            format = stored?.format ?: track.format,
            fileName = stored?.fileName,
            localPath = file?.absolutePath,
            sha256 = stored?.sha256,
            entryId = stored?.id,
            links = buildList {
                track.pageUrl?.let { add(MusicLink("Страница источника", it, "атрибуция обязательна")) }
                track.authorUrl?.let { add(MusicLink("Автор на источнике", it)) }
                track.license.url?.let { add(MusicLink("Текст лицензии", it)) }
                add(discogsLink(track.artistName, track.title))
            },
            similar = similar.filterNot { it.dedupeKey == track.dedupeKey },
            freeTrack = track,
            canEditLicence = stored != null,
        )
    }

    private fun localPage(entry: AudioLibraryEntry): TrackWikiPage {
        val file = File(audioDir, entry.fileName).takeIf { it.exists() }
        return TrackWikiPage(
            title = entry.title,
            artist = entry.artist,
            album = entry.album,
            year = entry.year,
            genre = entry.genre,
            source = entry.source,
            coverFile = entry.coverFile?.let { File(audioDir, it).absolutePath },
            license = entry.license,
            attributionNote = entry.attributionNote,
            format = entry.format,
            fileName = entry.fileName,
            localPath = file?.absolutePath,
            sha256 = entry.sha256,
            entryId = entry.id,
            links = buildList {
                entry.pageUrl?.let { add(MusicLink("Страница источника", it)) }
                entry.authorUrl?.let { add(MusicLink("Автор", it)) }
                entry.licenseUrl?.let { add(MusicLink("Лицензия", it)) }
            },
            canEditLicence = true,
        )
    }

    /** "Похожие треки" for any target, including an imported file we have never seen online. */
    suspend fun similarTracks(artist: String?, title: String?): List<MusicSearchResult> =
        withContext(Dispatchers.IO) {
            val seed = runCatching { musicBrainz.searchRecordings(title.orEmpty(), 3) }.getOrDefault(emptyList())
            val artistMbid = seed.firstOrNull { it.artist.equals(artist, ignoreCase = true) }?.artistMbid
            val byArtist = runCatching {
                musicBrainz.otherRecordingsByArtist(artistMbid, artist)
            }.getOrDefault(emptyList())
            (byArtist + seed).distinctBy { it.dedupeKey }
                .filterNot { it.dedupeKey == musicDedupeKey(artist, title.orEmpty()) }
                .take(12)
        }

    // --- история -----------------------------------------------------------

    /**
     * Rebuilds a target from a stored history row. `source` and `mbid` are the
     * only durable handles, which is exactly why `HistoryEntry.track` carries
     * them; everything else is re-fetched when the page opens.
     */
    fun targetFromHistory(entry: HistoryEntry): TrackTarget? {
        val stored = entry.track ?: return null
        val source = MusicSource.fromName(stored.source) ?: return null
        return when (source) {
            MusicSource.LOCAL, MusicSource.CCMIXTER, MusicSource.INTERNET_ARCHIVE -> {
                val libraryEntry = _library.value.firstOrNull { it.id == entry.id }
                    ?: _library.value.firstOrNull { it.source == source && it.title == stored.title }
                    ?: return null
                TrackTarget.Local(libraryEntry)
            }
            else -> {
                val mbid = stored.mbid
                TrackTarget.Metadata(
                    MusicSearchResult(
                        source = source,
                        sourceId = mbid ?: "${entry.id}",
                        title = stored.title,
                        artist = stored.artist.takeIf { it.isNotBlank() },
                        album = stored.album,
                        year = stored.year,
                        recordingMbid = mbid,
                        links = buildList {
                            stored.sourceUrl?.let { add(MusicLink("Страница трека", it)) }
                            add(discogsLink(stored.artist, stored.title))
                        },
                    )
                )
            }
        }
    }

    /** Re-opens a stored row; returns null when the library file is already gone. */
    suspend fun wikiPageForHistory(entry: HistoryEntry): TrackWikiPage? {
        ensureLibrary()
        val target = targetFromHistory(entry) ?: return null
        val page = wikiPage(target)
        return page.copy(coverUrl = page.coverUrl ?: entry.imageUrl, coverFile = page.coverFile)
    }

    /** Persists a "TRACK" row so the wiki page can be reopened without the bridge. */
    suspend fun rememberTrack(page: TrackWikiPage) = withContext(Dispatchers.IO) {
        val id = page.similarFileKey
        history.add(
            HistoryEntry(
                id = "track-$id",
                kind = "TRACK",
                title = page.title,
                subtitle = listOfNotNull(page.artist, page.year?.toString()).joinToString(" · "),
                imageUrl = page.coverUrl,
                createdAt = System.currentTimeMillis(),
                track = StoredTrack(
                    title = page.title,
                    artist = page.artist.orEmpty(),
                    album = page.album,
                    year = page.year,
                    source = page.source.name,
                    sourceUrl = page.links.firstNotNullOfOrNull { it.url },
                    mbid = page.metadataResult?.recordingMbid,
                    localPath = page.localPath,
                    license = page.license.badgeLabel ?: page.license.name,
                ),
            )
        )
    }

    // --- C. импорт своего файла --------------------------------------------

    /**
     * 1.0.3: кладёт скачанный трек в музыкальную библиотеку телефона.
     *
     * До этого файл оставался в приватной папке приложения, и в обычном
     * плеере его не было видно: пользователь получал «скачанный трек», который
     * нельзя было открыть ни одним системным приложением. Теперь трек лежит в
     * `Music/AMPS` с прошитыми тегами и обложкой.
     *
     * Теги вписываются **в байты файла**, а не в MediaStore: публичного API
     * для записи тегов в Android нет, а `ContentResolver.update()` по тегам
     * MediaProvider затирает при каждом сканировании.
     */
    suspend fun importToLibrary(
        track: FreeTrack,
        onProgress: (DownloadProgress) -> Unit = {},
    ): MediaStoreImporter.Imported {
        val service = importer ?: throw IOException("Импорт в музыкальную библиотеку недоступен")
        val entry = download(track, onProgress)
        val file = File(audioDir, entry.fileName)
        if (!file.exists()) throw IOException("Файл не найден после скачивания")

        val cover = entry.coverFile
            ?.let { File(audioDir, it) }
            ?.takeIf { it.exists() }
            ?.readBytes()
            ?.takeIf { it.size <= MAX_COVER_BYTES && it.isJpeg() }

        val displayName = audioFileName(track.artistName, track.title, "mp3")
        val imported = service.importMp3(
            source = file,
            displayName = displayName,
            tags = Id3Writer.Tags(
                title = track.title,
                artist = track.artistName,
                album = track.album,
                albumArtist = track.artistName,
                year = track.year?.toString(),
                // Атрибуция обязательна для CC-BY и CC-BY-SA: без неё в файле
                // нет упоминания автора, и это уже нарушение условий лицензии,
                // даже если файл лежит только на устройстве пользователя.
                comment = listOfNotNull(track.license.name, track.license.url)
                    .joinToString(" — ")
                    .takeIf { it.isNotBlank() },
                coverJpeg = cover,
            ),
        )
        history.add(
            HistoryEntry(
                id = "import-${track.sourceId}-${System.currentTimeMillis()}",
                kind = "TRACK",
                title = track.title,
                subtitle = "импорт в Music/AMPS",
                imageUrl = track.coverUrl,
                createdAt = System.currentTimeMillis(),
            )
        )
        return imported
    }

    /** Обложку вшиваем только если это настоящий JPEG и не гигантский файл. */
    private fun ByteArray.isJpeg(): Boolean =
        size > 3 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() &&
            !(this[2] == 0xFF.toByte() && this[3] == 0xD9.toByte())

    /**
     * Похоже ли начало файла на аудио.
     *
     * Нужно, чтобы отсечь HTML-страницу «Ошибка 403» или пустой ответ CDN,
     * которые сервер отдаёт с кодом 200 и которые иначе становятся «треком».
     * Проверяем ID3, MPEG-фрейм и RIFF/WAVE — три формы, в которых приходят
     * mp3 с этих площадок.
     */
    private fun ByteArray.looksLikeAudio(): Boolean {
        if (size < 4) return false
        // «ID3» — тег ID3v2 в начале файла.
        if (this[0] == 'I'.code.toByte() && this[1] == 'D'.code.toByte() &&
            this[2] == '3'.code.toByte()
        ) {
            return true
        }
        // «RIFF» + «WAVE» — контейнер WAV.
        if (this[0] == 'R'.code.toByte() && this[1] == 'I'.code.toByte() &&
            this[2] == 'F'.code.toByte() && this[3] == 'F'.code.toByte()
        ) {
            return true
        }
        // MPEG-аудио начинается с 11-битного синхрословада: 0xFF, затем биты
        // слота 11, то есть маска 0xE0.
        if (this[0] == 0xFF.toByte()) {
            return (this[1].toInt() and 0xE0) == 0xE0
        }
        return false
    }

    /**
     * Storage Access Framework import. The file is copied into `filesDir/audio/`
     * with a sha256 taken while it streams, and the tags come from
     * `MediaMetadataRetriever`. The licence stays empty on purpose: an imported
     * file has whatever licence its owner has, and the user fills that in through
     * [setAttribution].
     */
    /**
     * 1.1.3: імпорт трека за прямою URL-адресою файлу, яку користувач вставив
     * сам. Це НЕ скрейпінг сайтів і не пошук: застосунок завантажує лише той
     * файл, адресу якого користувач дав, і чесно перевіряє, що це аудіо.
     * Ліцензію застосунок не знає — в атрибуції стоїть сам URL, і
     * відповідальність за права на файл на тому, хто його вставив.
     */
    suspend fun importFromUrl(rawUrl: String): AudioLibraryEntry {
        val url = rawUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw IOException("Потрібне пряме посилання на файл (http/https)")
        }
        val rawName = url.substringAfterLast('/').substringBefore('?').ifBlank { "track" }
        val title = runCatching { java.net.URLDecoder.decode(rawName, "UTF-8") }
            .getOrDefault(rawName)
            .substringBeforeLast('.')
            .take(80)
            .ifBlank { "Імпорт" }
        val track = FreeTrack(
            source = MusicSource.URL_IMPORT,
            sourceId = "url-${url.hashCode().toUInt().toString(16)}",
            title = title,
            artistName = null,
            license = MusicLicense(name = "Файл користувача за посиланням", url = url),
            audioUrl = url,
            pageUrl = url,
        )
        return download(track)
    }

    suspend fun importAudio(uri: Uri): AudioLibraryEntry = withContext(Dispatchers.IO) {
        val resolver = appContext.contentResolver
        val mime = resolver.getType(uri)
        val displayName = resolver.displayName(uri) ?: "импорт"
        val extension = guessExtension(displayName, mime)

        val tags = readTags(uri)
        val title = tags.title?.takeIf { it.isNotBlank() }
            ?: displayName.substringBeforeLast('.', displayName)
        val artist = tags.artist?.takeIf { it.isNotBlank() }

        val id = "imp-${uri.toString().hashCode().toUInt().toString(16)}-${System.currentTimeMillis()}"
        val target = File(audioDir, audioFileName(artist, title, extension))
        val digest = copyToLibrary(resolver, uri, target) ?: throw IOException("Не удалось прочитать выбранный файл")

        val coverName = tags.embeddedPicture?.let { saveCover(id, it) }
        val entry = AudioLibraryEntry(
            id = id,
            title = title,
            artist = artist,
            album = tags.album,
            year = tags.year,
            genre = tags.genre,
            fileName = target.name,
            sha256 = digest,
            fileBytes = target.length(),
            mimeType = guessMimeType(displayName, mime),
            format = AudioFormat(
                durationSec = tags.durationMs?.let { (it / 1000L).toInt() },
                bitrateKbps = tags.bitrate?.div(1000),
                sampleRateHz = tags.sampleRate,
                fileBytes = target.length(),
                mimeType = guessMimeType(displayName, mime),
            ),
            source = MusicSource.LOCAL,
            coverFile = coverName,
            addedAt = System.currentTimeMillis(),
        )
        persistSidecar(entry, note = "Импортировано из хранилища устройства")
        upsert(entry)
        entry
    }

    // --- загрузка ----------------------------------------------------------

    /**
     * Streams a freely licensed file to disk, reporting progress, and verifies
     * it against the `md5`/`sha1` the source published when either exists. A
     * checksum mismatch deletes the partial file and fails loudly: a wrong file
     * that looks downloaded is worse than no file.
     */
    suspend fun download(
        track: FreeTrack,
        onProgress: (DownloadProgress) -> Unit = {},
    ): AudioLibraryEntry = withContext(Dispatchers.IO) {
        if (!track.license.isKnown) {
            throw IOException("Лицензия не указана — скачивание отключено")
        }
        val url = track.audioUrl
        if (url.isNullOrBlank()) throw IOException("Источник не публикует прямую ссылку на файл")

        val key = track.key
        val extension = guessExtension(extensionFromUrl(url), track.format.mimeType)
        val finalFile = File(audioDir, audioFileName(track.artistName, track.title, extension))
        val partialFile = File(audioDir, "${finalFile.name}.part")
        val label = listOfNotNull(track.artistName, track.title).joinToString(" — ")

        publish(key, DownloadProgress(key, label, state = DownloadState.RUNNING, totalBytes = track.format.fileBytes))
        onProgress(DownloadProgress(key, label, totalBytes = track.format.fileBytes))

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "*/*")
            .apply {
                // ccMixter answers a bare file GET from a non-browser client with
                // 403; a browser UA plus the upload page as Referer is the
                // documented workaround and costs nothing elsewhere.
                track.pageUrl?.let { header("Referer", it) }
            }
            .get()
            .build()

        try {
            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Источник ответил HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("Пустой ответ при скачивании")
                val total = body.contentLength().takeIf { it > 0 } ?: track.format.fileBytes
                val sha256 = MessageDigest.getInstance("SHA-256")
                val md5 = MessageDigest.getInstance("MD5")
                val sha1 = MessageDigest.getInstance("SHA-1")

                var read = 0L
                var lastEmit = 0L
                val buffer = ByteArray(64 * 1024)

                body.byteStream().use { input ->
                    FileOutputStream(partialFile).use { output ->
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            sha256.update(buffer, 0, count)
                            md5.update(buffer, 0, count)
                            sha1.update(buffer, 0, count)
                            read += count
                            val now = System.currentTimeMillis()
                            if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                                lastEmit = now
                                val snapshot = DownloadProgress(key, label, read, total)
                                publish(key, snapshot)
                                onProgress(snapshot)
                            }
                        }
                        output.flush()
                    }
                }

                track.md5?.let { expected ->
                    if (!expected.equals(md5.digest().hex(), ignoreCase = true)) {
                        throw IOException("Контрольная сумма MD5 не совпала — файл удалён")
                    }
                }
                track.sha1?.let { expected ->
                    if (!expected.equals(sha1.digest().hex(), ignoreCase = true)) {
                        throw IOException("Контрольная сумма SHA-1 не совпала — файл удалён")
                    }
                }

                // Jamendo (и другие CDN) периодически отвечают `200 OK` с пустым телом.
// Для OkHttp такой ответ успешен, поэтому раньше он проходил как скачанный
// файл: на диск ложился ноль байт, запись попадала в библиотеку с sha256
// пустого файла, а пользователю показывалось «Файл сохранён». Теперь пустой
// и непохожий на аудио ответ отбраковывается до попадания в библиотеку.
                if (read <= 0L) {
                    partialFile.delete()
                    throw IOException("Сервер отдал пустой файл (0 байт) — попробуйте ещё раз")
                }
                val head = ByteArray(minOf(read, 16).toInt())
                partialFile.inputStream().use { stream ->
                    // Фиксированный префикс читаем обычным циклом: у `readFully`
                    // из stdlib нужен либо API 33, либо десахаризация, а она в
                    // проекте выключена, и сборка падала бы на unresolved reference.
                    var filled = 0
                    while (filled < head.size) {
                        val got = stream.read(head, filled, head.size - filled)
                        if (got <= 0) break
                        filled += got
                    }
                }
                if (!head.looksLikeAudio()) {
                    partialFile.delete()
                    throw IOException(
                        "Сервер отдал не аудио, а страницу с ошибкой — попробуйте ещё раз",
                    )
                }
                // Расхождение с ожидаемым размером — тоже признак оборванной
                // выдачи: файл вроде бы есть, но это половина трека.
                val expectedBytes = track.format.fileBytes ?: 0L
                if (expectedBytes > 0 && read < expectedBytes / 2) {
                    partialFile.delete()
                    throw IOException(
                        "Скачалось $read байт вместо $expectedBytes — файл оборван, попробуйте ещё раз",
                    )
                }

                if (finalFile.exists()) finalFile.delete()
                if (!partialFile.renameTo(finalFile)) {
                    partialFile.copyTo(finalFile, overwrite = true)
                    partialFile.delete()
                }

                val entry = AudioLibraryEntry(
                    id = "dl:$key",
                    title = track.title,
                    artist = track.artistName,
                    album = track.album,
                    year = track.year,
                    fileName = finalFile.name,
                    sha256 = sha256.digest().hex(),
                    fileBytes = read,
                    mimeType = guessMimeType(finalFile.name, track.format.mimeType),
                    format = track.format.copy(
                        fileBytes = read,
                        durationSec = track.format.durationSec ?: probeDuration(finalFile),
                    ),
                    source = track.source,
                    sourceId = track.sourceId,
                    licenseName = track.license.name,
                    licenseUrl = track.license.url,
                    pageUrl = track.pageUrl,
                    authorUrl = track.authorUrl,
                    sourceSha1 = track.sha1,
                    sourceMd5 = track.md5,
                    addedAt = System.currentTimeMillis(),
                )
                persistSidecar(entry, note = null)
                upsert(entry)

                val done = DownloadProgress(key, label, read, read, DownloadState.DONE, "Файл сохранён")
                publish(key, done)
                onProgress(done)
                // 1.1.2: сповіщення в шторці — трек готовий навіть коли екран
                // застосунку згорнутий. Без дозволу нотифікатор мовчить.
                runCatching {
                    notifier?.notifyDownloadComplete(track.title, track.artistName.orEmpty())
                }
                entry
            }
        } catch (error: Throwable) {
            partialFile.delete()
            val message = "Не удалось скачать: ${error.readableMessage()}"
            val failed = DownloadProgress(key, label, state = DownloadState.FAILED, message = message)
            publish(key, failed)
            onProgress(failed)
            throw error
        }
    }

    // --- библиотека --------------------------------------------------------

    /** Reads the index from disk on first use, then returns the cached list. */
    suspend fun libraryEntries(): List<AudioLibraryEntry> = withLibrary { it }

    suspend fun libraryEntry(id: String): AudioLibraryEntry? = withLibrary { list ->
        list.firstOrNull { it.id == id }
    }

    suspend fun deleteEntry(id: String) = updateLibrary { list ->
        val victim = list.firstOrNull { it.id == id }
        if (victim != null) {
            File(audioDir, victim.fileName).delete()
            victim.coverFile?.let { File(audioDir, it).delete() }
            File(audioDir, "${victim.fileName}.amps.json").delete()
        }
        list.filterNot { it.id == id }
    }

    /** The user-declared licence / attribution for an imported or downloaded file. */
    suspend fun setAttribution(id: String, licenseName: String?, licenseUrl: String?, note: String?) =
        updateLibrary { list ->
            list.map { entry ->
                if (entry.id != id) {
                    entry
                } else {
                    entry.copy(
                        licenseName = licenseName?.trim()?.takeIf { it.isNotEmpty() } ?: entry.licenseName,
                        licenseUrl = licenseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: entry.licenseUrl,
                        attributionNote = note?.trim()?.takeIf { it.isNotEmpty() },
                    )
                }
            }
        }

    suspend fun entryFor(track: FreeTrack): AudioLibraryEntry? = withLibrary { list ->
        list.firstOrNull { it.source == track.source && it.sourceId == track.sourceId }
    }

    suspend fun entryForSource(source: MusicSource, sourceId: String): AudioLibraryEntry? = withLibrary { list ->
        list.firstOrNull { it.source == source && it.sourceId == sourceId }
    }

    // --- internals ---------------------------------------------------------

    private fun publish(key: String, progress: DownloadProgress) {
        _downloads.value = _downloads.value + (key to progress)
    }

    private suspend fun upsert(entry: AudioLibraryEntry) {
        updateLibrary { list -> list.filterNot { it.id == entry.id } + entry }
    }

    private suspend fun ensureLibrary() = libraryMutex.withLock {
        if (libraryLoaded) return@withLock
        libraryLoaded = true
        _library.value = runCatching { readLibrary() }.getOrDefault(emptyList())
    }

    private suspend fun <T> withLibrary(block: (List<AudioLibraryEntry>) -> T): T = libraryMutex.withLock {
        if (!libraryLoaded) {
            libraryLoaded = true
            _library.value = runCatching { readLibrary() }.getOrDefault(emptyList())
        }
        block(_library.value)
    }

    private suspend fun updateLibrary(
        block: (List<AudioLibraryEntry>) -> List<AudioLibraryEntry>,
    ): List<AudioLibraryEntry> = libraryMutex.withLock {
        if (!libraryLoaded) {
            libraryLoaded = true
            _library.value = runCatching { readLibrary() }.getOrDefault(emptyList())
        }
        val updated = block(_library.value)
        _library.value = updated
        runCatching { writeLibrary(updated) }
        updated
    }

    private fun readLibrary(): List<AudioLibraryEntry> {
        val file = libraryFile
        if (!file.exists()) return emptyList()
        return json.decodeFromString<List<AudioLibraryEntry>>(file.readText())
    }

    private fun writeLibrary(entries: List<AudioLibraryEntry>) {
        runCatching { libraryFile.writeText(json.encodeToString(entries)) }
    }

    /**
     * Provenance sidecar next to the audio file: where it came from, under which
     * licence, with which checksum. A CC-BY obligation is a licence term, and
     * keeping it beside the file is what makes it survive a reinstall.
     */
    private fun persistSidecar(entry: AudioLibraryEntry, note: String?) {
        val sidecar = Provenance(
            source = entry.source.label,
            identifier = entry.sourceId,
            title = entry.title,
            author = entry.artist,
            authorUrl = entry.authorUrl,
            pageUrl = entry.pageUrl,
            licenseName = entry.licenseName,
            licenseUrl = entry.licenseUrl,
            attribution = entry.attributionNote ?: note,
            sha256 = entry.sha256,
            sourceSha1 = entry.sourceSha1,
            sourceMd5 = entry.sourceMd5,
            fileBytes = entry.fileBytes,
        )
        runCatching {
            File(audioDir, "${entry.fileName}.amps.json")
                .writeText(json.encodeToString(sidecar))
        }
    }

    private fun copyToLibrary(resolver: ContentResolver, uri: Uri, target: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                }
                output.flush()
            }
        } ?: return null
        digest.digest().hex()
    }.getOrNull()

    private fun saveCover(id: String, bytes: ByteArray): String? = runCatching {
        val name = "cover-$id.jpg"
        File(audioDir, name).writeBytes(bytes)
        name
    }.getOrNull()

    private fun probeDuration(file: File): Int? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?.let { (it / 1000L).toInt() }
        } finally {
            releaseQuietly(retriever)
        }
    }.getOrNull()

    private fun ContentResolver.displayName(uri: Uri): String? = runCatching {
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    private data class Tags(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val year: Int? = null,
        val genre: String? = null,
        val durationMs: Long? = null,
        val bitrate: Int? = null,
        val sampleRate: Int? = null,
        val embeddedPicture: ByteArray? = null,
    )

    private fun readTags(uri: Uri): Tags = runCatching {
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(appContext, uri)
            fun meta(key: Int) = retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            Tags(
                title = htmlToPlainText(meta(MediaMetadataRetriever.METADATA_KEY_TITLE)),
                artist = htmlToPlainText(meta(MediaMetadataRetriever.METADATA_KEY_ARTIST)),
                album = htmlToPlainText(meta(MediaMetadataRetriever.METADATA_KEY_ALBUM)),
                year = meta(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4)?.toIntOrNull(),
                genre = htmlToPlainText(meta(MediaMetadataRetriever.METADATA_KEY_GENRE)),
                durationMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                bitrate = meta(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull(),
                sampleRate = meta(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull(),
                embeddedPicture = retriever.embeddedPicture,
            )
        } finally {
            releaseQuietly(retriever)
        }
    }.getOrDefault(Tags())

    private fun releaseQuietly(retriever: MediaMetadataRetriever) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { retriever.release() }
        }
    }

    private fun discogsLink(artist: String?, title: String): MusicLink {
        val query = listOf(artist.orEmpty(), title).filter { it.isNotBlank() }.joinToString(" ")
        val url = "https://www.discogs.com/search/?q=${query.trim().replace(" ", "+")}&type=release"
        return MusicLink("Discogs", url, "поиск издания; Discogs не отдаёт файлы")
    }

    private fun extensionFromUrl(url: String): String? =
        url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").takeIf { it.isNotEmpty() }

    private val audioDir: File
        get() = File(appContext.filesDir, "audio").apply { if (!exists()) mkdirs() }

    private val libraryFile: File get() = File(audioDir, "library.json")

    @Serializable
    private data class Provenance(
        val source: String,
        val identifier: String? = null,
        val title: String,
        val author: String? = null,
        val authorUrl: String? = null,
        val pageUrl: String? = null,
        val licenseName: String? = null,
        val licenseUrl: String? = null,
        val attribution: String? = null,
        val sha256: String,
        val sourceSha1: String? = null,
        val sourceMd5: String? = null,
        val fileBytes: Long = 0L,
    )

    private companion object {
        const val MAX_TARGETS = 80
        const val PROGRESS_INTERVAL_MS = 120L

        /**
         * Сколько секунд ждём каждый источник.
         *
         * Метаданным хватает шести: это один-два запроса. Файловым источникам
         * нужно больше — Internet Archive делает по дополнительному запросу на
         * каждую находку, и на медленной сети это десятки секунд.
         *
         * Ограничение нужно потому, что шесть источников опрашиваются параллельно,
         * и без него один зависший съедал бы весь общий таймаут и поиск выглядел
         * бы «ничего не нашлось» из-за одного плохого соединения.
         */
        const val METADATA_TIMEOUT_MS = 6_000L
        const val FILE_TIMEOUT_MS = 12_000L

        /**
         * Сколько записей всё равно опрашиваем в режиме «только скачиваемое».
         *
         * Эти строки не показываются — они нужны только для честного сообщения
         * «треки найдены, скачать их нельзя». Без них провал выглядел бы как
         * «музыки такого названия не существует», а это неправда.
         */
        const val HONEST_METADATA_LIMIT = 8

        /**
         * Обложка вшивается в теги файла, а не показывается отдельно, поэтому
         * она должна быть достаточно маленькой, чтобы файл оставался
         * разумного размера. Больше 2 МБ — это уже не обложка, а фотография.
         */
        const val MAX_COVER_BYTES = 2 * 1024 * 1024

        /**
         * Only sent to the file endpoints of the CC sources. A stock OkHttp UA
         * is what ccMixter answers with 403.
         */
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
