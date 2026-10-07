package dev.amps.app.data.remote.backend

import dev.amps.app.data.remote.backend.dto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 1.1.0: HTTP-клієнт для бекенду на OkHttp + kotlinx.serialization.
 *
 * Без Retrofit: жодної нової залежності, збірка лишається офлайн.
 *
 * Конверт відповідей один для всіх — { success, data, error, errorCode }.
 * Поле data розбирається serializer'ом конкретного виклику: попередня
 * версія декодувала все як TokenResponse, через що профіль, 2FA і
 * синхронізація не доживали б до ViewModel.
 */
class BackendApi private constructor(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val json: Json,
) {

    private val jsonMediaType = "application/json".toMediaType()

    companion object {
        private const val DEFAULT_BASE_URL = "https://amps-backend-production.up.railway.app/"

        @Volatile
        private var instance: BackendApi? = null

        fun getOrCreate(baseUrl: String? = null, client: OkHttpClient): BackendApi {
            val url = baseUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL
            return instance ?: synchronized(this) {
                instance ?: BackendApi(client, url, Json { ignoreUnknownKeys = true }).also { instance = it }
            }
        }

        fun reset() {
            instance = null
        }
    }

    // ===== Auth =====
    suspend fun register(request: RegisterRequest): ApiResult = withContext(Dispatchers.IO) {
        execute(
            "POST", "auth/register",
            json.encodeToString(RegisterRequest.serializer(), request),
            TokenResponse.serializer(), null,
        )
    }

    suspend fun login(request: LoginRequest): ApiResult = withContext(Dispatchers.IO) {
        execute(
            "POST", "auth/login",
            json.encodeToString(LoginRequest.serializer(), request),
            TokenResponse.serializer(), null,
        )
    }

    suspend fun verifyCode(request: VerifyCodeRequest): ApiResult = withContext(Dispatchers.IO) {
        execute(
            "POST", "auth/verify",
            json.encodeToString(VerifyCodeRequest.serializer(), request),
            TokenResponse.serializer(), null,
        )
    }

    suspend fun requestPasswordReset(request: PasswordResetRequest): ApiResult = withContext(Dispatchers.IO) {
        execute(
            "POST", "auth/password/reset",
            json.encodeToString(PasswordResetRequest.serializer(), request),
            UnitResult.serializer(), null,
        )
    }

    suspend fun confirmPasswordReset(request: PasswordResetConfirmRequest): ApiResult = withContext(Dispatchers.IO) {
        execute(
            "POST", "auth/password/reset/confirm",
            json.encodeToString(PasswordResetConfirmRequest.serializer(), request),
            TokenResponse.serializer(), null,
        )
    }

    suspend fun refreshTokens(request: RefreshRequest): ApiResult = withContext(Dispatchers.IO) {
        execute(
            "POST", "auth/refresh",
            json.encodeToString(RefreshRequest.serializer(), request),
            TokenResponse.serializer(), null,
        )
    }

    // ===== Profile =====
    suspend fun getProfile(authHeader: String): ApiResult = withContext(Dispatchers.IO) {
        execute("GET", "auth/me", null, UserInfo.serializer(), authHeader)
    }

    // ===== 2FA =====
    suspend fun getTwoFactorStatus(authHeader: String): ApiResult = withContext(Dispatchers.IO) {
        execute("GET", "auth/2fa/status", null, TwoFactorStatusResponse.serializer(), authHeader)
    }

    suspend fun setupTwoFactor(authHeader: String): ApiResult = withContext(Dispatchers.IO) {
        execute("POST", "auth/2fa/setup", null, TwoFactorSetupResponse.serializer(), authHeader)
    }

    suspend fun verifyTwoFactor(authHeader: String, request: TwoFactorVerifyRequest): ApiResult =
        withContext(Dispatchers.IO) {
            execute(
                "POST", "auth/2fa/verify",
                json.encodeToString(TwoFactorVerifyRequest.serializer(), request),
                TwoFactorStatusResponse.serializer(), authHeader,
            )
        }

    suspend fun disableTwoFactor(authHeader: String): ApiResult = withContext(Dispatchers.IO) {
        execute("POST", "auth/2fa/disable", null, TwoFactorStatusResponse.serializer(), authHeader)
    }

    // ===== Sync =====
    suspend fun getSyncData(authHeader: String): ApiResult = withContext(Dispatchers.IO) {
        execute("GET", "auth/sync", null, SyncResponse.serializer(), authHeader)
    }

    suspend fun syncData(authHeader: String, request: SyncRequest): ApiResult = withContext(Dispatchers.IO) {
        execute(
            "POST", "auth/sync",
            json.encodeToString(SyncRequest.serializer(), request),
            SyncResponse.serializer(), authHeader,
        )
    }

    // ===== Health =====
    suspend fun healthCheck(): ApiResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(baseUrl + "health").get().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    ApiResult.Success(Unit)
                } else {
                    ApiResult.Failure(response.code, "HTTP ${response.code}", null)
                }
            }
        } catch (e: Exception) {
            ApiResult.NetworkError(e.message ?: "Мережева помилка")
        }
    }

    // ===== Core =====

    /**
     * Один виконавець для всіх запитів: збирає запит, надсилає, читає
     * конверт і розбирає data у конкретний тип [dataSerializer].
     */
    private suspend fun <T : Any> execute(
        method: String,
        path: String,
        bodyJson: String?,
        dataSerializer: KSerializer<T>,
        authHeader: String?,
    ): ApiResult = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url(baseUrl + path)
        when (method) {
            "POST" -> requestBuilder.post((bodyJson ?: "").toRequestBody(jsonMediaType))
            "GET" -> requestBuilder.get()
        }
        authHeader?.let { requestBuilder.addHeader("Authorization", it) }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                val envelope = try {
                    json.decodeFromString(Envelope.serializer(), bodyString)
                } catch (_: Exception) {
                    null
                }

                if (!response.isSuccessful) {
                    ApiResult.Failure(
                        code = response.code,
                        error = envelope?.error ?: bodyString.takeIf { it.isNotBlank() }
                            ?: "HTTP ${response.code}",
                        errorCode = envelope?.errorCode,
                    )
                } else if (envelope == null) {
                    ApiResult.Failure(response.code, "Незрозуміла відповідь сервера", "BAD_BODY")
                } else if (!envelope.success) {
                    ApiResult.Failure(
                        response.code,
                        envelope.error ?: "Невідома помилка",
                        envelope.errorCode,
                    )
                } else {
                    val element = envelope.data
                        ?: return@use ApiResult.Failure(response.code, "Порожня відповідь сервера", "EMPTY_DATA")
                    val typed = json.decodeFromJsonElement(dataSerializer, element)
                    ApiResult.Success(typed)
                }
            }
        } catch (e: Exception) {
            ApiResult.NetworkError(e.message ?: "Мережева помилка")
        }
    }

    /** Конверт відповіді: data лишається сирим JsonElement до розбору. */
    @kotlinx.serialization.Serializable
    private data class Envelope(
        val success: Boolean,
        val data: JsonElement? = null,
        val error: String? = null,
        val errorCode: String? = null,
    )
}
