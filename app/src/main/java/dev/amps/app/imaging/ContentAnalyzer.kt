package dev.amps.app.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import dev.amps.app.data.model.ContentLabel
import dev.amps.app.data.model.FrameContent
import dev.amps.app.util.readableMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Говорит, что **есть** на картинке, для случая, когда обратный поиск ничего
 * не нашёл.
 *
 * ML Kit's bundled image labeler answers with generic English categories —
 * "person", "sky", "building", "food". That is the whole point and the whole
 * limit of this class: it produces the raw generic signal, which is enough for
 * a later stage to reject a wrong guess and enough for the user to see
 * something useful on a picture IQDB did not recognise. It does **not** know
 * anime character names, anime titles, or specific fictional places, and nothing
 * below pretends otherwise — those come from the booru tags and the wiki.
 *
 * Everything here is best-effort and total:
 *  - [analyze] never throws into the caller, not even after [close];
 *  - every failure becomes a [FrameContent] carrying `analyzed = false` plus a
 *    Russian [FrameContent.unavailableReason], so the phone still searches;
 *  - it never blocks longer than [LABEL_TIMEOUT_MS].
 *
 * Not thread-safe in its internals but safe to call concurrently: the detector
 * is held in an [AtomicReference] and handed to one caller at a time, and ML
 * Kit itself accepts concurrent `process()` calls.
 */
class ContentAnalyzer {

    private val labelerRef = AtomicReference<ImageLabeler?>(null)

    /**
     * Labels one frame.
     *
     * [jpegBytes] must be an encoded JPEG or PNG — the caller guarantees it.
     *
     * There is no ML Kit factory for encoded bytes: `InputImage.fromByteArray`
     * only accepts *raw* NV21/YV12 camera buffers (it needs an explicit width,
     * height, rotation and pixel format), never a compressed file. So the bytes
     * are decoded exactly once, sub-sampled down to [MAX_INPUT_EDGE_PX] on the
     * long edge, and handed over as a [Bitmap]. Nothing is re-encoded to JPEG:
     * that would only add a second lossy pass on top of the decode the API
     * forces on us.
     *
     * Runs entirely on [Dispatchers.Default]; the caller never sees the Main
     * thread pay for it. Returns an "unavailable" result — never an exception —
     * when the labeler is closed, the bytes will not decode, ML Kit fails, or
     * the detector does not answer within [LABEL_TIMEOUT_MS].
     *
     * Expected input: the same JPEG `ImageLoader.prepare` already produces for
     * the bridge, i.e. at most ~1280 px on the long edge and 8 MB. Anything
     * above [MAX_INPUT_BYTES] is refused before a single pixel is allocated.
     *
     * No EXIF is read, matching [FrameFingerprint]: the caller is expected to
     * hand over pixels already in their final orientation. A frame that arrives
     * sideways will still be labelled, just on a rotated picture — a known
     * limitation, not a crash.
     */
    suspend fun analyze(jpegBytes: ByteArray): FrameContent = withContext(Dispatchers.Default) {
        val startedAt = System.nanoTime()
        analyse(jpegBytes, startedAt)
    }

    /**
     * Releases the native detector. Idempotent, and [analyze] stays safe to call
     * afterwards — it simply reports itself unavailable instead of throwing.
     */
    fun close() {
        labelerRef.getAndSet(null)?.let { labeler ->
            runCatching { labeler.close() }
        }
    }

