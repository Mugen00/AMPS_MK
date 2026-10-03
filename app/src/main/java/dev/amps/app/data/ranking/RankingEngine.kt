package dev.amps.app.data.ranking

import dev.amps.app.data.model.FrameVerdict
import dev.amps.app.data.model.RankedCandidate

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Взвешивает несколько слабых сигналов в один честный ответ.
 *
 * **Почему это в приложении, а не на сервере.** С 1.0.3 моста нет вообще, и
 * «спросить у кого-то» больше некому. Правила при этом не изменились: это
 * буквальный перенос `bridge/src/ranking.mjs`, включая пороги и формулировки
 * причин. Расхождение в одном пороге дало бы тихую смену вердикта, поэтому
 * числа здесь зафиксированы и покрыты тестом.
 *
 * **`score` — это сумма согласия, а не вероятность.** Два сошедшихся источника
 * плюс бонус за независимость законно дают около 1.0, три — могут её превысить.
 * В пределах 0..1 зажимается только `confidence`.
 */
object RankingEngine {

    /** Метки, означающие «на кадре есть человек». */
    private val PERSON_LABELS = setOf(
        "person", "people", "face", "human", "man", "woman", "boy", "girl", "crowd", "portrait",
    )

    private const val W_TRACE = 0.55f
    private const val W_SAUCE = 0.26f
    private const val W_INDEX = 0.24f
    private const val W_AGREEMENT = 0.16f
    private const val W_AGREEMENT_MAX = 0.32f

    private const val T_FLOOR = 0.45f
    private const val T_IDENTIFIED = 0.7f
    private const val T_SAUCE_DROP = 0.45f
    private const val T_SAUCE_OUTRANK = 0.6f
    private const val T_SAUCE_CONFLICT = 0.3f
    private const val T_INDEX_WINDOW = 60f
    private const val T_CONFLICT_FACTOR = 0.75f
    private const val T_LABEL_FACTOR = 0.7f
    private const val T_LABEL_TRUST = 0.5f
    private const val T_MAX_CANDIDATES = 5

    /** Один источник не дотягивает до «определено» сколь угодно высоким сходством. */
    private const val SINGLE_SOURCE_CAP = 0.55f

    private fun clamp01(value: Float): Float = if (value.isNaN()) 0f else value.coerceIn(0f, 1f)

    private fun round3(value: Float): Float {
        val scaled = value * 1000f
        // Kotlin.roundToInt округляет половины от нуля, JS toFixed — от нуля же,
        // но для отрицательных знаков поведение совпадает, а положительные у нас
        // единственные, что действительно важны.
        return (scaled.roundToInt()) / 1000f
    }

    private fun percent(value: Float): String =
        String.format(java.util.Locale.US, "%.1f %%", clamp01(value) * 100f)

    private fun normalizeTitle(value: String?): String =
        value?.lowercase()?.replace(Regex("[^a-z0-9а-яё]+"), " ")?.trim().orEmpty()

    private fun sourceName(id: String): String = when (id) {
        "trace" -> "trace.moe"
        "sauce" -> "SauceNAO"
        "index" -> "индекс кадров"
        else -> id
    }

    // --- входные сигналы ----------------------------------------------------

    /** Что сказал trace.moe. */
    data class TraceHit(
        val matched: Boolean = false,
        val anilistId: Int? = null,
        /** Все известные названия серии: английское, ромадзи, местное, синонимы. */
        val titles: List<String> = emptyList(),
        val similarity: Float = 0f,
        val episode: Int? = null,
        val timestamp: Float? = null,
    )

    /** Одно совпадение SauceNAO. */
    data class SauceHit(
        val similarity: Float = 0f,
        val anilistId: Int? = null,
        val source: String? = null,
        val title: String? = null,
        val series: String? = null,
        val copyright: String? = null,
        val characters: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
    )

    /** Что ответил индекс кадров. */
    data class IndexHit(
        val matched: Boolean = false,
        val anilistId: Int? = null,
        val episode: Int? = null,
        val timestamp: Float? = null,
        val distance: Int = 0,
    )

    data class Label(val label: String, val confidence: Float)

    data class Signals(
        val trace: TraceHit? = null,
        /** null — SauceNAO вообще не настроен; иначе список его совпадений. */
        val sauce: List<SauceHit>? = null,
        val index: IndexHit? = null,
        val labels: List<Label> = emptyList(),
    )

    // --- внутренние слоты ---------------------------------------------------

