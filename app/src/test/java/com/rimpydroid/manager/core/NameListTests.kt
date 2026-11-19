package com.rimpydroid.manager.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.nio.file.Files

/**
 * Lists that only have the names of the mods, finding mods on the Workshop by name, Core in the RimpyDroid folder,
 * a Workshop page whose markup changed, and the ".nomedia" file. Pure JVM.
 */
class NameListTests {

    private val realPost = WorkshopApi.postForm
    private val realGet = WorkshopApi.getText

    @After
    fun restoreTheNetwork() {
        WorkshopApi.postForm = realPost
        WorkshopApi.getText = realGet
    }

    private fun tmpDir(): File = Files.createTempDirectory("rimpydroid-names").toFile()

    private fun mod(id: String, name: String = id, needs: List<ModDependency> = emptyList()): ModEntry = ModEntry(
        dir = File("/fake/" + name.replace(' ', '_') + "_" + id),
        meta = ModMeta(
            name = name, authors = "", packageId = id, description = "", url = "", modVersion = "",
            supportedVersions = listOf("1.6"), dependencies = needs,
            loadBefore = emptyList(), loadAfter = emptyList(), incompatibleWith = emptyList(),
        ),
        source = ModSource.LOCAL,
        workshopId = null,
        installedManifest = null,
        lastModified = 1000L,
        previewFile = null,
    )

    private fun item(id: Long, title: String, subscriptions: Long = 0L, tags: List<String> = emptyList(), fileType: Int = 0) = WorkshopItem(
        id = id, title = title, description = "", previewUrl = null, fileSize = 0L, timeUpdated = 0L, timeCreated = 0L,
        manifest = "1", consumerAppId = 294100L, fileType = fileType, subscriptions = subscriptions, tags = tags,
        children = emptyList(), result = 1, visibility = 0, banned = false,
    )

    /** A Steam that lists [pageIds] (given the searched text) on its browse page and knows the [titles] of the items. */
    private fun fakeSteam(titles: Map<Long, String>, pageIds: (String) -> List<Long>) {
        WorkshopApi.getText = { url ->
            val q = Regex("searchtext=([^&]*)").find(url)?.groupValues?.get(1).orEmpty()
            pageIds(URLDecoder.decode(q, "UTF-8")).joinToString("") { """<div class="workshopItem"><a data-publishedfileid="$it"></a></div>""" }
        }
        WorkshopApi.postForm = { _, fields ->
            val ids = fields.filter { it.first.startsWith("publishedfileids[") }.map { it.second.toLong() }
            val details = ids.joinToString(",") { id ->
                """{"publishedfileid":"$id","result":1,"title":"${titles[id] ?: "x"}","consumer_app_id":294100,"file_type":0,"subscriptions":5,"hcontent_file":"99","file_size":"10","time_updated":1,"time_created":1,"visibility":0,"banned":0}"""
            }
            """{"response":{"result":1,"resultcount":${ids.size},"publishedfiledetails":[$details]}}"""
        }
    }

    // ---- NameMatch ----------------------------------------------------------------------------------

    @Test
    fun theSameWordsScoreFullWhateverTheTagsAndSpacing() {
        assertEquals(NameMatch.EXACT, NameMatch.score("Hospitality", "[1.6] Hospitality"))
        assertEquals(NameMatch.EXACT, NameMatch.score("Combat Extended", "combat extended (Continued)"))
        assertTrue(NameMatch.score("RimHUD", "Rim HUD") >= 96)
        assertEquals(0, NameMatch.score("Hospitality", "Dubs Bad Hygiene"))
    }

    @Test
    fun aTitleThatLacksAWordIsAnotherMod() {
        val sibling = NameMatch.score("Vanilla Factions Expanded - Core", "Vanilla Factions Expanded - Ancients")
        assertTrue(sibling.toString(), sibling < NameMatch.MIN_SCORE)
    }

    @Test
    fun aFewExtraWordsAreFineButASentenceIsNot() {
        assertTrue(NameMatch.score("Combat Extended", "Combat Extended: Guns") >= NameMatch.MIN_SCORE)
        assertTrue(NameMatch.score("Harmony", "Harmony and a whole lot of other words in this very long title") < NameMatch.MIN_SCORE)
    }

    @Test
    fun theBestItemIsTheExactTitleThenTheMostSubscribed() {
        val items = listOf(
            item(1, "Hospitality - Better Guests", subscriptions = 100000),
            item(2, "Hospitality", subscriptions = 10),
            item(3, "Hospitality", subscriptions = 500),
            item(4, "Hospitality", subscriptions = 9999999, fileType = WorkshopUtil.FILE_TYPE_COLLECTION),
        )
        assertEquals(3L, NameMatch.best("Hospitality", items)?.id)
        assertNull(NameMatch.best("Something else entirely", items))
    }

