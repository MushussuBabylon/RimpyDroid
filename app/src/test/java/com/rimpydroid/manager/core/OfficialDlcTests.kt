package com.rimpydroid.manager.core

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DLC of the RimpyDroid folder. The game is not in a folder the app can see there, so nothing official is scanned:
 * the person says which DLC they own (all ticked from the start, they untick what they lack) and those are put in the
 * load order and in ModsConfig.xml (`activeMods` and `knownExpansions`).
 */
class OfficialDlcTests {

    private val royalty = "ludeon.rimworld.royalty"
    private val ideology = "ludeon.rimworld.ideology"
    private val biotech = "ludeon.rimworld.biotech"
    private val anomaly = "ludeon.rimworld.anomaly"
    private val odyssey = "ludeon.rimworld.odyssey"
    private val core = "ludeon.rimworld"
    private val all = listOf(royalty, ideology, biotech, anomaly, odyssey)

    private val dirs = ArrayList<File>()

    @After
    fun cleanUp() {
        dirs.forEach { it.deleteRecursively() }
    }

    private fun temp(): File = Files.createTempDirectory("rimpy-dlc").toFile().also { dirs.add(it) }

    private fun mod(id: String, before: List<String> = emptyList(), dir: File = File("/fake/$id")) = ModEntry(
        dir = dir,
        meta = ModMeta(
            name = id, authors = "", packageId = id, description = "", url = "", modVersion = "",
            supportedVersions = listOf("1.6"), dependencies = emptyList(),
            loadBefore = before, loadAfter = emptyList(), incompatibleWith = emptyList(),
        ),
        source = ModSource.LOCAL, workshopId = null, installedManifest = null, lastModified = 1L, previewFile = null,
    )

    /** What the scan makes of Core and a DLC found in a game folder. */
    private fun found(id: String, folder: String) =
        mod(id, dir = File("/game/Data/$folder")).copy(source = ModSource.OFFICIAL)

    private fun rimpy(chosen: Boolean, owned: List<String> = emptyList()): Pair<AppConfig, File> {
        val root = temp()
        val cfgFile = File(File(root, "Config"), "ModsConfig.xml")
        val config = AppConfig(
            dataDir = root.absolutePath,
            modsDir = StorageLayout.modsDir(root).absolutePath,
            configPath = cfgFile.absolutePath,
            dlcChosen = chosen,
            ownedDlc = owned,
        )
        return config to cfgFile
    }

    // ---- the list of DLC ------------------------------------------------------------------------

    @Test
    fun normalizeKeepsOnlyKnownDlcInLoadingOrderWithoutDuplicates() {
        assertEquals(
            listOf(royalty, biotech),
            OfficialDlc.normalize(listOf("Ludeon.RimWorld.Biotech", "some.mod", royalty, "LUDEON.RIMWORLD.ROYALTY", core)),
        )
        assertEquals(emptyList<String>(), OfficialDlc.normalize(emptyList()))
        assertEquals(all, OfficialDlc.normalize(all.reversed()))
    }

    @Test
    fun theChoiceSurvivesTheSettingsText() {
        assertEquals("$royalty,$biotech", OfficialDlc.format(listOf(biotech, royalty)))
        assertEquals(listOf(royalty, biotech), OfficialDlc.parse("$biotech, $royalty"))
        assertEquals(emptyList<String>(), OfficialDlc.parse(null))
        assertEquals(emptyList<String>(), OfficialDlc.parse(""))
    }

    // ---- what the app treats as installed -------------------------------------------------------

    @Test
    fun theRimpyDroidFolderIgnoresOfficialContentUntilTheDlcAreChosen() {
        assertTrue(rimpy(chosen = false).first.ignoresOfficial)
        assertFalse(rimpy(chosen = true, owned = all).first.ignoresOfficial)
    }

    @Test
    fun withoutAChoiceNothingIsAdded() {
        val mods = listOf(mod("a.mod"))
        assertEquals(mods, OfficialDlc.withChosen(mods, rimpy(chosen = false, owned = all).first))
    }

    @Test
    fun chosenDlcAndCoreStandInForTheGame() {
        val (config, _) = rimpy(chosen = true, owned = listOf(royalty, biotech))
        val shown = OfficialDlc.withChosen(listOf(mod("a.mod")), config)

        assertEquals(listOf(core, royalty, biotech, "a.mod"), shown.map { it.id })
        assertTrue(shown.take(3).all { it.isOfficial && OfficialDlc.isStandIn(it) })
        assertFalse(OfficialDlc.isStandIn(shown.last()))
        assertFalse("nothing was found in a game folder", OfficialDlc.gameVisible(shown))
        assertEquals(emptyList<String>(), OfficialDlc.detected(shown))
    }

