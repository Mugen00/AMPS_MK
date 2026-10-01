package dev.kagami.app.core

import android.content.Context
import dev.kagami.app.data.local.HistoryStore
import dev.kagami.app.data.remote.AniListClient
import dev.kagami.app.data.remote.BridgeClient
import dev.kagami.app.data.remote.CcMixterClient
import dev.kagami.app.data.remote.CoverArtClient
import dev.kagami.app.data.remote.InternetArchiveClient
import dev.kagami.app.data.remote.ItunesClient
import dev.kagami.app.data.remote.MusicBrainzClient
import dev.kagami.app.data.repo.FrameRepository
import dev.kagami.app.data.repo.MusicRepository
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

    val bridge: BridgeClient by lazy { BridgeClient(httpClient, settings) }
    val aniList: AniListClient by lazy { AniListClient(httpClient) }

    val itunes: ItunesClient by lazy { ItunesClient(plainHttpClient) }
    val musicBrainz: MusicBrainzClient by lazy { MusicBrainzClient(plainHttpClient) }
    val coverArt: CoverArtClient by lazy { CoverArtClient(plainHttpClient) }
    val ccMixter: CcMixterClient by lazy { CcMixterClient(plainHttpClient) }
    val internetArchive: InternetArchiveClient by lazy { InternetArchiveClient(plainHttpClient) }

    val history: HistoryStore by lazy { HistoryStore(context) }

    val frameRepository: FrameRepository by lazy { FrameRepository(bridge, aniList, history) }
    val musicRepository: MusicRepository by lazy {
        MusicRepository(
            itunes = itunes,
            musicBrainz = musicBrainz,
            coverArt = coverArt,
            ccMixter = ccMixter,
            internetArchive = internetArchive,
            context = context,
            history = history,
        )
    }
}
