package com.rimpydroid.manager.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.rimpydroid.manager.AppLog
import com.rimpydroid.manager.MainActivity
import com.rimpydroid.manager.R
import com.rimpydroid.manager.RimpyDroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and shows progress while [DownloadQueue] works, also with the screen off
 * or the app in the background. The work itself is done by the queue's own thread.
 */
@OptIn(FlowPreview::class)
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        RimpyDroidApp.createChannels(this)
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(DownloadQueue.tasks.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (e: Exception) {
            AppLog.w("Service", "Could not go to the foreground", e)
        }
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RimpyDroid:download")?.apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }

        scope.launch {
            DownloadQueue.tasks.sample(800).collect { tasks ->
                if (stopped) return@collect
                if (tasks.none { it.status == TaskStatus.QUEUED || it.status == TaskStatus.RUNNING }) {
                    finishWith(tasks)
                } else {
                    NotificationManagerCompat.from(this@DownloadService).let { nm ->
                        if (nm.areNotificationsEnabled()) {
                            try {
                                nm.notify(NOTIFICATION_ID, buildNotification(tasks))
                            } catch (e: SecurityException) {
                                // notification permission revoked meanwhile
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_ALL) DownloadQueue.cancelAll()
        return START_NOT_STICKY
    }

    /** Android 15 limits "data sync" services to about six hours. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLog.w("Service", "Android stopped the download service (time limit)")
        DownloadQueue.cancelAll()
        stopSelf()
    }

    override fun onDestroy() {
        stopped = true
        scope.cancel()
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // already released
        }
        super.onDestroy()
    }

    private fun finishWith(tasks: List<DownloadTask>) {
        stopped = true
        val done = tasks.count { it.status == TaskStatus.DONE }
        val failed = tasks.count { it.status == TaskStatus.FAILED }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if ((done > 0 || failed > 0) && NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            val text = buildString {
                append(done).append(" installed")
                if (failed > 0) append(", ").append(failed).append(" failed")
            }
            val n = NotificationCompat.Builder(this, RimpyDroidApp.CHANNEL_DOWNLOADS)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle("Downloads finished")
                .setContentText(text)
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build()
            try {
                NotificationManagerCompat.from(this).notify(SUMMARY_ID, n)
            } catch (e: SecurityException) {
                // notification permission revoked meanwhile
            }
        }
        stopSelf()
    }

    private fun openAppIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun buildNotification(tasks: List<DownloadTask>): Notification {
        val total = tasks.count { it.status != TaskStatus.CANCELLED }
        val finished = tasks.count { it.status == TaskStatus.DONE || it.status == TaskStatus.FAILED }
        val running = tasks.firstOrNull { it.status == TaskStatus.RUNNING }
        val title = if (total <= 1) "Downloading mod" else "Downloading mods (${minOf(finished + 1, total)} of $total)"
        val text = running?.title ?: "Waiting…"
        val percent = ((running?.percent ?: 0f) * 100).toInt().coerceIn(0, 100)

        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, RimpyDroidApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, running == null || running.percent <= 0f)
            .setContentIntent(openAppIntent())
            .addAction(0, "Cancel all", cancel)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 41
        private const val SUMMARY_ID = 42
        const val ACTION_CANCEL_ALL = "com.rimpydroid.manager.CANCEL_ALL"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            } catch (e: Exception) {
                // e.g. started from the background on Android 12+: the queue still runs, just without the notification
                AppLog.w("Service", "Could not start the download service", e)
            }
        }
    }
}