    private class Slot(val key: String) {
        var anilistId: Int? = null
        var trace: TraceHit? = null
        val sauce: MutableList<SauceHit> = mutableListOf()
        var index: IndexHit? = null
    }

    private class Scored(val slot: Slot, val score: Float, val sources: List<String>, val parts: List<String>, val lead: String?)

    fun rank(signals: Signals): FrameVerdict {
        val reasons = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val trace = signals.trace
        val traceId = trace?.anilistId?.takeIf { it > 0 }
        val hasTrace = trace != null && trace.matched && (traceId != null || trace.titles.isNotEmpty())

        val indexHit = signals.index?.takeIf { it.matched }
        val sauceHits = signals.sauce.orEmpty()
        val labelsPresent = signals.labels.isNotEmpty()
        val hasPerson = signals.labels.any {
            it.label.lowercase() in PERSON_LABELS && it.confidence >= T_LABEL_TRUST
        }

        if (signals.sauce == null) {
            warnings += "SauceNAO не настроен: второго источника нет, подтвердить совпадение нечем."
        }

        val pool = linkedMapOf<String, Slot>()
        fun slot(key: String): Slot = pool.getOrPut(key) { Slot(key) }

        var traceSlot: Slot? = null
        if (hasTrace) {
            traceSlot = slot(if (traceId != null) "id:$traceId" else "name:${normalizeTitle(trace!!.titles.firstOrNull())}")
            traceSlot.anilistId = traceId
            traceSlot.trace = trace
        }

        if (indexHit != null) {
            val id = indexHit.anilistId?.takeIf { it > 0 }
            val target = slot(if (id != null) "id:$id" else "name:${indexHit.episode ?: ""}")
            if (target.anilistId == null) target.anilistId = id
            target.index = indexHit
        }

        val kept = mutableListOf<SauceHit>()
        val dropped = mutableListOf<SauceHit>()
        val hits = mutableListOf<Pair<SauceHit, Slot>>()

        for (hit in sauceHits) {
            if (hit.similarity < T_SAUCE_DROP) {
                dropped += hit
                continue
            }
            val target: Slot? = when {
                hit.anilistId != null -> slot("id:${hit.anilistId}")
                traceSlot != null && nameMatchesTitle(hit, traceSlot!!.trace!!.titles) -> traceSlot
                else -> {
                    val name = normalizeTitle(hit.series ?: hit.copyright ?: hit.title)
                    if (name.isEmpty()) null else slot("name:$name")
                }
            }
            if (target == null) {
                dropped += hit
                continue
            }
            target.anilistId = target.anilistId ?: hit.anilistId
            target.sauce += hit
            kept += hit
            hits += hit to target
        }

        val ranked = pool.values
            .map { scoreSlot(it) }
            .filter { it.score >= T_FLOOR }
            .sortedWith(compareByDescending<Scored> { it.score }
                .thenByDescending { it.sources.size }
                .thenBy { it.slot.key })
            .toMutableList()

        applyOutrankGuard(ranked, reasons, warnings)

        val candidateRows = ranked.take(T_MAX_CANDIDATES).map { row ->
            RankedCandidate(
                anilistId = row.slot.anilistId ?: 0,
                title = bestTitle(row.slot) ?: "",
                score = row.score,
                sources = row.sources,
                why = row.parts,
            )
        }

        if (dropped.isNotEmpty()) {
            val listed = dropped.sortedByDescending { it.similarity }.take(4).joinToString(", ") { percent(it.similarity) }
            reasons += "SauceNAO вернул ещё ${dropped.size} совпадени(й) ниже порога ${percent(T_SAUCE_DROP)} ($listed) — это шум, они не попали в ответ."
        }

        val winner = ranked.firstOrNull()
        val runner = ranked.getOrNull(1)

        // Конфликт — сильное совпадение SauceNAO, ушедшее в другой слот.
        val conflicts = mutableListOf<SauceHit>()
        if (winner != null) {
            for ((hit, slot) in hits) {
                if (hit.similarity < T_SAUCE_CONFLICT) continue
                if (slot === winner.slot) continue
                if (conflicts.any { it === hit }) continue
                conflicts += hit
            }
        }
        for (hit in conflicts) {
            val winnerRef = winner?.slot?.anilistId
                ?: winner?.slot?.trace?.titles?.firstOrNull()
                ?: "другой тайтл"
            val hitName = hit.series ?: hit.copyright ?: hit.title ?: "без названия"
            warnings += "Источники не согласны: ${sourceName("trace")} называет $winnerRef (${percent(winner?.slot?.trace?.similarity ?: 0f)}), " +
                "а ${sourceName("sauce")} — ${hit.anilistId?.let { "AniList $it" } ?: "\"$hitName\""} (${percent(hit.similarity)}). " +
                "Одно из двух неверно, поэтому выбор не делаем."
        }

