package dev.amps.backend.service

import dev.amps.backend.config.RedisConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * 1.6.8: Presence Service — онлайн/офлайн статус, last seen, typing indicators.
 *
 * Ключі:
 * - "presence:user:{userId}" = JSON { online: true, lastSeen: timestamp, wsCount: Int }
 * - "presence:typing:{chatId}:{userId}" = timestamp (TTL 10с)
 * - Pub/Sub канал "presence:events" для real-time оновлень
 */
class PresenceService(private val redis: RedisConfig) {

    private val logger = LoggerFactory.getLogger("amps.backend.presence")
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    @kotlinx.serialization.Serializable
    data class PresenceState(
        val online: Boolean,
        val lastSeen: Long,
        val wsCount: Int = 0,
    )

    /** Позначити користувача онлайном (викликається при WS connect). */
    suspend fun setOnline(userId: Int) = withContext(Dispatchers.IO) {
        val key = "presence:user:$userId"
        redis.syncPool.borrowObject().use { conn ->
            val existing = conn.sync().get(key)
            val state = existing?.let { json.decodeFromString(PresenceState.serializer(), it) }
                ?: PresenceState(online = true, lastSeen = Instant.now().toEpochMilli(), wsCount = 0)
            val updated = state.copy(online = true, wsCount = state.wsCount + 1)
            conn.sync().set(key, json.encodeToString(PresenceState.serializer(), updated))
            conn.sync().expire(key, 86400) // 24h TTL
        }
        publishEvent("online", userId)
    }

    /** Позначити користувача офлайном (викликається при WS disconnect). */
    suspend fun setOffline(userId: Int) = withContext(Dispatchers.IO) {
        val key = "presence:user:$userId"
        redis.syncPool.borrowObject().use { conn ->
            val existing = conn.sync().get(key)
            val state = existing?.let { json.decodeFromString(PresenceState.serializer(), it) }
            if (state != null) {
                val newCount = (state.wsCount - 1).coerceAtLeast(0)
                val updated = if (newCount == 0) {
                    state.copy(online = false, lastSeen = Instant.now().toEpochMilli(), wsCount = 0)
                } else {
                    state.copy(wsCount = newCount)
                }
                conn.sync().set(key, json.encodeToString(PresenceState.serializer(), updated))
                conn.sync().expire(key, 86400)
            }
        }
        publishEvent("offline", userId)
    }

    /** Отримати статус користувача. */
    suspend fun getPresence(userId: Int): PresenceState? = withContext(Dispatchers.IO) {
        val key = "presence:user:$userId"
        redis.syncPool.borrowObject().use { conn ->
            val value = conn.sync().get(key)
            value?.let { json.decodeFromString(PresenceState.serializer(), it) }
        }
    }

    /** Отримати статуси декількох користувачів (batch). */
    suspend fun getPresences(userIds: List<Int>): Map<Int, PresenceState> = withContext(Dispatchers.IO) {
        if (userIds.isEmpty()) return@withContext emptyMap()
        redis.syncPool.borrowObject().use { conn ->
            val keys = userIds.map { "presence:user:$it" }
            val keyValues = conn.sync().mget(*keys.toTypedArray())
            userIds.zip(keyValues).mapNotNull { (id, kv) ->
                kv?.value?.let { json.decodeFromString(PresenceState.serializer(), it) }?.let { id to it }
            }.toMap()
        }
    }

    /** Встановити typing indicator (TTL 10с). */
    suspend fun setTyping(chatId: Int, userId: Int) = withContext(Dispatchers.IO) {
        val key = "presence:typing:$chatId:$userId"
        redis.syncPool.borrowObject().use { conn ->
            conn.sync().setex(key, 10, Instant.now().toEpochMilli().toString())
        }
        publishEvent("typing", userId, chatId)
    }

    /** Отримати список користувачів, які пишуть у чаті. */
    suspend fun getTypingUsers(chatId: Int): List<Int> = withContext(Dispatchers.IO) {
        val pattern = "presence:typing:$chatId:*"
        redis.syncPool.borrowObject().use { conn ->
            val keys = conn.sync().keys(pattern)
            keys.mapNotNull { key ->
                val parts = key.split(":")
                if (parts.size == 4) parts[3].toIntOrNull() else null
            }
        }
    }

    /** Очистити typing indicator. */
    suspend fun clearTyping(chatId: Int, userId: Int) = withContext(Dispatchers.IO) {
        val key = "presence:typing:$chatId:$userId"
        redis.syncPool.borrowObject().use { conn ->
            conn.sync().del(key)
        }
    }

    /** Отримати онлайн-користувачів з групи (batch). */
    suspend fun getOnlineUsers(userIds: List<Int>): Set<Int> = withContext(Dispatchers.IO) {
        val presences = getPresences(userIds)
        presences.filter { it.value.online }.keys.toSet()
    }

    private suspend fun publishEvent(event: String, userId: Int, chatId: Int? = null) = withContext(Dispatchers.IO) {
        @kotlinx.serialization.Serializable
        data class PresenceEvent(
            val event: String,
            val userId: Int,
            val chatId: Int?,
            val timestamp: Long,
        )
        val payload = json.encodeToString(PresenceEvent.serializer(), PresenceEvent(event, userId, chatId, Instant.now().toEpochMilli()))
        redis.asyncConnection.async().publish("presence:events", payload)
    }
}