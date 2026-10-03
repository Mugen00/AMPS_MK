package dev.amps.app.ui.screens.wiki

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
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Visibility
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
import dev.amps.app.data.model.AnimeCharacter
import dev.amps.app.data.model.AnimeMedia
import dev.amps.app.data.model.AnimeWikiPage
import dev.amps.app.data.model.CharacterGuess
import dev.amps.app.data.model.FrameContent
import dev.amps.app.data.model.FrameHit
import dev.amps.app.data.model.FrameVerdict
import dev.amps.app.ui.components.ConfidenceDial
import dev.amps.app.ui.components.EmptyState
import dev.amps.app.ui.components.InfoChip
import dev.amps.app.ui.components.NetworkImage
import dev.amps.app.ui.components.SectionCard
import dev.amps.app.ui.components.StatRow
import dev.amps.app.ui.theme.AmpsColors
import dev.amps.app.util.htmlToAnnotatedString
import dev.amps.app.util.openUrl

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

                // 1.0.2: вердикт идёт первым после обложки — человек должен
                // увидеть «я не уверен» раньше, чем название серии, а не после.
                page.verdict?.let { verdict ->
                    item { VerdictCard(verdict, onOpen = { openUrl(context, it) }) }
                }

                page.content?.let { content ->
                    if (content.analyzed) item { FrameContentCard(content) }
                }

                page.frame?.let { hit ->
                    item { FrameFoundCard(hit, page.searchedImageSha256) { hit.url?.let { openUrl(context, it) } } }
                }

                if (page.guess != null || page.candidates.isNotEmpty()) {
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

                if (page.wiki != null || page.rosterCharacters.isNotEmpty() || page.rosterPlaces.isNotEmpty()) {
                    item {
                        FandomCard(
                            page = page,
                            onOpen = { openUrl(context, it) },
                        )
                    }
                }

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
                            title = "Ответ IQDB",
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
private fun VerdictCard(verdict: FrameVerdict, onOpen: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val accent = when {
        verdict.identified -> AmpsColors.cyan
        verdict.uncertain -> AmpsColors.amber
        else -> scheme.error
    }
    SectionCard(title = "Насколько уверены источники", icon = Icons.AutoMirrored.Filled.FactCheck) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ConfidenceDial(
                percent = verdict.confidencePercent,
                label = "уверенность",
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = verdict.headline.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    color = accent,
                )
                if (verdict.agreedSources.isNotEmpty()) {
                    Text(
                        text = "Совпали источники: " + verdict.agreedSources.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (verdict.reasons.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            verdict.reasons.forEach { reason ->
                Text(
                    text = "· $reason",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }

        if (verdict.warnings.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            verdict.warnings.forEach { warning ->
                Surface(
                    color = scheme.errorContainer.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }

        if (verdict.candidates.size > 1) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Другие варианты",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
            )
            verdict.candidates.drop(1).forEach { candidate ->
                // Ссылка на AniList есть только у варианта, который удалось
                // подтвердить; у остальных известен лишь бо́ру-тег, и ссылку
                // туда выдумывать нельзя.
                val url = candidate.anilistId.takeIf { it > 0 }
                    ?.let { "https://anilist.co/anime/$it" }
                if (url == null) {
                    Text(
                        text = candidate.title.ifBlank { "тег серии" } +
                            candidate.sources.joinToString(prefix = "  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                } else {
                    TextButton(onClick = { onOpen(url) }, contentPadding = PaddingValues(0.dp)) {
                        Text(
                            text = candidate.title.ifBlank { "AniList #${candidate.anilistId}" } +
                                candidate.sources.joinToString(prefix = "  ·  "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FrameContentCard(content: FrameContent) {
    val scheme = MaterialTheme.colorScheme
    val buckets = listOf(
        "Люди" to content.subjects,
        "Место" to content.placeHints,
        "Предметы" to content.itemHints,
    ).filter { it.second.isNotEmpty() }
    if (buckets.isEmpty() && content.labels.isEmpty()) return

    SectionCard(title = "Что видно на картинке", icon = Icons.Default.Visibility) {
        Text(
            text = "Определено на самом телефоне, без интернета и без отправки картинки наружу. " +
                "Это общие признаки, а не названия — имена персонажей и мест приходят из бо́ру-тегов и вики.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        buckets.forEach { (title, values) ->
            if (values.isEmpty()) return@forEach
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                values.forEach { InfoChip(text = it, color = scheme.primary) }
            }
        }
        if (buckets.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                content.labels.take(8).forEach { InfoChip(text = it.label, color = scheme.primary) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FandomCard(page: AnimeWikiPage, onOpen: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    SectionCard(title = "Фандом-вики", icon = Icons.Default.Language) {
        val wiki = page.wiki
        if (wiki == null && page.rosterCharacters.isEmpty() && page.rosterPlaces.isEmpty()) return@SectionCard

        if (wiki?.url != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = wiki.slug ?: "Вики серии",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (wiki.intro != null) {
                        Text(
                            text = wiki.intro.take(280),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                TextButton(onClick = { onOpen(wiki.url!!) }) { Text("Открыть") }
            }
        }

        if (page.rosterPlaces.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Места", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                page.rosterPlaces.forEach { place ->
                    InfoChip(
                        text = place.name,
                        color = scheme.tertiary,
                        modifier = place.url?.let { Modifier.clickable { onOpen(it) } } ?: Modifier,
                    )
                }
            }
        }

        if (page.rosterCharacters.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Персонажи по вики", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                page.rosterCharacters.take(24).forEach { entry ->
                    InfoChip(
                        text = entry.name,
                        color = scheme.primary,
                        modifier = entry.url?.let { Modifier.clickable { onOpen(it) } } ?: Modifier,
                    )
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
                media.status?.let { InfoChip(it, color = AmpsColors.cyan) }
                if (media.isAdult) InfoChip("18+", color = AmpsColors.rose)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FrameFoundCard(hit: FrameHit, sha256: String?, onOpenSource: () -> Unit) {
    SectionCard(
        title = "Где нашлось совпадение",
        icon = Icons.Default.ImageSearch,
        trailing = { InfoChip(hit.source, color = AmpsColors.cyan) },
    ) {
        Text(
            "IQDB ищет по иллюстрациям, скриншотам и фото в бо́ру-базах, а не по кадрам видеозаписей. " +
                "Поэтому у находки нет ни номера эпизода, ни таймкода: сервис их не сообщает, а выдумывать их нельзя.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            hit.similarityLabel?.let { InfoChip("Сходство $it", color = AmpsColors.cyan) }
            InfoChip(
                if (hit.exactMatch) "точное совпадение" else "похожее совпадение",
                color = if (hit.exactMatch) AmpsColors.cyan else AmpsColors.amber,
            )
        }
        Spacer(Modifier.height(12.dp))
        if (hit.url != null) {
            OutlinedButton(onClick = onOpenSource, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Открыть найденную картинку")
            }
        }
        sha256?.let {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "SHA-256 изображения: ${it.take(24)}…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * **1.0.5: здесь разведены две разные вещи, которые раньше были одной.**
 *
 * Прежде карточка называлась «Персонаж в кадре», а сверху показывала первого
 * кандидата из AniList, даже если совпадение по тегам не нашлось вовсе. Это
 * прямой обман: список персонажей серии ничего не говорит о том, кто изображён
 * на картинке.
 *
 * Теперь:
 * - сверху — только тот, кого IQDB назвал по бо́ру-тегу и кого AniList
 *   подтвердил; подпись прямо говорит, что это «по тегам источника»;
 * - ниже — весь состав серии с честной оговоркой, что это перечень серии, а не
 *   догадка о том, кто на картинке.
 *
 * Если тегов не было вовсе, не выдумывается ничего: так и написано, что
 * определить нечем и почему.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CharacterCard(
    guess: CharacterGuess?,
    candidates: List<AnimeCharacter>,
    onSelect: (AnimeCharacter) -> Unit,
) {
    val found = guess?.character
    SectionCard(
        title = "Персонаж",
        icon = Icons.Default.Face,
        trailing = {
            found?.let { InfoChip("по тегам источника", color = AmpsColors.cyan) }
        },
    ) {
        if (found == null) {
            Text(
                "Совпадений не найдено. IQDB ищет по иллюстрациям и скриншотам, которые уже есть в бо́ру-базах; " +
                    "обычное фото или скриншот из видеоигры там не лежат — и назвать по ним персонажа нечем.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(Modifier.fillMaxWidth()) {
                NetworkImage(
                    url = found.image,
                    contentDescription = found.displayName,
                    modifier = Modifier
                        .size(width = 108.dp, height = 150.dp)
                        .clip(RoundedCornerShape(16.dp)),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = found.displayName.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "найден по тегам источника, а не распознан на картинке",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    found.name.native?.takeIf { it.isNotBlank() }?.let {
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
                        InfoChip(if (found.role == "MAIN") "Главный" else "Второстепенный", color = AmpsColors.violet)
                        found.age?.let { InfoChip(it, color = AmpsColors.amber) }
                        found.gender?.let { InfoChip(it.lowercase().replaceFirstChar(Char::uppercase), color = AmpsColors.amber) }
                        found.bloodType?.let { InfoChip("Группа $it", color = AmpsColors.rose) }
                    }
                }
            }

            if (guess.reason.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Почему: ${guess.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AmpsColors.cyan,
                )
            }

            found.description?.let { description ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = htmlToAnnotatedString(description)?.text.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (found.appearances.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("Появления", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(found.appearances.filter { it.cover != null }) { media ->
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
        }

        // Состав серии — это отдельный факт, и подписывать его надо отдельно:
        // перечисление персонажей не равно утверждению «вот этот изображён».
        if (candidates.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (found != null) "Персонажи этой серии" else "Персонажи серии",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Кто именно в кадре, определяет только совпадение по тегам — этот список к картинке отношения не имеет.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(candidates.filter { found == null || it.id != found.id }) { candidate ->
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
        page.similarArt.forEach { art ->
            art.url?.let { add(Triple(it, art.title ?: art.source, "${art.source} · ${art.similarity ?: 0}%")) }
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
        media.score?.let { StatRow("Оценка", "$it / 100", valueColor = AmpsColors.cyan) }
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
                            color = AmpsColors.cyan,
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
                        source.label.contains("IQDB") -> Icons.Default.ImageSearch
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
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
