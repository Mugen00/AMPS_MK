package dev.amps.backend.service

import dev.amps.backend.model.ApiResponse
import dev.amps.backend.model.GroupCreatedResponse
import dev.amps.backend.model.GroupDetailDto
import dev.amps.backend.model.GroupDto
import dev.amps.backend.model.GroupEntity
import dev.amps.backend.model.GroupInviteCodeEntity
import dev.amps.backend.model.GroupInviteCodeTable
import dev.amps.backend.model.GroupJoinRequestDto
import dev.amps.backend.model.GroupJoinRequestEntity
import dev.amps.backend.model.GroupJoinRequestTable
import dev.amps.backend.model.GroupListResponse
import dev.amps.backend.model.GroupMemberDto
import dev.amps.backend.model.GroupMemberEntity
import dev.amps.backend.model.GroupMemberTable
import dev.amps.backend.model.GroupMembersResponse
import dev.amps.backend.model.GroupRequestsResponse
import dev.amps.backend.model.GroupUpdatedResponse
import dev.amps.backend.model.GroupTable
import dev.amps.backend.model.PostEntity
import dev.amps.backend.model.PostMediaEntity
import dev.amps.backend.model.PostMediaTable
import dev.amps.backend.model.PostTable
import dev.amps.backend.model.ProfileDto
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 1.6.0: групи — спільноти за інтересами всередині Спільноти. Публічні
 * (вступ одразу) і приватні (запит на вступ, який схвалює власник/мод).
 * Ролі: owner — один на групу, mod — призначає owner, member — решта.
 * Активності в групі нараховують XP: створення групи і вступ.
 * Постинг у групі — через FeedService (пост із group_id).
 */
