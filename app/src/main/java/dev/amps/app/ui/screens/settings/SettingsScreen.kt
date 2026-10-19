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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.os.Build
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.amps.app.core.ThemeMode
import dev.amps.app.core.t
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
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val vName = info.versionName
            val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "${vName} ($vCode)"
        }.getOrNull().orEmpty().ifBlank { "—" }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(t("Настройки")) },
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
            SectionCard(title = t("Аккаунт"), icon = Icons.Default.Person) {
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

            SectionCard(title = t("Ключи API"), icon = Icons.Default.VpnKey) {
                Text(
                    "Пошук по картинке идёт через IQDB — без ключа и регистрации. " +
                        "Jamendo тоже подключён сразу. Ключ нужен только для AI-анализа фото (Gemini).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.geminiApiKey,
                    onValueChange = viewModel::onGeminiKey,
                    label = { Text(t("Ключ Google AI Studio (Gemini)")) },
                    placeholder = { Text("AIza…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    // 1.1.3: чесно — де взяти ключ і чому він не вшитий у застосунок.
                    "Безкоштовний ключ видає Google AI Studio (aistudio.google.com/apikey). " +
                        "Зберігається лише на пристрої: вшивати ключ у публічний APK — подарувати " +
                        "його чужим ботам, які спалять безкоштовний ліміт.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                // 1.2.0: Web Client ID для входу через Google.
                OutlinedTextField(
                    value = state.googleWebClientId,
                    onValueChange = viewModel::onGoogleClientId,
                    label = { Text("Google Client ID (Web)") },
                    placeholder = { Text("1234567890-abc.apps.googleusercontent.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Ідентифікатор Web-клієнта з Google Cloud Console (APIs & Services → Credentials). " +
                        "Він же треба у GOOGLE_CLIENT_IDS на сервері. Це публічний ID, не секрет: " +
                        "без нього кнопка «Увійти через Google» просто захована.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = viewModel::save,
                    enabled = state.dirty,
                ) { Text(t("Зберегти ключі")) }
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

            // 1.5.0: мова інтерфейсу — застосовується одразу, без перезапуску.
            SectionCard(title = t("Мова"), icon = Icons.Default.Language) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    dev.amps.app.core.Loc.Lang.entries.forEach { lang ->
                        val selected = when (lang) {
                            dev.amps.app.core.Loc.Lang.SYSTEM ->
                                dev.amps.app.core.Loc.language == dev.amps.app.core.Loc.Lang.SYSTEM
                            else ->
                                dev.amps.app.core.Loc.language == lang ||
                                    (dev.amps.app.core.Loc.language == dev.amps.app.core.Loc.Lang.SYSTEM &&
                                        dev.amps.app.core.Loc.systemLanguage == lang.code)
                        }
                        FilterChip(
                            selected = selected,
                            onClick = { dev.amps.app.core.Loc.setLanguage(context, lang) },
                            label = {
                                Text(
                                    when (lang) {
                                        dev.amps.app.core.Loc.Lang.SYSTEM -> t("Системна")
                                        dev.amps.app.core.Loc.Lang.UK -> "Українська"
                                        dev.amps.app.core.Loc.Lang.RU -> "Русский"
                                        dev.amps.app.core.Loc.Lang.EN -> "English"
                                    }
                                )
                            },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    t("Системна — як у системи телефона. Зміна набирає сили одразу."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(title = t("Оформление"), icon = Icons.Default.Info) {
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
                    title = t("Класть скачанное в музыку телефона"),
                    subtitle = t("Файл появится в «Музыка/AMPS» и откроется любым плеером"),
                    checked = state.importToMediaStore,
                    onChange = viewModel::setImportToMediaStore,
                )
                SwitchRow(
                    title = t("Хранить историю поиска"),
                    checked = state.keepHistory,
                    onChange = viewModel::setKeepHistory,
                )
                SwitchRow(
                    title = t("Приховувати 18+"),
                    subtitle = t("Frame Wiki не показуватиме збіги з рейтингом e/q (1.2.3)"),
                    checked = state.hideNsfw,
                    onChange = viewModel::setHideNsfw,
                )
                Spacer(Modifier.height(12.dp))

                // --- 1.2.4: сезонні теми і генератор унікальних тем ---
                SwitchRow(
                    title = t("Сезонні теми"),
                    subtitle = t("Палітра змінюється сама за датою пристрою: зараз — ") +
                        dev.amps.app.ui.theme.SeasonTheme.seasonName(java.time.LocalDate.now()).lowercase(),
                    checked = state.seasonalTheme,
                    onChange = viewModel::setSeasonalTheme,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = viewModel::generateTheme) {
                        Text(t("🎲 Згенерувати тему"))
                    }
                    Spacer(Modifier.width(10.dp))
                    if (state.customHue != null) {
                        TextButton(onClick = viewModel::resetCustomTheme) {
                            Text(t("Скинути"))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Прев'ю палітри: основний / другорядний / контрастний відтінки.
                val hue = state.customHue
                    ?: dev.amps.app.ui.theme.SeasonTheme.hueFor(java.time.LocalDate.now())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf(0f, 45f, 180f).forEach { shift ->
                        androidx.compose.material3.Surface(
                            shape = CircleShape,
                            color = androidx.compose.ui.graphics.Color.hsv(
                                (hue + shift + 360f) % 360f,
                                0.52f,
                                0.9f,
                            ),
                            modifier = Modifier.size(22.dp),
                        ) {}
                        Spacer(Modifier.width(8.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (state.customHue != null) "Власна тема активна — переважає сезонну"
                        else if (state.seasonalTheme)
                            "Сезонна: " + dev.amps.app.ui.theme.SeasonTheme.seasonName(java.time.LocalDate.now())
                        else "Фірмовий фіолет",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionCard(title = t("Источники музыки"), icon = Icons.Default.Info) {
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

            SectionCard(title = t("О приложении"), icon = Icons.Default.Info) {
                StatRow("Название", "AMPS · поиск по картинке и музыке")
                // Read from the package so it can never go stale next to the build.
                StatRow("Версия", installedVersion)
                StatRow("Поиск по картинке", "IQDB · без ключа и регистрации")
                StatRow("Данные о серии", "AniList GraphQL")
                StatRow("Музыка", "iTunes · MusicBrainz · Internet Archive · ccMixter")
                Spacer(Modifier.height(10.dp))
                Button(onClick = onOpenUpdates) { Text(t("Проверить обновления")) }
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
