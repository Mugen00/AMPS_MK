package dev.amps.app.core

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val bridgeUrl: String = DEFAULT_BRIDGE_URL,
    val jamendoClientId: String = "",
    val autoCheckBridge: Boolean = true,
    val keepSearchHistory: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.DARK,
    /** 1.0.1: find the bridge over UDP instead of expecting a typed address. */
    val autoBridge: Boolean = true,
) {
    val bridgeConfigured: Boolean get() = bridgeUrl.isNotBlank()

    companion object {
        /**
         * Only used when [autoBridge] is off. 10.0.2.2 is the host loopback as
         * seen from the Android emulator; on a real phone the bridge is found
         * by UDP discovery, so nothing has to be typed.
         */
        const val DEFAULT_BRIDGE_URL = "http://10.0.2.2:8787"
    }
}

private val Context.dataStore by preferencesDataStore(name = "amps_settings")

class SettingsStore(private val context: Context) {

    private object Keys {
        val bridgeUrl = stringPreferencesKey("bridge_url")
        val jamendoClientId = stringPreferencesKey("jamendo_client_id")
        val autoCheckBridge = booleanPreferencesKey("auto_check_bridge")
        val keepHistory = booleanPreferencesKey("keep_history")
        val themeMode = stringPreferencesKey("theme_mode")
        val autoBridge = booleanPreferencesKey("auto_bridge")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            bridgeUrl = prefs[Keys.bridgeUrl] ?: AppSettings.DEFAULT_BRIDGE_URL,
            jamendoClientId = prefs[Keys.jamendoClientId].orEmpty(),
            autoCheckBridge = prefs[Keys.autoCheckBridge] ?: true,
            keepSearchHistory = prefs[Keys.keepHistory] ?: true,
            themeMode = prefs[Keys.themeMode]
                ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
                ?: ThemeMode.DARK,
            autoBridge = prefs[Keys.autoBridge] ?: true,
        )
    }

    suspend fun setBridgeUrl(url: String) = put(Keys.bridgeUrl, url.trim())

    suspend fun setJamendoClientId(id: String) = put(Keys.jamendoClientId, id.trim())

    suspend fun setAutoCheckBridge(value: Boolean) = put(Keys.autoCheckBridge, value)

    suspend fun setKeepHistory(value: Boolean) = put(Keys.keepHistory, value)

    suspend fun setThemeMode(mode: ThemeMode) = put(Keys.themeMode, mode.name)

    suspend fun setAutoBridge(value: Boolean) = put(Keys.autoBridge, value)

    private suspend fun <T> put(key: androidx.datastore.preferences.core.Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }
}
