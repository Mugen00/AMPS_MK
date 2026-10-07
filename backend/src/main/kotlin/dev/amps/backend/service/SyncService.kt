package dev.amps.backend.service

import dev.amps.backend.model.ApiResponse
import dev.amps.backend.model.ApiResponse.Companion.ok
import dev.amps.backend.model.SyncDataEntity
import dev.amps.backend.model.SyncDataTable
import dev.amps.backend.model.SyncRequest
import dev.amps.backend.model.SyncResponse
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/**
 * 1.1.0: синхронізація історії пошуку між пристроями.
 *
 * Оптимістичне блокування номерами версій: клієнт надсилає свою версію N,
 * сервер зберігає нову лише при точному збігу зі своєю. Розбіжність —
 * 409 з VERSION_CONFLICT, і конфлікт вирішує користувач, а не тиха
 * втрата даних. Це та сама домовленість, що й у локальному режимі 1.0.9.
 */
class SyncService {

    private val json = Json { ignoreUnknownKeys = true }
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

    suspend fun get(userId: Int): ApiResponse<SyncResponse> = newSuspendedTransaction {
        val row = SyncDataEntity.find { SyncDataTable.userId eq userId }.firstOrNull()
        ok(
            row?.let { SyncResponse(version = it.version, data = decode(it.dataJson)) }
                ?: SyncResponse(version = 0, data = emptyMap())
        )
    }

    suspend fun push(userId: Int, request: SyncRequest): ApiResponse<SyncResponse> = newSuspendedTransaction {
        val row = SyncDataEntity.find { SyncDataTable.userId eq userId }.firstOrNull()
        if (row == null) {
            if (request.version != 0) {
                return@newSuspendedTransaction ApiResponse.fail<SyncResponse>(
                    "Версія не збігається: сервер 0, клієнт ${request.version}", "VERSION_CONFLICT")
            }
            SyncDataEntity.new {
                this.userId = userId
                dataJson = encode(request.data)
                version = 1
                updatedAt = System.currentTimeMillis()
            }
            ok(SyncResponse(version = 1, data = request.data))
        } else {
            if (request.version != row.version) {
                return@newSuspendedTransaction ApiResponse.fail<SyncResponse>(
                    "Версія не збігається: сервер ${row.version}, клієнт ${request.version}",
                    "VERSION_CONFLICT")
            }
            row.dataJson = encode(request.data)
            row.version += 1
            row.updatedAt = System.currentTimeMillis()
            ok(SyncResponse(version = row.version, data = request.data))
        }
    }

    private fun encode(data: Map<String, String>): String =
        json.encodeToString(mapSerializer, data)

    private fun decode(raw: String): Map<String, String> =
        json.decodeFromString(mapSerializer, raw)
}
