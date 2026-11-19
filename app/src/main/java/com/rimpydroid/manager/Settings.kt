package com.rimpydroid.manager

import android.content.Context
import android.os.Environment
import com.rimpydroid.manager.core.AppConfig
import com.rimpydroid.manager.core.OfficialDlc
import com.rimpydroid.manager.core.StorageLayout
import com.rimpydroid.manager.core.ThemeColors
import java.io.File

/** The user's choices, kept in SharedPreferences. */
class Settings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init {
        migrateDefaults()
    }

    fun load(): AppConfig {
        val d = AppConfig()
        val gameDir = prefs.getString(K_GAME, d.gameDir) ?: d.gameDir
        val modsDir = prefs.getString(K_MODS, d.modsDir) ?: d.modsDir
        return AppConfig(
            gameDir = gameDir,
            modsDir = modsDir,
            configPath = prefs.getString(K_CONFIG, d.configPath) ?: d.configPath,
            autoSave = prefs.getBoolean(K_AUTOSAVE, d.autoSave),
            keepOrder = prefs.getBoolean(K_KEEP_ORDER, d.keepOrder),
            autoCheckUpdates = prefs.getBoolean(K_AUTO_UPDATES, d.autoCheckUpdates),
            versionTag = prefs.getString(K_VERSION_TAG, d.versionTag) ?: d.versionTag,
            maxDownloads = prefs.getInt(K_MAX_DOWNLOADS, d.maxDownloads).coerceIn(2, 25),
            parallelMods = prefs.getInt(K_PARALLEL_MODS, d.parallelMods)
                .coerceIn(AppConfig.MIN_PARALLEL_MODS, AppConfig.MAX_PARALLEL_MODS),
            themeMode = prefs.getInt(K_THEME, d.themeMode).coerceIn(AppConfig.THEME_LIGHT, AppConfig.THEME_SYSTEM),
            accentColor = prefs.getInt(K_ACCENT, d.accentColor).let { if (it == 0) 0 else ThemeColors.opaque(it) },
            uiStyle = prefs.getInt(K_STYLE, d.uiStyle).coerceIn(AppConfig.STYLE_CLASSIC, AppConfig.STYLE_GLASS),
            // the RimpyDroid folder may have been made somewhere else than in Downloads
            dataDir = prefs.getString(K_DATA_DIR, null)?.trim()?.takeIf { it.isNotEmpty() } ?: defaultDataDir(),
            gameVersion = prefs.getString(K_GAME_VERSION, d.gameVersion) ?: d.gameVersion,
            language = prefs.getString(K_LANGUAGE, d.language) ?: d.language,
            // someone who already chose a folder with an earlier version does not need the welcome guide
            onboarded = prefs.getBoolean(K_ONBOARDED, gameDir.isNotBlank() || modsDir.isNotBlank()),
            dlcChosen = prefs.getBoolean(K_DLC_CHOSEN, d.dlcChosen),
            ownedDlc = OfficialDlc.parse(prefs.getString(K_DLC_OWNED, null)),
            dlcPrompted = prefs.getBoolean(K_DLC_PROMPTED, d.dlcPrompted),
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
            .putInt(K_PARALLEL_MODS, c.parallelMods.coerceIn(AppConfig.MIN_PARALLEL_MODS, AppConfig.MAX_PARALLEL_MODS))
            .putInt(K_THEME, c.themeMode.coerceIn(AppConfig.THEME_LIGHT, AppConfig.THEME_SYSTEM))
            .putInt(K_ACCENT, if (c.accentColor == 0) 0 else ThemeColors.opaque(c.accentColor))
            .putInt(K_STYLE, c.uiStyle.coerceIn(AppConfig.STYLE_CLASSIC, AppConfig.STYLE_GLASS))
            .putString(K_GAME_VERSION, c.gameVersion)
            .putString(K_DATA_DIR, c.dataDir.takeIf { it != defaultDataDir() }) // only a place that was chosen is kept; the default follows the phone
            .putString(K_LANGUAGE, c.language)
            .putBoolean(K_ONBOARDED, c.onboarded)
            .putBoolean(K_DLC_CHOSEN, c.dlcChosen)
            .putString(K_DLC_OWNED, OfficialDlc.format(c.ownedDlc))
            .putBoolean(K_DLC_PROMPTED, c.dlcPrompted)
            .apply()
    }

    /** True once the notification permission was asked for (so that we do not ask on every start). */
    var askedNotifications: Boolean
        get() = prefs.getBoolean(K_ASKED_NOTIF, false)
        set(value) {
            prefs.edit().putBoolean(K_ASKED_NOTIF, value).apply()
        }

    /**
     * The defaults are now 16 connections and 5 mods at a time. A phone that still holds the old defaults (8 and 3,
     * saved without the person ever choosing them) gets the new ones once; any other number was chosen and stays.
     */
    private fun migrateDefaults() {
        if (prefs.getBoolean(K_DEFAULTS_V2, false)) return
        val edit = prefs.edit()
        if (prefs.getInt(K_MAX_DOWNLOADS, -1) == 8) edit.remove(K_MAX_DOWNLOADS)
        if (prefs.getInt(K_PARALLEL_MODS, -1) == 3) edit.remove(K_PARALLEL_MODS)
        edit.putBoolean(K_DEFAULTS_V2, true).apply()
    }

    /** Download/RimpyDroid on the phone's shared storage (the folder is created when the person chooses it). */
    @Suppress("DEPRECATION")
    fun defaultDataDir(): String = try {
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), StorageLayout.FOLDER_NAME).absolutePath
    } catch (e: Throwable) {
        ""
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
        const val K_PARALLEL_MODS = "parallel_mods"
        const val K_THEME = "theme_mode"
        const val K_ACCENT = "accent_color"
        const val K_STYLE = "ui_style"
        const val K_ASKED_NOTIF = "asked_notifications"
        const val K_GAME_VERSION = "game_version"
        const val K_LANGUAGE = "language"
        const val K_DATA_DIR = "data_dir"
        const val K_ONBOARDED = "onboarded"
        const val K_DEFAULTS_V2 = "defaults_v2"
        const val K_DLC_CHOSEN = "dlc_chosen"
        const val K_DLC_OWNED = "dlc_owned"
        const val K_DLC_PROMPTED = "dlc_prompted"
    }
}
