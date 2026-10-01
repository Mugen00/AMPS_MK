package dev.kagami.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.kagami.app.core.SettingsStore
import dev.kagami.app.core.ThemeMode
import dev.kagami.app.data.model.BridgeHealth
import dev.kagami.app.data.remote.BridgeClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class SettingsUiState(
    val bridgeUrl: String = "",
    val jamendoClientId: String = "",
    val themeMode: ThemeMode = ThemeMode.DARK,
    val autoCheckBridge: Boolean = true,
    val keepHistory: Boolean = true,
    val health: BridgeHealth? = null,
    val checking: Boolean = false,
    val dirty: Boolean = false,
)

class SettingsViewModel(
    private val settings: SettingsStore,
    private val bridge: BridgeClient,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val current = settings.settings.first()
            _state.value = _state.value.copy(
                bridgeUrl = current.bridgeUrl,
                jamendoClientId = current.jamendoClientId,
                themeMode = current.themeMode,
                autoCheckBridge = current.autoCheckBridge,
                keepHistory = current.keepSearchHistory,
            )
            if (current.autoCheckBridge) check()
        }
    }

    fun onBridgeUrl(value: String) {
        _state.value = _state.value.copy(bridgeUrl = value, dirty = true)
    }

    fun onJamendoId(value: String) {
        _state.value = _state.value.copy(jamendoClientId = value, dirty = true)
    }

    fun save() = viewModelScope.launch {
        settings.setBridgeUrl(_state.value.bridgeUrl)
        settings.setJamendoClientId(_state.value.jamendoClientId)
        _state.value = _state.value.copy(dirty = false)
        check()
    }

    fun check() = viewModelScope.launch {
        _state.value = _state.value.copy(checking = true)
        val health = bridge.health()
        _state.value = _state.value.copy(checking = false, health = health)
    }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch {
        settings.setThemeMode(mode)
        _state.value = _state.value.copy(themeMode = mode)
    }

    fun setAutoCheck(value: Boolean) = viewModelScope.launch {
        settings.setAutoCheckBridge(value)
        _state.value = _state.value.copy(autoCheckBridge = value)
    }

    fun setKeepHistory(value: Boolean) = viewModelScope.launch {
        settings.setKeepHistory(value)
        _state.value = _state.value.copy(keepHistory = value)
    }

    class Factory(
        private val settings: SettingsStore,
        private val bridge: BridgeClient,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(settings, bridge) as T
    }
}
