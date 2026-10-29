package com.rimpydroid.manager.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rimpydroid.manager.core.Cleanup
import com.rimpydroid.manager.core.CleanupKind
import com.rimpydroid.manager.core.FileUtil
import com.rimpydroid.manager.core.ModEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The "Clean up" tool of the Mods tab: delete or disable many mods at once, always after a preview. */
@Composable
fun CleanupDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val lib by vm.library.collectAsState()
    var pending by remember { mutableStateOf<CleanupKind?>(null) }

    val incompatible = remember(lib.mods, lib.active, lib.game) {
        Cleanup.select(CleanupKind.INCOMPATIBLE, lib.mods, lib.active, lib.game)
    }
    val inactive = remember(lib.mods, lib.active, lib.game) {
        Cleanup.select(CleanupKind.INACTIVE, lib.mods, lib.active, lib.game)
    }
    val duplicates = remember(lib.mods, lib.active, lib.game) {
        Cleanup.select(CleanupKind.DUPLICATES, lib.mods, lib.active, lib.game)
    }
    val activeIncompatible = remember(lib.mods, lib.active, lib.game) {
        Cleanup.incompatibleActiveIds(lib.mods, lib.active, lib.game).size
    }

    val kind = pending
    if (kind != null) {
        val picked = when (kind) {
            CleanupKind.INCOMPATIBLE -> incompatible
            CleanupKind.INACTIVE -> inactive
            CleanupKind.DUPLICATES -> duplicates
        }
        ConfirmDelete(
            kind = kind,
            mods = picked,
            all = lib.mods,
            onConfirm = {
                vm.deleteMods(picked)
                onDismiss()
            },
            onBack = { pending = null },
        )
    } else {
        val game = lib.game
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Clean up mods") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CleanupRow(
                        title = if (game != null) "Incompatible with RimWorld ${game.short}" else "Incompatible with your game version",
                        hint = when {
                            game == null -> "The game version is not known. Set the RimWorld folder in Settings."
                            incompatible.isEmpty() -> "None. Every mod fits your game version, or does not say."
                            else -> plural(incompatible.size, "mod is", "mods are") + " made only for other game versions."
                        },
                        action = "Delete",
                        enabled = incompatible.isNotEmpty(),
                        danger = true,
                    ) { pending = CleanupKind.INCOMPATIBLE }
                    HorizontalDivider()
                    CleanupRow(
                        title = "Disable incompatible mods",
                        hint = when {
                            game == null -> "The game version is not known."
                            activeIncompatible == 0 -> "None of your enabled mods is incompatible."
                            else -> "Takes " + plural(activeIncompatible, "enabled mod", "enabled mods") +
                                " out of the load order. The files stay, and you can undo it."
                        },
                        action = "Disable",
                        enabled = activeIncompatible > 0,
                    ) {
                        vm.disableIncompatible()
                        onDismiss()
                    }
                    HorizontalDivider()
                    CleanupRow(
                        title = "Inactive mods",
                        hint = if (inactive.isEmpty()) {
                            "None. Everything installed is in your load order."
                        } else {
                            plural(inactive.size, "mod is", "mods are") + " not in your load order."
                        },
                        action = "Delete",
                        enabled = inactive.isNotEmpty(),
                        danger = true,
                    ) { pending = CleanupKind.INACTIVE }
                    HorizontalDivider()
                    CleanupRow(
                        title = "Duplicate copies",
                        hint = if (duplicates.isEmpty()) {
                            "None. No mod is installed twice."
                        } else {
                            plural(duplicates.size, "extra copy", "extra copies") +
                                " found (mods installed in more than one folder). The copy the app uses stays."
                        },
                        action = "Delete",
                        enabled = duplicates.isNotEmpty(),
                        danger = true,
                    ) { pending = CleanupKind.DUPLICATES }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
    }
}

@Composable
private fun CleanupRow(
    title: String,
    hint: String,
    action: String,
    enabled: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onClick, enabled = enabled) {
            Text(action, color = if (danger && enabled) MaterialTheme.colorScheme.error else Color.Unspecified)
        }
    }
}

/** The preview before anything is deleted: what goes, how much space it frees, and what it may break. */
@Composable
private fun ConfirmDelete(
    kind: CleanupKind,
    mods: List<ModEntry>,
    all: List<ModEntry>,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    val bytes by produceState<Long?>(null, mods) {
        value = withContext(Dispatchers.IO) {
            var total = 0L
            for (m in mods) total += FileUtil.dirSize(m.dir)
            total
        }
    }
    val dependents = remember(mods, all) { Cleanup.brokenDependents(mods, all) }
    val title = when (kind) {
        CleanupKind.INCOMPATIBLE -> "Delete " + plural(mods.size, "incompatible mod", "incompatible mods") + "?"
        CleanupKind.INACTIVE -> "Delete " + plural(mods.size, "inactive mod", "inactive mods") + "?"
        CleanupKind.DUPLICATES -> "Delete " + plural(mods.size, "extra copy", "extra copies") + "?"
    }
    val why = when (kind) {
        CleanupKind.INCOMPATIBLE ->
            "These mods name other RimWorld versions only, so they may not work with your game. " +
                "Some old mods still work. If you are not sure, use Disable instead."
        CleanupKind.INACTIVE ->
            "These mods are not in your load order. Core and the DLCs are never deleted."
        CleanupKind.DUPLICATES ->
            "These are extra copies of mods that are installed in more than one folder. The copy the app uses stays."
    }
    AlertDialog(
        onDismissRequest = onBack,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(why, style = MaterialTheme.typography.bodyMedium)
                val shown = mods.take(12)
                for (m in shown) {
                    Text(
                        "• " + m.name + (if (kind == CleanupKind.DUPLICATES) "  (folder ${m.dir.name})" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (mods.size > shown.size) {
                    Text(
                        "…and ${mods.size - shown.size} more",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                val b = bytes
                Text(
                    if (b != null) "Frees about ${FileUtil.humanSize(b)}." else "Working out how much space this frees…",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 10.dp),
                )
                if (dependents.isNotEmpty()) {
                    Text(
                        "Careful: these mods you keep need one of the mods above: " +
                            dependents.take(5).joinToString { it.name } + (if (dependents.size > 5) "…" else ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                Text(
                    "This cannot be undone. Mods from the Workshop can be downloaded again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = mods.isNotEmpty()) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onBack) { Text("Back") } },
    )
}

/** "1 mod" / "5 mods", with the wording of the one and the many form given by the caller. */
private fun plural(n: Int, one: String, many: String): String = if (n == 1) "1 $one" else "$n $many"
