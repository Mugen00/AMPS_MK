package dev.amps.app.media

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Пишет ID3v2.4 прямо в байты MP3 — до того, как файл попадёт в MediaStore.
 *
 * **Почему так, а не иначе.** В Android нет публичного API для записи тегов:
 * `MediaMetadataRetriever` только читает, а `ContentResolver.update()` по тегам
 * MediaStore на API 29+ работает только до следующего сканирования — MediaProvider
 * выводит значения из содержимого файла и затирает всё, что туда записали.
 * Единственный способ, который переживает перезагрузку, — вписать теги в сам файл.
 *
 * Зависимостей нет намеренно: ради шести фреймов и картинки тянуть библиотеку
 * на несколько мегабайт в приложение, где всё остальное работает на OkHttp и
 * kotlinx-serialization, незачем.
 */
object Id3Writer {

    /** Размер в ID3v2.4 — syncsafe: 7 бит на байт, старший бит каждого байта 0. */
    private fun syncsafe(value: Int): ByteArray {
        require(value in 0..0x0FFFFFFF) { "ID3 size out of range: $value" }
        return byteArrayOf(
            ((value shr 21) and 0x7F).toByte(),
            ((value shr 14) and 0x7F).toByte(),
            ((value shr 7) and 0x7F).toByte(),
            (value and 0x7F).toByte(),
        )
    }

    /** `InputStream.skip` вправе вернуть 0, хотя данные есть — читать надо. */
    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped <= 0) {
                if (input.read() < 0) return
                left--
            } else {
                left -= skipped
            }
        }
    }

    private fun frame(id: String, payload: ByteArray): ByteArray {
        require(id.length == 4) { "frame id must be 4 chars: $id" }
        val out = ByteArrayOutputStream(10 + payload.size)
        out.write(id.toByteArray(Charsets.US_ASCII))
        out.write(syncsafe(payload.size))
        out.write(byteArrayOf(0, 0)) // флаги фрейма
        out.write(payload)
        return out.toByteArray()
    }

    /** 0x03 = UTF-8. Stagefright читает именно эту кодировку, 0x01 тоже допустим. */
    private fun textFrame(id: String, text: String): ByteArray {
        val clean = text.replace("\u0000", "")
        return frame(id, byteArrayOf(0x03) + clean.toByteArray(Charsets.UTF_8) + 0)
    }

    /** COMM: кодировка(1) + язык(3) + описание\0 + текст\0 */
    private fun commentFrame(text: String): ByteArray = frame(
        "COMM",
        byteArrayOf(0x03) + "eng".toByteArray(Charsets.US_ASCII) + 0 +
            text.replace("\u0000", "").toByteArray(Charsets.UTF_8) + 0,
    )

    /**
     * Кодировка 0x00 = Latin-1 — единственная, после которой не нужно
     * unsynchronisation. Обложка должна быть JPEG: PNG внутри ID3 встречается
     * редко, и часть плееров показывает вместо картинки «битую» заглушку.
     * Latin-1 в описании — осознанный компромисс: она гарантирует отсутствие
     * 0xFF в теге, а «мусор» от несовпадения кодировок читается как сдвиг на
     * байт и ломает все теги разом.
     */
    private fun pictureFrame(image: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(image.size + 32)
        out.write(0x00)
        out.write("image/jpeg".toByteArray(Charsets.US_ASCII))
        out.write(0)
        out.write(0x03) // Cover (front)
        out.write(0) // пустое описание
        out.write(image)
        return frame("APIC", out.toByteArray())
    }

    data class Tags(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val year: String? = null,
        val genre: String? = null,
        val track: String? = null,
        val comment: String? = null,
        /** Обложка JPEG. PNG намеренно не поддерживается — см. [pictureFrameLatin1]. */
        val coverJpeg: ByteArray? = null,
    )

    fun buildTag(tags: Tags): ByteArray {
        val frames = ArrayList<ByteArray>()
        fun put(value: String?, id: String) {
            if (!value.isNullOrBlank()) frames += textFrame(id, value)
        }
        put(tags.title, "TIT2")
        put(tags.artist, "TPE1")
        put(tags.album, "TALB")
        put(tags.albumArtist, "TPE2")
        put(tags.year, "TDRC") // в ID3v2.4 год записан именно так, не TYER
        put(tags.genre, "TCON")
        put(tags.track, "TRCK")
        if (!tags.comment.isNullOrBlank()) frames += commentFrame(tags.comment)
        tags.coverJpeg?.let { frames += pictureFrame(it) }

        val body = ByteArrayOutputStream().apply { frames.forEach { write(it) } }.toByteArray()
        return ByteArrayOutputStream(10 + body.size).apply {
            write("ID3".toByteArray(Charsets.US_ASCII))
            write(byteArrayOf(4, 0)) // ID3v2.4.0
            write(0) // флаги тега
            write(syncsafe(body.size))
            write(body)
        }.toByteArray()
    }

    /**
     * Кладёт тег в начало MP3, вырезая старый ID3v2. Файл целиком в память не
     * читается: трек на 10 минут — это десятки мегабайт, а на телефоне это
     * верный способ получить OutOfMemoryError посреди импорта.
     */
    fun applyTag(source: File, destination: File, tag: ByteArray) {
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output ->
                output.write(tag)
                val head = ByteArray(10)
                val read = input.read(head)
                val hasTag = read == 10 &&
                    head[0] == 'I'.code.toByte() &&
                    head[1] == 'D'.code.toByte() &&
                    head[2] == '3'.code.toByte()
                when {
                    hasTag -> {
                        val size = ((head[6].toInt() and 0x7F) shl 21) or
                            ((head[7].toInt() and 0x7F) shl 14) or
                            ((head[8].toInt() and 0x7F) shl 7) or
                            (head[9].toInt() and 0x7F)
                        // футер есть только у неразмеченного тега с флагом 0x10
                        val footer = if ((head[5].toInt() and 0x10) != 0) 10 else 0
                        skipFully(input, 10L + size + footer)
                    }
                    read > 0 -> output.write(head, 0, read)
                }
                input.copyTo(output, DEFAULT_BUFFER_SIZE)
            }
        }
    }
}
