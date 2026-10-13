package dev.amps.backend.service

import dev.amps.backend.model.ApiResponse
import dev.amps.backend.model.ApiResponse.Companion.ok
import dev.amps.backend.model.ChatDialogDto
import dev.amps.backend.model.ChatDialogsResponse
import dev.amps.backend.model.ChatMessageDto
import dev.amps.backend.model.ChatMessageEntity
import dev.amps.backend.model.ChatMessageTable
import dev.amps.backend.model.ChatMessagesResponse
import dev.amps.backend.model.ChatReadEntity
import dev.amps.backend.model.ChatReadTable
import dev.amps.backend.model.ChatReactionEntity
import dev.amps.backend.model.ChatReactionTable
import dev.amps.backend.model.GroupEntity
import dev.amps.backend.model.GroupMemberEntity
import dev.amps.backend.model.GroupMemberTable
import dev.amps.backend.model.ProfileDto
import dev.amps.backend.model.UserEntity
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/**
 * 1.6.0: чати — приватні діалоги 1-на-1 і групові чати (усередині груп).
 * Без WebSocket: клієнт опитує список діалогів та історію кожні ~15 с.
 * У чатах — текст і фото (сховище спільне з медіа постів). Кожне
 * повідомлення — невеликий XP за активність.
 */
