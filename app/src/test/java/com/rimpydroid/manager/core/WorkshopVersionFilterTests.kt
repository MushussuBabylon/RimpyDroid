package com.rimpydroid.manager.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The version filter of the Workshop search. Choosing "1.7" must never list a mod that is only tagged "1.6" or "1.4",
 * even when Steam ignores the `requiredtags` it was asked for. These tests stand in for Steam through the hooks of
 * [WorkshopApi]: the fake Steam below deliberately does NOT filter anything itself.
 */
class WorkshopVersionFilterTests {

    private val realPost = WorkshopApi.postForm
    private val realGet = WorkshopApi.getText

    @After
    fun restoreTheNetwork() {
        WorkshopApi.postForm = realPost
        WorkshopApi.getText = realGet
    }

    /** id -> the tags the details API reports for it. */
    private val tagsOf = LinkedHashMap<Long, List<String>>()

    /** The browse URLs that were asked for, in order. */
    private val browseUrls = ArrayList<String>()

    private fun mod(id: Long, vararg versions: String) {
        tagsOf[id] = listOf("Mod") + versions
    }

    private fun detailsJson(id: Long): String {
        val tags = tagsOf[id]?.joinToString(",") { """{"tag":"$it"}""" } ?: ""
        return """{"publishedfileid":"$id","result":1,"consumer_app_id":294100,"title":"Mod $id","file_size":"500",""" +
            """"hcontent_file":"9000$id","time_updated":1700000000,"tags":[$tags]}"""
    }

    /**
     * Steam answers every browse request with the next [perPage] ids of [order] (so it ignores the version tag) and
     * says how many entries there are in all.
     */
    private fun fakeSteam(order: List<Long>, perPage: Int = 30) {
        WorkshopApi.postForm = { _, fields ->
            val ids = fields.filter { it.first.startsWith("publishedfileids[") }.map { it.second.toLong() }
            val details = ids.joinToString(",") { detailsJson(it) }
            """{"response":{"result":1,"resultcount":${ids.size},"publishedfiledetails":[$details]}}"""
        }
        WorkshopApi.getText = { url ->
            browseUrls.add(url)
            val p = Regex("""[?&]p=(\d+)""").find(url)!!.groupValues[1].toInt()
            val from = (p - 1) * perPage
            val slice = order.drop(from).take(perPage)
            val divs = slice.joinToString("") { """<div data-publishedfileid="$it"></div>""" }
            val last = from + slice.size
            if (slice.isEmpty()) "<html></html>" else "$divs Showing ${from + 1}-$last of ${order.size} entries"
        }
    }

    @Test
    fun onlyModsTaggedForTheChosenVersionAreReturned() {
        mod(1_000_001, "1.7")
        mod(1_000_002, "1.6")
        mod(1_000_003, "1.4", "1.5")
        mod(1_000_004, "1.6", "1.7") // supports 1.7 as well as 1.6: it belongs in the 1.7 list
        fakeSteam(tagsOf.keys.toList())

        val page = WorkshopApi.search("", SearchSort.POPULAR, 1, versionTag = "1.7")

        assertEquals(listOf(1_000_001L, 1_000_004L), page.items.map { it.id })
        assertTrue(page.items.all { "1.7" in it.versionTags })
        assertTrue("Steam was still asked to filter", browseUrls.single().contains("requiredtags%5B0%5D=1.7"))
    }

    @Test
    fun aModForOtherVersionsOnlyNeverShowsUp() {
        mod(1_000_001, "1.6")
        mod(1_000_002, "1.5")
        mod(1_000_003, "1.4")
        fakeSteam(tagsOf.keys.toList())

        val page = WorkshopApi.search("", SearchSort.POPULAR, 1, versionTag = "1.7")

        assertTrue("nothing is tagged 1.7: nothing is shown", page.items.isEmpty())
        assertFalse(page.hasMore)
    }

    @Test
    fun aVersionIsMatchedExactlyNotByItsPrefix() {
        mod(1_000_001, "1.70")
        mod(1_000_002, "11.7")
        mod(1_000_003, "1.7")
        fakeSteam(tagsOf.keys.toList())

        val page = WorkshopApi.search("", SearchSort.POPULAR, 1, versionTag = "1.7")

        assertEquals(listOf(1_000_003L), page.items.map { it.id })
    }

