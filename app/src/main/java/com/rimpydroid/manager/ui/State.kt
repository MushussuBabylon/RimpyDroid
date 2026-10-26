package com.rimpydroid.manager.ui

import com.rimpydroid.manager.core.GameVersion
import com.rimpydroid.manager.core.Issue
import com.rimpydroid.manager.core.ModEntry
import com.rimpydroid.manager.core.ResolvedInput
import com.rimpydroid.manager.core.RowSet
import com.rimpydroid.manager.core.SearchSort
import com.rimpydroid.manager.core.Severity
import com.rimpydroid.manager.core.UpdateInfo
import com.rimpydroid.manager.core.UpdateSummary
import com.rimpydroid.manager.core.WorkshopItem
import java.io.File

/** Screens that cover the whole window (the four main tabs live below them). */
sealed class Page {
    object None : Page()
    data class ModDetails(val key: String) : Page()
    object Issues : Page()
    data class FolderPicker(val purpose: PickPurpose) : Page()
    object Backups : Page()
    object Log : Page()
    object Crash : Page()
}

enum class PickPurpose { GAME_DIR, MODS_DIR, CONFIG }

/** A short message for the snackbar, optionally with one action. */
class UiMessage(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

/** Everything about the mods on disk and the load order. */
data class LibraryState(
    val loading: Boolean = false,
    val message: String = "",
    /** A scan has finished at least once. */
    val loaded: Boolean = false,
    val error: String? = null,
    val game: GameVersion? = null,
    val mods: List<ModEntry> = emptyList(),
    val ignoredFolders: List<String> = emptyList(),
    /** ModsConfig.xml exists and was read. */
    val configRead: Boolean = false,
    /** The load order, lower-case package ids. */
    val active: List<String> = emptyList(),
    /** What ModsConfig.xml contains right now. */
    val savedActive: List<String> = emptyList(),
    val rows: RowSet = RowSet(emptyList(), emptyList()),
    val issues: List<Issue> = emptyList(),
    val updates: Map<String, UpdateInfo> = emptyMap(),
    val updateSummary: UpdateSummary = UpdateSummary(0, 0, 0, 0),
    val checkingUpdates: Boolean = false,
    /** Installed Workshop mods by Workshop id. */
    val workshopInstalled: Map<Long, ModEntry> = emptyMap(),
    /** Remarks from the last sort (rules that had to be ignored). */
    val sortNotes: List<String> = emptyList(),
) {
    val dirty: Boolean get() = active != savedActive
    val errorCount: Int get() = issues.count { it.severity == Severity.ERROR }
    val warningCount: Int get() = issues.count { it.severity == Severity.WARNING }
    val activeInstalled: Int get() = rows.active.count { !it.missing }
}

data class RulesInfo(
    val ruleCount: Int = 0,
    /** When the cached file was written (epoch millis), 0 = no cache. */
    val cachedAt: Long = 0L,
    val updating: Boolean = false,
    val error: String? = null,
)

data class WorkshopState(
    val query: String = "",
    val sort: SearchSort = SearchSort.POPULAR,
    val collections: Boolean = false,
    val versionTag: String = "",
    val loading: Boolean = false,
    val items: List<WorkshopItem> = emptyList(),
    val page: Int = 1,
    val hasMore: Boolean = false,
    /** A search or link lookup has been run. */
    val searched: Boolean = false,
    /** Set when the text was Workshop links / ids / a collection. */
    val resolved: ResolvedInput? = null,
    val notes: List<String> = emptyList(),
    val error: String? = null,
)

/** Candidates found by the automatic search in the folder picker. */
data class DetectState(
    val running: Boolean = false,
    val done: Boolean = false,
    val gameDirs: List<String> = emptyList(),
    val configFiles: List<String> = emptyList(),
)

/** Where an item moves in the load order. */
enum class MoveKind { TOP, UP, DOWN, BOTTOM }

/** A copy of ModsConfig.xml made before an older save. */
data class BackupInfo(val file: File, val time: Long, val modCount: Int)
