package dev.amps.app.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.amps.app.data.remote.backend.ApiResult
import dev.amps.app.data.remote.backend.BackendApi
import dev.amps.app.data.remote.backend.BackendSession
import dev.amps.app.data.remote.backend.dto.AvatarResponse
import dev.amps.app.data.remote.backend.dto.ProfileDto
import dev.amps.app.data.remote.backend.dto.ProfileUpdateRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 1.1.2: стан сторінки профіля — перегляд і редагування.
 *
 * Профіль створюється на сервері ліниво при першому читанні, тому навіть
 * акаунт з 1.1.0 побачить свій профіль із дефолтами (ім'я = логін).
 */
data class ProfileUiState(
    val loading: Boolean = false,
    val saving: Boolean = false,
    val uploadingAvatar: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val login: String = "",
    val displayName: String = "",
    val bio: String = "",
    /** Відносний шлях аватара ("/media/avatar/…") або null. */
    val avatarPath: String? = null,
    val dirty: Boolean = false,
    // 1.4.0: улюблені жанри й аніме профілю.
    val favoriteGenres: List<String> = emptyList(),
    val favoriteAnime: List<dev.amps.app.data.remote.backend.dto.FavoriteAnimeDto> = emptyList(),
    // ===== Сітка постів (1.4.0, Instagram-стиль) =====
    val postsGrid: List<dev.amps.app.data.remote.backend.dto.PostDto> = emptyList(),
    val gridLoading: Boolean = false,
    // ===== Статистика «анімешника» (1.2.3) =====
    val statsLoading: Boolean = false,
    val postsCount: Int = 0,
    val followersCount: Int = 0,
    /** Тайтлів зі статусом «дивлюсь». */
    val watchingCount: Int = 0,
    /** Назва тайтла, який зараз дивлюсь — «Тепер дивлюсь: …». */
    val watchingTitle: String? = null,
    // ===== Рівні і XP (1.2.4) =====
    val xp: Int = 0,
    val level: Int = 1,
    /** Скільки XP лишилось до наступного рівня (0 — максимальний). */
    val xpToNext: Int = 0,
)

class ProfileViewModel(
    private val backendApi: BackendApi,
    private val backendSession: BackendSession,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    /** Мій userId (для сітки власних постів через /profile/{id}/posts). */
    private var myUserId: Int? = null

    init {
        viewModelScope.launch {
            loadInternal()
            loadStatsInternal()
            loadPostsInternal()
        }
    }

    /** Свайп-перезагрузка (1.4.0): профіль + статистика + сітка постів. */
    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(refreshing = true)
            loadInternal()
            loadStatsInternal()
            loadPostsInternal()
            _state.value = _state.value.copy(refreshing = false)
        }
    }

    fun load() {
        viewModelScope.launch { loadInternal() }
    }

    private suspend fun loadInternal() {
        _state.value = _state.value.copy(loading = true, error = null)
        val result = backendSession.authedCall { header -> backendApi.fetchProfile(header) }
        when (result) {
            is ApiResult.Success -> {
                val profile = result.data as ProfileDto
                myUserId = profile.userId
                _state.value = _state.value.copy(
                    loading = false,
                    login = profile.login,
                    displayName = profile.displayName,
                    bio = profile.bio.orEmpty(),
                    avatarPath = profile.avatarPath,
                    favoriteGenres = profile.favoriteGenres,
                    favoriteAnime = profile.favoriteAnime,
                    dirty = false,
                )
            }
            is ApiResult.Failure -> _state.value = _state.value.copy(loading = false, error = result.error)
            is ApiResult.NetworkError -> _state.value = _state.value.copy(loading = false, error = result.message)
        }
    }

    /** 1.2.3: статистика «анімешника» — пости, підписники, аніме-список і «тепер дивлюсь». */
    fun loadStats() {
        viewModelScope.launch { loadStatsInternal() }
    }

    private suspend fun loadStatsInternal() {
        _state.value = _state.value.copy(statsLoading = true)
        val result = backendSession.authedCall { header -> backendApi.getProfileStats(header) }
        when (result) {
            is ApiResult.Success -> {
                val stats = result.data as dev.amps.app.data.remote.backend.dto.ProfileStatsDto
                _state.value = _state.value.copy(
                    statsLoading = false,
                    postsCount = stats.posts,
                    followersCount = stats.followers,
                    watchingCount = stats.watching,
                    watchingTitle = stats.watchingTitle,
                    xp = stats.xp,
                    level = stats.level,
                    xpToNext = stats.xpToNext,
                )
            }
            else -> _state.value = _state.value.copy(statsLoading = false)
        }
    }

    /** Сітка власних постів (1.4.0): мініатюри як в Instagram. */
    fun loadMyPosts() {
        val uid = myUserId ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(gridLoading = true)
            val result = backendSession.authedCall { header -> backendApi.fetchUserPosts(header, uid) }
            when (result) {
                is ApiResult.Success -> {
                    val feed = result.data as dev.amps.app.data.remote.backend.dto.FeedResponse
                    _state.value = _state.value.copy(
                        gridLoading = false,
                        postsGrid = feed.items.mapNotNull { item -> item.post },
                    )
                }
                else -> _state.value = _state.value.copy(gridLoading = false)
            }
        }
    }

    private suspend fun loadPostsInternal() {
        val uid = myUserId ?: return
        val result = backendSession.authedCall { header -> backendApi.fetchUserPosts(header, uid) }
        when (result) {
            is ApiResult.Success -> {
                val feed = result.data as dev.amps.app.data.remote.backend.dto.FeedResponse
                _state.value = _state.value.copy(
                    gridLoading = false,
                    postsGrid = feed.items.mapNotNull { item -> item.post },
                )
            }
            else -> _state.value = _state.value.copy(gridLoading = false)
        }
    }

    // --- Улюблене (1.4.0) ----------------------------------------------------

    /** Перемкнути жанр і одразу зберегти. */
    fun toggleFavoriteGenre(genre: String) {
        val current = _state.value
        val updated = if (current.favoriteGenres.contains(genre)) {
            current.favoriteGenres - genre
        } else {
            current.favoriteGenres + genre
        }
        saveFavoritesAnime(updated, current.favoriteAnime)
    }

    /** Додати улюблений тайтл (максимум 12). */
    fun addFavoriteAnime(item: dev.amps.app.data.remote.backend.dto.FavoriteAnimeDto) {
        val current = _state.value
        if (current.favoriteAnime.any { it.anilistId == item.anilistId }) return
        if (current.favoriteAnime.size >= 12) {
            _state.value = _state.value.copy(notice = "Максимум 12 улюблених аніме")
            return
        }
        saveFavoritesAnime(current.favoriteGenres, current.favoriteAnime + item)
    }

    /** Прибрати тайтл з улюблених. */
    fun removeFavoriteAnime(anilistId: Int) {
        val current = _state.value
        saveFavoritesAnime(
            current.favoriteGenres,
            current.favoriteAnime.filterNot { it.anilistId == anilistId },
        )
    }

    /** Зберегти обидва списки одним PUT /profile. */
    fun saveFavoritesAnime(genres: List<String>, anime: List<dev.amps.app.data.remote.backend.dto.FavoriteAnimeDto>) {
        val current = _state.value
        if (current.saving) return
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, error = null)
            val result = backendSession.authedCall { header ->
                backendApi.updateProfile(
                    header,
                    ProfileUpdateRequest(
                        displayName = current.displayName.trim(),
                        bio = current.bio.trim().takeIf { it.isNotEmpty() },
                        favoriteGenres = genres,
                        favoriteAnime = anime,
                    ),
                )
            }
            when (result) {
                is ApiResult.Success -> {
                    val profile = result.data as ProfileDto
                    _state.value = _state.value.copy(
                        saving = false,
                        favoriteGenres = profile.favoriteGenres,
                        favoriteAnime = profile.favoriteAnime,
                        notice = null,
                    )
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, error = result.error)
                is ApiResult.NetworkError -> _state.value = _state.value.copy(saving = false, error = result.message)
            }
        }
    }

    /** Зберегти жанри одним списком (аніме не чіпаємо). */
    fun saveFavoriteGenres(genres: List<String>) {
        val current = _state.value
        if (current.saving) return
        viewModelScope.launch {
            _state.value = current.copy(saving = true, error = null)
            val result = backendSession.authedCall { header ->
                backendApi.updateProfile(
                    header,
                    ProfileUpdateRequest(
                        displayName = current.displayName.trim(),
                        bio = current.bio.trim().takeIf { it.isNotEmpty() },
                        favoriteGenres = genres,
                        favoriteAnime = null,
                    ),
                )
            }
            when (result) {
                is ApiResult.Success -> {
                    val profile = result.data as ProfileDto
                    _state.value = _state.value.copy(
                        saving = false,
                        favoriteGenres = profile.favoriteGenres,
                        favoriteAnime = profile.favoriteAnime,
                    )
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, error = result.error)
                is ApiResult.NetworkError -> _state.value = _state.value.copy(saving = false, error = result.message)
            }
        }
    }

    fun setDisplayName(text: String) {
        _state.value = _state.value.copy(displayName = text.take(64), dirty = true)
    }

    fun setBio(text: String) {
        _state.value = _state.value.copy(bio = text.take(280), dirty = true)
    }

    /** Зберігає ім'я та біо; порожнє біо означає «без біо». Улюблене не змінюється. */
    fun save() {
        val current = _state.value
        if (current.saving) return
        viewModelScope.launch {
            _state.value = current.copy(saving = true, error = null)
            val result = backendSession.authedCall { header ->
                backendApi.updateProfile(
                    header,
                    ProfileUpdateRequest(
                        displayName = current.displayName.trim(),
                        bio = current.bio.trim().takeIf { it.isNotEmpty() },
                        favoriteGenres = current.favoriteGenres,
                        favoriteAnime = current.favoriteAnime,
                    ),
                )
            }
            when (result) {
                is ApiResult.Success -> {
                    val profile = result.data as ProfileDto
                    _state.value = _state.value.copy(
                        saving = false,
                        displayName = profile.displayName,
                        bio = profile.bio.orEmpty(),
                        avatarPath = profile.avatarPath,
                        favoriteGenres = profile.favoriteGenres,
                        favoriteAnime = profile.favoriteAnime,
                        dirty = false,
                        notice = "Профіль збережено",
                    )
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, error = result.error)
                is ApiResult.NetworkError -> _state.value = _state.value.copy(saving = false, error = result.message)
            }
        }
    }

    /** Аватар: jpeg/png/webp до 10 МБ, сервер перетворює на файл у сховищі. */
    fun uploadAvatar(bytes: ByteArray) {
        if (_state.value.uploadingAvatar) return
        viewModelScope.launch {
            _state.value = _state.value.copy(uploadingAvatar = true, error = null)
            val result = backendSession.authedCall { header -> backendApi.uploadAvatar(header, bytes) }
            when (result) {
                is ApiResult.Success -> {
                    val avatar = result.data as AvatarResponse
                    _state.value = _state.value.copy(
                        uploadingAvatar = false,
                        avatarPath = avatar.avatarUrl,
                        notice = "Аватар оновлено",
                    )
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(uploadingAvatar = false, error = result.error)
                is ApiResult.NetworkError -> _state.value =
                    _state.value.copy(uploadingAvatar = false, error = result.message)
            }
        }
    }

    fun clearMessages() {
        _state.value = _state.value.copy(error = null, notice = null)
    }

    class Factory(
        private val backendApi: BackendApi,
        private val backendSession: BackendSession,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ProfileViewModel(backendApi, backendSession) as T
    }
}
