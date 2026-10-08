package dev.amps.app.ui.screens.framesearch

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.amps.app.core.SettingsStore
import dev.amps.app.data.model.AnimeWikiPage
import dev.amps.app.data.model.EmptyResult
import dev.amps.app.data.remote.GeminiVision
import dev.amps.app.data.repo.FrameRepository
import dev.amps.app.data.repo.Outcome
import dev.amps.app.util.ImageLoader
import dev.amps.app.util.PreparedImage
import dev.amps.app.util.readableMessage
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
) {
    val busy: Boolean get() = stage != null
    val hasImage: Boolean get() = image != null
}

/** 1.1.3: стан AI-аналізу фото через Gemini — окремо від пошуку по базах. */
data class GeminiState(
    val busy: Boolean = false,
    /** Відповідь моделі — Markdown, рендериться через Markwon. */
    val result: String? = null,
    val error: String? = null,
)

class FrameSearchViewModel(
    private val repository: FrameRepository,
    private val appContext: Context,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(FrameSearchState())
    val state: StateFlow<FrameSearchState> = _state.asStateFlow()

    private val _gemini = MutableStateFlow(GeminiState())
    val gemini: StateFlow<GeminiState> = _gemini.asStateFlow()

    private val _navigation = MutableStateFlow(false)
    val navigation: StateFlow<Boolean> = _navigation.asStateFlow()

    fun onImagePicked(uri: Uri) = viewModelScope.launch {
        _state.value = _state.value.copy(error = null, miss = null, page = null)
        runCatching { ImageLoader.prepare(appContext, uri) }
            .onSuccess { image -> _state.value = _state.value.copy(image = image) }
            .onFailure { error -> _state.value = _state.value.copy(error = error.readableMessage()) }
    }

    fun search() = viewModelScope.launch {
        val image = _state.value.image ?: return@launch
        // Стадии с 1.0.3 описывали запросы к трём разным сервисам. Теперь
        // внешний источник один, поэтому текст должен называть именно его —
        // иначе пользователь ждёт «проверку по 1,7 млрд сцен», которой больше нет.
        _state.value = _state.value.copy(
            stage = "Отправляю картинку в IQDB…",
            error = null,
            miss = null,
            page = null,
        )

        runCatching {
            _state.value = _state.value.copy(stage = "Жду совпадения от бо́ру-баз…")
            repository.identify(image.bytes, image.fileName, image.mime, image.previewDataUrl)
        }
            .onSuccess { result ->
                when (result) {
                    is Outcome.Found -> {
                        _state.value = _state.value.copy(stage = null, page = result.page)
                        _navigation.value = true
                    }
                    is Outcome.Miss ->
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

    /**
     * 1.1.3: AI-аналіз вибраного фото через Gemini. Незалежний від пошуку
     * по базах: працює і тоді, коли IQDB дав промах. Ключ читається з
     * налаштувань на кожен виклик — змінюється без перезапуску.
     */
    fun analyzeWithGemini() {
        val image = _state.value.image ?: return
        if (_gemini.value.busy) return
        viewModelScope.launch {
            _gemini.value = GeminiState(busy = true)
            runCatching {
                val key = settings.settings.first().geminiApiKey
                GeminiVision.analyze(image.bytes, key)
            }
                .onSuccess { markdown -> _gemini.value = GeminiState(result = markdown) }
                .onFailure { error -> _gemini.value = GeminiState(error = error.readableMessage()) }
        }
    }

    fun clearGemini() {
        _gemini.value = GeminiState()
    }

    fun clear() {
        _state.value = FrameSearchState()
        _gemini.value = GeminiState()
    }

    class Factory(
        private val repository: FrameRepository,
        private val appContext: Context,
        private val settings: SettingsStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            FrameSearchViewModel(repository, appContext, settings) as T
    }
}
