package dev.amps.app.data.remote

import dev.amps.app.data.model.MusicLink
import dev.amps.app.data.model.MusicSearchResult
import dev.amps.app.data.model.MusicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

/**
 * iTunes Search API — metadata only.
 *
 * The response also carries `previewUrl`, Apple's official 30 second AAC clip.
 * It is deliberately not parsed: it is not a file, it may not be persisted, and
 * a "download" button next to it would be exactly the kind of thing that turns
 * an identifier into a rip. This client answers "what is this song", nothing
 * else.
 */
class ItunesClient(private val client: OkHttpClient) {

    private val endpoint = "https://itunes.apple.com"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    suspend fun searchSongs(term: String, limit: Int = 20): List<MusicSearchResult> =
        withContext(Dispatchers.IO) {
            val url = "$endpoint/search".toHttpUrl().newBuilder()
                .addQueryParameter("term", term.trim())
                .addQueryParameter("entity", "song")
                .addQueryParameter("limit", limit.coerceIn(1, 200).toString())
                .addQueryParameter("country", defaultCountry())
                .addQueryParameter("media", "music")
                .build()
            parseResults(get(url.toString()))
        }

    suspend fun lookupSong(trackId: Long): MusicSearchResult? = withContext(Dispatchers.IO) {
        val url = "$endpoint/lookup".toHttpUrl().newBuilder()
            .addQueryParameter("id", trackId.toString())
            .addQueryParameter("entity", "song")
            .addQueryParameter("country", defaultCountry())
            .build()
        parseResults(get(url.toString())).firstOrNull()
    }

    /**
     * `…/100x100bb.jpg` is a two-axis placeholder: the size has to replace both
     * occurrences or the CDN hands back the 100px original.
     */
    fun artwork(url: String?, size: Int): String? {
        val clean = url?.takeIf { it.isNotBlank() } ?: return null
        val edge = size.coerceIn(30, 1200)
        return if (clean.contains("100x100bb")) {
            clean.replace("100x100bb", "${edge}x${edge}bb")
        } else {
            clean
        }
    }

    private fun get(url: String): JsonArray {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", AMPS_USER_AGENT)
            .get()
            .build()
        val text = client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("iTunes ответил HTTP ${response.code}")
            body
        }
        val parsed = json.parseToJsonElement(text) as? JsonObject
            ?: throw IllegalStateException("iTunes вернул не-JSON ответ")
        return parsed.arr("results")
    }

    private fun parseResults(results: JsonArray): List<MusicSearchResult> =
        results.objects().mapNotNull { row ->
            val trackId = row.long("trackId") ?: return@mapNotNull null
            val title = row.str("trackName") ?: return@mapNotNull null
            val artist = row.str("artistName")
            val collectionId = row.long("collectionId")
            MusicSearchResult(
                source = MusicSource.ITUNES,
                sourceId = trackId.toString(),
                title = title,
                artist = artist,
                album = row.str("collectionName"),
                year = row.str("releaseDate")?.take(4)?.toIntOrNull(),
                genre = row.str("primaryGenreName"),
                durationSec = row.long("trackTimeMillis")?.let { it / 1000L }?.toInt(),
                coverUrl = artwork(row.str("artworkUrl100"), 600),
                trackNo = row.int("trackNumber"),
                links = buildList {
                    row.str("trackViewUrl")?.let { add(MusicLink("iTunes", it, "страница трека")) }
                    row.str("artistViewUrl")?.let { add(MusicLink("Исполнитель в iTunes", it)) }
                    collectionId?.let { add(MusicLink("Альбом в iTunes", "https://music.apple.com/us/album/$it")) }
                },
            )
        }

    private companion object {
        /**
         * The request parameter is ISO-3166 alpha-2 lowercase; the response
         * field is alpha-3 uppercase, so the response value is never fed back in.
         */
        fun defaultCountry(): String {
            val country = Locale.getDefault().country.lowercase(Locale.ROOT)
            return if (country.length == 2 && country.all { it.isLetter() }) country else "us"
        }
    }
}
