package dev.amps.app.ui.screens.anime

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import dev.amps.app.data.model.AnimeMedia
import dev.amps.app.data.remote.AniListClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AnimeDetailState(
    val loading: Boolean = true,
    val media: AnimeMedia? = null,
    val error: String? = null,
)

/**
 * 1.3.0: повна сторінка тайтла — відкривається кліком по картці в каталозі.
 * Опис, жанри, студія, статус, епізоди, оцінка, трейлер і зовнішні сайти;
 * кнопка «Дивитись» відкриває сторінку тайтла на yummyani.me у WebView.
 */
class AnimeDetailViewModel(
    private val anilist: AniListClient,
    private val anilistId: Int,
) : ViewModel() {

    private val _state = MutableStateFlow(AnimeDetailState())
    val state: StateFlow<AnimeDetailState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = AnimeDetailState(loading = true)
            try {
                val media = anilist.media(anilistId)
                _state.value = AnimeDetailState(loading = false, media = media)
            } catch (e: Exception) {
                _state.value = AnimeDetailState(
                    loading = false,
                    error = "Не вдалося завантажити тайтл: ${e.message ?: "мережа недоступна"}",
                )
            }
        }
    }

    /** Картка для дій зі списком — зібрана з повних даних. */
    fun toCard(): dev.amps.app.data.remote.anilist.AniListClient.AnimeCard? =
        _state.value.media?.let { m ->
            dev.amps.app.data.remote.anilist.AniListClient.AnimeCard(
                anilistId = m.id,
                title = m.title.best ?: m.title.romaji ?: m.title.native ?: "Аніме ${m.id}",
                coverUrl = m.cover ?: "",
                genres = m.genres,
                episodes = m.episodes,
                averageScore = m.score,
                seasonYear = m.seasonYear,
                status = m.status,
            )
        }

    class Factory(
        private val anilist: AniListClient,
        private val anilistId: Int,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AnimeDetailViewModel(anilist, anilistId) as T
    }
}

private fun cardTitle(id: Int): String = "Аніме $id"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeDetailScreen(
    viewModel: AnimeDetailViewModel,
    animeViewModel: AnimeViewModel,
    onWatch: (String) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val media = state.media
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(media?.id) {
        // оновити свій список, щоб статус-чипи були актуальні
        if (media != null) animeViewModel.refreshMyList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        media?.title?.best ?: "Аніме",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            state.loading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.error != null -> Box(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error ?: "")
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = viewModel::load) { Text("Спробувати ще раз") }
                }
            }

            media != null -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    AsyncImage(
                        model = media.cover,
                        contentDescription = "Обкладинка",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(140.dp)
                            .height(200.dp)
                            .clip(RoundedCornerShape(14.dp)),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            media.title.best ?: media.title.romaji ?: media.title.native.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        media.title.romaji?.takeIf { it != media.title.best }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        val meta = buildList {
                            media.score?.let { add("★ $it%") }
                            media.episodes?.let { add("$it еп.") }
                            media.seasonYear?.let { add("$it") }
                            media.status?.let { add(statusLabel(it)) }
                        }.joinToString(" · ")
                        Text(
                            meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        media.studios.takeIf { it.isNotEmpty() }?.let { studios ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Студія: " + studios.joinToString(", ") { it.name },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { onWatch(media.title.best ?: media.title.romaji ?: media.title.native.orEmpty()) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Дивитись на yummyani.me")
                        }
                    }
                }

                if (media.genres.isNotEmpty()) {
                    FlowRowChips(media.genres)
                }

                media.description?.takeIf { it.isNotBlank() }?.let { desc ->
                    Text(
                        cleanupDescription(desc),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                // Мій список — ті самі статуси, що й у каталозі.
                val card = viewModel.toCard()
                if (card != null) {
                    val status = animeViewModel.myStatus(card.anilistId)
                    Text("Мій список", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AnimeViewModel.STATUS_LABELS.forEach { (code, label) ->
                            FilterChip(
                                selected = status?.status == code,
                                onClick = { animeViewModel.addToMyList(card, code) },
                                label = { Text(label) },
                            )
                        }
                    }
                    status?.let {
                        OutlinedButton(
                            onClick = { animeViewModel.removeFromMyListById(it.anilistId, it.title) },
                        ) { Text("Прибрати зі списку") }
                    }
                }

                if (media.externalLinks.isNotEmpty()) {
                    Text("Посилання", style = MaterialTheme.typography.titleSmall)
                    media.externalLinks.take(6).forEach { link ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clickable {
                                        link.url?.let { url ->
                                            runCatching {
                                                context.startActivity(
                                                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                )
                                            }
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(link.site ?: link.url ?: "", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun FlowRowChips(values: List<String>) {
    // Прості чипи у два рядки без ExperimentalLayoutApi.
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        values.chunked(3).forEach { rowValues ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowValues.forEach { value ->
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    ) {
                        Text(
                            value,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun statusLabel(status: String?): String = when (status) {
    "FINISHED" -> "Завершено"
    "RELEASING" -> "Виходить"
    "NOT_YET_RELEASED" -> "Не вийшов"
    "CANCELLED" -> "Скасовано"
    "HIATUS" -> "Пауза"
    else -> status ?: ""
}

/** Прибирає HTML-теги AniList (<br>, <i>) з опису. */
private fun cleanupDescription(raw: String): String =
    raw.replace("<br>", "\n").replace("<br/>", "\n").replace("<br />", "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&quot;", "\"").replace("&amp;", "&").replace("&#039;", "'")
