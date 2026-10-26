package com.rimpydroid.manager.core

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Finds Core / DLC (game folder "Data") and mods (the Mods folder). */
object ModScanner {

    class Result(val mods: List<ModEntry>, val ignoredFolders: List<String>)

    private class Job(val dir: File, val source: ModSource)

    fun scan(
        gameDir: File?,
        modsDir: File?,
        game: GameVersion?,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): Result {
        val jobs = ArrayList<Job>()

        if (gameDir != null) {
            val data = FileUtil.childIgnoreCase(gameDir, "Data")
            val dirs = data?.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
            for (d in dirs) jobs.add(Job(d, ModSource.OFFICIAL))
        }
        if (modsDir != null && modsDir.isDirectory) {
            val dirs = modsDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: emptyList()
            for (d in dirs) jobs.add(Job(d, ModSource.LOCAL))
        }

        val mods = ArrayList<ModEntry>()
        val ignored = ArrayList<String>()
        if (jobs.isEmpty()) return Result(mods, ignored)

        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = ArrayList<Future<ModEntry?>>()
            for (j in jobs) {
                futures.add(pool.submit(Callable<ModEntry?> { loadOne(j.dir, j.source, game) }))
            }
            var done = 0
            for ((i, f) in futures.withIndex()) {
                val entry = try {
                    f.get()
                } catch (e: Exception) {
                    null
                }
                if (entry != null) {
                    mods.add(entry)
                } else if (jobs[i].source == ModSource.LOCAL) {
                    ignored.add(jobs[i].dir.name)
                }
                done++
                if (onProgress != null && (done % 10 == 0 || done == futures.size)) onProgress(done, futures.size)
            }
        } finally {
            pool.shutdown()
        }

        mods.sortWith(compareBy<ModEntry> { !it.isOfficial }.thenBy { it.name.lowercase() }.thenBy { it.key })
        return Result(mods, ignored)
    }

    fun loadOne(dir: File, source: ModSource, game: GameVersion?): ModEntry? {
        val aboutDir = FileUtil.childIgnoreCase(dir, "About")
        if (aboutDir == null || !aboutDir.isDirectory) return null
        val aboutXml = FileUtil.childIgnoreCase(aboutDir, "About.xml")
        if (aboutXml == null || !aboutXml.isFile) return null

        val meta = AboutParser.parseFile(aboutXml, game, dir.name)
        val modified = maxOf(aboutXml.lastModified(), dir.lastModified())
        return ModEntry(
            dir = dir,
            meta = meta,
            source = source,
            workshopId = readWorkshopId(dir, aboutDir),
            installedManifest = InstallInfo.installedManifest(dir),
            lastModified = modified,
            previewFile = findPreview(aboutDir),
        )
    }

    private val numericName = Regex("^\\d{6,}$")

    /** Workshop id from About/PublishedFileId.txt, else from a purely numeric folder name. */
    fun readWorkshopId(modDir: File, aboutDir: File): Long? {
        val f = FileUtil.childIgnoreCase(aboutDir, "PublishedFileId.txt")
        if (f != null && f.isFile) {
            val text = FileUtil.readTextOrNull(f, 1024)?.trim()
            val id = text?.toLongOrNull()
            if (id != null && id > 0) return id
        }
        if (numericName.matches(modDir.name)) return modDir.name.toLongOrNull()
        return null
    }

    private val previewNames = listOf("Preview.png", "Preview.jpg", "Preview.jpeg", "ModIcon.png", "ModIcon.jpg")

    fun findPreview(aboutDir: File): File? {
        for (n in previewNames) {
            val f = FileUtil.childIgnoreCase(aboutDir, n)
            if (f != null && f.isFile && f.length() in 1..(6L * 1024 * 1024)) return f
        }
        return null
    }
}