        val characterScoped = winner?.slot?.sauce?.any { it.characters.isNotEmpty() } == true
        var labelConflict = false
        val labelNames = signals.labels.take(4).joinToString(", ") { it.label }
        when {
            labelsPresent && !hasPerson && characterScoped -> {
                labelConflict = true
                warnings += "В кадре нет ни одного упоминания человека, а совпадение найдено по персонажам — точность снижена."
                reasons += "Метки содержимого ($labelNames) не содержат ни одного человека, хотя совпадение character-scoped — по персонажам. " +
                    "Уверенность снижена до ${(T_LABEL_FACTOR * 100).roundToInt()} %."
            }
            labelsPresent && !hasPerson ->
                reasons += "Метки содержимого ($labelNames) не содержат человека — совпадение не опровергнуто, но и не подтверждено."
            labelsPresent ->
                reasons += "Метки содержимого содержат человека — совпадение по персонажу подтверждено картинкой."
            else ->
                reasons += "Метки содержимого не пришли — проверка содержимого не выполнялась."
        }

        var decision = "rejected"
        var confidence = 0f
        if (winner != null) {
            val margin = if (runner != null) {
                clamp01((winner.score - runner.score) / max(winner.score, 1e-6f))
            } else {
                1f
            }
            confidence = clamp01(winner.score) * (0.75f + 0.25f * margin)
            if (winner.sources.size < 2) confidence = minOf(confidence, SINGLE_SOURCE_CAP)
            when {
                conflicts.isNotEmpty() -> {
                    confidence *= T_CONFLICT_FACTOR
                    decision = "uncertain"
                    reasons += "Конфликт источников: ${conflicts.size} совпадени(й) SauceNAO с сходством не ниже ${percent(T_SAUCE_CONFLICT)} указывают на другой тайтл. " +
                        "Ответ не схлопываем в одну догадку, уверенность × $T_CONFLICT_FACTOR."
                }
                labelConflict -> {
                    confidence *= T_LABEL_FACTOR
                    decision = "uncertain"
                }
                winner.score >= T_IDENTIFIED && winner.sources.size >= 2 -> decision = "identified"
                else -> decision = "uncertain"
            }
            val named = winner.slot.anilistId?.let { "AniList $it" }
                ?: "\"${winner.slot.trace?.titles?.firstOrNull() ?: winner.slot.sauce.firstOrNull()?.series ?: "без названия"}\""
            reasons += "Лучший кандидат: $named с оценкой ${winner.score} по источникам ${winner.sources.joinToString(" + ") { sourceName(it) }}; " +
                if (runner != null) "запас над вторым кандидатом ${round3(winner.score - runner.score)}."
                else "второго кандидата в списке нет."
            if (winner.sources.size < 2) {
                reasons += "Источник один — независимого подтверждения нет, даже при высоком сходстве это не «identified»."
            }
        } else {
            val best = pool.values.map { scoreSlot(it) }
                .sortedWith(compareByDescending<Scored> { it.score }.thenByDescending { it.sources.size }.thenBy { it.slot.key })
                .firstOrNull()
            val anySource = hasTrace || indexHit != null || kept.isNotEmpty()
            reasons += if (anySource) {
                "Ни один кандидат не набрал порог ${round3(T_FLOOR)}: лучшая оценка ${best?.score ?: 0f}. Сказать «не уверен» честнее, чем назвать не то."
            } else {
                "Совпадений не было вовсе: trace.moe и индекс кадров молчат, SauceNAO ничего не дал."
            }
            if (labelsPresent && !anySource) {
                reasons += "Одни метки содержимого (небо, здание и т.п.) тайтл не называют — источник совпадения всё равно нужен."
            }
            if (labelsPresent) {
                reasons += "Метки содержимого не могут назвать тайтл сами по себе, в ответ они не идут."
            }
        }

