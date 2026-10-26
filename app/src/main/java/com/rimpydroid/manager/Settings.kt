package com.rimpydroid.manager

import android.content.Context
import com.rimpydroid.manager.core.AppConfig
import com.rimpydroid.manager.core.ThemeColors

/** The user's choices, kept in SharedPreferences. */
class Settings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppConfig {
        val d = AppConfig()
        return AppConfig(
            gameDir = prefs.getString(K_GAME, d.gameDir) ?: d.gameDir,
            modsDir = prefs.getString(K_MODS, d.modsDir) ?: d.modsDir,
            configPath = prefs.getString(K_CONFIG, d.configPath) ?: d.configPath,
            autoSave = prefs.getBoolean(K_AUTOSAVE, d.autoSave),
            keepOrder = prefs.getBoolean(K_KEEP_ORDER, d.keepOrder),
            autoCheckUpdates = prefs.getBoolean(K_AUTO_UPDATES, d.autoCheckUpdates),
            versionTag = prefs.getString(K_VERSION_TAG, d.versionTag) ?: d.versionTag,
            maxDownloads = prefs.getInt(K_MAX_DOWNLOADS, d.maxDownloads).coerceIn(2, 25),
            themeMode = prefs.getInt(K_THEME, d.themeMode).coerceIn(AppConfig.THEME_LIGHT, AppConfig.THEME_SYSTEM),
            accentColor = prefs.getInt(K_ACCENT, d.accentColor).let { if (it == 0) 0 else ThemeColors.opaque(it) },
        )
    }

    fun save(c: AppConfig) {
        prefs.edit()
            .putString(K_GAME, c.gameDir)
            .putString(K_MODS, c.modsDir)
            .putString(K_CONFIG, c.configPath)
            .putBoolean(K_AUTOSAVE, c.autoSave)
            .putBoolean(K_KEEP_ORDER, c.keepOrder)
            .putBoolean(K_AUTO_UPDATES, c.autoCheckUpdates)
            .putString(K_VERSION_TAG, c.versionTag)
            .putInt(K_MAX_DOWNLOADS, c.maxDownloads.coerceIn(2, 25))
            .putInt(K_THEME, c.themeMode.coerceIn(AppConfig.THEME_LIGHT, AppConfig.THEME_SYSTEM))
            .putInt(K_ACCENT, if (c.accentColor == 0) 0 else ThemeColors.opaque(c.accentColor))
            .apply()
    }

    /** True once the notification permission was asked for (so that we do not ask on every start). */
    var askedNotifications: Boolean
        get() = prefs.getBoolean(K_ASKED_NOTIF, false)
        set(value) {
            prefs.edit().putBoolean(K_ASKED_NOTIF, value).apply()
        }

    private companion object {
        const val K_GAME = "game_dir"
        const val K_MODS = "mods_dir"
        const val K_CONFIG = "config_path"
        const val K_AUTOSAVE = "auto_save"
        const val K_KEEP_ORDER = "keep_order"
        const val K_AUTO_UPDATES = "auto_check_updates"
        const val K_VERSION_TAG = "version_tag"
        const val K_MAX_DOWNLOADS = "max_downloads"
        const val K_THEME = "theme_mode"
        const val K_ACCENT = "accent_color"
        const val K_ASKED_NOTIF = "asked_notifications"
    }
}