    @Test
    fun furtherPagesAreReadWhenTheFirstOnesHoldTooFewMatches() {
        // 90 mods in all: 30 per page, and only the ones with id divisible by 9 are tagged 1.7 (10 in all)
        val ids = (1..90).map { 1_000_000L + it }
        for (id in ids) if (id % 9 == 0L) mod(id, "1.7") else mod(id, "1.6")
        fakeSteam(ids)

        val first = WorkshopApi.search("", SearchSort.POPULAR, 1, versionTag = "1.7")

        // page 1 holds 3 matches (10 are tagged in all), which is fewer than FILTER_FILL: it reads on
        assertTrue(browseUrls.size > 1)
        assertTrue(first.items.all { "1.7" in it.versionTags })
        assertEquals(first.items.map { it.id }, first.items.map { it.id }.distinct())
        assertEquals("the page it stopped on", browseUrls.size, first.page)
        // all three pages were read (the 10 matches are fewer than FILTER_FILL), so nothing is left
        assertEquals(10, first.items.size)
        assertFalse(first.hasMore)
        assertNull("items were dropped here, so Steam's count is not the count of what is shown", first.total)
    }

    @Test
    fun aFilteredSearchReadsNoMoreThanTheLimitOfPages() {
        val ids = (1..300).map { 1_000_000L + it }
        for (id in ids) mod(id, "1.5") // nothing at all is tagged 1.7
        fakeSteam(ids)

        val page = WorkshopApi.search("", SearchSort.POPULAR, 1, versionTag = "1.7")

        assertTrue(page.items.isEmpty())
        assertEquals(WorkshopApi.MAX_FILTER_PAGES, browseUrls.size)
        assertEquals("the screen can continue after the last page that was read", WorkshopApi.MAX_FILTER_PAGES, page.page)
        assertTrue("more pages exist, so the person can keep searching", page.hasMore)
    }

    @Test
    fun continuingAFilteredSearchStartsAfterTheLastPageRead() {
        val ids = (1..300).map { 1_000_000L + it }
        for (id in ids) mod(id, "1.5")
        fakeSteam(ids)

        val first = WorkshopApi.search("", SearchSort.POPULAR, 1, versionTag = "1.7")
        browseUrls.clear()
        WorkshopApi.search("", SearchSort.POPULAR, first.page + 1, versionTag = "1.7")

        assertTrue(browseUrls.first(), browseUrls.first().contains("&p=${WorkshopApi.MAX_FILTER_PAGES + 1}&"))
    }

    @Test
    fun stopsAsSoonAsThereAreEnoughMatches() {
        val ids = (1..90).map { 1_000_000L + it }
        for (id in ids) mod(id, "1.7")
        fakeSteam(ids)

        val page = WorkshopApi.search("", SearchSort.POPULAR, 1, versionTag = "1.7")

        assertEquals(1, browseUrls.size)
        assertEquals(30, page.items.size)
        assertTrue(page.hasMore)
        assertEquals("nothing was dropped, so Steam's count is right", 90L, page.total)
    }

    @Test
    fun withoutAVersionEverythingIsListedFromOnePage() {
        mod(1_000_001, "1.7")
        mod(1_000_002, "1.6")
        mod(1_000_003)
        fakeSteam(tagsOf.keys.toList())

        val page = WorkshopApi.search("", SearchSort.POPULAR, 1, versionTag = null)

        assertEquals(3, page.items.size)
        assertEquals(1, browseUrls.size)
        assertFalse(browseUrls.single().contains("requiredtags"))
        // a blank tag means "any version" as well
        browseUrls.clear()
        assertEquals(3, WorkshopApi.search("", SearchSort.POPULAR, 1, versionTag = "  ").items.size)
        assertFalse(browseUrls.single().contains("requiredtags"))
    }

    @Test
    fun collectionsAreNeverFilteredByVersion() {
        mod(1_000_001)
        fakeSteam(tagsOf.keys.toList())

        val page = WorkshopApi.search("", SearchSort.POPULAR, 1, collections = true, versionTag = "1.7")

        assertEquals(1, page.items.size)
        assertFalse(browseUrls.single().contains("requiredtags"))
    }

    @Test
    fun anItemWhoseDetailsCarryNoTagsIsLeftToSteamsOwnFilter() {
        val bare = WorkshopItem(
            1_000_009, "Bare", "", null, 0, 0, 0, null, 294100, 0, 0, emptyList(), emptyList(), 1, 0, false,
        )
        assertTrue(bare.supportsVersion("1.7"))
        val old = bare.copy(tags = listOf("Mod", "1.5"))
        assertFalse(old.supportsVersion("1.7"))
        assertTrue(old.supportsVersion("1.5"))
        assertTrue(old.supportsVersion(" 1.5 "))
    }
}
