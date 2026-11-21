package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.SessionUnread

/**
 * What an unread session was left on, as the bell colours it (`notificationTint` in
 * notification-store.ts): an approval (also a session tool's card), a question, a finished turn,
 * and anything else, which the web tints as it tints an approval.
 */
enum class UnreadKind(val label: String) {
    APPROVAL("Approval waiting"),
    QUESTION("Question waiting"),
    DONE("Turn complete"),
    OTHER("Notification"),
    ;

    companion object {
        fun of(type: String?): UnreadKind = when (type) {
            "approval_request", "collab_approval" -> APPROVAL
            "question" -> QUESTION
            "done" -> DONE
            else -> OTHER
        }
    }
}

/** One row of the bell: an unread session. */
data class UnreadBellRow(
    val sessionId: String,
    val title: String,
    val kind: UnreadKind,
)

/** A project heading of the bell and its unread sessions. */
data class UnreadBellGroup(
    val project: String,
    val rows: List<UnreadBellRow>,
)

/** The row's text: the session's title, else the first 12 characters of its id and "...". */
fun unreadBellTitle(sessionId: String, sessionTitle: String?): String =
    sessionTitle?.takeIf { it.isNotEmpty() } ?: "${sessionId.take(12)}..."

/**
 * The bell's list (`notification-bell-popover.tsx`): the unread sessions grouped by their project
 * in the order they arrived, a session filed under no project in "Unknown".
 */
fun groupUnreadSessions(unread: Map<String, SessionUnread>): List<UnreadBellGroup> {
    val groups = LinkedHashMap<String, MutableList<UnreadBellRow>>()
    for ((sessionId, entry) in unread) {
        val key = entry.projectName.ifEmpty { "Unknown" }
        groups.getOrPut(key) { mutableListOf() } +=
            UnreadBellRow(sessionId, unreadBellTitle(sessionId, entry.sessionTitle), UnreadKind.of(entry.type))
    }
    return groups.map { (project, rows) -> UnreadBellGroup(project, rows) }
}
