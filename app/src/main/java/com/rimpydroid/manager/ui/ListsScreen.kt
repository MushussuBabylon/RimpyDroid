package com.rimpydroid.manager.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rimpydroid.manager.core.ListApplier
import com.rimpydroid.manager.core.ModList

/** The "Lists" tab: saved load orders (profiles), import and export. */
@Composable
fun ListsScreen(vm: AppViewModel, padding: PaddingValues) {
    val lists by vm.lists.collectAsState()
    val lib by vm.library.collectAsState()
    val context = LocalContext.current
    var saving by remember { mutableStateOf(false) }
    var overwriteName by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            vm.importUri(uri, displayName(context, uri))
            importing = false
        }
    }

    LazyColumn(
        Modifier.padding(padding).fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "buttons") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { saving = true }, enabled = lib.active.isNotEmpty()) { Text(tr("Save current…")) }
                OutlinedButton(onClick = { importing = true }) { Text(tr("Import…")) }
            }
        }
        if (lists.isEmpty()) {
            item(key = "empty") {
                CenteredMessage(
                    tr("No saved lists yet"),
                    tr("A list remembers which mods are enabled and in what order. Save one before you experiment, " +
                        "or import a list from RimPy, RimSort or a friend."),
                )
            }
        }
        items(lists, key = { it.name }) { list ->
            AppCard(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clickable { selected = list.name },
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(list.name, style = MaterialTheme.typography.titleMedium)
                    val sub = listOfNotNull(
                        if (list.entries.size == 1) tr("1 mod") else tr("{0} mods", list.entries.size),
                        if (list.savedAt > 0L) tr("saved {0}", formatDate(list.savedAt)) else null,
                        list.gameVersion?.let { "RimWorld $it" },
                    ).joinToString(" · ")
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (saving) {
        TextInputDialog(
            title = tr("Save current load order"),
            label = tr("List name"),
            initial = "",
            confirmLabel = tr("Save"),
            supporting = tr("Saves the {0} mods in the active list, in this order.", lib.active.size),
            onConfirm = { name ->
                saving = false
                if (vm.listExists(name.trim())) overwriteName = name.trim() else vm.saveList(name)
            },
            onDismiss = { saving = false },
        )
    }
    val overwrite = overwriteName
    if (overwrite != null) {
        ConfirmDialog(
            title = tr("Replace \"{0}\"?", overwrite),
            text = tr("A list with this name exists. It will be replaced by the current load order."),
            confirmLabel = tr("Replace"),
            onConfirm = {
                vm.saveList(overwrite)
                overwriteName = null
            },
            onDismiss = { overwriteName = null },
        )
    }
    if (importing) {
        ImportDialog(
            onImportText = { text ->
                if (vm.importText(text, "Imported list") != null) importing = false
            },
            onPickFile = { picker.launch(arrayOf("*/*")) },
            onDismiss = { importing = false },
        )
    }
    val selectedList = lists.firstOrNull { it.name == selected }
    if (selectedList != null) {
        ListDialog(
            vm = vm,
            list = selectedList,
            onClose = { selected = null },
            onOverwrite = {
                selected = null
                overwriteName = selectedList.name
            },
            onRename = {
                selected = null
                renaming = selectedList.name
            },
            onDelete = {
                selected = null
                deleting = selectedList.name
            },
        )
    }
    val renameTarget = renaming
    if (renameTarget != null) {
        TextInputDialog(
            title = tr("Rename list"),
            label = tr("List name"),
            initial = renameTarget,
            confirmLabel = tr("Rename"),
            onConfirm = { name ->
                vm.renameList(renameTarget, name)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
    val deleteTarget = deleting
    if (deleteTarget != null) {
        ConfirmDialog(
            title = tr("Delete \"{0}\"?", deleteTarget),
            text = tr("The saved list is removed. Your mods are not touched."),
            confirmLabel = tr("Delete"),
            danger = true,
            onConfirm = {
                vm.deleteList(deleteTarget)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun ImportDialog(onImportText: (String) -> Unit, onPickFile: () -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Import a mod list")) },
        text = {
            Column {
                Text(
                    tr("Paste a list from RimPy, RimSort, this app, a ModsConfig.xml, a list of Workshop links, or just the names of the mods, one per line. You can also choose a file."),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onImportText(text) }, enabled = text.isNotBlank()) { Text(tr("Import")) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onPickFile) { Text(tr("Choose file…")) }
                TextButton(onClick = onDismiss) { Text(tr("Cancel")) }
            }
        },
    )
}

@Composable
private fun ListDialog(
    vm: AppViewModel,
    list: ModList,
    onClose: () -> Unit,
    onOverwrite: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val lib by vm.library.collectAsState()
    val context = LocalContext.current
    val result = remember(list, lib.mods) { vm.resolveList(list) }
    // what can be downloaded: mods whose Workshop link is known, and mods that are looked up on the Workshop by name
    val downloadable = result.missing.count { ListApplier.canDownload(it) }
    val byName = result.missing.count { ListApplier.lookupFor(it) != null }
    AppDialog(
        onDismissRequest = onClose,
        title = { Text(list.name) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    tr("{0} mods in the list: {1} installed, {2} missing.", list.entries.size, result.activeIds.size, result.missing.size),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (result.missing.isNotEmpty()) {
                    for (m in result.missing.take(6)) {
                        Text(
                            "• " + (m.name ?: m.id.ifEmpty { tr("Workshop item {0}", m.workshopId) }) +
                                if (m.workshopId != null) {
                                    ""
                                } else if (ListApplier.canDownload(m)) {
                                    " " + tr("(looked up by name)")
                                } else {
                                    " " + tr("(no Workshop link)")
                                },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (result.missing.size > 6) {
                        Text(tr("…and {0} more", result.missing.size - 6), style = MaterialTheme.typography.bodySmall)
                    }
                    if (byName > 0) {
                        Text(
                            tr("{0} of them are only known by name: they are looked up on the Steam Workshop first.", byName),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                Button(
                    onClick = {
                        vm.applyList(list)
                        onClose()
                    },
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text(tr("Apply list")) }
                if (downloadable > 0) {
                    OutlinedButton(
                        onClick = {
                            vm.downloadMissingAndApply(list)
                            onClose()
                        },
                    ) { Text(tr("Download {0} missing, then apply", downloadable)) }
                }
                OutlinedButton(onClick = onOverwrite) { Text(tr("Replace with current order")) }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { shareText(context, list.name, vm.exportText(list)) }) { Text(tr("Share")) }
                    TextButton(
                        onClick = {
                            copyToClipboard(context, list.name, vm.exportJson(list))
                            Toast.makeText(context, tr("Copied"), Toast.LENGTH_SHORT).show()
                        },
                    ) { Text(tr("Copy")) }
                    TextButton(onClick = onRename) { Text(tr("Rename")) }
                    TextButton(onClick = onDelete) { Text(tr("Delete"), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(tr("Close")) } },
    )
}

private fun displayName(context: Context, uri: Uri): String {
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) {
                    val name = c.getString(i)
                    if (!name.isNullOrBlank()) return name.substringBeforeLast('.')
                }
            }
        }
    } catch (e: Exception) {
        // fall through
    }
    return "Imported list"
}
