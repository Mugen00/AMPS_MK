package dev.amps.app.data.remote.backend

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.amps.app.core.SessionStore
import dev.amps.app.data.local.AccountStore
import dev.amps.app.data.local.HistoryEntry
import dev.amps.app.data.local.HistoryStore
import dev.amps.app.data.remote.backend.dto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 1.1.0: Менеджер синхронізації з бекендом.
 * Спрощена версія для офлайн-збірки.
 */
class SyncManager(
    private val context: Context,
    private val backendApi: BackendApi,
    private val sessionStore: SessionStore,
    private val accountStore: AccountStore,
    private val historyStore: HistoryStore,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private object Keys {
        val lastSyncVersion = longPreferencesKey("sync_version")
        val lastSyncTimestamp = longPreferencesKey("sync_timestamp")
        val pendingData = stringPreferencesKey("pending_data_json")
    }

    private object TokenKeys {
        val accessToken = stringPreferencesKey("access_token")
        val refreshToken = stringPreferencesKey("refresh_token")
    }

    // DataStore для синхронізації - як у SettingsStore
    private val Context.syncDataStore by preferencesDataStore(name = "amps_sync")
    private val dataStore = context.syncDataStore

    // DataStore для токенів
    private val Context.tokenDataStore by preferencesDataStore(name = "amps_tokens")
    private val tokenStore = context.tokenDataStore

    // Поточний стан синхронізації
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    sealed interface SyncState {
        data object Idle : SyncState
        data class Syncing(val progress: String) : SyncState
        data class Success(val timestamp: Long) : SyncState
        data class Conflict(val serverData: Map<String, String>, val localData: Map<String, String>) : SyncState
        data class Error(val message: String) : SyncState
    }

    /**
     * Запустити синхронізацію.
     */
    suspend fun sync(): SyncState {
        val session = sessionStore.session.first() ?: return SyncState.Error("Не авторизований")
        if (session.isGuest) return SyncState.Error("Гість не може синхронізуватися")

        val accessToken = getAccessToken() ?: return SyncState.Error("Немає access token")
        val authHeader = "Bearer $accessToken"

        _syncState.value = SyncState.Syncing("Підготовка даних...")
        val localData = collectLocalData()
        val currentVersion = getLocalVersion()

        _syncState.value = SyncState.Syncing("Відправка на сервер...")
        return try {
            val request = SyncRequest(version = currentVersion, data = localData)
            val result = backendApi.syncData(authHeader, request)

            when (result) {
                is ApiResult.Success -> {
                    saveLocalVersion(currentVersion + 1)
                    SyncState.Success(System.currentTimeMillis())
                }
                is ApiResult.Failure -> {
                    if (result.errorCode == "VERSION_CONFLICT") {
                        SyncState.Conflict(emptyMap(), localData)
                    } else {
                        SyncState.Error(result.error)
                    }
                }
                is ApiResult.NetworkError -> SyncState.Error(result.message)
            }
        } catch (e: Exception) {
            SyncState.Error("Мережева помилка: ${e.message}")
        }
    }

    /**
     * Вирішити конфлікт версій.
     */
    suspend fun resolveConflict(useLocal: Boolean): SyncState {
        val session = sessionStore.session.first() ?: return SyncState.Error("Не авторизований")
        val accessToken = getAccessToken() ?: return SyncState.Error("Немає токена")
        val authHeader = "Bearer $accessToken"

        _syncState.value = SyncState.Syncing("Вирішення конфлікту...")
        try {
            val currentVersion = getLocalVersion()
            val data = if (useLocal) collectLocalData() else collectLocalData()

            val request = SyncRequest(version = currentVersion, data = data)
            val result = backendApi.syncData(authHeader, request)

            return when (result) {
                is ApiResult.Success -> {
                    saveLocalVersion(currentVersion + 1)
                    SyncState.Success(System.currentTimeMillis())
                }
                is ApiResult.Failure -> SyncState.Error(result.error)
                is ApiResult.NetworkError -> SyncState.Error(result.message)
            }
        } catch (e: Exception) {
            return SyncState.Error("${e.message}")
        }
    }

    private suspend fun collectLocalData(): Map<String, String> = withContext(Dispatchers.IO) {
        val map = mutableMapOf<String, String>()
        val history: List<HistoryEntry> = historyStore.entries.first() ?: emptyList()
        map["history"] = json.encodeToString(history)
        return@withContext map
    }

    private suspend fun getLocalVersion(): Int {
        val prefs = dataStore.data.first()
        return prefs[Keys.lastSyncVersion]?.toInt() ?: 0
    }
    private suspend fun saveLocalVersion(version: Int) {
        dataStore.edit { it[Keys.lastSyncVersion] = version.toLong(); it[Keys.lastSyncTimestamp] = System.currentTimeMillis() }
    }

    private suspend fun getAccessToken(): String? {
        val prefs = tokenStore.data.first()
        return prefs[TokenKeys.accessToken]
    }
    private suspend fun getRefreshToken(): String? {
        val prefs = tokenStore.data.first()
        return prefs[TokenKeys.refreshToken]
    }
    suspend fun saveTokens(access: String, refresh: String) {
        tokenStore.edit { it[TokenKeys.accessToken] = access; it[TokenKeys.refreshToken] = refresh }
    }
    suspend fun clearAuth() {
        tokenStore.edit { it.remove(TokenKeys.accessToken); it.remove(TokenKeys.refreshToken) }
        _syncState.value = SyncState.Idle
    }
}