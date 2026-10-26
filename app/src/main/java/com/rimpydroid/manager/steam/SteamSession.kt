package com.rimpydroid.manager.steam

import com.rimpydroid.manager.AppLog
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.networking.steam3.ProtocolTypes
import `in`.dragonbra.javasteam.steam.cdn.Client
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOffCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration
// JavaSteam has its own Consumer interface (for old Android versions); java.util.function.Consumer does not fit.
import `in`.dragonbra.javasteam.util.compat.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One anonymous connection to the Steam network (the "CM" connection that answers questions such as
 * "which servers hold this file?"). Workshop items of RimWorld can be downloaded without an account:
 * no password, no Steam Guard, nothing to configure.
 *
 * The connection is only needed for those questions. The files themselves come over plain HTTPS from
 * Steam's download servers, so a dropped connection does not interrupt a transfer: [isReady] simply
 * turns false and whoever needs the next answer calls [connect] again.
 *
 * JavaSteam only makes progress while somebody calls [CallbackManager.runWaitCallbacks], so every
 * connection owns a small daemon thread that does exactly that.
 *
 * Connecting is tried in rounds: first with the library defaults (TCP and WebSocket), then over
 * WebSocket only (plain HTTPS port 443, which almost every network lets through), then TCP only.
 * Inside a round the library moves on to another Steam server after every failure.
 */
