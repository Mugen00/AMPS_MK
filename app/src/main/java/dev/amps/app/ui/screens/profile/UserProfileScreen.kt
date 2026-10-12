package dev.amps.app.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.amps.app.data.remote.backend.dto.FeedItemDto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dev.amps.app.core.t

/**
 * 1.2.5: екран чужого профіля — аватар/ім'я/біо, кнопка підписки,
 * лічильники (пости, підписники) і два таби: пости та репости.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    viewModel: UserProfileViewModel,
    baseUrl: String,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    androidx.compose.material3.Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(state.profile?.login?.let { "@$it" } ?: "Профіль") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            when {
                state.loading -> Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.error != null -> Column(Modifier.padding(20.dp)) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.Button(onClick = viewModel::load) { Text(t("Спробувати ще")) }
                }
                state.profile != null -> ProfileHeader(state, baseUrl, viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ProfileHeader(state: UserProfileUiState, baseUrl: String, viewModel: UserProfileViewModel) {
    val profile = state.profile ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = profile.avatarPath?.let { "$baseUrl$it" },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(profile.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "@${profile.login}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = viewModel::toggleFollow, enabled = !state.followBusy) {
                Text(if (state.isFollowing) "Ви підписані" else "+ Підписатись")
            }
        }
        profile.bio?.takeIf { it.isNotBlank() }?.let { bio ->
            Spacer(Modifier.height(8.dp))
            Text(bio, style = MaterialTheme.typography.bodyMedium)
        }
        // 1.4.0: улюблені жанри й аніме — read-only у чужому профілі.
        if (profile.favoriteGenres.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Улюблені жанри", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                profile.favoriteGenres.forEach { genre ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            genre,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
        if (profile.favoriteAnime.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Улюблене аніме", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(profile.favoriteAnime.size) { idx ->
                    val fav = profile.favoriteAnime[idx]
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(80.dp),
                    ) {
                        AsyncImage(
                            model = fav.cover,
                            contentDescription = fav.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.7f)
                                .clip(MaterialTheme.shapes.small),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            fav.title,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatBox("${state.postsCount}", "Пости", Modifier.weight(1f))
            StatBox("${state.followers}", "Підписників", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.tab == "posts",
                onClick = { viewModel.loadTab("posts") },
                label = { Text(t("Пости")) },
            )
            FilterChip(
                selected = state.tab == "reposts",
                onClick = { viewModel.loadTab("reposts") },
                label = { Text(t("Репости")) },
            )
        }
        Spacer(Modifier.height(10.dp))
        when {
            state.tabLoading -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                CircularProgressIndicator()
            }
            state.tabError != null -> Text(
                state.tabError ?: "",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 10.dp),
            )
            state.items.isEmpty() -> Text(
                if (state.tab == "reposts") "Ще немає репостів" else "Постів ще немає",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            else -> state.items.forEach { item ->
                UserPostCard(item, baseUrl)
                Spacer(Modifier.height(10.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun StatBox(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 10.dp),
        ) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Картка поста/репоста на екрані профіля — спрощена, без дій. */
@Composable
private fun UserPostCard(item: FeedItemDto, baseUrl: String) {
    val post = item.post
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            if (item.repostBy != null) {
                Text(
                    "Репост від ${item.repostBy.displayName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(
                        post.author.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "@${post.author.login} · ${SimpleDateFormat("d MMM yyyy", Locale("uk")).format(Date(post.createdAt))}",
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
                AsyncImage(
                    model = if (media.url.startsWith("http")) media.url else "$baseUrl${media.url}",
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(MaterialTheme.shapes.small),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "❤ ${post.likeCount} · 🔁 ${post.repostCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
