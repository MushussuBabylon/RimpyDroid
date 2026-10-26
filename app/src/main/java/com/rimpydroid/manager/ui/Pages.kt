package com.rimpydroid.manager.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rimpydroid.manager.AppLog

/** The in-app log, or the record of the last crash. Handy when something fails and nobody has a computer at hand. */
@Composable
fun LogPage(vm: AppViewModel, crash: Boolean) {
    val context = LocalContext.current
    val version by AppLog.version.collectAsState()
    val text = remember(version, crash) {
        if (crash) {
            vm.crashText() ?: "No crash recorded."
        } else {
            AppLog.asText().ifEmpty { "The log is empty." }
        }
    }
    PageScaffold(
        title = if (crash) "Last crash" else "Log",
        onBack = { vm.closePage() },
        actions = {
            TextButton(
                onClick = {
                    copyToClipboard(context, "RimpyDroid log", text)
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                },
            ) { Text("Copy") }
            TextButton(onClick = { shareText(context, "RimpyDroid log", text) }) { Text("Share") }
            if (crash) {
                TextButton(
                    onClick = {
                        vm.clearCrash()
                        vm.closePage()
                    },
                ) { Text("Delete") }
            } else {
                TextButton(onClick = { AppLog.clear() }) { Text("Clear") }
            }
        },
    ) { pad ->
        Box(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            SelectionContainer {
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
    }
}

/** Copies of ModsConfig.xml that the app made before it overwrote the file. */
@Composable
fun BackupsPage(vm: AppViewModel) {
    val backups by vm.backups.collectAsState()
    LaunchedEffect(Unit) { vm.refreshBackups() }
    PageScaffold(title = "ModsConfig.xml backups", onBack = { vm.closePage() }) { pad ->
        if (backups.isEmpty()) {
            CenteredMessage(
                "No backups yet",
                "A copy of ModsConfig.xml is kept every time the app saves over an existing file (the last 8 are kept).",
                Modifier.padding(pad),
            )
        } else {
            LazyColumn(Modifier.padding(pad).fillMaxSize()) {
                items(backups, key = { it.file.name }) { b ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(formatDate(b.time), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${b.modCount} active mods",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { vm.restoreBackup(b) }) { Text("Load") }
                        TextButton(onClick = { vm.deleteBackup(b) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
