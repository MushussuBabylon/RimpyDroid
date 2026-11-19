package com.rimpydroid.manager.core

import com.rimpydroid.manager.ui.Translations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings > Appearance: the accent colour that is drawn black on white and white on black must be called what it is
 * (it used to stay "Black" on a black screen), and every colour name has to exist in every language.
 */
class ThemeLabelTests {

    private val default = ThemeColors.PRESETS.first()

    @Test
    fun theDefaultAccentIsCalledBlackOnWhiteAndWhiteOnBlack() {
        assertEquals(0, default.argb)
        assertEquals("Black", ThemeColors.presetLabel(default, dark = false))
        assertEquals("White", ThemeColors.presetLabel(default, dark = true))
    }

    @Test
    fun theNameFollowsWhatTheColourIsDrawnWith() {
        assertEquals(ThemeColors.WHITE, ThemeColors.accentFor(default.argb, dark = true))
        assertEquals(ThemeColors.BLACK, ThemeColors.accentFor(default.argb, dark = false))
        // the label and the colour agree
        for (dark in listOf(false, true)) {
            val drawn = ThemeColors.accentFor(default.argb, dark)
            val label = ThemeColors.presetLabel(default, dark)
            assertEquals(if (drawn == ThemeColors.WHITE) "White" else "Black", label)
        }
    }

    @Test
    fun otherColoursKeepTheirNameOnBothScreens() {
        for (preset in ThemeColors.PRESETS.drop(1)) {
            assertEquals(preset.name, ThemeColors.presetLabel(preset, dark = false))
            assertEquals(preset.name, ThemeColors.presetLabel(preset, dark = true))
        }
    }

    @Test
    fun everyColourNameIsTranslatedIntoEveryLanguage() {
        val tables = Translations.build()
        val names = ThemeColors.PRESETS.map { it.name } + "White" + "Black" +
            "Black on white. The black can be any colour." + "White on black. The white can be any colour."
        for (code in Translations.CODES.filter { it != "en" }) {
            val table = tables.getValue(code)
            for (name in names) {
                val text = table[name]
                assertTrue("$code has no translation of \"$name\"", !text.isNullOrBlank())
            }
        }
    }

    @Test
    fun theColourIsNotLeftInEnglishInArabic() {
        val ar = Translations.build().getValue("ar")
        assertEquals("أبيض", ar["White"])
        assertEquals("أسود", ar["Black"])
        assertNotEquals("Red", ar["Red"])
        // the description follows the screen too, in Arabic
        assertTrue(ar.getValue("White on black. The white can be any colour.").startsWith("أبيض"))
        assertTrue(ar.getValue("Black on white. The black can be any colour.").startsWith("أسود"))
    }
}
