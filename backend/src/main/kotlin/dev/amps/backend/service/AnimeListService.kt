package dev.amps.backend.service

import dev.amps.backend.model.AnimeListDto
import dev.amps.backend.model.AnimeListEntity
import dev.amps.backend.model.AnimeListTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 1.2.2: аніме-список акаунта (дивлюсь / планую / переглянуто).
 * Тайтл ідентифікується anilistId — тоді нагадування про серії
 * можна перевіряти прямо в AniList.
 */
class AnimeListService {

    /** Свій список; найсвіжіші зверху. */
    fun list(uid: Int): List<AnimeListDto> = transaction {
        AnimeListEntity
            .find { dev.amps.backend.model.AnimeListTable.userId eq uid }
            .orderBy(dev.amps.backend.model.AnimeListTable.updatedAt to SortOrder.DESC)
            .map(::toDto)
    }

    /**
     * Додати або оновити тайтл: статус/нагадування перезаписуються,
     * дублікат (userId, anilistId) не створюється.
     */
    fun upsert(
        uid: Int,
        anilistId: Int,
        animeTitle: String,
        cover: String,
        animeStatus: String,
        withReminder: Boolean,
    ): Int? {
        if (anilistId <= 0) return null
        val cleanStatus = animeStatus.trim().lowercase()
        if (cleanStatus !in listOf("watching", "planned", "watched")) return null
        return transaction {
            val existing = dev.amps.backend.model.AnimeListEntity
                .find {
                    (dev.amps.backend.model.AnimeListTable.userId eq uid) and
                        (dev.amps.backend.model.AnimeListTable.anilistId eq anilistId)
                }
                .firstOrNull()
            if (existing != null) {
                existing.title = animeTitle.take(250)
                if (cover.isNotBlank()) existing.coverUrl = cover.take(500)
                existing.status = animeStatus
                existing.reminder = withReminder
                existing.updatedAt = System.currentTimeMillis()
                existing.id.value
            } else {
                dev.amps.backend.model.AnimeListEntity.new {
                    userId = uid
                    this.anilistId = anilistId
                    title = animeTitle.take(250)
                    coverUrl = cover.take(500)
                    status = cleanStatus.take(16)
                    reminder = withReminder
                    updatedAt = System.currentTimeMillis()
                }.id.value
            }
        }
    }

    /** Прибрати тайтл зі свого списку. */
    fun delete(uid: Int, anilistId: Int): Boolean = transaction {
        val row = dev.amps.backend.model.AnimeListEntity
            .find {
                (dev.amps.backend.model.AnimeListTable.userId eq uid) and
                    (dev.amps.backend.model.AnimeListTable.anilistId eq anilistId)
            }
            .firstOrNull() ?: return@transaction false
        row.delete()
        true
    }

    /** Тільки тайтли з нагадуваннями (для воркера). */
    fun reminders(userId: Int): List<AnimeListDto> = transaction {
        dev.amps.backend.model.AnimeListEntity
            .find {
                (dev.amps.backend.model.AnimeListTable.userId eq userId) and
                    (dev.amps.backend.model.AnimeListTable.reminder eq true)
            }
            .map(::toDto)
    }

    private fun toDto(e: dev.amps.backend.model.AnimeListEntity): AnimeListDto = AnimeListDto(
        anilistId = e.anilistId,
        title = e.title,
        coverUrl = e.coverUrl,
        status = e.status,
        reminder = e.reminder,
        updatedAt = e.updatedAt,
    )
}
