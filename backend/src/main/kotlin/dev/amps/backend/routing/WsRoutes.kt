package dev.amps.backend.routing

import dev.amps.backend.config.AppConfig
import dev.amps.backend.service.ChatService
import dev.amps.backend.service.PresenceService
import dev.amps.backend.service.WsPubSub
import dev.amps.backend.service.WsSessionManager
import io.ktor.server.application.Application
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.routing.routing
import io.ktor.server.websocket.*
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.serialization.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

@Serializable
sealed interface WsMessage {
    @Serializable
    data class Ping(val timestamp: Long = System.currentTimeMillis()) : WsMessage
    @Serializable
    data class Pong(val timestamp: Long = System.currentTimeMillis()) : WsMessage
    
    // Chat messages
    @Serializable
    data class ChatSend(
        val chatId: Int,
        val text: String,
        val tempId: String? = null
    ) : WsMessage
    
    @Serializable
    data class ChatMessage(
        val messageId: Int,
        val chatId: Int,
        val senderId: Int,
        val senderLogin: String,
        val text: String,
        val timestamp: Long,
        val tempId: String? = null
    ) : WsMessage
    
    @Serializable
    data class ChatHistory(
        val chatId: Int,
        val messages: List<ChatMessage>,
        val hasMore: Boolean
    ) : WsMessage
    
    @Serializable
    data class ChatRead(
        val chatId: Int,
        val messageId: Int
    ) : WsMessage
    
    @Serializable
    data class TypingStart(
        val chatId: Int
    ) : WsMessage
    
    @Serializable
    data class TypingStop(
        val chatId: Int
    ) : WsMessage
    
    @Serializable
    data class UserTyping(
        val chatId: Int,
        val userId: Int,
        val userLogin: String
    ) : WsMessage
    
    // Notifications
    @Serializable
    data class Notification(
        val id: Int,
        val type: String,
        val title: String,
        val body: String,
        val data: Map<String, String>? = null,
        val timestamp: Long = System.currentTimeMillis()
    ) : WsMessage
    
    // Presence
    @Serializable
    data class PresenceUpdate(
        val userId: Int,
        val online: Boolean,
        val lastSeen: Long? = null
    ) : WsMessage
    
    @Serializable
    data class SubscribeChat(
        val chatId: Int
    ) : WsMessage
    
    @Serializable
    data class UnsubscribeChat(
        val chatId: Int
    ) : WsMessage
    
    @Serializable
    data class Error(
        val code: String,
        val message: String
    ) : WsMessage
}

/**
 * 1.6.8: WebSocket routing — чат, нотифікації, presence, typing.
 *
 * Ендпоінти:
 * - /ws/chat?token=... — приватні/групові чати
 * - /ws/notifications?token=... — push-нотифікації
 * - /ws/presence?token=... — online status, typing
 *
 * Автентифікація: token в query parameter (QueryParamTokenAuth)
 */
