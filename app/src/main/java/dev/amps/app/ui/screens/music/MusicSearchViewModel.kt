package dev.amps.app.ui.screens.music

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.amps.app.data.model.AudioLibraryEntry
import dev.amps.app.data.model.DownloadProgress
import dev.amps.app.data.model.FreeTrack
import dev.amps.app.data.model.FreeTrackFilter
import dev.amps.app.data.model.LicenceSummary
import dev.amps.app.data.model.MusicHit
import dev.amps.app.data.model.MusicSearchScope
import dev.amps.app.data.model.MusicSource
import dev.amps.app.data.model.MusicSourceError
import dev.amps.app.data.model.TrackTarget
import dev.amps.app.data.ranking.MusicRanker
import dev.amps.app.data.remote.backend.BackendApi
import dev.amps.app.data.remote.backend.BackendSession
import dev.amps.app.data.remote.backend.dto.PlaylistDto
import dev.amps.app.data.remote.backend.dto.PlaylistTrackAddRequest
import dev.amps.app.data.remote.backend.dto.PlaylistTrackDto
import dev.amps.app.data.remote.backend.dto.PlaylistsResponse
import dev.amps.app.data.remote.backend.dto.PlaylistTracksResponse
import dev.amps.app.music.MusicOnlinePlayer
import dev.amps.app.util.readableMessage
import dev.amps.app.data.repo.MusicRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State for the "Музыка" screen: the unified search, the licence-filtered
 * free-track tab and the local library.
 *
 * Search is debounced by [DEBOUNCE_MS] because the iTunes guidance is to stay
 * under roughly twenty requests a minute, and every source failure is turned
 * into a Russian banner instead of an exception.
 *
 * **Два правила, которые видны пользователю.**
 *
 * 1. [scope] выбирается до поиска и меняет его смысл, а не только набор строк:
 *    в `ANY` выдача смешанная, в `DOWNLOADABLE` — только то, что можно забрать.
 *    Поэтому переключение перезапускает поиск, а не перерисовывает старое.
 * 2. [hitSummary] — честный счёт выдачи. Пустая строка поиска и «треки найдены,
 *    но скачать их нельзя» — это разные состояния, и второе обязано быть видно
 *    словами, а не пустым списком.
 */
