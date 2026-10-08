package dev.amps.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.amps.app.core.SettingsStore
import dev.amps.app.core.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class SettingsUiState(
    val jamendoClientId: String = "",
    val themeMode: ThemeMode = ThemeMode.DARK,
    val importToMediaStore: Boolean = true,
    val keepHistory: Boolean = true,
    /** 1.1.3: ключ Gemini; порожній — AI-аналіз фото недоступний. */
    val geminiApiKey: String = "",
    /** 1.2.0: Google Web Client ID; порожній — кнопка Google захована. */
    val googleWebClientId: String = "",
    val dirty: Boolean = false,
)

/**
 * 1.0.5: ключа SauceNAO в настройках больше нет — вшивати ключ у APK
 * означає віддати комусь чужу квоту. 1.1.3 повертає поле ключа навмисно:
 * Gemini API потребує свого ключа, і він вводиться користувачем у
 * Налаштуваннях, зберігаючись лише на пристрої (DataStore).
 */
class SettingsViewModel(
    private val settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val current = settings.settings.first()
            _state.value = _state.value.copy(
                jamendoClientId = current.jamendoClientId,
                themeMode = current.themeMode,
                importToMediaStore = current.importToMediaStore,
                keepHistory = current.keepSearchHistory,
                geminiApiKey = current.geminiApiKey,
                googleWebClientId = current.googleWebClientId,
            )
        }
    }

    fun onJamendoId(value: String) {
        _state.value = _state.value.copy(jamendoClientId = value, dirty = true)
    }

    /** 1.1.3: ключ Gemini редагується в тому ж блоці «Ключі API». */
    fun onGeminiKey(value: String) {
        _state.value = _state.value.copy(geminiApiKey = value, dirty = true)
    }

    /** 1.2.0: Google Web Client ID — публічний, уводиться у Налаштуваннях. */
    fun onGoogleClientId(value: String) {
        _state.value = _state.value.copy(googleWebClientId = value, dirty = true)
    }

    fun save() = viewModelScope.launch {
        settings.setJamendoClientId(_state.value.jamendoClientId)
        settings.setGeminiApiKey(_state.value.geminiApiKey)
        settings.setGoogleWebClientId(_state.value.googleWebClientId)
        _state.value = _state.value.copy(dirty = false)
    }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch {
        settings.setThemeMode(mode)
        _state.value = _state.value.copy(themeMode = mode)
    }

    fun setImportToMediaStore(value: Boolean) = viewModelScope.launch {
        settings.setImportToMediaStore(value)
        _state.value = _state.value.copy(importToMediaStore = value)
    }

    fun setKeepHistory(value: Boolean) = viewModelScope.launch {
        settings.setKeepHistory(value)
        _state.value = _state.value.copy(keepHistory = value)
    }

    class Factory(
        private val settings: SettingsStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(settings) as T
    }
}
