package dev.amps.app.data.remote.backend

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.amps.app.data.remote.backend.dto.RefreshRequest
import dev.amps.app.data.remote.backend.dto.TokenResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 1.1.2: сесія бекенда, що переживає рестарт застосунку.
 *
 * У 1.1.0 токени жили лише в пам'яті AuthViewModel — після перезапуску
 * користувача «викидало» з акаунта. Тепер access/refresh лежать у
 * DataStore (тим самим способом, що й налаштування), і стрічка, профіль
 * та фонова перевірка сповіщень мають до них доступ навіть без відкритого
 * екрана акаунта.
 *
 * [authedCall] — єдиний спосіб викликати захищені методи: підставляє
 * access-токен, а при 401 один раз оновлює його refresh-токеном і
 * повторює запит. Якщо оновити не вдалося — сесія очищається, і
 * користувачеві чесно кажуть увійти знову.
 */
data class StoredBackendSession(
    val login: String,
    val userId: Int,
    val accessToken: String,
    val refreshToken: String,
)

private val Context.backendSessionDataStore by preferencesDataStore(name = "amps_backend_session")

class BackendSession(
    private val context: Context,
    private val backendApi: BackendApi,
) {

    private object Keys {
        val accessToken = stringPreferencesKey("backend_access")
        val refreshToken = stringPreferencesKey("backend_refresh")
        val login = stringPreferencesKey("backend_login")
        val userId = intPreferencesKey("backend_user_id")
    }

    private val store = context.backendSessionDataStore

    /** Поточний збережений сесійний стан, якщо є. */
    val stored: Flow<StoredBackendSession?> = store.data.map { prefs ->
        val access = prefs[Keys.accessToken]
        val refresh = prefs[Keys.refreshToken]
        val login = prefs[Keys.login]
        if (access != null && refresh != null && !login.isNullOrBlank()) {
            StoredBackendSession(login = login, userId = prefs[Keys.userId] ?: 0, accessToken = access, refreshToken = refresh)
        } else {
            null
        }
    }

    suspend fun current(): StoredBackendSession? = stored.first()

    /** Зберігає свіжі токени після входу, реєстрації чи підтвердження кода. */
    suspend fun save(tokens: TokenResponse) {
        store.edit {
            it[Keys.accessToken] = tokens.accessToken
            it[Keys.refreshToken] = tokens.refreshToken
            it[Keys.login] = tokens.user.login
            it[Keys.userId] = tokens.user.id
        }
    }

    /** Вихід із бекенд-акаунта: токени забуваються. */
    suspend fun clear() {
        store.edit {
            it.remove(Keys.accessToken)
            it.remove(Keys.refreshToken)
            it.remove(Keys.login)
            it.remove(Keys.userId)
        }
    }

    /**
     * Захищений виклик із автопідстановкою токена: при 401 — одна спроба
     * оновити пару токенів і повторити запит.
     */
    suspend fun authedCall(block: suspend (String) -> ApiResult): ApiResult {
        val session = current()
            ?: return ApiResult.Failure(401, "Не авторизовано", "NO_SESSION")
        val first = block("Bearer ${session.accessToken}")
        if (first !is ApiResult.Failure || first.code != 401) return first
        val freshAccess = refresh(session.refreshToken) ?: return ApiResult.Failure(
            401, "Сесія завершилася — увійдіть знову", "SESSION_EXPIRED",
        )
        return block("Bearer $freshAccess")
    }

    /** Оновлює пару токенів; у разі невдачі сесія очищається. */
    private suspend fun refresh(refreshToken: String): String? {
        val result = backendApi.refreshTokens(RefreshRequest(refreshToken))
        return when (result) {
            is ApiResult.Success -> {
                val tokens = result.data as TokenResponse
                store.edit {
                    it[Keys.accessToken] = tokens.accessToken
                    it[Keys.refreshToken] = tokens.refreshToken
                }
                tokens.accessToken
            }
            else -> {
                clear()
                null
            }
        }
    }
}
