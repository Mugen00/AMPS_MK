@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.amps.app.ui.screens.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import dev.amps.app.data.model.AudioFormat
import dev.amps.app.data.model.MusicLink
import dev.amps.app.data.model.MusicSearchResult
import dev.amps.app.data.model.MusicSource
import dev.amps.app.data.model.TrackWikiPage
import dev.amps.app.ui.components.InfoChip
import dev.amps.app.ui.components.SectionCard
import dev.amps.app.ui.components.StatRow
import dev.amps.app.ui.theme.AmpsColors
import dev.amps.app.util.formatBytes
import dev.amps.app.util.formatDurationMs
import dev.amps.app.util.formatDurationSec
import dev.amps.app.util.formatSampleRate
import dev.amps.app.util.openUrl

/**
 * The track wiki page: identity, licence, technical facts, where it came from
 * and what else the artist recorded — plus a play button that only exists when
 * the app actually holds the file on disk.
 */
@Composable
fun TrackWikiScreen(
    viewModel: TrackWikiViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

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
                title = { Text(state.page?.source?.label ?: "Трек") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val page = state.page
        when {
            state.loading && page == null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            page == null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = state.error ?: "Трек не найден",
                    color = AmpsColors.danger,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = viewModel::load) { Text("Повторить") }
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                item { WikiHeader(page) }
                item {
                    val musicPlayer = (context.applicationContext as dev.amps.app.AmpsApp)
                        .container.musicPlayer
                    PlaybackCard(
                        page = page,
                        state = state,
                        onToggle = viewModel::togglePlayback,
                        onOnlinePlay = {
                            val track = page.freeTrack
                            val audio = track?.audioUrl
                            if (track != null && !audio.isNullOrBlank()) {
                                musicPlayer.play(
                                    listOf(
                                        dev.amps.app.music.MusicOnlinePlayer.QueueItem(
                                            trackKey = track.key,
                                            title = track.title,
                                            artist = track.artistName ?: "",
                                            audioUrl = audio,
                                            coverUrl = track.coverUrl,
                                            isPreview = track.source == dev.amps.app.data.model.MusicSource.ITUNES,
                                        ),
                                    ),
                                    track.key,
                                )
                            }
                        },
                    )
                }
                item { IdentityCard(page) }
                item { FactsCard(page) }
                item {
                    LicenceCard(
                        page = page,
                        editing = state.editingLicence,
                        saving = state.saving,
                        viewModel = viewModel,
                        onOpenLink = { openUrl(context, it) },
                    )
                }
                if (page.links.isNotEmpty()) {
                    item { LinksCard(page.links) { openUrl(context, it) } }
                }
                if (page.similar.isNotEmpty()) {
                    item {
                        Text(
                            text = "Похожие треки",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 18.dp, top = 18.dp, bottom = 8.dp),
                        )
                    }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(page.similar, key = { it.key }) { similar ->
                                SimilarCard(similar) { viewModel.openSimilar(similar) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- шапка ---------------------------------------------------------------------

@Composable
private fun WikiHeader(page: TrackWikiPage) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HEADER_HEIGHT),
    ) {
        val cover = page.coverUrl ?: page.coverFile?.let { "file://$it" }
        if (cover != null) {
            AsyncImage(
                model = cover,
                contentDescription = page.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
                            MaterialTheme.colorScheme.background,
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(18.dp),
        ) {
            Text(
                text = page.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            page.artist?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// --- воспроизведение ------------------------------------------------------------

@Composable
private fun PlaybackCard(
    page: TrackWikiPage,
    state: TrackWikiViewModel.UiState,
    onToggle: () -> Unit,
    onOnlinePlay: () -> Unit,
) {
    SectionCard(title = "Файл", icon = Icons.Default.MusicNote, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        if (page.playableFile == null) {
            Text(
                text = if (page.source.offersFile && page.freeTrack != null) {
                    "Файл ещё не скачан. Источник — ${page.source.label}."
                } else {
                    "Файла нет: ${page.source.label} — только метаданные, он не публикует аудио."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggle) {
                    Icon(
                        imageVector = if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.playing) "Пауза" else "Слушать",
                        tint = AmpsColors.violet,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (state.playing) "Играет" else "Слушать",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = page.fileName.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val duration = state.durationMs.takeIf { it > 0 }
            if (state.playing && duration != null) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (state.positionMs.toFloat() / duration).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${formatDurationMs(state.positionMs.toLong())} / ${formatDurationMs(duration.toLong())}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        page.freeTrack?.let { track ->
            // 1.2.1: завантажень більше немає — тільки онлайн-прослуховування.
            if (!track.audioUrl.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onOnlinePlay,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Слухати онлайн повністю")
                }
                if (track.source == dev.amps.app.data.model.MusicSource.ITUNES) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "iTunes дає лише 30-секундне прев'ю — повні версії дивіться на вкладці «Свободные».",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// --- оформление ----------------------------------------------------------------

@Composable
private fun IdentityCard(page: TrackWikiPage) {
    SectionCard(title = "Трек", icon = Icons.Default.Info, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            page.album?.let { InfoChip(it) }
            page.year?.let { InfoChip(it.toString(), color = AmpsColors.cyan) }
            page.genre?.let { InfoChip(it, color = AmpsColors.cyan) }
            formatDurationSec(page.format.durationSec)?.let { InfoChip(it) }
        }
        page.versionHint?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Версия: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FactsCard(page: TrackWikiPage) {
    SectionCard(title = "Технические данные", icon = Icons.Default.Album, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        StatRow("Длительность", formatDurationSec(page.format.durationSec) ?: "не указана")
        StatRow("Битрейт", bitrateLabel(page.format))
        StatRow("Частота дискретизации", formatSampleRate(page.format.sampleRateHz) ?: "не указана")
        page.format.channels?.let { StatRow("Каналы", it) }
        StatRow("Размер", formatBytes(page.format.fileBytes) ?: "не указан")
        page.format.mimeType?.let { StatRow("Формат", it) }
        page.localPath?.let { StatRow("Путь", it, valueColor = AmpsColors.cyan) }
        page.sha256?.let { StatRow("SHA-256", it) }
    }
}

private fun bitrateLabel(format: AudioFormat): String {
    val value = format.bitrateKbps?.let { "$it кбит/с" }
    return listOfNotNull(value, format.bitrateKind).joinToString(" · ").ifEmpty { "не указан" }
}

@Composable
private fun LicenceCard(
    page: TrackWikiPage,
    editing: Boolean,
    saving: Boolean,
    viewModel: TrackWikiViewModel,
    onOpenLink: (String) -> Unit,
) {
    SectionCard(
        title = "Лицензия",
        icon = Icons.Default.Check,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        val license = page.license
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = license.badgeLabel ?: license.name ?: "не указана",
                style = MaterialTheme.typography.titleSmall,
                color = if (license.isKnown) AmpsColors.violet else AmpsColors.danger,
            )
            if (page.source == MusicSource.LOCAL && !license.isKnown) {
                Spacer(Modifier.width(8.dp))
                InfoChip("свой файл", color = AmpsColors.amber)
            }
        }

        if (!license.isKnown) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (page.source == MusicSource.LOCAL) {
                    "Лицензия не указана (свой файл). Файл принадлежит вам — укажите условия " +
                        "ниже, если планируете его использовать или публиковать."
                } else {
                    "Источник не опубликовал лицензию — файл считается закрытым, " +
                        "скачивание и перепубликация запрещены."
                },
                style = MaterialTheme.typography.bodySmall,
                color = AmpsColors.danger,
            )
        } else {
            Spacer(Modifier.height(10.dp))
            license.obligations.forEach { obligation ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 2.dp),
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = AmpsColors.cyan,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(obligation, style = MaterialTheme.typography.bodySmall)
                }
            }
            license.url?.let { url ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = url,
                    style = MaterialTheme.typography.labelSmall,
                    color = AmpsColors.cyan,
                    modifier = Modifier.clickable { onOpenLink(url) },
                )
            }
        }

        page.attributionNote?.let { note ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Заметка об атрибуции",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(note, style = MaterialTheme.typography.bodySmall)
        }

        if (page.canEditLicence) {
            Spacer(Modifier.height(12.dp))
            if (editing) {
                LicenceEditor(saving = saving, viewModel = viewModel)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.onLicenceEditingChange(true) }) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Указать лицензию")
                    }
                    if (page.localPath != null) {
                        OutlinedButton(onClick = viewModel::deleteLocalFile) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = AmpsColors.danger,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Удалить")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LicenceEditor(saving: Boolean, viewModel: TrackWikiViewModel) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Лицензия (например CC BY 4.0)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text("Ссылка на лицензию") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = note,
        onValueChange = { note = it },
        label = { Text("Атрибуция: автор, источник, год") },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { viewModel.saveAttribution(name, url, note) }, enabled = !saving) {
            Text(if (saving) "Сохраняем…" else "Сохранить")
        }
        OutlinedButton(onClick = { viewModel.onLicenceEditingChange(false) }) { Text("Отмена") }
    }
}

@Composable
private fun LinksCard(links: List<MusicLink>, onOpen: (String) -> Unit) {
    SectionCard(
        title = "Источники",
        icon = Icons.Default.Link,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        links.forEach { link ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = link.url != null) { link.url?.let(onOpen) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Default.Link,
                    contentDescription = null,
                    tint = AmpsColors.cyan,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(link.label, style = MaterialTheme.typography.bodyMedium)
                    link.note?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    link.url?.let { url ->
                        Text(
                            text = url,
                            style = MaterialTheme.typography.labelSmall,
                            color = AmpsColors.cyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SimilarCard(result: MusicSearchResult, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .width(200.dp)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(10.dp)) {
            AsyncImage(
                model = result.coverUrl,
                contentDescription = result.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = result.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(result.album, result.year?.toString()).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Public so the nav host can build the view model itself and pass it in. */
@Composable
fun trackWikiViewModel(): TrackWikiViewModel {
    val repository = rememberMusicRepository()
    return viewModel(factory = TrackWikiViewModelFactory(repository))
}

private val HEADER_HEIGHT = 260.dp
