package dev.amps.app.data.remote

import dev.amps.app.data.model.MusicLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Cover Art Archive, reached the way it works today.
 *
 * `coverartarchive.org` is no longer the data host: it 307s to
 * `archive.org/metadata/mbid-<releaseMbid>`, which then 302s to a storage node.
 * OkHttp follows both by default, and a cross-host redirect here is normal, not
 * an error.
 *
 * "No cover" has two shapes and neither of them is a failure:
 *   * HTTP 404 for an MBID the archive has never seen;
 *   * HTTP 200 with a body of `{}` for a known-but-artless release group.
 * Both return null and the UI simply falls back to a placeholder.
 */
class CoverArtClient(private val client: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * `ConcurrentHashMap` refuses null values, so "no cover for this MBID" is
     * memoised as an empty string. Otherwise every list row would re-ask
     * archive.org about the same release.
     */
    private val cache = ConcurrentHashMap<String, String>()

    suspend fun frontCover(releaseMbid: String?): String? = withContext(Dispatchers.IO) {
        val mbid = releaseMbid?.trim()?.takeIf { it.isNotBlank() } ?: return@withContext null
        if (cache.containsKey(mbid)) return@withContext cache[mbid]?.takeIf { it.isNotEmpty() }
        val resolved = runCatching { lookup(mbid) }.getOrNull().orEmpty()
        cache[mbid] = resolved
        resolved.takeIf { it.isNotEmpty() }
    }

    /** Drops the memo, e.g. when the user asks for a refresh. */
    fun invalidate() = cache.clear()

    private fun lookup(releaseMbid: String): String? {
        val identifier = "mbid-$releaseMbid"
        val request = Request.Builder()
            .url("https://archive.org/metadata/$identifier".toHttpUrl())
            .header("User-Agent", AMPS_USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()

        val text = client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IllegalStateException("Cover Art Archive HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        if (text.isBlank()) return null

        val root = json.parseToJsonElement(text) as? JsonObject ?: return null
        if (root.arr("files").isEmpty()) return null
        val name = pickFile(root)?.str("name") ?: return null
        return "https://archive.org/download/$identifier/${encodePathSegment(name)}"
    }

    /**
     * Preference order taken from the verified responses: the 500px thumb is the
     * cheapest good-looking asset, and an original JPEG is the last resort. The
     * `_mb_metadata.xml` and `__ia_thumb.jpg` entries are filtered out by the
     * suffix rules already.
     */
    private fun pickFile(root: JsonObject): JsonObject? {
        val files = root.arr("files").objects()
        if (files.isEmpty()) return null
        val byName = { suffix: String -> files.firstOrNull { it.str("name")?.endsWith(suffix) == true } }
        return byName("_thumb500.jpg")
            ?: byName("_thumb250.jpg")
            ?: byName("-250.jpg")
            ?: files.firstOrNull {
                it.str("format") == "JPEG" &&
                    it.str("source") == "original" &&
                    it.str("name")?.endsWith(".jpg") == true
            }
            ?: files.firstOrNull { it.str("name")?.endsWith(".jpg") == true }
    }

    companion object {
        /** Kept here so the wiki screen can render the canonical CAA link. */
        fun releasePageUrl(releaseMbid: String?): String? =
            releaseMbid?.takeIf { it.isNotBlank() }?.let { "https://musicbrainz.org/release/$it" }

        fun link(releaseMbid: String?): MusicLink? = releasePageUrl(releaseMbid)
            ?.let { MusicLink("Обложка (Cover Art Archive)", "https://coverartarchive.org/release/$releaseMbid", it) }
    }
}

/**
 * `archive.org` serves file names raw inside a path segment; spaces have to be
 * percent-encoded but the rest of the name must not be.
 */
internal fun encodePathSegment(value: String): String = buildString {
    value.toByteArray(Charsets.UTF_8).forEach { byte ->
        val code = byte.toInt() and 0xFF
        val char = code.toChar()
        if (char.isLetterOrDigit() || char in "-_.~") {
            append(char)
        } else {
            append('%').append("%02X".format(code))
        }
    }
}
