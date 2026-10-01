package dev.amps.app.data.remote

import dev.amps.app.data.model.AudioFormat
import dev.amps.app.data.model.FreeTrack
import dev.amps.app.data.model.FreeTrackFilter
import dev.amps.app.data.model.MusicLicense
import dev.amps.app.data.model.MusicSource
import dev.amps.app.util.htmlToPlainText
import dev.amps.app.util.parseClockDuration
import dev.amps.app.util.parseSampleRate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * ccMixter — the best anonymous Creative Commons source: no key, no
 * registration, and every upload carries a machine readable `license_url` and a
 * direct `files[].download_url`.
 *
 * Two quirks from the verified spec are handled here:
 *  * the endpoint answers with `Content-Type: text/plain`, so the body is
 *    parsed as JSON without checking the media type;
 *  * the server is an old Apache 2.2 / PHP 5.4 stack, so the client is throttled
 *    to one request per second and never polled.
 *
 * `license_url` is authoritative: even with `lic=open` the rows are re-checked
 * client side, and a row whose licence cannot be read keeps `MusicLicense.UNKNOWN`
 * so the UI can refuse the download instead of guessing.
 */
class CcMixterClient(private val client: OkHttpClient) {

    private val endpoint = "https://ccmixter.org/api/query"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private val throttle = Mutex()
    private var lastRequestAt = 0L

    suspend fun search(query: String, filter: FreeTrackFilter, limit: Int = 20): List<FreeTrack> =
        withContext(Dispatchers.IO) {
            val url = endpoint.toHttpUrl().newBuilder()
                .addQueryParameter("f", "json")
                .addQueryParameter("limit", limit.coerceIn(1, 100).toString())
                .addQueryParameter("sort", "score")
                .apply {
                    filter.ccMixterLic?.let { addQueryParameter("lic", it) }
                    val term = query.trim()
                    if (term.isNotEmpty()) {
                        addQueryParameter("s", term)
                        addQueryParameter("tags", term)
                    }
                }
                .build()
            parse(fetch(url.toString())).filter { filter.accepts(it.license) }
        }

    /** A single upload by its id, used to re-check a licence before downloading. */
    suspend fun byId(uploadId: Long): FreeTrack? = withContext(Dispatchers.IO) {
        val url = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("f", "json")
            .addQueryParameter("ids", uploadId.toString())
            .addQueryParameter("dataview", "links_dl")
            .build()
        parse(fetch(url.toString())).firstOrNull()
    }

    private suspend fun fetch(url: String): String {
        awaitSlot()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", AMPS_USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("ccMixter ответил HTTP ${response.code}")
            text
        }
    }

    private suspend fun awaitSlot() {
        throttle.withLock {
            val waitFor = lastRequestAt + MIN_SPACING_MS - System.currentTimeMillis()
            if (waitFor > 0) delay(waitFor)
            lastRequestAt = System.currentTimeMillis()
        }
    }

    private fun parse(payload: String): List<FreeTrack> {
        val root = json.parseToJsonElement(payload) as? JsonArray ?: return emptyList()
        return root.objects().mapNotNull { it.toFreeTrack() }
    }

    private fun JsonObject.toFreeTrack(): FreeTrack? {
        val id = long("upload_id") ?: return null
        val title = uploadName() ?: return null
        val extra = obj("upload_extra")
        if (extra?.bool("nsfw") == true) return null

        val file = arr("files").objects().pickAudioFile() ?: return null
        val info = file.obj("file_format_info")
        val audioUrl = file.str("download_url")?.takeIf { it.startsWith("http") }
        val license = MusicLicense.fromUrl(str("license_url"), str("license_name"))

        return FreeTrack(
            source = MusicSource.CCMIXTER,
            sourceId = id.toString(),
            title = title,
            artistName = (str("user_real_name") ?: str("user_name"))?.takeIf { it.isNotBlank() },
            album = null,
            year = str("upload_date_format")?.let { text ->
                YEAR_IN_TEXT.find(text)?.value?.toIntOrNull()
            },
            license = license,
            audioUrl = audioUrl,
            pageUrl = str("file_page_url"),
            authorUrl = str("artist_page_url"),
            coverUrl = null,
            format = AudioFormat(
                durationSec = parseClockDuration(info?.str("ps")),
                mimeType = info?.str("mime_type"),
                fileBytes = file.long("file_rawsize"),
                channels = info?.str("ch"),
                bitrateKind = info?.str("br"),
                sampleRateHz = parseSampleRate(info?.str("sr")),
            ),
            sha1 = file.obj("file_extra")?.str("sha1"),
            md5 = null,
            tags = str("upload_tags").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(12),
        )
    }

    /** ccMixter titles arrive with the uploader's own markup. */
    private fun JsonObject.uploadName(): String? =
        htmlToPlainText(str("upload_name")) ?: str("upload_name")?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * `mp3 > flac > ogg`; zip and "remote" entries are skipped. The API has no
     * cover field at all, so no artwork is invented here.
     */
    private fun List<JsonObject>.pickAudioFile(): JsonObject? {
        val audio = filter { file ->
            val info = file.obj("file_format_info")
            val mediaType = info?.str("media-type")?.lowercase().orEmpty()
            val name = file.str("file_name")?.lowercase().orEmpty()
            val isArchive = name.endsWith(".zip") || info?.str("default-ext") == "zip"
            (mediaType == "audio" || name.substringAfterLast('.', "").let { it in AUDIO_EXTENSIONS }) && !isArchive
        }
        return audio.minByOrNull { file ->
            val ext = file.str("file_name")?.substringAfterLast('.', "mp3")?.lowercase().orEmpty()
            PREFERENCE.indexOf(ext).takeIf { it >= 0 } ?: PREFERENCE.size
        }
    }

    private companion object {
        const val MIN_SPACING_MS = 1_000L
        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "ogg", "oga", "wav", "m4a", "aac", "opus")
        val PREFERENCE = listOf("mp3", "m4a", "opus", "ogg", "oga", "flac", "wav", "aac")
        val YEAR_IN_TEXT = Regex("(?:19|20)\\d{2}")
    }
}
