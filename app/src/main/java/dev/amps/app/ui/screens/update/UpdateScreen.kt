@file:OptIn(ExperimentalMaterial3Api::class)

package dev.amps.app.ui.screens.update

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.amps.app.ui.components.EmptyState
import dev.amps.app.ui.components.InfoChip
import dev.amps.app.ui.components.SectionCard
import dev.amps.app.ui.components.StatRow
import dev.amps.app.ui.theme.AmpsColors
import dev.amps.app.update.UpdateRelease
import dev.amps.app.update.UpdateUiState
import dev.amps.app.update.UpdateViewModel
import dev.amps.app.util.formatBytes
import dev.amps.app.util.openUrl

/**
 * «Обновление»: asks GitHub for the newest AMPS, downloads the release APK and
 * hands it to the system package installer.
 *
 * The screen is a pure function of [UpdateViewModel.state] — every state of the
 * flow has its own card, and every card has exactly one obvious next step.
 */
@Composable
fun UpdateScreen(
    viewModel: UpdateViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context.findActivity()

    // Coming back from "install unknown apps" finishes the job that paused it.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                context.findActivity()?.let(viewModel::resumeAfterPermission)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val latest = when (val current = state) {
        is UpdateUiState.UpToDate -> current.latest
        else -> viewModel.release?.versionLabel
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Обновление") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { VersionCard(current = viewModel.currentVersion, latest = latest, state = state) }

            viewModel.release?.let { release ->
                item { ReleaseCard(release = release, onOpenPage = { openUrl(context, release.pageUrl) }) }
            }

            item {
                ActionCard(
                    state = state,
                    activity = activity,
                    onCheck = viewModel::check,
                    onDownload = viewModel::download,
                    onInstall = viewModel::install,
                    onOpenPermissionSettings = viewModel::openInstallPermissionSettings,
                    onRetry = { viewModel.retry(activity) },
                    onDismiss = viewModel::dismiss,
                )
            }
        }
    }
}

// --- версии -------------------------------------------------------------------

@Composable
private fun VersionCard(current: String, latest: String?, state: UpdateUiState) {
    val fresh = state is UpdateUiState.UpToDate
    SectionCard(title = "Версия", icon = Icons.Default.Info) {
        StatRow("Установлена", current)
        StatRow(
            label = "Доступна",
            value = latest ?: "—",
            valueColor = if (fresh) AmpsColors.cyan else AmpsColors.amber,
        )
        if (fresh) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Установленная версия — самая свежая.",
                style = MaterialTheme.typography.bodySmall,
                color = AmpsColors.cyan,
            )
        }
    }
}

@Composable
private fun ReleaseCard(release: UpdateRelease, onOpenPage: () -> Unit) {
    SectionCard(title = "Что нового", icon = Icons.Default.NewReleases) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InfoChip(release.versionLabel, color = AmpsColors.violet)
            Spacer(Modifier.width(8.dp))
            InfoChip(release.tag, color = AmpsColors.cyan)
            release.sizeLabel?.let {
                Spacer(Modifier.width(8.dp))
                InfoChip(it, color = AmpsColors.amber)
            }
        }

        release.publishedDate?.let {
            Spacer(Modifier.height(10.dp))
            StatRow("Опубликовано", it)
        }

        val notes = release.notes.orEmpty().markdownToPlainText()
        if (notes.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = notes,
                style = MaterialTheme.typography.bodySmall,
                color = AmpsColors.onSurfaceVariant,
            )
        }

        if (!release.pageUrl.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onOpenPage)
                    .padding(vertical = 4.dp),
            ) {
                Icon(
                    Icons.Default.OpenInNew,
                    contentDescription = null,
                    tint = AmpsColors.violet,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Открыть релиз на GitHub",
                    style = MaterialTheme.typography.labelMedium,
                    color = AmpsColors.violet,
                )
            }
        }
    }
}

// --- действия -----------------------------------------------------------------

