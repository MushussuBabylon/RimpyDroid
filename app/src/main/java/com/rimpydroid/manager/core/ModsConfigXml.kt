package com.rimpydroid.manager.core

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Contents of RimWorld's Config/ModsConfig.xml. */
data class ModsConfigData(
    val version: String?,
    val activeMods: List<String>, // package ids in load order
    val knownExpansions: List<String>,
)

object ModsConfigXml {

    fun read(file: File): ModsConfigData? {
        if (!file.isFile) return null
        val text = try {
            XmlUtil.readText(file)
        } catch (e: Exception) {
            return null
        }
        return parse(text)
    }

    fun parse(text: String): ModsConfigData? {
        try {
            val root = XmlUtil.parse(text).documentElement ?: return null
            val version = root.childText("version").ifBlank { null }
            val active = root.child("activeMods")?.liTexts().orEmpty()
            val known = root.child("knownExpansions")?.liTexts().orEmpty()
            return ModsConfigData(version, active.map { it.trim() }, known.map { it.trim() })
        } catch (e: Exception) {
            // fall through to the tolerant parser
        }
        val clean = XmlUtil.sanitize(text)
        val activeBlock = XmlUtil.tagBlock(clean, "activeMods") ?: return null
        val knownBlock = XmlUtil.tagBlock(clean, "knownExpansions")
        val version = XmlUtil.tagBlock(clean, "version")?.trim()
        return ModsConfigData(
            version = version?.ifBlank { null },
            activeMods = lis(activeBlock),
            knownExpansions = if (knownBlock != null) lis(knownBlock) else emptyList(),
        )
    }

    private fun lis(block: String): List<String> =
        XmlUtil.liBlocks(block)
            .map { XmlUtil.unescape(it).trim() }
            .filter { it.isNotEmpty() }

    fun render(version: String?, activeMods: List<String>, knownExpansions: List<String>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        sb.append("<ModsConfigData>\n")
        if (!version.isNullOrBlank()) {
            sb.append("  <version>").append(XmlUtil.escape(version.trim())).append("</version>\n")
        }
        sb.append("  <activeMods>\n")
        for (id in activeMods) {
            sb.append("    <li>").append(XmlUtil.escape(id)).append("</li>\n")
        }
        sb.append("  </activeMods>\n")
        sb.append("  <knownExpansions>\n")
        for (id in knownExpansions) {
            sb.append("    <li>").append(XmlUtil.escape(id)).append("</li>\n")
        }
        sb.append("  </knownExpansions>\n")
        sb.append("</ModsConfigData>\n")
        return sb.toString()
    }

    /**
     * Writes ModsConfig.xml. An existing file is copied to ModsConfig.xml.bak-<time> first and only
     * the newest [keepBackups] backups are kept. Version and knownExpansions are preserved from
     * the existing file; [fallbackExpansions] that it does not list yet are added after them (a DLC that was bought
     * since, or one the person just said they own: without it in this list the game would switch it on by itself).
     */
    fun write(
        file: File,
        activeMods: List<String>,
        fallbackVersion: String?,
        fallbackExpansions: List<String>,
        keepBackups: Int = 8,
    ) {
        val existing = read(file)
        val version = existing?.version ?: fallbackVersion
        val known = OfficialDlc.mergeKnown(existing?.knownExpansions.orEmpty(), fallbackExpansions)
        val parent = file.absoluteFile.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        if (file.isFile) backup(file, keepBackups)
        FileUtil.writeTextAtomic(file, render(version, activeMods, known))
    }

    fun backup(file: File, keep: Int) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val parent = file.absoluteFile.parentFile ?: return
        try {
            file.copyTo(File(parent, file.name + ".bak-" + stamp), overwrite = true)
        } catch (e: Exception) {
            return
        }
        val backups = parent.listFiles { f -> f.name.startsWith(file.name + ".bak-") }?.sortedBy { it.name } ?: return
        if (backups.size > keep) {
            for (old in backups.take(backups.size - keep)) old.delete()
        }
    }
}
