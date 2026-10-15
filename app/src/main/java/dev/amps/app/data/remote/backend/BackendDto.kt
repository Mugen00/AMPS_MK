package dev.amps.app.data.remote.backend.dto

import kotlinx.serialization.Serializable

@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null,
    val errorCode: String? = null
) {
    fun isSuccess(): Boolean = success && data != null
    fun getOrThrow(): T {
        if (isSuccess()) return data!!
        throw BackendException(error ?: "Unknown error", errorCode)
    }
}

class BackendException(message: String, val errorCode: String?) : Exception(message)

// ===== Auth DTOs =====
@Serializable
data class RegisterRequest(
    val login: String,
    val email: String,
    val password: String,
    val phone: String? = null
)

@Serializable
data class LoginRequest(
    val login: String,
    val password: String,
    /** 1.1.2: код з аутентифікатора — потрібен лише при ввімкненій 2FA. */
    val totp: String? = null
)

@Serializable
data class VerifyCodeRequest(
    val login: String,
    val code: String,
    val type: String  // "EMAIL_VERIFY" | "PHONE_VERIFY"
)

@Serializable
data class PasswordResetRequest(
    val email: String
)

@Serializable
data class PasswordResetConfirmRequest(
    val email: String,
    val code: String,
    val newPassword: String
)

@Serializable
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val user: UserInfo
)

@Serializable
data class UserInfo(
    val id: Int,
    val login: String,
    val email: String,
    val phone: String?,
    val emailVerified: Boolean,
    val phoneVerified: Boolean,
    val twoFactorEnabled: Boolean
)

@Serializable
data class RefreshRequest(
    val refreshToken: String
)

@Serializable
data class UnitResult(
    val success: Boolean = true
)

// ===== 2FA DTOs =====
@Serializable
data class TwoFactorSetupResponse(
    val secret: String,
    val otpAuthUrl: String
)

@Serializable
data class TwoFactorVerifyRequest(
    val code: String
)

@Serializable
data class TwoFactorStatusResponse(
    val enabled: Boolean
)

// ===== Sync DTOs =====
@Serializable
data class SyncRequest(
    val version: Int,
    val data: Map<String, String>
)

@Serializable
data class SyncResponse(
    val version: Int,
    val data: Map<String, String>
)

@Serializable
data class SyncConflictResponse(
    val serverVersion: Int,
    val serverData: Map<String, String>
)

/** Профіль користувача: показуване ім'я, біо, аватар. */
@Serializable
data class ProfileDto(
    val userId: Int,
    val login: String,
    val displayName: String,
    val bio: String? = null,
    /** Відносний шлях аватара ("/media/avatar/…") або null. */
    val avatarPath: String? = null,
    // 1.4.0: улюблені жанри й аніме — шапка профілю в стилі Instagram.
    val favoriteGenres: List<String> = emptyList(),
    val favoriteAnime: List<FavoriteAnimeDto> = emptyList()
)

/** Улюблений тайтл у профілі: id AniList + назва + обкладинка. */
@Serializable
data class FavoriteAnimeDto(
    val anilistId: Int,
    val title: String,
    val cover: String? = null
)

@Serializable
data class ProfileUpdateRequest(
    val displayName: String,
    val bio: String? = null,
    /** null — не змінювати; список (можливо порожній) — замінити. */
    val favoriteGenres: List<String>? = null,
    val favoriteAnime: List<FavoriteAnimeDto>? = null
)

/** 1.4.0: сторінка тайтла на yummyani.me для кнопки «Дивитись». */
@Serializable
data class WatchUrlResponse(
    val url: String,
    val foundTitle: String? = null
)

@Serializable
data class AvatarResponse(
    val avatarUrl: String
)

// ===== Спільнота (1.1.2) =====

@Serializable
data class MediaDto(
    /** "photo" | "video" */
    val kind: String,
    /** Відносний шлях: /media/{kind}/{file}; до нього додається адреса сервера. */
    val url: String
)

