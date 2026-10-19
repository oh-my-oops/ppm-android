package com.hienlh.ppm.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonElement

/**
 * GETs still waiting for an answer, by token and URL. A second caller of the same URL waits on
 * the request already out instead of sending its own: the web measured four identical requests
 * within 2 ms when one chat tab opened (`api-client.ts`).
 *
 * Not a cache. An entry goes as soon as its request settles, and one older than
 * [PpmHttp.GET_TIMEOUT_SECONDS] is no longer shared, so a request that stalls cannot hold back
 * every later caller of its URL.
 */
internal object InflightGets {
    private class Entry(val request: Deferred<JsonElement>, val startedAt: Long)

    private val entries = HashMap<String, Entry>()

    /** The request outlives a caller that gives up: others may be waiting on it. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun value(key: String, start: suspend () -> JsonElement): JsonElement {
        val entry = synchronized(entries) {
            val now = System.nanoTime()
            entries[key]?.takeIf { now - it.startedAt < PpmHttp.GET_TIMEOUT_SECONDS * 1_000_000_000 }
                ?: Entry(scope.async { start() }, now).also { created ->
                    entries[key] = created
                    created.request.invokeOnCompletion {
                        // Only its own entry: a later request may have taken the key once this one aged out.
                        synchronized(entries) { if (entries[key] === created) entries.remove(key) }
                    }
                }
        }
        return entry.request.await()
    }
}
