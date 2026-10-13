package dev.amps.backend.routing

import dev.amps.backend.model.ApiError
import dev.amps.backend.model.ChatMessageSendRequest
import dev.amps.backend.model.GroupCreateRequest
import dev.amps.backend.model.GroupKickRequest
import dev.amps.backend.model.GroupRoleRequest
import dev.amps.backend.service.ChatService
import dev.amps.backend.service.FeedService
import dev.amps.backend.service.FollowService
import dev.amps.backend.service.GroupService
import dev.amps.backend.service.MediaStorage
import dev.amps.backend.service.ProfileService
import dev.amps.backend.service.ReelService
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray

/**
 * 1.6.0: маршрути груп, чатів і рілсів.
 *
 * Групи: публічні (вступ одразу) і приватні (запит, який схвалюють
 * власник/модератори), ролі owner/mod/member, лента постів групи і
 * груповий чат. Чати: приватні діалоги 1-на-1 + групові чати.
 * Рілси: вертикальні відео 9:16 зі своєю лентою. Роздача медіа —
 * публічна (загальний маршрут /media у feedRoutes).
 */
fun Application.socialRoutes(
    feedService: FeedService,
    profileService: ProfileService,
    media: MediaStorage,
    groups: GroupService,
    chats: ChatService,
    reels: ReelService,
    follows: FollowService,
) {
    groupRoutes(feedService, media, groups, chats)
    chatRoutes(media, chats, profiles = profileService, follows = follows)
    reelRoutes(media, reels)
}

