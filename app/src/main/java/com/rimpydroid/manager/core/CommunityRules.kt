package com.rimpydroid.manager.core

import java.io.File

/**
 * Community load-order rules in the format of the RimSort "Community Rules Database"
 * (communityRules.json):
 *
 *   { "timestamp": 123, "rules": { "<packageId>": {
 *        "loadAfter":  { "<otherId>": { "name": [...], "comment": [...] } },
 *        "loadBefore": { ... },
 *        "loadTop":    { "value": true },
 *        "loadBottom": { "value": true },
 *        "incompatibleWith": { "<otherId>": { "comment": "..." } } } } }
 *
 * The parser is tolerant: id lists may be objects keyed by id, or arrays of ids.
 */
class CommunityRules(
    val timestamp: Long,
    /** mod id -> ids this mod must load BEFORE */
    val loadBefore: Map<String, Set<String>>,
    /** mod id -> ids this mod must load AFTER */
    val loadAfter: Map<String, Set<String>>,
    val loadTop: Set<String>,
    val loadBottom: Set<String>,
    /** mod id -> (other id -> comment) */
    val incompatible: Map<String, Map<String, String>>,
    val ruleCount: Int,
) {
    companion object {
        val EMPTY = CommunityRules(0L, emptyMap(), emptyMap(), emptySet(), emptySet(), emptyMap(), 0)

        fun parse(json: String): CommunityRules {
            val root = Json.parse(json).asObj() ?: throw JsonException("Rules file is not a JSON object")
            val timestamp = root["timestamp"].asLong() ?: 0L
            val rules = root["rules"].asObj() ?: root

            val before = LinkedHashMap<String, Set<String>>()
            val after = LinkedHashMap<String, Set<String>>()
            val top = LinkedHashSet<String>()
            val bottom = LinkedHashSet<String>()
            val incompatible = LinkedHashMap<String, Map<String, String>>()
            var count = 0

            for ((rawId, node) in rules) {
                val id = rawId.trim().lowercase()
                if (id.isEmpty()) continue
                val r = node.asObj() ?: continue
                count++

                val b = idsOf(r["loadBefore"])
                if (b.isNotEmpty()) before[id] = b
                val a = idsOf(r["loadAfter"])
                if (a.isNotEmpty()) after[id] = a
                if (flag(r["loadTop"]) || flag(r["loadFirst"])) top.add(id)
                if (flag(r["loadBottom"]) || flag(r["loadLast"])) bottom.add(id)

                val inc = r["incompatibleWith"]
                if (inc != null) {
                    val m = LinkedHashMap<String, String>()
                    val incObj = inc.asObj()
                    if (incObj != null) {
                        for ((otherRaw, v) in incObj) {
                            val other = otherRaw.trim().lowercase()
                            if (other.isEmpty()) continue
                            m[other] = commentOf(v)
                        }
                    } else {
                        for (other in idsOf(inc)) m[other] = ""
                    }
                    if (m.isNotEmpty()) incompatible[id] = m
                }
            }
            return CommunityRules(timestamp, before, after, top, bottom, incompatible, count)
        }

        fun parseFile(file: File): CommunityRules? {
            val text = FileUtil.readTextOrNull(file, 32 * 1024 * 1024) ?: return null
            return try {
                parse(text)
            } catch (e: Exception) {
                null
            }
        }

        private fun idsOf(node: Any?): Set<String> {
            val out = LinkedHashSet<String>()
            val obj = node.asObj()
            if (obj != null) {
                for (k in obj.keys) {
                    val id = k.trim().lowercase()
                    if (id.isNotEmpty() && id != "value" && id != "comment") out.add(id)
                }
                return out
            }
            val arr = node.asArr()
            if (arr != null) {
                for (e in arr) {
                    val s = e.asStr()
                    if (s != null) {
                        val id = s.trim().lowercase()
                        if (id.isNotEmpty()) out.add(id)
                        continue
                    }
                    val o = e.asObj() ?: continue
                    val id = (o["packageId"].asStr() ?: o["id"].asStr())?.trim()?.lowercase()
                    if (!id.isNullOrEmpty()) out.add(id)
                }
            }
            return out
        }

        private fun flag(node: Any?): Boolean {
            if (node == null) return false
            val b = node.asBool()
            if (b != null) return b
            val obj = node.asObj() ?: return false
            return obj["value"].asBool() ?: false
        }

        private fun commentOf(v: Any?): String {
            val obj = v.asObj() ?: return v.asStr() ?: ""
            val c = obj["comment"]
            val arr = c.asArr()
            if (arr != null) return arr.mapNotNull { it.asStr() }.filter { it.isNotBlank() }.joinToString(" ")
            return c.asStr() ?: ""
        }
    }
}
