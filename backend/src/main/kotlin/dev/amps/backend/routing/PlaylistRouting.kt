package dev.amps.backend.routing

import dev.amps.backend.model.ApiError
import dev.amps.backend.model.ApiResponse
import dev.amps.backend.model.PlaylistCreateRequest
import dev.amps.backend.model.PlaylistCreatedResponse
import dev.amps.backend.model.PlaylistDto
import dev.amps.backend.model.PlaylistTrackAddRequest
import dev.amps.backend.model.PlaylistTrackDto
import dev.amps.backend.model.PlaylistsResponse
import dev.amps.backend.model.PlaylistTracksResponse
import dev.amps.backend.service.PlaylistService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

/**
 * 1.2.1: свої плейлісти — всі маршрути лише з токеном; кожен плейліст
 * належить одному акаунту (перевірка в сервісі). Відповіді — у спільному
 * конверті ApiResponse.ok(...), який застосунок уже вміє розбирати.
 */
fun Application.playlistRoutes(playlists: PlaylistService) {
    routing {
        authenticate("auth-jwt") {
            get("/playlists") {
                val uid = call.requireUid() ?: return@get
                val rows = playlists.list(uid).map { (id, name, meta) ->
                    PlaylistDto(
                        id = id,
                        name = name,
                        trackCount = meta.second,
                        createdAt = meta.first,
                    )
                }
                call.respondApi(ApiResponse.ok(PlaylistsResponse(playlists = rows)))
            }
            post("/playlists") {
                val uid = call.requireUid() ?: return@post
                val body = call.receive<PlaylistCreateRequest>()
                val id = playlists.create(uid, body.name)
                if (id == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError(error = "Назва плейліста порожня", errorCode = "BAD_NAME"),
                    )
                } else {
                    call.respondApi(ApiResponse.ok(PlaylistCreatedResponse(playlistId = id)))
                }
            }
            delete("/playlists/{id}") {
                val uid = call.requireUid() ?: return@delete
                val id = call.parameters["id"]?.toIntOrNull()
                if (id == null || !playlists.delete(uid, id)) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ApiError(error = "Плейліст не знайдено", errorCode = "NO_PLAYLIST"),
                    )
                } else {
                    call.respondApi(ApiResponse.ok(PlaylistsResponse(playlists = emptyList())))
                }
            }
            get("/playlists/{id}/tracks") {
                val uid = call.requireUid() ?: return@get
                val id = call.parameters["id"]?.toIntOrNull()
                val tracks = id?.let { playlists.tracks(uid, it) }
                if (tracks == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ApiError(error = "Плейліст не знайдено", errorCode = "NO_PLAYLIST"),
                    )
                } else {
                    call.respondApi(ApiResponse.ok(PlaylistTracksResponse(tracks = tracks)))
                }
            }
            post("/playlists/{id}/tracks") {
                val uid = call.requireUid() ?: return@post
                val id = call.parameters["id"]?.toIntOrNull()
                val body = call.receive<PlaylistTrackAddRequest>()
                val snapshot = PlaylistTrackDto(
                    id = 0,
                    source = body.source,
                    sourceId = body.sourceId,
                    title = body.title,
                    artist = body.artist,
                    audioUrl = body.audioUrl,
                    coverUrl = body.coverUrl,
                    pageUrl = body.pageUrl,
                    licenseUrl = body.licenseUrl,
                    durationSec = body.durationSec,
                    position = 0,
                    addedAt = 0L,
                )
                val rowId = id?.let { playlists.addTrack(uid, it, snapshot) }
                if (rowId == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ApiError(error = "Плейліст не знайдено", errorCode = "NO_PLAYLIST"),
                    )
                } else {
                    call.respondApi(ApiResponse.ok(PlaylistCreatedResponse(playlistId = rowId)))
                }
            }
            delete("/playlists/{id}/tracks/{trackId}") {
                val uid = call.requireUid() ?: return@delete
                val id = call.parameters["id"]?.toIntOrNull()
                val trackId = call.parameters["trackId"]?.toIntOrNull()
                val ok = id != null && trackId != null && playlists.removeTrack(uid, id, trackId)
                if (!ok) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ApiError(error = "Трек не знайдено", errorCode = "NO_TRACK"),
                    )
                } else {
                    call.respondApi(ApiResponse.ok(PlaylistTracksResponse(tracks = emptyList())))
                }
            }
        }
    }
}
