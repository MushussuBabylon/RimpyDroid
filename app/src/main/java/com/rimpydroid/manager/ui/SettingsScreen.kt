package com.rimpydroid.manager.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rimpydroid.manager.BuildConfig
import com.rimpydroid.manager.R
import com.rimpydroid.manager.core.AppConfig
import com.rimpydroid.manager.core.GameLocator
import com.rimpydroid.manager.core.ThemeColors
import com.rimpydroid.manager.service.DownloadQueue
import kotlin.math.roundToInt

/** The "Settings" tab: every group of settings sits in its own bordered card, with a line between its settings. */
@Composable
fun SettingsScreen(vm: AppViewModel, padding: PaddingValues, onGrantStorage: () -> Unit) {
    val cfg by vm.config.collectAsState()
    val hasStorage by vm.hasStorageAccess.collectAsState()
    val rules by vm.rulesInfo.collectAsState()
    val lib by vm.library.collectAsState()
    val hasCrash = remember { vm.crashText() != null }
    val gameOk = remember(cfg.gameDir) { cfg.gameDirFile?.let { GameLocator.isGameDir(it) } }

    LazyColumn(
        Modifier.padding(padding).fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp),
    ) {
        item(key = "storage") { StorageCard(vm, cfg, hasStorage, gameOk, onGrantStorage) }
        item(key = "order") { LoadOrderCard(vm, cfg) }
        item(key = "downloads") { DownloadSettingsCard(vm, cfg) }
        item(key = "language") {
            SettingsCard(tr("Language")) {
                LanguageChips(cfg.language, onSelect = { code -> vm.updateConfig { it.copy(language = code) } }, modifier = Modifier.padding(16.dp))
            }
        }
        item(key = "appearance") { AppearanceCard(vm, cfg) }
        item(key = "rules") {
            SettingsCard(tr("Community sorting rules")) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            if (rules.ruleCount == 0) tr("No rules downloaded yet") else tr("{0} rules, updated {1}", rules.ruleCount, relativeAge(rules.cachedAt)),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            tr("From the RimSort community database. Tells the sorter which mods need a special place."),
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
                        OutlinedButton(onClick = { vm.updateRules() }) { Text(tr("Update")) }
                    }
                }
            }
        }
        item(key = "maintenance") {
            SettingsCard(tr("Maintenance")) {
                FlowRow(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { vm.reload() }) { Text(tr("Rescan mods")) }
                    TextButton(onClick = { vm.openPage(Page.Backups) }) { Text(tr("ModsConfig backups")) }
                    TextButton(onClick = { vm.openPage(Page.Log) }) { Text(tr("View log")) }
                    if (hasCrash) TextButton(onClick = { vm.openPage(Page.Crash) }) { Text(tr("Last crash")) }
                    TextButton(onClick = { vm.openPage(Page.Help) }) { Text(tr("Help & guide")) }
                    TextButton(onClick = { vm.updateConfig { it.copy(onboarded = false) } }) { Text(tr("Show the welcome guide again")) }
                }
                if (lib.ignoredFolders.isNotEmpty()) {
                    CardDivider()
                    Text(
                        tr("{0} folder(s) in Mods have no readable About.xml and are ignored: {1}", lib.ignoredFolders.size, lib.ignoredFolders.take(5).joinToString(", ") + if (lib.ignoredFolders.size > 5) ", …" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        item(key = "about") {
            SettingsCard(tr("About")) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.app_logo),
                        contentDescription = null,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)),
                    )
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text("RimpyDroid ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            tr("Not affiliated with Ludeon Studios, Valve, RimPy or RimSort."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageCard(vm: AppViewModel, cfg: AppConfig, hasStorage: Boolean, gameOk: Boolean?, onGrantStorage: () -> Unit) {
    SettingsCard(tr("Storage")) {
        if (hasStorage) {
            CardRow(title = tr("File access"), summary = tr("Allowed"))
        } else {
            CardRow(
                title = tr("File access"),
                summary = tr("Not allowed"),
                trailing = { Button(onClick = onGrantStorage) { Text(tr("Allow")) } },
            )
        }
        CardDivider()
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(tr("Where your files live"), style = MaterialTheme.typography.bodyLarge)
            StorageOptions(vm, cfg, hasStorage, Modifier.padding(top = 10.dp))
        }
        CardDivider()
        PathRow(
            title = tr("RimWorld folder"),
            value = cfg.gameDir,
            hint = tr("Not set. The folder that contains Data and Mods."),
            status = when (gameOk) {
                true -> tr("Looks like a RimWorld folder")
                false -> tr("No Data/Core folder found here")
                null -> null
            },
            statusOk = gameOk == true,
            onChoose = { vm.openPage(Page.FolderPicker(PickPurpose.GAME_DIR)) },
            onClear = { vm.updateConfig { it.copy(gameDir = "") } },
        )
        CardDivider()
        PathRow(
            title = tr("Mods folder"),
            value = cfg.modsDir,
            hint = if (cfg.gameDir.isEmpty()) tr("Not set.") else tr("Default: {0}", cfg.modsDirFile?.absolutePath ?: ""),
            status = null,
            statusOk = true,
            onChoose = { vm.openPage(Page.FolderPicker(PickPurpose.MODS_DIR)) },
            onClear = { vm.updateConfig { it.copy(modsDir = "") } },
        )
        CardDivider()
        PathRow(
            title = tr("ModsConfig.xml"),
            value = cfg.configPath,
            hint = tr("Not set. The file the game reads its load order from."),
            status = null,
            statusOk = true,
            onChoose = { vm.openPage(Page.FolderPicker(PickPurpose.CONFIG)) },
            onClear = { vm.updateConfig { it.copy(configPath = "") } },
        )
        if (cfg.gameDir.isBlank()) {
            CardDivider()
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(tr("RimWorld version"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    tr("Used to spot mods made for another version. Not needed when a RimWorld folder is chosen."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (v in listOf("", "1.7", "1.6", "1.5", "1.4")) {
                        FilterChip(
                            selected = cfg.gameVersion == v,
                            onClick = {
                                vm.updateConfig { it.copy(gameVersion = v) }
                                vm.reload()
                            },
                            label = { Text(if (v.isEmpty()) tr("Unknown") else v) },
                        )
                    }
                }
            }
        }
        CardDivider()
        CardRow(
            title = tr("Backups and lists"),
            summary = tr("Lists are copied to Download/RimpyDroid/Lists. ModsConfig backups sit next to ModsConfig.xml. Both survive removing the app."),
            onClick = { vm.openPage(Page.Backups) },
        )
    }
}

@Composable
private fun LoadOrderCard(vm: AppViewModel, cfg: AppConfig) {
    SettingsCard(tr("Load order")) {
        SwitchRow(
            tr("Save changes automatically"),
            tr("A moment after every change. A backup of the old file is kept."),
            cfg.autoSave,
        ) { on -> vm.updateConfig { it.copy(autoSave = on) } }
        CardDivider()
        SwitchRow(
            tr("Keep the current order when sorting"),
            tr("Mods without a rule stay where they are instead of being sorted by name."),
            cfg.keepOrder,
        ) { on -> vm.updateConfig { it.copy(keepOrder = on) } }
        CardDivider()
        SwitchRow(
            tr("Check for mod updates on start"),
            tr("Asks Steam which of your Workshop mods changed."),
            cfg.autoCheckUpdates,
        ) { on -> vm.updateConfig { it.copy(autoCheckUpdates = on) } }
    }
}

@Composable
private fun DownloadSettingsCard(vm: AppViewModel, cfg: AppConfig) {
    SettingsCard(tr("Downloads")) {
        SliderRow(
            title = tr("Mods downloaded at the same time"),
            value = cfg.parallelMods,
            min = AppConfig.MIN_PARALLEL_MODS,
            max = AppConfig.MAX_PARALLEL_MODS,
            default = AppConfig.DEFAULT_PARALLEL_MODS,
            onCommit = { n -> vm.updateConfig { it.copy(parallelMods = n) } },
        )
        CardDivider()
        SliderRow(
            title = tr("Download connections"),
            value = cfg.maxDownloads.coerceIn(2, 16),
            min = 2,
            max = 16,
            default = AppConfig.DEFAULT_CONNECTIONS,
            onCommit = { n -> vm.updateConfig { it.copy(maxDownloads = n) } },
        )
        CardDivider()
        Text(
            tr("Connections are shared by all mods. More is faster on good Wi-Fi; use fewer on weak Wi-Fi. Limited by your phone's memory (at most {0}).", DownloadQueue.memoryCap()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        TextButton(
            onClick = {
                vm.updateConfig { it.copy(maxDownloads = AppConfig.DEFAULT_CONNECTIONS, parallelMods = AppConfig.DEFAULT_PARALLEL_MODS) }
            },
            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
        ) { Text(tr("Reset to defaults")) }
    }
}

@Composable
private fun AppearanceCard(vm: AppViewModel, cfg: AppConfig) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val dark = LocalDarkTheme.current
    val custom = !ThemeColors.isDefault(cfg.accentColor) && ThemeColors.PRESETS.none { it.argb == cfg.accentColor }
    SettingsCard(tr("Appearance")) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            Text(tr("Style"), style = MaterialTheme.typography.bodyLarge)
            Text(
                tr("Classic is the plain white and black look. Liquid glass has see-through cards over a soft moving gradient."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = cfg.uiStyle == AppConfig.STYLE_CLASSIC,
                onClick = { vm.updateConfig { it.copy(uiStyle = AppConfig.STYLE_CLASSIC) } },
                label = { Text(tr("Classic")) },
            )
            FilterChip(
                selected = cfg.uiStyle == AppConfig.STYLE_GLASS,
                onClick = { vm.updateConfig { it.copy(uiStyle = AppConfig.STYLE_GLASS) } },
                label = { Text(tr("Liquid glass")) },
            )
        }
        CardDivider()
        FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = cfg.themeMode == AppConfig.THEME_LIGHT,
                onClick = { vm.updateConfig { it.copy(themeMode = AppConfig.THEME_LIGHT) } },
                label = { Text(tr("White")) },
            )
            FilterChip(
                selected = cfg.themeMode == AppConfig.THEME_DARK,
                onClick = { vm.updateConfig { it.copy(themeMode = AppConfig.THEME_DARK) } },
                label = { Text(tr("Black")) },
            )
            FilterChip(
                selected = cfg.themeMode == AppConfig.THEME_SYSTEM,
                onClick = { vm.updateConfig { it.copy(themeMode = AppConfig.THEME_SYSTEM) } },
                label = { Text(tr("Follow system")) },
            )
        }
        CardDivider()
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(tr("Accent colour"), style = MaterialTheme.typography.bodyLarge)
            Text(
                // what the colour is called follows the screen: black on white, white on black
                if (dark) tr("White on black. The white can be any colour.") else tr("Black on white. The black can be any colour."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (preset in ThemeColors.PRESETS) {
                    FilterChip(
                        selected = if (preset.argb == 0) ThemeColors.isDefault(cfg.accentColor) else cfg.accentColor == preset.argb,
                        onClick = { vm.updateConfig { it.copy(accentColor = preset.argb) } },
                        label = { Text(tr(ThemeColors.presetLabel(preset, dark))) },
                        leadingIcon = { ColorDot(ThemeColors.accentFor(preset.argb, dark), 16.dp) },
                    )
                }
                FilterChip(
                    selected = custom,
                    onClick = { showPicker = true },
                    label = { Text(if (custom) tr("Custom") + " " + ThemeColors.toHex(cfg.accentColor) else tr("Custom") + "…") },
                    leadingIcon = {
                        ColorDot(ThemeColors.accentFor(if (custom) cfg.accentColor else ThemeColors.PICKER_START, dark), 16.dp)
                    },
                )
            }
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

@Composable
private fun SwitchRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    CardRow(
        title = title,
        summary = summary,
        onClick = { onChange(!checked) },
        trailing = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

/** A whole-number setting with a slider; the value is stored when the finger lifts. */
@Composable
private fun SliderRow(title: String, value: Int, min: Int, max: Int, default: Int, onCommit: (Int) -> Unit) {
    var shown by remember(value) { mutableStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(shown.roundToInt().toString(), style = MaterialTheme.typography.titleMedium)
        }
        Slider(
            value = shown,
            onValueChange = { shown = it },
            onValueChangeFinished = { onCommit(shown.roundToInt().coerceIn(min, max)) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = max - min - 1,
        )
        Text(
            tr("Default: {0}", default),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
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
            TextButton(onClick = onChoose) { Text(tr("Choose…")) }
            if (value.isNotEmpty()) TextButton(onClick = onClear) { Text(tr("Clear")) }
        }
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

    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Choose a colour")) },
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
                        label = { Text(tr("Hex code")) },
                        singleLine = true,
                        isError = ThemeColors.parseHex(hex) == null,
                        modifier = Modifier.weight(1f),
                    )
                }
                ChannelSlider("R", red) { setChannels(it, green, blue) }
                ChannelSlider("G", green) { setChannels(red, it, blue) }
                ChannelSlider("B", blue) { setChannels(red, green, it) }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PreviewBox(tr("On white"), ThemeColors.accentFor(argb, false), Color.White)
                    PreviewBox(tr("On black"), ThemeColors.accentFor(argb, true), Color.Black)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onUse(argb) }) { Text(tr("Use")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
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
