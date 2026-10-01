package dev.amps.app.data.model

/** DTOs returned by the local bridge (see bridge/README.md). */

@kotlinx.serialization.Serializable
data class Titles(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
) {
    /** Best available display title: English, then romaji, then native. */
    val best: String? get() = english ?: romaji ?: native
}

@kotlinx.serialization.Serializable
data class BridgeAnime(
    val id: Int? = null,
    val idMal: Int? = null,
    val title: Titles? = null,
    val description: String? = null,
    val coverImage: BridgeCover? = null,
    val episodes: Int? = null,
    val genres: List<String> = emptyList(),
    val startDate: BridgeDate? = null,
    val status: String? = null,
    val format: String? = null,
    val averageScore: Int? = null,
    val popularity: Int? = null,
    val synonyms: List<String> = emptyList(),
    val isAdult: Boolean = false,
)

@kotlinx.serialization.Serializable
data class BridgeCover(
    val extraLarge: String? = null,
    val large: String? = null,
    val medium: String? = null,
) {
    val best: String? get() = extraLarge ?: large ?: medium
}

@kotlinx.serialization.Serializable
data class BridgeDate(val year: Int? = null, val month: Int? = null, val day: Int? = null)

@kotlinx.serialization.Serializable
data class BridgeVideo(
    val id: String? = null,
    val part: Int? = null,
    val length: Int? = null,
    val url: String? = null,
)

@kotlinx.serialization.Serializable
data class TraceResult(
    val engine: String = "trace.moe",
    val matched: Boolean = false,
    val anilist: BridgeAnime? = null,
    val episode: Int? = null,
    val frame: Int? = null,
    val timestamp: Double? = null,
    val similarity: Double? = null,
    val video: BridgeVideo? = null,
    val raw: String? = null,
)

@kotlinx.serialization.Serializable
data class SauceResult(
    val configured: Boolean = false,
    val matched: Boolean = false,
    val similarity: Double? = null,
    val index: Int? = null,
    val source: String? = null,
    val title: String? = null,
    val url: String? = null,
    val author: String? = null,
    val characters: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val series: String? = null,
    val copyright: String? = null,
    val results: List<SauceCandidate> = emptyList(),
    val raw: String? = null,
)

/** One SauceNAO hit, kept so the wiki page can show the other matches too. */
@kotlinx.serialization.Serializable
data class SauceCandidate(
    val index: Int? = null,
    val similarity: Double? = null,
    val title: String? = null,
    val author: String? = null,
    val url: String? = null,
    val characters: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val series: String? = null,
    val copyright: String? = null,
)

@kotlinx.serialization.Serializable
data class FrameIdentifyResponse(
    val trace: TraceResult? = null,
    val sauce: SauceResult? = null,
    val error: String? = null,
    val message: String? = null,
)

@kotlinx.serialization.Serializable
data class BridgeHealth(
    val ok: Boolean = false,
    val version: String? = null,
    val uptimeSec: Long? = null,
    val keys: BridgeKeys = BridgeKeys(),
    val nodes: Map<String, BridgeNode> = emptyMap(),
    val error: String? = null,
    val message: String? = null,
)

@kotlinx.serialization.Serializable
data class BridgeKeys(
    val traceMoe: Boolean = false,
    val sauceNao: Boolean = false,
)

@kotlinx.serialization.Serializable
data class BridgeNode(
    val ready: Boolean = false,
    val tools: List<String> = emptyList(),
    val error: String? = null,
)
