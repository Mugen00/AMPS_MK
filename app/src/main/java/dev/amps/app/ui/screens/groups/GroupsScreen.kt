package dev.amps.app.ui.screens.groups

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.amps.app.core.t
import dev.amps.app.data.remote.backend.dto.ChatMessageDto
import dev.amps.app.data.remote.backend.dto.FeedItemDto
import dev.amps.app.data.remote.backend.dto.GroupDto
import dev.amps.app.data.remote.backend.dto.GroupJoinRequestDto
import dev.amps.app.data.remote.backend.dto.GroupMemberDto

/**
 * 1.6.0: екран Групи — каталог + мої групи, створення, деталі з постами,
 * учасниками (ролі, виключення, модератори), запитами на вступ у
 * приватні групи і груповим чатом.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsScreen(
    viewModel: GroupsViewModel,
    baseUrl: String,
    onOpenAuth: () -> Unit,
    onOpenUserProfile: (Int) -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    LaunchedEffect(Unit) { viewModel.load() }

    var showCreate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val selected = state.selected

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(if (selected == null) t("Групи 👥") else selected.group.name)
                },
                navigationIcon = {
                    if (selected != null) {
                        IconButton(onClick = viewModel::close) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("Назад"))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (selected == null && state.authorized) {
                androidx.compose.material3.FloatingActionButton(
                    onClick = { showCreate = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                ) {
                    Icon(Icons.Default.Add, contentDescription = t("Створити групу"), tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        },
    ) { padding ->
        when {
            !state.authorized -> GuestHint(onOpenAuth)
            selected != null -> GroupDetailView(
                state = state,
                baseUrl = baseUrl,
                onBack = viewModel::close,
                onJoin = viewModel::join,
                onLeave = viewModel::leave,
                onDelete = { confirmDelete = true },
                onTab = viewModel::setDetailTab,
                onOpenUser = onOpenUserProfile,
                onKick = viewModel::kick,
                onRole = viewModel::setRole,
                onApprove = viewModel::approveRequest,
                onReject = viewModel::rejectRequest,
                onDraft = viewModel::setComposingText,
                onAttach = { bytes -> viewModel.attachPhoto(bytes) },
                onPublish = viewModel::publishInGroup,
                onChatDraft = viewModel::setChatDraft,
                onSendChat = viewModel::sendChat,
                onSendChatPhoto = viewModel::sendChatPhoto,
            )
            else -> GroupsListView(
                state = state,
                baseUrl = baseUrl,
                onSearch = viewModel::setSearch,
                onSubmitSearch = viewModel::submitSearch,
                onTab = viewModel::setTab,
                onRefresh = viewModel::refresh,
                onOpen = viewModel::open,
            )
        }
    }

    // Діалог створення групи.
    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text(t("Нова група")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = state.createName,
                        onValueChange = viewModel::setCreateName,
                        label = { Text(t("Назва групи")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.createDescription,
                        onValueChange = viewModel::setCreateDescription,
                        label = { Text(t("Опис (необов'язково)")) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = state.createPrivate, onCheckedChange = viewModel::setCreatePrivate)
                        Text(t("Приватна — вступ за запитом 🔒"), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.create { showCreate = false }
                    },
                    enabled = state.createName.isNotBlank() && !state.creating,
                ) {
                    if (state.creating) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(t("Створити (+30 XP)"))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text(t("Скасувати")) }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(t("Видалити групу?")) },
            text = { Text(t("Групу, її пости і чат буде видалено назавжди.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteGroup()
                }) { Text(t("Видалити")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(t("Скасувати")) }
            },
        )
    }
}

/** Гість: групи потребують акаунта. */
@Composable
private fun GuestHint(onOpenAuth: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("👥", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(12.dp))
        Text(t("Групи — це спільноти всередині AMPS: пости, чат і учасники."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpenAuth) { Text(t("Увійти в акаунт")) }
    }
}

// ===== Список груп =====

