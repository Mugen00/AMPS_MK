package dev.amps.app.ui.screens.anime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.amps.app.data.remote.anilist.AniListClient
import dev.amps.app.data.remote.backend.dto.AnimeListDto

/**
 * 1.2.2: екран каталогу аніме. Вкладки «Топ / Сезон / Жанри / Моє аніме»
 * і пошук; тап на картку — діалог «у мій список»: статуси
 * (Дивлюсь / Планую / Переглянуто) і нагадування про нові серії.
 */
/**
 * 1.2.2: екран каталогу аніме. Вкладки «Топ / Сезон / Жанри / Моє аніме»
 * і пошук. 1.3.0: тап на картку відкриває повну сторінку тайтла
 * (опис, жанри, студія, «Дивитись»); статуси списку — на сторінці тайтла.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeScreen(
    viewModel: AnimeViewModel,
    onBack: (() -> Unit)? = null,
    onOpenDetails: (Int) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
                Text(
                    "Аніме",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Топ") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Сезон") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Жанри") })
                Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("Моє") })
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Пошук аніме") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { viewModel.search(query) }) {
                            Icon(Icons.Default.Search, contentDescription = "Пошук")
                        }
                    },
                )
            }
            if (tab == 2) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listItems(AnimeViewModel.GENRES) { g ->
                        AssistChip(
                            onClick = { viewModel.loadGenre(g) },
                            label = { Text(g) },
                        )
                    }
                }
            }

            // Перемикання вкладок підтягує відповідний набір карток.
            LaunchedEffect(tab) {
                when (tab) {
                    0 -> viewModel.loadTop()
                    1 -> viewModel.loadSeason()
                }
            }

            when {
                state.loading -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                state.error != null -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { Text(state.error!!, color = MaterialTheme.colorScheme.error) }

                tab == 3 -> MyAnimeList(state, viewModel)
                else -> CatalogGrid(state.cards, viewModel, onOpenDetails)
            }
        }
    }

    selected?.let { card ->
        AddAnimeDialog(
            card = card,
            existing = viewModel.myStatus(card.anilistId),
            onStatus = { status ->
                viewModel.addToMyList(card, status)
                viewModel.openCard(null)
            },
            onRemove = {
                viewModel.removeFromMyList(card)
                viewModel.openCard(null)
            },
            onReminder = { on -> viewModel.setReminder(card, on) },
            onDismiss = { viewModel.openCard(null) },
        )
    }
}

/** Сітка карток 2 колонки: обкладинка, назва, рік/оцінка. */
@Composable
private fun CatalogGrid(
    cards: List<AniListClient.AnimeCard>,
    viewModel: AnimeViewModel,
    onOpenDetails: (Int) -> Unit,
) {
    if (cards.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Нічого не знайдено")
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        gridItems(cards, key = { it.anilistId }) { card ->
            // 1.2.2: статус свого списку рахується тут і передається далі.
            // 1.3.0: тап — сторінка тайтла; довгий тап — діалог «у мій список».
            AnimeCardView(
                card = card,
                statusLabel = viewModel.myStatus(card.anilistId)?.let {
                    AnimeViewModel.STATUS_LABELS[it.status] ?: it.status
                },
                onClick = { onOpenDetails(card.anilistId) },
                onLongClick = { viewModel.openCard(card) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun AnimeCardView(
    card: AniListClient.AnimeCard,
    statusLabel: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    Card(modifier = Modifier
        .fillMaxWidth()
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Column {
            AsyncImage(
                model = card.coverUrl,
                contentDescription = card.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.7f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
            )
            Column(Modifier.padding(8.dp)) {
                Text(
                    card.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val extra = listOfNotNull(
                    card.seasonYear?.toString(),
                    card.episodes?.let { "$it еп." },
                    card.averageScore?.let { "★ $it%" },
                ).joinToString(" · ")
                if (extra.isNotBlank()) {
                    Text(
                        extra,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (statusLabel != null) {
                    Text(
                        statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** Вкладка «Моє аніме»: свій список, групування за статусами. */
@Composable
private fun MyAnimeList(
    state: AnimeViewModel.UiState,
    viewModel: AnimeViewModel,
) {
    if (state.myList.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (state.authorized) "Список порожній — додайте тайтли з каталогу" else "Увійдіть у профіль, щоб вести свій список")
        }
        return
    }
    val sections = listOf("watching" to "Дивлюсь", "planned" to "Планую", "watched" to "Переглянуто")
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sections.forEach { (statusKey, label) ->
            val group = state.myList.filter { it.status == statusKey }
            if (group.isNotEmpty()) {
                item {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                }
                listItems(group, key = { it.anilistId }) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = item.coverUrl,
                            contentDescription = item.title,
                            modifier = Modifier
                                .width(52.dp)
                                .height(70.dp)
                                .clip(RoundedCornerShape(6.dp)),
                        )
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(horizontal = 10.dp),
                        ) {
                            Text(
                                item.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                (if (item.reminder) "Нагадування: увімк" else "Нагадування: вимк") +
                                    " · " + (AnimeViewModel.STATUS_LABELS[item.status] ?: item.status),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        IconButton(onClick = {
                            viewModel.removeFromMyListById(item.anilistId, item.title)
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Видалити")
                        }
                    }
                }
            }
        }
    }
}

/** Діалог «у мій список»: статус + нагадування + видалення. */
@Composable
private fun AddAnimeDialog(
    card: AniListClient.AnimeCard,
    existing: AnimeListDto?,
    onStatus: (String) -> Unit,
    onRemove: () -> Unit,
    onReminder: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var reminder by remember(card.anilistId) { mutableStateOf(existing?.reminder ?: true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(card.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text(
                    listOfNotNull(
                        card.seasonYear?.toString(),
                        card.episodes?.let { "$it еп." },
                        card.averageScore?.let { "★ $it%" },
                        card.status?.let { "статус: $it" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(8.dp))
                AnimeViewModel.STATUS_LABELS.forEach { (code, label) ->
                    TextButton(onClick = { onStatus(code) }) { Text(label) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = reminder, onCheckedChange = { onReminder(it) })
                    Text("Нагадувати про нові серії")
                }
                if (existing != null) {
                    TextButton(onClick = onRemove) { Text("Прибрати зі списку") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрити") } },
    )
}
