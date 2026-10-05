package dev.amps.app.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.amps.app.data.local.Account
import dev.amps.app.data.local.AccountStore
import dev.amps.app.core.Session
import dev.amps.app.core.SessionStore
import dev.amps.app.security.Totp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 1.0.9: вход, регистрация и настройка защиты аккаунта.
 *
 * **Почему хеширование и проверка идут через [Dispatchers.Default].**
 * PBKDF2 на 210 000 итераций — это работа на десятки миллисекунд, и на
 * главном потоке она превратит нажатие кнопки в подвисание. Проверка пароля
 * обязана быть постоянной по времени, а без этого она ещё и выдаст себя
 * длительностью отклика.
 *
 * **Что означает [AuthState.AwaitingCode].** Пароль принят, но у аккаунта
 * включён код из приложения-аутентификатора. Это не ошибка и не отказ:
 * вход не завершён, но и не отклонён. Отдельное состояние нужно, чтобы
 * интерфейс не показывал «неверный пароль» человеку с верным паролем.
 */
data class AuthState(
    val mode: Mode = Mode.LOGIN,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    /** Пароль принят, ждём код из аутентификатора. */
    val awaitingCode: Boolean = false,
    val pendingLogin: String = "",
    /** Готовый секрет для включения 2FA; показывается один раз. */
    val totpSecret: String? = null,
    val account: Account? = null,
) {
    enum class Mode { LOGIN, REGISTER, SECURITY }

    val canSubmit: Boolean get() = !busy && !awaitingCode
}

/** Состояние приложения с точки зрения входа. */
sealed interface SessionUi {
    data object Loading : SessionUi
    data class Signed(val session: Session, val account: Account?) : SessionUi
}

