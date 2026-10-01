package dev.kagami.app.ui.screens.music

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.kagami.app.data.model.AudioLibraryEntry
import dev.kagami.app.data.model.DownloadProgress
import dev.kagami.app.data.model.FreeTrack
import dev.kagami.app.data.model.FreeTrackFilter
import dev.kagami.app.data.model.MusicSearchResult
import dev.kagami.app.data.model.MusicSource
import dev.kagami.app.data.model.MusicSourceError
import dev.kagami.app.data.model.TrackTarget
import dev.kagami.app.util.readableMessage
import dev.kagami.app.data.repo.MusicRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State for the "Музыка" screen: the metadata search, the free-track tab and
 * the local library.
 *
 * Search is debounced by [DEBOUNCE_MS] because the iTunes guidance is to stay
 * under roughly twenty requests a minute, and every source failure is turned
 * into a Russian banner instead of an exception.
 */
class MusicSearchViewModel(
    private val repository: MusicRepository,
) : ViewModel() {

    data class UiState(
        val query: String = "",
        val tab: Int = TAB_METADATA,
        val searching: Boolean = false,
        val loadingFree: Boolean = false,
        val results: List<MusicSearchResult> = emptyList(),
        val freeResults: List<FreeTrack> = emptyList(),
        val filter: FreeTrackFilter = FreeTrackFilter.OPEN,
        val errors: List<MusicSourceError> = emptyList(),
        val message: String? = null,
        val recentQueries: List<String> = emptyList(),
        val library: List<AudioLibraryEntry> = emptyList(),
        val importsInProgress: Boolean = false,
    ) {
        val hasQuery: Boolean get() = query.isNotBlank()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Live download progress, keyed by `FreeTrack.key`. */
    val downloads: StateFlow<Map<String, DownloadProgress>> = repository.downloads

    private var searchJob: Job? = null
    private var freeJob: Job? = null

    init {
        refreshLibrary()
    }

    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value) }
        searchJob?.cancel()
        if (value.isBlank()) {
            _state.update { it.copy(results = emptyList(), searching = false, errors = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            runSearch(value)
        }
    }

    /** Explicit submit (keyboard "search"): skips the debounce. */
    fun submit() {
        val query = _state.value.query
        if (query.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { runSearch(query) }
    }

    fun clearQuery() {
        searchJob?.cancel()
        _state.update { it.copy(query = "", results = emptyList(), errors = emptyList(), searching = false) }
    }

    fun onTabChange(index: Int) {
        _state.update { it.copy(tab = index) }
        if (index == TAB_FREE && _state.value.freeResults.isEmpty()) {
            loadFree()
        }
    }

    fun onFilterChange(filter: FreeTrackFilter) {
        if (filter == _state.value.filter) return
        _state.update { it.copy(filter = filter) }
        loadFree()
    }

    fun loadFree() {
        val snapshot = _state.value
        freeJob?.cancel()
        freeJob = viewModelScope.launch {
            _state.update { it.copy(loadingFree = true) }
            val outcome = runCatching {
                repository.freeTracks(snapshot.query, snapshot.filter, FREE_LIMIT)
            }.getOrElse { error ->
                _state.update {
                    it.copy(
                        loadingFree = false,
                        freeResults = emptyList(),
                        errors = listOf(MusicSourceError(FreeTrackErrorSource, "Свободные треки: ${error.readableMessage()}")),
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(loadingFree = false, freeResults = outcome.results, errors = outcome.errors)
            }
        }
    }

    fun rememberQuery() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) return
        _state.update { it.copy(recentQueries = (listOf(query) + it.recentQueries).distinct().take(8)) }
    }

    // --- navigation targets -------------------------------------------------

    /**
     * Registers the row, marks it as the track the wiki screen should open, and
     * returns the id for callers that want to log it. The nav graph stays flat,
     * so the host only has to navigate to its track route.
     */
    fun openResult(result: MusicSearchResult): String =
        repository.openTarget(TrackTarget.Metadata(result))

    fun openFreeTrack(track: FreeTrack): String = repository.openTarget(TrackTarget.Free(track))

    fun openEntry(entry: AudioLibraryEntry): String = repository.openTarget(TrackTarget.Local(entry))

    // --- files --------------------------------------------------------------

    fun download(track: FreeTrack) {
        viewModelScope.launch {
            runCatching { repository.download(track) }
                .onSuccess { entry ->
                    refreshLibrary()
                    _state.update { it.copy(message = "Сохранено: ${entry.fileName}") }
                }
                .onFailure { error ->
                    _state.update { it.copy(message = "Не удалось скачать: ${error.readableMessage()}") }
                }
        }
    }

    fun importAudio(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(importsInProgress = true) }
            runCatching { repository.importAudio(uri) }
                .onSuccess { entry ->
                    _state.update {
                        it.copy(importsInProgress = false, message = "Импортировано: ${entry.fileName}")
                    }
                    refreshLibrary()
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(importsInProgress = false, message = "Не удалось импортировать: ${error.readableMessage()}")
                    }
                }
        }
    }

    fun deleteEntry(id: String) {
        viewModelScope.launch {
            runCatching { repository.deleteEntry(id) }
                .onSuccess { refreshLibrary() }
                .onFailure { error -> _state.update { it.copy(message = error.readableMessage()) } }
        }
    }

    fun refreshLibrary() {
        viewModelScope.launch {
            val entries = runCatching { repository.libraryEntries() }.getOrDefault(emptyList())
            _state.update { it.copy(library = entries) }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private suspend fun runSearch(query: String) {
        _state.update { it.copy(searching = true) }
        val outcome = runCatching { repository.searchTracks(query, METADATA_LIMIT) }
        outcome
            .onSuccess { result ->
                _state.update {
                    it.copy(searching = false, results = result.results, errors = result.errors)
                }
            }
            .onFailure { error ->
                _state.update {
                    it.copy(
                        searching = false,
                        results = emptyList(),
                        errors = listOf(MusicSourceError(FreeTrackErrorSource, "Поиск: ${error.readableMessage()}")),
                    )
                }
            }
        rememberQuery()
        if (_state.value.tab == TAB_FREE) loadFree()
    }

    companion object {
        const val TAB_METADATA = 0
        const val TAB_FREE = 1
        const val TAB_LIBRARY = 2

        /** iTunes asks for ≥350 ms between calls; 420 ms leaves a little slack. */
        const val DEBOUNCE_MS = 420L
        const val METADATA_LIMIT = 25
        const val FREE_LIMIT = 20

        /** Banner source used when the failure belongs to the whole screen. */
        val FreeTrackErrorSource = MusicSource.KAGAMI
    }
}

/**
 * Plain factory so the container can build the view model without a DI graph:
 * `viewModel(factory = MusicSearchViewModelFactory(container.musicRepository))`.
 */
class MusicSearchViewModelFactory(
    private val repository: MusicRepository,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(MusicSearchViewModel::class.java)) {
            "MusicSearchViewModelFactory не умеет создавать ${modelClass.name}"
        }
        return MusicSearchViewModel(repository) as T
    }
}
