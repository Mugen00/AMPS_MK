package dev.amps.backend.config

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.StringCodec
import io.lettuce.core.support.ConnectionPoolSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.pool2.impl.GenericObjectPool
import org.apache.commons.pool2.impl.GenericObjectPoolConfig
import org.slf4j.LoggerFactory

/**
 * Redis конфігурація для Railway Redis plugin.
 * Очікує змінну середовища REDIS_URL (наприклад: redis://user:pass@host:port).
 * Якщо не задано — створює клієнт без підключення (для тестів/дев).
 */
class RedisConfig(private val config: AppConfig) {

    private val logger = LoggerFactory.getLogger("amps.backend.redis")

    /** Клієнт Lettuce (thread-safe, reusable). */
    val client: RedisClient = createClient()

    /** Пулінг з'єднань для синхронного API. */
    val syncPool: GenericObjectPool<StatefulRedisConnection<String, String>> = createSyncPool()

    /** Асинхронне з'єднання (для pub/sub, streams). */
    val asyncConnection: StatefulRedisConnection<String, String> = client.connect(StringCodec.UTF8).also { conn ->
        conn.async() // ініціалізуємо async API
    }

    private fun createClient(): RedisClient {
        val url = config.redisUrl ?: return RedisClient.create(RedisURI.Builder.redis("localhost").withPort(6379).build())
        return try {
            RedisClient.create(RedisURI.create(url))
        } catch (e: Exception) {
            logger.warn("Не вдалося створити Redis клієнт з REDIS_URL: ${e.message}. Використовуємо локальний.")
            RedisClient.create(RedisURI.Builder.redis("localhost").withPort(6379).build())
        }
    }

    private fun createSyncPool(): GenericObjectPool<StatefulRedisConnection<String, String>> {
        val poolConfig = GenericObjectPoolConfig<StatefulRedisConnection<String, String>>().apply {
            maxTotal = 20
            maxIdle = 10
            minIdle = 2
            testOnBorrow = true
            testOnReturn = false
        }
        return ConnectionPoolSupport.createGenericObjectPool(
            { client.connect(StringCodec.UTF8) },
            poolConfig
        )
    }

    /** Отримати синхронне з'єднання з пулу (try-with-resources паттерн). */
    suspend fun <T> withSyncConnection(block: (StatefulRedisConnection<String, String>) -> T): T =
        withContext(Dispatchers.IO) {
            val conn = syncPool.borrowObject()
            try {
                block(conn)
            } finally {
                syncPool.returnObject(conn)
            }
        }

    /** Закрити всі ресурси (викликати при shutdown). */
    fun shutdown() {
        logger.info("Закриваємо Redis ресурси...")
        syncPool.close()
        asyncConnection.close()
        client.shutdown()
    }
}