    private suspend fun analyse(jpegBytes: ByteArray, startedAt: Long): FrameContent {
        if (jpegBytes.isEmpty()) return unavailable(EMPTY_INPUT_REASON, startedAt)
        if (jpegBytes.size > MAX_INPUT_BYTES) return unavailable(TOO_LARGE_REASON, startedAt)

        // Created on first use and kept for the life of the analyzer: building a
        // labeler is the expensive part, and a second one would double the
        // model's footprint for no gain.
        val labeler = acquireLabeler() ?: return unavailable(CLOSED_REASON, startedAt)

        val bitmap = try {
            decodeForLabelling(jpegBytes)
        } catch (error: OutOfMemoryError) {
            return unavailable(DECODE_FAILED_REASON, startedAt)
        } catch (error: Exception) {
            return unavailable(error.readableMessage(), startedAt)
        }
        if (bitmap == null) return unavailable(DECODE_FAILED_REASON, startedAt)

        // The bitmap is deliberately never recycled. After LABEL_TIMEOUT_MS the
        // ML Kit Task may still be holding it — that is what the timeout means —
        // and recycling underneath it risks a native crash. At the sub-sampled
        // size above, letting the GC take it costs nothing.
        val labels = try {
            withTimeoutOrNull(LABEL_TIMEOUT_MS) {
                labeler.process(InputImage.fromBitmap(bitmap, ROTATION_UPRIGHT)).awaitCompletion()
            }
        } catch (error: CancellationException) {
            // Never swallow this: withTimeoutOrNull relies on it to give up, and
            // it is how a cancelled caller unwinds.
            throw error
        } catch (error: Exception) {
            return unavailable(describe(error), startedAt)
        }

        if (labels == null) return unavailable(TIMEOUT_REASON, startedAt)
        return toFrameContent(labels, startedAt)
    }

    /**
     * The detector, created once.
     *
     * `ImageLabeling.getClient` is expected to be cheap and total, but a class
     * that cannot be instantiated must degrade to "unavailable" rather than
     * crash the first frame of every session — so the creation is guarded, and
     * `null` here always means "no detector available".
     */
    private fun acquireLabeler(): ImageLabeler? {
        labelerRef.get()?.let { return it }
        val created = runCatching {
            ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
        }.getOrNull() ?: return null

        return if (labelerRef.compareAndSet(null, created)) {
            created
        } else {
            // Another thread won; drop ours on the floor and use the winner.
            runCatching { created.close() }
            labelerRef.get()
        }
    }

    /** Decoded, sub-sampled copy for the labeler, or `null` if the bytes are not an image. */
    private fun decodeForLabelling(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /**
     * The largest power-of-two sub-sample that still leaves the long edge above
     * [MAX_INPUT_EDGE_PX] — `BitmapFactory` only accepts powers of two, so the
     * decoded edge lands somewhere in `[640, 1280)` px rather than exactly on
     * the target. Close enough: the labeler resizes internally anyway.
     */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        var longEdge = maxOf(width, height)
        while (longEdge / 2 >= MAX_INPUT_EDGE_PX) {
            longEdge /= 2
            sample *= 2
        }
        return sample
    }

