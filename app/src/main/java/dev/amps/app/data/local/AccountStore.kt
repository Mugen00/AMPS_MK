package dev.amps.app.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.amps.app.security.PasswordHasher

/**
 * 1.0.9: аккаунты пользователя.
 *
 * **Где живут данные.** Только в приватном хранилище приложения на телефоне.
 * В репозиторий или на внешний сервис они не попадают: история Git
 * безвозвратна, один утёкший коммит раскрывает всех зарегистрированных
 * сразу и навсегда, а правила GitHub прямо запрещают использовать
 * репозиторий как базу. Поэтому аккаунты локальные, и это осознанный
 * выбор, а не недоработка.
 *
 * **Почему SQLite, а не Room.** Сборка идёт с `--offline` и не может
 * скачать новую зависимость. `SQLiteOpenHelper` входит в Android.
 *
 * **Чего здесь нет.** Пароля текстом, секрета 2FA в открытом виде в
 * настройках и синхронизации между устройствами: её нечем делать без
 * сервера, а имитация синхронизации через файл означала бы ровно то
 * хранилище, от которого мы отказались.
 */
data class Account(
    val id: Long,
    val login: String,
    val digest: PasswordHasher.Digest,
    val totpSecret: String?,
    val email: String?,
    val phone: String?,
    val createdAt: Long,
) {
    val twoFactorEnabled: Boolean get() = !totpSecret.isNullOrBlank()
}

class AccountStore(context: Context) {

