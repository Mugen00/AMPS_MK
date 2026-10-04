package dev.amps.app.data.ranking

import dev.amps.app.data.model.CharacterTagName
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
 * **1.0.6: вторым источником стал аниме-тегер, и проверка сменила смысл на
 * противоположный.** До этой версии здесь жил сигнал от ML Kit — общие
 * словари, которые умели сказать только «на картинке есть человек». Штраф за
 * отсутствие человека был ×0,70 ([T_LABEL_FACTOR]): логика была «ML Kit ищет
 * человека вообще, и если он не нашёл — совпадение по персонажу сомнительно».
 *
 * С тегером это рассуждение больше не работает. `wd-swinv2-tagger-v3` обучен
 * на аниме-иллюстрациях и знает аниме-персонажей; его пустой ответ на
 * не-anime картинке — это не «не разобрался», а честное «здесь иллюстрации
 * нет». Измеренный разрыв это подтверждает: на мусоре оценки держатся у
 * 0,50–0,53, уверенная находка даёт 0,73, порог стоит на 0,60.
 *
 * Поэтому:
 *  - **тегер нашёл персонажа** — сильный сигнал согласия, а не отсутствие
 *    проблемы: это независимое подтверждение, что перед нами иллюстрация,
 *    и оно **повышает** счёт ([W_TAGGER_CHARACTER]);
 *  - **тегер не нашёл персонажа** — это предупреждение в текст, а **не**
 *    штраф. Наказывать балл за «не знаю» — значит наказывать за честность
 *    модели; при этом блокировать «определено» такой пустой ответ больше не
 *    может.
 *
 * **`score` — это сумма согласия, а не вероятность.** В пределах 0..1 зажимается
 * только `confidence`.
 */
object RankingEngine {

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

    /**
     * **1.0.6, новый положительный сигнал.** Модель на телефоне нашла на
     * картинке персонажа. Это независимое подтверждение того, что перед нами
     * аниме-иллюстрация: IQDB проверяет, *похоже ли* на то, что лежит в базе,
     * а тегер отвечает на другой вопрос — *есть ли тут вообще персонаж*.
     */
    private const val W_TAGGER_CHARACTER = 0.08f

    /**
     * А имя из бо́ру-тега IQDB совпало с тем, что тегер распознал на картинке.
     * Это уже согласие двух независимых источников, поэтому оно дороже
     * одного лишь факта «персонаж есть».
     */
    private const val W_TAGGER_AGREEMENT = 0.07f

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

    /**
     * Потолок для серии, названной **без** совпадения картинок — только по
     * бо́ру-тегу, который распознала модель на телефоне.
     *
     * Это принципиально другой по природе вывод, чем совпадение по картинке,
     * и поднимать его до уровня настоящих находок незачем: картинки никто не
     * искал в базе, а имя пришло от модели, у которой правильное имя стоит
     * первым в 55,6 случаях из 100. Потолок держит такой ответ честно спорным.
     */
    private const val T_TAGGER_ONLY_CAP = 0.30f

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

    /** Один бо́ру-тег, который распознала модель на телефоне. */
    data class TaggerTag(val tag: String, val probability: Float)

    /**
     * Всё, что сказал аниме-тегер, вместе с тем, что AniList смог из этого
     * получить.
     *
     * [available] отделяет «модель не смогла запуститься» от «модель отработала
     * и ничего не нашла». Разница принципиальная: первое — сбой, второе —
     * честный отрицательный ответ, и оно попадает в предупреждения, а не в
     * штраф.
     */
    data class TaggerResult(
        val available: Boolean = false,
        val hasPerson: Boolean = false,
        /** До пяти персонажей по убыванию вероятности. */
        val characters: List<TaggerTag> = emptyList(),
        val resolvedMediaId: Int? = null,
        val resolvedMediaTitle: String? = null,
        val resolvedViaTag: String? = null,
        val confirmedCharacter: String? = null,
    )

    data class Signals(
        /** null — поиск не дал результата вовсе (ошибка или пустой ответ). */
        val iqdb: IqdbResult? = null,
        /** Что IQDB ответил вместо результата. */
        val iqdbError: String? = null,
        val tagger: TaggerResult = TaggerResult(),
    )

