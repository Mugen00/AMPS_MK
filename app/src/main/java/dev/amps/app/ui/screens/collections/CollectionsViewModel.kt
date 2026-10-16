package dev.amps.app.ui.screens.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.amps.app.data.remote.backend.ApiResult
import dev.amps.app.data.remote.backend.BackendApi
import dev.amps.app.data.remote.backend.BackendSession
import dev.amps.app.data.remote.backend.dto.AnimeCollectionDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class CollectionsUiState(
    val loading: Boolean = true,
    /** Картки аніме; свіжі зверху (за останнім збереженням). */
    val collections: List<AnimeCollectionDto> = emptyList(),
    /** Відкрита картка — показує персонажів усередині. */
    val openCollectionId: Int? = null,
    val error: String? = null,
)

/**
 * 1.6.6: розділ «Колекції» — картки аніме з персонажами, яких користувач
 * відкрив через Gemini-аналіз фото (розділ «Картинка» → «Зберегти»).
 *
 * Оновлення в реальному часі: список перечитується при вході і далі
 * кожні 5 секунд, поки розділ відкритий — нове збереження з «Картинки»
 * з'являється в картці саме, без pull-to-refresh.
 */
class CollectionsViewModel(
    private val backendApi: BackendApi,
    private val backendSession: BackendSession,
) : ViewModel() {

    private val _state = MutableStateFlow(CollectionsUiState())
    val state: StateFlow<CollectionsUiState> = _state.asStateFlow()

    init {
        refresh()
        startAutoRefresh()
    }

    /** Перечитати колекції з сервера (після збереження — миттєво). */
    fun refresh() {
        viewModelScope.launch {
            val result = backendSession.authedCall { header -> backendApi.getCollections(header) }
            val response = (result as? ApiResult.Success)?.data
                as? dev.amps.app.data.remote.backend.dto.AnimeCollectionsResponse
            when {
                result is ApiResult.Success && response != null -> {
                    // Оптимістично: зберігаємо відкриту картку відкритою.
                    val openId = _state.value.openCollectionId
                    _state.value = _state.value.copy(
                        loading = false,
                        error = null,
                        collections = response.collections,
                        openCollectionId = openId?.takeIf { id -> response.collections.any { it.id == id } },
                    )
                }
                result is ApiResult.Success -> {
                    // 200 без даних — порожній список.
                    _state.value = _state.value.copy(loading = false, collections = emptyList())
                }
                else -> _state.value = _state.value.copy(
                    loading = false,
                    error = (result as? ApiResult.Failure)?.error
                        ?: (result as? ApiResult.NetworkError)?.message
                        ?: "Не вдалося завантажити колекції",
                )
            }
        }
    }

    /** Фон: кожні 5 с перечитуємо — колекція живе сама. */
    private fun startAutoRefresh() {
        viewModelScope.launch {
            while (isActive) {
                delay(5_000)
                val result = backendSession.authedCall { header -> backendApi.getCollections(header) }
                val response = (result as? ApiResult.Success)?.data
                    as? dev.amps.app.data.remote.backend.dto.AnimeCollectionsResponse
                if (response != null) {
                    val openId = _state.value.openCollectionId
                    _state.value = _state.value.copy(
                        loading = false,
                        error = null,
                        collections = response.collections,
                        openCollectionId = openId?.takeIf { id -> response.collections.any { it.id == id } },
                    )
                }
            }
        }
    }

    /** Відкрити/закрити картку аніме (список персонажів). */
    fun toggleCollection(collectionId: Int) {
        _state.value = _state.value.copy(
            openCollectionId = if (_state.value.openCollectionId == collectionId) null else collectionId,
        )
    }

    /** Видалити картку аніме разом з персонажами. */
    fun deleteCollection(collectionId: Int) {
        viewModelScope.launch {
            val result = backendSession.authedCall { header ->
                backendApi.deleteCollection(header, collectionId)
            }
            if (result is ApiResult.Success) refresh() else {
                _state.value = _state.value.copy(
                    error = (result as? ApiResult.Failure)?.error
                        ?: (result as? ApiResult.NetworkError)?.message
                        ?: "Не вдалося видалити колекцію",
                )
            }
        }
    }

    /** Видалити одного персонажа з картки. */
    fun deleteCharacter(characterId: Int) {
        viewModelScope.launch {
            val result = backendSession.authedCall { header ->
                backendApi.deleteCollectionCharacter(header, characterId)
            }
            if (result is ApiResult.Success) refresh() else {
                _state.value = _state.value.copy(
                    error = (result as? ApiResult.Failure)?.error
                        ?: (result as? ApiResult.NetworkError)?.message
                        ?: "Не вдалося видалити персонажа",
                )
            }
        }
    }

    fun consumeError() {
        _state.value = _state.value.copy(error = null)
    }

    class Factory(
        private val backendApi: BackendApi,
        private val backendSession: BackendSession,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CollectionsViewModel(backendApi, backendSession) as T
    }
}
