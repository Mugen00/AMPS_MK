package dev.amps.app.ui.screens.framesearch

import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.amps.app.core.GeminiChatSession
import dev.amps.app.ui.theme.AmpsColors
import io.noties.markwon.Markwon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 1.3.0: «Пошук по картинці» повністю став AI-чатом: вибираєш фото —
 * ведеш переписку з Gemini про нього (питання, деталі, продовження),
 * кожен аналіз автоматично зберігається в історію на пристрої.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrameSearchScreen(
    viewModel: FrameSearchViewModel,
    onOpenHistory: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showHistory by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(viewModel::onImagePicked) }

    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI-аналіз фото") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    if (state.messages.isNotEmpty()) {
                        TextButton(onClick = viewModel::newChat) { Text("Нове питання") }
                    }
                    IconButton(onClick = { showHistory = true }) {
                        Icon(Icons.Default.History, contentDescription = "Історія аналізів")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            val image = state.image
            if (image == null) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    DropZone(onPick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                }
            } else {
                ChatBody(
                    state = state,
                    onSend = viewModel::send,
                    onInput = viewModel::setInput,
                    onReplace = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onClear = viewModel::clear,
                )
            }
        }
    }

    if (showHistory) {
        HistoryDialog(
            chats = chats,
            onOpen = { session ->
                showHistory = false
                viewModel.openSession(session)
            },
            onDelete = viewModel::deleteSession,
            onClose = { showHistory = false },
        )
    }
}

@Composable
private fun ChatBody(
    state: FrameSearchState,
    onSend: () -> Unit,
    onInput: (String) -> Unit,
    onReplace: () -> Unit,
    onClear: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        FramePreviewCard(
            previewDataUrl = state.image?.previewDataUrl.orEmpty(),
            dimensions = state.image?.let { img ->
                if (img.width > 0) "${img.width}×${img.height}" else ""
            }.orEmpty(),
            enabled = !state.busy,
            onReplace = onReplace,
            onClear = onClear,
        )
        Spacer(Modifier.height(8.dp))

        if (state.messages.isEmpty() && !state.busy) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Постав питання про фото",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Gemini розпізнає персонажа, опише сцену і відповість на додаткові питання у переписці.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.messages) { msg ->
                    ChatBubble(msg)
                }
                if (state.busy) {
                    item { ThinkingBubble() }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }

        state.error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
        ) {
            OutlinedTextField(
                value = state.input,
                onValueChange = onInput,
                placeholder = { Text("Питання про фото…") },
                modifier = Modifier.weight(1f),
                maxLines = 3,
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { if (!state.busy) onSend() },
                enabled = !state.busy && state.image != null,
            ) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Надіслати",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** Markdown → TextView через Markwon; посилання клікабельні. */
@Composable
private fun MarkdownText(markdown: String) {
    val context = LocalContext.current
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val markwon = remember { Markwon.create(context) }
    AndroidView(
        factory = { ctx ->
            TextView(ctx).apply {
                movementMethod = LinkMovementMethod.getInstance()
                setTextColor(textColor)
                textSize = 15f
            }
        },
        update = { view -> markwon.setMarkdown(view, markdown) },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Поки модель пише: м'яка пульсуюча бульбашка (1.3.0 — анімації). */
@Composable
private fun ThinkingBubble() {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "thinking")
    val a by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(700),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Gemini пише…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = a),
                )
            }
        }
    }
}

@Composable
private fun ChatBubble(msg: ChatMsg) {
    val isUser = msg.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp,
            ),
            color = if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
            else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .padding(vertical = 4.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    if (isUser) "Ви" else "Gemini",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                if (isUser) {
                    Text(msg.text, style = MaterialTheme.typography.bodyMedium)
                } else {
                    MarkdownText(msg.text)
                }
            }
        }
    }
}

/** Історія аналізів: список збережених чатів з відкриттям і видаленням. */
@Composable
private fun HistoryDialog(
    chats: List<GeminiChatSession>,
    onOpen: (GeminiChatSession) -> Unit,
    onDelete: (GeminiChatSession) -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {},
        title = { Text("Історія аналізів") },
        dismissButton = {
            TextButton(onClick = onClose) { Text("Закрити") }
        },
        text = {
            if (chats.isEmpty()) {
                Text("Поки що немає збережених аналізів")
            } else {
                LazyColumn(Modifier.height(340.dp)) {
                    items(chats) { session ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    session.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                )
                                Text(
                                    SimpleDateFormat("d MMM yyyy, HH:mm", Locale("uk")).format(Date(session.createdAt)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onOpen(session) }) { Text("Відкрити") }
                            IconButton(onClick = { onDelete(session) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Видалити",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun DropZone(onPick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 44.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(86.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                AmpsColors.violet.copy(alpha = 0.35f),
                                AmpsColors.cyan.copy(alpha = 0.25f),
                            )
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.PhotoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(38.dp),
                )
            }
            Text("Виберіть картинку", style = MaterialTheme.typography.titleMedium)
            Text(
                "AI розпізнає персонажа чи об'єкт, опише контекст і відповість на питання. " +
                    "Фото надсилається лише до Gemini і зберігається в історії лише на вашому пристрої.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            FilledTonalButton(onClick = onPick) {
                Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Вибрати зображення")
            }
        }
    }
}

@Composable
private fun FramePreviewCard(
    previewDataUrl: String,
    dimensions: String,
    enabled: Boolean,
    onReplace: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(14.dp))
            ) {
                AsyncImage(
                    model = previewDataUrl,
                    contentDescription = "Вибране зображення",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = dimensions,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onReplace, enabled = enabled) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Змінити")
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onClear, enabled = enabled) {
                    Icon(Icons.Default.Close, contentDescription = "Очистити", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
