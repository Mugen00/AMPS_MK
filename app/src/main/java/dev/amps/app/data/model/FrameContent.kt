package dev.amps.app.data.model

import kotlinx.serialization.Serializable

/**
 * Один тег аниме-тегера: бо́ру-строка и вероятность, которую выдала модель.
 *
 * [tag] — это то, что стоит в `anime_tags.csv`, дословно: `aqua_hair`,
 * `school_uniform`. Показывать пользователю такую строку можно только вместе
 * с [phrase]; ради числовых полей и честной подписи «по тегам источника».
 */
@Serializable
data class ContentTag(
    val tag: String,
    /** 0..1. Порог отсечения задаёт сам тегер, здесь вероятность уже выше него. */
    val probability: Float,
    /** Русская фраза из [AppearanceLexicon]. Только у внешности. */
    val phrase: String? = null,
) {
    val percent: Int get() = (probability * 100f + 0.5f).toInt()
}

/**
 * Одна гипотеза о персонаже — «на картинке может быть вот этот».
 *
 * **Их всегда несколько, и это не недоделка.** Модель `wd-swinv2-tagger-v3` на
 * наборе из 36 картинок с известным ответом поставила правильного персонажа на
 * первое место в 55,6 % случаев (fp32) и 52,8 % (int8), зато в первую пятёрку —
 * в 100 %. Поэтому наружу уходит вся пятёрка: выбор одного означал бы регулярно
 * показывать не того. Подпись под этим — «предположения по тегам», а не
 * «персонаж определён».
 *
 * [anilistId] заполняется не сразу: [AnimeTagger] отдаёт только бо́ру-строку,
 * а каноническое имя, портрет и серия приходят из AniList уже в репозитории.
 * Пока [anilistId] равен `null`, имя показано нормализованным тегом
 * ([CharacterTagName]) и подписано как неуточнённое.
 */
@Serializable
data class CharacterHypothesis(
    /** Бо́ру-тег как его отдала модель: `rem_(re:zero)`. */
    val tag: String,
    /** Что показываем: имя AniList, а если его нет — нормализованный тег. */
    val name: String,
    val probability: Float,
    val anilistId: Int? = null,
    val image: String? = null,
    /** Самая популярная серия, в которой персонаж появляется. */
    val mediaTitle: String? = null,
) {
    val percent: Int get() = (probability * 100f + 0.5f).toInt()

    /** Имя подтверждено AniList, а не просто приведено в порядок из тега. */
    val resolved: Boolean get() = anilistId != null
}

/**
 * 1.0.6: что на самом деле есть на картинке.
 *
 * **Что изменилось по сравнению с 1.0.5.** Раньше здесь лежали метки ML Kit —
 * общие английские словари «person», «sky», «building», о которых нельзя было
 * сказать ничего, кроме «на картинке кто-то есть». Эти словари занимали 20 МБ
 * APK и не знали имён персонажей вовсе. Теперь здесь то, что выдал аниме-тегер:
 *
 *  - [characters] — до пяти гипотез о персонаже с их вероятностями;
 *  - [appearance] — признаки внешности, уже переведённые [AppearanceLexicon];
 *  - [description] — описание, собранное из этих признаков по шаблону;
 *  - [hasPerson] — есть ли на картинке кто-то вообще.
 *
 * **Два разных пустых состояния, и их нельзя смешивать.**
 * [analyzed] = `false` с [unavailableReason] означает «модель не смогла
 * отработать» — определять нечего. [analyzed] = `true` при пустых
 * [characters] и [appearance] означает другое: модель отработала и честно не
 * нашла ничего. На не-anime картинках её оценки держатся у 0,50–0,53, у
 * настоящей находки — 0,73, и пустой список здесь не поломка.
 */
@Serializable
data class FrameContent(
    val characters: List<CharacterHypothesis> = emptyList(),
    val appearance: List<ContentTag> = emptyList(),
    /** Описание внешности по шаблону; `null`, если словарь не дал ни одной фразы. */
    val description: String? = null,
    val hasPerson: Boolean = false,
    val analyzed: Boolean = false,
    val unavailableReason: String? = null,
    /** Время анализа целиком, вместе с первым копированием модели в файлы. */
    val elapsedMs: Long = 0L,
) {
    /** Модель отработала, но не нашла на картинке ничего. */
    val emptyButAnalyzed: Boolean
        get() = analyzed && characters.isEmpty() && appearance.isEmpty()
}
