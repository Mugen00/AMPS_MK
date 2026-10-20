package dev.amps.app.music.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.amps.app.music.data.repository.MusicRepository
import dev.amps.app.music.model.DownloadStatus
import dev.amps.app.music.model.DownloadTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stub WorkManager Worker для завантаження треків.
 * Спрощена версія для збірки.
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val repository = MusicRepository(applicationContext)
    private val downloadId = inputData.getString("DOWNLOAD_ID") ?: ""

    companion object {
        const val ARG_DOWNLOAD_ID = "DOWNLOAD_ID"
        const val NOTIFICATION_ID = 2001
        const val CHANNEL_ID = "amps_music_download"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (downloadId.isBlank()) {
            return@withContext Result.failure()
        }
        // Stub implementation - always success for now
        val task = repository.getDownloadByTrack(downloadId)
        if (task != null) {
            repository.completeDownload(downloadId, "/stub/path/${task.trackId}.mp3")
        }
        Result.success()
    }
}

/** Хелпер для запуску завантаження. */
object DownloadManager {
    suspend fun enqueueDownload(
        context: Context,
        repository: MusicRepository,
        trackId: String,
        trackTitle: String,
        trackArtist: String,
        streamUrl: String,
        coverUrl: String?,
        totalBytes: Long = 0,
    ) {
        // Stub implementation
        val task = DownloadTask(
            id = trackId,
            trackId = trackId,
            trackTitle = trackTitle,
            trackArtist = trackArtist,
            streamUrl = streamUrl,
            coverUrl = coverUrl,
            totalBytes = totalBytes,
        )
        repository.createDownload(task)
    }

    fun cancelDownload(context: Context, trackId: String) {
        // Stub implementation
    }
}