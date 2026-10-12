package dev.amps.backend.routing

import dev.amps.backend.config.AppConfig
import dev.amps.backend.model.ApiError
import dev.amps.backend.model.ProfileUpdateRequest
import dev.amps.backend.service.FeedService
import dev.amps.backend.service.MediaStorage
import dev.amps.backend.service.ProfileService
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray

/**
 * 1.1.2: маршрути спільноти та профіля.
 *
 * Захищені JWT: стрічка, створення поста, лайк, репост, профіль.
 * Роздача медіа — публічна: імена файлів — UUID, невгадувані, і
 * застосунку простіше завантажувати картинки без заголовка токена.
 *
 * Розмір перевіряється ще при читанні: файл більший за ліміт до сховища
 * не потрапляє взагалі.
 */
fun Application.feedRoutes(
    config: AppConfig,
    feedService: FeedService,
    profileService: ProfileService,
    media: MediaStorage,
    stories: dev.amps.backend.service.StoryService,
    // 1.2.2: коментарі, підписки, аніме-список.
    comments: dev.amps.backend.service.CommentService,
    follows: dev.amps.backend.service.FollowService,
    animeList: dev.amps.backend.service.AnimeListService,
    // 1.2.3: опитування під постами.
    polls: dev.amps.backend.service.PollService,
    // 1.4.0: резолвер «Дивитись» — сторінка тайтла на yummyani.
    animeWatch: dev.amps.backend.service.AnimeWatchService,
) {
    routing {
        // 1.4.0: публічний резолвер — знаходить сторінку тайтла на
        // yummyani.me за назвою (їхній відкритий пошуковий API + кеш).
        get("/anime/watch") {
            val q = call.parameters["q"].orEmpty()
            if (q.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(error = "Потрібна назва тайтла (?q=...)"),
                )
            } else {
                call.respondApi(animeWatch.resolve(q))
            }
        }
        // Роздача медіа — публічна: імена файлів — невгадувані UUID.
        get("/media/{kind}/{file}") {
            val kind = call.parameters["kind"].orEmpty()
            val file = call.parameters["file"].orEmpty()
            val target = media.resolve(kind, file)
            if (target == null) {
                call.respond(HttpStatusCode.NotFound, ApiError(error = "Файл не знайдено"))
            } else {
                call.respondFile(target)
            }
        }

        authenticate("auth-jwt") {
            // --- профіль ---
            get("/profile") {
                call.requireUid()?.let { uid -> call.respondApi(profileService.get(uid)) }
            }
            put("/profile") {
                val body = call.receive<ProfileUpdateRequest>()
                call.requireUid()?.let { uid ->
                    call.respondApi(
                        profileService.update(
                            uid,
                            body.displayName,
                            body.bio,
                            body.favoriteGenres,
                            body.favoriteAnime,
                        )
                    )
                }
            }
            post("/profile/avatar") {
                val uid = call.requireUid() ?: return@post
                val upload = call.receiveUpload() ?: return@post
                call.respondApi(profileService.setAvatar(uid, upload.bytes))
            }

            // --- стрічка: створення — лише з токеном ---
            post("/feed") {
                val uid = call.requireUid() ?: return@post
                val upload = call.receivePost(media) ?: return@post
                call.respondApi(feedService.createPost(uid, upload.text, upload.files))
            }
            post("/feed/{id}/like") {
                call.requireUid()?.let { uid ->
                    val id = call.parameters["id"]?.toIntOrNull()
                    if (id == null) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ApiError(error = "Невірний ідентифікатор поста"),
                        )
                    } else {
                        call.respondApi(feedService.toggleLike(uid, id))
                    }
                }
            }
            post("/feed/{id}/repost") {
                call.requireUid()?.let { uid ->
                    val id = call.parameters["id"]?.toIntOrNull()
                    if (id == null) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ApiError(error = "Невірний ідентифікатор поста"),
                        )
                    } else {
                        call.respondApi(feedService.toggleRepost(uid, id))
                    }
                }
            }

            // --- сторіс (1.2.0): публікація і видалення — лише свої ---
            post("/stories") {
                val uid = call.requireUid() ?: return@post
                val upload = call.receiveStory(media) ?: return@post
                call.respondApi(stories.create(uid, upload.kind, upload.fileName))
            }
            delete("/stories/{id}") {
                call.requireUid()?.let { uid ->
                    val id = call.parameters["id"]?.toIntOrNull()
                    if (id == null) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ApiError(error = "Невірний ідентифікатор сторіс"),
                        )
                    } else {
                        val removed = stories.delete(uid, id)
                        if (removed) {
                            call.respond(dev.amps.backend.model.ApiResponse(success = true, data = true))
                        } else {
                            call.respond(
                                HttpStatusCode.NotFound,
                                ApiError(error = "Сторіс не знайдено (або це не ваша)", errorCode = "NO_STORY"),
                            )
                        }
                    }
                }
            }
        }

        authenticate("auth-jwt", optional = true) {
            get("/feed") {
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 20)
                    .coerceIn(1, 50)
                val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L)
                    .coerceAtLeast(0)
                // 1.2.2: scope=following — стрічка лише підписок (потрібен токен).
                val scope = call.request.queryParameters["scope"] ?: "all"
                call.respondApi(feedService.feed(call.optionalUid() ?: 0, limit, offset, scope))
            }

            // 1.2.0: сторіс видно всім — як і стрічку; лише живі, свіжі зверху.
            get("/stories") {
                call.respondApi(dev.amps.backend.model.ApiResponse.ok(
                    dev.amps.backend.model.StoriesResponse(stories.list())
                ))
            }

            // 1.2.2: коментарі читаються публічно, як і стрічка.
            get("/posts/{id}/comments") {
                val id = call.parameters["id"]?.toIntOrNull()
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, ApiError(error = "Невірний id поста"))
                } else {
                    call.respondApi(dev.amps.backend.model.ApiResponse.ok(
                        dev.amps.backend.model.CommentsResponse(comments = comments.list(id))
                    ))
                }
            }
        }

        authenticate("auth-jwt") {
            // --- 1.2.2: коментарі ---
            post("/posts/{id}/comments") {
                val uid = call.requireUid() ?: return@post
                val id = call.parameters["id"]?.toIntOrNull()
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, ApiError(error = "Невірний id поста"))
                } else {
                    val body = call.receive<dev.amps.backend.model.CommentCreateRequest>()
                    val id = comments.add(uid, id, body.text)
                    if (id == null) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ApiError(error = "Коментар порожній або поста немає", errorCode = "BAD_COMMENT"),
                        )
                    } else {
                        call.respondApi(dev.amps.backend.model.ApiResponse.ok(
                            dev.amps.backend.model.CommentCreatedResponse(commentId = id)
                        ))
                    }
                }
            }
            delete("/comments/{id}") {
                val uid = call.requireUid() ?: return@delete
                val id = call.parameters["id"]?.toIntOrNull()
                if (id == null || !comments.delete(uid, id)) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ApiError(error = "Коментар не знайдено (або це не ваш)", errorCode = "NO_COMMENT"),
                    )
                } else {
                    call.respondApi(dev.amps.backend.model.ApiResponse.ok(
                        dev.amps.backend.model.CommentsResponse(comments = emptyList())
                    ))
                }
            }

            // --- 1.2.3: опитування ---
            post("/posts/{id}/poll") {
                val uid = call.requireUid() ?: return@post
                val postId = call.parameters["id"]?.toIntOrNull()
                val body = call.receive<dev.amps.backend.model.PollCreateRequest>()
                val createdPollId = if (postId != null) polls.createForPost(
                    uid = uid,
                    postId = postId,
                    questionText = body.question,
                    optionTexts = body.options,
                ) else null
                if (postId == null || createdPollId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError(error = "Опитування: 2–4 варіанти, пост ваш", errorCode = "BAD_POLL"),
                    )
                } else {
                    call.respondApi(dev.amps.backend.model.ApiResponse.ok(
                        dev.amps.backend.model.PollResponse(
                            poll = polls.forPost(postId, uid) ?: dev.amps.backend.model.PollDto(
                                pollId = createdPollId, question = null, options = emptyList(),
                            )
                        )
                    ))
                }
            }
            post("/poll/{id}/vote/{optionId}") {
                val uid = call.requireUid() ?: return@post
                val pollId = call.parameters["id"]?.toIntOrNull()
                val optionId = call.parameters["optionId"]?.toIntOrNull()
                val poll = if (pollId != null && optionId != null) polls.vote(uid, pollId, optionId) else null
                if (poll == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError(error = "Опитування або варіант не знайдено", errorCode = "NO_POLL"),
                    )
                } else {
                    call.respondApi(dev.amps.backend.model.ApiResponse.ok(
                        dev.amps.backend.model.PollResponse(poll = poll)
                    ))
                }
            }

            // --- 1.2.5: підписка — toggle (клієнт шле POST follow/{userId}) ---
            post("/follow/{userId}") {
                val uid = call.requireUid() ?: return@post
                val targetId = call.parameters["userId"]?.toIntOrNull()
                if (targetId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError(error = "Невірний користувач", errorCode = "BAD_FOLLOW"),
                    )
                    return@post
                }
                val result = follows.toggle(uid, targetId)
                if (result == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError(error = "На себе підписатись не можна", errorCode = "BAD_FOLLOW"),
                    )
                } else {
                    call.respondApi(dev.amps.backend.model.ApiResponse.ok(
                        dev.amps.backend.model.FollowToggleResponse(following = result.first, followers = result.second)
                    ))
                }
            }

            // --- 1.2.5: чужий профіль — дані + чи підписаний + лічильники ---
            get("/profile/{userId}") {
                val uid = call.requireUid() ?: return@get
                val targetId = call.parameters["userId"]?.toIntOrNull() ?: 0
                val user = org.jetbrains.exposed.sql.transactions.transaction {
                    dev.amps.backend.model.UserEntity.findById(targetId)
                }
                if (user == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ApiError(error = "Профіль не знайдено", errorCode = "NO_PROFILE"),
                    )
                    return@get
                }
                val profileDto = profileService.get(targetId).data
                if (profileDto == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ApiError(error = "Профіль не знайдено", errorCode = "NO_PROFILE"),
                    )
                    return@get
                }
                call.respondApi(dev.amps.backend.model.ApiResponse.ok(
                    dev.amps.backend.model.UserProfileResponse(
                        profile = profileDto,
                        isFollowing = follows.isFollowing(uid, targetId),
                        followers = follows.followerCount(targetId),
                        postsCount = org.jetbrains.exposed.sql.transactions.transaction {
                            dev.amps.backend.model.PostEntity
                                .find { dev.amps.backend.model.PostTable.userId eq targetId }
                                .count().toInt()
                        },
                    )
                ))
            }

            // --- 1.2.5: пости і репости користувача (чужий профіль) ---
            get("/profile/{userId}/posts") {
                val uid = call.requireUid() ?: return@get
                val targetId = call.parameters["userId"]?.toIntOrNull() ?: 0
                if (targetId <= 0) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError(error = "Невірний користувач", errorCode = "BAD_PROFILE"),
                    )
                    return@get
                }
                call.respondApi(feedService.userPosts(targetId, uid))
            }
            get("/profile/{userId}/reposts") {
                val uid = call.requireUid() ?: return@get
                val targetId = call.parameters["userId"]?.toIntOrNull() ?: 0
                if (targetId <= 0) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError(error = "Невірний користувач", errorCode = "BAD_USER"),
                    )
                    return@get
                }
                call.respondApi(feedService.userReposts(targetId, uid))
            }

            // --- 1.2.3: статистика профілю (пости / підписники / аніме) ---
            get("/profile/stats") {
                val uid = call.requireUid() ?: return@get
                val postsCount = org.jetbrains.exposed.sql.transactions.transaction {
                    dev.amps.backend.model.PostEntity
                        .find { dev.amps.backend.model.PostTable.userId eq uid }
                        .count().toInt()
                }
                val watchingItems = animeList.list(uid).filter { it.status == "watching" }
                // 1.2.4: XP і рівень.
                val xp = dev.amps.backend.service.XpLedger.xpOf(uid)
                val level = dev.amps.backend.service.XpLedger.levelFor(xp)
                call.respondApi(dev.amps.backend.model.ApiResponse.ok(
                    dev.amps.backend.model.ProfileStatsDto(
                        posts = postsCount,
                        followers = follows.followerCount(uid),
                        watching = watchingItems.size,
                        watchingTitle = watchingItems.firstOrNull()?.title,
                        xp = xp,
                        level = level,
                        xpToNext = if (level >= 100) 0 else dev.amps.backend.service.XpLedger.xpForLevel(level + 1) - xp,
                    )
                ))
            }

            // --- 1.2.2: аніме-список акаунта ---
            get("/anime/list") {
                val uid = call.requireUid() ?: return@get
                call.respondApi(dev.amps.backend.model.ApiResponse.ok(
                    dev.amps.backend.model.AnimeListResponse(animeList.list(uid))
                ))
            }
            put("/anime/list") {
                val uid = call.requireUid() ?: return@put
                val body = call.receive<dev.amps.backend.model.AnimeListUpsertRequest>()
                val saved = animeList.upsert(
                    uid = uid,
                    anilistId = body.anilistId,
                    animeTitle = body.title,
                    cover = body.coverUrl,
                    animeStatus = body.status,
                    withReminder = body.reminder,
                )
                if (saved == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError(error = "Невірні дані тайтла", errorCode = "BAD_ANIME"),
                    )
                } else {
                    call.respondApi(dev.amps.backend.model.ApiResponse.ok(
                        dev.amps.backend.model.AnimeListResponse(animeList.list(uid))
                    ))
                }
            }
            delete("/anime/list/{anilistId}") {
                val uid = call.requireUid() ?: return@delete
                val aid = call.parameters["anilistId"]?.toIntOrNull()
                if (aid == null || !animeList.delete(uid, aid)) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ApiError(error = "Тайтл не знайдено у списку", errorCode = "NO_ANIME"),
                    )
                } else {
                    call.respondApi(dev.amps.backend.model.ApiResponse.ok(
                        dev.amps.backend.model.AnimeListResponse(animeList.list(uid))
                    ))
                }
            }
        }
    }
}

