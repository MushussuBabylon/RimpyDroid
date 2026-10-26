package com.rimpydroid.manager.steam

import com.rimpydroid.manager.AppLog
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.cdn.Server
import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import `in`.dragonbra.javasteam.steam.handlers.steamcontent.SteamContent
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.types.ChunkData
import `in`.dragonbra.javasteam.types.DepotManifest
import `in`.dragonbra.javasteam.util.SteamKitWebRequestException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** The Steam network cannot be reached at all (no internet, blocked, Steam down). */
class SteamConnectException(message: String) : IOException(message)

/** A problem that trying again will not fix: the item is not available to anonymous downloads, the storage is full... */
class SteamFatalException(message: String) : IOException(message)

/** A download server answered with an HTTP error. */
class CdnStatusException(val status: Int, message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Everything the downloader needs from Steam, in plain terms. [JavaSteamBackend] talks to Steam through
 * JavaSteam; tests use a fake. All methods block and may be called from several threads, except where noted.
 */
interface SteamBackend {

    /** Makes sure there is a connection to Steam (opens one if needed). Throws [SteamConnectException]. */
    @Throws(IOException::class)
    fun connect()

    /** The depot that holds the Workshop items of [appId]. Falls back to the app id itself when Steam does not say. */
    @Throws(IOException::class)
    fun workshopDepot(appId: Int): Int

    /** The 32-byte key that unlocks the depot. Throws [SteamFatalException] when Steam refuses anonymous access. */
    @Throws(IOException::class)
    fun depotKey(depotId: Int, appId: Int): ByteArray

    /** Download servers, best first. */
    @Throws(IOException::class)
    fun cdnServers(appId: Int): List<Server>

    /** The code Steam wants to see in the request for the file list. 0 when none was granted. */
    @Throws(IOException::class)
    fun manifestRequestCode(depotId: Int, appId: Int, manifestId: Long): Long

    /** The file list (manifest) with decrypted file names. */
    @Throws(IOException::class)
    fun downloadManifest(depotId: Int, manifestId: Long, requestCode: Long, server: Server, depotKey: ByteArray, token: String?): DepotManifest

    /**
     * Downloads, decrypts and unpacks one chunk into [dest] (at least max(compressed, uncompressed) bytes long)
     * and returns the number of bytes written. Throws [CdnStatusException] for HTTP errors.
     */
    @Throws(IOException::class)
    fun downloadChunk(depotId: Int, chunk: ChunkData, server: Server, dest: ByteArray, depotKey: ByteArray, token: String?): Int

    /** A CDN token that was fetched earlier for [host], if any. */
    fun knownCdnToken(depotId: Int, host: String): String?

    /** Asks Steam for a CDN token after a server answered 401/403. Null when Steam has none. Never reconnects. */
    @Throws(InterruptedException::class)
    fun requestCdnToken(appId: Int, depotId: Int, host: String): String?

    /** Drops the connection and everything that depends on it. Safe to call from any thread. */
    fun reset()

    /** Like [reset], and forgets what was learned about depots too. */
    fun close()
}

/** Which of the servers Steam listed to use, in which order. */
object ServerSelection {

    /**
     * Servers that serve [appId] and are plain content servers (type "SteamCache" or "CDN"), lightest load first.
     * Proxy and China-only servers come after them. When Steam lists no such server at all, every listed server is used.
     */
    fun order(all: List<Server>, appId: Int): List<Server> {
        val withHost = all.filter { !it.host.isNullOrEmpty() || !it.vHost.isNullOrEmpty() }
        fun eligible(s: Server): Boolean =
            (s.allowedAppIds.isEmpty() || s.allowedAppIds.contains(appId)) && (s.type == "SteamCache" || s.type == "CDN")

        val first = withHost.filter { eligible(it) && !it.useAsProxy && !it.steamChinaOnly }.sortedBy { it.weightedLoad }
        val second = withHost.filter { eligible(it) && (it.useAsProxy || it.steamChinaOnly) }.sortedBy { it.weightedLoad }
        val good = first + second
        // Servers of unknown types are only worth trying when there is nothing better.
        return if (good.isNotEmpty()) good else withHost.distinct()
    }

    fun hostOf(s: Server): String = s.host?.takeIf { it.isNotEmpty() } ?: s.vHost.orEmpty()
}

/**
 * The real thing: JavaSteam. This is the mechanism of RimDroid's anonymous Workshop downloader
 * (SteamAnonModDownloader.java, MIT licence, Copyright (c) 2026 udarmolota; see the notice in
 * SteamDownloader.kt), ported to Kotlin.
 *
 * JavaSteam's high-level DepotDownloader is not used: version 1.8.0 skips every normal Workshop item
 * ("unsupported file type First") because its list of accepted types contains EWorkshopFileType.Community
 * while the type read from Steam is the distinct constant First, which has the same code 0.
 * So the lower-level pieces are driven directly: PICS for the Workshop depot, the depot key, the list of
 * content servers, the manifest and then the chunks.
 */
class JavaSteamBackend(
    private val session: SteamSession = SteamSession(),
    /** Base pause before a question to Steam is asked again after a failure (a hook for tests). */
    private val retryPauseMs: Long = 400L,
) : SteamBackend {

    private val workshopDepots = ConcurrentHashMap<Int, Int>()
    private val depotKeys = ConcurrentHashMap<Int, ByteArray>()
    private val tokens = ConcurrentHashMap<String, String>() // "" = Steam has none for this host
    private val tokenLocks = ConcurrentHashMap<String, Any>()
    private val tokenFailures = AtomicInteger()

    @Volatile
    private var servers: List<Server>? = null

    private val connectLock = Any()

    override fun connect() {
        ensureConnected()
    }

    private fun ensureConnected(): SteamClient {
        synchronized(connectLock) {
            val existing = session.client
            if (existing != null && session.isReady) return existing
            // what was learned over the old connection is not trusted any more
            servers = null
            tokens.clear()
            tokenFailures.set(0)
            return session.connect()
        }
    }

    /** Runs [block] with a live connection; when it fails the connection is rebuilt and the call tried again. */
    private fun <T> cm(what: String, block: (SteamClient) -> T): T {
        var last: Exception? = null
        for (attempt in 1..CM_TRIES) {
            val c = ensureConnected()
            try {
                return block(c)
            } catch (e: InterruptedException) {
                throw e
            } catch (e: SteamFatalException) {
                throw e
            } catch (e: SteamConnectException) {
                throw e
            } catch (e: Exception) {
                last = e
                AppLog.w("Steam", "$what failed (try $attempt of $CM_TRIES): ${e.message ?: e.javaClass.simpleName}")
                if (attempt < CM_TRIES) {
                    session.close() // the connection is probably dead; ensureConnected() opens a new one
                    Thread.sleep(retryPauseMs * attempt)
                }
            }
        }
        throw IOException("$what failed: ${last?.message ?: last?.javaClass?.simpleName}", last)
    }

    // ---- questions for Steam (need the connection) -------------------------------------------------

    override fun workshopDepot(appId: Int): Int {
        workshopDepots[appId]?.let { return it }
        val resolved = try {
            cm("Looking up the Workshop depot") { c -> resolveWorkshopDepot(c, appId) }
        } catch (e: SteamConnectException) {
            throw e
        } catch (e: IOException) {
            AppLog.w("Steam", "Could not read the Workshop depot from Steam (${e.message}); assuming depot $appId")
            appId
        }
        val depot = if (resolved > 0) resolved else appId
        workshopDepots[appId] = depot
        AppLog.i("Steam", "Workshop depot of app $appId: $depot")
        return depot
    }

    private fun resolveWorkshopDepot(c: SteamClient, appId: Int): Int {
        val apps = c.getHandler<SteamApps>() ?: throw IOException("Steam library is missing its apps handler")
        var token = 0L
        try {
            val tk = futureGet(apps.picsGetAccessTokens(listOf(appId), emptyList<Int>()).toFuture(), 30_000, "access token")
            tk.appTokens[appId]?.let { token = it }
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            // public apps do not need a token
        }
        val rs = futureGet(
            apps.picsGetProductInfo(listOf(PICSRequest(appId, token)), emptyList<PICSRequest>()).toFuture(),
            60_000,
            "app info",
        )
        for (cb in rs.results) {
            val info = cb.apps[appId] ?: continue
            return info.keyValues["depots"]["workshopdepot"].asInteger(-1)
        }
        return -1
    }

    override fun depotKey(depotId: Int, appId: Int): ByteArray {
        depotKeys[depotId]?.let { return it }
        val key = cm("Requesting the decryption key") { c ->
            val apps = c.getHandler<SteamApps>() ?: throw IOException("Steam library is missing its apps handler")
            val cb = futureGet(apps.getDepotDecryptionKey(depotId, appId).toFuture(), 30_000, "decryption key")
            if (cb.result == EResult.AccessDenied) {
                throw SteamFatalException("Steam does not allow anonymous downloads of this item (the game must be owned).")
            }
            if (cb.result != EResult.OK) throw IOException("Steam answered ${cb.result}")
            val k = cb.depotKey
            if (k.size != 32) throw IOException("Steam returned an unusable key (${k.size} bytes)")
            k
        }
        depotKeys[depotId] = key
        return key
    }

    override fun cdnServers(appId: Int): List<Server> {
        servers?.let { return it }
        val all = cm("Looking up download servers") { c ->
            val content = c.getHandler<SteamContent>() ?: throw IOException("Steam library is missing its content handler")
            awaitDeferred(30_000, "download servers") { s -> content.getServersForSteamPipe(parentScope = s) }
        }
        val ordered = ServerSelection.order(all, appId)
        if (ordered.isEmpty()) throw IOException("Steam returned no download servers.")
        AppLog.i("Steam", "${ordered.size} download servers, first: ${ordered.take(3).joinToString { "${ServerSelection.hostOf(it)}:${it.port}" }}")
        servers = ordered
        return ordered
    }

    override fun manifestRequestCode(depotId: Int, appId: Int, manifestId: Long): Long =
        cm("Requesting the file list") { c ->
            val content = c.getHandler<SteamContent>() ?: throw IOException("Steam library is missing its content handler")
            awaitDeferred(30_000, "file list request") { s ->
                content.getManifestRequestCode(depotId, appId, manifestId, "public", null, s)
            }
        }

    override fun knownCdnToken(depotId: Int, host: String): String? = tokens["$depotId/$host"]?.takeIf { it.isNotEmpty() }

    override fun requestCdnToken(appId: Int, depotId: Int, host: String): String? {
        val key = "$depotId/$host"
        tokens[key]?.let { return it.takeIf { t -> t.isNotEmpty() } }
        // One question per server, however many chunk threads run into the same 403 at the same moment.
        synchronized(tokenLocks.computeIfAbsent(key) { Any() }) {
            tokens[key]?.let { return it.takeIf { t -> t.isNotEmpty() } }
            if (tokenFailures.get() >= MAX_TOKEN_FAILURES) return null
            var token: String? = null
            try {
                val c = session.client
                if (c != null && session.isReady) {
                    val content = c.getHandler<SteamContent>()
                    if (content != null) {
                        val t = awaitDeferred(15_000, "download token") { s -> content.getCDNAuthToken(appId, depotId, host, s) }
                        if (t.result == EResult.OK) token = t.token else tokenFailures.incrementAndGet()
                    }
                } else {
                    tokenFailures.incrementAndGet()
                }
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                tokenFailures.incrementAndGet()
                AppLog.w("Steam", "No download token for $host: ${e.message ?: e.javaClass.simpleName}")
            }
            tokens[key] = token.orEmpty()
            return token
        }
    }

    // ---- files (plain HTTPS, no connection needed) ------------------------------------------------

    override fun downloadManifest(
        depotId: Int,
        manifestId: Long,
        requestCode: Long,
        server: Server,
        depotKey: ByteArray,
        token: String?,
    ): DepotManifest {
        val cdn = session.cdn()
        return httpGet("file list", server, 90_000) {
            cdn.downloadManifestFuture(depotId, manifestId, requestCode, server, depotKey, null, token)
        }
    }

    override fun downloadChunk(
        depotId: Int,
        chunk: ChunkData,
        server: Server,
        dest: ByteArray,
        depotKey: ByteArray,
        token: String?,
    ): Int {
        val cdn = session.cdn()
        return httpGet("chunk", server, 90_000) {
            cdn.downloadDepotChunkFuture(depotId, chunk, server, dest, depotKey, null, token)
        }
    }

    private fun <T> httpGet(what: String, server: Server, timeoutMs: Long, start: () -> CompletableFuture<T>): T {
        val future = start()
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw IOException("Timed out waiting for the $what from ${ServerSelection.hostOf(server)}")
        } catch (e: ExecutionException) {
            throw translate(e.cause ?: e, server)
        } catch (e: java.util.concurrent.CancellationException) {
            throw IOException("The $what download was cancelled")
        }
    }

    private fun translate(t: Throwable, server: Server): IOException {
        val host = ServerSelection.hostOf(server)
        return when (t) {
            is SteamKitWebRequestException ->
                if (t.statusCode > 0) CdnStatusException(t.statusCode, "HTTP ${t.statusCode} from $host", t)
                else IOException("${t.message} ($host)", t)
            is IOException -> t
            else -> IOException("${t.javaClass.simpleName}: ${t.message} ($host)", t)
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun <T> futureGet(f: CompletableFuture<T>, timeoutMs: Long, what: String): T {
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            f.cancel(true)
            throw IOException("Steam did not answer ($what) within ${timeoutMs / 1000} s")
        } catch (e: ExecutionException) {
            val c = e.cause
            throw IOException("$what: ${c?.message ?: c?.javaClass?.simpleName ?: "failed"}", c)
        } catch (e: java.util.concurrent.CancellationException) {
            throw IOException("The request for the $what was cancelled (connection lost?)")
        }
    }

    private fun <T> awaitDeferred(timeoutMs: Long, what: String, start: (CoroutineScope) -> Deferred<T>): T {
        val scope = session.scope ?: throw IOException("The connection to Steam is closed.")
        val d = start(scope)
        try {
            return runBlocking { withTimeout(timeoutMs) { d.await() } }
        } catch (e: TimeoutCancellationException) {
            d.cancel()
            throw IOException("Steam did not answer ($what) within ${timeoutMs / 1000} s")
        } catch (e: java.util.concurrent.CancellationException) {
            throw IOException("The request for the $what was cancelled (connection lost?)")
        }
    }

    override fun reset() {
        session.close()
        servers = null
        tokens.clear()
        tokenFailures.set(0)
    }

    override fun close() {
        reset()
        workshopDepots.clear()
        depotKeys.clear()
    }

    companion object {
        private const val CM_TRIES = 3
        private const val MAX_TOKEN_FAILURES = 3
    }
}
