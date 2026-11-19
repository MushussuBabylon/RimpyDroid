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
    /** Download connections shared by all mods (chunks in flight). */
    val maxDownloads: Int = DEFAULT_CONNECTIONS,
    /** How many mods are downloaded at the same time, [MIN_PARALLEL_MODS] to [MAX_PARALLEL_MODS]. */
    val parallelMods: Int = DEFAULT_PARALLEL_MODS,
    /** [THEME_LIGHT] (white), [THEME_DARK] (black) or [THEME_SYSTEM] (follows the phone's setting). */
    val themeMode: Int = THEME_LIGHT,
    /**
     * The colour that replaces "the black" of the white-and-black look, as 0xAARRGGBB. 0 is the default:
     * black on white, white on black (see [ThemeColors.accentFor]).
     */
    val accentColor: Int = 0,
    /** [STYLE_CLASSIC] (the plain look, the default) or [STYLE_GLASS] (Liquid glass: translucent cards over a soft gradient). */
    val uiStyle: Int = STYLE_CLASSIC,
    /** The RimpyDroid folder in Downloads (lists live there, and mods and ModsConfig.xml when it is chosen). */
    val dataDir: String = "",
    /** The RimWorld version to assume when there is no game folder to read it from, such as "1.6". */
    val gameVersion: String = "",
    /** A language code of the app, or empty to follow the phone. */
    val language: String = "",
    /** The welcome guide has been seen (or skipped). */
    val onboarded: Boolean = false,
    /** The person has said which DLC they own (welcome guide or Settings): [ownedDlc] is then the answer, even when it is empty. */
    val dlcChosen: Boolean = false,
    /** Package ids of the DLC the person owns, such as "ludeon.rimworld.royalty" (see [OfficialDlc]). */
    val ownedDlc: List<String> = emptyList(),
    /** The question about the DLC has been shown once (so it is not asked again and again). */
    val dlcPrompted: Boolean = false,
) {
    val gameDirFile: File? get() = gameDir.trim().takeIf { it.isNotEmpty() }?.let { File(it) }

    val modsDirFile: File?
        get() = modsDir.trim().takeIf { it.isNotEmpty() }?.let { File(it) } ?: gameDirFile?.let { GameLocator.modsDir(it) }

    val configFile: File? get() = configPath.trim().takeIf { it.isNotEmpty() }?.let { File(it) }

    /** True when there is a folder to scan and download into. */
    val hasModsDir: Boolean get() = modsDirFile != null

    val dataDirFile: File? get() = dataDir.trim().takeIf { it.isNotEmpty() }?.let { File(it) }

    /** Where the files live, worked out from the folders in use: one of the `STORAGE_*` values. */
    val storageKind: Int
        get() {
            if (gameDir.isBlank() && modsDir.isBlank()) return STORAGE_NONE
            val root = dataDirFile
            val mods = modsDirFile
            if (gameDir.isBlank() && root != null && mods != null &&
                mods.absoluteFile == StorageLayout.modsDir(root).absoluteFile
            ) {
                return STORAGE_RIMPY
            }
            return if (gameDir.isNotBlank()) STORAGE_RIMWORLD else STORAGE_CUSTOM
        }

    /**
     * True in the RimpyDroid folder: the game is not in a folder this app can see (RimDroid keeps it in its own
     * storage), so Core and the DLC are left to the game. The app does not ask for them, does not offer them in the
     * list and does not report a mod for needing a DLC: RimWorld itself knows what it has. The one exception is the
     * file it writes: [Library.save] always puts Core in ModsConfig.xml, because the game treats a list without Core
     * as invalid.
     *
     * Once the person has said which DLC they own ([dlcChosen]) the app knows, and stops ignoring them: Core and the
     * chosen DLC are in the list like installed ones ([OfficialDlc.withChosen]) and are checked like any other mod.
     */
    val ignoresOfficial: Boolean get() = storageKind == STORAGE_RIMPY && !dlcChosen

    /** The RimpyDroid folder when the files live in one, otherwise null. */
    val rimpyRoot: File? get() = if (storageKind == STORAGE_RIMPY) dataDirFile else null

    /** [gameVersion] as a version without a build number (so nothing is written to ModsConfig.xml for it). */
    /** True when the Liquid glass look is chosen. */
    val glass: Boolean get() = uiStyle == STYLE_GLASS

    fun assumedGame(): GameVersion? =
        GameVersion.parse(gameVersion)?.let { GameVersion(it.major, it.minor, 0, "") }

    companion object {
        const val THEME_LIGHT = 0
        const val THEME_DARK = 1
        const val THEME_SYSTEM = 2

        const val STYLE_CLASSIC = 0
        const val STYLE_GLASS = 1

        const val MIN_PARALLEL_MODS = 1
        const val MAX_PARALLEL_MODS = 8
        const val DEFAULT_PARALLEL_MODS = 5
        const val DEFAULT_CONNECTIONS = 16

        const val STORAGE_NONE = 0
        const val STORAGE_RIMPY = 1
        const val STORAGE_RIMWORLD = 2
        const val STORAGE_CUSTOM = 3
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
        val game = GameVersion.readFromGameDir(gameDir) ?: config.assumedGame()
        val scan = ModScanner.scan(gameDir, config.modsDirFile, game, onProgress)
        // Core and the DLC the person says they own stand in for a game folder this app cannot see
        val mods = OfficialDlc.withChosen(scan.mods, config)
        val data = config.configFile?.let { ModsConfigXml.read(it) }
        val active = if (data != null) {
            ActiveListOps.normalize(data.activeMods)
        } else {
            ModelOps.initialActive(mods, rules, includeCore = !config.ignoresOfficial)
        }
        return LoadedLibrary(mods, scan.ignoredFolders, game, active, data != null)
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
        // The game wants Core in the list, also when this app cannot see the game folder (the RimpyDroid folder).
        val toWrite = ActiveListOps.withCore(active, OfficialDlc.loadsBeforeCore(mods))
        try {
            ModsConfigXml.write(file, toWrite, game?.raw?.ifBlank { null }, expansions)
        } catch (e: IOException) {
            throw IOException("Could not write ${file.absolutePath}: ${e.message}", e)
        } catch (e: SecurityException) {
            throw IOException("No permission to write ${file.absolutePath}", e)
        }
        // Read it back the way the game will: a file that does not come back as written is reported, not trusted.
        val back = ModsConfigXml.read(file)?.activeMods?.let { ActiveListOps.normalize(it) }
        if (back != toWrite) throw IOException("${file.name} was written but does not read back correctly. The previous file is kept as a .bak copy next to it.")
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
