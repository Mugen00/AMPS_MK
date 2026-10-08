package dev.amps.app.ui.screens.community

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.amps.app.data.remote.backend.dto.FeedItemDto
import dev.amps.app.data.remote.backend.dto.MediaDto
import dev.amps.app.data.remote.backend.dto.PostDto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 1.1.2: Спільнота — стрічка постів з фото і відео, лайки, репости.
 *
 * Чесні межі: відео грає системний плеєр (ExoPlayer в офлайн-збірці немає);
 * медіа ходять прямо з сервера — роздача публічна, бо імена файлів
 * невгадувані UUID. Авто-видалення постів немає — на кожному пості дата
 * публікації.
 */
@Composable
fun CommunityScreen(
    viewModel: CommunityViewModel,
    baseUrl: String,
    onOpenProfile: () -> Unit,
    onOpenAuth: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Дозвіл на сповіщення (Android 13+) запитується один раз при вході на
    // екран. Відмова нічого не ламає: фонові перевірки мовчать.
    if (Build.VERSION.SDK_INT >= 33) {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { }
        LaunchedEffect(Unit) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Вибір фото/відео для поста: системні документи, обмеження розміру — при читанні.
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            readBytesCapped(context, uri, maxBytes = 10L * 1024 * 1024)
                ?.let(viewModel::attachPhoto)
        }
    }
    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            readBytesCapped(context, uri, maxBytes = 50L * 1024 * 1024)
                ?.let(viewModel::attachVideo)
        }
    }

    // ===== Сторіс (1.2.0): камера телефону =====

    // Чернетки сторіс: камера пише у приватний cache/camera через FileProvider
    // (file:// на API 24+ заборонений). Фіксовані імена: кожен дубль перезаписує.
    val storyShotDir = remember { java.io.File(context.cacheDir, "camera").apply { mkdirs() } }
    val storyPhotoFile = remember { java.io.File(storyShotDir, "story.jpg") }
    val storyVideoFile = remember { java.io.File(storyShotDir, "story.mp4") }
    // Що знімати після дозволу камери: true — відео, false — фото.
    var pendingStoryVideo by remember { mutableStateOf(false) }

    val photoStoryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok ->
        if (ok) {
            val bytes = storyPhotoFile.takeIf { it.length() in 1..10L * 1024 * 1024 }?.readBytes()
            if (bytes != null) viewModel.publishStory("photo", bytes)
            else viewModel.publishStoryFailed("Знімок порожній або завеликий (ліміт 10 МБ)")
        }
    }
    val videoStoryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakeVideo(),
    ) { thumb ->
        // TakeVideo повертає Bitmap-прев'ю (null — запис не відбувся);
        // саме відео лежить у файлі за EXTRA_OUTPUT.
        if (thumb != null) {
            // TakeVideo не вміє ліміт тривалості — чесна межа лише за розміром.
            val bytes = storyVideoFile.takeIf { it.length() in 1..50L * 1024 * 1024 }?.readBytes()
            if (bytes != null) viewModel.publishStory("video", bytes)
            else viewModel.publishStoryFailed("Відео порожнє або завелике (ліміт 50 МБ)")
        }
    }

    // Дозвіл камери: після згоди запускаємо ту зйомку, що була запрошена.
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.updates",
                if (pendingStoryVideo) storyVideoFile else storyPhotoFile,
            )
            if (pendingStoryVideo) videoStoryLauncher.launch(uri) else photoStoryLauncher.launch(uri)
        } else {
            viewModel.publishStoryFailed("Без дозволу на камеру сторіс не зняти")
        }
    }

    fun startStoryCapture(isVideo: Boolean) {
        pendingStoryVideo = isVideo
        val file = if (isVideo) storyVideoFile else storyPhotoFile
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.updates", file,
        )
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            if (isVideo) videoStoryLauncher.launch(uri) else photoStoryLauncher.launch(uri)
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    // 1.2.0: стан панелі поста й меню сторіс.
    var showCompose by remember { mutableStateOf(false) }
    var showStoryMenu by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // Вибір фото для сторіс із галереї (минути камеру).
    val storyPhotoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            readBytesCapped(context, uri, maxBytes = 10L * 1024 * 1024)
                ?.let { viewModel.publishStory("photo", it) }
                ?: viewModel.publishStoryFailed("Фото завелике (ліміт 10 МБ)")
        }
    }

    // FAB і діалоги лежать у Box, який обгортає всю колонку, — інакше
    // align(BottomEnd) не працює.
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // --- заголовок ---
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Icon(Icons.Default.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Спільнота",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = viewModel::load) {
                    Icon(Icons.Default.Refresh, contentDescription = "Оновити")
                }
                // 1.1.3: для гостя кнопка профіля веде на вхід.
                IconButton(onClick = if (state.authorized) onOpenProfile else onOpenAuth) {
                    Icon(Icons.Default.Person, contentDescription = if (state.authorized) "Мій профіль" else "Увійти")
                }
            }

            if (state.error != null) {
                Text(
                    state.error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (state.notice != null) {
                Text(
                    state.notice!!,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // 1.1.3: сторіс завантажуються разом зі стрічкою — публічні.
            LaunchedEffect(Unit) { viewModel.loadStories() }

            // 1.2.0: панель поста — за кнопкою «+» (FAB): раніше ComposeBar
            // був першим рядком стрічки і прокручувався геть, тому кнопки
            // постинга на екрані взагалі не було видно.
            if (state.authorized && showCompose) {
                ComposeBar(
                    state = state,
                    onTextChange = viewModel::setComposeText,
                    onPickPhoto = {
                        photoPicker.launch(arrayOf("image/jpeg", "image/png", "image/webp", "image/gif"))
                    },
                    onPickVideo = { videoPicker.launch(arrayOf("video/mp4", "video/webm", "video/quicktime")) },
                    onClearAttachments = viewModel::clearAttachments,
                    onPublish = {
                        viewModel.publish()
                        showCompose = false
                    },
                    onClose = { showCompose = false },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
            }

            // --- стрічка ---
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp, vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 1.2.0: рядок сторіс — аватарки з кнопкою «+» першою.
                item {
                    StoriesRow(
                        state = state,
                        baseUrl = baseUrl,
                        onAddStory = {
                            if (state.authorized) showStoryMenu = true else onOpenAuth()
                        },
                        onOpenStory = { storyId -> viewModel.openStory(storyId) },
                    )
                }
                if (state.loading && state.items.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
                items(state.items, key = { "${it.kind}-${it.post.id}-${it.at}" }) { item ->
                    FeedItemCard(
                        item = item,
                        baseUrl = baseUrl,
                        busy = item.post.id in state.busyPostIds,
                        // 1.1.3: гість, натиснувши лайк/репост, попадає на вхід.
                        onLike = {
                            if (state.authorized) viewModel.toggleLike(item.post.id) else onOpenAuth()
                        },
                        onRepost = {
                            if (state.authorized) viewModel.toggleRepost(item.post.id) else onOpenAuth()
                        },
                    )
                }
                if (state.items.isNotEmpty() && !state.endReached) {
                    item {
                        TextButton(
                            onClick = viewModel::loadMore,
                            enabled = !state.loadingMore,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (state.loadingMore) "Завантаження…" else "Показати ще")
                        }
                    }
                }
            }
        }

        // --- FAB: помітна кнопка постинга (1.2.0) ---
        androidx.compose.material3.FloatingActionButton(
            onClick = {
                if (state.authorized) showCompose = !showCompose else onOpenAuth()
            },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
        ) {
            Icon(
                if (showCompose) Icons.Default.Close else Icons.Default.Add,
                contentDescription = if (showCompose) "Сховати поле поста" else "Написати пост",
            )
        }
    }

    // --- меню «+» на сторіс: камера чи галерея ---
    if (showStoryMenu) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showStoryMenu = false },
            title = { Text("Нова сторіс") },
            text = {
                Column {
                    Text(
                        "Сторіс живе 24 години і потім зникає сама. " +
                            "Фото — до 10 МБ, відео — до 50 МБ.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (state.storyUploading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Публікація…")
                        }
                    } else {
                        TextButton(onClick = { showStoryMenu = false; startStoryCapture(false) }) {
                            Text("Зняти фото на камеру")
                        }
                        TextButton(onClick = { showStoryMenu = false; startStoryCapture(true) }) {
                            Text("Записати відео (камера)")
                        }
                        TextButton(onClick = {
                            showStoryMenu = false
                            storyPhotoPicker.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
                        }) {
                            Text("Вибрати фото з галереї")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showStoryMenu = false }) { Text("Скасувати") }
            },
        )
    }

    // --- переглядач сторіс ---
    val openId = state.openStoryId
    if (openId != null) {
        StoriesViewer(
            stories = state.stories,
            baseUrl = baseUrl,
            startId = openId,
            onClose = { viewModel.openStory(null) },
        )
    }
}

