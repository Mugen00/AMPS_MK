package dev.amps.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.amps.app.core.ThemeMode
import dev.amps.app.ui.components.SectionCard
import dev.amps.app.ui.components.StatRow
import dev.amps.app.ui.theme.AmpsColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenUpdates: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
    sessionLabel: String? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val installedVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "—" }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
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

            // 1.0.9: аккаунт первым, потому что от него зависит, пишется ли
            // история. Гостю показывается честное объяснение, а не «войдите,
            // чтобы пользоваться приложением» — пользоваться можно и так.
            SectionCard(title = "Аккаунт", icon = Icons.Default.Person) {
                Text(
                    sessionLabel ?: "Гость",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (sessionLabel != null) {
                        "История поиска сохраняется. Пароль и код 2FA на этом устройстве."
                    } else {
                        "Гостевой режим: поиск по кадру и по музыке работает полностью, " +
                            "история не сохраняется. Аккаунт хранится только на этом устройстве — " +
                            "на сервер ничего не отправляется."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onOpenAccount) {
                    Text(if (sessionLabel != null) "Аккаунт и безопасность" else "Войти или создать")
                }
            }

            SectionCard(title = "Ключи API", icon = Icons.Default.VpnKey) {
                Text(
                    "Ключей в приложении больше нет. Поиск по картинке идёт через IQDB — он работает без ключа " +
                        "и без регистрации, поэтому вводить нечего. Jamendo тоже подключён сразу.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = AmpsColors.cyan,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("IQDB подключён", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Обратный поиск по картинке: спрашивает сразу Danbooru, Konachan, Gelbooru, Sankaku и другие базы",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = AmpsColors.cyan,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Jamendo подключён", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Полные треки с открытой лицензией — работает сразу, вводить ничего не нужно",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            SectionCard(title = "Оформление", icon = Icons.Default.Info) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.themeMode == mode,
                            onClick = { viewModel.setTheme(mode) },
                            label = {
                                Text(
                                    when (mode) {
                                        ThemeMode.DARK -> "Тёмная"
                                        ThemeMode.LIGHT -> "Светлая"
                                        ThemeMode.SYSTEM -> "Системная"
                                    }
                                )
                            },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                SwitchRow(
                    title = "Класть скачанное в музыку телефона",
                    subtitle = "Файл появится в «Музыка/AMPS» и откроется любым плеером",
                    checked = state.importToMediaStore,
                    onChange = viewModel::setImportToMediaStore,
                )
                SwitchRow(
                    title = "Хранить историю поиска",
                    checked = state.keepHistory,
                    onChange = viewModel::setKeepHistory,
                )
            }

            SectionCard(title = "Источники музыки", icon = Icons.Default.Info) {
                Text(
                    "Метаданные берутся из iTunes и MusicBrainz, свободные файлы — из Internet Archive и ccMixter. Приложение не скачивает треки из стриминговых сервисов.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                StatRow("Обложки", "Cover Art Archive / archive.org")
                StatRow("Свой файл", "импорт через системный выбор файла")
                StatRow("Лицензия", "без неё файл не предлагается к скачиванию")
            }

            SectionCard(title = "О приложении", icon = Icons.Default.Info) {
                StatRow("Название", "AMPS · поиск по картинке и музыке")
                // Read from the package so it can never go stale next to the build.
                StatRow("Версия", installedVersion)
                StatRow("Поиск по картинке", "IQDB · без ключа и регистрации")
                StatRow("Данные о серии", "AniList GraphQL")
                StatRow("Музыка", "iTunes · MusicBrainz · Internet Archive · ccMixter")
                Spacer(Modifier.height(10.dp))
                Button(onClick = onOpenUpdates) { Text("Проверить обновления") }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Лицензии: IQDB и AniList дают только метаданные. Аудио берётся лишь из источников со свободной лицензией или из файла, который вы импортировали сами.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
