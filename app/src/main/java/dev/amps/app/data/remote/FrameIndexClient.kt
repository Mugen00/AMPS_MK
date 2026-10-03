package dev.amps.app.data.remote

import android.util.Log
import dev.amps.app.data.model.IndexLookup
import dev.amps.app.data.model.IndexStats
import dev.amps.app.util.readableMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Body of `POST /api/frame/index/add`; the bridge names its fields in camelCase. */
@Serializable
internal data class IndexAddRequest(
    val hash: String,
    val anilistId: Int,
    val seriesTitle: String,
    val episode: Int? = null,
    val timestampSec: Float? = null,
    val source: String,
    val imageUrl: String? = null,
)

/**
 * The perceptual-hash index that lives on the PC.
 *
 * The bridge holds the frames of the series the user has catalogued; this client
 * asks it whether the frame just photographed is one of them. The index is a
 * bonus, never a requirement: the phone is regularly away from the PC, so a
 * missing bridge, a bridge without the index built, and a frame nobody indexed
 * all answer the same way — no match, no error, nothing to show the user.
 *
 * Every call therefore returns a value instead of throwing, and an unreachable
 * bridge is logged and treated as an empty index.
 */
class FrameIndexClient(
    private val client: OkHttpClient,
    private val bridge: BridgeClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /**
     * Nearest indexed frame to [hash], or an empty [IndexLookup] when the
     * bridge is away or has nothing close enough.
     */
    suspend fun lookup(hash: String): IndexLookup = withContext(Dispatchers.IO) {
        val base = bridge.currentBaseUrl() ?: return@withContext absent("адрес моста не задан")
        val url = "$base$PATH_MATCH".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("hash", hash)
            ?.build()
            ?: return@withContext absent("некорректный адрес $base")
        try {
            val text = get(url.toString())
            runCatching { json.decodeFromString(IndexLookup.serializer(), text) }
                .getOrElse { absent("ответ моста не разобран: ${it.readableMessage()}") }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            absent("мост на $base не отвечает: ${failure.readableMessage()}")
        }
    }

    /** What the bridge currently has indexed; zeroes when there is no bridge. */
    suspend fun stats(): IndexStats = withContext(Dispatchers.IO) {
        val base = bridge.currentBaseUrl() ?: return@withContext IndexStats()
        val url = "$base$PATH_STATS".toHttpUrlOrNull() ?: return@withContext IndexStats()
        try {
            val text = get(url.toString())
            runCatching { json.decodeFromString(IndexStats.serializer(), text) }
                .getOrElse {
                    Log.d(LOG_TAG, "Индекс моста недоступен: ${it.readableMessage()}")
                    IndexStats()
                }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Log.d(LOG_TAG, "Статистика индекса недоступна: ${failure.readableMessage()}")
            IndexStats()
        }
    }

    /**
     * Teaches the bridge one more frame, so the next sighting of it is a local
     * lookup instead of a remote search.
     *
     * [timestampSec] is the position inside the episode, not the moment of the
     * screenshot. Returns `false` — never throws — when the frame could not be
     * stored, which is the normal case with no bridge running.
     */
    suspend fun remember(
        hash: String,
        anilistId: Int,
        seriesTitle: String,
        episode: Int?,
        timestampSec: Float?,
        source: String,
        imageUrl: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        val base = bridge.currentBaseUrl() ?: return@withContext forget("адрес моста не задан")
        val url = "$base$PATH_ADD".toHttpUrlOrNull() ?: return@withContext forget("некорректный адрес $base")
        val body = runCatching {
            json.encodeToString(
                IndexAddRequest.serializer(),
                IndexAddRequest(
                    hash = hash,
                    anilistId = anilistId,
                    seriesTitle = seriesTitle,
                    episode = episode,
                    timestampSec = timestampSec,
                    source = source,
                    imageUrl = imageUrl,
                ),
            ).toRequestBody(JSON_MEDIA_TYPE)
        }.getOrElse {
            return@withContext forget("тело запроса не собрано: ${it.readableMessage()}")
        }
        try {
            val request = Request.Builder().url(url).post(body).build()
            client.newCall(request).execute().use { response ->
                val stored = response.isSuccessful
                if (!stored) Log.d(LOG_TAG, "Мост не сохранил кадр (HTTP ${response.code})")
                stored
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            forget("мост на $base не сохранил кадр: ${failure.readableMessage()}")
        }
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).get().build()
        return client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            text
        }
    }

    /** No match is an answer, not a failure; only the log says anything went wrong. */
    private fun absent(reason: String): IndexLookup {
        Log.d(LOG_TAG, "Поиск по индексу пропущен: $reason")
        return IndexLookup()
    }

    private fun forget(reason: String): Boolean {
        Log.d(LOG_TAG, "Кадр не сохранён в индекс: $reason")
        return false
    }

    private companion object {
        const val LOG_TAG = "FrameIndexClient"

        const val PATH_MATCH = "/api/frame/index/match"
        const val PATH_STATS = "/api/frame/index/stats"
        const val PATH_ADD = "/api/frame/index/add"

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}