package dev.amps.app.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.amps.app.data.local.Account
import dev.amps.app.data.local.AccountStore
import dev.amps.app.core.Session
import dev.amps.app.core.SessionStore
import dev.amps.app.data.remote.backend.ApiResult
import dev.amps.app.data.remote.backend.BackendApi
import dev.amps.app.data.remote.backend.dto.*
import dev.amps.app.security.Totp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 1.1.0: вхід, реєстрація, 2FA, синхронізація з бекендом.
 *
 * Підтримує два режими:
 * - Локальний (офлайн): PBKDF2 + SQLite, працює без інтернету
 * - Бекенд (Railway): JWT + email/SMS верифікація, password reset, sync
 *
 * Якщо налаштований backendBaseUrl — реєстрація/вхід йдуть через бекенд.
 * Локальні акаунти залишаються для офлайн-режиму та як fallback.
 */
data class AuthState(
    val mode: Mode = Mode.LOGIN,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    /** Пароль прийнято, чекаємо код з аутентифікатора (локальний 2FA). */
    val awaitingLocalCode: Boolean = false,
    val pendingLocalLogin: String = "",
    /** Чекаємо код від бекенду (email/SMS верифікація). */
    val awaitingBackendCode: Boolean = false,
    val pendingBackendType: String = "", // "EMAIL_VERIFY" | "PHONE_VERIFY" | "PASSWORD_RESET" | "2FA_SETUP" | "2FA_LOGIN"
    /** Секрет для налаштування 2FA (показується один раз). */
    val totpSecret: String? = null,
    /** otpauth-посилання того ж ключа — для аутентифікаторів, що вміють вставку. */
    val totpOtpauth: String? = null,
    /** 1.1.2: логін/пароль у пам'яті на час кроку введення кода 2FA. */
    val pendingBackendLogin: String = "",
    val pendingBackendPassword: String = "",
    val account: Account? = null,
    val backendUser: BackendUser? = null,
    /**
     * 1.1.3: одноразовий сигнал «автентифікація щойно завершилася» —
     * екран входу бачить його і веде користувача у Спільноту. Ставиться
     * лише в момент завершення входу/реєстрації, ніколи при відновленні
     * збереженої сесії, щоб випадкове відкриття екрана не викидало людей.
     */
    val justAuthenticated: Boolean = false,
) {
    enum class Mode { LOGIN, REGISTER, SECURITY, PASSWORD_RESET }

    val canSubmit: Boolean get() = !busy && !awaitingLocalCode && !awaitingBackendCode
    val isBackendMode: Boolean get() = backendUser != null
}

data class BackendUser(
    val accessToken: String,
    val refreshToken: String,
    val userInfo: UserInfo
)

/** Стан додатку з точки зору входу. */
sealed interface SessionUi {
    data object Loading : SessionUi
    data class Signed(val session: Session, val account: Account?, val backendUser: BackendUser?) : SessionUi
}

