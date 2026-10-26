package com.rimpydroid.manager.core

import java.io.File
import java.util.Locale

/** Small file-system helpers (pure JVM, shared by the app and the unit tests). */
object FileUtil {

    /** Case-insensitive child lookup (mods are made on Windows, where case does not matter). */
    fun childIgnoreCase(dir: File?, name: String): File? {
        if (dir == null) return null
        val exact = File(dir, name)
        if (exact.exists()) return exact
        val list = dir.listFiles() ?: return null
        for (f in list) {
            if (f.name.equals(name, ignoreCase = true)) return f
        }
        return null
    }

    /** Total size of all files below [dir]. Does not follow symlinked directories. */
    fun dirSize(dir: File, maxFiles: Int = 300000): Long {
        var total = 0L
        var count = 0
        val stack = ArrayList<File>()
        stack.add(dir)
        while (stack.isNotEmpty()) {
            val d = stack.removeAt(stack.size - 1)
            val children = d.listFiles() ?: continue
            for (c in children) {
                if (c.isDirectory) {
                    if (!isSymlink(c)) stack.add(c)
                } else {
                    total += c.length()
                    count++
                    if (count >= maxFiles) return total
                }
            }
        }
        return total
    }

    private fun isSymlink(f: File): Boolean {
        return try {
            val canon = f.canonicalFile
            val abs = File(f.parentFile?.canonicalFile ?: f.absoluteFile.parentFile, f.name)
            canon.path != abs.path
        } catch (e: Exception) {
            false
        }
    }

    /** Deletes a file or directory tree. Returns true when everything is gone. */
    fun deleteRecursive(f: File): Boolean {
        if (!f.exists()) return true
        if (f.isDirectory && !isSymlink(f)) {
            val children = f.listFiles()
            if (children != null) {
                for (c in children) deleteRecursive(c)
            }
        }
        return f.delete() || !f.exists()
    }

    fun humanSize(bytes: Long): String {
        if (bytes < 0) return "?"
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes.toDouble() / 1024.0
        var i = 0
        while (v >= 1024.0 && i < units.size - 1) {
            v /= 1024.0
            i++
        }
        return String.format(Locale.US, "%.1f %s", v, units[i])
    }

    /** Writes via a temp file + rename so a crash cannot leave a half-written file. */
    fun writeTextAtomic(file: File, text: String) {
        val parent = file.absoluteFile.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val tmp = File(parent, file.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            // Some file systems refuse to rename over an existing file.
            file.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    }

    fun readTextOrNull(file: File, maxBytes: Int = 4 * 1024 * 1024): String? {
        return try {
            if (!file.isFile) null else XmlUtil.readText(file, maxBytes)
        } catch (e: Exception) {
            null
        }
    }

    /** Characters that are not allowed in file names on Windows / Android. */
    fun safeFileName(name: String): String {
        val cleaned = name.trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        val shortened = if (cleaned.length > 80) cleaned.substring(0, 80) else cleaned
        return if (shortened.isEmpty() || shortened == "." || shortened == "..") "list" else shortened
    }
}
