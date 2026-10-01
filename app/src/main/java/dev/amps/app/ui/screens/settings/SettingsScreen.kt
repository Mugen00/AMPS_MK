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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.amps.app.core.ThemeMode
import dev.amps.app.data.model.BridgeHealth
import dev.amps.app.ui.components.InfoChip
import dev.amps.app.ui.components.SectionCard
import dev.amps.app.ui.components.StatRow
import dev.amps.app.ui.theme.AmpsColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

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

            SectionCard(title = "Локальный мост", icon = Icons.Default.Lan) {
                Text(
                    "Приложение не ходит в интернет за кадрами напрямую: телефон отправляет снимок на ваш компьютер, а тот вызывает локальные MCP-серверы trace.moe и SauceNAO. Компьютер и телефон должны быть в одной Wi-Fi сети.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.bridgeUrl,
                    onValueChange = viewModel::onBridgeUrl,
                    label = { Text("Адрес моста") },
                    placeholder = { Text("http://192.168.1.10:8787") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = viewModel::save) { Text(if (state.dirty) "Сохранить и проверить" else "Проверить") }
                    Spacer(Modifier.width(10.dp))
                    androidx.compose.material3.OutlinedButton(
                        onClick = viewModel::check,
                        enabled = !state.checking,
                    ) {
                        if (state.checking) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Обновить")
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                BridgeHealthCard(state.health)
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
                    title = "Проверять мост при запуске",
                    checked = state.autoCheckBridge,
                    onChange = viewModel::setAutoCheck,
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
                StatRow("Название", "AMPS · поиск кадра и музыки")
                StatRow("Версия", "1.0.0")
                StatRow("Поиск кадра", "trace.moe + SauceNAO через локальный мост")
                StatRow("Данные о серии", "AniList GraphQL")
                StatRow("Музыка", "iTunes · MusicBrainz · Internet Archive · ccMixter")
                Spacer(Modifier.height(10.dp))
                Text(
                    "Лицензии: trace.moe и AniList дают только метаданные. Аудио берётся лишь из источников со свободной лицензией или из файла, который вы импортировали сами.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun BridgeHealthCard(health: BridgeHealth?) {
    if (health == null) {
        Text(
            "Мост ещё не проверялся.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    if (health.error != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = AmpsColors.rose, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(health.error, style = MaterialTheme.typography.bodySmall, color = AmpsColors.rose)
        }
        return
    }

    val ready = health.nodes.values.count { it.ready }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ready > 0) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (ready > 0) AmpsColors.cyan else AmpsColors.amber,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Мост отвечает · версия ${health.version ?: "—"}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Spacer(Modifier.height(8.dp))
    StatRow("Активных узлов", "$ready из ${health.nodes.size}")
    StatRow(
        "Ключ trace.moe",
        if (health.keys.traceMoe) "задан" else "не нужен (квота по IP)",
        valueColor = if (health.keys.traceMoe) AmpsColors.cyan else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    StatRow(
        "Ключ SauceNAO",
        if (health.keys.sauceNao) "задан" else "нет — персонаж определяется слабее",
        valueColor = if (health.keys.sauceNao) AmpsColors.cyan else AmpsColors.amber,
    )
    health.uptimeSec?.let { StatRow("Время работы", "${it / 60} мин ${it % 60} с") }

    health.nodes.forEach { (name, node) ->
        Spacer(Modifier.height(8.dp))
        InfoChip(
            text = "$name: ${if (node.ready) "готов" else "не отвечает"}",
            color = if (node.ready) AmpsColors.cyan else AmpsColors.rose,
        )
        node.error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (node.tools.isNotEmpty()) {
            Text(
                text = node.tools.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
