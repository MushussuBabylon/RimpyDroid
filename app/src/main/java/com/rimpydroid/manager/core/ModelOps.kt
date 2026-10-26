package com.rimpydroid.manager.core

import java.io.File

/** A download the UI asks for; the service layer turns it into a queue entry. */
data class DownloadPlan(
    val workshopId: Long,
    val title: String,
    val installDir: File,
    val isUpdate: Boolean,
    val expectedManifest: String?,
    val timeUpdated: Long?,
    val sizeHint: Long,
)

/** One line of the mod list (active or inactive). [mod] is null for an id that is active but not installed. */
class ModRow(
    /** Lower-case package id, "" when the mod has none. */
    val id: String,
    /** Unique key: the mod folder, or "missing:<id>". */
    val key: String,
    val mod: ModEntry?,
    val active: Boolean,
    /** 1-based place in the load order, 0 when inactive. */
    val position: Int,
    val severity: Severity?,
    val issueCount: Int,
    val update: UpdateState?,
    /** Another folder with the same package id exists. */
    val duplicate: Boolean,
    /** False for mods without a package id and for Core (RimWorld always loads it). */
    val canToggle: Boolean,
    val searchText: String,
    /**
     * Core or a DLC that is in the load order while the game itself is not in a folder this app can see
     * (RimDroid keeps it in its own storage). It cannot be scanned, but it is not missing either.
     */
    val unseenOfficial: Boolean = false,
) {
    val missing: Boolean get() = mod == null
    val name: String get() = mod?.name ?: ModelOps.officialName(id) ?: id
}

class RowSet(val active: List<ModRow>, val inactive: List<ModRow>)

class SortOutcome(
    val order: List<String>,
    val warnings: List<String>,
    val ignoredRules: Int,
    val changed: Boolean,
)

class UpdateSummary(val available: Int, val untracked: Int, val unavailable: Int, val upToDate: Int)

/** Pure helpers between the file/network layer and the screens. */
object ModelOps {

    const val CORE_ID = "ludeon.rimworld"

    /** Core and the DLCs (package ids `ludeon.rimworld` and `ludeon.rimworld.<name>`): they come with the game. */
    fun isOfficialId(id: String): Boolean = id == CORE_ID || id.startsWith("$CORE_ID.")

    /** "Core", "Royalty", … for an official package id; null for any other id. */
    fun officialName(id: String): String? {
        if (!isOfficialId(id)) return null
        if (id == CORE_ID) return "Core"
        val last = id.substringAfterLast('.')
        return last.replaceFirstChar { it.uppercaseChar() }
    }

    /**
     * Stands in for Core or a DLC that is in the load order but has no folder to scan: the game is not in a folder
     * this app can see (RimDroid keeps it in its own storage). The sorter can then place it like the real thing.
     */
    internal fun officialStandIn(id: String): ModEntry = ModEntry(
        dir = File("/official-content/$id"), // never read: only a unique key
        meta = ModMeta(
            name = officialName(id) ?: id, authors = "Ludeon Studios", packageId = id, description = "", url = "",
            modVersion = "", supportedVersions = emptyList(), dependencies = emptyList(),
            loadBefore = emptyList(), loadAfter = emptyList(), incompatibleWith = emptyList(),
        ),
        source = ModSource.OFFICIAL,
        workshopId = null,
        installedManifest = null,
        lastModified = 0L,
        previewFile = null,
    )

    /** The load order of a fresh install: Core and the DLC the game folder contains. */
    fun initialActive(mods: List<ModEntry>, rules: CommunityRules): List<String> {
        val official = mods.filter { it.isOfficial && it.id.isNotEmpty() }
        if (official.isEmpty()) return emptyList()
        return Sorter.sort(official, rules, Sorter.TieBreak.ALPHABETICAL).order
    }

