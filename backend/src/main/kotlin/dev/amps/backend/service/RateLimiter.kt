package dev.amps.backend.service

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 1.6.1: простий in-memory rate-limiter (ковзне вікно). Захищає від
 * спам-абузу: надсилання повідомлень (і фарм XP за них), створення
 * постів/рілсів, запити кодів підтвердження. Одновузловий — для
 * одного інстансу Railway цього досить; при масштабуванні переїде
 * на Redis з тим самим інтерфейсом.
 */
object RateLimiter {

    private data class Bucket(val hits: MutableList<Long>)

    private val buckets = ConcurrentHashMap<String, MutableList<Long>>()

    /**
     * Повертає true, якщо дію в поточному вікні можна виконати.
     * [max] разів за [windowMs] на ключ (зазвичай "дія:userId" або "дія:email").
     */
    fun allow(key: String, max: Int, windowMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val list = buckets.getOrPut(key) { Collections.synchronizedList(mutableListOf()) }
        synchronized(list) {
            // Прибираємо влучення поза вікном.
            list.removeAll { now - it > windowMs }
            if (list.size >= max) return false
            list.add(now)
            return true
        }
    }

    /** Тестам і адмінці: повне скидання. */
    fun reset() = buckets.clear()
}