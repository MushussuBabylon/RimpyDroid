package com.rimpydroid.manager.steam

import `in`.dragonbra.javasteam.steam.cdn.Server
import `in`.dragonbra.javasteam.types.ChunkData
import java.util.concurrent.Semaphore

/**
 * Limits how many chunks are in flight at the same moment, over all mods that download at once. Every chunk that
 * is being downloaded needs a good amount of memory (the download, the decrypted copy and the unpacked data), so
 * this number has to stay low enough for the heap of the phone, however many mods run in parallel.
 *
 * The limit can be changed while downloads run; chunks that are already running finish normally. Waiting is
 * first come, first served, and it can be interrupted (cancelling a download does that).
 */
class ChunkGate(initialLimit: Int) {

    private class Permits(n: Int) : Semaphore(n, true) {
        fun shrink(by: Int) {
            reducePermits(by)
        }
    }

    private val permits = Permits(initialLimit.coerceAtLeast(1))
    private var limit = initialLimit.coerceAtLeast(1)

    @Synchronized
    fun setLimit(newLimit: Int) {
        val n = newLimit.coerceAtLeast(1)
        if (n > limit) permits.release(n - limit)
        if (n < limit) permits.shrink(limit - n)
        limit = n
    }

    /** Waits for a free place. Throws [InterruptedException] when the thread is interrupted meanwhile. */
    fun acquire() {
        permits.acquire()
    }

    fun release() {
        permits.release()
    }
}

/** A backend whose chunk downloads go through a [ChunkGate]. Everything else is passed on unchanged. */
class GatedBackend(private val inner: SteamBackend, private val gate: ChunkGate) : SteamBackend by inner {

    override fun downloadChunk(
        depotId: Int,
        chunk: ChunkData,
        server: Server,
        dest: ByteArray,
        depotKey: ByteArray,
        token: String?,
    ): Int {
        gate.acquire()
        try {
            return inner.downloadChunk(depotId, chunk, server, dest, depotKey, token)
        } finally {
            gate.release()
        }
    }
}