/** Вміст завантаженого файлу — уже прочитаний у пам'ять. */
internal class Upload(val bytes: ByteArray)

/**
 * Читає multipart-запит аватара: перший файл. Повертає null, коли відповідь
 * уже надіслано клієнту (немає файлу, перевищено ліміт, зчитування зірвалося).
 */
private suspend fun ApplicationCall.receiveUpload(): Upload? {
    var bytes: ByteArray? = null
    var tooLarge = false
    try {
        receiveMultipart().forEachPart { part ->
            if (part is PartData.FileItem && bytes == null && !tooLarge) {
                val read = part.provider().readCapped(AppConfig.MAX_PHOTO_BYTES)
                if (read == null) {
                    tooLarge = true
                } else {
                    bytes = read
                }
            }
            part.dispose()
        }
    } catch (e: Throwable) {
        respond(HttpStatusCode.BadRequest, ApiError(error = "Не вдалося прочитати запит", errorCode = "BAD_UPLOAD"))
        return null
    }
    when {
        tooLarge -> {
            respond(
                HttpStatusCode.PayloadTooLarge,
                ApiError(error = "Аватар більший за 10 МБ", errorCode = "TOO_LARGE"),
            )
            return null
        }
        bytes == null -> {
            respond(HttpStatusCode.BadRequest, ApiError(error = "Файл аватара не знайдено", errorCode = "NO_FILE"))
            return null
        }
    }
    return Upload(bytes!!)
}

