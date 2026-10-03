package dev.amps.app.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Кладёт готовый файл в музыкальную библиотеку телефона — `Music/AMPS`.
 *
 * **Почему IS_PENDING обязателен.** Без него файл виден в библиотеке, пока он
 * ещё дописывается. Сканер MediaProvider в этот момент читает обрезанный файл,
 * записывает в базу `duration = 0` и пустые теги — и больше не перечитывает их
 * никогда. Через эту ошибку файл остаётся сломанным до переустановки.
 *
 * **Почему на Android 9+ разрешений не нужно.** Scoped storage разрешает
 * приложению писать в общую папку Music через MediaStore без запроса прав.
 * `WRITE_EXTERNAL_STORAGE` нужен только на API 24–28, где MediaStore ещё
 * требует путь на диске, и именно там нужен отдельный путь.
 */
class MediaStoreImporter(private val context: Context) {

    /** Что получилось на выходе — приложению нужно знать, куда именно лег файл. */
    data class Imported(
        val uri: Uri,
        val displayPath: String,
    )

    /**
     * @param source файл с уже вписанными тегами; копируется, а не перемещается.
     * @param tags теги, которые надо вписать, если [tagBeforeImport] задан.
     */
    suspend fun importMp3(
        source: File,
        displayName: String,
        tags: Id3Writer.Tags? = null,
        folder: String = "${Environment.DIRECTORY_MUSIC}/AMPS",
        tagBeforeImport: Boolean = true,
    ): Imported = withContext(Dispatchers.IO) {
        val prepared = if (tagBeforeImport && tags != null) {
            val tagged = File(source.parentFile, "${source.name}.tagged")
            Id3Writer.applyTag(source, tagged, Id3Writer.buildTag(tags))
            tagged
        } else {
            source
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                importModern(prepared, displayName, folder)
            } else {
                importLegacy(prepared, displayName, folder)
            }
        } finally {
            if (tagBeforeImport && tags != null && prepared !== source) {
                prepared.delete()
            }
        }
    }

    private fun importModern(source: File, displayName: String, folder: String): Imported {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, folder)
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Система отказалась создавать запись в музыкальной библиотеке")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            } ?: throw IOException("Не удалось открыть созданный файл на запись")
        } catch (error: Throwable) {
            // Битый pending-item остаётся в базе навсегда и занимает имя.
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
        // Публикуем: MediaProvider сканирует файл и подхватывает ID3 с обложкой.
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
            null,
            null,
        )
        return Imported(uri, "$folder/$displayName")
    }

    @Suppress("DEPRECATION")
    private suspend fun importLegacy(source: File, displayName: String, folder: String): Imported {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), folder.substringAfter('/'))
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Не удалось создать папку $dir")
        val target = File(dir, displayName)
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        }

        val scanned = suspendCancellableCoroutine { continuation ->
            // Колбэк отдаёт (путь, uri) — нужен второй, первый строка.
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("audio/mpeg")) { _, found ->
                if (continuation.isActive) continuation.resume(found)
            }
        }
        // На API 24–28 IS_MUSIC ещё можно выставить, и без этого файл не попадёт
        // в раздел «Музыка» ни в одном системном плеере.
        scanned?.let { scannedUri ->
            runCatching {
                context.contentResolver.update(
                    scannedUri,
                    ContentValues().apply { put(MediaStore.Audio.Media.IS_MUSIC, 1) },
                    null,
                    null,
                )
            }
        }
        return Imported(scanned ?: Uri.fromFile(target), target.absolutePath)
    }

    /**
     * Убирает записи, которые остались pending: это происходит, когда процесс
     * убили между вставкой и публикацией. Чистить надо на старте — иначе папка
     * постепенно забьётся невидимыми файлами с занятыми именами.
     */
    suspend fun clearStalePending(displayName: String): Int = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext 0
        val resolver = context.contentResolver
        val selection = "${MediaStore.Audio.Media.IS_PENDING} = 1 AND " +
            "${MediaStore.Audio.Media.DISPLAY_NAME} = ?"
        runCatching {
            resolver.delete(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                selection,
                arrayOf(displayName),
            )
        }.getOrDefault(0)
    }
}
