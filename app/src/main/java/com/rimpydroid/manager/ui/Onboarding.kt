package com.rimpydroid.manager.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rimpydroid.manager.R
import com.rimpydroid.manager.core.AppConfig
import java.io.File
import kotlinx.coroutines.launch

private const val STEP_COUNT = 5
private const val FILES_STEP = 2

/** The first thing a new user sees: five short steps that explain how to use the app, from file access to the load order. */
@Composable
fun OnboardingScreen(vm: AppViewModel, onGrantStorage: () -> Unit) {
    val cfg by vm.config.collectAsState()
    val hasStorage by vm.hasStorageAccess.collectAsState()
    val page by vm.page.collectAsState()
    var step by rememberSaveable { mutableIntStateOf(0) }

    fun finish() {
        vm.updateConfig { it.copy(onboarded = true) }
    }

    // Back goes to the previous step, unless a page (such as the folder picker) is open on top of the guide
    BackHandler(enabled = step > 0 && page == Page.None) { step -= 1 }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tr("Step {0} of {1}", step + 1, STEP_COUNT),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { finish() }) { Text(tr("Skip")) }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                when (step) {
                    0 -> {
                        Image(
                            painter = painterResource(R.drawable.app_logo),
                            contentDescription = null,
                            modifier = Modifier.padding(bottom = 20.dp).size(104.dp).clip(RoundedCornerShape(24.dp)),
                        )
                        StepHeader(
                            tr("Welcome to RimpyDroid"),
                            tr("Download mods and manage your load order on your phone. A short guide follows."),
                        )
                        Text(
                            tr("Language"),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                        )
                        LanguageChips(cfg.language, onSelect = { code -> vm.updateConfig { it.copy(language = code) } })
                    }

                    1 -> {
                        StepHeader(
                            tr("Allow file access"),
                            tr("RimpyDroid needs All files access to read and write your mods and load order."),
                        )
                        Row(Modifier.padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (hasStorage) {
                                Tag(tr("Allowed"), okColor())
                            } else {
                                Button(onClick = onGrantStorage) { Text(tr("Allow access")) }
                                Text(
                                    tr("Not allowed yet"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 12.dp),
                                )
                            }
                        }
                    }

                    FILES_STEP -> {
                        StepHeader(tr("Where should your files live?"), tr("Pick one. You can change it later in Settings."))
                        StorageOptions(vm, cfg, hasStorage, Modifier.padding(top = 20.dp))
                    }

                    3 -> {
                        StepHeader(tr("Get mods and play"), "")
                        NumberedLine(
                            1,
                            tr("Workshop tab: search a mod by name, paste a link, or tap Browse to use the Steam Workshop like a browser. Tap Download."),
                        )
                        NumberedLine(
                            2,
                            tr("Mods tab: switch on the mods you want and tap Sort. The load order is saved for the game automatically."),
                        )
                    }

                    else -> {
                        StepHeader(
                            tr("Your data stays safe"),
                            tr("Lists and backups are kept in your phone's storage, so they survive removing the app."),
                        )
                        Text(
                            tr("Questions? Tap the (?) at the top of Settings and search for anything."),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(top = 20.dp),
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step > 0) TextButton(onClick = { step -= 1 }) { Text(tr("Back")) } else Spacer(Modifier.width(8.dp))
                Spacer(Modifier.weight(1f))
                StepDots(STEP_COUNT, step)
                Spacer(Modifier.weight(1f))
                if (step < STEP_COUNT - 1) {
                    Button(onClick = { step += 1 }, enabled = step != FILES_STEP || cfg.hasModsDir) { Text(tr("Next")) }
                } else {
                    Button(onClick = { finish() }) { Text(tr("Start")) }
                }
            }
        }
    }
}

@Composable
private fun StepHeader(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    if (body.isNotEmpty()) {
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun NumberedLine(number: Int, text: String) {
    Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.Top) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp).clip(CircleShape),
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(number.toString(), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
            }
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 14.dp))
    }
}

/**
 * The ways to say where the files live: a RimpyDroid folder in Downloads (made here, with Mods, Config and Lists
 * inside), the person's own RimWorld folder, or a RimpyDroid folder made in a place the person chooses. Used by the
 * welcome guide and by Settings.
 */
@Composable
fun StorageOptions(vm: AppViewModel, cfg: AppConfig, hasStorage: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val kind = cfg.storageKind
    val root = cfg.dataDirFile
    val defaultRoot = remember { vm.defaultDataDir.trim().takeIf { it.isNotEmpty() }?.let { File(it).absoluteFile } }
    // a RimpyDroid folder that is not the one in Downloads was made in a place the person chose
    val atDefault = root != null && defaultRoot != null && root.absoluteFile == defaultRoot

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OptionCard(
            selected = kind == AppConfig.STORAGE_RIMPY && atDefault,
            title = tr("RimpyDroid folder"),
            body = tr("Download/RimpyDroid with Mods, Config and Lists. Simple, and good for RimDroid."),
            badge = tr("Recommended"),
            onClick = {
                if (defaultRoot != null) {
                    scope.launch {
                        val error = vm.useRimpyFolder(defaultRoot)
                        if (error != null) Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                    }
                }
            },
        )
        OptionCard(
            selected = kind == AppConfig.STORAGE_RIMWORLD,
            title = tr("My RimWorld folder"),
            body = tr("The folder that holds Data and Mods (Winlator, Gamehub)."),
            onClick = {
                // leave the RimpyDroid folder behind, so that the Mods folder of the game is the one used
                if (kind == AppConfig.STORAGE_RIMPY) vm.updateConfig { it.copy(modsDir = "", configPath = "") }
                vm.openPage(Page.FolderPicker(PickPurpose.GAME_DIR))
            },
        )
        OptionCard(
            selected = kind == AppConfig.STORAGE_RIMPY && !atDefault,
            title = tr("RimpyDroid folder somewhere else"),
            body = if (kind == AppConfig.STORAGE_RIMPY && !atDefault && root != null) {
                tr("In use: {0}", root.absolutePath)
            } else {
                tr("Choose a folder, and RimpyDroid with Mods, Config and Lists is made inside it.")
            },
            onClick = { vm.openPage(Page.FolderPicker(PickPurpose.RIMPY_PARENT)) },
        )
        val mods = cfg.modsDirFile
        if (mods != null) {
            Text(
                tr("Mods go to: {0}", mods.absolutePath),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        // the DLC come with the RimpyDroid folder: the game is not where the app can see it
        DlcSection(vm, cfg, Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
    }
}
