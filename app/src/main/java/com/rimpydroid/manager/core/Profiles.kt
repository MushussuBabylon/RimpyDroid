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

    /** This list with the Workshop ids of [found] (by position in [entries]) written into entries that had none. */
    fun withWorkshopIds(found: Map<Int, Long>): ModList {
        if (found.isEmpty()) return this
        return copy(
            entries = entries.mapIndexed { i, e ->
                val ws = found[i]
                if (ws != null && e.workshopId == null) e.copy(workshopId = ws) else e
            },
        )
    }
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
                val name = o["name"].asStr()?.takeIf { it.isNotBlank() }
                // an entry that has only a name is kept: the mod can be looked up by it
                if (id.isEmpty() && ws == null && name == null) continue
                entries.add(ListEntry(id, name, ws))
            } else {
                val s = m.asStr()?.trim().orEmpty()
                if (s.isEmpty()) continue
                // a plain string is a package id, or else the name of a mod
                if (ListImporter.isPackageId(s)) entries.add(ListEntry(s.lowercase(), null, null)) else entries.add(ListEntry("", s, null))
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
    private val versionLike = Regex("""^[vV]?\d+(?:\.\d+)+[A-Za-z]?$""")
    private val bracketedId = Regex("""\[(\d{6,12})]""")

    // "- ", "* ", "1. ", "2) ", "[x] " in front of a line
    private val listMarker = Regex("""^(?:[-*\u2022\u00B7\u2013\u2014]+|\d{1,4}[.):]|\(\d{1,4}\)|\[[ xX\u2713\u2714]?])\s+""")

    // lines that describe a mod instead of naming one ("Authors: ...", "Total number of mods: 12")
    private val metadataLine = Regex(
        """^(?:package\s?ids?|authors?|urls?|steam\s?ids?|workshop\s?(?:ids?|urls?|links?)|(?:supported\s+)?versions?|description|created\s+with|date|total\b[^:=]*)\s*[:=]""",
        RegexOption.IGNORE_CASE,
    )
    private val linkOrPath = Regex("""\S*(?:://|/)\S*""")

    /** True for "author.modname": at least two dot-separated parts, and not a version number such as "v1.5.2". */
    fun isPackageId(token: String): Boolean =
        packageIdToken.matches(token) && !onlyDigitsAndDots.matches(token) && !versionLike.matches(token)

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
            val version = XmlUtil.tagBlock(xml, "gameVersion")?.trim()
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
        val block = XmlUtil.tagBlock(xml, tag) ?: return null
        return XmlUtil.liBlocks(block).map { XmlUtil.unescape(it).trim() }
    }

    private fun parsePlain(text: String, fallbackName: String): ModList? {
        val entries = ArrayList<ListEntry>()
        var name = fallbackName
        var nameFromHeader = false
        val lines = ArrayList<String>()
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
            lines.add(line)
        }
        // one line with several mods: "Harmony, Hospitality, Fluffy Breakdowns"
        val pieces = if (lines.size == 1 && (lines[0].contains(',') || lines[0].contains(';')) && WorkshopUtil.extractIds(lines[0]).isEmpty()) {
            lines[0].split(',', ';')
        } else {
            lines
        }
        for (piece in pieces) {
            val entry = parseLine(piece) ?: continue
            entries.add(entry)
        }
        if (entries.isEmpty()) return null
        return ModList(name, entries, null, System.currentTimeMillis())
    }

    /** True when [text] reads like the name of a mod and not like a heading, a sentence or a detail ("Authors: ..."). */
    private fun plausibleName(text: String): Boolean {
        if (text.length < 2 || text.length > 150) return false
        if (text.endsWith(":")) return false
        if (text.none { it.isLetter() }) return false
        if (text.split(' ').count { it.isNotEmpty() } > 12) return false
        return !metadataLine.containsMatchIn(text)
    }

    private fun tidy(text: String): String = text.trim().trim('"', '\'', '`', ',', ';', ':', '-', '\u2013', '\u2014', '|', '*', '\u2022', ' ')

    private fun parseLine(rawLine: String): ListEntry? {
        val line = listMarker.replaceFirst(rawLine.trim(), "").trim()
        if (line.isEmpty()) return null
        var body = line
        var comment: String? = null
        val hash = body.indexOf('#')
        if (hash >= 0) {
            comment = body.substring(hash + 1).trim().ifEmpty { null }
            body = body.substring(0, hash)
        }
        val ws = WorkshopUtil.extractIds(body + " " + (comment?.let { bracketedId.find(it)?.value } ?: "")).firstOrNull()
        var pid: String? = null
        var pidToken: String? = null
        for (tok in body.split(Regex("[\\s,;|\\t]+"))) {
            val tkn = tok.trim().trim('"', '\'', '<', '>', '(', ')', '[', ']')
            if (tkn.isEmpty() || tkn.contains("://") || tkn.contains('/')) continue
            if (isPackageId(tkn)) {
                pid = tkn.lowercase()
                pidToken = tkn
                break
            }
        }
        val commentName = comment?.replace(bracketedId, "")?.trim()?.ifEmpty { null }
        if (pid == null && ws == null) {
            // only a name (a list somebody typed): the mod is found by it, in the Mods folder or on the Workshop
            val bodyText = body.trim().let { if (it == "?") "" else it } // "?" stands for "no package id" in our own export
            val joined = listOfNotNull(bodyText.ifEmpty { null }, commentName).joinToString(" ").trim()
            if (joined.endsWith(":")) return null // a heading such as "Active mods:", not a mod
            val name = tidy(joined)
            if (!plausibleName(name)) return null
            return ListEntry("", name, null)
        }
        var name = commentName
        if (name == null) {
            // "brrainz.harmony  Harmony" or "Harmony (brrainz.harmony)": what stands next to the id is the name
            var rest = linkOrPath.replace(body, " ")
            rest = bracketedId.replace(rest, " ")
            if (ws != null) rest = rest.replace(ws.toString(), " ")
            if (pidToken != null) rest = rest.replace(pidToken, " ")
            val candidate = tidy(rest.replace(Regex("""[()\[\]<>]"""), " ").replace(Regex("""\s+"""), " "))
            if (plausibleName(candidate)) name = candidate
        }
        return ListEntry(pid.orEmpty(), name, ws)
    }
}

