/*
 * Parts of this file follow the mechanism of RimDroid's anonymous Workshop downloader
 * (SteamAnonModDownloader.java, MIT licence, Copyright (c) 2026 udarmolota): files are created at their
 * full size, chunks are downloaded in parallel and written at their offset, and every chunk rotates
 * through the list of download servers when one fails. See the full notice in SteamDownloader.kt.
 */
package com.rimpydroid.manager.steam

import com.rimpydroid.manager.AppLog
import com.rimpydroid.manager.core.FileUtil
import com.rimpydroid.manager.core.InstallInfo
import `in`.dragonbra.javasteam.enums.EDepotFileFlag
import `in`.dragonbra.javasteam.steam.cdn.Server
import `in`.dragonbra.javasteam.types.ChunkData
import `in`.dragonbra.javasteam.types.DepotManifest
import `in`.dragonbra.javasteam.util.Adler32
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Thrown when the user cancelled while the downloader was busy. */
class CancelledException : RuntimeException("cancelled")

/** No chunk arrived for too long. */
class StallException(seconds: Long) : IOException("No progress for $seconds seconds. Check your connection and try again.")

/** One chunk of one file that still has to be downloaded. */
class ChunkJob(val file: File, val chunk: ChunkData)

/** What a manifest asks for on disk, minus what is already there. */
class DepotPlan(
    /** Uncompressed size of all files of the item. */
    val totalBytes: Long,
    /** Bytes of chunks that are already on disk and correct. */
    val alreadyBytes: Long,
    /** Chunks that still have to be downloaded. */
    val jobs: List<ChunkJob>,
    /** Lower-case relative paths of everything that belongs to the item (files, folders and their parents). */
    val keep: Set<String>,
    val fileCount: Int,
)

/**
 * Turns a manifest into work: creates the folders, creates every file at its final size and works out which
 * chunks are missing. Files that exist already (an update) are checked chunk by chunk with the Adler-32 checksum
 * from the manifest, so only what changed is downloaded again.
 */
object DepotPlanner {

    /** A manifest path as a safe relative path with '/' separators, or null when it must not be written. */
    fun relativePath(name: String?): String? {
        if (name == null) return null
        val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null
        for (p in parts) {
            if (p == ".." || p.indexOf('\u0000') >= 0) return null
        }
        return parts.joinToString("/")
    }

    @Throws(IOException::class)
    fun plan(manifest: DepotManifest, root: File, isCancelled: () -> Boolean): DepotPlan {
        val jobs = ArrayList<ChunkJob>()
        val keep = HashSet<String>()
        var total = 0L
        var already = 0L
        var fileCount = 0
        var shared = ByteArray(0)

        for (f in manifest.files) {
            if (isCancelled()) throw CancelledException()
            val rel = relativePath(f.fileName) ?: continue
            if (rel.equals(InstallInfo.FILE_NAME, ignoreCase = true)) continue
            addToKeep(keep, rel)
            val target = File(root, rel)

            if (f.flags.contains(EDepotFileFlag.Directory)) {
                makeDirs(root, target)
                continue
            }
            if (!f.linkTarget.isNullOrEmpty()) continue // links cannot exist on the storage of a phone

            fileCount++
            total += f.totalSize
            makeDirs(root, target.parentFile)
            if (target.isDirectory) FileUtil.deleteRecursive(target) // a folder of the old version where a file belongs now

            try {
                RandomAccessFile(target, "rw").use { raf ->
                    val oldLength = raf.length()
                    if (oldLength != f.totalSize) raf.setLength(f.totalSize)
                    for (chunk in f.chunks) {
                        val len = chunk.uncompressedLength
                        if (len <= 0) continue
                        // Only content that was there before can match; the rest of a grown file is zeros.
                        val present = oldLength > 0 && chunk.offset + len <= oldLength
                        if (present) {
                            if (shared.size != len) shared = ByteArray(len)
                            val ok = try {
                                raf.seek(chunk.offset)
                                raf.readFully(shared, 0, len)
                                Adler32.calculate(shared) == chunk.checksum
                            } catch (e: IOException) {
                                false
                            }
                            if (ok) {
                                already += len
                                continue
                            }
                        }
                        jobs.add(ChunkJob(target, chunk))
                    }
                }
            } catch (e: IOException) {
                throw SteamFatalException("Cannot write ${target.name}: ${e.message ?: "storage error"}. Is the storage full or read-only?")
            }
        }
        return DepotPlan(total, already, jobs, keep, fileCount)
    }

