package com.rimpydroid.manager.service

import android.content.Context
import com.rimpydroid.manager.AppLog
import com.rimpydroid.manager.core.AppConfig
import com.rimpydroid.manager.core.FileUtil
import com.rimpydroid.manager.core.InstallInfo
import com.rimpydroid.manager.steam.ChunkGate
import com.rimpydroid.manager.steam.DownloadOutcome
import com.rimpydroid.manager.steam.DownloadRequest
import com.rimpydroid.manager.steam.GatedBackend
import com.rimpydroid.manager.steam.JavaSteamBackend
import com.rimpydroid.manager.steam.SteamDownloader
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class TaskStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class DownloadTask(
    val workshopId: Long,
    val title: String,
    val installDir: String,
    val isUpdate: Boolean,
    val expectedManifest: String?,
    val timeUpdated: Long?,
    val sizeHint: Long,
    val status: TaskStatus = TaskStatus.QUEUED,
    val percent: Float = 0f,
    val bytes: Long = 0L,
    val message: String = "",
)

/** What the UI asks for. */
data class QueueRequest(
    val workshopId: Long,
    val title: String,
    val installDir: File,
    val isUpdate: Boolean,
    val expectedManifest: String?,
    val timeUpdated: Long?,
    val sizeHint: Long,
)

/**
 * The list of downloads of the whole app. Up to [maxParallelMods] worker threads take the waiting mods in order,
 * so several mods download at the same time. Every worker has its own Steam connection (a downloader is not
 * thread-safe). A gate holds all workers together to [totalConnections] chunks in flight, because every chunk
 * costs memory, so a high number of mods at once cannot use up the heap of the phone. [DownloadService] keeps the
 * process alive and shows the notification.
 */
object DownloadQueue {

    /** Heap that is reckoned for one chunk in flight (it needs several MB while it is downloaded and unpacked). */
    private const val HEAP_PER_CONNECTION = 32L * 1024 * 1024
    private const val MIN_CONNECTIONS = 4
    private const val MAX_CONNECTIONS = 16

    /** Tries per item for failures that may pass (a bad download server, a dropped connection). */
    private const val MAX_ATTEMPTS = 3

    /** Workers that start together log in to Steam one after the other, not all in the same moment. */
    private const val WORKER_STAGGER_MS = 400L

    /** The list on screen is not redrawn for every single progress report of every mod. */
    private const val PROGRESS_PUSH_MS = 500L

    private val lock = Any()
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val _installed = MutableSharedFlow<Long>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emits the Workshop id of every mod that was installed or updated successfully. */
    val installed: SharedFlow<Long> = _installed.asSharedFlow()

    /** Connections (chunks in flight) for all mods together, as chosen in the settings. See [totalConnections]. */
    @Volatile
    var maxDownloads: Int = 8
        set(value) {
            field = value
            chunkGate.setLimit(totalConnections())
        }

    /** Every chunk in flight, of whichever mod, passes this gate. */
    private val chunkGate = ChunkGate(totalConnections())

    /**
     * How many mods download at the same time, [AppConfig.MIN_PARALLEL_MODS] to [AppConfig.MAX_PARALLEL_MODS].
     * A change counts at once: more workers start when mods are waiting; when the number is lowered, the surplus
     * workers stop after the mod they are busy with.
     */
    @Volatile
    var maxParallelMods: Int = AppConfig.DEFAULT_PARALLEL_MODS
        set(value) {
            field = value.coerceIn(AppConfig.MIN_PARALLEL_MODS, AppConfig.MAX_PARALLEL_MODS)
            synchronized(lock) { startWorkersLocked() }
        }

    /** Test hook: how long a transfer may deliver nothing before it counts as stuck. */
    @Volatile
    var stallMs: Long = 120_000L

    /** Test hook: how a worker gets its downloader. Every worker asks for its own. */
    @Volatile
    var downloaderFactory: () -> SteamDownloader = {
        SteamDownloader(connectionsPerMod(), stallMs, backend = GatedBackend(JavaSteamBackend(), chunkGate))
    }

    /** The most chunks that fit into the memory of this phone at once. */
    fun memoryCap(): Int =
        (Runtime.getRuntime().maxMemory() / HEAP_PER_CONNECTION).toInt().coerceIn(MIN_CONNECTIONS, MAX_CONNECTIONS)

