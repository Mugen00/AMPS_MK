@file:OptIn(ExperimentalMaterial3Api::class)

package dev.amps.app.ui.screens.music

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
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.amps.app.AmpsApp
import dev.amps.app.data.model.AudioLibraryEntry
import dev.amps.app.data.model.FreeTrack
import dev.amps.app.data.model.FreeTrackFilter
import dev.amps.app.data.model.LicenceSummary
import dev.amps.app.data.model.MusicHit
import dev.amps.app.data.model.MusicHitKind
import dev.amps.app.data.model.MusicSearchScope
import dev.amps.app.data.model.MusicSourceError
import dev.amps.app.data.repo.MusicRepository
import dev.amps.app.music.MusicOnlinePlayer
import dev.amps.app.ui.components.EmptyState
import dev.amps.app.ui.components.InfoChip
import dev.amps.app.ui.components.NetworkImage
import dev.amps.app.ui.theme.AmpsColors
import dev.amps.app.util.formatBytes
import dev.amps.app.util.formatDurationSec

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
    val snackbar = remember { SnackbarHostState() }

    // 1.2.1: стан онлайн-плеєра (трек, що грає зараз) — живе в AppContainer.
    val nowPlaying = viewModel.player?.now?.collectAsStateWithLifecycle()?.value
    val isPlaying = viewModel.player?.playing?.collectAsStateWithLifecycle()?.value ?: false

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importAudio)
    }

    // 1.1.3: діалог імпорту за прямою URL-адресою файлу.
    var showUrlImport by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.dismissMessage()
    }

    LaunchedEffect(Unit) {
        // 1.2.1: плейлісти підтягуються раз, при вході на вкладку музики.
        viewModel.loadPlaylists()
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
                    IconButton(onClick = { showUrlImport = true }) {
                        Icon(Icons.Default.Link, contentDescription = "Імпорт за посиланням")
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
            if (showUrlImport) {
                UrlImportDialog(
                    busy = state.importsInProgress,
                    onImport = { url ->
                        showUrlImport = false
                        viewModel.importFromUrl(url)
                    },
                    onDismiss = { showUrlImport = false },
                )
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                label = { Text("Название трека или «исполнитель — трек»") },
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

            // 1.2.1: завантажень більше немає — пошук і онлайн-прослуховування.
            ScopePicker(state.scope, state.scope.blurb) { viewModel.onScopeChange(it) }

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
                    selected = state.tab == MusicSearchViewModel.TAB_PLAYLISTS,
                    onClick = {
                        viewModel.onTabChange(MusicSearchViewModel.TAB_PLAYLISTS)
                        viewModel.loadPlaylists()
                    },
                    text = { Text("Плейлісти") },
                )
                Tab(
                    selected = state.tab == MusicSearchViewModel.TAB_LIBRARY,
                    onClick = { viewModel.onTabChange(MusicSearchViewModel.TAB_LIBRARY) },
                    text = { Text("Мои файлы") },
                )
            }

            when (state.tab) {
                MusicSearchViewModel.TAB_PLAYLISTS -> PlaylistsTab(state, viewModel, nowPlaying, isPlaying)
                MusicSearchViewModel.TAB_FREE -> FreeTracksTab(
                    state, viewModel, nowPlaying, isPlaying, onOpenTrack,
                )
                MusicSearchViewModel.TAB_LIBRARY -> LibraryTab(state, viewModel, onOpenTrack) {
                    picker.launch(arrayOf("audio/*"))
                }
                else -> MetadataTab(state, viewModel, nowPlaying, isPlaying, onOpenTrack)
            }

            // 1.2.1: міні-плеєр — завжди внизу, поки щось грає.
            nowPlaying?.let { now ->
                PlayerBar(
                    now = now,
                    isPlaying = isPlaying,
                    onToggle = viewModel::togglePlay,
                    onNext = viewModel::nextTrack,
                    onPrevious = viewModel::previousTrack,
                    onClose = viewModel::stopPlayer,
                )
            }
        }
    }

    // 1.2.1: діалог «додати в плейліст» — поверх екрана.
    state.addTrackTarget?.let { target ->
        AddToPlaylistDialog(
            track = target,
            playlists = state.playlists,
            busy = state.playlistBusy,
            onPick = { playlistId -> viewModel.addToPlaylist(target, playlistId) },
            onCreate = { name -> viewModel.createPlaylist(name) },
            onDismiss = viewModel::dismissAddToPlaylist,
        )
    }
}

