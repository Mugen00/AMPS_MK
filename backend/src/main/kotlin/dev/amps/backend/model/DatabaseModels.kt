package dev.amps.backend.model

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption

/**
 * 1.1.0: схема бази (PostgreSQL через Exposed DAO).
 *
 * Час зберігається epoch-мілісекундами в long-колонках: жодної
 * залежності від exposed-java-time і жодних проблем із часовими
 * поясами — сервер лише порівнює числа.
 */

// ===== Користувачі =====
object UserTable : IntIdTable("users") {
    val login = varchar("login", 50).uniqueIndex()
    val email = varchar("email", 255).uniqueIndex()
    val phone = varchar("phone", 30).nullable()
    val passwordHash = varchar("password_hash", 255)
    val emailVerified = bool("email_verified").default(false)
    val phoneVerified = bool("phone_verified").default(false)
    val twoFactorEnabled = bool("two_factor_enabled").default(false)
    val twoFactorSecret = varchar("two_factor_secret", 255).nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
}

class UserEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<UserEntity>(UserTable)

    var login by UserTable.login
    var email by UserTable.email
    var phone by UserTable.phone
    var passwordHash by UserTable.passwordHash
    var emailVerified by UserTable.emailVerified
    var phoneVerified by UserTable.phoneVerified
    var twoFactorEnabled by UserTable.twoFactorEnabled
    var twoFactorSecret by UserTable.twoFactorSecret
    var createdAt by UserTable.createdAt
    var updatedAt by UserTable.updatedAt
}

// ===== Коди підтвердження (email / SMS / скидання пароля) =====
object VerificationCodeTable : IntIdTable("verification_codes") {
    val userId = integer("user_id").references(UserTable.id, ReferenceOption.CASCADE)
    // У базі лишається хеш SHA-256, не сам код.
    val codeHash = varchar("code_hash", 64)
    val type = varchar("type", 20)
    val expiresAt = long("expires_at")
    val used = bool("used").default(false)
    val createdAt = long("created_at")
}

class VerificationCodeEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<VerificationCodeEntity>(VerificationCodeTable)

    var userId by VerificationCodeTable.userId
    var codeHash by VerificationCodeTable.codeHash
    var type by VerificationCodeTable.type
    var expiresAt by VerificationCodeTable.expiresAt
    var used by VerificationCodeTable.used
    var createdAt by VerificationCodeTable.createdAt
}

// ===== Синхронізація: пари ключ-значення на користувача =====
object SyncDataTable : IntIdTable("sync_data") {
    val userId = integer("user_id").references(UserTable.id, ReferenceOption.CASCADE).uniqueIndex()
    val dataJson = text("data_json")
    val version = integer("version")
    val updatedAt = long("updated_at")
}

class SyncDataEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<SyncDataEntity>(SyncDataTable)

    var userId by SyncDataTable.userId
    var dataJson by SyncDataTable.dataJson
    var version by SyncDataTable.version
    var updatedAt by SyncDataTable.updatedAt
}

// ===== Refresh-токени (тільки хеші) =====
object RefreshTokenTable : IntIdTable("refresh_tokens") {
    val userId = integer("user_id").references(UserTable.id, ReferenceOption.CASCADE)
    val tokenHash = varchar("token_hash", 64)
    val expiresAt = long("expires_at")
    val createdAt = long("created_at")
}

class RefreshTokenEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<RefreshTokenEntity>(RefreshTokenTable)

    var userId by RefreshTokenTable.userId
    var tokenHash by RefreshTokenTable.tokenHash
    var expiresAt by RefreshTokenTable.expiresAt
    var createdAt by RefreshTokenTable.createdAt
}