    /** Chunks in flight over all mods: the choice in the settings, but never more than [memoryCap]. */
    fun totalConnections(): Int = maxDownloads.coerceIn(2, memoryCap())

    /**
     * Threads (connections) one mod uses. The gate holds all mods together to [totalConnections]; a mod may have up
     * to twice its fair share of threads, so it can use what the others leave free. A downloader never uses fewer than 2.
     */
    fun connectionsPerMod(): Int = maxOf(2, minOf(totalConnections(), 2 * totalConnections() / maxParallelMods))

    /** Test hook: base pause before another attempt at an item that failed for a reason that may pass. */
    @Volatile
    var retryPauseMs: Long = 2_000L

    @Volatile
    private var appContext: Context? = null

    /** Called once from the Application so the queue can start the foreground service. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun startService() {
        appContext?.let { DownloadService.start(it) }
    }

    /** Number of worker threads that are alive. Guarded by [lock]. */
    private var workerCount = 0
    private var nextWorkerId = 1

    private val cancelled = ConcurrentHashMap.newKeySet<Long>()

    val isBusy: Boolean
        get() = _tasks.value.any { it.status == TaskStatus.QUEUED || it.status == TaskStatus.RUNNING }

    /** Adds downloads. Items that are already queued or running are skipped. Returns how many were added. */
    fun enqueue(requests: List<QueueRequest>): Int {
        var added = 0
        synchronized(lock) {
            // update() repeats the change when a worker changed a task meanwhile, so no progress or result gets lost
            _tasks.update { current ->
                val list = current.toMutableList()
                var n = 0
                for (r in requests) {
                    val i = list.indexOfFirst { it.workshopId == r.workshopId }
                    if (i >= 0) {
                        val old = list[i]
                        if (old.status == TaskStatus.QUEUED || old.status == TaskStatus.RUNNING) continue
                        list.removeAt(i)
                    }
                    cancelled.remove(r.workshopId)
                    list.add(
                        DownloadTask(
                            workshopId = r.workshopId,
                            title = r.title.ifBlank { "Workshop item ${r.workshopId}" },
                            installDir = r.installDir.absolutePath,
                            isUpdate = r.isUpdate,
                            expectedManifest = r.expectedManifest,
                            timeUpdated = r.timeUpdated,
                            sizeHint = r.sizeHint,
                        ),
                    )
                    n++
                }
                added = n
                list
            }
            if (added > 0) startWorkersLocked()
        }
        if (added > 0) startService()
        return added
    }

    fun cancel(workshopId: Long) {
        synchronized(lock) {
            val t = _tasks.value.firstOrNull { it.workshopId == workshopId } ?: return
            when (t.status) {
                TaskStatus.QUEUED -> replace(workshopId) { it.copy(status = TaskStatus.CANCELLED, message = "Cancelled") }
                TaskStatus.RUNNING -> cancelled.add(workshopId)
                else -> {}
            }
        }
    }

    fun cancelAll() {
        synchronized(lock) {
            _tasks.update { list ->
                list.map { if (it.status == TaskStatus.QUEUED) it.copy(status = TaskStatus.CANCELLED, message = "Cancelled") else it }
            }
            // what is running stops as soon as its worker notices
            for (t in _tasks.value) {
                if (t.status == TaskStatus.RUNNING) cancelled.add(t.workshopId)
            }
        }
    }

    /** Puts failed and cancelled downloads back in the queue. */
    fun retryFailed(): Int {
        var n = 0
        synchronized(lock) {
            val ids = HashSet<Long>()
            for (t in _tasks.value) {
                if (t.status == TaskStatus.FAILED || t.status == TaskStatus.CANCELLED) ids.add(t.workshopId)
            }
            if (ids.isNotEmpty()) {
                for (id in ids) cancelled.remove(id)
                _tasks.update { list ->
                    list.map {
                        if (it.workshopId in ids && (it.status == TaskStatus.FAILED || it.status == TaskStatus.CANCELLED)) {
                            it.copy(status = TaskStatus.QUEUED, percent = 0f, bytes = 0L, message = "")
                        } else {
                            it
                        }
                    }
                }
                n = ids.size
                startWorkersLocked()
            }
        }
        if (n > 0) startService()
        return n
    }

