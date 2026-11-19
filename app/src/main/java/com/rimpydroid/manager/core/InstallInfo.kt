package com.rimpydroid.manager.core

import java.io.File

/**
 * Bookkeeping for mods that RimpyDroid downloaded: a tiny JSON file in the mod folder, plus a
 * reader for the depot.config that JavaSteam's DepotDownloader keeps in ".DepotDownloader".
 */
object InstallInfo {

    const val FILE_NAME = ".rimpydroid.json"

    data class Info(
        val workshopId: Long?,
        val manifest: String?,
        val timeUpdated: Long?,
        val title: String?,
    )

    fun read(modDir: File): Info? {
        val f = File(modDir, FILE_NAME)
        val text = FileUtil.readTextOrNull(f, 64 * 1024) ?: return null
        val obj = Json.parseOrNull(text).asObj() ?: return null
        return Info(
            workshopId = obj["workshopId"].asLong(),
            manifest = obj["manifest"].asStr()?.ifBlank { null },
            timeUpdated = obj["timeUpdated"].asLong(),
            title = obj["title"].asStr(),
        )
    }

    fun write(modDir: File, info: Info) {
        val map = LinkedHashMap<String, Any?>()
        map["workshopId"] = info.workshopId?.toString()
        map["manifest"] = info.manifest
        map["timeUpdated"] = info.timeUpdated
        map["title"] = info.title
        map["installedAt"] = System.currentTimeMillis()
        try {
            FileUtil.writeTextAtomic(File(modDir, FILE_NAME), Json.stringify(map, pretty = true))
        } catch (e: Exception) {
            // not fatal: update detection falls back to depot.config
        }
    }

    /** Manifest id recorded by DepotDownloader (".DepotDownloader/depot.config"), as unsigned decimal. */
    fun depotConfigManifest(modDir: File): String? {
        val f = File(File(modDir, ".DepotDownloader"), "depot.config")
        val text = FileUtil.readTextOrNull(f, 64 * 1024) ?: return null
        val obj = Json.parseOrNull(text).asObj() ?: return null
        val ids = obj["installedManifestIDs"].asObj() ?: return null
        for (v in ids.values) {
            val l = v.asLong() ?: continue
            // DepotDownloader writes INVALID (Long.MAX_VALUE) while a download is unfinished.
            if (l == Long.MAX_VALUE || l == 0L) continue
            return java.lang.Long.toUnsignedString(l)
        }
        return null
    }

    /** Best known installed manifest: our own record first, then depot.config. */
    fun installedManifest(modDir: File): String? {
        return read(modDir)?.manifest ?: depotConfigManifest(modDir)
    }
}
