package dev.amps.backend.service

import dev.amps.backend.config.RedisConfig
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1.6.8: WebSocket Session Manager — керує активними WS з'єднаннями.
 *
 * Зберігає мапу: userId -> Set<WebSocketSession>
 * При масштабуванні (multi-instance) синхронізація через Redis Pub/Sub.
 */
class WsSessionManager(
    private val redis: RedisConfig,
    private val wsPubSub: WsPubSub,
    private val scope: CoroutineScope,
) {

    private val logger = LoggerFactory.getLogger("amps.backend.ws.sessions")
    private val sessionCounter = AtomicInteger(0)
    
    // Local sessions: userId -> Set of sessions
    private val userSessions = ConcurrentHashMap<Int, MutableSet<io.ktor.websocket.WebSocketSession>>()
    // Session -> userId mapping for cleanup
    private val sessionUserMap = ConcurrentHashMap<io.ktor.websocket.WebSocketSession, Int>()
    
    // Subscriptions: session -> Set of subscribed channels
    private val sessionSubscriptions = ConcurrentHashMap<io.ktor.websocket.WebSocketSession, MutableSet<String>>()

    init {
        // Start Redis subscription listeners for cross-instance messaging
        startRedisListeners()
    }

    /** Реєстрація нового WS сесії. */
    fun registerSession(session: io.ktor.websocket.WebSocketSession, userId: Int) {
        val sessionId = sessionCounter.incrementAndGet()
        userSessions.computeIfAbsent(userId) { 
            mutableSetOf() 
        }.add(session)
        sessionUserMap[session] = userId
        sessionSubscriptions[session] = mutableSetOf()
        logger.debug("WS registered: user=$userId, sessionId=$sessionId")
    }

    /** Видалення WS сесії. */
    fun unregisterSession(session: io.ktor.websocket.WebSocketSession) {
        val userId = sessionUserMap.remove(session)
        userId?.let { uid ->
            userSessions[uid]?.remove(session)
            if (userSessions[uid]?.isEmpty() == true) {
                userSessions.remove(uid)
            }
        }
        // Unsubscribe from all channels
        sessionSubscriptions.remove(session)?.forEach { channel ->
            scope.launch { wsPubSub.unsubscribeChat(channel.removePrefix("ws:chat:").toIntOrNull() ?: 0) }
        }
        sessionSubscriptions.remove(session)
        logger.debug("WS unregistered: user=$userId")
    }

    /** Підписати сесію на чат. */
    suspend fun subscribeToChat(session: io.ktor.websocket.WebSocketSession, chatId: Int) {
        val channel = "ws:chat:$chatId"
        sessionSubscriptions.getOrPut(session) { mutableSetOf() }.add(channel)
        wsPubSub.subscribeChat(chatId) { payload ->
            // Forward to this session if still connected
            // Note: We can't reliably check isOpen, so we'll catch exceptions
            scope.launch { 
                try {
                    session.send(io.ktor.websocket.Frame.Text(payload))
                } catch (e: Exception) {
                    // Session likely closed, ignore
                    logger.trace("Failed to send to session: ${e.message}")
                }
            }
        }
        logger.debug("Session subscribed to chat $chatId")
    }

    /** Відправити повідомлення конкретному юзеру (всі його сесії). */
    suspend fun sendToUser(userId: Int, payload: String) {
        userSessions[userId]?.forEach { session ->
            scope.launch { 
                try {
                    session.send(io.ktor.websocket.Frame.Text(payload))
                } catch (e: Exception) {
                    logger.trace("Failed to send to user $userId: ${e.message}")
                }
            }
        }
    }

    /** Відправити повідомлення конкретній сесії. */
    suspend fun sendToSession(session: io.ktor.websocket.WebSocketSession, payload: String) {
        scope.launch { 
            try {
                session.send(io.ktor.websocket.Frame.Text(payload))
            } catch (e: Exception) {
                logger.trace("Failed to send to session: ${e.message}")
            }
        }
    }

    /** Отримати всі активні сесії юзера. */
    fun getUserSessions(userId: Int): Set<io.ktor.websocket.WebSocketSession> = 
        userSessions[userId]?.toSet() ?: emptySet()

    /** Отримати userId для сесії. */
    fun getUserId(session: io.ktor.websocket.WebSocketSession): Int? = sessionUserMap[session]

    /** Перевірити чи юзер онлайн (має активні сесії). */
    fun isUserOnline(userId: Int): Boolean = userSessions[userId]?.isNotEmpty() == true

    /** Отримати кількість онлайн юзерів. */
    fun getOnlineUserCount(): Int = userSessions.size

    /** Отримати всі онлайн userId. */
    fun getOnlineUserIds(): Set<Int> = userSessions.keys.toSet()

    /** Запустити Redis listeners для cross-instance messaging. */
    private fun startRedisListeners() {
        // Subscribe to presence events
        scope.launch { wsPubSub.subscribePresence { payload ->
            // Broadcast presence updates to relevant sessions
            logger.trace("Received presence event: $payload")
        } }
        
        // Subscribe to notifications broadcast
        scope.launch { wsPubSub.subscribeNotifications(0) { payload ->
            // This is a broadcast to all - handle if needed
            logger.trace("Received broadcast notification: $payload")
        } }
    }

    /** Graceful shutdown. */
    fun shutdown() = scope.launch {
        userSessions.values.flatten().forEach { 
            try {
                it.close(CloseReason(1000, "Server shutdown"))
            } catch (e: Exception) {
                logger.trace("Error closing session: ${e.message}")
            }
        }
        userSessions.clear()
        sessionUserMap.clear()
        sessionSubscriptions.clear()
    }
}