    /**
     * Sorts the active list. Ids that are not installed stay in the list (behind the sorted mods) so that
     * the user can still see and fix them. Core and the DLCs are the exception: they belong to the game, which
     * this app may not be able to see, so they are sorted like installed ones and stay at the top.
     */
    fun sortActive(
        mods: List<ModEntry>,
        active: List<String>,
        rules: CommunityRules,
        keepCurrent: Boolean,
    ): SortOutcome {
        val index = ModIndex(mods)
        val normalized = ActiveListOps.normalize(active)
        val installed = ArrayList<ModEntry>()
        val missing = ArrayList<String>()
        for (id in normalized) {
            val m = index[id]
            when {
                m != null -> installed.add(m)
                isOfficialId(id) -> installed.add(officialStandIn(id))
                else -> missing.add(id)
            }
        }
        val tie = if (keepCurrent) Sorter.TieBreak.KEEP_CURRENT else Sorter.TieBreak.ALPHABETICAL
        val res = Sorter.sort(installed, rules, tie)
        val order = res.order + missing
        return SortOutcome(order, res.warnings, res.ignoredRules, order != normalized)
    }

    fun buildRows(
        mods: List<ModEntry>,
        active: List<String>,
        issues: List<Issue>,
        updates: Map<String, UpdateInfo>,
    ): RowSet {
        val index = ModIndex(mods)
        val byMod = Analyzer.byMod(issues)
        val activeIds = ActiveListOps.normalize(active)
        val used = HashSet<String>()

        // Core is found whenever the game folder is known; without it Core and the DLCs cannot be scanned
        val gameSeen = index.contains(CORE_ID)
        val activeRows = ArrayList<ModRow>(activeIds.size)
        for ((i, id) in activeIds.withIndex()) {
            val m = index[id]
            if (m != null) used.add(m.key)
            val unseen = m == null && !gameSeen && isOfficialId(id)
            activeRows.add(makeRow(id, m, true, i + 1, byMod[id], updates, index.duplicates.containsKey(id), unseen))
        }

        val inactiveRows = ArrayList<ModRow>()
        for (m in mods.sortedBy { it.name.lowercase() }) {
            if (m.key in used) continue
            val chosen = m.id.isNotEmpty() && index[m.id] === m
            val list = if (chosen) byMod[m.id] else null
            inactiveRows.add(makeRow(m.id, m, false, 0, list, updates, m.id.isNotEmpty() && index.duplicates.containsKey(m.id), false))
        }
        return RowSet(activeRows, inactiveRows)
    }

    private fun makeRow(
        id: String,
        mod: ModEntry?,
        active: Boolean,
        position: Int,
        issues: List<Issue>?,
        updates: Map<String, UpdateInfo>,
        duplicate: Boolean,
        unseenOfficial: Boolean,
    ): ModRow {
        val key = mod?.key ?: "missing:$id"
        val text = if (mod == null) {
            id + " " + (officialName(id) ?: "")
        } else {
            mod.name + " " + mod.meta.authors + " " + mod.id + " " + mod.dir.name
        }
        return ModRow(
            id = id,
            key = key,
            mod = mod,
            active = active,
            position = position,
            severity = Analyzer.worst(issues),
            issueCount = issues?.size ?: 0,
            update = mod?.let { updates[it.key]?.state },
            duplicate = duplicate,
            canToggle = mod != null && id.isNotEmpty() && id != CORE_ID,
            searchText = text.lowercase(),
            unseenOfficial = unseenOfficial,
        )
    }

