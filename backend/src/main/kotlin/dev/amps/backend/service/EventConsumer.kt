package dev.amps.backend.service

import dev.amps.backend.config.RedisConfig
import dev.amps.backend.model.PostTable
import dev.amps.backend.model.CommentTable
import dev.amps.backend.model.LikeTable
import dev.amps.backend.model.FollowTable
import dev.amps.backend.model.GroupTable
import dev.amps.backend.model.GroupMemberTable
import dev.amps.backend.model.ChatMessageTable
import dev.amps.backend.model.ReelTable
import dev.amps.backend.model.AnimeCollectionTable
import dev.amps.backend.model.AnimeCollectionCharacterTable
import dev.amps.backend.model.ProfileTable
import dev.amps.backend.model.PostEntity
import dev.amps.backend.service.DomainEvent
import io.lettuce.core.StreamMessage
import io.lettuce.core.XReadArgs
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.HashMap

/**
 * 1.6.8: Event Consumer — читає Domain Events з Redis Stream (простий polling)
 * і оновлює відповідні сервіси/таблиці (FeedService, ChatService, GroupService, тощо).
 * Використовує простий XREAD без consumer groups для сумісності з Lettuce 6.
 */
class EventConsumer(
    private val redis: RedisConfig,
    private val feedService: FeedService,
    private val chatService: dev.amps.backend.service.ChatService,
    private val groupService: dev.amps.backend.service.GroupService,
    private val profileService: ProfileService,
    private val animeCollectionService: dev.amps.backend.service.AnimeCollectionService,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val logger = LoggerFactory.getLogger("amps.backend.events.consume")
    private val json = Json { ignoreUnknownKeys = true }
    private val streamKey = "events:domain"
    private var lastId = "0-0"
    private val job = scope.launch { run() }

    /** Зупинити consumer. */
    fun shutdown() {
        job.cancel()
    }

    private suspend fun run() {
        while (!job.isCancelled) {
            try {
                withContext(Dispatchers.IO) {
                    val conn = redis.syncPool.borrowObject()
                    try {
                        val commands = conn.sync()
                        // Простий XREAD без блокування - polling кожні 5с
                        // Lettuce 6: xread приймає XReadArgs.StreamOffset
                        val offset = XReadArgs.StreamOffset.from(streamKey, lastId)
                        @Suppress("UNCHECKED_CAST")
                        val results = commands.xread(offset) as List<StreamMessage<String, String>>

                        results?.forEach { message ->
                            lastId = message.id
                            // Конвертуємо Kotlin Map в java.util.Map
                            val body = HashMap<String, String>(message.body)
                            processMessage(message.id, body)
                        }
                    } finally {
                        redis.syncPool.returnObject(conn)
                    }
                }
                // Невелика пауза між опитами (polling)
                delay(5000)
            } catch (e: Exception) {
                logger.error("Error in event consumer loop", e)
                delay(1000)
            }
        }
    }

    private fun processMessage(messageId: String, body: HashMap<String, String>) {
        val type = body["type"] ?: return
        val payload = body["payload"] ?: return

        try {
            when (type) {
                "PostCreated" -> handlePostCreated(payload)
                "LikeAdded" -> handleLikeAdded(payload)
                "CommentAdded" -> handleCommentAdded(payload)
                "UserRegistered" -> handleUserRegistered(payload)
                "UserFollowed" -> handleUserFollowed(payload)
                "GroupJoined" -> handleGroupJoined(payload)
                "GroupPostCreated" -> handleGroupPostCreated(payload)
                "MessageSent" -> handleMessageSent(payload)
                "CollectionSaved" -> handleCollectionSaved(payload)
                "FrameAnalyzed" -> handleFrameAnalyzed(payload)
                "ReelCreated" -> handleReelCreated(payload)
                else -> logger.warn("Unknown event type: $type")
            }
            logger.debug("Processed event $type (ID: $messageId)")
        } catch (e: Exception) {
            logger.error("Failed to process event $type (ID: $messageId)", e)
        }
    }

    private fun handlePostCreated(payload: String) {
        val event = json.decodeFromString<DomainEvent.PostCreated>(payload)
        transaction {
            feedService.invalidateFeedCacheSync()
        }
    }

    private fun handleLikeAdded(payload: String) {
        val event = json.decodeFromString<DomainEvent.LikeAdded>(payload)
        transaction {
            when (event.targetType) {
                "post" -> {
                    // Оновлюємо like_count через entity (як у FeedService)
                    val post = PostEntity.findById(event.targetId)
                    post?.let { it.likeCount = it.likeCount + 1 }
                    feedService.invalidateFeedCacheSync()
                }
                "reel" -> {
                    // ReelTable не має likeCount — можемо додати пізніше
                }
                "comment" -> {
                    // CommentTable може мати likeCount пізніше
                }
            }
        }
    }

    private fun handleCommentAdded(payload: String) {
        val event = json.decodeFromString<DomainEvent.CommentAdded>(payload)
        transaction {
            feedService.invalidateFeedCacheSync()
        }
    }

    private fun handleUserRegistered(payload: String) {
        val event = json.decodeFromString<DomainEvent.UserRegistered>(payload)
        logger.info("New user registered: ${event.login} (id=${event.userId})")
    }

    private fun handleUserFollowed(payload: String) {
        val event = json.decodeFromString<DomainEvent.UserFollowed>(payload)
        transaction {
            feedService.invalidateFeedCacheSync()
        }
    }

    private fun handleGroupJoined(payload: String) {
        val event = json.decodeFromString<DomainEvent.GroupJoined>(payload)
        transaction {
            feedService.invalidateFeedCacheSync()
        }
    }

    private fun handleGroupPostCreated(payload: String) {
        val event = json.decodeFromString<DomainEvent.GroupPostCreated>(payload)
        transaction {
            feedService.invalidateFeedCacheSync()
        }
    }

    private fun handleMessageSent(payload: String) {
        val event = json.decodeFromString<DomainEvent.MessageSent>(payload)
        logger.debug("Message sent in chat ${event.chatId} by user ${event.senderId}")
    }

    private fun handleCollectionSaved(payload: String) {
        val event = json.decodeFromString<DomainEvent.CollectionSaved>(payload)
        logger.debug("Collection saved for user ${event.userId}: ${event.animeTitle}")
    }

    private fun handleFrameAnalyzed(payload: String) {
        val event = json.decodeFromString<DomainEvent.FrameAnalyzed>(payload)
        logger.debug("Frame analyzed for user ${event.userId}: ${event.animeTitle}")
    }

    private fun handleReelCreated(payload: String) {
        val event = json.decodeFromString<DomainEvent.ReelCreated>(payload)
        transaction {
            feedService.invalidateFeedCacheSync()
        }
    }

    companion object {
        /** Створити EventConsumer і запустити вказаному scope. */
        fun start(
            redis: RedisConfig,
            feedService: FeedService,
            chatService: dev.amps.backend.service.ChatService,
            groupService: dev.amps.backend.service.GroupService,
            profileService: ProfileService,
            animeCollectionService: dev.amps.backend.service.AnimeCollectionService,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ): EventConsumer {
            return EventConsumer(
                redis, feedService, chatService, groupService,
                profileService, animeCollectionService, scope
            )
        }
    }
}