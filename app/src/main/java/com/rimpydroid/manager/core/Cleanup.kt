package com.rimpydroid.manager.core

/** The kinds of mods a clean-up can remove. */
enum class CleanupKind {
    /** Mods that name the game versions they were made for, and yours is not among them. */
    INCOMPATIBLE,

    /** Mods that are not in the load order (what the Inactive tab shows). */
    INACTIVE,

    /** Extra copies of a mod that is installed in more than one folder. The copy the app uses is kept. */
    DUPLICATES,
}

/** Pure helpers for the "Clean up" tool: which mods would go, and what that would break. Nothing here touches the disk. */
object Cleanup {

    /**
     * True when the mod lists the game versions it supports and the running one is not among them.
     * A mod that lists no version at all is not called incompatible: nothing says it will not work.
     */
    fun isIncompatible(mod: ModEntry, game: GameVersion?): Boolean {
        if (game == null || mod.isOfficial) return false
        val supported = mod.meta.supportedVersions
        return supported.isNotEmpty() && game.short !in supported
    }

    /** The mods a clean-up of [kind] would delete, by name. Core and the DLCs are never in the result. */
    fun select(kind: CleanupKind, mods: List<ModEntry>, active: List<String>, game: GameVersion?): List<ModEntry> {
        val picked: List<ModEntry> = when (kind) {
            CleanupKind.INCOMPATIBLE -> mods.filter { isIncompatible(it, game) }

            CleanupKind.INACTIVE -> {
                // the same rule as the Inactive tab: everything that is not the chosen copy of an enabled mod
                val index = ModIndex(mods)
                val inUse = HashSet<String>()
                for (id in ActiveListOps.normalize(active)) {
                    val m = index[id]
                    if (m != null) inUse.add(m.key)
                }
                mods.filter { !it.isOfficial && it.key !in inUse }
            }

            CleanupKind.DUPLICATES -> {
                val index = ModIndex(mods)
                val extra = ArrayList<ModEntry>()
                for ((id, copies) in index.duplicates) {
                    val keep = index[id]?.key ?: continue
                    for (m in copies) {
                        if (m.key != keep && !m.isOfficial) extra.add(m)
                    }
                }
                extra
            }
        }
        return picked.sortedBy { it.name.lowercase() }
    }

    /** Package ids in the load order whose mod is incompatible with the game (those can be disabled instead of deleted). */
    fun incompatibleActiveIds(mods: List<ModEntry>, active: List<String>, game: GameVersion?): List<String> {
        if (game == null) return emptyList()
        val index = ModIndex(mods)
        return ActiveListOps.normalize(active).filter { id ->
            val m = index[id]
            m != null && isIncompatible(m, game)
        }
    }

    /**
     * Mods that stay but list one of the [deleted] mods as a requirement that no other installed mod provides.
     * Requirements that were already unmet before the clean-up are not counted.
     */
    fun brokenDependents(deleted: List<ModEntry>, all: List<ModEntry>): List<ModEntry> {
        val gone = HashSet<String>()
        val goneIds = HashSet<String>()
        for (m in deleted) {
            gone.add(m.key)
            if (m.id.isNotEmpty()) goneIds.add(m.id)
        }
        if (goneIds.isEmpty()) return emptyList()
        val remainingIds = HashSet<String>()
        for (m in all) {
            if (m.key !in gone && m.id.isNotEmpty()) remainingIds.add(m.id)
        }
        val out = ArrayList<ModEntry>()
        for (m in all) {
            if (m.key in gone || m.isOfficial) continue
            val broken = m.meta.dependencies.any { d ->
                d.allIds.any { it in goneIds } && d.allIds.none { it in remainingIds }
            }
            if (broken) out.add(m)
        }
        return out.sortedBy { it.name.lowercase() }
    }
}