    private val helper = object : SQLiteOpenHelper(
        context.applicationContext, DATABASE, null, VERSION
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_LOGIN TEXT NOT NULL UNIQUE,
                    $COL_SALT TEXT NOT NULL,
                    $COL_HASH TEXT NOT NULL,
                    $COL_ITERATIONS INTEGER NOT NULL,
                    $COL_TOTP TEXT,
                    $COL_EMAIL TEXT,
                    $COL_PHONE TEXT,
                    $COL_CREATED INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 1.0.9: пересоздавать таблицу здесь нельзя. Удаление на
            // апгрейде стирает хеши паролей, а восстановить их нечем:
            // исходные пароли нигде не хранятся, только хеши. Человек
            // после обновления не смог бы войти в свой же аккаунт.
            //
            // Поэтому до появления реальных миграций база просто
            // остаётся как есть. Когда схема начнёт меняться, сюда
            // добавятся `ALTER TABLE ... ADD COLUMN` для каждого нового
            // поля — они безопасны, потому что не трогают старые строки.
        }
    }

    sealed interface Result {
        data class Ok(val account: Account) : Result
        data class Taken(val login: String) : Result
        data class WrongPassword(val login: String) : Result
        data class NoSuchAccount(val login: String) : Result
        data class WeakPassword(val reason: String) : Result
        data class NeedSecondFactor(val account: Account) : Result
        data class Failed(val reason: String) : Result
    }

    /** Регистрация. Логины сравниваются без учёта регистра и пробелов. */
    fun register(login: String, password: String, confirm: String): Result {
        val name = login.trim()
        if (name.length < 3) return Result.Failed("логин от 3 символов")
        if (name.any { it.isWhitespace() }) return Result.Failed("логин без пробелов")
        if (password != confirm) return Result.Failed("пароли не совпадают")

        PasswordHasher.validate(password)?.let { return Result.WeakPassword(it) }

        val digest = PasswordHasher.create(password)
        val values = ContentValues().apply {
            put(COL_LOGIN, name.lowercase())
            put(COL_SALT, digest.salt)
            put(COL_HASH, digest.hash)
            put(COL_ITERATIONS, digest.iterations)
            put(COL_CREATED, System.currentTimeMillis())
        }
        return try {
            val id = helper.writableDatabase.insertWithOnConflict(
                TABLE, null, values, SQLiteDatabase.CONFLICT_ABORT
            )
            if (id <= 0) Result.Taken(name) else Result.Ok(read(id)!!)
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
            Result.Taken(name)
        }
    }

    /**
     * Вход. Возвращает [Result.NeedSecondFactor], когда у аккаунта включён
     * 2FA: пароль верный, но без кода из приложения-аутентификатора вход
     * не считается завершённым.
     */
    fun login(login: String, password: String): Result {
        val name = login.trim().lowercase()
        val account = findByLogin(name) ?: return Result.NoSuchAccount(login)
        if (!PasswordHasher.verify(password, account.digest)) {
            return Result.WrongPassword(login)
        }
        return if (account.twoFactorEnabled) {
            Result.NeedSecondFactor(account)
        } else {
            Result.Ok(account)
        }
    }

    /** Вторая половина входа: проверка кода для [login]. */
    fun confirmSecondFactor(login: String, code: String): Result {
        val account = findByLogin(login.trim().lowercase())
            ?: return Result.NoSuchAccount(login)
        val secret = account.totpSecret
            ?: return Result.Failed("двухфакторная защита не включена")
        return if (dev.amps.app.security.Totp.verify(secret, code)) {
            Result.Ok(account)
        } else {
            Result.Failed("неверный код")
        }
    }

    fun findByLogin(login: String): Account? {
        helper.readableDatabase.query(
            TABLE, null, "$COL_LOGIN = ?", arrayOf(login.trim().lowercase()),
            null, null, null, "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toAccount() else null
        }
    }

    fun findById(id: Long): Account? {
        helper.readableDatabase.query(
            TABLE, null, "$COL_ID = ?", arrayOf(id.toString()),
            null, null, null, "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toAccount() else null
        }
    }

    fun changePassword(account: Account, oldPassword: String, newPassword: String): Result {
        if (!PasswordHasher.verify(oldPassword, account.digest)) {
            return Result.WrongPassword(account.login)
        }
        PasswordHasher.validate(newPassword)?.let { return Result.WeakPassword(it) }
        val digest = PasswordHasher.create(newPassword)
        val values = ContentValues().apply {
            put(COL_SALT, digest.salt)
            put(COL_HASH, digest.hash)
            put(COL_ITERATIONS, digest.iterations)
        }
        helper.writableDatabase.update(TABLE, values, "$COL_ID = ?", arrayOf(account.id.toString()))
        return Result.Ok(findById(account.id) ?: account)
    }

    /** Секрет 2FA сохраняется один раз и дальше не показывается. */
    fun setTwoFactor(account: Account, secret: String?): Result {
        val values = ContentValues().apply {
            if (secret == null) putNull(COL_TOTP) else put(COL_TOTP, secret)
        }
        helper.writableDatabase.update(TABLE, values, "$COL_ID = ?", arrayOf(account.id.toString()))
        return Result.Ok(findById(account.id) ?: account)
    }

    fun setEmail(account: Account, email: String?): Result {
        val values = ContentValues().apply {
            if (email.isNullOrBlank()) putNull(COL_EMAIL) else put(COL_EMAIL, email.trim())
        }
        helper.writableDatabase.update(TABLE, values, "$COL_ID = ?", arrayOf(account.id.toString()))
        return Result.Ok(findById(account.id) ?: account)
    }

    fun setPhone(account: Account, phone: String?): Result {
        val values = ContentValues().apply {
            if (phone.isNullOrBlank()) putNull(COL_PHONE) else put(COL_PHONE, phone.trim())
        }
        helper.writableDatabase.update(TABLE, values, "$COL_ID = ?", arrayOf(account.id.toString()))
        return Result.Ok(findById(account.id) ?: account)
    }

    private fun read(id: Long): Account? =
        helper.readableDatabase.query(
            TABLE, null, "$COL_ID = ?", arrayOf(id.toString()),
            null, null, null, "1"
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toAccount() else null }

    private fun android.database.Cursor.toAccount() = Account(
        id = getLong(getColumnIndexOrThrow(COL_ID)),
        login = getString(getColumnIndexOrThrow(COL_LOGIN)),
        digest = PasswordHasher.Digest(
            salt = getString(getColumnIndexOrThrow(COL_SALT)),
            hash = getString(getColumnIndexOrThrow(COL_HASH)),
            iterations = getInt(getColumnIndexOrThrow(COL_ITERATIONS)),
        ),
        totpSecret = getString(getColumnIndexOrThrow(COL_TOTP)),
        email = getString(getColumnIndexOrThrow(COL_EMAIL)),
        phone = getString(getColumnIndexOrThrow(COL_PHONE)),
        createdAt = getLong(getColumnIndexOrThrow(COL_CREATED)),
    )

    private companion object {
        const val DATABASE = "amps_accounts.db"
        const val VERSION = 1
        const val TABLE = "accounts"
        const val COL_ID = "id"
        const val COL_LOGIN = "login"
        const val COL_SALT = "salt"
        const val COL_HASH = "password_hash"
        const val COL_ITERATIONS = "iterations"
        const val COL_TOTP = "totp_secret"
        const val COL_EMAIL = "email"
        const val COL_PHONE = "phone"
        const val COL_CREATED = "created_at"
    }
}