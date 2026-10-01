@file:OptIn(ExperimentalMaterial3Api::class)

package dev.kagami.app.ui.screens.music

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.kagami.app.KagamiApp
import dev.kagami.app.data.model.AudioLibraryEntry
import dev.kagami.app.data.model.DownloadProgress
import dev.kagami.app.data.model.DownloadState
import dev.kagami.app.data.model.FreeTrack
import dev.kagami.app.data.model.FreeTrackFilter
import dev.kagami.app.data.model.MusicSearchResult
import dev.kagami.app.data.model.MusicSourceError
import dev.kagami.app.data.repo.MusicRepository
import dev.kagami.app.ui.components.EmptyState
import dev.kagami.app.ui.components.InfoChip
import dev.kagami.app.ui.components.NetworkImage
import dev.kagami.app.ui.theme.KagamiColors
import dev.kagami.app.util.formatBytes
import dev.kagami.app.util.formatDurationSec

/**
 * The "Музыка" tab.
 *
 * [onOpenTrack] is called after a row has been handed to the repository, so the
 * host only has to navigate to its own track route — the id never travels
 * through the nav graph. See `MusicRepository.currentTargetId`.
 */
@Composable
fun MusicSearchScreen(
    viewModel: MusicSearchViewModel,
    onOpenTrack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importAudio)
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.dismissMessage()
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Музыка") },
                actions = {
                    IconButton(onClick = { picker.launch(arrayOf("audio/*")) }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Импортировать свой файл")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                label = { Text("Название трека") },
                placeholder = { Text("например: One More Time") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.hasQuery) {
                        IconButton(onClick = viewModel::clearQuery) {
                            Icon(Icons.Default.Close, contentDescription = "Очистить")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.submit() }),
            )

            TabRow(selectedTabIndex = state.tab) {
                Tab(
                    selected = state.tab == MusicSearchViewModel.TAB_METADATA,
                    onClick = { viewModel.onTabChange(MusicSearchViewModel.TAB_METADATA) },
                    text = { Text("Поиск") },
                )
                Tab(
                    selected = state.tab == MusicSearchViewModel.TAB_FREE,
                    onClick = { viewModel.onTabChange(MusicSearchViewModel.TAB_FREE) },
                    text = { Text("Свободные") },
                )
                Tab(
                    selected = state.tab == MusicSearchViewModel.TAB_LIBRARY,
                    onClick = { viewModel.onTabChange(MusicSearchViewModel.TAB_LIBRARY) },
                    text = { Text("Мои файлы") },
                )
            }

            when (state.tab) {
                MusicSearchViewModel.TAB_FREE -> FreeTracksTab(state, downloads, viewModel, onOpenTrack)
                MusicSearchViewModel.TAB_LIBRARY -> LibraryTab(state, viewModel, onOpenTrack) {
                    picker.launch(arrayOf("audio/*"))
                }
                else -> MetadataTab(state, viewModel, onOpenTrack)
            }
        }
    }
}

// --- вкладка «Поиск» ---------------------------------------------------------

@Composable
private fun MetadataTab(
    state: MusicSearchViewModel.UiState,
    viewModel: MusicSearchViewModel,
    onOpenTrack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        SourceErrors(state.errors)
        if (state.searching && state.results.isEmpty()) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (state.results.isEmpty() && !state.searching) {
            EmptyState(
                icon = Icons.Default.Search,
                title = if (state.hasQuery) "Ничего не нашлось" else "Найдите трек по названию",
                message = if (state.hasQuery) {
                    "Проверьте раскладку и написание — иначе источники не найдут запись."
                } else {
                    "iTunes и MusicBrainz дают только метаданные: они помогают опознать трек, " +
                        "но файлов не отдают. Файлы — на вкладке «Свободные»."
                },
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.results, key = { it.key }) { row ->
                MetadataRow(row) {
                    viewModel.openResult(row)
                    onOpenTrack()
                }
            }
        }
    }
}

@Composable
private fun MetadataRow(result: MusicSearchResult, onClick: () -> Unit) {
    TrackCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NetworkImage(
                url = result.coverUrl,
                contentDescription = result.title,
                modifier = Modifier
                    .size(width = 56.dp, height = 56.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = result.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                result.artist?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = listOfNotNull(
                        result.album,
                        result.year?.toString(),
                        formatDurationSec(result.durationSec),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoChip(result.source.label, color = KagamiColors.cyan)
                    InfoChip("только метаданные", color = KagamiColors.amber)
                }
            }
        }
    }
}

// --- вкладка «Свободные треки» ------------------------------------------------

@Composable
private fun FreeTracksTab(
    state: MusicSearchViewModel.UiState,
    downloads: Map<String, DownloadProgress>,
    viewModel: MusicSearchViewModel,
    onOpenTrack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(FreeTrackFilter.entries) { filter ->
                FilterChip(
                    selected = state.filter == filter,
                    onClick = { viewModel.onFilterChange(filter) },
                    label = { Text(filter.label) },
                )
            }
        }
        Text(
            text = "Только источники, которые публикуют свободную лицензию. " +
                "Без читаемой лицензии файл считается закрытым.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        SourceErrors(state.errors)
        if (state.loadingFree) LinearProgressIndicator(Modifier.fillMaxWidth())

        if (state.freeResults.isEmpty() && !state.loadingFree) {
            EmptyState(
                icon = Icons.Default.MusicNote,
                title = "Свободных треков нет",
                message = if (state.hasQuery) {
                    "Попробуйте другое слово или смените фильтр лицензии — ccMixter ищет по тегам, " +
                        "Internet Archive — по названию издания."
                } else {
                    "Введите название или тег (ambient, jazz, chiptune) и откройте вкладку «Свободные»."
                },
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.freeResults, key = { it.key }) { track ->
                FreeTrackRow(
                    track = track,
                    progress = downloads[track.key],
                    onOpen = {
                        viewModel.openFreeTrack(track)
                        onOpenTrack()
                    },
                    onDownload = { viewModel.download(track) },
                )
            }
        }
    }
}

