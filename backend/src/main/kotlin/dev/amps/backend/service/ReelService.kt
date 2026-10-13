package dev.amps.backend.service

import dev.amps.backend.model.ApiResponse
import dev.amps.backend.model.ProfileDto
import dev.amps.backend.model.ReelDto
import dev.amps.backend.model.ReelEntity
import dev.amps.backend.model.ReelLikeEntity
import dev.amps.backend.model.ReelLikeResponse
import dev.amps.backend.model.ReelLikeTable
import dev.amps.backend.model.ReelTable
import dev.amps.backend.model.ReelsResponse
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/**
 * 1.6.0: рілси — вертикальні відео 9:16. Окрема таблиця (не пости):
 * власна лента з автоплеєм, лайками, пагінацією offset'ом. Відео лежить
 * у спільному сховищі (KIND_VIDEO). Публікація рилса — XP за активність.
 */
class ReelService(private val profiles: ProfileService) {

    /** Публікація рилса: відео вже у сховищі (ім'я повертає роутинг). */
    suspend fun create(
        userId: Int,
        videoFile: String,
        caption: String,
    ): ApiResponse<Int> = newSuspendedTransaction {
        if (videoFile.isBlank()) {
            return@newSuspendedTransaction ApiResponse.fail("Відео не завантажено", "NO_VIDEO")
        }
        val reel = ReelEntity.new {
            this.userId = userId
            this.videoFile = videoFile
            this.caption = caption.trim().take(500)
            this.createdAt = System.currentTimeMillis()
        }
        XpLedger.award(userId, XpLedger.REEL)
        ApiResponse.ok(reel.id.value)
    }

    /** Лента рілсів: свіжі зверху, пагінація offset'ом. */
    suspend fun list(viewerId: Int, limit: Int, offset: Long): ApiResponse<ReelsResponse> =
        newSuspendedTransaction {
            val rows = ReelEntity.all()
                .orderBy(ReelTable.createdAt to SortOrder.DESC)
                .limit(limit, offset)
                .toList()
            ApiResponse.ok(
                ReelsResponse(rows.map { reel ->
                    dev.amps.backend.model.ReelDto(
                        id = reel.id.value,
                        author = profiles.profileOf(profiles.ensureProfile(reel.userId)),
                        videoUrl = "/media/video/${reel.videoFile}",
                        caption = reel.caption,
                        createdAt = reel.createdAt,
                        likeCount = likeCount(reel.id.value),
                        likedByMe = likedBy(reel.id.value, viewerId),
                    )
                })
            )
        }

    /** Лайк перемикається: повторне натискання знімає його. */
    suspend fun toggleLike(viewerId: Int, reelId: Int): ApiResponse<ReelLikeResponse> =
        newSuspendedTransaction {
            val reel = ReelEntity.findById(reelId)
                ?: return@newSuspendedTransaction ApiResponse.fail("Рілс не знайдено", "NO_REEL")
            val existing = ReelLikeEntity.find {
                (ReelLikeTable.reelId eq reelId) and (ReelLikeTable.userId eq viewerId)
            }.firstOrNull()
            if (existing != null) {
                existing.delete()
            } else {
                ReelLikeEntity.new {
                    this.reelId = reelId
                    this.userId = viewerId
                }
                XpLedger.award(reel.userId, XpLedger.LIKE_RECEIVED)
            }
            ApiResponse.ok(
                ReelLikeResponse(
                    liked = existing == null,
                    likeCount = likeCount(reelId),
                )
            )
        }

    /** Видалити свій рилс (власник — автор). */
    suspend fun delete(reelId: Int, requesterId: Int): ApiResponse<Boolean> = newSuspendedTransaction {
        val reel = ReelEntity.findById(reelId)
            ?: return@newSuspendedTransaction ApiResponse.fail("Рілс не знайдено", "NO_REEL")
        if (reel.userId != requesterId) {
            return@newSuspendedTransaction ApiResponse.fail(
                "Видаляти можна тільки свій рилс", "NOT_OWNER",
            )
        }
        reel.delete()
        ApiResponse.ok(true)
    }

    private fun likeCount(reelId: Int): Int =
        ReelLikeEntity.find { ReelLikeTable.reelId eq reelId }.count().toInt()

    private fun likedBy(reelId: Int, userId: Int): Boolean = ReelLikeEntity.find {
        (ReelLikeTable.reelId eq reelId) and (ReelLikeTable.userId eq userId)
    }.firstOrNull() != null
}
