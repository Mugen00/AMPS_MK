package dev.amps.app.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * 1.6.3: стиснення фото перед відправкою. Фотки з камери/галереї важать
 * 3-8 МБ — до сервера і назад гоняємо webp, який важить у рази менше:
 * довша сторона обрізається до [maxDim] px, якість [quality] (0-100).
 * Якщо декодувати не вдалося або результат вийшов більший за оригінал —
 * повертаємо оригінальні байти без шкоди.
 */
object ImageCompress {

    /** Стиснути до webp; у разі сумнівів — оригінал. */
    fun toWebp(bytes: ByteArray, maxDim: Int = 1600, quality: Int = 82): ByteArray {
        val source = runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull() ?: return bytes
        val scaled = scaleDown(source, maxDim)
        val stream = ByteArrayOutputStream()
        val ok = runCatching {
            scaled.compress(Bitmap.CompressFormat.WEBP, quality, stream)
        }.getOrDefault(false)
        if (scaled !== source && scaled != source) scaled.recycle()
        if (!ok) return bytes
        val packed = stream.toByteArray()
        return if (packed.size < bytes.size) packed else bytes
    }

    /** Обрізає довшу сторону до [maxDim] (пропорції лишаються). */
    private fun scaleDown(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDim || longest == 0) return bitmap
        val scale = maxDim.toFloat() / longest
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return runCatching {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }.getOrDefault(bitmap)
    }
}
