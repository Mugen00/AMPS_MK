package dev.amps.app.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.amps.app.data.remote.backend.BackendApi
import dev.amps.app.data.remote.backend.BackendSession
import dev.amps.app.data.remote.backend.ApiResult
import dev.amps.app.data.remote.backend.dto.FeedItemDto
import dev.amps.app.data.remote.backend.dto.ProfileDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Стан екрана чужого профіля (1.2.5). */
data class UserProfileUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val profile: ProfileDto? = null,
    /** Чи підписаний поточний користувач на цього. */
    val isFollowing: Boolean = false,
    val followers: Int = 0,
    val postsCount: Int = 0,
    /** "posts" | "reposts" — активний таб. */
    val tab: String = "posts",
    val items: List<FeedItemDto> = emptyList(),
    val tabLoading: Boolean = false,
    val tabError: String? = null,
    val followBusy: Boolean = false,
)

class UserProfileViewModel(
    private val backendApi: BackendApi,
    private val backendSession: BackendSession,
    val userId: Int,
) : ViewModel() {

    private val _state = MutableStateFlow(UserProfileUiState())
    val state: StateFlow<UserProfileUiState> = _state.asStateFlow()

    init {
        load()
        loadTab("posts")
    }

    /** Профіль + лічильники. */
    fun load() {
        viewModelScope.launch {
            val result = backendSession.authedCall { header -> backendApi.fetchUserProfile(header, userId) }
            when (result) {
                is ApiResult.Success -> {
                    val data = result.data as dev.amps.app.data.remote.backend.dto.UserProfileResponse
                    _state.value = _state.value.copy(
                        loading = false,
                        profile = data.profile,
                        isFollowing = data.isFollowing,
                        followers = data.followers,
                        postsCount = data.postsCount,
                    )
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(loading = false, error = result.error)
                is ApiResult.NetworkError -> _state.value = _state.value.copy(loading = false, error = result.message)
            }
        }
    }

    /** Пости або репости профіля. */
    fun loadTab(tab: String) {
        _state.value = _state.value.copy(tab = tab, tabLoading = true, tabError = null)
        viewModelScope.launch {
            val result = backendSession.authedCall { header ->
                if (tab == "reposts") backendApi.fetchUserReposts(header, userId)
                else backendApi.fetchUserPosts(header, userId)
            }
            when (result) {
                is ApiResult.Success -> {
                    val feed = result.data as dev.amps.app.data.remote.backend.dto.FeedResponse
                    _state.value = _state.value.copy(tabLoading = false, items = feed.items)
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(tabLoading = false, tabError = result.error)
                is ApiResult.NetworkError -> _state.value = _state.value.copy(tabLoading = false, tabError = result.message)
            }
        }
    }

    /** Підписатись/відписатись — оновлює стан банера. */
    fun toggleFollow() {
        if (_state.value.followBusy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(followBusy = true)
            val result = backendSession.authedCall { header -> backendApi.toggleFollow(header, userId) }
            when (result) {
                is ApiResult.Success -> {
                    val toggle = result.data as dev.amps.app.data.remote.backend.dto.FollowToggleResponse
                    _state.value = _state.value.copy(
                        isFollowing = toggle.following,
                        followers = toggle.followers,
                        followBusy = false,
                    )
                }
                else -> _state.value = _state.value.copy(followBusy = false)
            }
        }
    }

    class Factory(
        private val backendApi: BackendApi,
        private val backendSession: BackendSession,
        private val userId: Int,
    ) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            UserProfileViewModel(backendApi, backendSession, userId) as T
    }
}
