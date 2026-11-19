package com.rimpydroid.manager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rimpydroid.manager.core.AppConfig
import com.rimpydroid.manager.core.GameVersion
import com.rimpydroid.manager.core.ModRow
import com.rimpydroid.manager.core.ModelOps
import com.rimpydroid.manager.core.Severity
import com.rimpydroid.manager.core.UpdateState

/** The "Mods" tab: the load order, search, banners for things that need attention. */
@Composable
fun ModsScreen(vm: AppViewModel, padding: PaddingValues, onGrantStorage: () -> Unit) {
    val lib by vm.library.collectAsState()
    val cfg by vm.config.collectAsState()
    val hasStorage by vm.hasStorageAccess.collectAsState()
    val newMods by vm.newMods.collectAsState()
    var section by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var hideUntracked by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.padding(padding).fillMaxSize()) {
        if (!hasStorage) {
            PermissionCard(onGrantStorage)
        } else if (!cfg.hasModsDir) {
            SetupCard(vm, cfg)
        } else if (lib.loading && lib.mods.isEmpty()) {
            LoadingBlock(lib.message.ifEmpty { tr("Reading mods…") })
        } else {
            if (lib.loading || lib.checkingUpdates) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            SummaryLine(vm, lib.activeInstalled, lib.rows.inactive.size, lib.game, lib.errorCount, lib.warningCount)

            val error = lib.error
            if (error != null) {
                Banner(
                    tr("Could not read the mods: {0}", error),
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    TextButton(onClick = { vm.reload() }) { Text(tr("Try again")) }
                }
            }
            if (cfg.configFile == null) {
                Banner(tr("RimWorld keeps the load order in ModsConfig.xml. Choose where that file is, otherwise changes cannot be saved.")) {
                    TextButton(onClick = { vm.openPage(Page.FolderPicker(PickPurpose.CONFIG)) }) { Text(tr("Choose")) }
                }
            } else if (!lib.configRead && lib.loaded) {
                Banner(tr("There is no ModsConfig.xml at {0} yet. It is created when you change the load order.", cfg.configPath))
            }
            if (lib.dirty && !cfg.autoSave) {
                Banner(tr("You have unsaved changes.")) {
                    TextButton(onClick = { vm.save() }) { Text(tr("Save")) }
                }
            }
            val newCount = remember(lib.rows, newMods) {
                lib.rows.inactive.count { r ->
                    val w = r.mod?.workshopId
                    w != null && w in newMods && r.canToggle
                }
            }
            if (newCount > 0) {
                Banner(if (newCount == 1) tr("1 new mod was installed.") else tr("{0} new mods were installed.", newCount)) {
                    TextButton(onClick = { vm.enableNewMods(false) }) { Text(tr("Enable")) }
                    TextButton(onClick = { vm.enableNewMods(true) }) { Text(tr("Enable and sort")) }
                    TextButton(onClick = { vm.dismissNewMods() }) { Text(tr("Dismiss")) }
                }
            }
            val summary = lib.updateSummary
            if (summary.available > 0) {
                Banner(if (summary.available == 1) tr("1 mod has an update.") else tr("{0} mods have updates.", summary.available)) {
                    TextButton(onClick = { vm.updateAll(false) }) { Text(tr("Update all")) }
                }
            }
            if (summary.untracked > 0 && summary.available == 0 && !hideUntracked) {
                Banner(
                    tr(
                        "{0} Workshop mods have no version record (they were not downloaded with this app). " +
                            "Fetch them once to enable update checks. Files that are already identical are not downloaded again.",
                        summary.untracked,
                    ),
                ) {
                    TextButton(onClick = { vm.updateAll(true) }) { Text(tr("Fetch")) }
                    TextButton(onClick = { hideUntracked = true }) { Text(tr("Hide")) }
                }
            }

            TabRow(selectedTabIndex = section) {
                Tab(selected = section == 0, onClick = { section = 0 }, text = { Text(tr("Active ({0})", lib.rows.active.size)) })
                Tab(selected = section == 1, onClick = { section = 1 }, text = { Text(tr("Inactive ({0})", lib.rows.inactive.size)) })
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(tr("Search name, author or package id")) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = tr("Clear search")) }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )

            val rows = remember(lib.rows, section, query) {
                ModelOps.filter(if (section == 0) lib.rows.active else lib.rows.inactive, query)
            }
            if (rows.isEmpty()) {
                CenteredMessage(
                    title = if (query.isNotBlank()) tr("Nothing matches") else if (section == 0) tr("No active mods") else tr("No inactive mods"),
                    text = if (query.isNotBlank()) "" else if (section == 0) tr("Enable mods on the Inactive tab, or download some from the Workshop tab.") else "",
                )
            } else {
                val glass = LocalGlass.current
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = if (glass) PaddingValues(bottom = 8.dp) else PaddingValues(0.dp),
                ) {
                    items(rows, key = { it.key }) { row ->
                        val item: @Composable () -> Unit = {
                            ModRowItem(
                                row = row,
                                game = lib.game,
                                onOpen = { vm.openPage(Page.ModDetails(row.key)) },
                                onToggle = { vm.toggle(row) },
                                onRemoveMissing = { vm.deactivate(row.id) },
                            )
                        }
                        if (glass) {
                            // Liquid glass: a pane per mod instead of rows with lines between them
                            AppPanel(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp), content = item)
                        } else {
                            item()
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryLine(vm: AppViewModel, active: Int, inactive: Int, game: GameVersion?, errors: Int, warnings: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val text = tr("{0} active, {1} inactive", active, inactive) + if (game != null) " · RimWorld ${game.short}" else ""
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (errors > 0) {
            Box(Modifier.clip(RoundedCornerShape(4.dp)).clickable { vm.openPage(Page.Issues) }) {
                Tag(if (errors == 1) tr("1 error") else tr("{0} errors", errors), MaterialTheme.colorScheme.error)
            }
        }
        if (warnings > 0) {
            Box(Modifier.clip(RoundedCornerShape(4.dp)).clickable { vm.openPage(Page.Issues) }) {
                Tag(if (warnings == 1) tr("1 warning") else tr("{0} warnings", warnings), warningColor())
            }
        }
    }
}

@Composable
private fun PermissionCard(onGrant: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Text(tr("Allow access to files"), style = MaterialTheme.typography.titleLarge)
        Text(
            tr("RimpyDroid reads and writes the RimWorld folder on your phone (Mods folder and ModsConfig.xml). " +
                "Android calls this permission \"All files access\"."),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = onGrant, modifier = Modifier.padding(top = 16.dp)) { Text(tr("Allow access")) }
    }
}

