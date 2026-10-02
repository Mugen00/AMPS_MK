package dev.amps.app.update

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.amps.app.util.readableMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Everything the update screen can be in.
 *
 * The path through the flow is linear — Idle → Checking → Available →
 * Downloading → ReadyToInstall → (NeedsInstallPermission) → Installing — and
 * any step can drop to [Failed] with a Russian message.
 */
sealed interface UpdateUiState {

    /** Nothing has been asked yet. */
    data object Idle : UpdateUiState

    /** A GitHub request is in flight. */
    data object Checking : UpdateUiState

    /** The installed build is the newest published one. */
    data class UpToDate(val current: String, val latest: String) : UpdateUiState

    /** A newer release is published; nothing has been downloaded yet. */
    data class Available(val release: UpdateRelease) : UpdateUiState

    /** The APK is streaming to disk. `totalBytes` is 0 when the size is unknown. */
    data class Downloading(val bytesRead: Long, val totalBytes: Long) : UpdateUiState {
        val fraction: Float
            get() = if (totalBytes > 0L) (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

        val percent: Int get() = (fraction * 100).toInt()

        val totalKnown: Boolean get() = totalBytes > 0L
    }

    /** The APK is on disk and verified to be this app; only the tap is missing. */
    data class ReadyToInstall(val file: String) : UpdateUiState {
        val fileName: String
            get() = file.substringAfterLast('/').substringAfterLast('\\')
    }

    /** The user has to allow installs from this app before it can continue. */
    data object NeedsInstallPermission : UpdateUiState

    /** The system installer is being started. */
    data object Installing : UpdateUiState

    /** Any step failed; [message] is already written for the user. */
    data class Failed(val message: String) : UpdateUiState
}

/**
 * Drives the over-the-air update: check GitHub, download the release APK, hand
 * it to the system installer.
 *
 * The view model owns no Android context beyond what the three helpers already
 * hold, so the flow can be reasoned about (and the state restored) without one.
 */
class UpdateViewModel(
    private val checker: UpdateChecker,
    private val downloader: UpdateDownloader,
    private val installer: UpdateInstaller,
) : ViewModel() {

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)

    /** The single source of truth for the screen. */
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    /** `versionName` of this build, known before the first check. */
    val currentVersion: String = checker.currentVersion()

    /**
     * The release being installed, kept so the release notes stay on screen
     * while the APK downloads and after it is ready.
     */
    var release: UpdateRelease? = null
        private set

    private var apkPath: String? = null
    private var busy: Job? = null

    /** Asks GitHub whether anything newer exists. */
    fun check() {
        if (_state.value is UpdateUiState.Checking) return
        busy = viewModelScope.launch {
            _state.value = UpdateUiState.Checking
            try {
                when (val result = checker.check()) {
                    is UpdateCheck.UpToDate -> {
                        release = null
                        apkPath = null
                        _state.value = UpdateUiState.UpToDate(current = result.current, latest = result.latest)
                    }

                    is UpdateCheck.Available -> {
                        apkPath = null
                        release = result.release
                        _state.value = UpdateUiState.Available(result.release)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = UpdateUiState.Failed("Проверка обновлений не удалась: ${error.readableMessage()}")
            }
        }
    }

    /** Streams [release]'s APK to disk, reporting byte counts into the state. */
    fun download() {
        val target = release ?: return
        if (_state.value is UpdateUiState.Downloading) return
        busy = viewModelScope.launch {
            _state.value = UpdateUiState.Downloading(bytesRead = 0L, totalBytes = target.apk.sizeBytes)
            try {
                val path = downloader.download(target) { read, total ->
                    _state.value = UpdateUiState.Downloading(bytesRead = read, totalBytes = total)
                }
                apkPath = path
                _state.value = UpdateUiState.ReadyToInstall(file = path)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                apkPath = null
                _state.value = UpdateUiState.Failed("Не удалось скачать обновление: ${error.readableMessage()}")
            }
        }
    }

    /**
     * Shows the system installer for the downloaded APK.
     *
     * On API 26+ without the "install unknown apps" permission this leaves the
     * state at [UpdateUiState.NeedsInstallPermission]; the screen then opens
     * [openInstallPermissionSettings] and calls [resumeAfterPermission] on the
     * way back.
     */
    fun install(activity: Activity) {
        val file = apkPath ?: return
        busy = viewModelScope.launch {
            _state.value = UpdateUiState.Installing
            try {
                _state.value = when (val outcome = installer.install(file, activity)) {
                    is InstallOutcome.PermissionRequired -> UpdateUiState.NeedsInstallPermission
                    // The installer is on screen; coming back leaves the button ready.
                    InstallOutcome.Launched -> UpdateUiState.ReadyToInstall(file)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = UpdateUiState.Failed("Установка не удалась: ${error.readableMessage()}")
            }
        }
    }

    /**
     * Sends the user to the per-app "install unknown apps" screen.
     *
     * On failure the state drops to [UpdateUiState.Failed]; the downloaded APK
     * is kept, so [retry] goes straight back to installing it.
     */
    fun openInstallPermissionSettings(activity: Activity) {
        if (apkPath == null) return
        try {
            activity.startActivity(installer.permissionSettingsIntent())
        } catch (error: Exception) {
            _state.value = UpdateUiState.Failed(
                "Не удалось открыть настройки установки: ${error.readableMessage()}",
            )
        }
    }

    /** Called when the screen resumes while waiting for that permission. */
    fun resumeAfterPermission(activity: Activity) {
        if (_state.value !is UpdateUiState.NeedsInstallPermission) return
        if (!installer.canInstallPackages()) return
        install(activity)
    }

    /** Repeats the step that failed: install, then download, then check. */
    fun retry(activity: Activity?) {
        when {
            activity != null && apkPath != null -> install(activity)
            release != null -> download()
            else -> check()
        }
    }

    /** Clears the whole flow and waits for a new check. */
    fun dismiss() {
        busy?.cancel()
        busy = null
        release = null
        apkPath = null
        _state.value = UpdateUiState.Idle
    }

    /**
     * Builds the view model without a DI graph. Once `AppContainer` grows these
     * services, the factory should read them from there instead of constructing
     * them per view model.
     */
    class Factory(private val context: Context) : ViewModelProvider.Factory {

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(UpdateViewModel::class.java)) {
                "UpdateViewModelFactory не умеет создавать ${modelClass.name}"
            }
            val app = context.applicationContext
            return UpdateViewModel(
                checker = UpdateChecker(app),
                downloader = UpdateDownloader(app),
                installer = UpdateInstaller(app),
            ) as T
        }
    }
}
