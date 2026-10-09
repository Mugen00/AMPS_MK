package dev.amps.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * 1.2.0 (патч 23): вбудований відеоплеєр у стилі Instagram.
 *
 * Пост або сторіс грають ПРЯМО в списку: автозапуск, повтор, за замовчуванням
 * без звуку для стрічки (tap по іконці — звук), без системних контролів.
 * Життєвий цикл: плеєр звільняється при виході з композиції; у фоні
 * застосунку — пауза, повернення — відновлення.
 *
 * TextureView замість SurfaceView: всередині Compose Dialog (переглядач
 * сторіс) SurfaceView неправильно з-порядковується з оверлеями.
 */
@Composable
fun InlineVideo(
    url: String,
    modifier: Modifier = Modifier,
    mutedByDefault: Boolean = true,
    loop: Boolean = true,
    /**
     * Керований звук (для сторіс: тап-зони навігації вкривають весь екран,
     * тож їхня кнопка звуку живе в хедері переглядача і приходить сюди).
     * null — некерований режим: кнопка всередині кутка (пости).
     */
    mutedOverride: Boolean? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            playWhenReady = true
            volume = if (mutedOverride ?: mutedByDefault) 0f else 1f
            prepare()
        }
    }
    var internalMuted by remember(url) { mutableStateOf(mutedByDefault) }
    val muted = mutedOverride ?: internalMuted

    // Синхронізація гучності з будь-яким джерелом зміни.
    LaunchedEffect(muted) {
        player.volume = if (muted) 0f else 1f
    }

    val observer = remember(player) {
        LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> player.pause()
                Lifecycle.Event.ON_RESUME -> if (player.playWhenReady) player.play()
                else -> Unit
            }
        }
    }
    DisposableEffect(lifecycleOwner, player) {
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.release()
        }
    }

    Box(modifier) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setKeepContentOnPlayerReset(true)
                    this.player = player
                }
            },
            update = { view -> if (view.player !== player) view.player = player },
            modifier = Modifier.matchParentSize(),
        )
        // Тап по відео — пауза/продовжити (як в Instagram).
        Box(
            Modifier
                .matchParentSize()
                .clickable {
                    if (player.isPlaying) player.pause() else player.play()
                },
        )
        // Кнопка звуку в кутку — лише в некерованому режимі (пости).
        if (mutedOverride == null) {
            IconButton(
                onClick = { internalMuted = !internalMuted },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(40.dp),
            ) {
                Icon(
                    if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    contentDescription = if (muted) "Увімкнути звук" else "Вимкнути звук",
                    tint = Color.White,
                )
            }
        }
    }
}
