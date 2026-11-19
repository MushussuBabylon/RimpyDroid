package com.rimpydroid.manager.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rimpydroid.manager.core.FileUtil
import com.rimpydroid.manager.core.ModEntry
import com.rimpydroid.manager.core.ResolvedInput
import com.rimpydroid.manager.core.SearchSort
import com.rimpydroid.manager.core.UpdateState
import com.rimpydroid.manager.core.WorkshopItem
import com.rimpydroid.manager.service.DownloadTask
import com.rimpydroid.manager.service.TaskStatus

/** The "Workshop" tab: search the Steam Workshop (or paste links) and download mods. */
@Composable
fun WorkshopScreen(vm: AppViewModel, padding: PaddingValues) {
    val ws by vm.workshop.collectAsState()
    val lib by vm.library.collectAsState()
    val tasks by vm.downloads.collectAsState()
    val cfg by vm.config.collectAsState()
    val focus = LocalFocusManager.current
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(Unit) { vm.ensureWorkshopLoaded() }

    // The text box keeps its own text, so that typing never waits for the view model. The view model is told of every
    // change (it searches with what it was told), and the box follows it when a search is started from elsewhere
    // (opening a collection puts its link into the box).
    var text by rememberSaveable { mutableStateOf(ws.query) }
    var seen by remember { mutableStateOf(ws.submitted) }
    LaunchedEffect(ws.submitted) {
        if (ws.submitted != seen) {
            seen = ws.submitted
            if (ws.submitted != text.trim()) text = ws.submitted
        }
    }

    val taskById = remember(tasks) { tasks.associateBy { it.workshopId } }
    val resolved = ws.resolved
    // A LazyColumn crashes on duplicate keys, so never show the same Workshop id twice.
    val shown = remember(resolved, ws.items) { (resolved?.items ?: ws.items).distinctBy { it.id } }
    val gameVersion = lib.game?.short

    Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
        // above the list, so that it stays in reach however far the results are scrolled
        SearchControls(
            query = text,
            sort = ws.sort,
            collections = ws.collections,
            versionTag = ws.versionTag,
            gameVersion = gameVersion,
            onQuery = {
                text = it
                vm.setQuery(it)
            },
            onClear = {
                text = ""
                vm.setQuery("")
            },
            onSubmit = {
                vm.setQuery(text)
                focus.clearFocus()
                vm.submitSearch()
            },
            onSort = { vm.setSort(it) },
            onCollections = { vm.setCollections(it) },
            onVersion = { vm.setVersionTag(it) },
        )
        if (ws.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (!cfg.hasModsDir) {
                item(key = "no-folder") {
                    Banner(tr("Choose the RimWorld folder first, so downloads know where to go.")) {
                        TextButton(onClick = { vm.openPage(Page.FolderPicker(PickPurpose.GAME_DIR)) }) { Text(tr("Choose folder")) }
                    }
                }
            }
            if (tasks.isNotEmpty()) {
                item(key = "downloads") { DownloadsCard(vm, tasks) }
            }
            if (resolved != null) {
                item(key = "resolved") {
                    val toGet = shown.filter { item ->
                        val installed = lib.workshopInstalled[item.id]
                        installed == null || lib.updates[installed.key]?.state == UpdateState.UPDATE_AVAILABLE
                    }
                    ResolvedHeader(
                        resolved = resolved,
                        toGetCount = toGet.size,
                        onDownloadAll = { vm.downloadResolved(resolved, toGet) },
                    )
                }
            }
            if (resolved == null && ws.submitted.isNotEmpty() && shown.isNotEmpty()) {
                item(key = "results-for") {
                    val total = ws.total
                    val label = if (total != null && total > 0) {
                        tr("{0} results for \u201C{1}\u201D", total, ws.submitted)
                    } else {
                        tr("Results for \u201C{0}\u201D", ws.submitted)
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            val error = ws.error
            if (error != null) {
                item(key = "error") {
                    Banner(
                        tr("Steam could not be reached: {0}", error),
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer,
                    ) {
                        TextButton(onClick = { vm.submitSearch() }) { Text(tr("Try again")) }
                    }
                }
            }
            items(shown, key = { it.id }) { item ->
                val installed = lib.workshopInstalled[item.id]
                WorkshopCard(
                    item = item,
                    installed = installed,
                    update = installed?.let { lib.updates[it.key]?.state },
                    task = taskById[item.id],
                    onDownload = { vm.downloadItem(item) },
                    onCancel = { vm.cancelDownload(item.id) },
                    onOpenCollection = { vm.openCollection(item) },
                    onOpenPage = { uriHandler.openUri(item.url) },
                )
            }
            if (ws.loading) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            } else if (shown.isEmpty() && ws.searched && error == null) {
                item(key = "empty") {
                    val filtered = resolved == null && ws.versionTag.isNotEmpty() && !ws.collections
                    if (filtered) {
                        // Mods for other versions are never listed under a version they are not tagged for
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CenteredMessage(
                                tr("No mods tagged {0}", ws.versionTag),
                                tr("Mods for other versions are not shown while this version is chosen."),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (ws.hasMore) {
                                    OutlinedButton(onClick = { vm.loadMore() }) { Text(tr("Keep searching")) }
                                }
                                Button(onClick = { vm.showAllVersions() }) { Text(tr("Show all versions")) }
                            }
                        }
                    } else {
                        CenteredMessage(tr("Nothing found"), tr("Try other words, or paste a Workshop link or collection."))
                    }
                }
            } else if (ws.hasMore && resolved == null) {
                item(key = "more") {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        OutlinedButton(onClick = { vm.loadMore() }) { Text(tr("Load more")) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchControls(
    query: String,
    sort: SearchSort,
    collections: Boolean,
    versionTag: String,
    gameVersion: String?,
    onQuery: (String) -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit,
    onSort: (SearchSort) -> Unit,
    onCollections: (Boolean) -> Unit,
    onVersion: (String) -> Unit,
) {
    var sortMenu by remember { mutableStateOf(false) }
    val versions = remember(gameVersion) {
        (listOf("") + listOfNotNull(gameVersion) + listOf("1.7", "1.6", "1.5", "1.4")).distinct()
    }
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(tr("Search mods by name, or paste Workshop links")) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = onClear) { Icon(Icons.Default.Close, contentDescription = tr("Clear")) }
                    }
                    IconButton(onClick = onSubmit) { Icon(Icons.Default.Search, contentDescription = tr("Search")) }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                FilterChip(
                    selected = false,
                    onClick = { sortMenu = true },
                    label = { Text(tr("Sort: {0}", tr(sort.label))) },
                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
                )
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    for (option in SearchSort.values()) {
                        DropdownMenuItem(
                            text = { Text(tr(option.label)) },
                            onClick = {
                                sortMenu = false
                                onSort(option)
                            },
                        )
                    }
                }
            }
            FilterChip(
                selected = collections,
                onClick = { onCollections(!collections) },
                label = { Text(tr("Collections")) },
            )
            for (tag in versions) {
                FilterChip(
                    selected = versionTag == tag,
                    onClick = { onVersion(tag) },
                    label = { Text(if (tag.isEmpty()) tr("Any version") else tag) },
                )
            }
        }
    }
}

