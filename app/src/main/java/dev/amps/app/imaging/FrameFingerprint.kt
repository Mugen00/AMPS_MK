package dev.amps.app.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 64-bit difference hash of a single frame, byte-for-byte identical to the
 * bridge's Node implementation.
 *
 * The hash is a cross-language contract: the phone computes it for a frame the
 * user just shot, the bridge compares it against every frame it has indexed,
 * and both sides must land on the same 16 hex characters or nothing ever
 * matches. The pipeline below is therefore fixed and must not be "improved":
 *
 *  1. exactly 9 cells wide by 8 high, every cell the area-weighted mean of the
 *     source pixels it covers — fractional coverage weighted by area, never
 *     nearest-neighbour and never an integer pixel count. Output column `ox`
 *     covers source x in `[ox*W/9, (ox+1)*W/9)`, output row `oy` covers source
 *     y in `[oy*H/8, (oy+1)*H/8)`;
 *  2. each source pixel is composited over black with round-half-up *before* it
 *     enters the mean: `round(channel * alpha / 255)`;
 *  3. the cell mean is rounded half-up as well: `floor(mean + 0.5)`;
 *  4. greyscale comes *after* the downscale, as integer luma
 *     `R*77 + G*150 + B*29` with a floor division by 256;
 *  5. bit `y*8 + x` is 1 when `cell(x,y) > cell(x+1,y)`, emitted row-major as
 *     16 lowercase hex characters, most significant bit first.
 *
 * Orientation: no EXIF is read here. A gallery screenshot carries its rotation
 * in EXIF, and the part of the app that already normalises orientation hands
 * this class pixels in their final orientation; re-reading EXIF would only
 * risk rotating a frame a second time.
 */
object FrameFingerprint {

    /** Shortest separation two hashes may have and still count as the same frame. */
    const val DEFAULT_MAX_DISTANCE = 10

    private const val GRID_WIDTH = 9
    private const val GRID_HEIGHT = 8
    private const val CELL_COUNT = GRID_WIDTH * GRID_HEIGHT
    private const val HASH_CHARS = 16
    private const val HASH_BITS = 64
    private const val HEX_DIGITS = "0123456789abcdef"

    /** Returned when a bitmap is unusable, so the "never throws" contract holds. */
    private const val ZERO_HASH = "0000000000000000"

    /**
     * Hash of an already-decoded bitmap. Returns [ZERO_HASH] for a bitmap this
     * cannot read (hardware config, already recycled) — prefer [dHashOrNull]
     * when "no hash" and "hash of a black frame" must be told apart.
     */
    fun dHash(bitmap: Bitmap): String = dHashOrNull(bitmap) ?: ZERO_HASH

