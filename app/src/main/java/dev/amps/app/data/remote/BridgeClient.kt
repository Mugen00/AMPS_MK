package dev.amps.app.data.remote

import dev.amps.app.core.SettingsStore
import dev.amps.app.data.model.BridgeHealth
import dev.amps.app.data.model.FrameIdentifyResponse
import dev.amps.app.util.readableMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Thrown when the local bridge cannot be reached at all. The repository catches
 * this specific type and falls back to talking to trace.moe directly, so the
 * app keeps working when the PC bridge is simply not running.
 */
class BridgeUnreachableException(message: String) : IOException(message)

/**
 * Talks to the local bridge (bridge/src/server.mjs) that proxies the trace.moe
 * and imgfind MCP servers. Everything the phone knows about reverse image search
 * arrives through these four calls.
 */
class BridgeClient(
    private val client: OkHttpClient,
    private val settings: SettingsStore,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** Health probe used by the settings screen. Never throws: a dead bridge is data. */
    suspend fun health(): BridgeHealth = withContext(Dispatchers.IO) {
        val base = currentBaseUrl() ?: return@withContext BridgeHealth(error = "Адрес моста не задан")
        runCatching {
            val request = Request.Builder().url("$base/api/health").get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful && body.isBlank()) {
                    BridgeHealth(error = "HTTP ${response.code}")
                } else {
                    json.decodeFromString(BridgeHealth.serializer(), body)
                }
            }
        }.getOrElse { BridgeHealth(error = it.readableMessage()) }
    }

    /**
     * Sends the raw frame to the bridge. The bridge runs trace.moe for the anime
     * and SauceNAO for the artwork, so one round trip yields both.
     */
    suspend fun identify(
        bytes: ByteArray,
        fileName: String,
        mime: String,
        anilistId: Int? = null,
    ): FrameIdentifyResponse = withContext(Dispatchers.IO) {
        val base = currentBaseUrl() ?: throw BridgeUnreachableException(
            "Мост не найден. Запустите bridge на компьютере или отключите автообнаружение и впишите адрес вручную.",
        )
        val body = bytes.toRequestBody(mime.toMediaType())
        val request = Request.Builder()
            .url("$base/api/frame/identify")
            .header("x-filename", fileName.ifBlank { "frame.png" })
            .apply { anilistId?.let { header("x-anilist-id", it.toString()) } }
            .post(body)
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) throw IOException("Мост вернул пустой ответ (HTTP ${response.code})")
                json.decodeFromString(FrameIdentifyResponse.serializer(), text)
            }
        }.getOrElse { error ->
            if (error is IOException && error !is BridgeUnreachableException) {
                throw BridgeUnreachableException("Мост на $base не отвечает: ${error.readableMessage()}")
            }
            throw error
        }
    }

    /** Address in use right now, discovery first and the typed fallback second. */
    suspend fun currentBaseUrl(): String? {
        val current = settings.settings.first()
        if (current.autoBridge) {
            BridgeDiscovery.cachedUrl()?.let { return it }
        }
        val raw = current.bridgeUrl.trim()
        if (raw.isEmpty()) return null
        val withScheme = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
        return withScheme.trimEnd('/')
    }
}
