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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.amps.app.security.Totp

/**
 * 1.0.9: вход, регистрация, двухфакторная защита.
 *
 * **Почему гостевой вход равноправен, а не «второй сорт».** Гость ищет по
 * кадру и по музыке ровно тем же кодом, что и пользователь аккаунта.
 * Разница одна — история поиска не пишется. Экран не пугает перед входом
 * и не делает гостя объектом вроде «вы почти не узнаете».
 *
 * **Честность про ограничения.** Подтверждение почты и номера в
 * приложении не настоящее: без сервера письмо и SMS отправить некуда.
 * Писать «подтверждён» было бы враньём, поэтому формулировка честная —
 * что именно гарантируется, а что нет.
 */
@Composable
fun AuthScreen(viewModel: AuthViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 1.0.9: назад уводит в приложение, а не закрывает его. Вход
            // не обязателен — гостю доступно всё то же самое, поэтому
            // кнопка «назад» здесь равна гостевому входу.
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
                state.awaitingCode -> CodeStep(state, viewModel)
                state.mode == AuthState.Mode.LOGIN -> LoginStep(state, viewModel)
                state.mode == AuthState.Mode.REGISTER -> RegisterStep(state, viewModel)
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
            "Поиск персонажа и музыки",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoginStep(state: AuthState, viewModel: AuthViewModel) {
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedTextField(
            value = login,
            onValueChange = { login = it; viewModel.clearError() },
            label = { Text("Логин") },
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
            onDone = { if (login.isNotBlank() && password.isNotBlank()) viewModel.login(login, password) },
        )

        StateMessage(state)

        Spacer(Modifier.height(12.dp))
        SubmitButton(
            text = "Войти",
            busy = state.busy,
            enabled = login.isNotBlank() && password.isNotBlank(),
            onClick = { viewModel.login(login, password) },
        )

        TextButton(onClick = { viewModel.setMode(AuthState.Mode.REGISTER) }) {
            Text("Создать аккаунт")
        }
        TextButton(onClick = { viewModel.continueAsGuest() }) {
            Text("Продолжить как гость")
        }

        Spacer(Modifier.height(16.dp))
        GuestNote()
    }
}

