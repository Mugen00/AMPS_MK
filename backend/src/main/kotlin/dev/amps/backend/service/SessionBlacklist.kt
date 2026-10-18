package dev.amps.backend.service

import dev.amps.backend.config.RedisConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * 1.6.8: Чорний список JWT токенів (revocation).
 *
 * Використовується для:
 * - Logout (access token в blacklist до закінчення TTL)
 * - Password change (всі токени користувача)
 * - Ban/Deactivate user
 * - Security events (suspicious activity)
 *
 * Ключ: "blacklist:jwt:{jti}" або "blacklist:user:{userId}:*" (pattern для mas-revocation)
 * TTL = час життя access токена (7 днів) або refresh токена (365 днів)
 */
class SessionBlacklist(private val redis: RedisConfig) {

    private val logger = LoggerFactory.getLogger("amps.backend.blacklist")

    companion object {
        const val ACCESS_TTL_DAYS = 7
        const val REFRESH_TTL_DAYS = 365
    }

    /** Додати JWT ID (jti) в чорний список з TTL. */
    suspend fun revoke(jti: String, ttlDays: Int = ACCESS_TTL_DAYS) = withContext(Dispatchers.IO) {
        val key = "blacklist:jwt:$jti"
        redis.syncPool.borrowObject().use { conn ->
            conn.sync().setex(key, ttlDays * 24L * 3600, "1")
        }
    }

    /** Додати access+refresh пару (jti + refresh token hash). */
    suspend fun revokePair(accessJti: String, refreshTokenHash: String) = withContext(Dispatchers.IO) {
        redis.syncPool.borrowObject().use { conn ->
            val sync = conn.sync()
            sync.setex("blacklist:jwt:$accessJti", ACCESS_TTL_DAYS * 24L * 3600, "1")
            sync.setex("blacklist:refresh:$refreshTokenHash", REFRESH_TTL_DAYS * 24L * 3600, "1")
        }
    }

    /** Перевірити чи токен у чорному списку. */
    suspend fun isRevoked(jti: String): Boolean = withContext(Dispatchers.IO) {
        val key = "blacklist:jwt:$jti"
        redis.syncPool.borrowObject().use { conn ->
            conn.sync().exists(key) == 1L
        }
    }

    /** Перевірити refresh token. */
    suspend fun isRefreshRevoked(refreshTokenHash: String): Boolean = withContext(Dispatchers.IO) {
        val key = "blacklist:refresh:$refreshTokenHash"
        redis.syncPool.borrowObject().use { conn ->
            conn.sync().exists(key) == 1L
        }
    }

    /** Відкликати ВСІ токени користувача (password change, ban). Використовує user:index для mas-revocation. */
    suspend fun revokeAllUserTokens(userId: Int) = withContext(Dispatchers.IO) {
        val key = "blacklist:user:$userId"
        val revokedAt = Instant.now().toEpochMilli()
        redis.syncPool.borrowObject().use { conn ->
            // Зберігаємо timestamp масово-відклику для користувача
            conn.sync().setex(key, REFRESH_TTL_DAYS * 24L * 3600, revokedAt.toString())
        }
    }

    /** Перевірити чи токен користувача відкликано масово (після password change/ban). */
    suspend fun isUserRevoked(userId: Int, tokenIssuedAt: Long): Boolean = withContext(Dispatchers.IO) {
        val key = "blacklist:user:$userId"
        redis.syncPool.borrowObject().use { conn ->
            val revokedAtStr = conn.sync().get(key)
            revokedAtStr?.toLongOrNull()?.let { revokedAt ->
                tokenIssuedAt < revokedAt
            } ?: false
        }
    }

    /** Очистити відкликані токени користувача (unban, restore). */
    suspend fun clearUserRevocation(userId: Int) = withContext(Dispatchers.IO) {
        val key = "blacklist:user:$userId"
        redis.syncPool.borrowObject().use { conn ->
            conn.sync().del(key)
        }
    }
}