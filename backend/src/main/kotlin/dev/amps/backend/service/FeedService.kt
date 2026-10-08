package dev.amps.backend.service

import dev.amps.backend.model.ApiResponse
import dev.amps.backend.model.ApiResponse.Companion.ok
import dev.amps.backend.model.FeedItemDto
import dev.amps.backend.model.FeedResponse
import dev.amps.backend.model.LikeEntity
import dev.amps.backend.model.LikeTable
import dev.amps.backend.model.LikeToggleResponse
import dev.amps.backend.model.MediaDto
import dev.amps.backend.model.PostCreatedResponse
import dev.amps.backend.model.PostDto
import dev.amps.backend.model.PostEntity
import dev.amps.backend.model.PostMediaEntity
import dev.amps.backend.model.PostMediaTable
import dev.amps.backend.model.PostTable
import dev.amps.backend.model.RepostEntity
import dev.amps.backend.model.RepostResponse
import dev.amps.backend.model.RepostTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/**
 * 1.1.2: стрічка спільноти — пости, лайки, репости.
 *
 * Лайк і репост перемикаються: повторне натискання прибирає їх. Репост —
 * внутрішній: чужий пост з'являється у стрічці окремим рядком із позначкою
 * «репост від X», і рахунок репостів видно всім. Пост існує, доки не
 * видалено: авто-видалення у 1.1.2 немає, на кожному пості показується
 * дата публікації.
 *
 * Медіа зберігаються файлами через [MediaStorage]; у базі лише імена.
 */