    // --- вердикт ------------------------------------------------------------

    fun rank(signals: Signals): FrameVerdict {
        val reasons = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val tagger = signals.tagger
        val result = signals.iqdb
        if (result == null) return taggerOnly(signals, reasons, warnings)

        val best = result.hits.maxByOrNull { it.similarity }
        // Совпадений по картинке нет. Это ещё не значит, что определить
        // нечего: серию могла назвать модель на телефоне, по бо́ру-тегу
        // персонажа. Такой ответ разбирается отдельно, потому что держится
        // на другом источнике и другой уверенности.
        if (best == null) return taggerOnly(signals, reasons, warnings)

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

        // --- сигнал тегера ----------------------------------------------------
        //
        // Здесь смысл обратный прежнему. Старый код штрафовал за отсутствие
        // человека — потому что ML Kit искал «человек вообще» и его молчание
        // было тревожным знаком. Тегер обучен на иллюстрациях, поэтому его
        // находка персонажа — это подтверждение, а молчание — честный ответ
        // «здесь иллюстрации нет», который в штраф не превращается.
        val taggerNames = tagger.characters.mapNotNull { CharacterTagName.normalize(it.tag) }
        if (taggerNames.isNotEmpty()) {
            score += W_TAGGER_CHARACTER
            parts += "тегер нашёл персонажа прямо на картинке (${taggerNames.take(3).joinToString(", ")}) " +
                "→ +${round3(W_TAGGER_CHARACTER)}"
        }
        // Имя из бо́ру-тега IQDB и имя, распознанное моделью, могут совпасть —
        // и тогда это согласие двух независимых источников, а не одно и то же
        // число, посчитанное дважды.
        val taggerAgrees = result.confirmedCharacter != null && taggerNames.any {
            CharacterTagName.sameName(result.confirmedCharacter, it)
        }
        if (taggerAgrees) {
            score += W_TAGGER_AGREEMENT
            parts += "имя «${result.confirmedCharacter}» совпало с тем, что тегер распознал на картинке " +
                "→ +${round3(W_TAGGER_AGREEMENT)}"
        } else if (taggerNames.isNotEmpty() && result.confirmedCharacter != null) {
            // Расхождение здесь ожидаемо, а не подозрительно: по замерам
            // правильное имя стоит первым в 55,6 случаях из 100, то есть почти
            // в половине снимков первым будет кто-то другой. Штраф за это
            // означал бы наказание за то, что список устроен правильно.
            warnings += "Тегер на картинке распознал других персонажей (${taggerNames.take(3).joinToString(", ")}), " +
                "а совпадение IQDB назвало «${result.confirmedCharacter}». Это не ошибка: правильное имя стоит " +
                "первым лишь в 55,6 случаях из 100, поэтому список показан целиком, а уверенность не снижается."
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

            // Пустой ответ тегера уверенность **не режет**. Раньше здесь стоял
            // штраф ×0,70 за отсутствие человека, но он был про общий словарь
            // ML Kit, который искал «человек вообще». Тегер знает аниме-тегов и
            // молчит там, где иллюстрации нет, — а наказывать баллом за
            // «не знаю» значит наказывать за честность.
            if (tagger.available && !tagger.hasPerson) {
                warnings += "Тегер на картинке ни одного персонажа не нашёл. Это честный ответ, а не сбой: " +
                    "на не-иллюстрациях его оценки держатся у 0,50–0,53, а настоящая находка даёт 0,73, " +
                    "и порог в 0,60 отсекает шум. На уверенность это не влияет."
            }

            // «Определено» требует всего сразу: высокий счёт, согласие минимум
            // двух независимых баз и подтверждение точным совпадением. Любое
            // неснятое сомнение оставляет ответ спорным, но не отрицает его.
            // Ответ тегера в это условие не входит: он и подтверждает, и не
            // опровергает, и запрещать «определено» из-за него нельзя.
            decision = if (
                score >= T_IDENTIFIED &&
                agreeing.size >= 2 &&
                result.exactMatchFound &&
                !conflict
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
            taggerNames.isNotEmpty() ->
                reasons += "Тегер нашёл на картинке персонажа (${taggerNames.take(3).joinToString(", ")}) — " +
                    "найденное по тегам совпадение картинкой не опровергнуто."
            tagger.available ->
                reasons += "Тегер отработал, но персонажа не нашёл; на уверенность идентификации это не влияет."
            else ->
                reasons += "Тегер не прислал результат — проверка содержимого не выполнялась."
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
     * Совпадений картинок нет — а серия, возможно, всё равно названа.
     *
     * Это единственный случай, где ответ может получиться **без** IQDB: модель
     * на телефоне узнала персонажа, AniList нашёл его по имени и отдал серию.
     * Такой вывод честно другой, поэтому и оценивается иначе: потолок
     * [T_TAGGER_ONLY_CAP] не даёт ему выглядеть как совпадение по картинке,
     * которого не было.
     *
     * Если и модер для назвать нечего — это честный отказ: ни одного
     * подтверждения получить не удалось, и подставлять что-то вместо него
     * нельзя.
     */
    private fun taggerOnly(
        signals: Signals,
        reasons: MutableList<String>,
        warnings: MutableList<String>,
    ): FrameVerdict {
        val tagger = signals.tagger
        val result = signals.iqdb

        when {
            result == null -> reasons += "IQDB не ответил: ${signals.iqdbError ?: "причина неизвестна"}. " +
                "Определять нечего, а догадываться без ответа — значит выдумывать."
            else -> reasons += "IQDB просмотрел ${result.scannedImages ?: "—"} изображений и не нашёл ни одного " +
                "совпадения не ниже ${percent(T_MIN_SIMILARITY)} — именно с этого порога сервис сам считает " +
                "находку настоящей."
        }

        if (result == null) {
            warnings += "Источник недоступен, поэтому вердикт держится только на том, что распознала модель " +
                "на телефоне. Повторите поиск при связи с интернетом."
        } else {
            warnings += "Совпадений не найдено. IQDB ищет по иллюстрациям и скриншотам, которые уже лежат в " +
                "бо́ру-базах; обычное фото или скриншот из видеоигры там не лежат."
        }

        val taggerNames = tagger.characters.mapNotNull { CharacterTagName.normalize(it.tag) }
        val mediaId = tagger.resolvedMediaId

        if (mediaId == null) {
            if (taggerNames.isEmpty()) {
                warnings += "Модель на телефоне тоже не нашла персонажа: на не-иллюстрациях её оценки держатся " +
                    "у 0,50–0,53, настоящая находка даёт 0,73. Скорее всего, перед нами не иллюстрация — " +
                    "определять здесь нечего."
            } else {
                reasons += "Модель нашла персонажа (${taggerNames.take(3).joinToString(", ")}), но AniList " +
                    "не смог назвать по его имени ни персонажа, ни серию."
                warnings += "Серия не названа: имя из бо́ру-тега в AniList не нашлось. Показать нечего, " +
                    "и подставлять вместо серии догадку не будем."
            }
            return FrameVerdict(
                decision = "rejected",
                confidence = 0f,
                reasons = reasons,
                warnings = warnings,
            )
        }

        val top = tagger.characters.maxByOrNull { it.probability }?.probability ?: 0f
        val score = minOf(T_TAGGER_ONLY_CAP, clamp01(top) * T_TAGGER_ONLY_CAP)

        reasons += "Серия «${tagger.resolvedMediaTitle ?: "AniList $mediaId"}» названа по бо́ру-тегу, который " +
            "распознала модель на телефоне (${tagger.resolvedViaTag ?: taggerNames.firstOrNull() ?: "—"}), " +
            "и оценкой ${round3(score)} — картинку в базах никто не искал."
        reasons += "Это предположение, а не совпадение: по замерам правильное имя стоит первым лишь в " +
            "55,6 случаях из 100, поэтому потолок для такого ответа ${round3(T_TAGGER_ONLY_CAP)}."
        warnings += "Подтверждения картинкой нет — совпадение по IQDB не найдено, серия названа только по тегу. " +
            "Серия показана как догадка, а не как определённый ответ."

        return FrameVerdict(
            decision = "uncertain",
            confidence = round3(clamp01(score)),
            reasons = reasons.distinct(),
            warnings = warnings.distinct(),
        )
    }

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