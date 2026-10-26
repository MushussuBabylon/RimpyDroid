/*
 * The way of downloading Workshop items in this app (anonymous Steam login, then a manual SteamPipe download:
 * Workshop depot from PICS, depot key, content servers, manifest, chunks) is the mechanism of RimDroid's
 * SteamAnonModDownloader.java, ported to Kotlin and adapted (installs into the mod folder, incremental updates).
 *
 * SPDX-License-Identifier: MIT
 *
 * Original work: Anonymous Steam Workshop mod downloader, original mechanism by udarmolota for RimDroid.
 * Copyright (c) 2026 udarmolota
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following condition: the above copyright notice and this
 * permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
 * NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.rimpydroid.manager.steam

import com.rimpydroid.manager.AppLog
import com.rimpydroid.manager.core.FileUtil
import com.rimpydroid.manager.core.InstallInfo
import com.rimpydroid.manager.core.WorkshopApi
import com.rimpydroid.manager.core.WorkshopItem
import com.rimpydroid.manager.core.WorkshopUtil
import `in`.dragonbra.javasteam.steam.cdn.Server
import `in`.dragonbra.javasteam.types.DepotManifest
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class DownloadRequest(
    val workshopId: Long,
    val title: String,
    val installDir: File,
    /** Manifest id the UI saw when the download was queued. The downloader asks Steam again for the newest one. */
    val expectedManifest: String?,
    val timeUpdated: Long?,
)

class DownloadProgress(val percent: Float, val bytes: Long, val status: String)

sealed class DownloadOutcome {
    object Success : DownloadOutcome()
    object Cancelled : DownloadOutcome()

    /**
     * [retryable]: trying again later may work (network trouble). [noConnection]: Steam could not be reached
     * at all, so trying the next item right away is pointless.
     */
    class Failed(
        val message: String,
        val retryable: Boolean = false,
        val noConnection: Boolean = false,
    ) : DownloadOutcome()
}

/**
 * Downloads Steam Workshop items (RimWorld, app 294100) one after another over a single anonymous
 * Steam connection, straight into the mod folder.
 *
 * JavaSteam's own DepotDownloader is not used: version 1.8.0 skips every normal Workshop item
 * (see [JavaSteamBackend]). Updates are incremental: files that are already in the folder are checked chunk
 * by chunk against the checksums in the manifest, and only chunks that differ are downloaded.
 *
 * Not thread-safe: use from one worker thread.
 */
