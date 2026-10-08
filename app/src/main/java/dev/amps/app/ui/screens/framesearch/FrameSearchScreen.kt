package dev.amps.app.ui.screens.framesearch

import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.amps.app.data.model.FrameContent
import dev.amps.app.ui.components.EmptyState
import dev.amps.app.ui.components.InfoChip
import dev.amps.app.ui.components.SectionCard
import dev.amps.app.ui.components.StageProgress
import dev.amps.app.ui.theme.AmpsColors
import dev.amps.app.util.openUrl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrameSearchScreen(
    viewModel: FrameSearchViewModel,
    onOpenWiki: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigation by viewModel.navigation.collectAsStateWithLifecycle()
    // 1.1.3: стан AI-аналізу Gemini — окремий потік.
    val gemini by viewModel.gemini.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(viewModel::onImagePicked) }

    LaunchedEffect(navigation) {
        if (navigation) {
            viewModel.consumeNavigation()
            onOpenWiki()
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Поиск по картинке") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Default.History, contentDescription = "История")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            if (state.page != null) {
                FoundBanner(
                    title = state.page?.media?.title?.best.orEmpty(),
                    subtitle = state.page?.frame?.let { hit ->
                        buildString {
                            hit.similarityLabel?.let { append("совпадение $it · ") }
                            append(hit.source)
                            if (!hit.exactMatch) append(" · не точное")
                        }
                    }.orEmpty(),
                    onOpen = onOpenWiki,
                )
            }

            if (state.hasImage) {
                FramePreviewCard(
                    previewDataUrl = state.image?.previewDataUrl.orEmpty(),
                    dimensions = state.image?.let { "${it.width}×${it.height}" }.orEmpty(),
                    enabled = !state.busy,
                    onReplace = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onClear = viewModel::clear,
                )
            } else {
                DropZone(
                    onPick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                )
            }

            if (state.busy) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    StageProgress(state.stage.orEmpty(), modifier = Modifier.padding(18.dp))
                }
            } else {
                Button(
                    onClick = viewModel::search,
                    enabled = state.hasImage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Найти по картинке", style = MaterialTheme.typography.titleMedium)
                }
            }

            state.miss?.let { miss ->
                EmptyState(
                    icon = Icons.Default.ImageSearch,
                    title = "Совпадений не найдено",
                    message = miss.reason,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 1.0.8: «ничего не нашлось» на книжной картинке — не беда
                // пользователя и не сбой поиска. Точное совпадение по ней
                // невозможно, и это надо сказать прямо, иначе человек будет
                // пробовать другие картинки вместо того, чтобы взять широкий
                // кадр. Блок ставится независимо от поисковой подсказки: он
                // объясняет причину промаха, а подсказка — что делать дальше.
                miss.content
                    ?.takeIf { it.narrowFrame }
                    ?.let { FrameShapeNotice(it) }
                // 1.0.6b: «ничего не найдено» — худший ответ, потому что человек
                // остаётся с картинкой и без единого движения вперёд. Описание
                // внешности — то, что модель определяет безошибочно, поэтому из
                // него собран запрос и готовы ссылки на поисковики.
                //
                // Показываем только когда запрос действительно собран: пустой
                // блок с кнопками без запроса хуже его отсутствия.
                miss.content
                    ?.takeIf { !it.searchQuery.isNullOrBlank() && it.searchLinks.isNotEmpty() }
                    ?.let { hint ->
                        AppearanceSearchHint(hint) { url -> openUrl(context, url) }
                    }
            }

            // 1.1.3: AI-аналіз фото через Gemini — структурована відповідь
            // з Markdown і клікабельними посиланнями. Працює навіть тоді,
            // коли точний пошук по базах дав промах.
            GeminiCard(
                gemini = gemini,
                enabled = state.hasImage,
                onAnalyze = viewModel::analyzeWithGemini,
                onDismiss = viewModel::clearGemini,
            )

            PipelineCard()

            Spacer(Modifier.height(20.dp))
        }
    }
}

