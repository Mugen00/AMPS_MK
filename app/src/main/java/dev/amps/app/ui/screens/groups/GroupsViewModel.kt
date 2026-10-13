package dev.amps.app.ui.screens.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.amps.app.core.Loc
import dev.amps.app.data.remote.backend.ApiResult
import dev.amps.app.data.remote.backend.BackendApi
import dev.amps.app.data.remote.backend.BackendSession
import dev.amps.app.data.remote.backend.dto.ChatMessageDto
import dev.amps.app.data.remote.backend.dto.FeedResponse
import dev.amps.app.data.remote.backend.dto.GroupDetailDto
import dev.amps.app.data.remote.backend.dto.GroupDto
import dev.amps.app.data.remote.backend.dto.GroupJoinRequestDto
import dev.amps.app.data.remote.backend.dto.GroupListResponse
import dev.amps.app.data.remote.backend.dto.GroupMemberDto
import dev.amps.app.data.remote.backend.dto.GroupMembersResponse
import dev.amps.app.data.remote.backend.dto.GroupRequestsResponse
import dev.amps.app.data.remote.backend.dto.PostCreatedResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 1.6.0: стан екрана Групи — каталог і мої групи, створення, деталі
 * (вступ/вихід, учасники з ролями, запити на вступ у приватні групи),
 * лента постів групи, публікація в групу і груповий чат.
 * Токен — через [BackendSession.authedCall], як у Спільноті.
 */
data class GroupsUiState(
    val authorized: Boolean = false,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    /** "catalog" (всі групи + пошук) | "my" (мої групи). */
    val tab: String = "catalog",
    val searchQuery: String = "",
    /** Каталог груп (може бути відфільтрований пошуком). */
    val groups: List<GroupDto> = emptyList(),
    /** Мої групи (якщо я учасник). */
    val myGroups: List<GroupDto> = emptyList(),
    /** Відкрита група: деталі + мій статус. */
    val selected: GroupDetailDto? = null,
    /** Учасники відкритої групи. */
    val members: List<GroupMemberDto> = emptyList(),
    /** Запити на вступ (власник/модератор приватної групи). */
    val requests: List<GroupJoinRequestDto> = emptyList(),
    /** Лента постів відкритої групи. */
    val posts: List<dev.amps.app.data.remote.backend.dto.FeedItemDto> = emptyList(),
    /** Вкладка в деталях: "posts" | "members" | "requests" | "chat". */
    val detailTab: String = "posts",
    /** Груповий чат відкритої групи. */
    val chat: List<ChatMessageDto> = emptyList(),
    val chatDraft: String = "",
    /** Чернетка створення групи. */
    val createName: String = "",
    val createDescription: String = "",
    val createPrivate: Boolean = false,
    val creating: Boolean = false,
    val posting: Boolean = false,
    val composingText: String = "",
    /** Байти фото, прикріпленого до поста групи. */
    val attachedPhotoBytes: ByteArray? = null,
    /** 1.6.4: прикріплене відео — «кружок» (до 60 с), показується колом. */
    val roundVideo: Boolean = false,
    /** id учасника/запита, для якого зараз йде дія (kick/role/approve). */
    val busyIds: Set<String> = emptySet(),
    val sendingChat: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
    /** 1.6.2: Authorization для приватних вкладень чатів. */
    val authHeader: String? = null,
    /** 1.6.4: хтось друкує у груповому чаті. */
    val peerTyping: Boolean = false,
) {
    /** Мій id (щоб не кікнути себе і ховати свої кнопки). */
    val myRole: String? get() = selected?.group?.myRole
    val isOwner: Boolean get() = myRole == "owner"
    val isModOrOwner: Boolean get() = myRole == "owner" || myRole == "mod"
    val canPublish: Boolean
        get() = !posting && selected != null &&
            (composingText.isNotBlank() || attachedPhotoBytes != null || roundVideo)
}

