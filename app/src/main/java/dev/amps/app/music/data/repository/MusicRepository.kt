package dev.amps.app.music.data.repository

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import dev.amps.app.music.data.local.MusicDatabase
import dev.amps.app.music.data.local.PlaylistEntity
import dev.amps.app.music.data.local.DownloadEntity
import dev.amps.app.music.data.local.TrackEntity
import dev.amps.app.music.model.DownloadStatus
import dev.amps.app.music.model.DownloadTask
import dev.amps.app.music.model.Playlist
import dev.amps.app.music.model.Track
import dev.amps.app.music.model.TrackSource
import java.util.UUID

/**
 * Репозиторій музичної бібліотеки — єдине джерело правди для UI.
 * Об'єднує локальну БД (Room) з віддаленими API через MusicApi.
 */
class MusicRepository(private val context: Context) {

    private val db = MusicDatabase.getInstance(context)
    private val trackDao = db.trackDao()
    private val playlistDao = db.playlistDao()
    private val downloadDao = db.downloadDao()

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    // ===== ТРЕКИ =====

    /** Всі треки (Flow для UI). */
    val allTracks: Flow<List<Track>> = trackDao.getAll().map { it.map(TrackEntity::toModel) }

    /** Треки за джерелом. */
    fun tracksBySource(source: TrackSource): Flow<List<Track>> =
        trackDao.getBySource(source.name).map { it.map(TrackEntity::toModel) }

    /** Локальні треки (імпортовані через SAF). */
    val localTracks: Flow<List<Track>> = tracksBySource(TrackSource.LOCAL)

    /** Завантажені для офлайну треки. */
    val downloadedTracks: Flow<List<Track>> = tracksBySource(TrackSource.DOWNLOADED)

    /** Улюблені треки. */
    val favoriteTracks: Flow<List<Track>> = trackDao.getFavorites().map { it.map(TrackEntity::toModel) }

    /** Пошук треків. */
    fun searchTracks(query: String): Flow<List<Track>> =
        trackDao.search("%$query%").map { it.map(TrackEntity::toModel) }

    /** Отримати трек за ID. */
    suspend fun getTrack(id: String): Track? = trackDao.getById(id)?.toModel()

    /** Отримати треки за списком ID (для плейлистів). */
    suspend fun getTracksByIds(ids: List<String>): List<Track> =
        trackDao.getByIds(ids).map(TrackEntity::toModel)

    /** Додати/оновити трек. */
    suspend fun upsertTrack(track: Track) {
        trackDao.insert(TrackEntity.fromModel(track))
    }

    /** Додати багато треків (наприклад, після пошуку API). */
    suspend fun upsertTracks(tracks: List<Track>) {
        trackDao.insertAll(tracks.map(TrackEntity::fromModel))
    }

    /** Видалити трек. */
    suspend fun deleteTrack(id: String) {
        trackDao.deleteById(id)
        // Також видаляємо з плейлистів (просто оновлюємо їх)
        removeTrackFromAllPlaylists(id)
    }

    /** Встановити/зняти улюблений. */
    suspend fun setFavorite(id: String, favorite: Boolean) {
        trackDao.setFavorite(id, favorite)
    }

    /** Збільшити лічильник відтворень. */
    suspend fun incrementPlayCount(id: String) {
        trackDao.incrementPlayCount(id, System.currentTimeMillis())
    }

    /** Видалити всі треки з джерела (наприклад, при повторному пошуку). */
    suspend fun clearSource(source: TrackSource) {
        trackDao.deleteBySource(source.name)
    }

    // ===== ПЛЕЙЛИСТИ =====

    /** Всі плейлисти. */
    val allPlaylists: Flow<List<Playlist>> = playlistDao.getAll().map { it.map(PlaylistEntity::toModel) }

    suspend fun getPlaylist(id: String): Playlist? = playlistDao.getById(id)?.toModel()

    suspend fun createPlaylist(name: String): Playlist {
        val id = UUID.randomUUID().toString()
        val playlist = Playlist(id = id, name = name)
        playlistDao.insert(PlaylistEntity.fromModel(playlist))
        return playlist
    }

    suspend fun updatePlaylist(playlist: Playlist) {
        val updated = playlist.copy(updatedAt = System.currentTimeMillis())
        playlistDao.update(PlaylistEntity.fromModel(updated))
    }

    suspend fun deletePlaylist(id: String) {
        playlistDao.deleteById(id)
    }

