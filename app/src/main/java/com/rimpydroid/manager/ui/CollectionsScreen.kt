package com.rimpydroid.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rimpydroid.manager.core.CollectionOps
import com.rimpydroid.manager.core.CollectionRecord
import com.rimpydroid.manager.core.CollectionStatus
import com.rimpydroid.manager.core.UpdateState
import com.rimpydroid.manager.service.TaskStatus

/**
 * The "Collections" tab: the Workshop collections that were downloaded (or added by their link), each one a unit that
 * can be applied, enabled, updated or deleted on its own, whatever else is installed.
 */
@Composable
fun CollectionsScreen(vm: AppViewModel, padding: PaddingValues) {
    val collections by vm.collections.collectAsState()
    val lib by vm.library.collectAsState()
    val tasks by vm.downloads.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var applyingId by remember { mutableStateOf<Long?>(null) }
    var deletingId by remember { mutableStateOf<Long?>(null) }
    val activeSet = remember(lib.active) { lib.active.toHashSet() }
    val busy = remember(tasks) {
        tasks.filter { it.status == TaskStatus.QUEUED || it.status == TaskStatus.RUNNING }.map { it.workshopId }.toHashSet()
    }
    fun statusOf(record: CollectionRecord): CollectionStatus =
        CollectionOps.status(record, lib.workshopInstalled, activeSet) { mod -> lib.updates[mod.key]?.state == UpdateState.UPDATE_AVAILABLE }

    LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "actions") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { adding = true }) { Text(tr("Add collection")) }
                OutlinedButton(onClick = { vm.updateAllCollections() }, enabled = collections.isNotEmpty()) { Text(tr("Update all")) }
            }
        }
        if (collections.isEmpty()) {
            item(key = "empty") {
                CenteredMessage(
                    tr("No collections yet"),
                    tr(
                        "A collection that you download from the Workshop tab is kept here, so that you can update, apply or " +
                            "delete each one on its own. You can also add one by its link.",
                    ),
                )
            }
        }
        items(collections, key = { it.id }) { record ->
            CollectionCard(
                record = record,
                status = statusOf(record),
                downloading = record.members.count { it.id in busy },
                lib = lib,
                activeSet = activeSet,
                onApply = { applyingId = record.id },
                onEnable = { vm.applyCollection(record.id, false) },
                onUpdate = { vm.updateCollection(record.id) },
                onDelete = { deletingId = record.id },
            )
        }
    }

    if (adding) {
        AddCollectionDialog(
            onAdd = { text, download ->
                adding = false
                vm.addCollections(text, download)
            },
            onDismiss = { adding = false },
        )
    }
    val applying = applyingId?.let { id -> collections.firstOrNull { it.id == id } }
    if (applying != null) {
        ConfirmDialog(
            title = tr("Apply \"{0}\"?", applying.title),
            text = tr(
                "Your load order becomes the {0} installed mods of this collection, sorted, plus the Core and DLC that are " +
                    "listed now. You can undo it.",
                statusOf(applying).installed,
            ),
            confirmLabel = tr("Apply"),
            onConfirm = {
                applyingId = null
                vm.applyCollection(applying.id, true)
            },
            onDismiss = { applyingId = null },
        )
    }
    val deleting = deletingId?.let { id -> collections.firstOrNull { it.id == id } }
    if (deleting != null) {
        val own = CollectionOps.exclusiveMembers(deleting, collections).toSet()
        // what "Delete mods too" removes: not Harmony, which other mods cannot work without
        val plan = CollectionOps.planDeletion(deleting, collections, lib.workshopInstalled)
        val ownInstalled = plan.delete.size
        val sharedInstalled = deleting.memberIds.count { it !in own && lib.workshopInstalled.containsKey(it) }
        AppDialog(
            onDismissRequest = { deletingId = null },
            title = { Text(tr("Delete \"{0}\"?", deleting.title)) },
            text = {
                Column {
                    Text(tr("\"Remove only\" takes the collection out of this tab and keeps all its mods."))
                    Text(
                        tr("\"Delete mods too\" also deletes its {0} installed mods that no other collection has.", ownInstalled),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (sharedInstalled > 0) {
                        Text(
                            tr("{0} of its mods are also in another collection and stay.", sharedInstalled),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (plan.kept.isNotEmpty()) {
                        Text(
                            tr("Harmony is never deleted with a collection: other mods cannot work without it."),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deletingId = null
                        vm.deleteCollection(deleting.id, true)
                    },
                ) { Text(tr("Delete mods too"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { deletingId = null }) { Text(tr("Cancel")) }
                    TextButton(
                        onClick = {
                            deletingId = null
                            vm.deleteCollection(deleting.id, false)
                        },
                    ) { Text(tr("Remove only")) }
                }
            },
        )
    }
}

@Composable
private fun CollectionCard(
    record: CollectionRecord,
    status: CollectionStatus,
    downloading: Int,
    lib: LibraryState,
    activeSet: Set<String>,
    onApply: () -> Unit,
    onEnable: () -> Unit,
    onUpdate: () -> Unit,
    onDelete: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var expanded by rememberSaveable(record.id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    AppCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(record.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = tr("More")) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (expanded) tr("Hide mods") else tr("Show mods")) },
                            onClick = {
                                menu = false
                                expanded = !expanded
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("Open on Steam")) },
                            onClick = {
                                menu = false
                                uriHandler.openUri(record.url)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("Delete…")) },
                            onClick = {
                                menu = false
                                onDelete()
                            },
                        )
                    }
                }
            }
            Text(
                tr("{0} mods, {1} installed, {2} enabled", status.total, status.installed, status.active),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val notes = ArrayList<String>()
            if (status.updates == 1) notes.add(tr("1 update available")) else if (status.updates > 1) notes.add(tr("{0} updates available", status.updates))
            if (status.missing > 0) notes.add(tr("{0} not installed", status.missing))
            if (downloading > 0) notes.add(tr("{0} downloading", downloading))
            if (record.updatedAt > 0L) notes.add(tr("updated {0}", relativeAge(record.updatedAt)))
            if (notes.isNotEmpty()) {
                Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onApply,
                    enabled = status.installed > 0,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f),
                ) { Text(tr("Apply")) }
                OutlinedButton(
                    onClick = onEnable,
                    enabled = status.installed > 0,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f),
                ) { Text(tr("Enable")) }
                OutlinedButton(
                    onClick = onUpdate,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f),
                ) { Text(tr("Update")) }
            }
            if (expanded) {
                Column(Modifier.padding(top = 8.dp)) {
                    for (m in record.members.take(40)) {
                        val mod = lib.workshopInstalled[m.id]
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                mod?.name ?: m.title.ifBlank { tr("Workshop item {0}", m.id) },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                            )
                            if (mod == null) {
                                Tag(tr("Missing"), MaterialTheme.colorScheme.error)
                            } else if (lib.updates[mod.key]?.state == UpdateState.UPDATE_AVAILABLE) {
                                Tag(tr("Update available"), MaterialTheme.colorScheme.primary)
                            } else if (mod.id in activeSet) {
                                Tag(tr("Enabled"), okColor())
                            } else {
                                Tag(tr("Installed"), MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (record.members.size > 40) {
                        Text(tr("…and {0} more", record.members.size - 40), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun AddCollectionDialog(onAdd: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Add a collection")) },
        text = {
            Column {
                Text(tr("Paste the link of a Workshop collection, or its id. You can paste several."), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(tr("Collection link or id")) },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(text, true) }, enabled = text.isNotBlank()) { Text(tr("Add and download")) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(tr("Cancel")) }
                TextButton(onClick = { onAdd(text, false) }, enabled = text.isNotBlank()) { Text(tr("Add only")) }
            }
        },
    )
}