@Composable
private fun ActionCard(
    state: UpdateUiState,
    activity: Activity?,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: (Activity) -> Unit,
    onOpenPermissionSettings: (Activity) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        UpdateUiState.Idle -> EmptyState(
            icon = Icons.Default.SystemUpdateAlt,
            title = "Доступно обновление",
            message = "Проверим GitHub: если вышел новый релиз AMPS, приложение скачает его и " +
                "предложит установить через системный установщик.",
            action = {
                Button(onClick = onCheck) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Проверить обновления")
                }
            },
        )

        UpdateUiState.Checking -> SectionCard(title = "Проверяем GitHub", icon = Icons.Default.SystemUpdateAlt) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Спрашиваем последний релиз AMPS…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is UpdateUiState.UpToDate -> EmptyState(
            icon = Icons.Default.CheckCircle,
            title = "Обновлений нет",
            message = "Установлена версия ${state.current}, а последний релиз — ${state.latest}.",
            action = {
                OutlinedButton(onClick = onCheck) { Text("Проверить ещё раз") }
            },
        )

        is UpdateUiState.Available -> SectionCard(
            title = "Доступно обновление ${state.release.versionLabel}",
            icon = Icons.Default.NewReleases,
        ) {
            Text(
                text = "Файл весит ${state.release.sizeLabel ?: "неизвестно"}. Установка идёт через " +
                    "системный установщик Android — подтвердите её в диалоге.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Скачать и установить")
            }
        }

        is UpdateUiState.Downloading -> SectionCard(title = "Скачиваем обновление", icon = Icons.Default.Download) {
            LinearProgressIndicator(
                progress = { state.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(50)),
                color = AmpsColors.violet,
                trackColor = AmpsColors.violet.copy(alpha = 0.18f),
            )
            Spacer(Modifier.height(10.dp))
            StatRow(
                label = "Готово",
                value = if (state.totalKnown) {
                    "${formatBytes(state.bytesRead) ?: "0 Б"} из ${formatBytes(state.totalBytes) ?: "?"}"
                } else {
                    "${formatBytes(state.bytesRead) ?: "0 Б"} · размер неизвестен"
                },
                valueColor = AmpsColors.cyan,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Скачиваем… ${state.percent}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is UpdateUiState.ReadyToInstall -> SectionCard(title = "Обновление готово", icon = Icons.Default.CheckCircle) {
            StatRow("Файл", state.fileName, valueColor = AmpsColors.cyan)
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { activity?.let(onInstall) },
                enabled = activity != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.SystemUpdateAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Установить")
            }
        }

        UpdateUiState.NeedsInstallPermission -> SectionCard(
            title = "Нужно разрешение",
            icon = Icons.Default.Warning,
        ) {
            Text(
                text = "Android не даёт приложениям ставить обновления, пока владелец телефона " +
                    "не разрешит установку из AMPS. Откройте настройки, включите «Установка " +
                    "неизвестных приложений» для AMPS и вернитесь — установка продолжится сама.",
                style = MaterialTheme.typography.bodySmall,
                color = AmpsColors.amber,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { activity?.let(onOpenPermissionSettings) },
                enabled = activity != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Разрешить установку")
            }
        }

        UpdateUiState.Installing -> SectionCard(title = "Открываем установщик", icon = Icons.Default.SystemUpdateAlt) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Передаём APK системному установщику Android…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is UpdateUiState.Failed -> SectionCard(title = "Не получилось", icon = Icons.Default.Warning) {
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall,
                color = AmpsColors.danger,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onRetry) { Text("Повторить") }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = onDismiss) { Text("Закрыть") }
            }
        }
    }
}

// --- вспомогательное ----------------------------------------------------------

/**
 * Release notes are markdown; the screen shows them as plain text, so the most
 * common markers are stripped and the rest is left as the author wrote it.
 */
private fun String.markdownToPlainText(): String {
    var text = this
    text = text.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")            // заголовки
    text = text.replace(Regex("(?m)^\\s{0,3}>\\s?"), "")                 // цитаты
    text = text.replace(Regex("(?m)^\\s*[-*+]\\s+"), "• ")               // маркеры списка
    text = text.replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "\$1")     // ссылки
    text = text.replace(Regex("!?\\[([^\\]]*)]\\[[^\\]]*]"), "\$1")      // ссылки-сноски
    text = text.replace(Regex("`+([^`]*)`+"), "\$1")                     // код
    text = text.replace(Regex("\\*\\*([^*]+)\\*\\*"), "\$1")             // жирный
    text = text.replace(Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)"), "\$1") // курсив
    text = text.replace(Regex("(?<!\\w)_([^_\\n]+)_(?!\\w)"), "\$1")      // _курсив_
    text = text.replace(Regex("(?m)^\\s*[-*_]{3,}\\s*$"), "• • •")       // разделитель

    return text
        .lines()
        .joinToString("\n") { it.trimEnd() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

/** The screen always lives inside the activity; unwrap whatever Compose hands us. */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current !is Activity && current is ContextWrapper) {
        current = current.baseContext
    }
    return current as? Activity
}

/** Public so the nav host can build the view model itself and pass it in. */
@Composable
fun updateViewModel(): UpdateViewModel {
    val context = LocalContext.current
    return viewModel(factory = UpdateViewModel.Factory(context.applicationContext))
}
