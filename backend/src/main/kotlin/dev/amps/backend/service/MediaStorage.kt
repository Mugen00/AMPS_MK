package dev.amps.backend.service

import dev.amps.backend.config.AppConfig
import java.io.File
import java.util.UUID

/**
 * 1.1.2: спільне сховище медіа — аватри, фото і відео постів.
 *
 * Файли лежать на диску під [AppConfig.mediaDir] (на Railway — том у /data,
 * який переживає передеплої); у базі лише імена файлів. Імена — UUID з
 * розширенням: невгадувані, тому роздача файлів не потребує авторизації.
 *
 * Каталоги створюються на старті. Якщо том не змонтовано, каталоги
 * створяться звичайним шляхом усередині контейнера — сервер працює, але
 * медіа при передеплої зникають: це чесно зафіксовано в патчноуті.
 */
class MediaStorage(private val config: AppConfig) {

    private val root = File(config.mediaDir)

    init {
        runCatching {
            dirFor(KIND_PHOTO).mkdirs()
            dirFor(KIND_VIDEO).mkdirs()
            dirFor(KIND_AVATAR).mkdirs()
        }
    }

    fun dirFor(kind: String): File = File(root, kind)

    /** Нове ім'я файлу: base-UUID.розширення — невгадуване і без шляхів. */
    fun newFileName(base: String, extension: String): String =
        "$base-${UUID.randomUUID()}.$extension"

    /**
     * Розширення для вкладення: з імені файлу клієнта або Content-Type.
     * Білий список — нічого крім зображень і відео не зберігається.
     */
    fun extensionOf(fileName: String?, contentType: String?, kind: String): String? {
        val fromName = fileName?.substringAfterLast('.', "")
            ?.lowercase()?.takeIf { it.length in 1..5 && it.all { ch -> ch.isLetterOrDigit() } }
        if (fromName != null) {
            val allowed = if (kind == KIND_PHOTO) PHOTO_EXT else VIDEO_EXT
            if (fromName in allowed) return fromName
        }
        return when {
            kind == KIND_PHOTO && contentType?.startsWith("image/") == true -> "jpg"
            kind == KIND_VIDEO && contentType == "video/webm" -> "webm"
            kind == KIND_VIDEO && contentType?.startsWith("video/") == true -> "mp4"
            else -> null
        }
    }

    /** Зберігає вміст і повертає ім'я файлу, або null, якщо диск відмовив. */
    fun save(kind: String, bytes: ByteArray, extension: String): String? {
        val name = newFileName(base = kind, extension = extension)
        return try {
            val dir = dirFor(kind)
            dir.mkdirs()
            File(dir, name).writeBytes(bytes)
            name
        } catch (e: Throwable) {
            null
        }
    }

    /** Файл для роздачі; шляхи й ".." відсікаються ще на рівні імені. */
    fun resolve(kind: String, fileName: String): File? {
        if (fileName.isEmpty() || fileName.contains('/') || fileName.contains('\\') || fileName.contains("..")) {
            return null
        }
        return dirFor(kind).resolve(fileName).takeIf { it.isFile }
    }

    /** Видаляє файл (старі аватри); помилки мовчки ігноруються. */
    fun delete(kind: String, fileName: String) {
        if (fileName.isBlank()) return
        runCatching { dirFor(kind).resolve(fileName).delete() }
    }

    companion object {
        const val KIND_PHOTO = "photo"
        const val KIND_VIDEO = "video"
        const val KIND_AVATAR = "avatar"

        private val PHOTO_EXT = setOf("jpg", "jpeg", "png", "webp", "gif")
        private val VIDEO_EXT = setOf("mp4", "webm", "mov", "m4v")
    }
}
