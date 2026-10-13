package dev.amps.app.ui.screens.reels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.amps.app.core.Loc
import dev.amps.app.data.remote.backend.ApiResult
import dev.amps.app.data.remote.backend.BackendApi
import dev.amps.app.data.remote.backend.BackendSession
import dev.amps.app.data.remote.backend.dto.PostCreatedResponse
import dev.amps.app.data.remote.backend.dto.ReelDto
import dev.amps.app.data.remote.backend.dto.ReelLikeResponse
import dev.amps.app.data.remote.backend.dto.ReelsResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 1.6.0: стан екрана Рілси — вертикальна лента відео 9:16 у повному
 * екрані (свайп вгору/вниз), лайки, публікація з галереї з підписом
 * і видалення свого рилса. Лайки — toggle, як у постів (+XP автору).
 */
data class ReelsUiState(
    val authorized: Boolean = false,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    /** Лента рілсів (свіжі зверху). */
    val reels: List<dev.amps.app.data.remote.backend.dto.ReelDto> = emptyList(),
    val endReached: Boolean = false,
    val loadingMore: Boolean = false,
    /** Публікація нового рилса: відео з галереї + підпис. */
    val publishing: Boolean = false,
    val publishCaption: String = "",
    /** id рилсів, у яких зараз перемикається лайк. */
    val busyReelIds: Set<Int> = emptySet(),
    val myUserId: Int = 0,
    val notice: String? = null,
    val error: String? = null,
)

class ReelsViewModel(
    private val backendApi: BackendApi,
    private val backendSession: BackendSession,
) : ViewModel() {

    private val _state = MutableStateFlow(ReelsUiState())
    val state: StateFlow<ReelsUiState> = _state.asStateFlow()

    private var lastAuthorized: Boolean? = null

    init {
        viewModelScope.launch {
            backendSession.stored.collect { stored ->
                val authorized = stored != null
                val previous = lastAuthorized
                lastAuthorized = authorized
                _state.value = _state.value.copy(authorized = authorized, myUserId = stored?.userId ?: 0)
                if (previous == null || (previous == false && authorized)) load()
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val result = backendSession.authedCall { header -> backendApi.getReels(header) }
            val reels = (result as? ApiResult.Success)?.data as? ReelsResponse
            _state.value = _state.value.copy(
                loading = false,
                reels = reels?.reels ?: emptyList(),
                endReached = (reels?.reels?.size ?: 0) < 20,
            )
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(refreshing = true)
            load()
            _state.value = _state.value.copy(refreshing = false)
        }
    }

    /** Наступна сторінка рілсів (offset = кількість завантажених). */
    fun loadMore() {
        val s = _state.value
        if (s.loadingMore || s.endReached || s.reels.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingMore = true)
            val result = backendSession.authedCall { header ->
                backendApi.getReels(header, limit = 10, offset = s.reels.size.toLong())
            }
            val page = (result as? ApiResult.Success)?.data as? ReelsResponse
            _state.value = if (page != null && page.reels.isNotEmpty()) {
                _state.value.copy(reels = _state.value.reels + page.reels, loadingMore = false)
            } else {
                _state.value.copy(loadingMore = false, endReached = true)
            }
        }
    }

    /** Лайк рилса — toggle: повторне натискання знімає. */
    fun toggleLike(reelId: Int) {
        if (_state.value.busyReelIds.contains(reelId)) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busyReelIds = _state.value.busyReelIds + reelId)
            val result = backendSession.authedCall { header -> backendApi.toggleReelLike(header, reelId) }
            when (result) {
                is ApiResult.Success -> {
                    val like = result.data as ReelLikeResponse
                    _state.value = _state.value.copy(
                        reels = _state.value.reels.map {
                            if (it.id == reelId) {
                                it.copy(likeCount = like.likeCount, likedByMe = like.liked)
                            } else {
                                it
                            }
                        },
                        busyReelIds = _state.value.busyReelIds - reelId,
                    )
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(error = result.error, busyReelIds = _state.value.busyReelIds - reelId)
                is ApiResult.NetworkError -> _state.value = _state.value.copy(error = result.message, busyReelIds = _state.value.busyReelIds - reelId)
            }
        }
    }

    /** Видалити свій рилс. */
    fun deleteReel(reelId: Int) {
        viewModelScope.launch {
            val result = backendSession.authedCall { header -> backendApi.deleteReel(header, reelId) }
            when (result) {
                is ApiResult.Success -> {
                    _state.value = _state.value.copy(notice = dev.amps.app.core.Loc.translate("Рилс видалено"))
                    load()
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(error = result.error)
                is ApiResult.NetworkError -> _state.value = _state.value.copy(error = result.message)
            }
        }
    }

    fun setPublishCaption(v: String) { _state.value = _state.value.copy(publishCaption = v.take(500)) }

    /** Публікація рилса: відео mp4/webm/mov ≤50 МБ з галереї + підпис. */
    fun publish(videoBytes: ByteArray, caption: String, onDone: (Boolean) -> Unit) {
        val s = _state.value
        if (s.publishing) return
        viewModelScope.launch {
            _state.value = _state.value.copy(publishing = true, error = null)
            val result = backendSession.authedCall { header ->
                backendApi.createReel(header, videoBytes, caption.trim())
            }
            when (result) {
                is ApiResult.Success -> {
                    _state.value = _state.value.copy(publishing = false, publishCaption = "", notice = Loc.translate("Рилс опубліковано 🎬 (+20 XP)"))
                    onDone(true)
                    load()
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(publishing = false, error = result.error)
                is ApiResult.NetworkError -> _state.value = _state.value.copy(publishing = false, error = result.message)
            }
        }
    }

    fun consumeError() { _state.value = _state.value.copy(error = null) }
    fun consumeNotice() { _state.value = _state.value.copy(notice = null) }

    class Factory(
        private val backendApi: BackendApi,
        private val backendSession: BackendSession,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ReelsViewModel(backendApi, backendSession) as T
    }
}
