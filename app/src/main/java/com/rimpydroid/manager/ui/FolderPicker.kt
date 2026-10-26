package com.rimpydroid.manager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rimpydroid.manager.core.AppConfig
import com.rimpydroid.manager.core.GameLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A small folder browser (Android's own picker does not give real file paths). */
@Composable
fun FolderPickerPage(vm: AppViewModel, purpose: PickPurpose) {
    val cfg by vm.config.collectAsState()
    val detect by vm.detect.collectAsState()
    val roots = vm.storageRoots
    var dir by remember { mutableStateOf(startDir(purpose, cfg, roots)) }
    val listing by produceState<List<File>?>(null, dir) {
        value = null
        value = withContext(Dispatchers.IO) {
            val all = dir.listFiles() ?: emptyArray<File>()
            all.filter { f ->
                (f.isDirectory && !f.name.startsWith(".")) ||
                    (purpose == PickPurpose.CONFIG && f.isFile && f.name.equals("ModsConfig.xml", ignoreCase = true))
            }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        }
    }
    val isGame = remember(dir) { GameLocator.isGameDir(dir) }
    val title = when (purpose) {
        PickPurpose.GAME_DIR -> "Choose the RimWorld folder"
        PickPurpose.MODS_DIR -> "Choose the Mods folder"
        PickPurpose.CONFIG -> "Choose ModsConfig.xml"
    }

    PageScaffold(title = title, onBack = { vm.closePage() }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((i, r) in roots.withIndex()) {
                    FilterChip(
                        selected = false,
                        onClick = { dir = r },
                        label = { Text(if (i == 0) "Internal storage" else "Storage ${r.name}") },
                    )
                }
                val primary = roots.firstOrNull()
                if (primary != null) {
                    val download = File(primary, "Download")
                    if (download.isDirectory) {
                        FilterChip(selected = false, onClick = { dir = download }, label = { Text("Download") })
                    }
                }
            }

            DetectCard(vm, purpose, detect)

            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    dir.absolutePath,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val parent = dir.parentFile
                val atRoot = roots.any { it.absolutePath == dir.absolutePath }
                if (parent != null && !atRoot) {
                    TextButton(onClick = { dir = parent }) { Text("Up") }
                }
            }
            HorizontalDivider()

            val files = listing
            if (files == null) {
                LoadingBlock("Reading folder…", Modifier.weight(1f))
            } else if (files.isEmpty()) {
                CenteredMessage("No subfolders here", modifier = Modifier.weight(1f))
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(files, key = { it.absolutePath }) { f ->
                        val looksLikeGame = f.isDirectory && GameLocator.isGameDir(f)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (f.isDirectory) dir = f else vm.applyPick(purpose, f)
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (f.isDirectory) f.name else f.name + "  (use this file)",
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (looksLikeGame) Tag("RimWorld", okColor())
                        }
                        HorizontalDivider()
                    }
                }
            }

            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                when (purpose) {
                    PickPurpose.GAME_DIR -> {
                        if (!isGame) {
                            Text(
                                "This folder has no Data/Core inside. Open the folder that contains Data and Mods.",
                                style = MaterialTheme.typography.bodySmall,
                                color = warningColor(),
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                    }
                    PickPurpose.MODS_DIR -> {}
                    PickPurpose.CONFIG -> {
                        Text(
                            "The file will be: ${GameLocator.configTarget(dir).absolutePath}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
                Button(
                    onClick = { vm.applyPick(purpose, dir) },
                    enabled = purpose != PickPurpose.GAME_DIR || isGame,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Use this folder") }
            }
        }
    }
}

@Composable
private fun DetectCard(vm: AppViewModel, purpose: PickPurpose, detect: DetectState) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Find automatically", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (detect.running) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("  searching…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 12.dp))
                } else {
                    TextButton(onClick = { vm.autoDetect() }) { Text(if (detect.done) "Search again" else "Search") }
                }
            }
            if (detect.done) {
                val paths = when (purpose) {
                    PickPurpose.GAME_DIR -> detect.gameDirs
                    PickPurpose.MODS_DIR -> detect.gameDirs.map { File(it, "Mods").absolutePath }
                    PickPurpose.CONFIG -> detect.configFiles
                }
                if (paths.isEmpty()) {
                    Text(
                        "Nothing found. Browse to the folder below.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                for (p in paths.take(6)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.applyPick(purpose, File(p)) }) { Text("Use") }
                    }
                }
            }
        }
    }
}

private fun startDir(purpose: PickPurpose, cfg: AppConfig, roots: List<File>): File {
    val candidates = when (purpose) {
        PickPurpose.GAME_DIR -> listOf(cfg.gameDir)
        PickPurpose.MODS_DIR -> listOf(cfg.modsDir, cfg.gameDir)
        PickPurpose.CONFIG -> listOf(File(cfg.configPath).parent ?: "", cfg.gameDir)
    }
    for (c in candidates) {
        if (c.isNotBlank()) {
            val f = File(c)
            if (f.isDirectory) return f
        }
    }
    return roots.firstOrNull() ?: File("/storage/emulated/0")
}
