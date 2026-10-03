package dev.amps.app.data.model

import kotlinx.serialization.Serializable

/**
 * One generic label ML Kit read out of a frame, with the raw classifier score.
 *
 * [label] is ML Kit's own English text — `sky`, `building`, `food`, `person`.
 * That is the entire vocabulary available here: the bundled image labeler knows
 * a few hundred everyday categories and nothing whatsoever about anime. It
 * cannot name a character, a series, or a fictional place, and this type must
 * never imply that it can. Named entities come from elsewhere — a wiki lookup
 * on the bridge — and are joined to this signal later.
 */
@Serializable
data class ContentLabel(val label: String, val confidence: Float)

/**
 * 1.0.2: что на самом деле есть на картинке — на случай, когда обратный поиск
 * ничего не нашёл.
 *
 * Before 1.0.2 a frame either matched or it did not, and a miss meant the app
 * knew nothing at all. This is the honest answer to "what is in this picture":
 * generic objects and scenes, bucketed so a later stage can use them without
 * re-parsing the raw label list, and useful on their own to show the user
 * something instead of an empty screen.
 *
 * What this is **not**: it does not identify characters, series or specific
 * locations. ML Kit's image labeler emits everyday English categories only.
 *
 * Shape of a result:
 *  - [labels] keeps every surviving label with its score, so nothing is lost;
 *  - [subjects] is the same list reduced to bare strings, best first;
 *  - [placeHints] / [itemHints] are the meaning buckets, capped and ordered;
 *  - [hasPerson] is the one boolean a caller can branch on cheaply;
 *  - [analyzed] is false whenever [unavailableReason] is set, which is the
 *    normal state on a device where the labeler could not run at all — the app
 *    must keep searching in that case.
 *
 * @Serializable because the bridge ranking endpoint consumes this as JSON.
 */
@Serializable
data class FrameContent(
    val labels: List<ContentLabel> = emptyList(),
    /** "person", "food", "building"… — the raw labels bucketed by meaning. */
    val subjects: List<String> = emptyList(),
    val placeHints: List<String> = emptyList(),
    val itemHints: List<String> = emptyList(),
    val hasPerson: Boolean = false,
    val analyzed: Boolean = false,
    val unavailableReason: String? = null,
    val elapsedMs: Long = 0L,
)