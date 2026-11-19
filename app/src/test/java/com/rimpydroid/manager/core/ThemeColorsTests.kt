package com.rimpydroid.manager.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The white-and-black theme: the colour maths and the guarantee that any chosen colour stays readable. */
class ThemeColorsTests {

    private val black = ThemeColors.BLACK
    private val white = ThemeColors.WHITE

    private fun argb(v: Long): Int = v.toInt()

    @Test
    fun contrastIsTheWcagRatio() {
        assertEquals(21.0, ThemeColors.contrast(black, white), 0.001)
        assertEquals(1.0, ThemeColors.contrast(white, white), 0.0001)
        assertEquals(ThemeColors.contrast(black, white), ThemeColors.contrast(white, black), 0.0)
        // the well-known grey #767676 on white is just above 4.5
        val grey = ThemeColors.contrast(argb(0xFF767676), white)
        assertTrue(grey.toString(), grey > 4.5 && grey < 4.6)
    }

    @Test
    fun readableOnIsAlwaysAtLeastFourPointFive() {
        assertEquals(white, ThemeColors.readableOn(black))
        assertEquals(black, ThemeColors.readableOn(white))
        for (r in 0..255 step 15) for (g in 0..255 step 15) for (b in 0..255 step 15) {
            val bg = ThemeColors.rgb(r, g, b)
            val fg = ThemeColors.readableOn(bg)
            assertTrue("#${ThemeColors.toHex(bg)} -> ${ThemeColors.toHex(fg)}", ThemeColors.contrast(fg, bg) >= 4.5)
        }
    }

    @Test
    fun mixMovesTowardsTheOtherColour() {
        assertEquals(black, ThemeColors.mix(black, white, 0f))
        assertEquals(white, ThemeColors.mix(black, white, 1f))
        assertEquals(ThemeColors.rgb(128, 128, 128), ThemeColors.mix(black, white, 0.5f))
        assertEquals("out-of-range amounts are clamped", white, ThemeColors.mix(black, white, 7f))
    }

    @Test
    fun theDefaultIsBlackOnWhiteAndWhiteOnBlack() {
        assertEquals(black, ThemeColors.accentFor(0, dark = false))
        assertEquals(white, ThemeColors.accentFor(0, dark = true))
        // a colour that is plain black counts as the default too (it would be invisible on the black scheme)
        assertTrue(ThemeColors.isDefault(0))
        assertTrue(ThemeColors.isDefault(black))
        assertTrue(ThemeColors.isDefault(0x00000000))
        assertFalse(ThemeColors.isDefault(argb(0xFF000001)))
        assertEquals(white, ThemeColors.accentFor(black, dark = true))
    }

    @Test
    fun aReadableColourIsLeftAloneAndAnUnreadableOneMovesAsLittleAsPossible() {
        val blue = argb(0xFF1565C0)
        assertEquals("fine on white", blue, ThemeColors.accentFor(blue, dark = false))
        val onBlack = ThemeColors.accentFor(blue, dark = true)
        assertNotEquals("too dark on black", blue, onBlack)
        assertTrue(ThemeColors.contrast(onBlack, black) >= 6.5)
        assertTrue("still blue", ThemeColors.blue(onBlack) > ThemeColors.red(onBlack))

        val yellow = argb(0xFFFFEB3B)
        val onWhite = ThemeColors.accentFor(yellow, dark = false)
        assertTrue(ThemeColors.contrast(onWhite, white) >= 5.7)
        assertEquals("fine on black", yellow, ThemeColors.accentFor(yellow, dark = true))
    }

    @Test
    fun everyColourStaysReadableOnWhiteAndOnBlack() {
        for (r in 0..255 step 17) for (g in 0..255 step 17) for (b in 0..255 step 17) {
            val c = ThemeColors.rgb(r, g, b)
            val light = ThemeColors.accentFor(c, dark = false)
            val dark = ThemeColors.accentFor(c, dark = true)
            assertTrue("${ThemeColors.toHex(c)} on white", ThemeColors.contrast(light, white) >= 5.7)
            assertTrue("${ThemeColors.toHex(c)} on black", ThemeColors.contrast(dark, black) >= 6.5)
        }
    }

    /** Text and background pairs of a palette, with the contrast each needs. */
    private fun pairs(p: ThemeColors.Palette): List<Triple<String, Pair<Int, Int>, Double>> = listOf(
        Triple("onPrimary", p.onPrimary to p.primary, 4.5),
        Triple("onPrimaryContainer", p.onPrimaryContainer to p.primaryContainer, 4.5),
        Triple("onSecondary", p.onSecondary to p.secondary, 4.5),
        Triple("onSecondaryContainer", p.onSecondaryContainer to p.secondaryContainer, 4.5),
        Triple("onTertiary", p.onTertiary to p.tertiary, 4.5),
        Triple("onTertiaryContainer", p.onTertiaryContainer to p.tertiaryContainer, 4.5),
        Triple("onBackground", p.onBackground to p.background, 4.5),
        Triple("onSurface", p.onSurface to p.surface, 4.5),
        Triple("onSurface/low", p.onSurface to p.surfaceContainerLow, 4.5),
        Triple("onSurface/container", p.onSurface to p.surfaceContainer, 4.5),
        Triple("onSurface/high", p.onSurface to p.surfaceContainerHigh, 4.5),
        Triple("onSurface/highest", p.onSurface to p.surfaceContainerHighest, 4.5),
        Triple("onSurface/variant", p.onSurface to p.surfaceVariant, 4.5),
        Triple("onSurfaceVariant", p.onSurfaceVariant to p.surfaceVariant, 4.5),
        Triple("onSurfaceVariant/surface", p.onSurfaceVariant to p.surface, 4.5),
        Triple("onSurfaceVariant/high", p.onSurfaceVariant to p.surfaceContainerHighest, 4.5),
        Triple("inverseOnSurface", p.inverseOnSurface to p.inverseSurface, 4.5),
        Triple("onError", p.onError to p.error, 4.5),
        Triple("onErrorContainer", p.onErrorContainer to p.errorContainer, 4.5),
        // the accent as text and as icons on the surfaces it is drawn on (titles, links, tags)
        Triple("primary/surface", p.primary to p.surface, 4.5),
        Triple("primary/low", p.primary to p.surfaceContainerLow, 4.5),
        Triple("primary/container", p.primary to p.surfaceContainer, 4.5),
        Triple("primary/high", p.primary to p.surfaceContainerHigh, 4.5),
        Triple("primary/highest", p.primary to p.surfaceContainerHighest, 4.5),
        Triple("primary/secondaryContainer", p.primary to p.secondaryContainer, 3.0),
        Triple("error/surface", p.error to p.surface, 4.5),
        // borders and outlines (graphics need 3:1)
        Triple("outline/surface", p.outline to p.surface, 3.0),
    )

