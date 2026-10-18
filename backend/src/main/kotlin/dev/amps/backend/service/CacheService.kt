package dev.amps.backend.service

import dev.amps.backend.config.RedisConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * 1.6.8: Універсальний кеш на Redis з TTL та авто-серіалізацією JSON.
 */
class CacheService(private val redis: RedisConfig) {

    private val logger = LoggerFactory.getLogger("amps.backend.cache")
    private val json = Json { ignoreUnknownKeys = true }

    /** Отримати значення або обчислити, кешувати, повернути. */
    suspend fun <T : Any> getOrPut(
        key: String,
        serializer: KSerializer<T>,
        ttlSeconds: Int = 300,
        namespace: String = "default",
        block: suspend () -> T
    ): T = withContext(Dispatchers.IO) {
        val fullKey = "cache:$namespace:$key"
        redis.syncPool.borrowObject().use { conn ->
            val value = conn.sync().get(fullKey)
            if (value != null) {
                try {
                    return@withContext json.decodeFromString(serializer, value)
                } catch (e: Exception) {
                    logger.warn("Кеш-повреження для $fullKey: ${e.message}, переобчислюємо")
                }
            }
            val fresh = block()
            val serialized = json.encodeToString(serializer, fresh)
            conn.sync().setex(fullKey, ttlSeconds.toLong(), serialized)
            fresh
        }
    }

    /** Отримати без обчислення (null якщо немає). */
    suspend fun <T : Any> get(
        key: String,
        serializer: KSerializer<T>,
        namespace: String = "default"
    ): T? = withContext(Dispatchers.IO) {
        val fullKey = "cache:$namespace:$key"
        redis.syncPool.borrowObject().use { conn ->
            val value = conn.sync().get(fullKey)
            value?.let { json.decodeFromString(serializer, it) }
        }
    }

    /** Покласти значення з TTL. */
    suspend fun <T : Any> put(
        key: String,
        value: T,
        serializer: KSerializer<T>,
        ttlSeconds: Int = 300,
        namespace: String = "default"
    ) = withContext(Dispatchers.IO) {
        val fullKey = "cache:$namespace:$key"
        val serialized = json.encodeToString(serializer, value)
        redis.syncPool.borrowObject().use { conn ->
            conn.sync().setex(fullKey, ttlSeconds.toLong(), serialized)
        }
    }

    /** Інвалідувати конкретний ключ. */
    suspend fun invalidate(key: String, namespace: String = "default") = withContext(Dispatchers.IO) {
        val fullKey = "cache:$namespace:$key"
        redis.syncPool.borrowObject().use { conn ->
            conn.sync().del(fullKey)
        }
    }

    /** Інвалідувати всі ключі за префіксом namespace (KEYS + DEL - просто для малих обсягів). */
    suspend fun invalidateNamespace(namespace: String) = withContext(Dispatchers.IO) {
        val pattern = "cache:$namespace:*"
        redis.syncPool.borrowObject().use { conn ->
            val keys = conn.sync().keys(pattern)
            if (keys.isNotEmpty()) {
                conn.sync().del(*keys.toTypedArray())
            }
        }
    }

    /** Синхронна версія invalidateNamespace для виклику з не-suspend контексту. */
    fun invalidateNamespaceSync(namespace: String) {
        val pattern = "cache:$namespace:*"
        redis.syncPool.borrowObject().use { conn ->
            val keys = conn.sync().keys(pattern)
            if (keys.isNotEmpty()) {
                conn.sync().del(*keys.toTypedArray())
            }
        }
    }

    /** Очистити весь кеш (негайно, обережно!). */
    suspend fun flushAll() = withContext(Dispatchers.IO) {
        redis.syncPool.borrowObject().use { conn ->
            conn.sync().flushall()
        }
    }
}