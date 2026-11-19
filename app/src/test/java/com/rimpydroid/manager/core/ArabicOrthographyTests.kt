package com.rimpydroid.manager.core

import com.rimpydroid.manager.ui.Translations
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fathatan (ً) belongs on the LAST letter of a word: "تلقائياً", "أيضاً", "معاً" - on the alef that ends the word, never on
 * the letter before it ("تلقائيًا"). These tests keep every translation row (and so every future one) in that form.
 */
class ArabicOrthographyTests {

    private val fathatan = 'ً'

    /** A fathatan that is followed by another Arabic letter is not on the last letter of its word. */
    private val misplaced = Regex("ً[ء-ي]")

    @Test
    fun noFathatanIsFollowedByAnotherLetter() {
        val offenders = ArrayList<String>()
        for (row in Translations.ROWS) {
            for (hit in misplaced.findAll(row)) {
                val start = (hit.range.first - 12).coerceAtLeast(0)
                val end = (hit.range.last + 4).coerceAtMost(row.length)
                offenders.add(row.substring(start, end))
            }
        }
        assertTrue("fathatan not on the last letter in: $offenders", offenders.isEmpty())
    }

    @Test
    fun theFormsThatWereWrongAreNowRight() {
        val arabic = Translations.build().getValue("ar").values.joinToString("\n")
        // none of the old "letter + ً + alef" endings is left
        assertTrue(!arabic.contains("تلقائيًا"))
        assertTrue(!arabic.contains("أيضًا"))
        assertTrue(arabic.contains("تلقائياً"))
        assertTrue(arabic.contains("أيضاً"))
        assertTrue(arabic.contains("معاً"))
    }

    @Test
    fun everyFathatanEndsItsWord() {
        for (row in Translations.ROWS) {
            for (i in row.indices) {
                if (row[i] != fathatan) continue
                val next = row.getOrNull(i + 1)
                assertTrue(
                    "fathatan in the middle of a word: " + row.substring((i - 8).coerceAtLeast(0), (i + 4).coerceAtMost(row.length)),
                    next == null || !(next in 'ء'..'ي'),
                )
            }
        }
    }
}
