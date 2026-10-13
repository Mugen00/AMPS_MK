package dev.amps.backend.service

import dev.amps.backend.model.ApiResponse
import dev.amps.backend.model.ChatDialogDto
import dev.amps.backend.model.ChatDialogsResponse
import dev.amps.backend.model.ChatMessageDto
import dev.amps.backend.model.ChatMessageEntity
import dev.amps.backend.model.ChatMessageTable
import dev.amps.backend.model.ChatMessagesResponse
import dev.amps.backend.model.ChatReadEntity
import dev.amps.backend.model.ChatReadTable
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
) {

    /** Приватне повідомлення користувачу. XP за активність. */
    suspend fun sendPrivate(
        fromId: Int,
        toUserId: Int,
        text: String,
        mediaFile: String?,
    ): ApiResponse<Boolean> = newSuspendedTransaction {
        if (toUserId == fromId) {
            return@newSuspendedTransaction ApiResponse.fail(
                "Собі повідомлення не надсилають", "SELF_CHAT",
            )
        }
        if (UserEntity.findById(toUserId) == null) {
            return@newSuspendedTransaction ApiResponse.fail("Отримувача не знайдено", "NO_USER")
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
        XpLedger.award(fromId, XpLedger.CHAT_MESSAGE)
        ApiResponse.ok(true)
    }

    /** Повідомлення в груповий чат: тільки учасники групи. */
    suspend fun sendGroup(
        fromId: Int,
        groupId: Int,
        text: String,
        mediaFile: String?,
    ): ApiResponse<Boolean> = newSuspendedTransaction {
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
        XpLedger.award(fromId, XpLedger.CHAT_MESSAGE)
        ApiResponse.ok(true)
    }

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
                    lastMediaUrl = last.mediaFile?.let { "/media/photo/$it" },
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
                    lastMediaUrl = last.mediaFile?.let { "/media/photo/$it" },
                    lastAt = last.createdAt,
                    unread = unreadGroup(viewerId, gid),
                )
            )
        }
        ApiResponse.ok(ChatDialogsResponse(dialogs.sortedByDescending { it.lastAt }))
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
        mediaUrl = mediaFile?.let { "/media/photo/$it" },
        createdAt = createdAt,
        mine = fromId == viewerId,
    )

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
