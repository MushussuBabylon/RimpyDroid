package com.rimpydroid.manager.core

import java.io.File

/** One line of a saved mod list. [id] is a lower-case package id and may be empty when only the Workshop id is known. */
data class ListEntry(val id: String, val name: String?, val workshopId: Long?)

/** A named, ordered mod list ("profile"). */
data class ModList(
    val name: String,
    val entries: List<ListEntry>,
    val gameVersion: String?,
    val savedAt: Long,
) {
    val packageIds: List<String> get() = entries.map { it.id }.filter { it.isNotEmpty() }
    val workshopIds: List<Long> get() = entries.mapNotNull { it.workshopId }.distinct()
}

/** The result of matching a list against what is installed. */
class ApplyResult(
    val activeIds: List<String>, // installed mods, in the list's order
    val missing: List<ListEntry>, // entries that are not installed
)

object ListCodec {

    const val FORMAT = "rimpydroid-list"

    fun toJson(list: ModList): String {
        val root = LinkedHashMap<String, Any?>()
        root["format"] = FORMAT
        root["version"] = 1
        root["name"] = list.name
        root["gameVersion"] = list.gameVersion
        root["savedAt"] = list.savedAt
        val mods = ArrayList<Any?>()
        for (e in list.entries) {
            val m = LinkedHashMap<String, Any?>()
            m["id"] = e.id
            if (!e.name.isNullOrBlank()) m["name"] = e.name
            if (e.workshopId != null) m["workshopId"] = e.workshopId.toString()
            mods.add(m)
        }
        root["mods"] = mods
        return Json.stringify(root, pretty = true)
    }

    fun fromJson(text: String, fallbackName: String = "Imported list"): ModList? {
        val root = Json.parseOrNull(text).asObj() ?: return null
        val mods = root["mods"].asArr() ?: return null
        val entries = ArrayList<ListEntry>()
        for (m in mods) {
            val o = m.asObj()
            if (o != null) {
                val id = o["id"].asStr()?.trim()?.lowercase().orEmpty()
                val ws = o["workshopId"].asLong()?.takeIf { it > 0 }
                if (id.isEmpty() && ws == null) continue
                entries.add(ListEntry(id, o["name"].asStr()?.takeIf { it.isNotBlank() }, ws))
            } else {
                val s = m.asStr()?.trim()?.lowercase() ?: continue
                if (s.isNotEmpty()) entries.add(ListEntry(s, null, null))
            }
        }
        val name = root["name"].asStr()?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackName
        return ModList(name, entries, root["gameVersion"].asStr(), root["savedAt"].asLong() ?: 0L)
    }

    /** Human readable, re-importable text: one mod per line "packageId  # name  [workshopId]". */
    fun toText(list: ModList): String {
        val sb = StringBuilder()
        sb.append("# ").append(list.name).append('\n')
        if (!list.gameVersion.isNullOrBlank()) sb.append("# RimWorld ").append(list.gameVersion).append('\n')
        for (e in list.entries) {
            if (e.id.isNotEmpty()) sb.append(e.id) else sb.append("?")
            if (!e.name.isNullOrBlank()) sb.append("  # ").append(e.name.replace('\n', ' '))
            if (e.workshopId != null) sb.append("  [").append(e.workshopId).append(']')
            sb.append('\n')
        }
        return sb.toString()
    }
}

/** Reads mod lists in the formats people actually have: our own JSON, RimWorld .rml/.rws metadata, ModsConfig.xml, plain text. */
object ListImporter {

    private val packageIdToken = Regex("""^[A-Za-z0-9_\-]+(?:\.[A-Za-z0-9_\-]+)+$""")
    private val onlyDigitsAndDots = Regex("""^[\d.]+$""")
    private val bracketedId = Regex("""\[(\d{6,12})]""")

    fun parse(text: String, fallbackName: String = "Imported list"): ModList? {
        val t = XmlUtil.sanitize(text).trim()
        if (t.isEmpty()) return null
        if (t.startsWith("{")) {
            ListCodec.fromJson(t, fallbackName)?.let { if (it.entries.isNotEmpty()) return it }
        }
        if (t.startsWith("<")) {
            parseXml(t, fallbackName)?.let { return it }
        }
        return parsePlain(t, fallbackName)
    }

    private fun parseXml(xml: String, fallbackName: String): ModList? {
        // RimWorld mod list (.rml), save game header (.rws): <modIds>, <modNames>, <modSteamIds>
        val ids = liBlock(xml, "modIds")
        if (ids != null && ids.isNotEmpty()) {
            val names = liBlock(xml, "modNames").orEmpty()
            val steam = liBlock(xml, "modSteamIds").orEmpty()
            val version = Regex("<gameVersion>(.*?)</gameVersion>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()
            val entries = ArrayList<ListEntry>()
            for ((i, raw) in ids.withIndex()) {
                val id = raw.trim().lowercase()
                if (id.isEmpty()) continue
                val ws = steam.getOrNull(i)?.trim()?.toLongOrNull()?.takeIf { it > 0 }
                entries.add(ListEntry(id, names.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }, ws))
            }
            return ModList(fallbackName, entries, version?.ifBlank { null }, System.currentTimeMillis())
        }
        // ModsConfig.xml
        val cfg = ModsConfigXml.parse(xml)
        if (cfg != null && cfg.activeMods.isNotEmpty()) {
            return ModList(
                fallbackName,
                cfg.activeMods.map { ListEntry(it.trim().lowercase(), null, null) },
                cfg.version,
                System.currentTimeMillis(),
            )
        }
        return null
    }

