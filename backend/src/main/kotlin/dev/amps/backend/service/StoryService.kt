package dev.amps.backend.service

import dev.amps.backend.config.AppConfig
import dev.amps.backend.model.ApiResponse
import dev.amps.backend.model.ProfileDto
import dev.amps.backend.model.StoryDto
import dev.amps.backend.model.StoryEntity
import dev.amps.backend.model.StoryTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 1.2.0: сторіс — медіа на 24 години.
 *
 * Сторіс — це файл у [MediaStorage] (папки photo/video, імена — UUID)
 * і запис у базі з expiresAt. Прострочені записи видаляються щоразу
 * при читанні списку — разом із файлом зі сховища: у списку прострочене
 * і так більше не показується, а диск не роздувається.
 *
 * Автор для кружечка береться через [ProfileService.ensureProfile] —
 * той самий спосіб, яким FeedService складає авторів постів.
 */
class StoryService(
    private val config: AppConfig,
    private val media: MediaStorage,
    private val profiles: ProfileService,
) {

    /** Створює сторіс після того, як роутинг поклав файл у сховище. */
    suspend fun create(uid: Int, kind: String, fileName: String): ApiResponse<StoryDto> =
        newSuspendedTransaction {
            val now = System.currentTimeMillis()
            val story = StoryEntity.new {
                userId = uid
                this.kind = kind
                this.fileName = fileName
                createdAt = now
                expiresAt = now + config.storyTtlMillis
            }
            profiles.ensureProfile(uid)
            ApiResponse.ok(dto(story))
        }

    /**
     * Живі сторіс усіх користувачів, новіші — першими. Прострочені
     * прибираються тут же: і запис, і файл зі сховища — том не роздувається.
     */
    suspend fun list(): List<StoryDto> {
        purgeExpired()
        return newSuspendedTransaction {
            StoryEntity.find { StoryTable.expiresAt greater System.currentTimeMillis() }
                .orderBy(StoryTable.createdAt to SortOrder.DESC)
                .map { dto(it) }
        }
    }

    /** Видаляє свою сторіс. Чужа або неіснуюча — false без помилки. */
    suspend fun delete(uid: Int, id: Int): Boolean = newSuspendedTransaction {
        val story = StoryEntity.find { StoryTable.id eq id }.firstOrNull()
            ?: return@newSuspendedTransaction false
        if (story.userId != uid) return@newSuspendedTransaction false
        runCatching { media.resolve(story.kind, story.fileName)?.delete() }
        story.delete()
        true
    }

    /**
     * Прибирає прострочені сторіс: і запис, і сам файл медіа.
     * Викликається при кожному читанні списку — дешево, бо таких
     * зазвичай одиниці, а Том медіа не роздувається.
     */
    fun purgeExpired() {
        val now = System.currentTimeMillis()
        val rows = transaction {
            StoryEntity.find { StoryTable.expiresAt less now }.toList()
        }
        for (story in rows) {
            runCatching { media.resolve(story.kind, story.fileName)?.delete() }
            transaction { story.delete() }
        }
    }

    private fun dto(story: StoryEntity): StoryDto {
        val profile = profiles.ensureProfile(story.userId)
        return StoryDto(
            id = story.id.value,
            author = profiles.profileOf(profile),
            kind = story.kind,
            url = "/media/${story.kind}/${story.fileName}",
            createdAt = story.createdAt,
            expiresAt = story.expiresAt,
        )
    }
}
