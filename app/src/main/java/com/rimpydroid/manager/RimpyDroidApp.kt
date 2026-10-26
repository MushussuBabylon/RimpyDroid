package com.rimpydroid.manager

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.rimpydroid.manager.service.DownloadQueue
import `in`.dragonbra.javasteam.util.log.LogListener
import `in`.dragonbra.javasteam.util.log.LogManager
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class RimpyDroidApp : Application() {

    override fun onCreate() {
        super.onCreate()
        LogManager.addListener(SteamLogBridge())
        createChannels(this)
        DownloadQueue.init(this)
        AppLog.i("App", "RimpyDroid ${BuildConfig.VERSION_NAME} on Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")

        // Keep the last crash so that Settings can show it (nobody has adb on the phone).
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                AppLog.e("Crash", "Uncaught exception in ${thread.name}", error)
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                File(filesDir, CRASH_FILE).writeText(AppLog.asText() + "\n\n" + sw.toString())
            } catch (e: Throwable) {
                // nothing more we can do
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Forwards the useful lines of the Steam library to our log (it logs a lot). */
    private class SteamLogBridge : LogListener {
        override fun onLog(clazz: Class<*>, message: String?, throwable: Throwable?) {
            if (message == null) return
            val name = clazz.simpleName
            if (name !in LOGGED_CLASSES) return
            if (message.startsWith("Received pong") || message.startsWith("Watchdog")) return // every few seconds
            AppLog.i("Steam/$name", message)
        }

        override fun onError(clazz: Class<*>, message: String?, throwable: Throwable?) {
            // Every failed chunk is logged by the downloader itself, with the server and the HTTP status;
            // the library's own report (with a stack trace each) would only flood the log.
            if (clazz.name.startsWith("in.dragonbra.javasteam.steam.cdn.")) return
            AppLog.e("Steam/${clazz.simpleName}", message ?: "(error)", throwable)
        }

        private companion object {
            /** The parts that show how connecting to Steam goes. */
            val LOGGED_CLASSES = setOf(
                "CMClient",
                "SteamClient",
                "SmartCMServerList",
                "TcpConnection",
                "WebSocketConnection",
                "EnvelopeEncryptedConnection",
            )
        }
    }

    companion object {
        const val CHANNEL_DOWNLOADS = "downloads"
        const val CRASH_FILE = "last-crash.txt"

        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CHANNEL_DOWNLOADS) == null) {
                    val ch = NotificationChannel(
                        CHANNEL_DOWNLOADS,
                        context.getString(R.string.download_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                    ch.description = context.getString(R.string.download_channel_desc)
                    ch.setShowBadge(false)
                    nm.createNotificationChannel(ch)
                }
            }
        }
    }
}
