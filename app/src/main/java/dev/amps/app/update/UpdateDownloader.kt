package dev.amps.app.update

import android.content.Context
import android.os.Environment
import android.util.Log
import dev.amps.app.util.formatBytes
import dev.amps.app.util.readableMessage
import dev.amps.app.util.sanitizeFileName
import dev.amps.app.util.sha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Sub-folder of the app's external files dir that holds release APKs. */
private const val DOWNLOAD_SUBDIR = "updates"

private const val BUFFER_SIZE = 64 * 1024
private const val PROGRESS_INTERVAL_MS = 150L

/** A release APK is a few megabytes; anything past this is a broken download. */
private const val MAX_HASHABLE_BYTES = 256L * 1024 * 1024L

private const val TAG = "AMPS/Update"

/**
 * Streams the release APK to disk.
 *
 * The file is written next to its target as `<name>.apk.part` and only renamed
 * once the stream ended cleanly, so an interrupted download can never be
 * mistaken for a finished one — the partial file is deleted on any failure.
 * Progress is reported as raw byte counts, which the screen turns into a
 * determinate progress bar.
 */
class UpdateDownloader(
    private val context: Context,
    private val client: OkHttpClient = updateHttpClient(),
) {

    /**
     * `getExternalFilesDir(DIRECTORY_DOWNLOADS)/updates` — app-private external
     * storage, so no storage permission is needed and the installer can read it.
     */
    fun downloadDirectory(): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.getExternalFilesDir(null)
            ?: context.filesDir
        return File(root, DOWNLOAD_SUBDIR)
    }

    /**
     * Downloads [release]'s APK and returns the absolute path of the finished
     * file. The SHA-256 of that file is logged (never shown as a trust anchor:
     * GitHub publishes no checksum, so the digest is a diagnostic, not proof).
     *
     * @throws IOException with a Russian, user-facing message.
     */
    suspend fun download(
        release: UpdateRelease,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): String = withContext(Dispatchers.IO) {
        val directory = downloadDirectory()
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Не удалось создать папку для обновления")
        }

        val target = File(directory, "${sanitizeFileName(release.tag)}.apk")
        val partial = File(directory, "${target.name}.part")
        if (partial.exists()) partial.delete()

        val request = Request.Builder()
            .url(release.apk.url)
            .header("User-Agent", UPDATE_USER_AGENT)
            .header("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")
            .get()
            .build()

        try {
            val written = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Сервер релизов ответил HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("Сервер прислал пустой файл")

                // GitHub answers the asset redirect with a Content-Length; the
                // size published in the JSON is the fallback when it is absent.
                val contentLength = body.contentLength()
                val total = if (contentLength > 0L) contentLength else release.apk.sizeBytes

                onProgress(0L, total)

                var read = 0L
                var lastEmit = 0L
                val buffer = ByteArray(BUFFER_SIZE)

                body.byteStream().use { input ->
                    FileOutputStream(partial).use { output ->
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            read += count

                            val now = System.currentTimeMillis()
                            if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                                lastEmit = now
                                onProgress(read, total)
                            }
                        }
                        output.flush()
                    }
                }

                // A truncated body is the classic silent failure of a resumed
                // download: Content-Length says more bytes should have come.
                if (contentLength > 0L && read != contentLength) {
                    throw IOException("Файл докачался не полностью: $read из $contentLength байт")
                }
                if (contentLength <= 0L && release.apk.sizeBytes > 0L && read != release.apk.sizeBytes) {
                    Log.w(TAG, "Размер APK ($read) не совпал с размером в релизе (${release.apk.sizeBytes})")
                }

                read
            }

            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }

            val sha256 = sha256Of(target)
            Log.i(
                TAG,
                "AMPS ${release.tag} скачан: ${formatBytes(written) ?: "$written Б"}, " +
                    "sha-256 $sha256, файл ${target.absolutePath}",
            )
            onProgress(written, written)
            target.absolutePath
        } catch (error: CancellationException) {
            partial.delete()
            throw error
        } catch (error: Throwable) {
            partial.delete()
            if (error is IOException) throw error
            throw IOException("Не удалось скачать обновление: ${error.readableMessage()}", error)
        }
    }

    /**
     * SHA-256 of the finished file through the shared [sha256] helper.
     *
     * The whole file is read into one array on purpose — that is the helper's
     * contract — so a pre-sized buffer is used instead of `readBytes()`, whose
     * doubling growth would briefly need twice the APK in memory.
     */
    private fun sha256Of(file: File): String {
        val length = file.length()
        if (length <= 0L) throw IOException("Файл обновления пуст")
        if (length > MAX_HASHABLE_BYTES) {
            throw IOException("Файл обновления подозрительно большой — ${formatBytes(length)}")
        }

        val bytes = ByteArray(length.toInt())
        file.inputStream().use { it.readFully(bytes) }
        return bytes.sha256()
    }
}
