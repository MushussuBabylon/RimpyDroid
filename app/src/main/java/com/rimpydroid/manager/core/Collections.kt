package com.rimpydroid.manager.core

import java.io.File

/** A mod of a collection: its Workshop id and the title it had when the collection was last read. */
data class CollectionMember(val id: Long, val title: String)

/**
 * A Workshop collection that was downloaded (or added by its link), kept as one unit so that it can be updated,
 * applied or deleted on its own. [members] are the mods of the collection, nested collections already unfolded.
 */
data class CollectionRecord(
    val id: Long,
    val title: String,
    val members: List<CollectionMember>,
    val addedAt: Long,
    val updatedAt: Long,
) {
    val memberIds: List<Long> get() = members.map { it.id }
    val url: String get() = "https://steamcommunity.com/sharedfiles/filedetails/?id=$id"
}

/** How a collection stands against what is on the phone. */
data class CollectionStatus(val total: Int, val installed: Int, val active: Int, val updates: Int) {
    val missing: Int get() = total - installed
}

/** What changed in a collection since it was last read. */
data class CollectionDiff(val added: List<CollectionMember>, val removed: List<CollectionMember>) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty()
}

object CollectionCodec {

    const val FORMAT = "rimpydroid-collection"

    fun toJson(r: CollectionRecord): String {
        val root = LinkedHashMap<String, Any?>()
        root["format"] = FORMAT
        root["version"] = 1
        root["id"] = r.id.toString()
        root["title"] = r.title
        root["addedAt"] = r.addedAt
        root["updatedAt"] = r.updatedAt
        val mods = ArrayList<Any?>()
        for (m in r.members) {
            val o = LinkedHashMap<String, Any?>()
            o["id"] = m.id.toString()
            if (m.title.isNotBlank()) o["title"] = m.title
            mods.add(o)
        }
        root["mods"] = mods
        return Json.stringify(root, pretty = true)
    }

    fun fromJson(text: String): CollectionRecord? {
        val root = Json.parseOrNull(text).asObj() ?: return null
        val id = root["id"].asLong()?.takeIf { it > 0 } ?: return null
        val mods = root["mods"].asArr() ?: return null
        val members = ArrayList<CollectionMember>()
        val seen = HashSet<Long>()
        for (m in mods) {
            val o = m.asObj() ?: continue
            val memberId = o["id"].asLong()?.takeIf { it > 0 } ?: continue
            if (seen.add(memberId)) members.add(CollectionMember(memberId, o["title"].asStr().orEmpty()))
        }
        val title = root["title"].asStr()?.takeIf { it.isNotBlank() } ?: id.toString()
        return CollectionRecord(id, title, members, root["addedAt"].asLong() ?: 0L, root["updatedAt"].asLong() ?: 0L)
    }
}

/** The collections that were downloaded, one JSON file each, named by the id of the collection. */
class CollectionStore(private val dir: File) {

    /** Every collection, the most recently updated first. The same collection is never listed twice. */
    fun all(): List<CollectionRecord> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        val byId = LinkedHashMap<Long, CollectionRecord>()
        for (f in files) {
            val text = FileUtil.readTextOrNull(f, 8 * 1024 * 1024) ?: continue
            val record = CollectionCodec.fromJson(text) ?: continue
            // a copied file holds the same collection again: the screen keys its rows by the id, so keep one
            val before = byId[record.id]
            if (before == null || record.updatedAt > before.updatedAt) byId[record.id] = record
        }
        return byId.values.sortedWith(compareByDescending<CollectionRecord> { it.updatedAt }.thenBy { it.title.lowercase() })
    }

    fun get(id: Long): CollectionRecord? {
        val text = FileUtil.readTextOrNull(fileFor(id), 8 * 1024 * 1024) ?: return null
        return CollectionCodec.fromJson(text)
    }

    fun save(record: CollectionRecord) {
        if (!dir.exists()) dir.mkdirs()
        FileUtil.writeTextAtomic(fileFor(record.id), CollectionCodec.toJson(record))
    }

    fun delete(id: Long): Boolean {
        val f = fileFor(id)
        return !f.exists() || f.delete()
    }

    private fun fileFor(id: Long): File = File(dir, "$id.json")
}

object CollectionOps {

    /** How [record] stands: [installed] maps Workshop ids to the mods on disk, [activeIds] is the load order. */
    fun status(
        record: CollectionRecord,
        installed: Map<Long, ModEntry>,
        activeIds: Set<String>,
        updateAvailable: (ModEntry) -> Boolean,
    ): CollectionStatus {
        var have = 0
        var active = 0
        var updates = 0
        for (m in record.members) {
            val mod = installed[m.id] ?: continue
            have++
            if (mod.id.isNotEmpty() && mod.id in activeIds) active++
            if (updateAvailable(mod)) updates++
        }
        return CollectionStatus(record.members.size, have, active, updates)
    }

    /** The mods of [record] that no other collection of [others] has as well: they go with it when it is deleted. */
    fun exclusiveMembers(record: CollectionRecord, others: Collection<CollectionRecord>): List<Long> {
        val shared = HashSet<Long>()
        for (o in others) {
            if (o.id != record.id) shared.addAll(o.memberIds)
        }
        return record.memberIds.filter { it !in shared }
    }

    /** Harmony's package id, and its Workshop id for a copy whose About.xml cannot be read. */
    const val HARMONY_ID = "brrainz.harmony"
    const val HARMONY_WORKSHOP_ID = 2009463077L

    /**
     * A mod that is never deleted together with a collection: Harmony, the library that most other mods cannot work
     * without. Taking it out with a collection that happens to hold it breaks every mod of the other collections and
     * of the load order, so only a person who deletes it on its own page gets rid of it.
     */
    fun isProtected(mod: ModEntry): Boolean = mod.id == HARMONY_ID || mod.workshopId == HARMONY_WORKSHOP_ID

    /** [delete] goes with the collection, [kept] would have gone but is protected (see [isProtected]). */
    class DeletePlan(val delete: List<ModEntry>, val kept: List<ModEntry>)

    /**
     * The installed mods that "Delete mods too" removes with [record]: the ones no other collection of [all] has,
     * without the protected ones. [installed] is the installed Workshop mods by Workshop id.
     */
    fun planDeletion(record: CollectionRecord, all: Collection<CollectionRecord>, installed: Map<Long, ModEntry>): DeletePlan {
        val own = exclusiveMembers(record, all).mapNotNull { installed[it] }.distinctBy { it.key }
        return DeletePlan(own.filter { !isProtected(it) }, own.filter { isProtected(it) })
    }

    /** What [fresh] has that [old] did not have, and what it no longer has. */
    fun diff(old: CollectionRecord, fresh: List<CollectionMember>): CollectionDiff {
        val before = old.memberIds.toSet()
        val after = fresh.map { it.id }.toSet()
        return CollectionDiff(
            added = fresh.filter { it.id !in before },
            removed = old.members.filter { it.id !in after },
        )
    }

    /** [record] as a list that [ListApplier] can apply: its mods by Workshop id, in the order of the collection. */
    fun asList(record: CollectionRecord): ModList = ModList(
        record.title,
        record.members.map { ListEntry("", it.title.ifBlank { null }, it.id) },
        null,
        record.updatedAt,
    )
}