/** Групи: каталог, створення, членство, ролі, запити, пости групи, груповий чат. */
fun Application.groupRoutes(
    feedService: FeedService,
    media: MediaStorage,
    groups: GroupService,
    chats: ChatService,
) {
    routing {
        // Каталог груп — публічний (назви й описи видно всім).
        get("/groups") {
            val q = call.parameters["q"]
            val uid = call.optionalUid() ?: 0
            call.respondApi(groups.list(q, uid))
        }

        authenticate("auth-jwt") {
            route("/groups") {
                post {
                    val uid = call.requireUid() ?: return@post
                    val body = call.receive<GroupCreateRequest>()
                    call.respondApi(groups.create(uid, body.name, body.description, body.isPrivate))
                }
                // Константні шляхи — до параметричного /{id} (Ktor віддає їм пріоритет).
                get("/my") {
                    call.requireUid()?.let { uid -> call.respondApi(groups.myGroups(uid)) }
                }
                get("/my-requests") {
                    call.requireUid()?.let { uid -> call.respondApi(groups.myRequests(uid)) }
                }
                get("/{id}") {
                    val id = call.parameters["id"]?.toIntOrNull()
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, ApiError(error = "Невірний ідентифікатор групи"))
                    } else {
                        call.requireUid()?.let { uid -> call.respondApi(groups.detail(id, uid)) }
                    }
                }
                post("/{id}/avatar") {
                    val uid = call.requireUid() ?: return@post
                    val id = call.parameters["id"]?.toIntOrNull() ?: return@post
                    val upload = call.receiveUpload() ?: return@post
                    if (upload.bytes.isEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, ApiError(error = "Порожній файл аватарки"))
                        return@post
                    }
                    val name = media.save(MediaStorage.KIND_AVATAR, upload.bytes, "jpg")
                    if (name == null) {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ApiError(error = "Сховище медіа недоступне", errorCode = "MEDIA_UNAVAILABLE"),
                        )
                    } else {
                        call.respondApi(groups.setAvatar(id, uid, name))
                    }
                }
                get("/{id}/members") {
                    val id = call.parameters["id"]?.toIntOrNull() ?: return@get
                    call.requireUid()?.let { call.respondApi(groups.members(id)) }
                }
                post("/{id}/join") {
                    val uid = call.requireUid() ?: return@post
                    val id = call.parameters["id"]?.toIntOrNull() ?: return@post
                    call.respondApi(groups.join(id, uid))
                }
                post("/{id}/leave") {
                    val uid = call.requireUid() ?: return@post
                    val id = call.parameters["id"]?.toIntOrNull() ?: return@post
                    call.respondApi(groups.leave(id, uid))
                }
                delete("/{id}") {
                    val uid = call.requireUid() ?: return@delete
                    val id = call.parameters["id"]?.toIntOrNull() ?: return@delete
                    call.respondApi(groups.delete(id, uid))
                }
                post("/{id}/kick") {
                    val uid = call.requireUid() ?: return@post
                    val id = call.parameters["id"]?.toIntOrNull() ?: return@post
                    val body = call.receive<GroupKickRequest>()
                    call.respondApi(groups.kick(id, uid, body.userId))
                }
                post("/{id}/role") {
                    val uid = call.requireUid() ?: return@post
                    val id = call.parameters["id"]?.toIntOrNull() ?: return@post
                    val body = call.receive<GroupRoleRequest>()
                    call.respondApi(groups.setRole(id, uid, body.userId, body.role))
                }
                get("/{id}/requests") {
                    val uid = call.requireUid() ?: return@get
                    val id = call.parameters["id"]?.toIntOrNull() ?: return@get
                    call.respondApi(groups.joinRequests(id, uid))
                }
                post("/{id}/requests/{rid}/approve") {
                    val uid = call.requireUid() ?: return@post
                    val id = call.parameters["id"]?.toIntOrNull() ?: return@post
                    val rid = call.parameters["rid"]?.toIntOrNull() ?: return@post
                    call.respondApi(groups.approveRequest(id, requestId = rid, requesterId = uid))
                }
                post("/{id}/requests/{rid}/reject") {
                    val uid = call.requireUid() ?: return@post
                    val id = call.parameters["id"]?.toIntOrNull() ?: return@post
                    call.respondApi(
                        groups.rejectRequest(id, requestId = call.parameters["rid"]?.toIntOrNull() ?: 0, requesterId = uid)
                    )
                }
                // Лента постів групи — тільки учасникам.
                get("/{id}/posts") {
                    val uid = call.requireUid() ?: return@get
                    val gid = call.parameters["id"]?.toIntOrNull() ?: return@get
                    if (!groups.isMember(gid, uid)) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            ApiError(error = "Пости групи доступні тільки учасникам", errorCode = "NOT_MEMBER"),
                        )
                    } else {
                        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 50)
                        val offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L
                        call.respondApi(feedService.groupPosts(gid, uid, limit, offset))
                    }
                }
                // Пост у групі: такий самий multipart, як звичайний пост.
                post("/{id}/posts") {
                    val uid = call.requireUid() ?: return@post
                    val gid = call.parameters["id"]?.toIntOrNull() ?: return@post
                    if (!groups.isMember(gid, uid)) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            ApiError(error = "Постити в групу можуть тільки учасники", errorCode = "NOT_MEMBER"),
                        )
                        return@post
                    }
                    val upload = call.receivePost(media) ?: return@post
                    call.respondApi(
                        feedService.createPost(
                            uid, upload.text, upload.files,
                            imageAspect = upload.imageAspect,
                            groupId = gid,
                        )
                    )
                }
                // Груповий чат: історія, текст, фото, прочитання.
                get("/{id}/chat") {
                    val uid = call.requireUid() ?: return@get
                    val gid = call.parameters["id"]?.toIntOrNull() ?: return@get
                    val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 100)
                    val offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L
                    call.respondApi(chats.historyGroup(gid, uid, limit, offset))
                }
                post("/{id}/chat") {
                    val uid = call.requireUid() ?: return@post
                    val gid = call.parameters["id"]?.toIntOrNull() ?: return@post
                    val body = call.receive<dev.amps.backend.model.ChatMessageSendRequest>()
                    call.respondApi(chats.sendGroup(uid, gid, body.text, null))
                }
                post("/{id}/chat/photo") {
                    val uid = call.requireUid() ?: return@post
                    val gid = call.parameters["id"]?.toIntOrNull() ?: return@post
                    val upload = call.receiveUpload() ?: return@post
                    val name = media.save(MediaStorage.KIND_PHOTO, upload.bytes, "jpg")
                    if (name == null) {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ApiError(error = "Сховище медіа недоступне", errorCode = "MEDIA_UNAVAILABLE"),
                        )
                    } else {
                        call.respondApi(chats.sendGroup(uid, gid, "", name))
                    }
                }
                post("/{id}/chat/read") {
                    val uid = call.requireUid() ?: return@post
                    call.respondApi(chats.markRead(uid, null, call.parameters["id"]?.toIntOrNull()))
                }
            }
        }
    }
}

