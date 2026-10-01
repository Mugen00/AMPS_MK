package dev.amps.app.util

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

data class PreparedImage(
    /** Bytes actually uploaded to the bridge. */
    val bytes: ByteArray,
    val mime: String,
    val fileName: String,
    /** Small inline preview, rendered straight from base64. */
    val previewDataUrl: String,
    val width: Int,
    val height: Int,
)

/**
 * Screenshots straight out of a gallery are 4–12 MP; trace.moe resizes to a
 * fixed grid internally, so shipping the original only wastes the user's mobile
 * data. Everything is capped before upload.
 */
object ImageLoader {

    private const val MAX_UPLOAD_EDGE = 1280
    private const val MAX_PREVIEW_EDGE = 480
    private const val MAX_BYTES = 8 * 1024 * 1024

    fun prepare(
        context: Context,
        uri: Uri,
        resolver: ContentResolver = context.contentResolver,
    ): PreparedImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Не удалось прочитать изображение" }

        val upload = decode(resolver, uri, bounds, MAX_UPLOAD_EDGE)
        val preview = decode(resolver, uri, bounds, MAX_PREVIEW_EDGE)

        val uploadBytes = ByteArrayOutputStream().use { stream ->
            val quality = if (upload.width * upload.height > 4_000_000) 80 else 90
            upload.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            stream.toByteArray()
        }

        val name = displayName(context, uri)

        return PreparedImage(
            bytes = if (uploadBytes.size <= MAX_BYTES) uploadBytes else preview.compressToBytes(70),
            mime = "image/jpeg",
            fileName = name.substringBeforeLast('.', name).ifBlank { "frame" } + ".jpg",
            previewDataUrl = preview.compressToBytes(80).let {
                "data:image/jpeg;base64," + Base64.encodeToString(it, Base64.NO_WRAP)
            },
            width = upload.width,
            height = upload.height,
        )
    }

    private fun decode(
        resolver: ContentResolver,
        uri: Uri,
        bounds: BitmapFactory.Options,
        maxEdge: Int,
    ): Bitmap {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        return bitmap ?: error("Не удалось декодировать изображение")
    }

    private fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (max(w, h) / 2 >= maxEdge) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    private fun Bitmap.compressToBytes(quality: Int): ByteArray = ByteArrayOutputStream().use { stream ->
        compress(Bitmap.CompressFormat.JPEG, quality, stream)
        stream.toByteArray()
    }

    private fun displayName(context: Context, uri: Uri): String =
        uri.lastPathSegment?.substringAfterLast('/') ?: "frame.png"

    @Suppress("unused")
    private fun scaleHint(width: Int, height: Int) = max(width, height).let { "${it}px (~${(it / 2f).roundToInt()})" }
}
