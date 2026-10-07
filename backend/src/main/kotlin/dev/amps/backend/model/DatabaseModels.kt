package dev.amps.backend.model

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption

/**
 * 1.1.0: схема бази (PostgreSQL через Exposed DAO).
 *
 * Час зберігається epoch-мілісекундами в long-колонках: жодної
 * залежності від exposed-java-time і жодних проблем із часовими
 * поясами — сервер лише порівнює числа.
 */

// ===== Користувачі =====
object UserTable : IntIdTable("users") {
    val login = varchar("login", 50).uniqueIndex()
    val email = varchar("email", 255).uniqueIndex()
    val phone = varchar("phone", 30).nullable()
    val passwordHash = varchar("password_hash", 255)
    val emailVerified = bool("email_verified").default(false)
    val phoneVerified = bool("phone_verified").default(false)
    val twoFactorEnabled = bool("two_factor_enabled").default(false)
    val twoFactorSecret = varchar("two_factor_secret", 255).nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
}

class UserEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<UserEntity>(UserTable)

    var login by UserTable.login
    var email by UserTable.email
    var phone by UserTable.phone
    var passwordHash by UserTable.passwordHash
    var emailVerified by UserTable.emailVerified
    var phoneVerified by UserTable.phoneVerified
    var twoFactorEnabled by UserTable.twoFactorEnabled
    var twoFactorSecret by UserTable.twoFactorSecret
    var createdAt by UserTable.createdAt
    var updatedAt by UserTable.updatedAt
}

// ===== Коди підтвердження (email / SMS / скидання пароля) =====
object VerificationCodeTable : IntIdTable("verification_codes") {
    val userId = integer("user_id").references(UserTable.id, ReferenceOption.CASCADE)
    // У базі лишається хеш SHA-256, не сам код.
    val codeHash = varchar("code_hash", 64)
    val type = varchar("type", 20)
    val expiresAt = long("expires_at")
    val used = bool("used").default(false)
    val createdAt = long("created_at")
}

class VerificationCodeEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<VerificationCodeEntity>(VerificationCodeTable)

    var userId by VerificationCodeTable.userId
    var codeHash by VerificationCodeTable.codeHash
    var type by VerificationCodeTable.type
    var expiresAt by VerificationCodeTable.expiresAt
    var used by VerificationCodeTable.used
    var createdAt by VerificationCodeTable.createdAt
}

// ===== Синхронізація: пари ключ-значення на користувача =====
object SyncDataTable : IntIdTable("sync_data") {
    val userId = integer("user_id").references(UserTable.id, ReferenceOption.CASCADE).uniqueIndex()
    val dataJson = text("data_json")
    val version = integer("version")
    val updatedAt = long("updated_at")
}

class SyncDataEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<SyncDataEntity>(SyncDataTable)

    var userId by SyncDataTable.userId
    var dataJson by SyncDataTable.dataJson
    var version by SyncDataTable.version
    var updatedAt by SyncDataTable.updatedAt
}

// ===== Refresh-токени (тільки хеші) =====
object RefreshTokenTable : IntIdTable("refresh_tokens") {
    val userId = integer("user_id").references(UserTable.id, ReferenceOption.CASCADE)
    val tokenHash = varchar("token_hash", 64)
    val expiresAt = long("expires_at")
    val createdAt = long("created_at")
}

class RefreshTokenEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<RefreshTokenEntity>(RefreshTokenTable)

    var userId by RefreshTokenTable.userId
    var tokenHash by RefreshTokenTable.tokenHash
    var expiresAt by RefreshTokenTable.expiresAt
    var createdAt by RefreshTokenTable.createdAt
}
