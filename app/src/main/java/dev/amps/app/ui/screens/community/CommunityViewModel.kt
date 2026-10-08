package dev.amps.app.ui.screens.community

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.amps.app.data.remote.backend.ApiResult
import dev.amps.app.data.remote.backend.BackendApi
import dev.amps.app.data.remote.backend.BackendSession
import dev.amps.app.data.remote.backend.dto.FeedResponse
import dev.amps.app.data.remote.backend.dto.LikeToggleResponse
import dev.amps.app.data.remote.backend.dto.RepostResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 1.1.2: стан Спільноти — стрічка постів, складання, лайки, репости.
 *
 * Токен не зберігається тут: кожен виклик іде через [BackendSession.authedCall],
 * який сам підставляє access-токен і оновлює його при 401.
 */
data class CommunityUiState(
    val authorized: Boolean = false,
    val loading: Boolean = false,
    val posting: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val items: List<dev.amps.app.data.remote.backend.dto.FeedItemDto> = emptyList(),
    val composeText: String = "",
    val attachedPhotoBytes: ByteArray? = null,
    val attachedVideoBytes: ByteArray? = null,
    /** Пости, у яких зараз перемикається лайк або репост. */
    val busyPostIds: Set<Int> = emptySet(),
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
) {
    val canPublish: Boolean
        get() = !posting &&
            (composeText.isNotBlank() || attachedPhotoBytes != null || attachedVideoBytes != null)
}

