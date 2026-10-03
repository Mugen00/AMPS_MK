package dev.amps.app.data.ranking

import dev.amps.app.data.model.FrameVerdict
import dev.amps.app.data.model.RankedCandidate

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Взвешивает совпадения IQDB в один честный ответ.
 *
 * **Что изменилось в 1.0.5 и почему это важнее формул.** До этого вердикт
 * складывался из трёх независимых сервисов, и уверенность росла оттого, что их
 * становилось больше. С 1.0.5 источник один — [dev.amps.app.data.remote.IqdbClient].
 * Это меняет главное правило: IQDB нельзя считать тем же, чем был trace.moe.
 *
 * IQDB — это не база, а **сборщик**: один запрос спрашивает сразу Danbooru,
 * Konachan, yande.re, Gelbooru, Sankaku, e-shuushuu, Zerochan и Anime-Pictures.
 * Формально источник один, фактически — независимые бо́ру-базы внутри одного
 * ответа. Отсюда и решение ниже:
 *
 * - согласие **нескольких разных баз** с высоким процентом — законное
 *   независимое подтверждение, потому что базы наполняют разные люди и
 *   пересечение между ними малое;
 * - но подтверждение из **одной** базы — не подтверждение. Именно поэтому
 *   действует [SINGLE_SOURCE_CAP], и «определено» недостижимо в одиночку.
 *
 * **`score` — это сумма согласия, а не вероятность.** В пределах 0..1 зажимается
 * только `confidence`.
 */
object RankingEngine {

    /** Метки, означающие «на картинке есть человек». */
    private val PERSON_LABELS = setOf(
        "person", "people", "face", "human", "man", "woman", "boy", "girl", "crowd", "portrait",
    )

    // --- веса ----------------------------------------------------------------
    // Числа перенесены из трёх-источниковой схемы 1.0.4 и пересобраны вокруг
    // одного сигнала. Главный вес остался тем же — 0.55: столько же отдавался
    // trace.moe, и процент сходства IQDB так же прямо переводится в уверенность.

    /** Сходство лучшего совпадения: единственный основной сигнал. */
    private const val W_SIMILARITY = 0.55f

    /**
     * Бонус за каждую **дополнительную** независимую базу, сошедшуюся с
     * процентом не ниже [T_AGREEMENT]. Верхняя граница [W_AGREEMENT_MAX]
     * ограничивает сумму: десяток баз не должен превращать 70 % в 100 %.
     */
    private const val W_AGREEMENT = 0.16f
    private const val W_AGREEMENT_MAX = 0.32f

    /** Персонаж найден по бо́ру-тегу и подтверждён AniList — именная находка. */
    private const val W_CHARACTER = 0.10f

    /** Серия названа по тегу серии — слабее, чем именная находка персонажа. */
    private const val W_SERIES = 0.06f

    // --- пороги --------------------------------------------------------------

    /** Ниже этого сходства IQDB сам считает совпадение шумом ([MIN_SIMILARITY]). */
    private const val T_MIN_SIMILARITY = 0.60f

    /**
     * Порог «высокого» процента для согласия баз. 70 % — заметно выше
     * [T_MIN_SIMILARITY]: две базы, просто ответившие «что-то похожее», не
     * подтверждают друг друга.
     */
    private const val T_AGREEMENT = 0.70f

    /** Уверенный счёт, выше которого вердикт может стать `identified`. */
    private const val T_IDENTIFIED = 0.70f

    /** Ниже этой итоговой оценки вердикт всё равно остаётся спорным. */
    private const val T_FLOOR = 0.30f

    /** Процент, начиная с которого второе совпадение с другим тегом серии считается спором. */
    private const val T_CONFLICT = 0.75f

    /** Сколько уверенности срезает неснятый конфликт баз. */
    private const val T_CONFLICT_FACTOR = 0.75f

    /** Сколько уверенности срезает противоречие с метками содержимого. */
    private const val T_LABEL_FACTOR = 0.70f

    /** Насколько ML Kit верит в слово, прежде чем считать его фактом. */
    private const val T_LABEL_TRUST = 0.5f

    private const val T_MAX_CANDIDATES = 5

    /**
     * Один источник не дотягивает до «определено» сколь угодно высоким
     * сходством. Это не перестраховка: IQDB отдаёт лучший процент среди
     * бо́ру-баз, и 99 % в одной базе означает лишь «очень похоже на то, что
     * там лежит», а не «это то самое произведение».
     */
    private const val SINGLE_SOURCE_CAP = 0.55f

    private fun clamp01(value: Float): Float = if (value.isNaN()) 0f else value.coerceIn(0f, 1f)

