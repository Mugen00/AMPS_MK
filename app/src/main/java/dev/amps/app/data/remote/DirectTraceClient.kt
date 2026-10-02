package dev.amps.app.data.remote

import dev.amps.app.data.model.BridgeAnime
import dev.amps.app.data.model.BridgeCover
import dev.amps.app.data.model.BridgeDate
import dev.amps.app.data.model.BridgeVideo
import dev.amps.app.data.model.FrameIdentifyResponse
import dev.amps.app.data.model.SauceResult
import dev.amps.app.data.model.Titles
import dev.amps.app.data.model.TraceResult
import dev.amps.app.util.readableMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * trace.moe straight from the phone, for the case where no bridge is on the LAN.
 *
 * The public API needs no key and charges the anonymous quota of the current IP,
 * so a user who never starts the bridge still gets "which anime, which episode,
 * which second" — only the SauceNAO half of the identify call is missing, which
 * is why [SauceResult.configured] is false here.
 *
 * The response is mapped onto the same DTOs the bridge returns, so nothing above
 * this class has to know which of the two answered.
 */
class DirectTraceClient(
    private val client: OkHttpClient,
    private val apiKey: String? = null,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** A frame upload plus the API round trip is slow; 30 s of read is the budget. */
    private val http by lazy {
        client.newBuilder()
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /** Never throws: a miss, a quota stop and a dead network are all results. */
    suspend fun identify(bytes: ByteArray, fileName: String, mimeType: String): FrameIdentifyResponse =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(ENDPOINT)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .post(formData(bytes, fileName, mimeType))
                    .build()
                http.newCall(request).execute().use(::readResponse)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                failed("Не удалось обратиться к trace.moe напрямую: ${failure.readableMessage()}")
            }
        }

    private fun formData(bytes: ByteArray, fileName: String, mimeType: String): MultipartBody {
        val image = bytes.toRequestBody(mimeType.toMediaTypeOrNull() ?: DEFAULT_MIME_TYPE.toMediaType())
        return MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image", fileName.ifBlank { DEFAULT_FILE_NAME }, image)
            .apply {
                apiKey?.trim()?.takeIf { it.isNotEmpty() }?.let { addFormDataPart("api_key", it) }
            }
            .build()
    }

    private fun readResponse(response: Response): FrameIdentifyResponse {
        val text = response.body?.string().orEmpty()
        val root = text.takeIf { it.isNotBlank() }
            ?.let { body -> runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() }
        val reason = root?.reportedReason()

        // trace.moe answers 404 for "this frame is not in the database", which is an
        // answer, not a broken connection — but only when the body really is its JSON.
        // An HTML 404 is what a moved endpoint looks like, and must not be reported to
        // the user as "this frame is unknown".
        if (!response.isSuccessful) {
            return if (response.code == HTTP_NOT_FOUND && root != null) {
                notMatched(reason ?: "trace.moe не нашёл этот кадр в своей базе", text)
            } else {
                failed("trace.moe ответил ошибкой (HTTP ${response.code})${reason?.let { ": $it" }.orEmpty()}")
            }
        }
        if (root == null) {
            return failed("trace.moe вернул ответ, который не удалось разобрать")
        }
        if (reason != null) return notMatched(reason, text)

        val hit = root.firstResult() ?: return notMatched("trace.moe не нашёл совпадений", text)
        val anime = hit.toBridgeAnime() ?: return notMatched("trace.moe не нашёл совпадений", text)
        return matched(hit, anime, text)
    }

    private fun matched(hit: JsonObject, anime: BridgeAnime, text: String): FrameIdentifyResponse =
        FrameIdentifyResponse(
            trace = TraceResult(
                engine = ENGINE,
                matched = true,
                anilist = anime,
                episode = hit.int("episode") ?: hit.int("episode_start"),
                frame = hit.int("frame"),
                timestamp = hit.dbl("timestamp") ?: hit.dbl("from"),
                similarity = hit.dbl("similarity"),
                video = hit.toBridgeVideo(),
                raw = text.take(MAX_RAW_CHARS),
            ),
            sauce = SAUCE_WITHOUT_KEY,
            message = null,
        )

    private fun notMatched(reason: String, text: String): FrameIdentifyResponse =
        FrameIdentifyResponse(
            trace = TraceResult(
                engine = ENGINE,
                matched = false,
                raw = text.take(MAX_RAW_CHARS),
            ),
            sauce = SAUCE_WITHOUT_KEY,
            message = reason,
        )

    private fun failed(message: String): FrameIdentifyResponse = FrameIdentifyResponse(
        trace = null,
        sauce = SAUCE_WITHOUT_KEY,
        error = message,
        message = message,
    )

    /**
     * "Nothing found" arrives as a blank `error` next to a populated result, and as
     * a text `result` when the frame is unknown; both have to read as an answer.
     */
    private fun JsonObject.reportedReason(): String? {
        str("error")?.let { return it }
        return when (val result = at("result")) {
            is JsonObject -> result.str("error")
            is JsonPrimitive -> result.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != EMPTY_RESULT }
            else -> null
        }
    }

    /** The API returns the best match first inside `result`, as a list. */
    private fun JsonObject.firstResult(): JsonObject? = when (val result = at("result")) {
        is JsonArray -> result.objects().firstOrNull()
        is JsonObject -> result
        else -> null
    }

    private fun JsonObject.toBridgeAnime(): BridgeAnime? {
        val block = obj("anilist")
            ?: return (int("anilist") ?: int("anilistID"))?.let { BridgeAnime(id = it) }
        return BridgeAnime(
            id = block.int("id"),
            idMal = block.int("idMal"),
            title = Titles(
                romaji = block.str("title", "romaji"),
                english = block.str("title", "english"),
                native = block.str("title", "native"),
            ),
            // Kept verbatim: the wiki screen strips the `<br>` AniList puts in here.
            description = block.str("description"),
            coverImage = block.obj("coverImage")?.let { cover ->
                BridgeCover(
                    extraLarge = cover.str("extraLarge"),
                    large = cover.str("large"),
                    medium = cover.str("medium"),
                )
            },
            episodes = block.int("episodes"),
            genres = block.strings("genres"),
            startDate = block.obj("startDate")?.let { date ->
                BridgeDate(year = date.int("year"), month = date.int("month"), day = date.int("day"))
            },
            status = block.str("status"),
            format = block.str("format"),
            averageScore = block.int("averageScore"),
            popularity = block.int("popularity"),
            synonyms = block.strings("synonyms"),
            isAdult = block.bool("isAdult") ?: false,
        )
    }

    /**
     * `video` is a preview URL (`.../video/<id>`, older builds end in `<W>x<H>`);
     * the bridge itself may answer with the four fields already split out.
     */
    private fun JsonObject.toBridgeVideo(): BridgeVideo? {
        val split = obj("video")
        if (split != null) {
            return BridgeVideo(
                id = split.str("id"),
                part = split.int("part"),
                length = split.int("length"),
                url = split.str("url"),
            )
        }
        val url = str("video") ?: return null
        val segments = url.substringBefore('?').split('/').filter { it.isNotEmpty() }
        val last = segments.lastOrNull().orEmpty()
        val id = if (last.matches(PREVIEW_SIZE)) segments.getOrNull(segments.size - 2) ?: last else last
        return BridgeVideo(
            id = id.takeIf { it.isNotEmpty() },
            part = int("episode") ?: int("episode_start"),
            length = null,
            url = url,
        )
    }

    companion object {
        /** The anonymous, keyless endpoint. */
        const val ENDPOINT = "https://api.trace.moe/search"

        /** Shown by the wiki so the user can see the answer skipped the bridge. */
        const val ENGINE = "trace.moe (прямое подключение)"

        private const val USER_AGENT = "AMPS/1.0 (android)"
        private const val DEFAULT_FILE_NAME = "frame.png"
        private const val DEFAULT_MIME_TYPE = "image/jpeg"
        private const val READ_TIMEOUT_SECONDS = 30L
        private const val HTTP_NOT_FOUND = 404
        private const val MAX_RAW_CHARS = 2_000
        private const val EMPTY_RESULT = "[]"

        /** `1920x1080` — the resolution segment of the older preview URLs. */
        private val PREVIEW_SIZE = Regex("""\d+x\d+""", RegexOption.IGNORE_CASE)

        /** SauceNAO needs its own key; without a bridge there is none to use. */
        private val SAUCE_WITHOUT_KEY = SauceResult(configured = false)
    }
}