package dev.amps.app.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.amps.app.data.remote.backend.ApiResult
import dev.amps.app.data.remote.backend.BackendApi
import dev.amps.app.data.remote.backend.BackendSession
import dev.amps.app.data.remote.backend.dto.AvatarResponse
import dev.amps.app.data.remote.backend.dto.ProfileDto
import dev.amps.app.data.remote.backend.dto.ProfileUpdateRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 1.1.2: стан сторінки профіля — перегляд і редагування.
 *
 * Профіль створюється на сервері ліниво при першому читанні, тому навіть
 * акаунт з 1.1.0 побачить свій профіль із дефолтами (ім'я = логін).
 */
data class ProfileUiState(
    val loading: Boolean = false,
    val saving: Boolean = false,
    val uploadingAvatar: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val login: String = "",
    val displayName: String = "",
    val bio: String = "",
    /** Відносний шлях аватара ("/media/avatar/…") або null. */
    val avatarPath: String? = null,
    val dirty: Boolean = false,
)

class ProfileViewModel(
    private val backendApi: BackendApi,
    private val backendSession: BackendSession,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val result = backendSession.authedCall { header -> backendApi.fetchProfile(header) }
            when (result) {
                is ApiResult.Success -> {
                    val profile = result.data as ProfileDto
                    _state.value = _state.value.copy(
                        loading = false,
                        login = profile.login,
                        displayName = profile.displayName,
                        bio = profile.bio.orEmpty(),
                        avatarPath = profile.avatarPath,
                        dirty = false,
                    )
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(loading = false, error = result.error)
                is ApiResult.NetworkError -> _state.value = _state.value.copy(loading = false, error = result.message)
            }
        }
    }

    fun setDisplayName(text: String) {
        _state.value = _state.value.copy(displayName = text.take(64), dirty = true)
    }

    fun setBio(text: String) {
        _state.value = _state.value.copy(bio = text.take(280), dirty = true)
    }

    /** Зберігає ім'я та біо; порожнє біо означає «без біо». */
    fun save() {
        val current = _state.value
        if (current.saving) return
        viewModelScope.launch {
            _state.value = current.copy(saving = true, error = null)
            val result = backendSession.authedCall { header ->
                backendApi.updateProfile(
                    header,
                    ProfileUpdateRequest(
                        displayName = current.displayName.trim(),
                        bio = current.bio.trim().takeIf { it.isNotEmpty() },
                    ),
                )
            }
            when (result) {
                is ApiResult.Success -> {
                    val profile = result.data as ProfileDto
                    _state.value = _state.value.copy(
                        saving = false,
                        displayName = profile.displayName,
                        bio = profile.bio.orEmpty(),
                        avatarPath = profile.avatarPath,
                        dirty = false,
                        notice = "Профіль збережено",
                    )
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, error = result.error)
                is ApiResult.NetworkError -> _state.value = _state.value.copy(saving = false, error = result.message)
            }
        }
    }

    /** Аватар: jpeg/png/webp до 10 МБ, сервер перетворює на файл у сховищі. */
    fun uploadAvatar(bytes: ByteArray) {
        if (_state.value.uploadingAvatar) return
        viewModelScope.launch {
            _state.value = _state.value.copy(uploadingAvatar = true, error = null)
            val result = backendSession.authedCall { header -> backendApi.uploadAvatar(header, bytes) }
            when (result) {
                is ApiResult.Success -> {
                    val avatar = result.data as AvatarResponse
                    _state.value = _state.value.copy(
                        uploadingAvatar = false,
                        avatarPath = avatar.avatarUrl,
                        notice = "Аватар оновлено",
                    )
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(uploadingAvatar = false, error = result.error)
                is ApiResult.NetworkError -> _state.value =
                    _state.value.copy(uploadingAvatar = false, error = result.message)
            }
        }
    }

    fun clearMessages() {
        _state.value = _state.value.copy(error = null, notice = null)
    }

    class Factory(
        private val backendApi: BackendApi,
        private val backendSession: BackendSession,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ProfileViewModel(backendApi, backendSession) as T
    }
}
