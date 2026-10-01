package dev.kagami.app.ui.screens.wiki

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.kagami.app.data.local.StoredFrame
import dev.kagami.app.data.model.AnimeCharacter
import dev.kagami.app.data.model.AnimeWikiPage
import dev.kagami.app.data.model.CharacterGuess
import dev.kagami.app.data.repo.FrameRepository
import dev.kagami.app.util.readableMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class WikiUiState(
    val page: AnimeWikiPage? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val rawExpanded: Boolean = false,
)

class WikiViewModel(private val repository: FrameRepository) : ViewModel() {

    private val _state = MutableStateFlow(WikiUiState())
    val state: StateFlow<WikiUiState> = _state.asStateFlow()

    fun show(page: AnimeWikiPage) {
        _state.value = WikiUiState(page = page)
    }

    /** History entries only keep ids, so the page is rebuilt from AniList. */
    fun openStored(stored: StoredFrame) = viewModelScope.launch {
        _state.value = WikiUiState(loading = true)
        runCatching { repository.reload(stored.anilistId, stored) }
            .onSuccess { _state.value = WikiUiState(page = it) }
            .onFailure { _state.value = WikiUiState(error = it.readableMessage()) }
    }

    fun selectCharacter(character: AnimeCharacter) {
        val page = _state.value.page ?: return
        _state.value = _state.value.copy(
            page = page.copy(
                guess = CharacterGuess(character, 1.0, "выбрано вручную"),
            )
        )
    }

    fun toggleRaw() {
        _state.value = _state.value.copy(rawExpanded = !_state.value.rawExpanded)
    }

    class Factory(private val repository: FrameRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = WikiViewModel(repository) as T
    }
}
