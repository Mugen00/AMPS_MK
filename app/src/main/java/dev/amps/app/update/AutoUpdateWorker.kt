package dev.amps.app.update

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 1.2.0: фонові патчі — оновлення, що не міняють versionName.
 *
 * Як це працює:
 *  1. Поки застосунок ЗАКРИТИЙ, періодична робота (WorkManager, кожні ~4
 *     години при доступній мережі) питає GitHub, чи є новіший реліз.
 *     Патч розпізнається за `build-code: N` у тілі релізу: N більший за
 *     versionCode встановленої збірки — є патч, навіть коли versionName
 *     обох «1.2.0». Глобальні релізи (1.2.1 і далі) ловляться як і раніше —
 *     за versionName.
 *  2. Знайдений APK завантажується у приватну папку updates — телефон
 *     може це робити з вимкненим застосунком.
 *  3. При наступному відкритті застосунку система встановлення запускається
 *     сама: користувач бачить тільки системне вікно «Оновити» — один тап.
 *
 * Чесна межа платформи: тиха установка БЕЗ жодного тапу на Android
 * неможлива для застосунків, встановлених вручну (право на безшумне
 * оновлення — привілейоване, є лише у Play Store та системних). Тому
 * «відкрив — вже оновлене» реалізовано як «відкрив — одразу вікно
 * встановлення з готовим файлом».
 */
class AutoUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            val checker = UpdateChecker(applicationContext)
            val check = checker.check()
            if (check is UpdateCheck.Available) {
                val release = check.release
                val already = AutoUpdatePending.read(applicationContext)
                val apkExists = already?.let { File(it.apkPath).isFile } == true
                if (already?.tag == release.tag && apkExists) {
                    return Result.success() // цей патч уже завантажений
                }
                val path = UpdateDownloader(applicationContext).download(release)
                AutoUpdatePending.write(
                    applicationContext,
                    AutoUpdatePending.PendingUpdate(
                        tag = release.tag,
                        buildCode = release.buildCode ?: 0L,
                        versionLabel = release.versionLabel,
                        apkPath = path,
                        downloadedAt = System.currentTimeMillis(),
                    ),
                )
            }
        } catch (_: Exception) {
            // Фонова перевірка мовчить: мережі немає, GitHub обмежив —
            // наступний період спробує знову.
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "amps-auto-update"

        /** Періодична перевірка+завантаження; викликається один раз зі [dev.amps.app.AmpsApp]. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AutoUpdateWorker>(4, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}

/** Маркер «патч завантажений, чекає установки» — файл поруч із самим APK. */
object AutoUpdatePending {

    @Serializable
    data class PendingUpdate(
        val tag: String,
        /** build-code релізу; 0 — реліз без мітки (глобальний). */
        val buildCode: Long = 0L,
        val versionLabel: String = "",
        val apkPath: String,
        val downloadedAt: Long,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun file(context: Context): File =
        File(UpdateDownloader(context).downloadDirectory(), "pending-update.json")

    fun write(context: Context, pending: PendingUpdate) {
        runCatching {
            val target = file(context)
            target.parentFile?.mkdirs()
            target.writeText(json.encodeToString(PendingUpdate.serializer(), pending))
        }
    }

    fun read(context: Context): PendingUpdate? = runCatching {
        val f = file(context)
        if (!f.isFile) return null
        json.decodeFromString(PendingUpdate.serializer(), f.readText())
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}

/**
 * Викликається з MainActivity.onCreate: якщо патч завантажений у фоні —
 * одразу віддаємо його системному встановлювачу. Один тап «Оновити» —
 * і застосунок оновлений; ніяких «йти в UPDATE і качати».
 */
fun maybeAutoInstallPendingUpdate(activity: Activity) {
    val pending = AutoUpdatePending.read(activity) ?: return
    val apk = File(pending.apkPath)
    if (!apk.isFile) {
        AutoUpdatePending.clear(activity)
        return
    }
    // Вже на цій або новішій збірці — маркер більше не потрібен.
    val installed = runCatching {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }.getOrNull() ?: 0L
    if (pending.buildCode in 1..installed) {
        AutoUpdatePending.clear(activity)
        return
    }
    // Без права «встановлювати невідомі застосунки» тихий запуск лише
    // показав би помилку — залишаємо установку екрану UPDATE.
    val installer = UpdateInstaller(activity)
    if (!installer.canInstallPackages()) return
    AutoUpdatePending.clear(activity)
    runCatching { installer.install(apk.absolutePath, activity) }
}
