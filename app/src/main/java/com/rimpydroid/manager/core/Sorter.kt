package com.rimpydroid.manager.core

import java.util.PriorityQueue

enum class EdgeKind { DEPENDENCY, ABOUT, COMMUNITY }

/** [from] has to load before [to]. [owner] is the mod (or rule owner) the statement comes from. */
data class Edge(val from: String, val to: String, val kind: EdgeKind, val owner: String)

/** Ordering constraints between mods, shared by the sorter and the issue checker. */
object LoadOrderGraph {

    /** Edges between the given mods only (ids not in [mods] are ignored). */
    fun edges(mods: Collection<ModEntry>, rules: CommunityRules): List<Edge> {
        val ids = LinkedHashSet<String>()
        for (m in mods) if (m.id.isNotEmpty()) ids.add(m.id)
        val ordered = mods.filter { it.id.isNotEmpty() }.sortedBy { it.id }

        val out = ArrayList<Edge>()
        val seen = HashSet<String>()
        fun add(from: String, to: String, kind: EdgeKind, owner: String) {
            if (from == to || from !in ids || to !in ids) return
            if (seen.add(from + "\u0000" + to)) out.add(Edge(from, to, kind, owner))
        }

        // dependencies come first so that they win over weaker statements when rules conflict
        for (m in ordered) {
            for (d in m.meta.dependencies) {
                for (pid in d.allIds) add(pid, m.id, EdgeKind.DEPENDENCY, m.id)
            }
        }
        for (m in ordered) {
            for (x in m.meta.loadAfter) add(x, m.id, EdgeKind.ABOUT, m.id)
            for (x in m.meta.loadBefore) add(m.id, x, EdgeKind.ABOUT, m.id)
        }
        for (m in ordered) {
            rules.loadAfter[m.id]?.sorted()?.forEach { add(it, m.id, EdgeKind.COMMUNITY, m.id) }
            rules.loadBefore[m.id]?.sorted()?.forEach { add(m.id, it, EdgeKind.COMMUNITY, m.id) }
        }
        return out
    }
}

/**
 * Automatic load order: a topological sort of the constraints (dependencies, About.xml
 * loadBefore/loadAfter, community rules) in which, among the mods that are free to go next,
 * Harmony, Core and the DLCs come first, then top-rules and libraries, then everything else
 * alphabetically, and bottom-rules last.
 */
object Sorter {

    enum class TieBreak { ALPHABETICAL, KEEP_CURRENT }

    class Result(
        val order: List<String>, // package ids (lower-case), all of the input mods
        val warnings: List<String>,
        val ignoredRules: Int,
    )

    private const val RANK_HARMONY = 0
    private const val RANK_CORE = 10
    private const val RANK_DLC = 20
    private const val RANK_TOP = 30
    private const val RANK_LIBRARY = 40
    private const val RANK_NORMAL = 50
    private const val RANK_BOTTOM = 60

    private val dlcOrder = listOf(
        "ludeon.rimworld.royalty",
        "ludeon.rimworld.ideology",
        "ludeon.rimworld.biotech",
        "ludeon.rimworld.anomaly",
        "ludeon.rimworld.odyssey",
    )

    private val libraryWords = Regex("""(^|[^a-z])(lib|library|libraries|framework)([^a-z]|$)|hugslib""")

