package com.rimpydroid.manager.core

import java.io.File
import java.io.IOException

/**
 * The "RimpyDroid" folder in the phone's Downloads, and what it holds:
 *
 *   RimpyDroid/Mods     the mods the app downloads
 *   RimpyDroid/Config   ModsConfig.xml (the file the game reads) and its automatic backups
 *   RimpyDroid/Lists    a copy of the saved mod lists
 *
 * It is an ordinary folder on shared storage, so it stays when the app is removed. Pure JVM.
 *
 * An empty ".nomedia" file lies in it, so that the phone's gallery does not show the preview pictures of the mods.
 */
object StorageLayout {

    const val FOLDER_NAME = "RimpyDroid"

    /** Android's gallery skips a folder (and the folders in it) that holds a file of this name. */
    const val NOMEDIA = ".nomedia"

    /** The app reads lists up to this size; a bigger file in the folder is not a list and is left alone. */
    private const val MAX_LIST_BYTES = 8L * 1024 * 1024

    /** What may be copied back from the shared folder in one go: anything can put files there, so it is bounded. */
    private const val MAX_RESTORED_FILES = 100
    private const val MAX_RESTORED_BYTES = 16L * 1024 * 1024

    fun modsDir(root: File): File = File(root, "Mods")

    fun configDir(root: File): File = File(root, "Config")

    fun configFile(root: File): File = File(configDir(root), "ModsConfig.xml")

    fun listsDir(root: File): File = File(root, "Lists")

    /** The copy of the downloaded collections (see CollectionStore), kept like the lists. */
    fun collectionsDir(root: File): File = File(root, "Collections")

    /**
     * The RimpyDroid folder to use when [parent] was chosen as the place for it: [parent] itself when it already is
     * one (so that "RimpyDroid/RimpyDroid" is never made), otherwise a "RimpyDroid" folder inside it.
     */
    fun rootIn(parent: File): File = if (parent.name.equals(FOLDER_NAME, ignoreCase = true)) parent else File(parent, FOLDER_NAME)

    /**
     * Puts the empty ".nomedia" file into [root] when it is not there yet. Returns true when it was made now, false when
     * it existed or could not be made (no permission, no such folder): the gallery then shows the previews, nothing breaks.
     */
    fun ensureNoMedia(root: File): Boolean {
        return try {
            val marker = File(root, NOMEDIA)
            if (!root.isDirectory || marker.exists()) false else marker.createNewFile()
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    /** Creates the folders and the ".nomedia" file. Returns null when the folders all exist afterwards, otherwise a short reason. */
    fun create(root: File): String? {
        try {
            for (d in listOf(root, modsDir(root), configDir(root), listsDir(root))) {
                if (!d.isDirectory && !d.mkdirs() && !d.isDirectory) return "Could not create " + d.absolutePath
            }
        } catch (e: SecurityException) {
            return "No permission to create " + root.absolutePath
        }
        ensureNoMedia(root)
        return null
    }

    private fun jsonFiles(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.length() <= MAX_LIST_BYTES && f.name.endsWith(".json", ignoreCase = true) }
            ?.toList().orEmpty()

    /**
     * Copies the lists that [shared] has and [local] lacks into [local] (after the app was installed again).
     * Existing lists are never replaced. Returns how many were copied.
     */
    fun restoreLists(local: File, shared: File): Int {
        var copied = 0
        var bytes = 0L
        for (f in jsonFiles(shared).sortedBy { it.name }) {
            if (copied >= MAX_RESTORED_FILES) break
            val target = File(local, f.name)
            if (target.exists()) continue
            val size = f.length()
            if (bytes + size > MAX_RESTORED_BYTES) continue
            try {
                local.mkdirs()
                f.copyTo(target, overwrite = false)
                copied++
                bytes += size
            } catch (e: IOException) {
                // this one is skipped, the others are still restored
            }
        }
        return copied
    }

    /**
     * Makes [shared] hold every list of [local] (a changed list replaces its old copy). [goneFileNames] are the file
     * names of lists that were deleted in the app; their copies are removed as well. Nothing else is ever deleted.
     * Returns false when something could not be written.
     */
    fun mirrorLists(local: File, shared: File, goneFileNames: Collection<String> = emptyList()): Boolean {
        var ok = true
        try {
            if (!shared.isDirectory && !shared.mkdirs() && !shared.isDirectory) return false
            for (f in jsonFiles(local)) {
                val target = File(shared, f.name)
                try {
                    if (!target.isFile || !target.readBytes().contentEquals(f.readBytes())) f.copyTo(target, overwrite = true)
                } catch (e: IOException) {
                    ok = false
                }
            }
            for (name in goneFileNames) {
                if (name.isBlank() || name.contains('/') || name.contains('\\')) continue
                if (!File(local, name).exists()) File(shared, name).delete()
            }
        } catch (e: SecurityException) {
            return false
        }
        return ok
    }
}
