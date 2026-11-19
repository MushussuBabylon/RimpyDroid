package com.rimpydroid.manager.core

/**
 * RimWorld's expansions (DLC). When the game is in a folder this app can see, the DLC are found there. When it is not
 * (the RimpyDroid folder: RimDroid, Winlator and Gamehub keep the game in their own storage) nothing official can be
 * scanned, so the person says which DLC they own, in the welcome guide or in Settings, and the app treats those as
 * installed: they are part of the load order, they are written to ModsConfig.xml (`activeMods` and `knownExpansions`),
 * and a mod that needs one that is not chosen is reported.
 */
object OfficialDlc {

    class Dlc(val id: String, val name: String)

    /** The expansions in the order RimWorld loads them. */
    val ALL: List<Dlc> = listOf(
        Dlc("ludeon.rimworld.royalty", "Royalty"),
        Dlc("ludeon.rimworld.ideology", "Ideology"),
        Dlc("ludeon.rimworld.biotech", "Biotech"),
        Dlc("ludeon.rimworld.anomaly", "Anomaly"),
        Dlc("ludeon.rimworld.odyssey", "Odyssey"),
    )

    private val order: Map<String, Int> = ALL.withIndex().associate { (i, d) -> d.id to i }

    fun isDlcId(id: String): Boolean = ActiveListOps.key(id) in order

    /** [ids] reduced to known expansions: lower-case, without duplicates, in the order of [ALL]. */
    fun normalize(ids: Collection<String>): List<String> {
        val keys = ids.map { ActiveListOps.key(it) }.toSet()
        return ALL.map { it.id }.filter { it in keys }
    }

    /** Where the stand-ins for Core and the chosen DLC live (never read: it only makes their keys unique). */
    const val STAND_IN_DIR = "/official-content/"

    /** True for an entry made up by this app for Core or a DLC, not found in a game folder. */
    fun isStandIn(mod: ModEntry): Boolean = mod.dir.path.replace('\\', '/').startsWith(STAND_IN_DIR)

    /** True when the game folder was scanned: Core was really found there. */
    fun gameVisible(mods: List<ModEntry>): Boolean =
        mods.any { it.isOfficial && it.id == ModelOps.CORE_ID && !isStandIn(it) }

    /** The expansions that were really found in the game folder, in the order of [ALL]. */
    fun detected(mods: List<ModEntry>): List<String> =
        normalize(mods.filter { it.isOfficial && !isStandIn(it) }.map { it.id })

    /**
     * [mods] with Core and the DLC the person chose added, when they say what they own ([AppConfig.dlcChosen]) and the
     * game folder is not visible. A game folder that is scanned is believed over the choice.
     */
    fun withChosen(mods: List<ModEntry>, config: AppConfig): List<ModEntry> {
        if (!config.dlcChosen) return mods
        if (mods.any { it.isOfficial && it.id == ModelOps.CORE_ID && !isStandIn(it) }) return mods
        val have = mods.filter { it.isOfficial }.map { it.id }.toSet()
        val wanted = listOf(ModelOps.CORE_ID) + normalize(config.ownedDlc)
        val extra = wanted.filter { it !in have }.map { ModelOps.officialStandIn(it) }
        return if (extra.isEmpty()) mods else extra + mods
    }

    /**
     * The load order after the person changed which DLC they own, from [before] to [after]:
     *  - a DLC that is no longer owned leaves the list;
     *  - a DLC that is owned now and was not before joins it, right after Core and the DLC already there;
     *  - a DLC that was owned before and is not in the list was switched off on purpose and stays off.
     * Anything that is not a DLC the app knows is left exactly where it is.
     */
    fun applyChoice(
        active: List<String>,
        before: Collection<String>,
        after: Collection<String>,
        loadsBeforeCore: (String) -> Boolean = { false },
    ): List<String> {
        val was = normalize(before).toSet()
        val now = normalize(after).toSet()
        val list = ActiveListOps.normalize(active).filter { !isDlcId(it) || it in now }
        val joining = ALL.map { it.id }.filter { it in now && it !in was && it !in list }
        if (joining.isEmpty()) return list
        // a DLC cannot be loaded without Core in front of it (a mod that asks to load before Core, Harmony, keeps its place)
        val withCore = ActiveListOps.withCore(list, loadsBeforeCore).toMutableList()
        // after the last official entry (Core, or a DLC that is already there)
        var at = 0
        for ((i, id) in withCore.withIndex()) if (ModelOps.isOfficialId(id)) at = i + 1
        withCore.addAll(at, joining)
        return withCore
    }

    /** Tells whether a mod asks to load before Core, for [ActiveListOps.withCore]. */
    fun loadsBeforeCore(mods: List<ModEntry>): (String) -> Boolean {
        val index = ModIndex(mods)
        return { id -> index[id]?.meta?.loadBefore?.any { it.trim().equals(ModelOps.CORE_ID, ignoreCase = true) } == true }
    }

    /** The package ids a "known expansions" list should hold: what was there, then what is new, without duplicates. */
    fun mergeKnown(existing: List<String>, wanted: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<String>()
        for (id in existing + wanted) {
            val k = ActiveListOps.key(id)
            if (k.isNotEmpty() && seen.add(k)) out.add(id.trim())
        }
        return out
    }

    /** Package ids stored as one text ("a,b"), as the settings keep them. */
    fun parse(text: String?): List<String> = normalize(text.orEmpty().split(',', ';', ' ', '\n').filter { it.isNotBlank() })

    fun format(ids: Collection<String>): String = normalize(ids).joinToString(",")
}
