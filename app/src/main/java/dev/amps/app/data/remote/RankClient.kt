package dev.amps.app.data.remote

import android.util.Log
import dev.amps.app.data.model.ContentLabel
import dev.amps.app.data.model.RankResult
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

/**
 * What the phone already knows about a frame when it asks for a verdict.
 *
 * Each signal is optional: a phone that never reached the index sends
 * `index = null`, and the ranker simply weighs the rest.
 */
@Serializable
data class RankRequest(
    val trace: TraceSignal? = null,
    val sauce: SauceSignal? = null,
    val index: IndexSignal? = null,
    /** On-device object and scene labels for the frame itself. */
    val labels: List<ContentLabel> = emptyList(),
)

/** The trace.moe half of the frame search. */
@Serializable
data class TraceSignal(
    val matched: Boolean = false,
    val anilistId: Int? = null,
    val episode: Int? = null,
    val timestamp: Float? = null,
    val similarity: Float? = null,
)

/** The SauceNAO half; [configured] is false when the bridge has no API key. */
@Serializable
data class SauceSignal(
    val configured: Boolean = false,
    val similarity: Float? = null,
    val source: String? = null,
    val characters: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
)

/** What the PC-side perceptual-hash index returned for this frame. */
@Serializable
data class IndexSignal(
    val matched: Boolean = false,
    val anilistId: Int? = null,
    val episode: Int? = null,
    val timestamp: Float? = null,
    val distance: Int? = null,
)

/**
 * Asks the bridge to weigh everything the phone knows about a frame and return
 * one ordered answer.
 *
 * The ranker is the strongest signal in the app — it sees all three searches at
 * once — but it only exists on the PC, and only when the bridge is up and
 * configured. So [rank] answers `null` for every failure the caller can do
 * nothing about, and the caller falls back to the plain trace.moe answer. It
 * never throws.
 */
class RankClient(
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
     * The ranked verdict for [body], or `null` when the bridge is unreachable,
     * answers with an error, or returns something that will not decode.
     */
    suspend fun rank(body: RankRequest): RankResult? = withContext(Dispatchers.IO) {
        val base = bridge.currentBaseUrl() ?: return@withContext skip("адрес моста не задан")
        val url = "$base$PATH_RANK".toHttpUrlOrNull()
            ?: return@withContext skip("некорректный адрес $base")
        val payload = runCatching {
            json.encodeToString(RankRequest.serializer(), body).toRequestBody(JSON_MEDIA_TYPE)
        }.getOrElse {
            return@withContext skip("тело запроса не собрано: ${it.readableMessage()}")
        }
        try {
            val request = Request.Builder().url(url).post(payload).build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext skip("мост ответил HTTP ${response.code}")
                }
                val result = runCatching { json.decodeFromString(RankResult.serializer(), text) }
                    .getOrElse {
                        return@withContext skip("ответ моста не разобран: ${it.readableMessage()}")
                    }
                // A result with no decision says nothing, so the caller hears
                // "no ranking" and keeps the plain trace.moe answer.
                if (result.decision.isBlank()) skip("мост не назвал решение") else result
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            skip("мост на $base не ответил: ${failure.readableMessage()}")
        }
    }

    private fun skip(reason: String): RankResult? {
        Log.d(LOG_TAG, "Ранжирование пропущено: $reason")
        return null
    }

    private companion object {
        const val LOG_TAG = "RankClient"
        const val PATH_RANK = "/api/rank"

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}