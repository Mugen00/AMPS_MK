package dev.amps.app.ui.screens.anime

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.amps.app.data.remote.anilist.AniListClient
import dev.amps.app.data.remote.backend.ApiResult
import dev.amps.app.data.remote.backend.BackendApi
import dev.amps.app.data.remote.backend.BackendSession
import dev.amps.app.data.remote.backend.dto.AnimeListDto
import dev.amps.app.data.remote.backend.dto.AnimeListResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 1.2.2: каталог аніме (AniList) + мій список акаунта.
 *
 * Джерела карток: топ, сезон, жанр, пошук. Доданий тайтл пишеться в акаунт
 * на бекенді (як плейлісти); звідти нагадувальний воркер бере тайтли
 * з увімкненими нагадуваннями про нові серії.
 */
class AnimeViewModel(
    private val anilist: AniListClient,
    private val api: BackendApi,
    private val backendSession: BackendSession?,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = false,
        val cards: List<AniListClient.AnimeCard> = emptyList(),
        val query: String = "",
        val error: String? = null,
        /** Мій список акаунта; потрібен вхід. */
        val myList: List<AnimeListDto> = emptyList(),
        val authorized: Boolean = false,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** Відкрита картка — діалог «у мій список». */
    private val _selected = MutableStateFlow<AniListClient.AnimeCard?>(null)
    val selected: StateFlow<AniListClient.AnimeCard?> = _selected

    companion object {
        val GENRES = listOf(
            "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror",
            "Mecha", "Music", "Mystery", "Psychological", "Romance", "Sci-Fi",
            "Slice of Life", "Sports", "Supernatural", "Thriller",
        )
        val STATUS_LABELS = linkedMapOf(
            "watching" to "Дивлюсь",
            "planned" to "Планую",
            "watched" to "Переглянуто",
        )
    }

    /** Свій список по anilistId — щоб кнопки карток показували статус. */
    private val myById = HashMap<Int, AnimeListDto>()

    init {
        viewModelScope.launch {
            val authorized = backendSession?.current() != null
            _state.update { it.copy(authorized = authorized) }
            refreshMyList()
        }
        loadTop()
    }

    fun loadTop() = load { anilist.top() }
    fun loadSeason() = load { anilist.season() }
    fun loadGenre(genre: String) = load { anilist.byGenre(genre) }
    fun search(query: String) = load { anilist.search(query) }

    /** Останній завантажувач — для свайп-перезагрузки (1.4.0). */
    private var lastLoader: (suspend () -> List<AniListClient.AnimeCard>?)? = null

    /** Свайп-перезагрузка: повторює останній запит каталогу. */
    fun refresh() {
        lastLoader?.let { load(it) }
    }

    fun openCard(card: AniListClient.AnimeCard?) {
        _selected.value = card
    }

    /**
     * 1.2.2 (патч 28): null від клієнта — AniList недоступний (мережа,
     * Cloudflare), а порожній список — чесний «нічого не знайдено».
     */
    private fun load(block: suspend () -> List<AniListClient.AnimeCard>?) {
        lastLoader = block
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val cards = block()
                if (cards == null) {
                    _state.update {
                        it.copy(loading = false, error = "AniList недоступний — перевірте інтернет і спробуйте ще раз")
                    }
                } else {
                    _state.update { it.copy(loading = false, cards = cards) }
                }
            } catch (e: java.io.IOException) {
                _state.update { it.copy(loading = false, error = "Мережа недоступна: ${e.message}") }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "Помилка: ${e.message}") }
            }
        }
    }

    /** Свій список — перечитується з бекенду. */
    fun refreshMyList() {
        val session = backendSession ?: return
        viewModelScope.launch {
            when (val result = session.authedCall { api.getAnimeList(it) }) {
                is ApiResult.Success -> {
                    val items = (result.data as AnimeListResponse).items
                    synchronized(myById) {
                        myById.clear()
                        items.forEach { myById[it.anilistId] = it }
                    }
                    _state.update { it.copy(myList = items) }
                }
                else -> Unit
            }
        }
    }

    /** Статус тайтла в моєму списку (null — не в списку). */
    fun myStatus(anilistId: Int): AnimeListDto? = synchronized(myById) { myById[anilistId] }

    /** Додати/змінити статус тайтла у своєму списку. */
    fun addToMyList(card: AniListClient.AnimeCard, status: String) =
        upsert(card, status, reminder = true)

    /** Перемкнути нагадування про серії. */
    fun setReminder(card: AniListClient.AnimeCard, reminder: Boolean) {
        val status = synchronized(myById) { myById[card.anilistId]?.status ?: "watching" }
        upsert(card, status, reminder)
    }

    fun removeFromMyList(card: AniListClient.AnimeCard) {
        val session = backendSession
        if (session == null) {
            _state.update { it.copy(message = "Увійдіть, щоб керувати списком") }
            return
        }
        viewModelScope.launch {
            when (val r = session.authedCall { header ->
                api.deleteAnimeList(authHeader = header, anilistId = card.anilistId)
            }) {
                is ApiResult.Success -> {
                    synchronized(myById) { myById.remove(card.anilistId) }
                    refreshMyListQuiet()
                    _state.update { it.copy(message = "Видалено: ${card.title}") }
                }
                is ApiResult.Failure -> _state.update { it.copy(message = r.error) }
                is ApiResult.NetworkError ->
                    _state.update { it.copy(message = "Мережа недоступна") }
            }
        }
    }

    /** Видалення зі вкладки «Моє»: у списку лежить AnimeListDto, не картка. */
    fun removeFromMyListById(anilistId: Int, title: String) {
        val session = backendSession
        if (session == null) {
            _state.update { it.copy(message = "Увійдіть, щоб керувати списком") }
            return
        }
        viewModelScope.launch {
            when (val r = session.authedCall { header ->
                api.deleteAnimeList(authHeader = header, anilistId = anilistId)
            }) {
                is ApiResult.Success -> {
                    synchronized(myById) { myById.remove(anilistId) }
                    refreshMyListQuiet()
                    _state.update { it.copy(message = "Видалено: $title") }
                }
                is ApiResult.Failure -> _state.update { it.copy(message = r.error) }
                is ApiResult.NetworkError ->
                    _state.update { it.copy(message = "Мережа недоступна") }
            }
        }
    }

    private fun upsert(card: AniListClient.AnimeCard, status: String, reminder: Boolean) {
        val session = backendSession
        if (session == null) {
            _state.update { it.copy(message = "Увійдіть, щоб зберігати тайтли в акаунті") }
            return
        }
        viewModelScope.launch {
            when (
                val r = session.authedCall { header ->
                    api.upsertAnimeList(
                        authHeader = header,
                        anilistId = card.anilistId,
                        title = card.title,
                        coverUrl = card.coverUrl,
                        status = status,
                        reminder = reminder,
                    )
                }
            ) {
                is ApiResult.Success -> {
                    synchronized(myById) {
                        myById[card.anilistId] = AnimeListDto(
                            anilistId = card.anilistId,
                            title = card.title,
                            coverUrl = card.coverUrl,
                            status = status,
                            reminder = reminder,
                            updatedAt = 0,
                        )
                    }
                    refreshMyList()
                    _state.update { it.copy(message = "Збережено: ${card.title}") }
                }
                is ApiResult.Failure -> _state.update { it.copy(message = r.error) }
                is ApiResult.NetworkError ->
                    _state.update { it.copy(message = "Мережа недоступна") }
            }
        }
    }

    /** Тихе перечитування списку без повідомлень. */
    fun refreshMyListQuiet() {
        viewModelScope.launch {
            when (val r = backendSession?.let { s ->
                s.authedCall { header -> api.getAnimeList(header) }
            }) {
                is ApiResult.Success -> {
                    val items = (r.data as AnimeListResponse).items
                    synchronized(myById) {
                        myById.clear()
                        items.forEach { myById[it.anilistId] = it }
                    }
                    _state.update { it.copy(myList = items) }
                }
                else -> Unit
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    class Factory(
        private val anilist: AniListClient,
        private val api: BackendApi,
        private val backendSession: BackendSession?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AnimeViewModel(anilist, api, backendSession) as T
    }
}