fun Application.wsRoutes(
    config: AppConfig,
    chatService: ChatService,
    presenceService: PresenceService,
    wsPubSub: WsPubSub,
    wsSessionManager: WsSessionManager,
    wsScope: CoroutineScope,
) {
    val logger = LoggerFactory.getLogger("amps.backend.ws.routes")
    val json = Json { ignoreUnknownKeys = true }

    routing {
        // WebSocket endpoint for chat
        webSocket("/ws/chat") {
            val token = call.request.queryParameters["token"]
            val userId = if (token != null && token.isNotBlank()) {
                try {
                    val jwt = com.auth0.jwt.JWT.decode(token)
                    val uidClaim = jwt.getClaim("uid")
                    uidClaim?.asInt()
                } catch (e: Exception) {
                    logger.warn("WS auth failed: ${e.message}")
                    null
                }
            } else null
            
            if (userId == null) {
                close(CloseReason(1000, "Invalid token"))
                return@webSocket
            }
            
            val userIdFinal = userId
            val session = this
            wsSessionManager.registerSession(session, userIdFinal)
            presenceService.setOnline(userIdFinal)
            
            logger.info("WS chat connected: user=$userIdFinal")
            
            // Send welcome/connected message
            session.send(Frame.Text(json.encodeToString(WsMessage.serializer(), WsMessage.Ping())))
            
            // Handle incoming messages using serialization
            try {
                while (true) {
                    val msg = receiveDeserialized<WsMessage>()
                    handleMessage(session, userIdFinal, msg, json, wsSessionManager, wsPubSub)
                }
            } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
                logger.info("WS chat channel closed: user=$userIdFinal")
            } catch (e: Exception) {
                logger.warn("WS chat error: user=$userIdFinal, error=${e.message}")
            }
            
            // Cleanup on close
            wsSessionManager.unregisterSession(session)
            presenceService.setOffline(userIdFinal)
        }

        // WebSocket endpoint for notifications
        webSocket("/ws/notifications") {
            val token = call.request.queryParameters["token"]
            val userId = if (token != null && token.isNotBlank()) {
                try {
                    val jwt = com.auth0.jwt.JWT.decode(token)
                    val uidClaim = jwt.getClaim("uid")
                    uidClaim?.asInt()
                } catch (e: Exception) {
                    logger.warn("WS auth failed: ${e.message}")
                    null
                }
            } else null
            
            if (userId == null) {
                close(CloseReason(1000, "Invalid token"))
                return@webSocket
            }
            
            val userIdFinal = userId
            val session = this
            wsSessionManager.registerSession(session, userIdFinal)
            presenceService.setOnline(userIdFinal)
            
            logger.info("WS notifications connected: user=$userIdFinal")
            
            // Handle incoming messages
            try {
                while (true) {
                    val msg = receiveDeserialized<WsMessage>()
                    when (msg) {
                        is WsMessage.SubscribeChat -> {
                            wsSessionManager.subscribeToChat(session, msg.chatId)
                            session.send(Frame.Text(json.encodeToString(WsMessage.serializer(), WsMessage.Error("OK", "Subscribed to chat ${msg.chatId}"))))
                        }
                        is WsMessage.UnsubscribeChat -> {
                            wsPubSub.unsubscribeChat(msg.chatId)
                            session.send(Frame.Text(json.encodeToString(WsMessage.serializer(), WsMessage.Error("OK", "Unsubscribed from chat ${msg.chatId}"))))
                        }
                        else -> { /* ignore */ }
                    }
                }
            } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
                logger.info("WS notifications channel closed: user=$userIdFinal")
            } catch (e: Exception) {
                logger.warn("WS notifications error: user=$userIdFinal, error=${e.message}")
            }
            
            wsSessionManager.unregisterSession(session)
            presenceService.setOffline(userIdFinal)
        }

        // WebSocket endpoint for presence/typing
        webSocket("/ws/presence") {
            val token = call.request.queryParameters["token"]
            val userId = if (token != null && token.isNotBlank()) {
                try {
                    val jwt = com.auth0.jwt.JWT.decode(token)
                    val uidClaim = jwt.getClaim("uid")
                    uidClaim?.asInt()
                } catch (e: Exception) {
                    logger.warn("WS auth failed: ${e.message}")
                    null
                }
            } else null
            
            if (userId == null) {
                close(CloseReason(1000, "Invalid token"))
                return@webSocket
            }
            
            val userIdFinal = userId
            val session = this
            wsSessionManager.registerSession(session, userIdFinal)
            presenceService.setOnline(userIdFinal)
            
            logger.info("WS presence connected: user=$userIdFinal")
            
            // Handle incoming messages
            try {
                while (true) {
                    val msg = receiveDeserialized<WsMessage>()
                    handleMessage(session, userIdFinal, msg, json, wsSessionManager, wsPubSub)
                }
            } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
                logger.info("WS presence channel closed: user=$userIdFinal")
            } catch (e: Exception) {
                logger.warn("WS presence error: user=$userIdFinal, error=${e.message}")
            }
            
            wsSessionManager.unregisterSession(session)
            presenceService.setOffline(userIdFinal)
        }
    }
}

// Function to handle messages
private suspend fun handleMessage(
    session: io.ktor.websocket.WebSocketSession,
    userId: Int,
    msg: WsMessage,
    json: kotlinx.serialization.json.Json,
    wsSessionManager: WsSessionManager,
    wsPubSub: WsPubSub,
) {
    val logger = LoggerFactory.getLogger("amps.backend.ws.routes")
    try {
        when (msg) {
            is WsMessage.Ping -> {
                session.send(Frame.Text(json.encodeToString(WsMessage.serializer(), WsMessage.Pong())))
            }
            is WsMessage.ChatSend -> {
                // TODO: Send message via chatService, then broadcast via wsPubSub
                session.send(Frame.Text(json.encodeToString(WsMessage.serializer(), WsMessage.Error("TODO", "Chat send not fully implemented yet"))))
            }
            is WsMessage.TypingStart -> {
                // TODO: presenceService.setTyping(msg.chatId, userId)
            }
            is WsMessage.TypingStop -> {
                // TODO: presenceService.clearTyping(msg.chatId, userId)
            }
            is WsMessage.SubscribeChat -> {
                wsSessionManager.subscribeToChat(session, msg.chatId)
                session.send(Frame.Text(json.encodeToString(WsMessage.serializer(), WsMessage.Error("OK", "Subscribed to chat ${msg.chatId}"))))
            }
            is WsMessage.UnsubscribeChat -> {
                wsPubSub.unsubscribeChat(msg.chatId)
            }
            else -> { /* ignore unknown */ }
        }
    } catch (e: Exception) {
        logger.warn("WS message handle error: ${e.message}")
    }
}