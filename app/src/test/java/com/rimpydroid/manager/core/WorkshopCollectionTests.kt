package com.rimpydroid.manager.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Collections: Steam's item details do not always say that an id is a collection, so the Workshop code asks
 * GetCollectionDetails (the way RimDroid does). These tests stand in for Steam through the hooks of [WorkshopApi].
 * (Workshop ids have at least six digits; shorter numbers are not taken for ids when links are read.)
 */
class WorkshopCollectionTests {

    private val realPost = WorkshopApi.postForm
    private val realGet = WorkshopApi.getText

    @After
    fun restoreTheNetwork() {
        WorkshopApi.postForm = realPost
        WorkshopApi.getText = realGet
    }

    // mods
    private val m1 = 1_000_001L
    private val m2 = 1_000_002L
    private val m3 = 1_000_003L
    private val m4 = 1_000_004L
    private val m5 = 1_000_005L

    // collections
    private val c1 = 2_976_993_124L
    private val c2 = 2_000_002L
    private val c3 = 2_000_003L

    /** What the fake Steam knows. */
    private class FakeSteam {
        /** id -> details JSON (an object). Ids that are not here are "not found". */
        val items = LinkedHashMap<Long, String>()

        /** collection id -> the children as the collection API lists them: (id, sortorder, filetype). */
        val collections = LinkedHashMap<Long, List<Triple<Long, Int, Int>>>()

        /** Ids that were asked about with the collection API, one entry per question. */
        val collectionQuestions = ArrayList<Long>()
        var failCollectionApi = false
        var collectionApiCalls = 0

        fun mod(id: Long, title: String = "Mod $id", appId: Int = 294100, extra: String = "") {
            items[id] = """{"publishedfileid":"$id","result":1,"consumer_app_id":$appId,"title":"$title","file_size":"500",""" +
                """"hcontent_file":"9000$id","time_updated":1700000000,"tags":[{"tag":"Mod"}]$extra}"""
        }

        /** A collection as the details API shows it: no file_type at all, and a content id like a mod's. */
        fun collection(id: Long, kids: List<Triple<Long, Int, Int>>, title: String = "Collection $id") {
            items[id] = """{"publishedfileid":"$id","result":1,"consumer_app_id":294100,"title":"$title","file_size":"0",""" +
                """"hcontent_file":"2046367999300246921","time_updated":1700000000}"""
            collections[id] = kids
        }

        fun post(url: String, fields: List<Pair<String, String>>): String {
            val ids = fields.filter { it.first.startsWith("publishedfileids[") }.map { it.second.toLong() }
            return if (url.contains("GetCollectionDetails")) {
                collectionApiCalls++
                if (failCollectionApi) throw IOException("Steam is down")
                collectionQuestions.addAll(ids)
                val details = ids.joinToString(",") { id ->
                    val kids = collections[id]
                    if (kids == null) {
                        """{"publishedfileid":"$id","result":9}"""
                    } else {
                        val children = kids.joinToString(",") { (c, order, type) ->
                            """{"publishedfileid":"$c","sortorder":$order,"filetype":$type}"""
                        }
                        """{"publishedfileid":"$id","result":1,"children":[$children]}"""
                    }
                }
                """{"response":{"result":1,"resultcount":${ids.size},"collectiondetails":[$details]}}"""
            } else {
                val details = ids.joinToString(",") { id -> items[id] ?: """{"publishedfileid":"$id","result":9}""" }
                """{"response":{"result":1,"resultcount":${ids.size},"publishedfiledetails":[$details]}}"""
            }
        }
    }

    private fun steam(block: FakeSteam.() -> Unit): FakeSteam {
        val s = FakeSteam()
        s.block()
        WorkshopApi.postForm = { url, fields -> s.post(url, fields) }
        return s
    }

    private fun link(id: Long) = "https://steamcommunity.com/sharedfiles/filedetails/?id=$id"

