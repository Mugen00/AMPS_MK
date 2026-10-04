package dev.amps.app.data.model

import java.util.Locale

/**
 * Словарь тегов внешности на русский и шаблон, собирающий из них описание.
 *
 * **Зачем он отдельно от имён персонажей.** Персонажей приложение называет
 * через AniList: там есть каноническое имя, портрет и серия. А вот волосы,
 * одежду, позу и фон AniList не знает — их знает только модель, и отдаёт она
 * бо́ру-строки. Словарь превращает эти строки в русские фразы, и больше
 * ничего: ни одна подсказка, додуманная «по смыслу», в описание не попадает.
 *
 * **Как отбирались теги.** В `assets/models/anime_tags.csv` у каждого тега есть
 * колонка `count` — сколько раз он встретился в обучающей выборке модели.
 * Отсюда взят первый критерий: тег внешности должен быть **частым**, иначе
 * словарь разрастётся восемью тысячами строк ради тегов, которые модель
 * практически не выдаёт. Порог — `count ≥ 98 809`, а это ровно первые **340**
 * самых частых тегов категории general из 8 106.
 *
 * Второй критерий жёстче первого: **тег должен читаться по-человечески и
 * переводиться однозначно.** Поэтому в словаре нет ни `hetero`/`yuri` (это не
 * внешность), ни `mosaic_censoring` (это не про картинку), ни `artist_name`
 * (это подпись художника), ни `2girls` в отдельном смысле — вместо них есть
 * `multiple_girls`. И тем более нет того, что к внешности не относится вовсе:
 * тела, груди и постельные сцены в описание не идут.
 *
 * **Что с тегом, которого в словаре нет.** Он молча пропускается. Заглушек
 * вида «признак №7» или дословного тега в скобках здесь нет намеренно: выдумка
 * о внешности хуже, чем её отсутствие, а отсутствие пользователь видит сразу.
 *
 * Итог отбора — 220 тегов. Персонажи сюда не входят: их имена приложение
 * получает из AniList, и подменять каноническое имя переводом было бы шагом
 * назад.
 */
object AppearanceLexicon {

    /**
     * Куда попадает фраза в предложении. Порядок перечисления — это и есть
     * порядок групп в описании: сначала кто, потом волосы, глаза, одежда,
     * украшения, поза, фон.
     */
    enum class Group(val title: String) {
        /** Кто изображён. Заголовка нет: это начало предложения. */
        SUBJECT(""),

        HAIR("Волосы"),
        EYES("Глаза"),
        CLOTHES("Одежда"),
        ACCESSORY("Аксессуары"),
        EXPRESSION("Поза и настроение"),
        BACKGROUND("Фон"),
    }

    /** Одна собранная группа: заголовок и её фразы, в порядке вероятности. */
    data class Clause(val group: Group, val items: List<String>)

    private data class Entry(val phrase: String, val group: Group)

