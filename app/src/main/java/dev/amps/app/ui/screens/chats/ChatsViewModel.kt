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
import dev.amps.app.data.remote.backend.dto.ChatMessageDto
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
 *
 * 1.6.2: реакції на повідомлення, «друкує…» (опитування 4 с; позначка
 * друку — тротлінг 3 с), блокування користувача.
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
    /** 1.6.2: співрозмовник друкує (опитується кожні 4 с, поки чат відкритий). */
    val peerTyping: Boolean = false,
    /** 1.6.2: Authorization-заголовок для приватних вкладень (фото чатів). */
    val authHeader: String? = null,
    val error: String? = null,
)

class ChatsViewModel(
    private val backendApi: BackendApi,
    private val backendSession: BackendSession,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatsUiState())
    val state: StateFlow<ChatsUiState> = _state.asStateFlow()

    private var lastAuthorized: Boolean? = null

    /** 1.6.2: тротлінг позначки друку — не частіше, ніж раз за 3 с. */
    private var lastTypingSentAt: Long = 0L

    /** 1.6.2: покоління циклів «друкує…» — щоб не плодити цикли опитування. */
    private var typingGeneration = 0

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
            peerTyping = false,
        )
        loadHistory()
        startTypingPolling()
        viewModelScope.launch {
            backendSession.authedCall { header -> backendApi.markChatRead(header, peerId) }
        }
    }

    /** Закрити чат — назад до списку (список оновиться з непрочитаними). */
    fun close() {
        _state.value = _state.value.copy(
            openPeerId = null, openTitle = "", openAvatar = null, messages = emptyList(),
            peerTyping = false,
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

    /**
     * 1.6.2: поки відкритий приватний чат — опитуємо «друкує…» кожні 4 с.
     * При кожному відкритті чату покоління зростає: старі цикли самі виходять.
     */
    private fun startTypingPolling() {
        val peer = _state.value.openPeerId ?: return
        val generation = ++typingGeneration
        viewModelScope.launch {
            while (lastAuthorized == true && _state.value.openPeerId == peer && generation == typingGeneration) {
                val result = backendSession.authedCall { header ->
                    backendApi.getTyping(header, peerUserId = peer)
                }
                if (generation == typingGeneration && _state.value.openPeerId == peer) {
                    val r = (result as? ApiResult.Success)?.data as? dev.amps.app.data.remote.backend.dto.TypingResponse
                    _state.value = _state.value.copy(peerTyping = r?.typing == true)
                }
                delay(4_000L)
            }
        }
    }

    fun setDraft(v: String) {
        _state.value = _state.value.copy(draft = v.take(1000))
        // 1.6.2: позначка «друкує…» — не частіше, ніж раз за 3 с.
        val peer = _state.value.openPeerId ?: return
        val now = System.currentTimeMillis()
        if (v.isNotBlank() && now - lastTypingSentAt > 3_000L) {
            lastTypingSentAt = now
            viewModelScope.launch {
                backendSession.authedCall { header -> backendApi.sendTyping(header, peerUserId = peer) }
            }
        }
    }

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

    // ===== 1.6.2: реакції на повідомлення =====

    /**
     * Перемкнути реакцію (емодзі) на повідомлення: сервер повертає
     * оновлений ChatMessageDto — підмінюємо його в історії.
     */
    fun reactToMessage(messageId: Int, emoji: String) {
        viewModelScope.launch {
            val result = backendSession.authedCall { header ->
                backendApi.reactToMessage(header, messageId, emoji)
            }
            when (result) {
                is ApiResult.Success -> {
                    val updated = result.data as? ChatMessageDto
                    if (updated != null) {
                        _state.value = _state.value.copy(
                            messages = _state.value.messages.map {
                                if (it.id == updated.id) updated else it
                            },
                        )
                    }
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(error = result.error)
                is ApiResult.NetworkError -> _state.value = _state.value.copy(error = result.message)
            }
        }
    }

    // ===== 1.6.3: видалення свого повідомлення =====

    /** Видалити повідомлення: прибираємо його з історії одразу після 200. */
    fun deleteMessage(messageId: Int) {
        viewModelScope.launch {
            val result = backendSession.authedCall { header ->
                backendApi.deleteChatMessage(header, messageId)
            }
            when (result) {
                is ApiResult.Success -> {
                    _state.value = _state.value.copy(
                        messages = _state.value.messages.filter { it.id != messageId },
                    )
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(error = result.error)
                is ApiResult.NetworkError -> _state.value = _state.value.copy(error = result.message)
            }
        }
    }

    // ===== 1.6.2: блокування =====

    /** Заблокувати/розблокувати співрозмовника; при блокуванні — закриваємо чат. */
    fun toggleBlockPeer() {
        val peer = _state.value.openPeerId ?: return
        viewModelScope.launch {
            val result = backendSession.authedCall { header -> backendApi.toggleBlock(header, peer) }
            when (result) {
                is ApiResult.Success -> {
                    val r = result.data as? dev.amps.app.data.remote.backend.dto.BlockToggleResponse
                    if (r?.blocked == true) {
                        _state.value = _state.value.copy(error = "Користувача заблоковано 🚫")
                        close()
                    } else {
                        _state.value = _state.value.copy(error = "Блокування знято ✅")
                    }
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(error = result.error)
                is ApiResult.NetworkError -> _state.value = _state.value.copy(error = result.message)
            }
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
