package com.rimpydroid.manager.ui

import android.app.Application
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rimpydroid.manager.AppLog
import com.rimpydroid.manager.RimpyDroidApp
import com.rimpydroid.manager.Settings
import com.rimpydroid.manager.core.ActiveListOps
import com.rimpydroid.manager.core.Analyzer
import com.rimpydroid.manager.core.AppConfig
import com.rimpydroid.manager.core.ApplyResult
import com.rimpydroid.manager.core.CommunityRules
import com.rimpydroid.manager.core.DownloadPlan
import com.rimpydroid.manager.core.FileUtil
import com.rimpydroid.manager.core.GameLocator
import com.rimpydroid.manager.core.HttpStatusException
import com.rimpydroid.manager.core.Issue
import com.rimpydroid.manager.core.Library
import com.rimpydroid.manager.core.ListCodec
import com.rimpydroid.manager.core.ListApplier
import com.rimpydroid.manager.core.ListEntry
import com.rimpydroid.manager.core.ListImporter
import com.rimpydroid.manager.core.ModEntry
import com.rimpydroid.manager.core.ModIndex
import com.rimpydroid.manager.core.ModList
import com.rimpydroid.manager.core.ModRow
import com.rimpydroid.manager.core.ModScanner
import com.rimpydroid.manager.core.ModSource
import com.rimpydroid.manager.core.ModelOps
import com.rimpydroid.manager.core.ModsConfigXml
import com.rimpydroid.manager.core.ProfileStore
import com.rimpydroid.manager.core.RulesStore
import com.rimpydroid.manager.core.SearchSort
import com.rimpydroid.manager.core.UpdateChecker
import com.rimpydroid.manager.core.UpdateInfo
import com.rimpydroid.manager.core.UpdateSummary
import com.rimpydroid.manager.core.WorkshopApi
import com.rimpydroid.manager.core.WorkshopItem
import com.rimpydroid.manager.core.WorkshopUtil
import com.rimpydroid.manager.service.DownloadQueue
import com.rimpydroid.manager.service.DownloadTask
import com.rimpydroid.manager.service.QueueRequest
import com.rimpydroid.manager.service.TaskStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Holds everything the screens show and performs every action. Disk and network work runs on
 * background dispatchers; state is only ever replaced through the flows below.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = Settings(app)
    private val rulesStore = RulesStore(File(app.filesDir, "community-rules.json"))
    private val profiles = ProfileStore(File(app.filesDir, "lists"))

    private val _config = MutableStateFlow(settings.load())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    private val _library = MutableStateFlow(LibraryState())
    val library: StateFlow<LibraryState> = _library.asStateFlow()

    private val _rulesInfo = MutableStateFlow(RulesInfo())
    val rulesInfo: StateFlow<RulesInfo> = _rulesInfo.asStateFlow()

    private val _workshop = MutableStateFlow(WorkshopState(versionTag = _config.value.versionTag))
    val workshop: StateFlow<WorkshopState> = _workshop.asStateFlow()

    private val _lists = MutableStateFlow<List<ModList>>(emptyList())
    val lists: StateFlow<List<ModList>> = _lists.asStateFlow()

    private val _page = MutableStateFlow<Page>(Page.None)
    val page: StateFlow<Page> = _page.asStateFlow()

    private val _hasStorageAccess = MutableStateFlow(true)
    val hasStorageAccess: StateFlow<Boolean> = _hasStorageAccess.asStateFlow()

    /** Workshop ids of mods installed in this session that the user has not looked at yet. */
    private val _newMods = MutableStateFlow<Set<Long>>(emptySet())
    val newMods: StateFlow<Set<Long>> = _newMods.asStateFlow()

    private val _detect = MutableStateFlow(DetectState())
    val detect: StateFlow<DetectState> = _detect.asStateFlow()

    private val _backups = MutableStateFlow<List<BackupInfo>>(emptyList())
    val backups: StateFlow<List<BackupInfo>> = _backups.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 16)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    val downloads: StateFlow<List<DownloadTask>> = DownloadQueue.tasks

    @Volatile
    private var rules: CommunityRules = CommunityRules.EMPTY

    private var loadJob: Job? = null
    private var analysisJob: Job? = null
    private var saveJob: Job? = null
    private var updatesJob: Job? = null
    private var searchJob: Job? = null
    private var undoList: List<String>? = null

    @Volatile
    private var configStamp = 0L
    private var pendingList: ModList? = null

    /** Items seen in searches and update checks (so that a download does not need another lookup). */
    private val known = ConcurrentHashMap<Long, WorkshopItem>()
    private val installedSinceIdle = HashSet<Long>()
    private val pendingMerges = AtomicInteger(0)

    val storageRoots: List<File> by lazy { computeStorageRoots() }

    init {
        DownloadQueue.maxDownloads = _config.value.maxDownloads
        DownloadQueue.maxParallelMods = _config.value.parallelMods
        rules = rulesStore.cached() ?: CommunityRules.EMPTY
        publishRulesInfo()
        refreshLists()
        viewModelScope.launch { DownloadQueue.installed.collect { onInstalled(it) } }
        viewModelScope.launch { watchQueue() }
        refreshRulesIfStale()
    }

    // ---- small helpers --------------------------------------------------------------------------

    private fun say(text: String) {
        _messages.tryEmit(UiMessage(text))
    }

    private fun say(message: UiMessage) {
        _messages.tryEmit(message)
    }

    private fun describe(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "No internet connection."
        is java.net.SocketTimeoutException -> "The connection timed out."
        is HttpStatusException -> if (e.code == 429) "Steam is limiting requests, try again in a minute." else (e.message ?: "HTTP ${e.code}")
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun withMods(s: LibraryState, mods: List<ModEntry>): LibraryState {
        val ws = HashMap<Long, ModEntry>()
        for (m in mods) {
            val id = m.workshopId ?: continue
            if (!m.isOfficial) ws.putIfAbsent(id, m)
        }
        return s.copy(mods = mods, workshopInstalled = ws)
    }

    // ---- navigation -----------------------------------------------------------------------------

    fun openPage(p: Page) {
        _page.value = p
    }

    fun closePage() {
        _page.value = Page.None
    }

    // ---- lifecycle ------------------------------------------------------------------------------

    /** Called whenever the app comes to the foreground. */
    fun onResume(hasStorage: Boolean) {
        val had = _hasStorageAccess.value
        _hasStorageAccess.value = hasStorage
        val s = _library.value
        if (hasStorage && (!s.loaded || !had) && !s.loading) {
            reload()
            return
        }
        if (hasStorage && s.loaded && !s.loading && !s.dirty) syncConfigFromDisk()
    }

    /** Called when the app goes to the background: pending changes are written right away. */
    fun flushSave() {
        if (_config.value.autoSave && _library.value.dirty) {
            saveJob?.cancel()
            viewModelScope.launch { saveNow(quiet = true) }
        }
    }

    // ---- settings -------------------------------------------------------------------------------

    fun updateConfig(change: (AppConfig) -> AppConfig) {
        val old = _config.value
        val new = change(old)
        if (new == old) return
        _config.value = new
        settings.save(new)
        DownloadQueue.maxDownloads = new.maxDownloads
        DownloadQueue.maxParallelMods = new.parallelMods
        val pathsChanged = new.gameDir != old.gameDir || new.modsDir != old.modsDir || new.configPath != old.configPath
        if (pathsChanged) {
            reload()
        } else if (new.autoSave && !old.autoSave && _library.value.dirty) {
            scheduleSave()
        }
    }

    fun applyPick(purpose: PickPurpose, file: File) {
        when (purpose) {
            PickPurpose.GAME_DIR -> updateConfig { it.copy(gameDir = file.absolutePath) }
            PickPurpose.MODS_DIR -> updateConfig { it.copy(modsDir = file.absolutePath) }
            PickPurpose.CONFIG -> updateConfig { it.copy(configPath = GameLocator.configTarget(file).absolutePath) }
        }
        closePage()
    }

    private fun computeStorageRoots(): List<File> {
        val out = LinkedHashSet<File>()
        try {
            @Suppress("DEPRECATION")
            out.add(Environment.getExternalStorageDirectory())
        } catch (e: Throwable) {
            // not available
        }
        try {
            for (d in getApplication<Application>().getExternalFilesDirs(null)) {
                if (d == null) continue
                val path = d.absolutePath
                val cut = path.indexOf("/Android/")
                if (cut > 0) out.add(File(path.substring(0, cut)))
            }
        } catch (e: Throwable) {
            // ignore
        }
        return out.filter { it.isDirectory }
    }

    fun autoDetect() {
        if (_detect.value.running) return
        _detect.value = DetectState(running = true)
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                GameLocator.discover(storageRoots, maxDepth = 4, timeLimitMs = 12_000)
            }
            _detect.value = DetectState(
                running = false,
                done = true,
                gameDirs = found.gameDirs.map { it.absolutePath },
                configFiles = found.configFiles.map { it.absolutePath },
            )
        }
    }

    // ---- loading --------------------------------------------------------------------------------

    fun reload() {
        loadJob?.cancel()
        saveJob?.cancel()
        val cfg = _config.value
        if (cfg.gameDirFile == null && cfg.modsDirFile == null) {
            _library.value = LibraryState(loaded = true)
            return
        }
        loadJob = viewModelScope.launch {
            _library.update { it.copy(loading = true, message = "Reading mods…", error = null) }
            try {
                val res = withContext(Dispatchers.IO) {
                    Library.load(cfg, rules) { done, total ->
                        _library.update { s -> if (s.loading) s.copy(message = "Reading mods… $done / $total") else s }
                    }
                }
                configStamp = withContext(Dispatchers.IO) { cfg.configFile?.takeIf { it.isFile }?.lastModified() ?: 0L }
                _library.update { s ->
                    val keptUpdates = LinkedHashMap<String, UpdateInfo>()
                    for (m in res.mods) {
                        val old = s.updates[m.key] ?: continue
                        keptUpdates[m.key] = UpdateInfo(UpdateChecker.stateFor(m, old.item), old.item)
                    }
                    withMods(s, res.mods).copy(
                        loading = false,
                        loaded = true,
                        message = "",
                        error = null,
                        game = res.game,
                        ignoredFolders = res.ignoredFolders,
                        configRead = res.configRead,
                        active = res.active,
                        savedActive = res.active,
                        updates = keptUpdates,
                        updateSummary = ModelOps.summarize(keptUpdates),
                        sortNotes = emptyList(),
                    )
                }
                undoList = null
                scheduleRecompute()
                AppLog.i("Library", "Loaded ${res.mods.size} mods, ${res.active.size} active, game ${res.game?.raw ?: "unknown"}")
                if (cfg.autoCheckUpdates) checkUpdates(manual = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("Library", "Load failed", e)
                _library.update { it.copy(loading = false, loaded = true, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun syncConfigFromDisk() {
        val cfg = _config.value
        val file = cfg.configFile ?: return
        viewModelScope.launch {
            val stamp = withContext(Dispatchers.IO) { if (file.isFile) file.lastModified() else 0L }
            if (stamp == configStamp) return@launch
            val ids = withContext(Dispatchers.IO) { Library.readActive(cfg) } ?: return@launch
            configStamp = stamp
            val s = _library.value
            if (s.dirty || s.loading) return@launch
            if (ids != s.active) {
                _library.update { it.copy(active = ids, savedActive = ids, configRead = true) }
                scheduleRecompute()
                say("ModsConfig.xml was changed outside the app, load order refreshed.")
            }
        }
    }

    /** Rebuilds the visible rows at once and the issue list a moment later (it is the slower part). */
    private fun scheduleRecompute() {
        val s = _library.value
        val quick = ModelOps.buildRows(s.mods, s.active, s.issues, s.updates)
        _library.update { it.copy(rows = quick) }
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            delay(250)
            val snap = _library.value
            val currentRules = rules
            val issues = withContext(Dispatchers.Default) { Analyzer.analyze(snap.mods, snap.active, currentRules, snap.game) }
            val rows = withContext(Dispatchers.Default) { ModelOps.buildRows(snap.mods, snap.active, issues, snap.updates) }
            _library.update { it.copy(issues = issues, rows = rows) }
        }
    }

    // ---- load order -----------------------------------------------------------------------------

    private fun setActive(list: List<String>, message: String? = null, undoable: Boolean = false) {
        val s = _library.value
        if (list == s.active) {
            if (message != null) say(message)
            return
        }
        if (undoable) undoList = s.active
        _library.update { it.copy(active = list) }
        scheduleRecompute()
        scheduleSave()
        if (message != null) {
            if (undoable) say(UiMessage(message, "Undo") { undo() }) else say(message)
        }
    }

    fun undo() {
        val prev = undoList ?: return
        undoList = null
        setActive(prev, "Previous load order restored")
    }

    fun toggle(row: ModRow) {
        if (!row.canToggle) return
        if (row.active) {
            setActive(ActiveListOps.deactivate(_library.value.active, row.id))
        } else {
            activate(row.id)
        }
    }

    fun activate(id: String) {
        val s = _library.value
        val r = ActiveListOps.activateWithDependencies(s.active, id, ModIndex(s.mods))
        val extra = r.added.size - 1
        setActive(r.list, if (extra > 0) "Also enabled $extra required mod" + (if (extra == 1) "" else "s") else null)
    }

    fun deactivate(id: String) {
        setActive(ActiveListOps.deactivate(_library.value.active, id))
    }

    fun move(id: String, kind: MoveKind) {
        val active = _library.value.active
        val list = when (kind) {
            MoveKind.TOP -> ActiveListOps.moveTo(active, id, 0)
            MoveKind.UP -> ActiveListOps.moveBy(active, id, -1)
            MoveKind.DOWN -> ActiveListOps.moveBy(active, id, 1)
            MoveKind.BOTTOM -> ActiveListOps.moveTo(active, id, active.size)
        }
        setActive(list)
    }

    fun moveAfter(id: String, anchor: String) {
        setActive(ActiveListOps.moveAfter(_library.value.active, id, anchor))
    }

    fun moveBefore(id: String, anchor: String) {
        setActive(ActiveListOps.moveBefore(_library.value.active, id, anchor))
    }

    fun sortNow() {
        viewModelScope.launch {
            val s = _library.value
            val keep = _config.value.keepOrder
            val currentRules = rules
            val out = withContext(Dispatchers.Default) { ModelOps.sortActive(s.mods, s.active, currentRules, keep) }
            if (_library.value.active != s.active) {
                // the user edited the list while it was being sorted; do not overwrite that edit with an older result
                say("The list changed while sorting. Tap Sort again.")
                return@launch
            }
            _library.update { it.copy(sortNotes = out.warnings) }
            if (!out.changed) {
                say("The load order is already sorted.")
                return@launch
            }
            val note = if (out.warnings.isEmpty()) "" else " (${out.warnings.size} conflicting rule" + (if (out.warnings.size == 1) "" else "s") + " skipped)"
            setActive(out.order, "Load order sorted$note", undoable = true)
        }
    }

    fun resetToOfficial() {
        val s = _library.value
        setActive(ModelOps.initialActive(s.mods, rules), "List reset to Core and the installed DLC", undoable = true)
    }

    fun applyFix(issue: Issue, deactivateId: String? = null) {
        val s = _library.value
        val r = ModelOps.applyFix(issue, s.active, s.mods, rules, _config.value.keepOrder, deactivateId)
        if (r == null) {
            if (issue.workshopIds.isNotEmpty()) downloadMissing(issue.workshopIds)
            return
        }
        setActive(r.active, r.note, undoable = true)
    }

    fun applySafeFixes() {
        val s = _library.value
        val r = ModelOps.applySafeFixes(s.issues, s.active, s.mods, rules, _config.value.keepOrder)
        setActive(r.active, r.note, undoable = true)
    }

    fun deleteMod(mod: ModEntry) {
        if (mod.isOfficial) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { FileUtil.deleteRecursive(mod.dir) }
            _library.update { s ->
                val mods = s.mods.filter { it.key != mod.key }
                val stillInstalled = mod.id.isNotEmpty() && mods.any { it.id == mod.id }
                val newActive = if (stillInstalled) s.active else ActiveListOps.deactivate(s.active, mod.id)
                val updates = s.updates - mod.key
                withMods(s, mods).copy(active = newActive, updates = updates, updateSummary = ModelOps.summarize(updates))
            }
            val open = _page.value
            if (open is Page.ModDetails && open.key == mod.key) closePage()
            scheduleRecompute()
            scheduleSave()
            if (ok) say("Deleted ${mod.name}") else say("Could not delete everything in ${mod.dir.name}.")
        }
    }

    /**
     * Deletes many mod folders at once (the Clean up tool). Core and the DLCs are never touched, and neither is a
     * folder that is being downloaded into right now. Deleted mods also leave the load order.
     */
    fun deleteMods(targets: List<ModEntry>) {
        val wanted = targets.filter { !it.isOfficial }.distinctBy { it.key }
        if (wanted.isEmpty()) return
        viewModelScope.launch {
            val busy = HashSet<String>()
            for (t in DownloadQueue.tasks.value) {
                if (t.status == TaskStatus.QUEUED || t.status == TaskStatus.RUNNING) busy.add(t.installDir)
            }
            val gone = ArrayList<ModEntry>()
            var failed = 0
            var skipped = 0
            withContext(Dispatchers.IO) {
                for (m in wanted) {
                    if (m.dir.absolutePath in busy) {
                        skipped++
                    } else if (FileUtil.deleteRecursive(m.dir)) {
                        gone.add(m)
                    } else {
                        failed++
                    }
                }
            }
            if (gone.isNotEmpty()) {
                val goneKeys = HashSet<String>()
                for (m in gone) goneKeys.add(m.key)
                undoList = null // an old "Undo" must not bring back mods that no longer exist
                _library.update { s ->
                    val mods = s.mods.filter { it.key !in goneKeys }
                    var newActive = s.active
                    for (m in gone) {
                        // an id stays in the load order while another folder still provides it
                        if (m.id.isNotEmpty() && mods.none { it.id == m.id }) newActive = ActiveListOps.deactivate(newActive, m.id)
                    }
                    val updates = s.updates.filterKeys { it !in goneKeys }
                    withMods(s, mods).copy(active = newActive, updates = updates, updateSummary = ModelOps.summarize(updates))
                }
                val open = _page.value
                if (open is Page.ModDetails && open.key in goneKeys) closePage()
                scheduleRecompute()
                scheduleSave()
            }
            val parts = ArrayList<String>()
            parts.add(if (gone.size == 1) "Deleted 1 mod" else "Deleted ${gone.size} mods")
            if (failed > 0) parts.add("$failed could not be deleted completely")
            if (skipped > 0) parts.add("$skipped skipped (being downloaded)")
            say(parts.joinToString(", "))
        }
    }

    /** Takes every enabled mod that does not fit the game version out of the load order. The files stay; Undo is offered. */
    fun disableIncompatible() {
        val s = _library.value
        val ids = com.rimpydroid.manager.core.Cleanup.incompatibleActiveIds(s.mods, s.active, s.game)
        if (ids.isEmpty()) {
            say("None of the enabled mods is incompatible with this game version.")
            return
        }
        val note = if (ids.size == 1) "Disabled 1 incompatible mod" else "Disabled ${ids.size} incompatible mods"
        setActive(ActiveListOps.deactivateAll(s.active, ids), note, undoable = true)
    }

    // ---- saving ---------------------------------------------------------------------------------

    private fun scheduleSave() {
        if (!_config.value.autoSave) return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(700)
            saveNow(quiet = true)
        }
    }

    fun save() {
        viewModelScope.launch { saveNow(quiet = false) }
    }

    /** One save at a time: the file is written through a fixed temporary name, two writers would trip over each other. */
    private val saveLock = Mutex()

    private suspend fun saveNow(quiet: Boolean): Boolean = saveLock.withLock {
        val cfg = _config.value
        val s = _library.value // read after the lock is ours, so it is the newest list
        if (cfg.configFile == null) {
            if (!quiet) say("Choose the ModsConfig.xml location in Settings first.")
            return@withLock false
        }
        if (quiet && !s.dirty) return@withLock true
        try {
            // Once started, the write is finished and recorded even if a newer edit cancels the job that started it.
            withContext(Dispatchers.IO + NonCancellable) {
                Library.save(cfg, s.game, s.mods, s.active)
                configStamp = cfg.configFile?.lastModified() ?: 0L
            }
            _library.update { it.copy(savedActive = s.active, configRead = true) }
            if (!quiet) say("Saved ${s.active.size} mods to ModsConfig.xml")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("Save", "Could not write ModsConfig.xml", e)
            say("Could not save the load order: ${e.message}")
            false
        }
    }

    // ---- backups ----------------------------------------------------------------------------------

    fun refreshBackups() {
        viewModelScope.launch {
            val cfg = _config.value
            _backups.value = withContext(Dispatchers.IO) { listBackups(cfg) }
        }
    }

    private fun listBackups(cfg: AppConfig): List<BackupInfo> {
        val file = cfg.configFile ?: return emptyList()
        val dir = file.absoluteFile.parentFile ?: return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith(file.name + ".bak-") } ?: return emptyList()
        return files.sortedByDescending { it.name }.map { f ->
            BackupInfo(f, f.lastModified(), ModsConfigXml.read(f)?.activeMods?.size ?: 0)
        }
    }

    fun restoreBackup(b: BackupInfo) {
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) { ModsConfigXml.read(b.file) }
            if (data == null) {
                say("That backup could not be read.")
                return@launch
            }
            setActive(ActiveListOps.normalize(data.activeMods), "Backup loaded", undoable = true)
            closePage()
        }
    }

    fun deleteBackup(b: BackupInfo) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { b.file.delete() }
            refreshBackups()
        }
    }

    // ---- updates ----------------------------------------------------------------------------------

    fun checkUpdates(manual: Boolean) {
        val s = _library.value
        val targets = s.mods.filter { !it.isOfficial && it.workshopId != null }
        if (targets.isEmpty()) {
            if (manual) say("No Workshop mods were found in the Mods folder.")
            return
        }
        if (s.checkingUpdates) return
        updatesJob = viewModelScope.launch {
            _library.update { it.copy(checkingUpdates = true) }
            try {
                val result = withContext(Dispatchers.IO) { UpdateChecker.check(targets) }
                for (info in result.values) {
                    val item = info.item
                    if (item != null) known[item.id] = item
                }
                val summary = ModelOps.summarize(result)
                _library.update { it.copy(updates = result, updateSummary = summary, checkingUpdates = false) }
                scheduleRecompute()
                if (manual) say(describeUpdates(summary))
            } catch (e: CancellationException) {
                _library.update { it.copy(checkingUpdates = false) }
                throw e
            } catch (e: Exception) {
                _library.update { it.copy(checkingUpdates = false) }
                AppLog.w("Updates", "Check failed", e)
                if (manual) say("Could not check for updates: ${describe(e)}")
            }
        }
    }

    private fun describeUpdates(s: UpdateSummary): String {
        val parts = ArrayList<String>()
        parts.add(if (s.available == 0) "All tracked mods are up to date" else "${s.available} update" + (if (s.available == 1) "" else "s") + " available")
        if (s.untracked > 0) parts.add("${s.untracked} without a version record")
        if (s.unavailable > 0) parts.add("${s.unavailable} no longer on the Workshop")
        return parts.joinToString(". ") + "."
    }

    fun updateAll(includeUntracked: Boolean) {
        val s = _library.value
        val dir = _config.value.modsDirFile
        if (dir == null) {
            say("Choose the RimWorld folder first (Settings).")
            return
        }
        val plans = ModelOps.updatePlans(s.mods, s.updates, dir, includeUntracked)
        if (plans.isEmpty()) {
            say("Nothing to update.")
            return
        }
        enqueuePlans(plans)
    }

    /** Updates a mod, or fetches it again when its version is unknown (only changed files are transferred). */
    fun downloadForMod(mod: ModEntry) {
        val id = mod.workshopId ?: return
        val item = known[id]
        if (item != null && item.isAvailable) {
            enqueueItems(listOf(item))
        } else {
            fetchAndEnqueue(listOf(id))
        }
    }

    // ---- downloads --------------------------------------------------------------------------------

    private fun enqueuePlans(plans: List<DownloadPlan>) {
        if (plans.isEmpty()) return
        DownloadQueue.maxDownloads = _config.value.maxDownloads
        val added = DownloadQueue.enqueue(
            plans.map {
                QueueRequest(it.workshopId, it.title, it.installDir, it.isUpdate, it.expectedManifest, it.timeUpdated, it.sizeHint)
            },
        )
        if (added == 0) {
            say("Already in the download queue.")
        } else {
            say("$added download" + (if (added == 1) "" else "s") + " queued.")
        }
    }

    private fun enqueueItems(items: List<WorkshopItem>) {
        val dir = _config.value.modsDirFile
        if (dir == null) {
            say("Choose the RimWorld folder first (Settings).")
            return
        }
        if (!_hasStorageAccess.value) {
            say("Allow access to all files first (Settings).")
            return
        }
        val collections = items.filter { it.isCollection }
        if (collections.isNotEmpty()) {
            // A collection has no files of its own: it is downloaded as the mods it lists.
            viewModelScope.launch {
                try {
                    val res = withContext(Dispatchers.IO) { WorkshopApi.resolveIds(collections.map { it.id }) }
                    for (i in res.items) known[i.id] = i
                    enqueueItems(items.filter { !it.isCollection } + res.items)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.w("Download", "Lookup failed", e)
                    say("Could not reach Steam: ${describe(e)}")
                }
            }
            return
        }
        val plans = ModelOps.plans(items, _library.value.mods, dir)
        if (plans.isEmpty()) {
            say("Nothing can be downloaded from that.")
            return
        }
        enqueuePlans(plans)
    }

    private fun fetchAndEnqueue(ids: List<Long>) {
        viewModelScope.launch {
            try {
                // collections among the ids become the mods they list
                val res = withContext(Dispatchers.IO) { WorkshopApi.resolveIds(ids) }
                for (i in res.items) known[i.id] = i
                val skipped = res.notes.count { !it.startsWith("Collection ") }
                if (res.items.isEmpty()) {
                    say(if (skipped > 0) "$skipped item(s) are no longer available on the Workshop." else "Nothing can be downloaded from that.")
                    return@launch
                }
                if (skipped > 0) say("$skipped item(s) are no longer available on the Workshop.")
                enqueueItems(res.items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("Download", "Lookup failed", e)
                say("Could not reach Steam: ${describe(e)}")
            }
        }
    }

    /** Downloads Workshop items by id (missing dependencies, missing mods of a list). */
    fun downloadMissing(ids: List<Long>) {
        val distinct = ids.distinct()
        if (distinct.isEmpty()) {
            say("None of the missing mods has a Workshop link.")
            return
        }
        fetchAndEnqueue(distinct)
    }

    fun downloadAllMissing() {
        downloadMissing(ModelOps.downloadableIds(_library.value.issues))
    }

    fun downloadItem(item: WorkshopItem) = enqueueItems(listOf(item))

    fun downloadItems(items: List<WorkshopItem>) = enqueueItems(items)

    fun cancelDownload(id: Long) = DownloadQueue.cancel(id)

    fun cancelAllDownloads() = DownloadQueue.cancelAll()

    fun retryFailedDownloads() {
        val n = DownloadQueue.retryFailed()
        if (n == 0) say("No failed downloads.")
    }

    fun clearFinishedDownloads() = DownloadQueue.clearFinished()

    private fun onInstalled(workshopId: Long) {
        installedSinceIdle.add(workshopId)
        val task = DownloadQueue.tasks.value.firstOrNull { it.workshopId == workshopId } ?: return
        pendingMerges.incrementAndGet()
        viewModelScope.launch {
            try {
                val game = _library.value.game
                val entry = withContext(Dispatchers.IO) { ModScanner.loadOne(File(task.installDir), ModSource.LOCAL, game) }
                if (entry == null) {
                    AppLog.w("Library", "Installed ${task.installDir} but it has no readable About.xml")
                    return@launch
                }
                _library.update { mergeEntry(it, entry) }
                if (!task.isUpdate) _newMods.update { it + workshopId }
                scheduleRecompute()
            } finally {
                pendingMerges.decrementAndGet()
            }
        }
    }

    private fun mergeEntry(s: LibraryState, entry: ModEntry): LibraryState {
        val mods = s.mods.filter { it.key != entry.key } + entry
        val item = entry.workshopId?.let { known[it] }
        val updates = if (item != null) {
            s.updates + (entry.key to UpdateInfo(UpdateChecker.stateFor(entry, item), item))
        } else {
            s.updates - entry.key
        }
        return withMods(s, mods).copy(updates = updates, updateSummary = ModelOps.summarize(updates))
    }

    private suspend fun watchQueue() {
        var wasBusy = false
        DownloadQueue.tasks.collect { list ->
            val busy = list.any { it.status == TaskStatus.QUEUED || it.status == TaskStatus.RUNNING }
            if (wasBusy && !busy) {
                wasBusy = false
                // the last "installed" event and the reading of the new folder arrive a moment later
                delay(500)
                while (pendingMerges.get() > 0) delay(100)
                if (!DownloadQueue.isBusy) onQueueIdle(DownloadQueue.tasks.value)
            } else {
                wasBusy = busy
            }
        }
    }

    private fun onQueueIdle(tasks: List<DownloadTask>) {
        val ok = installedSinceIdle.size
        installedSinceIdle.clear()
        val failed = tasks.count { it.status == TaskStatus.FAILED }
        if (ok > 0 || failed > 0) {
            val text = buildString {
                if (ok > 0) append(if (ok == 1) "1 mod installed" else "$ok mods installed")
                if (failed > 0) {
                    if (isNotEmpty()) append(", ")
                    append("$failed failed")
                }
            }
            if (failed > 0) say(UiMessage(text, "Retry") { retryFailedDownloads() }) else say(text)
        }
        val list = pendingList
        if (list != null) {
            pendingList = null
            applyList(list)
        }
    }

    // ---- new mods banner ----------------------------------------------------------------------------

    fun enableNewMods(sortAfter: Boolean) {
        val s = _library.value
        val ids = _newMods.value
        val index = ModIndex(s.mods)
        var list = s.active
        var enabled = 0
        for (row in s.rows.inactive) {
            val wid = row.mod?.workshopId ?: continue
            if (wid !in ids || !row.canToggle) continue
            val r = ActiveListOps.activateWithDependencies(list, row.id, index)
            enabled += r.added.size
            list = r.list
        }
        _newMods.value = emptySet()
        if (sortAfter) list = ModelOps.sortActive(s.mods, list, rules, _config.value.keepOrder).order
        if (enabled == 0 && list == s.active) {
            say("Nothing to enable.")
            return
        }
        setActive(list, "Enabled $enabled new mod" + (if (enabled == 1) "" else "s") + (if (sortAfter) " and sorted" else ""), undoable = true)
    }

    fun dismissNewMods() {
        _newMods.value = emptySet()
    }

    // ---- Workshop ---------------------------------------------------------------------------------

    fun setQuery(q: String) = _workshop.update { it.copy(query = q) }

    fun setSort(sort: SearchSort) {
        _workshop.update { it.copy(sort = sort) }
        submitSearch()
    }

    fun setCollections(on: Boolean) {
        _workshop.update { it.copy(collections = on) }
        submitSearch()
    }

    fun setVersionTag(tag: String) {
        _workshop.update { it.copy(versionTag = tag) }
        updateConfig { it.copy(versionTag = tag) }
        submitSearch()
    }

    fun ensureWorkshopLoaded() {
        val w = _workshop.value
        if (!w.searched && !w.loading) submitSearch()
    }

    private fun looksLikeLinks(text: String): Boolean {
        if (WorkshopUtil.extractIds(text).isEmpty()) return false
        return text.contains("steamcommunity.com", ignoreCase = true) ||
            text.contains("steam://", ignoreCase = true) ||
            text.matches(Regex("""[\d\s,;]+"""))
    }

    fun submitSearch() {
        val st = _workshop.value
        val q = st.query.trim()
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _workshop.update { it.copy(loading = true, error = null) }
            try {
                if (looksLikeLinks(q)) {
                    val res = withContext(Dispatchers.IO) { WorkshopApi.resolveInput(q) }
                    for (i in res.items) known[i.id] = i
                    _workshop.update {
                        it.copy(loading = false, resolved = res, items = emptyList(), page = 1, hasMore = false, searched = true, notes = res.notes)
                    }
                } else {
                    val page = withContext(Dispatchers.IO) {
                        WorkshopApi.search(q, st.sort, 1, st.collections, st.versionTag.ifBlank { null })
                    }
                    for (i in page.items) known[i.id] = i
                    _workshop.update {
                        it.copy(loading = false, resolved = null, items = page.items, page = 1, hasMore = page.hasMore, searched = true, notes = emptyList())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("Workshop", "Search failed", e)
                _workshop.update { it.copy(loading = false, error = describe(e), searched = true) }
            }
        }
    }

    fun loadMore() {
        val st = _workshop.value
        if (st.loading || !st.hasMore || st.resolved != null) return
        searchJob = viewModelScope.launch {
            _workshop.update { it.copy(loading = true, error = null) }
            try {
                val next = st.page + 1
                val page = withContext(Dispatchers.IO) {
                    WorkshopApi.search(st.query.trim(), st.sort, next, st.collections, st.versionTag.ifBlank { null })
                }
                for (i in page.items) known[i.id] = i
                _workshop.update { cur ->
                    val seen = cur.items.map { it.id }.toHashSet()
                    cur.copy(loading = false, items = cur.items + page.items.filter { it.id !in seen }, page = next, hasMore = page.hasMore)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _workshop.update { it.copy(loading = false, error = describe(e)) }
            }
        }
    }

    /** Shows the mods of a collection (the collection page link is resolved like a pasted link). */
    fun openCollection(item: WorkshopItem) {
        _workshop.update { it.copy(query = item.url) }
        submitSearch()
    }

    // ---- lists --------------------------------------------------------------------------------------

    fun refreshLists() {
        viewModelScope.launch {
            _lists.value = withContext(Dispatchers.IO) {
                try {
                    profiles.all()
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }
    }

    fun listExists(name: String): Boolean = profiles.exists(name)

    private fun currentAsList(name: String): ModList {
        val s = _library.value
        val index = ModIndex(s.mods)
        val entries = s.active.map { id ->
            val m = index[id]
            ListEntry(id, m?.name, m?.workshopId)
        }
        return ModList(name.trim(), entries, s.game?.short, System.currentTimeMillis())
    }

    fun saveList(name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty()) return false
        return try {
            profiles.save(currentAsList(clean))
            refreshLists()
            say("List \"$clean\" saved.")
            true
        } catch (e: Exception) {
            say("Could not save the list: ${e.message}")
            false
        }
    }

    fun deleteList(name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { profiles.delete(name) }
            refreshLists()
        }
    }

    fun renameList(old: String, new: String): Boolean {
        val clean = new.trim()
        if (clean.isEmpty()) return false
        val ok = try {
            profiles.rename(old, clean)
        } catch (e: Exception) {
            false
        }
        if (!ok) say("A list with that name already exists.")
        refreshLists()
        return ok
    }

    fun resolveList(list: ModList): ApplyResult = ListApplier.resolve(list, _library.value.mods)

    fun applyList(list: ModList) {
        val r = resolveList(list)
        val missing = r.missing.size
        val note = "List \"${list.name}\" applied" + if (missing > 0) ", $missing mod" + (if (missing == 1) "" else "s") + " not installed" else ""
        setActive(r.activeIds, note, undoable = true)
    }

    /** Downloads what the list needs and applies it when the downloads are done. */
    fun downloadMissingAndApply(list: ModList) {
        val r = resolveList(list)
        val ids = r.missing.mapNotNull { it.workshopId }.distinct()
        if (ids.isEmpty()) {
            applyList(list)
            return
        }
        pendingList = list
        applyList(list)
        fetchAndEnqueue(ids)
    }

    fun importText(text: String, fallbackName: String): ModList? {
        val parsed = ListImporter.parse(text, fallbackName)
        if (parsed == null || parsed.entries.isEmpty()) {
            say("No mods were found in that text.")
            return null
        }
        var name = parsed.name.ifBlank { fallbackName }
        var n = 2
        val base = name
        while (profiles.exists(name)) {
            name = "$base ($n)"
            n++
        }
        val list = parsed.copy(name = name)
        return try {
            profiles.save(list)
            refreshLists()
            say("Imported ${list.entries.size} mods as \"$name\".")
            list
        } catch (e: Exception) {
            say("Could not save the imported list: ${e.message}")
            null
        }
    }

    fun importUri(uri: Uri, displayName: String) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { ins ->
                        val bytes = ins.readBytes()
                        if (bytes.size > 8 * 1024 * 1024) null else String(bytes, Charsets.UTF_8)
                    }
                } catch (e: Exception) {
                    null
                }
            }
            if (text == null) {
                say("That file could not be read.")
            } else {
                importText(text, displayName)
            }
        }
    }

    fun exportJson(list: ModList): String = ListCodec.toJson(list)

    fun exportText(list: ModList): String = ListCodec.toText(list)

    // ---- community rules -------------------------------------------------------------------------------

    private fun publishRulesInfo() {
        _rulesInfo.value = RulesInfo(ruleCount = rules.ruleCount, cachedAt = rulesStore.cachedAt())
    }

    private fun refreshRulesIfStale() {
        val age = System.currentTimeMillis() - rulesStore.cachedAt()
        if (rules.ruleCount == 0 || age > 14L * 24 * 60 * 60 * 1000) updateRules(silent = true)
    }

    fun updateRules(silent: Boolean = false) {
        if (_rulesInfo.value.updating) return
        viewModelScope.launch {
            _rulesInfo.update { it.copy(updating = true, error = null) }
            try {
                val r = withContext(Dispatchers.IO) { rulesStore.download() }
                rules = r
                publishRulesInfo()
                scheduleRecompute()
                if (!silent) say("Community rules updated (${r.ruleCount} rules).")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("Rules", "Update failed", e)
                _rulesInfo.update { it.copy(updating = false, error = describe(e)) }
                if (!silent) say("Could not update the rules: ${describe(e)}")
            }
        }
    }

    // ---- diagnostics ---------------------------------------------------------------------------------

    fun crashText(): String? {
        val f = File(getApplication<Application>().filesDir, RimpyDroidApp.CRASH_FILE)
        return if (f.isFile) FileUtil.readTextOrNull(f, 256 * 1024) else null
    }

    fun clearCrash() {
        File(getApplication<Application>().filesDir, RimpyDroidApp.CRASH_FILE).delete()
    }
}
