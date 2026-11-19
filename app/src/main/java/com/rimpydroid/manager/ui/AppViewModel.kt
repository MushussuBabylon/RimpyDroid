package com.rimpydroid.manager.ui

import android.app.Application
import android.media.MediaScannerConnection
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
import com.rimpydroid.manager.core.UpdateState
import com.rimpydroid.manager.core.ResolvedInput
import com.rimpydroid.manager.core.CollectionStore
import com.rimpydroid.manager.core.CollectionRecord
import com.rimpydroid.manager.core.CollectionOps
import com.rimpydroid.manager.core.CollectionMember
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
import com.rimpydroid.manager.core.NameLookup
import com.rimpydroid.manager.core.OfficialDlc
import com.rimpydroid.manager.core.ProfileStore
import com.rimpydroid.manager.core.RulesStore
import com.rimpydroid.manager.core.SearchSort
import com.rimpydroid.manager.core.StorageLayout
import com.rimpydroid.manager.core.UpdateChecker
import com.rimpydroid.manager.core.XmlUtil
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

    // the collections that were downloaded (or added by their link), each kept as one unit
    private val collectionStore = CollectionStore(File(app.filesDir, "collections"))
    private val _collections = MutableStateFlow<List<CollectionRecord>>(emptyList())
    val collections: StateFlow<List<CollectionRecord>> = _collections.asStateFlow()

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
    private var listLookupJob: Job? = null
    private var undoList: List<String>? = null

    /** A change of the owned DLC (from, to) that the load order has not been given yet: the next load, or [applyDlcChange], does it. */
    private var pendingDlc: Pair<List<String>, List<String>>? = null

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
        refreshCollections()
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
        is java.net.UnknownHostException -> tr("No internet connection.")
        is java.net.SocketTimeoutException -> tr("The connection timed out.")
        is HttpStatusException -> if (e.code == 429) tr("Steam is limiting requests, try again in a minute.") else (e.message ?: "HTTP ${e.code}")
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

    /** The page with Steam's website cannot work without Android's WebView: say so and go back. */
    fun browserUnavailable() {
        say(tr("The in-app browser needs Android System WebView, which is missing or switched off on this device. Use the Workshop tab instead."))
        closePage()
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
        val dlcChanged = new.dlcChosen != old.dlcChosen || new.ownedDlc != old.ownedDlc
        if (pathsChanged) {
            reload()
        } else if (dlcChanged) {
            applyDlcChange()
        } else if (new.autoSave && !old.autoSave && _library.value.dirty) {
            scheduleSave()
        }
    }

    // ---- DLC ------------------------------------------------------------------------------------

    /**
     * The person says which DLC they own (the DLC list in Settings, the question after an update). Core and these DLC
     * stand in for a game folder the app cannot see: they join the load order, are written to ModsConfig.xml, and are
     * checked like installed mods. A DLC that is no longer owned leaves the list.
     */
    fun setOwnedDlc(ids: Collection<String>) {
        val old = _config.value
        val after = OfficialDlc.normalize(ids)
        val before = if (old.dlcChosen) old.ownedDlc else emptyList()
        if (old.dlcChosen && before == after) return
        pendingDlc = (pendingDlc?.first ?: before) to after
        updateConfig { it.copy(dlcChosen = true, ownedDlc = after, dlcPrompted = true) }
    }

    /** The question about the DLC was answered with "Not now" or closed: it is not asked again. */
    fun dismissDlcPrompt() = updateConfig { it.copy(dlcPrompted = true) }

    /** Every DLC is ticked when the RimpyDroid folder is chosen: whoever lacks one unticks it. */
    private fun allDlcIds(): List<String> = OfficialDlc.ALL.map { it.id }

    /** Brings the loaded library in line with a changed DLC choice without scanning the mods folder again. */
    private fun applyDlcChange() {
        val s = _library.value
        // a load that is running started with the old choice: run it again with the new one
        if (s.loading) {
            reload()
            return
        }
        val cfg = _config.value
        if (!s.loaded || (cfg.gameDirFile == null && cfg.modsDirFile == null)) return // the next load applies it
        val pend = pendingDlc ?: return
        pendingDlc = null
        val real = s.mods.filterNot { OfficialDlc.isStandIn(it) }
        _library.update { withMods(it, OfficialDlc.withChosen(real, cfg)) }
        val next = OfficialDlc.applyChoice(s.active, pend.first, pend.second, OfficialDlc.loadsBeforeCore(s.mods))
        if (next != s.active) setActive(next) else scheduleRecompute()
    }

    fun applyPick(purpose: PickPurpose, file: File) {
        when (purpose) {
            PickPurpose.GAME_DIR -> updateConfig { it.copy(gameDir = file.absolutePath) }
            PickPurpose.MODS_DIR -> updateConfig { it.copy(modsDir = file.absolutePath) }
            PickPurpose.CONFIG -> updateConfig { it.copy(configPath = GameLocator.configTarget(file).absolutePath) }
            PickPurpose.RIMPY_PARENT -> {
                // the page stays open when the folder cannot be made, so that another place can be chosen
                viewModelScope.launch {
                    val error = useRimpyFolder(StorageLayout.rootIn(file))
                    if (error == null) closePage() else say(error)
                }
                return
            }
        }
        closePage()
    }

    /** Where the RimpyDroid folder is made when it is not given another place: Download/RimpyDroid. */
    val defaultDataDir: String get() = settings.defaultDataDir()

    /**
     * Makes the RimpyDroid folder [root] (with Mods, Config and Lists, and the ".nomedia" file) and uses it for the
     * mods and the load order. Returns a short reason when that is not possible, otherwise null.
     */
    suspend fun useRimpyFolder(root: File): String? {
        val error = withContext(Dispatchers.IO) { StorageLayout.create(root) }
        if (error != null) return if (_hasStorageAccess.value) error else tr("Allow file access first.")
        // The game is not in a folder this app can see, so it cannot find the DLC. Every one of them is ticked from
        // the start (RimpyDroid folder = RimDroid, which has them all): whoever lacks one unticks it in Settings.
        // A choice made earlier is kept.
        if (!_config.value.dlcChosen) pendingDlc = (pendingDlc?.first ?: emptyList()) to allDlcIds()
        updateConfig {
            it.copy(
                gameDir = "",
                dataDir = root.absolutePath,
                modsDir = StorageLayout.modsDir(root).absolutePath,
                configPath = StorageLayout.configFile(root).absolutePath,
                dlcChosen = true,
                ownedDlc = if (it.dlcChosen) it.ownedDlc else allDlcIds(),
                dlcPrompted = true,
            )
        }
        return null
    }

    /**
     * In the RimpyDroid folder an empty ".nomedia" file keeps the previews of the mods out of the phone's gallery. It is
     * made here as well, for a folder that was made before the app did that. Call from a background thread.
     */
    private fun keepOutOfGallery(cfg: AppConfig) {
        val root = cfg.rimpyRoot ?: return
        if (!StorageLayout.ensureNoMedia(root)) return
        try {
            // has the gallery look at the folder again, so that previews it already knows disappear
            MediaScannerConnection.scanFile(getApplication<Application>(), arrayOf(File(root, StorageLayout.NOMEDIA).absolutePath), null, null)
        } catch (e: Exception) {
            AppLog.w("Library", "The gallery could not be told about the .nomedia file", e)
        }
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
            _library.update { it.copy(loading = true, message = tr("Reading mods…"), error = null) }
            try {
                val res = withContext(Dispatchers.IO) {
                    keepOutOfGallery(cfg)
                    Library.load(cfg, rules) { done, total ->
                        _library.update { s -> if (s.loading) s.copy(message = tr("Reading mods… {0} / {1}", done, total)) else s }
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
                // a DLC choice made before this load finished (or before the list was read) joins the load order now
                val pend = pendingDlc
                if (pend != null) {
                    pendingDlc = null
                    val next = OfficialDlc.applyChoice(res.active, pend.first, pend.second, OfficialDlc.loadsBeforeCore(res.mods))
                    if (next != res.active) setActive(next)
                }
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
                say(tr("ModsConfig.xml was changed outside the app, load order refreshed."))
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
            val checkOfficial = !_config.value.ignoresOfficial
            val issues = withContext(Dispatchers.Default) {
                Analyzer.analyze(snap.mods, snap.active, currentRules, snap.game, checkOfficial)
            }
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
            if (undoable) say(UiMessage(message, tr("Undo")) { undo() }) else say(message)
        }
    }

    fun undo() {
        val prev = undoList ?: return
        undoList = null
        setActive(prev, tr("Previous load order restored"))
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
        setActive(r.list, if (extra > 0) (if (extra == 1) tr("Also enabled 1 required mod") else tr("Also enabled {0} required mods", extra)) else null)
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
                say(tr("The list changed while sorting. Tap Sort again."))
                return@launch
            }
            _library.update { it.copy(sortNotes = out.warnings) }
            if (!out.changed) {
                say(tr("The load order is already sorted."))
                return@launch
            }
            val sorted = when (out.warnings.size) {
                0 -> tr("Load order sorted")
                1 -> tr("Load order sorted (1 conflicting rule skipped)")
                else -> tr("Load order sorted ({0} conflicting rules skipped)", out.warnings.size)
            }
            setActive(out.order, sorted, undoable = true)
        }
    }

    fun resetToOfficial() {
        val s = _library.value
        if (_config.value.ignoresOfficial) {
            // The game is not in a folder this app can see: keep what already belongs to the game, drop the mods
            val keep = s.active.filter { ModelOps.isOfficialId(it) }
            setActive(keep, tr("Load order cleared"), undoable = true)
            return
        }
        setActive(ModelOps.initialActive(s.mods, rules), tr("List reset to Core and the installed DLC"), undoable = true)
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
            if (ok) say(tr("Deleted {0}", mod.name)) else say(tr("Could not delete everything in {0}.", mod.dir.name))
        }
    }

    /**
     * Deletes many mod folders at once (the Clean up tool). Core and the DLCs are never touched, and neither is a
     * folder that is being downloaded into right now. Deleted mods also leave the load order.
     */
    fun deleteMods(targets: List<ModEntry>, note: String? = null) {
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
            parts.add(if (gone.size == 1) tr("Deleted 1 mod") else tr("Deleted {0} mods", gone.size))
            if (failed > 0) parts.add(tr("{0} could not be deleted completely", failed))
            if (skipped > 0) parts.add(tr("{0} skipped (being downloaded)", skipped))
            say(parts.joinToString(", ") + (if (note != null) ". $note" else ""))
        }
    }

    /** Takes every enabled mod that does not fit the game version out of the load order. The files stay; Undo is offered. */
    fun disableIncompatible() {
        val s = _library.value
        val ids = com.rimpydroid.manager.core.Cleanup.incompatibleActiveIds(s.mods, s.active, s.game)
        if (ids.isEmpty()) {
            say(tr("None of the enabled mods is incompatible with this game version."))
            return
        }
        val note = if (ids.size == 1) tr("Disabled 1 incompatible mod") else tr("Disabled {0} incompatible mods", ids.size)
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
            if (!quiet) say(tr("Choose the ModsConfig.xml location in Settings first."))
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
            if (!quiet) say(tr("Saved {0} mods to ModsConfig.xml", s.active.size))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("Save", "Could not write ModsConfig.xml", e)
            say(tr("Could not save the load order: {0}", e.message))
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
                say(tr("That backup could not be read."))
                return@launch
            }
            setActive(ActiveListOps.normalize(data.activeMods), tr("Backup loaded"), undoable = true)
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
            if (manual) say(tr("No Workshop mods were found in the Mods folder."))
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
                if (manual) say(tr("Could not check for updates: {0}", describe(e)))
            }
        }
    }

    private fun describeUpdates(s: UpdateSummary): String {
        val parts = ArrayList<String>()
        parts.add(if (s.available == 0) tr("All tracked mods are up to date") else if (s.available == 1) tr("1 update available") else tr("{0} updates available", s.available))
        if (s.untracked > 0) parts.add(tr("{0} without a version record", s.untracked))
        if (s.unavailable > 0) parts.add(tr("{0} no longer on the Workshop", s.unavailable))
        return parts.joinToString(". ") + "."
    }

    fun updateAll(includeUntracked: Boolean) {
        val s = _library.value
        val dir = _config.value.modsDirFile
        if (dir == null) {
            say(tr("Choose the RimWorld folder first (Settings)."))
            return
        }
        val plans = ModelOps.updatePlans(s.mods, s.updates, dir, includeUntracked)
        if (plans.isEmpty()) {
            say(tr("Nothing to update."))
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
            say(tr("Already in the download queue."))
        } else {
            say(if (added == 1) tr("1 download queued.") else tr("{0} downloads queued.", added))
        }
    }

    private fun enqueueItems(items: List<WorkshopItem>) {
        val dir = _config.value.modsDirFile
        if (dir == null) {
            say(tr("Choose the RimWorld folder first (Settings)."))
            return
        }
        if (!_hasStorageAccess.value) {
            say(tr("Allow access to all files first (Settings)."))
            return
        }
        val collections = items.filter { it.isCollection }
        if (collections.isNotEmpty()) {
            // A collection has no files of its own: it is downloaded as the mods it lists.
            viewModelScope.launch {
                try {
                    val res = withContext(Dispatchers.IO) { WorkshopApi.resolveIds(collections.map { it.id }) }
                    for (i in res.items) known[i.id] = i
                    // a collection that is downloaded is kept as one unit in the Collections tab
                    rememberCollections(collections, res.items)
                    enqueueItems(items.filter { !it.isCollection } + res.items)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.w("Download", "Lookup failed", e)
                    say(tr("Could not reach Steam: {0}", describe(e)))
                }
            }
            return
        }
        val plans = ModelOps.plans(items, _library.value.mods, dir)
        if (plans.isEmpty()) {
            say(tr("Nothing can be downloaded from that."))
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
                    say(if (skipped > 0) tr("{0} item(s) are no longer available on the Workshop.", skipped) else tr("Nothing can be downloaded from that."))
                    return@launch
                }
                if (skipped > 0) say(tr("{0} item(s) are no longer available on the Workshop.", skipped))
                enqueueItems(res.items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("Download", "Lookup failed", e)
                say(tr("Could not reach Steam: {0}", describe(e)))
            }
        }
    }

    /** Downloads Workshop items by id (missing dependencies, missing mods of a list). */
    fun downloadMissing(ids: List<Long>) {
        val distinct = ids.distinct()
        if (distinct.isEmpty()) {
            say(tr("None of the missing mods has a Workshop link."))
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
        if (n == 0) say(tr("No failed downloads."))
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
            val installed = if (ok == 1) tr("1 mod installed") else tr("{0} mods installed", ok)
            val text = when {
                failed == 0 -> installed
                ok == 0 -> tr("{0} failed", failed)
                else -> tr("{0}, {1} failed", installed, failed)
            }
            if (failed > 0) say(UiMessage(text, tr("Retry")) { retryFailedDownloads() }) else say(text)
        }
        val list = pendingList
        if (list != null) {
            pendingList = null
            applyList(list)
        }
    }

    // ---- collections --------------------------------------------------------------------------------

    fun refreshCollections() {
        viewModelScope.launch {
            _collections.value = withContext(Dispatchers.IO) {
                try {
                    collectionStore.all()
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }
    }

    /** Writes collection [id] down, or brings its record up to date, with [items] as its mods. */
    private suspend fun saveCollection(id: Long, title: String, items: List<WorkshopItem>): CollectionRecord {
        val now = System.currentTimeMillis()
        val record = withContext(Dispatchers.IO) {
            val old = collectionStore.get(id)
            val made = CollectionRecord(
                id = id,
                title = title.ifBlank { old?.title ?: id.toString() },
                members = items.map { CollectionMember(it.id, it.title) }.distinctBy { it.id },
                addedAt = old?.addedAt ?: now,
                updatedAt = now,
            )
            collectionStore.save(made)
            made
        }
        _collections.value = withContext(Dispatchers.IO) { collectionStore.all() }
        return record
    }

    /**
     * Keeps [collections] that are being downloaded as units in the Collections tab. [members] are the mods of the
     * collection when there is one collection and they are known already; otherwise each collection is read again.
     */
    private fun rememberCollections(collections: List<WorkshopItem>, members: List<WorkshopItem>?) {
        if (collections.isEmpty()) return
        viewModelScope.launch {
            try {
                for (c in collections) {
                    val items = if (collections.size == 1 && members != null) {
                        members
                    } else {
                        withContext(Dispatchers.IO) { WorkshopApi.resolveIds(listOf(c.id)).items }
                    }
                    if (items.isNotEmpty()) saveCollection(c.id, c.title, items)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("Collections", "A downloaded collection could not be kept", e)
            }
        }
    }

    /** The "Download N mods" button of pasted links or a collection: downloads [items] and keeps the collections that were pasted. */
    fun downloadResolved(res: ResolvedInput, items: List<WorkshopItem>) {
        enqueueItems(items)
        val asked = _workshop.value.asked
        val pasted = res.collections.filter { it.id in asked }
        // when other things were pasted too, the mods of the result are not all this collection's: read it on its own
        rememberCollections(pasted, if (pasted.size == asked.size) res.items else null)
    }

    /** What of [items] still has to be downloaded: not installed yet, or installed with a newer version on the Workshop. */
    private fun membersToGet(items: List<WorkshopItem>): List<WorkshopItem> {
        val s = _library.value
        return items.filter { item ->
            val installed = s.workshopInstalled[item.id]
            installed == null || s.updates[installed.key]?.state == UpdateState.UPDATE_AVAILABLE
        }
    }

    /** Adds the collections of the links or ids in [text] to the Collections tab; with [download] their missing mods are downloaded. */
    fun addCollections(text: String, download: Boolean) {
        val ids = WorkshopUtil.extractIds(text).distinct()
        if (ids.isEmpty()) {
            say(tr("No Workshop link or id found in that text."))
            return
        }
        viewModelScope.launch {
            try {
                var added = 0
                var lastTitle = ""
                val toGet = ArrayList<WorkshopItem>()
                for (id in ids) {
                    val res = withContext(Dispatchers.IO) { WorkshopApi.resolveIds(listOf(id)) }
                    val collection = res.collections.firstOrNull { it.id == id } ?: continue
                    for (i in res.items) known[i.id] = i
                    saveCollection(id, collection.title, res.items)
                    added++
                    lastTitle = collection.title
                    toGet.addAll(membersToGet(res.items))
                }
                when (added) {
                    0 -> say(tr("That is not a Workshop collection."))
                    1 -> say(tr("Collection \"{0}\" added.", lastTitle))
                    else -> say(tr("{0} collections added.", added))
                }
                if (download && toGet.isNotEmpty()) enqueueItems(toGet.distinctBy { it.id })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("Collections", "A collection could not be added", e)
                say(tr("Could not reach Steam: {0}", describe(e)))
            }
        }
    }

    /**
     * Reads collection [id] from Steam again and keeps its record up to date. Returns what has to be downloaded
     * (nothing when the collection is gone), or null when Steam could not be asked.
     */
    private suspend fun refreshCollection(id: Long, announce: Boolean): List<WorkshopItem>? {
        val old = _collections.value.firstOrNull { it.id == id } ?: return emptyList()
        try {
            val res = withContext(Dispatchers.IO) { WorkshopApi.resolveIds(listOf(id)) }
            val fresh = res.collections.firstOrNull { it.id == id }
            if (fresh == null || res.items.isEmpty()) {
                say(tr("Collection \"{0}\" is empty or no longer on the Workshop.", old.title))
                return emptyList()
            }
            for (i in res.items) known[i.id] = i
            val record = saveCollection(id, fresh.title, res.items)
            val diff = CollectionOps.diff(old, record.members)
            val toGet = membersToGet(res.items)
            if (announce) {
                if (!diff.isEmpty) {
                    say(tr("\"{0}\": {1} added, {2} removed on the Workshop.", record.title, diff.added.size, diff.removed.size))
                } else if (toGet.isEmpty()) {
                    say(tr("\"{0}\" is up to date.", record.title))
                }
            }
            return toGet
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("Collections", "A collection could not be read", e)
            say(tr("Could not reach Steam: {0}", describe(e)))
            return null
        }
    }

    /** Reads the collection again and downloads what is new in it or has a newer version. */
    fun updateCollection(id: Long) {
        viewModelScope.launch {
            val toGet = refreshCollection(id, announce = true) ?: return@launch
            if (toGet.isNotEmpty()) enqueueItems(toGet)
        }
    }

    /** [updateCollection] for every collection, one after the other (Steam limits how fast it answers). */
    fun updateAllCollections() {
        val ids = _collections.value.map { it.id }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val toGet = LinkedHashMap<Long, WorkshopItem>()
            for (id in ids) {
                val items = refreshCollection(id, announce = false) ?: break
                for (i in items) toGet.putIfAbsent(i.id, i)
            }
            if (toGet.isEmpty()) say(tr("All collections are up to date.")) else enqueueItems(toGet.values.toList())
        }
    }

    /**
     * Makes the installed mods of a collection the load order ([replace]), or adds them to it, with the installed mods
     * they need, and sorts the result. The Core and the DLC that are listed already stay.
     */
    fun applyCollection(id: Long, replace: Boolean) {
        val record = _collections.value.firstOrNull { it.id == id } ?: return
        val s = _library.value
        val resolved = ListApplier.resolve(CollectionOps.asList(record), s.mods, skipOfficial = _config.value.ignoresOfficial)
        if (resolved.activeIds.isEmpty()) {
            say(tr("None of the mods of \"{0}\" is installed yet.", record.title))
            return
        }
        val index = ModIndex(s.mods)
        var list: List<String> = if (replace) s.active.filter { ModelOps.isOfficialId(it) } else s.active
        for (modId in resolved.activeIds) list = ActiveListOps.activateWithDependencies(list, modId, index).list
        val sorted = ModelOps.sortActive(s.mods, list, rules, _config.value.keepOrder).order
        if (!replace && sorted == s.active) {
            say(tr("All installed mods of \"{0}\" are enabled already.", record.title))
        } else {
            setActive(
                sorted,
                if (replace) tr("Applied \"{0}\" and sorted", record.title) else tr("Enabled \"{0}\" and sorted", record.title),
                undoable = true,
            )
        }
        if (resolved.missing.isNotEmpty()) {
            say(tr("{0} mods of \"{1}\" are not installed yet. Update downloads them.", resolved.missing.size, record.title))
        }
    }

    /** Takes a collection out of the tab; with [withMods] the mods that no other collection has go too. */
    fun deleteCollection(id: Long, withMods: Boolean) {
        val record = _collections.value.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            // Harmony is never among the mods that go: the others cannot work without it
            val plan = CollectionOps.planDeletion(record, _collections.value, _library.value.workshopInstalled)
            withContext(Dispatchers.IO) { collectionStore.delete(id) }
            _collections.value = withContext(Dispatchers.IO) { collectionStore.all() }
            val targets = if (withMods) plan.delete else emptyList()
            val keptNote = if (withMods && plan.kept.isNotEmpty()) tr("Harmony was kept: other mods need it.") else null
            if (targets.isNotEmpty()) {
                deleteMods(targets, keptNote)
            } else if (withMods) {
                say(listOfNotNull(tr("Collection \"{0}\" removed.", record.title), keptNote).joinToString(" "))
            } else {
                say(tr("Collection \"{0}\" removed. Its mods are kept.", record.title))
            }
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
            say(tr("Nothing to enable."))
            return
        }
        val enabledNote = if (sortAfter) {
            if (enabled == 1) tr("Enabled 1 new mod and sorted") else tr("Enabled {0} new mods and sorted", enabled)
        } else {
            if (enabled == 1) tr("Enabled 1 new mod") else tr("Enabled {0} new mods", enabled)
        }
        setActive(list, enabledNote, undoable = true)
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

    /** The "Show all versions" button of an empty filtered result. */
    fun showAllVersions() = setVersionTag("")

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
            // What stood there before is cleared, so that the list on the screen is always the answer to the last question
            _workshop.update {
                it.copy(
                    loading = true, error = null, items = emptyList(), resolved = null,
                    hasMore = false, total = null, submitted = q, asked = emptySet(),
                )
            }
            try {
                if (looksLikeLinks(q)) {
                    val res = withContext(Dispatchers.IO) { WorkshopApi.resolveInput(q) }
                    for (i in res.items) known[i.id] = i
                    val asked = WorkshopUtil.extractIds(q).toSet()
                    _workshop.update {
                        it.copy(loading = false, resolved = res, items = emptyList(), page = 1, hasMore = false, searched = true, notes = res.notes, asked = asked)
                    }
                } else {
                    val tag = st.versionTag.ifBlank { null }
                    // Only mods tagged for the chosen version come back (WorkshopApi.search checks every item's own tags).
                    // Nothing is ever shown for another version: when none fit, the screen says so and offers
                    // "Show all versions" instead of quietly listing mods for versions that were not asked for.
                    val shown = withContext(Dispatchers.IO) { WorkshopApi.search(q, st.sort, 1, st.collections, tag) }
                    for (i in shown.items) known[i.id] = i
                    _workshop.update {
                        it.copy(
                            loading = false, resolved = null, items = shown.items, page = shown.page, hasMore = shown.hasMore,
                            total = shown.total, searched = true, notes = emptyList(),
                        )
                    }
                    if (shown.items.isEmpty() && q.isNotEmpty()) AppLog.w("Workshop", "The Workshop search for \"$q\" found nothing (Steam answered with a page of ${shown.htmlLength} characters)")
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
                // the text of the search that is shown, not what has been typed since
                val tag = st.versionTag.ifBlank { null }
                val page = withContext(Dispatchers.IO) {
                    WorkshopApi.search(st.submitted, st.sort, next, st.collections, tag)
                }
                for (i in page.items) known[i.id] = i
                _workshop.update { cur ->
                    val seen = cur.items.map { it.id }.toHashSet()
                    // a filtered search may have read several pages: continue after the last one it read
                    cur.copy(loading = false, items = cur.items + page.items.filter { it.id !in seen }, page = page.page, hasMore = page.hasMore)
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
            say(tr("List \"{0}\" saved.", clean))
            true
        } catch (e: Exception) {
            say(tr("Could not save the list: {0}", e.message))
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
        if (!ok) say(tr("A list with that name already exists."))
        refreshLists()
        return ok
    }

    fun resolveList(list: ModList): ApplyResult =
        ListApplier.resolve(list, _library.value.mods, skipOfficial = _config.value.ignoresOfficial)

    fun applyList(list: ModList) {
        val r = resolveList(list)
        val missing = r.missing.size
        val note = when (missing) {
            0 -> tr("List \"{0}\" applied", list.name)
            1 -> tr("List \"{0}\" applied, 1 mod not installed", list.name)
            else -> tr("List \"{0}\" applied, {1} mods not installed", list.name, missing)
        }
        setActive(r.activeIds, note, undoable = true)
    }

    /**
     * Downloads what the list needs and applies it when the downloads are done. A mod that the list only knows by its
     * name (or its package id) is looked up on the Workshop first; the Workshop ids that are found are kept in the list.
     */
    fun downloadMissingAndApply(list: ModList) {
        val r = resolveList(list)
        // where in the list each missing entry stands (the result keeps the entries themselves, in the list's order)
        val missingAt = ArrayList<Int>()
        var next = 0
        for ((i, e) in list.entries.withIndex()) {
            if (next < r.missing.size && r.missing[next] === e) {
                missingAt.add(i)
                next++
            }
        }
        val direct = missingAt.mapNotNull { list.entries[it].workshopId }.distinct()
        val searches = ArrayList<Pair<Int, NameLookup>>()
        for (i in missingAt) {
            val q = ListApplier.lookupFor(list.entries[i]) ?: continue
            searches.add(i to q)
        }
        if (searches.isEmpty()) {
            if (direct.isEmpty()) {
                applyList(list)
                say(tr("Nothing in this list can be downloaded: it has no Workshop link and no name to look for."))
                return
            }
            pendingList = list
            applyList(list)
            fetchAndEnqueue(direct)
            return
        }
        listLookupJob?.cancel()
        listLookupJob = viewModelScope.launch {
            say(tr("Looking up {0} mods on the Steam Workshop…", searches.size))
            val version = _config.value.versionTag.ifBlank { null } ?: _library.value.game?.short
            val answers = try {
                withContext(Dispatchers.IO) { WorkshopApi.lookupNames(searches.map { it.second }, preferVersion = version) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("Lists", "The mod names could not be looked up", e)
                null
            }
            val found = LinkedHashMap<Int, Long>()
            val notFound = ArrayList<String>()
            var failed = 0
            if (answers != null) {
                for ((k, pair) in searches.withIndex()) {
                    val answer = answers.getOrNull(k) ?: continue
                    val item = answer.item
                    if (item != null) {
                        found[pair.first] = item.id
                        known[item.id] = item
                    } else {
                        if (answer.failed) failed++
                        notFound.add(pair.second.query)
                    }
                }
            }
            val updated = list.withWorkshopIds(found)
            if (found.isNotEmpty()) {
                try {
                    withContext(Dispatchers.IO) { profiles.save(updated) }
                    refreshLists()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.w("Lists", "The Workshop ids could not be kept in the list", e)
                }
            }
            if (answers == null || failed == searches.size) {
                say(tr("The Steam Workshop could not be searched. Check the connection and try again."))
            } else if (notFound.isNotEmpty()) {
                val shown = notFound.take(3).joinToString(", ")
                val more = notFound.size - 3
                val names = if (more > 0) tr("{0} and {1} more", shown, more) else shown
                say(tr("Found {0} of {1} on the Workshop. Not found: {2}", found.size, searches.size, names))
            }
            val ids = (direct + found.values).distinct()
            pendingList = if (ids.isEmpty()) null else updated
            applyList(updated)
            if (ids.isNotEmpty()) fetchAndEnqueue(ids)
        }
    }

    fun importText(text: String, fallbackName: String): ModList? {
        val parsed = ListImporter.parse(text, fallbackName)
        if (parsed == null || parsed.entries.isEmpty()) {
            say(tr("No mods were found in that text."))
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
            say(tr("Imported {0} mods as \"{1}\".", list.entries.size, name))
            list
        } catch (e: Exception) {
            say(tr("Could not save the imported list: {0}", e.message))
            null
        }
    }

    fun importUri(uri: Uri, displayName: String) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { readListFile(uri) }
            if (text == null) {
                say(tr("That file could not be read."))
            } else {
                importText(text, displayName)
            }
        }
    }

    /** The text of a list file, or null when it cannot be read or is too big to be a list (over 8 MB). Never reads more than that. */
    private fun readListFile(uri: Uri): String? {
        val limit = 8 * 1024 * 1024
        return try {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { ins ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = ins.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > limit) return@use null
                    out.write(buffer, 0, n)
                }
                XmlUtil.decode(out.toByteArray())
            }
        } catch (e: Exception) {
            null
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
                if (!silent) say(tr("Community rules updated ({0} rules).", r.ruleCount))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("Rules", "Update failed", e)
                _rulesInfo.update { it.copy(updating = false, error = describe(e)) }
                if (!silent) say(tr("Could not update the rules: {0}", describe(e)))
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