    /** As [dHash], but `null` instead of a placeholder when the bitmap is unreadable. */
    fun dHashOrNull(bitmap: Bitmap): String? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val cells = runCatching { cells(bitmap) }.getOrNull() ?: return null
        return toHex(cells)
    }

    /**
     * Hash of raw image bytes, decoded here with [BitmapFactory].
     *
     * Deliberately never sub-samples: [android.graphics.BitmapFactory.Options.inSampleSize]
     * would throw pixels away and change the hash, so a caller that needs a
     * smaller image must shrink it before calling, not through this option.
     * Returns `null` for anything that will not decode — a bad frame is a
     * result, not a crash.
     */
    fun dHash(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
            inSampleSize = 1
        }
        val bitmap = runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }.getOrNull() ?: return null
        return try {
            dHashOrNull(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Bits two hashes differ in, `0..64`.
     *
     * Malformed input — not 16 characters, or not all hex — yields 64, the
     * largest possible distance, so a broken hash can never look like a match.
     * Upper and lower case are both accepted.
     */
    fun hamming(a: String, b: String): Int {
        val left = nibbles(a) ?: return HASH_BITS
        val right = nibbles(b) ?: return HASH_BITS
        var distance = 0
        for (i in 0 until HASH_CHARS) {
            distance += Integer.bitCount((left[i] xor right[i]) and 0xFF)
        }
        return distance
    }

    /** True when two hashes are [maxDistance] bits apart or closer. */
    fun matches(a: String, b: String, maxDistance: Int = DEFAULT_MAX_DISTANCE): Boolean =
        hamming(a, b) <= maxDistance

    /**
     * The 72 greyscale cells the bits are read from, row-major, 9 per row.
     * Exposed so the arithmetic can be asserted in a test and printed in a bug
     * report; the hash itself is the only thing callers normally need.
     */
    internal fun cells(bitmap: Bitmap): IntArray {
        val width = bitmap.width
        val height = bitmap.height
        val cells = IntArray(CELL_COUNT)

        for (outputY in 0 until GRID_HEIGHT) {
            val top = outputY.toDouble() * height / GRID_HEIGHT
            val bottom = (outputY + 1).toDouble() * height / GRID_HEIGHT
            val rowStart = top.toInt().coerceIn(0, height - 1)
            val rowEnd = ceil(bottom).toInt().coerceIn(rowStart + 1, height)

            for (outputX in 0 until GRID_WIDTH) {
                val left = outputX.toDouble() * width / GRID_WIDTH
                val right = (outputX + 1).toDouble() * width / GRID_WIDTH
                val columnStart = left.toInt().coerceIn(0, width - 1)
                val columnEnd = ceil(right).toInt().coerceIn(columnStart + 1, width)

                val columns = columnEnd - columnStart
                val rows = rowEnd - rowStart
                val pixels = IntArray(columns * rows)
                bitmap.getPixels(pixels, 0, columns, columnStart, rowStart, columns, rows)

                var red = 0.0
                var green = 0.0
                var blue = 0.0
                var area = 0.0
                for (row in 0 until rows) {
                    val sourceY = rowStart + row
                    val coverY = min(sourceY + 1.0, bottom) - max(sourceY.toDouble(), top)
                    if (coverY <= 0.0) continue
                    for (column in 0 until columns) {
                        val sourceX = columnStart + column
                        val coverX = min(sourceX + 1.0, right) - max(sourceX.toDouble(), left)
                        if (coverX <= 0.0) continue
                        val argb = pixels[row * columns + column]
                        val alpha = (argb ushr 24) and 0xFF
                        val coverage = alpha / 255.0
                        val weight = coverX * coverY
                        red += overBlack((argb shr 16) and 0xFF, coverage) * weight
                        green += overBlack((argb shr 8) and 0xFF, coverage) * weight
                        blue += overBlack(argb and 0xFF, coverage) * weight
                        area += weight
                    }
                }
                cells[outputY * GRID_WIDTH + outputX] = luma(red, green, blue, area)
            }
        }
        return cells
    }

    /** Step 2: one channel of one source pixel, composited over black, half-up. */
    private fun overBlack(channel: Int, coverage: Double): Double = floor(channel * coverage + 0.5)

    /** Steps 3 and 4: round the means half-up, then take the integer luma. */
    private fun luma(red: Double, green: Double, blue: Double, area: Double): Int {
        if (area <= 0.0) return 0
        val meanRed = floor(red / area + 0.5)
        val meanGreen = floor(green / area + 0.5)
        val meanBlue = floor(blue / area + 0.5)
        return floor((meanRed * 77 + meanGreen * 150 + meanBlue * 29) / 256).toInt()
    }

    /** Step 5: row-major bits, most significant first, 16 lowercase hex characters. */
    private fun toHex(cells: IntArray): String {
        val hex = StringBuilder(HASH_CHARS)
        var nibble = 0
        var bits = 0
        for (y in 0 until GRID_HEIGHT) {
            val row = y * GRID_WIDTH
            for (x in 0 until GRID_WIDTH - 1) {
                val bit = if (cells[row + x] > cells[row + x + 1]) 1 else 0
                nibble = (nibble shl 1) or bit
                if (++bits == 4) {
                    hex.append(HEX_DIGITS[nibble])
                    nibble = 0
                    bits = 0
                }
            }
        }
        return hex.toString()
    }

    /** `null` unless [hash] is exactly 16 hex characters. */
    private fun nibbles(hash: String): IntArray? {
        if (hash.length != HASH_CHARS) return null
        val nibbles = IntArray(HASH_CHARS)
        for (i in 0 until HASH_CHARS) {
            val value = Character.digit(hash[i], 16)
            if (value < 0) return null
            nibbles[i] = value
        }
        return nibbles
    }
}