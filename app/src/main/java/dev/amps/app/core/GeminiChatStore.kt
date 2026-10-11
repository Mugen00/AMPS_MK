package dev.amps.app.core

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 1.3.0: історія Gemini-аналізів — «чатів» по картинках. Зберігається
 * ЛИШЕ на пристрої (DataStore + jpeg у filesDir): це приватні розмови
 * з AI, сервер їх не бачить. Фундамент для майбутнього ІІ-помічника.
 */

@Serializable
data class GeminiChatMessage(
    /** "user" | "model" */
    val role: String,
    val text: String,
)

@Serializable
data class GeminiChatSession(
    val id: String,
    val createdAt: Long,
    /** Заголовок — перший рядок першої відповіді моделі. */
    val title: String,
    /** Шлях до збереженого фото у filesDir (для повторного відкриття). */
    val photoPath: String,
    val messages: List<GeminiChatMessage>,
)

private val Context.geminiDataStore by preferencesDataStore(name = "gemini_chats")

class GeminiChatStore(private val context: Context) {

    private val KEY_CHATS = stringPreferencesKey("chats_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Усі сесії, свіжі зверху. */
    val chats: Flow<List<GeminiChatSession>> = context.geminiDataStore.data.map { prefs ->
        prefs[KEY_CHATS]?.let { raw ->
            runCatching { json.decodeFromString<List<GeminiChatSession>>(raw) }.getOrNull()
        }.orEmpty().sortedByDescending { it.createdAt }
    }

    /** Додати або оновити (за id) сесію — викликається після кожної відповіді. */
    suspend fun upsert(session: GeminiChatSession) {
        context.geminiDataStore.edit { prefs ->
            val current = prefs[KEY_CHATS]?.let {
                runCatching { json.decodeFromString<List<GeminiChatSession>>(it) }.getOrNull()
            }.orEmpty()
            val updated = listOf(session) + current.filterNot { it.id == session.id }
            prefs[KEY_CHATS] = json.encodeToString(
                ListSerializer(GeminiChatSession.serializer()),
                updated.sortedByDescending { it.createdAt },
            )
        }
    }

    /** Видалити сесію і файл фото. */
    suspend fun delete(session: GeminiChatSession) {
        runCatching { File(session.photoPath).delete() }
        context.geminiDataStore.edit { prefs ->
            val current = decode(prefs[KEY_CHATS])
            prefs[KEY_CHATS] = json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(GeminiChatSession.serializer()),
                current.filterNot { it.id == session.id },
            )
        }
    }

    private fun decode(raw: String?): List<GeminiChatSession> =
        raw?.let { runCatching { json.decodeFromString<List<GeminiChatSession>>(it) }.getOrNull() }.orEmpty()

    /** Зберегти jpeg-байти фото сесії у filesDir/gemini_chats; повертає шлях. */
    fun savePhoto(sessionId: String, bytes: ByteArray): String {
        val dir = File(context.filesDir, "gemini_chats").apply { mkdirs() }
        val file = File(dir, "$sessionId.jpg")
        file.writeBytes(bytes)
        return file.absolutePath
    }
}
