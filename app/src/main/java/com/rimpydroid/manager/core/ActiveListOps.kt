package com.rimpydroid.manager.core

/** Pure helpers for editing the active mod list (package ids in load order). Every function returns a new list. */
object ActiveListOps {

    fun key(id: String): String = id.trim().lowercase()

    /** Lower-cased, trimmed, without blanks and duplicates (first occurrence wins). */
    fun normalize(ids: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (raw in ids) {
            val k = key(raw)
            if (k.isNotEmpty()) seen.add(k)
        }
        return seen.toList()
    }

    /** Adds [id] at the end (or at [position]) unless it is already active. */
    fun activate(list: List<String>, id: String, position: Int? = null): List<String> {
        val k = key(id)
        if (k.isEmpty() || list.contains(k)) return list
        val out = list.toMutableList()
        if (position == null || position >= out.size) out.add(k) else out.add(maxOf(0, position), k)
        return out
    }

    class WithDependencies(val list: List<String>, val added: List<String>)

    /**
     * Enables [id] and every installed-but-disabled mod it needs (recursively), dependencies first.
     * Dependencies that are not installed are ignored here; the issue checker reports them.
     */
    fun activateWithDependencies(list: List<String>, id: String, index: ModIndex): WithDependencies {
        val active = LinkedHashSet(list)
        val added = ArrayList<String>()

        fun visit(modId: String, depth: Int) {
            if (depth > 50) return
            val mod = index[modId]
            if (mod != null) {
                for (d in mod.meta.dependencies) {
                    val ids = d.allIds
                    if (ids.any { it in active }) continue
                    val installed = ids.firstOrNull { index.contains(it) } ?: continue
                    visit(installed, depth + 1)
                }
            }
            if (active.add(modId)) added.add(modId)
        }
        visit(key(id), 0)

        val out = ArrayList(list)
        for (a in added) if (!out.contains(a)) out.add(a)
        return WithDependencies(out, added)
    }

    fun deactivate(list: List<String>, id: String): List<String> {
        val k = key(id)
        return if (list.contains(k)) list.filter { it != k } else list
    }

    fun deactivateAll(list: List<String>, ids: Collection<String>): List<String> {
        val drop = ids.map { key(it) }.toSet()
        return list.filter { it !in drop }
    }

    /** Moves [id] so that it ends up at [toIndex] of the resulting list (clamped). */
    fun moveTo(list: List<String>, id: String, toIndex: Int): List<String> {
        val k = key(id)
        if (!list.contains(k)) return list
        val out = list.filter { it != k }.toMutableList()
        out.add(toIndex.coerceIn(0, out.size), k)
        return out
    }

    fun moveBy(list: List<String>, id: String, delta: Int): List<String> {
        val i = list.indexOf(key(id))
        if (i < 0) return list
        return moveTo(list, id, i + delta)
    }

    fun moveAfter(list: List<String>, id: String, anchor: String): List<String> {
        val k = key(id)
        val a = key(anchor)
        if (k == a || !list.contains(k) || !list.contains(a)) return list
        val out = list.filter { it != k }.toMutableList()
        out.add(out.indexOf(a) + 1, k)
        return out
    }

    fun moveBefore(list: List<String>, id: String, anchor: String): List<String> {
        val k = key(id)
        val a = key(anchor)
        if (k == a || !list.contains(k) || !list.contains(a)) return list
        val out = list.filter { it != k }.toMutableList()
        out.add(out.indexOf(a), k)
        return out
    }
}