    @Test
    fun theVersionOfTheGameDecidesBetweenEquallyGoodTitles() {
        val items = listOf(
            item(1, "Fluffy Breakdowns", subscriptions = 900, tags = listOf("1.4")),
            item(2, "Fluffy Breakdowns", subscriptions = 100, tags = listOf("1.6")),
        )
        assertEquals(2L, NameMatch.best("Fluffy Breakdowns", items, preferVersion = "1.6")?.id)
        assertEquals(1L, NameMatch.best("Fluffy Breakdowns", items)?.id)
    }

    @Test
    fun aNameIsGuessedFromAPackageIdWithoutItsAuthor() {
        assertEquals("harmony", NameMatch.guessFromPackageId("brrainz.harmony"))
        assertEquals("vanillafactionsexpanded core", NameMatch.guessFromPackageId("oskarpotocki.vanillafactionsexpanded.core"))
        assertNull(NameMatch.guessFromPackageId("nodot"))
        // a lower-case id is the same mod as the title with spaces
        assertTrue(NameMatch.score("vanillafactionsexpanded core", "Vanilla Factions Expanded - Core") >= NameMatch.MIN_GUESS_SCORE)
    }

    // ---- importing lists of names -------------------------------------------------------------------

    @Test
    fun aListThatOnlyHasNamesIsImported() {
        val text = "Harmony\nHospitality\n- Fluffy Breakdowns\n3. Dubs Bad Hygiene\n[x] Combat Extended\n\n  "
        val list = ListImporter.parse(text, "names")!!
        assertEquals(
            listOf("Harmony", "Hospitality", "Fluffy Breakdowns", "Dubs Bad Hygiene", "Combat Extended"),
            list.entries.map { it.name },
        )
        assertTrue(list.entries.all { it.id.isEmpty() && it.workshopId == null })
    }

    @Test
    fun linesThatDescribeAModAreNotTakenForNames() {
        val text = "Total number of mods: 3\nbrrainz.harmony\nAuthors: Someone\nHospitality\nActive mods:"
        val list = ListImporter.parse(text, "x")!!
        assertEquals(2, list.entries.size)
        assertEquals("brrainz.harmony", list.entries[0].id)
        assertEquals(ListEntry("", "Hospitality", null), list.entries[1])
    }

    @Test
    fun aVersionNumberInANameIsNotAPackageId() {
        val list = ListImporter.parse("Vanilla Expanded Framework v1.5.2", "x")!!
        assertEquals(1, list.entries.size)
        assertEquals("", list.entries[0].id)
        assertEquals("Vanilla Expanded Framework v1.5.2", list.entries[0].name)
    }

    @Test
    fun severalNamesOnOneLineAreSplit() {
        val list = ListImporter.parse("Harmony, Hospitality; Fluffy Breakdowns", "x")!!
        assertEquals(listOf("Harmony", "Hospitality", "Fluffy Breakdowns"), list.entries.map { it.name })
        val ids = ListImporter.parse("brrainz.harmony, orion.hospitality", "x")!!
        assertEquals(listOf("brrainz.harmony", "orion.hospitality"), ids.packageIds)
    }

    @Test
    fun aNameNextToAPackageIdBecomesTheNameOfTheEntry() {
        val list = ListImporter.parse("brrainz.harmony Harmony\nHospitality (orion.hospitality)", "x")!!
        assertEquals(ListEntry("brrainz.harmony", "Harmony", null), list.entries[0])
        assertEquals(ListEntry("orion.hospitality", "Hospitality", null), list.entries[1])
    }

    @Test
    fun entriesWithOnlyANameSurviveTheTextExport() {
        val list = ModList(
            "Names",
            listOf(ListEntry("", "Hospitality", null), ListEntry("", "Harmony", 2009463077L), ListEntry("brrainz.harmony", null, null)),
            null, 0L,
        )
        val back = ListImporter.parse(ListCodec.toText(list), "x")!!
        assertEquals(list.entries, back.entries)
    }

    @Test
    fun namesInJsonAreKept() {
        val json = """{"name":"j","mods":["brrainz.harmony","Hospitality",{"name":"Fluffy Breakdowns"},{"id":"A.B","name":"AB"},{"workshopId":"818773962"},{}]}"""
        val list = ListCodec.fromJson(json)!!
        assertEquals(
            listOf(
                ListEntry("brrainz.harmony", null, null),
                ListEntry("", "Hospitality", null),
                ListEntry("", "Fluffy Breakdowns", null),
                ListEntry("a.b", "AB", null),
                ListEntry("", null, 818773962L),
            ),
            list.entries,
        )
    }

