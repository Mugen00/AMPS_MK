package dev.amps.backend

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.amps.backend.config.AppConfig
import dev.amps.backend.config.RedisConfig
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
import dev.amps.backend.routing.socialRoutes
import dev.amps.backend.routing.wsRoutes
import dev.amps.backend.service.AuthService
import dev.amps.backend.service.CacheService
import dev.amps.backend.service.CounterService
import dev.amps.backend.service.EmailService
import dev.amps.backend.service.EventConsumer
import dev.amps.backend.service.EventPublisher
import dev.amps.backend.service.RecommendationService
import dev.amps.backend.service.FeedService
import dev.amps.backend.service.FollowService
import dev.amps.backend.service.MediaStorage
import dev.amps.backend.service.PlaylistService
import dev.amps.backend.service.PollService
import dev.amps.backend.service.PresenceService
import dev.amps.backend.service.ProfileService
import dev.amps.backend.service.RedisRateLimiter
import dev.amps.backend.service.SmsService
import dev.amps.backend.service.StoryService
import dev.amps.backend.service.SessionBlacklist
import dev.amps.backend.service.SyncService
import dev.amps.backend.service.TwoFactorService
import dev.amps.backend.service.WsPubSub
import dev.amps.backend.service.WsSessionManager
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
import io.ktor.server.websocket.WebSockets
import io.ktor.server.response.respond
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    // 1.2.2: підписки + коментарі + аніме-список (коментарям і стрічці потрібен follow).
    val followService = FollowService()
    // 1.2.3: опитування під постами.
    val pollService = dev.amps.backend.service.PollService()
    val animeWatchService = dev.amps.backend.service.AnimeWatchService()

    // 1.6.8: Redis services (перед FeedService для кешу)
    val redisConfig = RedisConfig(config)
    val cacheService = CacheService(redisConfig)
    val redisRateLimiter = RedisRateLimiter(redisConfig)
    val sessionBlacklist = SessionBlacklist(redisConfig)
    val presenceService = PresenceService(redisConfig)
    val counterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val counterService = CounterService(redisConfig, counterScope)
    val eventPublisher = EventPublisher(redisConfig)

    val feedService = FeedService(media, profileService, followService, pollService, cacheService)
    // 1.6.8: рекомендації (CF-based)
    val recommendationService = RecommendationService(cacheService)
    // 1.2.0: сторіс — ті самі папки медіа і той самий профільний сервіс.
    val storyService = StoryService(config, media, profileService)
    // 1.2.1: свої плейлісти акаунта — живуть у базі.
    val playlistService = PlaylistService(config)
    // 1.2.2: коментарі та аніме-список акаунта.
    val commentService = dev.amps.backend.service.CommentService(profileService)
    val animeListService = dev.amps.backend.service.AnimeListService()
    // 1.6.6: колекції аніме-персонажів (Gemini-аналіз → збереження).
    val animeCollectionService = dev.amps.backend.service.AnimeCollectionService()
    // 1.6.0: групи, чати (приватні + групові) і рілси.
    // 1.6.2: групи і рілси отримали сховище — видаляють свої файли.
    // 1.6.3: чати отримали сховище — видаляють файл разом з повідомленням.
    val groupService = dev.amps.backend.service.GroupService(profileService, media)
    val blockService = dev.amps.backend.service.BlockService()
    val chatService = dev.amps.backend.service.ChatService(profileService, groupService, blockService, media)
    val reelService = dev.amps.backend.service.ReelService(profileService, media)

    // 1.6.8: WebSocket services
    val wsPubSub = WsPubSub(redisConfig)
    val wsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val wsSessionManager = WsSessionManager(redisConfig, wsPubSub, wsScope)

    // 1.6.8: Event Consumer — читає Domain Events і оновлює сервіси
    val eventConsumerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val eventConsumer = EventConsumer.start(
        redisConfig, feedService, chatService, groupService,
        profileService, animeCollectionService, eventConsumerScope
    )

    initDatabase(config)

    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        install(WebSockets) {
            this.contentConverter = KotlinxWebsocketSerializationConverter(Json { ignoreUnknownKeys = true })
        }
        
        module(
            config, authService, twoFactorService, syncService,
            feedService, profileService, media, storyService, playlistService,
            commentService, followService, animeListService, animeCollectionService, pollService,
            animeWatchService, recommendationService, groupService, chatService, reelService,
            blockService,
            // 1.6.8: new services
            cacheService, redisRateLimiter, sessionBlacklist, presenceService,
            counterService, eventPublisher,
            wsPubSub, wsSessionManager,
            wsScope,
        )
    }.start(wait = true)

    // Graceful shutdown
    Runtime.getRuntime().addShutdownHook(Thread {
        eventConsumer.shutdown()
        eventConsumerScope.coroutineContext[Job]?.cancel()
        counterService.shutdown()
        wsSessionManager.shutdown()
        wsScope.coroutineContext[Job]?.cancel()
        redisConfig.shutdown()
    })
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
        // Схема створюється за відсутності; 1.4.0: createMissingTablesAndColumns
        // додає нові колонки (улюблене в профілі) до вже наявних таблиць —
        // міграційний фреймворк для десятка таблиць особистого проєкту
        // був би надлишковістю.
        SchemaUtils.createMissingTablesAndColumns(
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
            // 1.2.2: коментарі, підписки, аніме-список акаунта.
            dev.amps.backend.model.CommentTable,
            dev.amps.backend.model.FollowTable,
            dev.amps.backend.model.AnimeListTable,
            // 1.2.3: опитування (голосування лінкується на опції).
            dev.amps.backend.model.PollTable,
            dev.amps.backend.model.PollOptionTable,
            dev.amps.backend.model.PollVoteTable,
            // 1.2.4: XP і рівні.
            dev.amps.backend.model.XpTable,
            // 1.6.0: групи, чати, рілси.
            dev.amps.backend.model.GroupTable,
            dev.amps.backend.model.GroupMemberTable,
            dev.amps.backend.model.GroupJoinRequestTable,
            // 1.6.5: посилання-запрошення в спільноти.
            dev.amps.backend.model.GroupInviteCodeTable,
            dev.amps.backend.model.ChatMessageTable,
            dev.amps.backend.model.ChatReadTable,
            dev.amps.backend.model.ReelTable,
            dev.amps.backend.model.ReelLikeTable,
            // 1.6.2: реакції на повідомлення і блокування.
            dev.amps.backend.model.ChatReactionTable,
            dev.amps.backend.model.BlockTable,
            // 1.6.6: колекції аніме-персонажів (Gemini-аналіз → збереження).
            dev.amps.backend.model.AnimeCollectionTable,
            dev.amps.backend.model.AnimeCollectionCharacterTable,
        )
        // 1.6.2: samolікування кеш-лічильників постів — синхронізуємо
        // кеш із фактами (лайки/репости), щоб стрічка читала колонку
        // замість COUNT(*) на кожен пост. Дешево на нашому масштабі,
        // і після будь-яких ручних правок бази дані сходяться.
        runCatching {
            exec("UPDATE posts SET like_count = (SELECT COUNT(*) FROM likes WHERE likes.post_id = posts.id)")
            exec("UPDATE posts SET repost_count = (SELECT COUNT(*) FROM reposts WHERE reposts.post_id = posts.id)")
        }

        // 1.6.8: PostgreSQL FTS (tsvector + pg_trgm) — індекси та тригери
        exec("CREATE EXTENSION IF NOT EXISTS pg_trgm")

        // 1.6.8: ensure searchVector columns exist before creating GIN indexes
        exec("ALTER TABLE IF NOT EXISTS posts ADD COLUMN IF NOT EXISTS searchVector tsvector")
        exec("ALTER TABLE IF NOT EXISTS groups ADD COLUMN IF NOT EXISTS searchVector tsvector")
        exec("ALTER TABLE IF NOT EXISTS reels ADD COLUMN IF NOT EXISTS searchVector tsvector")
        exec("ALTER TABLE IF NOT EXISTS profiles ADD COLUMN IF NOT EXISTS searchVector tsvector")
        exec("ALTER TABLE IF NOT EXISTS users ADD COLUMN IF NOT EXISTS searchVector tsvector")
        exec("ALTER TABLE IF NOT EXISTS anime_collections ADD COLUMN IF NOT EXISTS searchVector tsvector")
        exec("ALTER TABLE IF NOT EXISTS anime_collection_characters ADD COLUMN IF NOT EXISTS searchVector tsvector")

        // GIN індекси на searchVector колонках
        exec("CREATE INDEX IF NOT EXISTS idx_posts_search_vector ON posts USING GIN (searchVector)")
        exec("CREATE INDEX IF NOT EXISTS idx_groups_search_vector ON groups USING GIN (searchVector)")
        exec("CREATE INDEX IF NOT EXISTS idx_reels_search_vector ON reels USING GIN (searchVector)")
        exec("CREATE INDEX IF NOT EXISTS idx_profiles_search_vector ON profiles USING GIN (searchVector)")
        exec("CREATE INDEX IF NOT EXISTS idx_users_search_vector ON users USING GIN (searchVector)")
        exec("CREATE INDEX IF NOT EXISTS idx_anime_collections_search_vector ON anime_collections USING GIN (searchVector)")
        exec("CREATE INDEX IF NOT EXISTS idx_anime_collection_chars_search_vector ON anime_collection_characters USING GIN (searchVector)")

        // pg_trgm GIN індекси для нечіткого пошуку (ILIKE-like)
        exec("CREATE INDEX IF NOT EXISTS idx_posts_text_trgm ON posts USING GIN (text gin_trgm_ops)")
        exec("CREATE INDEX IF NOT EXISTS idx_groups_name_trgm ON groups USING GIN (name gin_trgm_ops)")
        exec("CREATE INDEX IF NOT EXISTS idx_groups_desc_trgm ON groups USING GIN (description gin_trgm_ops)")
        exec("CREATE INDEX IF NOT EXISTS idx_reels_caption_trgm ON reels USING GIN (caption gin_trgm_ops)")
        exec("CREATE INDEX IF NOT EXISTS idx_profiles_name_trgm ON profiles USING GIN (display_name gin_trgm_ops)")
        exec("CREATE INDEX IF NOT EXISTS idx_users_login_trgm ON users USING GIN (login gin_trgm_ops)")
        exec("CREATE INDEX IF NOT EXISTS idx_anime_coll_title_trgm ON anime_collections USING GIN (anime_title gin_trgm_ops)")
        exec("CREATE INDEX IF NOT EXISTS idx_anime_chars_name_trgm ON anime_collection_characters USING GIN (name gin_trgm_ops)")

        // Функція оновлення tsvector для постів (створюється пізніше, коли база даних стабільна)

