package dev.amps.backend.service

import dev.amps.backend.config.AppConfig
import dev.amps.backend.model.ApiResponse
import dev.amps.backend.model.ApiResponse.Companion.ok
import dev.amps.backend.model.AvatarResponse
import dev.amps.backend.model.ProfileDto
import dev.amps.backend.model.ProfileEntity
import dev.amps.backend.model.ProfileTable
import dev.amps.backend.model.UserEntity
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/**
 * 1.1.2: профіль користувача — показуване ім'я, біо, аватар.
 *
 * Створюється ліниво при першому читанні: реєстрація не зобов'язана
 * знати про профіль, а перший запит створює рядок із дефолтами
 * (ім'я = логін). Так акаунти, зареєстровані ще в 1.1.0, отримують
 * профіль без жодної міграції.
 *
 * Аватар зберігається файлом у [MediaStorage]; у базі лише ім'я файлу.
 */
class ProfileService(private val media: MediaStorage) {

    suspend fun get(userId: Int): ApiResponse<ProfileDto> = newSuspendedTransaction {
        ok(profileOf(ensureProfile(userId)))
    }

    suspend fun update(userId: Int, displayName: String, bio: String?): ApiResponse<ProfileDto> =
        newSuspendedTransaction {
            val name = displayName.trim()
            if (name.isEmpty() || name.length > 64) {
                return@newSuspendedTransaction ApiResponse.fail<ProfileDto>(
                    "Ім'я — від 1 до 64 символів", "BAD_NAME")
            }
            val cleanBio = bio?.trim()?.takeIf { it.isNotEmpty() }
            if (cleanBio != null && cleanBio.length > 280) {
                return@newSuspendedTransaction ApiResponse.fail<ProfileDto>(
                    "Біо не довше 280 символів", "BIO_TOO_LONG")
            }
            val profile = ensureProfile(userId)
            profile.displayName = name
            profile.bio = cleanBio
            profile.updatedAt = System.currentTimeMillis()
            ok(profileOf(profile))
        }

    /**
     * Зберігає аватар і повертає його відносний шлях. Старий файл
     * видаляється: у сховищі не накопичуються аватри минулих версій.
     */
    suspend fun setAvatar(userId: Int, bytes: ByteArray): ApiResponse<AvatarResponse> {
        if (bytes.isEmpty()) return ApiResponse.fail("Порожній файл", "EMPTY_FILE")
        if (bytes.size > AppConfig.MAX_PHOTO_BYTES) {
            return ApiResponse.fail("Аватар більший за 10 МБ", "TOO_LARGE")
        }
        val fileName = media.save(MediaStorage.KIND_AVATAR, bytes, "jpg")
            ?: return ApiResponse.fail("Сховище медіа недоступне", "MEDIA_UNAVAILABLE")

        val oldFile = newSuspendedTransaction {
            val profile = ensureProfile(userId)
            val old = profile.avatarFile
            profile.avatarFile = fileName
            profile.updatedAt = System.currentTimeMillis()
            old
        }
        // Старий аватар видаляється поза транзакцією: диск — не база.
        if (oldFile != null) media.delete(MediaStorage.KIND_AVATAR, oldFile)
        return ApiResponse.ok(AvatarResponse(avatarUrl = "/media/avatar/$fileName"))
    }

    // --- допоміжне ----------------------------------------------------------

    /**
     * Профіль користувача, створюючи дефолтний за потреби.
     * Викликається лише всередині транзакції; FeedService користується
     * тим самим способом при складанні рядків стрічки.
     */
    internal fun ensureProfile(userId: Int): ProfileEntity {
        ProfileEntity.find { ProfileTable.userId eq userId }.firstOrNull()?.let { return it }
        val user = UserEntity.findById(userId)
            ?: error("Профіль належить неіснуючому користувачу $userId")
        return ProfileEntity.new {
            this.userId = userId
            displayName = user.login
            bio = null
            avatarFile = null
            updatedAt = System.currentTimeMillis()
        }
    }

    /** Профіль, створюючи дефолтний за потреби; для стрічки та власних сторінок. */
    suspend fun ensureAndGet(userId: Int): ApiResponse<ProfileDto> = newSuspendedTransaction {
        ok(profileOf(ensureProfile(userId)))
    }

    /** DTO профіля разом із логіном користувача. */
    fun profileOf(profile: ProfileEntity): ProfileDto {
        val user = UserEntity.findById(profile.userId)
        return ProfileDto(
            userId = profile.userId,
            login = user?.login ?: "unknown",
            displayName = profile.displayName,
            bio = profile.bio,
            avatarPath = profile.avatarFile?.let { "/media/avatar/$it" },
        )
    }
}
