package dev.amps.backend.service

import java.util.concurrent.ConcurrentHashMap

/**
 * 1.6.2: трекер «друкує…» — в пам'яті, без таблиць. Учасник друкує в чат
 * → його клієнт шле /chats/typing (тротлінг 1 раз за 3 с), трекер
 * пам'ятає позначку 6 секунд; опитувач другого боку бачить true.
 * Одновузловий — для одного інстансу Railway достатньо.
 */
object TypingTracker {

    private const val TTL_MILLIS = 6_000L

    private val lastTyped = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Учасник друкує в [key] ("u:{peerId}" / "g:{groupId}"). */
    fun mark(key: String) {
        lastTyped[key] = System.currentTimeMillis()
    }

    /** Чи хтось друкував у [key] останні 6 секунд. */
    fun isTyping(key: String): Boolean {
        val stamp = lastTyped[key] ?: return false
        return System.currentTimeMillis() - stamp < TTL_MILLIS
    }
}