/** Пост: автор, текст, вкладення, дата публікації, лічильники. */
@Serializable
data class PostDto(    val id: Int,
    val author: ProfileDto,
    val text: String,
    val media: List<MediaDto> = emptyList(),
    /** Епоха-мілісекунди — дата публікації, яку показує застосунок. */
    val createdAt: Long,
    val likeCount: Int,
    val repostCount: Int,
    val likedByMe: Boolean,
    val repostedByMe: Boolean,
    /** 1.2.3: опитування, прикріплене до поста (null — його немає). */
    val poll: PollDto? = null,
    /** 1.5.1: формат показу фото ("original"|"1:1"|"4:5"|"16:9"; null — як раніше). */
    val imageAspect: String? = null,
    /** 1.6.1: дата редагування (null — не редагувався); клієнт показує «(ред.)». */
    val editedAt: Long? = null,
    /** 1.6.3: «кружок» — кругле відео до хвилини; показується колом. */
    val roundVideo: Boolean = false,
    /** 1.6.5: закріплений пост спільноти. */
    val pinned: Boolean = false,
)

// 1.6.1: редагування власного поста.
@Serializable
data class PostEditRequest(val text: String)

// 1.6.1: оновлений пост після редагування.
@Serializable
data class PostEditedResponse(val post: PostDto)

// 1.6.1: пошук людей за ім'ям/логіном.
@Serializable
data class UsersSearchResponse(
    val users: List<ProfileDto> = emptyList()
)

// 1.6.1: сумарні непрочитані чатів — бейдж вкладки і фонові сповіщення.
@Serializable
data class ChatUnreadResponse(val unread: Int)

// ===== Опитування (1.2.3) =====

@Serializable
data class PollOptionDto(
    val id: Int,
    val text: String,
    val votes: Int
)

@Serializable
data class PollDto(
    val pollId: Int,
    val question: String? = null,
    val options: List<PollOptionDto> = emptyList(),
    /** Варіант, за який голосував я (null — ще не голосував). */
    val myOptionId: Int? = null
)

@Serializable
data class PollCreateRequest(
    val question: String = "",
    val options: List<String>
)

@Serializable
data class PollResponse(
    val poll: PollDto
)

@Serializable
data class ProfileStatsDto(
    val posts: Int,
    val followers: Int,
    val watching: Int,
    val watchingTitle: String? = null,
    // 1.2.4: XP і рівень 1–100 (старий сервер шле без них — дефолти).
    val xp: Int = 0,
    val level: Int = 1,
    val xpToNext: Int = 0
)

/**
 * Рядок стрічки: власне пост (kind = "POST") або репост — тоді
 * [repostBy] вказує, хто поширив, а [post] несе оригінал.
 */
@Serializable
data class FeedItemDto(
    val kind: String,
    val post: PostDto,
    val repostBy: ProfileDto? = null,
    val at: Long
)

@Serializable
data class FeedResponse(
    val items: List<FeedItemDto>
)

@Serializable
data class PostCreatedResponse(
    val postId: Int
)

// ===== Сторіс (1.2.0) =====

/** Одна сторіс: медіа за відносним URL і час зникнення. */
@Serializable
data class StoryDto(
    val id: Int,
    val author: ProfileDto,
    /** "photo" | "video" */
    val kind: String,
    /** Відносний шлях: /media/{kind}/{file}; клієнт додає адресу сервера. */
    val url: String,
    val createdAt: Long,
    val expiresAt: Long
)

@Serializable
data class StoriesResponse(
    val stories: List<StoryDto>
)

// ===== Google (1.2.0) =====

/** ID-токен від Credential Manager — сервер перевіряє його через tokeninfo. */
@Serializable
data class GoogleAuthRequest(
    val idToken: String
)

@Serializable
data class LikeToggleResponse(
    val liked: Boolean,
    val likeCount: Int
)

@Serializable
data class RepostResponse(
    val reposted: Boolean,
    val repostCount: Int
)

// ===== Плейлісти (1.2.1) =====

@Serializable
data class PlaylistDto(
    val id: Int,
    val name: String,
    val trackCount: Int,
    val createdAt: Long,
)

@Serializable
data class PlaylistsResponse(val playlists: List<PlaylistDto>)

