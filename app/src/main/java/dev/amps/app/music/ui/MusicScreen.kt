package dev.amps.app.music.ui

import android.content.Context
import android.os.Bundle
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaMetadata
import coil.compose.AsyncImage
import dev.amps.app.core.t
import dev.amps.app.music.data.remote.FreeMusicApi
import dev.amps.app.music.data.repository.MusicRepository
import dev.amps.app.music.download.DownloadManager
import dev.amps.app.music.import.LocalFileImporter
import dev.amps.app.music.model.DownloadStatus
import dev.amps.app.music.model.Track
import dev.amps.app.music.model.TrackSource
import dev.amps.app.music.player.ExoPlayerProvider
import dev.amps.app.music.player.AmpsMediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Головний екран музики: таби — Бібліотека, Пошук, Завантаження.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(
    repository: MusicRepository,
    freeMusicApi: FreeMusicApi,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = remember { CoroutineScope(Dispatchers.Main) }

    // Стани UI
    var selectedTab by remember { mutableStateOf(0) } // 0=Library, 1=Search, 2=Downloads
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<FreeMusicApi.Result<List<Track>>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }

    // Отримуємо дані з репозиторію
    val localTracks by repository.localTracks.collectAsStateWithLifecycle(initialValue = emptyList())
    val downloadedTracks by repository.downloadedTracks.collectAsStateWithLifecycle(initialValue = emptyList())
    val favoriteTracks by repository.favoriteTracks.collectAsStateWithLifecycle(initialValue = emptyList())
    val allDownloads by repository.allDownloads.collectAsStateWithLifecycle(initialValue = emptyList())

    // Імпортер файлів
    val importer = remember { LocalFileImporter(context, repository) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            scope.launch { importer.importFile(uri.toString()) }
        }
    }

    // Об'єднані треки для бібліотеки
    val libraryTracks = remember(localTracks, downloadedTracks) {
        (localTracks + downloadedTracks).distinctBy { it.id }
    }

    // Пошук функція
    fun performSearch() {
        if (searchQuery.trim().isNotEmpty()) {
            searching = true
            scope.launch {
                val result = freeMusicApi.searchTracks(searchQuery)
                searchResults = result
                searching = false
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(listOf("Бібліотека", "Пошук", "Завантаження")[selectedTab]) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = t("Назад"))
                    }
                },
                actions = {
                    if (selectedTab == 0) {
                        IconButton(onClick = { showImportDialog = true }) {
                            Icon(Icons.Filled.Add, contentDescription = t("Імпортувати музику"))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            // Таби
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                tabButton("Бібліотека", Icons.Filled.LibraryMusic, 0, selectedTab) { selectedTab = 0 }
                tabButton("Пошук", Icons.Filled.Search, 1, selectedTab) { selectedTab = 1 }
                tabButton("Завантаження", Icons.Filled.FileDownload, 2, selectedTab) { selectedTab = 2 }
            }

            Spacer(Modifier.height(8.dp))

            // Контент табу
            when (selectedTab) {
                0 -> LibraryTab(
                    tracks = libraryTracks,
                    favorites = favoriteTracks,
                    onPlay = { track, tracks -> playQueue(track, tracks, context) },
                    onToggleFavorite = { track -> scope.launch { repository.setFavorite(track.id, !track.isFavorite) } },
                    onDelete = { track -> scope.launch { repository.deleteTrack(track.id) } },
                    onImportClick = { filePicker.launch(arrayOf("audio/*")) },
                )
                1 -> SearchTab(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onSearch = { performSearch() },
                    results = searchResults,
                    searching = searching,
                    onDownload = { track -> downloadTrack(track) },
                    onPlay = { track, tracks -> playQueue(track, tracks, context) },
                )
                2 -> DownloadsTab(
                    downloads = allDownloads,
                    onCancel = { id -> cancelDownload(id) },
                    onRetry = { task -> retryDownload(task) },
                    onClearCompleted = { clearCompleted() },
                )
            }
        }
    }

    // Mini-player / PlayerBottomSheet
    PlayerBottomSheet(repository = repository)

    // Імпорт діалог
    if (showImportDialog) {
        ImportDialog(onDismiss = { showImportDialog = false }, onPick = { filePicker.launch(arrayOf("audio/*")) })
    }
}

// ===== ТАБ: БІБЛІОТЕКА =====

