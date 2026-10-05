package dev.amps.app.core

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.amps.app.data.local.Account
import dev.amps.app.data.local.AccountStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 1.0.9: кто сейчас в приложении.
 *
 * **Гостевой режим.** Гость пользуется приложением полностью — ищет по
 * кадру и по музыке — но история поиска ему не пишется. Это осознанный
 * выбор: гостю нечего предложить потерять, а пустая история на его
 * устройстве выглядела бы потерей данных, которой не было. Аккаунтная
 * история при этом не затрагивается: гостевой заход ничего в ней не
 * стирает.
 */
enum class SessionMode {
    /** Вошёл в аккаунт: логин и пароль приняты, второй фактор пройден, если включён. */
    ACCOUNT,

    /** Без аккаунта. Приложение работает, история не сохраняется. */
    GUEST,
}

data class Session(
    val mode: SessionMode,
    val accountId: Long,
    val login: String,
) {
    /**
     * Гость — история не пишется.
     *
     * Свойство вычисляется, а не передаётся в конструктор: значение целиком
     * выводится из [mode], и держать его отдельным полем означало бы
     * разрешить состояние «гость, но история пишется», которого быть не
     * должно ни при каких обстоятельствах.
     */
    val writesHistory: Boolean get() = mode == SessionMode.ACCOUNT

    val isGuest: Boolean get() = mode == SessionMode.GUEST

    companion object {
        val GUEST = Session(SessionMode.GUEST, 0L, "")

        fun of(account: Account) =
            Session(SessionMode.ACCOUNT, account.id, account.login)
    }
}

private val Context.sessionDataStore by preferencesDataStore(name = "amps_session")

class SessionStore(private val context: Context) {

    private object Keys {
        val mode = stringPreferencesKey("session_mode")
        val accountId = longPreferencesKey("account_id")
        val login = stringPreferencesKey("login")
    }

    /**
     * Текущая сессия.
     *
     * Если в настройках лежит аккаунт, которого больше нет в базе, сессия
     * молча падает в гостя. Иначе после удаления аккаунта приложение
     * осталось бы с несуществующим пользователем и падало бы при первом же
     * обращении к его данным.
     */
    val session: Flow<Session> = context.sessionDataStore.data.map { prefs ->
        val id = prefs[Keys.accountId] ?: 0L
        val login = prefs[Keys.login].orEmpty()
        if (prefs[Keys.mode] == SessionMode.ACCOUNT.name && id > 0L && login.isNotBlank()) {
            Session(SessionMode.ACCOUNT, id, login)
        } else {
            Session.GUEST
        }
    }

    suspend fun signIn(account: Account) {
        context.sessionDataStore.edit {
            it[Keys.mode] = SessionMode.ACCOUNT.name
            it[Keys.accountId] = account.id
            it[Keys.login] = account.login
        }
    }

    /** Выход всегда приводит в гостя, а не в пустое состояние. */
    suspend fun signOut() {
        context.sessionDataStore.edit {
            it[Keys.mode] = SessionMode.GUEST.name
            it.remove(Keys.accountId)
            it.remove(Keys.login)
        }
    }

    suspend fun continueAsGuest() = signOut()

    /**
     * Сверяет сохранённую сессию с базой и убирает её, если аккаунта нет.
     * Вызывается при старте приложения.
     */
    suspend fun reconcile(accounts: AccountStore) {
        val current = runCatching { session.first() }.getOrNull() ?: return
        if (current.mode != SessionMode.ACCOUNT) return
        if (accounts.findById(current.accountId) == null) signOut()
    }

    companion object {
        @Suppress("unused")
        val LEGACY_GUEST_FLAG = booleanPreferencesKey("guest_mode")
    }
}