// ===== Профіль (1.1.2): показуване ім'я, біо, аватар =====
object ProfileTable : IntIdTable("profiles") {
    val userId = integer("user_id").references(UserTable.id, ReferenceOption.CASCADE).uniqueIndex()
    val displayName = varchar("display_name", 64)
    val bio = varchar("bio", 280).nullable()
    /** Ім'я файлу аватара у сховищі медіа; UUID, без шляху. */
    val avatarFile = varchar("avatar_file", 100).nullable()
    // 1.4.0: улюблені жанри (JSON-масив рядків) і улюблені аніме (JSON-список).
    val favGenres = varchar("fav_genres", 500).nullable()
    val favAnime = varchar("fav_anime", 2500).nullable()
    val updatedAt = long("updated_at")
}

class ProfileEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<ProfileEntity>(ProfileTable)

    var userId by ProfileTable.userId
    var displayName by ProfileTable.displayName
    var bio by ProfileTable.bio
    var avatarFile by ProfileTable.avatarFile
    var favGenres by ProfileTable.favGenres
    var favAnime by ProfileTable.favAnime
    var updatedAt by ProfileTable.updatedAt
}

// ===== Спільнота: пости =====
object PostTable : IntIdTable("posts") {
    val userId = integer("user_id").references(UserTable.id, ReferenceOption.CASCADE)
    val text = varchar("text", 1000)
    val createdAt = long("created_at")

    // 1.5.1: формат показу фото, як в Instagram ("original"|"1:1"|"4:5"|"16:9").
    // null — старі пости й оригінальний показ без обрізання.
    val imageAspect = varchar("image_aspect", 12).nullable()

    // 1.6.0: пост належить групі (null — звичайний пост спільноти).
    // Без FK-декларації: таблиця груп оголошена нижче по файлу, а посилання
    // на групу чиститься при видаленні групи в сервісі.
    val groupId = integer("group_id").nullable()

    // 1.6.1: мітка редагування — відображається «(ред.)» у клієнтах.
    val editedAt = long("edited_at").nullable()

    // 1.6.2: кеш-лічильники — стрічка читає їх без COUNT(*) на кожен пост
    // (було 6 запитів на пост). Оновлюються при лайку/репості, бекфіл на старті.
    val likeCount = integer("like_count").default(0)
    val repostCount = integer("repost_count").default(0)

    // 1.6.3: «кружок» — кругле відео-повідомлення до хвилини (як у Telegram).
    val roundVideo = bool("round_video").default(false)

    // 1.6.5: закріплений пост спільноти (першим у ленті групи).
    val pinned = bool("pinned").default(false)
}

class PostEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<PostEntity>(PostTable)

    var userId by PostTable.userId
    var text by PostTable.text
    var createdAt by PostTable.createdAt
    var imageAspect by PostTable.imageAspect
    var groupId by PostTable.groupId
    var editedAt by PostTable.editedAt
    var likeCount by PostTable.likeCount
    var repostCount by PostTable.repostCount
    var roundVideo by PostTable.roundVideo
    var pinned by PostTable.pinned
}

// ===== Вкладення поста: фото або відео =====
object PostMediaTable : IntIdTable("post_media") {
    val postId = integer("post_id").references(PostTable.id, ReferenceOption.CASCADE)
    // "photo" | "video"
    val kind = varchar("kind", 8)
    // Ім'я файлу у сховищі: UUID + розширення; віддається за /media/{kind}/{file}
    val fileName = varchar("file_name", 100)
}

class PostMediaEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<PostMediaEntity>(PostMediaTable)

    var postId by PostMediaTable.postId
    var kind by PostMediaTable.kind
    var fileName by PostMediaTable.fileName
}

// ===== Лайки: пара (хто, що) існує в одному екземплярі =====
object LikeTable : IntIdTable("likes") {
    val userId = integer("user_id").references(UserTable.id, ReferenceOption.CASCADE)
    val postId = integer("post_id").references(PostTable.id, ReferenceOption.CASCADE)

    init {
        uniqueIndex(userId, postId)
    }
}

class LikeEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<LikeEntity>(LikeTable)

    var userId by LikeTable.userId
    var postId by LikeTable.postId
}

