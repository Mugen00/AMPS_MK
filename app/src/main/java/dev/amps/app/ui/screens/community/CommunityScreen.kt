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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

    // Вибір фото/відео: системні документи, обмеження розміру — при читанні.
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

        // --- стрічка ---
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp, vertical = 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                if (state.authorized) {
                    ComposeBar(
                        state = state,
                        onTextChange = viewModel::setComposeText,
                        onPickPhoto = {
                            photoPicker.launch(arrayOf("image/jpeg", "image/png", "image/webp", "image/gif"))
                        },
                        onPickVideo = { videoPicker.launch(arrayOf("video/mp4", "video/webm", "video/quicktime")) },
                        onClearAttachments = viewModel::clearAttachments,
                        onPublish = viewModel::publish,
                    )
                } else {
                    // 1.1.3: гість читає стрічку без акаунта.
                    GuestBanner(onOpenAuth = onOpenAuth)
                }
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

@Composable
private fun ComposeBar(
    state: CommunityUiState,
    onTextChange: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onPickVideo: () -> Unit,
    onClearAttachments: () -> Unit,
    onPublish: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
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
