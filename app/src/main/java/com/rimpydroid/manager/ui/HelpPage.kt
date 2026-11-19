package com.rimpydroid.manager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A heading in the help list: the big one over a section, or the small one over a group of the reference. */
private class HelpHeading(val text: String, val main: Boolean)

/** Written in English here and looked up with tr(), so the help follows the language of the app (and so does its search). */
private fun helpTopics(): List<HelpTopic> = listOf(
    HelpTopic(
        0,
        tr("Getting started"),
        tr("Allow file access, choose your folder, get mods in the Workshop tab, switch them on in the Mods tab, tap Sort."),
    ),
    HelpTopic(
        1,
        tr("Where are my files?"),
        tr("Settings, Storage: use the RimpyDroid folder (Download/RimpyDroid), your RimWorld folder, or a RimpyDroid folder made somewhere else. You can switch at any time."),
    ),
    HelpTopic(
        2,
        tr("Find and download mods"),
        tr("Workshop tab: search a mod by name, paste a link, or tap Browse to use the Steam Workshop like a browser. Tap Download."),
    ),
    HelpTopic(
        3,
        tr("Download speed"),
        tr("Settings, Downloads: connections (default 16) and mods at once (default 5). Use fewer on weak Wi-Fi."),
    ),
    HelpTopic(
        4,
        tr("Load order and Sort"),
        tr("Mods tab: switch on the mods you want and tap Sort. The load order is saved for the game automatically."),
    ),
    HelpTopic(
        5,
        tr("Problems and clean up"),
        tr("Problems shows what is missing or out of order, with one-tap fixes. Clean up mods (Mods tab menu) deletes incompatible, inactive or duplicate mods after a preview."),
    ),
    HelpTopic(
        6,
        tr("Lists and backups"),
        tr("Lists are copied to Download/RimpyDroid/Lists. ModsConfig backups sit next to ModsConfig.xml. Both survive removing the app."),
    ),
    HelpTopic(
        7,
        tr("Updates"),
        tr("Mods tab menu: Check for updates, then Update all. Only changed files are downloaded."),
    ),
    HelpTopic(
        8,
        tr("Steam does not connect"),
        tr("Some networks block Steam. Try a VPN or another DNS. Settings, View log shows what happened."),
    ),
    HelpTopic(
        9,
        tr("Winlator and RimDroid"),
        tr("Winlator or Gamehub: choose your RimWorld folder and the game's ModsConfig.xml (start the game with -savedatafolder). RimDroid: use the RimpyDroid folder and copy mods and ModsConfig.xml across."),
    ),
)

/** The help: quick answers, then every button and setting, all in one list with a search box on top. */
@Composable
fun HelpPage(vm: AppViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableIntStateOf(-1) }
    val q = query.trim()
    fun matches(t: HelpTopic): Boolean =
        q.isEmpty() || t.title.contains(q, ignoreCase = true) || t.body.contains(q, ignoreCase = true) || t.group.contains(q, ignoreCase = true)

    val quick = helpTopics().filter { matches(it) }
    val reference = helpReference().filter { matches(it) }
    val rows = ArrayList<Any>()
    if (quick.isNotEmpty()) {
        rows.add(HelpHeading(tr("Quick answers"), true))
        rows.addAll(quick)
    }
    if (reference.isNotEmpty()) {
        rows.add(HelpHeading(tr("Every button and setting"), true))
        var last = ""
        for (t in reference) {
            if (t.group != last) {
                last = t.group
                rows.add(HelpHeading(last, false))
            }
            rows.add(t)
        }
    }

    PageScaffold(title = tr("Help & guide"), onBack = { vm.closePage() }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(tr("Search the help")) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = tr("Clear")) }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                if (rows.isEmpty()) {
                    item(key = "none") { CenteredMessage(tr("Nothing matches")) }
                }
                items(rows, key = { if (it is HelpTopic) "t" + it.id else "h" + (it as HelpHeading).text }) { row ->
                    if (row is HelpHeading) {
                        Text(
                            row.text,
                            style = if (row.main) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
                            color = if (row.main) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = if (row.main) 16.dp else 12.dp, bottom = 4.dp, start = 4.dp),
                        )
                    } else if (row is HelpTopic) {
                        val expanded = q.isNotEmpty() || open == row.id
                        AppPanel(
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            Column(
                                Modifier.clickable { open = if (open == row.id) -1 else row.id }.padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                Text(row.title, style = MaterialTheme.typography.titleMedium)
                                if (expanded) {
                                    Text(
                                        row.body,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                item(key = "again") {
                    OutlinedButton(
                        onClick = {
                            vm.updateConfig { it.copy(onboarded = false) }
                            vm.closePage()
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp),
                    ) { Text(tr("Show the welcome guide again")) }
                }
            }
        }
    }
}
