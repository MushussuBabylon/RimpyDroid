package com.rimpydroid.manager.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Core and the DLCs when the game is not in a folder the app can see (RimDroid keeps it in its own storage, so a
 * ModsConfig.xml copied out of it lists them while no folder of theirs can be scanned).
 */
class OfficialContentTests {

    private val core = "ludeon.rimworld"
    private val royalty = "ludeon.rimworld.royalty"
    private val ideology = "ludeon.rimworld.ideology"
    private val noRules = CommunityRules.EMPTY

    private fun mod(
        id: String,
        name: String = id,
        deps: List<String> = emptyList(),
        after: List<String> = emptyList(),
        source: ModSource = ModSource.LOCAL,
    ): ModEntry = ModEntry(
        dir = File("/fake/" + name.replace(' ', '_') + "_" + id),
        meta = ModMeta(
            name = name, authors = "", packageId = id, description = "", url = "", modVersion = "",
            supportedVersions = listOf("1.6"),
            dependencies = deps.map { ModDependency(it.lowercase(), null, null, null) },
            loadBefore = emptyList(),
            loadAfter = after.map { it.lowercase() },
            incompatibleWith = emptyList(),
        ),
        source = source,
        workshopId = null,
        installedManifest = null,
        lastModified = 1000L,
        previewFile = null,
    )

    private fun gameMods(vararg ids: String): List<ModEntry> =
        ids.map { mod(it, ModelOps.officialName(it) ?: it, source = ModSource.OFFICIAL) }

    @Test
    fun namesAndIdsOfTheOfficialContent() {
        assertTrue(ModelOps.isOfficialId(core))
        assertTrue(ModelOps.isOfficialId(royalty))
        assertFalse(ModelOps.isOfficialId("ludeon.rimworldmod"))
        assertFalse(ModelOps.isOfficialId("brrainz.harmony"))
        assertFalse(ModelOps.isOfficialId(""))
        assertEquals("Core", ModelOps.officialName(core))
        assertEquals("Royalty", ModelOps.officialName(royalty))
        assertEquals("Odyssey", ModelOps.officialName("ludeon.rimworld.odyssey"))
        assertNull(ModelOps.officialName("brrainz.harmony"))
    }

    @Test
    fun sortKeepsCoreAndDlcAtTheTopWhenTheGameCannotBeSeen() {
        val mods = listOf(mod("a.mod", "A mod"), mod("brrainz.harmony", "Harmony"))
        val active = listOf("a.mod", ideology, "brrainz.harmony", royalty, core)
        val out = ModelOps.sortActive(mods, active, noRules, keepCurrent = false)
        assertEquals(listOf("brrainz.harmony", core, royalty, ideology, "a.mod"), out.order)
        assertTrue(out.changed)
    }

    @Test
    fun anOrderThatIsAlreadyRightIsReportedAsUnchanged() {
        val mods = listOf(mod("a.mod", "A mod"))
        val active = listOf(core, royalty, "a.mod")
        val out = ModelOps.sortActive(mods, active, noRules, keepCurrent = false)
        assertEquals(active, out.order)
        assertFalse(out.changed)
    }

    @Test
    fun missingModsStillGoBehindTheSortedOnes() {
        val mods = listOf(mod("a.mod", "A mod"))
        val out = ModelOps.sortActive(mods, listOf("gone.mod", "a.mod", core), noRules, keepCurrent = false)
        assertEquals(listOf(core, "a.mod", "gone.mod"), out.order)
    }

    @Test
    fun aModThatLoadsAfterADlcIsPlacedBehindIt() {
        val mods = listOf(mod("b.mod", "B mod", after = listOf(royalty)), mod("a.mod", "A mod"))
        val out = ModelOps.sortActive(mods, listOf("b.mod", "a.mod", royalty, core), noRules, keepCurrent = false)
        assertEquals(listOf(core, royalty, "a.mod", "b.mod"), out.order)
        assertTrue(out.order.indexOf("b.mod") > out.order.indexOf(royalty))
    }

    @Test
    fun aDlcThatThisAppDoesNotKnowYetStaysAboveTheNormalMods() {
        val mods = listOf(mod("a.mod", "A mod"))
        val future = "ludeon.rimworld.futuredlc"
        val out = ModelOps.sortActive(mods, listOf("a.mod", future, royalty, core), noRules, keepCurrent = false)
        assertEquals(listOf(core, royalty, future, "a.mod"), out.order)
    }

    @Test
    fun withTheGameInSightTheOldBehaviourIsKept() {
        // Core and Royalty are installed, Ideology is not: it still sits in its DLC place instead of at the very end
        val mods = gameMods(core, royalty) + mod("a.mod", "A mod")
        val out = ModelOps.sortActive(mods, listOf("a.mod", ideology, royalty, core), noRules, keepCurrent = false)
        assertEquals(listOf(core, royalty, ideology, "a.mod"), out.order)
    }

