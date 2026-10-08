package dev.amps.app.ui.screens.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.amps.app.security.Totp
import kotlinx.coroutines.flow.first

/**
 * 1.1.0: вход, регистрация, двухфакторная защита, синхронізація з бекендом.
 *
 * Підтримує два режими:
 * - Локальний (офлайн): PBKDF2 + SQLite, працює без інтернету
 * - Бекенд (Railway): JWT + email/SMS верифікація, password reset, sync
 */
@Composable
fun AuthScreen(
    viewModel: AuthViewModel,
    onBack: () -> Unit,
    /**
     * 1.1.3: викликається, коли вхід/реєстрація щойно завершилися успіхом —
     * навігація веде користувача у Спільноту, а не лишає його на екрані входу.
     */
    onAuthenticated: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()

    // 1.1.3: автоперехід після успішного входу/реєстрації. Флаг
    // одноразовий — споживаємо його до навігації, щоб повторне
    // відкриття екрана не викидало вже увійшовшого користувача.
    LaunchedEffect(state.justAuthenticated) {
        if (state.justAuthenticated) {
            viewModel.consumeAuthenticated()
            onAuthenticated()
        }
    }

    // 1.2.0: перечитуємо Google Client ID при кожному відкритті екрана —
    // щоб кнопка Google з'являлася одразу після внесення ID у Налаштуваннях.
    LaunchedEffect(Unit) { viewModel.refreshGoogleConfig() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 1.0.9: назад уводить в додаток, а не закриває його. Вхід
            // не обов'язковий — гостю доступне все те ж саме, тому
            // кнопка «назад» тут дорівнює гостовому входу.
            Box(Modifier.fillMaxWidth()) {
                TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    Spacer(Modifier.width(4.dp))
                    Text("Назад")
                }
            }

            AuthHeader()

            Spacer(Modifier.height(24.dp))

            when {
                // Local 2FA code
                state.awaitingLocalCode -> CodeStep(state, viewModel, isBackend = false)
                // Backend verification code (email/phone/password reset/2FA)
                state.awaitingBackendCode -> CodeStep(state, viewModel, isBackend = true)
                state.mode == AuthState.Mode.LOGIN -> LoginStep(state, viewModel)
                state.mode == AuthState.Mode.REGISTER -> RegisterStep(state, viewModel)
                state.mode == AuthState.Mode.PASSWORD_RESET -> PasswordResetStep(state, viewModel)
                else -> SecurityStep(state, viewModel)
            }
        }
    }
}