// ===== Репости: хто, що і коли =====
object RepostTable : IntIdTable("reposts") {
    val userId = integer("user_id").references(UserTable.id, ReferenceOption.CASCADE)
    val postId = integer("post_id").references(PostTable.id, ReferenceOption.CASCADE)
    val createdAt = long("created_at")

    init {
        uniqueIndex(userId, postId)
    }
}

class RepostEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<RepostEntity>(RepostTable)

    var userId by RepostTable.userId
    var postId by RepostTable.postId
    var createdAt by RepostTable.createdAt
}

// ===== Коментарі (1.2.2): обговорення під постами =====
object CommentTable : IntIdTable("comments") {
    val userId = integer("user_id").references(UserTable.id, ReferenceOption.CASCADE)
    val postId = integer("post_id").references(PostTable.id, ReferenceOption.CASCADE)
    val text = text("text")
    val createdAt = long("created_at")

    init {
        index(false, postId, createdAt)
    }
}

class CommentEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<CommentEntity>(CommentTable)

    var userId by CommentTable.userId
    var postId by CommentTable.postId
    var text by CommentTable.text
    var createdAt by CommentTable.createdAt
}

// ===== Підписки (1.2.2): хто на кого =====
object FollowTable : IntIdTable("follows") {
    val followerId = integer("follower_id").references(UserTable.id, ReferenceOption.CASCADE)
    val followedId = integer("followed_id").references(UserTable.id, ReferenceOption.CASCADE)
    val createdAt = long("created_at")

    init {
        uniqueIndex(followerId, followedId)
    }
}

class FollowEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<FollowEntity>(FollowTable)

    var followerId by FollowTable.followerId
    var followedId by FollowTable.followedId
    var createdAt by FollowTable.createdAt
}

// ===== Аніме-список акаунта (1.2.2): дивлюсь / планую / переглянуто =====
object AnimeListTable : IntIdTable("anime_list") {
    val userId = integer("user_id").references(UserTable.id, ReferenceOption.CASCADE)
    val anilistId = integer("anilist_id")
    val title = varchar("title", 250)
    val coverUrl = varchar("cover_url", 500)
    // "watching" | "planned" | "watched"
    val status = varchar("status", 16)
    // 1 = нагадувати про нову серію, 0 = ні.
    val reminder = bool("reminder").default(true)
    val updatedAt = long("updated_at")

    init {
        uniqueIndex(userId, anilistId)
    }
}

class AnimeListEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<AnimeListEntity>(AnimeListTable)

    var userId by AnimeListTable.userId
    var anilistId by AnimeListTable.anilistId
    var title by AnimeListTable.title
    var coverUrl by AnimeListTable.coverUrl
    var status by AnimeListTable.status
    var reminder by AnimeListTable.reminder
    var updatedAt by AnimeListTable.updatedAt
}

// ===== Сторіс (1.2.0): фото/відео на 24 години =====
object StoryTable : IntIdTable("stories") {
    val userId = integer("user_id").references(UserTable.id, ReferenceOption.CASCADE)
    // "photo" | "video"
    val kind = varchar("kind", 8)
    // Ім'я файлу у сховищі медіа (UUID); віддається за /media/{kind}/{file}
    val fileName = varchar("file_name", 100)
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")

    init {
        index(false, userId, createdAt)
    }
}

class StoryEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<StoryEntity>(StoryTable)

    var userId by StoryTable.userId
    var kind by StoryTable.kind
    var fileName by StoryTable.fileName
    var createdAt by StoryTable.createdAt
    var expiresAt by StoryTable.expiresAt
}

// ===== Плейлісти (1.2.1): свої списки треків, прив'язані до акаунта =====
object PlaylistTable : IntIdTable("playlists") {
    val userId = integer("user_id").references(UserTable.id, ReferenceOption.CASCADE)
    val name = varchar("name", 120)
    val createdAt = long("created_at")

    init {
        index(false, userId, createdAt)
    }
}

class PlaylistEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<PlaylistEntity>(PlaylistTable)

    var userId by PlaylistTable.userId
    var name by PlaylistTable.name
    var createdAt by PlaylistTable.createdAt
}

/**
 * Трек у плейлісті: знімок полів рядка пошуку (джерело + URL аудіо),
 * щоб трек грав навіть тоді, коли джерело пошуку не відповідає.
 */
object PlaylistTrackTable : IntIdTable("playlist_tracks") {
    val playlistId = integer("playlist_id").references(PlaylistTable.id, ReferenceOption.CASCADE)
    // "jamendo" | "archive" | "ccmixter" | "openverse" | "itunes" ...
    // Властивість названа trackSource: у ColumnSet вже є member `source`.
    val trackSource = varchar("source", 24)
    val sourceId = varchar("source_id", 200)
    val title = varchar("title", 250)
    val artist = varchar("artist", 200)
    val audioUrl = varchar("audio_url", 500)
    val coverUrl = varchar("cover_url", 500).nullable()
    val pageUrl = varchar("page_url", 500).nullable()
    val licenseUrl = varchar("license_url", 500).nullable()
    val durationSec = integer("duration_sec").nullable()
    val position = integer("position")
    val addedAt = long("added_at")

    init {
        index(false, playlistId, position)
    }
}

class PlaylistTrackEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<PlaylistTrackEntity>(PlaylistTrackTable)

    var playlistId by PlaylistTrackTable.playlistId
    var trackSource by PlaylistTrackTable.trackSource
    var sourceId by PlaylistTrackTable.sourceId
    var title by PlaylistTrackTable.title
    var artist by PlaylistTrackTable.artist
    var audioUrl by PlaylistTrackTable.audioUrl
    var coverUrl by PlaylistTrackTable.coverUrl
    var pageUrl by PlaylistTrackTable.pageUrl
    var licenseUrl by PlaylistTrackTable.licenseUrl
    var durationSec by PlaylistTrackTable.durationSec
    var position by PlaylistTrackTable.position
    var addedAt by PlaylistTrackTable.addedAt
}

// ===== Опитування (1.2.3): одне опитування на пост =====
object PollTable : IntIdTable("polls") {
    val postId = integer("post_id").references(PostTable.id, ReferenceOption.CASCADE).uniqueIndex()
    /** Запитання необов'язкове — часто вистачає тексту поста. */
    val question = varchar("question", 200).nullable()
}

class PollEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<PollEntity>(PollTable)

    var postId by PollTable.postId
    var question by PollTable.question
}

object PollOptionTable : IntIdTable("poll_options") {
    val pollId = integer("poll_id").references(PollTable.id, ReferenceOption.CASCADE)
    val text = varchar("text", 100)
    val position = integer("position")

    init {
        index(false, pollId, position)
    }
}

class PollOptionEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<PollOptionEntity>(PollOptionTable)

    var pollId by PollOptionTable.pollId
    var text by PollOptionTable.text
    var position by PollOptionTable.position
}

// Один голос на користувача на опитування; повторний голос перезаписується.
object PollVoteTable : IntIdTable("poll_votes") {
    val pollId = integer("poll_id").references(PollTable.id, ReferenceOption.CASCADE)
    val userId = integer("user_id").references(UserTable.id, ReferenceOption.CASCADE)
    val optionId = integer("option_id").references(PollOptionTable.id, ReferenceOption.CASCADE)

    init {
        uniqueIndex(pollId, userId)
    }
}

class PollVoteEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<PollVoteEntity>(PollVoteTable)

    var pollId by PollVoteTable.pollId
    var userId by PollVoteTable.userId
    var optionId by PollVoteTable.optionId
}

// ===== 1.6.0: групи =====
object GroupTable : IntIdTable("groups") {
    val ownerId = integer("owner_id").references(UserTable.id, ReferenceOption.CASCADE)
    val name = varchar("name", 80)
    val description = varchar("description", 500).default("")
    /** Ім'я файлу аватарки у сховищі (null — буква-заглушка). */
    val avatarFile = varchar("avatar_file", 100).nullable()
    /** 1.6.4: обкладинка спільноти (як у ВК) — широка шапка на сторінці групи. */
    val coverFile = varchar("cover_file", 100).nullable()
    val isPrivate = bool("is_private").default(false)
    val createdAt = long("created_at")
}

class GroupEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<GroupEntity>(GroupTable)

    var ownerId by GroupTable.ownerId
    var name by GroupTable.name
    var description by GroupTable.description
    var avatarFile by GroupTable.avatarFile
    var coverFile by GroupTable.coverFile
    var isPrivate by GroupTable.isPrivate
    var createdAt by GroupTable.createdAt
}

// Учасник групи: роль "owner" | "mod" | "member".
object GroupMemberTable : IntIdTable("group_members") {
    val groupId = integer("group_id").references(GroupTable.id, ReferenceOption.CASCADE)
    val userId = integer("user_id").references(UserTable.id, ReferenceOption.CASCADE)
    val role = varchar("role", 10)
    val joinedAt = long("joined_at")

    init {
        uniqueIndex(groupId, userId)
    }
}

class GroupMemberEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<GroupMemberEntity>(GroupMemberTable)

    var groupId by GroupMemberTable.groupId
    var userId by GroupMemberTable.userId
    var role by GroupMemberTable.role
    var joinedAt by GroupMemberTable.joinedAt
}

// 1.6.0: запити на вступ у приватну групу.
object GroupJoinRequestTable : IntIdTable("group_join_requests") {
    val groupId = integer("group_id").references(GroupTable.id, ReferenceOption.CASCADE)
    val userId = integer("user_id").references(UserTable.id, ReferenceOption.CASCADE)
    val createdAt = long("created_at")

    init {
        uniqueIndex(groupId, userId)
    }
}

class GroupJoinRequestEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<GroupJoinRequestEntity>(GroupJoinRequestTable)

    var groupId by GroupJoinRequestTable.groupId
    var userId by GroupJoinRequestTable.userId
    var createdAt by GroupJoinRequestTable.createdAt
}

// 1.6.5: посилання-запрошення в приватну/публічну групу.
object GroupInviteCodeTable : IntIdTable("group_invite_codes") {
    val groupId = integer("group_id").references(GroupTable.id, ReferenceOption.CASCADE)
    /** Короткий одноразовий/багаторазовий код. */
    val code = varchar("code", 32).uniqueIndex()
    /** Хто створив посилання (власник або модератор). */
    val creatorId = integer("creator_id").references(UserTable.id)
    /** Кількість сканувань, які вже викорістали (one-shot — maxUses=1). */
    val usedCount = integer("used_count").default(0)
    val maxUses = integer("max_uses").default(0)
    /** 0 = безстроково. */
    val expiresAt = long("expires_at").default(0)
    val createdAt = long("created_at")
}

class GroupInviteCodeEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<GroupInviteCodeEntity>(GroupInviteCodeTable)

    var groupId by GroupInviteCodeTable.groupId
    var code by GroupInviteCodeTable.code
    var creatorId by GroupInviteCodeTable.creatorId
    var usedCount by GroupInviteCodeTable.usedCount
    var maxUses by GroupInviteCodeTable.maxUses
    var expiresAt by GroupInviteCodeTable.expiresAt
    var createdAt by GroupInviteCodeTable.createdAt
}

// ===== 1.6.0: чати =====
object ChatMessageTable : IntIdTable("chat_messages") {
    val fromId = integer("from_id").references(UserTable.id, ReferenceOption.CASCADE)
    /** Отримувач: приватний діалог (1-на-1) або група (одне з двох не null). */
    val toUserId = integer("to_user_id").references(UserTable.id, ReferenceOption.CASCADE).nullable()
    val toGroupId = integer("to_group_id").references(GroupTable.id, ReferenceOption.CASCADE).nullable()
    val text = varchar("text", 1000).default("")
    /** Фото в чаті: ім'я файлу у сховищі, віддається за /media/photo/{file}. */
    val mediaFile = varchar("media_file", 100).nullable()
    val createdAt = long("created_at")

