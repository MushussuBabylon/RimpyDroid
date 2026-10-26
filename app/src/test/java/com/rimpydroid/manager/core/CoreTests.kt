package com.rimpydroid.manager.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Pure-JVM tests of the mod logic (no Android classes involved). */
class CoreTests {

    // ---- helpers -----------------------------------------------------------------------------------

    private fun tmpDir(): File = Files.createTempDirectory("rimpydroid-test").toFile()

    private fun write(f: File, text: String) {
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    private fun mod(
        id: String,
        name: String = id,
        deps: List<String> = emptyList(),
        after: List<String> = emptyList(),
        before: List<String> = emptyList(),
        incompatible: List<String> = emptyList(),
        versions: List<String> = listOf("1.6"),
        source: ModSource = ModSource.LOCAL,
        workshopId: Long? = null,
        manifest: String? = null,
    ): ModEntry = ModEntry(
        dir = File("/fake/" + name.replace(' ', '_') + "_" + id),
        meta = ModMeta(
            name = name, authors = "", packageId = id, description = "", url = "", modVersion = "",
            supportedVersions = versions,
            dependencies = deps.map { ModDependency(it.lowercase(), null, null, null) },
            loadBefore = before.map { it.lowercase() },
            loadAfter = after.map { it.lowercase() },
            incompatibleWith = incompatible.map { it.lowercase() },
        ),
        source = source,
        workshopId = workshopId,
        installedManifest = manifest,
        lastModified = 1000L,
        previewFile = null,
    )

    private fun rulesOf(json: String) = CommunityRules.parse(json)

    private val noRules = CommunityRules.EMPTY

    // ---- JSON ---------------------------------------------------------------------------------------

    @Test
    fun jsonRoundTripKeepsLongsExact() {
        val text = """{"a":[1,2.5,"xé\n",true,null],"b":{"c":"d"},"big":9007199254740993,"neg":-5}"""
        val v = Json.parse(text).asObj()!!
        assertEquals(9007199254740993L, v["big"].asLong())
        assertEquals(-5L, v["neg"].asLong())
        val again = Json.parse(Json.stringify(v)).asObj()!!
        assertEquals(v, again)
        val pretty = Json.parse(Json.stringify(v, pretty = true)).asObj()!!
        assertEquals(v, pretty)
        assertEquals("xé\n", v["a"].asArr()!![2])
    }

    @Test
    fun jsonRejectsBrokenInput() {
        assertNull(Json.parseOrNull("{\"a\":"))
        assertNull(Json.parseOrNull("[1,2,]"))
        assertNull(Json.parseOrNull("{} trailing"))
        assertNull(Json.parseOrNull(null))
    }

    // ---- About.xml ---------------------------------------------------------------------------------

    private val aboutSample = """<?xml version="1.0" encoding="utf-8"?>
<ModMetaData>
  <name>Vanilla Test &amp; Co</name>
  <author>Alice</author>
  <packageId>Alice.VanillaTest</packageId>
  <url>https://example.com</url>
  <supportedVersions>
    <li>1.5</li>
    <li>v1.6</li>
  </supportedVersions>
  <modDependencies>
    <li>
      <packageId>brrainz.harmony</packageId>
      <displayName>Harmony</displayName>
      <steamWorkshopUrl>steam://url/CommunityFilePage/2009463077</steamWorkshopUrl>
      <downloadUrl>https://github.com/pardeike/HarmonyRimWorld/releases/latest</downloadUrl>
    </li>
    <li>
      <packageId>Some.Framework</packageId>
      <alternativePackageIds><li>Other.Framework</li></alternativePackageIds>
    </li>
  </modDependencies>
  <modDependenciesByVersion>
    <v1.6><li><packageId>Only.For16</packageId><displayName>Only 1.6</displayName></li></v1.6>
    <v1.5><li><packageId>Only.For15</packageId></li></v1.5>
  </modDependenciesByVersion>
  <loadAfter><li>Ludeon.RimWorld</li></loadAfter>
  <loadAfterByVersion><v1.6><li>Late.Mod</li></v1.6></loadAfterByVersion>
  <loadBefore><li>Some.Patch</li></loadBefore>
  <incompatibleWith><li>Bad.Mod</li></incompatibleWith>
  <description>Hello</description>
  <descriptionsByVersion><v1.6>Hello 1.6</v1.6></descriptionsByVersion>
</ModMetaData>"""

    @Test
    fun aboutParserReadsEverythingForCurrentVersion() {
        val m = AboutParser.parseText(aboutSample, GameVersion(1, 6))
        assertEquals("Vanilla Test & Co", m.name)
        assertEquals("Alice", m.authors)
        assertEquals("Alice.VanillaTest", m.packageId)
        assertEquals(listOf("1.5", "1.6"), m.supportedVersions)
        assertEquals(listOf("brrainz.harmony", "some.framework", "only.for16"), m.dependencies.map { it.packageId })
        assertEquals(2009463077L, m.dependencies[0].workshopId)
        assertEquals(listOf("other.framework"), m.dependencies[1].alternatives)
        assertTrue(m.loadAfter.containsAll(listOf("ludeon.rimworld", "late.mod")))
        assertEquals(listOf("some.patch"), m.loadBefore)
        assertEquals(listOf("bad.mod"), m.incompatibleWith)
        assertEquals("Hello 1.6", m.description)
        assertNull(m.warning)
    }

    @Test
    fun aboutParserPicksOtherVersionSections() {
        val m = AboutParser.parseText(aboutSample, GameVersion(1, 5))
        assertEquals(listOf("brrainz.harmony", "some.framework", "only.for15"), m.dependencies.map { it.packageId })
        assertEquals(listOf("ludeon.rimworld"), m.loadAfter)
        assertEquals("Hello", m.description)
    }

    @Test
    fun aboutParserSurvivesBrokenXml() {
        val broken = "﻿<ModMetaData><name>Rock & Roll</name><packageId>A.B</packageId>" +
            "<supportedVersions><li>1.6</li></supportedVersions><loadAfter><li>X.Y</li></loadAfter></ModMetaData>"
        val m = AboutParser.parseText(broken, GameVersion(1, 6), "folder")
        assertEquals("Rock & Roll", m.name)
        assertEquals("A.B", m.packageId)
        assertEquals(listOf("1.6"), m.supportedVersions)
        assertEquals(listOf("x.y"), m.loadAfter)
        assertNotNull(m.warning)
        val garbage = AboutParser.parseText("not xml at all", GameVersion(1, 6), "fallback-name")
        assertEquals("fallback-name", garbage.name)
        assertEquals("", garbage.packageId)
    }

    @Test
    fun xmlThatDefinesEntitiesIsNotExpanded() {
        // a "billion laughs" file from a hostile mod: it must be refused quickly, and the mod still gets its name
        val bomb = "<?xml version=\"1.0\"?><!DOCTYPE lolz [<!ENTITY lol \"lol\">" +
            "<!ENTITY lol2 \"&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;\">" +
            "<!ENTITY lol3 \"&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;\">]>" +
            "<ModMetaData><name>Hostile</name><packageId>a.b</packageId><description>&lol3;</description></ModMetaData>"
        var refused = false
        try {
            XmlUtil.parse(bomb)
        } catch (e: Exception) {
            refused = true
        }
        assertTrue(refused)
        val m = AboutParser.parseText(bomb, GameVersion(1, 6), "folder")
        assertEquals("Hostile", m.name)
        assertEquals("a.b", m.packageId)
    }

    // ---- ModsConfig.xml -----------------------------------------------------------------------------

    private val modsConfigSample = """<?xml version="1.0" encoding="utf-8"?>
<ModsConfigData>
  <version>1.6.4630 rev467</version>
  <activeMods>
    <li>brrainz.harmony</li>
    <li>ludeon.rimworld</li>
    <li>Ludeon.RimWorld.Royalty</li>
  </activeMods>
  <knownExpansions>
    <li>ludeon.rimworld.royalty</li>
  </knownExpansions>
</ModsConfigData>"""

    @Test
    fun modsConfigParseAndRenderRoundTrip() {
        val d = ModsConfigXml.parse(modsConfigSample)!!
        assertEquals("1.6.4630 rev467", d.version)
        assertEquals(listOf("brrainz.harmony", "ludeon.rimworld", "Ludeon.RimWorld.Royalty"), d.activeMods)
        assertEquals(listOf("ludeon.rimworld.royalty"), d.knownExpansions)
        val again = ModsConfigXml.parse(ModsConfigXml.render(d.version, d.activeMods, d.knownExpansions))!!
        assertEquals(d, again)
    }

    @Test
    fun modsConfigWriteKeepsVersionBacksUpAndPrunes() {
        val dir = tmpDir()
        val f = File(dir, "Config/ModsConfig.xml")
        write(f, modsConfigSample)
        for (i in 1..12) write(File(dir, "Config/ModsConfig.xml.bak-2020010${i % 10}-0000${10 + i}"), "old")
        ModsConfigXml.write(f, listOf("ludeon.rimworld", "a.mod"), fallbackVersion = "9.9", fallbackExpansions = emptyList())
        val d = ModsConfigXml.read(f)!!
        assertEquals(listOf("ludeon.rimworld", "a.mod"), d.activeMods)
        assertEquals("1.6.4630 rev467", d.version) // the existing version wins
        assertEquals(listOf("ludeon.rimworld.royalty"), d.knownExpansions)
        val backups = f.parentFile.listFiles { x -> x.name.startsWith("ModsConfig.xml.bak-") }!!
        assertEquals(8, backups.size)
        assertTrue(backups.any { it.readText().contains("Ludeon.RimWorld.Royalty") }) // newest backup = previous content
    }

    @Test
    fun modsConfigIsCreatedWhenMissing() {
        val dir = tmpDir()
        val f = File(dir, "Config/ModsConfig.xml")
        ModsConfigXml.write(f, listOf("ludeon.rimworld"), "1.6.1 rev1", listOf("ludeon.rimworld.royalty"))
        val d = ModsConfigXml.read(f)!!
        assertEquals("1.6.1 rev1", d.version)
        assertEquals(listOf("ludeon.rimworld.royalty"), d.knownExpansions)
    }

    @Test
    fun modsConfigTolerantParserHandlesBrokenXml() {
        val broken = "<ModsConfigData><version>1.6</version><activeMods><li>a.b</li><li>c&d</li></activeMods></ModsConfigData>"
        val d = ModsConfigXml.parse(broken)!!
        assertEquals(listOf("a.b", "c&d"), d.activeMods)
    }

    // ---- Workshop helpers ---------------------------------------------------------------------------

    @Test
    fun workshopIdsAreFoundInAllLinkForms() {
        assertEquals(listOf(2009463077L), WorkshopUtil.extractIds("https://steamcommunity.com/sharedfiles/filedetails/?id=2009463077&searchtext="))
        assertEquals(listOf(2009463077L), WorkshopUtil.extractIds("https://steamcommunity.com/workshop/filedetails/?id=2009463077"))
        assertEquals(listOf(2009463077L), WorkshopUtil.extractIds("steam://url/CommunityFilePage/2009463077"))
        assertEquals(listOf(2009463077L, 818773962L), WorkshopUtil.extractIds("2009463077, 818773962\n"))
        assertEquals(listOf(2009463077L), WorkshopUtil.extractIds("2009463077 2009463077"))
        assertEquals(emptyList<Long>(), WorkshopUtil.extractIds("RimWorld 1.6.4630 rev467"))
        assertEquals(emptyList<Long>(), WorkshopUtil.extractIds("https://steamcommunity.com/id/76561198012345678"))
        assertEquals(emptyList<Long>(), WorkshopUtil.extractIds(null))
        assertEquals(2009463077L, WorkshopUtil.parseSingleId("steam://url/CommunityFilePage/2009463077"))
        assertNull(WorkshopUtil.parseSingleId("   "))
    }

    @Test
    fun plainTextStripsBbCodeAndRichText() {
        val t = WorkshopUtil.plainText("[b]Hello[/b] <color=#fff>World</color>\n[list][*]One[*]Two[/list][img]http://x/y.png[/img] &amp; done")
        assertTrue(t, t.contains("Hello World"))
        assertTrue(t, t.contains("• One"))
        assertTrue(t, t.contains("• Two"))
        assertTrue(t, t.contains("& done"))
        assertFalse(t, t.contains("[") || t.contains("<"))
    }

    @Test
    fun workshopItemParsing() {
        val item = Json.parse(
            """{"publishedfileid":"2009463077","result":1,"creator":"7656119800","creator_app_id":294100,"consumer_app_id":294100,
            "filename":"","file_size":"1234567","hcontent_file":"8340350889165239386","preview_url":"https://img/x/",
            "title":"Harmony","description":"[b]Hi[/b]","time_created":1570000000,"time_updated":1760000000,"visibility":0,"banned":0,
            "subscriptions":100,"lifetime_subscriptions":500,"tags":[{"tag":"Mod"},{"tag":"1.6"},{"tag":"1.5"}]}""",
        ).asObj()!!.let { WorkshopApi.parseItem(it)!! }
        assertEquals(2009463077L, item.id)
        assertEquals("8340350889165239386", item.manifest)
        assertEquals(1234567L, item.fileSize)
        assertEquals(1760000000L, item.timeUpdated)
        assertEquals(listOf("1.6", "1.5"), item.versionTags)
        assertTrue(item.isAvailable && item.isRimWorld && !item.isCollection)

        val coll = Json.parse(
            """{"publishedfileid":"1","result":1,"consumer_app_id":294100,"file_type":2,"title":"List",
            "children":[{"publishedfileid":"11","sortorder":1,"file_type":0},{"publishedfileid":"12","sortorder":2,"file_type":0}]}""",
        ).asObj()!!.let { WorkshopApi.parseItem(it)!! }
        assertTrue(coll.isCollection)
        assertEquals(listOf(11L, 12L), coll.children)

        val gone = Json.parse("""{"publishedfileid":"5","result":9}""").asObj()!!.let { WorkshopApi.parseItem(it)!! }
        assertFalse(gone.isAvailable)
    }

    @Test
    fun workshopBrowsePageParsing() {
        val html = """<a data-publishedfileid="111" href="x"></a><div data-publishedfileid="222"></div>
            <a data-publishedfileid="111"></a><div class="workshopBrowsePagingInfo">Showing 1-30 of 12,345 entries</div>"""
        assertEquals(listOf(111L, 222L), WorkshopApi.parseBrowseIds(html))
        assertTrue(WorkshopApi.parseHasMore(html, 2))
        assertFalse(WorkshopApi.parseHasMore("Showing 31-45 of 45 entries", 15))
        assertTrue(WorkshopApi.parseHasMore("no paging text", 30))
        assertFalse(WorkshopApi.parseHasMore("no paging text", 3))
    }

    // ---- install info / updates ---------------------------------------------------------------------

    @Test
    fun depotConfigManifestIsReadAsUnsigned() {
        val dir = tmpDir()
        write(File(dir, ".DepotDownloader/depot.config"), """{"installedManifestIDs":{"294100":8340350889165239386}}""")
        assertEquals("8340350889165239386", InstallInfo.depotConfigManifest(dir))
        write(File(dir, ".DepotDownloader/depot.config"), """{"installedManifestIDs":{"294100":-1}}""")
        assertEquals("18446744073709551615", InstallInfo.depotConfigManifest(dir))
        write(File(dir, ".DepotDownloader/depot.config"), """{"installedManifestIDs":{"294100":9223372036854775807}}""")
        assertNull(InstallInfo.depotConfigManifest(dir)) // "download unfinished" marker
        assertNull(InstallInfo.depotConfigManifest(tmpDir()))
    }

    @Test
    fun installInfoRoundTrip() {
        val dir = tmpDir()
        InstallInfo.write(dir, InstallInfo.Info(2009463077L, "123", 1760000000L, "Harmony"))
        val i = InstallInfo.read(dir)!!
        assertEquals(2009463077L, i.workshopId)
        assertEquals("123", i.manifest)
        assertEquals("Harmony", i.title)
        assertEquals("123", InstallInfo.installedManifest(dir))
    }

    private fun item(manifest: String?, result: Int = 1, id: Long = 5L) = WorkshopItem(
        id = id, title = "t", description = "", previewUrl = null, fileSize = 0, timeUpdated = 0, timeCreated = 0,
        manifest = manifest, consumerAppId = 294100, fileType = 0, subscriptions = 0, tags = emptyList(), children = emptyList(),
        result = result, visibility = 0, banned = false,
    )

    @Test
    fun updateStates() {
        val tracked = mod("a.b", workshopId = 5L, manifest = "100")
        assertEquals(UpdateState.UP_TO_DATE, UpdateChecker.stateFor(tracked, item("100")))
        assertEquals(UpdateState.UPDATE_AVAILABLE, UpdateChecker.stateFor(tracked, item("101")))
        assertEquals(UpdateState.UNAVAILABLE, UpdateChecker.stateFor(tracked, item("100", result = 9)))
        assertEquals(UpdateState.UNAVAILABLE, UpdateChecker.stateFor(tracked, null))
        val untracked = mod("c.d", workshopId = 5L)
        assertEquals(UpdateState.UNTRACKED, UpdateChecker.stateFor(untracked, item("100")))
        assertEquals(UpdateState.NOT_WORKSHOP, UpdateChecker.stateFor(mod("e.f"), null))
        assertEquals(UpdateState.NOT_WORKSHOP, UpdateChecker.stateFor(mod("ludeon.rimworld", source = ModSource.OFFICIAL, workshopId = 5L), item("1")))
    }

    // ---- community rules ----------------------------------------------------------------------------

    private val rulesSample = """{"timestamp":123,"rules":{
        "Brrainz.Harmony":{"loadBefore":{"UnlimitedHugs.HugsLib":{"name":["HugsLib"]}}},
        "A.Mod":{"loadAfter":{"B.Mod":{"name":["B"]}},"loadBottom":{"value":true,"comment":"last"}},
        "T.Mod":{"loadTop":{"value":true}},
        "X.Mod":{"incompatibleWith":{"Y.Mod":{"comment":"clash","name":"Y"}}},
        "N.Mod":{"loadTop":{"value":false}}
    }}"""

    @Test
    fun communityRulesParse() {
        val r = rulesOf(rulesSample)
        assertEquals(123L, r.timestamp)
        assertEquals(5, r.ruleCount)
        assertEquals(setOf("unlimitedhugs.hugslib"), r.loadBefore["brrainz.harmony"])
        assertEquals(setOf("b.mod"), r.loadAfter["a.mod"])
        assertEquals(setOf("a.mod"), r.loadBottom)
        assertEquals(setOf("t.mod"), r.loadTop)
        assertEquals("clash", r.incompatible["x.mod"]!!["y.mod"])
    }

    @Test
    fun communityRulesRealFile() {
        val path = System.getProperty("rimpydroid.rules")
        assumeTrue("set -Drimpydroid.rules=<communityRules.json> to run", path != null)
        val t0 = System.nanoTime()
        val r = CommunityRules.parseFile(File(path!!))!!
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("real rules: ${r.ruleCount} rules, ${r.loadAfter.size} loadAfter owners, ${r.loadBefore.size} loadBefore owners, parsed in ${ms} ms")
        assertTrue(r.ruleCount > 500)
        assertTrue(r.loadAfter["unlimitedhugs.hugslib"]!!.contains("ludeon.rimworld.biotech"))
        assertTrue("krkr.rocketman" in r.loadBottom)
        assertTrue("imranfish.xmlextensions" in r.loadTop)
        assertTrue(r.incompatible.isNotEmpty())
    }

    // ---- sorting ------------------------------------------------------------------------------------

    private fun positions(order: List<String>): Map<String, Int> = order.withIndex().associate { it.value to it.index }

    @Test
    fun sortPutsHarmonyCoreAndDlcFirstAndHonoursDependencies() {
        val mods = listOf(
            mod("zzz.content", "Zzz Content"),
            mod("aaa.content", "Aaa Content", deps = listOf("zzz.content")),
            mod("ludeon.rimworld.royalty", "Royalty", source = ModSource.OFFICIAL),
            mod("ludeon.rimworld", "Core", source = ModSource.OFFICIAL, before = emptyList()),
            mod("brrainz.harmony", "Harmony", before = listOf("ludeon.rimworld")),
            mod("some.patch", "Some Patch", after = listOf("aaa.content")),
            mod("a.bottom", "A Bottom"),
        )
        val rules = rulesOf("""{"rules":{"a.bottom":{"loadBottom":{"value":true}}}}""")
        val r = Sorter.sort(mods, rules)
        assertEquals(
            listOf("brrainz.harmony", "ludeon.rimworld", "ludeon.rimworld.royalty", "zzz.content", "aaa.content", "some.patch", "a.bottom"),
            r.order,
        )
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun sortGroupsLibrariesAndPullsTheirPrerequisitesUp() {
        val mods = listOf(
            mod("a.normal", "A Normal"),
            mod("z.dep", "Z Dep"),
            mod("m.lib", "M Library", deps = listOf("z.dep")),
            mod("b.normal", "B Normal"),
        )
        val r = Sorter.sort(mods, noRules)
        assertEquals(listOf("z.dep", "m.lib", "a.normal", "b.normal"), r.order)
    }

    @Test
    fun sortKeepsModsThatFollowABottomModAtTheBottom() {
        val mods = listOf(
            mod("a.bottom", "A Bottom"),
            mod("b.patch", "B Patch", after = listOf("a.bottom")),
            mod("c.normal", "C Normal"),
            mod("d.normal", "D Normal"),
        )
        val rules = rulesOf("""{"rules":{"a.bottom":{"loadBottom":{"value":true}}}}""")
        assertEquals(listOf("c.normal", "d.normal", "a.bottom", "b.patch"), Sorter.sort(mods, rules).order)
    }

    @Test
    fun sortHandlesCyclesWithoutLosingMods() {
        val mods = listOf(
            mod("a.mod", "A", after = listOf("b.mod")),
            mod("b.mod", "B", after = listOf("a.mod")),
            mod("c.mod", "C"),
        )
        val r = Sorter.sort(mods, noRules)
        assertEquals(3, r.order.size)
        assertEquals(3, r.order.toSet().size)
        assertEquals(1, r.ignoredRules)
        assertTrue(r.warnings.isNotEmpty())
    }

    @Test
    fun communityRulesNeverOverrideAuthorRules() {
        val mods = listOf(
            mod("a.mod", "A", deps = listOf("b.mod")),
            mod("b.mod", "B"),
        )
        val rules = rulesOf("""{"rules":{"a.mod":{"loadBefore":{"b.mod":{}}}}}""")
        val r = Sorter.sort(mods, rules)
        assertEquals(listOf("b.mod", "a.mod"), r.order)
        assertEquals(1, r.ignoredRules)
        assertTrue(r.warnings.isEmpty()) // a rejected community rule is not worth a warning
    }

    @Test
    fun sortTieBreakOptions() {
        val mods = listOf(mod("c.mod", "C"), mod("b.mod", "B"), mod("a.mod", "A"))
        assertEquals(listOf("a.mod", "b.mod", "c.mod"), Sorter.sort(mods, noRules).order)
        assertEquals(listOf("c.mod", "b.mod", "a.mod"), Sorter.sort(mods, noRules, Sorter.TieBreak.KEEP_CURRENT).order)
        // same input twice gives the same answer
        assertEquals(Sorter.sort(mods, noRules).order, Sorter.sort(mods.reversed(), noRules).order)
    }

    @Test
    fun sortIgnoresDuplicatesAndModsWithoutId() {
        val mods = listOf(mod("a.mod"), mod("a.mod"), mod(""))
        assertEquals(listOf("a.mod"), Sorter.sort(mods, noRules).order)
        assertEquals(emptyList<String>(), Sorter.sort(emptyList(), noRules).order)
    }

    @Test
    fun sortAlternativeDependenciesUseWhicheverIsActive() {
        val dep = ModDependency("main.fw", null, null, null, alternatives = listOf("alt.fw"))
        val m = mod("z.user").let { it.copy(meta = it.meta.copy(dependencies = listOf(dep))) }
        val r = Sorter.sort(listOf(m, mod("alt.fw", "Alt Fw")), noRules)
        assertEquals(listOf("alt.fw", "z.user"), r.order)
    }

    @Test
    fun sortWithTheRealCommunityRules() {
        val path = System.getProperty("rimpydroid.rules")
        assumeTrue("set -Drimpydroid.rules=<communityRules.json> to run", path != null)
        val rules = CommunityRules.parseFile(File(path!!))!!
        // every id the rules talk about becomes a fake mod
        val ids = LinkedHashSet<String>()
        for ((k, v) in rules.loadAfter) { ids.add(k); ids.addAll(v) }
        for ((k, v) in rules.loadBefore) { ids.add(k); ids.addAll(v) }
        ids.addAll(rules.loadBottom); ids.addAll(rules.loadTop)
        ids.add("ludeon.rimworld"); ids.add("ludeon.rimworld.royalty"); ids.add("ludeon.rimworld.ideology")
        val mods = ids.shuffled(java.util.Random(7)).map {
            mod(it, it, source = if (it.startsWith("ludeon.rimworld")) ModSource.OFFICIAL else ModSource.LOCAL)
        }
        val t0 = System.nanoTime()
        val r = Sorter.sort(mods, rules)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("sorted ${mods.size} mods with the real rules in $ms ms (ignored rules: ${r.ignoredRules})")
        assertEquals(mods.size, r.order.size)
        assertEquals(mods.size, r.order.toSet().size)
        val pos = positions(r.order)
        val edges = LoadOrderGraph.edges(mods, rules)
        val violated = edges.count { pos[it.from]!! > pos[it.to]!! }
        println("edges: ${edges.size}, violated: $violated")
        assertTrue("violated=$violated ignored=${r.ignoredRules}", violated <= r.ignoredRules)
        assertTrue(ms < 5000)
        assertTrue(pos["brrainz.harmony"]!! < pos["unlimitedhugs.hugslib"]!!)
        assertTrue(pos["ludeon.rimworld"]!! < pos["krkr.rocketman"]!!)
        // bottom mods really are at the bottom: only they and the mods forced after them may follow the first one
        val bottom = rules.loadBottom.filter { it in pos }
        val firstBottom = bottom.minOf { pos[it]!! }
        val succ = HashMap<String, MutableList<String>>()
        for (e in edges) succ.getOrPut(e.from) { ArrayList() }.add(e.to)
        val late = HashSet<String>(bottom)
        val stack = ArrayList(bottom)
        while (stack.isNotEmpty()) {
            for (w in succ[stack.removeAt(stack.size - 1)].orEmpty()) if (late.add(w)) stack.add(w)
        }
        println("first loadBottom mod at $firstBottom of ${mods.size}; mods bound to the bottom: ${late.size}")
        assertTrue("firstBottom=$firstBottom late=${late.size}", mods.size - firstBottom <= late.size)
        for (id in r.order.subList(firstBottom, r.order.size)) assertTrue("$id after the first bottom mod", id in late)
    }

    // ---- issue detection ----------------------------------------------------------------------------

    @Test
    fun analyzerFindsTheUsualProblems() {
        val core = mod("ludeon.rimworld", "Core", source = ModSource.OFFICIAL)
        val harmony = mod("brrainz.harmony", "Harmony")
        val a = mod(
            "a.mod", "A Mod", deps = listOf("brrainz.harmony", "missing.dep"),
            versions = listOf("1.5"), after = listOf("b.mod"), incompatible = listOf("c.mod"),
        )
        val b = mod("b.mod", "B Mod")
        val c = mod("c.mod", "C Mod")
        val issues = Analyzer.analyze(listOf(core, harmony, a, b, c), listOf("a.mod", "b.mod", "c.mod", "ghost.mod"), noRules, GameVersion(1, 6))
        val types = issues.map { it.type }.toSet()
        assertTrue(types.toString(), types.containsAll(listOf(
            IssueType.CORE_INACTIVE, IssueType.MISSING_MOD, IssueType.DEPENDENCY_INACTIVE, IssueType.MISSING_DEPENDENCY,
            IssueType.WRONG_ORDER, IssueType.INCOMPATIBLE, IssueType.OUTDATED_VERSION,
        )))
        val coreIssue = issues.first { it.type == IssueType.CORE_INACTIVE }
        assertEquals(Fix.ACTIVATE, coreIssue.fix)
        val inactive = issues.first { it.type == IssueType.DEPENDENCY_INACTIVE }
        assertEquals(listOf("brrainz.harmony"), inactive.relatedIds)
        assertEquals(Severity.ERROR, issues.first().severity) // errors first
        assertTrue(Analyzer.byMod(issues).containsKey("a.mod"))
    }

    @Test
    fun analyzerIsQuietAfterSorting() {
        val core = mod("ludeon.rimworld", "Core", source = ModSource.OFFICIAL)
        val harmony = mod("brrainz.harmony", "Harmony")
        val a = mod("a.mod", "A Mod", deps = listOf("brrainz.harmony"), after = listOf("b.mod"))
        val b = mod("b.mod", "B Mod")
        val all = listOf(core, harmony, a, b)
        val order = Sorter.sort(all, noRules).order
        val issues = Analyzer.analyze(all, order, noRules, GameVersion(1, 6))
        assertTrue(issues.toString(), issues.none { it.severity != Severity.INFO })
    }

    @Test
    fun analyzerSeesDuplicateInstallsAndMissingPackageIds() {
        val one = mod("a.mod", "A one")
        val two = mod("a.mod", "A two")
        val noId = mod("", "Nameless")
        val issues = Analyzer.analyze(listOf(one, two, noId), listOf("a.mod", "a.mod"), noRules, null)
        val types = issues.map { it.type }
        assertTrue(types.contains(IssueType.DUPLICATE_INSTALL))
        assertTrue(types.contains(IssueType.DUPLICATE_ACTIVE))
        assertTrue(types.contains(IssueType.NO_PACKAGE_ID))
    }

    @Test
    fun analyzerAcceptsAlternativeDependencies() {
        val dep = ModDependency("main.fw", "Main FW", null, null, alternatives = listOf("alt.fw"))
        val user = mod("z.user").let { it.copy(meta = it.meta.copy(dependencies = listOf(dep))) }
        val alt = mod("alt.fw")
        val issues = Analyzer.analyze(listOf(user, alt), listOf("alt.fw", "z.user"), noRules, null)
        assertTrue(issues.toString(), issues.none { it.type == IssueType.MISSING_DEPENDENCY || it.type == IssueType.DEPENDENCY_INACTIVE })
    }

    @Test
    fun analyzerReportsCommunityIncompatibility() {
        val rules = rulesOf("""{"rules":{"x.mod":{"incompatibleWith":{"y.mod":{"comment":"clash"}}}}}""")
        val issues = Analyzer.analyze(listOf(mod("x.mod"), mod("y.mod")), listOf("x.mod", "y.mod"), rules, null)
        val inc = issues.first { it.type == IssueType.INCOMPATIBLE }
        assertEquals(Severity.WARNING, inc.severity)
        assertTrue(inc.message.contains("clash"))
    }

    // ---- lists / profiles ---------------------------------------------------------------------------

    @Test
    fun importRimWorldModListMetadata() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<ModList><meta><gameVersion>1.6.4630 rev467</gameVersion>
<modIds><li>ludeon.rimworld</li><li>Brrainz.Harmony</li><li>local.mod</li></modIds>
<modNames><li>Core</li><li>Harmony</li><li>Local</li></modNames>
<modSteamIds><li>0</li><li>2009463077</li><li>0</li></modSteamIds></meta></ModList>"""
        val l = ListImporter.parse(xml, "x")!!
        assertEquals(listOf("ludeon.rimworld", "brrainz.harmony", "local.mod"), l.packageIds)
        assertEquals(listOf(2009463077L), l.workshopIds)
        assertEquals("Harmony", l.entries[1].name)
        assertEquals("1.6.4630 rev467", l.gameVersion)
    }

    @Test
    fun importModsConfigAndPlainText() {
        val c = ListImporter.parse(modsConfigSample, "cfg")!!
        assertEquals(listOf("brrainz.harmony", "ludeon.rimworld", "ludeon.rimworld.royalty"), c.packageIds)

        val plain = """# My list
ludeon.rimworld
Brrainz.Harmony  # Harmony  [2009463077]
https://steamcommunity.com/sharedfiles/filedetails/?id=818773962
2038000893
// comment
nonsense line without ids"""
        val l = ListImporter.parse(plain, "fallback")!!
        assertEquals("My list", l.name)
        assertEquals(4, l.entries.size)
        assertEquals(ListEntry("ludeon.rimworld", null, null), l.entries[0])
        assertEquals(ListEntry("brrainz.harmony", "Harmony", 2009463077L), l.entries[1])
        assertEquals(ListEntry("", null, 818773962L), l.entries[2])
        assertEquals(ListEntry("", null, 2038000893L), l.entries[3])
        assertNull(ListImporter.parse("   ", "x"))
        assertNull(ListImporter.parse("hello world", "x"))
    }

    @Test
    fun listCodecRoundTrips() {
        val list = ModList(
            "Dev list", listOf(
                ListEntry("ludeon.rimworld", "Core", null),
                ListEntry("brrainz.harmony", "Harmony", 2009463077L),
                ListEntry("", "Only on Workshop", 818773962L),
            ),
            "1.6.4630 rev467", 1700000000000L,
        )
        assertEquals(list, ListCodec.fromJson(ListCodec.toJson(list)))
        val viaText = ListImporter.parse(ListCodec.toText(list), "x")!!
        assertEquals(list.entries, viaText.entries)
        assertEquals("Dev list", viaText.name)
        assertEquals(list, ListImporter.parse(ListCodec.toJson(list), "x"))
    }

    @Test
    fun applyingAListMatchesInstalledModsByIdOrWorkshopId() {
        val harmony = mod("brrainz.harmony", "Harmony", workshopId = 2009463077L)
        val other = mod("other.mod", "Other", workshopId = 5555555L)
        val list = ModList(
            "l", listOf(
                ListEntry("brrainz.harmony", null, null),
                ListEntry("", null, 5555555L), // only the Workshop id is known
                ListEntry("not.installed", "Missing one", 777777L),
                ListEntry("brrainz.harmony", null, null), // duplicate
            ), null, 0,
        )
        val res = ListApplier.resolve(list, listOf(harmony, other))
        assertEquals(listOf("brrainz.harmony", "other.mod"), res.activeIds)
        assertEquals(1, res.missing.size)
        assertEquals("not.installed", res.missing[0].id)
    }

    @Test
    fun profileStoreSavesListsAndRenames() {
        val store = ProfileStore(File(tmpDir(), "profiles"))
        val l = ModList("My list: 1/2", listOf(ListEntry("a.b", null, null)), null, 5L)
        store.save(l)
        assertEquals(l, store.get("My list: 1/2"))
        assertEquals(1, store.all().size)
        assertTrue(store.rename("My list: 1/2", "Renamed"))
        assertNull(store.get("My list: 1/2"))
        assertEquals("Renamed", store.get("Renamed")!!.name)
        assertTrue(store.delete("Renamed"))
        assertEquals(0, store.all().size)
    }

    // ---- scanning disk ------------------------------------------------------------------------------

    private fun about(name: String, id: String, extra: String = "") =
        "<ModMetaData><name>$name</name><packageId>$id</packageId><supportedVersions><li>1.6</li></supportedVersions>$extra</ModMetaData>"

    @Test
    fun scannerFindsOfficialAndWorkshopMods() {
        val game = tmpDir()
        write(File(game, "Version.txt"), "1.6.4630 rev467\n")
        write(File(game, "Data/Core/About/About.xml"), about("Core", "Ludeon.RimWorld"))
        write(File(game, "Data/Royalty/About/About.xml"), about("Royalty", "Ludeon.RimWorld.Royalty"))
        write(File(game, "Mods/2009463077/About/About.xml"), about("Harmony", "brrainz.harmony"))
        write(File(game, "Mods/2009463077/About/PublishedFileId.txt"), "2009463077\n")
        File(game, "Mods/2009463077/About/Preview.png").writeBytes(ByteArray(10) { 1 })
        InstallInfo.write(File(game, "Mods/2009463077"), InstallInfo.Info(2009463077L, "555", null, "Harmony"))
        write(File(game, "Mods/Local Mod/About/about.xml"), about("Local Mod", "me.local")) // lower-case file name
        write(File(game, "Mods/NotAMod/readme.txt"), "hi")
        write(File(game, "Mods/.hidden/About/About.xml"), about("Hidden", "hidden.mod"))
        write(File(game, "Mods/Numeric/About/About.xml"), about("Numeric", "num.mod"))
        write(File(game, "Mods/1234567890/About/About.xml"), about("Folder id", "folder.id")) // id from folder name

        assertTrue(GameLocator.isGameDir(game))
        val version = GameVersion.readFromGameDir(game)!!
        assertEquals("1.6", version.short)

        val res = ModScanner.scan(game, GameLocator.modsDir(game), version)
        val byId = res.mods.associateBy { it.id }
        assertEquals(setOf("ludeon.rimworld", "ludeon.rimworld.royalty", "brrainz.harmony", "me.local", "num.mod", "folder.id"), byId.keys)
        assertEquals(listOf("NotAMod"), res.ignoredFolders)
        assertTrue(res.mods[0].isOfficial && res.mods[1].isOfficial) // official first
        val harmony = byId["brrainz.harmony"]!!
        assertEquals(2009463077L, harmony.workshopId)
        assertEquals("555", harmony.installedManifest)
        assertNotNull(harmony.previewFile)
        assertEquals(1234567890L, byId["folder.id"]!!.workshopId)
        assertNull(byId["me.local"]!!.workshopId)
    }

    @Test
    fun locatorFindsGameAndConfigFolders() {
        val root = tmpDir()
        write(File(root, "Games/RimWorld/Data/Core/About/About.xml"), about("Core", "Ludeon.RimWorld"))
        write(File(root, "Games/RimWorld/Mods/x/readme"), "x")
        write(File(root, "Download/RimWorldData/Config/ModsConfig.xml"), modsConfigSample)
        write(File(root, "Android/data/com.x/Data/Core/About/About.xml"), about("Hidden", "x.y"))
        write(File(root, "DCIM/Camera/a.jpg"), "x")
        val found = GameLocator.discover(listOf(root))
        assertEquals(listOf(File(root, "Games/RimWorld")), found.gameDirs)
        assertEquals(listOf(File(root, "Download/RimWorldData/Config/ModsConfig.xml")), found.configFiles)

        val cfg = File(root, "Download/RimWorldData/Config/ModsConfig.xml")
        assertEquals(cfg, GameLocator.findModsConfig(cfg))
        assertEquals(cfg, GameLocator.findModsConfig(cfg.parentFile))
        assertEquals(cfg, GameLocator.findModsConfig(cfg.parentFile.parentFile))
        assertNull(GameLocator.findModsConfig(File(root, "Games")))
        val fresh = File(root, "FreshData")
        assertEquals(File(fresh, "Config/ModsConfig.xml"), GameLocator.configTarget(fresh))
        assertEquals(File(root, "Download/RimWorldData/Config/ModsConfig.xml"), GameLocator.configTarget(File(root, "Download/RimWorldData")))
    }

    // ---- editing the active list --------------------------------------------------------------------

    @Test
    fun activeListOperations() {
        val l = listOf("a", "b", "c", "d")
        assertEquals(listOf("a", "b", "c", "d", "e"), ActiveListOps.activate(l, " E "))
        assertEquals(l, ActiveListOps.activate(l, "B"))
        assertEquals(listOf("a", "x", "b", "c", "d"), ActiveListOps.activate(l, "x", 1))
        assertEquals(listOf("a", "c", "d"), ActiveListOps.deactivate(l, "B"))
        assertEquals(listOf("d"), ActiveListOps.deactivateAll(l, listOf("A", "b", "c")))
        assertEquals(listOf("c", "a", "b", "d"), ActiveListOps.moveTo(l, "c", 0))
        assertEquals(listOf("a", "b", "d", "c"), ActiveListOps.moveTo(l, "c", 99))
        assertEquals(listOf("b", "a", "c", "d"), ActiveListOps.moveBy(l, "a", 1))
        assertEquals(l, ActiveListOps.moveBy(l, "a", -1))
        assertEquals(listOf("a", "c", "b", "d"), ActiveListOps.moveAfter(l, "b", "c"))
        assertEquals(listOf("a", "d", "b", "c"), ActiveListOps.moveBefore(l, "d", "b"))
        assertEquals(l, ActiveListOps.moveAfter(l, "b", "missing"))
        assertEquals(l, ActiveListOps.moveAfter(l, "b", "b"))
        assertEquals(listOf("a", "b"), ActiveListOps.normalize(listOf(" A", "b ", "a", "", "B")))
    }

    @Test
    fun enablingAModAlsoEnablesWhatItNeeds() {
        val harmony = mod("brrainz.harmony", "Harmony")
        val fw = mod("some.framework", "Framework", deps = listOf("brrainz.harmony"))
        val content = mod("a.content", "Content", deps = listOf("some.framework", "not.installed"))
        val index = ModIndex(listOf(harmony, fw, content))
        val r = ActiveListOps.activateWithDependencies(listOf("ludeon.rimworld"), "a.content", index)
        assertEquals(listOf("ludeon.rimworld", "brrainz.harmony", "some.framework", "a.content"), r.list)
        assertEquals(listOf("brrainz.harmony", "some.framework", "a.content"), r.added)
        // already active dependencies are left alone
        val r2 = ActiveListOps.activateWithDependencies(listOf("brrainz.harmony"), "a.content", index)
        assertEquals(listOf("brrainz.harmony", "some.framework", "a.content"), r2.list)
        // a dependency cycle must not hang
        val x = mod("x.mod", deps = listOf("y.mod"))
        val y = mod("y.mod", deps = listOf("x.mod"))
        val r3 = ActiveListOps.activateWithDependencies(emptyList(), "x.mod", ModIndex(listOf(x, y)))
        assertEquals(setOf("x.mod", "y.mod"), r3.list.toSet())
    }

    // ---- glue between disk, rules and screens ---------------------------------------------------------

    private fun official(id: String, name: String = id) = mod(id, name, source = ModSource.OFFICIAL)

    private val v16 = GameVersion(1, 6)

    @Test
    fun rowsSeparateActiveInactiveMissingAndDuplicates() {
        val core = official("ludeon.rimworld", "Core")
        val harmony = mod("brrainz.harmony", "Harmony", workshopId = 1L, manifest = "1")
        val dupA = mod("dup.mod", "Dup A")
        val dupB = mod("dup.mod", "Dup B")
        val noId = mod("", "Old Mod")
        val mods = listOf(core, harmony, dupA, dupB, noId)
        val active = listOf("ludeon.rimworld", "brrainz.harmony", "gone.mod")
        val issues = Analyzer.analyze(mods, active, noRules, v16)
        val rows = ModelOps.buildRows(mods, active, issues, emptyMap())

        assertEquals(active, rows.active.map { it.id })
        assertEquals(listOf(1, 2, 3), rows.active.map { it.position })
        assertTrue(rows.active[2].missing)
        assertEquals(Severity.WARNING, rows.active[2].severity)
        assertFalse(rows.active[0].canToggle) // Core is always on
        assertTrue(rows.active[1].canToggle)
        assertEquals(listOf("Dup A", "Dup B", "Old Mod"), rows.inactive.map { it.name })
        assertTrue(rows.inactive[0].duplicate)
        assertFalse(rows.inactive[2].canToggle) // no package id
        assertEquals(1, ModelOps.filter(rows.inactive, "dup a").size)
        assertEquals(2, ModelOps.filter(rows.inactive, "DUP").size)
        assertEquals(rows.inactive.size, ModelOps.filter(rows.inactive, "  ").size)
        assertEquals(1, ModelOps.filter(rows.active, "gone").size)
    }

    @Test
    fun sortingKeepsMissingModsAtTheEnd() {
        val core = official("ludeon.rimworld", "Core")
        val harmony = mod("brrainz.harmony", "Harmony")
        val a = mod("a.mod", "A", deps = listOf("brrainz.harmony"))
        val mods = listOf(core, harmony, a)
        val out = ModelOps.sortActive(mods, listOf("a.mod", "gone.mod", "ludeon.rimworld", "brrainz.harmony"), noRules, false)
        assertEquals(listOf("brrainz.harmony", "ludeon.rimworld", "a.mod", "gone.mod"), out.order)
        assertTrue(out.changed)
        val again = ModelOps.sortActive(mods, out.order, noRules, false)
        assertEquals(out.order, again.order)
        assertFalse(again.changed)
        assertEquals(listOf("ludeon.rimworld", "ludeon.rimworld.royalty"),
            ModelOps.initialActive(listOf(official("ludeon.rimworld.royalty"), official("ludeon.rimworld"), a), noRules))
        assertEquals(emptyList<String>(), ModelOps.initialActive(listOf(a), noRules))
    }

    @Test
    fun downloadPlansUseExistingFoldersAndSkipCollections() {
        val installed = mod("a.b", "A", workshopId = 111L, manifest = "1")
        val mods = listOf(installed)
        val modsDir = File("/fake/Mods")
        val upd = item("2", id = 111L).copy(timeUpdated = 50, fileSize = 1000)
        val fresh = item("3", id = 222L).copy(timeUpdated = 60, fileSize = 2000, title = "Fresh")
        val coll = item("4", id = 333L).copy(fileType = 2)
        val gone = item("5", id = 444L, result = 9)
        val plans = ModelOps.plans(listOf(upd, fresh, coll, gone, upd), mods, modsDir)
        assertEquals(listOf(111L, 222L), plans.map { it.workshopId })
        assertEquals(installed.dir, plans[0].installDir)
        assertTrue(plans[0].isUpdate)
        assertEquals(0L, plans[0].sizeHint)
        assertEquals(File(modsDir, "222"), plans[1].installDir)
        assertFalse(plans[1].isUpdate)
        assertEquals(2000L, plans[1].sizeHint)
        assertEquals("3", plans[1].expectedManifest)
        assertEquals(60L, plans[1].timeUpdated)
        assertEquals("Fresh", plans[1].title)
    }

    @Test
    fun updatePlansAndSummary() {
        val m1 = mod("m.one", "One", workshopId = 1L, manifest = "1")
        val m2 = mod("m.two", "Two", workshopId = 2L)
        val m3 = mod("m.three", "Three", workshopId = 3L, manifest = "3")
        val m4 = mod("m.four", "Four", workshopId = 4L, manifest = "4")
        val mods = listOf(m1, m2, m3, m4)
        val updates = mapOf(
            m1.key to UpdateInfo(UpdateState.UPDATE_AVAILABLE, item("2", id = 1L)),
            m2.key to UpdateInfo(UpdateState.UNTRACKED, item("9", id = 2L)),
            m3.key to UpdateInfo(UpdateState.UP_TO_DATE, item("3", id = 3L)),
            m4.key to UpdateInfo(UpdateState.UNAVAILABLE, null),
        )
        val dir = File("/fake/Mods")
        assertEquals(listOf(1L), ModelOps.updatePlans(mods, updates, dir, false).map { it.workshopId })
        assertEquals(listOf(1L, 2L), ModelOps.updatePlans(mods, updates, dir, true).map { it.workshopId })
        val s = ModelOps.summarize(updates)
        assertEquals(1, s.available)
        assertEquals(1, s.untracked)
        assertEquals(1, s.unavailable)
        assertEquals(1, s.upToDate)
        val issues = listOf(
            Issue(Severity.ERROR, IssueType.MISSING_DEPENDENCY, "a", "A", "x", workshopIds = listOf(9L, 8L), fix = Fix.DOWNLOAD),
            Issue(Severity.WARNING, IssueType.MISSING_MOD, "b", "B", "x", workshopIds = listOf(8L), fix = Fix.DOWNLOAD),
            Issue(Severity.WARNING, IssueType.WRONG_ORDER, "c", "C", "x", fix = Fix.SORT),
        )
        assertEquals(listOf(9L, 8L), ModelOps.downloadableIds(issues))
    }

    @Test
    fun oneTapFixesEditTheList() {
        val core = official("ludeon.rimworld", "Core")
        val harmony = mod("brrainz.harmony", "Harmony")
        val a = mod("a.mod", "A", deps = listOf("brrainz.harmony"))
        val b = mod("b.mod", "B", incompatible = listOf("a.mod"))
        val mods = listOf(core, harmony, a, b)
        val active = listOf("ludeon.rimworld", "a.mod")
        val issues = Analyzer.analyze(mods, active, noRules, v16)
        val dep = issues.first { it.type == IssueType.DEPENDENCY_INACTIVE }
        val fixed = ModelOps.applyFix(dep, active, mods, noRules, false)!!
        assertEquals(listOf("ludeon.rimworld", "a.mod", "brrainz.harmony"), fixed.active)

        val safe = ModelOps.applySafeFixes(issues, active, mods, noRules, false)
        assertEquals(listOf("brrainz.harmony", "ludeon.rimworld", "a.mod"), safe.active)
        assertTrue(Analyzer.analyze(mods, safe.active, noRules, v16).none { it.severity == Severity.ERROR })
        val nothing = ModelOps.applySafeFixes(Analyzer.analyze(mods, safe.active, noRules, v16), safe.active, mods, noRules, false)
        assertEquals(safe.active, nothing.active)
        assertEquals("Nothing to fix automatically", nothing.note)

        val both = listOf("ludeon.rimworld", "brrainz.harmony", "a.mod", "b.mod")
        val inc = Analyzer.analyze(mods, both, noRules, v16).first { it.type == IssueType.INCOMPATIBLE }
        assertEquals(
            listOf("ludeon.rimworld", "brrainz.harmony", "a.mod"),
            ModelOps.applyFix(inc, both, mods, noRules, false, deactivateId = "b.mod")!!.active,
        )
        val missing = Issue(Severity.WARNING, IssueType.MISSING_MOD, "gone.mod", "Gone", "x", fix = Fix.REMOVE_MISSING)
        assertEquals(listOf("a.mod"), ModelOps.applyFix(missing, listOf("gone.mod", "a.mod"), mods, noRules, false)!!.active)
        val dl = Issue(Severity.ERROR, IssueType.MISSING_DEPENDENCY, "a", "A", "x", fix = Fix.DOWNLOAD)
        assertNull(ModelOps.applyFix(dl, both, mods, noRules, false))
    }

    @Test
    fun libraryLoadsAndSavesTheLoadOrder() {
        val game = tmpDir()
        write(File(game, "Version.txt"), "1.6.4630 rev467\n")
        write(File(game, "Data/Core/About/About.xml"), about("Core", "Ludeon.RimWorld"))
        write(File(game, "Data/Royalty/About/About.xml"), about("Royalty", "Ludeon.RimWorld.Royalty"))
        write(File(game, "Mods/111111/About/About.xml"), about("One", "a.one"))
        write(File(game, "Mods/222222/About/About.xml"), about("Two", "a.two"))
        val cfgFile = File(File(tmpDir(), "Config"), "ModsConfig.xml")
        val config = AppConfig(gameDir = game.absolutePath, configPath = cfgFile.absolutePath)
        assertEquals(File(game, "Mods"), config.modsDirFile)

        val first = Library.load(config, noRules)
        assertFalse(first.configRead)
        assertEquals(listOf("ludeon.rimworld", "ludeon.rimworld.royalty"), first.active)
        assertEquals("1.6", first.game!!.short)
        assertEquals(4, first.mods.size)

        Library.save(config, first.game, first.mods, listOf("ludeon.rimworld", "a.two", "A.One"))
        val data = ModsConfigXml.read(cfgFile)!!
        assertEquals("1.6.4630 rev467", data.version)
        assertEquals(listOf("ludeon.rimworld.royalty"), data.knownExpansions)
        val second = Library.load(config, noRules)
        assertTrue(second.configRead)
        assertEquals(listOf("ludeon.rimworld", "a.two", "a.one"), second.active)
        assertEquals(second.active, Library.readActive(config))

        Library.save(config, second.game, second.mods, second.active.reversed())
        assertEquals(1, cfgFile.parentFile.listFiles { f -> f.name.contains(".bak-") }!!.size)
        assertEquals(listOf("a.one", "a.two", "ludeon.rimworld"), Library.readActive(config))

        val noConfig = AppConfig(gameDir = game.absolutePath)
        assertNull(Library.readActive(noConfig))
        try {
            Library.save(noConfig, first.game, first.mods, first.active)
            throw AssertionError("expected an IOException")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("ModsConfig.xml"))
        }
        // only a Mods folder, no game folder: still works
        val modsOnly = AppConfig(modsDir = File(game, "Mods").absolutePath)
        assertEquals(setOf("a.one", "a.two"), Library.load(modsOnly, noRules).mods.map { it.id }.toSet())
    }

    @Test
    fun rulesStoreCachesAndValidates() {
        val dir = tmpDir()
        val file = File(dir, "rules.json")
        val store = RulesStore(file) { _ -> rulesSample }
        assertNull(store.cached())
        assertEquals(0L, store.cachedAt())
        val rules = store.download()
        assertTrue(rules.ruleCount > 0)
        assertEquals(rules.ruleCount, store.cached()!!.ruleCount)
        assertTrue(store.cachedAt() > 0L)

        val broken = RulesStore(file) { _ -> "this is not json" }
        try {
            broken.download()
            throw AssertionError("expected an IOException")
        } catch (e: java.io.IOException) {
            // expected
        }
        val empty = RulesStore(file) { _ -> """{"timestamp":1,"rules":{}}""" }
        try {
            empty.download()
            throw AssertionError("expected an IOException")
        } catch (e: java.io.IOException) {
            // expected
        }
        assertEquals(rules.ruleCount, store.cached()!!.ruleCount) // the good cache survived

        val offline = RulesStore(File(dir, "other.json")) { _ -> throw java.io.IOException("offline") }
        try {
            offline.download()
            throw AssertionError("expected an IOException")
        } catch (e: java.io.IOException) {
            assertEquals("offline", e.message)
        }
        assertNull(offline.cached())
    }

    // ---- small utilities ----------------------------------------------------------------------------

    @Test
    fun xmlEscapeAndUnescapeAreInverse() {
        val s = "a & b < c > \"d\" 'e' é"
        assertEquals(s, XmlUtil.unescape(XmlUtil.escape(s)))
        assertEquals("A", XmlUtil.unescape("&#65;"))
        assertEquals("A", XmlUtil.unescape("&#x41;"))
        assertEquals("&unknown;", XmlUtil.unescape("&unknown;"))
    }

    @Test
    fun fileUtilBasics() {
        assertEquals("a_b_c", FileUtil.safeFileName("a/b:c"))
        assertEquals("list", FileUtil.safeFileName("   "))
        assertEquals("list", FileUtil.safeFileName(".."))
        assertEquals("512 B", FileUtil.humanSize(512))
        assertEquals("1.5 MB", FileUtil.humanSize(1536L * 1024))
        val dir = tmpDir()
        write(File(dir, "x/y.txt"), "12345")
        assertEquals(5L, FileUtil.dirSize(dir))
        assertTrue(FileUtil.deleteRecursive(dir))
        assertFalse(dir.exists())
        val f = File(tmpDir(), "a.txt")
        FileUtil.writeTextAtomic(f, "one")
        FileUtil.writeTextAtomic(f, "two")
        assertEquals("two", f.readText())
        assertFalse(File(f.parentFile, "a.txt.tmp").exists())
    }

    @Test
    fun gameVersionParsing() {
        val v = GameVersion.parse("1.6.4630 rev467")!!
        assertEquals("1.6", v.short)
        assertEquals("v1.6", v.xmlKey)
        assertEquals(4630, v.build)
        assertEquals(GameVersion(1, 5, 0, "v1.5").short, GameVersion.parse("v1.5")!!.short)
        assertNull(GameVersion.parse("nope"))
        assertNull(GameVersion.parse(null))
    }
}