    @Test
    fun aCollectionIsRecognisedEvenWhenTheDetailsDoNotSayIt() {
        val s = steam {
            collection(c1, listOf(Triple(m2, 2, 0), Triple(m1, 1, 0), Triple(m3, 3, 0)), title = "RimWorld Ultimate Collection")
            mod(m1); mod(m2); mod(m3)
        }
        // what the app saw before: nothing in the details marks it as a collection, and it even has a content id
        val plain = WorkshopApi.getDetails(c1)!!
        assertFalse(plain.isCollection)
        assertEquals("2046367999300246921", plain.manifest)

        val res = WorkshopApi.resolveInput(link(c1))
        assertEquals("the mods in the author's order", listOf(m1, m2, m3), res.items.map { it.id })
        assertEquals(listOf(c1), res.collections.map { it.id })
        assertTrue(res.collections[0].isCollection)
        assertNull("a collection has no files of its own", res.collections[0].manifest)
        assertTrue(res.notes.toString(), res.notes.any { it.startsWith("Collection \"RimWorld Ultimate Collection\": 3 item") })
        // the three mods came from the collection's own list: nobody asked the collection API about them
        assertEquals(listOf(c1), s.collectionQuestions)
    }

    @Test
    fun aModStaysAModAndIsNeverReplacedByItsRequiredItems() {
        steam {
            // the details of a mod may list its required items as children; that does not make it a collection
            mod(m1, extra = ""","children":[{"publishedfileid":"$m2","sortorder":1,"file_type":0}]""")
            mod(m2)
        }
        val res = WorkshopApi.resolveInput(link(m1))
        assertEquals(listOf(m1), res.items.map { it.id })
        assertTrue(res.collections.isEmpty())
        assertFalse(res.items[0].isCollection)
        assertEquals("9000$m1", res.items[0].manifest)
    }

    @Test
    fun aMixOfModsAndCollectionsKeepsTheOrderAndListsEveryModOnce() {
        steam {
            mod(m1); mod(m2); mod(m3); mod(m4)
            collection(c2, listOf(Triple(m2, 1, 0), Triple(m3, 2, 0)))
            collection(c3, listOf(Triple(m3, 1, 0), Triple(m4, 2, 0)))
        }
        val res = WorkshopApi.resolveInput("${link(m1)} ${link(c2)} ${link(c3)} ${link(m2)}")
        assertEquals(listOf(m1, m2, m3, m4), res.items.map { it.id })
        assertEquals(listOf(c2, c3), res.collections.map { it.id })
    }

    @Test
    fun aCollectionInsideACollectionIsExpandedAndALoopEnds() {
        val s = steam {
            mod(m1); mod(m2)
            collection(c2, listOf(Triple(m1, 1, 0), Triple(c3, 2, 2)))
            // c3 lists m2 and (by mistake) the collection it is part of
            collection(c3, listOf(Triple(m2, 1, 0), Triple(c2, 2, 2)))
        }
        val res = WorkshopApi.resolveInput(link(c2))
        assertEquals(listOf(m1, m2), res.items.map { it.id })
        assertEquals(listOf(c2, c3), res.collections.map { it.id })
        // each collection was asked about once, however often it is met
        assertEquals(listOf(c2, c3), s.collectionQuestions)
    }

    @Test
    fun unavailableAndForeignItemsAreReportedNotDownloaded() {
        val gone = 1_000_999L
        steam {
            mod(m1)
            mod(m5, title = "Other game", appId = 440)
            collection(c2, listOf(Triple(m1, 1, 0), Triple(gone, 2, 0), Triple(m5, 3, 0)))
        }
        val res = WorkshopApi.resolveInput(link(c2))
        assertEquals(listOf(m1), res.items.map { it.id })
        assertTrue(res.notes.toString(), res.notes.any { it.contains("$gone") && it.contains("not available") })
        assertTrue(res.notes.toString(), res.notes.any { it.contains("Other game") })
    }

