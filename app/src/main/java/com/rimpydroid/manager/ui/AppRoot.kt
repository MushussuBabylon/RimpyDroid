package com.rimpydroid.manager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rimpydroid.manager.service.DownloadTask
import com.rimpydroid.manager.service.TaskStatus

/** The whole UI: the four tabs, full-window pages on top, and the snackbar for short messages. */
@Composable
fun AppRoot(vm: AppViewModel, onGrantStorage: () -> Unit) {
    val page by vm.page.collectAsState()
    val snackbar = remember { SnackbarHostState() }

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
        MainShell(vm, onGrantStorage)
        if (page != Page.None) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                when (val p = page) {
                    is Page.ModDetails -> ModDetailsPage(vm, p.key)
                    is Page.FolderPicker -> FolderPickerPage(vm, p.purpose)
                    Page.Issues -> IssuesPage(vm)
                    Page.Backups -> BackupsPage(vm)
                    Page.Log -> LogPage(vm, crash = false)
                    Page.Crash -> LogPage(vm, crash = true)
                    Page.None -> {}
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = if (page == Page.None) 80.dp else 16.dp),
        )
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
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text("Mods") },
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Default.Search, contentDescription = null) },
                        label = { Text("Workshop") },
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Icon(Icons.Default.Menu, contentDescription = null) },
                        label = { Text("Lists") },
                    )
                    NavigationBarItem(
                        selected = tab == 3,
                        onClick = { tab = 3 },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("Settings") },
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            0 -> ModsScreen(vm, padding, onGrantStorage)
            1 -> WorkshopScreen(vm, padding)
            2 -> ListsScreen(vm, padding)
            else -> SettingsScreen(vm, padding, onGrantStorage)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar(vm: AppViewModel, tab: Int) {
    val lib by vm.library.collectAsState()
    val cfg by vm.config.collectAsState()
    var menu by remember { mutableStateOf(false) }
    val title = when (tab) {
        0 -> "Mods"
        1 -> "Workshop"
        2 -> "Lists"
        else -> "Settings"
    }
    TopAppBar(
        title = { Text(title) },
        actions = {
            when (tab) {
                0 -> {
                    if (lib.dirty && !cfg.autoSave) {
                        TextButton(onClick = { vm.save() }) { Text("Save") }
                    }
                    TextButton(onClick = { vm.sortNow() }) { Text("Sort") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Problems (${lib.issues.size})") },
                                onClick = {
                                    menu = false
                                    vm.openPage(Page.Issues)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (lib.checkingUpdates) "Checking for updates…" else "Check for updates") },
                                onClick = {
                                    menu = false
                                    vm.checkUpdates(true)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Update all (${lib.updateSummary.available})") },
                                enabled = lib.updateSummary.available > 0,
                                onClick = {
                                    menu = false
                                    vm.updateAll(false)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Fix problems automatically") },
                                onClick = {
                                    menu = false
                                    vm.applySafeFixes()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Reload from disk") },
                                onClick = {
                                    menu = false
                                    vm.reload()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Reset to Core and DLC") },
                                onClick = {
                                    menu = false
                                    vm.resetToOfficial()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("ModsConfig.xml backups") },
                                onClick = {
                                    menu = false
                                    vm.openPage(Page.Backups)
                                },
                            )
                        }
                    }
                }
                1 -> {
                    IconButton(onClick = { vm.submitSearch() }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
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
    val running = active.firstOrNull { it.status == TaskStatus.RUNNING }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (running != null) "Downloading: ${running.title}" else "Downloads waiting",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("${active.size} left", style = MaterialTheme.typography.labelMedium)
            }
            LinearProgressIndicator(
                progress = { running?.percent ?: 0f },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
}
