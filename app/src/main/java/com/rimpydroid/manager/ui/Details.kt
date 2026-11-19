package com.rimpydroid.manager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rimpydroid.manager.core.FileUtil
import com.rimpydroid.manager.core.Fix
import com.rimpydroid.manager.core.Issue
import com.rimpydroid.manager.core.ModEntry
import com.rimpydroid.manager.core.ModIndex
import com.rimpydroid.manager.core.ModRow
import com.rimpydroid.manager.core.ModelOps
import com.rimpydroid.manager.core.Severity
import com.rimpydroid.manager.core.UpdateState
import com.rimpydroid.manager.core.WorkshopUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---- one mod ---------------------------------------------------------------------------------------------

@Composable
fun ModDetailsPage(vm: AppViewModel, key: String) {
    val lib by vm.library.collectAsState()
    val mod = lib.mods.firstOrNull { it.key == key }
    PageScaffold(title = mod?.name ?: tr("Mod"), onBack = { vm.closePage() }) { pad ->
        if (mod == null) {
            CenteredMessage(tr("This mod is no longer installed."), modifier = Modifier.padding(pad))
        } else {
            ModDetailsBody(vm, mod, lib, Modifier.padding(pad))
        }
    }
}

@Composable
private fun ModDetailsBody(vm: AppViewModel, mod: ModEntry, lib: LibraryState, modifier: Modifier) {
    val uriHandler = LocalUriHandler.current
    val cfg by vm.config.collectAsState()
    val index = remember(lib.mods) { ModIndex(lib.mods) }
    val row = remember(lib.rows, mod.key) {
        lib.rows.active.firstOrNull { it.key == mod.key } ?: lib.rows.inactive.firstOrNull { it.key == mod.key }
    }
    val isActive = row?.active == true
    val canToggle = row?.canToggle == true
    val info = lib.updates[mod.key]
    var confirmDelete by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<String?>(null) } // "after" or "before"
    val size by produceState<Long?>(null, mod.dir) {
        value = withContext(Dispatchers.IO) { FileUtil.dirSize(mod.dir) }
    }
    val nameOf: (String) -> String = { id -> index[id]?.name ?: id }
    val description = remember(mod.meta.description) { WorkshopUtil.plainText(mod.meta.description).take(6000) }
    val issues = remember(lib.issues, mod.id) {
        if (mod.id.isEmpty()) emptyList() else lib.issues.filter { it.modId == mod.id }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val preview = mod.previewFile
        if (preview != null) {
            AsyncPreview(
                url = null,
                file = preview,
                modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Fit,
                maxSize = 720,
            )
        }
        Column {
            Text(mod.name, style = MaterialTheme.typography.headlineSmall)
            if (mod.meta.authors.isNotBlank()) {
                Text(tr("by {0}", mod.meta.authors), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (isActive) {
                Tag(tr("Enabled, place {0}", row?.position ?: 0), okColor())
            } else {
                Tag(tr("Disabled"), MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (info?.state) {
                UpdateState.UPDATE_AVAILABLE -> Tag(tr("Update available"), MaterialTheme.colorScheme.primary)
                UpdateState.UP_TO_DATE -> Tag(tr("Up to date"), okColor())
                UpdateState.UNTRACKED -> Tag(tr("Version unknown"), warningColor())
                UpdateState.UNAVAILABLE -> Tag(tr("Gone from Workshop"), MaterialTheme.colorScheme.error)
                else -> {}
            }
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canToggle) {
                if (isActive) {
                    OutlinedButton(onClick = { vm.deactivate(mod.id) }) { Text(tr("Disable")) }
                } else {
                    Button(onClick = { vm.activate(mod.id) }) { Text(tr("Enable")) }
                }
            }
            val workshopId = mod.workshopId
            if (workshopId != null && !mod.isOfficial) {
                if (info?.state == UpdateState.UPDATE_AVAILABLE) {
                    Button(onClick = { vm.downloadForMod(mod) }) { Text(tr("Update")) }
                } else {
                    OutlinedButton(onClick = { vm.downloadForMod(mod) }) { Text(tr("Re-download")) }
                }
                TextButton(onClick = { uriHandler.openUri(WorkshopUtil.itemUrl(workshopId)) }) { Text(tr("Open on Steam")) }
            }
        }

        if (isActive) {
            Text(tr("Load order"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.move(mod.id, MoveKind.TOP) }) { Text(tr("To top")) }
                OutlinedButton(onClick = { vm.move(mod.id, MoveKind.UP) }) { Text(tr("Up")) }
                OutlinedButton(onClick = { vm.move(mod.id, MoveKind.DOWN) }) { Text(tr("Down")) }
                OutlinedButton(onClick = { vm.move(mod.id, MoveKind.BOTTOM) }) { Text(tr("To bottom")) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picking = "after" }) { Text(tr("Move after…")) }
                OutlinedButton(onClick = { picking = "before" }) { Text(tr("Move before…")) }
            }
        }

        if (issues.isNotEmpty()) {
            Text(tr("Problems"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            for (issue in issues) IssueCard(vm, issue, nameOf, showName = false)
        }

        if (mod.meta.dependencies.isNotEmpty()) {
            Text(tr("Requires"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            for (dep in mod.meta.dependencies) {
                val ids = dep.allIds
                val installed = ids.firstNotNullOfOrNull { index[it] }
                val enabled = ids.any { it in lib.active } && installed != null
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        dep.displayName ?: installed?.name ?: dep.packageId,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (installed == null && cfg.ignoresOfficial && ids.any { ModelOps.isOfficialId(it) }) {
                        // Core or a DLC: the game knows whether it has it, this app cannot see the game here
                        Tag(tr("Part of the game"), MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (installed == null) {
                        Tag(tr("Missing"), MaterialTheme.colorScheme.error)
                        val wid = dep.workshopId
                        if (wid != null) TextButton(onClick = { vm.downloadMissing(listOf(wid)) }) { Text(tr("Download")) }
                    } else if (enabled) {
                        Tag(tr("Enabled"), okColor())
                    } else {
                        Tag(tr("Disabled"), warningColor())
                        TextButton(onClick = { vm.activate(installed.id) }) { Text(tr("Enable")) }
                    }
                }
            }
        }

        Text(tr("Details"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            InfoRow(tr("Package id"), mod.meta.packageId.ifBlank { "none" })
            if (mod.meta.modVersion.isNotBlank()) InfoRow(tr("Mod version"), mod.meta.modVersion)
            if (mod.meta.supportedVersions.isNotEmpty()) InfoRow(tr("Supports"), mod.meta.supportedVersions.joinToString(", "))
            mod.workshopId?.let { InfoRow(tr("Workshop id"), it.toString()) }
            mod.installedManifest?.let { InfoRow(tr("Installed build"), it) }
            InfoRow(tr("Folder"), mod.dir.absolutePath)
            size?.let { InfoRow(tr("Size"), FileUtil.humanSize(it)) }
            if (mod.lastModified > 0L) InfoRow(tr("Changed"), formatDate(mod.lastModified))
            if (mod.meta.url.isNotBlank()) {
                val url = mod.meta.url
                // the link comes from the mod's About.xml: only web addresses are opened, and a phone with no handler
                // must not bring the app down
                Row(Modifier.fillMaxWidth().clickable {
                    val web = url.trim()
                    if (web.startsWith("https://", ignoreCase = true) || web.startsWith("http://", ignoreCase = true)) {
                        try {
                            uriHandler.openUri(web)
                        } catch (e: Exception) {
                            // nothing can open it
                        }
                    }
                }) {
                    Text(tr("Link"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
                    Text(url, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                }
            }
        }

        if (description.isNotBlank()) {
            Text(tr("Description"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }

        if (!mod.isOfficial) {
            OutlinedButton(
                onClick = { confirmDelete = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(tr("Delete mod folder…")) }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = tr("Delete {0}?", mod.name),
            text = tr("The folder {0} and everything in it will be removed from your phone. This cannot be undone.", mod.dir.name),
            confirmLabel = tr("Delete"),
            danger = true,
            onConfirm = {
                confirmDelete = false
                vm.deleteMod(mod)
            },
            onDismiss = { confirmDelete = false },
        )
    }
    val mode = picking
    if (mode != null) {
        PickModDialog(
            title = if (mode == "after") tr("Move after which mod?") else tr("Move before which mod?"),
            rows = lib.rows.active,
            exclude = mod.id,
            onPick = { target ->
                if (mode == "after") vm.moveAfter(mod.id, target.id) else vm.moveBefore(mod.id, target.id)
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PickModDialog(
    title: String,
    rows: List<ModRow>,
    exclude: String,
    onPick: (ModRow) -> Unit,
    onDismiss: () -> Unit,
) {
    var q by remember { mutableStateOf("") }
    val shown = remember(rows, q) { ModelOps.filter(rows.filter { it.id != exclude && !it.missing }, q) }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = q,
                    onValueChange = { q = it },
                    singleLine = true,
                    placeholder = { Text(tr("Search")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(shown, key = { it.key }) { r ->
                        Text(
                            "${r.position}. ${r.name}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(r) }.padding(vertical = 10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

// ---- problems --------------------------------------------------------------------------------------------

@Composable
fun IssueCard(vm: AppViewModel, issue: Issue, nameOf: (String) -> String, showName: Boolean = true) {
    val color = severityColor(issue.severity)
    AppCard(
        Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (issue.severity == Severity.INFO) Icons.Default.Info else Icons.Default.Warning,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(18.dp),
                )
                if (showName) {
                    Text(
                        issue.modName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Text(issue.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (issue.fix) {
                    Fix.ACTIVATE -> TextButton(onClick = { vm.applyFix(issue) }) { Text(tr("Enable")) }
                    Fix.DOWNLOAD -> TextButton(onClick = { vm.applyFix(issue) }) { Text(tr("Download")) }
                    Fix.SORT -> TextButton(onClick = { vm.sortNow() }) { Text(tr("Sort load order")) }
                    Fix.REMOVE_MISSING -> TextButton(onClick = { vm.applyFix(issue) }) { Text(tr("Remove from list")) }
                    Fix.DEACTIVATE -> {
                        TextButton(onClick = { vm.applyFix(issue, issue.modId) }) { Text(tr("Disable {0}", nameOf(issue.modId))) }
                        val other = issue.relatedIds.firstOrNull()
                        if (other != null) {
                            TextButton(onClick = { vm.applyFix(issue, other) }) { Text(tr("Disable {0}", nameOf(other))) }
                        }
                    }
                    Fix.NONE -> {}
                }
            }
        }
    }
}

@Composable
fun IssuesPage(vm: AppViewModel) {
    val lib by vm.library.collectAsState()
    val index = remember(lib.mods) { ModIndex(lib.mods) }
    val nameOf: (String) -> String = { id -> index[id]?.name ?: id }
    val downloadable = remember(lib.issues) { ModelOps.downloadableIds(lib.issues) }
    val canAutoFix = remember(lib.issues) {
        lib.issues.any { it.fix == Fix.ACTIVATE || it.fix == Fix.SORT || it.fix == Fix.REMOVE_MISSING }
    }

    PageScaffold(title = tr("Problems ({0})", lib.issues.size), onBack = { vm.closePage() }) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (canAutoFix || downloadable.isNotEmpty()) {
                item(key = "actions") {
                    AppCard(
                        Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                tr("Quick fixes enable the mods that others need, remove entries of mods that are gone, and sort the list."),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (canAutoFix) {
                                    Button(onClick = { vm.applySafeFixes() }) { Text(tr("Fix automatically")) }
                                }
                                if (downloadable.isNotEmpty()) {
                                    OutlinedButton(onClick = { vm.downloadAllMissing() }) { Text(tr("Download {0} missing", downloadable.size)) }
                                }
                            }
                        }
                    }
                }
            }
            if (lib.issues.isEmpty()) {
                item(key = "none") { CenteredMessage(tr("No problems found"), tr("The active mods look consistent.")) }
            }
            for (severity in listOf(Severity.ERROR, Severity.WARNING, Severity.INFO)) {
                val group = lib.issues.filter { it.severity == severity }
                if (group.isEmpty()) continue
                item(key = "head-$severity") {
                    SectionTitle(
                        when (severity) {
                            Severity.ERROR -> tr("Errors")
                            Severity.WARNING -> tr("Warnings")
                            Severity.INFO -> tr("Notes")
                        },
                    )
                }
                items(group) { issue -> IssueCard(vm, issue, nameOf) }
            }
            if (lib.sortNotes.isNotEmpty()) {
                item(key = "sort-notes") {
                    AppCard(
                        Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(tr("Sorting notes"), style = MaterialTheme.typography.titleSmall)
                            Text(
                                tr("These requirements contradict each other, so the sorter followed the mods' own rules and skipped the others:"),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            for (note in lib.sortNotes.take(12)) {
                                Text("• $note", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