class AuthViewModel(
    private val accounts: AccountStore,
    private val sessionStore: SessionStore,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _session = MutableStateFlow<SessionUi>(SessionUi.Loading)
    val session: StateFlow<SessionUi> = _session.asStateFlow()

    init {
        viewModelScope.launch {
            sessionStore.session.collect { current ->
                val account = if (current.mode == dev.amps.app.core.SessionMode.ACCOUNT) {
                    accounts.findById(current.accountId)
                } else {
                    null
                }
                _session.value = SessionUi.Signed(current, account)
            }
        }
    }

    fun setMode(mode: AuthState.Mode) {
        _state.value = AuthState(mode = mode)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null, notice = null)
    }

    fun register(login: String, password: String, confirm: String) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { accounts.register(login, password, confirm) }
            handle(result) { account ->
                sessionStore.signIn(account)
                _state.value = AuthState(
                    mode = AuthState.Mode.SECURITY,
                    account = account,
                    notice = "Аккаунт создан. Теперь можно включить двухфакторную защиту.",
                )
            }
        }
    }

    fun login(login: String, password: String) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { accounts.login(login, password) }
            handle(result) { account ->
                sessionStore.signIn(account)
                _state.value = AuthState(mode = AuthState.Mode.SECURITY, account = account)
            }
        }
    }

    /** Вторая половина входа для аккаунта с включённым 2FA. */
    fun confirmCode(code: String) {
        val current = _state.value
        if (current.busy || !current.awaitingCode) return
        _state.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                accounts.confirmSecondFactor(current.pendingLogin, code)
            }
            handle(result) { account ->
                sessionStore.signIn(account)
                _state.value = AuthState(mode = AuthState.Mode.SECURITY, account = account)
            }
        }
    }

    fun cancelCode() {
        _state.value = _state.value.copy(awaitingCode = false, pendingLogin = "", error = null)
    }

    fun continueAsGuest() {
        viewModelScope.launch {
            sessionStore.continueAsGuest()
            _state.value = AuthState()
        }
    }

    fun signOut() {
        viewModelScope.launch {
            sessionStore.signOut()
            _state.value = AuthState()
        }
    }

    /**
     * Включение 2FA: секрет показывается один раз.
     *
     * Дальше его негде посмотреть — хранится он в базе, а не на экране.
     * Поэтому если человек потерял телефон с аутентификатором, зайти можно
     * только удалив данные приложения, и об этом сказано заранее.
     */
    fun beginTwoFactor() {
        val account = _state.value.account ?: return
        val secret = Totp.newSecret()
        _state.value = _state.value.copy(totpSecret = secret, account = account, error = null)
    }

    fun cancelTwoFactor() {
        _state.value = _state.value.copy(totpSecret = null)
    }

    fun confirmTwoFactor(code: String) {
        val current = _state.value
        val secret = current.totpSecret
        val account = current.account
        if (secret == null || account == null || current.busy) return
        _state.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            if (!Totp.verify(secret, code)) {
                _state.value = current.copy(busy = false, error = "неверный код — проверь время на телефоне")
                return@launch
            }
            val result = withContext(Dispatchers.Default) {
                accounts.setTwoFactor(account, secret)
            }
            handle(result) { updated ->
                _state.value = AuthState(
                    mode = AuthState.Mode.SECURITY,
                    account = updated,
                    notice = "Двухфакторная защита включена.",
                )
            }
        }
    }

    fun disableTwoFactor() {
        val account = _state.value.account ?: return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { accounts.setTwoFactor(account, null) }
            handle(result) { updated ->
                _state.value = AuthState(
                    mode = AuthState.Mode.SECURITY,
                    account = updated,
                    notice = "Двухфакторная защита выключена.",
                )
            }
        }
    }

    fun changePassword(oldPassword: String, newPassword: String) {
        val account = _state.value.account ?: return
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                accounts.changePassword(account, oldPassword, newPassword)
            }
            handle(result) { updated ->
                _state.value = _state.value.copy(
                    busy = false, account = updated, notice = "Пароль изменён.",
                )
            }
        }
    }

    fun setEmail(email: String) {
        val account = _state.value.account ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { accounts.setEmail(account, email) }
            handle(result) { updated ->
                _state.value = _state.value.copy(account = updated, notice = "Почта сохранена.")
            }
        }
    }

    fun setPhone(phone: String) {
        val account = _state.value.account ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { accounts.setPhone(account, phone) }
            handle(result) { updated ->
                _state.value = _state.value.copy(account = updated, notice = "Номер сохранён.")
            }
        }
    }

    /**
     * Единая разборка результата.
     *
     * Принимает `suspend`-лямбду, потому что успешный путь обязан записать
     * сессию — это запись в DataStore, то есть вызов из корутины. Обычная
     * лямбда компилировалась бы, но упала бы на месте.
     */
    private suspend fun handle(
        result: AccountStore.Result,
        onSuccess: suspend (Account) -> Unit,
    ) {
        when (result) {
            is AccountStore.Result.Ok -> onSuccess(result.account)

            is AccountStore.Result.NeedSecondFactor -> _state.value = _state.value.copy(
                busy = false,
                awaitingCode = true,
                pendingLogin = result.account.login,
                error = null,
            )

            is AccountStore.Result.Taken -> _state.value = _state.value.copy(
                busy = false, error = "логин «${result.login}» уже занят",
            )

            is AccountStore.Result.WrongPassword -> _state.value = _state.value.copy(
                busy = false,
                // Не говорим, чего именно не хватило: «нет такого пользователя»
                // и «неверный пароль» — разные подсказки для перебора.
                error = "неверный логин или пароль",
            )

            is AccountStore.Result.NoSuchAccount -> _state.value = _state.value.copy(
                busy = false, error = "неверный логин или пароль",
            )

            is AccountStore.Result.WeakPassword -> _state.value = _state.value.copy(
                busy = false, error = result.reason,
            )

            is AccountStore.Result.Failed -> _state.value = _state.value.copy(
                busy = false, error = result.reason,
            )
        }
    }

    class Factory(
        private val accounts: AccountStore,
        private val sessionStore: SessionStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AuthViewModel(accounts, sessionStore) as T
    }
}