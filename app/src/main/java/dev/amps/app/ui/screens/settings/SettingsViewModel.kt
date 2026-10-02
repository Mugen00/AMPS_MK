package dev.amps.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.amps.app.core.SettingsStore
import dev.amps.app.core.ThemeMode
import dev.amps.app.data.model.BridgeHealth
import dev.amps.app.data.remote.BridgeClient
import dev.amps.app.data.remote.BridgeDiscovery
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
    val autoBridge: Boolean = true,
    val discoveredUrl: String? = null,
    val discovering: Boolean = false,
    val health: BridgeHealth? = null,
    val checking: Boolean = false,
    val dirty: Boolean = false,
)

class SettingsViewModel(
    private val settings: SettingsStore,
    private val bridge: BridgeClient,
    private val discovery: BridgeDiscovery,
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
                autoBridge = current.autoBridge,
            )
            if (current.autoBridge) discover()
            if (current.autoCheckBridge) check()
        }
    }

    /**
     * 1.0.1: ask the network for a bridge instead of expecting a typed address.
     * Safe to call repeatedly — the result is cached inside [BridgeDiscovery].
     */
    fun discover() = viewModelScope.launch {
        _state.value = _state.value.copy(discovering = true)
        val found = runCatching { discovery.discover() }.getOrNull()
        _state.value = _state.value.copy(
            discovering = false,
            discoveredUrl = found?.url ?: BridgeDiscovery.cachedUrl(),
        )
        if (_state.value.autoCheckBridge) check()
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

    fun setAutoBridge(value: Boolean) = viewModelScope.launch {
        settings.setAutoBridge(value)
        _state.value = _state.value.copy(autoBridge = value)
        if (value) discover() else check()
    }

    class Factory(
        private val settings: SettingsStore,
        private val bridge: BridgeClient,
        private val discovery: BridgeDiscovery,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(settings, bridge, discovery) as T
    }
}