/**
 * 1.1.3: банер гостя — стрічку читають усі, а постити, лайкати й
 * репостнути можна після входу.
 */
@Composable
private fun GuestBanner(onOpenAuth: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Гостевий режим — читання",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Увійдіть, щоб постити, лайкати і робити репости.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(10.dp))
            Button(onClick = onOpenAuth) { Text("Увійти") }
        }
    }
}

/**
 * 1.2.0: рядок сторіс — кружечок «+» першим, потім по кружечку на автора.
 * Сторіс публічні, як стрічка: гість теж бачить кружечки.
 */
@Composable
private fun StoriesRow(
    state: CommunityUiState,
    baseUrl: String,
    onAddStory: () -> Unit,
    onOpenStory: (Int) -> Unit,
) {
    // Один кружечок на автора: сторіс групуються за userId, порядок —
    // за найсвіжішою сторіс автора (сервер і так віддає свіжі зверху).
    val groups = state.stories
        .groupBy { it.author.userId }
        .map { it.value }
    androidx.compose.foundation.lazy.LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        .clickable { onAddStory() },
                ) {
                    if (state.storyUploading) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Додати сторіс",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Моя",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(groups.size) { index ->
            val group = groups[index]
            val first = group.first()
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onOpenStory(first.id) },
            ) {
                StoryCircle(author = first.author, baseUrl = baseUrl)
                Spacer(Modifier.height(4.dp))
                Text(
                    first.author.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Кружечок автора сторіс: аватар або перша літера, з кільцем-виділенням. */
@Composable
private fun StoryCircle(
    author: dev.amps.app.data.remote.backend.dto.ProfileDto,
    baseUrl: String,
) {
    Box(
        modifier = Modifier
            .size(60.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .padding(2.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            if (author.avatarPath != null) {
                AsyncImage(
                    model = absoluteMediaUrl(baseUrl, author.avatarPath),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Text(
                        author.displayName.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * 1.2.0: повноекранний переглядач сторіс. Фото — 5 секунд з прогресом,
 * відео — кнопкою у системний плеєр (ExoPlayer у збірці немає, це чесно
 * зафіксовано). Тап справа — далі, зліва — назад, хрестик — вихід.
 */
@Composable
private fun StoriesViewer(
    stories: List<dev.amps.app.data.remote.backend.dto.StoryDto>,
    baseUrl: String,
    startId: Int,
    onClose: () -> Unit,
) {
    if (stories.isEmpty()) {
        onClose()
        return
    }
    val startIndex = stories.indexOfFirst { it.id == startId }.coerceAtLeast(0)
    var index by remember(startId) { mutableStateOf(startIndex) }
    val context = LocalContext.current

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = androidx.compose.ui.graphics.Color.Black,
            modifier = Modifier.fillMaxSize(),
        ) {
            val story = stories[index]
            Box(Modifier.fillMaxSize()) {
                if (story.kind == "photo") {
                    AsyncImage(
                        model = absoluteMediaUrl(baseUrl, story.url),
                        contentDescription = "Сторіс",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.align(Alignment.Center),
                    ) {
                        Icon(
                            Icons.Default.PlayCircle,
                            contentDescription = null,
                            tint = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.size(56.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Відео-сторіс — дивитися у плеєрі",
                            color = androidx.compose.ui.graphics.Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            openExternally(context, absoluteMediaUrl(baseUrl, story.url), "video/*")
                        }) {
                            Text("Відтворити")
                        }
                    }
                }

                // Тапи: ліва половина — назад, права — далі. Лежать ПІД
                // смужками прогресу і хедером (малюються раніше), щоб
                // хрестик лишався клікабельним.
                Row(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clickable {
                                if (index > 0) index -= 1 else onClose()
                            },
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clickable {
                                if (index < stories.size - 1) index += 1 else onClose()
                            },
                    )
                }

                // Смужки прогресу по всіх сторіс перегляду.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    stories.forEachIndexed { i, _ ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (i <= index) {
                                        Color.White
                                    } else {
                                        Color.White.copy(alpha = 0.3f)
                                    },
                                ),
                        )
                    }
                }

                // Хедер: хто і коли зникає.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 18.dp),
                ) {
                    StoryCircle(author = story.author, baseUrl = baseUrl)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            story.author.displayName,
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        val hoursLeft = ((story.expiresAt - System.currentTimeMillis()) / 3_600_000L).coerceAtLeast(0)
                        Text(
                            "зникне за $hoursLeft год",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Закрити",
                            tint = Color.White,
                        )
                    }
                }

                // Автопрокрутка фото: 5 секунд — і далі. Відео не тікає.
                if (story.kind == "photo") {
                    LaunchedEffect(index) {
                        kotlinx.coroutines.delay(5_000)
                        if (index < stories.size - 1) index += 1 else onClose()
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposeBar(
    state: CommunityUiState,
    onTextChange: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onPickVideo: () -> Unit,
    onClearAttachments: () -> Unit,
    onPublish: () -> Unit,
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Новий пост",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                // 1.2.0: панель відкривається FAB — хрестик її прибирає.
                if (onClose != {}) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Сховати")
                    }
                }
            }
            OutlinedTextField(
                value = state.composeText,
                onValueChange = onTextChange,
                placeholder = { Text("Що нового?") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Spacer(Modifier.height(8.dp))
            if (state.attachedPhotoBytes != null || state.attachedVideoBytes != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (state.attachedPhotoBytes != null) "Фото прикріплено" else "Відео прикріплено",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClearAttachments) {
                        Icon(Icons.Default.Close, contentDescription = "Прибрати вкладення")
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPickPhoto) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Прикріпити фото")
                }
                IconButton(onClick = onPickVideo) {
                    Icon(Icons.Default.Videocam, contentDescription = "Прикріпити відео")
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onPublish, enabled = state.canPublish) {
                    if (state.posting) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Опублікувати")
                    }
                }
            }
        }
    }
}

