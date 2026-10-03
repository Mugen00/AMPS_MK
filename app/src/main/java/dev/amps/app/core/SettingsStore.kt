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
    /**
     * 1.0.3: ключ SauceNAO спрашивает пользователя.
     *
     * Раньше он лежал в `.env` на компьютере и в APK не попадал. Вшить его в
     * приложение — значит отдать его тому, кто распакует APK, а под ним
     * квота, за которую отвечает владелец. Поэтому пустой ключ здесь означает
     * «второго источника нет», и это честно показывается в вердикте.
     */
    val sauceNaoApiKey: String = "",
    /** 1.0.3: импортировать скачанный трек в музыкальную библиотеку телефона. */
    val importToMediaStore: Boolean = true,
    val keepSearchHistory: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.DARK,
) {
    /** Подтверждён ли хоть один независимый источник помимо trace.moe. */
    val hasSecondSource: Boolean get() = sauceNaoApiKey.isNotBlank()

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
    }
}

private val Context.dataStore by preferencesDataStore(name = "amps_settings")

class SettingsStore(private val context: Context) {

    private object Keys {
        val jamendoClientId = stringPreferencesKey("jamendo_client_id")
        val sauceNaoApiKey = stringPreferencesKey("sauce_nao_api_key")
        val importToMediaStore = booleanPreferencesKey("import_to_media_store")
        val keepHistory = booleanPreferencesKey("keep_history")
        val themeMode = stringPreferencesKey("theme_mode")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            jamendoClientId = prefs[Keys.jamendoClientId]?.takeIf { it.isNotBlank() }
                ?: JamendoClient.DEFAULT_CLIENT_ID,
            sauceNaoApiKey = prefs[Keys.sauceNaoApiKey].orEmpty(),
            importToMediaStore = prefs[Keys.importToMediaStore] ?: true,
            keepSearchHistory = prefs[Keys.keepHistory] ?: true,
            themeMode = prefs[Keys.themeMode]
                ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
                ?: ThemeMode.DARK,
        )
    }

    suspend fun setJamendoClientId(id: String) = put(Keys.jamendoClientId, id.trim())

    suspend fun setSauceNaoApiKey(key: String) = put(Keys.sauceNaoApiKey, key.trim())

    suspend fun setImportToMediaStore(value: Boolean) = put(Keys.importToMediaStore, value)

    suspend fun setKeepHistory(value: Boolean) = put(Keys.keepHistory, value)

    suspend fun setThemeMode(mode: ThemeMode) = put(Keys.themeMode, mode.name)

    private suspend fun <T> put(key: androidx.datastore.preferences.core.Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }
}
