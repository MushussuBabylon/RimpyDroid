package com.rimpydroid.manager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rimpydroid.manager.core.FileUtil
import com.rimpydroid.manager.core.StorageLayout
import com.rimpydroid.manager.service.DownloadTask
import com.rimpydroid.manager.service.TaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The whole UI: the four tabs, full-window pages on top, and the snackbar for short messages. */
@Composable
fun AppRoot(vm: AppViewModel, onGrantStorage: () -> Unit) {
    val cfg by vm.config.collectAsState()
    ProvideLanguage(cfg.language) {
        AppContent(vm, onGrantStorage)
    }
}

@Composable
private fun AppContent(vm: AppViewModel, onGrantStorage: () -> Unit) {
    val page by vm.page.collectAsState()
    val cfg by vm.config.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    ListsKeptInDownloads(vm)
    CollectionsKeptInDownloads(vm)

    LaunchedEffect(Unit) {
        vm.messages.collect { message ->
            val result = snackbar.showSnackbar(
                message = message.text,
                actionLabel = message.actionLabel,
                duration = if (message.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) message.action?.invoke()
        }
    }

    BackHandler(enabled = page != Page.None) { vm.closePage() }

    Box(Modifier.fillMaxSize()) {
        // a new installation starts with the welcome guide; the tabs follow it
        if (cfg.onboarded) MainShell(vm, onGrantStorage) else OnboardingScreen(vm, onGrantStorage)
        if (cfg.onboarded) DlcPromptHost(vm)
        if (page != Page.None) {
            // opaque in both styles: in Liquid glass the page paints the gradient itself (see PageSurface)
            PageSurface {
                when (val p = page) {
                    is Page.ModDetails -> ModDetailsPage(vm, p.key)
                    is Page.FolderPicker -> FolderPickerPage(vm, p.purpose)
                    Page.Issues -> IssuesPage(vm)
                    Page.Backups -> BackupsPage(vm)
                    Page.Log -> LogPage(vm, crash = false)
                    Page.Crash -> LogPage(vm, crash = true)
                    Page.Help -> HelpPage(vm)
                    Page.Browse -> BrowsePage(vm)
                    Page.None -> {}
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = if (page == Page.None && cfg.onboarded) (if (LocalGlass.current) 96.dp else 80.dp) else 16.dp),
        ) { data -> GlassSnackbar(data) }
    }
}

/** The message at the bottom: a small pane of glass in Liquid glass, Material's own snackbar otherwise. */
@Composable
private fun GlassSnackbar(data: SnackbarData) {
    if (!rememberRealGlass()) {
        Snackbar(data)
        return
    }
    val dark = LocalDarkTheme.current
    val shape = MaterialTheme.shapes.large
    Snackbar(
        snackbarData = data,
        modifier = Modifier.padding(horizontal = 12.dp).glassBackground(shape, dark, kind = GlassKind.SHEET),
        shape = shape,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        actionColor = MaterialTheme.colorScheme.primary,
    )
}

/**
 * Keeps a copy of the saved lists in Download/RimpyDroid/Lists, so that they are still there after the app was removed
 * and installed again: once the app may use the storage, lists that only the copy has come back, and from then on every
 * change (also a deleted list) is repeated in the copy.
 */
@Composable
private fun ListsKeptInDownloads(vm: AppViewModel) {
    val cfg by vm.config.collectAsState()
    val hasStorage by vm.hasStorageAccess.collectAsState()
    val lists by vm.lists.collectAsState()
    val context = LocalContext.current
    val local = remember { File(context.filesDir, "lists") }
    val shared = cfg.dataDirFile?.let { StorageLayout.listsDir(it) }
    var ready by remember(shared, hasStorage) { mutableStateOf(false) }
    var previous by remember(shared) { mutableStateOf<Set<String>?>(null) }

    LaunchedEffect(shared, hasStorage) {
        if (shared == null || !hasStorage) return@LaunchedEffect
        val restored = withContext(Dispatchers.IO) { StorageLayout.restoreLists(local, shared) }
        if (restored > 0) vm.refreshLists()
        ready = true
    }
    LaunchedEffect(lists, ready) {
        if (!ready || shared == null) return@LaunchedEffect
        val names = lists.map { it.name }.toSet()
        val gone = previous?.minus(names).orEmpty()
        previous = names
        val goneFiles = gone.map { FileUtil.safeFileName(it) + ".json" }
        withContext(Dispatchers.IO) { StorageLayout.mirrorLists(local, shared, goneFiles) }
    }
}

/**
 * Like [ListsKeptInDownloads], for the downloaded collections: a copy in the "Collections" folder of the RimpyDroid
 * folder, so that the records survive an uninstall (the mods themselves are in the Mods folder next to it).
 */
@Composable
private fun CollectionsKeptInDownloads(vm: AppViewModel) {
    val cfg by vm.config.collectAsState()
    val hasStorage by vm.hasStorageAccess.collectAsState()
    val collections by vm.collections.collectAsState()
    val context = LocalContext.current
    val local = remember { File(context.filesDir, "collections") }
    val shared = cfg.dataDirFile?.let { StorageLayout.collectionsDir(it) }
    var ready by remember(shared, hasStorage) { mutableStateOf(false) }
    var previous by remember(shared) { mutableStateOf<Set<Long>?>(null) }

    LaunchedEffect(shared, hasStorage) {
        if (shared == null || !hasStorage) return@LaunchedEffect
        val restored = withContext(Dispatchers.IO) { StorageLayout.restoreLists(local, shared) }
        if (restored > 0) vm.refreshCollections()
        ready = true
    }
    LaunchedEffect(collections, ready) {
        if (!ready || shared == null) return@LaunchedEffect
        val ids = collections.map { it.id }.toSet()
        val gone = previous?.minus(ids).orEmpty()
        previous = ids
        // nothing to copy and nothing to take away: the folder is not made for nothing
        if (ids.isEmpty() && gone.isEmpty()) return@LaunchedEffect
        val goneFiles = gone.map { "$it.json" }
        withContext(Dispatchers.IO) { StorageLayout.mirrorLists(local, shared, goneFiles) }
    }
}

@Composable
private fun MainShell(vm: AppViewModel, onGrantStorage: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tasks by vm.downloads.collectAsState()

    Scaffold(
        topBar = { MainTopBar(vm, tab) },
        bottomBar = {
            Column {
                DownloadMiniBar(tasks, onClick = { tab = 1 })
                if (LocalGlass.current) {
                    // a floating pane of glass; the window insets move to the outside so that the pane keeps its size
                    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        AppPanel(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                            NavigationBar(
                                containerColor = Color.Transparent,
                                tonalElevation = 0.dp,
                                windowInsets = WindowInsets(0, 0, 0, 0),
                            ) { TabItems(tab) { tab = it } }
                        }
                    }
                } else {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                        TabItems(tab) { tab = it }
                    }
                }
            }
        },
    ) { padding ->
        when (tab) {
            0 -> ModsScreen(vm, padding, onGrantStorage)
            1 -> WorkshopScreen(vm, padding)
            2 -> CollectionsScreen(vm, padding)
            3 -> ListsScreen(vm, padding)
            else -> SettingsScreen(vm, padding, onGrantStorage)
        }
    }
}

