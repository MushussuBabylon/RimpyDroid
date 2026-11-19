package com.rimpydroid.manager.core

import java.io.File

/** A RimWorld version such as "1.6.4630 rev467". Mods only care about major.minor. */
data class GameVersion(val major: Int, val minor: Int, val build: Int = 0, val raw: String = "") {

    /** "1.6" */
    val short: String get() = "$major.$minor"

    /** Version key used in About.xml "...ByVersion" sections, e.g. "v1.6". */
    val xmlKey: String get() = "v$major.$minor"

    companion object {
        private val regex = Regex("""v?(\d+)\.(\d+)(?:\.(\d+))?""", RegexOption.IGNORE_CASE)

        fun parse(text: String?): GameVersion? {
            if (text == null) return null
            val m = regex.find(text.trim()) ?: return null
            val major = m.groupValues[1].toIntOrNull() ?: return null
            val minor = m.groupValues[2].toIntOrNull() ?: return null
            val build = m.groupValues[3].toIntOrNull() ?: 0
            return GameVersion(major, minor, build, text.trim())
        }

        /** Reads Version.txt from the game folder (first line, e.g. "1.6.4630 rev467"). */
        fun readFromGameDir(gameDir: File?): GameVersion? {
            if (gameDir == null) return null
            val f = FileUtil.childIgnoreCase(gameDir, "Version.txt") ?: return null
            val text = FileUtil.readTextOrNull(f, 4096) ?: return null
            val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
            return parse(firstLine)
        }
    }
}
