package dev.amps.app.data.remote.backend

import dev.amps.app.data.remote.backend.dto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * 1.1.0: HTTP-клієнт для бекенду на OkHttp + kotlinx.serialization.
 *
 * Без Retrofit: жодної нової залежності, збірка лишається офлайн.
 *
 * Конверт відповідей один для всіх — { success, data, error, errorCode }.
 * Поле data розбирається serializer'ом конкретного виклику: попередня
 * версія декодувала все як TokenResponse, через що профіль, 2FA і
 * синхронізація не доживали б до ViewModel.
 */
class BackendApi private constructor(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val json: Json,
) {

    private val jsonMediaType = "application/json".toMediaType()

    /** 1.1.2: ефективна адреса сервера — для складання URL медіа зі стрічки. */
    val effectiveBaseUrl: String get() = baseUrl

    companion object {
        /**
         * 1.1.2: адреса свіжого розгортання на Railway. Попереднє значення
         * вказувало на старий сервіс, і реєстрація «не працювала» саме
         * через це — застосунок стукався не в той сервер.
         */
        private const val DEFAULT_BASE_URL = "https://ampsmk-production.up.railway.app/"

        @Volatile
        private var instance: BackendApi? = null

        fun getOrCreate(baseUrl: String? = null, client: OkHttpClient): BackendApi {
            val url = baseUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL
            return instance ?: synchronized(this) {
                instance ?: BackendApi(client, url, Json { ignoreUnknownKeys = true }).also { instance = it }
            }
        }

        fun reset() {
            instance = null
        }
    }

    // ===== Auth =====
    suspend fun register(request: RegisterRequest): ApiResult = withContext(Dispatchers.IO) {
        execute(
            "POST", "auth/register",
            json.encodeToString(RegisterRequest.serializer(), request),
            TokenResponse.serializer(), null,
        )
    }

    suspend fun login(request: LoginRequest): ApiResult = withContext(Dispatchers.IO) {
        execute(
            "POST", "auth/login",
            json.encodeToString(LoginRequest.serializer(), request),
            TokenResponse.serializer(), null,
        )
    }

    suspend fun verifyCode(request: VerifyCodeRequest): ApiResult = withContext(Dispatchers.IO) {
        execute(
            "POST", "auth/verify",
            json.encodeToString(VerifyCodeRequest.serializer(), request),
            TokenResponse.serializer(), null,
        )
    }

    suspend fun requestPasswordReset(request: PasswordResetRequest): ApiResult = withContext(Dispatchers.IO) {
        execute(
            "POST", "auth/password/reset",
            json.encodeToString(PasswordResetRequest.serializer(), request),
            UnitResult.serializer(), null,
        )
    }

    suspend fun confirmPasswordReset(request: PasswordResetConfirmRequest): ApiResult = withContext(Dispatchers.IO) {
        execute(
            "POST", "auth/password/reset/confirm",
            json.encodeToString(PasswordResetConfirmRequest.serializer(), request),
            TokenResponse.serializer(), null,
        )
    }

    suspend fun refreshTokens(request: RefreshRequest): ApiResult = withContext(Dispatchers.IO) {
        execute(
            "POST", "auth/refresh",
            json.encodeToString(RefreshRequest.serializer(), request),
            TokenResponse.serializer(), null,
        )
    }

    // ===== Profile =====
    suspend fun getProfile(authHeader: String): ApiResult = withContext(Dispatchers.IO) {
        execute("GET", "auth/me", null, UserInfo.serializer(), authHeader)
    }

    // ===== 2FA =====
    suspend fun getTwoFactorStatus(authHeader: String): ApiResult = withContext(Dispatchers.IO) {
        execute("GET", "auth/2fa/status", null, TwoFactorStatusResponse.serializer(), authHeader)
    }

    suspend fun setupTwoFactor(authHeader: String): ApiResult = withContext(Dispatchers.IO) {
        execute("POST", "auth/2fa/setup", null, TwoFactorSetupResponse.serializer(), authHeader)
    }

    suspend fun verifyTwoFactor(authHeader: String, request: TwoFactorVerifyRequest): ApiResult =
        withContext(Dispatchers.IO) {
            execute(
                "POST", "auth/2fa/verify",
                json.encodeToString(TwoFactorVerifyRequest.serializer(), request),
                TwoFactorStatusResponse.serializer(), authHeader,
            )
        }

    suspend fun disableTwoFactor(authHeader: String): ApiResult = withContext(Dispatchers.IO) {
        execute("POST", "auth/2fa/disable", null, TwoFactorStatusResponse.serializer(), authHeader)
    }

    // ===== Sync =====
    suspend fun getSyncData(authHeader: String): ApiResult = withContext(Dispatchers.IO) {
        execute("GET", "auth/sync", null, SyncResponse.serializer(), authHeader)
    }

    suspend fun syncData(authHeader: String, request: SyncRequest): ApiResult = withContext(Dispatchers.IO) {
        execute(
            "POST", "auth/sync",
            json.encodeToString(SyncRequest.serializer(), request),
            SyncResponse.serializer(), authHeader,
        )
    }

    // ===== Health =====
    suspend fun healthCheck(): ApiResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(baseUrl + "health").get().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    ApiResult.Success(Unit)
                } else {
                    ApiResult.Failure(response.code, "HTTP ${response.code}", null)
                }
            }
        } catch (e: Exception) {
            ApiResult.NetworkError(e.message ?: "Мережева помилка")
        }
    }

    // ===== Спільнота і профіль (1.1.2) =====

    /** Мій профіль із сервера (створюється там ліниво). */
    suspend fun fetchProfile(authHeader: String): ApiResult = withContext(Dispatchers.IO) {
        execute("GET", "profile", null, ProfileDto.serializer(), authHeader)
    }

    suspend fun updateProfile(authHeader: String, request: ProfileUpdateRequest): ApiResult =
        withContext(Dispatchers.IO) {
            execute(
                "PUT", "profile",
                json.encodeToString(ProfileUpdateRequest.serializer(), request),
                ProfileDto.serializer(), authHeader,
            )
        }

    /** 1.4.0: сторінка тайтла на yummyani.me (публічний резолвер бекенда). */
    suspend fun fetchWatchUrl(title: String): ApiResult = withContext(Dispatchers.IO) {
        val encoded = java.net.URLEncoder.encode(title, "UTF-8")
        execute("GET", "anime/watch?q=$encoded", null, WatchUrlResponse.serializer(), null)
    }

    /** Аватар: multipart з одним файлом "avatar" (jpeg). */
    suspend fun uploadAvatar(authHeader: String, bytes: ByteArray): ApiResult = withContext(Dispatchers.IO) {
        executeMultipart(
            "profile/avatar", authHeader,
            listOf(MultipartPart(name = "avatar", filename = "avatar.jpg", contentType = "image/jpeg", bytes = bytes)),
            AvatarResponse.serializer(),
        )
    }

    /** Стрічка спільноти; limit 1..50, offset — зсув пагінації.
     *
     * 1.2.2: scope = "following" — стрічка лише підписок (потрібен токен).
     */
    suspend fun getFeed(
        authHeader: String,
        limit: Int = 20,
        offset: Long = 0,
        scope: String = "all",
    ): ApiResult =
        withContext(Dispatchers.IO) {
            execute(
                "GET", "feed?limit=$limit&offset=$offset&scope=$scope", null,
                FeedResponse.serializer(), authHeader,
            )
        }

    /**
     * 1.1.3: гостева стрічка — та сама адреса, але без токена. Сервер
     * віддає пости з чистими лічильниками («лайкнуто мною» = false).
     */
    suspend fun getFeedPublic(limit: Int = 20, offset: Long = 0): ApiResult =
        withContext(Dispatchers.IO) {
            execute("GET", "feed?limit=$limit&offset=$offset", null, FeedResponse.serializer(), null)
        }

    /**
     * Публікація поста: текст і вкладення multipart'ом. Вкладення —
     * частини з іменами "photo" (jpg/png/webp/gif, ≤10 МБ, 1.5.3: до 30
     * для каруселі) або "video" (mp4/webm/mov, ≤50 МБ); сервер береже
     * розмір ще при читанні.
     * 1.5.1: [imageAspect] — формат показу фото в стрічці ("original",
     * "1:1", "4:5", "16:9"), як в Instagram.
     * 1.5.3: [photos] — кілька фото для каруселі (або один — готовий колаж).
     */
    suspend fun createPost(
        authHeader: String,
        text: String,
        photos: List<ByteArray> = emptyList(),
        video: ByteArray? = null,
        imageAspect: String? = null,
    ): ApiResult = withContext(Dispatchers.IO) {
        val parts = buildList {
            add(MultipartPart(name = "text", value = text))
            if (imageAspect != null) {
                add(MultipartPart(name = "imageAspect", value = imageAspect))
            }
            photos.forEachIndexed { index, bytes ->
                add(
                    MultipartPart(
                        name = "photo",
                        filename = "photo$index.jpg",
                        contentType = "image/jpeg",
                        bytes = bytes,
                    )
                )
            }
            if (video != null) {
                add(MultipartPart(name = "video", filename = "video.mp4", contentType = "video/mp4", bytes = video))
            }
        }
        executeMultipart("feed", authHeader, parts, PostCreatedResponse.serializer())
    }

    suspend fun toggleLike(authHeader: String, postId: Int): ApiResult = withContext(Dispatchers.IO) {
        execute("POST", "feed/$postId/like", null, LikeToggleResponse.serializer(), authHeader)
    }

    suspend fun toggleRepost(authHeader: String, postId: Int): ApiResult = withContext(Dispatchers.IO) {
        execute("POST", "feed/$postId/repost", null, RepostResponse.serializer(), authHeader)
    }

    // ===== 1.6.1: редагування і видалення власного поста =====

    /** Редагувати текст свого поста (медіа лишаються). */
    suspend fun editPost(authHeader: String, postId: Int, text: String): ApiResult =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(
                PostEditRequest.serializer(),
                PostEditRequest(text = text),
            )
            execute("PATCH", "feed/$postId", body, PostEditedResponse.serializer(), authHeader)
        }

    /** Видалити свій пост (медіа й лічильники чистяться на сервері). */
    suspend fun deletePost(authHeader: String, postId: Int): ApiResult =
        withContext(Dispatchers.IO) {
            execute("DELETE", "feed/$postId", null, UnitResult.serializer(), authHeader)
        }

    // 1.6.1: пошук людей за ім'ям/логіном (для нових діалогів і підписок).
    suspend fun searchUsers(authHeader: String, q: String): ApiResult =
        withContext(Dispatchers.IO) {
            val suffix = "?q=" + java.net.URLEncoder.encode(q.trim(), "UTF-8")
            execute("GET", "users/search$suffix", null, UsersSearchResponse.serializer(), authHeader)
        }

    // ===== Коментарі (1.2.2) =====

    /** Коментарі поста (публічно читання, як і стрічка). */
    suspend fun getComments(postId: Int): ApiResult = withContext(Dispatchers.IO) {
        execute("GET", "posts/$postId/comments", null, CommentsResponse.serializer(), null)
    }

    /** Написати коментар (потрібен вхід). */
    suspend fun addComment(authHeader: String, postId: Int, text: String): ApiResult =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(
                CommentCreateRequest.serializer(),
                CommentCreateRequest(text = text),
            )
            execute("POST", "posts/$postId/comments", body, CommentCreatedResponse.serializer(), authHeader)
        }

    /** Видалити свій коментар. */
    suspend fun deleteComment(authHeader: String, commentId: Int): ApiResult =
        withContext(Dispatchers.IO) {
            execute("DELETE", "comments/$commentId", null, CommentsResponse.serializer(), authHeader)
        }

    // ===== Підписки (1.2.2) =====

    /** Підписатись/відписатись: toggle, як лайк. */
    suspend fun toggleFollow(authHeader: String, userId: Int): ApiResult =
        withContext(Dispatchers.IO) {
            execute("POST", "follow/$userId", null, FollowToggleResponse.serializer(), authHeader)
        }

    // ===== Чужий профіль (1.2.5) =====

    /** Чужий профіль: дані + чи підписаний я + лічильники. */
    suspend fun fetchUserProfile(authHeader: String, userId: Int): ApiResult =
        withContext(Dispatchers.IO) {
            execute("GET", "profile/$userId", null, UserProfileResponse.serializer(), authHeader)
        }

    /** Пости користувача (без репостів). */
    suspend fun fetchUserPosts(authHeader: String, userId: Int): ApiResult =
        withContext(Dispatchers.IO) {
            execute("GET", "profile/$userId/posts", null, FeedResponse.serializer(), authHeader)
        }

    /** Репости користувача — оригінальні пости з позначкою «репост від». */
    suspend fun fetchUserReposts(authHeader: String, userId: Int): ApiResult =
        withContext(Dispatchers.IO) {
            execute("GET", "profile/$userId/reposts", null, FeedResponse.serializer(), authHeader)
        }

    // ===== Опитування (1.2.3) =====

    /** Прикріпити опитування до СВОГО поста (2–4 варіанти). */
    suspend fun createPoll(
        authHeader: String,
        postId: Int,
        question: String,
        options: List<String>,
    ): ApiResult = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            PollCreateRequest.serializer(),
            PollCreateRequest(question = question, options = options),
        )
        execute("POST", "posts/$postId/poll", body, PollResponse.serializer(), authHeader)
    }

    /** Проголосувати (повторний тап за той самий варіант знімає голос). */
    suspend fun votePoll(authHeader: String, pollId: Int, optionId: Int): ApiResult =
        withContext(Dispatchers.IO) {
            execute("POST", "poll/$pollId/vote/$optionId", null, PollResponse.serializer(), authHeader)
        }

    /** Статистика мого профілю: пости / підписники / аніме. */
    suspend fun getProfileStats(authHeader: String): ApiResult = withContext(Dispatchers.IO) {
        execute("GET", "profile/stats", null, ProfileStatsDto.serializer(), authHeader)
    }

    // ===== Аніме-список акаунта (1.2.2) =====

    suspend fun getAnimeList(authHeader: String): ApiResult = withContext(Dispatchers.IO) {
        execute("GET", "anime/list", null, AnimeListResponse.serializer(), authHeader)
    }

    /** Додати/оновити тайтл у списку (статус + нагадування). */
    suspend fun upsertAnimeList(
        authHeader: String,
        anilistId: Int,
        title: String,
        coverUrl: String,
        status: String,
        reminder: Boolean,
    ): ApiResult = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            AnimeListUpsertRequest.serializer(),
            AnimeListUpsertRequest(
                anilistId = anilistId,
                title = title,
                coverUrl = coverUrl,
                status = status,
                reminder = reminder,
            ),
        )
        execute("PUT", "anime/list", body, AnimeListResponse.serializer(), authHeader)
    }

    /** Прибрати тайтл зі списку. */
    suspend fun deleteAnimeList(authHeader: String, anilistId: Int): ApiResult =
        withContext(Dispatchers.IO) {
            execute("DELETE", "anime/list/$anilistId", null, AnimeListResponse.serializer(), authHeader)
        }

    // ===== Сторіс (1.2.0) =====

    /** Живі сторіс усіх користувачів; публічно — як і стрічка. */
    suspend fun getStories(): ApiResult = withContext(Dispatchers.IO) {
        execute("GET", "stories", null, StoriesResponse.serializer(), null)
    }

    /**
     * Публікація сторіс: один файл "photo" (≤10 МБ) або "video" (≤50 МБ).
     * Живе 24 години, потім зникає і в списку, і зі сховища сервера.
     */
    suspend fun createStory(
        authHeader: String,
        kind: String,
        bytes: ByteArray,
    ): ApiResult = withContext(Dispatchers.IO) {
        val isVideo = kind == "video"
        val part = MultipartPart(
            name = kind,
            filename = if (isVideo) "story.mp4" else "story.jpg",
            contentType = if (isVideo) "video/mp4" else "image/jpeg",
            bytes = bytes,
        )
        executeMultipart("stories", authHeader, listOf(part), StoryDto.serializer())
    }

    suspend fun deleteStory(authHeader: String, storyId: Int): ApiResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl + "stories/$storyId")
            .delete()
            .addHeader("Authorization", authHeader)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                if (response.isSuccessful) ApiResult.Success(UnitResult(true)) else {
                    val envelope = runCatching { json.decodeFromString(Envelope.serializer(), bodyString) }.getOrNull()
                    ApiResult.Failure(
                        response.code,
                        envelope?.error ?: "Сторіс не знайдено",
                        envelope?.errorCode,
                    )
                }
            }
        } catch (e: Exception) {
            ApiResult.NetworkError(e.message ?: "Мережева помилка")
        }
    }

    // ===== Плейлісти (1.2.1) =====

    /** Свої плейлісти акаунта (живуть у базі сервера). */
    suspend fun getPlaylists(authHeader: String): ApiResult = withContext(Dispatchers.IO) {
        execute("GET", "playlists", null, PlaylistsResponse.serializer(), authHeader)
    }

    suspend fun createPlaylist(authHeader: String, name: String): ApiResult = withContext(Dispatchers.IO) {
        val body = json.encodeToString(PlaylistCreateRequest.serializer(), PlaylistCreateRequest(name))
        execute("POST", "playlists", body, PlaylistCreatedResponse.serializer(), authHeader)
    }

    suspend fun deletePlaylist(authHeader: String, playlistId: Int): ApiResult = withContext(Dispatchers.IO) {
        execute("DELETE", "playlists/$playlistId", null, PlaylistsResponse.serializer(), authHeader)
    }

    /** Треки плейліста — знімки рядків пошуку, готові до онлайн-програвання. */
    suspend fun getPlaylistTracks(authHeader: String, playlistId: Int): ApiResult = withContext(Dispatchers.IO) {
        execute("GET", "playlists/$playlistId/tracks", null, PlaylistTracksResponse.serializer(), authHeader)
    }

    suspend fun addPlaylistTrack(
        authHeader: String,
        playlistId: Int,
        track: PlaylistTrackAddRequest,
    ): ApiResult = withContext(Dispatchers.IO) {
        val body = json.encodeToString(PlaylistTrackAddRequest.serializer(), track)
        execute("POST", "playlists/$playlistId/tracks", body, PlaylistCreatedResponse.serializer(), authHeader)
    }

    suspend fun removePlaylistTrack(authHeader: String, playlistId: Int, trackRowId: Int): ApiResult =
        withContext(Dispatchers.IO) {
            execute(
                "DELETE", "playlists/$playlistId/tracks/$trackRowId", null,
                PlaylistTracksResponse.serializer(), authHeader,
            )
        }

    // ===== Google (1.2.0) =====

    /** Вхід/реєстрація через Google: ID-токен перевіряє сервер. */
    suspend fun googleAuth(idToken: String): ApiResult = withContext(Dispatchers.IO) {
        val body = json.encodeToString(GoogleAuthRequest.serializer(), GoogleAuthRequest(idToken))
        execute("POST", "auth/google", body, TokenResponse.serializer(), null)
    }

    // ===== Групи (1.6.0) =====

    /** Каталог груп (публічний), [q] — пошук за назвою. */
    suspend fun getGroups(authHeader: String, q: String? = null): ApiResult =
        withContext(Dispatchers.IO) {
            val suffix = if (q.isNullOrBlank()) "" else "?q=" + java.net.URLEncoder.encode(q, "UTF-8")
            execute("GET", "groups$q", null, GroupListResponse.serializer(), authHeader)
        }

    /** Мої групи. */
    suspend fun getMyGroups(authHeader: String): ApiResult = withContext(Dispatchers.IO) {
        execute("GET", "groups/my", null, GroupListResponse.serializer(), authHeader)
    }

    /** Мої запити на вступ у приватні групи. */
    suspend fun getMyJoinRequests(authHeader: String): ApiResult = withContext(Dispatchers.IO) {
        execute("GET", "groups/my-requests", null, GroupRequestsResponse.serializer(), authHeader)
    }

    /** Створити групу (30 XP за активність). */
    suspend fun createGroup(authHeader: String, name: String, description: String, isPrivate: Boolean): ApiResult =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(
                GroupCreateRequest.serializer(),
                GroupCreateRequest(name = name, description = description, isPrivate = isPrivate),
            )
            execute("POST", "groups", body, GroupCreatedResponse.serializer(), authHeader)
        }

    /** Деталі групи + мій статус (учасник / запит висить). */
    suspend fun getGroup(authHeader: String, groupId: Int): ApiResult = withContext(Dispatchers.IO) {
        execute("GET", "groups/$groupId", null, GroupDetailDto.serializer(), authHeader)
    }

    /** Аватар групи (власник) — такий самий multipart, як аватар профіля. */
    suspend fun uploadGroupAvatar(authHeader: String, groupId: Int, bytes: ByteArray): ApiResult =
        withContext(Dispatchers.IO) {
            executeMultipart(
                "groups/$groupId/avatar", authHeader,
                listOf(
                    MultipartPart(name = "file", filename = "avatar.jpg", contentType = "image/jpeg", bytes = bytes)
                ),
                AvatarResponse.serializer(),
            )
        }

    /** Вступ: публічна — одразу учасник; приватна — висилає запит (REQUEST_PENDING). */
    suspend fun joinGroup(authHeader: String, groupId: Int): ApiResult = withContext(Dispatchers.IO) {
        execute("POST", "groups/$groupId/join", null, UnitResult.serializer(), authHeader)
    }

    /** Вийти з групи (власнику не можна — спершу передати або видалити групу). */
    suspend fun leaveGroup(authHeader: String, groupId: Int): ApiResult = withContext(Dispatchers.IO) {
        execute("POST", "groups/$groupId/leave", null, UnitResult.serializer(), authHeader)
    }

    /** Видалити групу (тільки власник). */
    suspend fun deleteGroup(authHeader: String, groupId: Int): ApiResult = withContext(Dispatchers.IO) {
        execute("DELETE", "groups/$groupId", null, UnitResult.serializer(), authHeader)
    }

    /** Учасники групи з ролями. */
    suspend fun getGroupMembers(authHeader: String, groupId: Int): ApiResult =
        withContext(Dispatchers.IO) {
            execute("GET", "groups/$groupId/members", null, GroupMembersResponse.serializer(), authHeader)
        }

    /** Виключити учасника (власник/модератор). */
    suspend fun kickMember(authHeader: String, groupId: Int, userId: Int): ApiResult =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(GroupKickRequest.serializer(), GroupKickRequest(userId))
            execute("POST", "groups/$groupId/kick", body, UnitResult.serializer(), authHeader)
        }

    /** Змінити роль: "mod" або "member" (власник — тільки власник). */
    suspend fun setGroupRole(authHeader: String, groupId: Int, userId: Int, role: String): ApiResult =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(GroupRoleRequest.serializer(), GroupRoleRequest(userId, role))
            execute("POST", "groups/$groupId/role", body, GroupMembersResponse.serializer(), authHeader)
        }

    /** Запити на вступ (для власника/модераторів приватної групи). */
    suspend fun getJoinRequests(authHeader: String, groupId: Int): ApiResult =
        withContext(Dispatchers.IO) {
            execute("GET", "groups/$groupId/requests", null, GroupRequestsResponse.serializer(), authHeader)
        }

    /** Схвалити запит (rid — id запита). */
    suspend fun approveJoinRequest(authHeader: String, groupId: Int, requestId: Int): ApiResult =
        withContext(Dispatchers.IO) {
            execute("POST", "groups/$groupId/requests/$requestId/approve", null, UnitResult.serializer(), authHeader)
        }

    /** Відхилити запит. */
    suspend fun rejectJoinRequest(authHeader: String, groupId: Int, requestId: Int): ApiResult =
        withContext(Dispatchers.IO) {
            execute("POST", "groups/$groupId/requests/$requestId/reject", null, UnitResult.serializer(), authHeader)
        }

    /** Лента постів групи (тільки учасникам). */
    suspend fun getGroupPosts(authHeader: String, groupId: Int, limit: Int = 20, offset: Long = 0): ApiResult =
        withContext(Dispatchers.IO) {
            execute("GET", "groups/$groupId/posts?limit=$limit&offset=$offset", null, FeedResponse.serializer(), authHeader)
        }

    /**
     * Пост у групу: той самий multipart, що й звичайний пост
     * (текст + фото каруселлю/відео), тільки шлях /groups/{id}/posts.
     */
    suspend fun createGroupPost(
        authHeader: String,
        groupId: Int,
        text: String,
        photos: List<ByteArray> = emptyList(),
        video: ByteArray? = null,
        imageAspect: String? = null,
    ): ApiResult = withContext(Dispatchers.IO) {
        val parts = buildList {
            add(MultipartPart(name = "text", value = text))
            if (imageAspect != null) add(MultipartPart(name = "imageAspect", value = imageAspect))
            photos.forEachIndexed { index, bytes ->
                add(
                    MultipartPart(
                        name = "photo",
                        filename = "photo$index.jpg",
                        contentType = "image/jpeg",
                        bytes = bytes,
                    )
                )
            }
            if (video != null) {
                add(MultipartPart(name = "video", filename = "video.mp4", contentType = "video/mp4", bytes = video))
            }
        }
        executeMultipart("groups/$groupId/posts", authHeader, parts, PostCreatedResponse.serializer())
    }

    // ===== Чати (1.6.0) =====

    /** Список діалогів: приватні + групові, з непрочитаними. Опитується кожні ~15 с. */
    suspend fun getChats(authHeader: String): ApiResult = withContext(Dispatchers.IO) {
        execute("GET", "chats", null, ChatDialogsResponse.serializer(), authHeader)
    }

    // 1.6.1: сумарні непрочитані — бейдж вкладки «Чати» і фонові сповіщення.
    suspend fun getChatUnreadCount(authHeader: String): ApiResult = withContext(Dispatchers.IO) {
        execute("GET", "chats/unread-count", null, ChatUnreadResponse.serializer(), authHeader)
    }

    /** Контакти для старту діалогу: ті, на кого я підписаний. */
    suspend fun getChatContacts(authHeader: String): ApiResult = withContext(Dispatchers.IO) {
        execute("GET", "chats/contacts", null, ChatContactsResponse.serializer(), authHeader)
    }

    /** Історія приватного діалогу. */
    suspend fun getPrivateMessages(authHeader: String, userId: Int, limit: Int = 50, offset: Long = 0): ApiResult =
        withContext(Dispatchers.IO) {
            execute("GET", "chats/$userId?limit=$limit&offset=$offset", null, ChatMessagesResponse.serializer(), authHeader)
        }

    /** Надіслати текст у приватний діалог (+2 XP за повідомлення). */
    suspend fun sendPrivateMessage(authHeader: String, userId: Int, text: String): ApiResult =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(ChatMessageSendRequest.serializer(), ChatMessageSendRequest(text))
            execute("POST", "chats/$userId", body, UnitResult.serializer(), authHeader)
        }

    /** Надіслати фото у приватний діалог. */
    suspend fun sendPrivatePhoto(authHeader: String, userId: Int, bytes: ByteArray): ApiResult =
        withContext(Dispatchers.IO) {
            executeMultipart(
                "chats/$userId/photo", authHeader,
                listOf(
                    MultipartPart(name = "file", filename = "photo.jpg", contentType = "image/jpeg", bytes = bytes)
                ),
                UnitResult.serializer(),
            )
        }

    /** Позначити приватний діалог прочитаним (при відкритті чату). */
    suspend fun markChatRead(authHeader: String, userId: Int): ApiResult = withContext(Dispatchers.IO) {
        execute("POST", "chats/$userId/read", null, UnitResult.serializer(), authHeader)
    }

    /** Історія групового чату. */
    suspend fun getGroupChat(authHeader: String, groupId: Int, limit: Int = 50, offset: Long = 0): ApiResult =
        withContext(Dispatchers.IO) {
            execute("GET", "groups/$groupId/chat?limit=$limit&offset=$offset", null, ChatMessagesResponse.serializer(), authHeader)
        }

    /** Текст у груповий чат. */
    suspend fun sendGroupMessage(authHeader: String, groupId: Int, text: String): ApiResult =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(ChatMessageSendRequest.serializer(), ChatMessageSendRequest(text))
            execute("POST", "groups/$groupId/chat", body, UnitResult.serializer(), authHeader)
        }

    /** Фото в груповий чат. */
    suspend fun sendGroupPhoto(authHeader: String, groupId: Int, bytes: ByteArray): ApiResult =
        withContext(Dispatchers.IO) {
            executeMultipart(
                "groups/$groupId/chat/photo", authHeader,
                listOf(
                    MultipartPart(name = "file", filename = "photo.jpg", contentType = "image/jpeg", bytes = bytes)
                ),
                UnitResult.serializer(),
            )
        }

    /** Позначити груповий чат прочитаним. */
    suspend fun markGroupChatRead(authHeader: String, groupId: Int): ApiResult = withContext(Dispatchers.IO) {
        execute("POST", "groups/$groupId/chat/read", null, UnitResult.serializer(), authHeader)
    }

    // ===== 1.6.2: реакції, «друкує…», блокування, лідерборд =====

    /** Перемкнути реакцію на повідомлення (той самий емодзі ще раз — зняти). */
    suspend fun reactToMessage(authHeader: String, messageId: Int, emoji: String): ApiResult =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(
                ChatReactionToggleRequest.serializer(),
                ChatReactionToggleRequest(emoji),
            )
            execute("POST", "chats/message/$messageId/react", body, ChatMessageDto.serializer(), authHeader)
        }

    /** Позначка «друкує…» в чаті (клієнт тротлить 1 раз за 3 с). */
    suspend fun sendTyping(authHeader: String, peerUserId: Int? = null, groupId: Int? = null): ApiResult =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(
                ChatTypingRequest.serializer(),
                ChatTypingRequest(peerUserId = peerUserId, groupId = groupId),
            )
            execute("POST", "chats/typing", body, UnitResult.serializer(), authHeader)
        }

    /** Хтось друкує в чаті? */
    suspend fun getTyping(authHeader: String, peerUserId: Int? = null, groupId: Int? = null): ApiResult =
        withContext(Dispatchers.IO) {
            val q = if (groupId != null) "groupId=$groupId" else "peerUserId=$peerUserId"
            execute("GET", "chats/typing?$q", null, TypingResponse.serializer(), authHeader)
        }

    /** Заблокувати/розблокувати користувача (toggle). */
    suspend fun toggleBlock(authHeader: String, userId: Int): ApiResult = withContext(Dispatchers.IO) {
        execute("POST", "blocks/$userId", null, BlockToggleResponse.serializer(), authHeader)
    }

    /** Лідерборд XP: топ-50 + моє місце. */
    suspend fun getLeaderboard(authHeader: String): ApiResult = withContext(Dispatchers.IO) {
        execute("GET", "xp/leaderboard", null, LeaderboardResponse.serializer(), authHeader)
    }

    // ===== Рілси (1.6.0) =====

    /** Лента рілсів (публічна; для залогінених — лайки «мої»). */
    suspend fun getReels(authHeader: String?, limit: Int = 20, offset: Long = 0): ApiResult =
        withContext(Dispatchers.IO) {
            execute("GET", "reels?limit=$limit&offset=$offset", null, ReelsResponse.serializer(), authHeader)
        }

    /** Опублікувати рилс: відео mp4/webm/mov ≤50 МБ + підпис. */
    suspend fun createReel(authHeader: String, video: ByteArray, caption: String): ApiResult =
        withContext(Dispatchers.IO) {
            executeMultipart(
                "reels", authHeader,
                listOf(
                    MultipartPart(name = "caption", value = caption),
                    MultipartPart(name = "video", filename = "reel.mp4", contentType = "video/mp4", bytes = video),
                ),
                PostCreatedResponse.serializer(),
            )
        }

    /** Лайк рилса — toggle, як у постів. */
    suspend fun toggleReelLike(authHeader: String, reelId: Int): ApiResult = withContext(Dispatchers.IO) {
        execute("POST", "reels/$reelId/like", null, ReelLikeResponse.serializer(), authHeader)
    }

    /** Видалити свій рилс. */
    suspend fun deleteReel(authHeader: String, reelId: Int): ApiResult = withContext(Dispatchers.IO) {
        execute("DELETE", "reels/$reelId", null, UnitResult.serializer(), authHeader)
    }

    // ===== Core =====

    /**
     * Один виконавець для всіх запитів: збирає запит, надсилає, читає
     * конверт і розбирає data у конкретний тип [dataSerializer].
     */
    private suspend fun <T : Any> execute(
        method: String,
        path: String,
        bodyJson: String?,
        dataSerializer: KSerializer<T>,
        authHeader: String?,
    ): ApiResult = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url(baseUrl + path)
        when (method) {
            "POST" -> requestBuilder.post((bodyJson ?: "").toRequestBody(jsonMediaType))
            "PUT" -> requestBuilder.put((bodyJson ?: "").toRequestBody(jsonMediaType))
            // 1.2.2: DELETE раніше не був оброблений — запит ішов як GET,
            // і сервер відповідав 405; видалення не спрацьовувало.
            "DELETE" -> requestBuilder.delete()
            // 1.6.1: редагування постів.
            "PATCH" -> requestBuilder.patch((bodyJson ?: "").toRequestBody(jsonMediaType))
            "GET" -> requestBuilder.get()
        }
        authHeader?.let { requestBuilder.addHeader("Authorization", it) }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                val envelope = try {
                    json.decodeFromString(Envelope.serializer(), bodyString)
                } catch (_: Exception) {
                    null
                }

                if (!response.isSuccessful) {
                    ApiResult.Failure(
                        code = response.code,
                        error = envelope?.error ?: bodyString.takeIf { it.isNotBlank() }
                            ?: "HTTP ${response.code}",
                        errorCode = envelope?.errorCode,
                    )
                } else if (envelope == null) {
                    ApiResult.Failure(response.code, "Незрозуміла відповідь сервера", "BAD_BODY")
                } else if (!envelope.success) {
                    ApiResult.Failure(
                        response.code,
                        envelope.error ?: "Невідома помилка",
                        envelope.errorCode,
                    )
                } else {
                    val element = envelope.data
                        ?: return@use ApiResult.Failure(response.code, "Порожня відповідь сервера", "EMPTY_DATA")
                    val typed = json.decodeFromJsonElement(dataSerializer, element)
                    ApiResult.Success(typed)
                }
            }
        } catch (e: Exception) {
            ApiResult.NetworkError(e.message ?: "Мережева помилка")
        }
    }

    /** Конверт відповіді: data лишається сирим JsonElement до розбору. */
    @kotlinx.serialization.Serializable
    private data class Envelope(
        val success: Boolean,
        val data: JsonElement? = null,
        val error: String? = null,
        val errorCode: String? = null,
    )

    /** Одна частина multipart-запита: або текстове поле, або файл. */
    class MultipartPart(
        val name: String,
        val value: String? = null,
        val filename: String? = null,
        val contentType: String? = null,
        val bytes: ByteArray? = null,
    )

    /**
     * Multipart-відправник: файл або текстове поле. Відповідь — той самий
     * конверт, що й у звичайних викликів.
     */
    private suspend fun <T : Any> executeMultipart(
        path: String,
        authHeader: String?,
        parts: List<MultipartPart>,
        dataSerializer: KSerializer<T>,
    ): ApiResult = withContext(Dispatchers.IO) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .apply {
                parts.forEach { part ->
                    if (part.bytes != null && part.filename != null) {
                        val media = (part.contentType ?: "application/octet-stream").toMediaType()
                        addFormDataPart(
                            name = part.name,
                            filename = part.filename,
                            body = part.bytes.toRequestBody(media),
                        )
                    } else {
                        addFormDataPart(name = part.name, value = part.value.orEmpty())
                    }
                }
            }
            .build()
        val request = Request.Builder()
            .url(baseUrl + path)
            .post(requestBody)
            .apply { authHeader?.let { addHeader("Authorization", it) } }
            .build()

        try {
            client.newCall(request).execute().use { response ->
                parseEnvelope(response, dataSerializer)
            }
        } catch (e: Exception) {
            ApiResult.NetworkError(e.message ?: "Мережева помилка")
        }
    }

    /** Спільне розбирання конверта з OkHttp-відповіді. */
    private fun <T : Any> parseEnvelope(response: Response, dataSerializer: KSerializer<T>): ApiResult {
        val bodyString = response.body?.string() ?: ""
        val envelope = try {
            json.decodeFromString(Envelope.serializer(), bodyString)
        } catch (_: Exception) {
            null
        }
        if (!response.isSuccessful) {
            return ApiResult.Failure(
                code = response.code,
                error = envelope?.error ?: bodyString.takeIf { it.isNotBlank() }
                    ?: "HTTP ${response.code}",
                errorCode = envelope?.errorCode,
            )
        }
        if (envelope == null) {
            return ApiResult.Failure(response.code, "Незрозуміла відповідь сервера", "BAD_BODY")
        }
        if (!envelope.success) {
            return ApiResult.Failure(
                response.code,
                envelope.error ?: "Невідома помилка",
                envelope.errorCode,
            )
        }
        val element = envelope.data
            ?: return ApiResult.Failure(response.code, "Порожня відповідь сервера", "EMPTY_DATA")
        return try {
            ApiResult.Success(json.decodeFromJsonElement(dataSerializer, element))
        } catch (e: Exception) {
            ApiResult.Failure(response.code, "Незрозумілий формат відповіді", "BAD_DATA")
        }
    }
}