class ChatService(
    private val profiles: ProfileService,
    private val groups: GroupService,
    // 1.6.2: блокування — заблокований не зможе написати приватне повідомлення.
    private val blocks: dev.amps.backend.service.BlockService? = null,
    // 1.6.3: сховище — щоб при видаленні повідомлення стерти його файл.
    private val mediaStorage: MediaStorage? = null,
) {

    /** Приватне повідомлення користувачу. XP за активність. */
    suspend fun sendPrivate(
        fromId: Int,
        toUserId: Int,
        text: String,
        mediaFile: String?,
    ): ApiResponse<Boolean> = newSuspendedTransaction {
        // 1.6.1: антиспам — не більше 20 повідомлень за хвилину.
        if (!RateLimiter.allow("chat:$fromId", max = 20, windowMs = 60_000L)) {
            return@newSuspendedTransaction ApiResponse.fail(
                "Забагато повідомлень — трохи повільніше 🙏", "RATE_LIMITED",
            )
        }
        if (toUserId == fromId) {
            return@newSuspendedTransaction ApiResponse.fail(
                "Собі повідомлення не надсилають", "SELF_CHAT",
            )
        }
        if (UserEntity.findById(toUserId) == null) {
            return@newSuspendedTransaction ApiResponse.fail("Отримувача не знайдено", "NO_USER")
        }
        // 1.6.2: отримувач заблокував відправника — листування закрите.
        if (blocks?.isBlocked(blockerId = toUserId, targetId = fromId) == true) {
            return@newSuspendedTransaction ApiResponse.fail(
                "Користувач обмежив листування", "BLOCKED",
            )
        }
        val cleanText = text.trim().take(1000)
        if (cleanText.isEmpty() && mediaFile == null) {
            return@newSuspendedTransaction ApiResponse.fail("Порожнє повідомлення", "EMPTY_MESSAGE")
        }
        ChatMessageEntity.new {
            this.fromId = fromId
            this.toUserId = toUserId
            this.toGroupId = null
            this.text = cleanText
            this.mediaFile = mediaFile
            this.createdAt = System.currentTimeMillis()
        }
        // 1.6.1: XP за чат — не більш ніж за 100 повідомлень на добу.
        if (RateLimiter.allow("xpchat:$fromId", max = 100, windowMs = 86_400_000L)) {
            XpLedger.award(fromId, XpLedger.CHAT_MESSAGE)
        }
        ApiResponse.ok(true)
    }

    /** Повідомлення в груповий чат: тільки учасники групи. */
    suspend fun sendGroup(
        fromId: Int,
        groupId: Int,
        text: String,
        mediaFile: String?,
    ): ApiResponse<Boolean> = newSuspendedTransaction {
        // 1.6.1: антиспам і в груповому чаті.
        if (!RateLimiter.allow("chat:$fromId", max = 20, windowMs = 60_000L)) {
            return@newSuspendedTransaction ApiResponse.fail(
                "Забагато повідомлень — трохи повільніше 🙏", "RATE_LIMITED",
            )
        }
        if (!groups.isMember(groupId, fromId)) {
            return@newSuspendedTransaction ApiResponse.fail(
                "Писати в чат групи можуть тільки учасники", "NOT_MEMBER",
            )
        }
        val cleanText = text.trim().take(1000)
        if (cleanText.isEmpty() && mediaFile == null) {
            return@newSuspendedTransaction ApiResponse.fail("Порожнє повідомлення", "EMPTY_MESSAGE")
        }
        ChatMessageEntity.new {
            this.fromId = fromId
            this.toUserId = null
            this.toGroupId = groupId
            this.text = cleanText
            this.mediaFile = mediaFile
            this.createdAt = System.currentTimeMillis()
        }
        // 1.6.1: добовий ліміт XP за чат спільний для приватних і групових.
        if (RateLimiter.allow("xpchat:$fromId", max = 100, windowMs = 86_400_000L)) {
            XpLedger.award(fromId, XpLedger.CHAT_MESSAGE)
        }
        ApiResponse.ok(true)
    }

    /** 1.6.2: URL вкладення повідомлення. Старе фото (до 1.6.2) лежало
     *  у публічному каталозі photo-*, нове — у приватному private-*. */
    private fun mediaUrlOf(mediaFile: String?): String? =
        mediaFile?.let { if (it.startsWith("photo-")) "/media/photo/$it" else "/media/private/$it" }

    /** Історія приватного діалогу: свіжі зверху, пагінація offset'ом. */
    suspend fun historyPrivate(
        viewerId: Int,
        peerId: Int,
        limit: Int,
        offset: Long,
    ): ApiResponse<ChatMessagesResponse> = newSuspendedTransaction {
        val rows = ChatMessageEntity.find {
            ((ChatMessageTable.fromId eq viewerId) and (ChatMessageTable.toUserId eq peerId)) or
                ((ChatMessageTable.fromId eq peerId) and (ChatMessageTable.toUserId eq viewerId))
        }.orderBy(ChatMessageTable.createdAt to SortOrder.DESC)
            .limit(limit, offset)
            .toList()
        ApiResponse.ok(ChatMessagesResponse(rows.map { it.toDto(viewerId) }))
    }

    /** Історія групового чату — тільки для учасників групи. */
    suspend fun historyGroup(
        groupId: Int,
        viewerId: Int,
        limit: Int,
        offset: Long,
    ): ApiResponse<ChatMessagesResponse> = newSuspendedTransaction {
        if (!groups.isMember(groupId, viewerId)) {
            return@newSuspendedTransaction ApiResponse.fail(
                "Чат групи доступний тільки учасникам", "NOT_MEMBER",
            )
        }
        val rows = ChatMessageEntity.find { ChatMessageTable.toGroupId eq groupId }
            .orderBy(ChatMessageTable.createdAt to SortOrder.DESC)
            .limit(limit, offset)
            .toList()
        ApiResponse.ok(ChatMessagesResponse(rows.map { it.toDto(viewerId) }))
    }

    /**
     * Список діалогів: приватні (по співрозмовниках) + групові (у групах,
     * де я учасник і вже щось писали). Останнє повідомлення і непрочитані.
     */
    suspend fun dialogs(viewerId: Int): ApiResponse<ChatDialogsResponse> = newSuspendedTransaction {
        // Останні 500 приватних повідомлень із моєю участю — гуртуємо в пам'яті.
        val mine = ChatMessageEntity.find {
            (ChatMessageTable.fromId eq viewerId) or (ChatMessageTable.toUserId eq viewerId)
        }.orderBy(ChatMessageTable.createdAt to SortOrder.DESC)
            .limit(500).toList()

        val lastByPeer = LinkedHashMap<Int, ChatMessageEntity>()
        for (m in mine) {
            if (m.toGroupId != null) continue // групове — окремо
            val peer: Int? = if (m.fromId == viewerId) m.toUserId else m.fromId
            if (peer != null && peer != viewerId && !lastByPeer.containsKey(peer)) {
                lastByPeer[peer] = m
            }
        }

        val dialogs = ArrayList<ChatDialogDto>()
        lastByPeer.forEach { (peerId, last) ->
            val profile = profileOf(peerId)
            dialogs.add(
                ChatDialogDto(
                    peerId = peerId,
                    kind = KIND_USER,
                    title = profile.displayName,
                    avatarPath = profile.avatarPath,
                    lastText = last.text,
                    lastMediaUrl = mediaUrlOf(last.mediaFile),
                    lastAt = last.createdAt,
                    unread = unreadPrivate(viewerId, peerId),
                )
            )
        }

        // Групові чати: групи, де я учасник, з хоч одним повідомленням.
        val myGroups = GroupMemberEntity.find { GroupMemberTable.userId eq viewerId }
            .map { it.groupId }
        for (gid in myGroups) {
            val last = ChatMessageEntity.find { ChatMessageTable.toGroupId eq gid }
                .orderBy(ChatMessageTable.createdAt to SortOrder.DESC)
                .limit(1).firstOrNull() ?: continue
            val group = GroupEntity.findById(gid) ?: continue
            dialogs.add(
                ChatDialogDto(
                    peerId = gid,
                    kind = KIND_GROUP,
                    title = group.name,
                    avatarPath = group.avatarFile?.let { "/media/photo/$it" },
                    lastText = last.text,
                    lastMediaUrl = mediaUrlOf(last.mediaFile),
                    lastAt = last.createdAt,
                    unread = unreadGroup(viewerId, gid),
                )
            )
        }
        ApiResponse.ok(ChatDialogsResponse(dialogs.sortedByDescending { it.lastAt }))
    }

    /**
     * 1.6.1: сумарні непрочитані по всіх діалогах — для бейджа на вкладці
     * «Чати» і фонових сповіщень. Перевикористовує dialogs(): на 500
     * останніх повідомлень це дешево і завжди узгоджено зі списком.
     */
    suspend fun totalUnread(viewerId: Int): Int {
        val data = dialogs(viewerId).data ?: return 0
        return data.dialogs.sumOf { it.unread }
    }

    /**
     * 1.6.3: видалити своє повідомлення. Дозволено лише автору; реакції
     * зникають каскадом (FK ON DELETE CASCADE), файл медіа — з диска.
     */
    suspend fun deleteMessage(viewerId: Int, messageId: Int): ApiResponse<Unit> =
        newSuspendedTransaction {
            val message = ChatMessageEntity.findById(messageId)
                ?: return@newSuspendedTransaction ApiResponse.fail(
                    "Повідомлення не знайдено", "NO_MESSAGE",
                )
            if (message.fromId != viewerId) {
                return@newSuspendedTransaction ApiResponse.fail(
                    "Видаляти можна лише свої повідомлення", "NOT_OWNER",
                )
            }
            val file = message.mediaFile
            message.delete()
            // Диск — не база: приватний файл прибираємо після транзакції.
            if (file != null) {
                val kind = if (file.startsWith("photo-")) {
                    dev.amps.backend.service.MediaStorage.KIND_PHOTO
                } else {
                    dev.amps.backend.service.MediaStorage.KIND_PRIVATE
                }
                mediaStorage?.delete(kind, file)
            }
            ok(Unit)
        }

    /**
     * Позначити діалог прочитаним: [peerUserId] — приватний, [groupId] —
     * груповий. Викликається клієнтом при відкритті чату.
     */
    suspend fun markRead(viewerId: Int, peerUserId: Int?, groupId: Int?): ApiResponse<Boolean> =
        newSuspendedTransaction {
            val now = System.currentTimeMillis()
            val existing = if (peerUserId != null) {
                ChatReadEntity.find {
                    (ChatReadTable.userId eq viewerId) and (ChatReadTable.peerUserId eq peerUserId)
                }.firstOrNull()
            } else if (groupId != null) {
                ChatReadEntity.find {
                    (ChatReadTable.userId eq viewerId) and (ChatReadTable.groupId eq groupId)
                }.firstOrNull()
            } else {
                null
            }
            if (existing != null) {
                existing.lastReadAt = now
            } else {
                ChatReadEntity.new {
                    this.userId = viewerId
                    this.peerUserId = peerUserId
                    this.groupId = groupId
                    this.lastReadAt = now
                }
            }
            ApiResponse.ok(true)
        }

    // --- внутрішні дрібниці --------------------------------------------------

    private fun ChatMessageEntity.toDto(viewerId: Int): ChatMessageDto = ChatMessageDto(
        id = id.value,
        from = profileOf(fromId),
        toUserId = toUserId,
        toGroupId = toGroupId,
        text = text,
        mediaUrl = mediaUrlOf(mediaFile),
        createdAt = createdAt,
        mine = fromId == viewerId,
        // 1.6.2: реакції повідомлення — агресовані по емодзі, «mine» з viewerId.
        reactions = reactionsOf(id.value, viewerId),
    )

    /** 1.6.2: реакції повідомлення: group by emoji → (emoji, count, mine). */
    private fun reactionsOf(messageId: Int, viewerId: Int): List<dev.amps.backend.model.ChatReactionDto> {
        val rows = ChatReactionEntity.find { ChatReactionTable.messageId eq messageId }.toList()
        if (rows.isEmpty()) return emptyList()
        return rows.groupBy { it.emoji }.map { (emoji, list) ->
            dev.amps.backend.model.ChatReactionDto(
                emoji = emoji,
                count = list.size,
                mine = list.any { it.userId == viewerId },
            )
        }
    }

    // ===== 1.6.2: реакції на повідомлення =====

    /**
     * Перемкнути реакцію [emoji] на повідомлення. Дозволено: відправнику
     * й отримувачу приватного чату; учасникам групи — у груповому.
     * Повторна реакція тим самим емодзі знімає її.
     */
    suspend fun toggleReaction(viewerId: Int, messageId: Int, emoji: String): ApiResponse<ChatMessageDto> =
        newSuspendedTransaction {
            val message = ChatMessageEntity.findById(messageId)
                ?: return@newSuspendedTransaction ApiResponse.fail("Повідомлення не знайдено", "NO_MESSAGE")
            val canSee = when {
                message.fromId == viewerId || message.toUserId == viewerId -> true
                message.toGroupId != null -> groups.isMember(message.toGroupId!!, viewerId)
                else -> false
            }
            if (!canSee) {
                return@newSuspendedTransaction ApiResponse.fail(
                    "Реагувати можна лише у своїх чатах", "NOT_ALLOWED",
                )
            }
            val clean = emoji.trim()
            if (clean.isEmpty() || clean.length > 8) {
                return@newSuspendedTransaction ApiResponse.fail("Невірна реакція", "BAD_EMOJI")
            }
            val existing = ChatReactionEntity.find {
                (ChatReactionTable.messageId eq messageId) and
                    (ChatReactionTable.userId eq viewerId) and
                    (ChatReactionTable.emoji eq clean)
            }.firstOrNull()
            if (existing != null) {
                existing.delete()
            } else {
                ChatReactionEntity.new {
                    this.messageId = messageId
                    this.userId = viewerId
                    this.emoji = clean
                }
            }
            ok(message.toDto(viewerId))
        }

    /** 1.6.2: чи може [viewerId] бачити приватне вкладення [fileName]. */
    suspend fun canAccessMedia(viewerId: Int, fileName: String): Boolean = newSuspendedTransaction {
        val mine = ChatMessageEntity.find {
            (ChatMessageTable.mediaFile eq fileName) and
                ((ChatMessageTable.fromId eq viewerId) or (ChatMessageTable.toUserId eq viewerId))
        }.firstOrNull() != null
        if (mine) return@newSuspendedTransaction true
        // Групові вкладення: я учасник групи, де це фото було надіслане.
        val msg = ChatMessageEntity.find { ChatMessageTable.mediaFile eq fileName }
            .firstOrNull() ?: return@newSuspendedTransaction false
        val gid = msg.toGroupId ?: return@newSuspendedTransaction false
        groups.isMember(gid, viewerId)
    }

    private fun profileOf(userId: Int): ProfileDto =
        profiles.profileOf(profiles.ensureProfile(userId))

    /** Непрочитані в приватному діалозі: повідомлення співрозмовника після мого останнього відкриття. */
    private fun unreadPrivate(viewerId: Int, peerId: Int): Int {
        val lastRead = ChatReadEntity.find {
            (ChatReadTable.userId eq viewerId) and (ChatReadTable.peerUserId eq peerId)
        }.firstOrNull()?.lastReadAt ?: 0L
        return ChatMessageEntity.find {
            (ChatMessageTable.toUserId eq viewerId) and
                (ChatMessageTable.fromId eq peerId) and
                (ChatMessageTable.createdAt greater lastRead)
        }.count().toInt()
    }

    /** Непрочитані в груповому чаті: чужі повідомлення після мого останнього відкриття. */
    private fun unreadGroup(viewerId: Int, groupId: Int): Int {
        val lastRead = ChatReadEntity.find {
            (ChatReadTable.userId eq viewerId) and (ChatReadTable.groupId eq groupId)
        }.firstOrNull()?.lastReadAt ?: 0L
        return ChatMessageEntity.find {
            (ChatMessageTable.toGroupId eq groupId) and
                (ChatMessageTable.createdAt greater lastRead) and
                (ChatMessageTable.fromId neq viewerId)
        }.count().toInt()
    }

    companion object {
        const val KIND_USER = "user"
        const val KIND_GROUP = "group"
    }
}