    /** Removes finished entries (done, failed, cancelled) from the list. */
    fun clearFinished() {
        synchronized(lock) {
            _tasks.update { list -> list.filter { it.status == TaskStatus.QUEUED || it.status == TaskStatus.RUNNING } }
        }
    }

    // ---- workers -----------------------------------------------------------------------------------

    private fun replace(id: Long, change: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.workshopId == id) change(it) else it } }
    }

    private fun isCancelled(id: Long): Boolean = cancelled.contains(id)

    /**
     * Starts as many workers as are useful: never more than [maxParallelMods], and none for which no mod is waiting.
     * Call with [lock] held.
     */
    private fun startWorkersLocked() {
        val list = _tasks.value
        val running = list.count { it.status == TaskStatus.RUNNING }
        val queued = list.count { it.status == TaskStatus.QUEUED }
        val idle = (workerCount - running).coerceAtLeast(0) // alive, but no mod taken yet
        val wanted = minOf(maxParallelMods - workerCount, queued - idle)
        var started = 0
        while (started < wanted) {
            workerCount++
            val id = nextWorkerId++
            val delayMs = started * WORKER_STAGGER_MS
            val t = Thread({ runWorker(delayMs) }, "download-worker-$id")
            t.isDaemon = true
            t.start()
            started++
        }
    }

    /**
     * Takes the next waiting mod and marks it as running; null when there is none. A mod whose folder is being
     * written by another worker right now is left for later. Call with [lock] held.
     */
    private fun claimNextLocked(): DownloadTask? {
        val snapshot = _tasks.value
        val busyDirs = HashSet<String>()
        for (t in snapshot) {
            if (t.status == TaskStatus.RUNNING) busyDirs.add(t.installDir)
        }
        val next = snapshot.firstOrNull { it.status == TaskStatus.QUEUED && it.installDir !in busyDirs } ?: return null
        replace(next.workshopId) { it.copy(status = TaskStatus.RUNNING, percent = 0f, message = "Connecting…") }
        return next
    }

    private fun runWorker(startDelayMs: Long) {
        var downloader: SteamDownloader? = null
        var connections = 0 // the number of connections the downloader above was made for
        var current: DownloadTask? = null
        try {
            if (startDelayMs > 0) {
                try {
                    Thread.sleep(startDelayMs)
                } catch (e: InterruptedException) {
                    // nobody interrupts a worker that has not started yet; just go on
                }
            }
            while (true) {
                val task: DownloadTask
                synchronized(lock) {
                    // a worker that is too many for the current limit stops between two mods
                    val next = if (workerCount > maxParallelMods) null else claimNextLocked()
                    if (next == null) {
                        workerCount--
                        return
                    }
                    task = next
                }
                current = task
                // a new downloader (a new login to Steam) only when the settings changed since the last mod
                val want = connectionsPerMod()
                val existing = downloader
                val dl: SteamDownloader
                if (existing != null && want == connections) {
                    dl = existing
                } else {
                    downloader = null
                    existing?.close()
                    dl = downloaderFactory()
                    downloader = dl
                    connections = want
                }
                val noConnection = runTask(dl, task)
                current = null
                if (noConnection) {
                    // Every other item would wait through the same failed connection attempts; say so right away.
                    failQueued("Could not reach Steam. Check your internet connection, then tap Retry.")
                }
            }
        } catch (e: Throwable) {
            AppLog.e("Queue", "Worker crashed", e)
            synchronized(lock) {
                workerCount = (workerCount - 1).coerceAtLeast(0)
                val id = current?.workshopId
                if (id != null) {
                    // only the mod of this worker fails; the other workers go on
                    replace(id) {
                        if (it.status == TaskStatus.RUNNING) it.copy(status = TaskStatus.FAILED, message = "Internal error: ${e.message}") else it
                    }
                    cancelled.remove(id)
                    startWorkersLocked()
                }
            }
        } finally {
            downloader?.close()
        }
    }

    /** Runs one download (with retries). Returns true when Steam could not be reached at all. */
    private fun runTask(downloader: SteamDownloader, task: DownloadTask): Boolean {
        val dir = File(task.installDir)
        val existedBefore = dir.exists()

        // not enough room is much better reported before downloading than as a half-written mod
        if (task.sizeHint > 0) {
            val parent = if (existedBefore) dir else dir.parentFile
            val free = try {
                parent?.usableSpace ?: Long.MAX_VALUE
            } catch (e: Exception) {
                Long.MAX_VALUE
            }
            val needed = task.sizeHint + task.sizeHint / 4 + 20L * 1024 * 1024
            if (free < needed) {
                replace(task.workshopId) {
                    it.copy(
                        status = TaskStatus.FAILED,
                        message = "Not enough free space (need ${FileUtil.humanSize(needed)}, have ${FileUtil.humanSize(free)}).",
                    )
                }
                AppLog.w("Queue", "No space for ${task.workshopId}: need $needed, have $free")
                return false
            }
        }

        dir.mkdirs()
        // An interrupted download must not look like an up-to-date mod, so the record is cleared first.
        InstallInfo.write(dir, InstallInfo.Info(task.workshopId, null, null, task.title))

        val request = DownloadRequest(task.workshopId, task.title, dir, task.expectedManifest, task.timeUpdated)
        var attempt = 1
        var outcome: DownloadOutcome
        var lastPushMs = 0L
        var lastStatus = ""
        while (true) {
            outcome = downloader.download(
                request,
                onProgress = { p ->
                    val now = System.nanoTime() / 1_000_000L
                    if (p.status != lastStatus || now - lastPushMs >= PROGRESS_PUSH_MS || p.percent >= 1f) {
                        lastStatus = p.status
                        lastPushMs = now
                        replace(task.workshopId) { it.copy(percent = p.percent, bytes = p.bytes, message = p.status) }
                    }
                },
                isCancelled = { isCancelled(task.workshopId) },
            )
            val failed = outcome as? DownloadOutcome.Failed
            if (failed != null && failed.retryable && attempt < MAX_ATTEMPTS && !isCancelled(task.workshopId)) {
                attempt++
                AppLog.w("Queue", "Download of ${task.workshopId} failed (${failed.message}); attempt $attempt of $MAX_ATTEMPTS")
                replace(task.workshopId) { it.copy(message = "Retrying ($attempt/$MAX_ATTEMPTS): ${failed.message}") }
                // what was downloaded stays on disk, so the next attempt continues instead of starting over
                if (!pause(retryPauseMs * (attempt - 1), task.workshopId)) {
                    outcome = DownloadOutcome.Cancelled
                    break
                }
                continue
            }
            break
        }

        var noConnection = false
        when (val result = outcome) {
            is DownloadOutcome.Success -> {
                replace(task.workshopId) { it.copy(status = TaskStatus.DONE, percent = 1f, message = if (task.isUpdate) "Updated" else "Installed") }
                _installed.tryEmit(task.workshopId)
            }
            is DownloadOutcome.Cancelled -> {
                discardIfNew(dir, existedBefore)
                replace(task.workshopId) { it.copy(status = TaskStatus.CANCELLED, message = "Cancelled") }
            }
            is DownloadOutcome.Failed -> {
                discardIfNew(dir, existedBefore)
                AppLog.w("Queue", "Download of ${task.workshopId} failed: ${result.message}")
                replace(task.workshopId) { it.copy(status = TaskStatus.FAILED, message = result.message) }
                noConnection = result.noConnection
            }
        }
        cancelled.remove(task.workshopId)
        return noConnection
    }

    /** Waits [ms], but gives up early (false) when the download is cancelled meanwhile. */
    private fun pause(ms: Long, id: Long): Boolean {
        var left = ms
        while (left > 0) {
            if (isCancelled(id)) return false
            val step = minOf(left, 200L)
            try {
                Thread.sleep(step)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            left -= step
        }
        return !isCancelled(id)
    }

    private fun failQueued(message: String) {
        synchronized(lock) {
            _tasks.update { list ->
                list.map { if (it.status == TaskStatus.QUEUED) it.copy(status = TaskStatus.FAILED, message = message) else it }
            }
        }
    }

    /** A folder this run created and that never became a mod is removed so no empty husks remain. */
    private fun discardIfNew(dir: File, existedBefore: Boolean) {
        if (existedBefore) return
        try {
            FileUtil.deleteRecursive(dir)
        } catch (e: Exception) {
            AppLog.w("Queue", "Could not remove ${dir.name}", e)
        }
    }
}