/**
 * 1.0.8: правда о книжной картинке — до всяких подсказок.
 *
 * **Почему это не «возможно, ничего не нашлось», а объяснение.** Точный поиск
 * кадра физически не работает на пропорции меньше 1,2: замер на 21 картинке
 * дал по широким кадрам 16:9 (пропорция 1,6–2,1) сходство 96,2–100 %, то есть
 * точное совпадение, а по книжным и квадратным — 24–67 %, то есть шум.
 * Пересечения не было ни на одной картинке, поэтому закон жёсткий.
 *
 * **Почему обрезку не предлагаем.** Проверено отдельно: у точного кадра,
 * обрезанного в портрет, сходство падает обратно в шум. Единственное, что
 * работает, — широкий кадр из той же серии, поэтому совет именно такой.
 * Молчаливый промах заставляет человека перебирать картинки заново и
 * винить приложение, а здесь видно, что делать.
 */
@Composable
private fun FrameShapeNotice(content: FrameContent) {
    val shape = content.frameShape.orEmpty()
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = AmpsColors.amber.copy(alpha = 0.14f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = AmpsColors.amber,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Картинка $shape — точного поиска кадра не будет",
                    style = MaterialTheme.typography.titleSmall,
                    color = AmpsColors.amber,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Замер на 21 картинке: широкие кадры 16:9 (пропорция 1,6–2,1) trace.moe узнаёт " +
                    "точно — сходство 96,2–100 %. Книжные и квадратные (пропорция меньше 1,2) дают " +
                    "24–67 %, то есть шум, и пересечения с точными совпадениями не было ни разу.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Обрезать картинку не поможет: у точного кадра, обрезанного в портрет, сходство " +
                    "падает обратно в шум. Нужен широкий кадр из той же серии — например снимок экрана " +
                    "плеера во весь экран.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 1.0.6b: подсказка, которая остаётся, когда промах.
 *
 * **Почему поиск по внешности, а не по имени.** Имя на скриншоте аниме
 * определить нельзя: тегер обучен на иллюстрациях и на кадрах называет
 * персонажей уверенно и неверно. А вот цвет волос, глаза и одежду он читает
 * верно даже там, где IQDB и trace.moe молчат. Из этих признаков и собран
 * запрос.
 *
 * **Почему это подсказка, а не ответ.** «Длинные белые волосы, школьная форма»
 * подходит половине героев аниме, поэтому такой запрос персонажа не находит —
 * он лишь сужает круг, а решение принимает человек. Подписать это «результатом
 * поиска» было бы ровно тем же враньём, от которого приложение лечится.
 *
 * **Почему кнопка открывает браузер, а не ищет внутри приложения.** Проверено:
 * и DuckDuckGo, и Mojeek на автоматический запрос отдают капчу при коде 200
 * (`anomaly-modal__puzzle` и `captcha-wrap`). Разбирать такую выдачу значит
 * вшить конструкцию, которая рано или поздно сломается; обычный браузер
 * ломаться не умеет, а капчу при необходимости проходит человек.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSearchHint(content: FrameContent, onOpen: (String) -> Unit) {
    val query = content.searchQuery ?: return
    SectionCard(
        title = "Поиск по внешности",
        icon = Icons.Default.TravelExplore,
        trailing = { InfoChip("подсказка", color = AmpsColors.amber) },
    ) {
        Text(
            text = "Ничего не нашлось — но описание внешности модель определяет верно, " +
                "даже когда кадр никто опознать не смог. Из него собран запрос:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        content.description?.let { description ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "Запрос: «$query»",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content.searchLinks.forEach { link ->
                OutlinedButton(onClick = { onOpen(link.url) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(link.engine)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = "Это подсказка для проверки, а не ответ: признаки внешности подходят " +
                "половине героев аниме, и по ним персонажа не назвать. Поиск идёт в браузере, " +
                "где при необходимости пройдётся капча.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DropZone(onPick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 44.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(86.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                AmpsColors.violet.copy(alpha = 0.35f),
                                AmpsColors.cyan.copy(alpha = 0.25f),
                            )
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.PhotoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(38.dp),
                )
            }
            Text("Выберите картинку", style = MaterialTheme.typography.titleMedium)
            Text(
                "Иллюстрация, скриншот из аниме или фото персонажа с рисунка. Изображение уходит только на iqdb.org " +
                    "для поиска и нигде не сохраняется.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            FilledTonalButton(onClick = onPick) {
                Icon(Icons.Default.ImageSearch, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Выбрать изображение")
            }
        }
    }
}

@Composable
private fun FramePreviewCard(
    previewDataUrl: String,
    dimensions: String,
    enabled: Boolean,
    onReplace: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(18.dp))
            ) {
                AsyncImage(
                    model = previewDataUrl,
                    contentDescription = "Выбранное изображение",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = dimensions,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onReplace, enabled = enabled) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Заменить")
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onClear, enabled = enabled) {
                    Icon(Icons.Default.Close, contentDescription = "Очистить", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun FoundBanner(title: String, subtitle: String, onOpen: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = AmpsColors.cyan.copy(alpha = 0.14f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Search, contentDescription = null, tint = AmpsColors.cyan)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onOpen) { Text("Открыть") }
        }
    }
}