    private fun liBlock(xml: String, tag: String): List<String>? {
        val block = Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(xml) ?: return null
        return Regex("<li>(.*?)</li>", RegexOption.DOT_MATCHES_ALL).findAll(block.groupValues[1])
            .map { XmlUtil.unescape(it.groupValues[1]).trim() }
            .toList()
    }

    private fun parsePlain(text: String, fallbackName: String): ModList? {
        val entries = ArrayList<ListEntry>()
        var name = fallbackName
        var nameFromHeader = false
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#") || line.startsWith("//")) {
                // our own export starts with "# <list name>"
                if (!nameFromHeader && line.startsWith("# ") && !line.startsWith("# RimWorld ")) {
                    name = line.removePrefix("#").trim().ifEmpty { fallbackName }
                    nameFromHeader = true
                }
                continue
            }
            val entry = parseLine(line) ?: continue
            entries.add(entry)
        }
        if (entries.isEmpty()) return null
        return ModList(name, entries, null, System.currentTimeMillis())
    }

    private fun parseLine(line: String): ListEntry? {
        var body = line
        var comment: String? = null
        val hash = body.indexOf('#')
        if (hash >= 0) {
            comment = body.substring(hash + 1).trim().ifEmpty { null }
            body = body.substring(0, hash)
        }
        val ws = WorkshopUtil.extractIds(body + " " + (comment?.let { bracketedId.find(it)?.value } ?: "")).firstOrNull()
        var pid: String? = null
        for (tok in body.split(Regex("[\\s,;|\\t]+"))) {
            val tkn = tok.trim().trim('"', '\'', '<', '>', '(', ')', '[', ']')
            if (tkn.isEmpty() || tkn.contains("://") || tkn.contains('/')) continue
            if (onlyDigitsAndDots.matches(tkn)) continue
            if (packageIdToken.matches(tkn)) {
                pid = tkn.lowercase()
                break
            }
        }
        if (pid == null && ws == null) return null
        val name = comment?.replace(bracketedId, "")?.trim()?.ifEmpty { null }
        return ListEntry(pid.orEmpty(), name, ws)
    }
}

/** Matches list entries to installed mods. */
object ListApplier {
    fun resolve(list: ModList, allMods: List<ModEntry>): ApplyResult {
        val index = ModIndex(allMods)
        val byWorkshop = HashMap<Long, ModEntry>()
        for (m in allMods) {
            val w = m.workshopId ?: continue
            if (m.id.isNotEmpty()) byWorkshop.putIfAbsent(w, index[m.id] ?: m)
        }
        val active = ArrayList<String>()
        val seen = HashSet<String>()
        val missing = ArrayList<ListEntry>()
        for (e in list.entries) {
            val id = e.id.trim().lowercase()
            val mod = (if (id.isNotEmpty()) index[id] else null) ?: e.workshopId?.let { byWorkshop[it] }
            if (mod != null) {
                if (seen.add(mod.id)) active.add(mod.id)
            } else {
                missing.add(e)
            }
        }
        return ApplyResult(active, missing)
    }
}

/** Saved lists, one JSON file each in [dir]. */
class ProfileStore(private val dir: File) {

    fun all(): List<ModList> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        val out = ArrayList<ModList>()
        for (f in files) {
            val text = FileUtil.readTextOrNull(f, 8 * 1024 * 1024) ?: continue
            val list = ListCodec.fromJson(text, f.nameWithoutExtension) ?: continue
            out.add(list)
        }
        return out.sortedByDescending { it.savedAt }
    }

    fun get(name: String): ModList? {
        val f = fileFor(name)
        val text = FileUtil.readTextOrNull(f, 8 * 1024 * 1024) ?: return null
        return ListCodec.fromJson(text, name)
    }

    fun exists(name: String): Boolean = fileFor(name).isFile

    fun save(list: ModList) {
        if (!dir.exists()) dir.mkdirs()
        FileUtil.writeTextAtomic(fileFor(list.name), ListCodec.toJson(list))
    }

    fun delete(name: String): Boolean {
        val f = fileFor(name)
        return !f.exists() || f.delete()
    }

    fun rename(oldName: String, newName: String): Boolean {
        val list = get(oldName) ?: return false
        if (oldName != newName && exists(newName)) return false
        save(list.copy(name = newName))
        if (!fileFor(oldName).name.equals(fileFor(newName).name, ignoreCase = true)) delete(oldName)
        return true
    }

    private fun fileFor(name: String): File = File(dir, FileUtil.safeFileName(name) + ".json")
}
