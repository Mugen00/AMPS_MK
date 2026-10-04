package dev.amps.app.util

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
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
 * Скриншоты прямо из галереи весят 4–12 МП; IQDB всё равно сравнивает картинку
 * по уменьшенной сетке, поэтому отправка оригинала только тратит мобильный
 * трафик пользователя. Всё ужимается до отправки.
 *
 * ## Почему здесь появились [Format], [decode] и [aspectRatio]
 *
 * Класс раньше умел ровно одно: `BitmapFactory` плюс JPEG на выходе. Отсюда
 * три измеренные поломки.
 *
 * **1. Заголовок врал о том, что отправляется.** `mime` писался строкой
 * `"image/jpeg"` и не сверялся с байтами. Теперь формат берётся из самих
 * отправляемых байтов через [sniff]: если сжатие когда-нибудь сменится,
 * подпись последует за ним, а не станет врать.
 *
 * **2. HEIC и AVIF не читались вообще.** Фото с айфона — это контейнер HEIF,
 * а `BitmapFactory` его не понимает: `inJustDecodeBounds` отдаёт нули и
 * приложение отказывало на самом обычном снимке. Декодировать их умеет
 * `android.graphics.ImageDecoder` (API 28+), поэтому [decode] сначала пробует
 * `BitmapFactory` — как раньше, чтобы ничего не сломать на API 24–27 — и
 * только затем `ImageDecoder`.
 *
 * **3. Нечитаемый файл выглядел как пустой.** Молчаливый отказ означает
 * «определить нечего», а на деле это «декодер не смог». Поэтому [decode]
 * возвращает [Decode.Failed] с причиной, а [prepare] отдаёт эту причину как
 * текст пользователю, а не как исключение без слов.
 */
object ImageLoader {

    private const val MAX_UPLOAD_EDGE = 1280
    private const val MAX_PREVIEW_EDGE = 480
    private const val MAX_BYTES = 8 * 1024 * 1024

    /**
     * Сколько байт надо прочитать, чтобы понять формат.
     *
     * Ровно столько, сколько заглядывают сигнатуры: PNG — 8 байт, HEIF — 12
     * (`ftyp` + бренд), WebP — 12 (`RIFF` + `WEBP`).
     */
    private const val SNIFF_BYTES = 32

    /**
     * Формат картинки по первым байтам файла.
     *
     * Определение идёт по сигнатуре, а не по расширению и не по `Content-Type`
     * провайдера: имя файла пользователь переименовать может, а провайдер
     * отдаёт то, что записали при съёмке.
     */
    enum class Format(val mime: String, val extension: String) {
        JPEG("image/jpeg", "jpg"),
        PNG("image/png", "png"),
        WEBP("image/webp", "webp"),
        GIF("image/gif", "gif"),
        BMP("image/bmp", "bmp"),

        /** Фото с айфона. Читается только через `ImageDecoder` (API 28+). */
        HEIC("image/heic", "heic"),
        HEIF("image/heif", "heif"),

        /** Тот же контейнер ISO-BMFF, но другой бренд. */
        AVIF("image/avif", "avif"),
    }

    /** Итог декодирования: либо битмап, либо строка-причина, почему не вышло. */
    sealed interface Decode {
        data class Image(val bitmap: Bitmap) : Decode
        data class Failed(val reason: String) : Decode
    }

    /**
     * Формат по первым байтам. `null` — сигнатура не узнана.
     *
     * Молчаливый `null` здесь опаснее, чем кажется: вызывающий обязан в этом
     * случае перекодировать картинку, а не объявлять в заголовке чужой тип.
     */
    fun sniff(bytes: ByteArray): Format? {
        if (bytes.size < 12) return null
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return Format.JPEG
        }
        if (startsWith(bytes, PNG_SIGNATURE)) return Format.PNG
        if (ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WEBP") return Format.WEBP
        if (ascii(bytes, 0, 4) == "GIF8") return Format.GIF
        if (ascii(bytes, 0, 2) == "BM") return Format.BMP
        // HEIC и AVIF — один контейнер ISO-BMFF: `....ftyp` на четвёртом байте
        // и бренд на восьмом. Заголовок общий, поэтому различает их именно
        // бренд: `heic`/`mif1` против `avif`.
        if (ascii(bytes, 4, 4) == "ftyp") {
            return when (ascii(bytes, 8, 4)) {
                "heic", "heix", "hevc", "hevx" -> Format.HEIC
                "mif1", "msf1", "mif2", "msf2" -> Format.HEIF
                "avif", "avis" -> Format.AVIF
                else -> null
            }
        }
        return null
    }

