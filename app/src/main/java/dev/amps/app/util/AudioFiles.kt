package dev.amps.app.util

import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Small helpers shared by the music clients, the downloader and the importer.
 *
 * Every downloaded file is checksummed while it streams to disk, and every size
 * shown in the UI goes through [formatBytes] so that a 4 GB netlabel release
 * and a 3 MB chiptune read the same way.
 */

/** Digests a stream without loading it into memory. */
fun InputStream.digestHex(algorithm: String): String {
    val digest = MessageDigest.getInstance(algorithm)
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    return digest.hex()
}

/** Digests a file on disk. Returns null when the file is missing or unreadable. */
fun java.io.File.digestHex(algorithm: String): String? = runCatching {
    inputStream().use { it.digestHex(algorithm) }
}.getOrNull()

/** Finalises a digest that was fed while the bytes streamed past. */
fun MessageDigest.hex(): String = digest().toHex()

/** Lowercase hex of a digest output. */
fun ByteArray.toHex(): String = buildString(size * 2) {
    this@toHex.forEach { byte -> append("%02x".format(byte)) }
}

/** `6.72 МБ`, `1.4 ГБ`, `512 Б`. Binary units, but decimal-looking numbers. */
fun formatBytes(bytes: Long?): String? {
    if (bytes == null || bytes < 0) return null
    if (bytes < 1024) return "$bytes Б"
    val units = listOf("КБ", "МБ", "ГБ", "ТБ")
    var value = bytes.toDouble() / 1024.0
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return "%.2f %s".format(Locale.US, value, units[index])
}

/** `4:53`, `1:02:11`. Used for every duration, whatever shape the source used. */
fun formatDurationMs(millis: Long?): String? = millis?.takeIf { it >= 0 }?.let { formatDurationSec((it / 1000).toInt()) }

fun formatDurationSec(seconds: Int?): String? = seconds?.takeIf { it >= 0 }?.let { total ->
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val rest = total % 60
    if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
}

/** Parses the `"4:53"` shape ccMixter publishes in `files[].file_format_info.ps`. */
fun parseClockDuration(value: String?): Int? {
    val clean = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val parts = clean.split(':')
    if (parts.isEmpty() || parts.size > 3) return null
    var total = 0
    for (part in parts) {
        val number = part.trim().toIntOrNull() ?: return null
        total = total * 60 + number
    }
    return total.takeIf { it > 0 }
}

/** `44100` → `44.1 кГц`. */
fun formatSampleRate(hertz: Int?): String? {
    val value = hertz?.takeIf { it > 0 } ?: return null
    if (value % 1000 == 0) return "${value / 1000} кГц"
    return "%.1f кГц".format(Locale.US, value / 1000.0)
}

/** `320000` → `320 кбит/с`. */
fun formatBitrate(bitsPerSecond: Int?): String? {
    val value = bitsPerSecond?.takeIf { it > 0 } ?: return null
    if (value % 1000 == 0) return "${value / 1000} кбит/с"
    return "%.1f кбит/с".format(Locale.US, value / 1000.0)
}

/** `"44k"` (ccMixter) → `44100`. */
fun parseSampleRate(value: String?): Int? {
    val clean = value?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
    val khz = clean.removeSuffix("hz").removeSuffix("k").trim().toDoubleOrNull() ?: return null
    return (khz * 1000).toInt().takeIf { it > 0 }
}

/**
 * Extension for a file we are about to write. The container wins when the name
 * carries one (ccMixter hands out `.mp3`, archive.org `.flac`); the MIME type is
 * only a fallback so a `content://` import without an extension still lands as
 * something playable.
 */
fun guessExtension(fileName: String?, mimeType: String?): String {
    val fromName = fileName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)?.trim()
    if (!fromName.isNullOrEmpty() && fromName.length in 2..5 && fromName.all { it.isLetterOrDigit() }) {
        return fromName
    }
    return mimeTypeToExtension(mimeType) ?: "mp3"
}

/** Best-effort MIME type for a local audio file. */
fun guessMimeType(fileName: String?, mimeType: String? = null): String {
    if (!mimeType.isNullOrBlank()) return mimeType
    return extensionToMimeType(fileName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT))
        ?: "audio/*"
}

private fun mimeTypeToExtension(mimeType: String?): String? = when (mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)) {
    "audio/mpeg", "audio/mp3" -> "mp3"
    "audio/mp4", "audio/aac", "audio/x-aac" -> "m4a"
    "audio/flac", "audio/x-flac" -> "flac"
    "audio/ogg", "application/ogg", "audio/vorbis" -> "ogg"
    "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
    "audio/webm" -> "webm"
    "audio/opus" -> "opus"
    else -> null
}

private fun extensionToMimeType(extension: String?): String? = when (extension) {
    "mp3" -> "audio/mpeg"
    "m4a", "mp4", "aac" -> "audio/mp4"
    "flac" -> "audio/flac"
    "ogg", "oga" -> "audio/ogg"
    "opus" -> "audio/opus"
    "wav" -> "audio/wav"
    "webm" -> "audio/webm"
    else -> null
}

/**
 * `Daft Punk - One More Time.mp3` — the naming the workspace asks for. Falls back
 * to the title alone when the artist is unknown, and never returns an empty
 * stem.
 */
fun audioFileName(artist: String?, title: String, extension: String): String {
    val stem = listOfNotNull(
        artist?.trim()?.takeIf { it.isNotEmpty() },
        title.trim().takeIf { it.isNotEmpty() },
    ).joinToString(" - ").ifEmpty { "amps" }
    return "${sanitizeFileName(stem)}.$extension"
}

/** Strips path separators and characters FAT/exFAT reject, keeping it readable. */
fun sanitizeFileName(value: String): String {
    val cleaned = value
        .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim('.', ' ')
    return cleaned.take(120).ifEmpty { "amps" }
}