class GroupsViewModel(
    private val backendApi: dev.amps.app.data.remote.backend.BackendApi,
    private val backendSession: BackendSession,
) : androidx.lifecycle.ViewModel() {

    private val _state = MutableStateFlow(GroupsUiState())
    val state: StateFlow<GroupsUiState> = _state.asStateFlow()

    private var lastAuthorized: Boolean? = null

    init {
        viewModelScope.launch {
            backendSession.stored.collect { stored ->
                val authorized = stored != null
                val previous = lastAuthorized
                lastAuthorized = authorized
                _state.value = _state.value.copy(
                    authorized = authorized,
                    authHeader = stored?.let { "Bearer ${stored.accessToken}" },
                )
                when {
                    previous == null && _state.value.groups.isEmpty() -> load()
                    previous == false && authorized -> load()
                }
            }
        }
    }

    fun load() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        if (_state.value.authorized) {
            val mine = backendSession.authedCall { header -> backendApi.getMyGroups(header) }
            val mineList = (mine as? ApiResult.Success)?.data as? GroupListResponse
            _state.value = _state.value.copy(myGroups = mineList?.groups ?: emptyList())
        }
        val result = backendSession.authedCall { header -> backendApi.getGroups(header, _state.value.searchQuery) }
        when (result) {
            is ApiResult.Success -> {
                val list = result.data as GroupListResponse
                _state.value = _state.value.copy(loading = false, groups = list.groups)
            }
            is ApiResult.Failure -> _state.value = _state.value.copy(loading = false, error = result.error)
            is ApiResult.NetworkError -> _state.value = _state.value.copy(loading = false, error = result.message)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(refreshing = true)
            load()
            _state.value = _state.value.copy(refreshing = false)
        }
    }

    /** Чіп «Каталог ⇄ Мої групи». */
    fun setTab(tab: String) {
        if (_state.value.tab == tab) return
        _state.value = _state.value.copy(tab = tab)
    }

    fun setSearch(q: String) {
        _state.value = _state.value.copy(searchQuery = q)
    }

    /** Пошук по каталогу (на сервері, за назвою). */
    fun submitSearch() {
        viewModelScope.launch {
            val result = backendSession.authedCall { header -> backendApi.getGroups(header, _state.value.searchQuery) }
            val list = (result as? ApiResult.Success)?.data as? GroupListResponse
            if (list != null) _state.value = _state.value.copy(groups = list.groups)
        }
    }

    // ===== Створення =====

    fun setCreateName(v: String) { _state.value = _state.value.copy(createName = v.take(50)) }
    fun setCreateDescription(v: String) { _state.value = _state.value.copy(createDescription = v.take(300)) }
    fun setCreatePrivate(v: Boolean) { _state.value = _state.value.copy(createPrivate = v) }

    /** Створити групу: я — власник і перший учасник (+30 XP). */
    fun create(onCreated: (Int) -> Unit) {
        val s = _state.value
        if (s.creating || s.createName.isBlank()) return
        viewModelScope.launch {
            _state.value = s.copy(creating = true, error = null)
            val result = backendSession.authedCall { header ->
                backendApi.createGroup(header, s.createName.trim(), s.createDescription.trim(), s.createPrivate)
            }
            when (result) {
                is ApiResult.Success -> {
                    val created = result.data as dev.amps.app.data.remote.backend.dto.GroupCreatedResponse
                    _state.value = _state.value.copy(creating = false, createName = "", createDescription = "", createPrivate = false)
                    load()
                    onCreated(created.groupId)
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(creating = false, error = result.error)
                is ApiResult.NetworkError -> _state.value = _state.value.copy(creating = false, error = result.message)
            }
        }
    }

    // ===== Деталі групи =====

    /** Відкрити групу: деталі + учасники + пости + (якщо учасник) чат. */
    fun open(groupId: Int) {
        viewModelScope.launch {
            val result = backendSession.authedCall { header -> backendApi.getGroup(header, groupId) }
            val detail = (result as? ApiResult.Success)?.data as? GroupDetailDto
            _state.value = _state.value.copy(selected = detail, detailTab = "posts", posts = emptyList(), chat = emptyList(), members = emptyList(), requests = emptyList())
            if (detail != null) {
                loadMembers(groupId)
                loadGroupPosts(groupId)
                if (detail.group.isMember) {
                    loadGroupChat(groupId)
                    markChatRead(groupId)
                }
            }
        }
    }

    fun close() { _state.value = _state.value.copy(selected = null, posts = emptyList(), chat = emptyList(), members = emptyList(), requests = emptyList()) }

    fun setDetailTab(tab: String) {
        _state.value = _state.value.copy(detailTab = tab)
        val s = _state.value
        val gid = s.selected?.group?.id ?: return
        if (tab == "requests" && s.isModOrOwner) loadRequests(gid)
        if (tab == "chat" && s.selected.group.isMember) { loadGroupChat(gid); markChatRead(gid) }
    }

    /** Вступ: публічна — одразу; приватна — висилається запит (код REQUEST_PENDING). */
    fun join() {
        val gid = _state.value.selected?.group?.id ?: return
        viewModelScope.launch {
            val result = backendSession.authedCall { header -> backendApi.joinGroup(header, gid) }
            when (result) {
                is ApiResult.Success -> {
                    _state.value = _state.value.copy(notice = Loc.translate("Ви вступили до групи 🎉"))
                    open(gid); load()
                }
                is ApiResult.Failure -> {
                    // Приватна група: запит висилано і чекає схвалення.
                    val msg = if (result.errorCode == "REQUEST_PENDING") {
                        Loc.translate("Запит надіслано — чекаємо схвалення власника ⏳")
                    } else {
                        result.error
                    }
                    _state.value = _state.value.copy(notice = msg)
                    open(gid)
                }
                is ApiResult.NetworkError -> _state.value = _state.value.copy(error = result.message)
            }
        }
    }

    fun leave() {
        val gid = _state.value.selected?.group?.id ?: return
        viewModelScope.launch {
            val result = backendSession.authedCall { header -> backendApi.leaveGroup(header, gid) }
            if (result is ApiResult.Success) {
                _state.value = _state.value.copy(notice = Loc.translate("Ви вийшли з групи"))
                load()
            } else {
                _state.value = _state.value.copy(error = (result as? ApiResult.Failure)?.error)
            }
        }
    }

    /** Видалити групу (тільки власник): підтвердження — у діалозі екрана. */
    fun deleteGroup() {
        val gid = _state.value.selected?.group?.id ?: return
        viewModelScope.launch {
            val result = backendSession.authedCall { header -> backendApi.deleteGroup(header, gid) }
            if (result is ApiResult.Success) {
                _state.value = _state.value.copy(selected = null, notice = Loc.translate("Групу видалено"))
                load()
            } else {
                _state.value = _state.value.copy(error = (result as? ApiResult.Failure)?.error)
            }
        }
    }

    fun loadMembers(groupId: Int) {
        viewModelScope.launch {
            val result = backendSession.authedCall { header -> backendApi.getGroupMembers(header, groupId) }
            val list = (result as? ApiResult.Success)?.data as? GroupMembersResponse
            if (list != null) _state.value = _state.value.copy(members = list.members)
        }
    }

    private fun loadRequests(groupId: Int) {
        viewModelScope.launch {
            val result = backendSession.authedCall { header -> backendApi.getJoinRequests(header, groupId) }
            val list = (result as? ApiResult.Success)?.data as? GroupRequestsResponse
            if (list != null) _state.value = _state.value.copy(requests = list.requests)
        }
    }

    /** Схвалити запит на вступ: юзер стає учасником (+5 XP йому). */
    fun approveRequest(requestId: Int) {
        val gid = _state.value.selected?.group?.id ?: return
        viewModelScope.launch {
            val result = backendSession.authedCall { header -> backendApi.approveJoinRequest(header, gid, requestId) }
            if (result is ApiResult.Success) {
                _state.value = _state.value.copy(notice = Loc.translate("Запит схвалено ✅"))
                loadRequests(gid); loadMembers(gid); open(gid)
            } else {
                _state.value = _state.value.copy(error = (result as? ApiResult.Failure)?.error)
            }
        }
    }

    fun rejectRequest(requestId: Int) {
        val gid = _state.value.selected?.group?.id ?: return
        viewModelScope.launch {
            val result = backendSession.authedCall { header -> backendApi.rejectJoinRequest(header, gid, requestId) }
            if (result is ApiResult.Success) {
                _state.value = _state.value.copy(notice = Loc.translate("Запит відхилено"))
                loadRequests(gid)
            } else {
                _state.value = _state.value.copy(error = (result as? ApiResult.Failure)?.error)
            }
        }
    }

    /** Виключити учасника (власник/модератор; власника виключити не можна). */
    fun kick(userId: Int) {
        val gid = _state.value.selected?.group?.id ?: return
        viewModelScope.launch {
            val result = backendSession.authedCall { header -> backendApi.kickMember(header, gid, userId) }
            if (result is ApiResult.Success) {
                _state.value = _state.value.copy(notice = Loc.translate("Учасника виключено"))
                loadMembers(gid)
            } else {
                _state.value = _state.value.copy(error = (result as? ApiResult.Failure)?.error)
            }
        }
    }

    /** Змінити роль: "mod" (модератор) або "member" (учасник). */
    fun setRole(userId: Int, role: String) {
        val gid = _state.value.selected?.group?.id ?: return
        viewModelScope.launch {
            val result = backendSession.authedCall { header -> backendApi.setGroupRole(header, gid, userId, role) }
            if (result is ApiResult.Success) {
                _state.value = _state.value.copy(notice = Loc.translate("Роль змінено"))
                loadMembers(gid)
            } else {
                _state.value = _state.value.copy(error = (result as? ApiResult.Failure)?.error)
            }
        }
    }

    // ===== Пости групи =====

    private fun loadGroupPosts(groupId: Int) {
        viewModelScope.launch {
            val result = backendSession.authedCall { header -> backendApi.getGroupPosts(header, groupId) }
            val feed = (result as? ApiResult.Success)?.data as? dev.amps.app.data.remote.backend.dto.FeedResponse
            if (feed != null) _state.value = _state.value.copy(posts = feed.items)
        }
    }

    fun setComposingText(v: String) { _state.value = _state.value.copy(composingText = v.take(1000)) }

    fun attachPhoto(bytes: ByteArray?) { _state.value = _state.value.copy(attachedPhotoBytes = bytes, roundVideo = false) }

    // ===== 1.6.4: кружок у постах групи =====

    /** Прикріпити записаний «кружок» (до 60 с) — показується колом. */
    fun attachRoundVideo(bytes: ByteArray) {
        _state.value = _state.value.copy(attachedPhotoBytes = null, roundVideo = true)
    }

    /** Редагувати спільноту (власник/модератор): назва + опис. */
    fun updateGroup(name: String, description: String) {
        val gid = _state.value.selected?.group?.id ?: return
        if (name.isBlank()) return
        viewModelScope.launch {
            val result = backendSession.authedCall { header ->
                backendApi.updateGroup(header, gid, name.trim(), description.trim())
            }
            when (result) {
                is ApiResult.Success -> {
                    val updated = result.data as dev.amps.app.data.remote.backend.dto.GroupUpdatedResponse
                    _state.value = _state.value.copy(
                        selected = dev.amps.app.data.remote.backend.dto.GroupDetailDto(
                            group = updated.group,
                            myRequestPending = _state.value.selected?.myRequestPending ?: false,
                        ),
                        notice = Loc.translate("Спільноту оновлено ✏️"),
                    )
                    load()
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(error = result.error)
                is ApiResult.NetworkError -> _state.value = _state.value.copy(error = result.message)
            }
        }
    }

    /** Обкладинка спільноти (власник/модератор) — webp стискається заздалегідь. */
    fun uploadCover(bytes: ByteArray) {
        val gid = _state.value.selected?.group?.id ?: return
        viewModelScope.launch {
            val result = backendSession.authedCall { header -> backendApi.uploadGroupCover(header, gid, bytes) }
            if (result is ApiResult.Success) open(gid)
            else _state.value = _state.value.copy(error = (result as? ApiResult.Failure)?.error)
        }
    }

    /** Публікація поста в групу (тільки учасникам, +20 XP). */
    fun publishInGroup() {
        val s = _state.value
        val gid = s.selected?.group?.id ?: return
        if (!s.canPublish) return
        viewModelScope.launch {
            _state.value = s.copy(posting = true, error = null)
            val result = backendSession.authedCall { header ->
                backendApi.createGroupPost(
                    header, gid, s.composingText.trim(),
                    photos = listOfNotNull(s.attachedPhotoBytes),
                    // 1.6.4: кружок — кругле відео до хвилини.
                    roundVideo = s.roundVideo,
                )
            }
            when (result) {
                is ApiResult.Success -> {
                    _state.value = _state.value.copy(
                        posting = false, composingText = "", attachedPhotoBytes = null, roundVideo = false,
                    )
                    loadGroupPosts(gid)
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(posting = false, error = result.error)
                is ApiResult.NetworkError -> _state.value = _state.value.copy(posting = false, error = result.message)
            }
        }
    }

    // ===== Груповий чат =====

    fun setChatDraft(v: String) { _state.value = _state.value.copy(chatDraft = v.take(1000)) }

    private fun loadGroupChat(groupId: Int) {
        viewModelScope.launch {
            val result = backendSession.authedCall { header -> backendApi.getGroupChat(header, groupId) }
            val list = (result as? ApiResult.Success)?.data as? dev.amps.app.data.remote.backend.dto.ChatMessagesResponse
            if (list != null) _state.value = _state.value.copy(chat = list.messages)
        }
    }

    /** Надіслати текст у груповий чат (+2 XP за повідомлення). */
    fun sendChat() {
        val s = _state.value
        val gid = s.selected?.group?.id ?: return
        val text = s.chatDraft.trim()
        if (text.isEmpty() || s.sendingChat) return
        viewModelScope.launch {
            _state.value = s.copy(sendingChat = true)
            val result = backendSession.authedCall { header -> backendApi.sendGroupMessage(header, gid, text) }
            if (result is ApiResult.Success) {
                _state.value = _state.value.copy(chatDraft = "", sendingChat = false)
                loadGroupChat(gid)
            } else {
                _state.value = _state.value.copy(sendingChat = false, error = (result as? ApiResult.Failure)?.error)
            }
        }
    }

    /** Фото в груповий чат. */
    fun sendChatPhoto(bytes: ByteArray) {
        val gid = _state.value.selected?.group?.id ?: return
        viewModelScope.launch {
            val result = backendSession.authedCall { header -> backendApi.sendGroupPhoto(header, gid, bytes) }
            if (result is ApiResult.Success) loadGroupChat(gid)
            else _state.value = _state.value.copy(error = (result as? ApiResult.Failure)?.error)
        }
    }

    // ===== 1.6.4: реакції / видалення / друкує у груповому чаті =====

    /** Реакція на повідомлення групового чату (той самий емодзі ще раз — зняти). */
    fun reactToMessage(messageId: Int, emoji: String) {
        viewModelScope.launch {
            val result = backendSession.authedCall { header ->
                backendApi.reactToMessage(header, messageId, emoji)
            }
            val updated = (result as? ApiResult.Success)?.data
                as? dev.amps.app.data.remote.backend.dto.ChatMessageDto
            if (updated != null) {
                _state.value = _state.value.copy(
                    chat = _state.value.chat.map { if (it.id == updated.id) updated else it },
                )
            }
        }
    }

    /** Видалити своє повідомлення з групового чату. */
    fun deleteMessage(messageId: Int) {
        viewModelScope.launch {
            val result = backendSession.authedCall { header ->
                backendApi.deleteChatMessage(header, messageId)
            }
            when (result) {
                is ApiResult.Success -> _state.value = _state.value.copy(
                    chat = _state.value.chat.filter { it.id != messageId },
                )
                is ApiResult.Failure -> _state.value = _state.value.copy(error = result.error)
                is ApiResult.NetworkError -> _state.value = _state.value.copy(error = result.message)
            }
        }
    }

    /** Позначити «друкує» (тротлінг 3 с) — TypingTracker на сервері тримає 6 с. */
    private var lastTypingSentAt = 0L

    fun sendTyping() {
        val gid = _state.value.selected?.group?.id ?: return
        val now = System.currentTimeMillis()
        if (now - lastTypingSentAt < 3000) return
        lastTypingSentAt = now
        viewModelScope.launch {
            backendSession.authedCall { header -> backendApi.sendTyping(header, groupId = gid) }
        }
    }

    /** Опитати «друкує…» раз на 4 с, поки відкрита вкладка чату. */
    fun pollTyping() {
        val gid = _state.value.selected?.group?.id ?: return
        viewModelScope.launch {
            val result = backendSession.authedCall { header -> backendApi.getTyping(header, groupId = gid) }
            val typing = (result as? ApiResult.Success)?.data
                as? dev.amps.app.data.remote.backend.dto.TypingResponse
            if (typing != null && _state.value.peerTyping != typing.typing) {
                _state.value = _state.value.copy(peerTyping = typing.typing)
            }
        }
    }

    /** Груповий чат відкритий — непрочитані обнуляються. */
    private fun markChatRead(groupId: Int) {
        viewModelScope.launch {
            backendSession.authedCall { header -> backendApi.markGroupChatRead(header, groupId) }
        }
    }

    fun consumeNotice() { _state.value = _state.value.copy(notice = null) }
    fun consumeError() { _state.value = _state.value.copy(error = null) }

    class Factory(
        private val backendApi: dev.amps.app.data.remote.backend.BackendApi,
        private val backendSession: BackendSession,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
            GroupsViewModel(backendApi, backendSession) as T
    }
}
