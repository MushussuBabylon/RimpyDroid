package com.rimpydroid.manager.core

/**
 * Matches the name of a mod, as it stands in a list somebody wrote, against the titles of Workshop items.
 *
 * Titles carry tags such as "[1.6]" and people write the same name with different spacing ("RimHUD", "Rim HUD"),
 * so words are compared, not characters. A title that lacks a word of the wanted name is never taken for the same
 * mod: "Vanilla Factions Expanded - Core" must not turn into "Vanilla Factions Expanded - Ancients".
 */
object NameMatch {

    /** Score of a title whose words are the wanted name's words. */
    const val EXACT = 100

    /** The lowest score that counts as the mod meant when its name is known. */
    const val MIN_SCORE = 70

    /** The lowest score when the name was only guessed from a package id: it has to be (nearly) the same. */
    const val MIN_GUESS_SCORE = 90

    /** Bracketed tags in a title: "[1.6]", "(Continued)". */
    private val tags = Regex("""\[[^\]]*]|\([^)]*\)""")
    private val separators = Regex("""[^\p{L}\p{N}]+""")

    /** The words of [text] in lower case. Bracketed tags do not count, unless nothing else is left. */
    fun words(text: String): List<String> {
        val plain = splitWords(tags.replace(text, " "))
        return if (plain.isNotEmpty()) plain else splitWords(text)
    }

    private fun splitWords(text: String): List<String> =
        text.lowercase().split(separators).filter { it.isNotEmpty() }

    /** The letters and digits of [text] in one piece, so that "Rim HUD" and "RimHUD" come out the same. */
    fun squashed(text: String): String = words(text).joinToString("")

    /** 0 (nothing in common) up to [EXACT] (the same name). */
    fun score(wanted: String, title: String): Int {
        val w = words(wanted)
        val t = words(title)
        if (w.isEmpty() || t.isEmpty()) return 0
        if (w == t) return EXACT
        if (w.joinToString("") == t.joinToString("")) return 96
        val wantSet = w.toSet()
        val haveSet = t.toSet()
        val common = wantSet.count { it in haveSet }
        // a wanted word is missing: this is another mod, however much the names have in common
        if (common < wantSet.size) return minOf(60, common * 60 / wantSet.size)
        // everything wanted is there, and the title adds words: fine for a few ("- Better Guests"), not for a sentence
        if (haveSet.size - wantSet.size > 3) return 60
        return 70 + 30 * wantSet.size / haveSet.size
    }

    /**
     * The item of [items] that is the mod called [wanted], or null when none is close enough (at least [minScore]).
     * Of equally good titles the one for [preferVersion] wins, then the one with the most subscribers.
     */
    fun best(wanted: String, items: List<WorkshopItem>, minScore: Int = MIN_SCORE, preferVersion: String? = null): WorkshopItem? {
        var top: WorkshopItem? = null
        var topScore = 0
        for (item in items) {
            if (item.isCollection || !item.isAvailable) continue
            val s = score(wanted, item.title)
            if (s < minScore) continue
            val current = top
            val better = when {
                current == null -> true
                s != topScore -> s > topScore
                else -> rank(item, preferVersion) > rank(current, preferVersion)
            }
            if (better) {
                top = item
                topScore = s
            }
        }
        return top
    }

    private fun rank(item: WorkshopItem, preferVersion: String?): Long {
        val forVersion = !preferVersion.isNullOrBlank() && preferVersion in item.versionTags
        return (if (forVersion) 1_000_000_000_000L else 0L) + item.subscriptions
    }

    /**
     * A name to search for when a list only has the package id of a mod: the id without its author part
     * ("brrainz.harmony" becomes "harmony"). Null when the id does not have such a part.
     */
    fun guessFromPackageId(id: String): String? {
        val parts = id.trim().split('.').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val guess = parts.drop(1).joinToString(" ").replace('_', ' ').replace('-', ' ').trim()
        return guess.takeIf { squashed(it).length >= 3 }
    }
}
