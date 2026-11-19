package com.rimpydroid.manager.core

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Text that a mod author or another app controls must not hang the app or slip past a guard. The inputs here are the
 * ones the security review (run-1) found slow or bypassing; each must now finish at once.
 */
class HostileInputTests {

    private val dirs = ArrayList<File>()

    @After
    fun cleanUp() {
        dirs.forEach { it.deleteRecursively() }
    }

    private fun temp(): File = Files.createTempDirectory("rimpy-hostile").toFile().also { dirs.add(it) }

    /** Runs [block] and fails when it takes more than [limitMs]; a quadratic version needs minutes here. */
    private fun fast(limitMs: Long = 3000, block: () -> Unit) {
        val start = System.nanoTime()
        block()
        val took = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $took ms", took < limitMs)
    }

    // ---- entity guard ---------------------------------------------------------------------------

    @Test
    fun anEntityDefinitionHiddenBehindAControlCharacterIsStillRefused() {
        val xml = "<?xml version=\"1.0\"?><!DOCTYPE ModMetaData [<!ENT\u0001ITY a \"aaaa\">]><ModMetaData>&a;</ModMetaData>"
        var refused = false
        try {
            XmlUtil.parse(xml)
        } catch (e: IllegalArgumentException) {
            refused = true
        }
        assertTrue(refused)
    }

    @Test
    fun anEntityDefinitionAnywhereMakesAboutParserUseItsTolerantReader() {
        val xml = "<?xml version=\"1.0\"?><!DOCTYPE m [<!ENT\u0001ITY a \"x\">]><ModMetaData><name>Plain</name><packageId>a.b</packageId></ModMetaData>"
        val meta = AboutParser.parseText(xml, GameVersion(1, 6), "fallback")
        assertEquals("Plain", meta.name)
        assertEquals("a.b", meta.packageId)
        assertNotNull(meta.warning)
    }

    // ---- no quadratic matching ------------------------------------------------------------------

    @Test
    fun manyUnclosedListItemsInAMalformedAboutXmlAreReadQuickly() {
        val xml = "<ModMetaData><name>Slow</name><supportedVersions>" + "<li>".repeat(200_000) + "</supportedVersions>"
        fast {
            val meta = AboutParser.parseText(xml, GameVersion(1, 6), "fallback")
            assertEquals("Slow", meta.name)
        }
    }

    @Test
    fun manyUnclosedDependencyBlocksAreReadQuickly() {
        val xml = "<ModMetaData><name>Deps</name>" + "<modDependencies>".repeat(100_000)
        fast { assertEquals("Deps", AboutParser.parseText(xml, GameVersion(1, 6), "fallback").name) }
        val closed = "<ModMetaData><name>Deps</name>" + "<modDependencies><li>x</li></modDependencies>".repeat(50_000) + "</ModMetaData>"
        fast { assertEquals("Deps", AboutParser.parseText(closed + "<", GameVersion(1, 6), "fallback").name) }
    }

    @Test
    fun dependencyBlocksAreStillCutOutOfTheTolerantRead() {
        val xml = "<ModMetaData><name>Real</name><packageId>real.mod</packageId>" +
            "<modDependencies><li><packageId>dep.one</packageId><displayName>Dep</displayName></li></modDependencies>" +
            "<modDependenciesByVersion><v1.5><li><packageId>dep.two</packageId></li></v1.5></modDependenciesByVersion>" +
            "<description>a & b</description></ModMetaData>"
        val meta = AboutParser.parseText(xml, GameVersion(1, 6), "fallback")
        assertEquals("real.mod", meta.packageId)
        assertEquals("Real", meta.name)
        assertEquals("a & b", meta.description)
    }

    @Test
    fun tagBlockAndLiBlocksMatchWhatTheRegexesReturned() {
        assertEquals("x", XmlUtil.tagBlock("<a>x</a><a>y</a>", "a"))
        assertEquals(null, XmlUtil.tagBlock("<a>x", "a"))
        assertEquals(null, XmlUtil.tagBlock("</a><b></b>", "a"))
        assertEquals("1\n2", XmlUtil.tagBlock("<a>1\n2</a>", "a"))
        assertEquals(listOf("a", "", "b<li>c"), XmlUtil.liBlocks("<li>a</li> <li></li> <li>b<li>c</li> <li>tail"))
        assertEquals(emptyList<String>(), XmlUtil.liBlocks("<li>never closed"))
    }

    @Test
    fun aDescriptionWithManyOpenBracketsIsCleanedQuickly() {
        fast { WorkshopUtil.plainText("[b=".repeat(300_000)) }
        fast { WorkshopUtil.plainText("[url=" + "x".repeat(1_000_000)) }
        assertEquals("bold link", WorkshopUtil.plainText("[b]bold[/b] [url=https://example.org]link[/url]"))
    }

    @Test
    fun theTolerantModsConfigAndListReadersAreQuickToo() {
        fast {
            val cfg = "<ModsConfigData><activeMods>" + "<li>".repeat(200_000) + "</activeMods>"
            // not well-formed on purpose
            assertTrue(ModsConfigXml.parse(cfg)!!.activeMods.isEmpty())
        }
        fast { ListImporter.parse("<modIds>" + "<li>".repeat(200_000), "n") }
    }

    // ---- the scan survives and the copy-back is bounded -----------------------------------------

    @Test
    fun aModWithAHostileAboutXmlDoesNotStopTheScan() {
        val mods = temp()
        File(mods, "good/About").mkdirs()
        File(mods, "good/About/About.xml").writeText("<ModMetaData><name>Good</name><packageId>good.mod</packageId></ModMetaData>")
        File(mods, "bad/About").mkdirs()
        File(mods, "bad/About/About.xml").writeText("<ModMetaData><name>Bad</name><supportedVersions>" + "<li>".repeat(300_000))
        fast(10_000) {
            val scan = ModScanner.scan(null, mods, null)
            assertTrue(scan.mods.any { it.id == "good.mod" })
            assertTrue(scan.mods.any { it.meta.name == "Bad" })
        }
    }

    @Test
    fun onlyABoundedNumberOfListsIsCopiedBack() {
        val shared = temp()
        val local = File(temp(), "lists")
        for (i in 0 until 130) File(shared, "list-%03d.json".format(i)).writeText("{}")
        assertEquals(100, StorageLayout.restoreLists(local, shared))
        assertEquals(100, local.listFiles()!!.size)
        // what is there is never replaced and the rest still fits under the cap on a second run
        assertEquals(30, StorageLayout.restoreLists(local, shared))
    }

    @Test
    fun theCopyBackStopsAtTheTotalSize() {
        val shared = temp()
        val local = File(temp(), "lists")
        val chunk = "x".repeat(7 * 1024 * 1024)
        for (i in 0 until 4) File(shared, "big-$i.json").writeText(chunk)
        assertEquals(2, StorageLayout.restoreLists(local, shared)) // 14 MB fits, the third would make 21 MB
        assertFalse(File(local, "big-2.json").exists())
    }
}