/** Текст поста і вже збережені вкладення: пари «вид — ім'я файлу». */
internal data class PostUpload(val text: String, val files: List<Pair<String, String>>)

/**
 * Читає multipart-запит поста: текстове поле "text" і файли "photo"/"video".
 * Кожен файл обмежений лімітом свого виду (фото 10 МБ, відео 50 МБ) і
 * білим списком розширень; невідомі частини пропускаються. Медіа зберігаються
 * у сховище одразу тут — сервіс отримує лише імена файлів.
 */
private suspend fun ApplicationCall.receivePost(media: MediaStorage): PostUpload? {
    var text = ""
    var tooLarge = false
    var failed = false
    val files = mutableListOf<Pair<String, String>>()
    try {
        receiveMultipart().forEachPart { part ->
            when (part) {
                is PartData.FormItem -> if (part.name == "text") text = part.value
                is PartData.FileItem -> {
                    val kind = when (part.name) {
                        "photo" -> MediaStorage.KIND_PHOTO
                        "video" -> MediaStorage.KIND_VIDEO
                        else -> null
                    }
                    if (kind != null && !tooLarge && !failed && files.size < 6) {
                        val cap = if (kind == MediaStorage.KIND_VIDEO) {
                            AppConfig.MAX_VIDEO_BYTES
                        } else {
                            AppConfig.MAX_PHOTO_BYTES
                        }
                        val bytes = part.provider().readCapped(cap)
                        val ext = media.extensionOf(part.originalFileName, part.contentType?.toString(), kind)
                        when {
                            bytes == null -> tooLarge = true
                            ext == null -> Unit // не зображення і не відео — вкладення пропускається
                            else -> {
                                val name = media.save(kind, bytes, ext)
                                if (name == null) failed = true else files.add(kind to name)
                            }
                        }
                    }
                }
                else -> Unit
            }
            part.dispose()
        }
    } catch (e: Throwable) {
        respond(HttpStatusCode.BadRequest, ApiError(error = "Не вдалося прочитати запит", errorCode = "BAD_UPLOAD"))
        return null
    }
    when {
        tooLarge -> {
            respond(
                HttpStatusCode.PayloadTooLarge,
                ApiError(error = "Вкладення більші за дозволений ліміт", errorCode = "TOO_LARGE"),
            )
            return null
        }
        failed -> {
            respond(
                HttpStatusCode.InternalServerError,
                ApiError(error = "Сховище медіа недоступне", errorCode = "MEDIA_UNAVAILABLE"),
            )
            return null
        }
    }
    return PostUpload(text = text, files = files)
}

