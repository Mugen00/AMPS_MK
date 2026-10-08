package dev.amps.app.data.remote.backend.dto

import kotlinx.serialization.Serializable

@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null,
    val errorCode: String? = null
) {
    fun isSuccess(): Boolean = success && data != null
    fun getOrThrow(): T {
        if (isSuccess()) return data!!
        throw BackendException(error ?: "Unknown error", errorCode)
    }
}

class BackendException(message: String, val errorCode: String?) : Exception(message)

// ===== Auth DTOs =====
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
    /** 1.1.2: код з аутентифікатора — потрібен лише при ввімкненій 2FA. */
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

@Serializable
data class UnitResult(
    val success: Boolean = true
)

// ===== 2FA DTOs =====
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

// ===== Sync DTOs =====
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

// ===== Профіль (1.1.2) =====

/** Профіль користувача: показуване ім'я, біо, аватар. */
@Serializable
data class ProfileDto(
    val userId: Int,
    val login: String,
    val displayName: String,
    val bio: String? = null,
    /** Відносний шлях аватара ("/media/avatar/…") або null. */
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
    /** Відносний шлях: /media/{kind}/{file}; до нього додається адреса сервера. */
    val url: String
)

/** Пост: автор, текст, вкладення, дата публікації, лічильники. */
@Serializable
data class PostDto(
    val id: Int,
    val author: ProfileDto,
    val text: String,
    val media: List<MediaDto> = emptyList(),
    /** Епоха-мілісекунди — дата публікації, яку показує застосунок. */
    val createdAt: Long,
    val likeCount: Int,
    val repostCount: Int,
    val likedByMe: Boolean,
    val repostedByMe: Boolean
)

/**
 * Рядок стрічки: власне пост (kind = "POST") або репост — тоді
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
data class PostCreatedResponse(
    val postId: Int
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