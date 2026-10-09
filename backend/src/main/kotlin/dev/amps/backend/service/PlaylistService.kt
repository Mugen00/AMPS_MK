package dev.amps.backend.service

import dev.amps.backend.config.AppConfig
import dev.amps.backend.model.PlaylistEntity
import dev.amps.backend.model.PlaylistTable
import dev.amps.backend.model.PlaylistTrackDto
import dev.amps.backend.model.PlaylistTrackEntity
import dev.amps.backend.model.PlaylistTrackTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 1.2.1: свої плейлісти — прив'язані до акаунта і живуть у базі.
 * Трек зберігається як ЗНІМОК рядка пошуку (джерело + URL аудіо),
 * тому грає навіть тоді, коли джерело пошуку не відповідає.
 */
class PlaylistService(
    @Suppress("UNUSED_PARAMETER") private val config: AppConfig,
) {

    /** Список плейлістів свого акаунта (свіжі зверху) з кількістю треків. */
    fun list(uid: Int): List<Triple<Int, String, Pair<Long, Int>>> = transaction {
        PlaylistEntity.find { PlaylistTable.userId eq uid }
            .orderBy(PlaylistTable.createdAt to SortOrder.DESC)
            .map { pl ->
                val count = PlaylistTrackEntity
                    .find { PlaylistTrackTable.playlistId eq pl.id.value }
                    .count()
                    .toInt()
                Triple(pl.id.value, pl.name, pl.createdAt to count)
            }
    }

    /** Створити плейліст; порожня назва не допускається. */
    fun create(uid: Int, name: String): Int? {
        val trimmed = name.trim().take(120)
        if (trimmed.isEmpty()) return null
        return transaction {
            PlaylistEntity.new {
                userId = uid
                this.name = trimmed
                createdAt = System.currentTimeMillis()
            }.id.value
        }
    }

    /** Видалити свій плейліст (треки видаляться каскадом). Чуже — false. */
    fun delete(uid: Int, playlistId: Int): Boolean = transaction {
        val pl = PlaylistEntity.findById(playlistId) ?: return@transaction false
        if (pl.userId != uid) return@transaction false
        PlaylistTrackEntity
            .find { PlaylistTrackTable.playlistId eq playlistId }
            .forEach { it.delete() }
        pl.delete()
        true
    }

    /** Треки плейліста в порядку додавання; чужий плейліст — null. */
    fun tracks(uid: Int, playlistId: Int): List<PlaylistTrackDto>? = transaction {
        val pl = PlaylistEntity.findById(playlistId) ?: return@transaction null
        if (pl.userId != uid) return@transaction null
        PlaylistTrackEntity
            .find { PlaylistTrackTable.playlistId eq playlistId }
            .orderBy(PlaylistTrackTable.position to SortOrder.ASC)
            .map(::toDto)
    }

    /**
     * Додати трек у кінець свого плейліста. Дублікати (за джерелом+id)
     * не створюються — повертається id наявного рядка.
     */
    fun addTrack(uid: Int, plId: Int, track: PlaylistTrackDto): Int? = transaction {
        val pl = PlaylistEntity.findById(plId) ?: return@transaction null
        if (pl.userId != uid) return@transaction null
        val duplicate = PlaylistTrackEntity
            .find {
                (PlaylistTrackTable.playlistId eq plId) and
                    (PlaylistTrackTable.trackSource eq track.source) and
                    (PlaylistTrackTable.sourceId eq track.sourceId)
            }
            .firstOrNull()
        if (duplicate != null) return@transaction duplicate.id.value
        val nextPosition = (PlaylistTrackEntity
            .find { PlaylistTrackTable.playlistId eq plId }
            .maxByOrNull { it.position }?.position ?: -1) + 1
        PlaylistTrackEntity.new {
            playlistId = plId
            trackSource = track.source.take(24)
            sourceId = track.sourceId.take(200)
            title = track.title.take(250)
            artist = track.artist.take(200)
            audioUrl = track.audioUrl.take(500)
            coverUrl = track.coverUrl?.take(500)
            pageUrl = track.pageUrl?.take(500)
            licenseUrl = track.licenseUrl?.take(500)
            durationSec = track.durationSec
            position = nextPosition
            addedAt = System.currentTimeMillis()
        }.id.value
    }

    /** Прибрати трек зі СВОГО плейліста. */
    fun removeTrack(uid: Int, playlistId: Int, trackRowId: Int): Boolean = transaction {
        val pl = PlaylistEntity.findById(playlistId) ?: return@transaction false
        if (pl.userId != uid) return@transaction false
        val row = PlaylistTrackEntity.findById(trackRowId) ?: return@transaction false
        if (row.playlistId != playlistId) return@transaction false
        row.delete()
        true
    }

    private fun toDto(e: PlaylistTrackEntity): PlaylistTrackDto = PlaylistTrackDto(
        id = e.id.value,
        source = e.trackSource,
        sourceId = e.sourceId,
        title = e.title,
        artist = e.artist,
        audioUrl = e.audioUrl,
        coverUrl = e.coverUrl,
        pageUrl = e.pageUrl,
        licenseUrl = e.licenseUrl,
        durationSec = e.durationSec,
        position = e.position,
        addedAt = e.addedAt,
    )
}