class AuthViewModel(
    private val accounts: AccountStore,
    private val sessionStore: SessionStore,
    private val backendApi: BackendApi,
    private val settingsStore: dev.amps.app.core.SettingsStore,
    private val backendSession: dev.amps.app.data.remote.backend.BackendSession,
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
                _session.value = SessionUi.Signed(current, account, null)
            }
        }
        // 1.1.2: після рестарту бекенд-акаунт відновлюється зі сховища.
        // /auth/me перевіряє access-токен; при 401 BackendSession один раз
        // оновлює пару refresh-токеном. Не вийшло — звичайний вхід.
        viewModelScope.launch {
            val stored = backendSession.current() ?: return@launch
            val result = backendSession.authedCall { header -> backendApi.getProfile(header) }
            val userInfo = (result as? ApiResult.Success)?.data as? UserInfo ?: return@launch
            val fresh = backendSession.current() ?: return@launch
            _state.value = _state.value.copy(
                mode = AuthState.Mode.SECURITY,
                backendUser = BackendUser(fresh.accessToken, fresh.refreshToken, userInfo),
            )
        }
    }

    fun setMode(mode: AuthState.Mode) {
        _state.value = AuthState(mode = mode)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null, notice = null)
    }

    /**
     * 1.1.3: екран входу споживає сигнал і веде у Спільноту. Скидання
     * одразу після навігації, щоб повторне відкриття екрана не стрибало.
     */
    fun consumeAuthenticated() {
        _state.value = _state.value.copy(justAuthenticated = false)
    }

    // ===== Backend registration =====
    fun registerBackend(login: String, email: String, password: String, phone: String?) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            val request = RegisterRequest(login, email, password, phone)
            val result = backendApi.register(request)
            handleBackendResult(result) { tokens, userInfo ->
                _state.value = AuthState(
                    mode = AuthState.Mode.SECURITY,
                    backendUser = BackendUser(tokens.accessToken, tokens.refreshToken, userInfo),
                    notice = "Реєстрація успішна. Перевірте email для підтвердження.",
                    awaitingBackendCode = true,
                    pendingBackendType = "EMAIL_VERIFY"
                )
            }
        }
    }

    // ===== Backend login =====
    fun loginBackend(login: String, password: String, totp: String? = null) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            val request = LoginRequest(login, password, totp)
            val result = backendApi.login(request)
            if (result is ApiResult.Failure && result.errorCode == "NEED_2FA") {
                // 1.1.2: акаунт захищений 2FA — показуємо крок введення кода.
                // Пароль лишається лише в пам'яті до завершення входу.
                _state.value = AuthState(
                    mode = AuthState.Mode.LOGIN,
                    awaitingBackendCode = true,
                    pendingBackendType = "2FA_LOGIN",
                    pendingBackendLogin = login,
                    pendingBackendPassword = password,
                    notice = "Увімкнено 2FA — введіть код з аутентифікатора",
                )
                return@launch
            }
            handleBackendResult(result) { tokens, userInfo ->
                _state.value = AuthState(
                    mode = AuthState.Mode.SECURITY,
                    backendUser = BackendUser(tokens.accessToken, tokens.refreshToken, userInfo),
                    notice = if (!userInfo.emailVerified) {
                        "Вхід успішний. Перевірте email для підтвердження."
                    } else if (!userInfo.phoneVerified && userInfo.phone != null) {
                        "Вхід успішний. Можна підтвердити телефон."
                    } else {
                        "Вхід успішний."
                    },
                    awaitingBackendCode = !userInfo.emailVerified,
                    pendingBackendType = if (!userInfo.emailVerified) "EMAIL_VERIFY" else "",
                    // 1.1.3: вхід завершено — ведемо у Спільноту.
                    justAuthenticated = !userInfo.emailVerified,
                )
            }
        }
    }

    private fun handleBackendResult(
        result: ApiResult,
        onSuccess: (TokenResponse, UserInfo) -> Unit
    ) {
        when (result) {
            is ApiResult.Success -> {
                val tokens = result.data as TokenResponse
                // 1.1.2: токени зберігаються — сесія переживає рестарт.
                viewModelScope.launch { backendSession.save(tokens) }
                onSuccess(tokens, tokens.user)
            }
            is ApiResult.Failure -> _state.value = _state.value.copy(busy = false, error = result.error)
            is ApiResult.NetworkError -> _state.value = _state.value.copy(busy = false, error = result.message)
        }
    }

    // ===== Backend code verification (email/phone/password reset) =====
    fun verifyBackendCode(code: String) {
        val current = _state.value
        if (current.busy || !current.awaitingBackendCode) return
        // Код 2FA перевіряється окремим захищеним ендпоінтом, а не /auth/verify:
        // сервер розпізнає лише EMAIL_VERIFY і PHONE_VERIFY.
        if (current.pendingBackendType == "2FA_SETUP") {
            verifyBackendTwoFactor(code)
            return
        }
        if (current.pendingBackendType == "2FA_LOGIN") {
            // Повторний вхід уже з кодом з аутентифікатора.
            loginBackend(current.pendingBackendLogin, current.pendingBackendPassword, totp = code)
            return
        }
        _state.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            val login = current.backendUser?.userInfo?.login ?: current.pendingLocalLogin
            val request = VerifyCodeRequest(login, code, current.pendingBackendType)
            val result = backendApi.verifyCode(request)
            handleBackendResult(result) { tokens, userInfo ->
                _state.value = AuthState(
                    mode = AuthState.Mode.SECURITY,
                    backendUser = BackendUser(tokens.accessToken, tokens.refreshToken, userInfo),
                    notice = when (current.pendingBackendType) {
                        "EMAIL_VERIFY" -> "Email підтверджено!"
                        "PHONE_VERIFY" -> "Телефон підтверджено!"
                        "PASSWORD_RESET" -> "Пароль скинуто!"
                        else -> "Підтверджено!"
                    },
                    awaitingBackendCode = false,
                    pendingBackendType = "",
                    // 1.1.3: підтвердження завершено — ведемо у Спільноту.
                    justAuthenticated = true,
                )
            }
        }
    }

    // ===== Local registration (offline) =====
    fun registerLocal(login: String, password: String, confirm: String) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { accounts.register(login, password, confirm) }
            handleLocalResult(result) { account ->
                sessionStore.signIn(account)
                _state.value = AuthState(
                    mode = AuthState.Mode.SECURITY,
                    account = account,
                    notice = "Локальний акаунт створено. Можна увімкнути 2FA.",
                    // 1.1.3: локальна реєстрація одразу знак у — ведемо у Спільноту.
                    justAuthenticated = true,
                )
            }
        }
    }

    // ===== Local login (offline) =====
    fun loginLocal(login: String, password: String) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { accounts.login(login, password) }
            handleLocalResult(result) { account ->
                sessionStore.signIn(account)
                // 1.1.3: локальний вхід завершено — ведемо у Спільноту.
                _state.value = AuthState(
                    mode = AuthState.Mode.SECURITY,
                    account = account,
                    justAuthenticated = true,
                )
            }
        }
    }

    private suspend fun handleLocalResult(
        result: AccountStore.Result,
        onSuccess: suspend (Account) -> Unit
    ) {
        when (result) {
            is AccountStore.Result.Ok -> onSuccess(result.account)

            is AccountStore.Result.NeedSecondFactor -> _state.value = _state.value.copy(
                busy = false,
                awaitingLocalCode = true,
                pendingLocalLogin = result.account.login,
                error = null,
            )

            is AccountStore.Result.Taken -> _state.value = _state.value.copy(
                busy = false, error = "логін «${result.login}» вже зайнятий"
            )

            is AccountStore.Result.WrongPassword,
            is AccountStore.Result.NoSuchAccount -> _state.value = _state.value.copy(
                busy = false, error = "невірний логін або пароль"
            )

            is AccountStore.Result.WeakPassword -> _state.value = _state.value.copy(
                busy = false, error = result.reason
            )

            is AccountStore.Result.Failed -> _state.value = _state.value.copy(
                busy = false, error = result.reason
            )
        }
    }

    // ===== Local 2FA code confirmation =====
    fun confirmLocalCode(code: String) {
        val current = _state.value
        if (current.busy || !current.awaitingLocalCode) return
        _state.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                accounts.confirmSecondFactor(current.pendingLocalLogin, code)
            }
            handleLocalResult(result) { account ->
                sessionStore.signIn(account)
                // 1.1.3: локальний 2FA-код прийнято — ведемо у Спільноту.
                _state.value = AuthState(
                    mode = AuthState.Mode.SECURITY,
                    account = account,
                    justAuthenticated = true,
                )
            }
        }
    }

    fun cancelCode() {
        _state.value = _state.value.copy(
            awaitingLocalCode = false,
            awaitingBackendCode = false,
            pendingLocalLogin = "",
            pendingBackendType = "",
            pendingBackendLogin = "",
            pendingBackendPassword = "",
            error = null,
            // 1.1.3: «Продовжити без підтвердження» — якщо сесія вже є,
            // це завершений вхід, і екран веде у Спільноту. Якщо сесії немає,
            // людина просто лишається на екрані входу як гість.
            justAuthenticated = _state.value.backendUser != null || _state.value.account != null,
        )
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
            backendSession.clear()
            _state.value = AuthState()
        }
    }

    // ===== 2FA (local) =====
    fun beginTwoFactor() {
        val account = _state.value.account ?: return
        val secret = Totp.newSecret()
        _state.value = _state.value.copy(
            totpSecret = secret,
            totpOtpauth = Totp.otpauthUri(secret, account.login, "AMPS"),
            account = account,
            error = null,
        )
    }

    fun cancelTwoFactor() {
        _state.value = _state.value.copy(totpSecret = null, totpOtpauth = null)
    }

    fun confirmTwoFactor(code: String) {
        val current = _state.value
        val secret = current.totpSecret
        val account = current.account
        if (secret == null || account == null || current.busy) return
        _state.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            if (!Totp.verify(secret, code)) {
                _state.value = current.copy(busy = false, error = "невірний код — перевір час на телефоні")
                return@launch
            }
            val result = withContext(Dispatchers.Default) {
                accounts.setTwoFactor(account, secret)
            }
            handleLocalResult(result) { updated ->
                _state.value = AuthState(
                    mode = AuthState.Mode.SECURITY,
                    account = updated,
                    notice = "Двохфакторна захист увімкнено."
                )
            }
        }
    }

    fun disableTwoFactor() {
        val account = _state.value.account ?: return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { accounts.setTwoFactor(account, null) }
            handleLocalResult(result) { updated ->
                _state.value = AuthState(
                    mode = AuthState.Mode.SECURITY,
                    account = updated,
                    notice = "Двохфакторна захист вимкнено."
                )
            }
        }
    }

    // ===== Backend 2FA =====
    fun setupBackendTwoFactor() {
        val current = _state.value
        val accessToken = current.backendUser?.accessToken ?: return
        if (current.busy) return
        _state.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            val result = backendApi.setupTwoFactor("Bearer $accessToken")
            when (result) {
                is ApiResult.Success -> {
                    val setup = result.data as TwoFactorSetupResponse
                    _state.value = current.copy(
                        busy = false,
                        totpSecret = setup.secret,
                        totpOtpauth = setup.otpAuthUrl,
                        notice = "Відскануйте QR-код у Google Authenticator",
                        awaitingBackendCode = true,
                        pendingBackendType = "2FA_SETUP"
                    )
                }
                is ApiResult.Failure -> _state.value = current.copy(busy = false, error = result.error)
                is ApiResult.NetworkError -> _state.value = current.copy(busy = false, error = result.message)
            }
        }
    }

    fun verifyBackendTwoFactor(code: String) {
        val current = _state.value
        val accessToken = current.backendUser?.accessToken ?: return
        if (current.busy || !current.awaitingBackendCode || current.pendingBackendType != "2FA_SETUP") return
        _state.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            val request = TwoFactorVerifyRequest(code)
            val result = backendApi.verifyTwoFactor("Bearer $accessToken", request)
            when (result) {
                is ApiResult.Success -> {
                    _state.value = current.copy(
                        busy = false,
                        awaitingBackendCode = false,
                        pendingBackendType = "",
                        notice = "2FA увімкнено",
                        // 1.1.3: налаштування 2FA завершено — ведемо у Спільноту.
                        justAuthenticated = true,
                        backendUser = current.backendUser?.copy(
                            userInfo = current.backendUser!!.userInfo.copy(twoFactorEnabled = true)
                        )
                    )
                }
                is ApiResult.Failure -> _state.value = current.copy(busy = false, error = result.error)
                is ApiResult.NetworkError -> _state.value = current.copy(busy = false, error = result.message)
            }
        }
    }

    fun disableBackendTwoFactor() {
        val current = _state.value
        val accessToken = current.backendUser?.accessToken ?: return
        _state.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            val result = backendApi.disableTwoFactor("Bearer $accessToken")
            when (result) {
                is ApiResult.Success -> {
                    _state.value = current.copy(
                        busy = false,
                        notice = "2FA вимкнено",
                        backendUser = current.backendUser?.copy(
                            userInfo = current.backendUser!!.userInfo.copy(twoFactorEnabled = false)
                        )
                    )
                }
                is ApiResult.Failure -> _state.value = current.copy(busy = false, error = result.error)
                is ApiResult.NetworkError -> _state.value = current.copy(busy = false, error = result.message)
            }
        }
    }

    // ===== Password change (local) =====
    fun changePasswordLocal(oldPassword: String, newPassword: String) {
        val account = _state.value.account ?: return
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                accounts.changePassword(account, oldPassword, newPassword)
            }
            handleLocalResult(result) { updated ->
                _state.value = _state.value.copy(
                    busy = false, account = updated, notice = "Пароль змінено."
                )
            }
        }
    }

    // ===== Backend password reset =====
    fun requestPasswordReset(email: String) {
        _state.value = _state.value.copy(mode = AuthState.Mode.PASSWORD_RESET, busy = true, error = null)
        viewModelScope.launch {
            val request = PasswordResetRequest(email)
            val result = backendApi.requestPasswordReset(request)
            when (result) {
                is ApiResult.Success -> {
                    _state.value = _state.value.copy(
                        busy = false,
                        awaitingBackendCode = true,
                        pendingBackendType = "PASSWORD_RESET",
                        notice = "Код відправлено на email. Введіть його для скидання пароля."
                    )
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(busy = false, error = result.error)
                is ApiResult.NetworkError -> _state.value = _state.value.copy(busy = false, error = result.message)
            }
        }
    }

    fun confirmPasswordReset(email: String, code: String, newPassword: String) {
        val current = _state.value
        if (current.busy || !current.awaitingBackendCode || current.pendingBackendType != "PASSWORD_RESET") return
        _state.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            val request = PasswordResetConfirmRequest(email, code, newPassword)
            val result = backendApi.confirmPasswordReset(request)
            handleBackendResult(result) { tokens, userInfo ->
                _state.value = AuthState(
                    mode = AuthState.Mode.SECURITY,
                    backendUser = BackendUser(tokens.accessToken, tokens.refreshToken, userInfo),
                    notice = "Пароль успішно скинуто."
                )
            }
        }
    }

    // ===== Email/Phone setters (local only for now) =====
    fun setEmail(email: String) {
        val account = _state.value.account ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { accounts.setEmail(account, email) }
            handleLocalResult(result) { updated ->
                _state.value = _state.value.copy(account = updated, notice = "Email збережено.")
            }
        }
    }

    fun setPhone(phone: String) {
        val account = _state.value.account ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { accounts.setPhone(account, phone) }
            handleLocalResult(result) { updated ->
                _state.value = _state.value.copy(account = updated, notice = "Номер збережено.")
            }
        }
    }

    class Factory(
        private val accounts: AccountStore,
        private val sessionStore: SessionStore,
        private val backendApi: BackendApi,
        private val settingsStore: dev.amps.app.core.SettingsStore,
        private val backendSession: dev.amps.app.data.remote.backend.BackendSession,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AuthViewModel(accounts, sessionStore, backendApi, settingsStore, backendSession) as T
    }
}