    /**
     * Тег → русская фраза. Ключи в нижнем регистре, значения уже в той форме,
     * в которой они читаются в предложении.
     */
    private val PHRASES: Map<String, Entry> = mapOf(
        // --- кто ---------------------------------------------------------------
        "solo" to Entry("один персонаж", Group.SUBJECT),
        "1girl" to Entry("девушка", Group.SUBJECT),
        "1boy" to Entry("парень", Group.SUBJECT),
        "2girls" to Entry("две девушки", Group.SUBJECT),
        "2boys" to Entry("два парня", Group.SUBJECT),
        "3girls" to Entry("три девушки", Group.SUBJECT),
        "multiple_girls" to Entry("несколько девушек", Group.SUBJECT),
        "multiple_boys" to Entry("несколько парней", Group.SUBJECT),
        "solo_focus" to Entry("в одиночку", Group.SUBJECT),
        "no_humans" to Entry("людей нет", Group.SUBJECT),

        // --- волосы -----------------------------------------------------------
        "long_hair" to Entry("длинные", Group.HAIR),
        "very_long_hair" to Entry("очень длинные", Group.HAIR),
        "short_hair" to Entry("короткие", Group.HAIR),
        "medium_hair" to Entry("средней длины", Group.HAIR),
        "blonde_hair" to Entry("блонд", Group.HAIR),
        "brown_hair" to Entry("каштановые", Group.HAIR),
        "black_hair" to Entry("чёрные", Group.HAIR),
        "blue_hair" to Entry("синие", Group.HAIR),
        "white_hair" to Entry("белые", Group.HAIR),
        "pink_hair" to Entry("розовые", Group.HAIR),
        "grey_hair" to Entry("серые", Group.HAIR),
        "purple_hair" to Entry("фиолетовые", Group.HAIR),
        "red_hair" to Entry("красные", Group.HAIR),
        "green_hair" to Entry("зелёные", Group.HAIR),
        "orange_hair" to Entry("оранжевые", Group.HAIR),
        "aqua_hair" to Entry("бирюзовые", Group.HAIR),
        "multicolored_hair" to Entry("разноцветные", Group.HAIR),
        "two-tone_hair" to Entry("двухцветные", Group.HAIR),
        "streaked_hair" to Entry("цветные пряди", Group.HAIR),
        "gradient_hair" to Entry("с переливом цвета", Group.HAIR),
        "hair_bun" to Entry("пучок на голове", Group.HAIR),
        "double_bun" to Entry("два пучка", Group.HAIR),
        "twintails" to Entry("две косы", Group.HAIR),
        "ponytail" to Entry("хвост", Group.HAIR),
        "side_ponytail" to Entry("боковой хвост", Group.HAIR),
        "braid" to Entry("коса", Group.HAIR),
        "single_braid" to Entry("одна коса", Group.HAIR),
        "twin_braids" to Entry("две косички спереди", Group.HAIR),
        "ahoge" to Entry("чуб", Group.HAIR),
        "sidelocks" to Entry("пряди у лица", Group.HAIR),
        "hair_between_eyes" to Entry("чёлка между глазами", Group.HAIR),
        "hair_over_one_eye" to Entry("волосы закрывают один глаз", Group.HAIR),
        "blunt_bangs" to Entry("прямая чёлка", Group.HAIR),
        "parted_bangs" to Entry("пробор в чёлке", Group.HAIR),
        "two_side_up" to Entry("две пряди вверх", Group.HAIR),
        "floating_hair" to Entry("волосы развеваются", Group.HAIR),

        // --- глаза ------------------------------------------------------------
        "blue_eyes" to Entry("синие", Group.EYES),
        "brown_eyes" to Entry("карие", Group.EYES),
        "red_eyes" to Entry("красные", Group.EYES),
        "green_eyes" to Entry("зелёные", Group.EYES),
        "purple_eyes" to Entry("фиолетовые", Group.EYES),
        "yellow_eyes" to Entry("жёлтые", Group.EYES),
        "pink_eyes" to Entry("розовые", Group.EYES),
        "black_eyes" to Entry("чёрные", Group.EYES),
        "aqua_eyes" to Entry("бирюзовые", Group.EYES),
        "grey_eyes" to Entry("серые", Group.EYES),
        "orange_eyes" to Entry("оранжевые", Group.EYES),
        "symbol-shaped_pupils" to Entry("необычный зрачок", Group.EYES),

        // --- одежда -----------------------------------------------------------
        "shirt" to Entry("рубашка", Group.CLOTHES),
        "white_shirt" to Entry("белая рубашка", Group.CLOTHES),
        "black_shirt" to Entry("чёрная рубашка", Group.CLOTHES),
        "collared_shirt" to Entry("рубашка с воротником", Group.CLOTHES),
        "open_shirt" to Entry("расстёгнутая рубашка", Group.CLOTHES),
        "skirt" to Entry("юбка", Group.CLOTHES),
        "black_skirt" to Entry("чёрная юбка", Group.CLOTHES),
        "blue_skirt" to Entry("синяя юбка", Group.CLOTHES),
        "pleated_skirt" to Entry("плиссированная юбка", Group.CLOTHES),
        "miniskirt" to Entry("мини-юбка", Group.CLOTHES),
        "dress" to Entry("платье", Group.CLOTHES),
        "white_dress" to Entry("белое платье", Group.CLOTHES),
        "black_dress" to Entry("чёрное платье", Group.CLOTHES),
        "long_sleeves" to Entry("длинные рукава", Group.CLOTHES),
        "short_sleeves" to Entry("короткие рукава", Group.CLOTHES),
        "sleeveless" to Entry("без рукавов", Group.CLOTHES),
        "puffy_sleeves" to Entry("пышные рукава", Group.CLOTHES),
        "puffy_short_sleeves" to Entry("пышные короткие рукава", Group.CLOTHES),
        "wide_sleeves" to Entry("широкие рукава", Group.CLOTHES),
        "collar" to Entry("воротник", Group.CLOTHES),
        "jacket" to Entry("куртка", Group.CLOTHES),
        "black_jacket" to Entry("чёрная куртка", Group.CLOTHES),
        "coat" to Entry("пальто", Group.CLOTHES),
        "cape" to Entry("плащ", Group.CLOTHES),
        "sweater" to Entry("свитер", Group.CLOTHES),
        "hoodie" to Entry("худи", Group.CLOTHES),
        "vest" to Entry("жилет", Group.CLOTHES),
        "crop_top" to Entry("короткий топ", Group.CLOTHES),
        "sash" to Entry("пояс", Group.CLOTHES),
        "gloves" to Entry("перчатки", Group.CLOTHES),
        "white_gloves" to Entry("белые перчатки", Group.CLOTHES),
        "black_gloves" to Entry("чёрные перчатки", Group.CLOTHES),
        "elbow_gloves" to Entry("длинные перчатки", Group.CLOTHES),
        "fingerless_gloves" to Entry("перчатки без пальцев", Group.CLOTHES),
        "hat" to Entry("шляпа", Group.CLOTHES),
        "boots" to Entry("ботинки", Group.CLOTHES),
        "shoes" to Entry("туфли", Group.CLOTHES),
        "high_heels" to Entry("каблуки", Group.CLOTHES),
        "socks" to Entry("носки", Group.CLOTHES),
        "kneehighs" to Entry("гольфы", Group.CLOTHES),
        "thighhighs" to Entry("чулки", Group.CLOTHES),
        "white_thighhighs" to Entry("белые чулки", Group.CLOTHES),
        "black_thighhighs" to Entry("чёрные чулки", Group.CLOTHES),
        "pantyhose" to Entry("колготки", Group.CLOTHES),
        "black_pantyhose" to Entry("чёрные колготки", Group.CLOTHES),
        "swimsuit" to Entry("купальник", Group.CLOTHES),
        "one-piece_swimsuit" to Entry("купальник целиком", Group.CLOTHES),
        "bikini" to Entry("бикини", Group.CLOTHES),
        "shorts" to Entry("шорты", Group.CLOTHES),
        "school_uniform" to Entry("школьная форма", Group.CLOTHES),
        "serafuku" to Entry("морская форма", Group.CLOTHES),
        "uniform" to Entry("форма", Group.CLOTHES),
        "kimono" to Entry("кимоно", Group.CLOTHES),
        "japanese_clothes" to Entry("японская одежда", Group.CLOTHES),
        "maid" to Entry("платье горничной", Group.CLOTHES),
        "frills" to Entry("оборки", Group.CLOTHES),
        "apron" to Entry("фартук", Group.CLOTHES),
        "armor" to Entry("броня", Group.CLOTHES),
        "striped_clothes" to Entry("полосатая одежда", Group.CLOTHES),
        "plaid" to Entry("клетчатая одежда", Group.CLOTHES),
        "bare_shoulders" to Entry("открытые плечи", Group.CLOTHES),
        "off_shoulder" to Entry("с обнажённых плеч", Group.CLOTHES),
        "strapless" to Entry("без лямок", Group.CLOTHES),
        "bare_arms" to Entry("открытые руки", Group.CLOTHES),
        "bare_legs" to Entry("открытые ноги", Group.CLOTHES),
        "detached_sleeves" to Entry("рукава отдельно от корпуса", Group.CLOTHES),

        // --- аксессуары и детали ---------------------------------------------
        "hair_ornament" to Entry("украшение в волосах", Group.ACCESSORY),
        "hair_ribbon" to Entry("лента в волосах", Group.ACCESSORY),
        "hair_bow" to Entry("бант в волосах", Group.ACCESSORY),
        "hair_flower" to Entry("цветок в волосах", Group.ACCESSORY),
        "hairclip" to Entry("заколка", Group.ACCESSORY),
        "hairband" to Entry("повязка", Group.ACCESSORY),
        "ribbon" to Entry("лента", Group.ACCESSORY),
        "bow" to Entry("бант", Group.ACCESSORY),
        "bowtie" to Entry("бабочка", Group.ACCESSORY),
        "necktie" to Entry("галстук", Group.ACCESSORY),
        "neckerchief" to Entry("платок на шее", Group.ACCESSORY),
        "neck_ribbon" to Entry("лента на шее", Group.ACCESSORY),
        "sailor_collar" to Entry("морской воротник", Group.ACCESSORY),
        "choker" to Entry("чокер", Group.ACCESSORY),
        "scarf" to Entry("шарф", Group.ACCESSORY),
        "necklace" to Entry("ожерелье", Group.ACCESSORY),
        "bracelet" to Entry("браслет", Group.ACCESSORY),
        "earrings" to Entry("серьги", Group.ACCESSORY),
        "jewelry" to Entry("украшения", Group.ACCESSORY),
        "wrist_cuffs" to Entry("манжеты на запястьях", Group.ACCESSORY),
        "thigh_strap" to Entry("ремешок на бедре", Group.ACCESSORY),
        "glasses" to Entry("очки", Group.ACCESSORY),
        "hood" to Entry("капюшон", Group.ACCESSORY),
        "animal_ears" to Entry("звериные уши", Group.ACCESSORY),
        "cat_ears" to Entry("кошачьи уши", Group.ACCESSORY),
        "fox_ears" to Entry("лисьи уши", Group.ACCESSORY),
        "rabbit_ears" to Entry("кроличьи уши", Group.ACCESSORY),
        "animal_ear_fluff" to Entry("пушок на ушах", Group.ACCESSORY),
        "horns" to Entry("рога", Group.ACCESSORY),
        "wings" to Entry("крылья", Group.ACCESSORY),
        "tail" to Entry("звериный хвост", Group.ACCESSORY),
        "cat_tail" to Entry("кошачий хвост", Group.ACCESSORY),
        "halo" to Entry("нимб", Group.ACCESSORY),
        "bell" to Entry("колокольчик", Group.ACCESSORY),
        "flower" to Entry("цветы", Group.ACCESSORY),
        "eyelashes" to Entry("ресницы", Group.ACCESSORY),
        "makeup" to Entry("макияж", Group.ACCESSORY),
        "tattoo" to Entry("тату", Group.ACCESSORY),
        "mole_under_eye" to Entry("родинка под глазом", Group.ACCESSORY),

        // --- поза и настроение ------------------------------------------------
        "looking_at_viewer" to Entry("смотрит на зрителя", Group.EXPRESSION),
        "looking_to_the_side" to Entry("смотрит в сторону", Group.EXPRESSION),
        "looking_back" to Entry("смотрит через плечо", Group.EXPRESSION),
        "looking_at_another" to Entry("смотрит на другого персонажа", Group.EXPRESSION),
        "smile" to Entry("улыбается", Group.EXPRESSION),
        "grin" to Entry("широко улыбается", Group.EXPRESSION),
        "blush" to Entry("румянец на щеках", Group.EXPRESSION),
        "open_mouth" to Entry("рот открыт", Group.EXPRESSION),
        "closed_mouth" to Entry("рот закрыт", Group.EXPRESSION),
        "parted_lips" to Entry("приоткрытые губы", Group.EXPRESSION),
        "tongue_out" to Entry("высунут язык", Group.EXPRESSION),
        "fang" to Entry("клыки", Group.EXPRESSION),
        "closed_eyes" to Entry("глаза закрыты", Group.EXPRESSION),
        "one_eye_closed" to Entry("один глаз прикрыт", Group.EXPRESSION),
        "v-shaped_eyebrows" to Entry("брови домиком", Group.EXPRESSION),
        "tears" to Entry("слёзы", Group.EXPRESSION),
        "sweat" to Entry("испарина на лбу", Group.EXPRESSION),
        "head_tilt" to Entry("голова наклонена", Group.EXPRESSION),
        "chibi" to Entry("нарисовано в стиле чиби", Group.EXPRESSION),
        "full_body" to Entry("в полный рост", Group.EXPRESSION),
        "upper_body" to Entry("кадр по пояс", Group.EXPRESSION),
        "cowboy_shot" to Entry("кадр по бедро", Group.EXPRESSION),
        "profile" to Entry("в профиль", Group.EXPRESSION),
        "from_behind" to Entry("со спины", Group.EXPRESSION),
        "from_side" to Entry("сбоку", Group.EXPRESSION),
        "dutch_angle" to Entry("снимок под углом", Group.EXPRESSION),
        "standing" to Entry("стоит", Group.EXPRESSION),
        "sitting" to Entry("сидит", Group.EXPRESSION),
        "kneeling" to Entry("на коленях", Group.EXPRESSION),
        "lying" to Entry("лежит", Group.EXPRESSION),
        "on_back" to Entry("на спине", Group.EXPRESSION),
        "hand_up" to Entry("рука поднята", Group.EXPRESSION),
        "arm_up" to Entry("рука вверх", Group.EXPRESSION),
        "arms_up" to Entry("руки вверх", Group.EXPRESSION),
        "hand_on_own_hip" to Entry("рука на бедре", Group.EXPRESSION),
        "barefoot" to Entry("босиком", Group.EXPRESSION),
        "multiple_views" to Entry("несколько кадров", Group.EXPRESSION),

        // --- фон --------------------------------------------------------------
        "simple_background" to Entry("однотонный", Group.BACKGROUND),
        "white_background" to Entry("белый", Group.BACKGROUND),
        "grey_background" to Entry("серый", Group.BACKGROUND),
        "gradient_background" to Entry("градиентный", Group.BACKGROUND),
        "blurry_background" to Entry("размытый", Group.BACKGROUND),
        "outdoors" to Entry("на улице", Group.BACKGROUND),
        "indoors" to Entry("в помещении", Group.BACKGROUND),
        "sky" to Entry("небо", Group.BACKGROUND),
        "blue_sky" to Entry("голубое небо", Group.BACKGROUND),
        "cloud" to Entry("облака", Group.BACKGROUND),
        "day" to Entry("день", Group.BACKGROUND),
        "water" to Entry("вода", Group.BACKGROUND),
        "tree" to Entry("дерево", Group.BACKGROUND),
        "window" to Entry("окно", Group.BACKGROUND),
        "monochrome" to Entry("одноцветный", Group.BACKGROUND),
        "greyscale" to Entry("в оттенках серого", Group.BACKGROUND),
        "comic" to Entry("оформление комикса", Group.BACKGROUND),
        "speech_bubble" to Entry("облачко с текстом", Group.BACKGROUND),
        "sparkle" to Entry("блики", Group.BACKGROUND),
        "petals" to Entry("лепестки", Group.BACKGROUND),
    )

