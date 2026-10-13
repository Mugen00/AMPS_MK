package dev.amps.backend.service

import dev.amps.backend.config.AppConfig
import dev.amps.backend.model.ApiResponse
import dev.amps.backend.model.ApiResponse.Companion.ok
import dev.amps.backend.model.AvatarResponse
import dev.amps.backend.model.FavoriteAnimeDto
import dev.amps.backend.model.ProfileDto
import dev.amps.backend.model.ProfileEntity
import dev.amps.backend.model.ProfileTable
import dev.amps.backend.model.UserEntity
import dev.amps.backend.model.UserTable
import dev.amps.backend.model.UsersSearchResponse
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.lowerCase
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
 *
 * 1.4.0: улюблені жанри й аніме зберігаються JSON-рядками в профілі —
 * окремі таблиці для списку з десятка елементів були б надмірністю.
 */
class ProfileService(private val media: MediaStorage) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun get(userId: Int): ApiResponse<ProfileDto> = newSuspendedTransaction {
        ok(profileOf(ensureProfile(userId)))
    }

    suspend fun update(
        userId: Int,
        displayName: String,
        bio: String?,
        favoriteGenres: List<String>? = null,
        favoriteAnime: List<dev.amps.backend.model.FavoriteAnimeDto>? = null,
    ): ApiResponse<ProfileDto> =
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
            val genres = favoriteGenres?.let { list ->
                val clean = list.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                if (clean.size > MAX_FAV_GENRES) {
                    return@newSuspendedTransaction ApiResponse.fail<ProfileDto>(
                        "Улюблених жанрів — не більше $MAX_FAV_GENRES", "TOO_MANY_GENRES")
                }
                clean
            }
            val anime = favoriteAnime?.let { list ->
                val clean = list.distinctBy { it.anilistId }.take(MAX_FAV_ANIME)
                if (list.size > MAX_FAV_ANIME) {
                    return@newSuspendedTransaction ApiResponse.fail<ProfileDto>(
                        "Улюблених аніме — не більше $MAX_FAV_ANIME", "TOO_MANY_ANIME")
                }
                clean
            }
            val profile = ensureProfile(userId)
            profile.displayName = name
            profile.bio = cleanBio
            if (genres != null) {
                profile.favGenres = json.encodeToString(ListSerializer(String.serializer()), genres)
            }
            if (anime != null) {
                profile.favAnime = json.encodeToString(ListSerializer(FavoriteAnimeDto.serializer()), anime)
            }
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

    /** Профіль, створюючи дефолтний за потреби; для стрічки та власних сторінок. */
    suspend fun ensureAndGet(userId: Int): ApiResponse<ProfileDto> = newSuspendedTransaction {
        ok(profileOf(ensureProfile(userId)))
    }

    /**
     * 1.6.1: пошук людей за ім'ям (профіль) або логіном — case-insensitive,
     * contains. Логін шукаємо по users, ім'я — по профилях; результати
     * з'єднуються без дублів, до [limit] профілів.
     */
    suspend fun searchUsers(query: String, limit: Int = 20): ApiResponse<UsersSearchResponse> =
        newSuspendedTransaction {
            val q = query.trim().lowercase()
            if (q.length < 2) return@newSuspendedTransaction ok(UsersSearchResponse(emptyList()))
            val found = LinkedHashMap<Int, ProfileDto>()
            ProfileEntity.find {
                ProfileTable.displayName.lowerCase() like "%$q%"
            }.orderBy(ProfileTable.updatedAt to SortOrder.DESC).limit(limit).toList().forEach {
                found[it.userId] = profileOf(it)
            }
            // Логіни, яких ще немає в результатах.
            if (found.size < limit) {
                UserEntity.find { UserTable.login.lowerCase() like "%$q%" }
                    .limit(limit).toList().forEach { user ->
                        val uid = user.id.value
                        if (!found.containsKey(uid)) {
                            found[uid] = profileOf(ensureProfile(uid))
                        }
                    }
            }
            ok(UsersSearchResponse(users = found.values.take(limit).toList()))
        }

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

    /** DTO профіля разом із логіном користувача. */
    fun profileOf(profile: ProfileEntity): ProfileDto {
        val user = UserEntity.findById(profile.userId)
        val genres = profile.favGenres?.let { raw ->
            runCatching { json.decodeFromString(ListSerializer(String.serializer()), raw) }
                .getOrDefault(emptyList())
        }.orEmpty()
        val anime = profile.favAnime?.let { raw ->
            runCatching {
                json.decodeFromString(ListSerializer(FavoriteAnimeDto.serializer()), raw)
            }.getOrDefault(emptyList())
        }.orEmpty()
        return ProfileDto(
            userId = profile.userId,
            login = user?.login ?: "unknown",
            displayName = profile.displayName,
            bio = profile.bio,
            avatarPath = profile.avatarFile?.let { "/media/avatar/$it" },
            favoriteGenres = genres,
            favoriteAnime = anime,
        )
    }

    companion object {
        const val MAX_FAV_GENRES = 8
        const val MAX_FAV_ANIME = 12
    }
}