    private fun addToKeep(keep: MutableSet<String>, rel: String) {
        var path = rel.lowercase()
        keep.add(path)
        while (true) {
            val i = path.lastIndexOf('/')
            if (i < 0) break
            path = path.substring(0, i)
            if (!keep.add(path)) break
        }
    }

    /** Creates [dir]; a plain file that is in the way somewhere on the path (from an older version) is replaced by a folder. */
    @Throws(IOException::class)
    private fun makeDirs(root: File, dir: File?) {
        if (dir == null || dir.isDirectory) return
        val chain = ArrayList<File>()
        var d: File? = dir
        while (d != null && d != root && !d.isDirectory) {
            chain.add(d)
            d = d.parentFile
        }
        for (x in chain.asReversed()) {
            if (x.exists() && !x.isDirectory) x.delete()
            if (!x.mkdir() && !x.isDirectory) {
                throw SteamFatalException("Cannot create the folder ${x.absolutePath}. Is the storage full or read-only?")
            }
        }
    }

    /**
     * Deletes what an older version of the item left behind and the new one no longer has. Names are compared
     * without regard to case, because the storage of a phone may not tell "A.xml" from "a.xml".
     * Hidden entries in the item's top folder and this app's own record are left alone.
     */
    fun removeStale(root: File, keep: Set<String>): Int {
        var removed = 0

        fun isLink(f: File): Boolean = try {
            Files.isSymbolicLink(f.toPath())
        } catch (e: Exception) {
            false
        }

        fun walk(dir: File, prefix: String) {
            val kids = dir.listFiles() ?: return
            for (k in kids) {
                if (prefix.isEmpty() && k.name.startsWith(".")) continue
                val rel = if (prefix.isEmpty()) k.name else prefix + "/" + k.name
                val wanted = rel.lowercase() in keep
                if (isLink(k)) {
                    if (!wanted && k.delete()) removed++
                } else if (k.isDirectory) {
                    if (wanted) {
                        walk(k, rel)
                    } else if (FileUtil.deleteRecursive(k)) {
                        removed++
                    }
                } else if (!wanted && k.delete()) {
                    removed++
                }
            }
        }
        walk(root, "")
        FileUtil.deleteRecursive(File(root, ".DepotDownloader")) // left by an earlier version of this app
        return removed
    }
}

/**
 * Asks a download server for something, and when it answers 401/403 asks Steam for a token for that server
 * and tries once more (public content normally needs none).
 */
class CdnAccess(private val backend: SteamBackend, private val appId: Int, private val depotId: Int) {

    @Throws(IOException::class)
    fun <T> call(server: Server, block: (String?) -> T): T {
        val host = ServerSelection.hostOf(server)
        val known = backend.knownCdnToken(depotId, host)
        try {
            return block(known)
        } catch (e: CdnStatusException) {
            if ((e.status == 401 || e.status == 403) && known == null) {
                val token = backend.requestCdnToken(appId, depotId, host)
                if (token != null) return block(token)
            }
            throw e
        }
    }
}

