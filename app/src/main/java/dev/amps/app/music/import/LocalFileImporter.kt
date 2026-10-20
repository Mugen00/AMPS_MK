package dev.amps.app.music.import

import android.content.Context
import dev.amps.app.music.data.repository.MusicRepository
import dev.amps.app.music.model.Track
import dev.amps.app.music.model.TrackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Stub імпорт локальних аудіофайлів.
 * Спрощена версія для збірки.
 */
class LocalFileImporter(
    private val context: Context,
    private val repository: MusicRepository,
) {

    /** Запустити системний вибір аудіофайлів (stub). */
    fun pickAudioFiles() {
        // TODO: implement SAF picker
    }

    /** Запустити вибір з кастомним MIME (stub). */
    fun pickAudioFiles(mimeTypes: Array<String>) {
        // TODO: implement SAF picker
    }

    /** Імпортувати файл за шляхом (stub). */
    suspend fun importFile(filePath: String) = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (file.exists()) {
            val ext = file.extension.ifBlank { "mp3" }
            val fileName = "${UUID.randomUUID()}.$ext"
            val destDir = File(context.filesDir, "music")
            destDir.mkdirs()
            val destFile = File(destDir, fileName)
            
            file.copyTo(destFile)

            val track = Track(
                id = "local_$fileName",
                title = file.nameWithoutExtension,
                artist = "Невідомий виконавець",
                album = "",
                source = TrackSource.LOCAL,
                filePath = destFile.absolutePath,
                durationMs = 0,
            )

            repository.upsertTrack(track)
        }
    }
}