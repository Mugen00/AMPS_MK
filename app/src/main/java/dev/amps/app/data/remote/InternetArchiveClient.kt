package dev.amps.app.data.remote

import dev.amps.app.data.model.AudioFormat
import dev.amps.app.data.model.FreeTrack
import dev.amps.app.data.model.FreeTrackFilter
import dev.amps.app.data.model.MusicLicense
import dev.amps.app.data.model.MusicSource
import dev.amps.app.util.htmlToPlainText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Internet Archive — the primary free-licence backend.
 *
 * It is the only verified source that gives a genuine licence, an anonymous
 * keyless endpoint, `Accept-Ranges: bytes`, and a per-file `md5`/`sha1` to
 * verify the download against. Its weak point is ergonomics, which this client
 * fixes: the advanced search only returns item-level metadata, so each hit is
 * enriched with `/metadata/<identifier>` to find the actual audio file.
 *
 * `licenseurl` is *omitted entirely* from items that carry no licence, and
 * `creator` / `collection` may each be a bare string or an array. All three are
 * read defensively; a row that ends up without a readable licence keeps
 * `MusicLicense.UNKNOWN` and is shown as closed rather than silently accepted.
 */
class InternetArchiveClient(private val client: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val enrichment = Semaphore(MAX_PARALLEL_METADATA_CALLS)

    suspend fun search(
        query: String,
        filter: FreeTrackFilter,
        limit: Int = 20,
    ): List<FreeTrack> = withContext(Dispatchers.IO) {
        val rows = limit.coerceIn(1, 100)
        val term = query.trim().replace("\"", " ")
        val searchQuery = buildString {
            append("collection:(netlabels) AND mediatype:(audio)")
            append(" AND licenseurl:(").append(filter.archiveLicenseClause).append(')')
            if (term.isNotEmpty()) append(" AND title:(\"").append(term).append("\")")
        }
        val url = "https://archive.org/advancedsearch.php".toHttpUrl().newBuilder()
            .addQueryParameter("q", searchQuery)
            .addQueryParameter("fl[]", "identifier")
            .addQueryParameter("fl[]", "title")
            .addQueryParameter("fl[]", "creator")
            .addQueryParameter("fl[]", "licenseurl")
            .addQueryParameter("fl[]", "year")
            .addQueryParameter("fl[]", "item_size")
            .addQueryParameter("sort[]", "downloads desc")
            .addQueryParameter("rows", rows.toString())
            .addQueryParameter("page", "1")
            .addQueryParameter("output", "json")
            .build()

        val root = json.parseToJsonElement(get(url.toString())) as? JsonObject ?: return@withContext emptyList()
        val docs = root.obj("response")?.arr("docs")?.objects().orEmpty()

        // The search response has no file list, so each hit costs one extra call.
        // A handful of them are resolved in parallel and the rest are dropped
        // rather than returned without a playable file.
        coroutineScope {
            docs.mapNotNull { doc ->
                async {
                    enrichment.withPermit {
                        val identifier = doc.str("identifier") ?: return@withPermit null
                        runCatching { item(identifier) }.getOrNull()?.takeIf { it.audioUrl != null }
                    }
                }
            }.awaitAll()
        }.filterNotNull().filter { filter.accepts(it.license) }
    }

    /** One item with its audio files resolved. Returns null when it has none. */
    suspend fun item(identifier: String): FreeTrack? = withContext(Dispatchers.IO) {
        val id = identifier.trim()
        if (id.isEmpty()) return@withContext null
        val root = json.parseToJsonElement(get("https://archive.org/metadata/$id".toHttpUrl().toString()))
            as? JsonObject ?: return@withContext null
        val metadata = root.obj("metadata")
        val files = root.arr("files").objects()

        val audio = files.firstOrNull { isAudio(it) } ?: return@withContext null
        val fileName = audio.str("name") ?: return@withContext null
        val creators = metadata?.at("creator").asTextList()
        val license = MusicLicense.fromUrl(metadata?.str("licenseurl"), licenseName(metadata?.str("licenseurl")))
        val cover = files.firstOrNull { isCover(it) }?.str("name")

        FreeTrack(
            source = MusicSource.INTERNET_ARCHIVE,
            sourceId = id,
            title = htmlToPlainText(metadata?.str("title"))
                ?: htmlToPlainText(audio.str("title"))
                ?: id,
            artistName = creators.firstOrNull(),
            album = htmlToPlainText(audio.str("album")) ?: htmlToPlainText(metadata?.str("title")),
            year = (metadata?.str("year") ?: metadata?.str("date"))?.take(4)?.toIntOrNull(),
            license = license,
            audioUrl = "https://archive.org/download/$id/${encodePathSegment(fileName)}",
            pageUrl = "https://archive.org/details/$id",
            authorUrl = null,
            coverUrl = cover?.let { "https://archive.org/download/$id/${encodePathSegment(it)}" },
            format = AudioFormat(
                // `length` is seconds, published as a string: "3149.14".
                durationSec = audio.str("length")?.toDoubleOrNull()?.toInt(),
                fileBytes = audio.long("size"),
                mimeType = mimeOf(fileName),
                channels = null,
            ),
            sha1 = audio.str("sha1"),
            md5 = audio.str("md5"),
            tags = metadata?.str("subject").orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }.take(12),
        )
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", AMPS_USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Internet Archive ответил HTTP ${response.code}")
            text
        }
    }

    private fun isAudio(file: JsonObject): Boolean {
        val format = file.str("format")?.lowercase().orEmpty()
        val name = file.str("name")?.lowercase().orEmpty()
        if (name.endsWith(".xml") || name.endsWith(".txt") || name.contains("_files.xml")) return false
        return AUDIO_FORMATS.any { format == it } ||
            AUDIO_EXTENSIONS.any { name.endsWith(".$it") }
    }

    private fun isCover(file: JsonObject): Boolean {
        val name = file.str("name")?.lowercase().orEmpty()
        return file.str("format") == "JPEG" &&
            name.endsWith(".jpg") &&
            "__ia_thumb" !in name &&
            "_thumb" !in name
    }

    private fun mimeOf(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "flac" -> "audio/flac"
        "ogg", "oga" -> "audio/ogg"
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        "m4b" -> "audio/mp4"
        else -> "audio/mpeg"
    }

    private fun licenseName(url: String?): String? = when {
        url == null -> null
        "publicdomain/zero" in url || "cc0" in url -> "CC0 1.0"
        "publicdomain/mark" in url -> "Public Domain Mark 1.0"
        "publicdomain" in url -> "Public Domain"
        "by-nc-nd" in url -> "Attribution Noncommercial NoDerivatives"
        "by-nc-sa" in url -> "Attribution Noncommercial ShareAlike"
        "by-nc" in url -> "Attribution Noncommercial"
        "by-sa" in url -> "Attribution ShareAlike"
        "by-nd" in url -> "Attribution NoDerivatives"
        "by" in url -> "Attribution"
        else -> null
    }

    private companion object {
        const val MAX_PARALLEL_METADATA_CALLS = 4
        val AUDIO_FORMATS = setOf("vbr mp3", "mp3", "128kbps mp3", "ogg vorbis", "flac", "wav", "aiff")
        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "ogg", "oga", "wav", "m4a", "m4b")
    }
}

/** `creator` and `collection` are sometimes a string and sometimes an array. */
private fun JsonElement?.asTextList(): List<String> = when (this) {
    is JsonArray -> strings()
    is JsonPrimitive -> contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList()
    else -> emptyList()
}
