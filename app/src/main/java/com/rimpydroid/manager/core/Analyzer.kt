package com.rimpydroid.manager.core

enum class Severity { ERROR, WARNING, INFO }

enum class IssueType {
    CORE_INACTIVE,
    MISSING_MOD,
    MISSING_DEPENDENCY,
    DEPENDENCY_INACTIVE,
    WRONG_ORDER,
    INCOMPATIBLE,
    OUTDATED_VERSION,
    DUPLICATE_INSTALL,
    DUPLICATE_ACTIVE,
    NO_PACKAGE_ID,
    BROKEN_ABOUT,
}

/** What one tap can do about an issue. */
enum class Fix { NONE, ACTIVATE, DOWNLOAD, SORT, DEACTIVATE, REMOVE_MISSING }

data class Issue(
    val severity: Severity,
    val type: IssueType,
    val modId: String, // lower-case package id of the mod the issue is about ("" = general)
    val modName: String,
    val message: String,
    val relatedIds: List<String> = emptyList(), // package ids involved (to activate, etc.)
    val workshopIds: List<Long> = emptyList(), // Workshop ids that would fix it (download)
    val fix: Fix = Fix.NONE,
)

object Analyzer {

    private const val CORE_ID = "ludeon.rimworld"

    /**
     * @param allMods everything found on disk (Core, DLC, Mods folder)
     * @param activeIds the current load order, lower-case package ids (unknown ids = missing mods)
     */
    fun analyze(
        allMods: List<ModEntry>,
        activeIds: List<String>,
        rules: CommunityRules,
        game: GameVersion?,
    ): List<Issue> {
        val index = ModIndex(allMods)
        val active = activeIds.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val activeSet = LinkedHashSet(active)
        val pos = HashMap<String, Int>()
        for ((i, id) in active.withIndex()) pos.putIfAbsent(id, i)

        val issues = ArrayList<Issue>()
        fun nameOf(id: String): String = index[id]?.name ?: ModelOps.officialName(id) ?: id

        // Core and the DLCs live with the game. When the game is not in a folder this app can see (RimDroid keeps it
        // in its own storage) none of them is found, and that says nothing about whether they are installed.
        val gameSeen = index.contains(CORE_ID)
        fun unknowable(id: String): Boolean = !gameSeen && ModelOps.isOfficialId(id)

        // what the mods say about mods that are not installed (names, Workshop links)
        val depInfo = HashMap<String, ModDependency>()
        for (m in allMods) for (d in m.meta.dependencies) depInfo.putIfAbsent(d.packageId, d)

        // ---- Core ---------------------------------------------------------------------------------
        if (CORE_ID !in activeSet) {
            issues.add(
                Issue(
                    Severity.ERROR, IssueType.CORE_INACTIVE, CORE_ID, "Core",
                    "Core is not in the active list. RimWorld needs it.",
                    relatedIds = if (index.contains(CORE_ID)) listOf(CORE_ID) else emptyList(),
                    fix = if (index.contains(CORE_ID)) Fix.ACTIVATE else Fix.NONE,
                ),
            )
        }

        // ---- the same id twice in the list --------------------------------------------------------
        val seen = HashSet<String>()
        val reported = HashSet<String>()
        for (id in active) {
            if (!seen.add(id) && reported.add(id)) {
                issues.add(
                    Issue(
                        Severity.WARNING, IssueType.DUPLICATE_ACTIVE, id, nameOf(id),
                        "Appears more than once in the active list.",
                        fix = Fix.SORT,
                    ),
                )
            }
        }

        // ---- missing mods ----------------------------------------------------------------------------
        for (id in activeSet) {
            if (index.contains(id) || unknowable(id)) continue
            val info = depInfo[id]
            val ws = info?.workshopId
            issues.add(
                Issue(
                    Severity.WARNING, IssueType.MISSING_MOD, id, info?.displayName ?: id,
                    "Is in the active list but not installed." +
                        if (ws != null) " It can be downloaded from the Workshop." else "",
                    workshopIds = listOfNotNull(ws),
                    fix = if (ws != null) Fix.DOWNLOAD else Fix.REMOVE_MISSING,
                ),
            )
        }

        val activeMods = ArrayList<ModEntry>()
        for (id in activeSet) index[id]?.let { activeMods.add(it) }

        // ---- dependencies --------------------------------------------------------------------------
        for (m in activeMods) {
            for (d in m.meta.dependencies) {
                val ids = d.allIds
                if (ids.any { it in activeSet && (index.contains(it) || unknowable(it)) }) continue
                val label = d.displayName ?: nameOf(d.packageId)
                val installed = ids.firstOrNull { index.contains(it) }
                if (installed != null) {
                    issues.add(
                        Issue(
                            Severity.ERROR, IssueType.DEPENDENCY_INACTIVE, m.id, m.name,
                            "Requires \"${index[installed]?.name ?: label}\", which is installed but not enabled.",
                            relatedIds = listOf(installed),
                            fix = Fix.ACTIVATE,
                        ),
                    )
                } else {
                    val ws = d.workshopId
                    val official = d.packageId.startsWith("ludeon.rimworld")
                    issues.add(
                        Issue(
                            Severity.ERROR, IssueType.MISSING_DEPENDENCY, m.id, m.name,
                            if (official && !gameSeen) "Requires \"$label\" (DLC), which is not in the load order."
                            else if (official) "Requires \"$label\" (DLC), which was not found in the game folder."
                            else "Requires \"$label\", which is not installed." +
                                if (ws != null) " It can be downloaded from the Workshop." else "",
                            relatedIds = listOf(d.packageId),
                            workshopIds = listOfNotNull(ws),
                            fix = if (ws != null) Fix.DOWNLOAD else Fix.NONE,
                        ),
                    )
                }
            }
        }

        // ---- order ---------------------------------------------------------------------------------
        val edges = LoadOrderGraph.edges(activeMods, rules)
        data class Wrong(val owner: String, val after: Boolean, val author: Boolean)
        val wrong = LinkedHashMap<Wrong, MutableList<String>>()
        for (e in edges) {
            val pf = pos[e.from] ?: continue
            val pt = pos[e.to] ?: continue
            if (pf < pt) continue
            // the owner states "I want to be after X" (owner == to) or "before X" (owner == from)
            val ownerIsTo = e.owner == e.to
            val other = if (ownerIsTo) e.from else e.to
            val author = e.kind != EdgeKind.COMMUNITY
            wrong.getOrPut(Wrong(e.owner, ownerIsTo, author)) { ArrayList() }.add(other)
        }
        for ((w, others) in wrong) {
            val names = others.distinct().map { nameOf(it) }
            val shown = names.take(4).joinToString(", ") { "\"$it\"" } +
                if (names.size > 4) " and ${names.size - 4} more" else ""
            val dir = if (w.after) "after" else "before"
            issues.add(
                Issue(
                    Severity.WARNING, IssueType.WRONG_ORDER, w.owner, nameOf(w.owner),
                    (if (w.author) "Must load $dir " else "Community rules say it should load $dir ") + shown + ".",
                    relatedIds = others.distinct(),
                    fix = Fix.SORT,
                ),
            )
        }

        // ---- incompatibilities ----------------------------------------------------------------------
        val pairs = HashSet<String>()
        fun pairKey(a: String, b: String) = if (a < b) "$a|$b" else "$b|$a"
        for (m in activeMods) {
            for (other in m.meta.incompatibleWith) {
                if (other == m.id || other !in activeSet || !index.contains(other)) continue
                if (!pairs.add(pairKey(m.id, other))) continue
                issues.add(
                    Issue(
                        Severity.ERROR, IssueType.INCOMPATIBLE, m.id, m.name,
                        "Declares itself incompatible with \"${nameOf(other)}\", and both are enabled.",
                        relatedIds = listOf(other),
                        fix = Fix.DEACTIVATE,
                    ),
                )
            }
        }
        for (m in activeMods) {
            val inc = rules.incompatible[m.id] ?: continue
            for ((other, comment) in inc) {
                if (other == m.id || other !in activeSet || !index.contains(other)) continue
                if (!pairs.add(pairKey(m.id, other))) continue
                issues.add(
                    Issue(
                        Severity.WARNING, IssueType.INCOMPATIBLE, m.id, m.name,
                        "Community rules list it as incompatible with \"${nameOf(other)}\"." +
                            if (comment.isNotBlank()) " Note: " + comment.trim() else "",
                        relatedIds = listOf(other),
                        fix = Fix.DEACTIVATE,
                    ),
                )
            }
        }

        // ---- game version ----------------------------------------------------------------------------
        if (game != null) {
            for (m in activeMods) {
                if (m.isOfficial) continue
                val supported = m.meta.supportedVersions
                if (supported.isEmpty()) {
                    issues.add(
                        Issue(
                            Severity.INFO, IssueType.OUTDATED_VERSION, m.id, m.name,
                            "Does not list any supported game version.",
                        ),
                    )
                } else if (game.short !in supported) {
                    issues.add(
                        Issue(
                            Severity.WARNING, IssueType.OUTDATED_VERSION, m.id, m.name,
                            "Made for RimWorld ${supported.joinToString(", ")}; you run ${game.short}. It may not work.",
                        ),
                    )
                }
            }
        }

        // ---- the same package id in several folders -----------------------------------------------------
        for ((id, list) in index.duplicates) {
            val isActive = id in activeSet
            val chosen = index[id]
            val others = list.filter { it !== chosen }.joinToString(", ") { it.dir.name }
            issues.add(
                Issue(
                    if (isActive) Severity.WARNING else Severity.INFO, IssueType.DUPLICATE_INSTALL, id, nameOf(id),
                    "Installed in ${list.size} folders (using \"${chosen?.dir?.name}\", also: $others). Delete the copies you do not need.",
                ),
            )
        }

        // ---- broken metadata ---------------------------------------------------------------------------
        for (m in allMods) {
            if (m.id.isEmpty()) {
                issues.add(
                    Issue(
                        Severity.INFO, IssueType.NO_PACKAGE_ID, "", m.name,
                        "\"${m.dir.name}\": About.xml has no packageId, so the mod cannot be put in a load order.",
                    ),
                )
            }
            val w = m.meta.warning
            if (w != null && (m.id.isEmpty() || m.id in activeSet)) {
                issues.add(Issue(Severity.INFO, IssueType.BROKEN_ABOUT, m.id, m.name, w))
            }
        }

        return issues.sortedWith(
            compareBy<Issue> { it.severity.ordinal }
                .thenBy { pos[it.modId] ?: Int.MAX_VALUE }
                .thenBy { it.type.ordinal },
        )
    }

    fun byMod(issues: List<Issue>): Map<String, List<Issue>> = issues.filter { it.modId.isNotEmpty() }.groupBy { it.modId }

    fun worst(issues: List<Issue>?): Severity? = issues?.minByOrNull { it.severity.ordinal }?.severity
}
