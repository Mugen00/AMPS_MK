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
    /** 1.4.0: свайп-перезагрузка стрічки. */
    val refreshing: Boolean = false,
    val posting: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val items: List<dev.amps.app.data.remote.backend.dto.FeedItemDto> = emptyList(),
    val composeText: String = "",
    val attachedPhotoBytes: ByteArray? = null,
    val attachedVideoBytes: ByteArray? = null,
    /** 1.5.1: формат показу фото в стрічці — як в Instagram
     *  ("original"|"1:1"|"4:5"|"16:9"). Діє, доки прикріплене фото. */
    val attachAspect: String = "original",
    // ===== 1.5.3: режим множинного постинга фото =====
    /** Режим вкладень: "single" (одне фото) | "carousel" (1–30 фото) | "collage" (3–4). */
    val attachMode: String = "single",
    /** Фото каруселі (1–30 штук). */
    val attachedPhotos: List<ByteArray> = emptyList(),
    /** Готовий колаж (JPEG з 3–4 фото) — публікується як одне фото. */
    val collageBytes: ByteArray? = null,
    /** Колаж зараз збирається. */
    val collageBuilding: Boolean = false,
    /** 1.6.3: прикріплене відео — «кружок» (до 60 с), показується колом. */
    val roundVideo: Boolean = false,
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
    // ===== Опитування (1.2.3) =====
    /** Складальник: чи додано опитування до нового поста. */
    val pollEnabled: Boolean = false,
    /** Чернетка варіантів опитування (2–4 поля). */
    val pollDraftOptions: List<String> = listOf("", ""),
    /** pollId → голос зараз надсилається. */
    val busyPollIds: Set<Int> = emptySet(),
) {
    val canPublish: Boolean
        get() = !posting && (
            composeText.isNotBlank() ||
                attachedPhotoBytes != null ||
                attachedVideoBytes != null ||
                attachedPhotos.isNotEmpty() ||
                (attachMode == "collage" && collageBytes != null) ||
                (pollEnabled && pollDraftOptions.count { it.isNotBlank() } >= 2)
            )

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
        _state.value = _state.value.copy(
            attachedPhotoBytes = bytes,
            attachedVideoBytes = null,
            attachAspect = "original",
            roundVideo = false,
        )
    }

    fun attachVideo(bytes: ByteArray) {
        _state.value = _state.value.copy(
            attachedVideoBytes = bytes,
            attachedPhotoBytes = null,
            attachAspect = "original",
            roundVideo = false,
        )
    }

    /** 1.6.3: прикріпити записаний з камери «кружок» (до 60 с). */
    fun attachRoundVideo(bytes: ByteArray) {
        _state.value = _state.value.copy(
            attachedVideoBytes = bytes,
            attachedPhotoBytes = null,
            attachedPhotos = emptyList(),
            attachMode = "single",
            attachAspect = "original",
            roundVideo = true,
        )
    }

    /** 1.5.1: вибір формату показу фото (Instagram-стиль) у композері. */
    fun setAttachAspect(aspect: String) {
        _state.value = _state.value.copy(attachAspect = aspect)
    }

    /**
     * 1.5.3: перемикання режиму постинга фото ("single"|"carousel"|"collage").
     * Вкладення іншого режиму очищаються — без сюрпризів.
     */
    fun setAttachMode(mode: String) {
        val cur = _state.value
        if (cur.attachMode == mode) return
        _state.value = cur.copy(
            attachMode = mode,
            attachedPhotoBytes = null,
            attachedVideoBytes = null,
            attachedPhotos = emptyList(),
            collageBytes = null,
            collageBuilding = false,
            attachAspect = "original",
            roundVideo = false,
        )
    }

    /**
     * 1.5.3: вкладення кількох фото. Карусель — 1–30 фото; колаж —
     * рівно 3–4, з яких одразу збирається готовий квадратний JPEG.
     */
    fun attachPhotos(bytesList: List<ByteArray>) {
        if (bytesList.isEmpty()) return
        val mode = _state.value.attachMode
        when (mode) {
            "collage" -> {
                if (bytesList.size !in 3..4) {
                    _state.value = _state.value.copy(
                        error = dev.amps.app.core.Loc.translate("Для колажу потрібно 3–4 фото"),
                    )
                    return
                }
                _state.value = _state.value.copy(
                    attachedPhotos = bytesList, collageBytes = null, collageBuilding = true,
                )
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                    val collage = dev.amps.app.core.CollageBuilder.buildCollage(bytesList)
                    _state.value = _state.value.copy(
                        collageBytes = collage,
                        collageBuilding = false,
                        error = if (collage == null) {
                            dev.amps.app.core.Loc.translate("Не вдалося зібрати колаж")
                        } else {
                            null
                        },
                    )
                }
            }
            // Карусель: до 30 фото.
            else -> _state.value = _state.value.copy(attachedPhotos = bytesList.take(30))
        }
    }

    /** 1.5.3: прибрати одне фото з каруселі в композері. */
    fun removeCarouselPhoto(index: Int) {
        val list = _state.value.attachedPhotos.toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            _state.value = _state.value.copy(attachedPhotos = list)
        }
    }

    fun clearAttachments() {
        _state.value = _state.value.copy(
            attachedPhotoBytes = null,
            attachedVideoBytes = null,
            attachedPhotos = emptyList(),
            collageBytes = null,
            collageBuilding = false,
            attachAspect = "original",
        )
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
    fun load() = viewModelScope.launch { loadInternal() }

    private suspend fun loadInternal() {
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

    /** 1.2.2: перемикання стрічки «Всі ⇄ Підписки». */
    fun setFeedScope(scope: String) {
        if (_state.value.feedScope == scope) return
        _state.value = _state.value.copy(feedScope = scope)
        load()
    }

    /** 1.4.0: свайп-перезагрузка стрічки — свіжа сторінка + сторіс. */
    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(refreshing = true)
            loadInternal()
            refreshStories()
            _state.value = _state.value.copy(refreshing = false)
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

    /** Публікація: текст (до 1000 символів), вкладення і/або опитування. */
    fun publish() {
        val current = _state.value
        if (!current.canPublish) return
        // 1.5.3: колаж надсилається лише коли вже зібраний.
        if (current.attachMode == "collage" && current.collageBytes == null) return
        viewModelScope.launch {
            _state.value = current.copy(posting = true, error = null)
            val pollOptions = if (current.pollEnabled) {
                current.pollDraftOptions.map { it.trim() }.filter { it.isNotBlank() }
            } else {
                emptyList()
            }
            // 1.5.3: список фото залежить від режиму постинга.
            val photos = when (current.attachMode) {
                "carousel" -> current.attachedPhotos
                "collage" -> listOfNotNull(current.collageBytes)
                else -> listOfNotNull(current.attachedPhotoBytes)
            }
            // Колаж завжди квадратний — формат показу примусово 1:1.
            val imageAspect = when (current.attachMode) {
                "collage" -> "1:1"
                else -> current.attachAspect
            }.takeIf { photos.isNotEmpty() }
            val result = backendSession.authedCall { header ->
                backendApi.createPost(
                    header,
                    text = current.composeText.trim(),
                    photos = photos,
                    video = current.attachedVideoBytes.takeIf { current.attachMode == "single" },
                    imageAspect = imageAspect,
                    // 1.6.3: кружок — показується колом у стрічці.
                    roundVideo = current.roundVideo && current.attachMode == "single",
                )
            }
            val createdPost = (result as? ApiResult.Success)
                ?.data as? dev.amps.app.data.remote.backend.dto.PostCreatedResponse
            // 1.2.3: опитування прикріплюється одразу після поста.
            val pollResult = if (createdPost != null && current.pollEnabled &&
                current.pollDraftOptions.count { it.isNotBlank() } >= 2
            ) {
                backendSession.authedCall { header ->
                    backendApi.createPoll(
                        header,
                        postId = createdPost.postId,
                        question = "",
                        options = current.pollDraftOptions.map { it.trim() }.filter { it.isNotBlank() },
                    )
                }
            } else {
                null
            }
            when (result) {
                is ApiResult.Success -> {
                    val pollFailed = pollResult is ApiResult.Failure || pollResult is ApiResult.NetworkError
                    _state.value = _state.value.copy(
                        posting = false,
                        composeText = "",
                        attachedPhotoBytes = null,
                        attachedVideoBytes = null,
                        attachedPhotos = emptyList(),
                        collageBytes = null,
                        collageBuilding = false,
                        attachAspect = "original",
                        roundVideo = false,
                        pollEnabled = false,
                        pollDraftOptions = listOf("", ""),
                        notice = if (current.pollEnabled && pollFailed) {
                            "Опубліковано, але опитування не прикріпилось"
                        } else {
                            "Опубліковано"
                        },
                    )
                    load()
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(posting = false, error = result.error)
                is ApiResult.NetworkError -> _state.value = _state.value.copy(posting = false, error = result.message)
            }
        }
    }

    // ===== Опитування (1.2.3) =====

    /** Увімкнути/вимкнути складальник опитування в діалозі публікації. */
    fun togglePollComposer() {
        _state.value = _state.value.copy(pollEnabled = !_state.value.pollEnabled)
    }

    fun setPollOption(index: Int, text: String) {
        val opts = _state.value.pollDraftOptions.toMutableList()
        if (index !in opts.indices) return
        opts[index] = text.take(100)
        _state.value = _state.value.copy(pollDraftOptions = opts)
    }

    /** Додати поле варіанта (щонайбільше 4). */
    fun addPollOption() {
        val current = _state.value
        if (current.pollDraftOptions.size >= 4) return
        _state.value = current.copy(pollDraftOptions = current.pollDraftOptions + "")
    }

    /** Прибрати поле варіанта (мінімум лишається 2). */
    fun removePollOption(index: Int) {
        val current = _state.value
        if (index !in current.pollDraftOptions.indices || current.pollDraftOptions.size <= 2) return
        val opts = current.pollDraftOptions.toMutableList()
        opts.removeAt(index)
        _state.value = current.copy(pollDraftOptions = opts)
    }

    /** Голосувати: повторний тап за той самий варіант знімає голос. */
    fun votePoll(postId: Int, pollId: Int, optionId: Int) {
        val current = _state.value
        if (!current.authorized) {
            _state.value = current.copy(notice = "Увійдіть, щоб голосувати")
            return
        }
        if (pollId in current.busyPollIds) return
        _state.value = current.copy(busyPollIds = current.busyPollIds + pollId)
        viewModelScope.launch {
            when (val result = backendSession.authedCall { header ->
                backendApi.votePoll(header, pollId, optionId)
            }) {
                is ApiResult.Success -> {
                    val poll = (result.data as dev.amps.app.data.remote.backend.dto.PollResponse).poll
                    _state.value = _state.value.copy(
                        busyPollIds = _state.value.busyPollIds - pollId,
                        items = _state.value.items.map { item ->
                            if (item.post.id == postId && item.post.poll?.pollId == pollId) {
                                item.copy(post = item.post.copy(poll = poll))
                            } else {
                                item
                            }
                        },
                    )
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(
                    error = result.error,
                    busyPollIds = _state.value.busyPollIds - pollId,
                )
                is ApiResult.NetworkError -> _state.value = _state.value.copy(
                    busyPollIds = _state.value.busyPollIds - pollId, error = result.message,
                )
            }
        }
    }

    // ===== Коментарі (1.2.2) =====
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

    // ===== 1.6.1: редагування і видалення власних постів =====

    /**
     * Редагувати текст свого поста. Сервер повертає оновлений пост —
     * підставляємо його в стрічку; репости й опитування лишаються.
     */
    fun editPost(postId: Int, text: String) {
        viewModelScope.launch {
            val result = backendSession.authedCall { header -> backendApi.editPost(header, postId, text) }
            val edited = (result as? ApiResult.Success)?.data as? dev.amps.app.data.remote.backend.dto.PostEditedResponse
            when {
                edited != null -> _state.value = _state.value.copy(
                    items = updateItem(_state.value.items, postId) { edited.post },
                    notice = dev.amps.app.core.Loc.translate("Пост оновлено ✏️"),
                )
                result is ApiResult.Failure -> _state.value = _state.value.copy(error = result.error)
                result is ApiResult.NetworkError -> _state.value = _state.value.copy(error = result.message)
                else -> _state.value = _state.value.copy(error = "Не вдалося зберегти зміни")
            }
        }
    }

    /** Видалити свій пост зі стрічки. */
    fun deletePost(postId: Int) {
        viewModelScope.launch {
            val result = backendSession.authedCall { header -> backendApi.deletePost(header, postId) }
            when (result) {
                is ApiResult.Success -> _state.value = _state.value.copy(
                    items = _state.value.items.filterNot { it.post.id == postId },
                    notice = dev.amps.app.core.Loc.translate("Пост видалено"),
                )
                is ApiResult.Failure -> _state.value = _state.value.copy(error = result.error)
                is ApiResult.NetworkError -> _state.value = _state.value.copy(error = result.message)
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
