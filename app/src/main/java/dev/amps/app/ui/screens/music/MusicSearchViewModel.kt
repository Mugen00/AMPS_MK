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

    /**
     * 1.0.3: скачать **и положить в музыку телефона** одним действием.
     *
     * Раньше файл оставался внутри приложения, и обычный плеер его не видел.
     * Теперь после скачивания он кладётся в `Музыка/AMPS` с тегами и обложкой —
     * то есть в список, который пользователь уже слушает.
     */
    fun downloadAndImport(track: FreeTrack) {
        viewModelScope.launch {
            runCatching { repository.importToLibrary(track) }
                .onSuccess { imported ->
                    refreshLibrary()
                    _state.update {
                        it.copy(message = "В «Музыка/AMPS»: ${imported.displayPath.substringAfterLast('/')}")
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(message = "Не удалось сохранить: ${error.readableMessage()}") }
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
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(MusicSearchViewModel::class.java)) {
            "MusicSearchViewModelFactory не умеет создавать ${modelClass.name}"
        }
        return MusicSearchViewModel(repository) as T
    }
}