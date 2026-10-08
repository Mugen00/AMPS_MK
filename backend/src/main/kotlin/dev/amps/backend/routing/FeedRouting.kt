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
) {
    routing {
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
                    call.respondApi(profileService.update(uid, body.displayName, body.bio))
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
        }

        // 1.1.3: гостевий режим — читання стрічки публічне. З валідним
        // токеном сервер іще й повертає «лайкнуто мною»/«репостнув мною»,
        // без токена — гість бачить чисті лічильники (viewerId = 0).
        // Лайк, репост і публікація лишаються лише для аутентифікованих.
        authenticate("auth-jwt", optional = true) {
            get("/feed") {
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 20)
                    .coerceIn(1, 50)
                val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L)
                    .coerceAtLeast(0)
                call.respondApi(feedService.feed(call.optionalUid() ?: 0, limit, offset))
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
