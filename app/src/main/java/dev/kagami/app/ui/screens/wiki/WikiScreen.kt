package dev.kagami.app.ui.screens.wiki

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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kagami.app.data.model.AnimeCharacter
import dev.kagami.app.data.model.AnimeMedia
import dev.kagami.app.data.model.AnimeWikiPage
import dev.kagami.app.data.model.FrameHit
import dev.kagami.app.ui.components.ConfidenceDial
import dev.kagami.app.ui.components.EmptyState
import dev.kagami.app.ui.components.InfoChip
import dev.kagami.app.ui.components.NetworkImage
import dev.kagami.app.ui.components.SectionCard
import dev.kagami.app.ui.components.StatRow
import dev.kagami.app.ui.theme.KagamiColors
import dev.kagami.app.util.htmlToAnnotatedString
import dev.kagami.app.util.openUrl

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WikiScreen(
    viewModel: WikiViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val page = state.page

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = page?.media?.title?.best ?: "Вики",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            page == null -> EmptyState(
                icon = Icons.Default.Book,
                title = "Страница не найдена",
                message = state.error ?: "Сначала найдите кадр, затем откроется вики-страница.",
                modifier = Modifier.padding(padding),
            )

            else -> LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item { HeroBlock(page) }

                page.frame?.let { hit ->
                    item { FrameFoundCard(hit, page.searchedImageSha256) { hit.sceneUrl?.let { openUrl(context, it) } } }
                }

                if (page.candidates.isNotEmpty()) {
                    item {
                        CharacterCard(
                            guess = page.guess,
                            candidates = page.candidates,
                            onSelect = viewModel::selectCharacter,
                        )
                    }
                }

                item {
                    SimilarImagesBlock(
                        page = page,
                        onOpen = { openUrl(context, it) },
                    )
                }

                item { SeriesCard(page.media) }
                item { FactsCard(page.media) }

                if (page.media.relations.isNotEmpty()) {
                    item { RelationsCard(page.media, onOpen = { openUrl(context, it) }) }
                }
                if (page.media.recommendations.isNotEmpty()) {
                    item { RecommendationsCard(page.media, onOpen = { openUrl(context, it) }) }
                }
                if (page.sources.isNotEmpty()) {
                    item { SourcesCard(page, onOpen = { openUrl(context, it) }) }
                }

                page.rawEngineText?.let { raw ->
                    item {
                        SectionCard(
                            title = "Ответ движка",
                            icon = Icons.Default.AutoAwesome,
                        ) {
                            Text(
                                text = if (state.rawExpanded) raw else raw.take(220) + if (raw.length > 220) "…" else "",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = viewModel::toggleRaw) {
                                Text(if (state.rawExpanded) "Свернуть" else "Показать целиком")
                                Icon(
                                    if (state.rawExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroBlock(page: AnimeWikiPage) {
    val media = page.media
    Box(
        Modifier
            .fillMaxWidth()
            .height(340.dp)
            .clip(RoundedCornerShape(26.dp)),
    ) {
        NetworkImage(
            url = media.banner ?: media.cover,
            contentDescription = media.title.best,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.25f), Color.Transparent, Color.Black.copy(alpha = 0.88f))
                    )
                )
        )
        page.frame?.similarityPercent?.let { percent ->
            Box(Modifier.padding(16.dp)) {
                ConfidenceDial(percent = percent)
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(18.dp)
        ) {
            Text(
                text = media.title.english ?: media.title.romaji.orEmpty(),
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
            )
            media.title.native?.takeIf { it.isNotBlank() && it != media.title.english }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.82f),
                )
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                media.startDate?.shortLabel?.let { InfoChip(it, color = Color.White) }
                media.format?.let { InfoChip(it, color = Color.White) }
                media.episodes?.let { InfoChip("$it серий", color = Color.White) }
                media.status?.let { InfoChip(it, color = KagamiColors.cyan) }
                if (media.isAdult) InfoChip("18+", color = KagamiColors.rose)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FrameFoundCard(hit: FrameHit, sha256: String?, onOpenScene: () -> Unit) {
    SectionCard(
        title = "Кадр найден",
        icon = Icons.Default.PlayCircle,
        trailing = { InfoChip(hit.engine, color = KagamiColors.cyan) },
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            hit.episode?.let { InfoChip("Серия $it", color = KagamiColors.amber) }
            hit.timestampLabel?.let { InfoChip("Таймкод $it", color = KagamiColors.amber) }
            hit.similarityPercent?.let { InfoChip("Сходство $it%", color = KagamiColors.cyan) }
        }
        Spacer(Modifier.height(12.dp))
        if (hit.sceneUrl != null) {
            OutlinedButton(onClick = onOpenScene, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Открыть найденную сцену")
            }
        }
        sha256?.let {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "SHA-256 кадра: ${it.take(24)}…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CharacterCard(
    guess: dev.kagami.app.data.model.CharacterGuess?,
    candidates: List<AnimeCharacter>,
    onSelect: (AnimeCharacter) -> Unit,
) {
    val character = guess?.character ?: candidates.firstOrNull()
    SectionCard(
        title = "Персонаж в кадре",
        icon = Icons.Default.Face,
        trailing = {
            guess?.let { InfoChip("${(it.score * 100).toInt()}%", color = KagamiColors.cyan) }
        },
    ) {
        if (character == null) {
            Text(
                "Персонажи не определены.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        Row(Modifier.fillMaxWidth()) {
            NetworkImage(
                url = character.image,
                contentDescription = character.displayName,
                modifier = Modifier
                    .size(width = 108.dp, height = 150.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = character.displayName.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                )
                character.name.native?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    InfoChip(if (character.role == "MAIN") "Главный" else "Второстепенный", color = KagamiColors.violet)
                    character.age?.let { InfoChip(it, color = KagamiColors.amber) }
                    character.gender?.let { InfoChip(it.lowercase().replaceFirstChar(Char::uppercase), color = KagamiColors.amber) }
                    character.bloodType?.let { InfoChip("Группа $it", color = KagamiColors.rose) }
                }
            }
        }

        if (guess != null && guess.reason.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Почему: ${guess.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = KagamiColors.cyan,
            )
        }

        character.description?.let { description ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = htmlToAnnotatedString(description)?.text.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (character.appearances.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("Появления", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(character.appearances.filter { it.cover != null }) { media ->
                    Column(Modifier.width(86.dp)) {
                        NetworkImage(
                            url = media.cover,
                            contentDescription = media.title.best,
                            modifier = Modifier
                                .size(86.dp)
                                .clip(RoundedCornerShape(12.dp)),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = media.title.best.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (candidates.size > 1) {
            Spacer(Modifier.height(14.dp))
            Text("Другие персонажи серии", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(candidates.filter { it.id != character.id }) { candidate ->
                    Column(
                        modifier = Modifier
                            .width(74.dp)
                            .clickable { onSelect(candidate) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        NetworkImage(
                            url = candidate.image,
                            contentDescription = candidate.displayName,
                            modifier = Modifier
                                .size(74.dp)
                                .clip(RoundedCornerShape(50)),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = candidate.displayName.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SimilarImagesBlock(page: AnimeWikiPage, onOpen: (String) -> Unit) {
    val items = buildList {
        page.frame?.previewImageUrl?.let { add(Triple(it, "Найденный кадр", "trace.moe")) }
        page.similarArt.forEach { art ->
            art.url?.let { add(Triple(it, art.source, "SauceNAO · ${art.similarity ?: 0}%")) }
        }
        page.guess?.character?.let { character ->
            character.image?.let { add(Triple(it, "Портрет персонажа", "AniList")) }
        }
    }
    if (items.isEmpty()) return

    SectionCard(title = "Похожие изображения", icon = Icons.Default.Image) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items) { (url, caption, badge) ->
                Column(Modifier.width(150.dp)) {
                    Box(
                        Modifier
                            .size(150.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onOpen(url) }
                    ) {
                        NetworkImage(url, caption, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeriesCard(media: AnimeMedia) {
    SectionCard(title = "О произведении", icon = Icons.Default.Book) {
        val description = htmlToAnnotatedString(media.description)
        if (description != null) {
            Text(description, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp))
        }
        if (media.genres.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                media.genres.forEach { InfoChip(it, color = MaterialTheme.colorScheme.primary) }
            }
        }
        if (media.synonyms.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Синонимы: ${media.synonyms.take(4).joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FactsCard(media: AnimeMedia) {
    SectionCard(title = "Факты", icon = Icons.Default.Category) {
        StatRow("Формат", media.format?.lowercase()?.replaceFirstChar(Char::uppercase) ?: "—")
        StatRow("Серий", media.episodes?.toString() ?: "—")
        StatRow("Длительность", media.duration?.let { "$it мин" } ?: "—")
        StatRow("Статус", media.status?.lowercase()?.replaceFirstChar(Char::uppercase) ?: "—")
        StatRow("Дата выхода", media.startDate?.shortLabel ?: "—")
        StatRow("Студия", media.studios.firstOrNull { it.isMain }?.name ?: media.studios.firstOrNull()?.name ?: "—")
        media.score?.let { StatRow("Оценка", "$it / 100", valueColor = KagamiColors.cyan) }
        media.popularity?.let { StatRow("Популярность", "%,d".format(it)) }
        media.favourites?.let { StatRow("В избранном", "%,d".format(it)) }
        media.trending?.let { StatRow("В тренде", "%,d".format(it)) }
        if (media.tags.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                media.tags.forEach { tag ->
                    InfoChip("${tag.name} ${tag.rank}%", color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

@Composable
private fun RelationsCard(media: AnimeMedia, onOpen: (String) -> Unit) {
    SectionCard(title = "Связи", icon = Icons.Default.Link) {
        media.relations.forEach { relation ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onOpen(relation.media.anilistUrl) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NetworkImage(
                    url = relation.media.cover,
                    contentDescription = relation.media.title.best,
                    modifier = Modifier
                        .size(42.dp, 58.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = relation.media.title.best.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = relation.type.lowercase().replaceFirstChar(Char::uppercase),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecommendationsCard(media: AnimeMedia, onOpen: (String) -> Unit) {
    SectionCard(title = "Похожие аниме", icon = Icons.Default.Tv) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(media.recommendations) { recommendation ->
                Column(
                    modifier = Modifier
                        .width(104.dp)
                        .clickable { onOpen(recommendation.anilistUrl) },
                ) {
                    NetworkImage(
                        url = recommendation.cover,
                        contentDescription = recommendation.title.best,
                        modifier = Modifier
                            .size(104.dp)
                            .aspectRatio(0.72f)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = recommendation.title.best.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    recommendation.score?.let {
                        Text(
                            text = "$it / 100",
                            style = MaterialTheme.typography.labelMedium,
                            color = KagamiColors.cyan,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcesCard(page: AnimeWikiPage, onOpen: (String) -> Unit) {
    SectionCard(title = "Источники", icon = Icons.Default.Link) {
        page.sources.forEach { source ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = source.url != null) { source.url?.let(onOpen) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = when {
                        source.label.contains("trace") -> Icons.Default.AutoAwesome
                        source.label.contains("AniList") -> Icons.Default.Book
                        else -> Icons.Default.Link
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(source.label, style = MaterialTheme.typography.bodyMedium)
                    source.note?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (source.url != null) {
                    Icon(
                        Icons.Default.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
