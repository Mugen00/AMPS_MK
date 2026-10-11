package dev.amps.backend.service

import dev.amps.backend.model.XpEntity
import dev.amps.backend.model.XpTable
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 1.2.4: XP і рівні (1–100). Формула рівня — [levelFor]:
 * рівень n вимагає 100×(n−1) накопичених XP, рівень 100 — 9900 XP.
 * Титули рівнів зберігаються на клієнті (список українською), сервер
 * зберігає лише XP. Глобальний об'єкт, щоб не тягнути крізь DI:
 * XP — скромний побічний ефект будь-якої соціальної дії.
 */
object XpLedger {

    /** Бали за дії 1.2.4. */
    const val POST = 25
    const val COMMENT = 8
    const val LIKE_GIVEN = 2
    const val LIKE_RECEIVED = 3
    const val REPOST = 8
    const val POLL_CREATE = 15
    const val POLL_VOTE = 5
    const val ANIME_ADD = 10
    const val STORY = 10

    /** Рівень із накопиченого XP: 1 + xp/100, стеля 100. */
    fun levelFor(xp: Int): Int = (1 + xp / 100).coerceIn(1, 100)

    /** XP, потрібний для досягнення рівня [level] (від 2). */
    fun xpForLevel(level: Int): Int = (level - 1).coerceAtLeast(0) * 100

    fun award(userId: Int, points: Int) {
        if (points == 0) return
        transaction {
            val existing = XpEntity.find { XpTable.userId eq userId }.firstOrNull()
            if (existing == null) {
                XpEntity.new {
                    this.userId = userId
                    this.xp = points.coerceAtLeast(0)
                    this.updatedAt = System.currentTimeMillis()
                }
            } else {
                existing.xp = (existing.xp + points).coerceAtLeast(0)
                existing.updatedAt = System.currentTimeMillis()
            }
        }
    }

    /** XP користувача (0, якщо ще нічого не робив). */
    fun xpOf(userId: Int): Int = transaction {
        XpEntity.find { XpTable.userId eq userId }.firstOrNull()?.xp ?: 0
    }
}