    /**
     * Bridges a Play services `Task` into a suspending call.
     *
     * ML Kit returns a [Task] and there is no `Task.await()`; the `addOn…Listener`
     * pair is the documented API and the only thing used here. A cancelled
     * continuation simply ignores whichever listener arrives late, so cancelling
     * the caller costs nothing. A cancelled *Task* fires neither listener — which
     * is precisely why the call is wrapped in a timeout rather than left to hang.
     */
    private suspend fun Task<List<ImageLabel>>.awaitCompletion(): List<ImageLabel> =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { labels -> continuation.resume(labels) }
            addOnFailureListener { error -> continuation.resumeWith(Result.failure(error)) }
        }

    private fun toFrameContent(raw: List<ImageLabel>, startedAt: Long): FrameContent {
        // ML Kit hands labels back best-first already; re-sorting costs nothing
        // and makes every bucket below descending by construction.
        val labels = raw
            .map { ContentLabel(label = it.text.orEmpty().trim(), confidence = it.confidence) }
            .filter { it.label.isNotEmpty() && it.confidence >= MIN_CONFIDENCE }
            .sortedByDescending { it.confidence }

        // Buckets are matched case-insensitively, but keep the original spelling
        // in the output — these strings are shown to a person.
        val keys = labels.map { it.label.lowercase(Locale.ROOT) }

        return FrameContent(
            labels = labels,
            subjects = labels.map { it.label }.take(MAX_HINTS),
            placeHints = bucket(keys, PLACE_LABELS),
            itemHints = bucket(keys, ITEM_LABELS),
            hasPerson = keys.any { it in PERSON_LABELS },
            analyzed = true,
            unavailableReason = null,
            elapsedMs = elapsedMs(startedAt),
        )
    }

    /** The [keys] that mean a thing in [members], best first, deduplicated and capped. */
    private fun bucket(keys: List<String>, members: Set<String>): List<String> =
        keys.filter { it in members }.distinct().take(MAX_HINTS)

    /**
     * A failure the user could read. ML Kit's own exception gets the fixed
     * Russian phrasing — its message is an English diagnostic code, not something
     * to put in front of anyone — while anything else goes through
     * [readableMessage] like the rest of the app.
     */
    private fun describe(error: Throwable): String {
        if (error !is MlKitException) return error.readableMessage()
        val detail = error.message?.takeIf { it.isNotBlank() }
        return if (detail == null) MLKIT_FAILED_REASON else "$MLKIT_FAILED_REASON: $detail"
    }

    private fun unavailable(reason: String, startedAt: Long): FrameContent =
        FrameContent(analyzed = false, unavailableReason = reason, elapsedMs = elapsedMs(startedAt))

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L

    private companion object {

        /**
         * ML Kit's confidence is an uncalibrated classifier score, not a
         * probability: a plainly correct "sky" over an anime cel often lands
         * near 0.6, and a low floor keeps that while dropping the noise tail.
         */
        const val MIN_CONFIDENCE = 0.55f

        /**
         * Eight rows per bucket. Past that a person stops scanning and the
         * bridge payload stops being worth sending.
         */
        const val MAX_HINTS = 8

        /**
         * Decode target for the long edge, in pixels. The labeler shrinks its
         * input internally, so a larger bitmap only buys decode time and heap on
         * a 2 GB phone; the caller already hands over frames capped at 1280 px.
         */
        const val MAX_INPUT_EDGE_PX = 640

        /** ~12 MB of JPEG is far past any sane frame; refuse before allocating. */
        const val MAX_INPUT_BYTES = 12 * 1024 * 1024

        /**
         * Upper bound on how long a frame may hold the caller. ML Kit has no
         * `Task.await`, so this is what replaces that call's own timeout.
         */
        const val LABEL_TIMEOUT_MS = 10_000L

        /** Callers hand over pixels already upright, so no rotation is applied. */
        const val ROTATION_UPRIGHT = 0

        const val EMPTY_INPUT_REASON = "Кадр пустой"
        const val TOO_LARGE_REASON = "Кадр слишком большой для анализа"
        const val DECODE_FAILED_REASON = "Не удалось прочитать кадр"
        const val CLOSED_REASON = "Распознавание содержимого выключено"
        const val TIMEOUT_REASON = "Распознавание содержимого заняло слишком много времени"
        const val MLKIT_FAILED_REASON = "Распознавание содержимого недоступно на этом устройстве"

        /** Any of these makes the frame "about a person". */
        val PERSON_LABELS = setOf(
            "person", "people", "face", "human", "man", "woman", "boy", "girl", "crowd", "group", "portrait",
        )

        /** Scene and background words — where the frame seems to be. */
        val PLACE_LABELS = setOf(
            "sky", "building", "room", "indoor", "outdoor", "street", "forest", "mountain", "water", "beach",
            "night", "grass", "field", "classroom", "kitchen", "bedroom", "hall", "station", "bridge", "castle",
            "temple", "school", "park", "sea", "river", "snow", "desert", "road", "house", "window", "door",
            "floor", "ceiling", "wall",
        )

        /** Things that can be held, worn or put on a table. */
        val ITEM_LABELS = setOf(
            "food", "drink", "cup", "book", "phone", "laptop", "computer", "chair", "table", "bed", "flower",
            "plant", "animal", "cat", "dog", "bird", "car", "vehicle", "bag", "box", "weapon", "sword",
            "umbrella", "hat", "shoe", "glasses", "clock", "picture", "poster", "television", "bottle", "plate",
            "fruit", "vegetable", "cake", "candy", "toy", "ball", "guitar", "piano",
        )
    }
}