    // ---- applying lists of names --------------------------------------------------------------------

    @Test
    fun aListOfNamesMatchesTheInstalledModsByName() {
        val harmony = mod("brrainz.harmony", "Harmony")
        val hospitality = mod("orion.hospitality", "Hospitality")
        val list = ModList(
            "n",
            listOf(ListEntry("", "[1.6] Hospitality", null), ListEntry("", "harmony", null), ListEntry("", "Not installed", null)),
            null, 0L,
        )
        val result = ListApplier.resolve(list, listOf(harmony, hospitality))
        assertEquals(listOf("orion.hospitality", "brrainz.harmony"), result.activeIds)
        assertEquals(1, result.missing.size)
        assertEquals("Not installed", result.missing[0].name)
    }

    @Test
    fun aNameThatTwoInstalledModsHaveMatchesNone() {
        val list = ModList("n", listOf(ListEntry("", "Same", null)), null, 0L)
        val result = ListApplier.resolve(list, listOf(mod("a.one", "Same"), mod("b.two", "Same")))
        assertTrue(result.activeIds.isEmpty())
        assertEquals(1, result.missing.size)
    }

    @Test
    fun coreAndTheDlcInAListAreLeftToTheGameInTheRimpyDroidFolder() {
        val list = ModList(
            "n",
            listOf(ListEntry("ludeon.rimworld", "Core", null), ListEntry("ludeon.rimworld.royalty", "Royalty", null), ListEntry("brrainz.harmony", "Harmony", null)),
            null, 0L,
        )
        val harmony = mod("brrainz.harmony", "Harmony")
        // as far as the app can see, Core and Royalty are not installed
        assertEquals(2, ListApplier.resolve(list, listOf(harmony)).missing.size)
        val left = ListApplier.resolve(list, listOf(harmony), skipOfficial = true)
        assertEquals(listOf("brrainz.harmony"), left.activeIds)
        assertTrue(left.missing.isEmpty())
    }

    @Test
    fun whatCanBeLookedUpOnTheWorkshop() {
        assertNull(ListApplier.lookupFor(ListEntry("", "Known link", 123456789L)))
        val byName = ListApplier.lookupFor(ListEntry("", "Hospitality", null))!!
        assertEquals("Hospitality", byName.query)
        assertEquals(NameMatch.MIN_SCORE, byName.minScore)
        val guess = ListApplier.lookupFor(ListEntry("brrainz.harmony", null, null))!!
        assertEquals("harmony", guess.query)
        assertEquals(NameMatch.MIN_GUESS_SCORE, guess.minScore)
        assertNull(ListApplier.lookupFor(ListEntry("ludeon.rimworld.royalty", "Royalty", null)))
        assertNull(ListApplier.lookupFor(ListEntry("", null, null)))
        assertTrue(ListApplier.canDownload(ListEntry("", null, 123456789L)))
        assertTrue(ListApplier.canDownload(ListEntry("", "Hospitality", null)))
        assertFalse(ListApplier.canDownload(ListEntry("", null, null)))
    }

    @Test
    fun foundWorkshopIdsAreWrittenIntoTheList() {
        val list = ModList("x", listOf(ListEntry("", "A", null), ListEntry("", "B", 5L), ListEntry("", "C", null)), null, 0L)
        val updated = list.withWorkshopIds(mapOf(0 to 11L, 1 to 22L))
        assertEquals(listOf(11L, 5L, null), updated.entries.map { it.workshopId })
        assertTrue(list === list.withWorkshopIds(emptyMap()))
    }

    // ---- finding mods on the Workshop by name -------------------------------------------------------

    @Test
    fun aModIsFoundByItsNameOnTheWorkshop() {
        fakeSteam(mapOf(1000001L to "Hospitality - Better Guests", 1000002L to "[1.6] Hospitality", 1000003L to "Dubs Bad Hygiene")) {
            listOf(1000001L, 1000002L, 1000003L)
        }
        assertEquals(1000002L, WorkshopApi.findByName("Hospitality")?.id)
        assertNull(WorkshopApi.findByName("Something that is not there"))
    }