    /** Сколько тегов в словаре. Считается из карты, а не выдумывается. */
    val size: Int get() = PHRASES.size

    /** Русская фраза для тега либо `null`, если такого тега в словаре нет. */
    fun phrase(tag: String): String? = PHRASES[tag.lowercase(Locale.ROOT)]?.phrase

    /**
     * Раскладывает теги по группам, сохраняя порядок вероятности.
     *
     * Вход — пары `(бо́ру-тег, вероятность)` в том порядке, в каком их отдала
     * модель: от более вероятных к менее. Порядок важен — в описании он и
     * читается как «что бросается в глаза сначала».
     */
    fun clauses(tags: List<Pair<String, Float>>): List<Clause> {
        val collected = LinkedHashMap<Group, MutableList<String>>()
        for ((tag, _) in tags) {
            val entry = PHRASES[tag.lowercase(Locale.ROOT)] ?: continue
            val items = collected.getOrPut(entry.group) { mutableListOf() }
            if (items.size < MAX_ITEMS_PER_GROUP) items += entry.phrase
        }
        return Group.entries.mapNotNull { group ->
            val items = collected[group] ?: return@mapNotNull null
            if (items.isEmpty()) null else Clause(group, items.distinct())
        }
    }

    /**
     * Собирает описание одной фразой по шаблону:
     * `Девушка. Волосы: длинные, бирюзовые. Одежда: школьная форма.`
     *
     * Возвращает `null`, если ни один тег в словарь не попал: пустую строку
     * показывать нечем, а «признаки не распознаны» вместо честного «описание
     * собрать не из чего» — это ровно та подмена, которой приложение
     * избегает.
     */
    fun describe(tags: List<Pair<String, Float>>): String? {
        val groups = clauses(tags)
        if (groups.isEmpty()) return null
        return groups.joinToString(" ") { clause ->
            if (clause.group == Group.SUBJECT) {
                clause.items.joinToString(", ") { it.replaceFirstChar(Char::uppercase) } + "."
            } else {
                "${clause.group.title}: ${clause.items.joinToString(", ")}."
            }
        }
    }

    /**
     * Сколько фраз берём из одной группы. Больше — описание перестаёт быть
     * описанием и превращается в перечень тегов, который читать невозможно.
     */
    private const val MAX_ITEMS_PER_GROUP = 5
}
