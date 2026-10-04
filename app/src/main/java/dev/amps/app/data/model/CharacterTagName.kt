package dev.amps.app.data.model

import java.util.Locale

/**
 * Приводит бо́ру-тег персонажа к тому виду, который понимает поиск AniList.
 *
 * **Тег — это не имя.** Danbooru записывает персонажа служебной строкой, в
 * которой к имени прицеплены уточнения в скобках, а пробел заменён
 * подчёркиванием. AniList такие строки не понимает: поиск по
 * `rem_(re:zero)` не находит ничего, а по `rem` находит нужного.
 *
 * Правило ровно одно, и оно разбирает весь вид бо́ру-имён:
 *
 *  1. **Всё начиная с первой открывающей скобки отбрасывается целиком.**
 *     Уточнения в Danbooru всегда идут в конце тега, по порядку «от более
 *     общего к более частому»: персонаж, вариант одежды, произведение. Ни одна
 *     их часть в имя не входит.
 *  2. **`_` — это пробел.** В Danbooru-разметке им заменяют все пробелы.
 *  3. **Остаток проверяется на пригодность.** Пустая строка или обрезок
 *     короче [MIN_LENGTH] символов в запрос не идёт: AniList на такой строке
 *     вернёт первую страницу вообще чего угодно, и это будет выглядеть как
 *     находка, которой нет.
 *
 * Примеры (проверяются глазами, функция чистая и детерминированная):
 *
 * | бо́ру-тег                            | запрос в AniList       |
 * |--------------------------------------|------------------------|
 * | `rem_(re:zero)`                      | `rem`                  |
 * | `hatsune_miku`                       | `hatsune miku`         |
 * | `artoria_pendragon_(alter_swimsuit_rider)_(fate)` | `artoria pendragon` |
 * | `madoka_kaname_(puella_magi_madoka_magica)` | `madoka kaname`   |
 * | `sailor_uniform_(serafuku)`          | `sailor uniform`       |
 * | `himiko_toga_(my_hero_academia)`      | `himiko toga`          |
 * | `zero_two_(darling_in_the_franxx)`   | `zero two`             |
 * | `esdeath_(akame_no_gaen)`            | `esdeath`              |
 * | `hatsune_miku_(append)`              | `hatsune miku`         |
 * | `kanna_kamui_(shinryaku_no_i_am_a_hero)` | `kanna kamui`      |
 * | `(disambiguation)`                   | `null` — запрос не делаем |
 * | `ab`                                 | `null` — короче [MIN_LENGTH] |
 * | `` (пустая строка)                   | `null`                 |
 *
 * Обратное преобразование не нужно: нормализованное имя AniList мы никуда не
 * записываем и обратно в тег не превращаем.
 */
object CharacterTagName {

    /** Короче этого запрос в AniList не делаем: результат будет случайным. */
    const val MIN_LENGTH = 3

    /**
     * Возвращает строку для поиска либо `null`, если из тега имени не выходит.
     */
    fun normalize(raw: String): String? {
        val name = raw.substringBefore('(')
            .replace('_', ' ')
            .replace(REPEATED_SPACES, " ")
            .trim()
        if (name.length < MIN_LENGTH) return null
        // Имя без единой буквы — это «123», «2020» или служебная метка.
        // AniList такие строки трактует как даты и возвращает ерунду.
        if (name.none(Char::isLetter)) return null
        return name
    }

    /**
     * Одно ли это и то же имя: имя из AniList и нормализованный бо́ру-тег.
     *
     * Нужно для честного сравнения в [dev.amps.app.data.ranking.RankingEngine]:
     * модель пишет `rem`, AniList отдаёт `Rem`, и без сверки это две разные
     * строки, хотя означают одно.
     */
    fun sameName(anilistName: String, tag: String): Boolean {
        val left = (normalize(anilistName) ?: anilistName).trim().lowercase(Locale.ROOT)
        val right = (normalize(tag) ?: tag).trim().lowercase(Locale.ROOT)
        return left.isNotEmpty() && left == right
    }

    private val REPEATED_SPACES = Regex("\\s+")
}
