package dev.amps.backend.model

import kotlinx.serialization.Serializable

/**
 * 1.1.0: DTO бекенду — дзеркальна копія контрактних класів застосунку
 * (dev.amps.app.data.remote.backend.dto). Імена полів змінювати не можна:
 * обидві сторони збиралися окремо, і узгодженність тримається саме цими
 * оголошеннями.
 */

@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null,
    val errorCode: String? = null
) {
    companion object {
        fun <T> ok(data: T) = ApiResponse(success = true, data = data)
        fun <T> fail(error: String, errorCode: String? = null) =
            ApiResponse<T>(success = false, error = error, errorCode = errorCode)
    }
}

/**
 * Помилка без узагальнення: у конверта окремий клас не потрібен,
 * клієнт все одно читає лише error/errorCode, а зірчастий проекційний
 * серіалізатор — ризик, якого можна уникнути.
 */
@Serializable
data class ApiError(
    val success: Boolean = false,
    val error: String,
    val errorCode: String? = null
)

// ===== Auth =====
@Serializable
data class RegisterRequest(
    val login: String,
    val email: String,
    val password: String,
    val phone: String? = null
)

@Serializable
data class LoginRequest(
    val login: String,
    val password: String,
    /**
     * 1.1.2: код з аутентифікатора. Обов'язковий лише тоді, коли у
     * користувача ввімкнено 2FA; без нього сервер відповідає NEED_2FA,
     * і клієнт показує крок введення кода.
     */
    val totp: String? = null
)

@Serializable
data class VerifyCodeRequest(
    val login: String,
    val code: String,
    val type: String  // "EMAIL_VERIFY" | "PHONE_VERIFY"
)

@Serializable
data class PasswordResetRequest(
    val email: String
)

@Serializable
data class PasswordResetConfirmRequest(
    val email: String,
    val code: String,
    val newPassword: String
)

@Serializable
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val user: UserInfo
)

@Serializable
data class UserInfo(
    val id: Int,
    val login: String,
    val email: String,
    val phone: String?,
    val emailVerified: Boolean,
    val phoneVerified: Boolean,
    val twoFactorEnabled: Boolean
)

@Serializable
data class RefreshRequest(
    val refreshToken: String
)

/**
 * 1.2.0: вхід через Google. Клієнт (Credential Manager) отримує
 * ID-токен, сервер перевіряє підпис через tokeninfo Google і "aud".
 */
@Serializable
data class GoogleAuthRequest(
    val idToken: String
)

@Serializable
data class UnitResult(
    val success: Boolean = true
)

// ===== 2FA =====
@Serializable
data class TwoFactorSetupResponse(
    val secret: String,
    val otpAuthUrl: String
)

@Serializable
data class TwoFactorVerifyRequest(
    val code: String
)

@Serializable
data class TwoFactorStatusResponse(
    val enabled: Boolean
)

// ===== Синхронізація =====
@Serializable
data class SyncRequest(
    val version: Int,
    val data: Map<String, String>
)

@Serializable
data class SyncResponse(
    val version: Int,
    val data: Map<String, String>
)

@Serializable
data class SyncConflictResponse(
    val serverVersion: Int,
    val serverData: Map<String, String>
)

/** Мапа запису користувача на DTO для клієнта. */
fun UserEntity.info() = UserInfo(
    id = id.value,
    login = login,
    email = email,
    phone = phone,
    emailVerified = emailVerified,
    phoneVerified = phoneVerified,
    twoFactorEnabled = twoFactorEnabled,
)

// ===== Профіль (1.1.2) =====
@Serializable
data class ProfileDto(
    val userId: Int,
    val login: String,
    val displayName: String,
    val bio: String? = null,
    /** Відносний шлях аватара або null; клієнт додає адресу сервера. */
    val avatarPath: String? = null
)

@Serializable
data class ProfileUpdateRequest(
    val displayName: String,
    val bio: String? = null
)

@Serializable
data class AvatarResponse(
    val avatarUrl: String
)

// ===== Спільнота (1.1.2) =====

@Serializable
data class MediaDto(
    /** "photo" | "video" */
    val kind: String,
    /** Відносний шлях: /media/{kind}/{file}; клієнт додає адресу сервера. */
    val url: String
)

@Serializable
data class PostDto(
    val id: Int,
    val author: ProfileDto,
    val text: String,
    val media: List<MediaDto> = emptyList(),
    /** Епоха-мілісекунди: дата публікації, яку показує застосунок. */
    val createdAt: Long,
    val likeCount: Int,
    val repostCount: Int,
    val likedByMe: Boolean,
    val repostedByMe: Boolean
)

/**
 * Рядок стрічки: або власне пост (kind = "POST"), або репост — тоді
 * [repostBy] вказує, хто поширив, а [post] несе оригінал.
 */
@Serializable
data class FeedItemDto(
    val kind: String,
    val post: PostDto,
    val repostBy: ProfileDto? = null,
    val at: Long
)

@Serializable
data class FeedResponse(
    val items: List<FeedItemDto>
)

@Serializable
data class LikeToggleResponse(
    val liked: Boolean,
    val likeCount: Int
)

@Serializable
data class RepostResponse(
    val reposted: Boolean,
    val repostCount: Int
)

@Serializable
data class PostCreatedResponse(
    val postId: Int
)

@Serializable
data class StoriesResponse(
    val stories: List<StoryDto>
)

// ===== Плейлісти (1.2.1) =====

@Serializable
data class PlaylistDto(
    val id: Int,
    val name: String,
    val trackCount: Int,
    val createdAt: Long
)

@Serializable
data class PlaylistsResponse(
    val playlists: List<PlaylistDto>
)

@Serializable
data class PlaylistCreatedResponse(
    val playlistId: Int
)

/** Трек у плейлісті: знімок рядка пошуку — грає навіть без пошуку. */
@Serializable
data class PlaylistTrackDto(
    val id: Int,
    val source: String,
    val sourceId: String,
    val title: String,
    val artist: String,
    val audioUrl: String,
    val coverUrl: String? = null,
    val pageUrl: String? = null,
    val licenseUrl: String? = null,
    val durationSec: Int? = null,
    val position: Int,
    val addedAt: Long
)

@Serializable
data class PlaylistCreateRequest(
    val name: String
)

@Serializable
data class PlaylistTrackAddRequest(
    val source: String,
    val sourceId: String,
    val title: String,
    val artist: String = "",
    val audioUrl: String,
    val coverUrl: String? = null,
    val pageUrl: String? = null,
    val licenseUrl: String? = null,
    val durationSec: Int? = null
)

@Serializable
data class PlaylistTracksResponse(
    val tracks: List<PlaylistTrackDto>
)

// ===== Сторіс (1.2.0) =====

/** Одна сторіс: медіа за відносним URL і час зникнення. */
@Serializable
data class StoryDto(
    val id: Int,
    val author: ProfileDto,
    /** "photo" | "video" */
    val kind: String,
    /** Відносний шлях: /media/{kind}/{file}; клієнт додає адресу сервера. */
    val url: String,
    val createdAt: Long,
    val expiresAt: Long,
    /** Уже прострочена? Сервер віддає лише живі, але клієнт перевіряє сам. */
    val expired: Boolean = false
)