@Composable
private fun FreeTrackRow(
    track: FreeTrack,
    progress: DownloadProgress?,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
) {
    TrackCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NetworkImage(
                url = track.coverUrl,
                contentDescription = track.title,
                modifier = Modifier
                    .size(width = 56.dp, height = 56.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(track.artistName, track.year?.toString())
                        .joinToString(" · ")
                        .ifEmpty { "автор не указан" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        formatDurationSec(track.format.durationSec),
                        formatBytes(track.format.fileBytes),
                        track.format.sampleRateHz?.let { "${it / 1000} кГц" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onDownload,
                enabled = track.downloadable && progress?.state != DownloadState.RUNNING,
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "Скачать",
                    tint = if (track.downloadable) KagamiColors.violet else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            InfoChip(track.source.label, color = KagamiColors.cyan)
            val licence = track.license.badgeLabel
            if (licence != null) {
                InfoChip(licence, color = KagamiColors.violet)
            } else {
                InfoChip("лицензия не указана", color = KagamiColors.danger)
            }
            if (track.license.nonCommercial) InfoChip("NC", color = KagamiColors.amber)
            if (track.license.shareAlike) InfoChip("SA", color = KagamiColors.amber)
        }

        track.licenceNote?.let { note ->
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = KagamiColors.danger,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(note, style = MaterialTheme.typography.labelSmall, color = KagamiColors.danger)
            }
        }

        if (track.license.requiresAttribution) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "При использовании укажите автора: ${track.artistName ?: "имя не указано"}" +
                    (track.pageUrl?.let { " — $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        progress?.let { bar ->
            Spacer(Modifier.height(8.dp))
            when (bar.state) {
                DownloadState.RUNNING -> {
                    LinearProgressIndicator(
                        progress = { bar.fraction ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "${formatBytes(bar.bytesRead)} из ${formatBytes(bar.totalBytes) ?: "?"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DownloadState.DONE -> Text(
                    text = "Файл сохранён: ${formatBytes(bar.bytesRead)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = KagamiColors.violet,
                )
                DownloadState.FAILED -> Text(
                    text = bar.message.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = KagamiColors.danger,
                )
            }
        }
    }
}

// --- вкладка «Мои файлы» ------------------------------------------------------

@Composable
private fun LibraryTab(
    state: MusicSearchViewModel.UiState,
    viewModel: MusicSearchViewModel,
    onOpenTrack: () -> Unit,
    onImport: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Импортированные и скачанные файлы",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(onClick = onImport, enabled = !state.importsInProgress) {
                if (state.importsInProgress) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Импорт")
                }
            }
        }

        if (state.library.isEmpty()) {
            EmptyState(
                icon = Icons.Default.LibraryMusic,
                title = "Пока пусто",
                message = "Импортируйте свой файл через системный выбор — Kagami скопирует его, " +
                    "посчитает sha256 и прочитает теги. Лицензию указываете вы сами.",
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.library, key = { it.id }) { entry ->
                LibraryRow(
                    entry = entry,
                    onOpen = {
                        viewModel.openEntry(entry)
                        onOpenTrack()
                    },
                    onDelete = { viewModel.deleteEntry(entry.id) },
                )
            }
        }
    }
}

@Composable
private fun LibraryRow(entry: AudioLibraryEntry, onOpen: () -> Unit, onDelete: () -> Unit) {
    TrackCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NetworkImage(
                url = entry.coverFile?.let { "file://$it" },
                contentDescription = entry.title,
                modifier = Modifier
                    .size(width = 56.dp, height = 56.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(entry.artist, entry.album).joinToString(" · ")
                        .ifEmpty { "исполнитель не указан" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.fileName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoChip(entry.source.label, color = KagamiColors.cyan)
                    InfoChip(
                        text = entry.license.badgeLabel ?: "не указана (свой файл)",
                        color = if (entry.licenceKnown) KagamiColors.violet else KagamiColors.amber,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = KagamiColors.danger)
            }
        }
    }
}

// --- общие мелкие виджеты -----------------------------------------------------

@Composable
private fun TrackCard(onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(12.dp), content = content)
    }
}

@Composable
private fun SourceErrors(errors: List<MusicSourceError>) {
    if (errors.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        errors.forEach { error ->
            Text(
                text = error.message,
                style = MaterialTheme.typography.labelSmall,
                color = KagamiColors.danger,
            )
        }
    }
}

// --- точка входа во view model -------------------------------------------------

/** Public so the nav host can build the view model itself and pass it in. */
@Composable
fun musicSearchViewModel(): MusicSearchViewModel {
    val repository = rememberMusicRepository()
    return viewModel(factory = MusicSearchViewModelFactory(repository))
}

@Composable
fun rememberMusicRepository(): MusicRepository {
    val context = LocalContext.current
    return remember(context) {
        (context.applicationContext as KagamiApp).container.musicRepository
    }
}
