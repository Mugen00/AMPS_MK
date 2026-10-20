package dev.amps.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import dev.amps.app.ui.screens.anime.AnimeScreen
import dev.amps.app.ui.screens.auth.AuthScreen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.amps.app.core.AppContainer
import dev.amps.app.core.AppSettings
import dev.amps.app.ui.screens.auth.AuthScreen
import dev.amps.app.ui.screens.auth.AuthViewModel
import dev.amps.app.ui.screens.auth.SessionUi
import dev.amps.app.ui.screens.community.CommunityScreen
import dev.amps.app.ui.screens.community.CommunityViewModel
import dev.amps.app.ui.screens.framesearch.FrameSearchScreen
import dev.amps.app.ui.screens.framesearch.FrameSearchViewModel
import dev.amps.app.ui.screens.history.HistoryScreen
import dev.amps.app.ui.screens.profile.ProfileScreen
import dev.amps.app.ui.screens.profile.ProfileViewModel
import dev.amps.app.ui.screens.profile.UserProfileScreen
import dev.amps.app.ui.screens.update.UpdateScreen
import dev.amps.app.ui.screens.update.updateViewModel
import dev.amps.app.ui.screens.music.MusicSearchScreen
import dev.amps.app.ui.screens.music.MusicSearchViewModel
import dev.amps.app.ui.screens.music.MusicSearchViewModelFactory
import dev.amps.app.ui.screens.music.TrackWikiScreen
import dev.amps.app.ui.screens.music.TrackWikiViewModel
import dev.amps.app.ui.screens.music.TrackWikiViewModelFactory
import dev.amps.app.ui.screens.settings.SettingsScreen
import dev.amps.app.ui.screens.settings.SettingsViewModel
import dev.amps.app.ui.screens.wiki.WikiScreen
import dev.amps.app.update.UpdateUiState
import dev.amps.app.music.ui.MusicScreen
import dev.amps.app.music.data.remote.FreeMusicApi
import dev.amps.app.music.data.repository.MusicRepository
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dev.amps.app.ui.screens.wiki.WikiViewModel
import dev.amps.app.ui.theme.AmpsTheme

class MainActivity : ComponentActivity() {

    private var sharedImage: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 1.5.0: мова інтерфейсу (system/uk/ru/en) — читається зі сховища.
        dev.amps.app.core.Loc.init(this)
        enableEdgeToEdge()
        sharedImage = intent.imageUriOrNull()

        val container = (application as AmpsApp).container

        // 1.2.0: патч завантажений у фоні, поки застосунок був закритий —
        // одразу показуємо системне встановлення: відкрив → вікно «Оновити».
        dev.amps.app.update.maybeAutoInstallPendingUpdate(this)

        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            // 1.2.4: тема дня — власна згенерована має пріоритет; інакше
            // сезонна за датою пристрою (синхронізація з часом системи).
            val accentHue = settings.customHue
                ?: (if (settings.seasonalTheme) dev.amps.app.ui.theme.SeasonTheme.hueFor(java.time.LocalDate.now()) else null)
            // 1.5.0: читання ревізії мови в корені — при зміні мови вся
            // композиція перемальовується, t() повертає нові переклади.
            val languageRevision = dev.amps.app.core.Loc.revision.intValue
            AmpsTheme(themeMode = settings.themeMode, accentHue = accentHue) {
                AmpsRoot(container = container, initialSharedImage = sharedImage)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.imageUriOrNull()?.let { uri ->
            sharedImage = uri
            recreate()
        }
    }

    private fun Intent.imageUriOrNull(): Uri? {
        if (action != Intent.ACTION_SEND) return null
        if (type?.startsWith("image/") != true) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }
}

