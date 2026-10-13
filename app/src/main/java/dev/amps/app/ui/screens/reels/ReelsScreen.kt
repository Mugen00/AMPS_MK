package dev.amps.app.ui.screens.reels

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.amps.app.core.t
import dev.amps.app.data.remote.backend.dto.ReelDto

/**
 * 1.6.0: Рілси — повноекранна вертикальна лента відео 9:16
 * (свайп вгору/вниз, як у TikTok/Reels). Відео грає тільки на активній
 * сторінці; сусідні показують обкладинку з назвою автора. Лайк — серце
 * праворуч, кнопка видалення — для своїх, FAB — публікація з галереї.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReelsScreen(
    viewModel: ReelsViewModel,
    baseUrl: String,
    onOpenAuth: () -> Unit,
    onOpenUserProfile: (Int) -> Unit,
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
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }
    LaunchedEffect(Unit) { viewModel.load() }

    // Публікація: відео з галереї (≤50 МБ) + підпис (діалог).
    var pendingVideoBytes by remember { mutableStateOf<ByteArray?>(null) }
    var showCaptionDialog by remember { mutableStateOf(false) }
    var confirmDeleteReelId by remember { mutableStateOf<Int?>(null) }

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val all = stream.readBytes()
                    if (all.size > 50L * 1024 * 1024) null else all
                }
            }.getOrNull()
            if (bytes != null) {
                pendingVideoBytes = bytes
                showCaptionDialog = true
            } else {
                // Відео більші за 50 МБ не приймає сервер — кажемо чесно.
            }
        }
    }

    Scaffold(
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (state.authorized) videoPicker.launch(arrayOf("video/mp4", "video/webm", "video/quicktime"))
                    else onOpenAuth()
                },
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(Icons.Default.Add, contentDescription = t("Опублікувати рилс"), tint = MaterialTheme.colorScheme.onPrimary)
            }
        },
    ) { padding ->
        if (state.loading && state.reels.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = Color.White) }
            return@Scaffold
        }
        if (state.reels.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🎬", style = MaterialTheme.typography.displayLarge)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        t("Рілсів ще немає — зніми вертикальне відео і стань першим! ✨"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(16.dp))
                    if (!state.authorized) {
                        Button(onClick = onOpenAuth) { Text(t("Увійти в акаунт")) }
                    }
                }
            }
            return@Scaffold
        }

        val pagerState = rememberPagerState(pageCount = { state.reels.size })

        // Свайп до останньої сторінки → підвантажити ще.
        LaunchedEffect(pagerState, state.reels.size) {
            snapshotFlow { pagerState.currentPage }.collect { page ->
                if (page >= state.reels.size - 2) viewModel.loadMore()
            }
        }

        VerticalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) { page ->
            val reel = state.reels.getOrNull(page)
            if (reel != null) {
                ReelPage(
                    reel = reel,
                    baseUrl = baseUrl,
                    isActive = pagerState.currentPage == page,
                    likedByMe = reel.likedByMe,
                    isMine = state.myUserId > 0 && reel.author.userId == state.myUserId,
                    busy = state.busyReelIds.contains(reel.id),
                    onLike = { viewModel.toggleLike(reel.id) },
                    onDelete = { confirmDeleteReelId = reel.id },
                    onOpenUser = onOpenUserProfile,
                )
            }
        }
    }

    // Діалог підпису до рилса.
    if (showCaptionDialog && pendingVideoBytes != null) {
        AlertDialog(
            onDismissRequest = {
                showCaptionDialog = false
                pendingVideoBytes = null
            },
            title = { Text(t("Новий рилс 🎬")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(t("Вертикальне відео 9:16 — з’явиться в ленті рілсів."))
                    OutlinedTextField(
                        value = state.publishCaption,
                        onValueChange = viewModel::setPublishCaption,
                        label = { Text(t("Підпис (необов'язково)")) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                    if (state.publishing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(t("Завантажуємо…"))
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val bytes = pendingVideoBytes
                        if (bytes != null) {
                            viewModel.publish(bytes, state.publishCaption) {
                                showCaptionDialog = false
                                pendingVideoBytes = null
                            }
                        }
                    },
                    enabled = !state.publishing,
                ) { Text(t("Опублікувати (+20 XP)")) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showCaptionDialog = false
                        pendingVideoBytes = null
                    },
                ) { Text(t("Скасувати")) }
            },
        )
    }

    if (confirmDeleteReelId != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteReelId = null },
            title = { Text(t("Видалити рилс?")) },
            text = { Text(t("Відео буде видалено назавжди.")) },
            confirmButton = {
                TextButton(onClick = {
                    val id = confirmDeleteReelId
                    confirmDeleteReelId = null
                    if (id != null) viewModel.deleteReel(id)
                }) { Text(t("Видалити")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteReelId = null }) { Text(t("Скасувати")) }
            },
        )
    }
}

/**
 * Одна сторінка рилса: відео на весь екран (грає лише активна),
 * автор і підпис знизу, серце-лайк праворуч, видалення — своїм.
 */
@Composable
private fun ReelPage(
    reel: ReelDto,
    baseUrl: String,
    isActive: Boolean,
    likedByMe: Boolean,
    isMine: Boolean,
    busy: Boolean,
    onLike: () -> Unit,
    onDelete: () -> Unit,
    onOpenUser: (Int) -> Unit,
) {
    val videoUrl = if (reel.videoUrl.startsWith("http")) reel.videoUrl else baseUrl.trimEnd('/') + reel.videoUrl
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (isActive) {
            dev.amps.app.ui.components.InlineVideo(
                url = videoUrl,
                modifier = Modifier.fillMaxSize(),
                mutedByDefault = true,
                loop = true,
            )
        } else {
            // Неактивна сторінка: чорний фон — відео стартує, коли сторінка активна.
            Box(Modifier.fillMaxSize().background(Color.Black))
        }

        // Кнопка видалення — тільки свій рилс.
        if (isMine) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 60.dp, end = 8.dp),
            ) {
                Icon(Icons.Default.Delete, contentDescription = t("Видалити рилс"), tint = Color.White)
            }
        }

        // Нижній блок: автор + підпис.
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 16.dp, bottom = 90.dp, end = 90.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onOpenUser(reel.author.userId) },
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (reel.author.avatarPath != null) {
                        AsyncImage(
                            model = if (reel.author.avatarPath.startsWith("http")) reel.author.avatarPath
                            else baseUrl.trimEnd('/') + reel.author.avatarPath,
                            contentDescription = reel.author.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(
                            reel.author.displayName.take(1).uppercase(),
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    reel.author.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (reel.caption.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    reel.caption,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Права колонка: лайк.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 90.dp),
        ) {
            IconButton(onClick = onLike, enabled = !busy) {
                Icon(
                    imageVector = if (likedByMe) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (likedByMe) t("Зняти лайк") else t("Лайк"),
                    tint = if (likedByMe) Color(0xFFFF4081) else Color.White,
                    modifier = Modifier.size(34.dp),
                )
            }
            Text(
                reel.likeCount.toString(),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