// Групи

// Рілси
            CREATE OR REPLACE FUNCTION update_reels_search_vector()
            RETURNS trigger AS \$\$
            BEGIN
                NEW.searchVector :=
                    setweight(to_tsvector('ukrainian', coalesce(NEW.caption, '')), 'A') ||
                    setweight(to_tsvector('russian', coalesce(NEW.caption, '')), 'B') ||
                    setweight(to_tsvector('english', coalesce(NEW.caption, '')), 'C');
                RETURN NEW;
            END
            \$\$ LANGUAGE plpgsql;
        """)
        exec("DROP TRIGGER IF EXISTS trigger_update_reels_search_vector ON reels")
        exec("CREATE TRIGGER trigger_update_reels_search_vector BEFORE INSERT OR UPDATE ON reels FOR EACH ROW EXECUTE FUNCTION update_reels_search_vector()")

        // Профілі
        exec("""
            CREATE OR REPLACE FUNCTION update_profiles_search_vector()
            RETURNS trigger AS \$\$
            BEGIN
                NEW.searchVector :=
                    setweight(to_tsvector('ukrainian', coalesce(NEW.display_name, '')), 'A') ||
                    setweight(to_tsvector('russian', coalesce(NEW.display_name, '')), 'B') ||
                    setweight(to_tsvector('english', coalesce(NEW.display_name, '')), 'C') ||
                    setweight(to_tsvector('ukrainian', coalesce(NEW.bio, '')), 'D') ||
                    setweight(to_tsvector('russian', coalesce(NEW.bio, '')), 'D') ||
                    setweight(to_tsvector('english', coalesce(NEW.bio, '')), 'D');
                RETURN NEW;
            END
            \$\$ LANGUAGE plpgsql;
        """)
        exec("DROP TRIGGER IF EXISTS trigger_update_profiles_search_vector ON profiles")
        exec("CREATE TRIGGER trigger_update_profiles_search_vector BEFORE INSERT OR UPDATE ON profiles FOR EACH ROW EXECUTE FUNCTION update_profiles_search_vector()")

        // Користувачі
        exec("""
            CREATE OR REPLACE FUNCTION update_users_search_vector()
            RETURNS trigger AS \$\$
            BEGIN
                NEW.searchVector :=
                    setweight(to_tsvector('ukrainian', coalesce(NEW.login, '')), 'A') ||
                    setweight(to_tsvector('russian', coalesce(NEW.login, '')), 'B') ||
                    setweight(to_tsvector('english', coalesce(NEW.login, '')), 'C');
                RETURN NEW;
            END
            \$\$ LANGUAGE plpgsql;
        """)
        exec("DROP TRIGGER IF EXISTS trigger_update_users_search_vector ON users")
        exec("CREATE TRIGGER trigger_update_users_search_vector BEFORE INSERT OR UPDATE ON users FOR EACH ROW EXECUTE FUNCTION update_users_search_vector()")

        // Аніме колекції
        exec("""
            CREATE OR REPLACE FUNCTION update_anime_collections_search_vector()
            RETURNS trigger AS \$\$
            BEGIN
                NEW.searchVector :=
                    setweight(to_tsvector('ukrainian', coalesce(NEW.anime_title, '')), 'A') ||
                    setweight(to_tsvector('russian', coalesce(NEW.anime_title, '')), 'B') ||
                    setweight(to_tsvector('english', coalesce(NEW.anime_title, '')), 'C');
                RETURN NEW;
            END
            \$\$ LANGUAGE plpgsql;
        """)
        exec("DROP TRIGGER IF EXISTS trigger_update_anime_collections_search_vector ON anime_collections")
        exec("CREATE TRIGGER trigger_update_anime_collections_search_vector BEFORE INSERT OR UPDATE ON anime_collections FOR EACH ROW EXECUTE FUNCTION update_anime_collections_search_vector()")

        // Персонажі аніме колекцій
        exec("""
            CREATE OR REPLACE FUNCTION update_anime_chars_search_vector()
            RETURNS trigger AS \$\$
            BEGIN
                NEW.searchVector :=
                    setweight(to_tsvector('ukrainian', coalesce(NEW.name, '')), 'A') ||
                    setweight(to_tsvector('russian', coalesce(NEW.name, '')), 'B') ||
                    setweight(to_tsvector('english', coalesce(NEW.name, '')), 'C') ||
                    setweight(to_tsvector('ukrainian', coalesce(NEW.note, '')), 'D') ||
                    setweight(to_tsvector('russian', coalesce(NEW.note, '')), 'D') ||
                    setweight(to_tsvector('english', coalesce(NEW.note, '')), 'D');
                RETURN NEW;
            END
            \$\$ LANGUAGE plpgsql;
        """)
        exec("DROP TRIGGER IF EXISTS trigger_update_anime_chars_search_vector ON anime_collection_characters")
        exec("CREATE TRIGGER trigger_update_anime_chars_search_vector BEFORE INSERT OR UPDATE ON anime_collection_characters FOR EACH ROW EXECUTE FUNCTION update_anime_chars_search_vector()")

        // Бекфіл існуючих записів
        exec("UPDATE posts SET searchVector = update_posts_search_vector()")
        exec("UPDATE groups SET searchVector = update_groups_search_vector()")
        exec("UPDATE reels SET searchVector = update_reels_search_vector()")
        exec("UPDATE profiles SET searchVector = update_profiles_search_vector()")
        exec("UPDATE users SET searchVector = update_users_search_vector()")
        exec("UPDATE anime_collections SET searchVector = update_anime_collections_search_vector()")
        exec("UPDATE anime_collection_characters SET searchVector = update_anime_chars_search_vector()")
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
    commentService: dev.amps.backend.service.CommentService,
    followService: dev.amps.backend.service.FollowService,
    animeListService: dev.amps.backend.service.AnimeListService,
    animeCollectionService: dev.amps.backend.service.AnimeCollectionService,
    pollService: dev.amps.backend.service.PollService,
    animeWatchService: dev.amps.backend.service.AnimeWatchService,
    recommendationService: RecommendationService,
    groupService: dev.amps.backend.service.GroupService,
    chatService: dev.amps.backend.service.ChatService,
    reelService: dev.amps.backend.service.ReelService,
    blockService: dev.amps.backend.service.BlockService,
    // 1.6.8: new services
    cacheService: CacheService,
    redisRateLimiter: RedisRateLimiter,
    sessionBlacklist: SessionBlacklist,
    presenceService: PresenceService,
    counterService: CounterService,
    eventPublisher: EventPublisher,
    wsPubSub: WsPubSub,
    wsSessionManager: WsSessionManager,
    wsScope: CoroutineScope,
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

    authRoutes(authService, twoFactorService, syncService, media, redisRateLimiter, sessionBlacklist)
    // 1.1.2: спільнота, профіль і роздача медіа; 1.2.0: сторіс;
    // 1.2.2: коментарі, підписки, аніме-список; 1.6.6: колекції.
    feedRoutes(config, feedService, profileService, media, storyService, commentService, followService, animeListService, animeCollectionService, pollService, animeWatchService, recommendationService)
    // 1.2.1: свої плейлісти акаунта — у базі, онлайн-слухання в застосунку.
    playlistRoutes(playlistService)
    // 1.6.0: групи, чати, рілси.
    socialRoutes(
        feedService, profileService, media, groupService, chatService, reelService, followService,
        blockService,
    )
    // 1.6.8: WebSocket routes
    wsRoutes(config, chatService, presenceService, wsPubSub, wsSessionManager, wsScope)
}