@Composable
private fun ResolvedHeader(resolved: ResolvedInput, toGetCount: Int, onDownloadAll: () -> Unit) {
    AppCard(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(Modifier.padding(12.dp)) {
            val count = resolved.items.size
            Text(
                if (count == 1) tr("Found 1 mod") else tr("Found {0} mods", count),
                style = MaterialTheme.typography.titleMedium,
            )
            for (c in resolved.collections.take(3)) {
                Text(tr("Collection: {0}", c.title), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            }
            for (n in resolved.notes.filter { !it.startsWith("Collection ") }.take(4)) {
                Text(n, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            }
            if (toGetCount > 0) {
                Button(onClick = onDownloadAll, modifier = Modifier.padding(top = 8.dp)) {
                    Text(if (toGetCount == 1) tr("Download 1 mod") else tr("Download {0} mods", toGetCount))
                }
            } else if (count > 0) {
                Text(tr("Everything here is already installed and up to date."), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun WorkshopCard(
    item: WorkshopItem,
    installed: ModEntry?,
    update: UpdateState?,
    task: DownloadTask?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onOpenCollection: () -> Unit,
    onOpenPage: () -> Unit,
) {
    AppCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(10.dp)) {
            Row {
                AsyncPreview(
                    url = item.previewUrl,
                    file = null,
                    modifier = Modifier.size(width = 112.dp, height = 63.dp).clip(RoundedCornerShape(6.dp)),
                    maxSize = 240,
                )
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(
                        if (item.isCollection && item.children.isNotEmpty()) tr("{0} mods", item.children.size) else null,
                        if (item.fileSize > 0) FileUtil.humanSize(item.fileSize) else null,
                        if (item.timeUpdated > 0) tr("updated {0}", relativeAge(item.timeUpdated * 1000)) else null,
                        if (item.subscriptions > 0) tr("{0} subscribers", compactCount(item.subscriptions)) else null,
                    ).joinToString(" · ")
                    if (meta.isNotEmpty()) {
                        Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                    val versions = item.versionTags
                    if (versions.isNotEmpty() || item.isCollection) {
                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (item.isCollection) Tag(tr("Collection"), MaterialTheme.colorScheme.primary)
                            for (v in versions.take(4)) Tag(v, MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (task != null && task.status == TaskStatus.FAILED) {
                Text(task.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (task != null && (task.status == TaskStatus.QUEUED || task.status == TaskStatus.RUNNING)) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (task.status == TaskStatus.QUEUED) tr("Waiting in the queue") else "${(task.percent * 100).toInt()}%  ${task.message}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (task.status == TaskStatus.RUNNING) {
                            LinearProgressIndicator(
                                progress = { task.percent.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                        }
                    }
                    TextButton(onClick = onCancel) { Text(tr("Cancel")) }
                } else if (item.isCollection) {
                    Button(onClick = onOpenCollection) { Text(tr("Show mods")) }
                    TextButton(onClick = onOpenPage) { Text(tr("Steam page")) }
                } else if (installed != null && update == UpdateState.UPDATE_AVAILABLE) {
                    Button(onClick = onDownload) { Text(tr("Update")) }
                    TextButton(onClick = onOpenPage) { Text(tr("Steam page")) }
                } else if (installed != null) {
                    Tag(tr("Installed"), okColor())
                    TextButton(onClick = onDownload) { Text(tr("Re-download")) }
                    TextButton(onClick = onOpenPage) { Text(tr("Steam page")) }
                } else {
                    Button(onClick = onDownload) { Text(tr("Download")) }
                    TextButton(onClick = onOpenPage) { Text(tr("Steam page")) }
                }
            }
        }
    }
}

// ---- download queue --------------------------------------------------------------------------------------------

private fun statusOrder(s: TaskStatus): Int = when (s) {
    TaskStatus.RUNNING -> 0
    TaskStatus.QUEUED -> 1
    TaskStatus.FAILED -> 2
    TaskStatus.DONE -> 3
    TaskStatus.CANCELLED -> 4
}

@Composable
private fun DownloadsCard(vm: AppViewModel, tasks: List<DownloadTask>) {
    val running = tasks.count { it.status == TaskStatus.RUNNING }
    val queued = tasks.count { it.status == TaskStatus.QUEUED }
    val failed = tasks.count { it.status == TaskStatus.FAILED }
    val done = tasks.count { it.status == TaskStatus.DONE }
    val cancelled = tasks.count { it.status == TaskStatus.CANCELLED }
    var expanded by rememberSaveable { mutableStateOf(true) }
    AppCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Downloads"), style = MaterialTheme.typography.titleMedium)
                    val summary = listOfNotNull(
                        if (running + queued > 0) tr("{0} active", running + queued) else null,
                        if (done > 0) tr("{0} done", done) else null,
                        if (failed > 0) tr("{0} failed", failed) else null,
                        if (cancelled > 0) tr("{0} cancelled", cancelled) else null,
                    ).joinToString(" · ")
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) tr("Collapse") else tr("Expand"),
                    )
                }
            }
            if (expanded) {
                val ordered = remember(tasks) { tasks.sortedBy { statusOrder(it.status) } }
                for (t in ordered.take(40)) {
                    DownloadRow(t, onCancel = { vm.cancelDownload(t.workshopId) })
                }
                if (ordered.size > 40) {
                    Text(
                        tr("…and {0} more", ordered.size - 40),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    if (running + queued > 0) TextButton(onClick = { vm.cancelAllDownloads() }) { Text(tr("Cancel all")) }
                    if (failed + cancelled > 0) TextButton(onClick = { vm.retryFailedDownloads() }) { Text(tr("Retry failed")) }
                    if (done + failed + cancelled > 0) TextButton(onClick = { vm.clearFinishedDownloads() }) { Text(tr("Clear finished")) }
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(t: DownloadTask, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val text = when (t.status) {
                TaskStatus.QUEUED -> tr("Waiting")
                TaskStatus.RUNNING ->
                    "${(t.percent * 100).toInt()}%" +
                        (if (t.bytes > 0) " · " + FileUtil.humanSize(t.bytes) else "") +
                        (if (t.message.isNotBlank()) " · " + t.message else "")
                TaskStatus.DONE -> t.message.ifBlank { tr("Done") }
                TaskStatus.FAILED -> t.message.ifBlank { tr("Failed") }
                TaskStatus.CANCELLED -> tr("Cancelled")
            }
            val color = when (t.status) {
                TaskStatus.FAILED -> MaterialTheme.colorScheme.error
                TaskStatus.DONE -> okColor()
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (t.status == TaskStatus.RUNNING) {
                LinearProgressIndicator(
                    progress = { t.percent.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
        if (t.status == TaskStatus.QUEUED || t.status == TaskStatus.RUNNING) {
            IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = tr("Cancel download")) }
        }
    }
}
