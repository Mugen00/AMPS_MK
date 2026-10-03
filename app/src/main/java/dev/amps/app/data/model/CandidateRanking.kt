package dev.amps.app.data.model

import kotlinx.serialization.Serializable

/**
 * DTOs for the bridge's frame index and its ranked answer.
 *
 * Every field has a default, so an older bridge that answers with fewer keys
 * still decodes, and a newer one that adds keys is ignored rather than fatal
 * (`ignoreUnknownKeys` is on the client instances). The bridge speaks camelCase —
 * it is JavaScript — so no `@SerialName` renaming is needed anywhere here. Adding
 * snake_case aliases would silently drop every id instead.
 */

/** One anime the ranker considered, with the score it earned. */
@Serializable
data class RankCandidate(
    val anilistId: Int,
    val title: String,
    val score: Float,
    /** Engines that also pointed at this title, e.g. `trace.moe`, `SauceNAO`. */
    val sources: List<String> = emptyList(),
    /** Short human-readable reasons, already translated by the bridge. */
    val why: List<String> = emptyList(),
)

/** The single answer the ranker settled on. */
@Serializable
data class RankPrimary(
    val anilistId: Int,
    val title: String,
    /** The one engine that carried the decision, e.g. `index`. */
    val source: String,
    /** Every engine that agrees with [source]. */
    val agreement: List<String> = emptyList(),
)

/**
 * What `POST /api/rank` answers.
 *
 * [decision] is `"identified"`, `"uncertain"` or `"rejected"`; the candidates
 * are only worth showing for the first two, and [reasons] says why either way.
 */
@Serializable
data class RankResult(
    val decision: String,
    val confidence: Float = 0f,
    val primary: RankPrimary? = null,
    val candidates: List<RankCandidate> = emptyList(),
    val reasons: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

/** The indexed frame that came closest to the queried hash. */
@Serializable
data class IndexMatch(
    val anilistId: Int? = null,
    val seriesTitle: String? = null,
    val episode: Int? = null,
    val timestampSec: Float? = null,
    /** Hamming distance in bits; the bridge only returns a hit within the threshold. */
    val distance: Int? = null,
    val imageUrl: String? = null,
    val source: String? = null,
)

/**
 * `GET /api/frame/index/match`. The bridge answers with `entry`, not `match` —
 * a null [entry] means "nothing indexed looks like this", which is the normal
 * answer for almost every frame and must not be treated as an error.
 */
@Serializable
data class IndexLookup(
    val entry: IndexMatch? = null,
    /** Distance to the nearest indexed frame even when it was rejected as too far. */
    val distance: Int? = null,
    /** How many indexed frames the bridge actually compared against. */
    val checked: Int = 0,
    val matched: Boolean = false,
    val maxDistance: Int = 10,
)

/** `GET /api/frame/index/stats` — what the bridge has in its index right now. */
@Serializable
data class IndexStats(
    val count: Int = 0,
    val series: Int = 0,
    val episodes: Int = 0,
    val sources: List<String> = emptyList(),
    val builtAt: String? = null,
)

/** One anime the bridge knows enough about to index frames from. */
@Serializable
data class CatalogEntry(
    val anilistId: Int,
    val title: CatalogTitles? = null,
    val synonyms: List<String> = emptyList(),
    val format: String? = null,
    val seasonYear: Int? = null,
    val episodes: Int? = null,
    val genres: List<String> = emptyList(),
    val popularity: Int? = null,
    val cover: String? = null,
    val studios: List<String> = emptyList(),
) {
    val bestTitle: String?
        get() = listOfNotNull(title?.romaji, title?.english, title?.native).firstOrNull()
}

@Serializable
data class CatalogTitles(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
)