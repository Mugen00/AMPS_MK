package dev.kagami.app.data.model

/** Domain models for the anime wiki page. */

data class AnimeMedia(
    val id: Int,
    val idMal: Int? = null,
    val title: Titles = Titles(),
    val description: String? = null,
    val synonyms: List<String> = emptyList(),
    val format: String? = null,
    val status: String? = null,
    val episodes: Int? = null,
    val duration: Int? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val startDate: FuzzyDate? = null,
    val genres: List<String> = emptyList(),
    val tags: List<AnimeTag> = emptyList(),
    val cover: String? = null,
    val banner: String? = null,
    val coverColor: String? = null,
    val score: Int? = null,
    val popularity: Int? = null,
    val favourites: Int? = null,
    val trending: Int? = null,
    val isAdult: Boolean = false,
    val studios: List<Studio> = emptyList(),
    val characters: List<AnimeCharacter> = emptyList(),
    val relations: List<Relation> = emptyList(),
    val recommendations: List<AnimeMedia> = emptyList(),
    val externalLinks: List<ExternalLink> = emptyList(),
    val trailerThumbnail: String? = null,
) {
    val anilistUrl: String get() = "https://anilist.co/anime/$id"
    val malUrl: String? get() = idMal?.let { "https://myanimelist.net/anime/$it" }
    val anidbUrl: String? get() = idMal?.let { "https://anidb.net/anime/$it" }
}

data class FuzzyDate(val year: Int? = null, val month: Int? = null, val day: Int? = null) {
    val shortLabel: String?
        get() = when {
            year == null -> null
            month == null || day == null -> year.toString()
            else -> "${format(month)}.${format(day)}.$year"
        }

    private fun format(value: Int) = value.toString().padStart(2, '0')
}

data class AnimeTag(val id: Int, val name: String, val rank: Int)

data class Studio(val name: String, val isMain: Boolean)

data class Relation(val type: String, val media: AnimeMedia)

data class ExternalLink(val site: String, val url: String, val type: String? = null)

data class AnimeCharacter(
    val id: Int,
    val name: CharacterName = CharacterName(),
    val role: String = "MAIN",
    val image: String? = null,
    val description: String? = null,
    val age: String? = null,
    val gender: String? = null,
    val bloodType: String? = null,
    val isAlive: Boolean? = null,
    val favourites: Int = 0,
    val appearances: List<AnimeMedia> = emptyList(),
) {
    val anilistUrl: String get() = "https://anilist.co/character/$id"
    val displayName: String? get() = name.full ?: name.native
}

data class CharacterName(val full: String? = null, val native: String? = null)

/** Where the frame was found. */
data class FrameHit(
    val engine: String = "trace.moe",
    val episode: Int? = null,
    val frame: Int? = null,
    val timestamp: Double? = null,
    val similarity: Double? = null,
    val sceneUrl: String? = null,
) {
    val timestampLabel: String?
        get() = timestamp?.let {
            val total = it.toLong()
            val minutes = total / 60
            val seconds = total % 60
            "%d:%02d".format(minutes, seconds)
        }

    val similarityPercent: Int? get() = similarity?.let { (it * 100).toInt() }

    /** trace.moe publishes a still for every match; same id, different path. */
    val previewImageUrl: String?
        get() = sceneUrl
            ?.replace("/video/", "/image/")
            ?.let { if (it.contains('?')) it else "$it?size=l" }
}

/** A character that was picked out of the frame, with the reason it won. */
data class CharacterGuess(
    val character: AnimeCharacter,
    val score: Double,
    val reason: String,
)

data class ExternalArt(
    val source: String,
    val title: String?,
    val author: String?,
    val url: String?,
    val similarity: Int?,
)

/** Everything a single frame resolved to: this is what the wiki screen renders. */
data class AnimeWikiPage(
    val media: AnimeMedia,
    val frame: FrameHit? = null,
    val guess: CharacterGuess? = null,
    val candidates: List<AnimeCharacter> = emptyList(),
    val similarArt: List<ExternalArt> = emptyList(),
    val sources: List<SourceRef> = emptyList(),
    val rawEngineText: String? = null,
    val searchedImage: String? = null,
    val searchedImageSha256: String? = null,
)

data class SourceRef(val label: String, val url: String? = null, val note: String? = null)

/** A frame that produced nothing usable. */
data class EmptyResult(
    val reason: String,
    val raw: String?,
    val searchedImage: String? = null,
    val searchedImageSha256: String? = null,
    val bridgeError: String? = null,
)