@Composable
private fun RegisterStep(state: AuthState, viewModel: AuthViewModel) {
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedTextField(
            value = login,
            onValueChange = { login = it; viewModel.clearError() },
            label = { Text("Логин") },
            singleLine = true,
            supportingText = { Text("от 3 символов, без пробелов") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        PasswordField(
            value = password,
            onValueChange = { password = it; viewModel.clearError() },
            label = "Пароль",
        )
        Spacer(Modifier.height(10.dp))
        PasswordField(
            value = confirm,
            onValueChange = { confirm = it; viewModel.clearError() },
            label = "Пароль ещё раз",
            onDone = { if (login.isNotBlank() && password.isNotBlank()) viewModel.register(login, password, confirm) },
        )

        StateMessage(state)

        Spacer(Modifier.height(12.dp))
        SubmitButton(
            text = "Создать аккаунт",
            busy = state.busy,
            enabled = login.isNotBlank() && password.isNotBlank() && confirm.isNotBlank(),
            onClick = { viewModel.register(login, password, confirm) },
        )

        TextButton(onClick = { viewModel.setMode(AuthState.Mode.LOGIN) }) {
            Text("Уже есть аккаунт")
        }
        TextButton(onClick = { viewModel.continueAsGuest() }) {
            Text("Продолжить как гость")
        }

        Spacer(Modifier.height(16.dp))
        StorageNote()
    }
}

@Composable
private fun CodeStep(state: AuthState, viewModel: AuthViewModel) {
    var code by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text(
            "Второй фактор",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Пароль верный. Введите код из приложения-аутентификатора для «${state.pendingLogin}».",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = code.filter { it.isDigit() }.take(Totp.DIGITS); viewModel.clearError() },
            label = { Text("Шестизначный код") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { if (code.length == Totp.DIGITS) viewModel.confirmCode(code) },
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        StateMessage(state)

        Spacer(Modifier.height(12.dp))
        SubmitButton(
            text = "Подтвердить",
            busy = state.busy,
            enabled = code.length == Totp.DIGITS,
            onClick = { viewModel.confirmCode(code) },
        )
        TextButton(onClick = { viewModel.cancelCode() }) { Text("Назад") }
    }
}

@Composable
private fun SecurityStep(state: AuthState, viewModel: AuthViewModel) {
    val account = state.account

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val secret = state.totpSecret
        if (secret != null) {
            TotpSetup(secret, account?.login.orEmpty(), state, viewModel)
        } else {
            if (account != null) {
                Text(
                    "Аккаунт: ${account.login}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (account.twoFactorEnabled) {
                        "Двухфакторная защита включена"
                    } else {
                        "Двухфакторная защита выключена"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            StateMessage(state)
            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    if (account?.twoFactorEnabled == true) {
                        viewModel.disableTwoFactor()
                    } else {
                        viewModel.beginTwoFactor()
                    }
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (account?.twoFactorEnabled == true) "Выключить 2FA" else "Включить 2FA")
            }

            Spacer(Modifier.height(10.dp))
            PasswordChangeBlock(state, viewModel)
            Spacer(Modifier.height(10.dp))
            ContactsBlock(state, viewModel)

            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { viewModel.signOut() }) { Text("Выйти из аккаунта") }
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
        "Добавьте этот ключ в Google Authenticator",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "Ключ показывается один раз. Если потеряете телефон с аутентификатором, " +
            "зайти можно будет только удалением данных приложения — аккаунт вместе с ними.",
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
    Text(
        "Имя аккаунта: AMPS ($login)",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(14.dp))
    OutlinedTextField(
        value = code,
        onValueChange = { code = code.filter { it.isDigit() }.take(Totp.DIGITS); viewModel.clearError() },
        label = { Text("Код из приложения") },
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
        text = "Включить",
        busy = state.busy,
        enabled = code.length == Totp.DIGITS,
        onClick = { viewModel.confirmTwoFactor(code) },
    )
    TextButton(onClick = { viewModel.cancelTwoFactor() }) { Text("Отмена") }
}

@Composable
private fun PasswordChangeBlock(state: AuthState, viewModel: AuthViewModel) {
    var old by remember { mutableStateOf("") }
    var fresh by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth()) {
        Text("Смена пароля", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        PasswordField(old, { old = it; viewModel.clearError() }, "Текущий пароль")
        Spacer(Modifier.height(8.dp))
        PasswordField(fresh, { fresh = it; viewModel.clearError() }, "Новый пароль")
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { viewModel.changePassword(old, fresh); old = ""; fresh = "" },
            enabled = !state.busy && old.isNotBlank() && fresh.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Изменить пароль") }
    }
}

@Composable
private fun ContactsBlock(state: AuthState, viewModel: AuthViewModel) {
    var email by remember(state.account?.email) { mutableStateOf(state.account?.email.orEmpty()) }
    var phone by remember(state.account?.phone) { mutableStateOf(state.account?.phone.orEmpty()) }

    Column(Modifier.fillMaxWidth()) {
        Text("Почта и телефон", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Сохраняются только на этом устройстве и никуда не отправляются. " +
                "Настоящее подтверждение не работает: чтобы отправить письмо или SMS, " +
                "нужен сервер и платный сервис.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Почта") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { viewModel.setEmail(email) },
            enabled = email.isNotBlank() && state.account?.email != email.trim(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Сохранить почту") }
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
        ) { Text("Сохранить номер") }
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
                    contentDescription = if (visible) "Скрыть пароль" else "Показать пароль",
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
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (busy) {
            CircularProgressIndicator(
                Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(text)
    }
}

@Composable
private fun StateMessage(state: AuthState) {
    val message = state.error ?: state.notice
    if (message == null) return
    Spacer(Modifier.height(10.dp))
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = if (state.error != null) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun GuestNote() {
    Note(
        "Гостевой режим — это полноценный доступ: поиск по кадру и по музыке " +
            "работает так же. Не сохраняется только история поиска: " +
            "гостю нечего предложить потерять, а пустой список выглядел бы " +
            "потерей данных, которой не было."
    )
}

@Composable
private fun StorageNote() {
    Note(
        "Аккаунт хранится только на этом устройстве. Данные не уходят на сервер: " +
            "истории Git безвозвратны, и хеш пароля или секрет 2FA, однажды " +
            "попавшие в коммит, остаются там навсегда."
    )
}

@Composable
private fun Note(text: String) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
        )
    }
}