@Composable
private fun SetupCard(vm: AppViewModel, cfg: AppConfig) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Text(tr("Set up"), style = MaterialTheme.typography.titleLarge)
        Text(
            tr("Choose the RimWorld folder that Winlator or RimDroid uses: the folder that contains \"Data\" and \"Mods\". " +
                "Mods you download here go into its Mods folder."),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(
            onClick = { vm.openPage(Page.FolderPicker(PickPurpose.GAME_DIR)) },
            modifier = Modifier.padding(top = 16.dp),
        ) { Text(tr("Choose RimWorld folder")) }
        if (cfg.gameDir.isNotEmpty()) {
            Text(
                tr("Chosen: {0}", cfg.gameDir),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Text(
            tr("You can also choose only a Mods folder in Settings."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

private enum class TagKind { PLAIN, GOOD, WARN, BAD, PRIMARY }

private fun rowTags(row: ModRow, game: GameVersion?): List<Pair<String, TagKind>> {
    val mod = row.mod ?: return emptyList()
    val out = ArrayList<Pair<String, TagKind>>()
    if (mod.isOfficial) {
        out.add((if (row.id == ModelOps.CORE_ID) tr("Core") else tr("DLC")) to TagKind.PLAIN)
    } else if (mod.workshopId != null) {
        out.add(tr("Workshop") to TagKind.PLAIN)
    } else {
        out.add(tr("Local") to TagKind.PLAIN)
    }
    when (row.update) {
        UpdateState.UPDATE_AVAILABLE -> out.add(tr("Update available") to TagKind.PRIMARY)
        UpdateState.UNTRACKED -> out.add(tr("Version unknown") to TagKind.WARN)
        UpdateState.UNAVAILABLE -> out.add(tr("Gone from Workshop") to TagKind.BAD)
        else -> {}
    }
    val supported = mod.meta.supportedVersions
    if (game != null && !mod.isOfficial && supported.isNotEmpty() && game.short !in supported) {
        out.add(tr("Not marked for {0}", game.short) to TagKind.WARN)
    }
    if (row.duplicate) out.add(tr("Duplicate") to TagKind.WARN)
    if (row.id.isEmpty()) out.add(tr("No package id") to TagKind.BAD)
    return out
}

@Composable
private fun ModRowItem(
    row: ModRow,
    game: GameVersion?,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onRemoveMissing: () -> Unit,
) {
    val mod = row.mod
    val tags = remember(row, game, Lang.code) { rowTags(row, game) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = mod != null, onClick = onOpen)
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.active) {
            Text(
                row.position.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.width(34.dp).padding(end = 6.dp),
            )
        } else {
            Spacer(Modifier.width(6.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                row.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (mod == null && !row.unseenOfficial) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
            val sub = if (row.unseenOfficial) {
                // the game is not in a folder this app can see (RimDroid keeps it in its own storage)
                tr("Part of the game · {0}", row.id)
            } else if (mod == null) {
                tr("Not installed")
            } else {
                listOf(mod.meta.authors, row.id).filter { it.isNotBlank() }.joinToString(" · ")
            }
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (tags.isNotEmpty()) {
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((text, kind) in tags) {
                        val color = when (kind) {
                            TagKind.PLAIN -> MaterialTheme.colorScheme.onSurfaceVariant
                            TagKind.GOOD -> okColor()
                            TagKind.WARN -> warningColor()
                            TagKind.BAD -> MaterialTheme.colorScheme.error
                            TagKind.PRIMARY -> MaterialTheme.colorScheme.primary
                        }
                        Tag(text, color)
                    }
                }
            }
        }
        val severity = row.severity
        if (severity != null) {
            Icon(
                Icons.Default.Warning,
                contentDescription = if (severity == Severity.ERROR) tr("Has errors") else tr("Has warnings"),
                tint = severityColor(severity),
                modifier = Modifier.padding(horizontal = 4.dp).size(20.dp),
            )
        }
        if (mod == null && !row.unseenOfficial) {
            TextButton(onClick = onRemoveMissing) { Text(tr("Remove")) }
        } else {
            Switch(checked = row.active, onCheckedChange = { onToggle() }, enabled = row.canToggle)
        }
    }
}
