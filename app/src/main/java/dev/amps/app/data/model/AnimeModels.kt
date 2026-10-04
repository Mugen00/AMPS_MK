package dev.amps.app.data.model

import dev.amps.app.data.remote.TraceMoeClient

/** Domain models for the anime wiki page. */

/**
 * Названия произведения во всех видах, в которых их знают разные базы.
 *
 * С 1.0.5 эта модель переехала сюда из `BridgeModels.kt`: остальной файл был
 * DTO-моделями локального моста, которых с 1.0.3 нет, а после удаления
 * trace.moe и SauceNAO не осталось и их. Держать отдельный файл ради одной
 * структуры — значит врать структурой проекта.
 */
@kotlinx.serialization.Serializable
data class Titles(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
) {
    /** Best available display title: English, then romaji, then native. */
    val best: String? get() = english ?: romaji ?: native
}

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

/**
 * Где именно картинка нашлась.
 *
 * **1.0.5: отсюда ушли серия, эпизод, таймкод и ссылка на сцену.** Так выглядела
 * находка trace.moe — кадр из видеозаписи, поэтому у него был номер секунды.
 * IQDB ищет по иллюстрациям, скриншотам и фотографиям, а не по кадрам
 * записей, поэтому ни эпизода, ни секунды у него нет и **выдумывать их нельзя**:
 * правдивая пустая строка лучше красивого несуществующего таймкода.
 *
 * Осталось ровно то, что сервис сказал: какая база ответила, с каким сходством
 * и была ли находка точной.
 */
data class FrameHit(
    val source: String = "IQDB",
    /** Сходство целым процентом, как его отдаёт IQDB: 60..100. */
    val similarityPercent: Int? = null,
    /** Страница найденной картинки в бо́ру-базе. */
    val url: String? = null,
    /** IQDB пометил находку как «Best match», а не как дополнительную. */
    val exactMatch: Boolean = false,
) {
    val similarityLabel: String? get() = similarityPercent?.let { "$it %" }
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
    /** 1.0.2: насколько уверенно назван тайтл и почему. */
    val verdict: FrameVerdict? = null,
    /** 1.0.2: что модель увидела на самом кадре — люди, небо, стол, книга. */
    val content: FrameContent? = null,
    /** 1.0.2: фандом-вики серии. */
    val wiki: WikiReference? = null,
    /** 1.0.2: персонажи и места по вики — именами, а не QID. */
    val rosterCharacters: List<RosterEntry> = emptyList(),
    val rosterPlaces: List<RosterEntry> = emptyList(),
    /** 1.0.2: отпечаток кадра, чтобы можно было отправить его в общий индекс. */
    val frameHash: String? = null,
    /**
     * 1.0.6b: находка trace.moe, если серия названа по совпадению кадра.
     *
     * Это **доказательство**, по которому вообще появилась эта страница: имя
     * файла в архиве, серия, эпизод, секунда и сходство. Без него вики-страница
     * выглядит так, будто серия названа по бо́ру-тегам, а это другой и более
     * слабый путь. Название серии в [media] взято именно из этого совпадения.
     */
    val frameMatch: TraceMoeClient.Match? = null,
)

data class SourceRef(val label: String, val url: String? = null, val note: String? = null)

/**
 * Взвешенный вердикт по всем источникам сразу. Раньше приложение показывало
 * первый попавшийся ответ; теперь оно показывает степень уверенности, объяснение
 * и — когда источники спорят — альтернативы.
 */
data class FrameVerdict(
    val decision: String = "identified",
    val confidence: Float = 0f,
    val reasons: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val candidates: List<RankedCandidate> = emptyList(),
    val agreedSources: List<String> = emptyList(),
) {
    val identified: Boolean get() = decision == "identified"
    val uncertain: Boolean get() = decision == "uncertain"
    val rejected: Boolean get() = decision == "rejected"

    val confidencePercent: Int get() = (confidence * 100).toInt()

    /** Насколько честно показывать результат: уверенный, спорный или отвергнутый. */
    val headline: String?
        get() = when (decision) {
            "identified" -> "Определено с уверенностью $confidencePercent %"
            "uncertain" -> "Не уверен: источники расходятся"
            else -> "Не удалось определить уверенно"
        }
}

data class RankedCandidate(
    val anilistId: Int,
    val title: String,
    val score: Float = 0f,
    val sources: List<String> = emptyList(),
    val why: List<String> = emptyList(),
)

/** Вики-страница серии, найденная через фандомы и Wikidata. */
data class WikiReference(
    val slug: String? = null,
    val url: String? = null,
    val intro: String? = null,
    val images: List<String> = emptyList(),
)

data class RosterEntry(
    val name: String,
    val url: String? = null,
    val qid: String? = null,
    val source: String? = null,
)

/** A frame that produced nothing usable. */
data class EmptyResult(
    val reason: String,
    val raw: String?,
    val searchedImage: String? = null,
    val searchedImageSha256: String? = null,
    /**
     * 1.0.6b: описание внешности и ссылки на поиск по нему.
     *
     * Нужно потому, что «ничего не найдено» — худший ответ: человек остаётся
     * с картинкой и без единого движения вперёд. Описание внешности — это то,
     * что модель определяет верно, и из него собирается запрос.
     */
    val content: dev.amps.app.data.model.FrameContent? = null,
)
