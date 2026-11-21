package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.SessionInfo
import java.time.Instant

/**
 * When a chat counts as archived, and the history list's status filter (`src/shared/session-archive.ts`).
 * A chat is archived by hand, or once it has been idle the device's "Auto-archive" days; a pinned
 * chat, the manager chat and one un-archived by hand (`kept`) never are.
 */
enum class SessionStatusFilter(val wire: String, val label: String) {
    ACTIVE("active", "Active"),
    RUNNING("running", "Running"),
    UNREAD("unread", "Unread"),
    ARCHIVED("archived", "Archived"),
    ALL("all", "All"),
    ;

    companion object {
        fun of(wire: String?): SessionStatusFilter? = entries.firstOrNull { it.wire == wire }
    }
}

object SessionArchive {
    /** The auto-archive choices beside the filter; 0 = never (`AUTO_ARCHIVE_DAYS`). */
    val AUTO_ARCHIVE_DAYS = listOf(0, 7, 14, 30, 90)

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Archived by hand, or idle at least [autoDays] days (0 = never), unless pinned, the manager chat or `kept`. */
    fun isArchived(s: SessionInfo, autoDays: Int, now: Instant = Instant.now()): Boolean {
        if (s.archive == "archived") return true
        if (s.archive == "kept" || s.pinned || s.manager || autoDays <= 0) return false
        val at = s.updatedAt ?: s.createdAt ?: return false
        return now.toEpochMilli() - at.toEpochMilli() >= autoDays * DAY_MS
    }

    /** Whether a session belongs under [filter] (`matchesStatusFilter`). */
    fun matches(
        filter: SessionStatusFilter,
        s: SessionInfo,
        autoDays: Int,
        running: (String) -> Boolean,
        unread: (String) -> Boolean,
        now: Instant = Instant.now(),
    ): Boolean {
        val archived = isArchived(s, autoDays, now)
        return when (filter) {
            SessionStatusFilter.ALL -> true
            SessionStatusFilter.ARCHIVED -> archived
            SessionStatusFilter.RUNNING -> running(s.id)
            SessionStatusFilter.UNREAD -> unread(s.id)
            SessionStatusFilter.ACTIVE -> !archived || running(s.id) || unread(s.id)
        }
    }

    /**
     * The list under [filter] (`useSessionHistory`'s `filteredSessions`). A search under Active
     * shows archived chats too: a chat looked for by name is wanted wherever it was put.
     */
    fun filter(
        sessions: List<SessionInfo>,
        filter: SessionStatusFilter,
        autoDays: Int,
        searching: Boolean,
        running: (String) -> Boolean,
        unread: (String) -> Boolean,
        now: Instant = Instant.now(),
    ): List<SessionInfo> {
        val status = if (searching && filter == SessionStatusFilter.ACTIVE) SessionStatusFilter.ALL else filter
        if (status == SessionStatusFilter.ALL) return sessions
        return sessions.filter { matches(status, it, autoDays, running, unread, now) }
    }

    /** What the empty list says (`No sessions yet`, `No archived sessions`). */
    fun emptyText(filter: SessionStatusFilter, searching: Boolean): String = when {
        searching -> "No matching sessions"
        filter == SessionStatusFilter.ACTIVE || filter == SessionStatusFilter.ALL -> "No sessions yet"
        else -> "No ${filter.label.lowercase()} sessions"
    }
}
