package dev.amps.app.core

import android.content.Context
import dev.amps.app.data.local.HistoryStore
import dev.amps.app.data.remote.AniListClient
import dev.amps.app.data.remote.CcMixterClient
import dev.amps.app.data.remote.CoverArtClient
import dev.amps.app.data.remote.DirectTraceClient
import dev.amps.app.data.remote.InternetArchiveClient
import dev.amps.app.data.remote.ItunesClient
import dev.amps.app.data.remote.JamendoClient
import dev.amps.app.data.remote.MusicBrainzClient
import dev.amps.app.data.remote.SauceClient
import dev.amps.app.data.remote.WikiClient
import dev.amps.app.data.repo.FrameRepository
import dev.amps.app.data.repo.MusicRepository
import dev.amps.app.imaging.ContentAnalyzer
import dev.amps.app.media.MediaStoreImporter
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container. The app is small enough that a DI framework would
 * only add build weight; everything is created lazily and lives for the process.
 */
class AppContainer(private val context: Context) {

    val settings = SettingsStore(context)

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

    val directTrace: DirectTraceClient by lazy { DirectTraceClient(httpClient) }
    val aniList: AniListClient by lazy { AniListClient(httpClient) }

    // 1.0.3: моста нет, каждый источник — прямой HTTPS-запрос с телефона.
    val sauce: SauceClient by lazy {
        SauceClient(httpClient) { settings.settings.first().sauceNaoApiKey }
    }
    val wiki: WikiClient by lazy { WikiClient(plainHttpClient) }

    val itunes: ItunesClient by lazy { ItunesClient(plainHttpClient) }
    val musicBrainz: MusicBrainzClient by lazy { MusicBrainzClient(plainHttpClient) }
    val coverArt: CoverArtClient by lazy { CoverArtClient(plainHttpClient) }
    val ccMixter: CcMixterClient by lazy { CcMixterClient(plainHttpClient) }
    val internetArchive: InternetArchiveClient by lazy { InternetArchiveClient(plainHttpClient) }
    val jamendo: JamendoClient by lazy {
        JamendoClient(httpClient) { settings.settings.first().jamendoClientId }
    }

    val history: HistoryStore by lazy { HistoryStore(context) }

    val mediaStoreImporter: MediaStoreImporter by lazy { MediaStoreImporter(context) }
    val contentAnalyzer: ContentAnalyzer by lazy { ContentAnalyzer() }

    val frameRepository: FrameRepository by lazy {
        FrameRepository(
            trace = directTrace,
            aniList = aniList,
            history = history,
            sauce = sauce,
            wiki = wiki,
            contentAnalyzer = contentAnalyzer,
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
            context = context,
            history = history,
            importer = mediaStoreImporter,
        )
    }
}
