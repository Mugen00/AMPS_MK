package dev.amps.backend

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.amps.backend.config.AppConfig
import dev.amps.backend.model.ApiError
import dev.amps.backend.model.LikeTable
import dev.amps.backend.model.PostMediaTable
import dev.amps.backend.model.PostTable
import dev.amps.backend.model.ProfileTable
import dev.amps.backend.model.RefreshTokenTable
import dev.amps.backend.model.RepostTable
import dev.amps.backend.model.StoryTable
import dev.amps.backend.model.PlaylistTable
import dev.amps.backend.model.PlaylistTrackTable
import dev.amps.backend.model.SyncDataTable
import dev.amps.backend.model.UserTable
import dev.amps.backend.model.VerificationCodeTable
import dev.amps.backend.routing.authRoutes
import dev.amps.backend.routing.feedRoutes
import dev.amps.backend.routing.playlistRoutes
import dev.amps.backend.service.AuthService
import dev.amps.backend.service.EmailService
import dev.amps.backend.service.FeedService
import dev.amps.backend.service.MediaStorage
import dev.amps.backend.service.PlaylistService
import dev.amps.backend.service.ProfileService
import dev.amps.backend.service.SmsService
import dev.amps.backend.service.StoryService
import dev.amps.backend.service.SyncService
import dev.amps.backend.service.TwoFactorService
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * 1.1.0: серверна частина AMPS — Ktor 3 + PostgreSQL, розгортання на
 * Railway через backend/Dockerfile.
 *
 * Обов'язкові змінні: DATABASE_URL (Railway PostgreSQL), JWT_SECRET.
 * Без них сервер стартує з ясно сформульованою відмовою у журналі —
 * це краще, ніж мовчки працювати без авторизації.
 */
fun main() {
    val config = AppConfig()
    val emailService = EmailService(config)
    val smsService = SmsService(config)
    val authService = AuthService(config, emailService, smsService)
    val twoFactorService = TwoFactorService()
    val syncService = SyncService()
    // 1.1.2: спільнота і профіль — сховище медіа спільне для всіх трьох.
    val media = MediaStorage(config)
    val profileService = ProfileService(media)
    val feedService = FeedService(media, profileService)
    // 1.2.0: сторіс — ті самі папки медіа і той самий профільний сервіс.
    val storyService = StoryService(config, media, profileService)
    // 1.2.1: свої плейлісти акаунта — живуть у базі.
    val playlistService = PlaylistService(config)

    initDatabase(config)

    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        module(
            config, authService, twoFactorService, syncService,
            feedService, profileService, media, storyService, playlistService,
        )
    }.start(wait = true)
}

private fun initDatabase(config: AppConfig) {
    val hikari = HikariConfig().apply {
        jdbcUrl = config.jdbcUrl
        username = config.dbUser
        password = config.dbPassword
        driverClassName = "org.postgresql.Driver"
        maximumPoolSize = 10
        minimumIdle = 2
    }
    Database.connect(HikariDataSource(hikari))
    transaction {
        // Схема створюється за відсутності — міграційний фреймворк для
        // десятка таблиць особистого проєкту був би надлишковістю.
        SchemaUtils.create(
            UserTable,
            VerificationCodeTable,
            SyncDataTable,
            RefreshTokenTable,
            ProfileTable,
            PostTable,
            PostMediaTable,
            LikeTable,
            RepostTable,
            StoryTable,
            PlaylistTable,
            PlaylistTrackTable,
        )
    }
}

fun Application.module(
    config: AppConfig,
    authService: AuthService,
    twoFactorService: TwoFactorService,
    syncService: SyncService,
    feedService: FeedService,
    profileService: ProfileService,
    media: MediaStorage,
    storyService: StoryService,
    playlistService: dev.amps.backend.service.PlaylistService,
) {
    install(ContentNegotiation) {
        json(kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
    }

    // Застосунок — не браузер, CORS йому байдужий; політика відкрита
    // для майбутнього веб-клієнта.
    install(CORS) {
        anyHost()
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowHeader("Content-Type")
        allowHeader("Authorization")
    }

    install(Authentication) {
        jwt("auth-jwt") {
            verifier(
                JWT.require(Algorithm.HMAC256(config.jwtSecret))
                    .withIssuer(AppConfig.ISSUER)
                    .build()
            )
            // Підпис уже перевірено verifier'ом; claim "uid" звіряється
            // в кожному маршруті через requireUid().
            validate { JWTPrincipal(it.payload) }
        }
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            LoggerFactory.getLogger("amps.backend").error("Невідома помилка", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiError(error = "Внутрішня помилка сервера", errorCode = "INTERNAL"),
            )
        }
    }

    authRoutes(authService, twoFactorService, syncService)
    // 1.1.2: спільнота, профіль і роздача медіа; 1.2.0: сторіс у тому ж блоці.
    feedRoutes(config, feedService, profileService, media, storyService)
    // 1.2.1: свої плейлісти акаунта — у базі, онлайн-слухання в застосунку.
    playlistRoutes(playlistService)
}