    @Test
    fun aGameFolderThatIsScannedIsBelievedOverTheChoice() {
        val (config, _) = rimpy(chosen = true, owned = all)
        val real = listOf(found(core, "Core"), found(royalty, "Royalty"))

        assertEquals(real, OfficialDlc.withChosen(real, config))
        assertTrue(OfficialDlc.gameVisible(real))
        assertEquals(listOf(royalty), OfficialDlc.detected(real))
    }

    @Test
    fun noChosenDlcIsEverAddedTwice() {
        val (config, _) = rimpy(chosen = true, owned = listOf(royalty))
        val once = OfficialDlc.withChosen(listOf(mod("a.mod")), config)
        // the library holds the stand-ins already (a second pass over it, as after an in-memory change)
        assertEquals(once.map { it.id }, OfficialDlc.withChosen(once, config).map { it.id })
    }

    // ---- the load order -------------------------------------------------------------------------

    @Test
    fun theFirstChoiceAddsCoreAndEveryChosenDlcInFrontOfTheMods() {
        val next = OfficialDlc.applyChoice(listOf("a.mod", "b.mod"), emptyList(), all)
        assertEquals(listOf(core) + all + listOf("a.mod", "b.mod"), next)
    }

    @Test
    fun harmonyKeepsItsPlaceInFrontOfCore() {
        val harmony = mod("brrainz.harmony", before = listOf(core))
        val next = OfficialDlc.applyChoice(
            listOf("brrainz.harmony", "a.mod"), emptyList(), listOf(royalty, ideology),
            OfficialDlc.loadsBeforeCore(listOf(harmony)),
        )
        assertEquals(listOf("brrainz.harmony", core, royalty, ideology, "a.mod"), next)
    }

    @Test
    fun newDlcJoinsRightAfterTheOnesThatAreThere() {
        val next = OfficialDlc.applyChoice(listOf(core, royalty, "a.mod"), listOf(royalty), listOf(royalty, biotech))
        assertEquals(listOf(core, royalty, biotech, "a.mod"), next)
    }

    @Test
    fun aDlcThatIsNotOwnedLeavesTheList() {
        val next = OfficialDlc.applyChoice(listOf(core) + all + listOf("a.mod"), all, listOf(royalty, biotech))
        assertEquals(listOf(core, royalty, biotech, "a.mod"), next)
    }

    @Test
    fun aDlcSwitchedOffOnPurposeStaysOff() {
        // royalty is owned (before and after) but is not in the list: the person deactivated it in the Mods tab
        val next = OfficialDlc.applyChoice(listOf(core, "a.mod"), listOf(royalty, biotech), listOf(royalty, biotech, ideology))
        assertEquals(listOf(core, ideology, "a.mod"), next)
    }

    @Test
    fun aDlcThatWasActiveAlreadyIsNotAddedAgain() {
        val next = OfficialDlc.applyChoice(listOf(core, royalty, "a.mod"), emptyList(), listOf(royalty))
        assertEquals(listOf(core, royalty, "a.mod"), next)
    }

    @Test
    fun otherModsAreNeverTouched() {
        val list = listOf("x.mod", "ludeon.rimworld.futuredlc", "y.mod")
        assertEquals(list, OfficialDlc.applyChoice(list, listOf(royalty), listOf(royalty)))
    }

    // ---- the file the game reads ----------------------------------------------------------------