class FeedService(
    private val media: MediaStorage,
    private val profiles: ProfileService,
) {

    /**
     * Створює пост. Медіа до цього моменту вже лежать у сховищі
     * (роутинг зберігає файли до виклику) — сюди приходять пари
     * «вид вкладення — ім'я файлу».
     */
    suspend fun createPost(
        userId: Int,
        text: String,
        media: List<Pair<String, String>>,
    ): ApiResponse<PostCreatedResponse> = newSuspendedTransaction {
        if (text.isBlank() && media.isEmpty()) {
            return@newSuspendedTransaction ApiResponse.fail<PostCreatedResponse>(
                "Пост не може бути порожнім", "EMPTY_POST")
        }
        val post = PostEntity.new {
            this.userId = userId
            this.text = text.trim().take(1000)
            this.createdAt = System.currentTimeMillis()
        }
        media.forEach { (kind, fileName) ->
            PostMediaEntity.new {
                this.postId = post.id.value
                this.kind = kind
                this.fileName = fileName
            }
        }
        // Профіль автора створюється разом із першим постом.
        profiles.ensureProfile(userId)
        ApiResponse.ok(PostCreatedResponse(postId = post.id.value))
    }

    /** Стрічка: пости та репости впереміш, свіжі зверху, зі зсувом для пагінації. */
    suspend fun feed(viewerId: Int, limit: Int, offset: Long): ApiResponse<FeedResponse> =
        newSuspendedTransaction {
            // Охоплюємо на третину більше записів, ніж потрібно: частина
            // піде на злиття з репостами, і відрізання робиться після сортування.
            val fetch = (limit.toLong() * 2 + offset + 32L).coerceAtMost(Int.MAX_VALUE.toLong())
            val posts = PostEntity.all()
                .orderBy(PostTable.createdAt to SortOrder.DESC)
                .limit(fetch.toInt(), offset = 0)
                .toList()
            val reposts = RepostEntity.all()
                .orderBy(RepostTable.createdAt to SortOrder.DESC)
                .limit(fetch.toInt(), offset = 0)
                .toList()

            val items = buildList {
                posts.forEach { add(FeedItemDto(KIND_POST, postDto(it, viewerId), null, it.createdAt)) }
                reposts.forEach { repost ->
                    val original = PostEntity.findById(repost.postId) ?: return@forEach
                    add(
                        FeedItemDto(
                            kind = KIND_REPOST,
                            post = postDto(original, viewerId),
                            repostBy = profiles.profileOf(profiles.ensureProfile(repost.userId)),
                            at = repost.createdAt,
                        )
                    )
                }
            }
                .sortedByDescending { it.at }
                .drop(offset.toInt())
                .take(limit)
            ok(FeedResponse(items))
        }

    /** Лайк перемикається: повторне натискання знімає його. */
    suspend fun toggleLike(userId: Int, postId: Int): ApiResponse<LikeToggleResponse> =
        newSuspendedTransaction {
            PostEntity.findById(postId)
                ?: return@newSuspendedTransaction ApiResponse.fail<LikeToggleResponse>(
                    "Поста не знайдено", "NO_POST")
            val existing = LikeEntity.find {
                (LikeTable.userId eq userId) and (LikeTable.postId eq postId)
            }.firstOrNull()
            if (existing != null) {
                existing.delete()
            } else {
                LikeEntity.new {
                    this.userId = userId
                    this.postId = postId
                }
            }
            val count = LikeEntity.find { LikeTable.postId eq postId }.count().toInt()
            ApiResponse.ok(LikeToggleResponse(liked = existing == null, likeCount = count))
        }

    /**
     * Репост: чужий пост з'являється у стрічці з позначкою «репост від вас».
     * Повторне натискання прибирає репост.
     */
    suspend fun toggleRepost(userId: Int, postId: Int): ApiResponse<RepostResponse> =
        newSuspendedTransaction {
            PostEntity.findById(postId)
                ?: return@newSuspendedTransaction ApiResponse.fail<RepostResponse>(
                    "Поста не знайдено", "NO_POST")
            val existing = RepostEntity.find {
                (RepostTable.userId eq userId) and (RepostTable.postId eq postId)
            }.firstOrNull()
            if (existing != null) {
                existing.delete()
            } else {
                RepostEntity.new {
                    this.userId = userId
                    this.postId = postId
                    this.createdAt = System.currentTimeMillis()
                }
            }
            val count = RepostEntity.find { RepostTable.postId eq postId }.count().toInt()
            ApiResponse.ok(RepostResponse(reposted = existing == null, repostCount = count))
        }

    /** Стрічка власних постів — майбутня сторінка профіля. */
    suspend fun myPosts(userId: Int): ApiResponse<FeedResponse> = newSuspendedTransaction {
        val posts = PostEntity.find { PostTable.userId eq userId }
            .orderBy(PostTable.createdAt to SortOrder.DESC)
            .toList()
        ok(FeedResponse(items = posts.map { FeedItemDto(KIND_POST, postDto(it, userId), null, it.createdAt) }))
    }

    // --- складання рядка стрічки --------------------------------------------

    /** DTO поста: автор-профіль, вкладення, лічильники і «моє чи ні». */
    private fun postDto(post: PostEntity, viewerId: Int): PostDto {
        val authorProfile = profiles.profileOf(profiles.ensureProfile(post.userId))
        val attachments = PostMediaEntity.find { PostMediaTable.postId eq post.id.value }
            .map { MediaDto(kind = it.kind, url = "/media/${it.kind}/${it.fileName}") }
        val likeCount = LikeEntity.find { LikeTable.postId eq post.id.value }.count().toInt()
        val repostCount = RepostEntity.find { RepostTable.postId eq post.id.value }.count().toInt()
        val likedByMe = LikeEntity.find {
            (LikeTable.postId eq post.id.value) and (LikeTable.userId eq viewerId)
        }.firstOrNull() != null
        val repostedByMe = RepostEntity.find {
            (RepostTable.postId eq post.id.value) and (RepostTable.userId eq viewerId)
        }.firstOrNull() != null
        return PostDto(
            id = post.id.value,
            author = authorProfile,
            text = post.text,
            media = attachments,
            createdAt = post.createdAt,
            likeCount = likeCount,
            repostCount = repostCount,
            likedByMe = likedByMe,
            repostedByMe = repostedByMe,
        )
    }

    companion object {
        const val KIND_POST = "POST"
        const val KIND_REPOST = "REPOST"
    }
}
