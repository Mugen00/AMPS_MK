package dev.amps.app.ui.screens.framesearch

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.amps.app.core.GeminiChatMessage
import dev.amps.app.core.GeminiChatSession
import dev.amps.app.core.GeminiChatStore
import dev.amps.app.core.SettingsStore
import dev.amps.app.data.remote.GeminiVision
import dev.amps.app.util.ImageLoader
import dev.amps.app.util.PreparedImage
import dev.amps.app.util.readableMessage
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Одне повідомлення чату в UI. */
data class ChatMsg(val role: String, val text: String) // "user" | "model"

data class FrameSearchState(
    val image: PreparedImage? = null,
    /** Переписка з Gemini по поточному фото. */
    val messages: List<ChatMsg> = emptyList(),
    val input: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    /** id поточної сесії (після першої відповіді сесія зберігається). */
    val sessionId: String? = null,
)

/**
 * 1.3.0: Frame Search повністю переписаний на AI-чат: зворотний пошук
 * по базах (IQDB/SauceNAO) прибрано — Gemini веде переписку про фото,
 * кожен аналіз зберігається в історію на пристрої.
 */
class FrameSearchViewModel(
    private val appContext: Context,
    private val settings: SettingsStore,
    private val chatStore: GeminiChatStore,
) : ViewModel() {

    private val _state = MutableStateFlow(FrameSearchState())
    val state: StateFlow<FrameSearchState> = _state.asStateFlow()

    /** Історія збережених аналізів — свіжі зверху. */
    val chats: StateFlow<List<GeminiChatSession>> = chatStore.chats.stateIn(
        viewModelScope,
        kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    fun onImagePicked(uri: Uri) = viewModelScope.launch {
        runCatching { ImageLoader.prepare(appContext, uri) }
            .onSuccess { image -> _state.value = FrameSearchState(image = image) }
            .onFailure { error -> _state.value = FrameSearchState(error = error.readableMessage()) }
    }

    fun setInput(value: String) {
        _state.value = _state.value.copy(input = value)
    }

    /**
     * Надіслати повідомлення. Перше — несе фото (дефолтний промпт, якщо
     * поле порожнє); далі — мультитурн з повною історією переписки.
     */
    fun send() {
        val image = _state.value.image ?: return
        if (_state.value.busy) return
        val userText = _state.value.input.trim().ifBlank { DEFAULT_PROMPT }
        val turns = _state.value.messages.map { GeminiVision.ChatTurn(it.role, it.text) } +
            GeminiVision.ChatTurn("user", userText)
        _state.value = _state.value.copy(
            busy = true,
            input = "",
            messages = _state.value.messages + ChatMsg("user", userText),
        )
        viewModelScope.launch {
            val key = settings.settings.first().geminiApiKey
            runCatching { GeminiVision.chat(image.bytes, key, turns) }
                .onSuccess { answer ->
                    _state.value = _state.value.copy(
                        busy = false,
                        error = null,
                        messages = _state.value.messages + ChatMsg("model", answer),
                    )
                    persistSession()
                }
                .onFailure { failure ->
                    _state.value = _state.value.copy(busy = false, error = failure.readableMessage())
                }
        }
    }

    /** Нове питання про те саме фото: переписка чиста, фото лишається. */
    fun newChat() {
        _state.value = _state.value.copy(messages = emptyList(), input = "", error = null)
    }

    /** Відкрити збережену сесію з історії: фото з файлу + переписка. */
    fun openSession(session: GeminiChatSession) {
        viewModelScope.launch {
            val bytes = runCatching { File(session.photoPath).readBytes() }.getOrNull()
            if (bytes == null) {
                _state.value = _state.value.copy(error = "Фото цієї сесії видалено з пам'яті пристрою")
                return@launch
            }
            val image = PreparedImage(
                bytes = bytes,
                mime = "image/jpeg",
                fileName = "${session.id}.jpg",
                previewDataUrl = "",
                width = 0,
                height = 0,
            )
            _state.value = FrameSearchState(
                image = image,
                sessionId = session.id,
                messages = session.messages.map { ChatMsg(it.role, it.text) },
            )
        }
    }

    fun deleteSession(session: GeminiChatSession) = viewModelScope.launch {
        chatStore.delete(session)
    }

    fun clear() {
        _state.value = FrameSearchState()
    }

    /** Автозбереження після кожної відповіді моделі. */
    private fun persistSession() {
        val snapshot = _state.value
        val image = snapshot.image ?: return
        val id = snapshot.sessionId ?: java.util.UUID.randomUUID().toString()
        viewModelScope.launch {
            val photoPath = chatStore.savePhoto(id, image.bytes)
            val firstAnswer = snapshot.messages
                .firstOrNull { it.role == "model" }?.text.orEmpty()
            val title = firstAnswer.split("\n").first().take(64).ifBlank { "Аналіз фото" }
            chatStore.upsert(
                GeminiChatSession(
                    id = id,
                    createdAt = System.currentTimeMillis(),
                    title = title,
                    photoPath = photoPath,
                    messages = snapshot.messages.map { GeminiChatMessage(it.role, it.text) },
                ),
            )
        }
    }

    companion object {
        /** Перша фраза, якщо користувач одразу тисне «надіслати». */
        const val DEFAULT_PROMPT =
            "Опиши це фото: що зображено, звідки це (аніме/манґа/гра), хто персонаж, які деталі впізнавані. Українською."
    }

    class Factory(
        private val appContext: Context,
        private val settings: SettingsStore,
        private val chatStore: GeminiChatStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            FrameSearchViewModel(appContext, settings, chatStore) as T
    }
}
