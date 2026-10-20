package dev.amps.app.music.player

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

/**
 * Stub MediaSessionService для фонового відтворення музики.
 * Спрощена версія для збірки.
 */
class AmpsMediaSessionService {

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    fun onCreate() {
        ExoPlayerProvider.getPlayer(this as Context)
    }

    fun onDestroy() {
        scope.cancel()
    }

    companion object {
        /** Запустити сервіс (Foreground). */
        fun start(context: Context) {
            val intent = Intent(context, AmpsMediaSessionService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Зупинити сервіс. */
        fun stop(context: Context) {
            val intent = Intent(context, AmpsMediaSessionService::class.java)
            context.stopService(intent)
        }
    }
}