// --- вкладка «Поиск» ---------------------------------------------------------

/**
 * Переключатель «что именно ищем» и пояснение под ним.
 *
 * Пояснение обязано быть видно **всегда**, а не только при пустой выдаче:
 * человек должен знать про смешанные лицензии до того, как потратит время на
 * прокрутку строк, которые скачать нельзя.
 */
@Composable
private fun ScopePicker(
    selected: MusicSearchScope,
    blurb: String,
    onSelect: (MusicSearchScope) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = "Что ищем",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MusicSearchScope.entries.forEach { scope ->
                FilterChip(
                    selected = selected == scope,
                    onClick = { onSelect(scope) },
                    label = { Text(scope.label) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = blurb,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * Смешанная выдача: сначала скачиваемое, потом метаданные.
 *
 * Два списка в одном — это ровно то, что человек ожидает, пришедши за музыкой:
 * верхняя половина ответа на вопрос «что можно забрать», нижняя — «что можно
 * хотя бы узнать». Разделение видно и подписано, а не спрятано в сортировке.
 */
@Composable
private fun MetadataTab(
    state: MusicSearchViewModel.UiState,
    viewModel: MusicSearchViewModel,
    nowPlaying: MusicOnlinePlayer.NowPlaying?,
    isPlaying: Boolean,
    onOpenTrack: () -> Unit,
) {
    val files = state.hits.filter { it.freeTrack != null }
    val metadata = state.hits.filter { it.freeTrack == null }

    Column(Modifier.fillMaxSize()) {
        SourceErrors(state.errors)
        LicenceBanner(state.hitSummary)
        if (state.searching && state.hits.isEmpty()) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        if (state.hits.isEmpty() && !state.searching) {
            EmptySearchResult(state)
        }

        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (files.isNotEmpty()) {
                item(key = "section-files") {
                    SectionLabel(
                        title = "Слухати онлайн",
                        count = files.size,
                        subtitle = "Повний трек з вільного джерела — грає одразу",
                        color = AmpsColors.violet,
                    )
                }
                items(files, key = { it.key }) { hit ->
                    UnifiedTrackRow(
                        hit = hit,
                        isPlaying = nowPlaying?.trackKey == hit.freeTrack?.key && isPlaying,
                        onPlay = {
                            val track = hit.freeTrack ?: return@UnifiedTrackRow
                            val mine = nowPlaying?.trackKey == track.key
                            if (mine) viewModel.togglePlay() else viewModel.playTrack(track)
                        },
                        onAddToPlaylist = { hit.freeTrack?.let(viewModel::showAddToPlaylist) },
                        onClick = {
                            if (viewModel.openResult(hit)) onOpenTrack()
                        },
                    )
                }
            }

            if (metadata.isNotEmpty()) {
                item(key = "section-meta") {
                    SectionLabel(
                        title = "Только метаданные",
                        count = metadata.size,
                        subtitle = "Описание записи есть, файла у нас нет",
                        color = AmpsColors.amber,
                    )
                }
                items(metadata, key = { it.key }) { hit ->
                    UnifiedTrackRow(
                        hit = hit,
                        isPlaying = false,
                        onPlay = {},
                        onAddToPlaylist = {},
                        onClick = {
                            if (viewModel.openResult(hit)) onOpenTrack()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Пустая выдача говорит ровно то, что произошло.
 *
 * Три состояния различаются намеренно, потому что раньше они сливались в одно
 * пустое место: «треки нашлись, но скачать нельзя» — это не «ничего не нашлось»,
 * и человек в первом случае может осознанно переключить режим или поискать иначе.
 */
@Composable
private fun EmptySearchResult(state: MusicSearchViewModel.UiState) {
    when {
        !state.hasQuery -> EmptyState(
            icon = Icons.Default.Search,
            title = "Найдите трек по названию",
            message = "Можно написать просто «One More Time», «исполнитель — трек» или жанр — " +
                "«jazz». Скачиваемое показывается выше, описания — ниже.",
        )

        state.metadataOnlyNoFiles -> EmptyState(
            icon = Icons.Default.Info,
            title = "Треки найдены, скачать их нельзя",
            message = "Источники описаний нашли ${state.metadataFound} записей, но файлов они не " +
                "отдают. Скачать можно то, что публикуют Jamendo, Internet Archive и ccMixter; " +
                "по этому запросу таких не нашлось — это не значит, что музыки не существует.",
        )

        state.nothingAtAll -> EmptyState(
            icon = Icons.Default.Search,
            title = "Ничего не нашлось",
            message = "Проверьте раскладку и написание. Запрос можно упростить до одного слова — " +
                "так находится больше, чем по полному названию.",
        )

        else -> EmptyState(
            icon = Icons.Default.Warning,
            title = "Источники не ответили",
            message = "Ни один источник не вернул результат, и часть из них сообщила об ошибке — " +
                "подробности выше. Проверьте соединение и попробуйте ещё раз.",
        )
    }
}

@Composable
private fun SectionLabel(title: String, count: Int, subtitle: String, color: androidx.compose.ui.graphics.Color) {
    Column(Modifier.padding(top = 4.dp, bottom = 2.dp)) {
        Text(
            text = "$title · $count",
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Сводка по лицензиям — то самое, что человек должен увидеть про выдачу.
 *
 * Считается из ответа (см. [dev.amps.app.data.ranking.MusicRanker.summarise]),
 * а не пишется текстом: подпись, обещающая результат, который источник не
 * прислал, хуже, чем отсутствие подписи.
 */
@Composable
private fun LicenceBanner(summary: LicenceSummary) {
    val headline = summary.headline
    if (headline.isBlank()) return
    val tone = when {
        summary.fileRows == 0 -> AmpsColors.amber
        summary.mixed -> AmpsColors.cyan
        else -> AmpsColors.violet
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(text = headline, style = MaterialTheme.typography.labelMedium, color = tone)
        val licences = summary.licenceLine
        if (licences.isNotBlank()) {
            Text(
                text = licences,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Строка объединённой выдачи.
 *
 * 1.2.1: кнопка скачивания заменена на ПЛЕЙ — треки вільних джерел грають
 * потоково, повністю. iTunes — чесна позначка «прев'ю 30с».
 */
@Composable
private fun UnifiedTrackRow(
    hit: MusicHit,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onClick: () -> Unit,
) {
    TrackCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NetworkImage(
                url = hit.coverUrl,
                contentDescription = hit.title,
                modifier = Modifier
                    .size(width = 56.dp, height = 56.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = hit.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        hit.artist,
                        hit.year?.toString(),
                        formatDurationSec(hit.durationSec),
                    ).joinToString(" · ").ifEmpty { "автор не указан" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = hit.match.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 1.2.1: ПЛЕЙ (онлайн-потік) + «додати в плейліст».
            if (hit.freeTrack?.audioUrl != null) {
                IconButton(onClick = onPlay) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Пауза" else "Слухати онлайн",
                        tint = AmpsColors.violet,
                    )
                }
            }
            IconButton(onClick = onAddToPlaylist) {
                Icon(
                    imageVector = Icons.Default.PlaylistAdd,
                    contentDescription = "Додати в плейліст",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            InfoChip(hit.source.label, color = AmpsColors.cyan)
            // Метка «что это за строка» стоит первой: именно она снимает
            // главное недоумение — почему одна строка скачивается, а нет.
            InfoChip(hit.kind.shortLabel, color = if (hit.downloadable) AmpsColors.violet else AmpsColors.amber)
            hit.license.badgeLabel?.let { InfoChip(it, color = AmpsColors.violet) }
            if (hit.license.nonCommercial) InfoChip("NC", color = AmpsColors.amber)
            if (hit.license.shareAlike) InfoChip("SA", color = AmpsColors.amber)
        }

        // Файл есть, а лицензия не прочиталась: кнопка уже серая, но сказать об
        // этом надо словами — иначе строка выглядит просто «ещё одним треком».
        if (hit.kind == MusicHitKind.FILE && !hit.license.isKnown) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = AmpsColors.danger,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Лицензия не указана — файл закрыт для скачивания",
                    style = MaterialTheme.typography.labelSmall,
                    color = AmpsColors.danger,
                )
            }
        }

        if (hit.license.isKnown && hit.license.requiresAttribution) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "При использовании укажите автора: ${hit.artist ?: "имя не указано"}" +
                    (hit.pageUrl?.let { " — $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --- вкладка «Свободные треки» ------------------------------------------------

@Composable
private fun FreeTracksTab(
    state: MusicSearchViewModel.UiState,
    viewModel: MusicSearchViewModel,
    nowPlaying: MusicOnlinePlayer.NowPlaying?,
    isPlaying: Boolean,
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
        // Сводка по лицензиям видна и здесь: выбранный фильтр «Любая CC» внутри
        // на самом деле прячет NC-записи, и об этом лучше сказать заранее.
        if (state.filter != FreeTrackFilter.OPEN) {
            Text(
                text = "Фильтр: ${state.filter.label}. Для обычного использования удобнее «Свободные (без NC)».",
                style = MaterialTheme.typography.labelSmall,
                color = AmpsColors.amber,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        LicenceBanner(state.freeSummary)
        if (state.loadingFree) LinearProgressIndicator(Modifier.fillMaxWidth())

        if (state.freeResults.isEmpty() && !state.loadingFree) {
            EmptyState(
                icon = Icons.Default.MusicNote,
                title = "Свободных треков нет",
                message = when {
                    !state.hasQuery ->
                        "Введите название или тег (ambient, jazz, chiptune) и откройте вкладку «Свободные»."
                    state.errors.isNotEmpty() ->
                        "Свободные источники не ответили — подробности выше. Это их сбой, " +
                            "а не отсутствие музыки: попробуйте ещё раз или смените фильтр."
                    else ->
                        "По этому запросу Jamendo, Internet Archive и ccMixter ничего не дали. " +
                            "Смените фильтр лицензии или упростите запрос до одного слова — " +
                            "свободные площадки ищут по тегам лучше, чем по точному названию."
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
                    isPlaying = nowPlaying?.trackKey == track.key && isPlaying,
                    onPlay = {
                        val mine = nowPlaying?.trackKey == track.key
                        if (mine) viewModel.togglePlay() else viewModel.playTrack(track)
                    },
                    onAddToPlaylist = { viewModel.showAddToPlaylist(track) },
                    onOpen = {
                        viewModel.openFreeTrack(track)
                        onOpenTrack()
                    },
                )
            }
        }
    }
}

@Composable
private fun FreeTrackRow(
    track: FreeTrack,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onOpen: () -> Unit,
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
            // 1.2.1: ПЛЕЙ (повне онлайн-прослуховування) + плейліст.
            if (!track.audioUrl.isNullOrBlank()) {
                IconButton(onClick = onPlay) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Пауза" else "Слухати онлайн",
                        tint = AmpsColors.violet,
                    )
                }
            }
            IconButton(onClick = onAddToPlaylist) {
                Icon(
                    imageVector = Icons.Default.PlaylistAdd,
                    contentDescription = "Додати в плейліст",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            InfoChip(track.source.label, color = AmpsColors.cyan)
            val licence = track.license.badgeLabel
            if (licence != null) {
                InfoChip(licence, color = AmpsColors.violet)
            } else {
                InfoChip("лицензия не указана", color = AmpsColors.danger)
            }
            if (track.license.nonCommercial) InfoChip("NC", color = AmpsColors.amber)
            if (track.license.shareAlike) InfoChip("SA", color = AmpsColors.amber)
        }

        track.licenceNote?.let { note ->
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = AmpsColors.danger,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(note, style = MaterialTheme.typography.labelSmall, color = AmpsColors.danger)
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
                message = "Импортируйте свой файл через системный выбор — AMPS скопирует его, " +
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
                    InfoChip(entry.source.label, color = AmpsColors.cyan)
                    InfoChip(
                        text = entry.license.badgeLabel ?: "не указана (свой файл)",
                        color = if (entry.licenceKnown) AmpsColors.violet else AmpsColors.amber,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = AmpsColors.danger)
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
                color = AmpsColors.danger,
            )
        }
    }
}

/** Треки плейліста → черга онлайн-плеєра. Ключ "pl:source:sourceId". */
private fun playlistToQueue(
    tracks: List<dev.amps.app.data.remote.backend.dto.PlaylistTrackDto>,
): List<MusicOnlinePlayer.QueueItem> = tracks.map { t ->
    MusicOnlinePlayer.QueueItem(
        trackKey = "pl:${t.source}:${t.sourceId}",
        title = t.title,
        artist = t.artist,
        audioUrl = t.audioUrl,
        coverUrl = t.coverUrl,
        isPreview = t.source == "itunes",
    )
}

// --- вкладка «Плейлісти» (1.2.1) ---------------------------------------------

/**
 * Свої плейлісти акаунта — живуть у базі сервера і переживают оновлення,
 * бо прив'язані до профіля, а не до пристрою.
 */
@Composable
private fun PlaylistsTab(
    state: MusicSearchViewModel.UiState,
    viewModel: MusicSearchViewModel,
    nowPlaying: MusicOnlinePlayer.NowPlaying?,
    isPlaying: Boolean,
) {
    var newPlaylistName by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        // Створення нового плейліста.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = newPlaylistName,
                onValueChange = { newPlaylistName = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Назва нового плейліста") },
            )
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = {
                    viewModel.createPlaylist(newPlaylistName)
                    newPlaylistName = ""
                },
                enabled = newPlaylistName.isNotBlank() && !state.playlistBusy,
            ) {
                Text("Створити")
            }
        }

        if (!state.playlistsAuthorized) {
            EmptyState(
                icon = Icons.Default.LibraryMusic,
                title = "Плейлісти живуть в акаунті",
                message = "Увійдіть у спільноті — плейлісти прив'яжуться до вашого профіля " +
                    "і зберігатимуться на сервері: вони не зникнуть після оновлення застосунку.",
            )
            return@Column
        }
        if (state.playlistsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())

        if (state.openPlaylistId != null) {
            // --- детальний вигляд: треки відкритого плейліста ---
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = state.openPlaylistName.ifEmpty { "Плейліст" },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "${state.openPlaylistTracks.size} треків",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = viewModel::closePlaylist) { Text("До списку") }
            }
            if (state.openPlaylistTracks.isNotEmpty()) {
                val queue = playlistToQueue(state.openPlaylistTracks)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    FilledTonalButton(onClick = {
                        viewModel.player?.play(queue, queue.firstOrNull()?.trackKey.orEmpty())
                    }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Грати все")
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.openPlaylistTracks, key = { it.id }) { row ->
                    PlaylistTrackRow(
                        track = row,
                        isPlaying = nowPlaying?.trackKey == "pl:${row.source}:${row.sourceId}" && isPlaying,
                        onPlay = {
                            val queue = playlistToQueue(state.openPlaylistTracks)
                            val mine = nowPlaying?.trackKey == "pl:${row.source}:${row.sourceId}"
                            if (mine) viewModel.togglePlay()
                            else viewModel.player?.play(queue, "pl:${row.source}:${row.sourceId}")
                        },
                        onRemove = {
                            state.openPlaylistId?.let { id -> viewModel.removePlaylistTrack(id, row.id) }
                        },
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.playlists.isEmpty() && !state.playlistsLoading) {
                    item {
                        EmptyState(
                            icon = Icons.Default.LibraryMusic,
                            title = "Плейлістів ще немає",
                            message = "Знайдіть трек на вкладках «Поиск» або «Свободные», " +
                                "натисніть «+» у рядку — і він опиниться тут. Плейлісти " +
                                "прив'язані до акаунта і живуть на сервері.",
                        )
                    }
                }
                items(state.playlists, key = { it.id }) { pl ->
                    TrackCard(onClick = { viewModel.openPlaylist(pl.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.LibraryMusic,
                                contentDescription = null,
                                tint = AmpsColors.violet,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(pl.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "${pl.trackCount} треків",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { viewModel.deletePlaylist(pl.id) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Видалити плейліст",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Рядок трека всередині плейліста: грає потоково, можна прибрати. */
@Composable
private fun PlaylistTrackRow(
    track: dev.amps.app.data.remote.backend.dto.PlaylistTrackDto,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    TrackCard(onClick = onPlay) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NetworkImage(
                url = track.coverUrl,
                contentDescription = track.title,
                modifier = Modifier
                    .size(width = 48.dp, height = 56.dp)
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
                    text = track.artist.ifEmpty { "автор не указан" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track.source,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onPlay) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Пауза" else "Слухати",
                    tint = AmpsColors.violet,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Прибрати з плейліста",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Міні-плеєр: що грає, плей/пауза, далі/назад, стоп. */
@Composable
private fun PlayerBar(
    now: MusicOnlinePlayer.NowPlaying,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            NetworkImage(
                url = now.coverUrl,
                contentDescription = now.title,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = now.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = (now.artist.ifBlank { "—" }) + if (now.isPreview) " · прев'ю 30с" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onPrevious) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Попередній")
            }
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Пауза" else "Грати",
                    tint = AmpsColors.violet,
                )
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Default.SkipNext, contentDescription = "Наступний")
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Зупинити", tint = AmpsColors.danger)
            }
        }
    }
}

/** Діалог вибору плейліста для трека (або створення нового). */
@Composable
private fun AddToPlaylistDialog(
    track: FreeTrack,
    playlists: List<dev.amps.app.data.remote.backend.dto.PlaylistDto>,
    busy: Boolean,
    onPick: (Int) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Додати в плейліст") },
        text = {
            Column {
                Text(
                    track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.height(180.dp)) {
                    items(playlists, key = { it.id }) { pl ->
                        TextButton(onClick = { onPick(pl.id) }) {
                            Text("${pl.name} · ${pl.trackCount}")
                        }
                    }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Або створити новий") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (newName.isNotBlank()) onCreate(newName) },
                enabled = !busy && newName.isNotBlank(),
            ) {
                Text("Створити плейліст")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Скасувати") }
        },
    )
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
        (context.applicationContext as AmpsApp).container.musicRepository
    }
}

/**
 * 1.1.3: діалог імпорту за прямою URL-адресою. Чесно каже, що застосунок
 * не шукає по сайтах — він завантажує лише файл, адресу якого користувач
 * дав сам, і перевіряє, що це справді аудіо, а не сторінка помилки.
 */
@Composable
private fun UrlImportDialog(
    busy: Boolean,
    onImport: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Імпорт за посиланням") },
        text = {
            Column {
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Завантаження…")
                    }
                } else {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Пряме посилання на аудіофайл") },
                        placeholder = { Text("https://…/track.mp3") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Працює з прямими посиланнями на mp3/wav — наприклад, з відкритих " +
                            "архівів чи власного хостингу. AMPS не збирає музику з піратських " +
                            "сайтів: файл має бути тим, на який у вас є право.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(url) },
                enabled = !busy && url.trim().startsWith("http"),
            ) {
                Text("Імпортувати")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Скасувати") }
        },
    )
}