@Serializable
data class PlaylistCreatedResponse(val playlistId: Int)

/** Трек у плейлісті: знімок рядка пошуку — грає навіть без пошуку. */
@Serializable
data class PlaylistTrackDto(
    val id: Int,
    val source: String,
    val sourceId: String,
    val title: String,
    val artist: String,
    val audioUrl: String,
    val coverUrl: String? = null,
    val pageUrl: String? = null,
    val licenseUrl: String? = null,
    val durationSec: Int? = null,
    val position: Int,
    val addedAt: Long,
)

@Serializable
data class PlaylistTracksResponse(val tracks: List<PlaylistTrackDto>)

@Serializable
data class PlaylistCreateRequest(val name: String)

@Serializable
data class PlaylistTrackAddRequest(
    val source: String,
    val sourceId: String,
    val title: String,
    val artist: String = "",
    val audioUrl: String,
    val coverUrl: String? = null,
    val pageUrl: String? = null,
    val licenseUrl: String? = null,
    val durationSec: Int? = null,
)

// ===== Коментарі (1.2.2) =====

/** Коментар під постом: автор з аватаркою, текст, час. */
@Serializable
data class CommentDto(
    val id: Int,
    val author: ProfileDto,
    val postId: Int,
    val text: String,
    val createdAt: Long,
)

@Serializable
data class CommentsResponse(val comments: List<CommentDto>)

@Serializable
data class CommentCreateRequest(val text: String)

@Serializable
data class CommentCreatedResponse(val commentId: Int)

// ===== Підписки (1.2.2) =====

@Serializable
data class FollowToggleResponse(
    val following: Boolean,
    val followers: Int,
)

/** 1.2.5: чужий профіль — дані + чи підписаний я + лічильники. */
@Serializable
data class UserProfileResponse(
    val profile: ProfileDto,
    val isFollowing: Boolean = false,
    val followers: Int = 0,
    val postsCount: Int = 0,
)

// ===== Аніме-список акаунта (1.2.2) =====

@Serializable
data class AnimeListDto(
    val anilistId: Int,
    val title: String,
    val coverUrl: String,
    /** "watching" | "planned" | "watched" */
    val status: String,
    val reminder: Boolean,
    val updatedAt: Long,
)

@Serializable
data class AnimeListResponse(val items: List<AnimeListDto>)

@Serializable
data class AnimeListUpsertRequest(
    val anilistId: Int,
    val title: String,
    val coverUrl: String = "",
    val status: String,
    val reminder: Boolean = true,
)

// ===== Групи (1.6.0) =====

@Serializable
data class GroupDto(
    val id: Int,
    val owner: ProfileDto,
    val name: String,
    val description: String,
    val avatarPath: String? = null,
    /** 1.6.4: обкладинка спільноти (як у ВК); null — градієнт-заглушка. */
    val coverPath: String? = null,
    val isPrivate: Boolean = false,
    val memberCount: Int = 0,
    /** Я — учасник цієї групи? */
    val isMember: Boolean = false,
    /** "owner" | "mod" | "member" — моя роль, якщо учасник. */
    val myRole: String? = null,
    val createdAt: Long = 0,
)

@Serializable
data class GroupCreateRequest(
    val name: String,
    val description: String = "",
    val isPrivate: Boolean = false,
)

@Serializable
data class GroupCreatedResponse(val groupId: Int)

// 1.6.4: оновлення групи (назва/опис) + відповідь.
@Serializable
data class GroupUpdateRequest(
    val name: String,
    val description: String = "",
)

@Serializable
data class GroupUpdatedResponse(val group: GroupDto)

@Serializable
data class GroupListResponse(val groups: List<GroupDto>)

@Serializable
data class GroupDetailDto(
    val group: GroupDto,
    /** Запит у приватну групу вже висілан і чекає схвалення. */
    val myRequestPending: Boolean = false,
)

@Serializable
data class GroupMemberDto(
    val userId: Int,
    val displayName: String,
    val login: String,
    val avatarPath: String? = null,
    /** "owner" | "mod" | "member" */
    val role: String,
    val joinedAt: Long,
)