@Composable
private fun AuthHeader() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Default.Shield,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text("AMPS", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Пошук персонажа і музики",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 1.2.0: один онлайн-акаунт. Локальні (офлайн) акаунти прибрані —
 * вхід лише через сервер (Railway) або Google; локальні кроки PBKDF2
 * вилучено з UI разом із перемикачем «Локальний / Через сервер».
 *
 * Google-кнопка показується лише коли у Налаштуваннях уведено
 * Google Web Client ID: без нього Credential Manager видасть помилку
 * ще до запиту до сервера, а сервер взагалі не матиме GOOGLE_CLIENT_IDS.
 */
@Composable
private fun LoginStep(state: AuthState, viewModel: AuthViewModel) {
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedTextField(
            value = login,
            onValueChange = { login = it; viewModel.clearError() },
            label = { Text("Логін або email") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Person, null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        PasswordField(
            value = password,
            onValueChange = { password = it; viewModel.clearError() },
            label = "Пароль",
            onDone = {
                if (login.isNotBlank() && password.isNotBlank()) {
                    viewModel.loginBackend(login, password)
                }
            },
        )

        StateMessage(state)

        Spacer(Modifier.height(12.dp))
        SubmitButton(
            text = "Увійти",
            busy = state.busy,
            enabled = login.isNotBlank() && password.isNotBlank(),
            onClick = { viewModel.loginBackend(login, password) },
        )

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { viewModel.setMode(AuthState.Mode.PASSWORD_RESET) }) {
            Text("Забули пароль?")
        }
        TextButton(onClick = { viewModel.setMode(AuthState.Mode.REGISTER) }) {
            Text("Створити акаунт")
        }

        GoogleSignInBlock(state, viewModel)

        Spacer(Modifier.height(16.dp))
        GuestNote()
    }
}

/**
 * 1.2.0 fix: кнопка Google, САМОДОСТАТНЯ — читає Client ID прямо з
 * DataStore при вході в композицію. Жодної залежності від того, чи
 * встигла ViewModel прочитати налаштування: раніше стан збирався
 * один раз і кнопка зникала після перемикання кроків або взагалі
 * не з'являлася до перезапуску.
 */
@Composable
private fun GoogleSignInBlock(state: AuthState, viewModel: AuthViewModel) {
    val context = LocalContext.current
    var clientId by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        clientId = dev.amps.app.core.SettingsStore(context).settings
            .first().googleWebClientId
    }
    if (clientId.isBlank()) return
    var googlePending by remember { mutableStateOf(false) }

    // 1.2.0: тут — лише отримання ID-токена від Credential Manager
    // (системне вікно Google); обмін на токени бекенду — у viewModel.
    LaunchedEffect(googlePending) {
        if (!googlePending) return@LaunchedEffect
        googlePending = false
        if (clientId.isBlank()) return@LaunchedEffect
        try {
            val manager = androidx.credentials.CredentialManager.create(context)
            val option = com.google.android.libraries.identity.googleid.GetGoogleIdOption.Builder()
                .setServerClientId(clientId)
                .setFilterByAuthorizedAccounts(false)
                .build()
            val request = androidx.credentials.GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build()
            val response = manager.getCredential(context, request)
            val credential = response.credential
            if (credential is androidx.credentials.CustomCredential &&
                credential.type == com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val google = com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.createFrom(credential.data)
                viewModel.googleSignIn(google.idToken)
            } else {
                viewModel.googleFailed("Google повернув невідомий тип облікових даних")
            }
        } catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) {
            // Користувач закрив вікно — не помилка.
        } catch (e: Exception) {
            viewModel.googleFailed(e.message ?: "Вхід через Google не вдався")
        }
    }

    OutlinedButton(
        onClick = { googlePending = true },
        enabled = !state.googleBusy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state.googleBusy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Google…")
        } else {
            Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Увійти через Google")
        }
    }
}

@Composable
private fun RegisterStep(state: AuthState, viewModel: AuthViewModel) {
    var login by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // 1.2.0: локальних акаунтів більше немає — реєстрація лише на сервері.

        OutlinedTextField(
            value = login,
            onValueChange = { login = it; viewModel.clearError() },
            label = { Text("Логін") },
            singleLine = true,
            supportingText = { Text("від 3 символів, без пробілів") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it; viewModel.clearError() },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it; viewModel.clearError() },
            label = { Text("Телефон (опціонально)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Phone,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        PasswordField(
            value = password,
            onValueChange = { password = it; viewModel.clearError() },
            label = "Пароль",
            onDone = { /* handled by confirm field */ },
        )
        Spacer(Modifier.height(10.dp))
        PasswordField(
            value = confirm,
            onValueChange = { confirm = it; viewModel.clearError() },
            label = "Підтвердження пароля",
            onDone = {
                if (login.isNotBlank() && email.isNotBlank() && password.isNotBlank() && confirm.isNotBlank()) {
                    viewModel.registerBackend(login, email, password, phone.takeIf { it.isNotBlank() })
                }
            },
        )

        StateMessage(state)

        Spacer(Modifier.height(12.dp))
        SubmitButton(
            text = "Зареєструватися",
            busy = state.busy,
            enabled = login.isNotBlank() && email.isNotBlank() && password.isNotBlank() && confirm.isNotBlank(),
            onClick = {
                viewModel.registerBackend(login, email, password, phone.takeIf { it.isNotBlank() })
            },
        )

        Spacer(Modifier.height(12.dp))
        GoogleSignInBlock(state, viewModel)

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { viewModel.setMode(AuthState.Mode.LOGIN) }) {
            Text("Вже є акаунт")
        }

        Spacer(Modifier.height(16.dp))
        StorageNote()
    }
}