    init {
        index(false, fromId)
    }
}

class ChatMessageEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<ChatMessageEntity>(ChatMessageTable)

    var fromId by ChatMessageTable.fromId
    var toUserId by ChatMessageTable.toUserId
    var toGroupId by ChatMessageTable.toGroupId
    var text by ChatMessageTable.text
    var mediaFile by ChatMessageTable.mediaFile
    var createdAt by ChatMessageTable.createdAt
}

// Позначки прочитання: приватний діалог — пара (хто, співрозмовник),
// груповий — (користувач, група). Одне з двох peer-полів заповнене.
object ChatReadTable : IntIdTable("chat_reads") {
    val userId = integer("user_id").references(UserTable.id, ReferenceOption.CASCADE)
    val peerUserId = integer("peer_user_id").references(UserTable.id, ReferenceOption.CASCADE).nullable()
    val groupId = integer("group_id").references(GroupTable.id, ReferenceOption.CASCADE).nullable()
    val lastReadAt = long("last_read_at")

    init {
        index(false, userId)
    }
}

class ChatReadEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<ChatReadEntity>(ChatReadTable)

    var userId by ChatReadTable.userId
    var peerUserId by ChatReadTable.peerUserId
    var groupId by ChatReadTable.groupId
    var lastReadAt by ChatReadTable.lastReadAt
}

// ===== 1.6.2: реакції на повідомлення =====
object ChatReactionTable : IntIdTable("chat_reactions") {
    val messageId = integer("message_id").references(ChatMessageTable.id, ReferenceOption.CASCADE)
    val userId = integer("user_id").references(UserTable.id, ReferenceOption.CASCADE)
    /** Один емодзі-рядок: "❤️", "😂", "👍" тощо. */
    val emoji = varchar("emoji", 16)

    init {
        uniqueIndex(messageId, userId, emoji)
    }
}

class ChatReactionEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<ChatReactionEntity>(ChatReactionTable)

    var messageId by ChatReactionTable.messageId
    var userId by ChatReactionTable.userId
    var emoji by ChatReactionTable.emoji
}

// ===== 1.6.2: блокування користувачів =====
object BlockTable : IntIdTable("blocks") {
    /** Хто заблокував. */
    val blockerId = integer("blocker_id").references(UserTable.id, ReferenceOption.CASCADE)
    /** Кого заблокували. */
    val blockedId = integer("blocked_id").references(UserTable.id, ReferenceOption.CASCADE)
    val createdAt = long("created_at")

    init {
        uniqueIndex(blockerId, blockedId)
    }
}

class BlockEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<BlockEntity>(BlockTable)

    var blockerId by BlockTable.blockerId
    var blockedId by BlockTable.blockedId
    var createdAt by BlockTable.createdAt
}
// ===== 1.6.0: рілси — вертикальні відео 9:16 =====
object ReelTable : IntIdTable("reels") {
    val userId = integer("user_id").references(UserTable.id, ReferenceOption.CASCADE)
    val videoFile = varchar("video_file", 100)
    val caption = varchar("caption", 500).default("")
    val createdAt = long("created_at")
}

class ReelEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<ReelEntity>(ReelTable)

    var userId by ReelTable.userId
    var videoFile by ReelTable.videoFile
    var caption by ReelTable.caption
    var createdAt by ReelTable.createdAt
}

object ReelLikeTable : IntIdTable("reel_likes") {
    val reelId = integer("reel_id").references(ReelTable.id, ReferenceOption.CASCADE)
    val userId = integer("user_id").references(UserTable.id, ReferenceOption.CASCADE)

    init {
        uniqueIndex(reelId, userId)
    }
}

class ReelLikeEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<ReelLikeEntity>(ReelLikeTable)

    var reelId by ReelLikeTable.reelId
    var userId by ReelLikeTable.userId
}