class SteamDownloader(
    private val maxDownloads: Int = 8,
    private val stallMs: Long = STALL_MS,
    private val backend: SteamBackend = JavaSteamBackend(),
    /** Where the current Workshop entry of an item comes from (a hook for tests). */
    private val itemSource: (Long) -> WorkshopItem? = { WorkshopApi.getDetails(it) },
    /** Base pause between two tries on another download server (a hook for tests). */
    private val retryPauseMs: Long = 400L,
    /** Whether Steam lists mods for an id, that is, whether it is a collection (a hook for tests). */
    private val collectionCheck: (Long) -> Boolean = { WorkshopApi.isCollection(it) },
) {

    /** Blocks until the item is installed, failed or cancelled. Never throws. */
    fun download(
        req: DownloadRequest,
        onProgress: (DownloadProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): DownloadOutcome {
        val watch = CancelWatch(isCancelled, Thread.currentThread())
        watch.start()
        try {
            return run(req, Reporter(onProgress), isCancelled)
        } catch (e: CancelledException) {
            backend.reset()
            return DownloadOutcome.Cancelled
        } catch (e: InterruptedException) {
            backend.reset()
            return if (watch.cancelled || isCancelled()) DownloadOutcome.Cancelled else DownloadOutcome.Failed("The download was interrupted.", retryable = true)
        } catch (e: SteamConnectException) {
            backend.reset()
            if (watch.cancelled || isCancelled()) return DownloadOutcome.Cancelled
            AppLog.e("Download", "No connection to Steam for ${req.workshopId}: ${e.message}")
            return DownloadOutcome.Failed(e.message ?: "Could not reach Steam.", retryable = false, noConnection = true)
        } catch (e: SteamFatalException) {
            backend.reset()
            if (watch.cancelled || isCancelled()) return DownloadOutcome.Cancelled
            AppLog.e("Download", "Failed ${req.workshopId}: ${e.message}")
            return DownloadOutcome.Failed(e.message ?: "The download failed.")
        } catch (e: IOException) {
            backend.reset()
            if (watch.cancelled || isCancelled()) return DownloadOutcome.Cancelled
            AppLog.e("Download", "Failed ${req.workshopId}", e)
            return DownloadOutcome.Failed(friendly(e), retryable = true)
        } catch (e: Throwable) {
            backend.reset()
            if (watch.cancelled || isCancelled()) return DownloadOutcome.Cancelled
            AppLog.e("Download", "Unexpected error for ${req.workshopId}", e)
            return DownloadOutcome.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            watch.finish()
            // the watcher may have interrupted this thread to break a blocking call; the next item must start clean
            if (watch.cancelled) Thread.interrupted()
        }
    }

    /** Disconnects from Steam and frees everything. Safe to call repeatedly. */
    fun close() = backend.close()

    // ---- the download ------------------------------------------------------------------------------

    private fun run(req: DownloadRequest, rep: Reporter, isCancelled: () -> Boolean): DownloadOutcome {
        fun checkCancelled() {
            if (isCancelled()) throw CancelledException()
        }
        checkCancelled()

        val dir = req.installDir
        dir.mkdirs()
        if (!dir.isDirectory) {
            return DownloadOutcome.Failed("Cannot create folder ${dir.absolutePath}. Check the Mods folder and storage permission.")
        }
        AppLog.i("Download", "Starting ${req.workshopId} \"${req.title}\"")

        // 1. What is the newest version? (public Web API, no Steam connection needed)
        rep.status("Asking the Workshop…")
        val item = fetchItem(req.workshopId)
        val manifestId = parseManifestId(item)
        val appId = if (item.consumerAppId > 0) item.consumerAppId.toInt() else WorkshopUtil.RIMWORLD_APP_ID
        checkCancelled()

        // 2. Steam: Workshop depot, key, download servers, permission to read the file list
        rep.status("Connecting to Steam…")
        backend.connect()
        checkCancelled()
        rep.status("Preparing…")
        val depot = backend.workshopDepot(appId)
        val key = backend.depotKey(depot, appId)
        val servers = backend.cdnServers(appId)
        val code = backend.manifestRequestCode(depot, appId, manifestId)
        if (code == 0L) AppLog.w("Download", "Steam gave no request code for the file list; trying without")
        AppLog.i(
            "Download",
            "${req.workshopId}: app $appId, depot $depot, manifest ${item.manifest}, ${servers.size} servers, " +
                "${if (item.fileSize > 0) FileUtil.humanSize(item.fileSize) else "size not given"}, " +
                "request code ${if (code == 0L) "none" else "granted"}",
        )
        checkCancelled()

        // 3. The file list
        rep.status("Fetching the file list…")
        val access = CdnAccess(backend, appId, depot)
        val manifest = fetchManifest(req.workshopId, access, depot, manifestId, code, key, servers, isCancelled)
        checkCancelled()
        if (manifest.files.none { isAboutXml(it.fileName) }) throw SteamFatalException(NO_ABOUT_XML)

        // 4. What is missing on disk?
        rep.status("Checking files…")
        val plan = DepotPlanner.plan(manifest, dir, isCancelled)
        AppLog.i(
            "Download",
            "${req.workshopId}: ${plan.fileCount} files, ${FileUtil.humanSize(plan.totalBytes)}; " +
                "${plan.jobs.size} chunks to fetch, ${FileUtil.humanSize(plan.alreadyBytes)} already on disk",
        )
        rep.progress(plan.alreadyBytes, plan.totalBytes)

        // 5. The chunks
        if (plan.jobs.isNotEmpty()) {
            rep.status("Downloading")
            val started = System.nanoTime()
            val transfer = ChunkTransfer(backend, access, depot, key, servers, maxDownloads.coerceIn(2, MAX_THREADS), stallMs, retryPauseMs)
            transfer.run(plan.jobs, plan.alreadyBytes, isCancelled) { done -> rep.progress(done, plan.totalBytes) }
            val seconds = (System.nanoTime() - started) / 1_000_000_000.0
            AppLog.i("Download", "${req.workshopId}: chunks done in ${"%.1f".format(seconds)} s")
        }
        checkCancelled()

        // 6. Make it a proper mod folder
        rep.status("Finishing…")
        if (!hasAboutXml(dir)) throw SteamFatalException(NO_ABOUT_XML)
        val removed = DepotPlanner.removeStale(dir, plan.keep)
        if (removed > 0) AppLog.i("Download", "${req.workshopId}: removed $removed outdated entries")
        finalizeInstall(req, item)

        rep.progress(plan.totalBytes, plan.totalBytes)
        rep.status("Done")
        AppLog.i("Download", "Finished ${req.workshopId} \"${req.title}\"")
        return DownloadOutcome.Success
    }

    /** The current Workshop entry; throws when the item cannot be downloaded at all. */
    private fun fetchItem(id: Long): WorkshopItem {
        val item = try {
            itemSource(id)
        } catch (e: IOException) {
            throw IOException("Could not reach the Steam Workshop: ${friendly(e)}", e)
        }
        if (item == null || item.result == 9) throw SteamFatalException("This item no longer exists on the Steam Workshop.")
        if (item.result == 15) throw SteamFatalException("This item is private or hidden, so Steam does not allow downloading it.")
        if (item.banned) throw SteamFatalException("Steam removed this item (it was banned).")
        if (item.result != 1) throw SteamFatalException("The Steam Workshop reported an error for this item (code ${item.result}).")
        if (item.isCollection) throw SteamFatalException("This is a collection, not a mod.")
        if (item.manifest == null) {
            throw SteamFatalException("This item has no downloadable files on Steam (its author hosts them elsewhere).")
        }
        return item
    }

    private fun parseManifestId(item: WorkshopItem): Long = try {
        java.lang.Long.parseUnsignedLong(item.manifest!!)
    } catch (e: NumberFormatException) {
        throw SteamFatalException("Steam returned an invalid content id (${item.manifest}) for this item.")
    }

    /**
     * Asks the download servers one after the other until one hands out the file list.
     *
     * A server that answers "not found" (or "no") has given a definite answer, unlike one that times out. When
     * several of them agree there is no such file list, waiting for the rest is pointless: the item is looked at
     * (is it a collection, which has no files?) and the download ends with a message that says what is wrong.
     */
    private fun fetchManifest(
        workshopId: Long,
        access: CdnAccess,
        depot: Int,
        manifestId: Long,
        code: Long,
        key: ByteArray,
        servers: List<Server>,
        isCancelled: () -> Boolean,
    ): DepotManifest {
        var last: Exception? = null
        val tries = (servers.size * 2).coerceIn(4, 12)
        val noSuchFile = LinkedHashSet<String>() // hosts that said 404 or 403
        val giveUpAt = minOf(GIVE_UP_AFTER, servers.size).coerceAtLeast(2)
        var looked = false
        for (i in 0 until tries) {
            if (isCancelled()) throw CancelledException()
            val server = servers[i % servers.size]
            val host = ServerSelection.hostOf(server)
            try {
                return access.call(server) { token -> backend.downloadManifest(depot, manifestId, code, server, key, token) }
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                last = e
                AppLog.w("Download", "File list from $host failed: ${friendly(e)}")
                if (e is CdnStatusException && (e.status == 404 || e.status == 403)) {
                    noSuchFile.add(host)
                    val enough = noSuchFile.size >= giveUpAt
                    if (!looked && (enough || noSuchFile.size >= LOOK_AFTER)) {
                        looked = true
                        if (looksLikeCollection(workshopId)) throw SteamFatalException(IS_A_COLLECTION)
                    }
                    if (enough) throw SteamFatalException(noFileList(noSuchFile.size))
                }
                Thread.sleep(minOf(retryPauseMs * (i + 1), 2000L))
            }
        }
        throw IOException("The download servers would not hand out the file list: ${friendly(last)}", last)
    }

    /** Asks Steam whether [id] is a collection. Trouble with the question counts as "no". */
    private fun looksLikeCollection(id: Long): Boolean = try {
        collectionCheck(id)
    } catch (e: InterruptedException) {
        throw e
    } catch (e: Exception) {
        AppLog.w("Download", "Could not check whether $id is a collection: ${friendly(e)}")
        false
    }

    private fun isAboutXml(manifestName: String?): Boolean =
        DepotPlanner.relativePath(manifestName)?.equals("About/About.xml", ignoreCase = true) == true

    private fun hasAboutXml(dir: File): Boolean {
        val about = FileUtil.childIgnoreCase(dir, "About") ?: return false
        val xml = FileUtil.childIgnoreCase(about, "About.xml") ?: return false
        return xml.isFile
    }

    private fun finalizeInstall(req: DownloadRequest, item: WorkshopItem) {
        val dir = req.installDir
        InstallInfo.write(
            dir,
            InstallInfo.Info(req.workshopId, item.manifest, item.timeUpdated.takeIf { it > 0 } ?: req.timeUpdated, req.title),
        )
        // RimWorld and other tools recognise Workshop mods by About/PublishedFileId.txt
        try {
            val about = FileUtil.childIgnoreCase(dir, "About")
            if (about != null && FileUtil.childIgnoreCase(about, "PublishedFileId.txt") == null) {
                File(about, "PublishedFileId.txt").writeText(req.workshopId.toString())
            }
        } catch (e: Exception) {
            AppLog.w("Download", "Could not write PublishedFileId.txt", e)
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** Progress as the queue wants it; the pieces arrive from different phases. */
    private class Reporter(private val sink: (DownloadProgress) -> Unit) {
        @Volatile
        private var status = "Starting…"

        @Volatile
        private var percent = 0f

        @Volatile
        private var bytes = 0L

        fun status(s: String) {
            status = s
            push()
        }

        fun progress(done: Long, total: Long) {
            bytes = done
            percent = if (total > 0) (done.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat() else 0f
            push()
        }

        private fun push() = sink(DownloadProgress(percent, bytes, status))
    }

    /** Notices a cancel request while the download thread sits in a blocking call and wakes it up. */
    private inner class CancelWatch(private val isCancelled: () -> Boolean, private val worker: Thread) : Thread("rd-cancel-watch") {
        private val gate = Any()

        @Volatile
        var cancelled = false
            private set

        private var done = false

        init {
            isDaemon = true
        }

        override fun run() {
            try {
                while (true) {
                    if (isCancelled()) {
                        synchronized(gate) {
                            if (done) return
                            cancelled = true
                        }
                        backend.reset() // stops everything that waits for Steam
                        worker.interrupt() // finish() waits for this, so the flag cannot arrive after the download ended
                        return
                    }
                    sleep(WATCH_MS)
                }
            } catch (e: InterruptedException) {
                // finish() is waking the watcher up
            }
        }

        /** Stops the watcher. When it is in the middle of a cancel, waits until that is complete. */
        fun finish() {
            synchronized(gate) { done = true }
            if (!cancelled) interrupt() // `cancelled` cannot change any more once `done` is set
            try {
                join()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    companion object {
        /** No chunk for this long means the transfer is stuck (a broken connection can look like that). */
        private const val STALL_MS = 120_000L
        private const val WATCH_MS = 250L
        private const val MAX_THREADS = 16
        private const val NO_ABOUT_XML =
            "Steam delivered no About/About.xml, so this does not look like a RimWorld mod (hidden, removed, or not a mod?)."

        /** This many download servers saying "not found" makes it a look at the item; this many end the attempt. */
        private const val LOOK_AFTER = 3
        private const val GIVE_UP_AFTER = 4

        private const val IS_A_COLLECTION =
            "This is a collection, not a mod. Use \u201cShow mods\u201d on it to download the mods it contains."

        private fun noFileList(servers: Int) =
            "Steam's download servers have no file list for this item (\u201cnot found\u201d from $servers of them). " +
                "It may have been removed or replaced, or it is not a mod. Try again later."

        /** A short human sentence for an error, without class names and stack traces. */
        fun friendly(e: Throwable?): String = when (e) {
            null -> "unknown error"
            is UnknownHostException -> "no internet connection (cannot find ${e.message})"
            is SocketTimeoutException -> "the connection timed out"
            is ConnectException -> "cannot connect (${e.message})"
            is SSLException -> "the secure connection failed (${e.message})"
            else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        }
    }
}