    @Test
    fun whenSteamDoesNotAnswerTheCollectionQuestionEverythingStaysAsItWas() {
        val s = steam {
            mod(m1)
            collection(c2, listOf(Triple(m1, 1, 0)))
        }
        s.failCollectionApi = true
        val res = WorkshopApi.resolveInput("${link(m1)} ${link(c2)}")
        assertEquals(listOf(m1, c2), res.items.map { it.id })
        assertTrue(res.collections.isEmpty())
        val marked = WorkshopApi.markCollections(WorkshopApi.getDetails(listOf(m1, c2)))
        assertTrue(marked.none { it.isCollection })
    }

    @Test
    fun isCollectionAsksTheCollectionApi() {
        steam {
            mod(m1)
            collection(c2, listOf(Triple(m1, 1, 0)))
            collection(c3, emptyList())
        }
        assertTrue(WorkshopApi.isCollection(c2))
        assertFalse(WorkshopApi.isCollection(m1))
        assertFalse("an empty collection has nothing to download either way", WorkshopApi.isCollection(c3))
        assertFalse(WorkshopApi.isCollection(1_000_404L))
    }

    @Test
    fun theCollectionsSectionOfASearchMarksEveryResult() {
        val s = steam {
            collection(c2, listOf(Triple(m1, 1, 0), Triple(m2, 2, 0)))
            collection(c3, emptyList()) // Steam gave no list this time
            mod(m1); mod(m2)
        }
        WorkshopApi.getText = { url ->
            assertTrue(url, url.contains("section=collections"))
            """<div data-publishedfileid="$c2"></div><div data-publishedfileid="$c3"></div>Showing 1-2 of 2 entries"""
        }
        val page = WorkshopApi.search("", SearchSort.POPULAR, 1, collections = true)
        assertEquals(listOf(c2, c3), page.items.map { it.id })
        assertTrue(page.items.all { it.isCollection })
        assertEquals(listOf(m1, m2), page.items[0].children)
        assertTrue(page.items.all { it.manifest == null })
        assertFalse(page.hasMore)
        assertTrue(s.collectionApiCalls >= 1)
    }

    @Test
    fun anItemSearchDoesNotAskTheCollectionApiAtAll() {
        val s = steam {
            mod(m1); mod(m2)
        }
        WorkshopApi.getText = { url ->
            assertTrue(url, url.contains("section=readytouseitems"))
            """<div data-publishedfileid="$m1"></div><div data-publishedfileid="$m2"></div>"""
        }
        val page = WorkshopApi.search("harmony", SearchSort.RELEVANCE, 1)
        assertEquals(listOf(m1, m2), page.items.map { it.id })
        assertTrue(page.items.none { it.isCollection })
        assertEquals(0, s.collectionApiCalls)
    }

    @Test
    fun aDetailsAnswerThatSaysCollectionIsStillTrusted() {
        steam {
            mod(m1)
            items[c2] = """{"publishedfileid":"$c2","result":1,"consumer_app_id":294100,"file_type":2,"title":"List","hcontent_file":"0"}"""
            collections[c2] = listOf(Triple(m1, 1, 0))
        }
        val first = WorkshopApi.getDetails(c2)!!
        assertTrue(first.isCollection)
        val res = WorkshopApi.resolveInput(link(c2))
        assertEquals(listOf(m1), res.items.map { it.id })
    }

    @Test
    fun resolveIdsWorksOnPlainIds() {
        steam {
            mod(m1); mod(m2)
            collection(c2, listOf(Triple(m2, 1, 0)))
        }
        val res = WorkshopApi.resolveIds(listOf(m1, c2, m1))
        assertEquals(listOf(m1, m2), res.items.map { it.id })
        assertTrue(WorkshopApi.resolveIds(emptyList()).items.isEmpty())
    }
}