    fun filter(rows: List<ModRow>, query: String): List<ModRow> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return rows
        return rows.filter { r -> words.all { r.searchText.contains(it) } }
    }

    // ---- downloads -----------------------------------------------------------------------------

    /**
     * Where an item goes: into the folder of the installed copy (update / repair) or into a new
     * folder named after the Workshop id, like Steam does.
     */
    fun plan(item: WorkshopItem, mods: List<ModEntry>, modsDir: File): DownloadPlan {
        val existing = mods.firstOrNull { !it.isOfficial && it.workshopId == item.id }
        val dir = existing?.dir ?: File(modsDir, item.id.toString())
        return DownloadPlan(
            workshopId = item.id,
            title = item.title.ifBlank { "Workshop item ${item.id}" },
            installDir = dir,
            isUpdate = existing != null,
            expectedManifest = item.manifest,
            timeUpdated = item.timeUpdated.takeIf { it > 0 },
            // an update only fetches what changed, the full size would be a false alarm for the space check
            sizeHint = if (existing != null) 0L else item.fileSize,
        )
    }

    fun plans(items: List<WorkshopItem>, mods: List<ModEntry>, modsDir: File): List<DownloadPlan> {
        val seen = HashSet<Long>()
        val out = ArrayList<DownloadPlan>()
        for (item in items) {
            if (!item.isAvailable || item.isCollection) continue
            if (seen.add(item.id)) out.add(plan(item, mods, modsDir))
        }
        return out
    }

    /** Mods whose Workshop item changed (and, when asked, mods with no version record). */
    fun updatePlans(
        mods: List<ModEntry>,
        updates: Map<String, UpdateInfo>,
        modsDir: File,
        includeUntracked: Boolean,
    ): List<DownloadPlan> {
        val items = ArrayList<WorkshopItem>()
        for (m in mods) {
            val info = updates[m.key] ?: continue
            val wanted = info.state == UpdateState.UPDATE_AVAILABLE ||
                (includeUntracked && info.state == UpdateState.UNTRACKED)
            val item = info.item
            if (wanted && item != null) items.add(item)
        }
        return plans(items, mods, modsDir)
    }

    fun summarize(updates: Map<String, UpdateInfo>): UpdateSummary {
        var available = 0
        var untracked = 0
        var unavailable = 0
        var upToDate = 0
        for (info in updates.values) {
            when (info.state) {
                UpdateState.UPDATE_AVAILABLE -> available++
                UpdateState.UNTRACKED -> untracked++
                UpdateState.UNAVAILABLE -> unavailable++
                UpdateState.UP_TO_DATE -> upToDate++
                UpdateState.NOT_WORKSHOP -> {}
            }
        }
        return UpdateSummary(available, untracked, unavailable, upToDate)
    }

    /** Workshop ids the issue list says would fix something (missing dependencies, missing mods). */
    fun downloadableIds(issues: List<Issue>): List<Long> =
        issues.filter { it.fix == Fix.DOWNLOAD }.flatMap { it.workshopIds }.distinct()

    // ---- fixing issues -----------------------------------------------------------------------------

    class FixResult(val active: List<String>, val note: String)

    /**
     * Applies the one-tap fixes that only change the active list (not downloads).
     * [deactivateId] chooses which side of an incompatibility to switch off.
     */
    fun applyFix(
        issue: Issue,
        active: List<String>,
        mods: List<ModEntry>,
        rules: CommunityRules,
        keepCurrent: Boolean,
        deactivateId: String? = null,
    ): FixResult? {
        val index = ModIndex(mods)
        return when (issue.fix) {
            Fix.ACTIVATE -> {
                var list = active
                var added = 0
                for (id in issue.relatedIds) {
                    val r = ActiveListOps.activateWithDependencies(list, id, index)
                    added += r.added.size
                    list = r.list
                }
                FixResult(list, if (added == 1) "Enabled 1 mod" else "Enabled $added mods")
            }
            Fix.SORT -> {
                val out = sortActive(mods, active, rules, keepCurrent)
                FixResult(out.order, "Load order sorted")
            }
            Fix.DEACTIVATE -> {
                val id = deactivateId ?: issue.modId
                FixResult(ActiveListOps.deactivate(active, id), "Disabled ${index[id]?.name ?: id}")
            }
            Fix.REMOVE_MISSING -> FixResult(ActiveListOps.deactivate(active, issue.modId), "Removed ${issue.modName} from the list")
            Fix.DOWNLOAD, Fix.NONE -> null
        }
    }

    /**
     * Applies every fix that only edits the list (enable needed mods, remove missing ones, sort once at the end).
     * Incompatibilities and downloads are left to the user.
     */
    fun applySafeFixes(
        issues: List<Issue>,
        active: List<String>,
        mods: List<ModEntry>,
        rules: CommunityRules,
        keepCurrent: Boolean,
    ): FixResult {
        var list = active
        var changes = 0
        var sort = false
        for (issue in issues) {
            when (issue.fix) {
                Fix.ACTIVATE, Fix.REMOVE_MISSING -> {
                    val r = applyFix(issue, list, mods, rules, keepCurrent) ?: continue
                    if (r.active != list) changes++
                    list = r.active
                }
                Fix.SORT -> sort = true
                else -> {}
            }
        }
        if (sort || changes > 0) {
            val s = sortActive(mods, list, rules, keepCurrent)
            if (s.changed) changes++
            list = s.order
        }
        return FixResult(list, if (changes == 0) "Nothing to fix automatically" else "Applied automatic fixes")
    }
}