    @Test
    fun everyPairOfEveryPaletteIsReadable() {
        val accents = ArrayList<Int>()
        accents.add(0)
        accents.addAll(ThemeColors.PRESETS.map { it.argb })
        for (r in 0..255 step 51) for (g in 0..255 step 51) for (b in 0..255 step 51) accents.add(ThemeColors.rgb(r, g, b))
        for (accent in accents) for (dark in listOf(false, true)) {
            val p = ThemeColors.palette(accent, dark)
            for ((name, pair, needed) in pairs(p)) {
                val ratio = ThemeColors.contrast(pair.first, pair.second)
                assertTrue(
                    "accent ${ThemeColors.toHex(accent)} dark=$dark $name: $ratio < $needed",
                    ratio >= needed,
                )
            }
        }
    }

    @Test
    fun theDefaultPaletteIsPlainWhiteAndBlack() {
        val light = ThemeColors.palette(0, dark = false)
        assertEquals(white, light.background)
        assertEquals(white, light.surface)
        assertEquals(black, light.primary)
        assertEquals(white, light.onPrimary)
        val dark = ThemeColors.palette(0, dark = true)
        assertEquals(black, dark.background)
        assertEquals(black, dark.surface)
        assertEquals(white, dark.primary)
        assertEquals(black, dark.onPrimary)
        // no colour in the neutral parts: red, green and blue are equal
        for (c in listOf(light.surfaceVariant, light.surfaceContainer, light.outline, dark.surfaceVariant, dark.surfaceContainerHigh, dark.outline)) {
            assertEquals(ThemeColors.red(c), ThemeColors.green(c))
            assertEquals(ThemeColors.green(c), ThemeColors.blue(c))
        }
    }

    @Test
    fun aChosenColourShowsUpInTheAccentsOnly() {
        val red = argb(0xFFB71C1C)
        val p = ThemeColors.palette(red, dark = false)
        assertEquals(red, p.primary)
        assertEquals(white, p.surface)
        assertEquals(white, p.background)
        assertTrue("container is a tint of the red", ThemeColors.red(p.primaryContainer) > ThemeColors.blue(p.primaryContainer))
    }

    @Test
    fun presetsStartWithTheDefaultAndAreAllDifferent() {
        assertEquals(0, ThemeColors.PRESETS.first().argb)
        assertEquals("Black", ThemeColors.PRESETS.first().name)
        assertEquals(ThemeColors.PRESETS.size, ThemeColors.PRESETS.map { it.argb }.toSet().size)
        assertEquals(ThemeColors.PRESETS.size, ThemeColors.PRESETS.map { it.name }.toSet().size)
        assertTrue(ThemeColors.PRESETS.drop(1).all { it.argb ushr 24 == 0xFF })
    }

    @Test
    fun everyPresetLooksOnWhiteExactlyAsItIsDefined() {
        for (preset in ThemeColors.PRESETS.drop(1)) {
            assertEquals(preset.name, preset.argb, ThemeColors.accentFor(preset.argb, dark = false))
        }
    }

    @Test
    fun hexCodesAreReadAndWritten() {
        assertEquals(argb(0xFF1565C0), ThemeColors.parseHex("#1565c0"))
        assertEquals(argb(0xFF1565C0), ThemeColors.parseHex("1565C0"))
        assertEquals(argb(0xFFFFAA00), ThemeColors.parseHex("#fa0"))
        assertEquals(argb(0xFF112233), ThemeColors.parseHex("  #123  "))
        assertNull(ThemeColors.parseHex(""))
        assertNull(ThemeColors.parseHex("#12"))
        assertNull(ThemeColors.parseHex("#12345"))
        assertNull(ThemeColors.parseHex("#GGGGGG"))
        assertNull(ThemeColors.parseHex("#1565C0FF"))
        assertEquals("#0A0B0C", ThemeColors.toHex(argb(0xFF0A0B0C)))
        assertEquals("#000000", ThemeColors.toHex(black))
        for (c in listOf(argb(0xFF123456), argb(0xFFFEDCBA), black, white)) {
            assertEquals(c, ThemeColors.parseHex(ThemeColors.toHex(c)))
        }
    }

    @Test
    fun theAccentIsKeptInTheSettings() {
        val c = AppConfig(accentColor = argb(0xFFB71C1C))
        assertEquals(argb(0xFFB71C1C), c.copy(themeMode = AppConfig.THEME_DARK).accentColor)
        assertEquals("nothing chosen is the default", 0, AppConfig().accentColor)
    }
}