class GroupService(
    private val profiles: ProfileService,
    // 1.6.2: сховище — щоб при видаленні групи стерти і її файли.
    private val mediaStorage: MediaStorage? = null,
) {

    /** Створення групи: власник автоматично перший учасник. XP за створення. */
    suspend fun create(
        userId: Int,
        name: String,
        description: String,
        isPrivate: Boolean,
    ): ApiResponse<GroupCreatedResponse> = newSuspendedTransaction {
        val cleanName = name.trim()
        if (cleanName.isBlank()) {
            return@newSuspendedTransaction ApiResponse.fail("Назва групи порожня", "EMPTY_NAME")
        }
        val group = GroupEntity.new {
            ownerId = userId
            this.name = cleanName.take(80)
            this.description = description.trim().take(500)
            this.isPrivate = isPrivate
            this.createdAt = System.currentTimeMillis()
        }
        GroupMemberEntity.new {
            this.groupId = group.id.value
            this.userId = userId
            this.role = ROLE_OWNER
            this.joinedAt = group.createdAt
        }
        XpLedger.award(userId, XpLedger.GROUP_CREATE)
        ApiResponse.ok(GroupCreatedResponse(group.id.value))
    }

    /** Аватарка групи: файл уже у сховищі; міняти може тільки власник. */
    suspend fun setAvatar(groupId: Int, requesterId: Int, fileName: String): ApiResponse<Boolean> =
        newSuspendedTransaction {
            val group = GroupEntity.findById(groupId)
                ?: return@newSuspendedTransaction ApiResponse.fail("Групу не знайдено", "NO_GROUP")
            if (group.ownerId != requesterId) {
                return@newSuspendedTransaction ApiResponse.fail(
                    "Аватарку групи міняє тільки власник", "NOT_OWNER",
                )
            }
            group.avatarFile = fileName
            ApiResponse.ok(true)
        }

    /**
     * 1.6.4: обкладинка спільноти (як у ВК) — файл уже у сховищі.
     * Міняти можуть власник або модератор.
     */
    suspend fun setCover(groupId: Int, requesterId: Int, fileName: String): ApiResponse<Boolean> =
        newSuspendedTransaction {
            val role = roleOfTx(groupId, requesterId)
            if (role != ROLE_OWNER && role != ROLE_MOD) {
                return@newSuspendedTransaction ApiResponse.fail(
                    "Обкладинку міняють адміни групи", "NOT_ADMIN",
                )
            }
            val group = GroupEntity.findById(groupId)
                ?: return@newSuspendedTransaction ApiResponse.fail("Групу не знайдено", "NO_GROUP")
            val old = group.coverFile
            group.coverFile = fileName
            // Стара обкладинка — геть, щоб у сховищі не копились дублі.
            if (old != null && old != fileName) {
                mediaStorage?.delete(MediaStorage.KIND_PHOTO, old)
            }
            ApiResponse.ok(true)
        }

    /**
     * 1.6.4: оновити назву/опис групи. Роль-рівень — "owner"/"mod"
     * (як у ВК: адміни спільноти редагують її сторінку).
     */
    suspend fun update(
        groupId: Int,
        requesterId: Int,
        name: String,
        description: String,
    ): ApiResponse<GroupUpdatedResponse> = newSuspendedTransaction {
        val role = roleOfTx(groupId, requesterId)
        if (role != ROLE_OWNER && role != ROLE_MOD) {
            return@newSuspendedTransaction ApiResponse.fail(
                "Оновлюють групу адміни", "NOT_ADMIN",
            )
        }
        val cleanName = name.trim()
        if (cleanName.isBlank()) {
            return@newSuspendedTransaction ApiResponse.fail("Назва групи порожня", "EMPTY_NAME")
        }
        val group = GroupEntity.findById(groupId)
            ?: return@newSuspendedTransaction ApiResponse.fail("Групу не знайдено", "NO_GROUP")
        group.name = cleanName.take(80)
        group.description = description.trim().take(500)
        ApiResponse.ok(GroupUpdatedResponse(group.toDto(requesterId)))
    }

    /** Каталог груп: пошук за назвою/описом (регістр не важливий). */
    suspend fun list(q: String?, viewerId: Int): ApiResponse<GroupListResponse> = newSuspendedTransaction {
        val all = GroupEntity.all()
            .orderBy(GroupTable.createdAt to SortOrder.DESC)
            .toList()
        val needle = q?.trim()?.lowercase().orEmpty()
        val filtered = if (needle.isBlank()) all else {
            all.filter { g ->
                g.name.lowercase().contains(needle) || g.description.lowercase().contains(needle)
            }
        }
        ApiResponse.ok(GroupListResponse(filtered.map { it.toDto(viewerId) }))
    }

    /** Мої групи — ті, де я вже учасник (власник теж учасник). */
    suspend fun myGroups(viewerId: Int): ApiResponse<GroupListResponse> = newSuspendedTransaction {
        val myGroupIds = GroupMemberEntity.find { GroupMemberTable.userId eq viewerId }
            .map { it.groupId }
        val groups = myGroupIds.mapNotNull { GroupEntity.findById(it) }
            .sortedByDescending { it.createdAt }
        ApiResponse.ok(GroupListResponse(groups.map { it.toDto(viewerId) }))
    }

    /** Карточка групи: роль, членство, висить чи мій запит на вступ. */
    suspend fun detail(groupId: Int, viewerId: Int): ApiResponse<GroupDetailDto> = newSuspendedTransaction {
        val group = GroupEntity.findById(groupId)
            ?: return@newSuspendedTransaction ApiResponse.fail("Групу не знайдено", "NO_GROUP")
        val role = roleOfTx(groupId, viewerId)
        val pending = pendingRequestTx(groupId, viewerId)
        ApiResponse.ok(
            GroupDetailDto(
                id = group.id.value,
                owner = profileOf(group.ownerId),
                name = group.name,
                description = group.description,
                avatarPath = group.avatarFile?.let { "/media/photo/$it" },
                // 1.6.4: обкладинка спільноти.
                coverPath = group.coverFile?.let { "/media/photo/$it" },
                isPrivate = group.isPrivate,
                memberCount = memberCountTx(groupId),
                isMember = role != null,
                myRole = role,
                myRequestPending = group.isPrivate && role == null && pending,
                createdAt = group.createdAt,
            )
        )
    }

    /** Учасники групи: власник перший, далі за датою вступу. */
    suspend fun members(groupId: Int): ApiResponse<GroupMembersResponse> = newSuspendedTransaction {
        if (GroupEntity.findById(groupId) == null) {
            return@newSuspendedTransaction ApiResponse.fail("Групу не знайдено", "NO_GROUP")
        }
        val rows = GroupMemberEntity.find { GroupMemberTable.groupId eq groupId }
            .orderBy(GroupMemberTable.joinedAt to SortOrder.ASC)
            .toList()
        ApiResponse.ok(
            GroupMembersResponse(rows.map { m ->
                val p = profileOf(m.userId)
                GroupMemberDto(
                    userId = m.userId,
                    displayName = p.displayName,
                    login = p.login,
                    avatarPath = p.avatarPath,
                    role = m.role,
                    joinedAt = m.joinedAt,
                )
            })
        )
    }

    /** Вступ: публічна — одразу учасник; приватна — запит на схвалення. */
    suspend fun join(groupId: Int, userId: Int): ApiResponse<Boolean> = newSuspendedTransaction {
        val group = GroupEntity.findById(groupId)
            ?: return@newSuspendedTransaction ApiResponse.fail("Групу не знайдено", "NO_GROUP")
        if (roleOfTx(groupId, userId) != null) {
            return@newSuspendedTransaction ApiResponse.ok(true) // вже учасник — не помилка
        }
        if (group.isPrivate) {
            if (pendingRequestTx(groupId, userId)) {
                return@newSuspendedTransaction ApiResponse.fail(
                    "Запит на вступ уже надіслано", "REQUEST_PENDING",
                )
            }
            GroupJoinRequestEntity.new {
                this.groupId = groupId
                this.userId = userId
                this.createdAt = System.currentTimeMillis()
            }
            return@newSuspendedTransaction ApiResponse.fail(
                "Запит на вступ надіслано — чекай схвалення", "REQUEST_PENDING",
            )
        }
        GroupMemberEntity.new {
            this.groupId = groupId
            this.userId = userId
            this.role = ROLE_MEMBER
            this.joinedAt = System.currentTimeMillis()
        }
        XpLedger.award(userId, XpLedger.GROUP_JOIN)
        ApiResponse.ok(true)
    }

    /** Вихід із групи; власник вийти не може — спершу передати роль чи видалити. */
    suspend fun leave(groupId: Int, userId: Int): ApiResponse<Boolean> = newSuspendedTransaction {
        val group = GroupEntity.findById(groupId)
            ?: return@newSuspendedTransaction ApiResponse.fail("Групу не знайдено", "NO_GROUP")
        if (group.ownerId == userId) {
            return@newSuspendedTransaction ApiResponse.fail(
                "Власник не може вийти: передай роль або видали групу", "OWNER_CANT_LEAVE",
            )
        }
        val membership = GroupMemberEntity.find {
            (GroupMemberTable.groupId eq groupId) and (GroupMemberTable.userId eq userId)
        }.firstOrNull() ?: return@newSuspendedTransaction ApiResponse.fail(
            "Ти не учасник цієї групи", "NOT_MEMBER",
        )
        membership.delete()
        ApiResponse.ok(true)
    }

    /** Видалення групи — тільки власник. 1.6.2: медіа її постів і аватар теж стираються. */
    suspend fun delete(groupId: Int, requesterId: Int): ApiResponse<Boolean> = newSuspendedTransaction {
        val group = GroupEntity.findById(groupId)
            ?: return@newSuspendedTransaction ApiResponse.fail("Групу не знайдено", "NO_GROUP")
        if (group.ownerId != requesterId) {
            return@newSuspendedTransaction ApiResponse.fail(
                "Видаляти групу може тільки власник", "NOT_OWNER",
            )
        }
        // 1.6.2: збираємо медіа постів групи до каскадного видалення рядків.
        val groupPostIds = PostEntity.find { PostTable.groupId eq groupId }
            .map { it.id.value }
        val mediaFiles = if (groupPostIds.isEmpty()) emptyList() else {
            PostMediaEntity.find { PostMediaTable.postId inList groupPostIds }
                .map { it.kind to it.fileName }
        }
        val avatarFile = group.avatarFile
        group.delete()
        // Диск — не база: файли прибираємо після успішної транзакції.
        val storage = mediaStorage
        if (storage != null) {
            mediaFiles.forEach { (kind, fileName) -> storage.delete(kind, fileName) }
            if (avatarFile != null) {
                storage.delete(dev.amps.backend.service.MediaStorage.KIND_AVATAR, avatarFile)
            }
        }
        ApiResponse.ok(true)
    }

    /** Кік учасника: owner або mod; власника і себе кікнути не можна. */
    suspend fun kick(groupId: Int, requesterId: Int, targetId: Int): ApiResponse<Boolean> =
        newSuspendedTransaction {
            val role = roleOfTx(groupId, requesterId)
            if (role != ROLE_OWNER && role != ROLE_MOD) {
                return@newSuspendedTransaction ApiResponse.fail(
                    "Кікати можуть тільки власник і модератори", "NOT_ALLOWED",
                )
            }
            if (targetId == requesterId) {
                return@newSuspendedTransaction ApiResponse.fail(
                    "Себе кікають через вихід із групи", "SELF_KICK",
                )
            }
            val target = GroupMemberEntity.find {
                (GroupMemberTable.groupId eq groupId) and (GroupMemberTable.userId eq targetId)
            }.firstOrNull() ?: return@newSuspendedTransaction ApiResponse.fail(
                "Користувач не учасник групи", "NOT_MEMBER",
            )
            if (target.role == ROLE_OWNER) {
                return@newSuspendedTransaction ApiResponse.fail(
                    "Власника кікнути не можна", "OWNER_KICK",
                )
            }
            target.delete()
            ApiResponse.ok(true)
        }

    /**
     * Призначення ролі: owner може зробити учасника модератором (mod) або
     * зняти модератора назад у member. Модератори ролі не міняють.
     */
    suspend fun setRole(groupId: Int, requesterId: Int, targetId: Int, role: String): ApiResponse<Boolean> =
        newSuspendedTransaction {
            val group = GroupEntity.findById(groupId)
                ?: return@newSuspendedTransaction ApiResponse.fail("Групу не знайдено", "NO_GROUP")
            if (group.ownerId != requesterId) {
                return@newSuspendedTransaction ApiResponse.fail("Ролі міняє тільки власник", "NOT_OWNER")
            }
            if (role !in listOf(ROLE_MOD, ROLE_MEMBER)) {
                return@newSuspendedTransaction ApiResponse.fail("Невідома роль", "BAD_ROLE")
            }
            if (targetId == group.ownerId) {
                return@newSuspendedTransaction ApiResponse.fail("Власника змінити не можна", "IS_OWNER")
            }
            val membership = GroupMemberEntity.find {
                (GroupMemberTable.groupId eq groupId) and (GroupMemberTable.userId eq targetId)
            }.firstOrNull() ?: return@newSuspendedTransaction ApiResponse.fail(
                "Користувач не учасник групи", "NOT_MEMBER",
            )
            membership.role = role
            ApiResponse.ok(true)
        }

    /** Запити на вступ у приватну групу — лише owner/mod. */
    suspend fun joinRequests(groupId: Int, requesterId: Int): ApiResponse<GroupRequestsResponse> =
        newSuspendedTransaction {
            val role = roleOfTx(groupId, requesterId)
            if (role != ROLE_OWNER && role != ROLE_MOD) {
                return@newSuspendedTransaction ApiResponse.fail(
                    "Запити бачать власник і модератори", "NOT_ALLOWED",
                )
            }
            val rows = GroupJoinRequestEntity.find { GroupJoinRequestTable.groupId eq groupId }
                .orderBy(GroupJoinRequestTable.createdAt to SortOrder.ASC)
                .toList()
            ApiResponse.ok(
                GroupRequestsResponse(rows.map { req ->
                    GroupJoinRequestDto(
                        requestId = req.id.value,
                        groupId = groupId,
                        user = profileOf(req.userId),
                        createdAt = req.createdAt,
                    )
                })
            )
        }

    /** Схвалити запит: користувач стає member. */
    suspend fun approveRequest(groupId: Int, requestId: Int, requesterId: Int): ApiResponse<Boolean> =
        newSuspendedTransaction {
            val role = roleOfTx(groupId, requesterId)
            if (role != ROLE_OWNER && role != ROLE_MOD) {
                return@newSuspendedTransaction ApiResponse.fail(
                    "Схвалюють власник і модератори", "NOT_ALLOWED",
                )
            }
            val request = GroupJoinRequestEntity.findById(requestId)
                ?: return@newSuspendedTransaction ApiResponse.fail("Запит не знайдено", "NO_REQUEST")
            if (request.groupId != groupId) {
                return@newSuspendedTransaction ApiResponse.fail("Запит іншої групи", "WRONG_GROUP")
            }
            if (GroupMemberEntity.find {
                    (GroupMemberTable.groupId eq groupId) and
                        (GroupMemberTable.userId eq request.userId)
                }.firstOrNull() == null) {
                GroupMemberEntity.new {
                    this.groupId = groupId
                    this.userId = request.userId
                    this.role = ROLE_MEMBER
                    this.joinedAt = System.currentTimeMillis()
                }
                XpLedger.award(request.userId, XpLedger.GROUP_JOIN)
            }
            request.delete()
            ApiResponse.ok(true)
        }

    /** Відхилити запит на вступ. */
    suspend fun rejectRequest(groupId: Int, requestId: Int, requesterId: Int): ApiResponse<Boolean> =
        newSuspendedTransaction {
            val role = roleOfTx(groupId, requesterId)
            if (role != ROLE_OWNER && role != ROLE_MOD) {
                return@newSuspendedTransaction ApiResponse.fail(
                    "Відхиляють власник і модератори", "NOT_ALLOWED",
                )
            }
            GroupJoinRequestEntity.findById(requestId)?.delete()
            ApiResponse.ok(true)
        }

    /** Мої запити на вступ (по всіх групах) — для вкладки клієнта. */
    suspend fun myRequests(viewerId: Int): ApiResponse<GroupRequestsResponse> = newSuspendedTransaction {
        val mine = GroupJoinRequestEntity.find { GroupJoinRequestTable.userId eq viewerId }
            .orderBy(GroupJoinRequestTable.createdAt to SortOrder.ASC)
            .toList()
        ApiResponse.ok(
            GroupRequestsResponse(mine.map { req ->
                GroupJoinRequestDto(
                    requestId = req.id.value,
                    groupId = req.groupId,
                    user = profileOf(req.userId),
                    createdAt = req.createdAt,
                )
            })
        )
    }

    // --- 1.6.5: нові можливості спільнот ------------------------------------

    /**
     * 1.6.5: передати роль власника іншому учаснику. Тільки власник може;
     * ціль — модератор або учасник (не сам власник). Попередньо назначаємо
     * новому власнику модератора (щоб не залишити групу без адмінів).
     */
    suspend fun transferOwnership(groupId: Int, ownerId: Int, targetId: Int): ApiResponse<Boolean> =
        newSuspendedTransaction {
            val group = GroupEntity.findById(groupId)
                ?: return@newSuspendedTransaction ApiResponse.fail("Групу не знайдено", "NO_GROUP")
            if (group.ownerId != ownerId) {
                return@newSuspendedTransaction ApiResponse.fail("Передати роль може тільки власник", "NOT_OWNER")
            }
            if (targetId == ownerId) {
                return@newSuspendedTransaction ApiResponse.fail("Сам собі передати роль не можна", "SELF_TRANSFER")
            }
            val target = GroupMemberEntity.find {
                (GroupMemberTable.groupId eq groupId) and (GroupMemberTable.userId eq targetId)
            }.firstOrNull() ?: return@newSuspendedTransaction ApiResponse.fail(
                "Користувач не учасник цієї групи", "NOT_MEMBER",
            )
            if (target.role == ROLE_OWNER) {
                return@newSuspendedTransaction ApiResponse.fail("Вже власник", "ALREADY_OWNER")
            }
            // Клеймо попереднього власника модератором, а нового — власником.
            val oldOwnerMember = GroupMemberEntity.find {
                (GroupMemberTable.groupId eq groupId) and (GroupMemberTable.userId eq group.ownerId)
            }.firstOrNull()
            oldOwnerMember?.role = ROLE_MOD
            group.ownerId = targetId
            target.role = ROLE_OWNER
            ApiResponse.ok(true)
        }

    /**
     * 1.6.5: закріпити/розкріпити пост у спільноті. owner/mod.
     * При закріпленні старі закріплення знімаємо (один закріплено — як у ВК).
     */
    suspend fun pinPost(groupId: Int, requesterId: Int, postId: Int, pin: Boolean): ApiResponse<Boolean> =
        newSuspendedTransaction {
            val role = roleOfTx(groupId, requesterId)
            if (role != ROLE_OWNER && role != ROLE_MOD) {
                return@newSuspendedTransaction ApiResponse.fail("Закріплює адмін", "NOT_ADMIN")
            }
            val post = PostEntity.findById(postId)
                ?: return@newSuspendedTransaction ApiResponse.fail("Пост не знайдено", "NO_POST")
            if (post.groupId != groupId) {
                return@newSuspendedTransaction ApiResponse.fail("Пост іншої групи", "WRONG_GROUP")
            }
            if (pin) {
                PostEntity.find { (PostTable.groupId eq groupId) and (PostTable.pinned eq true) }.forEach { it.pinned = false }
            }
            post.pinned = pin
            ApiResponse.ok(true)
        }

    /** 1.6.5: створити посилання-запрошення в групу (owner/mod). maxUses=0 = безстроково/безлімітно. */
    suspend fun createInvite(
        groupId: Int,
        creatorId: Int,
        maxUses: Int,
        expiresAt: Long,
    ): ApiResponse<String> = newSuspendedTransaction {
        val role = roleOfTx(groupId, creatorId)
        if (role != ROLE_OWNER && role != ROLE_MOD) {
            return@newSuspendedTransaction ApiResponse.fail("Посилання створює адмін", "NOT_ADMIN")
        }
        val code = generateCode()
        GroupInviteCodeEntity.new {
            this.groupId = groupId
            this.code = code
            this.creatorId = creatorId
            this.usedCount = 0
            this.maxUses = maxUses
            this.expiresAt = expiresAt
            this.createdAt = System.currentTimeMillis()
        }
        // 1.6.3: коротка назва спільноти для QR/посилання — owner+1 символ для унікальності.
        ApiResponse.ok(code)
    }

    /** 1.6.5: вступ за посиланням-запрошенням (один раз для one-shot). */
    suspend fun joinByInvite(groupId: Int, userId: Int, code: String): ApiResponse<Boolean> =
        newSuspendedTransaction {
            if (roleOfTx(groupId, userId) != null) {
                return@newSuspendedTransaction ApiResponse.fail("Ти вже учасник цієї групи", "ALREADY_MEMBER")
            }
            val invite = GroupInviteCodeEntity.find {
                (GroupInviteCodeTable.groupId eq groupId) and (GroupInviteCodeTable.code eq code)
            }.firstOrNull() ?: return@newSuspendedTransaction ApiResponse.fail("Невірний код", "BAD_CODE")
            val now = System.currentTimeMillis()
            if (invite.expiresAt != 0L && invite.expiresAt < now) {
                return@newSuspendedTransaction ApiResponse.fail("Посилання застаріло", "EXPIRED")
            }
            if (invite.maxUses > 0 && invite.usedCount >= invite.maxUses) {
                return@newSuspendedTransaction ApiResponse.fail("Всі сканування використані", "USED_UP")
            }
            GroupMemberEntity.new {
                this.groupId = groupId
                this.userId = userId
                this.role = ROLE_MEMBER
                this.joinedAt = now
            }
            invite.usedCount = invite.usedCount + 1
            XpLedger.award(userId, XpLedger.GROUP_JOIN)
            ApiResponse.ok(true)
        }

    private fun generateCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = java.util.Random()
        return (1..8).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    // --- внутрішні дрібниці --------------------------------------------------

    /** Роль користувача в групі або null — не учасник. Публічно для інших сервісів. */
    fun roleOf(groupId: Int, userId: Int): String? = transaction {
        GroupMemberEntity.find {
            (GroupMemberTable.groupId eq groupId) and (GroupMemberTable.userId eq userId)
        }.firstOrNull()?.role
    }

    /** Чи є користувач учасником групи (для постингу і чатів групи). */
    fun isMember(groupId: Int, userId: Int): Boolean = roleOf(groupId, userId) != null

    /** Ідентифікатори всіх учасників групи (власник теж). */
    fun memberIds(groupId: Int): Set<Int> = transaction {
        GroupMemberEntity.find { GroupMemberTable.groupId eq groupId }.map { it.userId }.toSet()
    }

    private fun profileOf(userId: Int): ProfileDto =
        profiles.profileOf(profiles.ensureProfile(userId))

    private fun memberCountTx(groupId: Int): Int =
        GroupMemberEntity.find { GroupMemberTable.groupId eq groupId }.count().toInt()

    private fun roleOfTx(groupId: Int, userId: Int): String? = GroupMemberEntity.find {
        (GroupMemberTable.groupId eq groupId) and (GroupMemberTable.userId eq userId)
    }.firstOrNull()?.role

    private fun pendingRequestTx(groupId: Int, userId: Int): Boolean =
        GroupJoinRequestEntity.find {
            (GroupJoinRequestTable.groupId eq groupId) and (GroupJoinRequestTable.userId eq userId)
        }.firstOrNull() != null

    private fun GroupEntity.toDto(viewerId: Int): GroupDto {
        val membership = GroupMemberEntity.find {
            (GroupMemberTable.groupId eq this@toDto.id.value) and (GroupMemberTable.userId eq viewerId)
        }.firstOrNull()
        return GroupDto(
            id = id.value,
            owner = profileOf(ownerId),
            name = name,
            description = description,
            avatarPath = avatarFile?.let { "/media/photo/$it" },
            // 1.6.4: обкладинка спільноти.
            coverPath = coverFile?.let { "/media/photo/$it" },
            isPrivate = isPrivate,
            memberCount = memberCountTx(id.value),
            isMember = membership != null,
            myRole = membership?.role,
            createdAt = createdAt,
        )
    }

    companion object {
        const val ROLE_OWNER = "owner"
        const val ROLE_MOD = "mod"
        const val ROLE_MEMBER = "member"
    }
}
