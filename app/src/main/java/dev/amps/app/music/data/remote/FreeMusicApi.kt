package dev.amps.app.music.data.remote

import dev.amps.app.music.model.Track
import dev.amps.app.music.model.TrackSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Клієнт для безкоштовних музичних API.
 *
 * Джерела:
 * - Jamendo (потребує client_id — безкоштовна реєстрація на dev.jamendo.com)
 * - Free Music Archive (публічний API)
 * - Internet Archive (API без ключа)
 * - ccMixter (RSS/Atom feed)
 * - Openverse (WordPress API)
 */
class FreeMusicApi(
    private val jamendoClientId: String = "YOUR_JAMENDO_CLIENT_ID", // TODO: додати в BuildConfig
) {

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpRequestRetry) {
            maxRetries = 3
        }
        expectSuccess = false
    }

    private val jsonDecoder = Json { ignoreUnknownKeys = true }

    private suspend fun <T> parseResponse(response: io.ktor.client.statement.HttpResponse, type: kotlinx.serialization.KSerializer<T>): T {
        val bodyText = response.bodyAsText()
        return jsonDecoder.decodeFromString(type, bodyText)
    }

    // ===== JAMENDO =====

    /** Пошук треків на Jamendo. */
    suspend fun searchJamendo(query: String, limit: Int = 20): Result<List<Track>> = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.jamendo.com/v3.0/tracks/?client_id=$jamendoClientId&format=json&search=$query&limit=$limit&include=musicinfo"
            val response = client.get(url)
            if (response.status == HttpStatusCode.OK) {
                val body = parseResponse(response, JamendoResponse.serializer())
                val tracks = body.results.map { it.toTrack() }
                Result.success(tracks)
            } else {
                Result.failure(Exception("Jamendo API error: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Отримати популярні треки на Jamendo. */
    suspend fun getPopularJamendo(limit: Int = 20): Result<List<Track>> = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.jamendo.com/v3.0/tracks/?client_id=$jamendoClientId&format=json&order=popularity_total&limit=$limit&include=musicinfo"
            val response = client.get(url)
            if (response.status == HttpStatusCode.OK) {
                val body = parseResponse(response, JamendoResponse.serializer())
                Result.success(body.results.map { it.toTrack() })
            } else {
                Result.failure(Exception("Jamendo API error: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ===== INTERNET ARCHIVE =====

    /** Пошук на Internet Archive. */
    suspend fun searchInternetArchive(query: String, limit: Int = 20): Result<List<Track>> = withContext(Dispatchers.IO) {
        try {
            val url = "https://archive.org/advancedsearch.php?q=$query&fl[]=identifier,title,creator,date,mediatype,description,cover&rows=$limit&output=json"
            val response = client.get(url)
            if (response.status == HttpStatusCode.OK) {
                val body = parseResponse(response, ArchiveSearchResponse.serializer())
                if (body.response != null) {
                    val tracks = body.response!!.docs.map { it.toTrack() }
                    Result.success(tracks)
                } else {
                    Result.failure(Exception("Internet Archive: empty response"))
                }
            } else {
                Result.failure(Exception("Internet Archive error: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ===== OPENVERSSE (WordPress) =====

    /** Пошук на Openverse. */
    suspend fun searchOpenverse(query: String, limit: Int = 20): Result<List<Track>> = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.openverse.org/v1/audio/?q=$query&page_size=$limit"
            val response = client.get(url)
            if (response.status == HttpStatusCode.OK) {
                val body = parseResponse(response, OpenverseResponse.serializer())
                val tracks = body.results.map { it.toTrack() }
                Result.success(tracks)
            } else {
                Result.failure(Exception("Openverse error: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ===== УНІВЕРСАЛЬНИЙ ПОШУК =====

    /** Пошук по всіх джерелах одночасно. */
    suspend fun searchAll(query: String, limitPerSource: Int = 10): Map<TrackSource, Result<List<Track>>> = withContext(Dispatchers.IO) {
        val jamendo = searchJamendo(query, limitPerSource)
        val archive = searchInternetArchive(query, limitPerSource)
        val openverse = searchOpenverse(query, limitPerSource)
        mapOf(
            TrackSource.JAMENDO to jamendo,
            TrackSource.INTERNET_ARCHIVE to archive,
            TrackSource.OPENVERSE to openverse,
        )
    }

    /** Пошук треків — об'єднує результати з усіх джерел у один список. */
    suspend fun searchTracks(query: String, limitPerSource: Int = 10): Result<List<Track>> = withContext(Dispatchers.IO) {
        val allResults = searchAll(query, limitPerSource)
        val allTracks = mutableListOf<Track>()
        allResults.forEach { (_, result) ->
            result.getOrNull()?.let { tracks ->
                allTracks.addAll(tracks)
            }
        }
        allTracks.sortByDescending { it.streamUrl != null }
        Result.success(allTracks)
    }

    // ===== DTO для відповідей =====

    @Serializable
    private data class JamendoResponse(val results: List<JamendoTrack> = emptyList())

    @Serializable
    private data class JamendoTrack(
        val id: Int,
        val name: String,
        val artist_name: String,
        val album_name: String?,
        val duration: Int,
        val audiodownload: String?,
        val audio: String?,
        val image: String?,
        val license_ccurl: String?,
        val genre: String?,
        val releasedate: String?,
    ) {
        fun toTrack(): Track = Track(
            id = "jamendo_$id",
            title = name,
            artist = artist_name,
            album = album_name ?: "",
            source = TrackSource.JAMENDO,
            remoteId = id.toString(),
            streamUrl = audiodownload ?: audio,
            coverUrl = image,
            durationMs = duration.toLong() * 1000,
            genre = genre,
            year = releasedate?.take(4)?.toIntOrNull(),
            license = license_ccurl?.substringAfterLast("/"),
        )
    }

    @Serializable
    private data class ArchiveSearchResponse(
        val response: ArchiveResponse = ArchiveResponse(),
    )

    @Serializable
    private data class ArchiveResponse(
        val docs: List<ArchiveDoc> = emptyList(),
    )

    @Serializable
    private data class ArchiveDoc(
        val identifier: String,
        val title: String,
        val creator: String?,
        val date: String?,
        val mediatype: String?,
        val description: String?,
        val cover: String?,
    ) {
        fun toTrack(): Track = Track(
            id = "archive_$identifier",
            title = title,
            artist = creator ?: "Невідомий",
            album = "",
            source = TrackSource.INTERNET_ARCHIVE,
            remoteId = identifier,
            streamUrl = "https://archive.org/download/$identifier/$identifier.mp3",
            coverUrl = cover?.let { "https://archive.org/services/img/$identifier" },
            durationMs = 0,
            genre = mediatype,
            year = date?.take(4)?.toIntOrNull(),
            license = "Public Domain / CC",
        )
    }

    @Serializable
    private data class OpenverseResponse(
        val results: List<OpenverseTrack> = emptyList(),
    )

    @Serializable
    private data class OpenverseTrack(
        val identifier: String,
        val title: String,
        val creator: String?,
        val creator_url: String?,
        val tags: List<String>? = emptyList(),
        val url: String,
        val thumbnail: String?,
        val audio: String?,
        val audio_set: Map<String, String>?,
        val duration: Double?,
        val license: String,
        val license_version: String?,
        val license_url: String?,
        val attribution: String?,
    ) {
        fun toTrack(): Track {
            val audio_url = audio ?: audio_set?.values?.firstOrNull()
            return Track(
                id = "openverse_$identifier",
                title = title,
                artist = creator ?: "Невідомий",
                album = "",
                source = TrackSource.OPENVERSE,
                remoteId = identifier,
                streamUrl = audio_url,
                coverUrl = thumbnail,
                durationMs = (duration ?: 0.0).toLong() * 1000,
                license = "$license ${license_version ?: ""}",
            )
        }
    }

    sealed class Result<T> {
        data class Success<T>(val value: T) : Result<T>()
        data class Failure<T>(val error: Exception) : Result<T>()

        companion object {
            fun <T> success(value: T): Result<T> = Success(value)
            fun <T> failure(error: Exception): Result<T> = Failure(error)
        }

        fun isSuccess(): Boolean = this is Success<*>
        fun isFailure(): Boolean = this is Failure<*>
        fun getOrNull(): T? = (this as? Success)?.value
        fun getOrElse(default: T): T = (this as? Success)?.value ?: default
        fun exceptionOrNull(): Exception? = (this as? Failure)?.error
    }
}