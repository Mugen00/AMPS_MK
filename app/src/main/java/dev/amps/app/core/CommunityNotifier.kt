package dev.amps.app.core

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.amps.app.data.remote.backend.ApiResult
import dev.amps.app.data.remote.backend.BackendApi
import dev.amps.app.data.remote.backend.BackendSession
import dev.amps.app.data.remote.backend.dto.FeedResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 1.1.2: фонова перевірка Спільноти — раз на годину.
 *
 * Живе в області корутин контейнера (без WorkManager: його в офлайн-збірці
 * немає) і працює, поки жив процес застосунку. Якщо збережена сесія
 * бекенда є — раз на годину читає перші 10 рядків стрічки і порівнює:
 *
 * - нові пости (id більше за бачений раніше) — «у Спільноті нові пости»;
 * - власні пости з лічильниками, що змінилися — «лайки і репости».
 *
 * Жодних push-ів: чесний локальний опитувач. Без дозволу на сповіщення
 * (Android 13+) або без сесії — мовчки нічого не робить.
 */
class CommunityNotifier(
    private val context: Context,
    private val backendApi: BackendApi,
    private val backendSession: BackendSession,
    private val notifier: AmpsNotifier,
) {

    /** Останні бачені лічильники власних постів: postId → [SeenCounts]. */
    @Serializable
    data class SeenCounts(val likes: Int, val reposts: Int)

    private object Keys {
        val lastSeenPostId = longPreferencesKey("community_last_seen_post_id")
        val seenCounts = stringPreferencesKey("community_seen_counts_json")
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var started = false

    private val checkIntervalMillis = 60L * 60 * 1000
    private val firstCheckDelayMillis = 3L * 60 * 1000

    private val Context.communityDataStore by preferencesDataStore(name = "amps_community")
    private val store = context.communityDataStore

    /** Запускає годинний цикл; повторний виклик не робить нічого. */
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

    /** Один прохід перевірки; кожна помилка — тихо і до наступної години. */
    private suspend fun check() {
        val me = backendSession.current() ?: return
        val result = backendSession.authedCall { header -> backendApi.getFeed(header, limit = 10) }
        val feed = (result as? ApiResult.Success)?.data as? FeedResponse ?: return

        val prefs = store.data.first()
        val lastSeen = prefs[Keys.lastSeenPostId] ?: -1L
        val seenCounts = prefs[Keys.seenCounts]
            ?.let { runCatching { json.decodeFromString<Map<Int, SeenCounts>>(it) }.getOrNull() }
            ?: emptyMap()

        val maxPostId = feed.items.maxOfOrNull { it.post.id.toLong() } ?: return
        val newCount = feed.items.count { it.post.id.toLong() > lastSeen }
        val myId = me.userId

        val lines = mutableListOf<String>()
        if (newCount > 0 && lastSeen >= 0) {
            lines += "Нові пости: $newCount"
        }
        feed.items
            .filter { it.post.author.userId == myId }
            .forEach { item ->
                val previous = seenCounts[item.post.id] ?: return@forEach
                val likeDelta = item.post.likeCount - previous.likes
                val repostDelta = item.post.repostCount - previous.reposts
                if (likeDelta > 0) lines += "Лайки ваших постів: +$likeDelta"
                if (repostDelta > 0) lines += "Репости ваших постів: +$repostDelta"
            }

        if (lines.isNotEmpty()) {
            notifier.notifyCommunityActivity(
                "Спільнота",
                lines.take(3).joinToString("\n"),
            )
        }

        val counts = feed.items
            .filter { it.post.author.userId == myId }
            .associate { it.post.id to SeenCounts(it.post.likeCount, it.post.repostCount) }
        store.edit {
            it[Keys.lastSeenPostId] = maxPostId
            it[Keys.seenCounts] = json.encodeToString(counts)
        }
    }
}