@Serializable
data class GroupMembersResponse(val members: List<GroupMemberDto>)

@Serializable
data class GroupJoinRequestDto(
    val requestId: Int,
    val groupId: Int,
    val user: ProfileDto,
    val createdAt: Long,
)

@Serializable
data class GroupRequestsResponse(val requests: List<GroupJoinRequestDto>)

@Serializable
data class GroupRoleRequest(val userId: Int, val role: String)

@Serializable
data class GroupKickRequest(val userId: Int)

// 1.6.5: нові запити для керування спільнотою.
@Serializable
data class GroupTransferRequest(val userId: Int)

@Serializable
data class GroupPinRequest(val pin: Boolean)

@Serializable
data class GroupInviteRequest(
    /** 0 = безкінечна кількість сканувань. */
    val maxUses: Int,
    val expiresAt: Long,
)

@Serializable
data class GroupInviteCodeResponse(val code: String)

// ===== Чати (1.6.0) =====

@Serializable
data class ChatMessageDto(
    val id: Int,
    val from: ProfileDto,
    val toUserId: Int? = null,
    val toGroupId: Int? = null,
    val text: String,
    val mediaUrl: String? = null,
    val createdAt: Long,
    /** Своє повідомлення — щоб клієнт вмів вирівнювати бульбашки. */
    val mine: Boolean = false,
    /** 1.6.2: реакції повідомлення (emoji → count + чи моя). */
    val reactions: List<ChatReactionDto> = emptyList(),
)

/** 1.6.2: реакція повідомлення — емодзі, скільки людей, чи моя. */
@Serializable
data class ChatReactionDto(
    val emoji: String,
    val count: Int,
    val mine: Boolean,
)

// 1.6.2: перемикання реакції.
@Serializable
data class ChatReactionToggleRequest(val emoji: String)

// 1.6.2: «друкує…».
@Serializable
data class ChatTypingRequest(val peerUserId: Int? = null, val groupId: Int? = null)

@Serializable
data class TypingResponse(val typing: Boolean)

// 1.6.2: блокування користувача — toggle.
@Serializable
data class BlockToggleResponse(val blocked: Boolean)

// ===== Лідерборд XP (1.6.2) =====

@Serializable
data class LeaderboardEntryDto(
    val userId: Int,
    val displayName: String,
    val avatarPath: String? = null,
    val xp: Int,
    val level: Int,
)

@Serializable
data class LeaderboardResponse(
    val entries: List<LeaderboardEntryDto> = emptyList(),
    val myRank: Int = 0,
    val myXp: Int = 0,
    val myLevel: Int = 1,
)

@Serializable
data class ChatMessagesResponse(val messages: List<ChatMessageDto>)

@Serializable
data class ChatMessageSendRequest(val text: String)

/** Рядок списку чатів: співрозмовник (або група) + останнє повідомлення. */
@Serializable
data class ChatDialogDto(
    /** Для приватного — id співрозмовника; для групового — id групи. */
    val peerId: Int,
    /** "user" | "group" */
    val kind: String,
    val title: String,
    val avatarPath: String? = null,
    val lastText: String = "",
    val lastMediaUrl: String? = null,
    val lastAt: Long,
    /** Непрочитані з останнього відкриття. */
    val unread: Int = 0,
)

@Serializable
data class ChatDialogsResponse(val dialogs: List<ChatDialogDto>)

/** Контакти для старту діалогу: ті, на кого я підписаний. */
@Serializable
data class ChatContactsResponse(val contacts: List<ProfileDto>)

// ===== Рілси (1.6.0) =====

@Serializable
data class ReelDto(
    val id: Int,
    val author: ProfileDto,
    /** Відносний шлях відео: /media/video/{file}. */
    val videoUrl: String,
    val caption: String = "",
    val createdAt: Long = 0,
    val likeCount: Int = 0,
    val likedByMe: Boolean = false,
)

@Serializable
data class ReelsResponse(val reels: List<ReelDto>)

@Serializable
data class ReelLikeResponse(val liked: Boolean, val likeCount: Int)