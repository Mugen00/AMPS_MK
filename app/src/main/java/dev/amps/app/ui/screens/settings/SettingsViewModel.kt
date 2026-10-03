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
    val sauceNaoApiKey: String = "",
    val themeMode: ThemeMode = ThemeMode.DARK,
    val importToMediaStore: Boolean = true,
    val keepHistory: Boolean = true,
    val dirty: Boolean = false,
)

/**
 * 1.0.3: настройки моста исчезли, вместо них — ключи, которые вводит сам
 * пользователь.
 *
 * Ключ SauceNAO не вшивается в приложение намеренно: он лежит в `.env` на
 * компьютере разработчика, а APK его не содержит. Так квота остаётся того,
 * кому ключ выдан, и приложение, скачанное кем угодно, не тратит её вместо
 * него. Обратная сторона — из коробки поиск кадров опирается на один источник,
 * и приложение говорит об этом прямо.
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
                sauceNaoApiKey = current.sauceNaoApiKey,
                themeMode = current.themeMode,
                importToMediaStore = current.importToMediaStore,
                keepHistory = current.keepSearchHistory,
            )
        }
    }

    fun onJamendoId(value: String) {
        _state.value = _state.value.copy(jamendoClientId = value, dirty = true)
    }

    fun onSauceNaoKey(value: String) {
        _state.value = _state.value.copy(sauceNaoApiKey = value, dirty = true)
    }

    fun save() = viewModelScope.launch {
        settings.setJamendoClientId(_state.value.jamendoClientId)
        settings.setSauceNaoApiKey(_state.value.sauceNaoApiKey)
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