class CommunityViewModel(
    private val backendApi: BackendApi,
    private val backendSession: BackendSession,
) : ViewModel() {

    private val _state = MutableStateFlow(CommunityUiState())
    val state: StateFlow<CommunityUiState> = _state.asStateFlow()

    /** 1.1.3: попередній стан входу — щоб побачити перехід «гість → акаунт». */
    private var lastAuthorized: Boolean? = null

    init {
        viewModelScope.launch {
            backendSession.stored.collect { stored ->
                val authorized = stored != null
                val previous = lastAuthorized
                lastAuthorized = authorized
                _state.value = _state.value.copy(authorized = authorized)
                when {
                    // Перше завантаження — публічне або з токеном.
                    previous == null && _state.value.items.isEmpty() -> load()
                    // Увійшли після гостевого читання — перезавантажуємо,
                    // щоб сервер повернув «лайкнуто мною»/«репостнув мною».
                    previous == false && authorized -> load()
                }
            }
        }
    }

    fun setComposeText(text: String) {
        _state.value = _state.value.copy(composeText = text.take(1000))
    }

    fun attachPhoto(bytes: ByteArray) {
        _state.value = _state.value.copy(attachedPhotoBytes = bytes, attachedVideoBytes = null)
    }

    fun attachVideo(bytes: ByteArray) {
        _state.value = _state.value.copy(attachedVideoBytes = bytes, attachedPhotoBytes = null)
    }

    fun clearAttachments() {
        _state.value = _state.value.copy(attachedPhotoBytes = null, attachedVideoBytes = null)
    }

    fun clearMessages() {
        _state.value = _state.value.copy(error = null, notice = null)
    }

    /**
     * Завантаження стрічки: акаунт — з токеном (сервер повертає і його
     * лайки), гість — публічний запит із чистими лічильниками.
     */
    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val result = if (_state.value.authorized) {
                backendSession.authedCall { header -> backendApi.getFeed(header) }
            } else {
                backendApi.getFeedPublic()
            }
            when (result) {
                is ApiResult.Success -> {
                    val feed = result.data as FeedResponse
                    _state.value = _state.value.copy(
                        loading = false,
                        items = feed.items,
                        endReached = feed.items.isEmpty(),
                    )
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(loading = false, error = result.error)
                is ApiResult.NetworkError -> _state.value = _state.value.copy(loading = false, error = result.message)
            }
        }
    }

    /** Наступна сторінка стрічки: зсув = кількість уже завантажених рядків. */
    fun loadMore() {
        val current = _state.value
        if (current.loadingMore || current.endReached || current.items.isEmpty()) return
        viewModelScope.launch {
            _state.value = current.copy(loadingMore = true)
            val result = if (current.authorized) {
                backendSession.authedCall { header ->
                    backendApi.getFeed(header, limit = 20, offset = current.items.size.toLong())
                }
            } else {
                backendApi.getFeedPublic(limit = 20, offset = current.items.size.toLong())
            }
            val feed = (result as? ApiResult.Success)?.data as? FeedResponse
            _state.value = if (feed != null && feed.items.isNotEmpty()) {
                _state.value.copy(loadingMore = false, items = _state.value.items + feed.items)
            } else {
                _state.value.copy(loadingMore = false, endReached = true)
            }
        }
    }

    /** Публікація: текст (до 1000 символів) і щонайбільше одне вкладення. */
    fun publish() {
        val current = _state.value
        if (!current.canPublish) return
        viewModelScope.launch {
            _state.value = current.copy(posting = true, error = null)
            val result = backendSession.authedCall { header ->
                backendApi.createPost(
                    header,
                    text = current.composeText.trim(),
                    photo = current.attachedPhotoBytes,
                    video = current.attachedVideoBytes,
                )
            }
            when (result) {
                is ApiResult.Success -> {
                    _state.value = _state.value.copy(
                        posting = false,
                        composeText = "",
                        attachedPhotoBytes = null,
                        attachedVideoBytes = null,
                        notice = "Опубліковано",
                    )
                    load()
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(posting = false, error = result.error)
                is ApiResult.NetworkError -> _state.value = _state.value.copy(posting = false, error = result.message)
            }
        }
    }

    fun toggleLike(postId: Int) {
        val current = _state.value
        if (postId in current.busyPostIds) return
        _state.value = current.copy(busyPostIds = current.busyPostIds + postId)
        viewModelScope.launch {
            val result = backendSession.authedCall { header -> backendApi.toggleLike(header, postId) }
            val response = (result as? ApiResult.Success)?.data as? LikeToggleResponse
            _state.value = if (response != null) {
                _state.value.copy(
                    items = updateItem(_state.value.items, postId) { post ->
                        post.copy(likedByMe = response.liked, likeCount = response.likeCount)
                    },
                    busyPostIds = _state.value.busyPostIds - postId,
                )
            } else {
                _state.value.copy(busyPostIds = _state.value.busyPostIds - postId)
            }
        }
    }

    fun toggleRepost(postId: Int) {
        val current = _state.value
        if (postId in current.busyPostIds) return
        _state.value = current.copy(busyPostIds = current.busyPostIds + postId)
        viewModelScope.launch {
            val result = backendSession.authedCall { header -> backendApi.toggleRepost(header, postId) }
            val response = (result as? ApiResult.Success)?.data as? RepostResponse
            _state.value = if (response != null) {
                _state.value.copy(
                    items = updateItem(_state.value.items, postId) { post ->
                        post.copy(repostedByMe = response.reposted, repostCount = response.repostCount)
                    },
                    busyPostIds = _state.value.busyPostIds - postId,
                )
            } else {
                _state.value.copy(busyPostIds = _state.value.busyPostIds - postId)
            }
        }
    }

    class Factory(
        private val backendApi: BackendApi,
        private val backendSession: BackendSession,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CommunityViewModel(backendApi, backendSession) as T
    }

    /** Оновлює пост у стрічці, включно з рядками репостів того ж поста. */
    private fun updateItem(
        items: List<dev.amps.app.data.remote.backend.dto.FeedItemDto>,
        postId: Int,
        transform: (dev.amps.app.data.remote.backend.dto.PostDto) -> dev.amps.app.data.remote.backend.dto.PostDto,
    ): List<dev.amps.app.data.remote.backend.dto.FeedItemDto> =
        items.map { item ->
            if (item.post.id == postId) item.copy(post = transform(item.post)) else item
        }
}
