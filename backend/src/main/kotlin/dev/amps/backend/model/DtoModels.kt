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
    val password: String
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
