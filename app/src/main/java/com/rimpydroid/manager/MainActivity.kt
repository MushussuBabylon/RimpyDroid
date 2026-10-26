package com.rimpydroid.manager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings as SystemSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import com.rimpydroid.manager.ui.AppRoot
import com.rimpydroid.manager.ui.AppViewModel
import com.rimpydroid.manager.ui.RimpyDroidTheme
import com.rimpydroid.manager.ui.isDarkTheme

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: AppViewModel

    private val legacyStorageLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            viewModel.onResume(hasStorageAccess())
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[AppViewModel::class.java]
        setContent {
            val config by viewModel.config.collectAsState()
            val dark = isDarkTheme(config.themeMode)
            // The icons of the status and navigation bar must suit the app's own theme (white or black),
            // which is not necessarily the phone's.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(NAV_LIGHT_SCRIM, NAV_DARK_SCRIM) { dark },
                )
                // what shows behind the app while the keyboard moves or a screen is still being drawn
                window.setBackgroundDrawable(ColorDrawable(if (dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE))
                onDispose { }
            }
            RimpyDroidTheme(config.themeMode, config.accentColor) {
                AppRoot(viewModel, onGrantStorage = { requestStorage() })
            }
        }
        askForNotifications()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume(hasStorageAccess())
    }

    override fun onStop() {
        super.onStop()
        viewModel.flushSave()
    }

    private fun hasStorageAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager()
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(SystemSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")),
                )
            } catch (e: Exception) {
                try {
                    startActivity(Intent(SystemSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (e2: Exception) {
                    Toast.makeText(this, "Open Android settings and allow \"All files access\" for RimpyDroid.", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            legacyStorageLauncher.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
            )
        }
    }

    /** The download notification is only visible when this is allowed (Android 13+); asked once. */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val settings = Settings(this)
        if (settings.askedNotifications) return
        settings.askedNotifications = true
        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        // the scrims enableEdgeToEdge() uses behind a 3-button navigation bar
        val NAV_LIGHT_SCRIM = android.graphics.Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
        val NAV_DARK_SCRIM = android.graphics.Color.argb(0x80, 0x1B, 0x1B, 0x1B)
    }
}
