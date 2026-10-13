package dev.amps.backend.service

import dev.amps.backend.model.ApiResponse
import dev.amps.backend.model.ApiResponse.Companion.ok
import dev.amps.backend.model.BlockEntity
import dev.amps.backend.model.BlockTable
import dev.amps.backend.model.BlockToggleResponse
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/**
 * 1.6.2: блокування користувачів. Заблокований не може писати мені
 * в приватний чат (групові чати не чіпаємо — там порядок тримають
 * модератори). Toggle: повторний виклик знімає блокування.
 */
class BlockService {

    /** Перемкнути блокування [targetId] з боку [blockerId]. */
    suspend fun toggle(blockerId: Int, targetId: Int): ApiResponse<BlockToggleResponse> =
        newSuspendedTransaction {
            if (blockerId == targetId) {
                return@newSuspendedTransaction ApiResponse.fail(
                    "Себе заблокувати не можна", "SELF_BLOCK",
                )
            }
            val existing = BlockEntity.find {
                (BlockTable.blockerId eq blockerId) and
                    (BlockTable.blockedId eq targetId)
            }.firstOrNull()
            if (existing != null) {
                existing.delete()
                ok(BlockToggleResponse(blocked = false))
            } else {
                BlockEntity.new {
                    this.blockerId = blockerId
                    blockedId = targetId
                    createdAt = System.currentTimeMillis()
                }
                ok(BlockToggleResponse(blocked = true))
            }
        }

    /** Чи [blockerId] заблокував [targetId] — для фільтра надсилання. */
    fun isBlocked(blockerId: Int, targetId: Int): Boolean =
        BlockEntity.find {
            (BlockTable.blockerId eq blockerId) and
                (BlockTable.blockedId eq targetId)
        }.firstOrNull() != null
}