    /**
     * Декодирует картинку, уменьшая длинную сторону до [maxEdge].
     *
     * Порядок проб не случаен. `BitmapFactory` идёт первым, а не «на всякий
     * случай»: JPEG, PNG, WebP и GIF он понимает на любом устройстве, а
     * `ImageDecoder` появился только в Android 9 и на старых телефонах его
     * просто нет. Второй путь — ровно для того, что первым не читается:
     * HEIC с айфона и AVIF.
     */
    fun decode(bytes: ByteArray, maxEdge: Int): Decode {
        if (bytes.isEmpty()) return Decode.Failed("Файл пустой")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            val bitmap = BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
            if (bitmap != null) return Decode.Image(bitmap)
        }

        return decodeModern(bytes, maxEdge)?.let(Decode::Image) ?: Decode.Failed(failureReason(bytes))
    }

    /**
     * Перекодирует картинку в JPEG — тем же кодом, которым готовится загрузка.
     *
     * Нужен там, куда можно отдать только JPEG: trace.moe надёжнее всего
     * принимает именно его, а формат входного файла приложение не контролирует
     * (снимок с айфона, WebP из мессенджера). `null` — прочитать нечем, и
     * вызывающий обязан это честно обработать, а не отправить мусор.
     */
    fun toJpeg(bytes: ByteArray, maxEdge: Int = MAX_UPLOAD_EDGE, quality: Int = 90): ByteArray? =
        when (val decoded = decode(bytes, maxEdge)) {
            is Decode.Image -> decoded.bitmap.compressToBytes(quality)
            is Decode.Failed -> null
        }

    /**
     * Ширина/высота картинки без полного декодирования.
     *
     * Нужна там, где важна только пропорция: полный битмап на 12 МП ради одного
     * деления — это лишние мегабайты и лишние сотни миллисекунд. Возвращает
     * `0f`, если размер выяснить не удалось; вызывающий сам решает, что с этим
     * делать, и не выдаёт догадку за факт.
     */
    fun aspectRatio(bytes: ByteArray): Float {
        if (bytes.isEmpty()) return 0f
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return 0f
        return bounds.outWidth.toFloat() / bounds.outHeight.toFloat()
    }

    fun prepare(
        context: Context,
        uri: Uri,
        resolver: ContentResolver = context.contentResolver,
    ): PreparedImage {
        val upload = requireBitmap(resolver, uri, MAX_UPLOAD_EDGE)
        // Превью читается отдельным проходом: `AsyncImage` рисует data URL сам,
        // и 480 px ему достаточно. Если второй проход вдруг не удался, берём
        // уже готовый битмап — файл-то один и тот же.
        val preview = decodeUri(resolver, uri, MAX_PREVIEW_EDGE)
            .let { if (it is Decode.Image) it.bitmap else upload }

        val quality = if (upload.width * upload.height > 4_000_000) 80 else 90
        val uploadBytes = upload.compressToBytes(quality)
        val previewBytes = preview.compressToBytes(80)
        val payload = if (uploadBytes.size <= MAX_BYTES) uploadBytes else preview.compressToBytes(70)

        // Тип и имя файла берутся из байтов, которые действительно уйдут
        // наружу. Раньше здесь стояли две константы `image/jpeg`, и любое
        // изменение кодировки немедленно превращалось во враньё в заголовке.
        val format = sniff(payload) ?: Format.JPEG
        val previewFormat = sniff(previewBytes) ?: Format.JPEG
        val name = displayName(context, uri)

        return PreparedImage(
            bytes = payload,
            mime = format.mime,
            fileName = name.substringBeforeLast('.', name).ifBlank { "frame" } + "." + format.extension,
            // Превью — тоже свои байты, поэтому подпись data URL выводится из
            // них, а не пишется строкой: смена сжатия иначе сделала бы её
            // неверной молча и без единого признака.
            previewDataUrl = "data:${previewFormat.mime};base64," +
                Base64.encodeToString(previewBytes, Base64.NO_WRAP),
            width = upload.width,
            height = upload.height,
        )
    }

    /**
     * Читает картинку из `Uri` и объясняет человеку, если не вышло.
     *
     * Текст причины уходит в snackbar: человек выбрал снимок с айфона, получил
     * отказ — и должен понять, что дело в снимке, а не в приложении.
     */
    private fun requireBitmap(resolver: ContentResolver, uri: Uri, maxEdge: Int): Bitmap =
        when (val decoded = decodeUri(resolver, uri, maxEdge)) {
            is Decode.Image -> decoded.bitmap
            is Decode.Failed -> throw IllegalArgumentException(decoded.reason)
        }

    /**
     * Тот же [decode], но для `Uri`.
     *
     * Источник открывается дважды — отдельно на заголовок и отдельно на пиксели,
     * а не читается целиком в память: фото на 12 МП это десятки мегабайт, и
     * держать их ради битмапа на 1280 px незачем. Для второго пути поток
     * открывает уже сам `ImageDecoder` через `createSource(resolver, uri)`.
     */
    private fun decodeUri(resolver: ContentResolver, uri: Uri, maxEdge: Int): Decode {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            val bitmap = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(
                    it,
                    null,
                    BitmapFactory.Options().apply {
                        inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    },
                )
            }
            if (bitmap != null) return Decode.Image(bitmap)
        }

        // `BitmapFactory` файл не понял — значит, это HEIC или AVIF, и читать
        // его надо другим декодером. Если и он недоступен, причина уходит
        // человеку словами, а не молчаливым отказом.
        val modern = decodeModernUri(resolver, uri, maxEdge)
        return if (modern != null) {
            Decode.Image(modern)
        } else {
            Decode.Failed(failureReason(readHeader(resolver, uri)))
        }
    }

    private fun readHeader(resolver: ContentResolver, uri: Uri): ByteArray {
        val buffer = ByteArray(SNIFF_BYTES)
        val filled = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                var total = 0
                while (total < SNIFF_BYTES) {
                    val read = input.read(buffer, total, SNIFF_BYTES - total)
                    if (read <= 0) break
                    total += read
                }
                total
            } ?: 0
        }.getOrDefault(0)
        return buffer.copyOf(filled.coerceIn(0, SNIFF_BYTES))
    }

    /**
     * Второй путь декодирования: `android.graphics.ImageDecoder` (API 28+).
     *
     * Он нужен ровно для контейнеров, которых нет в `BitmapFactory`: HEIC
     * с айфона и AVIF. На API 24–27 метода в системе просто нет, там
     * `decodeModern` честно возвращает `null`, и вызывающий сообщает об этом
     * словом, а не пустым результатом.
     */
    private fun decodeModern(bytes: ByteArray, maxEdge: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(bytes)) { decoder, info, _ ->
                applyLimits(decoder, info.size.width, info.size.height, maxEdge)
            }
        }.getOrNull()
    }

    private fun decodeModernUri(resolver: ContentResolver, uri: Uri, maxEdge: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return runCatching {
            // Именно эта пара: `createSource(InputStream)` есть в AOSP, но в
            // публичных заголовках SDK её нет, а `createSource(resolver, uri)`
            // — документированная и сама переоткрывает поток, что важно после
            // прохода `BitmapFactory` по тому же `Uri`.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                applyLimits(decoder, info.size.width, info.size.height, maxEdge)
            }
        }.getOrNull()
    }

    private fun applyLimits(
        decoder: ImageDecoder,
        width: Int,
        height: Int,
        maxEdge: Int,
    ) {
        val longest = max(width, height)
        if (longest > maxEdge) {
            val scale = maxEdge.toFloat() / longest
            decoder.setTargetSize(
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1),
            )
        }
        // Программный аллокатор обязателен, а не аккуратность: `getPixels()`
        // у битмапа в памяти GPU бросает IllegalStateException, а тегер читает
        // пиксели поштучно.
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        // Картинка в 4000×3000 при поиске кадра всё равно станет 448×448:
        // держать 48 МБ ради этого незачем.
        decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
    }

    /**
     * Причина отказа человеческим языком.
     *
     * Самое частое из «не прочиталось» — снимок с айфона в HEIC на Android 8:
     * там нет ни `ImageDecoder`, ни кодека HEIC в `BitmapFactory`. Это не
     * поломка приложения, и сказать об этом нужно прямо, иначе человек будет
     * искать причину не там.
     */
    private fun failureReason(header: ByteArray): String {
        val format = sniff(header)
        val modern = format == Format.HEIC || format == Format.HEIF || format == Format.AVIF
        val name = format?.let { "${it.mime} (${it.extension})" }
            ?: "формат не распознан по первым байтам"
        return if (modern && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            "$name: на этом устройстве (Android ${Build.VERSION.RELEASE}) нет кодека для такого файла — " +
                "он появился в Android 9. Подойдёт снимок экрана или фото, пересохранённое в JPEG или PNG."
        } else {
            "$name: системный декодер не смог прочитать файл — он повреждён или это не изображение."
        }
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

    private fun startsWith(bytes: ByteArray, signature: ByteArray): Boolean {
        if (bytes.size < signature.size) return false
        for (i in signature.indices) if (bytes[i] != signature[i]) return false
        return true
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)

    /** Сигнатура PNG — единственная, которую сверяют целиком. */
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
}