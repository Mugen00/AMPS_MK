package dev.amps.app.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.amps.app.util.readableMessage
import java.io.File
import java.io.IOException

/** MIME type of an APK for the pre-O install path. */
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

/** The applicationId every published release carries. */
private const val RELEASE_PACKAGE = "dev.amps.app"

/** Sub-folder of `getExternalFilesDir(null)` exposed by @xml/amps_file_paths. */
private const val PROVIDER_SUBDIR = "updates"

/** What the caller should do after [UpdateInstaller.install]. */
sealed interface InstallOutcome {
    /** The system package installer was started and took over the screen. */
    data object Launched : InstallOutcome

    /**
     * The app is not allowed to install packages yet. Open [settingsIntent],
     * then call [UpdateInstaller.install] again once the user is back.
     */
    data class PermissionRequired(val settingsIntent: Intent) : InstallOutcome
}

/**
 * Hands a downloaded APK to Android's own package installer — the same way
 * F-Droid does it, so the user sees the system confirmation dialog and Android
 * checks the signature for us.
 *
 * Three cases are covered:
 *  * API 26+ (`canRequestPackageInstalls()` false): the app returns
 *    [InstallOutcome.PermissionRequired] with the "unknown sources" settings
 *    intent, and the caller retries once the user comes back;
 *  * API 26+ with the permission already granted: `ACTION_INSTALL_PACKAGE` on a
 *    FileProvider URI, so no `file://` URI error can occur;
 *  * API 24/25: `ACTION_VIEW` with the APK mime type — those releases have no
 *    per-app "install unknown apps" toggle, so the permission branch is skipped
 *    entirely (and `REQUEST_INSTALL_PACKAGES` is meaningless there).
 *
 * The downloaded file is never handed over unless its manifest says it is this
 * very app: a release asset is the same package, never a different one.
 */
class UpdateInstaller(private val context: Context) {

    /** `${applicationId}.updates` — matches the provider in AndroidManifest.xml. */
    val authority: String = "${context.packageName}.updates"

    /** Below API 26 every app may install packages, so this is always true. */
    fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /**
     * "Allow this app to install unknown apps", scoped to AMPS itself.
     *
     * `Settings.ACTION_MANAGE_APP_INSTALL_UNKNOWN_SOURCES` is public since API 26
     * and its `package:` data URI drops the user straight onto the AMPS toggle,
     * so nobody has to pick the app out of a list. On API 24/25 this intent is
     * never sent: those releases have no such screen and install freely.
     */
    fun permissionSettingsIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_APP_INSTALL_UNKNOWN_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Verifies the APK and shows the system installer.
     *
     * @param apkPath absolute path returned by [UpdateDownloader.download].
     * @throws IOException with a Russian, user-facing message when the file is
     *   missing, unreadable, not an APK, or not this app.
     */
    @Suppress("DEPRECATION") // Intent.ACTION_INSTALL_PACKAGE: deprecated in API 29, still the documented path.
    fun install(apkPath: String, activity: Activity): InstallOutcome {
        val file = File(apkPath)
        if (!file.isFile) throw IOException("Файл обновления не найден")

        // Never install something that is not AMPS: parse the archive manifest.
        requireSameApp(file)

        val shareable = moveToProviderRoot(file)

        if (!canInstallPackages()) {
            return InstallOutcome.PermissionRequired(permissionSettingsIntent())
        }

        val uri = try {
            FileProvider.getUriForFile(context, authority, shareable)
        } catch (error: IllegalArgumentException) {
            throw IOException("Система не разрешает открыть файл обновления: ${error.readableMessage()}")
        }

        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri)
        } else {
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME_TYPE)
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        try {
            activity.startActivity(intent)
        } catch (error: Exception) {
            throw IOException("Системный установщик недоступен: ${error.readableMessage()}")
        }

        return InstallOutcome.Launched
    }

    /**
     * Refuses anything that is not this app: `getPackageArchiveInfo` reads the
     * manifest of an uninstalled archive, so it works on any API 24+.
     */
    private fun requireSameApp(file: File) {
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: throw IOException("Файл не распознан как APK — установка отменена")

        val archivePackage = info.packageName
        // The release applicationId, or whatever this build was installed as
        // (a debug install is `dev.amps.app.debug`).
        val allowed = archivePackage == RELEASE_PACKAGE || archivePackage == context.packageName
        if (!allowed) {
            throw IOException(
                "В файле пакет $archivePackage, а приложение — ${context.packageName}. " +
                    "Установка отменена",
            )
        }
    }

    /**
     * The APK is downloaded into `getExternalFilesDir(DIRECTORY_DOWNLOADS)`,
     * while `@xml/amps_file_paths` exposes `<external-files-path path="updates/"/>`,
     * which FileProvider resolves against `getExternalFilesDir(null)`. Those are
     * two different roots, so a file sitting in the download folder would be
     * refused by `getUriForFile` with "Failed to find configured root".
     *
     * Both paths are the same filesystem, so the file is *moved* (a rename, no
     * data copy) into the exposed folder right before installation. A file that
     * is already there is used in place.
     */
    private fun moveToProviderRoot(file: File): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val exposed = File(root, PROVIDER_SUBDIR)

        val sameDirectory = runCatching {
            file.canonicalFile.parentFile == exposed.canonicalFile
        }.getOrDefault(false)
        if (sameDirectory) return file

        if (!exposed.exists() && !exposed.mkdirs()) {
            throw IOException("Не удалось подготовить папку для установки")
        }

        val target = File(exposed, file.name)
        if (target.exists() && !target.delete()) {
            throw IOException("Не удалось заменить старый файл обновления")
        }
        if (!file.renameTo(target)) {
            // Rename can fail across volumes; a copy still gets the job done.
            file.copyTo(target, overwrite = true)
            file.delete()
        }
        return target
    }
}