/** Matches list entries to installed mods. */
object ListApplier {

    /**
     * What to look for on the Workshop to find the mod of [entry], or null when there is nothing to look for with
     * (no name and no usable package id), when its Workshop id is known already, or when it is part of the game.
     */
    fun lookupFor(entry: ListEntry): NameLookup? {
        if (entry.workshopId != null) return null
        val id = entry.id.trim().lowercase()
        if (id.isNotEmpty() && ModelOps.isOfficialId(id)) return null
        val name = entry.name?.trim().orEmpty()
        if (NameMatch.squashed(name).length >= 2) return NameLookup(name, NameMatch.MIN_SCORE)
        val guess = if (id.isNotEmpty()) NameMatch.guessFromPackageId(id) else null
        return guess?.let { NameLookup(it, NameMatch.MIN_GUESS_SCORE) }
    }

    /** True when the mod of [entry] can be downloaded: its Workshop id is known, or it can be looked up by its name. */
    fun canDownload(entry: ListEntry): Boolean = entry.workshopId != null || lookupFor(entry) != null

    /**
     * Matches the entries of [list] to installed mods. With [skipOfficial] (the RimpyDroid folder, where the game is not
     * in a folder the app can see) entries for Core and the DLC are left to the game: not activated, not reported missing.
     */
    fun resolve(list: ModList, allMods: List<ModEntry>, skipOfficial: Boolean = false): ApplyResult {
        val index = ModIndex(allMods)
        val byWorkshop = HashMap<Long, ModEntry>()
        val byName = HashMap<String, ModEntry>()
        val sameName = HashSet<String>() // names that more than one installed mod has: no entry is matched by them
        for (m in allMods) {
            if (m.id.isEmpty()) continue
            val chosen = index[m.id] ?: m
            val w = m.workshopId
            if (w != null) byWorkshop.putIfAbsent(w, chosen)
            val key = NameMatch.squashed(m.name)
            if (key.isNotEmpty()) {
                val before = byName.putIfAbsent(key, chosen)
                if (before != null && before.id != chosen.id) sameName.add(key)
            }
        }
        val active = ArrayList<String>()
        val seen = HashSet<String>()
        val missing = ArrayList<ListEntry>()
        for (e in list.entries) {
            val id = e.id.trim().lowercase()
            var mod = (if (id.isNotEmpty()) index[id] else null) ?: e.workshopId?.let { byWorkshop[it] }
            if (mod == null && id.isEmpty() && e.workshopId == null) {
                // a list of names: the installed mod that has the name
                val key = NameMatch.squashed(e.name.orEmpty())
                if (key.isNotEmpty() && key !in sameName) mod = byName[key]
            }
            if (mod != null) {
                if (seen.add(mod.id)) active.add(mod.id)
            } else if (skipOfficial && id.isNotEmpty() && ModelOps.isOfficialId(id)) {
                // the game's own content: the game knows whether it has it
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
            // A copy of a file keeps the name written inside it. The list is then known by the name of its file, so
            // that every name is one file (the screen keys its rows by the name, and the same key twice would crash it).
            val own = fileFor(list.name).name.equals(f.name, ignoreCase = true)
            out.add(if (own) list else list.copy(name = f.nameWithoutExtension))
        }
        return out.sortedByDescending { it.savedAt }.distinctBy { it.name.lowercase() }
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
