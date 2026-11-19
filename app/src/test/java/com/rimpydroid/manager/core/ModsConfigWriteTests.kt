package com.rimpydroid.manager.core

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RimWorld calls a ModsConfig.xml without Core ("ludeon.rimworld") invalid. In the RimpyDroid folder the app cannot see
 * the game, so it used to write the list without Core; these tests keep the file valid.
 */
class ModsConfigWriteTests {

    private val dirs = ArrayList<File>()

    @After
    fun cleanUp() {
        dirs.forEach { it.deleteRecursively() }
    }

    private fun temp(): File = Files.createTempDirectory("rimpy-cfg").toFile().also { dirs.add(it) }

    private fun mod(id: String, before: List<String> = emptyList()) = ModEntry(
        dir = File("/fake/$id"),
        meta = ModMeta(
            name = id, authors = "", packageId = id, description = "", url = "", modVersion = "",
            supportedVersions = listOf("1.6"), dependencies = emptyList(),
            loadBefore = before, loadAfter = emptyList(), incompatibleWith = emptyList(),
        ),
        source = ModSource.LOCAL, workshopId = null, installedManifest = null, lastModified = 1L, previewFile = null,
    )

    private fun rimpyConfig(): Pair<AppConfig, File> {
        val root = temp()
        val cfgFile = File(File(root, "Config"), "ModsConfig.xml")
        val config = AppConfig(
            dataDir = root.absolutePath,
            modsDir = StorageLayout.modsDir(root).absolutePath,
            configPath = cfgFile.absolutePath,
        )
        assertTrue(config.ignoresOfficial)
        return config to cfgFile
    }

    @Test
    fun withCoreAddsCoreOnlyWhenItIsMissing() {
        assertEquals(listOf("ludeon.rimworld"), ActiveListOps.withCore(emptyList()))
        assertEquals(listOf("ludeon.rimworld", "a.mod"), ActiveListOps.withCore(listOf("a.mod")))
        // present already: nothing moves, not even when something sits in front of it (Harmony does)
        assertEquals(
            listOf("brrainz.harmony", "ludeon.rimworld", "a.mod"),
            ActiveListOps.withCore(listOf("brrainz.harmony", "Ludeon.RimWorld", "a.mod")),
        )
        // a mod that loads before Core keeps its place in front of it
        assertEquals(
            listOf("brrainz.harmony", "ludeon.rimworld", "a.mod"),
            ActiveListOps.withCore(listOf("brrainz.harmony", "a.mod")) { it == "brrainz.harmony" },
        )
    }

    @Test
    fun theRimpyDroidFolderWritesCoreEvenThoughItCannotSeeTheGame() {
        val (config, cfgFile) = rimpyConfig()
        val mods = listOf(mod("brrainz.harmony", before = listOf("ludeon.rimworld")), mod("a.mod"), mod("b.mod"))
        Library.save(config, null, mods, listOf("brrainz.harmony", "a.mod", "b.mod"))
        val data = ModsConfigXml.read(cfgFile)!!
        assertEquals(listOf("brrainz.harmony", "ludeon.rimworld", "a.mod", "b.mod"), data.activeMods)
        // and the file is well-formed XML for the game's strict reader
        XmlUtil.parse(cfgFile.readText())
    }

    @Test
    fun anEmptyLoadOrderIsStillAValidFileWithCore() {
        val (config, cfgFile) = rimpyConfig()
        Library.save(config, null, emptyList(), emptyList())
        assertEquals(listOf("ludeon.rimworld"), ModsConfigXml.read(cfgFile)!!.activeMods)
    }

    @Test
    fun aListThatHasCoreAndTheDlcIsWrittenAsItIs() {
        val (config, cfgFile) = rimpyConfig()
        val list = listOf("ludeon.rimworld", "ludeon.rimworld.royalty", "ludeon.rimworld.biotech", "a.mod")
        Library.save(config, null, listOf(mod("a.mod")), list)
        assertEquals(list, ModsConfigXml.read(cfgFile)!!.activeMods)
    }

    @Test
    fun theFileThatRimWorldRejectedIsRepairedOnTheNextSave() {
        // the shape of the file from the phone: valid XML, 31 mods, five known expansions, no Core and no DLC
        val ids = listOf(
            "brrainz.harmony", "prospero1088.convergence.core", "prospero1088.convergence.formations", "defi.blueprints.fork",
            "buildable.terrain", "telardo.dragselect", "vr.missilegirl",
        )
        val (config, cfgFile) = rimpyConfig()
        cfgFile.parentFile.mkdirs()
        val broken = ModsConfigXml.render(
            "1.6.4871 rev590", ids,
            listOf("ludeon.rimworld.anomaly", "ludeon.rimworld.biotech", "ludeon.rimworld.ideology", "ludeon.rimworld.odyssey", "ludeon.rimworld.royalty"),
        )
        cfgFile.writeText(broken)
        assertFalse(ModsConfigXml.read(cfgFile)!!.activeMods.contains("ludeon.rimworld"))

        val mods = ids.map { mod(it, before = if (it == "brrainz.harmony") listOf("ludeon.rimworld") else emptyList()) }
        val loaded = Library.load(config, CommunityRules.parse("{}"))
        Library.save(config, loaded.game, mods, loaded.active)

        val fixed = ModsConfigXml.read(cfgFile)!!
        assertEquals(listOf("brrainz.harmony", "ludeon.rimworld") + ids.drop(1), fixed.activeMods)
        assertEquals("1.6.4871 rev590", fixed.version)          // the game's own version text is kept
        assertEquals(5, fixed.knownExpansions.size)            // and so are the expansions it already knew
    }
}