/** The five tabs of the bottom bar; the same items serve the classic bar and the glass one. */
@Composable
private fun RowScope.TabItems(tab: Int, onTab: (Int) -> Unit) {
    NavigationBarItem(
        selected = tab == 0,
        onClick = { onTab(0) },
        icon = { Icon(Icons.Default.Home, contentDescription = null) },
        label = { Text(tr("Mods")) },
    )
    NavigationBarItem(
        selected = tab == 1,
        onClick = { onTab(1) },
        icon = { Icon(Icons.Default.Search, contentDescription = null) },
        label = { Text(tr("Workshop")) },
    )
    NavigationBarItem(
        selected = tab == 2,
        onClick = { onTab(2) },
        icon = { Icon(Icons.Default.Star, contentDescription = null) },
        label = { Text(tr("Collections")) },
    )
    NavigationBarItem(
        selected = tab == 3,
        onClick = { onTab(3) },
        icon = { Icon(Icons.Default.Menu, contentDescription = null) },
        label = { Text(tr("Lists")) },
    )
    NavigationBarItem(
        selected = tab == 4,
        onClick = { onTab(4) },
        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
        label = { Text(tr("Settings")) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar(vm: AppViewModel, tab: Int) {
    val lib by vm.library.collectAsState()
    val cfg by vm.config.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var showCleanup by remember { mutableStateOf(false) }
    if (showCleanup) CleanupDialog(vm = vm, onDismiss = { showCleanup = false })
    val title = when (tab) {
        0 -> tr("Mods")
        1 -> tr("Workshop")
        2 -> tr("Collections")
        3 -> tr("Lists")
        else -> tr("Settings")
    }
    TopAppBar(
        title = { Text(title) },
        colors = appBarColors(),
        actions = {
            when (tab) {
                0 -> {
                    if (lib.dirty && !cfg.autoSave) {
                        TextButton(onClick = { vm.save() }) { Text(tr("Save")) }
                    }
                    TextButton(onClick = { vm.sortNow() }) { Text(tr("Sort")) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = tr("More")) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(tr("Problems ({0})", lib.issues.size)) },
                                onClick = {
                                    menu = false
                                    vm.openPage(Page.Issues)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (lib.checkingUpdates) tr("Checking for updates…") else tr("Check for updates")) },
                                onClick = {
                                    menu = false
                                    vm.checkUpdates(true)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("Update all ({0})", lib.updateSummary.available)) },
                                enabled = lib.updateSummary.available > 0,
                                onClick = {
                                    menu = false
                                    vm.updateAll(false)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("Fix problems automatically")) },
                                onClick = {
                                    menu = false
                                    vm.applySafeFixes()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("Clean up mods…")) },
                                onClick = {
                                    menu = false
                                    showCleanup = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("Reload from disk")) },
                                onClick = {
                                    menu = false
                                    vm.reload()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (cfg.ignoresOfficial) tr("Clear the load order") else tr("Reset to Core and DLC")) },
                                onClick = {
                                    menu = false
                                    vm.resetToOfficial()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("ModsConfig.xml backups")) },
                                onClick = {
                                    menu = false
                                    vm.openPage(Page.Backups)
                                },
                            )
                        }
                    }
                }

                1 -> {
                    TextButton(onClick = { vm.openPage(Page.Browse) }) { Text(tr("Browse")) }
                    IconButton(onClick = { vm.submitSearch() }) { Icon(Icons.Default.Refresh, contentDescription = tr("Refresh")) }
                }

                4 -> {
                    // the (?) at the top of Settings: the help, with a search box
                    QuestionButton(description = tr("Help & guide"), onClick = { vm.openPage(Page.Help) })
                }

                else -> {}
            }
        },
    )
}

