package dev.amps.backend.service

import dev.amps.backend.model.PollDto
import dev.amps.backend.model.PollEntity
import dev.amps.backend.model.PollOptionDto
import dev.amps.backend.model.PollOptionEntity
import dev.amps.backend.model.PollOptionTable
import dev.amps.backend.model.PollTable
import dev.amps.backend.model.PollVoteEntity
import dev.amps.backend.model.PollVoteTable
import dev.amps.backend.model.PostEntity
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 1.2.3: опитування під постами. Один голос на користувача; повторний
 * голос за інший варіант перезаписується, за той самий — знімається
 * (toggle-семантика, як лайк). Результати відкриті всім, голосування —
 * тільки аутентифікованим.
 *
 * Параметри методів свідомо названі інакше за властивості таблиць:
 * всередині Exposed new{} локальні параметри тінять поля сутності.
 */
class PollService {

    /** 2–4 варіанти, кожен непорожній до 100 символів. */
    fun validateOptions(raw: List<String>): List<String>? {
        val cleaned = raw.map { it.trim().take(100) }.filter { it.isNotBlank() }
        return if (cleaned.size in 2..4) cleaned else null
    }

    /** Створює опитування для свого поста; запитання необов'язкове. */
    fun createForPost(
        uid: Int,
        postId: Int,
        questionText: String?,
        optionTexts: List<String>,
    ): Int? = transaction {
        val post = PostEntity.findById(postId) ?: return@transaction null
        if (post.userId != uid) return@transaction null
        val cleaned = optionTexts.map { it.trim().take(100) }.filter { it.isNotBlank() }
        if (cleaned.size !in 2..4) return@transaction null
        val poll = PollEntity.new {
            this.postId = postId
            this.question = questionText?.trim()?.takeIf { it.isNotBlank() }?.take(200)
        }
        cleaned.forEachIndexed { index, text ->
            PollOptionEntity.new {
                this.pollId = poll.id.value
                this.text = text
                this.position = index
            }
        }
        // 1.2.4: XP за створення опитування.
        dev.amps.backend.service.XpLedger.award(uid, dev.amps.backend.service.XpLedger.POLL_CREATE)
        poll.id.value
    }

    /**
     * Голосування: не голосував → голосує; голосував за інший —
     * перемикає; за той самий — знімає голос.
     */
    fun vote(uid: Int, pollId: Int, optionId: Int): PollDto? = transaction {
        val poll = PollEntity.findById(pollId) ?: return@transaction null
        val option = PollOptionEntity.find {
            (PollOptionTable.pollId eq pollId) and (PollOptionTable.id eq optionId)
        }.firstOrNull() ?: return@transaction null
        val existing = PollVoteEntity.find {
            (PollVoteTable.pollId eq pollId) and (PollVoteTable.userId eq uid)
        }.firstOrNull()
        when {
            existing == null -> {
                PollVoteEntity.new {
                    this.pollId = pollId
                    this.userId = uid
                    this.optionId = optionId
                }
                // 1.2.4: XP за голос.
                dev.amps.backend.service.XpLedger.award(uid, dev.amps.backend.service.XpLedger.POLL_VOTE)
            }
            existing.optionId == optionId -> existing.delete()
            else -> existing.optionId = optionId
        }
        toDto(poll, uid)
    }

    /** Опитування поста з лічильниками і голосом переглядача. */
    fun forPost(postId: Int, viewerId: Int): PollDto? = transaction {
        val poll = PollEntity.find { PollTable.postId eq postId }.firstOrNull()
            ?: return@transaction null
        toDto(poll, viewerId)
    }

    private fun toDto(poll: PollEntity, viewerId: Int): PollDto {
        val pollId = poll.id.value
        val options = PollOptionEntity
            .find { PollOptionTable.pollId eq pollId }
            .orderBy(PollOptionTable.position to SortOrder.ASC)
            .toList()
        val votesByOption = PollVoteEntity
            .find { PollVoteTable.pollId eq pollId }
            .toList()
            .groupBy { it.optionId }
        val myVote = PollVoteEntity.find {
            (PollVoteTable.pollId eq pollId) and (PollVoteTable.userId eq viewerId)
        }.firstOrNull()?.optionId
        return PollDto(
            pollId = pollId,
            question = poll.question,
            options = options.map { opt ->
                PollOptionDto(
                    id = opt.id.value,
                    text = opt.text,
                    votes = votesByOption[opt.id.value]?.size ?: 0,
                )
            },
            myOptionId = myVote,
        )
    }
}
