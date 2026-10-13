package dev.amps.app.ui.screens.chats

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Badge
import androidx.compose.material3.AlertDialog
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
) {
    val context = LocalContext.current
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val bytes = readBytesCapped(context, uri, 10L * 1024 * 1024)
            if (bytes != null) onSendPhoto(bytes)
        }
    }
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
                ChatBubble(message, baseUrl)
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
            IconButton(onClick = onSend, enabled = state.draft.isNotBlank() && !state.sending) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = t("Надіслати"),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Бульбашка повідомлення: мої — праворуч (акцент), чужі — ліворуч.
 * Фото теж у бульбашці, як у месенджерах.
 */
@Composable
private fun ChatBubble(message: ChatMessageDto, baseUrl: String) {
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
                AsyncImage(
                    model = dev.amps.app.ui.screens.groups.absoluteMediaUrl(baseUrl, message.mediaUrl),
                    contentDescription = t("Фото повідомлення"),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            }
            if (message.text.isNotBlank()) {
                Text(
                    message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message.mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
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
}

private fun readBytesCapped(context: android.content.Context, uri: Uri, maxBytes: Long): ByteArray? =
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = stream.readBytes()
            if (bytes.size > maxBytes) null else bytes
        }
    }.getOrNull()
