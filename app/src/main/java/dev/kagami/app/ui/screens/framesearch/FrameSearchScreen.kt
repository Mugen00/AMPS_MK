package dev.kagami.app.ui.screens.framesearch

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.kagami.app.ui.components.EmptyState
import dev.kagami.app.ui.components.StageProgress
import dev.kagami.app.ui.theme.KagamiColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrameSearchScreen(
    viewModel: FrameSearchViewModel,
    onOpenWiki: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigation by viewModel.navigation.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(viewModel::onImagePicked) }

    LaunchedEffect(navigation) {
        if (navigation) {
            viewModel.consumeNavigation()
            onOpenWiki()
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Поиск по кадру") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Default.History, contentDescription = "История")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            if (state.page != null) {
                FoundBanner(
                    title = state.page?.media?.title?.best.orEmpty(),
                    subtitle = state.page?.frame?.let { hit ->
                        buildString {
                            hit.episode?.let { append("серия $it · ") }
                            hit.timestampLabel?.let { append("$it · ") }
                            append("${hit.similarityPercent ?: 0}% совпадения")
                        }
                    }.orEmpty(),
                    onOpen = onOpenWiki,
                )
            }

            if (state.hasImage) {
                FramePreviewCard(
                    previewDataUrl = state.image?.previewDataUrl.orEmpty(),
                    dimensions = state.image?.let { "${it.width}×${it.height}" }.orEmpty(),
                    enabled = !state.busy,
                    onReplace = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onClear = viewModel::clear,
                )
            } else {
                DropZone(
                    onPick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                )
            }

            if (state.busy) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    StageProgress(state.stage.orEmpty(), modifier = Modifier.padding(18.dp))
                }
            } else {
                Button(
                    onClick = viewModel::search,
                    enabled = state.hasImage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Найти источник кадра", style = MaterialTheme.typography.titleMedium)
                }
            }

            state.miss?.let { miss ->
                EmptyState(
                    icon = Icons.Default.ImageSearch,
                    title = "Кадр не найден",
                    message = buildString {
                        append(miss.reason)
                        miss.bridgeError?.let { append("\n\nМост: $it") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    action = {
                        OutlinedButton(onClick = onOpenSettings) { Text("Проверить мост") }
                    },
                )
            }

            PipelineCard(bridgeUrl = state.bridgeUrl, onOpenSettings = onOpenSettings)

            Spacer(Modifier.height(20.dp))
        }
    }
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
                                KagamiColors.violet.copy(alpha = 0.35f),
                                KagamiColors.cyan.copy(alpha = 0.25f),
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
            Text("Выберите кадр из аниме", style = MaterialTheme.typography.titleMedium)
            Text(
                "Скриншот, фото экрана или обрезанный фрагмент серии. Кадр никуда не сохраняется: он уходит только на ваш компьютер через локальный мост.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            FilledTonalButton(onClick = onPick) {
                Icon(Icons.Default.ImageSearch, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Выбрать изображение")
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
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(18.dp))
            ) {
                AsyncImage(
                    model = previewDataUrl,
                    contentDescription = "Выбранный кадр",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.height(10.dp))
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
                    Text("Заменить")
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onClear, enabled = enabled) {
                    Icon(Icons.Default.Close, contentDescription = "Очистить", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun FoundBanner(title: String, subtitle: String, onOpen: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = KagamiColors.cyan.copy(alpha = 0.14f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Search, contentDescription = null, tint = KagamiColors.cyan)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onOpen) { Text("Открыть") }
        }
    }
}

@Composable
private fun PipelineCard(bridgeUrl: String, onOpenSettings: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("Как это работает", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            PipelineStep("1", "trace.moe", "находит серию, эпизод и секунду в кадре")
            PipelineStep("2", "SauceNAO", "ищет персонажа по тегам и исходную картинку")
            PipelineStep("3", "AniList", "собирает описание, жанры и галерею персонажа")
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.BugReport,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Мост: ${bridgeUrl.ifBlank { "не задан" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onOpenSettings) { Text("Настроить") }
            }
        }
    }
}

@Composable
private fun PipelineStep(number: String, name: String, description: String) {
    Row(
        modifier = Modifier.padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)) {
            Text(
                number,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(22.dp)
                    .padding(top = 3.dp),
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