/** Один рядок стрічки: пост або репост із позначкою, хто поширив. */
@Composable
private fun FeedItemCard(
    item: FeedItemDto,
    baseUrl: String,
    busy: Boolean,
    onLike: () -> Unit,
    onRepost: () -> Unit,
) {
    val post = item.post
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            if (item.repostBy != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Repeat,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Репост від ${item.repostBy.displayName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AuthorAvatar(post, baseUrl)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        post.author.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "@${post.author.login} · ${formatDate(item.at)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (post.text.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(post.text, style = MaterialTheme.typography.bodyMedium)
            }
            post.media.forEach { media ->
                Spacer(Modifier.height(8.dp))
                MediaView(media, baseUrl)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onLike, enabled = !busy) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            if (post.likedByMe) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Лайк",
                            tint = if (post.likedByMe) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                Text("${post.likeCount}", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onRepost, enabled = !busy) {
                    Icon(
                        Icons.Default.Repeat,
                        contentDescription = "Репост",
                        tint = if (post.repostedByMe) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Text("${post.repostCount}", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Аватар автора: картинка або коло з першою літерою імені. */
@Composable
private fun AuthorAvatar(post: PostDto, baseUrl: String) {
    val path = post.author.avatarPath
    if (path != null) {
        AsyncImage(
            model = absoluteMediaUrl(baseUrl, path),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape),
        )
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
        ) {
            Text(
                post.author.displayName.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Вкладення поста: фото — картинка з коїл, відео — картка, що відкриває
 * системний плеєр за прямою адресою.
 */
@Composable
private fun MediaView(media: MediaDto, baseUrl: String) {
    val context = LocalContext.current
    val url = absoluteMediaUrl(baseUrl, media.url)
    if (media.kind == "photo") {
        AsyncImage(
            model = url,
            contentDescription = "Фото поста",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable { openExternally(context, url, "image/*") },
        )
    } else {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { openExternally(context, url, "video/*") },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(14.dp),
            ) {
                Icon(Icons.Default.PlayCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("Відео — відтворити", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// --- допоміжне --------------------------------------------------------------

/** Абсолютна адреса медіа: сервер віддає відносний шлях /media/… */
private fun absoluteMediaUrl(baseUrl: String, path: String): String =
    if (path.startsWith("http")) path else baseUrl.trimEnd('/') + path

/** Дата публікації: «12 березня 2025, 18:40». */
private fun formatDate(epochMillis: Long): String =
    SimpleDateFormat("d MMMM yyyy, HH:mm", Locale("uk")).format(Date(epochMillis))

/** Читає вміст документа з обмеженням розміру; понад ліміт — null. */
private fun readBytesCapped(context: android.content.Context, uri: Uri, maxBytes: Long): ByteArray? =
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = stream.readBytes()
            if (bytes.size > maxBytes) null else bytes
        }
    }.getOrNull()

/** Відкрити посилання в системному плеєрі/переглядачі; без нього — тихо. */
private fun openExternally(context: android.content.Context, url: String, mimeType: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), mimeType)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
