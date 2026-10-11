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
    // ===== Сторіс (1.2.0) =====
    /** Живі сторіс усіх авторів, свіжі зверху. */
    val stories: List<dev.amps.app.data.remote.backend.dto.StoryDto> = emptyList(),
    val storiesLoading: Boolean = false,
    /** Сторіс, яку зараз завантажуємо на сервер. */
    val storyUploading: Boolean = false,
    /** Відкритий переглядач: id сторіс, яку показуємо (null — закрито). */
    val openStoryId: Int? = null,
    /** 1.2.0 (патч 24): id поточного акаунта — щоб кружечок «Моя»
     *  відкривав СВОЇ сторіс, як в Instagram (0 — гість). */
    val currentUserId: Int = 0,
    // ===== Коментарі (1.2.2) =====
    /** postId → коментарі (свіжі зверху, як віддає сервер). */
    val comments: Map<Int, List<dev.amps.app.data.remote.backend.dto.CommentDto>> = emptyMap(),
    /** postId → чернетка поля «написати коментар». */
    val commentDrafts: Map<Int, String> = emptyMap(),
    /** postId → коментар зараз завантажується/надсилається. */
    val busyCommentPosts: Set<Int> = emptySet(),
    // ===== Підписки (1.2.2) =====
    /** userId → підписаний чи ні (стан після toggle). */
    val followStates: Map<Int, Boolean> = emptyMap(),
    /** Чіп стрічки: "all" | "following". */
    val feedScope: String = "all",
) {
    val canPublish: Boolean
        get() = !posting &&
            (composeText.isNotBlank() || attachedPhotoBytes != null || attachedVideoBytes != null)

    /** 1.2.0 (патч 24): мої живі сторіс — для розумного кружечка «Моя». */
    val myStories: List<dev.amps.app.data.remote.backend.dto.StoryDto>
        get() = if (currentUserId > 0) {
            stories.filter { it.author.userId == currentUserId }
        } else {
            emptyList()
        }
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
                _state.value = _state.value.copy(
                    authorized = authorized,
                    // 1.2.0 (патч 24): чию аватарку показувати в «Моя».
                    currentUserId = stored?.userId ?: 0,
                )
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
    /**
     * Завантаження стрічки: акаунт — з токеном (сервер повертає і його
     * лайки), гість — публічний запит із чистими лічильниками.
     * 1.2.2: feedScope = "following" — тільки підписки (потрібен вхід).
     */
    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val scopeParam = _state.value.feedScope
            val result = if (_state.value.authorized) {
                backendSession.authedCall { header ->
                    backendApi.getFeed(header, scope = scopeParam)
                }
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

    /** 1.2.2: перемикання стрічки «Всі ⇄ Підписки». */
    fun setFeedScope(scope: String) {
        if (_state.value.feedScope == scope) return
        _state.value = _state.value.copy(feedScope = scope)
        load()
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

    // ===== Коментарі (1.2.2) =====

    /** Відкрити коментарі поста: читає список із сервера. */
    fun loadComments(postId: Int) {
        if (postId in _state.value.busyCommentPosts) return
        _state.value = _state.value.copy(busyCommentPosts = _state.value.busyCommentPosts + postId)
        viewModelScope.launch {
            when (val result = backendApi.getComments(postId)) {
                is ApiResult.Success -> {
                    val comments = (result.data as dev.amps.app.data.remote.backend.dto.CommentsResponse).comments
                    _state.value = _state.value.copy(
                        comments = _state.value.comments + (postId to comments),
                        busyCommentPosts = _state.value.busyCommentPosts - postId,
                    )
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(
                    error = result.error,
                    busyCommentPosts = _state.value.busyCommentPosts - postId,
                )
                is ApiResult.NetworkError -> _state.value = _state.value.copy(
                    busyCommentPosts = _state.value.busyCommentPosts - postId,
                    error = result.message,
                )
            }
        }
    }

    fun setCommentDraft(postId: Int, text: String) {
        _state.value = _state.value.copy(
            commentDrafts = _state.value.commentDrafts + (postId to text.take(1000)),
        )
    }

    /** Надіслати чернетку коментаря; після успіху — перечитати список. */
    fun addComment(postId: Int) {
        val current = _state.value
        if (!current.authorized) {
            _state.value = current.copy(notice = "Увійдіть, щоб коментувати")
            return
        }
        val text = current.commentDrafts[postId]?.trim().orEmpty()
        if (text.isBlank()) return
        if (postId in current.busyCommentPosts) return
        _state.value = current.copy(busyCommentPosts = current.busyCommentPosts + postId)
        viewModelScope.launch {
            when (val result = backendSession.authedCall { header ->
                backendApi.addComment(header, postId, text)
            }) {
                is ApiResult.Success -> {
                    _state.value = _state.value.copy(
                        busyCommentPosts = _state.value.busyCommentPosts - postId,
                        commentDrafts = _state.value.commentDrafts + (postId to ""),
                    )
                    loadComments(postId)
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(
                    busyCommentPosts = _state.value.busyCommentPosts - postId,
                    error = result.error,
                )
                is ApiResult.NetworkError -> _state.value = _state.value.copy(
                    busyCommentPosts = _state.value.busyCommentPosts - postId,
                    error = result.message,
                )
            }
        }
    }

    /** Видалити свій коментар і перечитати список поста. */
    fun deleteComment(postId: Int, commentId: Int) {
        viewModelScope.launch {
            backendSession.authedCall { header ->
                backendApi.deleteComment(authHeader = header, commentId = commentId)
            }
            loadComments(postId)
        }
    }

    /** Підписатись/відписатись на автора поста. */
    fun toggleFollow(userId: Int) {
        if (_state.value.currentUserId == userId) return
        viewModelScope.launch {
            when (val result = backendSession.authedCall { header ->
                backendApi.toggleFollow(header, userId)
            }) {
                is ApiResult.Success -> {
                    val response = result.data as dev.amps.app.data.remote.backend.dto.FollowToggleResponse
                    _state.value = _state.value.copy(
                        followStates = _state.value.followStates + (userId to response.following),
                        notice = if (response.following) "Підписано" else "Підписку знято",
                    )
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(error = result.error)
                is ApiResult.NetworkError ->
                    _state.value = _state.value.copy(error = result.message)
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

    // ===== Сторіс (1.2.0) =====

    /** Живі сторіс; публічно — як і стрічка. Викликається разом із load(). */
    fun loadStories() {
        viewModelScope.launch { refreshStories() }
    }

    /** 1.2.0 (патч 25): suspend-ядро, щоб чекати список після публікації. */
    private suspend fun refreshStories() {
        _state.value = _state.value.copy(storiesLoading = true)
        when (val result = backendApi.getStories()) {
            is ApiResult.Success -> _state.value = _state.value.copy(
                storiesLoading = false,
                stories = (result.data as dev.amps.app.data.remote.backend.dto.StoriesResponse).stories,
            )
            is ApiResult.Failure -> _state.value = _state.value.copy(
                storiesLoading = false,
                // Сторіс — необов'язковий блок: помилка не затьмарює стрічку.
                notice = "Сторіс не завантажилися: ${result.error}",
            )
            is ApiResult.NetworkError -> _state.value = _state.value.copy(
                storiesLoading = false,
                notice = "Сторіс не завантажилися: ${result.message}",
            )
        }
    }

    /**
     * Публікація сторіс: фото або відео з камери/галереї. Живе 24 години.
     *
     * 1.2.0 (патч 25): після успіху список оновлюється ДО відкриття
     * переглядача — і щойно опублікована сторіс ОДРАЗУ показується на весь
     * екран, як в Instagram (фіксує «зняв — нічого не з'явилось»).
     */
    fun publishStory(kind: String, bytes: ByteArray) {
        if (_state.value.storyUploading) return
        viewModelScope.launch {
            _state.value = _state.value.copy(storyUploading = true, error = null, notice = null)
            val result = backendSession.authedCall { header ->
                backendApi.createStory(header, kind, bytes)
            }
            when (result) {
                is ApiResult.Success -> {
                    val created = result.data as? dev.amps.app.data.remote.backend.dto.StoryDto
                    refreshStories()
                    _state.value = _state.value.copy(
                        storyUploading = false,
                        notice = "Сторіс опубліковано — зникне через 24 години",
                        // Автопоказ опублікованого: список уже свіжий.
                        openStoryId = created?.id?.takeIf { id ->
                            _state.value.stories.any { it.id == id }
                        },
                    )
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(storyUploading = false, error = result.error)
                is ApiResult.NetworkError -> _state.value = _state.value.copy(storyUploading = false, error = result.message)
            }
        }
    }

    fun deleteStory(storyId: Int) {
        viewModelScope.launch {
            backendSession.authedCall { header -> backendApi.deleteStory(header, storyId) }
            loadStories()
        }
    }

    fun openStory(storyId: Int?) {
        _state.value = _state.value.copy(openStoryId = storyId)
    }

    /** 1.2.0: сторіс не вдалося навіть підготувати (камера/розмір). */
    fun publishStoryFailed(message: String) {
        _state.value = _state.value.copy(storyUploading = false, error = message)
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
