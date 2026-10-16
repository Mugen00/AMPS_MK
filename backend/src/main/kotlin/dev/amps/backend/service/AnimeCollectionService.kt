package dev.amps.backend.service

import dev.amps.backend.model.AnimeCollectionCharacterEntity
import dev.amps.backend.model.AnimeCollectionCharacterTable
import dev.amps.backend.model.AnimeCollectionEntity
import dev.amps.backend.model.AnimeCollectionTable
import dev.amps.backend.model.ApiResponse
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 1.6.6: колекції аніме-персонажів.
 *
 * Gemini-аналіз фото у застосунку впізнає персонажа і аніме; кнопка
 * «Зберегти» кладе його в картку аніме: одна картка на назву аніме,
 * всередині — список персонажів, яких користувач відкрив. Кожне нове
 * збереження оновлює картку (updatedAt — сортування «свіжі зверху»).
 */
class AnimeCollectionService {

    /** Усі колекції користувача з персонажами — для розділу «Колекції». */
    fun list(uid: Int): dev.amps.backend.model.AnimeCollectionsResponse = transaction {
        val collections = AnimeCollectionEntity
            .find { AnimeCollectionTable.userId eq uid }
            .orderBy(AnimeCollectionTable.updatedAt to SortOrder.DESC)
            .toList()
        dev.amps.backend.model.AnimeCollectionsResponse(collections.map { toDto(it) })
    }

    /**
     * Зберегти персонажа: картка аніме створюється за потреби (унікально
     * на (user, animeTitle)), персонаж — унікально на (collection, name).
     * Повертає оновлену картку і ознаку «новий персонаж».
     */
    fun saveCharacter(
        uid: Int,
        animeTitle: String,
        characterName: String,
        note: String,
    ): ApiResponse<dev.amps.backend.model.SaveCharacterResponse> = transaction {
        val cleanAnime = animeTitle.trim().take(250)
        val cleanName = characterName.trim().take(150)
        val cleanNote = note.trim().take(600)
        if (cleanAnime.isEmpty() || cleanName.isEmpty()) {
            return@transaction ApiResponse.fail(
                "Назва аніме і персонажа обов'язкові", "EMPTY_FIELDS",
            )
        }
        val now = System.currentTimeMillis()
        val collection = AnimeCollectionEntity.find {
            (AnimeCollectionTable.userId eq uid) and (AnimeCollectionTable.animeTitle eq cleanAnime)
        }.firstOrNull() ?: AnimeCollectionEntity.new {
            userId = uid
            this.animeTitle = cleanAnime
            createdAt = now
            updatedAt = now
        }
        val existing = AnimeCollectionCharacterEntity.find {
            (AnimeCollectionCharacterTable.collectionId eq collection.id.value) and
                (AnimeCollectionCharacterTable.name eq cleanName)
        }.firstOrNull()
        val added = existing == null
        if (existing != null) {
            if (cleanNote.isNotBlank()) existing.note = cleanNote
        } else {
            AnimeCollectionCharacterEntity.new {
                collectionId = collection.id.value
                this.name = cleanName
                this.note = cleanNote
                createdAt = now
            }
        }
        collection.updatedAt = now
        ApiResponse.ok(
            dev.amps.backend.model.SaveCharacterResponse(
                collection = toDto(collection),
                added = added,
            )
        )
    }

    /** Видалити картку аніме з усіма персонажами (лише свою). */
    fun deleteCollection(uid: Int, collectionId: Int): ApiResponse<Boolean> = transaction {
        val collection = AnimeCollectionEntity.findById(collectionId)
            ?: return@transaction ApiResponse.fail("Колекцію не знайдено", "NO_COLLECTION")
        if (collection.userId != uid) {
            return@transaction ApiResponse.fail("Це не твоя колекція", "FOREIGN_COLLECTION")
        }
        AnimeCollectionCharacterEntity.find {
            AnimeCollectionCharacterTable.collectionId eq collectionId
        }.forEach { it.delete() }
        collection.delete()
        ApiResponse.ok(true)
    }

    /** Видалити одного персонажа з картки (лише свою). */
    fun deleteCharacter(uid: Int, characterId: Int): ApiResponse<Boolean> = transaction {
        val character = AnimeCollectionCharacterEntity.findById(characterId)
            ?: return@transaction ApiResponse.fail("Персонажа не знайдено", "NO_CHARACTER")
        val collection = AnimeCollectionEntity.findById(character.collectionId)
        if (collection == null || collection.userId != uid) {
            return@transaction ApiResponse.fail("Це не твоя колекція", "FOREIGN_COLLECTION")
        }
        character.delete()
        // Картка спорожніла — прибираємо її зовсім, як порожню папку.
        val remaining = AnimeCollectionCharacterEntity
            .find { AnimeCollectionCharacterTable.collectionId eq collection.id.value }
            .limit(1).toList()
        if (remaining.isEmpty()) collection.delete()
        ApiResponse.ok(true)
    }

    private fun toDto(e: AnimeCollectionEntity): dev.amps.backend.model.AnimeCollectionDto {
        val characters = AnimeCollectionCharacterEntity
            .find { AnimeCollectionCharacterTable.collectionId eq e.id.value }
            .orderBy(AnimeCollectionCharacterTable.createdAt to SortOrder.ASC)
            .map { ch ->
                dev.amps.backend.model.CollectionCharacterDto(
                    id = ch.id.value,
                    name = ch.name,
                    note = ch.note,
                    createdAt = ch.createdAt,
                )
            }
        return dev.amps.backend.model.AnimeCollectionDto(
            id = e.id.value,
            animeTitle = e.animeTitle,
            characters = characters,
            characterCount = characters.size,
            updatedAt = e.updatedAt,
        )
    }
}
