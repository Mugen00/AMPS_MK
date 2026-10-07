package dev.amps.app.core

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.amps.app.data.remote.JamendoClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    /**
     * 1.0.3: Jamendo работает сразу, без ввода. [JamendoClient.DEFAULT_CLIENT_ID]
     * — открытый идентификатор клиентского приложения, а не ключ аккаунта,
     * поэтому поле оставлено лишь для того, чтобы можно было подставить свой.
     */
    val jamendoClientId: String = JamendoClient.DEFAULT_CLIENT_ID,
    /** 1.0.3: импортировать скачанный трек в музыкальную библиотеку телефона. */
    val importToMediaStore: Boolean = true,
    val keepSearchHistory: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.DARK,
    /** 1.1.0: базовий URL бекенду (Railway). Якщо порожній — використовується дефолтний. */
    val backendBaseUrl: String = "",
) {
    companion object {
        /**
         * Настройки моста (`bridge_url`, `auto_bridge`, `auto_check_bridge`)
         * намеренно удалены, а не просто переименованы: моста с 1.0.3 нет.
         * Старые значения остаются в DataStore как мёртвые записи — их можно
         * удалить вручную, но чистить автоматически при обновлении не нужно,
         * потому что лишний ключ в настройках ничего не ломает, а миграция
         * с риском потерять данные хуже пустого места в файле.
         */
        @Suppress("unused")
        const val LEGACY_BRIDGE_KEYS = "bridge_url, auto_bridge, auto_check_bridge"

        /**
         * 1.0.5: `sauce_nao_api_key` удалён из настроек вместе с самим
         * SauceNAO. Значение остаётся в файле как мёртвая запись — читать его
         * больше нечем, а удаление при обновлении рискует потерять данные
         * сильнее, чем просто оставить лишний байт в JSON.
         */
        @Suppress("unused")
        const val LEGACY_SAUCE_KEYS = "sauce_nao_api_key"
    }
}

private val Context.dataStore by preferencesDataStore(name = "amps_settings")

class SettingsStore(private val context: Context) {

    private object Keys {
        val jamendoClientId = stringPreferencesKey("jamendo_client_id")
        val importToMediaStore = booleanPreferencesKey("import_to_media_store")
        val keepHistory = booleanPreferencesKey("keep_history")
        val themeMode = stringPreferencesKey("theme_mode")
        val backendBaseUrl = stringPreferencesKey("backend_base_url")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            jamendoClientId = prefs[Keys.jamendoClientId]?.takeIf { it.isNotBlank() }
                ?: JamendoClient.DEFAULT_CLIENT_ID,
            importToMediaStore = prefs[Keys.importToMediaStore] ?: true,
            keepSearchHistory = prefs[Keys.keepHistory] ?: true,
            themeMode = prefs[Keys.themeMode]
                ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
                ?: ThemeMode.DARK,
            backendBaseUrl = prefs[Keys.backendBaseUrl]?.takeIf { it.isNotBlank() } ?: "",
        )
    }

    suspend fun setJamendoClientId(id: String) = put(Keys.jamendoClientId, id.trim())

    suspend fun setImportToMediaStore(value: Boolean) = put(Keys.importToMediaStore, value)

    suspend fun setKeepHistory(value: Boolean) = put(Keys.keepHistory, value)

    suspend fun setThemeMode(mode: ThemeMode) = put(Keys.themeMode, mode.name)

    /** 1.1.0: базовий URL бекенду для синхронізації та авторизації. */
    suspend fun setBackendBaseUrl(url: String) = put(Keys.backendBaseUrl, url.trim())

    private suspend fun <T> put(key: androidx.datastore.preferences.core.Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }
}
