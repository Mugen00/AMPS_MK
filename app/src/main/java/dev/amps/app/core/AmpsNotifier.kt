package dev.amps.app.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.amps.app.MainActivity

/**
 * 1.1.2: сповіщення застосунку — два канали.
 *
 * - "downloads" — завершення завантаження трека;
 * - "community" — активність у Спільноті (нові пости, лайки, репости).
 *
 * Дозволу POST_NOTIFICATIONS (Android 13+) додаток звідси не випрошує:
 * канали створюються одразу, а сповіщення просто не показуються, поки
 * користувач не дав дозвіл — його запитує екран Спільноти. Без звуку —
 * тихий текст у шторці.
 */
class AmpsNotifier(private val context: Context) {

    companion object {
        const val CHANNEL_DOWNLOADS = "downloads"
        const val CHANNEL_COMMUNITY = "community"

        private const val NOTIFICATION_ID_COMMUNITY = 299

        /** Кожне завантаження — свій id: шторка не зливає треки в один. */
        @Volatile
        private var downloadIdCounter = 3000

        private fun nextDownloadId(): Int {
            downloadIdCounter += 1
            return downloadIdCounter
        }
    }

    /** Створює канали. Ідемпотентно — викликається щоразу при старті. */
    fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val downloads = NotificationChannel(
            CHANNEL_DOWNLOADS,
            "Завантаження",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "Завантаження треків завершено" }
        val community = NotificationChannel(
            CHANNEL_COMMUNITY,
            "Спільнота",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "Нові пости, лайки та репости" }
        manager.createNotificationChannel(downloads)
        manager.createNotificationChannel(community)
    }

    /**
     * «Завантаження завершено». Викликається з MusicRepository; без дозволу
     * мовчки пропускається — користувачеві ніщо не заважає.
     */
    fun notifyDownloadComplete(title: String, artist: String) {
        notify(
            channel = CHANNEL_DOWNLOADS,
            id = nextDownloadId(),
            title = "Завантажено",
            text = listOfNotNull(
                title.takeIf { it.isNotBlank() },
                artist.takeIf { it.isNotBlank() }?.let { "• $it" },
            ).joinToString(" "),
        )
    }

    /** Сповіщення про активність у Спільноті; одне на цикл перевірки. */
    fun notifyCommunityActivity(title: String, text: String) {
        notify(channel = CHANNEL_COMMUNITY, id = 2100, title = title, text = text)
    }

    /** Чи має застосунок право показувати сповіщення. */
    fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = context.checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            return granted
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun notify(channel: String, id: Int, title: String, text: String) {
        if (!canPostNotifications()) return
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val pending = PendingIntent.getActivity(
            context, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Дозвіл відкликали посеред роботи — сповіщення просто не з'явиться.
        }
    }
}