        return FrameVerdict(
            decision = decision,
            confidence = round3(clamp01(confidence)),
            reasons = reasons,
            warnings = warnings,
            candidates = candidateRows,
            agreedSources = winner?.sources.orEmpty(),
        )
    }

    // --- вспомогательное ----------------------------------------------------

    /** SauceNAO часто отдаёт название серии вместо AniList id. */
    private fun nameMatchesTitle(hit: SauceHit, titles: List<String>): Boolean {
        val target = normalizeTitle(hit.series ?: hit.copyright ?: hit.title)
        if (target.isEmpty() || target.split(' ').size < 3) return false
        return titles.any { candidate ->
            val alias = normalizeTitle(candidate)
            if (alias.isEmpty()) return@any false
            alias == target ||
                (alias.length >= 8 && alias.contains(target)) ||
                (target.length >= 8 && target.contains(alias))
        }
    }

    private fun bestTitle(slot: Slot): String? =
        slot.trace?.titles?.firstOrNull()
            ?: slot.sauce.maxByOrNull { normalizeTitle(it.series ?: it.title).length }?.series
            ?: slot.sauce.firstOrNull()?.title

    private fun closeness(distance: Int): Float =
        if (distance <= 0) 1f else clamp01(1f - distance / T_INDEX_WINDOW)

    private fun scoreSlot(slot: Slot): Scored {
        val parts = mutableListOf<String>()
        val sources = mutableListOf<String>()
        val contribution = mutableMapOf<String, Float>()
        var score = 0f

        slot.trace?.let { trace ->
            val add = W_TRACE * clamp01(trace.similarity)
            score += add
            sources += "trace"
            contribution["trace"] = add
            val when_ = trace.episode?.let { ", серия $it" } ?: ""
            parts += "trace.moe: сходство ${percent(trace.similarity)}$when_ → вклад ${round3(add)}"
        }

        if (slot.sauce.isNotEmpty()) {
            val best = slot.sauce.maxByOrNull { it.similarity }!!
            val add = W_SAUCE * best.similarity * best.similarity
            score += add
            sources += "sauce"
            contribution["sauce"] = add
            parts += "SauceNAO: сходство ${percent(best.similarity)} → вклад ${round3(add)} (затухание: сходство возведено в квадрат)"
            if (best.characters.isNotEmpty()) {
                parts += "SauceNAO опознал персонажей: ${best.characters.take(4).joinToString(", ")}"
            }
            val weaker = slot.sauce.size - 1
            if (weaker > 0) {
                parts += "ещё $weaker совпадени(й) SauceNAO по тому же тайтлу учтено как одно подтверждение"
            }
        }

        slot.index?.let { index ->
            val add = W_INDEX * closeness(index.distance)
            score += add
            sources += "index"
            contribution["index"] = add
            parts += "индекс кадров: смещение ${index.distance} → вклад ${round3(add)}"
        }

        if (sources.size >= 2) {
            val bonus = minOf(W_AGREEMENT_MAX, W_AGREEMENT * (sources.size - 1))
            score += bonus
            parts += "бонус за независимое подтверждение: ${sources.joinToString(" + ") { sourceName(it) }} → +${round3(bonus)}"
        }

        val lead = sources.maxByOrNull { contribution[it] ?: -1f }
        return Scored(slot, round3(score), sources, parts, lead)
    }

    /**
     * Правило 2: совпадение SauceNAO слабее 60 % не имеет права обойти
     * находку trace.moe или индекса кадров — как бы ни была высока её оценка.
     */
    private fun applyOutrankGuard(ranked: MutableList<Scored>, reasons: MutableList<String>, warnings: MutableList<String>) {
        if (ranked.size < 2) return
        val top = ranked[0]
        if (top.sources.contains("trace") || top.sources.contains("index")) return
        val rivalIndex = ranked.indexOfFirst { it.sources.contains("trace") || it.sources.contains("index") }
        if (rivalIndex <= 0) return
        val best = top.slot.sauce.maxByOrNull { it.similarity } ?: return
        if (best.similarity >= T_SAUCE_OUTRANK) return
        val rival = ranked[rivalIndex]
        ranked.removeAt(0)
        ranked.add(1, top)
        val rivalSource = rival.sources.firstOrNull { it != "sauce" } ?: "trace"
        reasons += "SauceNAO (${percent(best.similarity)}) не может обойти ${sourceName(rivalSource)}: сходство ниже ${percent(T_SAUCE_OUTRANK)} — оставляем его ниже."
        warnings += "SauceNAO дал ${percent(best.similarity)} и не смог опередить ${sourceName(rivalSource)}."
    }
}
