package dev.kagami.app.data.repo

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import dev.kagami.app.data.local.HistoryEntry
import dev.kagami.app.data.local.HistoryStore
import dev.kagami.app.data.local.StoredTrack
import dev.kagami.app.data.model.AudioFormat
import dev.kagami.app.data.model.AudioLibraryEntry
import dev.kagami.app.data.model.DownloadProgress
import dev.kagami.app.data.model.DownloadState
import dev.kagami.app.data.model.FreeTrack
import dev.kagami.app.data.model.FreeTrackFilter
import dev.kagami.app.data.model.FreeTrackResults
import dev.kagami.app.data.model.MusicLicense
import dev.kagami.app.data.model.MusicLink
import dev.kagami.app.data.model.MusicSearchResult
import dev.kagami.app.data.model.MusicSource
import dev.kagami.app.data.model.MusicSourceError
import dev.kagami.app.data.model.SearchResults
import dev.kagami.app.data.model.TrackTarget
import dev.kagami.app.data.model.TrackWikiPage
import dev.kagami.app.data.model.musicDedupeKey
import dev.kagami.app.data.remote.CcMixterClient
import dev.kagami.app.data.remote.CoverArtClient
import dev.kagami.app.data.remote.InternetArchiveClient
import dev.kagami.app.data.remote.ItunesClient
import dev.kagami.app.data.remote.MusicBrainzClient
import dev.kagami.app.util.audioFileName
import dev.kagami.app.util.guessExtension
import dev.kagami.app.util.guessMimeType
import dev.kagami.app.util.hex
import dev.kagami.app.util.htmlToPlainText
import dev.kagami.app.util.readableMessage
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
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
 * (with a `<file>.kagami.json` provenance sidecar next to every download). A
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
    context: Context,
    private val history: HistoryStore,
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

        val itunesCall = async { runCatching { itunes.searchSongs(term, limit) } }
        val brainzCall = async { runCatching { musicBrainz.searchRecordings(term, limit) } }
        val fromItunes = itunesCall.await()
        val fromBrainz = brainzCall.await()

        val errors = buildList {
            fromItunes.exceptionOrNull()?.let {
                add(MusicSourceError(MusicSource.ITUNES, "iTunes: ${it.readableMessage()}"))
            }
            fromBrainz.exceptionOrNull()?.let {
                add(MusicSourceError(MusicSource.MUSICBRAINZ, "MusicBrainz: ${it.readableMessage()}"))
            }
        }

        val merged = LinkedHashMap<String, MusicSearchResult>()
        fromItunes.getOrDefault(emptyList()).forEach { row ->
            merged.putIfAbsent(row.dedupeKey, row)
        }
        fromBrainz.getOrDefault(emptyList()).forEach { row ->
            val existing = merged[row.dedupeKey]
            if (existing == null) {
                merged[row.dedupeKey] = row
            } else if (existing.releaseMbid == null) {
                // Same song, but iTunes has no MBID: keep it so the wiki page
                // can still reach the Cover Art Archive.
                merged[row.dedupeKey] = existing.copy(
                    releaseMbid = row.releaseMbid,
                    recordingMbid = row.recordingMbid,
                    artistMbid = row.artistMbid,
                    versionHint = existing.versionHint ?: row.versionHint,
                    links = (existing.links + row.links).distinctBy { it.url },
                )
            }
        }

        SearchResults(results = merged.values.toList(), errors = errors)
    }

    // --- B. свободные треки ------------------------------------------------

    /**
     * ccMixter and the Internet Archive in parallel. Rows are never invented:
     * both clients only return what the source published, and a row whose
     * licence is unreadable survives the call with [FreeTrack.licenceNote] set so
     * the list can grey it out instead of hiding the fact that it exists.
     */
    suspend fun freeTracks(
        query: String,
        filter: FreeTrackFilter,
        limit: Int = 20,
    ): FreeTrackResults = withContext(Dispatchers.IO) {
        val perSource = (limit + 1) / 2
        val ccCall = async { runCatching { ccMixter.search(query, filter, perSource) } }
        val archiveCall = async { runCatching { internetArchive.search(query, filter, perSource) } }
        val fromCc = ccCall.await()
        val fromArchive = archiveCall.await()

        val errors = buildList {
            fromCc.exceptionOrNull()?.let { add(MusicSourceError(MusicSource.CCMIXTER, "ccMixter: ${it.readableMessage()}")) }
            fromArchive.exceptionOrNull()?.let {
                add(MusicSourceError(MusicSource.INTERNET_ARCHIVE, "Internet Archive: ${it.readableMessage()}"))
            }
        }

        val merged = LinkedHashMap<String, FreeTrack>()
        (fromArchive.getOrDefault(emptyList()) + fromCc.getOrDefault(emptyList())).forEach { row ->
            val key = "${row.source.name}:${row.sourceId}"
            if (merged[key] == null) merged[key] = row
        }

        FreeTrackResults(results = merged.values.take(limit), errors = errors)
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
     * Storage Access Framework import. The file is copied into `filesDir/audio/`
     * with a sha256 taken while it streams, and the tags come from
     * `MediaMetadataRetriever`. The licence stays empty on purpose: an imported
     * file has whatever licence its owner has, and the user fills that in through
     * [setAttribution].
     */
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
            File(audioDir, "${victim.fileName}.kagami.json").delete()
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
            File(audioDir, "${entry.fileName}.kagami.json")
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
         * Only sent to the file endpoints of the CC sources. A stock OkHttp UA
         * is what ccMixter answers with 403.
         */
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
