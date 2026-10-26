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
        val activeBlock = Regex("<activeMods>(.*?)</activeMods>", RegexOption.DOT_MATCHES_ALL).find(clean)
        if (activeBlock == null) return null
        val knownBlock = Regex("<knownExpansions>(.*?)</knownExpansions>", RegexOption.DOT_MATCHES_ALL).find(clean)
        val version = Regex("<version>(.*?)</version>", RegexOption.DOT_MATCHES_ALL).find(clean)?.groupValues?.get(1)?.trim()
        return ModsConfigData(
            version = version?.ifBlank { null },
            activeMods = lis(activeBlock.groupValues[1]),
            knownExpansions = if (knownBlock != null) lis(knownBlock.groupValues[1]) else emptyList(),
        )
    }

    private fun lis(block: String): List<String> =
        Regex("<li>(.*?)</li>", RegexOption.DOT_MATCHES_ALL).findAll(block)
            .map { XmlUtil.unescape(it.groupValues[1]).trim() }
            .filter { it.isNotEmpty() }
            .toList()

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
     * the existing file unless given explicitly.
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
        val known = if (existing != null && existing.knownExpansions.isNotEmpty()) existing.knownExpansions else fallbackExpansions
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