@Composable
private fun PasswordResetStep(state: AuthState, viewModel: AuthViewModel) {
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var step by remember { mutableStateOf(0) } // 0 = email, 1 = code, 2 = new password

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Скидання пароля",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        when (step) {
            0 -> {
                Text(
                    "Введіть email, на який прийде код для скидання пароля.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it; viewModel.clearError() },
                    label = { Text("Email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (email.isNotBlank()) viewModel.requestPasswordReset(email) }
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                SubmitButton(
                    text = "Надіслати код",
                    busy = state.busy,
                    enabled = email.isNotBlank(),
                    onClick = {
                        if (email.isNotBlank()) {
                            viewModel.requestPasswordReset(email)
                            if (!state.busy) step = 1
                        }
                    },
                )
            }
            1 -> {
                Text(
                    "Код надіслано на $email. Введіть його для підтвердження.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter { it.isDigit() }.take(6); viewModel.clearError() },
                    label = { Text("Шестизначний код") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (code.length == 6) step = 2 }
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                SubmitButton(
                    text = "Підтвердити код",
                    busy = state.busy,
                    enabled = code.length == 6,
                    onClick = { if (code.length == 6) step = 2 },
                )
                TextButton(onClick = { viewModel.cancelCode(); step = 0 }) { Text("Назад") }
            }
            2 -> {
                Text(
                    "Введіть новий пароль.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                PasswordField(
                    value = newPassword,
                    onValueChange = { newPassword = it; viewModel.clearError() },
                    label = "Новий пароль",
                )
                Spacer(Modifier.height(10.dp))
                PasswordField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it; viewModel.clearError() },
                    label = "Підтвердження пароля",
                    onDone = {
                        if (newPassword.isNotBlank() && confirmPassword.isNotBlank()) {
                            viewModel.confirmPasswordReset(email, code, newPassword)
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
                SubmitButton(
                    text = "Скинути пароль",
                    busy = state.busy,
                    enabled = newPassword.isNotBlank() && confirmPassword.isNotBlank(),
                    onClick = {
                        if (newPassword.isNotBlank() && confirmPassword.isNotBlank()) {
                            viewModel.confirmPasswordReset(email, code, newPassword)
                        }
                    },
                )
                TextButton(onClick = { step = 1 }) { Text("Назад до коду") }
            }
        }

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { viewModel.setMode(AuthState.Mode.LOGIN) }) {
            Text("Назад до входу")
        }
    }
}

@Composable
private fun CodeStep(state: AuthState, viewModel: AuthViewModel, isBackend: Boolean) {
    var code by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text(
            if (isBackend) {
                when (state.pendingBackendType) {
                    "EMAIL_VERIFY" -> "Верифікація email"
                    "PHONE_VERIFY" -> "Верифікація телефону"
                    "PASSWORD_RESET" -> "Скидання пароля"
                    "2FA_SETUP" -> "Налаштування 2FA"
                    "2FA_LOGIN" -> "Вхід з 2FA"
                    else -> "Підтвердження"
                }
            } else {
                "Двохфакторна авторизація"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (isBackend) {
                when (state.pendingBackendType) {
                    "EMAIL_VERIFY" -> "Введіть код, надісланий на email."
                    "PHONE_VERIFY" -> "Введіть код з SMS."
                    "PASSWORD_RESET" -> "Введіть код для скидання пароля."
                    "2FA_SETUP" -> "Введіть код з Google Authenticator."
                    "2FA_LOGIN" -> "Введіть код з аутентифікатора для «${state.pendingBackendLogin}»."
                    else -> "Введіть код для підтвердження."
                }
            } else {
                "Пароль верний. Введіть код з додатку-аутентифікатора для «${state.pendingLocalLogin}»."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = code,
            // 1.1.3: раніше тут було `code = code.filter { it.isDigit() }` —
            // внутрішній `it` фільтра затіняв введений рядок, поле мовчало,
            // і ні email-код реєстрації, ні 2FA-код ввести не вдавалося.
            onValueChange = { raw -> code = raw.filter { ch -> ch.isDigit() }.take(Totp.DIGITS); viewModel.clearError() },
            label = { Text("Шестизначний код") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { if (code.length == Totp.DIGITS) {
                    if (isBackend) viewModel.verifyBackendCode(code) else viewModel.confirmLocalCode(code)
                }},
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        StateMessage(state)

        Spacer(Modifier.height(12.dp))
        SubmitButton(
            text = "Підтвердити",
            busy = state.busy,
            enabled = code.length == Totp.DIGITS,
            onClick = {
                if (code.length == Totp.DIGITS) {
                    if (isBackend) viewModel.verifyBackendCode(code) else viewModel.confirmLocalCode(code)
                }
            },
        )
        TextButton(onClick = { viewModel.cancelCode() }) { Text("Назад") }
        // 1.1.3: реєстрація і вхід уже видають токени до моменту підтвердження.
        // Якщо лист не дійшов (наприклад, Resend не налаштований на сервері),
        // користувач може продовжити — сервер знову запропонує код при вході.
        if (isBackend && state.pendingBackendType == "EMAIL_VERIFY" && state.backendUser != null) {
            TextButton(onClick = { viewModel.cancelCode() }) {
                Text("Продовжити без підтвердження")
            }
        }
    }
}

@Composable
private fun SecurityStep(state: AuthState, viewModel: AuthViewModel) {
    val account = state.account
    val backendUser = state.backendUser

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val secret = state.totpSecret
        if (secret != null) {
            TotpSetup(secret, account?.login.orEmpty() ?: backendUser?.userInfo?.login.orEmpty() ?: "", state, viewModel)
        } else {
            if (account != null) {
                Text(
                    "Локальний акаунт: ${account.login}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (account.twoFactorEnabled) {
                        "Двохфакторна захист увімкнено"
                    } else {
                        "Двохфакторна захист вимкнено"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (backendUser != null) {
                Text(
                    "Обліковий запис: ${backendUser.userInfo.login}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        if (backendUser.userInfo.emailVerified) "Email: ✓ верифіковано" else "Email: ✗ не верифіковано",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (backendUser.userInfo.phone != null) {
                        Text(
                            if (backendUser.userInfo.phoneVerified) "Телефон: ✓ верифіковано" else "Телефон: ✗ не верифіковано",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        if (backendUser.userInfo.twoFactorEnabled) "2FA: ✓ увімкнено" else "2FA: ✗ вимкнено",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            StateMessage(state)
            Spacer(Modifier.height(12.dp))

            // Local 2FA
            if (account != null) {
                OutlinedButton(
                    onClick = {
                        if (account.twoFactorEnabled) {
                            viewModel.disableTwoFactor()
                        } else {
                            viewModel.beginTwoFactor()
                        }
                    },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (account.twoFactorEnabled) "Вимкнути 2FA" else "Увімкнути 2FA")
                }

                Spacer(Modifier.height(10.dp))
                PasswordChangeBlock(state, viewModel)
                Spacer(Modifier.height(10.dp))
                ContactsBlock(state, viewModel)
            }

            // Backend 2FA
            if (backendUser != null) {
                OutlinedButton(
                    onClick = {
                        if (backendUser.userInfo.twoFactorEnabled) {
                            viewModel.disableBackendTwoFactor()
                        } else {
                            viewModel.setupBackendTwoFactor()
                        }
                    },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (backendUser.userInfo.twoFactorEnabled) "Вимкнути 2FA (сервер)" else "Увімкнути 2FA (сервер)")
                }

                Spacer(Modifier.height(10.dp))
                // Підтвердження email: окремого ендпоінту «надіслати ще раз»
                // у 1.1.0 немає — свіжий код сервер надсилає при повторному
                // вході, і ми кажемо про це прямо, без фальшивої кнопки.
                if (!backendUser.userInfo.emailVerified) {
                    Text(
                        "Не отримали листа? Вийдіть і увійдіть знову — " +
                            "ми надішлемо новий код підтвердження.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (backendUser.userInfo.phone != null && !backendUser.userInfo.phoneVerified) {
                    Text(
                        "Код для телефону надсилається при реєстрації; " +
                            "повторне надсилання — у наступній версії.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { viewModel.signOut() }) { Text("Вийти з акаунта") }
        }
    }
}

@Composable
private fun TotpSetup(
    secret: String,
    login: String,
    state: AuthState,
    viewModel: AuthViewModel,
) {
    var code by remember { mutableStateOf("") }

    Text(
        "Додайте цей ключ у Google Authenticator",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "Ключ показується один раз. Якщо втратите телефон з аутентифікатором, " +
            "зайти можна буде лише видаленням даних додатку — акаунт разом з ними.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(14.dp))
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            secret,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(12.dp),
        )
    }
    Spacer(Modifier.height(6.dp))
    // 1.1.2: ключ для аутентифікатора тепер копіюється однією кнопкою —
    // вручну 32 випадкові символи не набрати без помилки.
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedButton(
            onClick = {
                clipboard.setText(AnnotatedString(secret))
                copied = true
            },
            modifier = Modifier.weight(1f),
        ) {
            Text(if (copied) "Скопійовано ✓" else "Скопіювати ключ")
        }
        state.totpOtpauth?.let { otpauth ->
            OutlinedButton(
                onClick = { clipboard.setText(AnnotatedString(otpauth)) },
                modifier = Modifier.weight(1f),
            ) {
                Text("Скопіювати посилання")
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        "Назва акаунту: AMPS ($login)",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(14.dp))
    OutlinedTextField(
        value = code,
        // 1.1.3: той самий дефект затінення `it` — код при увімкненні 2FA
        // не вводився.
        onValueChange = { raw -> code = raw.filter { ch -> ch.isDigit() }.take(Totp.DIGITS); viewModel.clearError() },
        label = { Text("Код з додатку") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = { if (code.length == Totp.DIGITS) viewModel.confirmTwoFactor(code) },
        ),
        modifier = Modifier.fillMaxWidth(),
    )

    StateMessage(state)

    Spacer(Modifier.height(12.dp))
    SubmitButton(
        text = if (state.isBackendMode) "Увімкнути 2FA (сервер)" else "Увімкнути",
        busy = state.busy,
        enabled = code.length == Totp.DIGITS,
        onClick = { if (code.length == Totp.DIGITS) viewModel.confirmTwoFactor(code) },
    )
    TextButton(onClick = { viewModel.cancelTwoFactor() }) { Text("Скасувати") }
}

@Composable
private fun PasswordChangeBlock(state: AuthState, viewModel: AuthViewModel) {
    var old by remember { mutableStateOf("") }
    var fresh by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth()) {
        Text("Зміна пароля", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        PasswordField(old, { old = it; viewModel.clearError() }, "Поточний пароль")
        Spacer(Modifier.height(8.dp))
        PasswordField(fresh, { fresh = it; viewModel.clearError() }, "Новий пароль")
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { viewModel.changePasswordLocal(old, fresh); old = ""; fresh = "" },
            enabled = !state.busy && old.isNotBlank() && fresh.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Змінити пароль") }
    }
}

@Composable
private fun ContactsBlock(state: AuthState, viewModel: AuthViewModel) {
    var email by remember(state.account?.email) { mutableStateOf(state.account?.email.orEmpty()) }
    var phone by remember(state.account?.phone) { mutableStateOf(state.account?.phone.orEmpty()) }

    Column(Modifier.fillMaxWidth()) {
        Text("Пошта і телефон", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Зберігаються лише на цьому пристрої і нікуди не надсилаються. " +
                "Насправді підтвердження не працює: щоб надіслати лист або SMS, " +
                "потрібен сервер і платний сервіс.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Пошта") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { viewModel.setEmail(email) },
            enabled = email.isNotBlank() && state.account?.email != email.trim(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Зберегти пошту") }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("Телефон") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { viewModel.setPhone(phone) },
            enabled = phone.isNotBlank() && state.account?.phone != phone.trim(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Зберегти номер") }
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    onDone: (() -> Unit)? = null,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next,
        ),
        keyboardActions = KeyboardActions(
            onDone = { onDone?.invoke() },
        ),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (visible) "Приховати пароль" else "Показати пароль",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SubmitButton(
    text: String,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(text)
        }
    }
}

@Composable
private fun StateMessage(state: AuthState) {
    if (state.error != null) {
        Text(
            state.error!!,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            
        )
    }
    if (state.notice != null) {
        Text(
            state.notice!!,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            
        )
    }
}

@Composable
private fun GuestNote() {
    Text(
        "Гість має повний функціонал пошуку, але історія не зберігається.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun StorageNote() {
    Text(
        "Локальні акаунти зберігаються тільки на цьому пристрої. " +
            "Пароль хешується PBKDF2 (200 000 ітерацій), секрет 2FA — AES-256.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        
        modifier = Modifier.fillMaxWidth(),
    )
}