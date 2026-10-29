package com.rimpydroid.manager.core

import java.io.File
import java.io.IOException

/** The user's choices (Settings screen). Paths are kept as text so that they survive a missing storage permission. */
data class AppConfig(
    val gameDir: String = "",
    /** Empty means "<gameDir>/Mods". */
    val modsDir: String = "",
    /** Full path of ModsConfig.xml (the one the game reads). */
    val configPath: String = "",
    val autoSave: Boolean = true,
    val keepOrder: Boolean = false,
    val autoCheckUpdates: Boolean = true,
    val versionTag: String = "",
    val maxDownloads: Int = 8,
    /** How many mods are downloaded at the same time, [MIN_PARALLEL_MODS] to [MAX_PARALLEL_MODS]. */
    val parallelMods: Int = DEFAULT_PARALLEL_MODS,
    /** [THEME_LIGHT] (white), [THEME_DARK] (black) or [THEME_SYSTEM] (follows the phone's setting). */
    val themeMode: Int = THEME_LIGHT,
    /**
     * The colour that replaces "the black" of the white-and-black look, as 0xAARRGGBB. 0 is the default:
     * black on white, white on black (see [ThemeColors.accentFor]).
     */
    val accentColor: Int = 0,
) {
    val gameDirFile: File? get() = gameDir.trim().takeIf { it.isNotEmpty() }?.let { File(it) }

    val modsDirFile: File?
        get() = modsDir.trim().takeIf { it.isNotEmpty() }?.let { File(it) } ?: gameDirFile?.let { GameLocator.modsDir(it) }

    val configFile: File? get() = configPath.trim().takeIf { it.isNotEmpty() }?.let { File(it) }

    /** True when there is a folder to scan and download into. */
    val hasModsDir: Boolean get() = modsDirFile != null

    companion object {
        const val THEME_LIGHT = 0
        const val THEME_DARK = 1
        const val THEME_SYSTEM = 2

        const val MIN_PARALLEL_MODS = 1
        const val MAX_PARALLEL_MODS = 5
        const val DEFAULT_PARALLEL_MODS = 3
    }
}

class LoadedLibrary(
    val mods: List<ModEntry>,
    val ignoredFolders: List<String>,
    val game: GameVersion?,
    /** The active list, lower-case package ids. */
    val active: List<String>,
    /** True when ModsConfig.xml was found and read. */
    val configRead: Boolean,
)

/** Reads and writes everything on disk that belongs to the load order. */
object Library {

    fun load(config: AppConfig, rules: CommunityRules, onProgress: ((Int, Int) -> Unit)? = null): LoadedLibrary {
        val gameDir = config.gameDirFile
        val game = GameVersion.readFromGameDir(gameDir)
        val scan = ModScanner.scan(gameDir, config.modsDirFile, game, onProgress)
        val data = config.configFile?.let { ModsConfigXml.read(it) }
        val active = if (data != null) {
            ActiveListOps.normalize(data.activeMods)
        } else {
            ModelOps.initialActive(scan.mods, rules)
        }
        return LoadedLibrary(scan.mods, scan.ignoredFolders, game, active, data != null)
    }

    /** Reads only the load order again (the game or another tool may have changed it). */
    fun readActive(config: AppConfig): List<String>? {
        val data = config.configFile?.let { ModsConfigXml.read(it) } ?: return null
        return ActiveListOps.normalize(data.activeMods)
    }

    /** Writes ModsConfig.xml (a backup of the old file is kept). Throws IOException with a readable message. */
    @Throws(IOException::class)
    fun save(config: AppConfig, game: GameVersion?, mods: List<ModEntry>, active: List<String>) {
        val file = config.configFile ?: throw IOException("The ModsConfig.xml location is not set (Settings).")
        val expansions = mods.filter { it.isOfficial && it.id.isNotEmpty() && it.id != ModelOps.CORE_ID }.map { it.id }
        try {
            ModsConfigXml.write(file, active, game?.raw?.ifBlank { null } ?: game?.short, expansions)
        } catch (e: IOException) {
            throw IOException("Could not write ${file.absolutePath}: ${e.message}", e)
        } catch (e: SecurityException) {
            throw IOException("No permission to write ${file.absolutePath}", e)
        }
    }
}

/** Community rules, cached in a file and refreshed from the RimSort database on request. */
class RulesStore(
    private val file: File,
    private val fetch: (String) -> String = { Http.getString(it, 30_000, 8 * 1024 * 1024) },
) {
    /** The cached rules, or null when nothing usable is stored. */
    fun cached(): CommunityRules? {
        if (!file.isFile) return null
        return try {
            CommunityRules.parseFile(file)?.takeIf { it.ruleCount > 0 }
        } catch (e: Exception) {
            null
        }
    }

    /** When the cache was written (epoch millis), 0 if there is none. */
    fun cachedAt(): Long = if (file.isFile) file.lastModified() else 0L

    /** Downloads, validates and stores the newest rules. Throws IOException when that is not possible. */
    @Throws(IOException::class)
    fun download(): CommunityRules {
        val text = fetch(URL)
        val rules = try {
            CommunityRules.parse(text)
        } catch (e: Exception) {
            throw IOException("The rules file could not be read: ${e.message}", e)
        }
        if (rules.ruleCount == 0) throw IOException("The downloaded rules file contains no rules.")
        FileUtil.writeTextAtomic(file, text)
        return rules
    }

    companion object {
        const val URL = "https://raw.githubusercontent.com/RimSort/Community-Rules-Database/main/communityRules.json"
    }
}
