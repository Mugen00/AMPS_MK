package dev.amps.app.ui.screens.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.amps.app.data.remote.anilist.AniListClient
import dev.amps.app.data.remote.backend.dto.FavoriteAnimeDto
import kotlinx.coroutines.launch
import dev.amps.app.core.t

/**
 * 1.1.2: сторінка профіля. 1.4.0: реворк у стилі Instagram — шапка
 * з аватаром і статистикою, сітка власних постів мініатюрами, полиця
 * улюблених аніме (пошук з AniList) і улюблені жанри; редагування імені
 * та біо — у діалозі. Кнопка «Оновити» — те саме, що свайп зверху.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    baseUrl: String,
    onBack: () -> Unit,
    onOpenAnime: (Int) -> Unit = {},
    anilist: AniListClient? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showEditDialog by remember { mutableStateOf(false) }
    var showGenreDialog by remember { mutableStateOf(false) }
    var showAnimeSearch by remember { mutableStateOf(false) }
    // 1.6.2: діалог лідерборду рівнів.
    var showLeaderboard by remember { mutableStateOf(false) }

    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            readBytesCapped(context, uri, maxBytes = 10L * 1024 * 1024)?.let(viewModel::uploadAvatar)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Text(
                "Мій профіль",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (state.refreshing) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
            } else {
                IconButton(onClick = viewModel::refresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Оновити")
                }
            }
        }

        if (state.loading) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // --- Instagram-шапка: аватар + ім'я/логін/рівень ---
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    val path = state.avatarPath
                    if (path != null) {
                        AsyncImage(
                            model = if (path.startsWith("http")) path else baseUrl.trimEnd('/') + path,
                            contentDescription = "Аватар",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(84.dp)
                                .clip(CircleShape)
                                .clickable {
                                    avatarPicker.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
                                },
                        )
                    } else {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(96.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                                .clickable { avatarPicker.launch(arrayOf("image/jpeg", "image/png", "image/webp")) },
                        ) {
                            Icon(
                                Icons.Default.AddAPhoto,
                                contentDescription = "Додати аватар",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    if (state.uploadingAvatar) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        state.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "@${state.login}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Рівень ${state.level} · ${dev.amps.app.ui.theme.TitleCatalog.titleFor(state.level)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                TextButton(onClick = { showEditDialog = true }) { Text(t("Редагувати")) }
            }

            Spacer(Modifier.height(14.dp))

            // --- статистика як в Instagram ---
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ProfileStat(state.postsCount, "постів")
                ProfileStat(state.followersCount, "підписників")
                ProfileStat(state.watchingCount, "дивлюсь")
                ProfileStat(state.level, "рівень")
            }
            Spacer(Modifier.height(10.dp))

            // --- 1.6.2: лідерборд XP ---
            OutlinedButton(
                onClick = {
                    viewModel.loadLeaderboard()
                    showLeaderboard = true
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("🏆 " + t("Лідерборд рівнів"))
            }
            Spacer(Modifier.height(6.dp))

            state.watchingTitle?.let { title ->
                Text(
                    "📺 Тепер дивлюсь: $title",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
            }

            if (state.bio.isNotBlank()) {
                Text(state.bio, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(14.dp))
            }

            if (state.error != null) {
                Text(state.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }
            if (state.notice != null) {
                Text(state.notice!!, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }

            // --- улюблені жанри (1.4.0) ---
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Улюблені жанри", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { showGenreDialog = true }) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (state.favoriteGenres.isEmpty()) "Додати" else "Змінити")
                }
            }
            if (state.favoriteGenres.isEmpty()) {
                Text(
                    "Познач жанри, які любиш — вони видно всім у профілі.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                GenreChips(state.favoriteGenres, onRemove = viewModel::toggleFavoriteGenre)
            }

            Spacer(Modifier.height(14.dp))

            // --- полиця улюблених аніме (1.4.0) ---
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Улюблене аніме", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { showAnimeSearch = true }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(t("Додати"))
                }
            }
            if (state.favoriteAnime.isEmpty()) {
                Text(
                    "Полиця улюблених тайтлів з AniList — до 12 штук, видно всім.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(state.favoriteAnime.size) { index ->
                        val fav = state.favoriteAnime[index]
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(86.dp),
                        ) {
                            AsyncImage(
                                model = fav.cover,
                                contentDescription = fav.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.7f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onOpenAnime(fav.anilistId) },
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                fav.title,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "Прибрати",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.clickable { viewModel.removeFavoriteAnime(fav.anilistId) },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // --- сітка постів як в Instagram ---
            Text("Пости", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (state.gridLoading && state.postsGrid.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(120.dp), Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            } else if (state.postsGrid.isEmpty()) {
                Text(
                    "Ще немає постів — перший з'явиться тут мініатюрою.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                PostsGrid(posts = state.postsGrid, baseUrl = baseUrl)
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // --- діалоги ---

    if (showEditDialog) {
        EditProfileDialog(
            name = state.displayName,
            bio = state.bio,
            saving = state.saving,
            onName = viewModel::setDisplayName,
            onBio = viewModel::setBio,
            onSave = {
                viewModel.save()
                showEditDialog = false
            },
            onDismiss = { showEditDialog = false },
        )
    }

    if (showGenreDialog) {
        GenrePickerDialog(
            selected = state.favoriteGenres,
            onToggle = viewModel::toggleFavoriteGenre,
            onDismiss = { showGenreDialog = false },
        )
    }

    if (showAnimeSearch) {
        AnimeSearchDialog(
            anilist = anilist,
            onPick = { item -> viewModel.addFavoriteAnime(item) },
            onDismiss = { showAnimeSearch = false },
        )
    }

    // --- 1.6.2: діалог лідерборду рівнів ---
    if (showLeaderboard) {
        AlertDialog(
            onDismissRequest = { showLeaderboard = false },
            confirmButton = {
                TextButton(onClick = { showLeaderboard = false }) { Text(t("Закрити")) }
            },
            title = { Text("🏆 " + t("Лідерборд рівнів")) },
            text = {
                Column {
                    if (state.leaderboardLoading) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    } else if (state.leaderboard.isEmpty()) {
                        Text(
                            t("Поки ніхто не набрав XP — почни першим 🚀"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        // Моє місце — жирним рядком зверху.
                        if (state.myRank > 0) {
                            Text(
                                "${t("Моє місце")}: #${state.myRank}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp),
                        ) {
                            itemsIndexed(state.leaderboard) { index, entry ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                ) {
                                    Text(
                                        "${index + 1}.",
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.width(30.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        if (entry.displayName.isNotBlank()) entry.displayName else "@${entry.userId}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "${entry.xp} XP · ${t("рів.")}${entry.level}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}

/** Лічильник у стилі Instagram: число жирним, підпис дрібним снизу. */
@Composable
private fun ProfileStat(value: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "$value",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Чипси жанрів: тап по хрестику прибирає жанр з улюблених. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenreChips(genres: List<String>, onRemove: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        genres.forEach { genre ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(genre, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Прибрати $genre",
                        modifier = Modifier
                            .size(14.dp)
                            .clickable { onRemove(genre) },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Сітка постів 3-в-ряд: перший файл поста мініатюрою, як в Instagram.
 * Пост без медіа — плитка з початком тексту. Тап наявного поста
 * (1.4.1: деталі поста з профіля) поки нікуди не веде.
 */
@Composable
private fun PostsGrid(posts: List<dev.amps.app.data.remote.backend.dto.PostDto>, baseUrl: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        posts.chunked(3).forEach { rowPosts ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                for (index in 0 until 3) {
                    val post = rowPosts.getOrNull(index)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        val photo = post?.media?.firstOrNull { it.kind == "photo" }
                        if (photo != null) {
                            AsyncImage(
                                model = baseUrl.trimEnd('/') + photo.url,
                                contentDescription = post?.text?.take(40),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else if (post != null) {
                            Text(
                                post.text,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Діалог редагування імені й біо. */
@Composable
private fun EditProfileDialog(
    name: String,
    bio: String,
    saving: Boolean,
    onName: (String) -> Unit,
    onBio: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Редагувати профіль")) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = onName,
                    label = { Text(t("Ім'я")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = bio,
                    onValueChange = onBio,
                    label = { Text(t("Про себе (до 280)")) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = name.isNotBlank() && !saving) { Text(t("Зберегти")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("Скасувати")) }
        },
    )
}

/**
 * Діалог вибору улюблених жанрів: сітка чипсів із каталогу аніме;
 * тап перемикає і зберігає одразу (ліміт 8 тримається на сервері).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenrePickerDialog(
    selected: List<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Улюблені жанри")) },
        text = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                dev.amps.app.ui.screens.anime.AnimeViewModel.GENRES.forEach { genre ->
                    androidx.compose.material3.FilterChip(
                        selected = selected.contains(genre),
                        onClick = { onToggle(genre) },
                        label = { Text(genre) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(t("Готово")) }
        },
    )
}

/**
 * Пошук тайтла в AniList для полиці улюблених. Порожній клієнт
 * (AniList недоступний) — діалог пояснює це замість пустого списку.
 */
@Composable
private fun AnimeSearchDialog(
    anilist: dev.amps.app.data.remote.anilist.AniListClient?,
    onPick: (FavoriteAnimeDto) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<AniListClient.AnimeCard>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Додати улюблене аніме")) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(t("Назва аніме (ромаджі/англійською)")) },
                    singleLine = true,
                    trailingIcon = {
                        androidx.compose.material3.IconButton(
                            onClick = {
                                if (query.isNotBlank() && !loading) {
                                    loading = true
                                    searched = true
                                    scope.launch {
                                        val found = anilist?.search(query.trim())
                                        results = found.orEmpty()
                                        loading = false
                                    }
                                }
                            },
                        ) {
                            androidx.compose.material3.Icon(
                                Icons.Default.Search,
                                contentDescription = "Шукати",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                if (loading) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                } else if (results.isEmpty()) {
                    Text(
                        if (searched) "Нічого не знайдено" else "Пошук у каталозі AniList",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        results.take(8).forEach { card ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onPick(
                                            FavoriteAnimeDto(
                                                anilistId = card.anilistId,
                                                title = card.title,
                                                cover = card.coverUrl,
                                            )
                                        )
                                    },
                            ) {
                                AsyncImage(
                                    model = card.coverUrl,
                                    contentDescription = card.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(width = 44.dp, height = 60.dp)
                                        .clip(RoundedCornerShape(6.dp)),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        card.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    card.seasonYear?.let { year ->
                                        Text(
                                            "$year",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(t("Закрити")) }
        },
    )
}

/** Читає вміст документа з обмеженням розміру; понад ліміт — null. */
private fun readBytesCapped(context: android.content.Context, uri: android.net.Uri, maxBytes: Long): ByteArray? =
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = stream.readBytes()
            if (bytes.size > maxBytes) null else bytes
        }
    }.getOrNull()