private object Routes {
    const val FRAME = "frame"
    const val WIKI = "wiki"
    const val MUSIC = "music"
    const val MUSIC_TRACK = "music/track"
    const val SETTINGS = "settings"
    const val AUTH = "auth"
    const val COMMUNITY = "community"
    const val PROFILE = "profile"
    const val USER_PROFILE = "user/{userId}"
    const val ANIME = "anime"
    const val ANIME_DETAIL = "anime/detail/{anilistId}"
    const val WEB_VIEW = "web/{url}"
    const val HISTORY = "history"
    const val UPDATE = "update"
    // 1.6.0
    const val GROUPS = "groups"
    const val CHATS = "chats"
    const val REELS = "reels"
    // 1.6.6: колекції аніме-персонажів (Gemini-аналіз → збереження).
    const val COLLECTIONS = "collections"
}

private data class TabItem(val route: String, val label: String, val icon: ImageVector)

// 1.6.3: ModalBottomSheet — experimental API.
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AmpsRoot(container: AppContainer, initialSharedImage: Uri?) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val frameViewModel: FrameSearchViewModel = viewModel(
        factory = FrameSearchViewModel.Factory(
            appContext = context.applicationContext,
            settings = container.settings,
            chatStore = container.geminiChatStore,
            // 1.6.6: збереження персонажів у колекції аніме.
            backendApi = container.backendApi,
            backendSession = container.backendSession,
        )
    )
    val wikiViewModel: WikiViewModel = viewModel(factory = WikiViewModel.Factory(container.frameRepository))
    val musicViewModel: MusicSearchViewModel = viewModel(
        factory = MusicSearchViewModelFactory(
            repository = container.musicRepository,
            backendApi = container.backendApi,
            backendSession = container.backendSession,
            player = container.musicPlayer,
        )
    )
    val trackViewModel: TrackWikiViewModel = viewModel(
        factory = TrackWikiViewModelFactory(container.musicRepository)
    )
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(container.settings)
    )
    val authViewModel: AuthViewModel = viewModel(
        factory = AuthViewModel.Factory(
            container.accounts,
            container.session,
            container.backendApi,
            container.settings,
            container.backendSession
        )
    )
    // 1.1.2: Спільнота і профіль — сесія бекенда спільна з фоновою перевіркою.
    // 1.1.2: Спільнота і профіль — сесія бекенда спільна з фоновою перевіркою.
    val communityViewModel: CommunityViewModel = viewModel(
        factory = CommunityViewModel.Factory(container.backendApi, container.backendSession)
    )
    val profileViewModel: ProfileViewModel = viewModel(
        factory = ProfileViewModel.Factory(container.backendApi, container.backendSession)
    )
    // 1.6.0: групи, чати і рілси — та сама сесія бекенда.
    val groupsViewModel: dev.amps.app.ui.screens.groups.GroupsViewModel = viewModel(
        factory = dev.amps.app.ui.screens.groups.GroupsViewModel.Factory(container.backendApi, container.backendSession)
    )
    val chatsViewModel: dev.amps.app.ui.screens.chats.ChatsViewModel = viewModel(
        factory = dev.amps.app.ui.screens.chats.ChatsViewModel.Factory(container.backendApi, container.backendSession)
    )
    val reelsViewModel: dev.amps.app.ui.screens.reels.ReelsViewModel = viewModel(
        factory = dev.amps.app.ui.screens.reels.ReelsViewModel.Factory(container.backendApi, container.backendSession)
    )

    // A frame shared from the gallery goes straight into the search screen.
    var shared by remember { mutableStateOf(initialSharedImage) }
    LaunchedEffect(shared) {
        shared?.let { uri ->
            frameViewModel.onImagePicked(uri)
            shared = null
            navController.navigateToTab(Routes.FRAME)
        }
    }

    // 1.6.3: у панелі — 4 головні вкладки, решта — у «Ще» (bottom sheet).
    val primaryTabs = listOf(
        TabItem(Routes.COMMUNITY, dev.amps.app.core.t("Спільнота"), Icons.Default.Groups),
        TabItem(Routes.CHATS, dev.amps.app.core.t("Чати"), Icons.Default.Chat),
        TabItem(Routes.REELS, dev.amps.app.core.t("Рілси"), Icons.Default.Movie),
        TabItem(Routes.MUSIC, dev.amps.app.core.t("Музыка"), Icons.Default.MusicNote),
    )
    val moreTabs = listOf(
        TabItem(Routes.FRAME, dev.amps.app.core.t("Картинка"), Icons.Default.ImageSearch),
        TabItem(Routes.GROUPS, dev.amps.app.core.t("Групи"), Icons.Default.Group),
        TabItem(Routes.ANIME, dev.amps.app.core.t("Аніме"), Icons.Default.PlayArrow),
        // 1.6.6: колекції персонажів — картки аніме з відкритими персонажами.
        TabItem(Routes.COLLECTIONS, dev.amps.app.core.t("Колекції"), Icons.Default.CollectionsBookmark),
        TabItem(Routes.SETTINGS, dev.amps.app.core.t("Настройки"), Icons.Default.Settings),
    )
    val allTabRoutes = (primaryTabs + moreTabs).map { it.route }
    var showMoreSheet by remember { mutableStateOf(false) }

    // 1.6.1: сумарні непрочитані чатів — бейдж на вкладці «Чати».
    // VM чатів живий уся сесія (створений тут) і опитує список кожні ~15 с,
    // тому бейдж свіжий навіть коли користувач в іншій вкладці.
    val chatsState by chatsViewModel.state.collectAsStateWithLifecycle()
    val chatUnread = chatsState.dialogs.sumOf { it.unread }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (currentRoute in allTabRoutes) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    // 1.6.3: 4 головні вкладки + «Ще» — ряд навігації дихає,
                    // решта розділів — у листку «Ще».
                    primaryTabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { navController.navigateToTab(tab.route) },
                            icon = {
                                // 1.6.1: бейдж непрочитаних на вкладці «Чати».
                                if (tab.route == Routes.CHATS && chatUnread > 0) {
                                    BadgedBox(badge = {
                                        Badge { Text(chatUnread.toString()) }
                                    }) {
                                        Icon(tab.icon, contentDescription = tab.label)
                                    }
                                } else {
                                    Icon(tab.icon, contentDescription = tab.label)
                                }
                            },
                            label = { Text(tab.label) },
                            alwaysShowLabel = false,
                        )
                    }
                    NavigationBarItem(
                        selected = currentRoute !in primaryTabs.map { it.route } && currentRoute in allTabRoutes,
                        onClick = { showMoreSheet = true },
                        icon = {
                            Icon(
                                Icons.Default.MoreHoriz,
                                contentDescription = dev.amps.app.core.t("Ще"),
                            )
                        },
                        label = { Text(dev.amps.app.core.t("Ще")) },
                        alwaysShowLabel = false,
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.FRAME,
            modifier = Modifier.padding(padding),
            // 1.6.6: глобальний редизайн переходів — shared-axis фізика
            // (Material Motion): легкий зсув + масштаб + fade на spring.
            enterTransition = {
                androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core.spring(
                        stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                    )
                ) + androidx.compose.animation.slideInHorizontally(
                    animationSpec = androidx.compose.animation.core.spring(
                        stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                    ),
                    initialOffsetX = { it / 8 }
                ) + androidx.compose.animation.scaleIn(
                    animationSpec = androidx.compose.animation.core.spring(
                        stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
                    ),
                    initialScale = 0.96f,
                )
            },
            exitTransition = {
                androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core.tween(150)
                ) + androidx.compose.animation.scaleOut(
                    animationSpec = androidx.compose.animation.core.tween(200),
                    targetScale = 0.97f,
                )
            },
            popEnterTransition = {
                androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core.spring(
                        stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                    )
                ) + androidx.compose.animation.scaleIn(
                    animationSpec = androidx.compose.animation.core.spring(
                        stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
                    ),
                    initialScale = 0.96f,
                )
            },
            popExitTransition = {
                androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core.tween(150)
                ) + androidx.compose.animation.slideOutHorizontally(
                    animationSpec = androidx.compose.animation.core.spring(
                        stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                    ),
                    targetOffsetX = { it / 8 }
                )
            },
        ) {
            composable(Routes.FRAME) {
                FrameSearchScreen(
                    viewModel = frameViewModel,
                )
            }
            composable(Routes.WIKI) {
                WikiScreen(
                    viewModel = wikiViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            // 1.1.2: Спільнота — стрічка, пости з фото/відео, лайки, репости.
            // 1.1.2: Спільнота — стрічка, пости з фото/відео, лайки, репости.
            composable(Routes.COMMUNITY) {
                CommunityScreen(
                    viewModel = communityViewModel,
                    baseUrl = container.backendApi.effectiveBaseUrl,
                    onOpenProfile = { navController.navigate(Routes.PROFILE) },
                    onOpenAuth = { navController.navigate(Routes.AUTH) },
                    // 1.2.5: тап на автора → чужий профіль (пости, репости, підписка).
                    onOpenUserProfile = { userId ->
                        navController.navigate("user/$userId")
                    },
                )
            }
            // 1.2.5: чужий профіль — підписка, пости і репости користувача.
            composable(
                Routes.USER_PROFILE,
                arguments = listOf(androidx.navigation.navArgument("userId") {
                    type = androidx.navigation.NavType.IntType
                }),
            ) { entry ->
                val targetUserId = entry.arguments?.getInt("userId") ?: 0
                UserProfileScreen(
                    viewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                        key = "user-$targetUserId",
                        factory = dev.amps.app.ui.screens.profile.UserProfileViewModel.Factory(
                            backendApi = container.backendApi,
                            backendSession = container.backendSession,
                            userId = targetUserId,
                        ),
                    ),
                    baseUrl = container.backendApi.effectiveBaseUrl,
                    onBack = { navController.popBackStack() },
                )
            }
            // 1.6.0: Групи — каталог, створення, учасники, пости групи, чат групи.
            composable(Routes.GROUPS) {
                dev.amps.app.ui.screens.groups.GroupsScreen(
                    viewModel = groupsViewModel,
                    baseUrl = container.backendApi.effectiveBaseUrl,
                    onOpenAuth = { navController.navigate(Routes.AUTH) },
                    onOpenUserProfile = { userId -> navController.navigate("user/$userId") },
                )
            }
            // 1.6.0: Чати — приватні діалоги; групові відкривають деталь групи.
            composable(Routes.CHATS) {
                dev.amps.app.ui.screens.chats.ChatsScreen(
                    viewModel = chatsViewModel,
                    baseUrl = container.backendApi.effectiveBaseUrl,
                    onOpenAuth = { navController.navigate(Routes.AUTH) },
                    onOpenGroup = { groupId ->
                        navController.navigateToTab(Routes.GROUPS)
                        groupsViewModel.open(groupId)
                    },
                )
            }
            // 1.6.0: Рілси — вертикальні відео 9:16 у повному екрані.
            composable(Routes.REELS) {
                dev.amps.app.ui.screens.reels.ReelsScreen(
                    viewModel = reelsViewModel,
                    baseUrl = container.backendApi.effectiveBaseUrl,
                    onOpenAuth = { navController.navigate(Routes.AUTH) },
                    onOpenUserProfile = { userId -> navController.navigate("user/$userId") },
                )
            }
            // 1.6.6: Колекції — картки аніме з персонажами (Gemini-аналіз).
            composable(Routes.COLLECTIONS) {
                val collectionsVm: dev.amps.app.ui.screens.collections.CollectionsViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(
                        factory = dev.amps.app.ui.screens.collections.CollectionsViewModel.Factory(
                            backendApi = container.backendApi,
                            backendSession = container.backendSession,
                        ),
                    )
                dev.amps.app.ui.screens.collections.CollectionsScreen(
                    viewModel = collectionsVm,
                )
            }
            // 1.2.2: каталог аніме — AniList, мій список, нагадування серій.
            // 1.3.0: тап на картку — сторінка тайтла (опис, трейлер, «Дивитись»).
            composable(Routes.ANIME) {
                val animeVm: dev.amps.app.ui.screens.anime.AnimeViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(
                        factory = dev.amps.app.ui.screens.anime.AnimeViewModel.Factory(
                            container.anilistClient,
                            container.backendApi,
                            container.backendSession,
                        ),
                    )
                AnimeScreen(
                    viewModel = animeVm,
                    onOpenDetails = { anilistId ->
                        navController.navigate("anime/detail/$anilistId")
                    },
                )
            }
            composable(
                Routes.ANIME_DETAIL,
                arguments = listOf(
                    androidx.navigation.navArgument("anilistId") {
                        type = androidx.navigation.NavType.IntType
                    },
                ),
            ) { entry ->
                val anilistId = entry.arguments?.getInt("anilistId") ?: 0
                val detailVm: dev.amps.app.ui.screens.anime.AnimeDetailViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(
                        key = "anime-detail-$anilistId",
                        factory = dev.amps.app.ui.screens.anime.AnimeDetailViewModel.Factory(
                            container.aniList,
                            anilistId,
                        ),
                    )
                val animeVm: dev.amps.app.ui.screens.anime.AnimeViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(
                        viewModelStoreOwner = androidx.compose.ui.platform.LocalContext.current as androidx.activity.ComponentActivity,
                        factory = dev.amps.app.ui.screens.anime.AnimeViewModel.Factory(
                            container.anilistClient,
                            container.backendApi,
                            container.backendSession,
                        ),
                    )
                // 1.4.0: scope для резолвера «Дивитись» — з контексту екрана.
                val activity = androidx.compose.ui.platform.LocalContext.current
                    as androidx.activity.ComponentActivity
                dev.amps.app.ui.screens.anime.AnimeDetailScreen(
                    viewModel = detailVm,
                    animeViewModel = animeVm,
                    // 1.4.0: «Дивитись» → резолвер знаходить сторінку тайтла
                    // на yummyani.me (їхній відкритий пошуковий API; тільки
                    // посилання — плеєр лишається на їхньому сайті). Порядок:
                    // клієнт напряму (Cloudflare пропускає мобільні IP),
                    // потім бекенд, потім звичайний пошук сайту.
                    onWatch = { title ->
                        activity.lifecycleScope.launch {
                            val clientResolved = runCatching {
                                container.yummyWatch.resolve(title)
                            }.getOrNull()
                            val target = clientResolved ?: when (
                                val r = container.backendApi.fetchWatchUrl(title)
                            ) {
                                is dev.amps.app.data.remote.backend.ApiResult.Success ->
                                    (r.data as? dev.amps.app.data.remote.backend.dto.WatchUrlResponse)?.url
                                else -> null
                            } ?: "https://old.yummyani.me/?s=${java.net.URLEncoder.encode(title, "UTF-8")}"
                            val encoded = java.net.URLEncoder.encode(target, "UTF-8")
                            navController.navigate("web/$encoded")
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                Routes.WEB_VIEW,
                arguments = listOf(
                    androidx.navigation.navArgument("url") {
                        type = androidx.navigation.NavType.StringType
                    },
                ),
            ) { entry ->
                val rawUrl = entry.arguments?.getString("url").orEmpty()
                val url = java.net.URLDecoder.decode(rawUrl, "UTF-8")
                dev.amps.app.ui.screens.anime.WebViewScreen(
                    url = url,
                    title = dev.amps.app.core.t("Перегляд сайту"),
                    onBack = { navController.popBackStack() },
                )
            }
            // 1.1.2: профіль — ім'я, біо, аватар; редагуються лише тут.
            // 1.4.0: Instagram-профіль — улюблене з AniList + сітка постів.
            composable(Routes.PROFILE) {
                ProfileScreen(
                    viewModel = profileViewModel,
                    baseUrl = container.backendApi.effectiveBaseUrl,
                    onBack = { navController.popBackStack() },
                    onOpenAnime = { anilistId ->
                        navController.navigate("anime/detail/$anilistId")
                    },
                    anilist = container.anilistClient,
                )
            }
            composable(Routes.SETTINGS) {
                val session by authViewModel.session.collectAsStateWithLifecycle()
                val signed = session as? SessionUi.Signed
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onOpenUpdates = { navController.navigate(Routes.UPDATE) },
                    onOpenAccount = { navController.navigate(Routes.AUTH) },
                    sessionLabel = signed?.session
                        ?.takeIf { !it.isGuest }
                        ?.login,
                )
            }
            // 1.0.9: вход, регистрация и безопасность. Аккаунт не блокирует
            // приложение — гость пользуется им целиком, поэтому экран
            // открывается по кнопке, а не принудительно на старте.
            // 1.1.3: после успешного входа/регистрации — автопереход
            // в Спільноту: сессия уже сохранена, стричка подхватит её
            // сама (CommunityViewModel следит за backendSession).
            composable(Routes.AUTH) {
                AuthScreen(
                    viewModel = authViewModel,
                    onBack = { navController.popBackStack() },
                    onAuthenticated = {
                        navController.navigate(Routes.COMMUNITY) {
                            popUpTo(Routes.AUTH) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.UPDATE) {
                UpdateScreen(
                    viewModel = updateViewModel(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen(
                    store = container.history,
                    onOpenFrame = { entry ->
                        entry.frame?.let(wikiViewModel::openStored)
                        navController.navigate(Routes.WIKI)
                    },
                    onOpenTrack = { entry ->
                        val trackId = container.musicRepository.openFromHistory(entry)
                        if (trackId != null) navController.navigate(Routes.MUSIC_TRACK)
                        else navController.navigateToTab(Routes.MUSIC)
                    },
                )
            }
            composable(Routes.MUSIC) {
                MusicScreen(
                    repository = container.newMusicRepository,
                    freeMusicApi = container.freeMusicApi,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.MUSIC_TRACK) {
                TrackWikiScreen(
                    viewModel = trackViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }

    // 1.6.3: листок «Ще» — решта розділів одним дотиком.
    if (showMoreSheet) {
        ModalBottomSheet(onDismissRequest = { showMoreSheet = false }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    dev.amps.app.core.t("Ще"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                moreTabs.forEach { tab ->
                    ListItem(
                        headlineContent = { Text(tab.label) },
                        leadingContent = { Icon(tab.icon, contentDescription = null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showMoreSheet = false
                                navController.navigateToTab(tab.route)
                            },
                    )
                }
            }
        }
    }

    // 1.0.1: ask GitHub once per launch and say so if a newer build is published.
    val updateModel = updateViewModel()
    val updateState by updateModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { updateModel.check() }

    val pending = updateState as? UpdateUiState.Available
    if (pending != null) {
        AlertDialog(
            onDismissRequest = updateModel::dismiss,
            title = { Text(dev.amps.app.core.t("Доступно обновление")) },
            text = {
                Text(
                    dev.amps.app.core.t("Установлена ") + updateModel.currentVersion +
                        dev.amps.app.core.t(", доступна ") + pending.release.versionLabel + ". " +
                        dev.amps.app.core.t("Новое приложение скачается и установится само.")
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        updateModel.dismiss()
                        navController.navigate(Routes.UPDATE)
                    },
                ) { Text(dev.amps.app.core.t("Обновить")) }
            },
            dismissButton = {
                TextButton(onClick = updateModel::dismiss) { Text(dev.amps.app.core.t("Позже")) }
            },
        )
    }
}

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
