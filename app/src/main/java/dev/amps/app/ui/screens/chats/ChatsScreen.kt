package dev.amps.app.ui.screens.chats

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Badge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.amps.app.data.remote.backend.dto.ChatDialogDto
import dev.amps.app.data.remote.backend.dto.ChatMessageDto

/**
 * 1.6.0: екран Чати — список діалогів (приватні з людьми + групові) з
 * лічильником непрочитаних; приватна переписка 1-на-1: текст і фото.
 * Групові чати повністю живуть у деталях групи — список лише веде туди
 * через [onOpenGroup]. Список чатів тіпається кожні ~15 с.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    viewModel: ChatsViewModel,
    baseUrl: String,
    onOpenAuth: () -> Unit,
    onOpenGroup: (Int) -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeError()
        }
    }
    LaunchedEffect(Unit) {
        viewModel.startPolling()
        viewModel.loadContacts()
    }

    var showContacts by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (state.openPeerId == null) t("Чати 💬") else state.openTitle) },
                navigationIcon = {
                    if (state.openPeerId != null) {
                        IconButton(onClick = viewModel::close) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("Назад"))
                        }
                    }
                },
                actions = {
                    if (state.openPeerId == null && state.authorized) {
                        IconButton(onClick = {
                            viewModel.loadContacts()
                            showContacts = true
                        }) {
                            Icon(Icons.Default.AddComment, contentDescription = t("Новий діалог"))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            when {
                !state.authorized -> GuestHint(onOpenAuth)
                state.openPeerId != null -> PrivateChatView(
                    state = state,
                    baseUrl = baseUrl,
                    onDraft = viewModel::setDraft,
                    onSend = viewModel::sendMessage,
                    onSendPhoto = viewModel::sendPhoto,
                    onReact = viewModel::reactToMessage,
                    onToggleBlock = viewModel::toggleBlockPeer,
                    onDeleteMessage = viewModel::deleteMessage,
                )
                else -> DialogsList(
                    state = state,
                    onOpenPrivate = { peerId, title, avatar -> viewModel.openPrivate(peerId, title, avatar) },
                    onOpenGroup = onOpenGroup,
                    onStartNew = {
                        viewModel.loadContacts()
                        showContacts = true
                    },
                )
            }
        }
    }

    // Кому написати: контакти (підписки) + 1.6.1: пошук людей за ім'ям/логіном.
    if (showContacts) {
        var searchQuery by remember { mutableStateOf("") }
        LaunchedEffect(Unit) { viewModel.loadContacts() }
        AlertDialog(
            onDismissRequest = { showContacts = false },
            title = { Text(t("Кому написати")) },
            text = {
                Column {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            viewModel.searchUsers(it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(t("Пошук людей…")) },
                    )
                    Spacer(Modifier.height(8.dp))
                    val combined = remember(
                        state.searchResults, state.contacts, searchQuery,
                    ) {
                        val unique = LinkedHashMap<Int, dev.amps.app.data.remote.backend.dto.ProfileDto>()
                        state.searchResults.forEach { unique[it.userId] = it }
                        state.contacts.forEach { unique.putIfAbsent(it.userId, it) }
                        unique.values.toList()
                    }
                    if (combined.isEmpty() && !state.searchLoading) {
                        Text(t("Підпишіться на когось у Спільноті — і почніть діалог 💬"))
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(combined.size) { index ->
                                val contact = combined[index]
                                Text(
                                    text = contact.displayName + " (@" + contact.login + ")",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            showContacts = false
                                            viewModel.openPrivate(contact.userId, contact.displayName, contact.avatarPath)
                                        }
                                        .padding(vertical = 10.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showContacts = false }) { Text(t("Закрити")) }
            },
        )
    }
}

/** Гість: чати потребують акаунта. */
@Composable
private fun GuestHint(onOpenAuth: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("💬", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(12.dp))
        Text(
            t("Чати доступні власникам акаунта AMPS: приватні діалоги і чати груп."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpenAuth) { Text(t("Увійти в акаунт")) }
    }
}