class MusicSearchViewModel(
    private val repository: MusicRepository,
    private val backendApi: BackendApi? = null,
    private val backendSession: BackendSession? = null,
    val player: MusicOnlinePlayer? = null,
) : ViewModel() {

    data class UiState(
        val query: String = "",
        val tab: Int = TAB_METADATA,
        val searching: Boolean = false,
        val loadingFree: Boolean = false,
        /** Что человек хочет получить: файлы, метаданные или и то и другое. */
        val scope: MusicSearchScope = MusicSearchScope.ANY,
        val hits: List<MusicHit> = emptyList(),
        val hitSummary: LicenceSummary = LicenceSummary(),
        /** Что ответили источники метаданных, даже если строки не показаны. */
        val metadataFound: Int = 0,
        val freeResults: List<FreeTrack> = emptyList(),
        val freeSummary: LicenceSummary = LicenceSummary(),
        val filter: FreeTrackFilter = FreeTrackFilter.OPEN,
        val errors: List<MusicSourceError> = emptyList(),
        val message: String? = null,
        val recentQueries: List<String> = emptyList(),
        val library: List<AudioLibraryEntry> = emptyList(),
        val importsInProgress: Boolean = false,
        // ===== 1.2.1: плейлісти акаунта (живуть у базі сервера) =====
        val playlists: List<PlaylistDto> = emptyList(),
        val playlistsLoading: Boolean = false,
        /** Відкритий плейліст: id; null — показуємо список плейлістів. */
        val openPlaylistId: Int? = null,
        val openPlaylistName: String = "",
        val openPlaylistTracks: List<PlaylistTrackDto> = emptyList(),
        val playlistBusy: Boolean = false,
        /** Трек, який користувач додає в плейліст (відкрито діалог вибору). */
        val addTrackTarget: FreeTrack? = null,
        /** Плейлісти прив'язані до акаунта — без входу вкладка просить увійти. */
        val playlistsAuthorized: Boolean = false,
    ) {
        val hasQuery: Boolean get() = query.isNotBlank()

        /**
         * Главное честное состояние: треки нашлись, но скачать их нельзя.
         *
         * Именно его раньше не существовало — вместо него был пустой список, а
         * пустой список читается как «такой музыки нет вообще». Считается по
         * [metadataFound], а не по показанным строкам: в режиме «только
         * скачиваемое» метаданные в выдачу не попадают вовсе, и без этого поля
         * режим молчал бы там, где должен говорить.
         */
        val metadataOnlyNoFiles: Boolean
            get() = hasQuery && hitSummary.fileRows == 0 && metadataFound > 0

        /**
         * Ничего не нашлось, и хотя бы один источник ответил.
         *
         * Отличается от предыдущего случая принципиально: здесь не «нельзя
         * скачать», а «нечего скачивать» — и подсказка должна быть другой.
         */
        val nothingAtAll: Boolean
            get() = hasQuery && hits.isEmpty() && !searching && metadataFound == 0
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
            _state.update {
                it.copy(
                    hits = emptyList(),
                    searching = false,
                    errors = emptyList(),
                    hitSummary = LicenceSummary(),
                    metadataFound = 0,
                )
            }
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
        _state.update {
            it.copy(
                query = "",
                hits = emptyList(),
                errors = emptyList(),
                searching = false,
                hitSummary = LicenceSummary(),
                metadataFound = 0,
            )
        }
    }

    /**
     * Смена режима поиска перезапускает сам поиск.
     *
     * Перерисовать старую выдачу нельзя: в режиме «только скачиваемое» она
     * содержала бы строки, которые человек попросил не показывать. Перезапуск
     * дешевле и честнее, чем объяснять, почему список не изменился.
     */
    fun onScopeChange(scope: MusicSearchScope) {
        if (scope == _state.value.scope) return
        _state.update { it.copy(scope = scope, hits = emptyList(), hitSummary = LicenceSummary()) }
        val query = _state.value.query
        if (query.isNotBlank()) submit()
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
            val outcome = try {
                repository.freeTracks(snapshot.query, snapshot.filter, FREE_LIMIT)
            } catch (cancel: CancellationException) {
                // Отмена — это не сбой: перезапущенный фильтр просто не должен
                // дописывать результат поверх уже показанного.
                throw cancel
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        loadingFree = false,
                        freeResults = emptyList(),
                        freeSummary = LicenceSummary(),
                        errors = listOf(MusicSourceError(FreeTrackErrorSource, "Свободные треки: ${error.readableMessage()}")),
                    )
                }
                return@launch
            }
            // Сводка считается из того, что реально вернулось, а не из того, что
            // обещал источник до запроса: у Jamendo с неверным client_id ответ
            // приходит пустым, и текст ошибки живёт в теле ответа.
            val summary = MusicRanker.summarise(outcome.results.map(MusicRanker::fromFreeTrack))
            _state.update {
                it.copy(loadingFree = false, freeResults = outcome.results, freeSummary = summary, errors = outcome.errors)
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
     * so the host only has to navigate to its own track route.
     */
    fun openResult(hit: MusicHit): Boolean {
        val target = hit.target ?: return false
        repository.openTarget(target)
        return true
    }

    fun openFreeTrack(track: FreeTrack): String = repository.openTarget(TrackTarget.Free(track))

    fun openEntry(entry: AudioLibraryEntry): String = repository.openTarget(TrackTarget.Local(entry))

    // --- files --------------------------------------------------------------

    /**
     * 1.2.1: завантажень більше немає. Замість кнопки «скачати» — ПЛЕЙ:
     * повне онлайн-прослуховування потоково з вільних джерел
     * (Jamendo / Internet Archive / ccMixter / Openverse; iTunes — 30с-прев'ю).
     * [queue] — що гратиме далі (видача пошуку).
     */
    fun playTrack(track: FreeTrack, queue: List<FreeTrack> = _state.value.freeResults) {
        val p = player ?: return
        val items = queue.filter { !it.audioUrl.isNullOrBlank() }.map { t ->
            MusicOnlinePlayer.QueueItem(
                trackKey = t.key,
                title = t.title,
                artist = t.artistName ?: "",
                audioUrl = t.audioUrl.orEmpty(),
                coverUrl = t.coverUrl,
                isPreview = t.source == MusicSource.ITUNES,
            )
        }
        p.play(items, track.key)
    }

    fun togglePlay() = player?.toggle()
    fun nextTrack() = player?.next()
    fun previousTrack() = player?.previous()
    fun stopPlayer() = player?.stop()

    // --- плейлісти акаунта (1.2.1) -------------------------------------------

    /** Плейлісти живуть у базі на сервері — потрібен вхід. */
    fun loadPlaylists() {
        val api = backendApi ?: return
        val session = backendSession ?: return
        viewModelScope.launch {
            _state.update { it.copy(playlistsLoading = true) }
            val stored = session.current()
            if (stored == null) {
                _state.update { it.copy(playlistsLoading = false, playlistsAuthorized = false) }
                return@launch
            }
            when (val result = api.getPlaylists("Bearer ${stored.accessToken}")) {
                is dev.amps.app.data.remote.backend.ApiResult.Success -> _state.update {
                    it.copy(
                        playlistsLoading = false,
                        playlistsAuthorized = true,
                        playlists = (result.data as PlaylistsResponse).playlists,
                    )
                }
                is dev.amps.app.data.remote.backend.ApiResult.Failure -> _state.update {
                    it.copy(playlistsLoading = false, message = "Плейлісти: ${result.error}")
                }
                is dev.amps.app.data.remote.backend.ApiResult.NetworkError -> _state.update {
                    it.copy(playlistsLoading = false, message = "Плейлісти: ${result.message}")
                }
            }
        }
    }

    fun createPlaylist(name: String) {
        val api = backendApi ?: return
        val session = backendSession ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val stored = session.current() ?: run {
                _state.update { it.copy(message = "Плейлісти зберігаються в акаунті — увійдіть у спільноті") }
                return@launch
            }
            _state.update { it.copy(playlistBusy = true) }
            when (val result = api.createPlaylist("Bearer ${stored.accessToken}", trimmed)) {
                is dev.amps.app.data.remote.backend.ApiResult.Success -> {
                    loadPlaylists()
                    _state.update { it.copy(playlistBusy = false, message = "Плейліст створено") }
                }
                is dev.amps.app.data.remote.backend.ApiResult.Failure ->
                    _state.update { it.copy(playlistBusy = false, message = result.error) }
                is dev.amps.app.data.remote.backend.ApiResult.NetworkError ->
                    _state.update { it.copy(playlistBusy = false, message = result.message) }
            }
        }
    }

    fun deletePlaylist(playlistId: Int) {
        val api = backendApi ?: return
        val session = backendSession ?: return
        viewModelScope.launch {
            val stored = session.current() ?: return@launch
            _state.update { it.copy(playlistBusy = true) }
            api.deletePlaylist("Bearer ${stored.accessToken}", playlistId)
            if (_state.value.openPlaylistId == playlistId) closePlaylist()
            loadPlaylists()
        }
    }

    fun openPlaylist(id: Int) {
        val api = backendApi ?: return
        val session = backendSession ?: return
        viewModelScope.launch {
            val stored = session.current() ?: return@launch
            _state.update { it.copy(playlistBusy = true, openPlaylistId = id) }
            when (val result = api.getPlaylistTracks("Bearer ${stored.accessToken}", id)) {
                is dev.amps.app.data.remote.backend.ApiResult.Success -> {
                    val name = _state.value.playlists
                        .firstOrNull { it.id == id }?.name.orEmpty()
                    _state.update {
                        it.copy(
                            playlistBusy = false,
                            openPlaylistId = id,
                            openPlaylistName = name,
                            openPlaylistTracks = (result.data as PlaylistTracksResponse).tracks,
                        )
                    }
                }
                is dev.amps.app.data.remote.backend.ApiResult.Failure ->
                    _state.update { it.copy(playlistBusy = false, message = result.error) }
                is dev.amps.app.data.remote.backend.ApiResult.NetworkError ->
                    _state.update { it.copy(playlistBusy = false, message = result.message) }
            }
        }
    }

    fun closePlaylist() {
        _state.update {
            it.copy(openPlaylistId = null, openPlaylistName = "", openPlaylistTracks = emptyList())
        }
    }

    /** Відкрити діалог «додати в плейліст» для трека з видачі. */
    fun showAddToPlaylist(track: FreeTrack) {
        loadPlaylists()
        _state.update { it.copy(addTrackTarget = track) }
    }

    fun dismissAddToPlaylist() = _state.update { it.copy(addTrackTarget = null) }

    /** Додати трек (знімок рядка пошуку) у свій плейліст. */
    fun addToPlaylist(track: FreeTrack, playlistId: Int) {
        val api = backendApi ?: return
        val session = backendSession ?: return
        viewModelScope.launch {
            val stored = session.current() ?: run {
                _state.update {
                    it.copy(addTrackTarget = null, message = "Плейлісти доступні після входу в акаунт")
                }
                return@launch
            }
            val request = PlaylistTrackAddRequest(
                source = track.source.name.lowercase(),
                sourceId = track.sourceId,
                title = track.title,
                artist = track.artistName.orEmpty(),
                audioUrl = track.audioUrl.orEmpty(),
                coverUrl = track.coverUrl,
                pageUrl = track.pageUrl,
                licenseUrl = track.license.url,
                durationSec = track.format.durationSec,
            )
            _state.update { it.copy(playlistBusy = true, addTrackTarget = null) }
            when (val result = api.addPlaylistTrack("Bearer ${stored.accessToken}", playlistId, request)) {
                is dev.amps.app.data.remote.backend.ApiResult.Success ->
                    _state.update { it.copy(playlistBusy = false, message = "Додано: ${track.title}") }
                is dev.amps.app.data.remote.backend.ApiResult.Failure ->
                    _state.update { it.copy(playlistBusy = false, message = result.error) }
                is dev.amps.app.data.remote.backend.ApiResult.NetworkError ->
                    _state.update { it.copy(playlistBusy = false, message = result.message) }
            }
        }
    }

    fun removePlaylistTrack(playlistId: Int, trackRowId: Int) {
        val api = backendApi ?: return
        val session = backendSession ?: return
        viewModelScope.launch {
            val stored = session.current() ?: return@launch
            _state.update { it.copy(playlistBusy = true) }
            when (
                api.removePlaylistTrack("Bearer ${stored.accessToken}", playlistId, trackRowId)
            ) {
                is dev.amps.app.data.remote.backend.ApiResult.Success -> {
                    _state.update { it.copy(playlistBusy = false) }
                    openPlaylist(playlistId)
                }
                else -> _state.update {
                    it.copy(playlistBusy = false, message = "Не вдалося прибрати трек")
                }
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
                        it.copy(
                            importsInProgress = false,
                            message = "Не удалось импортировать: ${error.readableMessage()}",
                        )
                    }
                }
        }
    }

    /**
     * 1.1.3: імпорт за прямою URL-адресою файлу, яку користувач вставив сам.
     * Застосунок не шукає по сайтах — лише завантажує вказаний файл,
     * перевіряє, що це аудіо, і кладе в бібліотеку з атрибуцією-URL.
     */
    fun importFromUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(importsInProgress = true) }
            runCatching { repository.importFromUrl(trimmed) }
                .onSuccess { entry ->
                    _state.update {
                        it.copy(importsInProgress = false, message = "Імпортовано: ${entry.fileName}")
                    }
                    refreshLibrary()
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            importsInProgress = false,
                            message = "Не вдалося імпортувати: ${error.readableMessage()}",
                        )
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
        val snapshot = _state.value
        _state.update { it.copy(searching = true) }
        val outcome = try {
            repository.searchAll(query, snapshot.scope, SEARCH_LIMIT)
        } catch (cancel: CancellationException) {
            // Пользователь допечатал запрос: отменённый ответ не должен ни
            // показаться, ни затереть собой новый.
            throw cancel
        } catch (error: Throwable) {
            _state.update {
                it.copy(
                    searching = false,
                    hits = emptyList(),
                    hitSummary = LicenceSummary(),
                    metadataFound = 0,
                    errors = listOf(MusicSourceError(FreeTrackErrorSource, "Поиск: ${error.readableMessage()}")),
                )
            }
            return
        }

        val summary = MusicRanker.summarise(outcome.hits)
        _state.update {
            it.copy(
                searching = false,
                hits = outcome.hits,
                hitSummary = summary,
                // Считается по ответу источников, а не по показанным строкам: в
                // режиме «только скачиваемое» метаданные в выдачу не попадают,
                // и без этого числа «треки найдены, скачать нельзя» не сработало бы.
                metadataFound = outcome.metadataFound,
                errors = outcome.errors,
            )
        }
        rememberQuery()
        if (_state.value.tab == TAB_FREE) loadFree()
    }

    companion object {
        const val TAB_METADATA = 0
        const val TAB_FREE = 1
        const val TAB_LIBRARY = 2

        /** 1.2.1: свої плейлісти акаунта (у базі сервера). */
        const val TAB_PLAYLISTS = 3

        /** iTunes asks for ≥350 ms between calls; 420 ms leaves a little slack. */
        const val DEBOUNCE_MS = 420L

        /**
         * Объединённая выдача: пять источников, и каждый уже отдаёт свой
         * максимум. 30 строк — это то, что человек способен пролистать, при этом
         * достаточно, чтобы скачиваемое не оказалось на тридцатой позиции.
         */
        const val SEARCH_LIMIT = 30
        const val FREE_LIMIT = 20

        /** Banner source used when the failure belongs to the whole screen. */
        val FreeTrackErrorSource = MusicSource.AMPS
    }
}

/**
 * Plain factory so the container can build the view model without a DI graph:
 * `viewModel(factory = MusicSearchViewModelFactory(container.musicRepository))`.
 */
class MusicSearchViewModelFactory(
    private val repository: MusicRepository,
    private val backendApi: BackendApi? = null,
    private val backendSession: BackendSession? = null,
    private val player: MusicOnlinePlayer? = null,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(MusicSearchViewModel::class.java)) {
            "MusicSearchViewModelFactory не умеет создавать ${modelClass.name}"
        }
        return MusicSearchViewModel(repository, backendApi, backendSession, player) as T
    }
}