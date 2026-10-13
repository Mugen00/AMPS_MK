package dev.amps.app.core

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.amps.app.data.remote.backend.ApiResult
import dev.amps.app.data.remote.backend.BackendApi
import dev.amps.app.data.remote.backend.BackendSession
import dev.amps.app.data.remote.backend.dto.ChatUnreadResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 1.6.1: фонова перевірка чатів — раз на 2 хвилини.
 *
 * Живе в області корутин контейнера (без WorkManager: його в офлайн-збірці
 * немає) і працює, поки жив процес застосунку. Зі збереженою сесією
 * бекенда читає сумарні непрочитані (/chats/unread-count) — легкий
 * ендпоінт — і при зростанні показує «Нові повідомлення: N».
 *
 * Жодних push-ів: чесний локальний опитувач, як і [CommunityNotifier].
 * Без дозволу на сповіщення (Android 13+) або без сесії — мовчки нічого.
 */
class ChatNotifier(
    private val context: Context,
    private val backendApi: BackendApi,
    private val backendSession: BackendSession,
    private val notifier: AmpsNotifier,
) {

    private object Keys {
        val lastUnread = longPreferencesKey("chats_last_unread")
    }

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    @Volatile
    private var started = false

    private val checkIntervalMillis = 2L * 60 * 1000
    private val firstCheckDelayMillis = 60L * 1000

    private val Context.chatsDataStore by androidx.datastore.preferences.preferencesDataStore(
        name = "amps_chats",
    )
    private val store = context.chatsDataStore

    /** Запускає цикл перевірки; повторний виклик не робить нічого. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            delay(firstCheckDelayMillis)
            while (true) {
                runCatching { check() }
                delay(checkIntervalMillis)
            }
        }
    }

    /** Один прохід: читаємо сумарні непрочитані, сповіщаємо про зростання. */
    private suspend fun check() {
        if (backendSession.current() == null) return
        val result = backendSession.authedCall { header -> backendApi.getChatUnreadCount(header) }
        val unread = (result as? ApiResult.Success)?.data as? dev.amps.app.data.remote.backend.dto.ChatUnreadResponse
            ?: return

        val prefs = store.data.first()
        val lastSeen = prefs[Keys.lastUnread] ?: 0L
        store.edit { it[Keys.lastUnread] = unread.unread.toLong() }

        if (unread.unread > lastSeen) {
            notifier.notifyNewMessages(unread.unread)
        }
    }
}
