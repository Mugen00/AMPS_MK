package dev.amps.app.data.local

import android.content.Context
import dev.amps.app.data.model.FrameHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Запись истории поиска по картинке.
 *
 * **1.0.5:** эпизод, таймкод и номер кадра ушли вместе с trace.moe — у IQDB
 * их нет, а выдумывать нечего. Остались id серии, сходство и имя персонажа.
 * Старые записи в файле эти поля ещё содержат: `Json { ignoreUnknownKeys }`
 * их пропустит, поэтому миграция не нужна и данные не теряются.
 */
@Serializable
data class StoredFrame(
    val anilistId: Int,
    val similarity: Double? = null,
    val characterName: String? = null,
    val engine: String = "IQDB",
) {
    fun toFrameHit() = FrameHit(
        source = engine,
        similarityPercent = similarity?.let { (it * 100).toInt() },
    )
}

@Serializable
data class HistoryEntry(
    val id: String,
    val kind: String,
    val title: String,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    val createdAt: Long,
    val frame: StoredFrame? = null,
    val track: StoredTrack? = null,
)

@Serializable
data class StoredTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val year: Int? = null,
    val source: String,
    val sourceUrl: String? = null,
    val mbid: String? = null,
    val localPath: String? = null,
    val license: String? = null,
)

/**
 * Search history as a plain JSON file: a handful of entries does not justify a
 * database, and keeping it on disk means the wiki can be reopened later — по
 * сохранённому id серия дочитывается из AniList даже без сети.
 *
 * 1.0.9: [writesHistory] — гостевой режим. Гость ищет ровно так же, как
 * пользователь аккаунта, но его поиски не попадают в историю. Проверка стоит
 * здесь, а не в каждом месте вызова, потому что мест вызова три, а правило
 * одно; разъехавшееся по коду правило рано или поздно забудет одно из них.
 */
class HistoryStore(
    private val context: Context,
    private val writesHistory: () -> Boolean = { true },
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file: File get() = File(context.filesDir, "history.json")
    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    init {
        _entries.value = runCatching {
            if (file.exists()) json.decodeFromString<List<HistoryEntry>>(file.readText()) else emptyList()
        }.getOrDefault(emptyList())
    }

    suspend fun add(entry: HistoryEntry) = withContext(Dispatchers.IO) {
        // Гость не оставляет следов. Проверка идёт на записи, а не на чтении:
        // уже сохранённая история аккаунта должна оставаться видимой и после
        // выхода из него.
        if (!writesHistory()) return@withContext
        val updated = (listOf(entry) + _entries.value)
            .distinctBy { it.id }
            .take(MAX_ENTRIES)
        _entries.value = updated
        persist(updated)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        _entries.value = emptyList()
        persist(emptyList())
    }

    private fun persist(entries: List<HistoryEntry>) {
        runCatching {
            file.writeText(json.encodeToString(entries))
        }
    }

    private companion object {
        const val MAX_ENTRIES = 60
    }
}
