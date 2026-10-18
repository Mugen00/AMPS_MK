package dev.amps.backend.service

import dev.amps.backend.config.RedisConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * 1.6.8: Redis-based Rate Limiter — Sliding Window Log алгоритм.
 *
 * Використовує sorted set (ZSET) з timestamp як score.
 * Ключ: "ratelimit:{namespace}:{identifier}"
 *
 * Приклад:
 * ```kotlin
 * val allowed = redisRateLimiter.tryAcquire("login", "ip:192.168.1.1", 5, 300) // 5 запитів за 5 хв
 * if (!allowed) throw RateLimitExceeded("Забагато спроб входу")
 * ```
 */
class RedisRateLimiter(private val redis: RedisConfig) {

    private val logger = LoggerFactory.getLogger("amps.backend.redisratelimit")

    data class Result(
        val allowed: Boolean,
        val remaining: Int,
        val retryAfterSeconds: Long?,
        val limit: Int,
        val windowSeconds: Int,
    )

    /**
     * Спроба отримати дозвіл.
     * @param namespace логічний простір (login, register, post, message, search, api)
     * @param identifier унікальний ідентифікатор (userId, IP, тощо)
     * @param maxRequests максимальна кількість запитів у вікні
     * @param windowSeconds розмір вікна в секундах
     */
    suspend fun tryAcquire(
        namespace: String,
        identifier: String,
        maxRequests: Int,
        windowSeconds: Int
    ): Result = withContext(Dispatchers.IO) {
        val key = "ratelimit:$namespace:$identifier"
        val now = Instant.now().toEpochMilli()
        val windowStart = now - windowSeconds * 1000L

        redis.syncPool.borrowObject().use { conn ->
            val sync = conn.sync()

            // 1. Видалити старі записи (за межами вікна)
            sync.zremrangebyscore(key, 0.0, windowStart.toDouble())

            // 2. Порахувати поточну кількість
            val currentCount = sync.zcard(key)

            if (currentCount < maxRequests) {
                // Додаємо поточний запит
                val member = "$now-${java.util.UUID.randomUUID()}"
                sync.zadd(key, now, member)
                sync.expire(key, Duration.ofSeconds(windowSeconds + 10L))

                Result(
                    allowed = true,
                    remaining = maxRequests - currentCount.toInt() - 1,
                    retryAfterSeconds = null,
                    limit = maxRequests,
                    windowSeconds = windowSeconds,
                )
            } else {
                val oldest = sync.zrange(key, 0, 0)
                val retryAfter = if (oldest.isNotEmpty()) {
                    val oldestScore = oldest[0].split("-")[0].toLongOrNull() ?: now
                    ((oldestScore + windowSeconds * 1000L - now) / 1000).coerceAtLeast(1)
                } else windowSeconds.toLong()

                Result(
                    allowed = false,
                    remaining = 0,
                    retryAfterSeconds = retryAfter,
                    limit = maxRequests,
                    windowSeconds = windowSeconds,
                )
            }
        }
    }

    /** Перевірити ліміт без збільшення лічильника. */
    suspend fun checkLimit(
        namespace: String,
        identifier: String,
        maxRequests: Int,
        windowSeconds: Int
    ): Result = withContext(Dispatchers.IO) {
        val key = "ratelimit:$namespace:$identifier"
        val now = Instant.now().toEpochMilli()
        val windowStart = now - windowSeconds * 1000L

        redis.syncPool.borrowObject().use { conn ->
            val sync = conn.sync()
            sync.zremrangebyscore(key, 0.0, windowStart.toDouble())
            val currentCount = sync.zcard(key)

            if (currentCount < maxRequests) {
                Result(true, maxRequests - currentCount.toInt(), null, maxRequests, windowSeconds)
            } else {
                val oldest = sync.zrange(key, 0, 0)
                val retryAfter = if (oldest.isNotEmpty()) {
                    val oldestScore = oldest[0].split("-")[0].toLongOrNull() ?: now
                    ((oldestScore + windowSeconds * 1000L - now) / 1000).coerceAtLeast(1)
                } else windowSeconds.toLong()
                Result(false, 0, retryAfter, maxRequests, windowSeconds)
            }
        }
    }

    /** Скинути ліміт для ідентифікатора. */
    suspend fun reset(namespace: String, identifier: String) = withContext(Dispatchers.IO) {
        val key = "ratelimit:$namespace:$identifier"
        redis.syncPool.borrowObject().use { conn ->
            conn.sync().del(key)
        }
    }

    /** Отримати заголовки для HTTP відповіді. */
    fun Result.toHeaders(): Map<String, String> = mapOf(
        "X-RateLimit-Limit" to limit.toString(),
        "X-RateLimit-Remaining" to remaining.toString(),
        "X-RateLimit-Reset" to (Instant.now().epochSecond + (retryAfterSeconds ?: windowSeconds.toLong())).toString(),
    ).plus(if (retryAfterSeconds != null) mapOf("Retry-After" to retryAfterSeconds.toString()) else emptyMap())
}

/**
 * 1.6.8: Предефініровані конфігурації rate limits для RedisRateLimiter.
 */
object RedisRateLimitConfig {
    val LOGIN = RedisRateLimitRule("login", max = 5, windowSec = 300)
    val REGISTER = RedisRateLimitRule("register", max = 3, windowSec = 3600)
    val REFRESH_TOKEN = RedisRateLimitRule("refresh", max = 10, windowSec = 300)
    val PASSWORD_RESET = RedisRateLimitRule("password_reset", max = 2, windowSec = 3600)

    val CREATE_POST = RedisRateLimitRule("create_post", max = 30, windowSec = 3600)
    val CREATE_COMMENT = RedisRateLimitRule("create_comment", max = 60, windowSec = 3600)
    val SEND_MESSAGE = RedisRateLimitRule("send_message", max = 100, windowSec = 300)
    val SEND_CHAT_PHOTO = RedisRateLimitRule("send_chat_photo", max = 20, windowSec = 300)

    val LIKE = RedisRateLimitRule("like", max = 200, windowSec = 300)
    val FOLLOW = RedisRateLimitRule("follow", max = 50, windowSec = 3600)
    val GROUP_JOIN = RedisRateLimitRule("group_join", max = 20, windowSec = 3600)
    val GROUP_INVITE = RedisRateLimitRule("group_invite", max = 10, windowSec = 3600)

    val SEARCH = RedisRateLimitRule("search", max = 30, windowSec = 60)
    val FRAME_SEARCH = RedisRateLimitRule("frame_search", max = 10, windowSec = 300)
    val COLLECTION_SAVE = RedisRateLimitRule("collection_save", max = 20, windowSec = 300)

    val GENERAL_API = RedisRateLimitRule("api", max = 1000, windowSec = 60)
}

data class RedisRateLimitRule(
    val namespace: String,
    val max: Int,
    val windowSec: Int
)