package dev.amps.app.music.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import dev.amps.app.music.model.DownloadStatus
import dev.amps.app.music.model.TrackSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Room Entity для треку.
 * Використовуємо String ID (UUID або remote ID) як PrimaryKey.
 */
@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["source"]),
        Index(value = ["isFavorite"]),
        Index(value = ["lastPlayedAt"]),
    ]
)
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val source: TrackSource,
    val remoteId: String?,
    val filePath: String?,
    val streamUrl: String?,
    val coverUrl: String?,
    val durationMs: Long,
    val genre: String?,
    val year: Int?,
    val license: String?,
    val downloadedAt: Long,
    val playCount: Int,
    val lastPlayedAt: Long,
    val isFavorite: Boolean,
) {
    companion object {
        fun fromModel(track: dev.amps.app.music.model.Track): TrackEntity = TrackEntity(
            id = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            source = track.source,
            remoteId = track.remoteId,
            filePath = track.filePath,
            streamUrl = track.streamUrl,
            coverUrl = track.coverUrl,
            durationMs = track.durationMs,
            genre = track.genre,
            year = track.year,
            license = track.license,
            downloadedAt = track.downloadedAt,
            playCount = track.playCount,
            lastPlayedAt = track.lastPlayedAt,
            isFavorite = track.isFavorite,
        )
    }

    fun toModel(): dev.amps.app.music.model.Track = dev.amps.app.music.model.Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        source = source,
        remoteId = remoteId,
        filePath = filePath,
        streamUrl = streamUrl,
        coverUrl = coverUrl,
        durationMs = durationMs,
        genre = genre,
        year = year,
        license = license,
        downloadedAt = downloadedAt,
        playCount = playCount,
        lastPlayedAt = lastPlayedAt,
        isFavorite = isFavorite,
    )
}

/** Room Entity для плейлиста. */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val trackIdsJson: String,           // JSON масив ID треків
    val createdAt: Long,
    val updatedAt: Long,
    val coverUrl: String?,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromModel(playlist: dev.amps.app.music.model.Playlist): PlaylistEntity = PlaylistEntity(
            id = playlist.id,
            name = playlist.name,
            trackIdsJson = json.encodeToString(playlist.trackIds),
            createdAt = playlist.createdAt,
            updatedAt = playlist.updatedAt,
            coverUrl = playlist.coverUrl,
        )
    }

    fun toModel(): dev.amps.app.music.model.Playlist {
        val trackIds = json.decodeFromString<List<String>>(trackIdsJson)
        return dev.amps.app.music.model.Playlist(
            id = id,
            name = name,
            trackIds = trackIds,
            createdAt = createdAt,
            updatedAt = updatedAt,
            coverUrl = coverUrl,
        )
    }
}

/** Room Entity для завдання завантаження. */
@Entity(
    tableName = "downloads",
    indices = [Index(value = ["status"]), Index(value = ["trackId"])]
)
data class DownloadEntity(
    @PrimaryKey val id: String,
    val trackId: String,
    val trackTitle: String,
    val trackArtist: String,
    val streamUrl: String,
    val coverUrl: String?,
    val status: DownloadStatus,
    val progress: Float,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val filePath: String?,
    val error: String?,
    val createdAt: Long,
    val completedAt: Long,
) {
    companion object {
        fun fromModel(task: dev.amps.app.music.model.DownloadTask): DownloadEntity = DownloadEntity(
            id = task.id,
            trackId = task.trackId,
            trackTitle = task.trackTitle,
            trackArtist = task.trackArtist,
            streamUrl = task.streamUrl,
            coverUrl = task.coverUrl,
            status = task.status,
            progress = task.progress,
            bytesDownloaded = task.bytesDownloaded,
            totalBytes = task.totalBytes,
            filePath = task.filePath,
            error = task.error,
            createdAt = task.createdAt,
            completedAt = task.completedAt,
        )
    }

    fun toModel(): dev.amps.app.music.model.DownloadTask = dev.amps.app.music.model.DownloadTask(
        id = id,
        trackId = trackId,
        trackTitle = trackTitle,
        trackArtist = trackArtist,
        streamUrl = streamUrl,
        coverUrl = coverUrl,
        status = status,
        progress = progress,
        bytesDownloaded = bytesDownloaded,
        totalBytes = totalBytes,
        filePath = filePath,
        error = error,
        createdAt = createdAt,
        completedAt = completedAt,
    )
}