    @Test
    fun manyNamesAreLookedUpTogetherAndAnsweredInOrder() {
        fakeSteam(mapOf(2000001L to "Harmony", 2000002L to "Hospitality")) { q ->
            when {
                q.contains("harmony", ignoreCase = true) -> listOf(2000001L)
                q.contains("hospitality", ignoreCase = true) -> listOf(2000002L)
                else -> emptyList()
            }
        }
        val answers = WorkshopApi.lookupNames(
            listOf(NameLookup("Harmony"), NameLookup("Hospitality"), NameLookup("Nothing like it"), NameLookup("harmony")),
        )
        assertEquals(listOf<Long?>(2000001L, 2000002L, null, 2000001L), answers.map { it.item?.id })
        assertTrue(answers.none { it.failed })
    }

    @Test
    fun aLookupThatCannotReachSteamIsReportedAsFailed() {
        WorkshopApi.getText = { throw IOException("offline") }
        val answers = WorkshopApi.lookupNames(listOf(NameLookup("Harmony")))
        assertTrue(answers[0].failed)
        assertNull(answers[0].item)
    }

    // ---- the Workshop page --------------------------------------------------------------------------

    @Test
    fun idsAreFoundWhenTheMarkupOfThePageHasChanged() {
        val classic = """<a class="ugc" data-publishedfileid="111111111"></a><a data-publishedfileid="222222222"></a>"""
        assertEquals(listOf(111111111L, 222222222L), WorkshopApi.parseBrowseIds(classic))
        val links = """<a href="https://steamcommunity.com/sharedfiles/filedetails/?id=333333333&amp;searchtext=x">a</a>
            <a href="https://steamcommunity.com/sharedfiles/filedetails/?id=333333333">b</a>
            <a href="https://steamcommunity.com/sharedfiles/filedetails/?id=444444444">c</a>"""
        assertEquals(listOf(333333333L, 444444444L), WorkshopApi.parseBrowseIds(links))
        val data = """<script>var d={"items":[{"publishedfileid":"555555555"},{"publishedfileid":666666666}]}</script>"""
        assertEquals(listOf(555555555L, 666666666L), WorkshopApi.parseBrowseIds(data))
        assertTrue(WorkshopApi.parseBrowseIds("<html>nothing</html>").isEmpty())
    }

    @Test
    fun theNumberOfResultsIsReadFromBothKindsOfPage() {
        assertEquals(100L, WorkshopApi.parseTotal("Showing 1-30 of 100 entries"))
        assertEquals(13L, WorkshopApi.parseTotal("<div>13 entries matching filters</div>"))
        assertNull(WorkshopApi.parseTotal("<html></html>"))
        assertTrue(WorkshopApi.parseHasMore("Showing 1-30 of 100 entries", 30))
        assertFalse(WorkshopApi.parseHasMore("Showing 91-100 of 100 entries", 10))
        assertFalse(WorkshopApi.parseHasMore("13 entries matching filters", 13, 1))
        assertTrue(WorkshopApi.parseHasMore("95 entries matching filters", 30, 1))
        assertFalse(WorkshopApi.parseHasMore("95 entries matching filters", 5, 4))
    }

    @Test
    fun collectionsAreSearchedWithoutAVersionTag() {
        var asked = ""
        WorkshopApi.getText = { url ->
            asked = url
            ""
        }
        WorkshopApi.search("pack", SearchSort.RELEVANCE, 1, collections = true, versionTag = "1.6")
        assertFalse(asked, asked.contains("requiredtags"))
        assertTrue(asked, asked.contains("section=collections"))
        WorkshopApi.search("pack", SearchSort.RELEVANCE, 1, collections = false, versionTag = "1.6")
        assertTrue(asked, asked.contains("requiredtags"))
    }

    // ---- Core in the RimpyDroid folder --------------------------------------------------------------

    @Test
    fun theRimpyDroidFolderIgnoresCoreAndTheOthersDoNot() {
        val root = File("/storage/emulated/0/Download/RimpyDroid")
        val rimpy = AppConfig(
            dataDir = root.path,
            modsDir = StorageLayout.modsDir(root).path,
            configPath = StorageLayout.configFile(root).path,
        )
        assertTrue(rimpy.ignoresOfficial)
        assertEquals(root, rimpy.rimpyRoot)
        val game = AppConfig(dataDir = root.path, gameDir = "/x/RimWorld")
        assertFalse(game.ignoresOfficial)
        assertNull(game.rimpyRoot)
    }

    @Test
    fun coreIsNotDemandedWhenItIsLeftToTheGame() {
        val a = mod("a.mod")
        val demanded = Analyzer.analyze(listOf(a), listOf("a.mod"), CommunityRules.EMPTY, GameVersion(1, 6))
        assertTrue(demanded.any { it.type == IssueType.CORE_INACTIVE })
        val left = Analyzer.analyze(listOf(a), listOf("a.mod"), CommunityRules.EMPTY, GameVersion(1, 6), checkOfficial = false)
        assertTrue(left.toString(), left.none { it.type == IssueType.CORE_INACTIVE })
    }