/** Downloads chunks on several threads and writes each one at its place in its file. */
class ChunkTransfer(
    private val backend: SteamBackend,
    private val access: CdnAccess,
    private val depotId: Int,
    private val depotKey: ByteArray,
    private val servers: List<Server>,
    private val threads: Int,
    private val stallMs: Long,
    private val retryPauseMs: Long = 400L,
) {

    private val abort = AtomicBoolean(false)
    private val error = AtomicReference<Throwable?>(null)
    private val doneBytes = AtomicLong(0)
    private val lastActivity = AtomicLong(0)
    private val nextServer = AtomicInteger(0)
    private val failuresByHost = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * Blocks until every chunk is on disk. [onTick] gets the number of bytes done so far, about every
     * [TICK_MS]. Throws [CancelledException], [StallException], or the first error of a worker.
     */
    @Throws(Exception::class)
    fun run(jobs: List<ChunkJob>, startBytes: Long, isCancelled: () -> Boolean, onTick: (Long) -> Unit) {
        if (jobs.isEmpty()) return
        val queue = ConcurrentLinkedQueue(jobs)
        val n = minOf(threads, jobs.size).coerceAtLeast(1)
        val finished = CountDownLatch(n)
        doneBytes.set(startBytes)
        lastActivity.set(nowMs())
        val workers = ArrayList<Thread>()
        for (i in 1..n) {
            val t = Thread({ work(queue, finished) }, "rd-chunk-$i")
            t.isDaemon = true
            workers.add(t)
            t.start()
        }
        try {
            while (!finished.await(TICK_MS, TimeUnit.MILLISECONDS)) {
                if (isCancelled()) throw CancelledException()
                error.get()?.let { throw it }
                val idle = nowMs() - lastActivity.get()
                if (idle > stallMs) throw StallException(stallMs / 1000)
                onTick(doneBytes.get())
            }
            error.get()?.let { throw it }
            if (isCancelled()) throw CancelledException()
            onTick(doneBytes.get())
        } finally {
            abort.set(true)
            for (t in workers) t.interrupt()
            for (t in workers) {
                try {
                    t.join(1000)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
    }

    private fun work(queue: ConcurrentLinkedQueue<ChunkJob>, finished: CountDownLatch) {
        try {
            while (!abort.get()) {
                val job = queue.poll() ?: break
                val written = fetch(job)
                doneBytes.addAndGet(written.toLong())
                lastActivity.set(nowMs())
            }
        } catch (e: InterruptedException) {
            // aborted by the main loop
        } catch (e: Throwable) {
            error.compareAndSet(null, e)
            abort.set(true)
        } finally {
            finished.countDown()
        }
    }

    /** One chunk, with retries across the servers. Returns the bytes written. */
    private fun fetch(job: ChunkJob): Int {
        val chunk = job.chunk
        val size = maxOf(chunk.compressedLength, chunk.uncompressedLength)
        val tries = maxOf(servers.size, MIN_TRIES)
        var last: Exception? = null
        var attempt = 0
        while (attempt < tries) {
            if (abort.get()) throw InterruptedException()
            val server = servers[Math.floorMod(nextServer.get(), servers.size)]
            val host = ServerSelection.hostOf(server)
            // a fresh buffer every time: a try that timed out may still write into its own buffer later
            val dest = ByteArray(size)
            try {
                val n = access.call(server) { token -> backend.downloadChunk(depotId, chunk, server, dest, depotKey, token) }
                write(job.file, chunk.offset, dest, n)
                return n
            } catch (e: InterruptedException) {
                throw e
            } catch (e: SteamFatalException) {
                throw e
            } catch (e: Exception) {
                last = e
                noteFailure(host, e)
                nextServer.incrementAndGet() // the next try goes to the next server
                attempt++
                if (attempt < tries) Thread.sleep(minOf(retryPauseMs * attempt, 3000L))
            }
        }
        throw IOException("The download servers kept failing: ${SteamDownloader.friendly(last)}", last)
    }

    private fun write(file: File, offset: Long, data: ByteArray, length: Int) {
        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(offset)
                raf.write(data, 0, length)
            }
        } catch (e: IOException) {
            throw SteamFatalException("Cannot write ${file.name}: ${e.message ?: "storage error"}. Is the storage full?")
        }
    }

    private fun noteFailure(host: String, e: Exception) {
        val n = failuresByHost.getOrPut(host) { AtomicInteger() }.incrementAndGet()
        if (n <= 3 || n % 25 == 0) {
            AppLog.w("Download", "Chunk from $host failed ($n so far): ${SteamDownloader.friendly(e)}")
        }
    }

    companion object {
        const val TICK_MS = 300L
        const val MIN_TRIES = 10

        private fun nowMs(): Long = System.nanoTime() / 1_000_000L
    }
}
