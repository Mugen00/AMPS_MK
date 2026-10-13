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
import dev.amps.backend.model.PostEditedResponse
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
    private val follows: FollowService? = null,
    // 1.2.3: опитування під постами.
    private val polls: PollService? = null,
) {

    /**
     * Створює пост. Медіа до цього моменту вже лежать у сховищі
     * (роутинг зберігає файли до виклику) — сюди приходять пари
     * «вид вкладення — ім'я файлу». 1.6.0: [groupId] — пост у групі
     * (учасник — перевіряє роутинг через GroupService); XP за пост
     * у групі окремі (GROUP_POST замість POST).
     */
    suspend fun createPost(
        userId: Int,
        text: String,
        media: List<Pair<String, String>>,
        pollOptions: List<String>? = null,
        pollQuestion: String? = null,
        // 1.5.1: формат показу фото, як в Instagram.
        imageAspect: String? = null,
        // 1.6.0: пост у групі (null — звичайний пост спільноти).
        groupId: Int? = null,
    ): ApiResponse<PostCreatedResponse> = newSuspendedTransaction {
        // 1.6.1: антиспам — не більше 10 постів за хвилину (постинг у групи теж).
        if (!RateLimiter.allow("post:$userId", max = 10, windowMs = 60_000L)) {
            return@newSuspendedTransaction ApiResponse.fail<PostCreatedResponse>(
                "Забагато постів поспіль — трохи повільніше 🙏", "RATE_LIMITED")
        }
        val cleanPoll = pollOptions?.let { polls?.validateOptions(it) }
        if (text.isBlank() && media.isEmpty() && cleanPoll == null) {
            return@newSuspendedTransaction ApiResponse.fail<PostCreatedResponse>(
                "Пост не може бути порожнім", "EMPTY_POST")
        }
        val post = PostEntity.new {
            this.userId = userId
            this.text = text.trim().take(1000)
            this.createdAt = System.currentTimeMillis()
            this.imageAspect = imageAspect.takeIf { it in KNOWN_ASPECTS }
            this.groupId = groupId
        }
        media.forEach { (kind, fileName) ->
            PostMediaEntity.new {
                this.postId = post.id.value
                this.kind = kind
                this.fileName = fileName
            }
        }
        // 1.2.3: опитування створюється разом із постом.
        if (cleanPoll != null) {
            polls?.createForPost(userId, post.id.value, pollQuestion, cleanPoll)
        }
        // 1.2.4: XP за пост (пост у групі — своя активність).
        if (groupId != null) {
            dev.amps.backend.service.XpLedger.award(userId, dev.amps.backend.service.XpLedger.GROUP_POST)
        } else {
            dev.amps.backend.service.XpLedger.award(userId, dev.amps.backend.service.XpLedger.POST)
        }
        // Профіль автора створюється разом із першим постом.
        profiles.ensureProfile(userId)
        ApiResponse.ok(PostCreatedResponse(postId = post.id.value))
    }

    /** Стрічка: пости та репости впереміш, свіжі зверху, зі зсувом для пагінації.
     *
     * 1.2.2: [scope] = "following" — тільки пости/репости тих, на кого
     * підписаний [viewerId] (власні пости теж видимі — так зручно).
     */
    suspend fun feed(
        viewerId: Int,
        limit: Int,
        offset: Long,
        scope: String = "all",
    ): ApiResponse<FeedResponse> =
        newSuspendedTransaction {
            val followingIds: Set<Int>? = if (scope == "following" && follows != null) {
                follows.followingIds(viewerId).toSet() + viewerId
            } else {
                null
            }
            // Охоплюємо на третину більше записів, ніж потрібно: частина
            // піде на злиття з репостами, і відрізання робиться після сортування.
            val fetch = (limit.toLong() * 2 + offset + 32L).coerceAtMost(Int.MAX_VALUE.toLong())
            val allPosts = PostEntity.all()
                .orderBy(PostTable.createdAt to SortOrder.DESC)
                .limit(fetch.toInt(), offset = 0)
                .toList()
                // 1.6.0: пости груп — у ленті своєї групи, не в загальній.
                .filter { it.groupId == null }
            // 1.2.2: режим «підписки» — тільки свої й тих, на кого підписаний.
            val posts = if (followingIds != null) {
                allPosts.filter { it.userId in followingIds }
            } else {
                allPosts
            }
            val allReposts = RepostEntity.all()
                .orderBy(RepostTable.createdAt to SortOrder.DESC)
                .limit(fetch.toInt(), offset = 0)
                .toList()
            val reposts = if (followingIds != null) {
                allReposts.filter { it.userId in followingIds }
            } else {
                allReposts
            }

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

    /**
     * 1.6.1: редагування власного поста. Медіа й опитування лишаються
     * як були — змінюється лише текст; зберігаємо мітку editedAt,
     * клієнти показують «(ред.)» поруч із датою.
     */
    suspend fun editPost(userId: Int, postId: Int, text: String): ApiResponse<PostEditedResponse> =
        newSuspendedTransaction {
            val clean = text.trim()
            if (clean.isEmpty()) return@newSuspendedTransaction ApiResponse.fail("Текст порожній")
            if (clean.length > 1000) return@newSuspendedTransaction ApiResponse.fail("Текст задовгий (макс. 1000)")
            val post = PostEntity.findById(postId)
                ?: return@newSuspendedTransaction ApiResponse.fail("Поста не знайдено", "NO_POST")
            if (post.userId != userId) {
                return@newSuspendedTransaction ApiResponse.fail("Редагувати можна лише свій пост", "NOT_OWNER")
            }
            post.text = clean
            post.editedAt = System.currentTimeMillis()
            ok(PostEditedResponse(post = postDto(post, userId)))
        }

    /**
     * 1.6.1/1.6.2: видалення власного поста. Каскадні посилання (медіа,
     * лайки, репости, коментарі, опитування) мають ON DELETE CASCADE на
     * posts. 1.6.2: файли медіа видаляються з диска одразу — сиріт не лишається.
     */
    suspend fun deletePost(userId: Int, postId: Int): ApiResponse<Unit> =
        newSuspendedTransaction {
            val post = PostEntity.findById(postId)
                ?: return@newSuspendedTransaction ApiResponse.fail("Поста не знайдено", "NO_POST")
            if (post.userId != userId) {
                return@newSuspendedTransaction ApiResponse.fail("Видалити можна лише свій пост", "NOT_OWNER")
            }
            // Файли для видалення збираємо до видалення рядків.
            val mediaFiles = PostMediaEntity.find { PostMediaTable.postId eq postId }
                .map { it.kind to it.fileName }
            post.delete()
            // Диск — не база: файли прибираємо після успішної транзакції.
            mediaFiles.forEach { (kind, fileName) -> media.delete(kind, fileName) }
            ok(Unit)
        }

    /** Лайк перемикається: повторне натискання знімає його. */
    suspend fun toggleLike(userId: Int, postId: Int): ApiResponse<LikeToggleResponse> =
        newSuspendedTransaction {
            val post = PostEntity.findById(postId)
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
            // 1.6.2: синхронізуємо кеш-лічильник із фактом.
            post.likeCount = count
            if (existing == null) {
                // 1.2.4: XP за лайк — тому, хто лайкнув, і автору поста.
                dev.amps.backend.service.XpLedger.award(userId, dev.amps.backend.service.XpLedger.LIKE_GIVEN)
                if (post.userId != userId) {
                    dev.amps.backend.service.XpLedger.award(post.userId, dev.amps.backend.service.XpLedger.LIKE_RECEIVED)
                }
            }
            ApiResponse.ok(LikeToggleResponse(liked = existing == null, likeCount = count))
        }

    /**
     * Репост: чужий пост з'являється у стрічці з позначкою «репост від вас».
     * Повторне натискання прибирає репост.
     */
    suspend fun toggleRepost(userId: Int, postId: Int): ApiResponse<RepostResponse> =
        newSuspendedTransaction {
            val post = PostEntity.findById(postId)
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
            // 1.6.2: синхронізуємо кеш-лічильник репостів.
            post.repostCount = count
            if (existing == null) {
                dev.amps.backend.service.XpLedger.award(userId, dev.amps.backend.service.XpLedger.REPOST)
            }
            ApiResponse.ok(RepostResponse(reposted = existing == null, repostCount = count))
        }

    /** Стрічка власних постів — майбутня сторінка профіля. */
    suspend fun myPosts(userId: Int): ApiResponse<FeedResponse> = newSuspendedTransaction {
        val posts = PostEntity.find { PostTable.userId eq userId }
            .orderBy(PostTable.createdAt to SortOrder.DESC)
            .toList()
        ok(FeedResponse(items = posts.map { FeedItemDto(KIND_POST, postDto(it, userId), null, it.createdAt) }))
    }

    /**
     * 1.2.5: пости конкретного користувача — чужий профіль.
     * [viewerId] потрібен, щоб у картках було видно власні лайки/голоси.
     * 1.6.0: пости груп у профілі не показуються (вони в групі).
     */
    suspend fun userPosts(targetId: Int, viewerId: Int): ApiResponse<FeedResponse> = newSuspendedTransaction {
        val posts = PostEntity.find { PostTable.userId eq targetId }
            .orderBy(PostTable.createdAt to SortOrder.DESC)
            .toList()
            .filter { it.groupId == null }
        ok(FeedResponse(items = posts.map { FeedItemDto(KIND_POST, postDto(it, viewerId), null, it.createdAt) }))
    }

    /**
     * 1.6.0: лента постів групи — тільки для учасників (перевіряє роутинг
     * через GroupService.isMember); свіжі зверху, пагінація offset'ом.
     */
    suspend fun groupPosts(
        groupId: Int,
        viewerId: Int,
        limit: Int,
        offset: Long,
    ): ApiResponse<FeedResponse> = newSuspendedTransaction {
        val posts = PostEntity.find { PostTable.groupId eq groupId }
            .orderBy(PostTable.createdAt to SortOrder.DESC)
            .limit(limit, offset)
            .toList()
        ok(FeedResponse(items = posts.map { FeedItemDto(KIND_POST, postDto(it, viewerId), null, it.createdAt) }))
    }

    /** 1.2.5: репости користувача — оригінальні пости з позначкою «репост від». */
    suspend fun userReposts(targetId: Int, viewerId: Int): ApiResponse<FeedResponse> = newSuspendedTransaction {
        val reposts = RepostEntity.find { RepostTable.userId eq targetId }
            .orderBy(RepostTable.createdAt to SortOrder.DESC)
            .toList()
        val items = reposts.mapNotNull { repost ->
            val original = PostEntity.findById(repost.postId) ?: return@mapNotNull null
            FeedItemDto(
                kind = KIND_REPOST,
                post = postDto(original, viewerId),
                repostBy = profiles.profileOf(profiles.ensureProfile(repost.userId)),
                at = repost.createdAt,
            )
        }
        ok(FeedResponse(items))
    }

    // --- складання рядка стрічки --------------------------------------------

    /**
     * 1.6.2: батч-складання DTO постів — заміна N+1. Замість 6 запитів
     * на кожен пост — фіксована кількість на всю сторінку: автори одним
     * запитом, вкладення одним, лайки/репости глядача — по одному;
     * лічильники беремо з кеш-колонок (оновлюються при лайку/репості,
     * самолікування — на старті). Опитування — по-посту (рідкісні).
     * Викликається лише всередині транзакції.
     */
    private fun postsToDtos(posts: List<PostEntity>, viewerId: Int): Map<Int, PostDto> {
        if (posts.isEmpty()) return emptyMap()
        val ids = posts.map { it.id.value }
        val authorIds = posts.map { it.userId }.distinct()

        // Автори — одним запитом; відсутні профілі (ліниве створення) — точково.
        val authorsByUserId = dev.amps.backend.model.ProfileEntity
            .find { dev.amps.backend.model.ProfileTable.userId inList authorIds }
            .associateBy { it.userId }

        // Вкладення — одним запитом, групуємо в пам'яті.
        val mediaByPost = HashMap<Int, MutableList<MediaDto>>()
        PostMediaEntity.find { PostMediaTable.postId inList ids }.forEach { row ->
            mediaByPost.getOrPut(row.postId) { ArrayList() }
                .add(MediaDto(kind = row.kind, url = "/media/${row.kind}/${row.fileName}"))
        }

        // Мої лайки/репости по всій сторінці — по одному запиту.
        val likedIds = LikeEntity.find {
            (LikeTable.userId eq viewerId) and (LikeTable.postId inList ids)
        }.map { it.postId }.toSet()
        val repostedIds = RepostEntity.find {
            (RepostTable.userId eq viewerId) and (RepostTable.postId inList ids)
        }.map { it.postId }.toSet()

        val result = HashMap<Int, PostDto>(posts.size)
        for (post in posts) {
            val author = authorsByUserId[post.userId] ?: profiles.ensureProfile(post.userId)
            val pid = post.id.value
            result[pid] = PostDto(
                id = post.id.value,
                author = profiles.profileOf(author),
                text = post.text,
                media = mediaByPost[pid] ?: emptyList(),
                createdAt = post.createdAt,
                // 1.6.2: лічильники з кеш-колонок — без COUNT(*).
                likeCount = post.likeCount,
                repostCount = post.repostCount,
                likedByMe = pid in likedIds,
                repostedByMe = pid in repostedIds,
                // 1.2.3: опитування, якщо є (рідкісне — залишається по-посту).
                poll = polls?.forPost(pid, viewerId),
                // 1.5.1: формат показу фото.
                imageAspect = post.imageAspect,
                // 1.6.1: мітка редагування.
                editedAt = post.editedAt,
            )
        }
        return result
    }

    /** DTO одного поста (обгортка над батчем). */
    private fun postDto(post: PostEntity, viewerId: Int): PostDto =
        postsToDtos(listOf(post), viewerId).getValue(post.id.value)

    companion object {
        const val KIND_POST = "POST"
        const val KIND_REPOST = "REPOST"

        /** 1.5.1: допустимі формати показу фото (все інше → "original"). */
        val KNOWN_ASPECTS = setOf("original", "1:1", "4:5", "16:9")
    }
}