/**
 * 1.0.5: источник один и он бесплатный — карточка говорит об этом прямо.
 *
 * Прежде здесь стоял статус «второй источник подключён / добавьте ключ
 * SauceNAO». Ключа больше нет, и обе половины этой фразы стали неправдой.
 * Теперь честное содержание: кто ищет, чего стоит ждать и чего поиск **не**
 * умеет — а именно этого не хватало больше всего.
 */
@Composable
private fun PipelineCard() {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("Как это работает", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            PipelineStep("1", "IQDB", "ищет по иллюстрациям, скриншотам и фото в бо́ру-базах")
            PipelineStep("2", "AniList", "по тегам находит персонажа, а по нему — серию")
            PipelineStep("3", "Вики фандома", "дописывает, кто этот персонаж и где он появляется")
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = AmpsColors.amber,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Ключей и регистрации не нужно. Но IQDB ищет только по тому, что уже лежит в его базах: " +
                        "обычное фото или скриншот из видеоигры он не найдёт — и скажет об этом, а не промолчит.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PipelineStep(number: String, name: String, description: String) {
    Row(
        modifier = Modifier.padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)) {
            Text(
                number,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(22.dp)
                    .padding(top = 3.dp),
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 1.1.3: карточка AI-аналізу фото (Gemini). Відповідь моделі — Markdown:
 * заголовки, списки і клікабельні посилання рендерить Markwon.
 *
 * Чесно: Gemini розуміє ЗОБРАЖЕННЯ, а не шукає збіг у базі — для точного
 * «звідки цей кадр» надійніший пошук вище. Ключ задається в Налаштуваннях;
 * без нього кнопка веде в Налаштування.
 */
@Composable
private fun GeminiCard(
    gemini: GeminiState,
    enabled: Boolean,
    onAnalyze: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text("AI-аналіз фото", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "Gemini розпізнає персонажа чи об'єкт, описує контекст і дає посилання. " +
                    "Для точного збігу кадру надійніший пошук по базах вище.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                gemini.busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Gemini аналізує фото…", style = MaterialTheme.typography.bodyMedium)
                }
                gemini.result != null -> {
                    MarkdownText(gemini.result.orEmpty())
                    TextButton(onClick = onDismiss) { Text("Сховати відповідь") }
                }
                else -> {
                    gemini.error?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(onClick = onAnalyze, enabled = enabled) {
                        Text(if (enabled) "Проаналізувати через AI" else "Спочатку виберіть фото")
                    }
                }
            }
        }
    }
}

/** Markdown → TextView через Markwon; посилання клікабельні. */
@Composable
private fun MarkdownText(markdown: String) {
    val context = LocalContext.current
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val markwon = remember { Markwon.create(context) }
    AndroidView(
        factory = { ctx ->
            TextView(ctx).apply {
                movementMethod = LinkMovementMethod.getInstance()
                setTextColor(textColor)
                textSize = 15f
            }
        },
        update = { view -> markwon.setMarkdown(view, markdown) },
        modifier = Modifier.fillMaxWidth(),
    )
}
