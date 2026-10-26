package com.rimpydroid.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rimpydroid.manager.BuildConfig
import com.rimpydroid.manager.core.AppConfig
import com.rimpydroid.manager.core.GameLocator
import com.rimpydroid.manager.core.ThemeColors
import kotlin.math.roundToInt

private val THEME_CHOICES = listOf(
    AppConfig.THEME_LIGHT to "White",
    AppConfig.THEME_DARK to "Black",
    AppConfig.THEME_SYSTEM to "Follow system",
)

/** The "Settings" tab. */
@Composable
fun SettingsScreen(vm: AppViewModel, padding: PaddingValues, onGrantStorage: () -> Unit) {
    val cfg by vm.config.collectAsState()
    val hasStorage by vm.hasStorageAccess.collectAsState()
    val rules by vm.rulesInfo.collectAsState()
    val lib by vm.library.collectAsState()
    val hasCrash = remember { vm.crashText() != null }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    val gameOk = remember(cfg.gameDir) { cfg.gameDirFile?.let { GameLocator.isGameDir(it) } }

    LazyColumn(
        Modifier.padding(padding).fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        // ---- storage ----
        item(key = "storage") {
            Column {
                SectionTitle("Storage access")
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(if (hasStorage) "Allowed" else "Not allowed", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Needed to read and write the RimWorld folder.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!hasStorage) Button(onClick = onGrantStorage) { Text("Allow") }
                }
            }
        }

        // ---- folders ----
        item(key = "folders") {
            Column {
                SectionTitle("Folders")
                PathRow(
                    title = "RimWorld folder",
                    value = cfg.gameDir,
                    hint = "Not set. The folder that contains Data and Mods.",
                    status = when (gameOk) {
                        true -> "Looks like a RimWorld folder"
                        false -> "No Data/Core folder found here"
                        null -> null
                    },
                    statusOk = gameOk == true,
                    onChoose = { vm.openPage(Page.FolderPicker(PickPurpose.GAME_DIR)) },
                    onClear = { vm.updateConfig { it.copy(gameDir = "") } },
                )
                HorizontalDivider()
                PathRow(
                    title = "Mods folder",
                    value = cfg.modsDir,
                    hint = if (cfg.gameDir.isEmpty()) "Not set." else "Default: ${cfg.modsDirFile?.absolutePath ?: "Mods inside the RimWorld folder"}",
                    status = null,
                    statusOk = true,
                    onChoose = { vm.openPage(Page.FolderPicker(PickPurpose.MODS_DIR)) },
                    onClear = { vm.updateConfig { it.copy(modsDir = "") } },
                )
                HorizontalDivider()
                PathRow(
                    title = "ModsConfig.xml",
                    value = cfg.configPath,
                    hint = "Not set. The file the game reads its load order from.",
                    status = null,
                    statusOk = true,
                    onChoose = { vm.openPage(Page.FolderPicker(PickPurpose.CONFIG)) },
                    onClear = { vm.updateConfig { it.copy(configPath = "") } },
                )
                TextButton(onClick = { showHelp = !showHelp }, modifier = Modifier.padding(start = 8.dp)) {
                    Text(if (showHelp) "Hide setup help" else "How do I set this up with Winlator or RimDroid?")
                }
                if (showHelp) HelpCard()
            }
        }

        // ---- load order ----
        item(key = "behavior") {
            Column {
                SectionTitle("Load order")
                SettingSwitch(
                    "Save changes automatically",
                    "Writes ModsConfig.xml a moment after every change (a backup of the old file is kept).",
                    cfg.autoSave,
                ) { on -> vm.updateConfig { it.copy(autoSave = on) } }
                HorizontalDivider()
                SettingSwitch(
                    "Keep the current order when sorting",
                    "Mods without any ordering rule stay where they are instead of being sorted by name.",
                    cfg.keepOrder,
                ) { on -> vm.updateConfig { it.copy(keepOrder = on) } }
                HorizontalDivider()
                SettingSwitch(
                    "Check for mod updates on start",
                    "Asks Steam which of your Workshop mods changed.",
                    cfg.autoCheckUpdates,
                ) { on -> vm.updateConfig { it.copy(autoCheckUpdates = on) } }
            }
        }

        // ---- downloads ----
        item(key = "downloads") {
            Column {
                SectionTitle("Downloads")
                Text(
                    "Parallel connections per mod. More is faster on a good connection, fewer is gentler on weak Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (n in listOf(2, 4, 8, 12, 16)) {
                        FilterChip(
                            selected = cfg.maxDownloads == n,
                            onClick = { vm.updateConfig { it.copy(maxDownloads = n) } },
                            label = { Text("$n") },
                        )
                    }
                }
            }
        }

        // ---- appearance ----
        item(key = "appearance") {
            var showPicker by rememberSaveable { mutableStateOf(false) }
            val dark = LocalDarkTheme.current
            val custom = !ThemeColors.isDefault(cfg.accentColor) && ThemeColors.PRESETS.none { it.argb == cfg.accentColor }
            Column {
                SectionTitle("Appearance")
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for ((mode, label) in THEME_CHOICES) {
                        FilterChip(
                            selected = cfg.themeMode == mode,
                            onClick = { vm.updateConfig { it.copy(themeMode = mode) } },
                            label = { Text(label) },
                        )
                    }
                }
                Text(
                    "White and black. The black can be any colour:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (preset in ThemeColors.PRESETS) {
                        FilterChip(
                            selected = if (preset.argb == 0) ThemeColors.isDefault(cfg.accentColor) else cfg.accentColor == preset.argb,
                            onClick = { vm.updateConfig { it.copy(accentColor = preset.argb) } },
                            label = { Text(preset.name) },
                            leadingIcon = { ColorDot(ThemeColors.accentFor(preset.argb, dark), 16.dp) },
                        )
                    }
                    FilterChip(
                        selected = custom,
                        onClick = { showPicker = true },
                        label = { Text(if (custom) "Custom " + ThemeColors.toHex(cfg.accentColor) else "Custom\u2026") },
                        leadingIcon = {
                            ColorDot(ThemeColors.accentFor(if (custom) cfg.accentColor else ThemeColors.PICKER_START, dark), 16.dp)
                        },
                    )
                }
            }
            if (showPicker) {
                ColourDialog(
                    initial = cfg.accentColor,
                    onUse = { argb ->
                        showPicker = false
                        vm.updateConfig { it.copy(accentColor = argb) }
                    },
                    onDismiss = { showPicker = false },
                )
            }
        }

        // ---- rules ----
        item(key = "rules") {
            Column {
                SectionTitle("Community sorting rules")
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (rules.ruleCount == 0) "No rules downloaded yet" else "${rules.ruleCount} rules, updated ${relativeAge(rules.cachedAt)}",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "From the RimSort community database. They tell the sorter about mods that need a special place in the load order.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val error = rules.error
                        if (error != null) {
                            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (rules.updating) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        OutlinedButton(onClick = { vm.updateRules() }) { Text("Update") }
                    }
                }
            }
        }

        // ---- maintenance ----
        item(key = "maintenance") {
            Column {
                SectionTitle("Maintenance")
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { vm.reload() }) { Text("Rescan mods") }
                    TextButton(onClick = { vm.openPage(Page.Backups) }) { Text("ModsConfig backups") }
                    TextButton(onClick = { vm.openPage(Page.Log) }) { Text("View log") }
                    if (hasCrash) TextButton(onClick = { vm.openPage(Page.Crash) }) { Text("Last crash") }
                }
                if (lib.ignoredFolders.isNotEmpty()) {
                    Text(
                        "${lib.ignoredFolders.size} folder(s) in Mods have no readable About.xml and are ignored: " +
                            lib.ignoredFolders.take(5).joinToString(", ") + if (lib.ignoredFolders.size > 5) ", …" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }

        // ---- about ----
        item(key = "about") {
            Column {
                SectionTitle("About")
                Text(
                    "RimpyDroid ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(
                    "Mod manager for RimWorld on Android. Downloads from the Steam Workshop use an anonymous Steam login " +
                        "(JavaSteam); sorting rules come from the RimSort Community Rules Database. " +
                        "Not affiliated with Ludeon Studios, Valve, RimPy or RimSort.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PathRow(
    title: String,
    value: String,
    hint: String,
    status: String?,
    statusOk: Boolean,
    onChoose: () -> Unit,
    onClear: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            value.ifEmpty { hint },
            style = MaterialTheme.typography.bodySmall,
            color = if (value.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
        )
        if (status != null && value.isNotEmpty()) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (statusOk) okColor() else warningColor(),
            )
        }
        Row {
            TextButton(onClick = onChoose) { Text("Choose…") }
            if (value.isNotEmpty()) TextButton(onClick = onClear) { Text("Clear") }
        }
    }
}