/** Чати: список діалогів, історія, надсилання (текст або фото), прочитання. */
fun Application.chatRoutes(
    media: MediaStorage,
    chats: ChatService,
    profiles: ProfileService,
    follows: FollowService,
) {
    routing {
        authenticate("auth-jwt") {
            route("/chats") {
                get {
                    call.requireUid()?.let { uid -> call.respondApi(chats.dialogs(uid)) }
                }
                // Контакти для старту діалогу: ті, на кого я підписаний.
                get("/contacts") {
                    val uid = call.requireUid() ?: return@get
                    val contacts = follows.followingIds(uid).map {
                        profiles.profileOf(profiles.ensureProfile(it))
                    }
                    call.respondApi(
                        dev.amps.backend.model.ApiResponse.ok(
                            dev.amps.backend.model.ChatContactsResponse(contacts = contacts)
                        )
                    )
                }
                get("/{userId}") {
                    val uid = call.requireUid() ?: return@get
                    val peer = call.parameters["userId"]?.toIntOrNull() ?: return@get
                    val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 100)
                    val offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L
                    call.respondApi(chats.historyPrivate(uid, peer, limit, offset))
                }
                post("/{userId}") {
                    val uid = call.requireUid() ?: return@post
                    val peer = call.parameters["userId"]?.toIntOrNull() ?: return@post
                    val body = call.receive<dev.amps.backend.model.ChatMessageSendRequest>()
                    call.respondApi(chats.sendPrivate(uid, peer, body.text, null))
                }
                post("/{userId}/photo") {
                    val uid = call.requireUid() ?: return@post
                    val peer = call.parameters["userId"]?.toIntOrNull() ?: return@post
                    val upload = call.receiveUpload() ?: return@post
                    val ext = media.extensionOf("photo.jpg", "image/jpeg", MediaStorage.KIND_PHOTO)
                    val name = ext?.let { media.save(MediaStorage.KIND_PHOTO, upload.bytes, it) }
                    if (name == null) {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ApiError(error = "Сховище медіа недоступне", errorCode = "MEDIA_UNAVAILABLE"),
                        )
                    } else {
                        call.respondApi(chats.sendPrivate(uid, peer, "", name))
                    }
                }
                post("/{userId}/read") {
                    val uid = call.requireUid() ?: return@post
                    val peer = call.parameters["userId"]?.toIntOrNull() ?: return@post
                    call.respondApi(chats.markRead(uid, peerUserId = peer, groupId = null))
                }
            }
        }
    }
}

/** Рілси: вертикальні відео 9:16 зі своєю лентою. */
fun Application.reelRoutes(media: MediaStorage, reels: ReelService) {
    routing {
        // Лента рілсів — публічна (як стрічка); для залогінених — лайки «мої».
        get("/reels") {
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 50)
            val offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L
            call.respondApi(reels.list(call.optionalUid() ?: 0, limit, offset))
        }
        authenticate("auth-jwt") {
            post("/reels") {
                val uid = call.requireUid() ?: return@post
                val upload = call.receiveReel(media) ?: return@post
                call.respondApi(reels.create(uid, upload.videoFile, upload.caption))
            }
            post("/reels/{id}/like") {
                call.requireUid()?.let { uid ->
                    val id = call.parameters["id"]?.toIntOrNull()
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, ApiError(error = "Невірний ідентифікатор рилса"))
                    } else {
                        call.respondApi(reels.toggleLike(uid, id))
                    }
                }
            }
            delete("/reels/{id}") {
                call.requireUid()?.let { uid ->
                    val id = call.parameters["id"]?.toIntOrNull()
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, ApiError(error = "Невірний ідентифікатор рилса"))
                    } else {
                        call.respondApi(reels.delete(id, uid))
                    }
                }
            }
        }
    }
}

/** Рілс до запису в базу: ім'я відео у сховищі + підпис. */
internal data class ReelUpload(val videoFile: String, val caption: String)

/**
 * 1.6.0: читає multipart-запит рилса — один файл "video" (mp4/webm/mov,
 * ≤50 МБ) і текстове поле "caption". Медіа зберігається тут, сервіс
 * отримує лише ім'я файлу. Без non-local return — частини просто
 * збираються, а перевірка робиться після циклу.
 */
private suspend fun ApplicationCall.receiveReel(media: MediaStorage): ReelUpload? {
    var caption = ""
    var videoFile: String? = null
    var tooLarge = false
    try {
        receiveMultipart().forEachPart { part ->
            when (part) {
                is PartData.FormItem -> if (part.name == "caption") {
                    caption = part.value.trim().take(500)
                }
                is PartData.FileItem -> if (part.name == "video") {
                    val bytes = part.provider().readCapped(dev.amps.backend.config.AppConfig.MAX_VIDEO_BYTES)
                    val ext = media.extensionOf(part.originalFileName, part.contentType?.toString(), MediaStorage.KIND_VIDEO)
                    val saved = if (bytes == null) {
                        tooLarge = true
                        null
                    } else {
                        ext?.let { e -> media.save(MediaStorage.KIND_VIDEO, bytes, e) }
                    }
                    videoFile = saved ?: videoFile
                }
                else -> Unit
            }
            part.dispose()
        }
    } catch (e: Throwable) {
        respond(HttpStatusCode.BadRequest, ApiError(error = "Не вдалося прочитати запит", errorCode = "BAD_UPLOAD"))
        return null
    }
    if (tooLarge) {
        respond(
            HttpStatusCode.PayloadTooLarge,
            ApiError(error = "Відео більше за дозволений ліміт (50 МБ)", errorCode = "TOO_LARGE"),
        )
        return null
    }
    val file = videoFile
    if (file == null) {
        respond(
            HttpStatusCode.BadRequest,
            ApiError(error = "Потрібне відео mp4/webm/mov", errorCode = "BAD_UPLOAD"),
        )
        return null
    }
    return ReelUpload(file, caption)
}