@Composable
fun LibraryTab(
    tracks: List<Track>,
    favorites: List<Track>,
    onPlay: (Track, List<Track>) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onDelete: (Track) -> Unit,
    onImportClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (tracks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.LibraryMusic, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    Spacer(Modifier.height(16.dp))
                    Text(t("Музична бібліотека порожня"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(t("Додайте треки через імпорт або завантажте з пошуку"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onImportClick) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(t("Імпортувати файли"))
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 0.dp, 16.dp, 100.dp),
            ) {
                if (favorites.isNotEmpty()) {
                    item {
                        Text(t("Улюблені"), style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    items(favorites) { track ->
                        TrackItem(
                            track = track,
                            showFavorite = true,
                            onClick = { onPlay(track, tracks) },
                            onFavoriteClick = { onToggleFavorite(track) },
                            onDeleteClick = { onDelete(track) },
                        )
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }

                item {
                    Text(t("Усі треки"), style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                }
                items(tracks) { track ->
                    TrackItem(
                        track = track,
                        showFavorite = true,
                        onClick = { onPlay(track, tracks) },
                        onFavoriteClick = { onToggleFavorite(track) },
                        onDeleteClick = { onDelete(track) },
                    )
                }
            }
        }
    }
}

// ===== ТАБ: ПОШУК =====

@Composable
fun SearchTab(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    results: FreeMusicApi.Result<List<Track>>?,
    searching: Boolean,
    onDownload: (Track) -> Unit,
    onPlay: (Track, List<Track>) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text(t("Пошук музики (Jamendo, Archive.org, Openverse)")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = onSearch, enabled = !searching) {
                    if (searching) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Filled.Search, contentDescription = t("Пошук"))
                }
            },
        )

        Spacer(Modifier.height(16.dp))

        when {
            searching -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            results != null -> {
                when (results) {
                    is FreeMusicApi.Result.Success -> {
                        val tracks = (results as FreeMusicApi.Result.Success<List<Track>>).value
                        if (tracks.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(t("Нічого не знайдено"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(tracks) { track ->
                                    SearchTrackItem(
                                        track = track,
                                        onPlay = { onPlay(track, tracks) },
                                        onDownload = { onDownload(track) },
                                    )
                                }
                            }
                        }
                    }
                    is FreeMusicApi.Result.Failure -> {
                        val error = (results as FreeMusicApi.Result.Failure<List<Track>>).error
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(t("Помилка пошуку: ${error.message}"), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            else -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(t("Введіть запит для пошуку музики"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ===== ТАБ: ЗАВАНТАЖЕННЯ =====

@Composable
fun DownloadsTab(
    downloads: List<dev.amps.app.music.model.DownloadTask>,
    onCancel: (String) -> Unit,
    onRetry: (dev.amps.app.music.model.DownloadTask) -> Unit,
    onClearCompleted: () -> Unit,
) {
    val active = downloads.filter { it.status in listOf(DownloadStatus.PENDING, DownloadStatus.DOWNLOADING) }
    val completed = downloads.filter { it.status == DownloadStatus.COMPLETED }
    val failed = downloads.filter { it.status == DownloadStatus.FAILED }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (downloads.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(t("Завантажень немає"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            if (active.isNotEmpty()) {
                Text(t("Активні"), style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(active) { task ->
                        DownloadItem(task = task, onCancel = { onCancel(task.id) }, onRetry = { onRetry(task) })
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            if (completed.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(t("Завершено"), style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    TextButton(onClick = onClearCompleted) { Text(t("Очистити")) }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(completed) { task ->
                        DownloadItem(task = task, onCancel = { onCancel(task.id) }, onRetry = { onRetry(task) }, showProgress = false)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            if (failed.isNotEmpty()) {
                Text(t("Помилки"), style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(failed) { task ->
                        DownloadItem(task = task, onCancel = { onCancel(task.id) }, onRetry = { onRetry(task) }, showError = true)
                    }
                }
            }
        }
    }
}

// ===== КОМПОНЕНТИ =====

@Composable
fun TrackItem(
    track: Track,
    showFavorite: Boolean,
    onClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                track.coverUrl?.let {
                    AsyncImage(model = it, contentDescription = track.title, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp))
                } ?: Icon(Icons.Filled.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (track.album.isNotBlank()) Text(track.album, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                if (track.source != TrackSource.LOCAL && track.source != TrackSource.DOWNLOADED) {
                    Text(track.sourceLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }

            if (showFavorite) {
                IconButton(onClick = onFavoriteClick) {
                    val favoriteIcon = if (track.isFavorite) Icons.Filled.Favorite else Icons.Outlined.Favorite
                    Icon(favoriteIcon, contentDescription = if (track.isFavorite) t("Видалити з улюблених") else t("Додати в улюблені"), tint = if (track.isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onDeleteClick) {
                Icon(Icons.Filled.Close, contentDescription = t("Видалити"), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
fun SearchTrackItem(
    track: Track,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                track.coverUrl?.let { AsyncImage(model = it, contentDescription = track.title, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp)) } ?: Icon(Icons.Filled.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(track.sourceLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Button(onClick = onDownload) {
                Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(4.dp))
                Text(t("Завантажити"))
            }
        }
    }
}

@Composable
fun DownloadItem(
    task: dev.amps.app.music.model.DownloadTask,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    showProgress: Boolean = true,
    showError: Boolean = false,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    task.coverUrl?.let { AsyncImage(model = it, contentDescription = task.trackTitle, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp)) } ?: Icon(Icons.Filled.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(task.trackTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(task.trackArtist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (showProgress) {
                        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                            LinearProgressIndicator(progress = task.progress, modifier = Modifier.weight(1f).padding(end = 8.dp))
                            Text("${(task.progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (showError) {
                        Text(task.error ?: t("Помилка"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                if (showProgress) {
                    TextButton(onClick = onCancel) { Text(t("Скасувати")) }
                } else if (showError) {
                    TextButton(onClick = onRetry) { Text(t("Спробувати знову")) }
                } else {
                    TextButton(onClick = {}) { Text(t("Відкрити")) }
                }
            }
        }
    }
}

@Composable
fun tabButton(title: String, icon: ImageVector, index: Int, selected: Int, onClick: () -> Unit) {
    val isSelected = index == selected
    androidx.compose.material3.TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(title, style = MaterialTheme.typography.labelSmall, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ===== ДІАЛОГ ІМПОРТУ =====

@Composable
fun ImportDialog(onDismiss: () -> Unit, onPick: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Імпортувати музику")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp)) {
                Text(t("Оберіть аудіофайли з пам'яті пристрою. Підтримувані формати: MP3, FLAC, OGG, WAV, M4A."), style = MaterialTheme.typography.bodyMedium)
                Text(t("Файли будуть скопійовані у приховану папку додатку і залишаться доступними навіть якщо ви видалите оригінали."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = { onPick(); onDismiss() }) { Text(t("Обрати файли")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Скасувати")) } },
    )
}

// ===== MINI-PLAYER / PLAYER BOTTOM SHEET =====

@Composable
fun PlayerBottomSheet(repository: MusicRepository) {
    val context = LocalContext.current
    val player = ExoPlayerProvider.currentPlayer

    var expanded by remember { mutableStateOf(false) }
    val currentTrack = remember { AtomicReference<Track?>(null) }

    // Оновлюємо поточний трек з плеєра
    LaunchedEffect(player) {
        player?.let { p ->
            snapshotFlow { p.currentMediaItem }.collect { mediaItem ->
                val metadata = mediaItem?.mediaMetadata as? MediaMetadata
                val track = metadata?.let { meta ->
                    mediaItem.mediaId?.let { id ->
                        Track(
                            id = id,
                            title = meta.title?.toString() ?: "",
                            artist = meta.artist?.toString() ?: "",
                            album = meta.albumTitle?.toString() ?: "",
                            source = TrackSource.JAMENDO,
                            durationMs = 0,
                        )
                    }
                }
                currentTrack.set(track)
            }
        }
    }

    val track = currentTrack.get()

    if (track == null || player == null) return@PlayerBottomSheet

    val isPlaying = player.playWhenReady
    val durationMs = player.duration.toInt()
    val currentPosMs = player.currentPosition.toInt()
    val progress = if (durationMs > 0) currentPosMs.toFloat() / durationMs else 0f
    val currentPos = formatTime(currentPosMs.toLong())
    val totalDur = formatTime(durationMs.toLong())

    // Mini-player
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (expanded) 300.dp else 72.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (!expanded) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    track.coverUrl?.let { AsyncImage(model = it, contentDescription = track.title, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp)) } ?: Icon(Icons.Filled.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(track.artist, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { player.playWhenReady = !isPlaying }) {
                    Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (isPlaying) t("Пауза") else t("Грати"))
                }
                IconButton(onClick = { player.seekToNext() }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = t("Наступний"))
                }
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = t("Розгорнути"))
                }
            }
        } else {
            // Розгорнутий плеєр
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    track.coverUrl?.let { AsyncImage(model = it, contentDescription = track.title, contentScale = ContentScale.Crop, modifier = Modifier.size(200.dp)) } ?: Icon(Icons.Filled.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(track.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Slider(
                    value = progress,
                    onValueChange = { player.seekTo((it * durationMs).toLong()) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text(currentPos, style = MaterialTheme.typography.labelMedium)
                    Text(totalDur, style = MaterialTheme.typography.labelMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    IconButton(onClick = { player.seekToPrevious() }) { Icon(Icons.Filled.SkipPrevious, contentDescription = t("Попередній"), modifier = Modifier.size(32.dp)) }
                    Button(onClick = { player.playWhenReady = !isPlaying }, modifier = Modifier.size(64.dp)) {
                        Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (isPlaying) t("Пауза") else t("Грати"), modifier = Modifier.size(32.dp))
                    }
                    IconButton(onClick = { player.seekToNext() }) { Icon(Icons.Filled.SkipNext, contentDescription = t("Наступний"), modifier = Modifier.size(32.dp)) }
                }
                IconButton(onClick = { expanded = false }) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = t("Згорнути"), modifier = Modifier.padding(top = 16.dp)) }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val seconds = (ms / 1000) % 60
    val minutes = (ms / 60000)
    return "%02d:%02d".format(minutes, seconds)
}

// ===== ХЕЛПЕРИ =====

private fun playQueue(track: Track, tracks: List<Track>, context: Context) {
    ExoPlayerProvider.setQueue(tracks, context, tracks.indexOfFirst { it.id == track.id })
    ExoPlayerProvider.currentPlayer?.playWhenReady = true
    AmpsMediaSessionService.start(context)
}

private fun downloadTrack(track: Track) {
    // TODO: викликати DownloadManager.enqueueDownload
}

private fun cancelDownload(id: String) {
    // TODO
}

private fun retryDownload(task: dev.amps.app.music.model.DownloadTask) {
    // TODO
}

private fun clearCompleted() {
    // TODO
}