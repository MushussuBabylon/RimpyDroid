package com.rimpydroid.manager.core

import org.w3c.dom.Element
import java.io.File

/** Reads About/About.xml. Never throws: a broken file produces a ModMeta with a warning. */
object AboutParser {

    fun parseFile(about: File, game: GameVersion?, fallbackName: String): ModMeta {
        val text = try {
            XmlUtil.readText(about, 2 * 1024 * 1024)
        } catch (e: Exception) {
            return emptyMeta(fallbackName, "Cannot read About.xml: ${e.message}")
        }
        return parseText(text, game, fallbackName)
    }

    fun parseText(text: String, game: GameVersion?, fallbackName: String = ""): ModMeta {
        val doc = try {
            XmlUtil.parse(text)
        } catch (e: Exception) {
            null
        }
        val root = doc?.documentElement
        if (root == null) return regexFallback(XmlUtil.sanitize(text), fallbackName)
        return try {
            fromDom(root, game, fallbackName)
        } catch (e: Exception) {
            regexFallback(XmlUtil.sanitize(text), fallbackName)
        }
    }

    private fun emptyMeta(name: String, warning: String?): ModMeta = ModMeta(
        name = name,
        authors = "",
        packageId = "",
        description = "",
        url = "",
        modVersion = "",
        supportedVersions = emptyList(),
        dependencies = emptyList(),
        loadBefore = emptyList(),
        loadAfter = emptyList(),
        incompatibleWith = emptyList(),
        warning = warning,
    )

    // ---- DOM based parser ---------------------------------------------------------------------

    private fun fromDom(root: Element, game: GameVersion?, fallbackName: String): ModMeta {
        val vKey = game?.xmlKey

        val name = root.childText("name").ifBlank { fallbackName }

        var authors = root.childText("author")
        if (authors.isBlank()) {
            authors = root.child("authors")?.liTexts()?.joinToString(", ") ?: ""
        }

        val packageId = root.childText("packageId")
        val url = root.childText("url")
        val modVersion = root.childText("modVersion")

        var description = ""
        if (vKey != null) {
            description = root.child("descriptionsByVersion")?.child(vKey)?.textContent?.trim() ?: ""
        }
        if (description.isBlank()) description = root.childText("description")

        val supported = LinkedHashSet<String>()
        root.child("supportedVersions")?.liTexts()?.forEach { t ->
            GameVersion.parse(t)?.let { v -> supported.add(v.short) }
        }
        val target = root.childText("targetVersion")
        if (target.isNotEmpty()) GameVersion.parse(target)?.let { v -> supported.add(v.short) }

        // dependencies (generic + current version)
        val deps = LinkedHashMap<String, ModDependency>()
        readDeps(root.child("modDependencies")).forEach { deps.putIfAbsent(it.packageId, it) }
        if (vKey != null) {
            readDeps(root.child("modDependenciesByVersion")?.child(vKey)).forEach { deps.putIfAbsent(it.packageId, it) }
        }

        val loadBefore = LinkedHashSet<String>()
        loadBefore.addAll(readIds(root.child("loadBefore")))
        loadBefore.addAll(readIds(root.child("forceLoadBefore")))
        val loadAfter = LinkedHashSet<String>()
        loadAfter.addAll(readIds(root.child("loadAfter")))
        loadAfter.addAll(readIds(root.child("forceLoadAfter")))
        val incompatible = LinkedHashSet<String>()
        incompatible.addAll(readIds(root.child("incompatibleWith")))
        if (vKey != null) {
            loadBefore.addAll(readIds(root.child("loadBeforeByVersion")?.child(vKey)))
            loadBefore.addAll(readIds(root.child("forceLoadBeforeByVersion")?.child(vKey)))
            loadAfter.addAll(readIds(root.child("loadAfterByVersion")?.child(vKey)))
            loadAfter.addAll(readIds(root.child("forceLoadAfterByVersion")?.child(vKey)))
            incompatible.addAll(readIds(root.child("incompatibleWithByVersion")?.child(vKey)))
        }

        return ModMeta(
            name = name,
            authors = authors,
            packageId = packageId,
            description = description,
            url = url,
            modVersion = modVersion,
            supportedVersions = supported.toList(),
            dependencies = deps.values.toList(),
            loadBefore = loadBefore.toList(),
            loadAfter = loadAfter.toList(),
            incompatibleWith = incompatible.toList(),
        )
    }

    private fun readIds(el: Element?): List<String> {
        if (el == null) return emptyList()
        return el.liTexts().map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    }

    private fun readDeps(el: Element?): List<ModDependency> {
        if (el == null) return emptyList()
        val out = ArrayList<ModDependency>()
        for (li in el.childElements()) {
            if (!li.tagName.equals("li", ignoreCase = true)) continue
            val pid = li.childText("packageId").trim().lowercase()
            if (pid.isEmpty()) continue
            val alternatives = readIds(li.child("alternativePackageIds")).filter { it != pid }
            out.add(
                ModDependency(
                    packageId = pid,
                    displayName = li.childText("displayName").ifBlank { null },
                    steamWorkshopUrl = li.childText("steamWorkshopUrl").ifBlank { null },
                    downloadUrl = li.childText("downloadUrl").ifBlank { null },
                    alternatives = alternatives,
                ),
            )
        }
        return out
    }

    // ---- tolerant fallback --------------------------------------------------------------------

    private fun block(xml: String, tag: String): String? {
        val m = Regex("<$tag>(.*?)</$tag>", setOf(RegexOption.DOT_MATCHES_ALL)).find(xml) ?: return null
        return m.groupValues[1]
    }

    private fun liList(xml: String?): List<String> {
        if (xml == null) return emptyList()
        return Regex("<li>(.*?)</li>", setOf(RegexOption.DOT_MATCHES_ALL)).findAll(xml)
            .map { XmlUtil.unescape(it.groupValues[1]).trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    /** Used when About.xml is not well-formed XML (stray '&' and similar). Only the basics are recovered. */
    private fun regexFallback(xml: String, fallbackName: String): ModMeta {
        // Drop dependency blocks first so their <packageId> / <displayName> do not get picked up.
        val stripped = xml
            .replace(Regex("<modDependencies(ByVersion)?>.*?</modDependencies(ByVersion)?>", RegexOption.DOT_MATCHES_ALL), "")
        fun tag(t: String): String = block(stripped, t)?.let { XmlUtil.unescape(it).trim() } ?: ""

        val supported = LinkedHashSet<String>()
        liList(block(stripped, "supportedVersions")).forEach { GameVersion.parse(it)?.let { v -> supported.add(v.short) } }

        val authors = tag("author").ifBlank { liList(block(stripped, "authors")).joinToString(", ") }
        return ModMeta(
            name = tag("name").ifBlank { fallbackName },
            authors = authors,
            packageId = tag("packageId"),
            description = tag("description"),
            url = tag("url"),
            modVersion = tag("modVersion"),
            supportedVersions = supported.toList(),
            dependencies = emptyList(),
            loadBefore = liList(block(stripped, "loadBefore")).map { it.lowercase() },
            loadAfter = liList(block(stripped, "loadAfter")).map { it.lowercase() },
            incompatibleWith = emptyList(),
            warning = "About.xml is not valid XML (read in tolerant mode, dependencies may be missing)",
        )
    }
}
