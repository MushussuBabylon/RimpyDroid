package com.rimpydroid.manager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rimpydroid.manager.core.AppConfig
import com.rimpydroid.manager.core.OfficialDlc

private val everyDlc: Set<String> = OfficialDlc.ALL.map { it.id }.toSet()

/**
 * One switch for each RimWorld DLC. [owned] is what is ticked; every DLC starts ticked (RimDroid has all of them), so
 * all a person has to do is to untick the ones they do not own.
 */
@Composable
fun DlcChecklist(owned: Set<String>, onChange: (Set<String>) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        for (dlc in OfficialDlc.ALL) {
            val on = dlc.id in owned
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onChange(if (on) owned - dlc.id else owned + dlc.id) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(dlc.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = on, onCheckedChange = { checked -> onChange(if (checked) owned + dlc.id else owned - dlc.id) })
            }
        }
    }
}

/**
 * The DLC of the RimpyDroid folder, in the welcome guide and in Settings. The game is not in a folder this app can
 * see there, so the person says what they own. Nothing to show for a RimWorld folder: its DLC are read from it.
 */
@Composable
fun DlcSection(vm: AppViewModel, cfg: AppConfig, modifier: Modifier = Modifier) {
    if (cfg.storageKind != AppConfig.STORAGE_RIMPY) return
    val owned = if (cfg.dlcChosen) cfg.ownedDlc.toSet() else everyDlc
    Column(modifier) {
        Text(tr("DLC"), style = MaterialTheme.typography.bodyLarge)
        Text(
            tr("They are all ticked. Untick the ones you do not own. The ticked ones are put in your load order and in ModsConfig.xml."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
        )
        DlcChecklist(owned, onChange = { vm.setOwnedDlc(it) })
        if (!cfg.dlcChosen) {
            // an installation from before this choice existed: nothing has been applied until it is confirmed
            Button(onClick = { vm.setOwnedDlc(owned) }, modifier = Modifier.padding(top = 6.dp)) { Text(tr("Add these DLC")) }
        }
    }
}

/** Asked once when the app is updated from a version that did not ask which DLC a person owns. */
@Composable
fun DlcPromptHost(vm: AppViewModel) {
    val cfg by vm.config.collectAsState()
    val lib by vm.library.collectAsState()
    val due = cfg.onboarded && !cfg.dlcChosen && !cfg.dlcPrompted &&
        cfg.storageKind == AppConfig.STORAGE_RIMPY && lib.loaded
    if (!due) return
    var picked by remember { mutableStateOf(everyDlc) }
    AppDialog(
        onDismissRequest = { vm.dismissDlcPrompt() },
        title = { Text(tr("Which DLC do you own?")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr("The game is not in a folder this app can see, so it cannot tell. Untick the DLC you do not own. The rest are added to your load order and to ModsConfig.xml. You can change this later in Settings."),
                    style = MaterialTheme.typography.bodyMedium,
                )
                DlcChecklist(picked, onChange = { picked = it })
            }
        },
        confirmButton = { TextButton(onClick = { vm.setOwnedDlc(picked) }) { Text(tr("Save")) } },
        dismissButton = { TextButton(onClick = { vm.dismissDlcPrompt() }) { Text(tr("Not now")) } },
    )
}
