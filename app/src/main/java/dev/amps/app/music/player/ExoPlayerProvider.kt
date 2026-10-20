package dev.amps.app.music.player

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.amps.app.music.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Singleton ExoPlayer для всього додатку.
 */
@UnstableApi
object ExoPlayerProvider {

    private var player: ExoPlayer? = null
    private var cache: SimpleCache? = null

    private const val CACHE_SIZE = 500 * 1024 * 1024L // 500 MB
    private const val CACHE_DIR = "exoplayer_cache"

    /** Отримати або створити ExoPlayer instance. */
    fun getPlayer(context: Context): ExoPlayer {
        return player ?: synchronized(this) {
            val appContext = context.applicationContext

            // Кеш для стрімів
            val cacheDir = File(appContext.cacheDir, CACHE_DIR)
            val evictor = LeastRecentlyUsedCacheEvictor(CACHE_SIZE)
            cache = SimpleCache(cacheDir, evictor)

            val dataSourceFactory = DefaultHttpDataSource.Factory()
            val cacheDataSourceFactory = CacheDataSource.Factory()
                .setCache(cache!!)
                .setUpstreamDataSourceFactory(dataSourceFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

            val mediaSourceFactory = DefaultMediaSourceFactory(cacheDataSourceFactory)

            player = ExoPlayer.Builder(appContext)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()

            player!!
        }
    }

    /** Очистити плеєр (при завершенні додатку). */
    fun release() {
        player?.release()
        player = null
        cache = null
    }

    /** Поточний плеєр (може бути null). */
    val currentPlayer: ExoPlayer?
        get() = player

    /** Кеш для інспекції/очищення. */
    val currentCache: SimpleCache?
        get() = cache

    /** Створити MediaItem з треку. */
    fun createMediaItem(track: Track, context: Context): MediaItem {
        return MediaItem.Builder()
            .setUri(getPlayableUri(track, context).toString())
            .setMediaId(track.id)
            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.album)
                .build())
            .build()
    }

    /** Отримати URI для відтворення (локальний файл або стрім). */
    private fun getPlayableUri(track: Track, context: Context): Uri {
        return when (track.source) {
            dev.amps.app.music.model.TrackSource.LOCAL,
            dev.amps.app.music.model.TrackSource.DOWNLOADED -> {
                track.filePath?.let { File(it) }
                    ?.takeIf { it.exists() }
                    ?.let { Uri.fromFile(it) }
                    ?: Uri.EMPTY
            }
            else -> track.streamUrl?.let { Uri.parse(it) } ?: Uri.EMPTY
        }
    }

    /** Додати трек у чергу відтворення. */
    fun addToQueue(track: Track, context: Context) {
        val player = getPlayer(context)
        val mediaItem = createMediaItem(track, context)
        player.addMediaItem(mediaItem)
    }

    /** Встановити чергу відтворення. */
    fun setQueue(tracks: List<Track>, context: Context, startIndex: Int = 0) {
        val player = getPlayer(context)
        val mediaItems = tracks.map { createMediaItem(it, context) }
        player.setMediaItems(mediaItems, startIndex, 0)
    }

    /** CoroutineScope для плеєра. */
    val playerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
}

/** Extension для легкого доступу до плеєра. */
@UnstableApi
fun Context.getPlayer(): ExoPlayer = ExoPlayerProvider.getPlayer(this)