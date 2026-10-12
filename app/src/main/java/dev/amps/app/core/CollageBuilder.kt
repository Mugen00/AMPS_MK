package dev.amps.app.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max

/**
 * 1.5.3: збирач колажів для поста — 3 або 4 фото склеюються в ОДНУ
 * квадратну картинку (JPEG 1080×1080), яка публікується як звичайне
 * фото поста. Сервер про колаж не знає — для нього це одне фото.
 *
 * Розкладки (як в Instagram-колажах):
 *  - 4 фото → сітка 2×2;
 *  - 3 фото → два зверху + одне знизу на всю ширину.
 * Плитки обрізаються по центру (crop), розділювачі — білі лінії.
 */
object CollageBuilder {

    private const val CANVAS_SIZE = 1080
    private const val GAP = 4

    /** Збирає колаж із 3–4 фото; null — якщо хоч одне фото не декодується. */
    fun buildCollage(photos: List<ByteArray>): ByteArray? {
        if (photos.size !in 3..4) return null
        val out = Bitmap.createBitmap(CANVAS_SIZE, CANVAS_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val background = Paint().apply { color = Color.WHITE }
        canvas.drawRect(0f, 0f, CANVAS_SIZE.toFloat(), CANVAS_SIZE.toFloat(), background)
        val half = CANVAS_SIZE / 2
        when (photos.size) {
            // Сітка 2×2: кожна плитка — чверть полотна.
            4 -> {
                drawTile(canvas, background, photos[0], 0, 0, half, half)
                drawTile(canvas, background, photos[1], half, 0, half, half)
                drawTile(canvas, background, photos[2], 0, half, half, half)
                drawTile(canvas, background, photos[3], half, half, half, half)
            }
            // 3 фото: два зверху, одне знизу на всю ширину.
            else -> {
                drawTile(canvas, background, photos[0], 0, 0, half, half)
                drawTile(canvas, background, photos[1], half, 0, half, half)
                drawTile(canvas, background, photos[2], 0, half, CANVAS_SIZE, half)
            }
        }
        val stream = java.io.ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        out.recycle()
        return stream.toByteArray()
    }

    /**
     * Малює одне фото в плитку [x, y, w, h] з обрізанням по центру
     * (еквівалент ContentScale.Crop) і білими розділювачами по краях.
     */
    private fun drawTile(
        canvas: Canvas,
        background: android.graphics.Paint,
        bytes: ByteArray,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ) {
        val rect = RectF(
            (x + GAP).toFloat(),
            (y + GAP).toFloat(),
            (x + w - GAP).toFloat(),
            (y + h - GAP).toFloat(),
        )
        val tile = decodeCropped(bytes, rect.width().toInt(), rect.height().toInt()) ?: return
        val save = canvas.save()
        canvas.drawRect(rect, background)
        canvas.clipRect(rect)
        // Масштаб crop: плитка повністю вкрита зображенням, центр збігається.
        val scale = max(rect.width() / tile.width, rect.height() / tile.height)
        val scaledW = tile.width * scale
        val scaledH = tile.height * scale
        val dest = RectF(
            rect.centerX() - scaledW / 2f,
            rect.centerY() - scaledH / 2f,
            rect.centerX() + scaledW / 2f,
            rect.centerY() + scaledH / 2f,
        )
        canvas.drawBitmap(tile, null, dest, null)
        canvas.restoreToCount(save)
        tile.recycle()
    }

    /** Декодує байти і обрізає по центру до пропорцій [targetW]×[targetH]. */
    private fun decodeCropped(bytes: ByteArray, targetW: Int, targetH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val tileRatio = targetW.toFloat() / targetH
        val srcRatio = src.width.toFloat() / src.height
        // Пропорції вже ті — центрування зробить drawBitmap.
        if (abs(srcRatio / tileRatio - 1f) < 0.02f) return src
        val (cropW, cropH) = if (srcRatio > tileRatio) {
            (src.height * tileRatio).toInt() to src.height
        } else {
            src.width to (src.width / tileRatio).toInt()
        }
        val cropped = Bitmap.createBitmap(src, (src.width - cropW) / 2, (src.height - cropH) / 2, cropW, cropH)
        if (cropped != src) src.recycle()
        return cropped
    }
}
