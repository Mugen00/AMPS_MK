package dev.amps.backend.service

import dev.amps.backend.config.RedisConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * 1.6.8: Domain Events — події предметної області для асинхронної обробки.
 */
@Serializable
sealed interface DomainEvent {
    @Serializable
    data class PostCreated(
        val postId: Int,
        val authorId: Int,
        val groupId: Int?,
        val text: String,
        val tags: List<String> = emptyList(),
        val timestamp: Long = System.currentTimeMillis()
    ) : DomainEvent

    @Serializable
    data class LikeAdded(
        val targetType: String,
        val targetId: Int,
        val userId: Int,
        val timestamp: Long = System.currentTimeMillis()
    ) : DomainEvent

    @Serializable
    data class CommentAdded(
        val commentId: Int,
        val postId: Int,
        val authorId: Int,
        val text: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : DomainEvent

    @Serializable
    data class UserRegistered(
        val userId: Int,
        val login: String,
        val email: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : DomainEvent

    @Serializable
    data class UserFollowed(
        val followerId: Int,
        val followingId: Int,
        val timestamp: Long = System.currentTimeMillis()
    ) : DomainEvent

    @Serializable
    data class GroupJoined(
        val groupId: Int,
        val userId: Int,
        val role: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : DomainEvent

    @Serializable
    data class GroupPostCreated(
        val postId: Int,
        val groupId: Int,
        val authorId: Int,
        val text: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : DomainEvent

    @Serializable
    data class MessageSent(
        val chatId: Int,
        val messageId: Int,
        val senderId: Int,
        val text: String,
        val isGroup: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    ) : DomainEvent

    @Serializable
    data class CollectionSaved(
        val userId: Int,
        val animeTitle: String,
        val characterName: String,
        val note: String?,
        val timestamp: Long = System.currentTimeMillis()
    ) : DomainEvent

    @Serializable
    data class FrameAnalyzed(
        val frameId: Int,
        val userId: Int,
        val animeTitle: String?,
        val characterName: String?,
        val timestamp: Long = System.currentTimeMillis()
    ) : DomainEvent

    @Serializable
    data class ReelCreated(
        val reelId: Int,
        val authorId: Int,
        val caption: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : DomainEvent
}

/**
 * 1.6.8: Publisher для Domain Events в Redis Stream.
 */
class EventPublisher(private val redis: RedisConfig) {

    private val logger = LoggerFactory.getLogger("amps.backend.events.publish")
    private val json = Json { ignoreUnknownKeys = true }
    private val streamKey = "events:domain"

    /** Опублікувати подію в stream. Повертає stream entry ID. */
    suspend fun publish(event: DomainEvent): String = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(event)
        val fields = mapOf(
            "type" to event::class.simpleName,
            "payload" to payload,
            "timestamp" to System.currentTimeMillis().toString(),
        )
        redis.syncPool.borrowObject().use { conn ->
            val id = conn.sync().xadd(streamKey, fields)
            logger.trace("Published event ${event::class.simpleName} with ID $id")
            id
        }
    }
}