    private fun round3(value: Float): Float = (value * 1000f).roundToInt() / 1000f

    private fun percent(value: Float): String =
        String.format(java.util.Locale.US, "%.1f %%", clamp01(value) * 100f)

    /** Приводит бо́ру-тег к сравнимому виду: `Axis_Powers!` и `axis powers` — одно. */
    private fun normalizeTag(value: String): String =
        value.lowercase().replace('!', ' ').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    // --- входные сигналы ----------------------------------------------------

    /** Одно совпадение IQDB: одна бо́ру-база, её процент и её теги. */
    data class IqdbHit(
        /** Danbooru, Konachan, Sankaku, e-shuushuu… — как их назвал IQDB. */
        val source: String = "",
        /** Сходство 0..1. В ответе IQDB приходит целым процентом. */
        val similarity: Float = 0f,
        val url: String? = null,
        /** IQDB пометил находку как «Best match». */
        val isBest: Boolean = false,
        val characterTags: List<String> = emptyList(),
        val seriesTags: List<String> = emptyList(),
    )

    /** Что дал IQDB целиком, вместе с тем, что AniList смог из этого получить. */
    data class IqdbResult(
        val hits: List<IqdbHit> = emptyList(),
        /** IQDB спрятал точную находку в блоке «Best match». */
        val exactMatchFound: Boolean = false,
        val scannedImages: Int? = null,
        /** Серия, названная по бо́ру-тегам через AniList. */
        val resolvedMediaId: Int? = null,
        val resolvedMediaTitle: String? = null,
        /** Чем именно серия названа: «тег персонажа», «тег серии»… */
        val resolvedVia: String? = null,
        /** Сам бо́ру-тег, по которому AniList назвал серию. */
        val resolvedViaTag: String? = null,
        /** Персонаж, чьё имя найдено в тегах IQDB и подтверждено AniList. */
        val confirmedCharacter: String? = null,
    )

    data class Label(val label: String, val confidence: Float)

    data class Signals(
        /** null — поиск не дал результата вовсе (ошибка или пустой ответ). */
        val iqdb: IqdbResult? = null,
        /** Что IQDB ответил вместо результата. */
        val iqdbError: String? = null,
        val labels: List<Label> = emptyList(),
    )

    // --- вердикт ------------------------------------------------------------

