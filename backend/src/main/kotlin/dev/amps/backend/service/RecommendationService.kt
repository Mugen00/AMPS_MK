package dev.amps.backend.service

import dev.amps.backend.model.LikeTable
import dev.amps.backend.model.PostTable
import dev.amps.backend.model.CommentTable
import dev.amps.backend.model.LikeEntity
import dev.amps.backend.model.PostEntity
import dev.amps.backend.model.CommentEntity
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.SortOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap

/**
 * 1.6.8: SQL-based Collaborative Filtering рекомендаційний двигун.
 *
 * Побудова:
 * 1. User-Item матриця неявного фідбеку: лайки постів/рілсів + коментарі + перегляди
 * 2. Cosine similarity між користувачами (item-based або user-based)
 * 3. Рекомендації = пости, які лайкнули подібні користувачі, але поточний не лайкнув
 *
 * Кешування: результати кешуються на 10 хвилин.
 * Оновлення: перерахунок similarity запускається періодично або по події LikeAdded.
 */
class RecommendationService(
    private val cache: CacheService,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val logger = org.slf4j.LoggerFactory.getLogger("amps.backend.recommendations")
    private val similarityCache = ConcurrentHashMap<Int, List<Pair<Int, Double>>>()

    /**
     * Отримати рекомендації для користувача.
     * Повертає ID постів, відсортованих за очікуваною релевантністю.
     */
    suspend fun getRecommendations(
        userId: Int,
        limit: Int = 20,
        offset: Int = 0,
    ): List<Int> = withContext(Dispatchers.IO) {
        // 1. Отримуємо подібних користувачів
        val similarUsers = getSimilarUsers(userId, topK = 50)

        if (similarUsers.isEmpty()) {
            // Cold start: повертаємо популярні пости
            return@withContext getPopularPosts(limit, offset)
        }

        // 2. Збираємо кандидатів: пости, які лайкнули подібні юзери, але не лайкнув поточний
        val candidateScores = mutableMapOf<Int, Double>()

        // Поточні лайки юзера (щоб не рекомендувати їх)
        val userLikedPosts = getUserLikedPostIds(userId)

        for ((similarUserId, similarity) in similarUsers) {
            val likedBySimilar = getUserLikedPostIds(similarUserId)
            for (postId in likedBySimilar) {
                if (postId !in userLikedPosts) {
                    // Вага = similarity * 1.0 (неявний фідбек = 1)
                    candidateScores.merge(postId, similarity) { old, add -> old + add }
                }
            }
        }

        // 3. Сортуємо за скором і повертаємо топ-N
        candidateScores.toList()
            .sortedByDescending { it.second }
            .drop(offset)
            .take(limit)
            .map { it.first }
    }

    /** Отримати топ-K подібних користувачів (user-based CF). */
    private fun getSimilarUsers(userId: Int, topK: Int): List<Pair<Int, Double>> {
        // Перевіряємо кеш
        similarityCache[userId]?.let { cached ->
            return cached.take(topK)
        }

        return transaction {
            // Отримуємо вектор лайків поточного юзера
            val userVector = getUserItemVector(userId)
            if (userVector.isEmpty()) return@transaction emptyList()

            // Отримуємо всіх юзерів, які мають хоча б 1 спільний лайк
            val candidateUserIds = getCandidateUsers(userId, userVector.keys.toSet())
            if (candidateUserIds.isEmpty()) return@transaction emptyList()

            // Обчислюємо cosine similarity
            val similarities = mutableListOf<Pair<Int, Double>>()
            for (candidateId in candidateUserIds) {
                if (candidateId == userId) continue
                val candidateVector = getUserItemVector(candidateId)
                if (candidateVector.isEmpty()) continue

                val similarity = cosineSimilarity(userVector, candidateVector)
                if (similarity > 0.1) { // Поріг мінімальної схожості
                    similarities.add(candidateId to similarity)
                }
            }

            val result = similarities.sortedByDescending { it.second }.take(topK)
            similarityCache[userId] = result
            result
        }
    }

    /** User-Item вектор: postId -> 1.0 (implicit feedback). */
    private fun getUserItemVector(userId: Int): Map<Int, Double> {
        val result = mutableMapOf<Int, Double>()

        transaction {
            // Лайки постів - використовуємо DAO-style
            LikeEntity.find { LikeTable.userId eq userId }.forEach {
                result[it.postId] = 1.0
            }
            // Коментарі теж считаємо як implicit feedback
            CommentEntity.find { CommentTable.userId eq userId }.forEach {
                result[it.postId] = 1.0
            }
        }

        return result
    }

    /** Кандидати для similarity: юзери з перетином лайків > 0. */
    private fun getCandidateUsers(userId: Int, userItems: Set<Int>): Set<Int> {
        if (userItems.isEmpty()) return emptySet()
        val candidates = mutableSetOf<Int>()
        transaction {
            // Знайти юзерів, які лайкнули хоча б один з цих постів
            LikeEntity.find { LikeTable.postId inList userItems }.forEach {
                candidates.add(it.userId)
            }
        }
        return candidates
    }

    /** Cosine similarity між двома sparse векторами. */
    private fun cosineSimilarity(vecA: Map<Int, Double>, vecB: Map<Int, Double>): Double {
        val commonKeys = vecA.keys.intersect(vecB.keys)
        if (commonKeys.isEmpty()) return 0.0

        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0

        for (key in commonKeys) {
            val a = vecA[key]!!
            val b = vecB[key]!!
            dotProduct += a * b
        }
        for (value in vecA.values) normA += value * value
        for (value in vecB.values) normB += value * value

        if (normA == 0.0 || normB == 0.0) return 0.0
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB))
    }

    /** Поточні лайки юзера (post IDs). */
    private fun getUserLikedPostIds(userId: Int): Set<Int> {
        val result = mutableSetOf<Int>()
        transaction {
            LikeEntity.find { LikeTable.userId eq userId }.forEach {
                result.add(it.postId)
            }
        }
        return result
    }

    /** Fallback: популярні пости (за like_count). */
    private fun getPopularPosts(limit: Int, offset: Int): List<Int> {
        return transaction {
            PostEntity.find { PostTable.groupId.isNull() }
                .orderBy(PostTable.likeCount to SortOrder.DESC, PostTable.createdAt to SortOrder.DESC)
                .limit(limit, offset.toLong())
                .map { it.id.value.toInt() }
        }
    }

    /** Інвалідувати кеш схожості для юзера (викликається при новому лайку). */
    fun invalidateUserSimilarity(userId: Int) {
        similarityCache.remove(userId)
    }

    /** Перерахувати схожість для юзера (може викликатися періодично). */
    suspend fun refreshUserSimilarity(userId: Int) {
        similarityCache.remove(userId)
        withContext(Dispatchers.IO) {
            getSimilarUsers(userId, topK = 50)
        }
    }
}