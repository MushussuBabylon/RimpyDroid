package com.rimpydroid.manager.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ---------------------------------------------------------------------------------------------
// HTTP
// ---------------------------------------------------------------------------------------------

class HttpStatusException(val code: Int, message: String) : IOException(message)

/** Minimal HttpURLConnection wrapper (JDK only). Always call from a background thread. */
object Http {

    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; RimpyDroid) AppleWebKit/537.36 (KHTML, like Gecko)"

    fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun getString(url: String, timeoutMs: Int = 25_000, maxBytes: Int = 16 * 1024 * 1024): String {
        val bytes = retry { request("GET", url, null, timeoutMs, maxBytes) }
        return XmlUtil.decode(bytes)
    }

    fun getBytes(url: String, timeoutMs: Int = 25_000, maxBytes: Int = 8 * 1024 * 1024): ByteArray =
        retry { request("GET", url, null, timeoutMs, maxBytes) }

    fun postForm(
        url: String,
        fields: List<Pair<String, String>>,
        timeoutMs: Int = 25_000,
        maxBytes: Int = 16 * 1024 * 1024,
    ): String {
        val body = fields.joinToString("&") { encode(it.first) + "=" + encode(it.second) }.toByteArray(Charsets.UTF_8)
        val bytes = retry { request("POST", url, body, timeoutMs, maxBytes) }
        return XmlUtil.decode(bytes)
    }

    private fun <T> retry(times: Int = 3, block: () -> T): T {
        var last: Exception? = null
        for (attempt in 0 until times) {
            try {
                return block()
            } catch (e: HttpStatusException) {
                // 4xx means "no" (except throttling): retrying will not help
                if (e.code in 400..499 && e.code != 429 && e.code != 408) throw e
                last = e
            } catch (e: IOException) {
                last = e
            }
            if (attempt < times - 1) {
                try {
                    Thread.sleep(800L * (attempt + 1))
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        throw last ?: IOException("Request failed")
    }

    private fun request(method: String, url: String, body: ByteArray?, timeoutMs: Int, maxBytes: Int): ByteArray {
        var current = url
        var currentMethod = method
        var currentBody = body
        for (hop in 0..5) {
            val conn = URL(current).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = currentMethod
                conn.connectTimeout = 15_000
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept", "*/*")
                conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                if (current.contains("steamcommunity.com")) {
                    conn.setRequestProperty("Cookie", "Steam_Language=english; birthtime=-2208988799; lastagecheckage=1-January-1900")
                }
                if (currentBody != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    conn.setFixedLengthStreamingMode(currentBody.size)
                    conn.outputStream.use { it.write(currentBody) }
                }
                val code = conn.responseCode
                if (code in 300..399 && code != 304) {
                    val loc = conn.getHeaderField("Location") ?: throw IOException("Redirect without Location (HTTP $code)")
                    current = URL(URL(current), loc).toString()
                    if (code == 301 || code == 302 || code == 303) {
                        currentMethod = "GET"
                        currentBody = null
                    }
                    continue
                }
                if (code !in 200..299) {
                    val err = try {
                        conn.errorStream?.use { readLimited(it, 2048) }?.let { String(it, Charsets.UTF_8) } ?: ""
                    } catch (e: Exception) {
                        ""
                    }
                    throw HttpStatusException(code, "HTTP $code from ${hostOf(current)}" + if (err.isNotBlank()) ": " + err.take(160).replace('\n', ' ') else "")
                }
                return conn.inputStream.use { readLimited(it, maxBytes) }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    private fun hostOf(url: String): String = try {
        URL(url).host
    } catch (e: Exception) {
        url
    }

    private fun readLimited(ins: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw IOException("Response too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

// ---------------------------------------------------------------------------------------------
// Workshop helpers
// ---------------------------------------------------------------------------------------------

object WorkshopUtil {

    const val RIMWORLD_APP_ID = 294100

    /** The Workshop's own number for "collection" (0 is a normal item). */
    const val FILE_TYPE_COLLECTION = 2

    // "...?id=123", "steam://url/CommunityFilePage/123", or a bare number that stands on its own
    private val idRegex = Regex(
        """(?:[?&]id=|CommunityFilePage/)(\d{6,12})|(?<![\w./=:-])(\d{6,12})(?![\w./-])""",
        RegexOption.IGNORE_CASE,
    )

    /** All Workshop ids found in pasted text (links, steam:// urls, bare numbers). Keeps order, no duplicates. */
    fun extractIds(text: String?): List<Long> {
        if (text.isNullOrBlank()) return emptyList()
        val out = LinkedHashSet<Long>()
        for (m in idRegex.findAll(text)) {
            val digits = if (m.groupValues[1].isNotEmpty()) m.groupValues[1] else m.groupValues[2]
            val id = digits.toLongOrNull() ?: continue
            if (id > 0) out.add(id)
        }
        return out.toList()
    }

    fun parseSingleId(text: String?): Long? = extractIds(text).firstOrNull()

    fun itemUrl(id: Long): String = "https://steamcommunity.com/sharedfiles/filedetails/?id=$id"

    private val bbTag = Regex("""\[/?(?:b|i|u|s|h[1-6]|list|olist|quote|code|spoiler|noparse|hr|strike|table|tr|td|th|p|center|previewyoutube|img|url|color|size|font|dynamiclink|sup|sub)(?:=[^\]]*)?]""", RegexOption.IGNORE_CASE)
    private val bbBullet = Regex("""\[\*]""")
    private val xmlTag = Regex("""</?[a-zA-Z][^>]{0,120}>""")
    private val manyBlank = Regex("""\n{3,}""")

    /** Workshop (BBCode) / About.xml (Unity rich text) descriptions as plain text. */
    fun plainText(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        var t = raw.replace("\r\n", "\n").replace('\r', '\n')
        t = t.replace(Regex("""\[img][^\[]*\[/img]""", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
        t = bbBullet.replace(t, "\n• ")
        t = bbTag.replace(t, "")
        t = xmlTag.replace(t, "")
        t = XmlUtil.unescape(t)
        t = t.lines().joinToString("\n") { it.trimEnd() }
        t = manyBlank.replace(t, "\n\n")
        return t.trim()
    }
}

data class WorkshopItem(
    val id: Long,
    val title: String,
    val description: String,
    val previewUrl: String?,
    val fileSize: Long,
    val timeUpdated: Long, // epoch seconds
    val timeCreated: Long,
    val manifest: String?, // hcontent_file: id of the latest content manifest (unsigned decimal)
    val consumerAppId: Long,
    val fileType: Int, // 0 = item, 2 = collection
    val subscriptions: Long,
    val tags: List<String>,
    val children: List<Long>,
    val result: Int, // 1 = ok, 9 = not found (deleted), 15 = access denied (private)
    val visibility: Int,
    val banned: Boolean,
) {
    val isCollection: Boolean get() = fileType == WorkshopUtil.FILE_TYPE_COLLECTION
    val isRimWorld: Boolean get() = consumerAppId == WorkshopUtil.RIMWORLD_APP_ID.toLong()

    /**
     * This item as a collection that lists [kids]. A collection has no files of its own, so the content id
     * Steam sometimes still shows for it is dropped.
     */
    fun asCollection(kids: List<Long>): WorkshopItem =
        copy(fileType = WorkshopUtil.FILE_TYPE_COLLECTION, children = kids, manifest = null)

    /** True when the item can be downloaded anonymously. */
    val isAvailable: Boolean get() = result == 1 && !banned && title.isNotEmpty()

    val url: String get() = WorkshopUtil.itemUrl(id)

    /** Game versions listed as tags ("1.5", "1.6"). */
    val versionTags: List<String> get() = tags.filter { Regex("""\d+\.\d+""").matches(it) }
}

enum class SearchSort(val param: String, val label: String) {
    RELEVANCE("textsearch", "Relevance"),
    POPULAR("trend", "Popular (this week)"),
    SUBSCRIBED("totaluniquesubscribers", "Most subscribed"),
    RECENT("mostrecent", "Newest"),
    UPDATED("lastupdated", "Recently updated"),
}

class SearchPage(val items: List<WorkshopItem>, val page: Int, val hasMore: Boolean)

class ResolvedInput(
    val items: List<WorkshopItem>, // downloadable mods (collections expanded), no duplicates
    val collections: List<WorkshopItem>,
    val notes: List<String>,
)

/** What Steam lists in one collection. */
class CollectionInfo(
    /** Everything in it, in the author's order, without duplicates. */
    val children: List<Long>,
    /** Those of [children] that are collections themselves. */
    val collections: Set<Long>,
) {
    companion object {
        val NONE = CollectionInfo(emptyList(), emptySet())
    }
}

object WorkshopApi {

    private const val DETAILS_URL = "https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/"
    private const val COLLECTION_URL = "https://api.steampowered.com/ISteamRemoteStorage/GetCollectionDetails/v1/"
    private const val BROWSE_URL = "https://steamcommunity.com/workshop/browse/"

    /** How form requests and pages reach Steam (hooks for tests). */
    @Volatile
    var postForm: (String, List<Pair<String, String>>) -> String = { url, fields -> Http.postForm(url, fields) }

    @Volatile
    var getText: (String) -> String = { url -> Http.getString(url) }

    /** Details for [ids], in the same order. Ids Steam does not know are returned with result != 1. */
    fun getDetails(ids: Collection<Long>): List<WorkshopItem> {
        val distinct = ids.distinct()
        if (distinct.isEmpty()) return emptyList()
        val byId = HashMap<Long, WorkshopItem>()
        for (chunk in distinct.chunked(100)) {
            val fields = ArrayList<Pair<String, String>>()
            fields.add("itemcount" to chunk.size.toString())
            for ((i, id) in chunk.withIndex()) fields.add("publishedfileids[$i]" to id.toString())
            val text = postForm(DETAILS_URL, fields)
            val response = Json.parse(text).asObj()?.get("response").asObj()
                ?: throw IOException("Unexpected answer from Steam")
            val details = response["publishedfiledetails"].asArr() ?: emptyList()
            for (d in details) {
                val item = parseItem(d.asObj() ?: continue) ?: continue
                byId[item.id] = item
            }
        }
        return distinct.map { id -> byId[id] ?: missingItem(id) }
    }

    fun getDetails(id: Long): WorkshopItem? = getDetails(listOf(id)).firstOrNull()

    /**
     * What Steam's GetCollectionDetails says about each of [ids]. An id that is not a collection comes back
     * without children (or not at all).
     */
    fun getCollections(ids: Collection<Long>): Map<Long, CollectionInfo> {
        val distinct = ids.distinct()
        if (distinct.isEmpty()) return emptyMap()
        val out = LinkedHashMap<Long, CollectionInfo>()
        for (chunk in distinct.chunked(50)) {
            val fields = ArrayList<Pair<String, String>>()
            fields.add("collectioncount" to chunk.size.toString())
            for ((i, id) in chunk.withIndex()) fields.add("publishedfileids[$i]" to id.toString())
            val text = postForm(COLLECTION_URL, fields)
            val response = Json.parse(text).asObj()?.get("response").asObj() ?: continue
            val details = response["collectiondetails"].asArr() ?: continue
            for (d in details) {
                val o = d.asObj() ?: continue
                val id = o["publishedfileid"].asLong() ?: continue
                out[id] = if ((o["result"].asInt() ?: 1) == 1) collectionInfo(o["children"]) else CollectionInfo.NONE
            }
        }
        return out
    }

    /** Child ids of each collection (empty list for anything that is not one). */
    fun getCollectionChildren(ids: Collection<Long>): Map<Long, List<Long>> =
        getCollections(ids).mapValues { it.value.children }

    /** True when Steam lists mods for [id], that is, when it is a collection. Network errors are thrown. */
    fun isCollection(id: Long): Boolean = getCollections(listOf(id))[id]?.children?.isNotEmpty() == true

    /**
     * Steam's item details do not say reliably that an item is a collection, so a collection can look like a mod
     * whose files do not exist (a download then ends with "not found" from every server). GetCollectionDetails
     * knows: it lists what is in a collection and nothing for anything else. RimDroid asks it about every id
     * for the same reason.
     *
     * Returns [items] with the collections among them marked ([WorkshopItem.isCollection]) and their
     * [WorkshopItem.children] filled in. [allAreCollections] says that the caller already knows that they all
     * are (a search in the Collections section). A question Steam does not answer changes nothing.
     */
    fun markCollections(items: List<WorkshopItem>, allAreCollections: Boolean = false): List<WorkshopItem> =
        mark(items, askCollections(items), allAreCollections)

    /** The collection API's answers for the available items among [items]; nothing when Steam does not answer. */
    private fun askCollections(items: List<WorkshopItem>): Map<Long, CollectionInfo> {
        val ask = items.filter { it.isAvailable }.map { it.id }.distinct()
        if (ask.isEmpty()) return emptyMap()
        return try {
            getCollections(ask)
        } catch (e: IOException) {
            emptyMap()
        } catch (e: JsonException) {
            emptyMap()
        }
    }

    private fun mark(items: List<WorkshopItem>, answers: Map<Long, CollectionInfo>, allAreCollections: Boolean): List<WorkshopItem> =
        items.map { item ->
            val kids = answers[item.id]?.children.orEmpty()
            when {
                !item.isAvailable -> item
                kids.isNotEmpty() -> item.asCollection(kids)
                allAreCollections || item.isCollection -> item.asCollection(item.children)
                else -> item
            }
        }

    /** Workshop browse page, scraped (Steam offers no key-less search API). */
    fun search(
        query: String,
        sort: SearchSort,
        page: Int,
        collections: Boolean = false,
        versionTag: String? = null,
    ): SearchPage {
        var effective = sort
        if (query.isBlank() && sort == SearchSort.RELEVANCE) effective = SearchSort.POPULAR
        val sb = StringBuilder(BROWSE_URL)
        sb.append("?appid=").append(WorkshopUtil.RIMWORLD_APP_ID)
        if (query.isNotBlank()) sb.append("&searchtext=").append(Http.encode(query.trim()))
        sb.append("&childpublishedfileid=0")
        sb.append("&browsesort=").append(effective.param)
        sb.append("&section=").append(if (collections) "collections" else "readytouseitems")
        sb.append("&actualsort=").append(effective.param)
        sb.append("&p=").append(maxOf(1, page))
        sb.append("&numperpage=30")
        if (effective == SearchSort.POPULAR) sb.append("&days=7")
        if (!versionTag.isNullOrBlank()) sb.append("&requiredtags%5B0%5D=").append(Http.encode(versionTag))

        val html = getText(sb.toString())
        val ids = parseBrowseIds(html)
        val hasMore = parseHasMore(html, ids.size)
        val found = if (ids.isEmpty()) emptyList() else getDetails(ids).filter { it.isAvailable }
        // The Collections section lists collections only, whatever the item details say about their type.
        val items = if (collections) markCollections(found, allAreCollections = true) else found
        return SearchPage(items, page, hasMore)
    }

    private val browseIdRegex = Regex("""data-publishedfileid="(\d+)"""")
    private val pagingRegex = Regex("""Showing\s+([\d,.]+)\s*-\s*([\d,.]+)\s+of\s+([\d,.]+)""")

    fun parseBrowseIds(html: String): List<Long> {
        val out = LinkedHashSet<Long>()
        for (m in browseIdRegex.findAll(html)) {
            val id = m.groupValues[1].toLongOrNull() ?: continue
            out.add(id)
        }
        return out.toList()
    }

    fun parseHasMore(html: String, idsOnPage: Int): Boolean {
        val m = pagingRegex.find(html)
        if (m != null) {
            val last = m.groupValues[2].replace(",", "").replace(".", "").toLongOrNull()
            val total = m.groupValues[3].replace(",", "").replace(".", "").toLongOrNull()
            if (last != null && total != null) return last < total
        }
        return idsOnPage >= 9
    }

    /**
     * Turns pasted text (item links, collection links, bare ids) into downloadable items.
     * Collections are expanded to their mods.
     */
    fun resolveInput(text: String): ResolvedInput {
        val ids = WorkshopUtil.extractIds(text).take(600)
        if (ids.isEmpty()) {
            return ResolvedInput(emptyList(), emptyList(), listOf("No Steam Workshop links or ids found."))
        }
        return resolveIds(ids)
    }

    /**
     * [ids] as downloadable mods, in order. A collection among them is replaced by the mods it lists (a collection
     * inside a collection too, up to three levels), a mod that is listed twice counts once, and what cannot be
     * downloaded (removed, private, not for RimWorld) is left out with a note.
     */
    fun resolveIds(ids: List<Long>): ResolvedInput {
        val notes = ArrayList<String>()
        val mods = LinkedHashMap<Long, WorkshopItem>()
        val collections = ArrayList<WorkshopItem>()
        val seen = HashSet<Long>()

        fun consider(item: WorkshopItem) {
            when {
                !item.isAvailable -> notes.add("Item ${item.id} is not available (removed, private or hidden).")
                !item.isRimWorld -> notes.add("\"${item.title}\" is not a RimWorld item, skipped.")
                item.isCollection -> {} // handled by the loop below
                else -> if (!mods.containsKey(item.id)) mods[item.id] = item
            }
        }

        // what the collection API said so far: the first question is about every id, later ones only about collections
        val answers = HashMap<Long, CollectionInfo>()
        var pending = ArrayList<WorkshopItem>()
        if (ids.isNotEmpty()) {
            val first = getDetails(ids)
            answers.putAll(askCollections(first))
            for (item in mark(first, answers, false)) {
                if (item.isAvailable && item.isRimWorld && item.isCollection) pending.add(item) else consider(item)
            }
        }

        var depth = 0
        while (pending.isNotEmpty() && depth < 3) {
            pending = ArrayList(pending.filter { seen.add(it.id) })
            if (pending.isEmpty()) break
            collections.addAll(pending)
            val unasked = pending.filter { it.id !in answers }
            if (unasked.isNotEmpty()) answers.putAll(askCollections(unasked))
            val next = ArrayList<WorkshopItem>()
            val childIds = LinkedHashSet<Long>()
            val nested = HashSet<Long>()
            val perCollection = LinkedHashMap<Long, List<Long>>()
            for (c in pending) {
                val info = answers[c.id]
                val kids = info?.children?.takeIf { it.isNotEmpty() } ?: c.children
                perCollection[c.id] = kids
                childIds.addAll(kids)
                if (info != null) nested.addAll(info.collections)
            }
            childIds.removeAll(mods.keys)
            val kidItems = if (childIds.isEmpty()) emptyList() else getDetails(childIds)
            for (k in kidItems) {
                // What a collection lists as a collection is one, whatever the item details say. Everything else
                // it lists is a mod: no question needs to be asked about each of them.
                val item = if (k.isAvailable && k.id in nested) k.asCollection(k.children) else k
                if (item.isAvailable && item.isRimWorld && item.isCollection) next.add(item) else consider(item)
            }
            for (c in pending) {
                val kids = perCollection[c.id].orEmpty()
                notes.add("Collection \"${c.title}\": ${kids.size} item(s).")
            }
            pending = next
            depth++
        }
        return ResolvedInput(mods.values.toList(), collections, notes)
    }

    // ---- parsing ---------------------------------------------------------------------------------

    private fun childIds(node: Any?): List<Long> {
        val out = ArrayList<Long>()
        for (c in node.asArr().orEmpty()) {
            val o = c.asObj() ?: continue
            val id = o["publishedfileid"].asLong() ?: continue
            out.add(id)
        }
        return out
    }

    /** The entries of a GetCollectionDetails answer: author's order, and which of them are collections. */
    private fun collectionInfo(node: Any?): CollectionInfo {
        val entries = ArrayList<Triple<Int, Long, Boolean>>() // sort order, id, is a collection
        for ((i, c) in node.asArr().orEmpty().withIndex()) {
            val o = c.asObj() ?: continue
            val id = o["publishedfileid"].asLong() ?: continue
            val type = o["filetype"].asInt() ?: o["file_type"].asInt() ?: 0
            entries.add(Triple(o["sortorder"].asInt() ?: i, id, type == WorkshopUtil.FILE_TYPE_COLLECTION))
        }
        val ordered = entries.sortedBy { it.first }.distinctBy { it.second }
        return CollectionInfo(ordered.map { it.second }, ordered.filter { it.third }.map { it.second }.toSet())
    }

    private fun missingItem(id: Long) = WorkshopItem(
        id = id, title = "", description = "", previewUrl = null, fileSize = 0, timeUpdated = 0, timeCreated = 0,
        manifest = null, consumerAppId = 0, fileType = 0, subscriptions = 0, tags = emptyList(), children = emptyList(),
        result = 9, visibility = 0, banned = false,
    )

    fun parseItem(o: Map<String, Any?>): WorkshopItem? {
        val id = o["publishedfileid"].asLong() ?: return null
        val result = o["result"].asInt() ?: 1
        val tags = ArrayList<String>()
        for (t in o["tags"].asArr().orEmpty()) {
            val tag = t.asObj()?.get("tag").asStr() ?: t.asStr()
            if (!tag.isNullOrBlank()) tags.add(tag.trim())
        }
        val manifest = o["hcontent_file"].asStr()?.trim()?.takeIf { it.isNotEmpty() && it != "0" }
        return WorkshopItem(
            id = id,
            title = o["title"].asStr()?.trim().orEmpty(),
            description = o["description"].asStr().orEmpty(),
            previewUrl = o["preview_url"].asStr()?.takeIf { it.isNotBlank() },
            fileSize = o["file_size"].asLong() ?: 0L,
            timeUpdated = o["time_updated"].asLong() ?: 0L,
            timeCreated = o["time_created"].asLong() ?: 0L,
            manifest = manifest,
            consumerAppId = o["consumer_app_id"].asLong() ?: 0L,
            fileType = o["file_type"].asInt() ?: 0,
            subscriptions = o["lifetime_subscriptions"].asLong() ?: o["subscriptions"].asLong() ?: 0L,
            tags = tags,
            children = childIds(o["children"]),
            result = result,
            visibility = o["visibility"].asInt() ?: 0,
            banned = (o["banned"].asInt() ?: 0) != 0,
        )
    }
}