    fun rank(signals: Signals): FrameVerdict {
        val reasons = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val labelsPresent = signals.labels.isNotEmpty()
        val hasPerson = signals.labels.any {
            it.label.lowercase() in PERSON_LABELS && it.confidence >= T_LABEL_TRUST
        }
        val labelNames = signals.labels.take(4).joinToString(", ") { it.label }

        val result = signals.iqdb
        if (result == null) {
            reasons += "IQDB не ответил: ${signals.iqdbError ?: "причина неизвестна"}. " +
                "Определять нечего, а догадываться без ответа — значит выдумывать."
            warnings += "Источник недоступен, поэтому вердикт пустой. Повторите поиск при связи с интернетом."
            if (labelsPresent) {
                reasons += "Метки содержимого ($labelNames) не называют произведение — в ответ они не идут."
            }
            return FrameVerdict(
                decision = "rejected",
                confidence = 0f,
                reasons = reasons,
                warnings = warnings,
            )
        }

        val best = result.hits.maxByOrNull { it.similarity }
        if (best == null) {
            reasons += "IQDB просмотрел ${result.scannedImages ?: "—"} изображений и не нашёл ни одного совпадения " +
                "не ниже ${percent(T_MIN_SIMILARITY)} — именно с этого порога сервис сам считает находку настоящей."
            warnings += "Совпадений не найдено. IQDB ищет по иллюстрациям и скриншотам, которые уже лежат в бо́ру-базах; " +
                "обычное фото или скриншот из видеоигры там не лежат."
            if (labelsPresent) {
                reasons += "Метки содержимого ($labelNames) тайтл не называют — совпадение всё равно нужно."
            }
            return FrameVerdict(
                decision = "rejected",
                confidence = 0f,
                reasons = reasons,
                warnings = warnings,
            )
        }

        // --- сигналы ---------------------------------------------------------

        val bestSimilarity = best.similarity
        val agreeing = result.hits
            .filter { it.similarity >= T_AGREEMENT }
            .map { it.source.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        // Спор: две сильные находки с **непересекающимися** тегами серии
        // действительно указывают в разные произведения. Находка без тегов
        // серии спором не считается — тегов просто может не быть.
        val strongSeriesTags = result.hits
            .filter { it.similarity >= T_CONFLICT }
            .map { it.seriesTags.map(::normalizeTag).filter(String::isNotEmpty).toSet() }
            .filter { it.isNotEmpty() }
        val conflict = strongSeriesTags.distinct().size > 1

        val parts = mutableListOf<String>()
        var score = W_SIMILARITY * clamp01(bestSimilarity)
        parts += "IQDB: лучшее совпадение ${percent(bestSimilarity)} в базе «${best.source}» → вклад ${round3(score)}"

        if (bestSimilarity < T_MIN_SIMILARITY) {
            reasons += "Лучшее совпадение ${percent(bestSimilarity)} ниже порога самого IQDB " +
                "(${percent(T_MIN_SIMILARITY)}) — сервис считает его шумом, и мы вместе с ним."
        }
        if (result.exactMatchFound) {
            parts += "IQDB пометил находку как точную (Best match), а не как дополнительную"
        } else {
            reasons += "IQDB не пометил находку как точную: «Best match» на странице нет, значит это лучшее из похожих, а не сама картинка."
        }

        if (agreeing.size >= 2) {
            val bonus = minOf(W_AGREEMENT_MAX, W_AGREEMENT * (agreeing.size - 1))
            score += bonus
            parts += "независимые базы сошлись с процентом не ниже ${percent(T_AGREEMENT)}: " +
                "${agreeing.joinToString(", ")} → +${round3(bonus)}"
        } else {
            val only = agreeing.firstOrNull() ?: best.source
            parts += "согласие только одной базы («$only») — независимого подтверждения нет"
        }

        if (result.confirmedCharacter != null) {
            score += W_CHARACTER
            parts += "персонаж «${result.confirmedCharacter}» найден по бо́ру-тегу и подтверждён AniList → +${round3(W_CHARACTER)}"
        }
        if (result.resolvedMediaId != null && result.resolvedVia != null) {
            score += W_SERIES
            parts += "серия «${result.resolvedMediaTitle ?: "AniList ${result.resolvedMediaId}"}» названа по ${result.resolvedVia} → +${round3(W_SERIES)}"
        }

        // --- кандидаты ------------------------------------------------------

        val groups = groupCandidates(result).toMutableList()
        val resolvedTag = normalizeTag(result.resolvedViaTag.orEmpty())
        groups.forEach { group ->
            if (resolvedTag.isNotEmpty() && group.key == resolvedTag) {
                group.anilistId = result.resolvedMediaId
                group.title = result.resolvedMediaTitle ?: group.title
            }
        }
        groups.sortWith(
            compareByDescending<CandidateGroup> { it.similarity }
                .thenByDescending { it.sources.size }
                .thenBy { it.key },
        )

        val runner = groups.getOrNull(1)
        val winner = groups.firstOrNull()
        val margin = if (winner != null && runner != null && winner.similarity > 0f) {
            clamp01((winner.similarity - runner.similarity) / winner.similarity)
        } else {
            1f
        }

        var decision = "rejected"
        var confidence = 0f

        if (winner == null || winner.similarity <= 0f) {
            reasons += "Совпадений выше ${percent(T_MIN_SIMILARITY)} нет — показывать нечего."
        } else {
            confidence = clamp01(score) * (0.75f + 0.25f * margin)

            if (agreeing.size < 2) {
                confidence = minOf(confidence, SINGLE_SOURCE_CAP)
            }
            if (conflict) {
                confidence *= T_CONFLICT_FACTOR
                warnings += "Базы не согласны: сильные совпадения (не ниже ${percent(T_CONFLICT)}) " +
                    "указывают на разные теги серии — ${strongSeriesTags.distinct().joinToString(" / ") { tags -> tags.joinToString(", ") }}. " +
                    "Одно из них неверно, поэтому уверенность × $T_CONFLICT_FACTOR."
            }
            if (!result.exactMatchFound) {
                warnings += "Точного совпадения IQDB не нашёл. Совпадения есть, но все они — «похожее», " +
                    "поэтому назвать произведение точно нельзя: показываем лучшее и честно говорим, что это догадка."
            }

            // Метки содержимого на уверенность **идентификации** не влияют:
            // «человек на картинке» ничего не говорит о том, какая это серия.
            // Но найденное по персонажу совпадение они способны опровергнуть.
            val characterScoped = result.confirmedCharacter != null
            if (characterScoped && labelsPresent && !hasPerson) {
                confidence *= T_LABEL_FACTOR
                warnings += "Совпадение найдено по персонажу, а ML Kit на картинке не видит ни одного человека — точность снижена."
            }

            // «Определено» требует всего сразу: высокий счёт, согласие минимум
            // двух независимых баз и подтверждение точным совпадением. Любое
            // неснятое сомнение оставляет ответ спорным, но не отрицает его.
            decision = if (
                score >= T_IDENTIFIED &&
                agreeing.size >= 2 &&
                result.exactMatchFound &&
                !conflict &&
                !(characterScoped && labelsPresent && !hasPerson)
            ) {
                "identified"
            } else {
                "uncertain"
            }
            if (score < T_FLOOR) {
                reasons += "Итоговая оценка ${round3(score)} ниже порога ${round3(T_FLOOR)} — " +
                    "показываем найденное, но уверенным называть нельзя."
            }

            val named = result.resolvedMediaTitle
                ?: winner.title.ifBlank { "AniList ${result.resolvedMediaId}" }
            reasons += "Лучший вариант: $named с оценкой ${round3(score)}; источники внутри IQDB: " +
                (if (agreeing.isEmpty()) "только «${best.source}»" else agreeing.joinToString(", ")) + "."
            if (runner != null) {
                reasons += "Запас над вторым вариантом ${round3(winner.similarity - runner.similarity)} по сходству."
            } else {
                reasons += "Второго варианта в списке нет — спорить не с кем."
            }
            if (agreeing.size < 2) {
                reasons += "Источник один — независимого подтверждения нет, даже при 99 % это не «определено»."
            }
        }

        when {
            labelsPresent && hasPerson ->
                reasons += "Метки содержимого ($labelNames) видят человека — совпадение по персонажу картинкой не опровергнуто."
            labelsPresent ->
                reasons += "Метки содержимого ($labelNames) человека не видят; на уверенность идентификации это не влияет."
            else ->
                reasons += "Метки содержимого не пришли — проверка содержимого не выполнялась."
        }

        // Альтернативы показываем ровно те, что IQDB сам считает совпадением:
        // ниже [T_MIN_SIMILARITY] это шум, и подсовывать его пользователю как
        // «возможный вариант» — такое же враньё, как и уверенное молчание.
        val candidateRows = groups
            .filter { it.similarity >= T_MIN_SIMILARITY || it.anilistId != null }
            .take(T_MAX_CANDIDATES)
            .map { group ->
                RankedCandidate(
                    anilistId = group.anilistId ?: 0,
                    title = group.title.ifBlank { "тег «${group.key}»" },
                    score = round3(group.similarity),
                    sources = group.sources.toList(),
                    why = buildList {
                        add("лучшее сходство ${percent(group.similarity)}")
                        if (group.sources.size >= 2) {
                            add("базы сошлись: ${group.sources.joinToString(", ")}")
                        }
                        if (group.anilistId != null) add("серия подтверждена AniList по тегу «${result.resolvedViaTag}»")
                    },
                )
            }

        // Пошаговый разбор счёта уходит в объяснение целиком: пользователь
        // должен видеть, из чего сложилась уверенность, а не итоговый процент.
        reasons += parts

        return FrameVerdict(
            decision = decision,
            confidence = round3(clamp01(confidence)),
            reasons = reasons.distinct(),
            warnings = warnings.distinct(),
            candidates = candidateRows,
            agreedSources = agreeing,
        )
    }

    // --- вспомогательное ----------------------------------------------------

    /**
     * Группирует совпадения по серии.
     *
     * Ключ группы — тег серии из бо́ру-тегов (`axis powers`). Если тегов серии
     * у совпадения нет, группой становится сама база: у неё есть собственное
     * имя, и смешивать её с чужой серией было бы выдумкой.
     */
    private fun groupCandidates(result: IqdbResult): List<CandidateGroup> {
        val byKey = linkedMapOf<String, CandidateGroup>()
        result.hits.forEach { hit ->
            val tag = hit.seriesTags.firstOrNull()?.let(::normalizeTag).orEmpty()
            val key = tag.ifBlank { "база: ${hit.source.trim().lowercase()}" }
            val group = byKey.getOrPut(key) { CandidateGroup(key) }
            group.similarity = maxOf(group.similarity, hit.similarity)
            group.sources.add(hit.source.trim())
            if (group.title.isBlank()) {
                group.title = hit.seriesTags.firstOrNull()?.takeIf { it.isNotBlank() }
                    ?: hit.url?.substringAfter("//")?.substringBefore("/")
                    ?: hit.source
            }
        }
        return byKey.values.toList()
    }

    private class CandidateGroup(val key: String) {
        var title: String = ""
        var similarity: Float = 0f
        var anilistId: Int? = null
        val sources: MutableSet<String> = linkedSetOf()
    }
}