/**
 * Читає канал до [cap] байтів. Повертає null, якщо файл більший —
 * зайве навіть у пам'ять не потрапляє: читається щонайбільше cap+1 байт.
 */
private suspend fun ByteReadChannel.readCapped(cap: Long): ByteArray? {
    val buffer = readBuffer((cap + 1).toInt())
    val bytes = buffer.readByteArray()
    return if (bytes.size.toLong() > cap) null else bytes
}

/** Сторіс до запису в базу: вид і вже збережене ім'я файлу. */
internal data class StoryUpload(val kind: String, val fileName: String)

/**
 * 1.2.0: читає multipart-запит сторіс — один файл "photo" або "video".
 * Ліміти ті самі, що в постів (фото 10 МБ, відео 50 МБ), білий список
 * розширень спільний зі стрічкою; файл кладеться у сховище одразу тут.
 */
private suspend fun ApplicationCall.receiveStory(media: MediaStorage): StoryUpload? {
    var kind: String? = null
    var fileName: String? = null
    var tooLarge = false
    var failed = false
    try {
        receiveMultipart().forEachPart { part ->
            when (part) {
                is PartData.FormItem -> Unit // текст у сторіс немає: лише медіа
                is PartData.FileItem -> {
                    if (kind == null && !tooLarge && !failed) {
                        val fileKind = when (part.name) {
                            "photo" -> MediaStorage.KIND_PHOTO
                            "video" -> MediaStorage.KIND_VIDEO
                            else -> null
                        }
                        if (fileKind != null) {
                            val cap = if (fileKind == MediaStorage.KIND_VIDEO) {
                                AppConfig.MAX_VIDEO_BYTES
                            } else {
                                AppConfig.MAX_PHOTO_BYTES
                            }
                            val bytes = part.provider().readCapped(cap)
                            val ext = media.extensionOf(part.originalFileName, part.contentType?.toString(), fileKind)
                            when {
                                bytes == null -> tooLarge = true
                                ext == null -> Unit // не зображення і не відео — пропускається
                                else -> {
                                    val name = media.save(fileKind, bytes, ext)
                                    if (name == null) failed = true else {
                                        kind = fileKind
                                        fileName = name
                                    }
                                }
                            }
                        }
                    }
                }
                else -> Unit
            }
            part.dispose()
        }
    } catch (e: Throwable) {
        respond(HttpStatusCode.BadRequest, ApiError(error = "Не вдалося прочитати запит", errorCode = "BAD_UPLOAD"))
        return null
    }
    when {
        tooLarge -> {
            respond(
                HttpStatusCode.PayloadTooLarge,
                ApiError(error = "Сторіс більша за дозволений ліміт (фото 10 МБ, відео 50 МБ)", errorCode = "TOO_LARGE"),
            )
            return null
        }
        failed -> {
            respond(
                HttpStatusCode.InternalServerError,
                ApiError(error = "Сховище медіа недоступне", errorCode = "MEDIA_UNAVAILABLE"),
            )
            return null
        }
        kind == null -> {
            respond(
                HttpStatusCode.BadRequest,
                ApiError(error = "Файл сторіс не знайдено (part 'photo' або 'video')", errorCode = "NO_FILE"),
            )
            return null
        }
    }
    return StoryUpload(kind!!, fileName!!)
}
