package dev.kagami.app.ui.screens.framesearch

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.kagami.app.core.SettingsStore
import dev.kagami.app.data.model.AnimeWikiPage
import dev.kagami.app.data.model.EmptyResult
import dev.kagami.app.data.repo.FrameRepository
import dev.kagami.app.util.ImageLoader
import dev.kagami.app.util.PreparedImage
import dev.kagami.app.util.readableMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class FrameSearchState(
    val image: PreparedImage? = null,
    val stage: String? = null,
    val page: AnimeWikiPage? = null,
    val miss: EmptyResult? = null,
    val error: String? = null,
    val bridgeUrl: String = "",
) {
    val busy: Boolean get() = stage != null
    val hasImage: Boolean get() = image != null
}

class FrameSearchViewModel(
    private val repository: FrameRepository,
    private val settings: SettingsStore,
    private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(FrameSearchState())
    val state: StateFlow<FrameSearchState> = _state.asStateFlow()

    private val _navigation = MutableStateFlow(false)
    val navigation: StateFlow<Boolean> = _navigation.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(bridgeUrl = settings.settings.first().bridgeUrl)
        }
    }

    fun onImagePicked(uri: Uri) = viewModelScope.launch {
        _state.value = _state.value.copy(error = null, miss = null, page = null)
        runCatching { ImageLoader.prepare(appContext, uri) }
            .onSuccess { image -> _state.value = _state.value.copy(image = image) }
            .onFailure { error -> _state.value = _state.value.copy(error = error.readableMessage()) }
    }

    fun search() = viewModelScope.launch {
        val image = _state.value.image ?: return@launch
        _state.value = _state.value.copy(
            stage = "Отправляю кадр в trace.moe…",
            error = null,
            miss = null,
            page = null,
        )

        runCatching {
            _state.value = _state.value.copy(stage = "Сверяю кадр с 1,7 млрд сцен…")
            repository.identify(image.bytes, image.fileName, image.mime, image.previewDataUrl)
        }
            .onSuccess { result ->
                when (result) {
                    is FrameRepository.Outcome.Found -> {
                        _state.value = _state.value.copy(stage = null, page = result.page)
                        _navigation.value = true
                    }
                    is FrameRepository.Outcome.Miss ->
                        _state.value = _state.value.copy(stage = null, miss = result.result)
                }
            }
            .onFailure { error ->
                _state.value = _state.value.copy(stage = null, error = error.readableMessage())
            }
    }

    fun consumeNavigation() {
        _navigation.value = false
    }

    fun clear() {
        _state.value = FrameSearchState(bridgeUrl = _state.value.bridgeUrl)
    }

    class Factory(
        private val repository: FrameRepository,
        private val settings: SettingsStore,
        private val appContext: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            FrameSearchViewModel(repository, settings, appContext) as T
    }
}
