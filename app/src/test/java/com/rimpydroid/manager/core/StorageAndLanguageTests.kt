package com.rimpydroid.manager.core

import com.rimpydroid.manager.ui.Translations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The RimpyDroid folder, the copy of the lists that survives removing the app, the new defaults and the translation table. */
class StorageAndLanguageTests {

    private fun tmpDir(): File = Files.createTempDirectory("rimpydroid-test").toFile()

    @Test
    fun creatingTheFolderMakesModsConfigAndLists() {
        val root = File(tmpDir(), "RimpyDroid")
        assertNull(StorageLayout.create(root))
        assertTrue(StorageLayout.modsDir(root).isDirectory)
        assertTrue(StorageLayout.configDir(root).isDirectory)
        assertTrue(StorageLayout.listsDir(root).isDirectory)
        assertEquals("ModsConfig.xml", StorageLayout.configFile(root).name)
    }

    @Test
    fun listsComeBackAfterAReinstallAndNothingIsOverwritten() {
        val base = tmpDir()
        val shared = File(base, "shared").apply { mkdirs() }
        val local = File(base, "local") // a fresh installation: nothing here yet
        File(shared, "A.json").writeText("{\"a\":1}")
        File(shared, "B.json").writeText("{\"b\":1}")
        assertEquals(2, StorageLayout.restoreLists(local, shared))
        File(local, "A.json").writeText("{\"a\":2}")
        assertEquals(0, StorageLayout.restoreLists(local, shared))
        assertEquals("{\"a\":2}", File(local, "A.json").readText())
    }

    @Test
    fun mirroringCopiesChangesAndRemovesOnlyDeletedLists() {
        val base = tmpDir()
        val local = File(base, "local").apply { mkdirs() }
        val shared = File(base, "shared")
        File(local, "A.json").writeText("1")
        File(local, "B.json").writeText("1")
        assertTrue(StorageLayout.mirrorLists(local, shared))
        assertEquals("1", File(shared, "A.json").readText())
        File(local, "A.json").writeText("2")
        File(local, "B.json").delete()
        File(shared, "Keep.json").writeText("x") // not known to the app: it stays
        assertTrue(StorageLayout.mirrorLists(local, shared, listOf("B.json")))
        assertEquals("2", File(shared, "A.json").readText())
        assertFalse(File(shared, "B.json").exists())
        assertTrue(File(shared, "Keep.json").exists())
    }

    @Test
    fun theFolderChoiceIsRecognisedFromThePaths() {
        val root = File("/storage/emulated/0/Download/RimpyDroid")
        val rimpy = AppConfig(
            dataDir = root.path,
            modsDir = StorageLayout.modsDir(root).path,
            configPath = StorageLayout.configFile(root).path,
        )
        assertEquals(AppConfig.STORAGE_RIMPY, rimpy.storageKind)
        assertEquals(AppConfig.STORAGE_RIMWORLD, AppConfig(dataDir = root.path, gameDir = "/x/RimWorld").storageKind)
        assertEquals(AppConfig.STORAGE_NONE, AppConfig(dataDir = root.path).storageKind)
        assertEquals(AppConfig.STORAGE_CUSTOM, AppConfig(dataDir = root.path, modsDir = "/somewhere/Mods").storageKind)
    }

    @Test
    fun anAssumedVersionHasNoBuildNumberSoNothingIsWrittenForIt() {
        val game = AppConfig(gameVersion = "1.6").assumedGame()
        assertNotNull(game)
        assertEquals("1.6", game!!.short)
        assertEquals("", game.raw)
        assertNull(AppConfig().assumedGame())
    }

    @Test
    fun theDefaultsAre16ConnectionsAnd5ModsAtOnce() {
        assertEquals(16, AppConfig().maxDownloads)
        assertEquals(5, AppConfig().parallelMods)
    }

    @Test
    fun everyTranslationRowFitsTheLanguagesAndKeepsItsPlaceholders() {
        val columns = Translations.CODES.size + 1
        val marks = Regex("""\{\d\}""")
        val seen = HashSet<String>()
        for (row in Translations.ROWS) {
            val parts = row.split(" | ")
            assertEquals("columns of: $row", columns, parts.size)
            assertTrue("duplicate row: " + parts[0], seen.add(parts[0]))
            val expected = marks.findAll(parts[0]).map { it.value }.toSet()
            for (p in parts) {
                assertTrue("empty text in: " + parts[0], p.isNotBlank())
                assertEquals("placeholders of: " + parts[0], expected, marks.findAll(p).map { it.value }.toSet())
            }
        }
        val tables = Translations.build()
        assertEquals(Translations.CODES.size, tables.size)
        for (code in Translations.CODES) assertEquals(Translations.ROWS.size, tables.getValue(code).size)
    }
}
