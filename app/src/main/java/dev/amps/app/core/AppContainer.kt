package dev.amps.app.core

import android.content.Context
import dev.amps.app.data.local.AccountStore
import dev.amps.app.data.local.HistoryStore
import dev.amps.app.data.remote.AniListClient
import dev.amps.app.data.remote.CcMixterClient
import dev.amps.app.data.remote.CoverArtClient
import dev.amps.app.data.remote.InternetArchiveClient
import dev.amps.app.data.remote.AppearanceSearchClient
import dev.amps.app.data.remote.DanbooruClient
import dev.amps.app.data.remote.IqdbClient
import dev.amps.app.data.remote.TraceMoeClient
import dev.amps.app.data.remote.ItunesClient
import dev.amps.app.data.remote.JamendoClient
import dev.amps.app.data.remote.MusicBrainzClient
import dev.amps.app.data.remote.OpenverseClient
import dev.amps.app.data.remote.WikiClient
import dev.amps.app.data.remote.backend.BackendApi
import dev.amps.app.data.remote.backend.SyncManager
import dev.amps.app.data.repo.FrameRepository
import dev.amps.app.data.repo.MusicRepository
import dev.amps.app.imaging.AnimeTagger
import dev.amps.app.imaging.ContentAnalyzer
import dev.amps.app.media.MediaStoreImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container. The app is small enough that a DI framework would
 * only add build weight; everything is created lazily and lives for the process.
 *
 * 1.0.5: ключей у поиска по картинке больше нет вообще. IQDB работает без
 * регистрации и без ключа, поэтому пользователю нечего вводить, а приложению
 * нечего хранить.
 */
class AppContainer(private val context: Context) {

    val settings = SettingsStore(context)

    /**
     * 1.0.9: аккаунты и сессия.
     *
     * Аккаунты локальные — в приватном хранилище телефона. В репозиторий они
     * не попадают намеренно: история Git безвозвратна, и хеш пароля или
     * секрет 2FA, однажды попавшие в коммит, остаются там навсегда.
     */
    val accounts by lazy { AccountStore(context) }
    val session by lazy { SessionStore(context) }

    /**
     * Текущий режим для синхронных проверок.
     *
     * [SessionStore.session] — поток, а история спрашивает «писать или нет»
     * из обычного кода. Здесь держится последнее прочитанное значение:
     * до первого срабатывания коллектора принимается `true`, иначе первый
     * же запуск приложения гостем стёр бы возможность записи в историю
     * до того, как система успела прочитать сессию.
     */
    @Volatile
    private var writesHistory: Boolean = true

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            session.session.collect { current ->
                writesHistory = current.writesHistory
            }
        }
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val plainHttpClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    val iqdb: IqdbClient by lazy { IqdbClient(httpClient) }

    /**
     * 1.1.1: полные теги персонажей Danbooru-поста, который нашёл IQDB.
     * Работает без ключа; подробности — в [DanbooruClient].
     */
    val danbooru: DanbooruClient by lazy { DanbooruClient(httpClient) }

    /**
     * 1.0.6b: точный поиск кадра в базе серий.
     *
     * Нужен потому, что на скриншотах аниме IQDB отвечает «no relevant
     * matches», а тегер обучен на бо́ру-артах и там молчит. Единственный
     * источник, который сравнивает кадры, а не картинки целиком.
     */
    val traceMoe: TraceMoeClient by lazy { TraceMoeClient(httpClient) }

    /** 1.0.6b: собирает поисковый запрос по описанию внешности и даёт ссылки. */
    val appearanceSearch: AppearanceSearchClient by lazy { AppearanceSearchClient() }
    val aniList: AniListClient by lazy { AniListClient(httpClient) }

    val wiki: WikiClient by lazy { WikiClient(plainHttpClient) }

    val itunes: ItunesClient by lazy { ItunesClient(plainHttpClient) }
    val musicBrainz: MusicBrainzClient by lazy { MusicBrainzClient(plainHttpClient) }
    val coverArt: CoverArtClient by lazy { CoverArtClient(plainHttpClient) }
    val ccMixter: CcMixterClient by lazy { CcMixterClient(plainHttpClient) }
    val internetArchive: InternetArchiveClient by lazy { InternetArchiveClient(plainHttpClient) }

    /**
     * 1.1.1: агрегатор открытых аудио (Free Music Archive, freesound,
     * Wikimedia и другие). Ключа не требует; если анонимный лимит
     * исчерпан, источник падает баннером, не роняя остальные.
     */
    val openverse: OpenverseClient by lazy { OpenverseClient(httpClient) }
    val jamendo: JamendoClient by lazy {
        JamendoClient(httpClient) { settings.settings.first().jamendoClientId }
    }

    val history: HistoryStore by lazy {
        HistoryStore(context) { writesHistory }
    }

    val mediaStoreImporter: MediaStoreImporter by lazy { MediaStoreImporter(context) }

    /**
     * 1.1.0: бекенд API та синхронізація.
     *
     * Базовий URL бекенду береться з налаштувань (backendBaseUrl).
     * Якщо не задано — використовується дефолтний Railway URL.
     * Використовуємо runBlocking тут, бо lazy-блок не є suspend-функцією.
     */
    val backendApi: BackendApi by lazy {
        val baseUrl = runBlocking { settings.settings.first().backendBaseUrl }
        BackendApi.getOrCreate(baseUrl, httpClient)
    }

    val syncManager: SyncManager by lazy {
        SyncManager(context, backendApi, session, accounts, history)
    }

    /**
     * 1.0.6: аниме-тегер вместо ML Kit.
     *
     * Контекст у него обязателен: модель лежит в assets, а ONNX Runtime
     * открывает её по пути, поэтому тегер один раз копирует файл в `filesDir`.
     * Поэтому он ленивый — 167 МБ не должны копироваться ради приложения,
     * которым пользователь только слушает музыку.
     */
    val animeTagger: AnimeTagger by lazy { AnimeTagger(context) }
    val contentAnalyzer: ContentAnalyzer by lazy { ContentAnalyzer(animeTagger) }

    val frameRepository: FrameRepository by lazy {
        FrameRepository(
            iqdb = iqdb,
            aniList = aniList,
            history = history,
            wiki = wiki,
            contentAnalyzer = contentAnalyzer,
            traceMoe = traceMoe,
            appearanceSearch = appearanceSearch,
            danbooru = danbooru,
        )
    }
    val musicRepository: MusicRepository by lazy {
        MusicRepository(
            itunes = itunes,
            musicBrainz = musicBrainz,
            coverArt = coverArt,
            ccMixter = ccMixter,
            internetArchive = internetArchive,
            jamendo = jamendo,
            openverse = openverse,
            context = context,
            history = history,
            importer = mediaStoreImporter,
        )
    }
}
