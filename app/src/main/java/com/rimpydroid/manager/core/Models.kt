package com.rimpydroid.manager.core

import java.io.File

/** One entry of <modDependencies> in About.xml. */
data class ModDependency(
    val packageId: String, // lower-case
    val displayName: String?,
    val steamWorkshopUrl: String?,
    val downloadUrl: String?,
    /** Other package ids that satisfy this dependency as well (<alternativePackageIds>), lower-case. */
    val alternatives: List<String> = emptyList(),
) {
    /** Workshop id parsed from steamWorkshopUrl (steam://url/CommunityFilePage/123 or a web link). */
    val workshopId: Long? get() = WorkshopUtil.parseSingleId(steamWorkshopUrl)

    /** The package id plus all alternatives. */
    val allIds: List<String> get() = listOf(packageId) + alternatives
}

/** What we read from a mod's About/About.xml, already merged for the current game version. */
data class ModMeta(
    val name: String,
    val authors: String,
    val packageId: String, // original case, "" if the mod has none
    val description: String,
    val url: String,
    val modVersion: String,
    val supportedVersions: List<String>, // normalized to "1.6"
    val dependencies: List<ModDependency>,
    val loadBefore: List<String>, // lower-case package ids (loadBefore + forceLoadBefore)
    val loadAfter: List<String>, // lower-case package ids (loadAfter + forceLoadAfter)
    val incompatibleWith: List<String>,
    val warning: String? = null, // set when About.xml was malformed and read in tolerant mode
)

enum class ModSource { OFFICIAL, LOCAL }

/** A mod (or Core / DLC) found on disk. */
data class ModEntry(
    val dir: File,
    val meta: ModMeta,
    val source: ModSource,
    val workshopId: Long?,
    val installedManifest: String?, // manifest id installed by RimpyDroid (null if unknown)
    val lastModified: Long,
    val previewFile: File?,
) {
    /** Lower-case package id; this is what ModsConfig.xml stores. */
    val id: String = meta.packageId.trim().lowercase()

    val name: String get() = if (meta.name.isBlank()) dir.name else meta.name

    val isOfficial: Boolean get() = source == ModSource.OFFICIAL

    val key: String get() = dir.absolutePath
}

/**
 * Picks one entry per package id. When the same package id exists in several folders
 * (for example a local copy and a Workshop copy) the official one wins, then the newest.
 */
class ModIndex(mods: List<ModEntry>) {
    val byId: Map<String, ModEntry>
    val duplicates: Map<String, List<ModEntry>>

    init {
        val groups = LinkedHashMap<String, MutableList<ModEntry>>()
        for (m in mods) {
            if (m.id.isEmpty()) continue
            groups.getOrPut(m.id) { ArrayList() }.add(m)
        }
        val chosen = LinkedHashMap<String, ModEntry>()
        val dups = LinkedHashMap<String, List<ModEntry>>()
        for ((id, list) in groups) {
            val best = list.sortedWith(
                compareByDescending<ModEntry> { it.isOfficial }
                    .thenByDescending { it.lastModified }
                    .thenBy { it.key },
            ).first()
            chosen[id] = best
            if (list.size > 1) dups[id] = list
        }
        byId = chosen
        duplicates = dups
    }

    operator fun get(id: String): ModEntry? = byId[id]

    fun contains(id: String): Boolean = byId.containsKey(id)
}
