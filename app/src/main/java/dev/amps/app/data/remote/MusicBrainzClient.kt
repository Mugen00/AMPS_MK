package dev.amps.app.data.remote

import dev.amps.app.data.model.MusicLink
import dev.amps.app.data.model.MusicSearchResult
import dev.amps.app.data.model.MusicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * MusicBrainz is metadata only — it never publishes an audio file — but it is
 * the only keyless source that mints a durable MBID, and that MBID is the join
 * key for the Cover Art Archive and for the release list.
 *
 * Two house rules are enforced here rather than left to the caller:
 *
 *  * every request carries a contactable `User-Agent`. MusicBrainz classifies
 *    the classic `Java` / `Apache-HttpClient` agents as anonymous and answers
 *    them with 503 bursts, which is exactly what a stock OkHttp client sends.
 *  * at most one request per [MIN_SPACING_MS], serialised through a mutex so
 *    two coroutines cannot slip through the same window.
 */
internal const val AMPS_USER_AGENT = "AMPS/1.0 ( https://github.com/amps-app )"

class MusicBrainzClient(private val client: OkHttpClient) {

    private val base = "https://musicbrainz.org/ws/2"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private val throttle = Mutex()
    private var lastRequestAt = 0L

    /**
     * Free-text search over recordings. Bootlegs are dropped client side,
     * because `status=official` is not usable on a search query and a wall of
     * "1996-08-14: Great Woods" rows is useless in a music player.
     */
    suspend fun searchRecordings(query: String, limit: Int = 20): List<MusicSearchResult> =
        withContext(Dispatchers.IO) {
            val url = "$base/recording".toHttpUrl().newBuilder()
                .addQueryParameter("query", "recording:\"${query.trim().replace("\"", " ")}\"")
                .addQueryParameter("fmt", "json")
                .addQueryParameter("limit", limit.coerceIn(1, 100).toString())
                .addQueryParameter("inc", "artist-credits+releases+release-groups")
                .build()
            parseRecordings(fetch(url.toString()), officialOnly = true)
        }

    suspend fun recording(mbid: String): MusicSearchResult? = withContext(Dispatchers.IO) {
        val url = "$base/recording/$mbid".toHttpUrl().newBuilder()
            .addQueryParameter("fmt", "json")
            .addQueryParameter("inc", "artist-credits+releases+release-groups")
            .build()
        parseRecordings(fetch(url.toString()), officialOnly = true).firstOrNull()
    }

    /**
     * "Похожие треки" for the wiki page: everything else the same artist
     * recorded. Browsing by MBID is exact, so it is tried first; a name query
     * is the fallback for rows that only ever came from iTunes.
     */
    suspend fun otherRecordingsByArtist(
        artistMbid: String?,
        artistName: String?,
        limit: Int = 12,
    ): List<MusicSearchResult> = withContext(Dispatchers.IO) {
        if (!artistMbid.isNullOrBlank()) {
            val url = "$base/recording".toHttpUrl().newBuilder()
                .addQueryParameter("artist", artistMbid)
                .addQueryParameter("fmt", "json")
                .addQueryParameter("limit", limit.coerceIn(1, 100).toString())
                .addQueryParameter("inc", "artist-credits+releases+release-groups")
                .build()
            val browsed = runCatching { parseRecordings(fetch(url.toString()), true) }
                .getOrDefault(emptyList())
            if (browsed.isNotEmpty()) return@withContext browsed
        }
        if (artistName.isNullOrBlank()) return@withContext emptyList()

        val url = "$base/recording".toHttpUrl().newBuilder()
            .addQueryParameter("query", "artist:\"${artistName.replace("\"", " ")}\"")
            .addQueryParameter("fmt", "json")
            .addQueryParameter("limit", (limit + 6).coerceIn(1, 100).toString())
            .addQueryParameter("inc", "artist-credits+releases+release-groups")
            .build()
        runCatching { parseRecordings(fetch(url.toString()), true) }
            .getOrDefault(emptyList())
    }

    private suspend fun fetch(url: String): String {
        awaitSlot()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", AMPS_USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 503) {
                throw IOException("MusicBrainz временно ограничил запросы (503) — попробуйте позже")
            }
            if (!response.isSuccessful) {
                throw IOException("MusicBrainz ответил HTTP ${response.code}")
            }
            text
        }
    }

    /**
     * Holds the flat 1 request/second slot. A mutex rather than a token bucket:
     * the limit is per source IP and a bucket would let a burst through that the
     * server answers with 503.
     */
    private suspend fun awaitSlot() {
        throttle.withLock {
            val waitFor = lastRequestAt + MIN_SPACING_MS - System.currentTimeMillis()
            if (waitFor > 0) delay(waitFor)
            lastRequestAt = System.currentTimeMillis()
        }
    }

    /** Handles both the search envelope and the single-object lookup response. */
    private fun parseRecordings(payload: String, officialOnly: Boolean): List<MusicSearchResult> {
        val root = json.parseToJsonElement(payload) as? JsonObject ?: return emptyList()
        val rows = root.arr("recordings").objects().ifEmpty { listOf(root) }
        return rows.mapNotNull { it.toResult(officialOnly) }
    }

    private fun JsonObject.toResult(officialOnly: Boolean): MusicSearchResult? {
        val mbid = str("id") ?: return null
        val title = str("title") ?: return null
        val releases = arr("releases").objects()
        val chosen = releases.firstOrNull { it.str("status").equals("Official", ignoreCase = true) }
            ?: releases.firstOrNull().takeIf { !officialOnly }
        val group = chosen?.obj("release-group")
        val media = chosen?.arr("media")?.objects()?.firstOrNull()
        val track = media?.arr("track")?.objects()?.firstOrNull()
        val artistCredit = arr("artist-credit").objects()
        val artistMbid = artistCredit.firstNotNullOfOrNull { it.obj("artist")?.str("id") }
        val joinedArtist = artistCredit.joinToString("") { credit ->
            credit.str("name").orEmpty() + credit.str("joinphrase").orEmpty()
        }.takeIf { it.isNotBlank() }

        return MusicSearchResult(
            source = MusicSource.MUSICBRAINZ,
            sourceId = mbid,
            title = title,
            artist = joinedArtist ?: artistCredit.firstNotNullOfOrNull { it.str("name") },
            album = chosen?.str("title"),
            year = (group?.str("first-release-date") ?: chosen?.str("date"))?.take(4)?.toIntOrNull(),
            genre = group?.arr("tags")?.objects()?.firstOrNull()?.str("name"),
            // `length` is milliseconds and is frequently null on this endpoint.
            durationSec = long("length")?.let { it / 1000L }?.toInt(),
            versionHint = str("disambiguation"),
            recordingMbid = mbid,
            releaseMbid = chosen?.str("id"),
            artistMbid = artistMbid,
            releaseStatus = chosen?.str("status"),
            trackNo = track?.str("number")?.toIntOrNull(),
            links = buildList {
                add(MusicLink("MusicBrainz", "https://musicbrainz.org/recording/$mbid", "запись в базе"))
                chosen?.str("id")?.let { add(MusicLink("Релиз", "https://musicbrainz.org/release/$it")) }
                artistMbid?.let { add(MusicLink("Исполнитель", "https://musicbrainz.org/artist/$it")) }
            },
        )
    }

    private companion object {
        /** Documented policy is 1 request/second per IP; 1.1 s leaves head-room. */
        const val MIN_SPACING_MS = 1_100L
    }
}