class SteamSession(
    private val rounds: List<Round> = DEFAULT_ROUNDS,
    private val roundTimeoutMs: Long = ROUND_TIMEOUT_MS,
    /** Base pause before the next Steam server is tried after a failed attempt (a hook for tests). */
    private val retryDelayMs: Long = RETRY_DELAY_MS,
) {

    enum class State { IDLE, CONNECTING, LOGGING_IN, READY, LOST, FAILED, CLOSED }

    /** [protocols] null = library default. [attempts] = how many servers to try before the next round. */
    class Round(val label: String, val protocols: ProtocolTypes?, val attempts: Int)

    @Volatile
    private var link: Link? = null

    /** Bumped by [close]; a [connect] that is still waiting notices it and gives up. */
    @Volatile
    private var generation = 0

    @Volatile
    private var lastState = State.IDLE

    val state: State
        get() = link?.state ?: lastState

    @Volatile
    var error: String? = null
        private set

    /** The connected client, or null while there is no connection attempt. */
    val client: SteamClient?
        get() = link?.client

    /** Scope for coroutine-style JavaSteam calls; cancelled together with the connection. */
    val scope: CoroutineScope?
        get() = link?.scope

    val isReady: Boolean
        get() {
            val l = link ?: return false
            return l.state == State.READY && l.client.isConnected
        }

    /**
     * Connects and logs in anonymously. Blocks until ready. Throws [SteamConnectException] with a readable
     * message when no round succeeded (or when [close] was called meanwhile).
     */
    @Throws(IOException::class)
    fun connect(): SteamClient {
        val gen = synchronized(this) {
            closeLink()
            error = null
            generation
        }
        var lastError: String? = null
        for ((index, round) in rounds.withIndex()) {
            if (generation != gen) throw SteamConnectException("Cancelled")
            val l = try {
                Link(round, index + 1, retryDelayMs)
            } catch (e: Exception) {
                throw SteamConnectException("Could not start the Steam library: ${e.message ?: e.javaClass.simpleName}")
            }
            synchronized(this) {
                if (generation != gen) {
                    l.close()
                    throw SteamConnectException("Cancelled")
                }
                link = l
            }
            AppLog.i("Steam", "Connecting to Steam (${round.label})…")
            val ok = l.open(roundTimeoutMs)
            if (generation != gen || Thread.currentThread().isInterrupted) {
                l.close()
                throw SteamConnectException("Cancelled")
            }
            if (ok) {
                AppLog.i("Steam", "Logged in anonymously (${round.label})")
                return l.client
            }
            lastError = l.error ?: "Timed out while connecting to Steam."
            AppLog.w("Steam", "Round ${index + 1} (${round.label}) failed: $lastError")
            synchronized(this) {
                if (link === l) link = null
                lastState = State.FAILED
            }
            l.close()
        }
        val message = lastError ?: "Could not reach Steam."
        error = message
        throw SteamConnectException(message)
    }

    /** The client for downloading files over HTTPS. Shares the connection's lifetime. */
    @Throws(IOException::class)
    fun cdn(): Client {
        val l = link ?: throw IOException("The connection to Steam is closed.")
        return l.cdn()
    }

    /** Closes the connection and cancels everything waiting for it. Safe to call from any thread, repeatedly. */
    fun close() {
        synchronized(this) {
            generation++
            closeLink()
        }
    }

    private fun closeLink() {
        val l = link
        link = null
        if (l != null) {
            lastState = if (l.state == State.FAILED) State.FAILED else State.CLOSED
            l.close()
        } else if (lastState != State.FAILED) {
            lastState = State.CLOSED
        }
    }

    // ---------------------------------------------------------------------------------------------

    /** One [SteamClient] with its callback thread, for one round of connecting. */
    private class Link(private val round: Round, private val number: Int, private val retryDelayMs: Long) {

        @Volatile
        var state = State.CONNECTING

        @Volatile
        var error: String? = null

        @Volatile
        private var running = true

        @Volatile
        private var attempts = 0

        private val settled = CountDownLatch(1)
        private val http: OkHttpClient = buildHttp()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client: SteamClient
        private val manager: CallbackManager
        private val user: SteamUser
        private val subscriptions = ArrayList<Closeable>()
        private var pump: Thread? = null
        private var cdn: Client? = null

        init {
            val protocols = round.protocols
            val config = SteamConfiguration.create { builder ->
                builder.withHttpClient(http)
                builder.withConnectionTimeout(CONNECT_TIMEOUT_MS)
                if (protocols != null) builder.withProtocolTypes(protocols)
            }
            val c = SteamClient(config)
            client = c
            manager = CallbackManager(c)
            user = c.getHandler<SteamUser>() ?: throw IOException("Steam library is missing its user handler")

            subscriptions.add(manager.subscribe(ConnectedCallback::class.java, Consumer<ConnectedCallback> { onConnected() }))
            subscriptions.add(manager.subscribe(DisconnectedCallback::class.java, Consumer<DisconnectedCallback> { onDisconnected(it) }))
            subscriptions.add(manager.subscribe(LoggedOnCallback::class.java, Consumer<LoggedOnCallback> { onLoggedOn(it) }))
            subscriptions.add(manager.subscribe(LoggedOffCallback::class.java, Consumer<LoggedOffCallback> { onLoggedOff(it) }))
        }

        /** Starts connecting and waits until this round is ready or has given up. */
        fun open(timeoutMs: Long): Boolean {
            val t = Thread({
                // connect() first looks up the list of Steam servers over HTTPS, which can take a long time on a
                // bad network. Doing it on this thread keeps the timeout below honest and lets close() release
                // the caller at once.
                try {
                    client.connect()
                } catch (e: Throwable) {
                    if (running) fail("Could not start connecting to Steam: ${e.message ?: e.javaClass.simpleName}")
                }
                if (!running) {
                    try {
                        client.disconnect() // closed while the lookup was running: do not leave a connection behind
                    } catch (e: Throwable) {
                        // ignore
                    }
                }
                while (running) {
                    try {
                        manager.runWaitCallbacks(500L)
                    } catch (e: Throwable) {
                        if (running) AppLog.e("Steam", "Callback error", e)
                    }
                }
            }, "steam-callbacks-$number")
            t.isDaemon = true
            pump = t
            t.start()

            val finished = try {
                settled.await(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!finished && state != State.READY && error == null) {
                error = "Timed out while connecting to Steam. Check your internet connection."
            }
            return state == State.READY
        }

        @Synchronized
        fun cdn(): Client {
            if (!running) throw IOException("The connection to Steam is closed.")
            var c = cdn
            if (c == null) {
                c = Client(client)
                cdn = c
            }
            return c
        }

        fun close() {
            running = false
            if (state != State.FAILED) state = State.CLOSED
            try {
                scope.cancel()
            } catch (e: Throwable) {
                // already cancelled
            }
            try {
                client.disconnect()
            } catch (e: Throwable) {
                // already gone
            }
            for (s in subscriptions) {
                try {
                    s.close()
                } catch (e: Throwable) {
                    // ignore
                }
            }
            subscriptions.clear()
            try {
                cdn?.close() // also stops the HTTP client of this connection, see below
            } catch (e: Throwable) {
                // ignore
            }
            try {
                http.connectionPool.evictAll()
                http.dispatcher.executorService.shutdown()
            } catch (e: Throwable) {
                // ignore
            }
            settled.countDown()
            val t = pump
            pump = null
            if (t != null && t !== Thread.currentThread()) {
                t.interrupt() // it may be sleeping between two connection attempts
                try {
                    t.join(1500)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        // ---- callbacks (run on the pump thread) ------------------------------------------------

        private fun onConnected() {
            if (!running) return
            state = State.LOGGING_IN
            AppLog.i("Steam", "Connected, logging in anonymously")
            user.logOnAnonymous()
        }

        private fun onDisconnected(cb: DisconnectedCallback) {
            if (!running) return
            // The library reports its own disconnects as well (connect() disconnects first); only a real drop counts.
            if (cb.isUserInitiated) return
            when (state) {
                State.CONNECTING, State.LOGGING_IN -> retryOrFail("The connection dropped")
                State.READY -> {
                    state = State.LOST
                    AppLog.w("Steam", "The connection to Steam was lost; it will be re-opened when needed")
                }
                else -> {}
            }
        }

        private fun onLoggedOn(cb: LoggedOnCallback) {
            if (!running) return
            if (cb.result == EResult.OK) {
                state = State.READY
                settled.countDown()
            } else {
                retryOrFail("Steam did not accept the anonymous login (${cb.result})")
            }
        }

        private fun onLoggedOff(cb: LoggedOffCallback) {
            if (!running) return
            AppLog.w("Steam", "Logged off: ${cb.result}")
            if (state == State.READY) state = State.LOST
        }

        private fun retryOrFail(reason: String) {
            attempts++
            if (attempts >= round.attempts) {
                fail("$reason. Could not reach Steam; some networks block it. Try another network or Wi-Fi.")
                return
            }
            AppLog.w("Steam", "$reason (attempt $attempts of ${round.attempts}), trying another server")
            try {
                Thread.sleep(retryDelayMs * attempts)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            if (!running) return
            state = State.CONNECTING
            client.connect()
        }

        private fun fail(message: String) {
            AppLog.e("Steam", message)
            error = message
            state = State.FAILED
            settled.countDown()
        }

        private fun buildHttp(): OkHttpClient {
            // JavaSteam downloads every chunk with this client; its default of 5 parallel requests per host
            // would throttle a download that uses 8 or more connections.
            val dispatcher = Dispatcher()
            dispatcher.maxRequests = 64
            dispatcher.maxRequestsPerHost = 32
            return OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val RETRY_DELAY_MS = 1_200L
        private const val ROUND_TIMEOUT_MS = 30_000L

        val DEFAULT_ROUNDS: List<Round> = listOf(
            Round("default", null, 3),
            Round("WebSocket", ProtocolTypes.WEB_SOCKET, 3),
            Round("TCP", ProtocolTypes.TCP, 2),
        )
    }
}