@Composable
private fun GroupsListView(
    state: GroupsUiState,
    baseUrl: String,
    onSearch: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onTab: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpen: (Int) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { onTab("catalog") },
                label = { Text(t("Каталог")) },
                leadingIcon = if (state.tab == "catalog") {
                    { Text("✓", style = MaterialTheme.typography.labelSmall) }
                } else null,
            )
            AssistChip(
                onClick = { onTab("my") },
                label = { Text(t("Мої групи")) },
                leadingIcon = if (state.tab == "my") {
                    { Text("✓", style = MaterialTheme.typography.labelSmall) }
                } else null,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (state.tab == "catalog") {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onSearch,
                label = { Text(t("Пошук груп за назвою")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = onSubmitSearch) {
                        Text("🔍")
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
        }
        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }
        val list = if (state.tab == "my") state.myGroups else state.groups
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (state.tab == "my") t("Ви поки не в жодній групі — знайдіть у каталозі або створіть свою ✨")
                    else t("Груп ще немає — створіть першу! ✨"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(list, key = { it.id }) { group ->
                GroupCard(group, baseUrl, onClick = { onOpen(group.id) })
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun GroupCard(group: GroupDto, baseUrl: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GroupAvatar(group.avatarPath, group.name, size = 46.dp, baseUrl = baseUrl)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        group.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (group.isPrivate) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = t("Приватна група"),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (group.description.isNotBlank()) {
                    Text(
                        group.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    t("👤 учасників") + ": ${group.memberCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun GroupAvatar(
    avatarPath: String?,
    name: String,
    size: androidx.compose.ui.unit.Dp,
    baseUrl: String,
) {
    val url = if (avatarPath != null) absoluteMediaUrl(baseUrl, avatarPath) else null
    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ===== Деталі групи =====

@Composable
private fun GroupDetailView(
    state: GroupsUiState,
    baseUrl: String,
    onBack: () -> Unit,
    onJoin: () -> Unit,
    onLeave: () -> Unit,
    onDelete: () -> Unit,
    onTab: (String) -> Unit,
    onOpenUser: (Int) -> Unit,
    onKick: (Int) -> Unit,
    onRole: (Int, String) -> Unit,
    onApprove: (Int) -> Unit,
    onReject: (Int) -> Unit,
    onDraft: (String) -> Unit,
    onAttach: (ByteArray?) -> Unit,
    onPublish: () -> Unit,
    onChatDraft: (String) -> Unit,
    onSendChat: () -> Unit,
    onSendChatPhoto: (ByteArray) -> Unit,
) {
    val group = state.selected?.group ?: return
    val context = LocalContext.current
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onAttach(readBytesCapped(context, uri, 10L * 1024 * 1024))
    }
    val chatPhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readBytesCapped(context, uri, 10L * 1024 * 1024)?.let(onSendChatPhoto)
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        // Шапка групи.
        Row(verticalAlignment = Alignment.CenterVertically) {
            GroupAvatar(group.avatarPath, group.name, size = 54.dp, baseUrl = baseUrl)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(group.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    t("👤 учасників") + ": ${group.memberCount}" +
                        if (group.isPrivate) " · " + t("приватна 🔒") else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (group.description.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(group.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                group.isMember && !state.isOwner -> OutlinedButton(onClick = onLeave) { Text(t("Вийти")) }
                !group.isMember && state.selected?.myRequestPending == true -> {
                    AssistChip(onClick = {}, label = { Text(t("⏳ Запит висилано")) })
                }
                !group.isMember -> Button(onClick = onJoin) {
                    Text(if (group.isPrivate) t("Запросити вступ") else t("Вступити (+5 XP)"))
                }
            }
            if (state.isOwner) {
                OutlinedButton(onClick = onDelete) { Text(t("Видалити групу"), color = MaterialTheme.colorScheme.error) }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Вкладки деталей — лише учасникам.
        if (group.isMember) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { onTab("posts") }, label = { Text(t("Пости")) })
                AssistChip(onClick = { onTab("members") }, label = { Text(t("Учасники")) })
                if (state.isModOrOwner) {
                    AssistChip(onClick = { onTab("requests") }, label = { Text(t("Запити")) })
                }
                AssistChip(onClick = { onTab("chat") }, label = { Text(t("Чат")) })
            }
            Spacer(Modifier.height(8.dp))

            when (state.detailTab) {
                "members" -> MembersTab(state, onOpenUser, onKick, onRole)
                "requests" -> RequestsTab(state, onApprove, onReject)
                "chat" -> GroupChatTab(state, baseUrl, onChatDraft, onSendChat, { chatPhotoPicker.launch(arrayOf("image/*")) })
                else -> GroupPostsTab(
                    state, baseUrl,
                    photoPicker = { photoPicker.launch(arrayOf("image/*")) },
                    onDraft, onAttach, onPublish,
                    onOpenUser = onOpenUser,
                )
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    t("Пости, чат і учасники — лише для учасників групи 🔒"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ===== Вкладка постів групи =====

@Composable
private fun GroupPostsTab(
    state: GroupsUiState,
    baseUrl: String,
    photoPicker: () -> Unit,
    onDraft: (String) -> Unit,
    onAttach: (ByteArray?) -> Unit,
    onPublish: () -> Unit,
    onOpenUser: (Int) -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Композер поста групи.
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(12.dp)) {
                    OutlinedTextField(
                        value = state.composingText,
                        onValueChange = onDraft,
                        label = { Text(t("Написати пост у групу…")) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = photoPicker) {
                            Icon(Icons.Default.AttachFile, contentDescription = t("Прикріпити фото"))
                        }
                        if (state.attachedPhotoBytes != null) {
                            Text(t("📷 Фото прикріплено") + " ✕", modifier = Modifier.clickable { onAttach(null) })
                        }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = onPublish, enabled = state.canPublish) {
                            if (state.posting) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text(t("Опублікувати (+20 XP)"))
                            }
                        }
                    }
                }
            }
        }
        if (state.posts.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 30.dp), contentAlignment = Alignment.Center) {
                    Text(t("Постів у групі ще немає — напишіть перший ✨"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(state.posts, key = { "${it.kind}-${it.post.id}" }) { item ->
            GroupPostCard(item, baseUrl, onOpenUser)
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

/** Картка поста групи: автор, дата, текст, фото. */
@Composable
private fun GroupPostCard(item: FeedItemDto, baseUrl: String, onOpenUser: (Int) -> Unit) {
    val post = item.post
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onOpenUser(post.author.userId) }) {
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(post.author.displayName.take(1).uppercase(), style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(post.author.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(post.createdAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (post.text.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(post.text, style = MaterialTheme.typography.bodyMedium)
            }
            val photo = post.media.firstOrNull { it.kind == "photo" }
            val video = post.media.firstOrNull { it.kind == "video" }
            if (photo != null) {
                Spacer(Modifier.height(8.dp))
                AsyncImage(
                    model = absoluteMediaUrl(baseUrl, photo.url),
                    contentDescription = t("Фото поста"),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)),
                )
            }
            if (video != null) {
                Spacer(Modifier.height(8.dp))
                dev.amps.app.ui.components.InlineVideo(
                    url = absoluteMediaUrl(baseUrl, video.url),
                    modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(12.dp)),
                )
            }
        }
    }
}

// ===== Вкладка учасників =====

@Composable
private fun MembersTab(
    state: GroupsUiState,
    onOpenUser: (Int) -> Unit,
    onKick: (Int) -> Unit,
    onRole: (Int, String) -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        items(state.members, key = { it.userId }) { member ->
            MemberRow(member, state, onOpenUser, onKick, onRole)
        }
        item { Spacer(Modifier.height(60.dp)) }
    }
}

@Composable
private fun MemberRow(
    member: GroupMemberDto,
    state: GroupsUiState,
    onOpenUser: (Int) -> Unit,
    onKick: (Int) -> Unit,
    onRole: (Int, String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val roleLabel = when (member.role) {
        "owner" -> "👑 " + t("Власник")
        "mod" -> "🛡 " + t("Модератор")
        else -> t("Учасник")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenUser(member.userId) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(member.displayName.take(1).uppercase(), style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(member.displayName, style = MaterialTheme.typography.bodyMedium)
            Text(roleLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        // Керування: власник керує всіма крім себе; модератор — звичайними учасниками.
        val canManage = state.isModOrOwner && member.role != "owner" && member.userId != state.selected?.group?.owner?.userId
        if (canManage) {
            Box {
                TextButton(onClick = { menu = true }) { Text("⋮") }
                androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (state.isOwner) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(if (member.role == "mod") t("Зняти модератора") else t("Зробити модератором 🛡")) },
                            onClick = {
                                menu = false
                                onRole(member.userId, if (member.role == "mod") "member" else "mod")
                            },
                        )
                    }
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(t("Виключити з групи"), color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menu = false
                            onKick(member.userId)
                        },
                    )
                }
            }
        }
    }
}

// ===== Вкладка запитів =====

@Composable
private fun RequestsTab(
    state: GroupsUiState,
    onApprove: (Int) -> Unit,
    onReject: (Int) -> Unit,
) {
    if (state.requests.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(vertical = 30.dp), contentAlignment = Alignment.Center) {
            Text(t("Нових запитів немає"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        items(state.requests, key = { it.requestId }) { request ->
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(request.user.displayName, style = MaterialTheme.typography.bodyMedium)
                        Text("@${request.user.login}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { onApprove(request.requestId) }) { Text("✓ " + t("Схвалити")) }
                    TextButton(onClick = { onReject(request.requestId) }) { Text("✕ " + t("Відхилити")) }
                }
            }
        }
        item { Spacer(Modifier.height(60.dp)) }
    }
}

// ===== Груповий чат =====

@Composable
private fun GroupChatTab(
    state: GroupsUiState,
    baseUrl: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onPickPhoto: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            if (state.chat.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 30.dp), contentAlignment = Alignment.Center) {
                        Text(t("Напишіть перше повідомлення у чат групи 💬"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(state.chat, key = { it.id }) { message ->
                ChatBubble(message, baseUrl, state.authHeader)
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        ) {
            IconButton(onClick = onPickPhoto) {
                Icon(Icons.Default.AttachFile, contentDescription = t("Надіслати фото"))
            }
            OutlinedTextField(
                value = state.chatDraft,
                onValueChange = onDraft,
                label = { Text(t("Повідомлення…")) },
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            IconButton(onClick = onSend, enabled = state.chatDraft.isNotBlank() && !state.sendingChat) {
                Icon(Icons.Default.Send, contentDescription = t("Надіслати"), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Бульбашка повідомлення: мої — праворуч, чужі — ліворуч.
 *  1.6.2: приватні вкладення (private-*) — із Authorization-заголовком. */
@Composable
private fun ChatBubble(message: ChatMessageDto, baseUrl: String, authHeader: String? = null) {
    val align = if (message.mine) Alignment.CenterEnd else Alignment.CenterStart
    Box(Modifier.fillMaxWidth(), contentAlignment = align) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomStart = if (message.mine) 16.dp else 4.dp,
                        bottomEnd = if (message.mine) 4.dp else 16.dp,
                    ),
                )
                .background(
                    if (message.mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                    else MaterialTheme.colorScheme.surfaceVariant,
                )
                .padding(10.dp),
        ) {
            if (!message.mine) {
                Text(
                    message.from.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (message.mediaUrl != null) {
                val url = absoluteMediaUrl(baseUrl, message.mediaUrl)
                if (message.mediaUrl.startsWith("/media/private/") && authHeader != null) {
                    AsyncImage(
                        model = coil.request.ImageRequest.Builder(LocalContext.current)
                            .data(url)
                            .addHeader("Authorization", authHeader)
                            .build(),
                        contentDescription = t("Фото повідомлення"),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(10.dp)),
                    )
                } else {
                    AsyncImage(
                        model = url,
                        contentDescription = t("Фото повідомлення"),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(10.dp)),
                    )
                }
            }
            // 1.6.2: чіпси реакцій.
            if (message.reactions.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    message.reactions.forEach { reaction ->
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (reaction.mine) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                            } else {
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                            },
                        ) {
                            Text(
                                "${reaction.emoji} ${reaction.count}",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
            Text(
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(message.createdAt)),
                style = MaterialTheme.typography.labelSmall,
                color = if (message.mine) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --- допоміжне --------------------------------------------------------------

/** Абсолютна адреса медіа: сервер віддає відносний шлях /media/… */
internal fun absoluteMediaUrl(baseUrl: String, path: String): String =
    if (path.startsWith("http")) path else baseUrl.trimEnd('/') + path

private fun readBytesCapped(context: android.content.Context, uri: Uri, maxBytes: Long): ByteArray? =
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = stream.readBytes()
            if (bytes.size > maxBytes) null else bytes
        }
    }.getOrNull()
