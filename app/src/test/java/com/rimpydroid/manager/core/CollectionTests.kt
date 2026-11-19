package com.rimpydroid.manager.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Downloaded collections, the Classic default style and the limit of mods downloaded at the same time. Pure JVM. */
class CollectionTests {

    private fun tmpDir(): File = Files.createTempDirectory("rimpydroid-collections").toFile()

    private fun mod(id: String, workshopId: Long?): ModEntry = ModEntry(
        dir = File("/fake/" + id),
        meta = ModMeta(
            name = id, authors = "", packageId = id, description = "", url = "", modVersion = "",
            supportedVersions = listOf("1.6"), dependencies = emptyList(),
            loadBefore = emptyList(), loadAfter = emptyList(), incompatibleWith = emptyList(),
        ),
        source = ModSource.LOCAL,
        workshopId = workshopId,
        installedManifest = null,
        lastModified = 1000L,
        previewFile = null,
    )

    private fun record(id: Long, vararg members: Long, updatedAt: Long = 10L) = CollectionRecord(
        id = id,
        title = "Pack $id",
        members = members.map { CollectionMember(it, "Mod $it") },
        addedAt = 1L,
        updatedAt = updatedAt,
    )

    @Test
    fun aRecordSurvivesTheJsonRoundTrip() {
        val r = CollectionRecord(
            id = 123456789L,
            title = "My \"big\" pack",
            members = listOf(CollectionMember(111111111L, "Harmony"), CollectionMember(222222222L, "")),
            addedAt = 5L,
            updatedAt = 9L,
        )
        assertEquals(r, CollectionCodec.fromJson(CollectionCodec.toJson(r)))
        assertNull(CollectionCodec.fromJson("not json"))
        assertNull(CollectionCodec.fromJson("""{"id":"0","mods":[]}"""))
        // the same mod twice in a file is one mod
        val twice = CollectionCodec.fromJson("""{"id":"5","title":"x","mods":[{"id":"7"},{"id":"7"},{"id":"8"}]}""")!!
        assertEquals(listOf(7L, 8L), twice.memberIds)
    }

    @Test
    fun theStoreKeepsOneRecordPerCollectionEvenWhenAFileIsCopied() {
        val dir = File(tmpDir(), "collections")
        val store = CollectionStore(dir)
        store.save(record(100L, 1L, 2L, updatedAt = 5L))
        store.save(record(200L, 3L, updatedAt = 8L))
        File(dir, "100.json").copyTo(File(dir, "100 (copy).json"))
        val all = store.all()
        assertEquals(listOf(200L, 100L), all.map { it.id }) // the most recently updated first, each id once
        assertEquals(listOf(1L, 2L), store.get(100L)?.memberIds)
        assertTrue(store.delete(100L))
        assertNull(store.get(100L))
        assertTrue(store.delete(100L)) // nothing left to delete is fine
    }

    @Test
    fun theStatusCountsInstalledEnabledAndUpdatedMods() {
        val r = record(1L, 10L, 20L, 30L, 40L)
        val installed = mapOf(10L to mod("a.one", 10L), 20L to mod("b.two", 20L), 30L to mod("c.three", 30L))
        val s = CollectionOps.status(r, installed, setOf("a.one", "c.three", "x.other")) { it.workshopId == 30L }
        assertEquals(CollectionStatus(total = 4, installed = 3, active = 2, updates = 1), s)
        assertEquals(1, s.missing)
    }

    @Test
    fun onlyTheModsNoOtherCollectionHasGoWithIt() {
        val a = record(1L, 10L, 20L, 30L)
        val b = record(2L, 30L, 40L)
        assertEquals(listOf(10L, 20L), CollectionOps.exclusiveMembers(a, listOf(a, b)))
        assertEquals(listOf(40L), CollectionOps.exclusiveMembers(b, listOf(a, b)))
        assertEquals(listOf(10L, 20L, 30L), CollectionOps.exclusiveMembers(a, listOf(a)))
    }

    @Test
    fun aRefreshShowsWhatWasAddedAndWhatWasRemoved() {
        val old = record(1L, 10L, 20L)
        val fresh = listOf(CollectionMember(20L, "Mod 20"), CollectionMember(30L, "New one"))
        val d = CollectionOps.diff(old, fresh)
        assertEquals(listOf(30L), d.added.map { it.id })
        assertEquals(listOf(10L), d.removed.map { it.id })
        assertTrue(CollectionOps.diff(old, old.members).isEmpty)
    }