    @Test
    fun aModThatNeedsADlcIsNotReportedWhenTheGameIsLeftToTheGame() {
        val races = mod("convergence.races", "Convergence: Races", listOf(ModDependency("ludeon.rimworld.biotech", "Biotech", null, null)))
        val active = listOf("convergence.races")
        val reported = Analyzer.analyze(listOf(races), active, CommunityRules.EMPTY, GameVersion(1, 6))
        assertTrue(reported.any { it.type == IssueType.MISSING_DEPENDENCY })
        val left = Analyzer.analyze(listOf(races), active, CommunityRules.EMPTY, GameVersion(1, 6), checkOfficial = false)
        assertTrue(left.toString(), left.none { it.type == IssueType.MISSING_DEPENDENCY || it.type == IssueType.DEPENDENCY_INACTIVE })
        // a mod that is not part of the game is still reported when it is missing
        val needsMod = mod("x.y", "XY", listOf(ModDependency("brrainz.harmony", "Harmony", null, null)))
        val other = Analyzer.analyze(listOf(needsMod), listOf("x.y"), CommunityRules.EMPTY, GameVersion(1, 6), checkOfficial = false)
        assertTrue(other.any { it.type == IssueType.MISSING_DEPENDENCY })
        // an alternative that is a DLC counts as the game's content too
        val either = mod("a.b", "AB", listOf(ModDependency("some.other", "Other", null, null, listOf("ludeon.rimworld.royalty"))))
        val viaAlternative = Analyzer.analyze(listOf(either), listOf("a.b"), CommunityRules.EMPTY, GameVersion(1, 6), checkOfficial = false)
        assertTrue(viaAlternative.toString(), viaAlternative.none { it.type == IssueType.MISSING_DEPENDENCY })
    }

    @Test
    fun aFreshLoadOrderHasNoCoreStandInWhenCoreIsLeftToTheGame() {
        assertEquals(listOf(ModelOps.CORE_ID), ModelOps.initialActive(emptyList(), CommunityRules.EMPTY))
        assertEquals(emptyList<String>(), ModelOps.initialActive(emptyList(), CommunityRules.EMPTY, includeCore = false))
    }

    // ---- the RimpyDroid folder and its .nomedia file ------------------------------------------------

    @Test
    fun makingTheRimpyDroidFolderAlsoMakesTheNoMediaFile() {
        val parent = tmpDir()
        val root = StorageLayout.rootIn(parent)
        assertEquals(File(parent, "RimpyDroid"), root)
        assertNull(StorageLayout.create(root))
        val marker = File(root, ".nomedia")
        assertTrue(marker.isFile)
        assertEquals(0L, marker.length())
        // it is there already: nothing is made again
        assertFalse(StorageLayout.ensureNoMedia(root))
        // a folder that does not exist gets none
        assertFalse(StorageLayout.ensureNoMedia(File(parent, "missing")))
        assertFalse(File(parent, "missing").exists())
    }

    @Test
    fun aFolderForAnOlderInstallGetsTheNoMediaFileLater() {
        val root = File(tmpDir(), "RimpyDroid")
        assertTrue(root.mkdirs())
        assertFalse(File(root, ".nomedia").exists())
        assertTrue(StorageLayout.ensureNoMedia(root))
        assertTrue(File(root, ".nomedia").isFile)
    }

    @Test
    fun aFolderThatAlreadyIsCalledRimpyDroidIsUsedAsItIs() {
        val parent = tmpDir()
        val named = File(parent, "RimpyDroid")
        assertEquals(named, StorageLayout.rootIn(named))
        assertEquals(File(parent, "rimpydroid"), StorageLayout.rootIn(File(parent, "rimpydroid")))
        assertEquals(File(parent, "RimpyDroid"), StorageLayout.rootIn(parent))
    }

    // ---- saved lists --------------------------------------------------------------------------------

    @Test
    fun aCopiedListFileDoesNotMakeTwoListsWithTheSameName() {
        val dir = File(tmpDir(), "profiles")
        val store = ProfileStore(dir)
        store.save(ModList("Mine", listOf(ListEntry("a.b", null, null)), null, 5L))
        File(dir, "Mine.json").copyTo(File(dir, "Mine (copy).json"))
        val names = store.all().map { it.name }
        assertEquals(names.toString(), 2, names.size)
        assertEquals(names.toString(), names.size, names.map { it.lowercase() }.toSet().size)
    }
}