    @Test
    fun noProblemIsReportedForCoreAndDlcWhenTheGameCannotBeSeen() {
        val mods = listOf(mod("a.mod", "A mod", deps = listOf(royalty)))
        val active = listOf(core, royalty, "a.mod")
        val issues = Analyzer.analyze(mods, active, noRules, null)
        assertTrue(issues.toString(), issues.none { it.type == IssueType.MISSING_MOD })
        assertTrue(issues.toString(), issues.none { it.type == IssueType.MISSING_DEPENDENCY })
        assertTrue(issues.toString(), issues.none { it.type == IssueType.CORE_INACTIVE })
    }

    @Test
    fun theAutomaticFixNeverRemovesCoreOrADlcThatCannotBeSeen() {
        val mods = listOf(mod("a.mod", "A mod"))
        val active = listOf("a.mod", royalty, core)
        val issues = Analyzer.analyze(mods, active, noRules, null)
        val fixed = ModelOps.applySafeFixes(issues, active, mods, noRules, keepCurrent = false)
        // nothing to fix: all three entries are still there (a later Sort puts Core and the DLC first)
        assertEquals(active, fixed.active)
        val sorted = ModelOps.sortActive(mods, fixed.active, noRules, keepCurrent = false).order
        assertEquals(listOf(core, royalty, "a.mod"), sorted)
    }

    @Test
    fun aModThatIsReallyMissingIsStillReportedAndRemovedByTheAutomaticFix() {
        val mods = listOf(mod("a.mod", "A mod"))
        val active = listOf(core, "gone.mod", "a.mod")
        val issues = Analyzer.analyze(mods, active, noRules, null)
        val missing = issues.filter { it.type == IssueType.MISSING_MOD }
        assertEquals(1, missing.size)
        assertEquals("gone.mod", missing[0].modId)
        val fixed = ModelOps.applySafeFixes(issues, active, mods, noRules, keepCurrent = false)
        assertEquals(listOf(core, "a.mod"), fixed.active)
    }

    @Test
    fun aDlcThatIsNotInstalledIsStillReportedWhenTheGameFolderIsSeen() {
        val mods = gameMods(core) + mod("a.mod", "A mod")
        val issues = Analyzer.analyze(mods, listOf(core, royalty, "a.mod"), noRules, null)
        val missing = issues.filter { it.type == IssueType.MISSING_MOD }
        assertEquals(1, missing.size)
        assertEquals(royalty, missing[0].modId)
        assertEquals(Fix.REMOVE_MISSING, missing[0].fix)
    }

    @Test
    fun aDlcThatAModNeedsAndTheListLacksIsExplainedAccordingToWhatIsKnown() {
        val needy = mod("a.mod", "A mod", deps = listOf(royalty))
        // game not visible: it is only known that the DLC is not in the load order
        val unseen = Analyzer.analyze(listOf(needy), listOf(core, "a.mod"), noRules, null)
            .single { it.type == IssueType.MISSING_DEPENDENCY }
        assertTrue(unseen.message, unseen.message.contains("not in the load order"))
        assertTrue(unseen.message, unseen.message.contains("Royalty"))
        // game visible, DLC folder absent: it really is not installed
        val seen = Analyzer.analyze(gameMods(core) + needy, listOf(core, "a.mod"), noRules, null)
            .single { it.type == IssueType.MISSING_DEPENDENCY }
        assertTrue(seen.message, seen.message.contains("not found in the game folder"))
    }

    @Test
    fun rowsShowCoreAndDlcAsPartOfTheGameWhenTheyCannotBeSeen() {
        val mods = listOf(mod("a.mod", "A mod"))
        val active = listOf(core, royalty, "a.mod")
        val rows = ModelOps.buildRows(mods, active, Analyzer.analyze(mods, active, noRules, null), emptyMap()).active
        assertEquals(3, rows.size)
        assertTrue(rows[0].unseenOfficial)
        assertTrue(rows[1].unseenOfficial)
        assertEquals("Core", rows[0].name)
        assertEquals("Royalty", rows[1].name)
        assertFalse(rows[2].unseenOfficial)
        assertEquals("A mod", rows[2].name)
        assertNull("no problem is attached to them", rows[0].severity)
        assertNull(rows[1].severity)
        assertTrue("searchable by name", rows[1].searchText.contains("royalty"))
    }

    @Test
    fun aMissingDlcIsAMissingModWhenTheGameFolderIsSeen() {
        val mods = gameMods(core) + mod("a.mod", "A mod")
        val active = listOf(core, royalty, "a.mod")
        val rows = ModelOps.buildRows(mods, active, Analyzer.analyze(mods, active, noRules, null), emptyMap()).active
        assertFalse(rows[0].missing)
        assertTrue(rows[1].missing)
        assertFalse(rows[1].unseenOfficial)
        assertNotNull(rows[1].severity)
    }
}
