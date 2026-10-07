package dev.amps.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 1.1.1: повні теги персонажів поста Danbooru.
 *
 * Сторінка IQDB віддає теги з атрибута `alt` прев'ю — і він обрізаний: у
 * поста з трьома персонажами в `alt` можуть залишитися один-два. Відкритий
 * JSON API Danbooru відповідає полем `tag_string_character` з усім
 * списком, без ключа й без реєстрації.
 *
 * Виклик робиться один на пошук і тільки тоді, коли IQDB уже назвав
 * Danbooru-пост: анонімний API обмежений за частотою, і це не те джерело,
 * яке можна опитувати в циклі.
 *
 * **Чесні межі.** Імена приходять із поста, який знайшов IQDB: арта в базі
 * немає — тегів немає. Закритий пост Danbooru не віддає JSON анонімно, і
 * тоді ланцюжок просто повертається до обрізаного `alt`.
 */
class DanbooruClient(
    private val client: OkHttpClient,
) {

    /**
     * Імена персонажів поста в тому самому вигляді, якого очікує ланцюжок
     * IQDB: `rem_(re:zero)` → `rem`, `hatsune_miku` → `hatsune miku`.
     */
    suspend fun characterNames(postUrl: String): List<String> = withContext(Dispatchers.IO) {
        val id = POST_ID.find(postUrl)?.groupValues?.getOrNull(1)
            ?: return@withContext emptyList()
        val request = Request.Builder()
            .url("$API/posts/$id.json")
            .header("User-Agent", BROWSER_USER_AGENT)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body?.string() ?: return@withContext emptyList()
            runCatching { json.decodeFromString(DanbooruPost.serializer(), body) }
                .getOrNull()
                ?.tagStringCharacter.orEmpty()
                .split(' ')
                .mapNotNull(::characterName)
                .distinct()
                .take(MAX_NAMES)
        }
    }

    /** `rem_(re:zero)` → `rem`; `hatsune_miku` → `hatsune miku`. */
    private fun characterName(tag: String): String? {
        val base = tag.substringBefore("_(")
        if (base.isBlank() || base.contains(':')) return null
        val name = base.replace('_', ' ').trim()
        return name.takeIf { it.isNotEmpty() }
    }

    /** Із відповіді потрібне одне поле; решту відкидає `ignoreUnknownKeys`. */
    @Serializable
    private data class DanbooruPost(
        @SerialName("tag_string_character") val tagStringCharacter: String = "",
    )

    private companion object {
        const val API = "https://danbooru.donmai.us"

        /**
         * Danbooru відхиляє запити з клієнтським User-Agent OkHttp, тому
         * підписуємося браузером — так само, як це робить IqdbClient.
         */
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

        /** `https://danbooru.donmai.us/posts/12345?tags=...` → `12345`. */
        val POST_ID = Regex("/posts/(\\d+)")

        /**
         * Пост із групою персонажів може мати десятки тегів; кожен іменований
         * тег піде в AniList окремим запитом, тому список обрізається.
         */
        const val MAX_NAMES = 5

        val json = Json { ignoreUnknownKeys = true }
    }
}
