package com.rimpydroid.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import java.util.Locale

/** A language the app can be shown in. [nativeName] is how the language writes its own name. */
class Language(val code: String, val nativeName: String, val rtl: Boolean = false)

/**
 * The language of the app. Text is written in English in the code and looked up with [tr]; a text that has no
 * translation (yet) is shown in English. The words themselves are in [Translations].
 */
object Lang {

    val ALL: List<Language> = listOf(
        Language("en", "English"),
        Language("ar", "العربية", rtl = true),
        Language("fa", "فارسی", rtl = true),
        Language("ru", "Русский"),
        Language("es", "Español"),
        Language("fr", "Français"),
        Language("de", "Deutsch"),
        Language("pt", "Português"),
        Language("tr", "Türkçe"),
        Language("zh", "中文"),
        Language("ja", "日本語"),
        Language("ko", "한국어"),
        Language("hi", "हिन्दी"),
        Language("id", "Bahasa Indonesia"),
        Language("it", "Italiano"),
        Language("uk", "Українська"),
    )

    /** The language in use. It is set while the screen is composed, before any text is looked up (see [ProvideLanguage]). */
    @Volatile
    var code: String = "en"

    // Text that was already drawn reads this, so it is drawn again when the language changes.
    private val tick = mutableIntStateOf(0)
    private var announced = "en"

    private val tables: Map<String, Map<String, String>> by lazy { Translations.build() }

    /** The language to use for a setting: a code from [ALL], or empty for "follow the phone" (English if unsupported). */
    fun resolve(setting: String): String {
        val chosen = setting.trim()
        if (chosen.isNotEmpty() && ALL.any { it.code == chosen }) return chosen
        val phone = Locale.getDefault().language.let { if (it == "in") "id" else it }
        return if (ALL.any { it.code == phone }) phone else "en"
    }

    fun isRtl(code: String): Boolean = ALL.firstOrNull { it.code == code }?.rtl == true

    fun translate(en: String): String {
        if (tick.intValue < 0) return en // never true; it only makes the caller depend on the language
        val c = code
        if (c == "en") return en
        return tables[c]?.get(en) ?: en
    }

    internal fun announce(newCode: String) {
        if (announced == newCode) return
        announced = newCode
        tick.intValue = tick.intValue + 1
    }
}

/** The text in the language of the app. */
fun tr(en: String): String = Lang.translate(en)

private val placeholder = Regex("""\{(\d)\}""")

/** Like [tr], and the values replace {0}, {1}, ... in the text (in one pass: a value is never searched for placeholders). */
fun tr(en: String, vararg args: Any?): String =
    placeholder.replace(Lang.translate(en)) { m -> args.getOrNull(m.groupValues[1].toInt())?.toString() ?: m.value }

/** Sets the language and the reading direction (right to left for Arabic and Persian) for everything inside. */
@Composable
fun ProvideLanguage(setting: String, content: @Composable () -> Unit) {
    val resolved = Lang.resolve(setting)
    Lang.code = resolved
    SideEffect { Lang.announce(resolved) }
    val direction = if (Lang.isRtl(resolved)) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides direction, content = content)
}
