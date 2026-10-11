package dev.amps.app.ui.theme

import java.time.LocalDate

/**
 * 1.2.4: сезонні теми. Hue береться з ДАТИ ПРИСТРОЮ (LocalDate.now() —
 * синхронізується з налаштуваннями часу і дати системи), тому сезонна
 * палітра змінюється сама — і навіть злегка зсувається день від дня,
 * щоб кожен день мав свій відтінок.
 *
 * Спец-дні мають пріоритет над сезоном: Хелловін, Різдво, Новий рік.
 */
object SeasonTheme {

    /** Весна — сакура; літо — блакить; осінь — гарбуз; зима — крига. */
    private const val HUE_SPRING = 330f
    private const val HUE_SUMMER = 195f
    private const val HUE_AUTUMN = 30f
    private const val HUE_WINTER = 208f

    /**
     * Hue дня: сезон (календарні місяці), плюс легкий дрейф ±8° за
     * днем року, щоб кожен день мав ледь інший тон. Спеціальні дати
     * перекривають сезон.
     */
    fun hueFor(date: LocalDate): Float {
        if (date.monthValue == 10 && date.dayOfMonth == 31) return 25f          // Хелловін
        if (date.monthValue == 1 && date.dayOfMonth == 7) return 0f             // Різдво
        if ((date.monthValue == 12 && date.dayOfMonth >= 30) ||
            (date.monthValue == 1 && date.dayOfMonth <= 2)
        ) return 45f                                                            // Новий рік
        val hue = when (date.monthValue) {
            3, 4, 5 -> HUE_SPRING
            6, 7, 8 -> HUE_SUMMER
            9, 10, 11 -> HUE_AUTUMN
            else -> HUE_WINTER
        }
        // Дрейф за днем року — палітра трохи дихає щодня.
        val drift = ((date.dayOfYear % 17) - 8).toFloat()
        return normalizedHue(hue + drift)
    }

    private fun normalizedHue(h: Float): Float {
        val m = h % 360f
        return if (m < 0f) m + 360f else m
    }

    /** Назва сезону/свята для підпису теми. */
    fun seasonName(date: LocalDate): String {
        if (date.monthValue == 10 && date.dayOfMonth == 31) return "Хелловін"
        if ((date.monthValue == 12 && date.dayOfMonth >= 30) ||
            (date.monthValue == 1 && date.dayOfMonth <= 2)
        ) return "Новорічна"
        if (date.monthValue == 1 && date.dayOfMonth == 7) return "Різдвяна"
        return when (date.monthValue) {
            3, 4, 5 -> "Весняна"
            6, 7, 8 -> "Літня"
            9, 10, 11 -> "Осіння"
            else -> "Зимова"
        }
    }
}
