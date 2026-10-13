package dev.amps.app.ui.screens.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.amps.app.data.remote.backend.ApiResult
import dev.amps.app.data.remote.backend.BackendApi
import dev.amps.app.data.remote.backend.BackendSession
import dev.amps.app.data.remote.backend.dto.ChatContactsResponse
import dev.amps.app.data.remote.backend.dto.ChatDialogDto
import dev.amps.app.data.remote.backend.dto.ChatDialogsResponse
import dev.amps.app.data.remote.backend.dto.ChatMessagesResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 1.6.0: стан екрана Чати — список діалогів (приватні + групові) з
 * непрочитаними, приватна переписка 1-на-1. Групові чати відкриваються
 * з екрана групи; тут вони теж у списку — тап відкриває відповідну групу.
 * Без WebSocket: список чатів опитується кожні ~15 секунд, поки екран живий.
 */
data class ChatsUiState(
    val authorized: Boolean = false,
    val loading: Boolean = false,
    val sending: Boolean = false,
    /** Список діалогів (приватні + групи, свіжі зверху). */
    val dialogs: List<ChatDialogDto> = emptyList(),
    /** Контакти (ті, на кого я підписаний) — для старту нового діалогу. */
    val contacts: List<dev.amps.app.data.remote.backend.dto.ProfileDto> = emptyList(),
    /** 1.6.1: результати пошуку людей (додаток до контактів). */
    val searchResults: List<dev.amps.app.data.remote.backend.dto.ProfileDto> = emptyList(),
    /** Пошук триває. */
    val searchLoading: Boolean = false,
    /** Відкритий приватний чат: id співрозмовника (null — список чатів). */
    val openPeerId: Int? = null,
    /** Заголовок і аватар відкритого чату. */
    val openTitle: String = "",
    val openAvatar: String? = null,
    /** Історія відкритого чату (свіжі зверху — як віддає сервер). */
    val messages: List<dev.amps.app.data.remote.backend.dto.ChatMessageDto> = emptyList(),
    val draft: String = "",
    val error: String? = null,
)

class ChatsViewModel(
    private val backendApi: BackendApi,
    private val backendSession: BackendSession,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatsUiState())
    val state: StateFlow<ChatsUiState> = _state.asStateFlow()

    private var lastAuthorized: Boolean? = null

    init {
        viewModelScope.launch {
            backendSession.stored.collect { stored ->
                val authorized = stored != null
                val previous = lastAuthorized
                lastAuthorized = authorized
                _state.value = _state.value.copy(authorized = authorized)
                when {
                    previous == null && authorized -> startPolling()
                    previous == false && authorized -> loadDialogs()
                }
            }
        }
    }

    /**
     * Опитування списку чатів кожні ~15 с: без WebSocket — просто і
     * надійно. Список тіпається і після повернення на екран чатів.
     */
    fun startPolling() {
        viewModelScope.launch {
            while (lastAuthorized == true) {
                loadDialogs()
                delay(15_000L)
            }
        }
    }

    fun loadDialogs() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val result = backendSession.authedCall { header -> backendApi.getChats(header) }
            val list = (result as? ApiResult.Success)?.data as? ChatDialogsResponse
            _state.value = _state.value.copy(
                loading = false,
                dialogs = list?.dialogs ?: _state.value.dialogs,
                error = (result as? ApiResult.Failure)?.error,
            )
        }
    }

    /** Контакти: ті, на кого я підписаний — щоб почати новий діалог. */
    fun loadContacts() {
        viewModelScope.launch {
            val result = backendSession.authedCall { header -> backendApi.getChatContacts(header) }
            val list = (result as? ApiResult.Success)?.data as? ChatContactsResponse
            if (list != null) _state.value = _state.value.copy(contacts = list.contacts)
        }
    }

    /**
     * 1.6.1: пошук людей за ім'ям або логіном — можна написати тому,
     * на кого ще не підписаний. Порожній запит очищає результати.
     */
    fun searchUsers(q: String) {
        val query = q.trim()
        if (query.length < 2) {
            _state.value = _state.value.copy(searchResults = emptyList(), searchLoading = false)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(searchLoading = true)
            val result = backendSession.authedCall { header -> backendApi.searchUsers(header, query) }
            val found = (result as? ApiResult.Success)?.data as? dev.amps.app.data.remote.backend.dto.UsersSearchResponse
            _state.value = _state.value.copy(
                searchResults = found?.users ?: emptyList(),
                searchLoading = false,
            )
        }
    }

    // ===== Приватний діалог =====

    /** Відкрити переписку: історія + діалог позначається прочитаним. */
    fun openPrivate(peerId: Int, title: String, avatarPath: String?) {
        _state.value = _state.value.copy(
            openPeerId = peerId,
            openTitle = title,
            openAvatar = avatarPath,
            messages = emptyList(),
            draft = "",
        )
        loadHistory()
        viewModelScope.launch {
            backendSession.authedCall { header -> backendApi.markChatRead(header, peerId) }
        }
    }

    /** Закрити чат — назад до списку (список оновиться з непрочитаними). */
    fun close() {
        _state.value = _state.value.copy(
            openPeerId = null, openTitle = "", openAvatar = null, messages = emptyList(),
        )
        loadDialogs()
    }

    private fun loadHistory() {
        val peer = _state.value.openPeerId ?: return
        viewModelScope.launch {
            val result = backendSession.authedCall { header -> backendApi.getPrivateMessages(header, peer) }
            val list = (result as? ApiResult.Success)?.data as? ChatMessagesResponse
            if (list != null) _state.value = _state.value.copy(messages = list.messages)
        }
    }

    fun setDraft(v: String) { _state.value = _state.value.copy(draft = v.take(1000)) }

    /** Надіслати текст у приватний чат (+2 XP за повідомлення). */
    fun sendMessage() {
        val peer = _state.value.openPeerId ?: return
        val text = _state.value.draft.trim()
        if (text.isEmpty() || _state.value.sending) return
        viewModelScope.launch {
            _state.value = _state.value.copy(sending = true)
            val result = backendSession.authedCall { header -> backendApi.sendPrivateMessage(header, peer, text) }
            when (result) {
                is ApiResult.Success -> {
                    _state.value = _state.value.copy(draft = "", sending = false)
                    loadHistory()
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(sending = false, error = result.error)
                is ApiResult.NetworkError -> _state.value = _state.value.copy(sending = false, error = result.message)
            }
        }
    }

    /** Фото в приватний чат. */
    fun sendPhoto(bytes: ByteArray) {
        val peer = _state.value.openPeerId ?: return
        viewModelScope.launch {
            val result = backendSession.authedCall { header -> backendApi.sendPrivatePhoto(header, peer, bytes) }
            if (result is ApiResult.Success) loadHistory()
            else _state.value = _state.value.copy(error = (result as? ApiResult.Failure)?.error)
        }
    }

    fun consumeError() { _state.value = _state.value.copy(error = null) }

    class Factory(
        private val backendApi: BackendApi,
        private val backendSession: BackendSession,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ChatsViewModel(backendApi, backendSession) as T
    }
}
