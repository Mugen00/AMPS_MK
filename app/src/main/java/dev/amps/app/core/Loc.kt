package dev.amps.app.core

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf

/**
 * 1.5.0: три мови інтерфейсу — українська (базова), російська, англійська.
 *
 * Українські рядки в коді — це ключі: [t] шукає їх у словнику поточної
 * мови і повертає переклад; нема перекладу — показується український
 * оригінал (м'яка деградація, нічого не падає).
 *
 * Мова зберігається в SharedPreferences ("app_lang": system|uk|ru|en),
 * читається один раз при старті [init]. Зміна мови миттєва: [t] у
 * композиції читає [revision] — лічильник, що зростає при кожній зміні,
 * тому всі екрани перемальовуються без перезапуску.
 */
object Loc {

    enum class Lang(val code: String) {
        SYSTEM("system"),
        UK("uk"),
        RU("ru"),
        EN("en"),
    }

    /** Назви мов для перемикача (самих себе не перекладаємо). */
    val langLabels = listOf("Системна", "Українська", "Російська", "English")

    /** Поточна мова (SYSTEM — як у системи телефона). */
    @Volatile var language: Lang = Lang.SYSTEM

    /** Лічильник змін мови — read у @Composable t() дає миттєве оновлення. */
    val revision = mutableIntStateOf(0)

    /** Системна мова телефона, визначена при старті. */
    @Volatile var systemLanguage: String = "uk"

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        systemLanguage = context.resources.configuration.locales[0].language
        language = runCatching {
            Lang.valueOf(prefs.getString(KEY, Lang.SYSTEM.name) ?: Lang.SYSTEM.name)
        }.getOrDefault(Lang.SYSTEM)
    }

    /** Зберегти мову і підняти ревізію — всі екрани перекладаються одразу. */
    fun setLanguage(context: Context, lang: Lang) {
        language = lang
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, lang.name).apply()
        revision.intValue += 1
    }

    /** Мова, у яку перекладаємо зараз: обрана або системна. */
    private val effective: String
        get() = when (language) {
            Lang.UK -> "uk"
            Lang.RU -> "ru"
            Lang.EN -> "en"
            Lang.SYSTEM -> systemLanguage
        }

    /** Переклад рядка (оригінал → мова користувача). У композиції — [t]. */
    fun translate(uk: String): String = when (effective) {
        "ru" -> ru[uk] ?: uk
        "en" -> en[uk] ?: uk
        else -> ukDict[uk] ?: uk
    }

    private const val PREFS = "amps_prefs"
    private const val KEY = "app_lang"

    // --- словники: ключ — оригінальний рядок у коді ----------------------

    /** Російська (укр-ключі + титули). */
    val ru: Map<String, String> = ruDict + ruTitles

    /** Англійська (укр-ключі + рос-ключі Wiki/Music + титули). */
    val en: Map<String, String> = enDict + enTitles

    /** Українська для рос-оригіналів (Wiki/Music екрани). */
    val uk: Map<String, String> = ukDict
}

/** Переклад рядка в композиції: реагує на зміну мови миттєво. */
@Composable
fun t(uk: String): String = Loc.translate(uk)