    /** Додати трек до плейлиста. */
    suspend fun addTrackToPlaylist(playlistId: String, trackId: String) {
        playlistDao.getById(playlistId)?.let { entity ->
            val playlist = entity.toModel()
            if (trackId !in playlist.trackIds) {
                val updated = playlist.copy(
                    trackIds = playlist.trackIds + trackId,
                    updatedAt = System.currentTimeMillis()
                )
                playlistDao.update(PlaylistEntity.fromModel(updated))
            }
        }
    }

    /** Видалити трек з плейлиста. */
    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        playlistDao.getById(playlistId)?.let { entity ->
            val playlist = entity.toModel()
            val updated = playlist.copy(
                trackIds = playlist.trackIds.filter { it != trackId },
                updatedAt = System.currentTimeMillis()
            )
            playlistDao.update(PlaylistEntity.fromModel(updated))
        }
    }

    /** Видалити трек з усіх плейлистів. */
    private suspend fun removeTrackFromAllPlaylists(trackId: String) {
        val playlists = playlistDao.getAll().first()
        playlists.forEach { entity ->
            val playlist = entity.toModel()
            if (trackId in playlist.trackIds) {
                val updated = playlist.copy(
                    trackIds = playlist.trackIds.filter { it != trackId },
                    updatedAt = System.currentTimeMillis()
                )
                playlistDao.update(PlaylistEntity.fromModel(updated))
            }
        }
    }

    // ===== ЗАВАНТАЖЕННЯ =====

    /** Всі завдання завантаження. */
    val allDownloads: Flow<List<DownloadTask>> = downloadDao.getAll().map { it.map(DownloadEntity::toModel) }

    /** Активні завантаження (PENDING, DOWNLOADING). */
    val activeDownloads: Flow<List<DownloadTask>> = downloadDao
        .getByStatuses(listOf(DownloadStatus.PENDING.name, DownloadStatus.DOWNLOADING.name))
        .map { it.map(DownloadEntity::toModel) }

    /** Завантаження за треком. */
    suspend fun getDownloadByTrack(trackId: String): DownloadTask? =
        downloadDao.getByTrackId(trackId)?.toModel()

    /** Створити нове завантаження. */
    suspend fun createDownload(task: DownloadTask) {
        downloadDao.insert(DownloadEntity.fromModel(task))
    }

    /** Оновити прогрес завантаження. */
    suspend fun updateDownloadProgress(id: String, progress: Float, bytesDownloaded: Long, totalBytes: Long) {
        downloadDao.getById(id)?.let { entity ->
            val updated = entity.toModel().copy(
                status = DownloadStatus.DOWNLOADING,
                progress = progress,
                bytesDownloaded = bytesDownloaded,
                totalBytes = totalBytes,
            )
            downloadDao.update(DownloadEntity.fromModel(updated))
        }
    }

    /** Завершити завантаження (успіх). */
    suspend fun completeDownload(id: String, filePath: String) {
        downloadDao.getById(id)?.let { entity ->
            val updated = entity.toModel().copy(
                status = DownloadStatus.COMPLETED,
                progress = 1f,
                filePath = filePath,
                completedAt = System.currentTimeMillis(),
            )
            downloadDao.update(DownloadEntity.fromModel(updated))

            // Також оновлюємо трек як DOWNLOADED
            trackDao.getById(entity.trackId)?.let { track ->
                val updatedTrack = track.toModel().copy(
                    source = TrackSource.DOWNLOADED,
                    filePath = filePath,
                    downloadedAt = System.currentTimeMillis(),
                )
                trackDao.insert(TrackEntity.fromModel(updatedTrack))
            }
        }
    }

    /** Позначити завантаження як помилку. */
    suspend fun failDownload(id: String, error: String) {
        downloadDao.getById(id)?.let { entity ->
            val updated = entity.toModel().copy(
                status = DownloadStatus.FAILED,
                error = error,
            )
            downloadDao.update(DownloadEntity.fromModel(updated))
        }
    }

    /** Видалити завантаження. */
    suspend fun deleteDownload(id: String) {
        downloadDao.deleteById(id)
    }

    /** Очистити завершені/провалені завантаження. */
    suspend fun clearCompletedDownloads() {
        downloadDao.deleteByStatus(DownloadStatus.COMPLETED.name)
        downloadDao.deleteByStatus(DownloadStatus.FAILED.name)
        downloadDao.deleteByStatus(DownloadStatus.CANCELLED.name)
    }

    /** Отримати треки для плейлиста. */
    suspend fun getPlaylistTracks(playlist: Playlist): List<Track> = getTracksByIds(playlist.trackIds)

    fun shutdown() {
        scope.cancel()
    }
}