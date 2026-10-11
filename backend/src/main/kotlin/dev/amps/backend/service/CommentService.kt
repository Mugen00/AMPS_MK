package dev.amps.backend.service

import dev.amps.backend.model.CommentDto
import dev.amps.backend.model.CommentEntity
import dev.amps.backend.model.CommentTable
import dev.amps.backend.model.ProfileDto
import dev.amps.backend.model.PostEntity
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 1.2.2: коментарі під постами. Читання — публічне (як і стрічка),
 * запис — лише з токеном; видалення — тільки свій коментар.
 *
 * profileOf/ensureProfile беруться з [ProfileService] — той самий код,
 * яким FeedService будує авторів постів, тому автори коментарів
 * гарантовано мають ті самі аватарки й імена.
 */
class CommentService(private val profiles: ProfileService) {

    /** Коментарі поста, свіжі зверху. */
    fun list(postId: Int): List<CommentDto> = transaction {
        CommentEntity
            .find { CommentTable.postId eq postId }
            .orderBy(CommentTable.createdAt to SortOrder.DESC)
            .map { c ->
                CommentDto(
                    id = c.id.value,
                    author = profiles.profileOf(profiles.ensureProfile(c.userId)),
                    postId = c.postId,
                    text = c.text,
                    createdAt = c.createdAt,
                )
            }
    }

    /** Додати коментар; порожній текст і неіснуючий пост не допускаються. */
    fun add(uid: Int, postId: Int, text: String): Int? {
        val trimmed = text.trim().take(1000)
        if (trimmed.isEmpty()) return null
        return transaction {
            PostEntity.findById(postId) ?: return@transaction null
            CommentEntity.new {
                userId = uid
                this.postId = postId
                this.text = trimmed
                createdAt = System.currentTimeMillis()
            }.id.value
        }
    }

    /** Видалити свій коментар; чужий — false. */
    fun delete(uid: Int, commentId: Int): Boolean = transaction {
        val c = CommentEntity.findById(commentId) ?: return@transaction false
        if (c.userId != uid) return@transaction false
        c.delete()
        true
    }

    /** Кількість коментарів поста — для бейджа на картці. */
    fun count(postId: Int): Int = transaction {
        CommentEntity.find { CommentTable.postId eq postId }.count().toInt()
    }
}
