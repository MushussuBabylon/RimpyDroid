package com.rimpydroid.manager.core

import java.io.File

/** Finds the RimWorld folder and ModsConfig.xml on the phone's storage. Pure JVM. */
object GameLocator {

    /** A RimWorld install folder: contains Data/Core/About. */
    fun isGameDir(dir: File?): Boolean {
        if (dir == null || !dir.isDirectory) return false
        val data = FileUtil.childIgnoreCase(dir, "Data") ?: return false
        val core = FileUtil.childIgnoreCase(data, "Core") ?: return false
        return FileUtil.childIgnoreCase(core, "About") != null
    }

    /** The Mods folder of a game folder (not created here). */
    fun modsDir(gameDir: File): File = FileUtil.childIgnoreCase(gameDir, "Mods") ?: File(gameDir, "Mods")

    /**
     * Finds ModsConfig.xml for something the user picked: the file itself, the Config folder,
     * or the data folder that was given to RimWorld with -savedatafolder (which contains Config).
     */
    fun findModsConfig(pick: File?): File? {
        if (pick == null) return null
        if (pick.isFile) return if (pick.name.equals("ModsConfig.xml", ignoreCase = true)) pick else null
        if (!pick.isDirectory) return null
        FileUtil.childIgnoreCase(pick, "ModsConfig.xml")?.takeIf { it.isFile }?.let { return it }
        val cfg = FileUtil.childIgnoreCase(pick, "Config")
        if (cfg != null) FileUtil.childIgnoreCase(cfg, "ModsConfig.xml")?.takeIf { it.isFile }?.let { return it }
        return null
    }

    /** Where ModsConfig.xml lives (or should be created) for the picked folder. */
    fun configTarget(pick: File): File {
        findModsConfig(pick)?.let { return it }
        if (pick.name.equals("Config", ignoreCase = true)) return File(pick, "ModsConfig.xml")
        return File(File(pick, "Config"), "ModsConfig.xml")
    }

    class Found(val gameDirs: List<File>, val configFiles: List<File>)

    private val skipNames = setOf(
        "android", "dcim", "pictures", "movies", "music", "podcasts", "ringtones", "notifications",
        "alarms", "audiobooks", "recordings", "whatsapp", "telegram", "miui", "lost.dir", "node_modules",
    )

    /**
     * Breadth-first search below [roots] for RimWorld folders and ModsConfig.xml files.
     * Stops after [timeLimitMs] or when [cancel] returns true.
     */
    fun discover(
        roots: List<File>,
        maxDepth: Int = 4,
        timeLimitMs: Long = 10_000,
        cancel: () -> Boolean = { false },
    ): Found {
        val games = LinkedHashSet<File>()
        val configs = LinkedHashSet<File>()
        val deadline = System.currentTimeMillis() + timeLimitMs
        val queue = ArrayDeque<Pair<File, Int>>()
        for (r in roots) if (r.isDirectory) queue.addLast(r to 0)

        while (queue.isNotEmpty()) {
            if (cancel() || System.currentTimeMillis() > deadline) break
            val (dir, depth) = queue.removeFirst()
            val kids = dir.listFiles() ?: continue
            var isGame = false
            for (k in kids) {
                val lower = k.name.lowercase()
                if (k.isFile && lower == "modsconfig.xml") configs.add(k)
                if (k.isDirectory && lower == "data" && isGameDir(dir)) isGame = true
            }
            if (isGame) {
                games.add(dir)
                continue // do not search inside a game folder
            }
            if (depth >= maxDepth) continue
            for (k in kids) {
                if (!k.isDirectory) continue
                val lower = k.name.lowercase()
                if (lower.startsWith(".") || lower in skipNames) continue
                queue.addLast(k to depth + 1)
            }
        }
        return Found(games.toList(), configs.toList())
    }
}
