package com.rimpydroid.manager.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Colour arithmetic for the theme, on 0xAARRGGBB integers (kept free of Compose so that it can be tested).
 *
 * The look is white and black. "The black" is the accent that buttons, titles and links use; the user can
 * replace it by any colour ([AppConfig.accentColor]). [accentFor] keeps a chosen colour readable on white
 * (light scheme) and on black (dark scheme).
 */
object ThemeColors {

    val BLACK: Int = 0xFF000000.toInt()
    val WHITE: Int = 0xFFFFFFFF.toInt()

    /** A colour on offer in Settings. [argb] 0 is the default: black on white, white on black. */
    class Preset(val name: String, val argb: Int)

    /** The colours on offer. Each is readable on white as it is (see [accentFor]), so its swatch shows what you get. */
    val PRESETS: List<Preset> = listOf(
        Preset("Black", 0),
        Preset("Red", 0xFFB71C1C.toInt()),
        Preset("Orange", 0xFFB23C00.toInt()),
        Preset("Green", 0xFF276F2B.toInt()),
        Preset("Teal", 0xFF00695C.toInt()),
        Preset("Blue", 0xFF1565C0.toInt()),
        Preset("Purple", 0xFF6A1B9A.toInt()),
        Preset("Pink", 0xFFAD1457.toInt()),
    )

    /** Where the colour picker starts when nothing custom was chosen yet. */
    val PICKER_START: Int = 0xFF1565C0.toInt()

    fun red(c: Int): Int = (c shr 16) and 0xFF

    fun green(c: Int): Int = (c shr 8) and 0xFF

    fun blue(c: Int): Int = c and 0xFF

    fun opaque(c: Int): Int = c or (0xFF shl 24)

    fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** [a] moved [t] (0 to 1) of the way towards [b], channel by channel. */
    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun channel(x: Int, y: Int): Int = (x + (y - x) * k).roundToInt()
        return rgb(channel(red(a), red(b)), channel(green(a), green(b)), channel(blue(a), blue(b)))
    }

    /** WCAG relative luminance: 0 for black, 1 for white. */
    fun luminance(c: Int): Double {
        fun linear(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(red(c)) + 0.7152 * linear(green(c)) + 0.0722 * linear(blue(c))
    }

    /** WCAG contrast ratio of two colours: 1 (identical) to 21 (black on white). */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Black or white, whichever reads better on [background]. Always a contrast of at least 4.5. */
    fun readableOn(background: Int): Int =
        if (contrast(WHITE, background) >= contrast(BLACK, background)) WHITE else BLACK

    /**
     * [color] made darker (on a light [background]) or lighter (on a dark one), as little as possible, until its
     * contrast against [background] is at least [minRatio].
     */
    fun ensureContrast(color: Int, background: Int, minRatio: Double): Int {
        val c = opaque(color)
        if (contrast(c, background) >= minRatio) return c
        val target = if (luminance(background) > 0.5) BLACK else WHITE
        for (step in 1..20) {
            val m = mix(c, target, step / 20f)
            if (contrast(m, background) >= minRatio) return m
        }
        return target
    }

    /** True when [saved] stands for the default: nothing chosen yet, or plain black. */
    fun isDefault(saved: Int): Boolean = saved == 0 || opaque(saved) == BLACK

    /**
     * The accent to draw with: the saved colour made readable on white ([dark] false) or on black ([dark] true).
     * The default is black on white and white on black. The ratios leave room for the grey surfaces that cards
     * and bars use (the darkest of them, #E6E6E6 and #262626, still give the 4.5 that WCAG asks of text).
     */
    fun accentFor(saved: Int, dark: Boolean): Int = when {
        isDefault(saved) -> if (dark) WHITE else BLACK
        dark -> ensureContrast(saved, BLACK, MIN_ON_BLACK)
        else -> ensureContrast(saved, WHITE, MIN_ON_WHITE)
    }

    private const val MIN_ON_WHITE = 5.7
    private const val MIN_ON_BLACK = 6.5

    /** Every colour of one scheme, as 0xAARRGGBB. */
    class Palette(
        val primary: Int, val onPrimary: Int, val primaryContainer: Int, val onPrimaryContainer: Int, val inversePrimary: Int,
        val secondary: Int, val onSecondary: Int, val secondaryContainer: Int, val onSecondaryContainer: Int,
        val tertiary: Int, val onTertiary: Int, val tertiaryContainer: Int, val onTertiaryContainer: Int,
        val background: Int, val onBackground: Int,
        val surface: Int, val onSurface: Int, val surfaceVariant: Int, val onSurfaceVariant: Int,
        val surfaceTint: Int, val inverseSurface: Int, val inverseOnSurface: Int,
        val error: Int, val onError: Int, val errorContainer: Int, val onErrorContainer: Int,
        val outline: Int, val outlineVariant: Int, val scrim: Int,
        val surfaceBright: Int, val surfaceDim: Int,
        val surfaceContainerLowest: Int, val surfaceContainerLow: Int, val surfaceContainer: Int,
        val surfaceContainerHigh: Int, val surfaceContainerHighest: Int,
    )

    private fun argb(v: Long): Int = v.toInt()

    /**
     * The colours of the white scheme ([dark] false) or the black one for the saved [accentColor] (0 = default).
     * Surfaces and text are neutral greys; everything that carries the accent is worked out from it, and every
     * text colour is black or white on its background, so the pairs always read well.
     */
    fun palette(accentColor: Int, dark: Boolean): Palette {
        val a = accentFor(accentColor, dark)
        val base = if (dark) BLACK else WHITE
        val minRatio = if (dark) MIN_ON_BLACK else MIN_ON_WHITE
        val primaryContainer = mix(base, a, if (dark) 0.30f else 0.14f)
        val secondary = ensureContrast(mix(a, argb(0xFF808080), 0.5f), base, minRatio)
        val secondaryContainer = mix(base, a, if (dark) 0.20f else 0.10f)
        val tertiary = ensureContrast(mix(a, argb(0xFF9A9A9A), 0.65f), base, minRatio)
        val tertiaryContainer = mix(base, a, if (dark) 0.14f else 0.07f)
        val other = accentFor(accentColor, !dark) // how the accent looks on the opposite scheme (inverse surfaces)
        return if (dark) {
            Palette(
                primary = a, onPrimary = readableOn(a), primaryContainer = primaryContainer,
                onPrimaryContainer = readableOn(primaryContainer), inversePrimary = other,
                secondary = secondary, onSecondary = readableOn(secondary), secondaryContainer = secondaryContainer,
                onSecondaryContainer = readableOn(secondaryContainer),
                tertiary = tertiary, onTertiary = readableOn(tertiary), tertiaryContainer = tertiaryContainer,
                onTertiaryContainer = readableOn(tertiaryContainer),
                background = argb(0xFF000000), onBackground = argb(0xFFEDEDED),
                surface = argb(0xFF000000), onSurface = argb(0xFFEDEDED),
                surfaceVariant = argb(0xFF1F1F1F), onSurfaceVariant = argb(0xFFC9C9C9),
                surfaceTint = a, inverseSurface = argb(0xFFEDEDED), inverseOnSurface = argb(0xFF1A1A1A),
                error = argb(0xFFFF8A80), onError = argb(0xFF4A0000),
                errorContainer = argb(0xFF7A1510), onErrorContainer = argb(0xFFFFDAD6),
                outline = argb(0xFF8C8C8C), outlineVariant = argb(0xFF3D3D3D), scrim = argb(0xFF000000),
                surfaceBright = argb(0xFF2A2A2A), surfaceDim = argb(0xFF000000),
                surfaceContainerLowest = argb(0xFF000000), surfaceContainerLow = argb(0xFF0E0E0E),
                surfaceContainer = argb(0xFF151515), surfaceContainerHigh = argb(0xFF1D1D1D),
                surfaceContainerHighest = argb(0xFF262626),
            )
        } else {
            Palette(
                primary = a, onPrimary = readableOn(a), primaryContainer = primaryContainer,
                onPrimaryContainer = readableOn(primaryContainer), inversePrimary = other,
                secondary = secondary, onSecondary = readableOn(secondary), secondaryContainer = secondaryContainer,
                onSecondaryContainer = readableOn(secondaryContainer),
                tertiary = tertiary, onTertiary = readableOn(tertiary), tertiaryContainer = tertiaryContainer,
                onTertiaryContainer = readableOn(tertiaryContainer),
                background = argb(0xFFFFFFFF), onBackground = argb(0xFF111111),
                surface = argb(0xFFFFFFFF), onSurface = argb(0xFF111111),
                surfaceVariant = argb(0xFFEFEFEF), onSurfaceVariant = argb(0xFF454545),
                surfaceTint = a, inverseSurface = argb(0xFF2B2B2B), inverseOnSurface = argb(0xFFF2F2F2),
                error = argb(0xFFB3261E), onError = argb(0xFFFFFFFF),
                errorContainer = argb(0xFFFFDAD6), onErrorContainer = argb(0xFF410002),
                outline = argb(0xFF767676), outlineVariant = argb(0xFFD0D0D0), scrim = argb(0xFF000000),
                surfaceBright = argb(0xFFFFFFFF), surfaceDim = argb(0xFFE3E3E3),
                surfaceContainerLowest = argb(0xFFFFFFFF), surfaceContainerLow = argb(0xFFF8F8F8),
                surfaceContainer = argb(0xFFF3F3F3), surfaceContainerHigh = argb(0xFFEDEDED),
                surfaceContainerHighest = argb(0xFFE6E6E6),
            )
        }
    }

    /** "#RRGGBB" in capitals (no locale involved: digits stay digits). */
    fun toHex(c: Int): String = "#" + (c and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()

    /** "#RGB", "#RRGGBB" or the same without "#"; null for anything else. The result is opaque. */
    fun parseHex(text: String): Int? {
        var t = text.trim().removePrefix("#")
        if (t.length == 3) t = t.map { "$it$it" }.joinToString("")
        if (t.length != 6 || !t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return opaque(t.toInt(16))
    }
}
