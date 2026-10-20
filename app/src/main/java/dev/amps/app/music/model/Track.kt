package dev.amps.app.music.model

import kotlinx.serialization.Serializable

/**
 * Трек у бібліотеці.
 *
 * [source] — звідки трек: LOCAL (імпорт з пристрою), JAMENDO, FREE_MUSIC_ARCHIVE,
 * INTERNET_ARCHIVE, CCMIXTER, OPENVERSE, DOWNLOADED (завантажено для офлайну).
 * [filePath] — локальний шлях до файлу (для LOCAL і DOWNLOADED).
 * [streamUrl] — пряме посилання на стрім (для API джерел).
 * [coverUrl] — обкладинка альбому/треку.
 * [durationMs] — тривалість в мілісекундах.
 */
@Serializable
data class Track(
    val id: String = "",                    // UUID або remote ID
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val source: TrackSource = TrackSource.LOCAL,
    val remoteId: String? = null,           // ID в джерелі (jamendo_id, fma_id, тощо)
    val filePath: String? = null,           // локальний файл (filesDir/music/...)
    val streamUrl: String? = null,          // пряме посилання на аудіо
    val coverUrl: String? = null,           // обкладинка
    val durationMs: Long = 0,               // тривалість
    val genre: String? = null,
    val year: Int? = null,
    val license: String? = null,            // CC-BY, CC0, тощо
    val downloadedAt: Long = 0,             // коли завантажено (для DOWNLOADED)
    val playCount: Int = 0,
    val lastPlayedAt: Long = 0,
    val isFavorite: Boolean = false,
) {
    /** Чи доступний для відтворення прямо зараз (локально або по стріму). */
    val isAvailable: Boolean
        get() = when (source) {
            TrackSource.LOCAL, TrackSource.DOWNLOADED -> filePath?.isNotBlank() == true
            else -> streamUrl?.isNotBlank() == true
        }

    /** Відображуваний джерело для UI. */
    val sourceLabel: String
        get() = when (source) {
            TrackSource.LOCAL -> "Локальний"
            TrackSource.JAMENDO -> "Jamendo"
            TrackSource.FREE_MUSIC_ARCHIVE -> "Free Music Archive"
            TrackSource.INTERNET_ARCHIVE -> "Internet Archive"
            TrackSource.CCMIXTER -> "ccMixter"
            TrackSource.OPENVERSE -> "Openverse"
            TrackSource.DOWNLOADED -> "Завантажено"
        }
}

/** Джерело треку. */
enum class TrackSource {
    LOCAL,              // імпорт через SAF
    JAMENDO,
    FREE_MUSIC_ARCHIVE,
    INTERNET_ARCHIVE,
    CCMIXTER,
    OPENVERSE,
    DOWNLOADED,         // завантажено через WorkManager для офлайну
}

/** Плейлист користувача. */
@Serializable
data class Playlist(
    val id: String = "",
    val name: String = "",
    val trackIds: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val coverUrl: String? = null,           // обкладинка плейлиста (перший трек)
)

/** Статус завантаження. */
enum class DownloadStatus {
    PENDING,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

/** Завантаження треку для офлайну. */
@Serializable
data class DownloadTask(
    val id: String = "",
    val trackId: String = "",
    val trackTitle: String = "",
    val trackArtist: String = "",
    val streamUrl: String = "",
    val coverUrl: String? = null,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val progress: Float = 0f,               // 0..1
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0,
    val filePath: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0,
)