/** Список діалогів: приватні й групові, з лічильником непрочитаних. */
@Composable
private fun DialogsList(
    state: ChatsUiState,
    onOpenPrivate: (Int, String, String?) -> Unit,
    onOpenGroup: (Int) -> Unit,
    onStartNew: () -> Unit,
) {
    if (state.loading && state.dialogs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (state.dialogs.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("💬", style = MaterialTheme.typography.displayLarge)
            Spacer(Modifier.size(12.dp))
            Text(
                t("Тут з’являться ваші діалоги й чати груп 💬"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(16.dp))
            Button(onClick = onStartNew) { Text(t("Почати діалог")) }
        }
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(state.dialogs.size) { index ->
            val dialog = state.dialogs[index]
            DialogRow(dialog, onClick = {
                if (dialog.kind == "user") {
                    onOpenPrivate(dialog.peerId, dialog.title, dialog.avatarPath)
                } else {
                    onOpenGroup(dialog.peerId)
                }
            })
        }
    }
}

@Composable
private fun DialogRow(dialog: ChatDialogDto, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (dialog.kind == "group") "👥" else dialog.title.take(1).uppercase(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                dialog.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                dialog.lastText.ifBlank { t("📷 фото") },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(dialog.lastAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (dialog.unread > 0) {
                androidx.compose.material3.Badge { Text(dialog.unread.toString()) }
            }
        }
    }
}

// ===== Приватний чат 1-на-1 =====

@Composable
private fun PrivateChatView(
    state: ChatsUiState,
    baseUrl: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onSendPhoto: (ByteArray) -> Unit,
    // 1.6.2: реакції і блокування.
    onReact: (Int, String) -> Unit = { _, _ -> },
    onToggleBlock: () -> Unit = {},
    // 1.6.3: видалення свого повідомлення.
    onDeleteMessage: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            // 1.6.3: фото стискається у webp перед відправкою.
            val bytes = readBytesCapped(context, uri, 10L * 1024 * 1024)
                ?.let(dev.amps.app.core.ImageCompress::toWebp)
            if (bytes != null) onSendPhoto(bytes)
        }
    }
    // 1.6.2: відкрита панель реакцій для конкретного повідомлення.
    var reactionFor by remember { mutableStateOf<Int?>(null) }
    // 1.6.2: підтвердження блокування співрозмовника.
    var confirmBlock by remember { mutableStateOf(false) }
    // 1.6.3: підтвердження видалення свого повідомлення.
    var deleteMessageId by remember { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (state.messages.isEmpty()) {
                item {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 30.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            t("Почніть діалог — напишіть перше повідомлення 💬"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(state.messages.size) { index ->
                val message = state.messages[index]
                ChatBubble(
                    message = message,
                    baseUrl = baseUrl,
                    authHeader = state.authHeader,
                    reactionOpen = reactionFor == message.id,
                    onToggleReactionBar = {
                        reactionFor = if (reactionFor == message.id) null else message.id
                    },
                    onReact = { emoji ->
                        onReact(message.id, emoji)
                        reactionFor = null
                    },
                    // 1.6.3: видалення свого повідомлення — з панелі реакцій.
                    canDelete = message.mine,
                    onDelete = { deleteMessageId = message.id },
                )
            }
            // 1.6.2: «друкує…» — дрібний рядок над полем введення.
            if (state.peerTyping) {
                item {
                    Text(
                        t("друкує…"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                    )
                }
            }
            item { Spacer(Modifier.size(8.dp)) }
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        ) {
            IconButton(onClick = { photoPicker.launch(arrayOf("image/*")) }) {
                Icon(Icons.Default.AttachFile, contentDescription = t("Надіслати фото"))
            }
            OutlinedTextField(
                value = state.draft,
                onValueChange = onDraft,
                label = { Text(t("Повідомлення…")) },
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            // 1.6.2: блокування співрозмовника — з підтвердженням.
            IconButton(onClick = { confirmBlock = true }) {
                Icon(
                    Icons.Default.Block,
                    contentDescription = t("Заблокувати"),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            IconButton(onClick = onSend, enabled = state.draft.isNotBlank() && !state.sending) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = t("Надіслати"),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    // 1.6.2: підтвердження блокування.
    if (confirmBlock) {
        AlertDialog(
            onDismissRequest = { confirmBlock = false },
            title = { Text(t("Заблокувати користувача?")) },
            text = { Text(t("Він не зможе писати вам у приватні чати, поки блокування не знято.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmBlock = false
                    onToggleBlock()
                }) { Text(t("Заблокувати 🚫")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmBlock = false }) { Text(t("Скасувати")) }
            },
        )
    }

    // 1.6.3: підтвердження видалення свого повідомлення.
    if (deleteMessageId != null) {
        AlertDialog(
            onDismissRequest = { deleteMessageId = null },
            title = { Text(t("Видалити повідомлення?")) },
            text = { Text(t("Повідомлення зникне у вас і у співрозмовника.")) },
            confirmButton = {
                TextButton(onClick = {
                    deleteMessageId?.let(onDeleteMessage)
                    deleteMessageId = null
                }) { Text(t("Видалити 🗑️")) }
            },
            dismissButton = {
                TextButton(onClick = { deleteMessageId = null }) { Text(t("Скасувати")) }
            },
        )
    }
}

/**
 * Бульбашка повідомлення: мої — праворуч (акцент), чужі — ліворуч.
 * Фото теж у бульбашці, як у месенджерах.
 */
/**
 * Бульбашка повідомлення: мої — праворуч (акцент), чужі — ліворуч.
 * Фото теж у бульбашці, як у месенджерах.
 *
 * 1.6.2: довгий тап — панель реакцій (❤️😂👍🔥😢😮), чіпси реакцій під
 * текстом; приватні фото завантажуються з Authorization-заголовком.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatBubble(
    message: ChatMessageDto,
    baseUrl: String,
    authHeader: String? = null,
    reactionOpen: Boolean = false,
    onToggleReactionBar: () -> Unit = {},
    onReact: (String) -> Unit = {},
    // 1.6.3: видалення свого повідомлення з панелі реакцій.
    canDelete: Boolean = false,
    onDelete: () -> Unit = {},
) {
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
                .combinedClickable(
                    onClick = onToggleReactionBar,
                    onLongClick = onToggleReactionBar,
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
                // 1.6.2: приватні вкладення (private-*) — із токеном у заголовку.
                val url = dev.amps.app.ui.screens.groups.absoluteMediaUrl(baseUrl, message.mediaUrl)
                if (message.mediaUrl.startsWith("/media/private/") && authHeader != null) {
                    AsyncImage(
                        model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                            .data(url)
                            .addHeader("Authorization", authHeader)
                            .build(),
                        contentDescription = t("Фото повідомлення"),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                } else {
                    AsyncImage(
                        model = url,
                        contentDescription = t("Фото повідомлення"),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                }
            }
            if (message.text.isNotBlank()) {
                Text(
                    message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message.mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
            }
            // 1.6.2: чіпси реакцій під текстом/фото.
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
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(message.createdAt)),
                style = MaterialTheme.typography.labelSmall,
                color = if (message.mine) {
                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
    // 1.6.2: панель емодзі для реакції — під бульбашкою.
    if (reactionOpen) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp, horizontal = 4.dp),
        ) {
            CHAT_REACTIONS.forEach { emoji ->
                AssistChip(
                    onClick = { onReact(emoji) },
                    label = { Text(emoji) },
                )
            }
            // 1.6.3: свої повідомлення можна видалити.
            if (canDelete) {
                AssistChip(
                    onClick = onDelete,
                    label = { Text("🗑️") },
                )
            }
        }
    }
}

/** 1.6.2: швидкі реакції на повідомлення. */
private val CHAT_REACTIONS = listOf("❤️", "😂", "👍", "🔥", "😢", "😮")

private fun readBytesCapped(context: android.content.Context, uri: Uri, maxBytes: Long): ByteArray? =
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = stream.readBytes()
            if (bytes.size > maxBytes) null else bytes
        }
    }.getOrNull()
