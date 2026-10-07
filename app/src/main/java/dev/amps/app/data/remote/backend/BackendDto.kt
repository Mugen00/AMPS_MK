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