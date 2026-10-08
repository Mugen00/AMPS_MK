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
    /**
     * 1.1.3: ключ Google AI Studio для AI-аналізу фото (Gemini). Порожній —
     * функція недоступна. Навмисно без дефолта в коді: публічний репозиторій.
     */
    val geminiApiKey: String = "",
    /**
     * 1.2.0: Google Client ID (Web-клієнт з Google Cloud Console). Він же —
     * serverClientId для Credential Manager, і його ж треба вписати у
     * GOOGLE_CLIENT_IDS на сервері. Порожній — кнопка «Увійти через Google»
     * захована. Публічний ідентифікатор клієнта, не секрет.
     */
    val googleWebClientId: String = "",
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
        val geminiApiKey = stringPreferencesKey("gemini_api_key")
        val googleWebClientId = stringPreferencesKey("google_web_client_id")
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
            geminiApiKey = prefs[Keys.geminiApiKey]?.takeIf { it.isNotBlank() } ?: "",
            googleWebClientId = prefs[Keys.googleWebClientId]?.takeIf { it.isNotBlank() } ?: "",
        )
    }

    suspend fun setJamendoClientId(id: String) = put(Keys.jamendoClientId, id.trim())

    suspend fun setImportToMediaStore(value: Boolean) = put(Keys.importToMediaStore, value)

    suspend fun setKeepHistory(value: Boolean) = put(Keys.keepHistory, value)

    suspend fun setThemeMode(mode: ThemeMode) = put(Keys.themeMode, mode.name)

    /** 1.1.0: базовий URL бекенду для синхронізації та авторизації. */
    suspend fun setBackendBaseUrl(url: String) = put(Keys.backendBaseUrl, url.trim())

    /** 1.1.3: ключ Gemini для AI-аналізу фото; зберігається лише на пристрої. */
    suspend fun setGeminiApiKey(key: String) = put(Keys.geminiApiKey, key.trim())

    /** 1.2.0: Google Web Client ID для входу через Google. */
    suspend fun setGoogleWebClientId(id: String) = put(Keys.googleWebClientId, id.trim())

    private suspend fun <T> put(key: androidx.datastore.preferences.core.Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }
}