    /**
     * @param mods the mods to sort, in their current order (duplicates by package id are ignored,
     *   mods without a package id are skipped)
     */
    fun sort(
        mods: List<ModEntry>,
        rules: CommunityRules,
        tieBreak: TieBreak = TieBreak.ALPHABETICAL,
    ): Result {
        // unique mods in current order
        val unique = ArrayList<ModEntry>()
        val seenIds = HashSet<String>()
        for (m in mods) {
            if (m.id.isEmpty()) continue
            if (seenIds.add(m.id)) unique.add(m)
        }
        val n = unique.size
        if (n == 0) return Result(emptyList(), emptyList(), 0)

        val indexOf = HashMap<String, Int>(n * 2)
        for ((i, m) in unique.withIndex()) indexOf[m.id] = i

        // ---- constraints -> acyclic graph --------------------------------------------------------
        val succ = Array(n) { ArrayList<Int>() }
        val warnings = ArrayList<String>()
        var ignored = 0
        val visit = IntArray(n)
        var stamp = 0

        fun reaches(start: Int, target: Int): Boolean {
            stamp++
            val stack = ArrayList<Int>()
            stack.add(start)
            visit[start] = stamp
            while (stack.isNotEmpty()) {
                val v = stack.removeAt(stack.size - 1)
                if (v == target) return true
                for (w in succ[v]) {
                    if (visit[w] != stamp) {
                        visit[w] = stamp
                        stack.add(w)
                    }
                }
            }
            return false
        }

        val edges = LoadOrderGraph.edges(unique, rules)
        for (e in edges) {
            val u = indexOf[e.from] ?: continue
            val v = indexOf[e.to] ?: continue
            if (reaches(v, u)) {
                ignored++
                if (e.kind != EdgeKind.COMMUNITY && warnings.size < 30) {
                    warnings.add(
                        "Conflicting rules: \"${unique[u].name}\" should load before \"${unique[v].name}\", " +
                            "but the opposite is also required. The first rule was ignored.",
                    )
                }
                continue
            }
            succ[u].add(v)
        }

        // ---- ranks ----------------------------------------------------------------------------------
        val dependedOn = HashMap<String, Int>()
        for (m in unique) {
            for (d in m.meta.dependencies) {
                val hit = d.allIds.firstOrNull { it in indexOf } ?: continue
                dependedOn[hit] = (dependedOn[hit] ?: 0) + 1
            }
        }
        val rank = IntArray(n)
        for ((i, m) in unique.withIndex()) rank[i] = baseRank(m, rules, dependedOn[m.id] ?: 0)

        // mods that have to load after a "bottom" mod cannot be earlier than it, so they belong at the bottom too
        val late = BooleanArray(n)
        val lateStack = ArrayList<Int>()
        for (i in 0 until n) {
            if (rank[i] == RANK_BOTTOM) {
                late[i] = true
                lateStack.add(i)
            }
        }
        while (lateStack.isNotEmpty()) {
            val v = lateStack.removeAt(lateStack.size - 1)
            for (w in succ[v]) {
                if (!late[w]) {
                    late[w] = true
                    lateStack.add(w)
                }
            }
        }
        for (i in 0 until n) if (late[i] && rank[i] < RANK_BOTTOM) rank[i] = RANK_BOTTOM

        // a mod that must precede a "higher" mod is pulled up to that mod's rank
        val topo = plainTopo(n, succ)
        val eff = rank.copyOf()
        for (k in topo.indices.reversed()) {
            val v = topo[k]
            for (w in succ[v]) if (eff[w] < eff[v]) eff[v] = eff[w]
        }

        // ---- final ordering -------------------------------------------------------------------------
        val tie = Array(n) { i ->
            if (tieBreak == TieBreak.KEEP_CURRENT) String.format("%08d", i)
            else unique[i].name.lowercase() + "\u0000" + unique[i].id
        }
        val inDeg = IntArray(n)
        for (v in 0 until n) for (w in succ[v]) inDeg[w]++

        val cmp = Comparator<Int> { a, b ->
            if (eff[a] != eff[b]) return@Comparator eff[a].compareTo(eff[b])
            val c = tie[a].compareTo(tie[b])
            if (c != 0) c else a.compareTo(b)
        }
        val queue = PriorityQueue<Int>(maxOf(n, 1), cmp)
        for (i in 0 until n) if (inDeg[i] == 0) queue.add(i)
        val order = ArrayList<String>(n)
        while (queue.isNotEmpty()) {
            val v = queue.poll()
            order.add(unique[v].id)
            for (w in succ[v]) {
                inDeg[w]--
                if (inDeg[w] == 0) queue.add(w)
            }
        }
        if (order.size != n) {
            // cannot happen (edges were added acyclically); keep the result complete anyway
            val placed = order.toHashSet()
            for (m in unique) if (m.id !in placed) order.add(m.id)
            warnings.add("Internal: some mods were appended without sorting.")
        }
        return Result(order, warnings, ignored)
    }

    private fun plainTopo(n: Int, succ: Array<ArrayList<Int>>): IntArray {
        val inDeg = IntArray(n)
        for (v in 0 until n) for (w in succ[v]) inDeg[w]++
        val out = IntArray(n)
        var count = 0
        val queue = ArrayDeque<Int>()
        for (i in 0 until n) if (inDeg[i] == 0) queue.addLast(i)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            out[count++] = v
            for (w in succ[v]) {
                inDeg[w]--
                if (inDeg[w] == 0) queue.addLast(w)
            }
        }
        return if (count == n) out else out.copyOf(count)
    }

    internal fun baseRank(m: ModEntry, rules: CommunityRules, dependents: Int): Int {
        val id = m.id
        if (id == "brrainz.harmony") return RANK_HARMONY
        if (id == "ludeon.rimworld") return RANK_CORE
        if (id.startsWith("ludeon.rimworld.")) {
            val k = dlcOrder.indexOf(id)
            return if (k >= 0) RANK_DLC + 1 + k else RANK_DLC + 1 + dlcOrder.size
        }
        if (m.isOfficial) return RANK_DLC + 1 + dlcOrder.size
        if (id in rules.loadTop) return RANK_TOP
        if (id in rules.loadBottom) return RANK_BOTTOM
        if (dependents >= 3) return RANK_LIBRARY
        if (libraryWords.containsMatchIn(id + " " + m.name.lowercase())) return RANK_LIBRARY
        return RANK_NORMAL
    }
}
