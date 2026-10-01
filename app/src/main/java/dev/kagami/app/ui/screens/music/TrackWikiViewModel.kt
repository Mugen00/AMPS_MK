package dev.kagami.app.ui.screens.music

import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.kagami.app.data.model.MusicSearchResult
import dev.kagami.app.data.model.TrackTarget
import dev.kagami.app.data.model.TrackWikiPage
import dev.kagami.app.data.repo.MusicRepository
import dev.kagami.app.util.readableMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State for the Track Wiki page: identity, licence, technical facts, source
 * links and local playback.
 *
 * The page follows `MusicRepository.currentTargetId`, so the nav graph needs no
 * route argument: the search screen opens a target and navigates, and tapping a
 * "похожий трек" row swaps the page in place.
 *
 * Playback is `android.media.MediaPlayer` on a plain file path the app owns
 * (`filesDir/audio/`). Nothing here can be pointed at a streaming URL, which is
 * the point: the only playable things are files the user imported and files a
 * CC source published together with a readable licence.
 */
class TrackWikiViewModel(
    private val repository: MusicRepository,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val page: TrackWikiPage? = null,
        val error: String? = null,
        val message: String? = null,
        val saving: Boolean = false,
        val playing: Boolean = false,
        val positionMs: Int = 0,
        val durationMs: Int = 0,
        val editingLicence: Boolean = false,
    ) {
        val hasFile: Boolean get() = page?.playableFile != null
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val downloads = repository.downloads

    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    init {
        viewModelScope.launch {
            repository.currentTargetId.collectLatest { id ->
                if (id != null) show(id)
            }
        }
    }

    /** Re-opens whatever target is current; used by the "повторить" button. */
    fun load() {
        val id = repository.currentTargetId.value
        if (id == null) {
            _state.update { it.copy(loading = false, error = "Трек не выбран") }
            return
        }
        viewModelScope.launch { show(id) }
    }

    private suspend fun show(id: String) {
        _state.update { it.copy(loading = true, error = null, editingLicence = false) }
        releasePlayer()
        val target = repository.targetOf(id)
        if (target == null) {
            _state.update { it.copy(loading = false, error = "Трек не найден в текущей сессии") }
            return
        }
        runCatching { repository.wikiPage(target) }
            .onSuccess { page ->
                _state.update {
                    it.copy(
                        loading = false,
                        page = page,
                        error = null,
                        durationMs = (page.format.durationSec ?: 0) * 1000,
                    )
                }
                repository.rememberTrack(page)
                if (page.similar.isEmpty()) loadSimilar(page.artist, page.title)
            }
            .onFailure { error ->
                _state.update {
                    it.copy(loading = false, error = "Не удалось открыть трек: ${error.readableMessage()}")
                }
            }
    }

    private fun loadSimilar(artist: String?, title: String?) {
        if (artist.isNullOrBlank() && title.isNullOrBlank()) return
        viewModelScope.launch {
            val similar = runCatching { repository.similarTracks(artist, title) }.getOrDefault(emptyList())
            _state.update { current -> current.copy(page = current.page?.copy(similar = similar)) }
        }
    }

    // --- playback -----------------------------------------------------------

    fun togglePlayback() {
        val path = _state.value.page?.playableFile
        if (path.isNullOrBlank()) {
            _state.update { it.copy(message = "Файла нет — играть нечего") }
            return
        }
        if (_state.value.playing) {
            pause()
            return
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { preparePlayer(path) }
                startPlayback()
            }.onFailure { error ->
                releasePlayer()
                _state.update {
                    it.copy(playing = false, message = "Не удалось воспроизвести: ${error.readableMessage()}")
                }
            }
        }
    }

    private suspend fun preparePlayer(path: String) {
        releasePlayer()
        player = MediaPlayer().apply {
            setDataSource(path)
            setOnCompletionListener { stopPlayback() }
            setOnErrorListener { _, what, extra ->
                _state.update { it.copy(playing = false, message = "Ошибка плеера $what/$extra") }
                true
            }
            prepare()
        }
    }

    private fun startPlayback() {
        val active = player ?: return
        active.start()
        _state.update { it.copy(playing = true, durationMs = active.duration.coerceAtLeast(0)) }
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                _state.update { it.copy(positionMs = active.currentPosition.coerceAtLeast(0)) }
                delay(POSITION_INTERVAL_MS)
            }
        }
    }

    private fun pause() {
        player?.pause()
        ticker?.cancel()
        _state.update { it.copy(playing = false) }
    }

    private fun stopPlayback() {
        ticker?.cancel()
        _state.update { it.copy(playing = false, positionMs = 0) }
    }

    private fun releasePlayer() {
        ticker?.cancel()
        runCatching { player?.release() }
        player = null
        _state.update { it.copy(playing = false, positionMs = 0) }
    }

    // --- licence ------------------------------------------------------------

    fun onLicenceEditingChange(editing: Boolean) = _state.update { it.copy(editingLicence = editing) }

    /**
     * The user declares the licence of their own file. For a CC download the
     * source's terms win, so only the free-text attribution note is stored.
     */
    fun saveAttribution(licenseName: String?, licenseUrl: String?, note: String?) {
        val entryId = _state.value.page?.entryId
        if (entryId == null) {
            _state.update { it.copy(message = "Этот трек ещё не сохранён локально") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            runCatching { repository.setAttribution(entryId, licenseName, licenseUrl, note) }
                .onSuccess {
                    val refreshed = repository.targetOf(repository.currentTargetId.value.orEmpty())
                        ?.let { repository.wikiPage(it) }
                    _state.update { current ->
                        current.copy(
                            saving = false,
                            editingLicence = false,
                            page = refreshed ?: current.page,
                            message = "Лицензия сохранена",
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(saving = false, message = "Не удалось сохранить: ${error.readableMessage()}")
                    }
                }
        }
    }

    fun download() {
        val track = _state.value.page?.freeTrack ?: return
        viewModelScope.launch {
            runCatching { repository.download(track) }
                .onSuccess {
                    _state.update { it.copy(message = "Файл сохранён локально") }
                    load()
                }
                .onFailure { error ->
                    _state.update { it.copy(message = "Не удалось скачать: ${error.readableMessage()}") }
                }
        }
    }

    fun deleteLocalFile() {
        val entryId = _state.value.page?.entryId ?: return
        viewModelScope.launch {
            releasePlayer()
            runCatching { repository.deleteEntry(entryId) }
                .onSuccess {
                    _state.update { current ->
                        current.copy(message = "Файл удалён", page = current.page?.copy(localPath = null))
                    }
                }
                .onFailure { error -> _state.update { it.copy(message = error.readableMessage()) } }
        }
    }

    /** Opens a similar row in this same page. */
    fun openSimilar(result: MusicSearchResult) {
        repository.openTarget(TrackTarget.Metadata(result))
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        releasePlayer()
        super.onCleared()
    }

    companion object {
        const val POSITION_INTERVAL_MS = 500L
    }
}

class TrackWikiViewModelFactory(
    private val repository: MusicRepository,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(TrackWikiViewModel::class.java)) {
            "TrackWikiViewModelFactory не умеет создавать ${modelClass.name}"
        }
        return TrackWikiViewModel(repository) as T
    }
}