/** Thin progress strip above the tab bar while something downloads; tapping it opens the Workshop tab. */
@Composable
private fun DownloadMiniBar(tasks: List<DownloadTask>, onClick: () -> Unit) {
    val active = tasks.filter { it.status == TaskStatus.RUNNING || it.status == TaskStatus.QUEUED }
    if (active.isEmpty()) return
    val running = active.filter { it.status == TaskStatus.RUNNING }
    // overall progress of everything that was not cancelled; finished mods count as complete
    val counted = tasks.filter { it.status != TaskStatus.CANCELLED }
    var sum = 0f
    for (t in counted) {
        sum += when (t.status) {
            TaskStatus.DONE, TaskStatus.FAILED -> 1f
            TaskStatus.RUNNING -> t.percent
            else -> 0f
        }
    }
    val overall = if (counted.isNotEmpty()) (sum / counted.size).coerceIn(0f, 1f) else 0f
    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        running.size > 1 -> tr("Downloading {0} mods at once", running.size)
                        running.size == 1 -> tr("Downloading: {0}", running[0].title)
                        else -> tr("Downloads waiting")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(tr("{0} left", active.size), style = MaterialTheme.typography.labelMedium)
            }
            LinearProgressIndicator(
                progress = { overall },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
    if (LocalGlass.current) {
        AppPanel(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp), shape = MaterialTheme.shapes.large, content = body)
    } else {
        Surface(modifier = Modifier.fillMaxWidth(), tonalElevation = 3.dp, content = body)
    }
}
