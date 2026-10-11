package dev.amps.backend.model

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption

// ===== XP і рівні (1.2.4): одна строка на користувача =====
object XpTable : IntIdTable("user_xp") {
    val userId = integer("user_id").references(UserTable.id, ReferenceOption.CASCADE).uniqueIndex()
    val xp = integer("xp").default(0)
    val updatedAt = long("updated_at")
}

class XpEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<XpEntity>(XpTable)

    var userId by XpTable.userId
    var xp by XpTable.xp
    var updatedAt by XpTable.updatedAt
}
