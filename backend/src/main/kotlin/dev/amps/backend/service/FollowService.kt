package dev.amps.backend.service

import dev.amps.backend.model.FollowEntity
import dev.amps.backend.model.FollowTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 1.2.2: підписки. Пара (хто, на кого) унікальна, як і лайки;
 * підписатися на себе не можна.
 */
class FollowService {

    /**
     * Toggle-семантика, як у лайків: підписаний → відписує,
     * не підписаний → підписує. Повертає (підписаний?, кількість підписників цілі).
     * null — ціль неіснуюча або це сам себе.
     */
    fun toggle(uid: Int, targetId: Int): Pair<Boolean, Int>? = transaction {
        if (uid == targetId) return@transaction null
        dev.amps.backend.model.UserEntity.findById(targetId) ?: return@transaction null
        val existing = FollowEntity
            .find { (FollowTable.followerId eq uid) and (FollowTable.followedId eq targetId) }
            .firstOrNull()
        if (existing != null) {
            existing.delete()
        } else {
            FollowEntity.new {
                followerId = uid
                followedId = targetId
                createdAt = System.currentTimeMillis()
            }
        }
        val followers = FollowEntity
            .find { FollowTable.followedId eq targetId }
            .count()
            .toInt()
        (existing == null) to followers
    }

    /** Чи підписаний [uid] на [targetId]. */
    fun isFollowing(uid: Int, targetId: Int): Boolean = transaction {
        FollowEntity
            .find { (FollowTable.followerId eq uid) and (FollowTable.followedId eq targetId) }
            .firstOrNull() != null
    }

    /** Список userId, на яких підписаний [uid] (свіжі зверху). */
    fun followingIds(uid: Int): List<Int> = transaction {
        FollowEntity
            .find { FollowTable.followerId eq uid }
            .orderBy(FollowTable.createdAt to SortOrder.DESC)
            .map { it.followedId }
    }

    /** Кількість підписників у користувача. */
    fun followerCount(userId: Int): Int = transaction {
        FollowEntity
            .find { FollowTable.followedId eq userId }
            .count()
            .toInt()
    }
}
