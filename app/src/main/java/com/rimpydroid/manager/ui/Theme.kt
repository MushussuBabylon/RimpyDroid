package com.rimpydroid.manager.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rimpydroid.manager.core.AppConfig
import com.rimpydroid.manager.core.ThemeColors

// White and black. Every surface is white (dark setting: black) with neutral greys for cards and bars, and the
// accent ("the black") is the one colour that buttons, titles and links use. The user can replace it by any colour
// (Settings > Appearance); ThemeColors.accentFor keeps a chosen colour readable on white and on black, and every
// tint below is worked out from it. Red stays reserved for errors, green and amber for "ok" and "warning".

private fun c(argb: Int): Color = Color(argb)

/** The Material colour scheme for a palette (the palette itself is worked out, and tested, in ThemeColors). */
private fun schemeOf(accentColor: Int, dark: Boolean): ColorScheme {
    val p = ThemeColors.palette(accentColor, dark)
    return if (dark) {
        darkColorScheme(
            primary = c(p.primary), onPrimary = c(p.onPrimary),
            primaryContainer = c(p.primaryContainer), onPrimaryContainer = c(p.onPrimaryContainer),
            inversePrimary = c(p.inversePrimary),
            secondary = c(p.secondary), onSecondary = c(p.onSecondary),
            secondaryContainer = c(p.secondaryContainer), onSecondaryContainer = c(p.onSecondaryContainer),
            tertiary = c(p.tertiary), onTertiary = c(p.onTertiary),
            tertiaryContainer = c(p.tertiaryContainer), onTertiaryContainer = c(p.onTertiaryContainer),
            background = c(p.background), onBackground = c(p.onBackground),
            surface = c(p.surface), onSurface = c(p.onSurface),
            surfaceVariant = c(p.surfaceVariant), onSurfaceVariant = c(p.onSurfaceVariant),
            surfaceTint = c(p.surfaceTint), inverseSurface = c(p.inverseSurface), inverseOnSurface = c(p.inverseOnSurface),
            error = c(p.error), onError = c(p.onError),
            errorContainer = c(p.errorContainer), onErrorContainer = c(p.onErrorContainer),
            outline = c(p.outline), outlineVariant = c(p.outlineVariant), scrim = c(p.scrim),
            surfaceBright = c(p.surfaceBright), surfaceDim = c(p.surfaceDim),
            surfaceContainerLowest = c(p.surfaceContainerLowest), surfaceContainerLow = c(p.surfaceContainerLow),
            surfaceContainer = c(p.surfaceContainer), surfaceContainerHigh = c(p.surfaceContainerHigh),
            surfaceContainerHighest = c(p.surfaceContainerHighest),
        )
    } else {
        lightColorScheme(
            primary = c(p.primary), onPrimary = c(p.onPrimary),
            primaryContainer = c(p.primaryContainer), onPrimaryContainer = c(p.onPrimaryContainer),
            inversePrimary = c(p.inversePrimary),
            secondary = c(p.secondary), onSecondary = c(p.onSecondary),
            secondaryContainer = c(p.secondaryContainer), onSecondaryContainer = c(p.onSecondaryContainer),
            tertiary = c(p.tertiary), onTertiary = c(p.onTertiary),
            tertiaryContainer = c(p.tertiaryContainer), onTertiaryContainer = c(p.onTertiaryContainer),
            background = c(p.background), onBackground = c(p.onBackground),
            surface = c(p.surface), onSurface = c(p.onSurface),
            surfaceVariant = c(p.surfaceVariant), onSurfaceVariant = c(p.onSurfaceVariant),
            surfaceTint = c(p.surfaceTint), inverseSurface = c(p.inverseSurface), inverseOnSurface = c(p.inverseOnSurface),
            error = c(p.error), onError = c(p.onError),
            errorContainer = c(p.errorContainer), onErrorContainer = c(p.onErrorContainer),
            outline = c(p.outline), outlineVariant = c(p.outlineVariant), scrim = c(p.scrim),
            surfaceBright = c(p.surfaceBright), surfaceDim = c(p.surfaceDim),
            surfaceContainerLowest = c(p.surfaceContainerLowest), surfaceContainerLow = c(p.surfaceContainerLow),
            surfaceContainer = c(p.surfaceContainer), surfaceContainerHigh = c(p.surfaceContainerHigh),
            surfaceContainerHighest = c(p.surfaceContainerHighest),
        )
    }
}

/** Softer corners than Material's defaults: cards, chips and dialogs all share them. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

/** Material's type scale with firmer titles, so headings stand out without being larger. */
private val AppTypography: Typography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 27.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

/** Whether the app currently draws the dark (black) scheme. */
val LocalDarkTheme = staticCompositionLocalOf { false }

/** True when [themeMode] (one of the `AppConfig.THEME_*` values) means the dark scheme right now. */
@Composable
fun isDarkTheme(themeMode: Int): Boolean = when (themeMode) {
    AppConfig.THEME_DARK -> true
    AppConfig.THEME_SYSTEM -> isSystemInDarkTheme()
    else -> false
}

/**
 * The look of the app: white and black by default, with [accentColor] (see [AppConfig.accentColor]; 0 = the
 * default) in place of the black. The colours are fixed on purpose; Android's wallpaper-based "dynamic" colours
 * would replace them with whatever the wallpaper suggests.
 */
@Composable
fun RimpyDroidTheme(
    themeMode: Int = AppConfig.THEME_LIGHT,
    accentColor: Int = 0,
    glass: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = isDarkTheme(themeMode)
    val scheme = remember(dark, accentColor, glass) { if (glass) glassSchemeOf(accentColor, dark) else schemeOf(accentColor, dark) }
    CompositionLocalProvider(LocalDarkTheme provides dark, LocalGlass provides glass) {
        MaterialTheme(colorScheme = scheme, shapes = if (glass) GlassShapes else AppShapes, typography = AppTypography) {
            // Liquid glass draws its soft gradient behind everything; the classic look has nothing behind the app
            if (glass) GlassBackdrop(dark, if (accentColor == 0) GLASS_DEFAULT_ACCENT else accentColor) { content() } else content()
        }
    }
}
