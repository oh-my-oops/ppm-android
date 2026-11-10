package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.FeedKind
import com.hienlh.ppm.client.FeedRow
import com.hienlh.ppm.client.FeedUnsupported
import com.hienlh.ppm.client.feed
import com.hienlh.ppm.client.readAllFeed
import com.hienlh.ppm.client.setFeedRead
import com.hienlh.ppm.protocol.SessionUnread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Persisted notification feed state backed by `/api/notifications/feed` and kept in sync across
 * devices via `notifications:feed_changed` on [GlobalEvents].
 *
 * When connected to an older server that returns HTTP 404 ([FeedUnsupported]), [supported] becomes
 * `false` and [rows] seamlessly falls back to [GlobalEvents.unread] without surfacing an error.
 */
class NotificationFeed(
    private val api: ApiClient,
    private val events: GlobalEvents? = null,
    parent: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))

    private val _rows = MutableStateFlow<List<FeedRow>>(emptyList())

    /** Newest-first notification rows from the server feed, or fallback session-unread rows. */
    val rows: StateFlow<List<FeedRow>> = _rows.asStateFlow()

    private val _supported = MutableStateFlow(true)

    /** `true` when the server implements `/api/notifications/feed`; `false` after HTTP 404 fallback. */
    val supported: StateFlow<Boolean> = _supported.asStateFlow()

    private val _hasMore = MutableStateFlow(false)

    /** Whether additional older rows can be fetched with [loadMore]. */
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _loading = MutableStateFlow(false)

    /** Whether a feed request is currently in flight. */
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private var hasLoadedOnce = false
    private var currentLimit = DEFAULT_LIMIT

    init {
        events?.onNotificationFeedChanged {
            load(limit = currentLimit)
        }
        events?.onGlobalReady {
            if (hasLoadedOnce) {
                load(limit = currentLimit)
            }
        }
    }

    /**
     * Loads the first page if this feed has not been loaded yet.
     */
    fun ensureLoaded(limit: Int = DEFAULT_LIMIT): Job {
        if (hasLoadedOnce) return Job().apply { complete() }
        return load(limit = limit)
    }

    /**
     * Fetches the notification feed asynchronously in [scope].
     */
    fun load(limit: Int = DEFAULT_LIMIT, before: Long? = null): Job {
        hasLoadedOnce = true
        currentLimit = limit
        return scope.launch {
            try {
                refresh(limit = limit, before = before)
            } catch (err: CancellationException) {
                throw err
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Fetches the next page of older notifications using the oldest loaded row's `createdAt`.
     */
    fun loadMore(limit: Int = DEFAULT_LIMIT): Job {
        if (!_supported.value || !_hasMore.value) return Job().apply { complete() }
        val oldest = _rows.value.minOfOrNull { it.createdAt } ?: return Job().apply { complete() }
        return load(limit = limit, before = oldest)
    }

    /**
     * Fetches the notification feed and updates [rows], [supported], and [hasMore].
     * Catches [FeedUnsupported] (HTTP 404) and switches to fallback mode backed by [GlobalEvents.unread].
     */
    suspend fun refresh(limit: Int = DEFAULT_LIMIT, before: Long? = null) {
        hasLoadedOnce = true
        currentLimit = limit
        _loading.value = true
        try {
            val page = api.feed(limit = limit, before = before)
            _supported.value = true
            _hasMore.value = page.hasMore
            _rows.value = if (before == null) {
                page.rows
            } else {
                (_rows.value + page.rows).distinctBy { it.id }
            }
        } catch (err: FeedUnsupported) {
            switchToFallback()
        } finally {
            _loading.value = false
        }
    }

    /**
     * Marks a notification row read or unread (`POST /api/notifications/feed/:id/read` or `/unread`).
     */
    fun setRead(id: Long, read: Boolean): Job = scope.launch {
        try {
            setReadAwait(id, read)
        } catch (err: CancellationException) {
            throw err
        } catch (_: Exception) {
        }
    }

    fun setRead(id: Int, read: Boolean): Job = setRead(id.toLong(), read)

    /**
     * Suspending version of [setRead] for callers and tests that await completion.
     */
    suspend fun setReadAwait(id: Long, read: Boolean): FeedRow? {
        val previous = _rows.value
        val existing = previous.firstOrNull { it.id == id }
        if (existing != null) {
            _rows.value = previous.map { if (it.id == id) it.copy(read = read) else it }
        }
        if (!_supported.value) {
            val sessionId = existing?.sessionId
            if (sessionId != null) {
                if (read) {
                    events?.markRead(sessionId)
                } else {
                    events?.markUnread(sessionId, existing.title)
                }
            }
            return _rows.value.firstOrNull { it.id == id }
        }
        return try {
            val updated = api.setFeedRead(id, read)
            val current = _rows.value
            _rows.value = if (current.any { it.id == id }) {
                current.map { if (it.id == id) updated else it }
            } else {
                current
            }
            if (read && updated.sessionId != null) {
                events?.clearSessionUnreadLocal(updated.sessionId!!)
            }
            updated
        } catch (err: FeedUnsupported) {
            switchToFallback()
            val sessionId = existing?.sessionId
            if (sessionId != null) {
                if (read) {
                    events?.markRead(sessionId)
                } else {
                    events?.markUnread(sessionId, existing.title)
                }
            }
            null
        } catch (err: CancellationException) {
            throw err
        } catch (err: Exception) {
            _rows.value = previous
            throw err
        }
    }

    /**
     * Marks all notifications read (`POST /api/notifications/feed/read-all`), or falls back to
     * [GlobalEvents.clearAll] when the server does not support the notification feed.
     */
    fun readAll(): Job = scope.launch {
        try {
            readAllAwait()
        } catch (err: CancellationException) {
            throw err
        } catch (_: Exception) {
        }
    }

    /** Alias for [readAll] used by the "Clear all" header button. */
    fun clearAll(): Job = readAll()

    /**
     * Suspending version of [readAll].
     */
    suspend fun readAllAwait() {
        if (!_supported.value) {
            _rows.value = emptyList()
            events?.clearAll()
            return
        }
        val previous = _rows.value
        _rows.value = previous.map { it.copy(read = true) }
        try {
            api.readAllFeed()
            events?.clearUnreadLocal()
        } catch (err: FeedUnsupported) {
            switchToFallback()
            _rows.value = emptyList()
            events?.clearAll()
        } catch (err: CancellationException) {
            throw err
        } catch (err: Exception) {
            _rows.value = previous
            throw err
        }
    }

    internal fun onUnreadChanged(unreadMap: Map<String, SessionUnread>) {
        if (!_supported.value) {
            _rows.value = fallbackRowsFromUnread(unreadMap, events?.project, clock())
        }
    }

    internal fun markSessionReadLocal(sessionId: String): Boolean {
        val current = _rows.value
        if (current.none { it.sessionId == sessionId && !it.read }) return false
        _rows.value = if (_supported.value) {
            current.map { if (it.sessionId == sessionId) it.copy(read = true) else it }
        } else {
            current.filterNot { it.sessionId == sessionId }
        }
        return true
    }

    private fun switchToFallback() {
        _supported.value = false
        _hasMore.value = false
        _rows.value = fallbackRowsFromUnread(
            unreadMap = events?.unread?.value.orEmpty(),
            defaultProject = events?.project,
            nowMs = clock(),
        )
    }

    companion object {
        const val DEFAULT_LIMIT = 50

        fun fallbackRowsFromUnread(
            unreadMap: Map<String, SessionUnread>,
            defaultProject: String? = null,
            nowMs: Long = 0L,
        ): List<FeedRow> {
            if (unreadMap.isEmpty()) return emptyList()
            return unreadMap.entries.mapIndexed { index, (sessionId, entry) ->
                val project = entry.projectName.ifEmpty { defaultProject.orEmpty().ifEmpty { "Unknown" } }
                val title = entry.sessionTitle?.takeIf { it.isNotEmpty() } ?: "${sessionId.take(12)}..."
                FeedRow(
                    id = -(index + 1).toLong(),
                    kind = FeedKind.fromWire(entry.type),
                    project = project,
                    sessionId = sessionId,
                    scheduleId = null,
                    title = title,
                    body = "",
                    createdAt = nowMs,
                    read = false,
                )
            }
        }
    }
}

/**
 * Project section inside the phone notifications sheet or tablet notification bell card.
 */
data class NotificationProjectGroup(
    val project: String,
    val rows: List<FeedRow>,
)

/**
 * Groups feed rows by project name while preserving the server's newest-first ordering.
 */
fun groupFeedRowsByProject(rows: List<FeedRow>): List<NotificationProjectGroup> {
    if (rows.isEmpty()) return emptyList()
    return rows
        .groupBy { it.project.ifBlank { "Unknown" } }
        .map { (project, groupRows) -> NotificationProjectGroup(project = project, rows = groupRows) }
}

/**
 * Formats the unread count subtitle in the notifications header (`"4 unread sessions"` / `"No unread"`).
 */
fun formatUnreadSummary(unreadCount: Int): String =
    if (unreadCount > 0) "$unreadCount unread session${if (unreadCount == 1) "" else "s"}" else "No unread"

/**
 * Human-readable label for a [FeedKind] matching the Phone/Tablet specs.
 */
fun FeedKind.displayLabel(): String = when (this) {
    FeedKind.APPROVAL -> "Approval waiting"
    FeedKind.QUESTION -> "Question waiting"
    FeedKind.DONE -> "Turn complete"
    FeedKind.SCHEDULE -> "Schedule"
    FeedKind.OTHER -> "Notification"
}

/**
 * Compact relative time string (`"now"`, `"2m"`, `"1h"`, `"3d"`) for a notification row.
 */
fun formatNotificationTime(createdAtMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (createdAtMs <= 0L) return ""
    val diffSec = maxOf(0L, (nowMs - createdAtMs) / 1_000L)
    return when {
        diffSec < 60L -> "now"
        diffSec < 3_600L -> "${diffSec / 60L}m"
        diffSec < 86_400L -> "${diffSec / 3_600L}h"
        else -> "${diffSec / 86_400L}d"
    }
}