@Composable
private fun HelpCard() {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Winlator and similar", style = MaterialTheme.typography.titleSmall)
        Text(
            "1. Keep RimWorld in a normal folder on your phone, for example Download/RimWorld, and choose that folder above. " +
                "It must contain Data and Mods. Your Winlator container needs to see that folder as a drive (container settings, Drives).",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "2. The game normally hides its settings inside the container, where other apps cannot reach them. " +
                "Start RimWorld with the launch argument -savedatafolder=<folder> so that it keeps its settings in a normal folder " +
                "(in Winlator: the shortcut's exec arguments, for example -savedatafolder=D:\\RimWorldData when D: is your Download folder). " +
                "Run the game once, then choose <folder>/Config/ModsConfig.xml above.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "3. Download mods on the Workshop tab, enable them on the Mods tab and tap Sort. " +
                "The game reads the list the next time it starts.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Without a ModsConfig.xml you can still use the app as a downloader and updater for the Mods folder.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("RimDroid", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        Text(
            "RimDroid keeps the game in its own storage. Set only the Mods folder above (for example Download/RimpyDroid/Mods) " +
                "and download there. Then zip the mods you want and, in RimDroid, open the side menu, Install mod / DLC (ZIP): " +
                "pick the zip, choose the instance and the type MODS, and RimDroid extracts the mods (installing a mod again replaces it).",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "For the load order, close the game and use Android's Files app to copy ModsConfig.xml from " +
                "RimDroid/instances/<instance>/unity3d/Ludeon Studios/RimWorld by Ludeon Studios/Config to a normal folder " +
                "(for example Download/RimpyDroid/Config). Choose that file above, sort, then copy it back over the old one.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** A small round swatch. */
@Composable
private fun ColorDot(argb: Int, size: Dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(argb), CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
    )
}

/** Picks any colour with three sliders or a hex code, and shows how it will look on white and on black. */
@Composable
private fun ColourDialog(initial: Int, onUse: (Int) -> Unit, onDismiss: () -> Unit) {
    val start = if (ThemeColors.isDefault(initial)) ThemeColors.PICKER_START else initial
    var red by remember { mutableStateOf(ThemeColors.red(start).toFloat()) }
    var green by remember { mutableStateOf(ThemeColors.green(start).toFloat()) }
    var blue by remember { mutableStateOf(ThemeColors.blue(start).toFloat()) }
    var hex by remember { mutableStateOf(ThemeColors.toHex(start)) }
    val argb = ThemeColors.rgb(red.roundToInt(), green.roundToInt(), blue.roundToInt())

    // one place that moves the sliders and the hex text together
    fun setChannels(r: Float, g: Float, b: Float) {
        red = r
        green = g
        blue = b
        hex = ThemeColors.toHex(ThemeColors.rgb(r.roundToInt(), g.roundToInt(), b.roundToInt()))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a colour") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColorDot(argb, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { text ->
                            hex = text
                            val c = ThemeColors.parseHex(text)
                            if (c != null) {
                                red = ThemeColors.red(c).toFloat()
                                green = ThemeColors.green(c).toFloat()
                                blue = ThemeColors.blue(c).toFloat()
                            }
                        },
                        label = { Text("Hex code") },
                        singleLine = true,
                        isError = ThemeColors.parseHex(hex) == null,
                        modifier = Modifier.weight(1f),
                    )
                }
                ChannelSlider("R", red) { setChannels(it, green, blue) }
                ChannelSlider("G", green) { setChannels(red, it, blue) }
                ChannelSlider("B", blue) { setChannels(red, green, it) }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PreviewBox("On white", ThemeColors.accentFor(argb, false), Color.White)
                    PreviewBox("On black", ThemeColors.accentFor(argb, true), Color.Black)
                }
                Text(
                    "A colour that would be hard to read is made a little lighter or darker.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onUse(argb) }) { Text("Use") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChannelSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(20.dp))
        Slider(value = value, onValueChange = onChange, valueRange = 0f..255f, modifier = Modifier.weight(1f))
        Text(value.roundToInt().toString(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(32.dp))
    }
}

@Composable
private fun RowScope.PreviewBox(label: String, text: Int, background: Color) {
    Box(
        Modifier
            .weight(1f)
            .background(background, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Text(label, color = Color(text), style = MaterialTheme.typography.titleSmall)
    }
}
