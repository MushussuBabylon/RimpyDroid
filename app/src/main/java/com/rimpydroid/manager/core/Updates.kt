package com.rimpydroid.manager.core

enum class UpdateState {
    UP_TO_DATE,
    UPDATE_AVAILABLE,

    /** Workshop mod that was not installed by this app: there is no record of its version. */
    UNTRACKED,

    /** Core, DLC and mods without a Workshop id. */
    NOT_WORKSHOP,

    /** Gone from the Workshop (removed, private or hidden). */
    UNAVAILABLE,
}

class UpdateInfo(val state: UpdateState, val item: WorkshopItem?)

object UpdateChecker {

    fun stateFor(mod: ModEntry, item: WorkshopItem?): UpdateState {
        if (mod.isOfficial || mod.workshopId == null) return UpdateState.NOT_WORKSHOP
        if (item == null || !item.isAvailable) return UpdateState.UNAVAILABLE
        val installed = mod.installedManifest
        val latest = item.manifest
        if (installed != null && latest != null) {
            return if (installed == latest) UpdateState.UP_TO_DATE else UpdateState.UPDATE_AVAILABLE
        }
        return UpdateState.UNTRACKED
    }

    /**
     * Asks Steam about every Workshop mod in [mods] (batched). Result is keyed by [ModEntry.key].
     * Throws IOException when Steam cannot be reached.
     */
    fun check(mods: List<ModEntry>): Map<String, UpdateInfo> {
        val ws = mods.filter { !it.isOfficial && it.workshopId != null }
        val items = WorkshopApi.getDetails(ws.mapNotNull { it.workshopId }.distinct()).associateBy { it.id }
        val out = LinkedHashMap<String, UpdateInfo>()
        for (m in mods) {
            val item = m.workshopId?.let { items[it] }
            out[m.key] = UpdateInfo(stateFor(m, item), item)
        }
        return out
    }
}