    @Test
    fun aCollectionCanBeAppliedAsAListByWorkshopId() {
        val r = record(1L, 10L, 20L, 30L)
        val mods = listOf(mod("a.one", 10L), mod("c.three", 30L))
        val result = ListApplier.resolve(CollectionOps.asList(r), mods)
        assertEquals(listOf("a.one", "c.three"), result.activeIds)
        assertEquals(1, result.missing.size)
        assertEquals(20L, result.missing[0].workshopId)
    }

    @Test
    fun theClassicStyleIsTheDefaultAndLiquidGlassIsAnOption() {
        assertEquals(AppConfig.STYLE_CLASSIC, AppConfig().uiStyle)
        assertFalse(AppConfig().glass)
        assertTrue(AppConfig(uiStyle = AppConfig.STYLE_GLASS).glass)
    }

    @Test
    fun upToEightModsCanBeDownloadedAtTheSameTime() {
        assertEquals(8, AppConfig.MAX_PARALLEL_MODS)
        assertTrue(AppConfig.DEFAULT_PARALLEL_MODS in AppConfig.MIN_PARALLEL_MODS..AppConfig.MAX_PARALLEL_MODS)
    }

    // ---- Harmony is never deleted with a collection ----------------------------------------------------

    private val harmonyWorkshopId = 2009463077L

    @Test
    fun harmonyStaysWhenACollectionIsDeletedWithItsMods() {
        val harmony = mod("Brrainz.Harmony", harmonyWorkshopId)
        val other = mod("some.mod", 11L)
        val pack = record(1, harmonyWorkshopId, 11L)
        val plan = CollectionOps.planDeletion(pack, listOf(pack), mapOf(harmonyWorkshopId to harmony, 11L to other))
        assertEquals(listOf("some.mod"), plan.delete.map { it.id })
        assertEquals(listOf("brrainz.harmony"), plan.kept.map { it.id })
    }

    @Test
    fun harmonyIsRecognisedByItsPackageIdWhateverTheWorkshopId() {
        // a copy that was made by hand, or a Workshop id that is not the usual one
        assertTrue(CollectionOps.isProtected(mod("brrainz.harmony", null)))
        assertTrue(CollectionOps.isProtected(mod("brrainz.harmony", 5L)))
    }

    @Test
    fun harmonyIsRecognisedByItsWorkshopIdWhenItsAboutXmlHasNoPackageId() {
        assertTrue(CollectionOps.isProtected(mod("", harmonyWorkshopId)))
    }

    @Test
    fun otherModsAreNotProtected() {
        assertFalse(CollectionOps.isProtected(mod("some.mod", 11L)))
        assertFalse(CollectionOps.isProtected(mod("brrainz.harmony.extras", 12L)))
        assertFalse(CollectionOps.isProtected(mod("fluffy.breakdowns", null)))
    }

    @Test
    fun aCollectionWithOnlyHarmonyDeletesNothing() {
        val harmony = mod("brrainz.harmony", harmonyWorkshopId)
        val pack = record(1, harmonyWorkshopId)
        val plan = CollectionOps.planDeletion(pack, listOf(pack), mapOf(harmonyWorkshopId to harmony))
        assertTrue(plan.delete.isEmpty())
        assertEquals(1, plan.kept.size)
    }

    @Test
    fun aModThatAnotherCollectionHasStaysAsBefore() {
        val shared = mod("shared.mod", 21L)
        val mine = mod("mine.mod", 22L)
        val a = record(1, 21L, 22L)
        val b = record(2, 21L)
        val plan = CollectionOps.planDeletion(a, listOf(a, b), mapOf(21L to shared, 22L to mine))
        assertEquals(listOf("mine.mod"), plan.delete.map { it.id })
        assertTrue(plan.kept.isEmpty())
    }

    @Test
    fun anUninstalledMemberIsNotPlanned() {
        val pack = record(1, harmonyWorkshopId, 11L)
        val plan = CollectionOps.planDeletion(pack, listOf(pack), emptyMap())
        assertTrue(plan.delete.isEmpty())
        assertTrue(plan.kept.isEmpty())
    }
}
