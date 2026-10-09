package dev.amps.app.music

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 1.2.1: ЄДИНИЙ онлайн-плеєр застосунку. Завантажень більше немає —
 * тільки потік: [dev.amps.app.data.model.FreeTrack.audioUrl] віддається
 * напряму в ExoPlayer (Jamendo / Internet Archive / ccMixter / Openverse —
 * вільні ліцензії; iTunes — лише 30-секундне прев'ю, це чесно позначено).
 *
 * Живе в [dev.amps.app.AppContainer], тому трек грає при переходах
 * між екранами і переживає перерисовку списку пошуку.
 */
class MusicOnlinePlayer(context: Context) {

    /** Що грає зараз (null — нічого). */
    data class NowPlaying(
        val trackKey: String,
        val title: String,
        val artist: String,
        val coverUrl: String?,
        val audioUrl: String,
        val isPreview: Boolean,
    )

    private val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build()

    private val _now = MutableStateFlow<NowPlaying?>(null)
    val now: StateFlow<NowPlaying?> = _now.asStateFlow()

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    /** Черга поточного прослуховування (плейліст або видача пошуку). */
    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue: StateFlow<List<QueueItem>> = _queue.asStateFlow()

    data class QueueItem(
        val trackKey: String,
        val title: String,
        val artist: String,
        val audioUrl: String,
        val coverUrl: String?,
        val isPreview: Boolean,
    )

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playing.value = isPlaying
            }
        })
    }

    /**
     * Грати один трек: [queue] — що грає далі після нього (видача або плейліст),
     * [startKey] — який трек перший. Повернення в черзі: кнопки «далі/назад»
     * працюють по [queue].
     */
    fun play(queue: List<QueueItem>, startKey: String) {
        if (queue.isEmpty()) return
        val startIndex = queue.indexOfFirst { it.trackKey == startKey }.let { if (it < 0) 0 else it }
        _queue.value = queue
        val start = queue[startIndex]
        _now.value = NowPlaying(
            trackKey = start.trackKey,
            title = start.title,
            artist = start.artist,
            coverUrl = start.coverUrl,
            audioUrl = start.audioUrl,
            isPreview = start.isPreview,
        )
        player.setMediaItems(queue.map { item -> MediaItem.fromUri(item.audioUrl) }, startIndex, 0L)
        player.prepare()
        player.playWhenReady = true
    }

    fun toggle() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun next() {
        player.seekToNextMediaItem()
        syncNowFromPlayer()
    }

    fun previous() {
        player.seekToPreviousMediaItem()
        syncNowFromPlayer()
    }

    fun stop() {
        player.stop()
        player.clearMediaItems()
        _now.value = null
        _queue.value = emptyList()
    }

    fun release() {
        player.release()
    }

    /** Кнопки «далі/назад» — NowPlaying оновлюється за позицією в черзі. */
    private fun syncNowFromPlayer() {
        val index = player.currentMediaItemIndex
        val item = _queue.value.getOrNull(index) ?: return
        _now.value = NowPlaying(
            trackKey = item.trackKey,
            title = item.title,
            artist = item.artist,
            coverUrl = item.coverUrl,
            audioUrl = item.audioUrl,
            isPreview = item.isPreview,
        )
    }
}
