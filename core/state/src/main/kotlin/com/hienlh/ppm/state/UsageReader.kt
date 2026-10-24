package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.UsageInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What one chat's usage chip reports on (`usageScopeKey`): the session's binding once it has one,
 * else the account the tab claimed for its provider.
 */
data class UsageScope(val project: String, val providerId: String, val sessionId: String?, val pickedAccountId: String?) {
    /** A tab with no session previews the account it claimed; a session's own binding wins once it exists. */
    val previewAccountId: String?
        get() = if (sessionId == null) pickedAccountId else null
}

/** One reading of a scope, and when the server last read the provider for it. */
data class UsageSnapshot(val scope: UsageScope, val usage: UsageInfo, val fetchedAt: String?) {
    companion object {
        /**
         * `snapshotFor`: a reading describes the scope only when it reports the account the scope
         * previews; anything else shows as empty rather than as another account's figures.
         */
        fun of(scope: UsageScope, data: UsageInfo?, fetchedAt: String?): UsageSnapshot {
            val matches = scope.pickedAccountId == null || data?.activeAccountId == scope.pickedAccountId
            return UsageSnapshot(scope, if (matches) data ?: UsageInfo() else UsageInfo(), if (matches) fetchedAt else null)
        }
    }
}

/**
 * The readings every chat on one server shares (use-usage.ts's module cache), and when each was
 * seeded from a `/chat/prepare`. Main thread only.
 */
class UsageCache(private val now: () -> Long = System::currentTimeMillis) {
    private val snapshots = HashMap<UsageScope, UsageSnapshot>()
    private val seededAt = HashMap<UsageScope, Long>()

    operator fun get(scope: UsageScope): UsageSnapshot? = snapshots[scope]

    fun put(snapshot: UsageSnapshot) {
        snapshots[snapshot.scope] = snapshot
    }

    /** `seedUsage`: a preparation's reading, ahead of the chip's own first read, filtered by account as a read is. */
    fun seed(scope: UsageScope, usage: UsageInfo, fetchedAt: String?) {
        put(UsageSnapshot.of(scope, usage, fetchedAt))
        seededAt[scope] = now()
    }

    /** The scope's reading when a seed put it there under [SEED_FRESH_MILLIS] ago: trusted over a read of its own. */
    fun freshSeed(scope: UsageScope): UsageSnapshot? {
        val at = seededAt[scope] ?: return null
        return snapshots[scope]?.takeIf { now() - at < SEED_FRESH_MILLIS }
    }

    companion object {
        const val SEED_FRESH_MILLIS = 30_000L
    }
}

/**
 * One chat's usage reading (`useUsage`): read half a second after it starts — or, while the tab's
 * `/chat/prepare` is out, taken from that answer when it seeded one fresh — then every two minutes.
 * Each answer replaces the reading whole, and only the newest request's answer lands. Main thread
 * only.
 */
class UsageReader(
    private val cache: UsageCache,
    private val fetch: suspend (scope: UsageScope, refresh: Boolean) -> UsageInfo?,
) {
    private val _snapshot = MutableStateFlow<UsageSnapshot?>(null)
    private val _loading = MutableStateFlow(false)
    val snapshot: StateFlow<UsageSnapshot?> = _snapshot.asStateFlow()
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** The scope [run] reads, while it runs. */
    private var current: UsageScope? = null
    private var request = 0

    /** What the chip shows for [scope]: this reader's reading of it, else the shared one, never the scope before. */
    fun shown(scope: UsageScope?, snapshot: UsageSnapshot?): UsageSnapshot? = when {
        scope == null -> null
        snapshot?.scope == scope -> snapshot
        else -> cache[scope]
    }

    /** Reads [scope] until cancelled; [prepare] is the tab's preparation still out, if any. */
    suspend fun run(scope: UsageScope, prepare: Deferred<*>?) {
        current = scope
        _snapshot.value = null
        _loading.value = false
        try {
            coroutineScope {
                launch {
                    if (prepare != null) {
                        try {
                            prepare.await()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                        }
                        cache.freshSeed(scope)?.let {
                            _snapshot.value = it
                            return@launch
                        }
                    }
                    delay(FIRST_READ_MILLIS)
                    read(refresh = false)
                }
                while (true) {
                    delay(POLL_MILLIS)
                    launch { read(refresh = false) }
                }
            }
        } finally {
            // An answer still out belongs to a scope no longer shown.
            request++
            if (current == scope) current = null
        }
    }

    /**
     * Reads the current scope: [refresh] asks the server to go back to the provider first (the
     * panel's refresh); without it, the cached reading — for a session whose account just changed.
     */
    suspend fun read(refresh: Boolean) {
        val scope = current ?: return
        val mine = ++request
        _loading.value = true
        try {
            val data = fetch(scope, refresh)
            if (mine != request) return
            val next = UsageSnapshot.of(scope, data, data?.lastFetchedAt)
            cache.put(next)
            _snapshot.value = next
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        } finally {
            if (mine == request) _loading.value = false
        }
    }

    companion object {
        const val FIRST_READ_MILLIS = 500L
        const val POLL_MILLIS = 120_000L
    }
}