    /** The ModsConfig.xml from the bug report: five known expansions, but neither Core nor a single DLC active. */
    @Test
    fun theFileThatShowedNoDlcNowListsAllOfThem() {
        val (config, cfgFile) = rimpy(chosen = true, owned = all)
        cfgFile.parentFile.mkdirs()
        cfgFile.writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<ModsConfigData>
  <version>1.6.4633 rev1261</version>
  <activeMods>
    <li>brrainz.harmony</li>
    <li>a.mod</li>
  </activeMods>
  <knownExpansions>
    <li>ludeon.rimworld.royalty</li>
    <li>ludeon.rimworld.ideology</li>
    <li>ludeon.rimworld.biotech</li>
    <li>ludeon.rimworld.anomaly</li>
    <li>ludeon.rimworld.odyssey</li>
  </knownExpansions>
</ModsConfigData>
""",
        )
        val harmony = mod("brrainz.harmony", before = listOf(core))
        val mods = OfficialDlc.withChosen(listOf(harmony, mod("a.mod")), config)
        val active = OfficialDlc.applyChoice(
            ModsConfigXml.read(cfgFile)!!.activeMods, emptyList(), all, OfficialDlc.loadsBeforeCore(mods),
        )

        Library.save(config, null, mods, active)

        val data = ModsConfigXml.read(cfgFile)!!
        assertEquals(listOf("brrainz.harmony", core) + all + listOf("a.mod"), data.activeMods)
        assertEquals(all, data.knownExpansions)
        XmlUtil.parse(cfgFile.readText())
    }

    @Test
    fun aDlcThatIsOwnedButSwitchedOffIsStillKnownSoTheGameDoesNotSwitchItOn() {
        val (config, cfgFile) = rimpy(chosen = true, owned = listOf(royalty, biotech))
        val mods = OfficialDlc.withChosen(listOf(mod("a.mod")), config)

        // biotech is owned, but the person deactivated it
        Library.save(config, null, mods, listOf(core, royalty, "a.mod"))

        val data = ModsConfigXml.read(cfgFile)!!
        assertEquals(listOf(core, royalty, "a.mod"), data.activeMods)
        assertEquals(listOf(royalty, biotech), data.knownExpansions)
    }

    @Test
    fun aDlcBoughtLaterIsAddedToTheKnownOnes() {
        val (config, cfgFile) = rimpy(chosen = true, owned = listOf(royalty, ideology))
        cfgFile.parentFile.mkdirs()
        cfgFile.writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<ModsConfigData>
  <version>1.6.4633 rev1261</version>
  <activeMods><li>ludeon.rimworld</li><li>ludeon.rimworld.royalty</li></activeMods>
  <knownExpansions><li>ludeon.rimworld.royalty</li></knownExpansions>
</ModsConfigData>
""",
        )
        val mods = OfficialDlc.withChosen(emptyList(), config)

        Library.save(config, null, mods, listOf(core, royalty, ideology))

        assertEquals(listOf(royalty, ideology), ModsConfigXml.read(cfgFile)!!.knownExpansions)
    }

    @Test
    fun theKnownListNeverHoldsTheSameExpansionTwice() {
        assertEquals(
            listOf(royalty, ideology, biotech),
            OfficialDlc.mergeKnown(listOf(royalty, ideology), listOf("Ludeon.RimWorld.Ideology", biotech, royalty)),
        )
        assertEquals(listOf(royalty), OfficialDlc.mergeKnown(listOf(royalty), emptyList()))
        assertEquals(listOf(royalty), OfficialDlc.mergeKnown(emptyList(), listOf(royalty)))
    }

    // ---- what the analyzer says -----------------------------------------------------------------

    @Test
    fun aModThatNeedsADlcThePersonDoesNotOwnIsReportedAndOneThatNeedsAnOwnedDlcIsNot() {
        val (config, _) = rimpy(chosen = true, owned = listOf(royalty))
        val needsRoyalty = mod("needs.royalty").let { m ->
            m.copy(meta = m.meta.copy(dependencies = listOf(dep(royalty, "Royalty"))))
        }
        val needsBiotech = mod("needs.biotech").let { m ->
            m.copy(meta = m.meta.copy(dependencies = listOf(dep(biotech, "Biotech"))))
        }
        val mods = OfficialDlc.withChosen(listOf(needsRoyalty, needsBiotech), config)
        val active = listOf(core, royalty, "needs.royalty", "needs.biotech")

        val issues = Analyzer.analyze(mods, active, CommunityRules.EMPTY, null, checkOfficial = !config.ignoresOfficial)

        val forBiotech = issues.filter { it.modId == "needs.biotech" }
        assertTrue("the missing DLC is reported: $issues", forBiotech.any { it.message.contains("Biotech") })
        assertTrue(forBiotech.any { it.message.contains("not among the DLC you own") })
        assertTrue("an owned DLC is no problem", issues.none { it.modId == "needs.royalty" && it.message.contains("Royalty") })
    }

    private fun dep(id: String, name: String) = ModDependency(packageId = id, displayName = name, steamWorkshopUrl = null, downloadUrl = null)
}
