package dev.amps.backend.routing

import dev.amps.backend.model.ApiError
import dev.amps.backend.model.ApiResponse
import dev.amps.backend.model.TwoFactorVerifyRequest
import dev.amps.backend.service.AuthService
import dev.amps.backend.service.SyncService
import dev.amps.backend.service.TwoFactorService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/**
 * 1.1.0: всі маршрути бекенду.
 *
 * `GET /health` — для Railway healthcheck і для застосунку.
 * Решта — під префіксом /auth: публічні (реєстрація, вхід, коди, скидання
 * пароля, оновлення токенів) і захищені JWT (профіль, 2FA, синхронізація).
 */
fun Application.authRoutes(auth: AuthService, twoFactor: TwoFactorService, sync: SyncService) {
    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok", "version" to "1.2.0"))
        }

        route("/auth") {
            post("/register") { call.respondApi(auth.register(call.receive())) }
            post("/login") { call.respondApi(auth.login(call.receive())) }
            // 1.2.0: вхід/реєстрація через Google — ID-токен Credential Manager.
            post("/google") { call.respondApi(auth.googleLogin(call.receive())) }
            post("/verify") { call.respondApi(auth.verifyCode(call.receive())) }
            post("/password/reset") { call.respondApi(auth.requestPasswordReset(call.receive())) }
            post("/password/reset/confirm") { call.respondApi(auth.confirmPasswordReset(call.receive())) }
            post("/refresh") { call.respondApi(auth.refresh(call.receive())) }

            authenticate("auth-jwt") {
                get("/me") { call.requireUid()?.let { call.respondApi(auth.profile(it)) } }
                get("/2fa/status") { call.requireUid()?.let { call.respondApi(twoFactor.status(it)) } }
                post("/2fa/setup") { call.requireUid()?.let { call.respondApi(twoFactor.setup(it)) } }
                post("/2fa/verify") {
                    val body = call.receive<TwoFactorVerifyRequest>()
                    call.requireUid()?.let { call.respondApi(twoFactor.verify(it, body.code)) }
                }
                post("/2fa/disable") { call.requireUid()?.let { call.respondApi(twoFactor.disable(it)) } }
                get("/sync") { call.requireUid()?.let { call.respondApi(sync.get(it)) } }
                post("/sync") {
                    val body = call.receive<dev.amps.backend.model.SyncRequest>()
                    call.requireUid()?.let { call.respondApi(sync.push(it, body)) }
                }
            }
        }
    }
}

/** Ідентифікатор користувача з JWT або 401. Спільний для auth- і feed-роутингу. */
internal suspend fun ApplicationCall.requireUid(): Int? {
    val uid = principal<JWTPrincipal>()?.payload?.getClaim("uid")?.asInt()
    if (uid == null || uid <= 0) {
        respond(HttpStatusCode.Unauthorized, ApiError(error = "Не авторизовано", errorCode = "UNAUTHORIZED"))
        return null
    }
    return uid
}

/**
 * 1.1.3: ідентифікатор з JWT без 401 — для публічних маршрутів читання
 * (гостева стрічка). Немає токена або він невалідний — null, відповідь
 * формує сам маршрут.
 */
internal suspend fun ApplicationCall.optionalUid(): Int? =
    principal<JWTPrincipal>()?.payload?.getClaim("uid")?.asInt()?.takeIf { it > 0 }

/**
 * Успіх — 200 із повним конвертом; невдача — 400 (409 для конфлікту
 * версій синхронізації). Клієнт читає errorCode у обох випадках.
 * Спільний для auth- і feed-роутингу.
 */
internal suspend inline fun <reified T : Any> ApplicationCall.respondApi(result: ApiResponse<T>) {
    if (result.success) {
        respond(result)
    } else {
        val status = if (result.errorCode == "VERSION_CONFLICT") {
            HttpStatusCode.Conflict
        } else {
            HttpStatusCode.BadRequest
        }
        respond(status, result)
    }
}
