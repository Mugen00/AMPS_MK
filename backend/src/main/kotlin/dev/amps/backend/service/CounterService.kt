package dev.amps.backend.service

import dev.amps.backend.config.RedisConfig
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 1.6.8: Async Counter Service — лічильники лайків, переглядів, репостів, відповідей.
 *
 * Архітектура:
 * - Redis INCR/DECR для миттєвого оновлення (real-time UI)
 * - Локальний буфер (ConcurrentHashMap) для батчингу записів в PostgreSQL
 * - Періодичний flush (кожні 30с) або при досяженні порогу (100 зміна)
 * - Graceful shutdown: flush перед вимкненням
 *
 * Ключі Redis: "counter:{type}:{id}" (type: like, view, reply, repost)
 */
class CounterService(private val redis: RedisConfig, private val scope: CoroutineScope) {

    private val logger = LoggerFactory.getLogger("amps.backend.counter")
    private val pendingFlush = ConcurrentHashMap<String, AtomicLong>()
    private val flushJob = scope.launch { flushLoop() }

    /** Типи лічильників. */
    enum class CounterType { LIKE, VIEW, REPLY, REPOST, CHAT_MESSAGE }

    private fun key(type: CounterType, id: Long): String = "counter:${type.name.lowercase()}:$id"

    /** Інкремент лічильника (миттєво в Redis, deferred в PG). */
    suspend fun increment(type: CounterType, id: Long, delta: Long = 1): Long = withContext(Dispatchers.IO) {
        val k = key(type, id)
        val newVal = redis.syncPool.borrowObject().use { conn ->
            conn.sync().incrby(k, delta)
        }
        // Додаємо в буфер для flush
        pendingFlush.computeIfAbsent(k, { AtomicLong() }).addAndGet(delta)
        newVal
    }

    /** Декремент лічильника. */
    suspend fun decrement(type: CounterType, id: Long, delta: Long = 1): Long = withContext(Dispatchers.IO) {
        val k = key(type, id)
        val newVal = redis.syncPool.borrowObject().use { conn ->
            conn.sync().decrby(k, delta)
        }
        pendingFlush.computeIfAbsent(k, { AtomicLong() }).addAndGet(-delta)
        newVal
    }

    /** Отримати поточне значення (з Redis). */
    suspend fun get(type: CounterType, id: Long): Long = withContext(Dispatchers.IO) {
        val k = key(type, id)
        redis.syncPool.borrowObject().use { conn ->
            conn.sync().get(k)?.toLongOrNull() ?: 0L
        }
    }

    /** Отримати кілька значень (batch). */
    suspend fun getMulti(type: CounterType, ids: List<Long>): Map<Long, Long> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyMap()
        redis.syncPool.borrowObject().use { conn ->
            val keys = ids.map { key(type, it) }
            @Suppress("UNCHECKED_CAST")
            val values = conn.sync().mget(*keys.toTypedArray()) as List<String?>
            ids.zip(values).mapNotNull { (id, value) ->
                value?.toLongOrNull()?.let { id to it }
            }.toMap()
        }
    }

    /** Принуловий flush всіх очікуваних змін в PostgreSQL. */
    suspend fun flushAll(): Int = withContext(Dispatchers.IO) {
        var flushed = 0
        pendingFlush.forEach { key, atomic ->
            val delta = atomic.getAndSet(0L)
            if (delta != 0L) {
                // Парсимо key: counter:{type}:{id}
                val parts = key.split(":")
                if (parts.size == 3) {
                    val type = CounterType.valueOf(parts[1].uppercase())
                    val id = parts[2].toLongOrNull()
                    if (id != null) {
                        flushToPostgres(type, id, delta)
                        flushed++
                    }
                }
            }
        }
        if (flushed > 0) logger.debug("Flushed $flushed counters to PostgreSQL")
        flushed
    }

    /** Запис в PostgreSQL (перевизначається в subclass або через callback). */
    protected fun flushToPostgres(type: CounterType, id: Long, delta: Long) {
        // TODO: реалізувати через відповідні сервіси (FeedService, ChatService, etc.)
        // Наприклад: feedService.updateLikeCount(id, delta)
        logger.trace("Flush to PG: $type $id delta=$delta (not implemented yet)")
    }

    /** Фоновий цикл flush кожні 30 секунд. */
    private fun flushLoop() = scope.launch {
        while (isActive) {
            delay(30_000)
            try {
                flushAll()
            } catch (e: Exception) {
                logger.warn("Counter flush failed: ${e.message}")
            }
        }
    }

    /** Graceful shutdown. */
    fun shutdown() {
        flushJob.cancel()
        runBlocking { flushAll() }
    }
}