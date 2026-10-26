package com.rimpydroid.manager.service

import android.content.Context
import com.rimpydroid.manager.AppLog
import com.rimpydroid.manager.core.FileUtil
import com.rimpydroid.manager.core.InstallInfo
import com.rimpydroid.manager.steam.DownloadOutcome
import com.rimpydroid.manager.steam.DownloadRequest
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
 * The list of downloads of the whole app. A single worker thread processes it in order over one
 * Steam connection; [DownloadService] keeps the process alive and shows the notification.
 */
object DownloadQueue {

    private val lock = Any()
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val _installed = MutableSharedFlow<Long>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emits the Workshop id of every mod that was installed or updated successfully. */
    val installed: SharedFlow<Long> = _installed.asSharedFlow()

    @Volatile
    var maxDownloads: Int = 8

    /** Test hook: how long a transfer may deliver nothing before it counts as stuck. */
    @Volatile
    var stallMs: Long = 120_000L

    /** Test hook: how the worker gets its downloader. */
    @Volatile
    var downloaderFactory: () -> SteamDownloader = { SteamDownloader(maxDownloads, stallMs) }

    /** Test hook: base pause before another attempt at an item that failed for a reason that may pass. */
    @Volatile
    var retryPauseMs: Long = 2_000L

    /** Tries per item for failures that may pass (a bad download server, a dropped connection). */
    private const val MAX_ATTEMPTS = 3

    @Volatile
    private var appContext: Context? = null

    /** Called once from the Application so the queue can start the foreground service. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun startService() {
        appContext?.let { DownloadService.start(it) }
    }

    private var workerAlive = false

    @Volatile
    private var cancelAll = false
    private val cancelled = ConcurrentHashMap.newKeySet<Long>()

    val isBusy: Boolean
        get() = _tasks.value.any { it.status == TaskStatus.QUEUED || it.status == TaskStatus.RUNNING }

    /** Adds downloads. Items that are already queued or running are skipped. Returns how many were added. */
    fun enqueue(requests: List<QueueRequest>): Int {
        var added = 0
        synchronized(lock) {
            val list = _tasks.value.toMutableList()
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
                added++
            }
            _tasks.value = list
            if (added > 0) {
                cancelAll = false
                if (!workerAlive) {
                    workerAlive = true
                    val t = Thread({ runWorker() }, "download-worker")
                    t.isDaemon = true
                    t.start()
                }
            }
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
            cancelAll = true
            _tasks.update { list ->
                list.map { if (it.status == TaskStatus.QUEUED) it.copy(status = TaskStatus.CANCELLED, message = "Cancelled") else it }
            }
        }
    }

    /** Puts failed and cancelled downloads back in the queue. */
    fun retryFailed(): Int {
        var n = 0
        synchronized(lock) {
            _tasks.update { list ->
                list.map {
                    if (it.status == TaskStatus.FAILED || it.status == TaskStatus.CANCELLED) {
                        n++
                        cancelled.remove(it.workshopId)
                        it.copy(status = TaskStatus.QUEUED, percent = 0f, bytes = 0L, message = "")
                    } else {
                        it
                    }
                }
            }
            if (n > 0) {
                cancelAll = false
                if (!workerAlive) {
                    workerAlive = true
                    val t = Thread({ runWorker() }, "download-worker")
                    t.isDaemon = true
                    t.start()
                }
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

    // ---- worker ------------------------------------------------------------------------------------

    private fun replace(id: Long, change: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.workshopId == id) change(it) else it } }
    }

    private fun isCancelled(id: Long): Boolean = cancelAll || cancelled.contains(id)

    private fun runWorker() {
        val downloader = downloaderFactory()
        try {
            while (true) {
                val task: DownloadTask
                synchronized(lock) {
                    val next = _tasks.value.firstOrNull { it.status == TaskStatus.QUEUED }
                    if (next == null) {
                        workerAlive = false
                        return
                    }
                    task = next
                    replace(task.workshopId) { it.copy(status = TaskStatus.RUNNING, percent = 0f, message = "Connecting…") }
                }
                val noConnection = runTask(downloader, task)
                if (noConnection) {
                    // Every other item would wait through the same failed connection attempts; say so right away.
                    failQueued("Could not reach Steam. Check your internet connection, then tap Retry.")
                }
            }
        } catch (e: Throwable) {
            AppLog.e("Queue", "Worker crashed", e)
            synchronized(lock) {
                workerAlive = false
                _tasks.update { list ->
                    list.map {
                        if (it.status == TaskStatus.RUNNING || it.status == TaskStatus.QUEUED) {
                            it.copy(status = TaskStatus.FAILED, message = "Internal error: ${e.message}")
                        } else {
                            it
                        }
                    }
                }
            }
        } finally {
            downloader.close()
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
        while (true) {
            outcome = downloader.download(
                request,
                onProgress = { p ->
                    replace(task.workshopId) { it.copy(percent = p.percent, bytes = p.bytes, message = p.status) }
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
