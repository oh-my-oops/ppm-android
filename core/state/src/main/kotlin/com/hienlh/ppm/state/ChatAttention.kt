package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.ApprovalRequest
import com.hienlh.ppm.protocol.SessionCollabApprovalInput
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.double
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What a chat that is not in front of the user calls for (use-chat.ts): a sound when its turn
 * ends, and a sound and a toast that stays when it waits for an approval or an answer.
 */
sealed interface ChatAttention {
    /** An approval or a question came in live (`approval_request`, never its replay). */
    data class Asked(val request: ApprovalRequest) : ChatAttention

    /**
     * Nothing waits any more (`approval_resolved`, or this chat sent, answered or stopped): the
     * toast goes.
     */
    data object Answered : ChatAttention

    /** The turn ended (`done` while it still ran). */
    data object Done : ChatAttention
}

/** The toast and the sound an approval or a question gets (`approval_request` in use-chat.ts). */
data class AttentionNotice(val title: String, val description: String, val warning: Boolean, val sound: String) {
    companion object {
        fun of(request: ApprovalRequest, projectName: String, sessionId: String): AttentionNotice {
            val question = request.tool == "AskUserQuestion"
            val connection = dbConnectionName(request)
            return AttentionNotice(
                title = when {
                    question -> "AI has a question"
                    connection != null -> "AI wants to change $connection"
                    else -> "${request.tool} needs permission"
                },
                description = projectName.ifEmpty { "Session ${sessionId.take(8)}" },
                warning = !question,
                sound = if (question) NotificationTones.QUESTION else NotificationTones.APPROVAL,
            )
        }

        /** A `db_execute` approval's connection (`dbExecuteApprovalInput`); null for any other request. */
        private fun dbConnectionName(request: ApprovalRequest): String? {
            if (request.tool != "ppm:db_execute") return null
            val input = request.input
            if (input["connectionId"].double == null || input["sql"].string == null) return null
            return input["connectionName"].string
        }
    }
}

/**
 * Classification of cards that pause a chat turn and wait on the user
 * (`designs/ppm-mobile-tablet-redesign/phone/1-chat/index.html`, Section 4 D1–D6).
 */
enum class WaitingCardKind {
    /** Standard tool permission (`Tool Approval Required`, e.g. `Bash`, `Edit`, `Write`). */
    TOOL_APPROVAL,

    /** `AskUserQuestion` single or multi-question card. */
    ASK_USER_QUESTION,

    /** `ppm:db_execute` write-SQL approval with PPM password prompt. */
    DB_EXECUTE,

    /** Session collaboration: `"The AI wants to create a session"`. */
    CREATE_SESSION,

    /** Session collaboration: `"The AI wants to message a session with more rights"`. */
    ELEVATE_SESSION,

    /** Session collaboration: `"These sessions reached their message limit"`. */
    RATE_LIMIT,
}

/**
 * State of the floating "Needs you" pill (`NeedsYouPill`, Requirement 4.2, Phone 1 D6) and
 * waiting-card keyboard focus behavior (`cardKeyboard`, Phone 1 D2 & D3).
 */
data class NeedsYouState(
    /** True when a waiting card is active and scrolled out of view (`!atBottom`). */
    val visible: Boolean,
    val kind: WaitingCardKind?,
    val label: String,
    val warning: Boolean,
    val requestId: String?,
    /**
     * True when the waiting card's own text input (`AskUserQuestion` "Other..." field or
     * `ppm:db_execute` password field) has keyboard focus, causing the bottom composer and
     * context chip row to step aside so the card sits directly above the keyboard.
     */
    val hidesComposerOnKeyboard: Boolean = false,
) {
    companion object {
        val None = NeedsYouState(
            visible = false,
            kind = null,
            label = "",
            warning = false,
            requestId = null,
            hidesComposerOnKeyboard = false,
        )
    }
}

/** Determines the [WaitingCardKind] for this [ApprovalRequest]. */
fun ApprovalRequest.waitingCardKind(): WaitingCardKind {
    val toolName = tool.trim()
    val kindField = input["kind"].string?.trim().orEmpty()
    val reasonField = input["reason"].string?.trim().orEmpty()
    return when {
        toolName == "AskUserQuestion" -> WaitingCardKind.ASK_USER_QUESTION
        toolName == "ppm:db_execute" || toolName.endsWith("__db_execute") -> WaitingCardKind.DB_EXECUTE
        toolName == "ppm:create_session" ||
            toolName.endsWith("__create_session") ||
            kindField == "create_session" ||
            reasonField == "create_session" -> WaitingCardKind.CREATE_SESSION
        toolName == "ppm:elevate_session" ||
            kindField == "elevate_session" ||
            reasonField == "permission_elevation" ||
            (
                (toolName == "ppm:send_message" || toolName.endsWith("__send_message")) &&
                    input["recipientPermissionMode"].string != null
                ) -> WaitingCardKind.ELEVATE_SESSION
        toolName == "ppm:rate_limit" ||
            kindField == "rate_limit" ||
            reasonField == "rate_limit" ||
            input["turns"].double != null -> WaitingCardKind.RATE_LIMIT
        else -> WaitingCardKind.TOOL_APPROVAL
    }
}

/**
 * Returns the exact label shown inside `NeedsYouPill` when [request] is scrolled out of view
 * (`designs/ppm-mobile-tablet-redesign/phone/1-chat/index.html`, Section 4 D6).
 */
fun needsYouPillLabel(request: ApprovalRequest): String = when (request.waitingCardKind()) {
    WaitingCardKind.ASK_USER_QUESTION -> "AI has a question"
    WaitingCardKind.DB_EXECUTE -> "Approve a database change"
    WaitingCardKind.CREATE_SESSION -> "The AI wants to create a session"
    WaitingCardKind.ELEVATE_SESSION -> "The AI wants to message a session with more rights"
    WaitingCardKind.RATE_LIMIT -> "These sessions reached their message limit"
    WaitingCardKind.TOOL_APPROVAL -> {
        // A schedule card names what it asks, as its heading does.
        val schedule = SessionCollabApprovalInput.from(request.tool, request.input) as? SessionCollabApprovalInput.Schedule
        schedule?.heading ?: "Waiting for approval · ${request.tool.trim().ifEmpty { "Tool" }}"
    }
}

/**
 * Resolves [NeedsYouState] from [pendingApproval], scroll position [atBottom], and whether
 * a waiting card text input currently holds keyboard focus ([cardKeyboardFocused]).
 */
fun resolveNeedsYouState(
    pendingApproval: ApprovalRequest?,
    atBottom: Boolean,
    cardKeyboardFocused: Boolean = false,
): NeedsYouState {
    if (pendingApproval == null) return NeedsYouState.None
    val kind = pendingApproval.waitingCardKind()
    val hasCardTextInput = kind == WaitingCardKind.ASK_USER_QUESTION || kind == WaitingCardKind.DB_EXECUTE
    return NeedsYouState(
        visible = !atBottom,
        kind = kind,
        label = needsYouPillLabel(pendingApproval),
        warning = kind != WaitingCardKind.ASK_USER_QUESTION,
        requestId = pendingApproval.requestId,
        hidesComposerOnKeyboard = cardKeyboardFocused && hasCardTextInput,
    )
}

/** Convenience accessor on [ChatTranscript] for the current [NeedsYouState]. */
fun ChatTranscript.needsYouState(
    atBottom: Boolean,
    cardKeyboardFocused: Boolean = false,
): NeedsYouState = resolveNeedsYouState(
    pendingApproval = pendingApproval,
    atBottom = atBottom,
    cardKeyboardFocused = cardKeyboardFocused,
)

/**
 * Day-group bucket classification for chat history (`designs/ppm-mobile-tablet-redesign/phone/1-chat/index.html`,
 * Section 7 G1, Requirement 4.3).
 */
enum class SessionDayBucketKind {
    PINNED,
    TODAY,
    YESTERDAY,
    DATED,
}

/**
 * A group of sessions in the phone Chat History list (`Pinned`, `Today`, `Yesterday`, or a date header).
 */
data class SessionDayGroup(
    val key: String,
    val label: String,
    val kind: SessionDayBucketKind,
    val date: LocalDate? = null,
    val sessions: List<SessionInfo>,
)

/**
 * Row action types available via left-swipe (`76 dp` buttons) and long-press sheet in phone Chat History
 * (`designs/ppm-mobile-tablet-redesign/phone/1-chat/index.html`, Section 7 G2).
 */
enum class SessionRowActionKind {
    PIN,
    UNPIN,
    RENAME,
    DELETE,
    MARK_UNREAD,
    SET_TAG,
}

/**
 * Descriptor for a single row action on a [SessionInfo] item.
 */
data class SessionRowAction(
    val kind: SessionRowActionKind,
    val label: String,
    val destructive: Boolean = false,
)

/**
 * Live status state displayed on a session row (`Running · now`, `Needs you · 2 min ago`, or relative time).
 */
enum class SessionLiveState {
    IDLE,
    RUNNING,
    NEEDS_YOU,
    UNREAD,
}

/**
 * Pure day-grouping and row-action helpers for Chat History (Requirement 4.3, Phone 1 Section 7 G1–G3).
 */
object HistoryByDay {
    private val sameYearFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val otherYearFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

    /**
     * Groups [sessions] into:
     * 1. `Pinned` (at top, if any pinned session exists), sorted by `lastActive` descending.
     * 2. `Today`, `Yesterday`, and dated groups (`MMM d` or `MMM d, yyyy`) for unpinned sessions,
     *    evaluated in [zoneId] relative to [now].
     */
    fun groupSessionsByDay(
        sessions: List<SessionInfo>,
        now: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<SessionDayGroup> {
        if (sessions.isEmpty()) return emptyList()
        val today = now.atZone(zoneId).toLocalDate()
        val yesterday = today.minusDays(1)

        val comparator = compareByDescending<SessionInfo> { it.lastActive ?: Instant.EPOCH }
            .thenBy { it.id }

        val pinned = sessions.filter { it.pinned }.sortedWith(comparator)
        val unpinned = sessions.filterNot { it.pinned }.sortedWith(comparator)

        val groups = mutableListOf<SessionDayGroup>()
        if (pinned.isNotEmpty()) {
            groups += SessionDayGroup(
                key = "pinned",
                label = "Pinned",
                kind = SessionDayBucketKind.PINNED,
                date = null,
                sessions = pinned,
            )
        }

        val byDate = LinkedHashMap<LocalDate?, MutableList<SessionInfo>>()
        for (session in unpinned) {
            val localDate = session.lastActive?.atZone(zoneId)?.toLocalDate()
            byDate.getOrPut(localDate) { mutableListOf() }.add(session)
        }

        for ((date, daySessions) in byDate) {
            val group = when (date) {
                today -> SessionDayGroup(
                    key = "today",
                    label = "Today",
                    kind = SessionDayBucketKind.TODAY,
                    date = date,
                    sessions = daySessions,
                )
                yesterday -> SessionDayGroup(
                    key = "yesterday",
                    label = "Yesterday",
                    kind = SessionDayBucketKind.YESTERDAY,
                    date = date,
                    sessions = daySessions,
                )
                null -> SessionDayGroup(
                    key = "earlier",
                    label = "Earlier",
                    kind = SessionDayBucketKind.DATED,
                    date = null,
                    sessions = daySessions,
                )
                else -> SessionDayGroup(
                    key = date.toString(),
                    label = formatDayHeader(date, today),
                    kind = SessionDayBucketKind.DATED,
                    date = date,
                    sessions = daySessions,
                )
            }
            groups += group
        }

        return groups
    }

    /** Formats a historical [date] header relative to [today]. */
    fun formatDayHeader(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> if (date.year == today.year) {
            date.format(sameYearFormatter)
        } else {
            date.format(otherYearFormatter)
        }
    }

    /**
     * Returns the 3 left-swipe actions (`76 dp` each) for a session row (Phone 1 Section 7 G2):
     * `Pin` / `Unpin`, `Rename`, and `Delete`.
     */
    fun swipeActionsForSession(session: SessionInfo): List<SessionRowAction> = listOf(
        if (session.pinned) {
            SessionRowAction(SessionRowActionKind.UNPIN, "Unpin")
        } else {
            SessionRowAction(SessionRowActionKind.PIN, "Pin")
        },
        SessionRowAction(SessionRowActionKind.RENAME, "Rename"),
        SessionRowAction(SessionRowActionKind.DELETE, "Delete", destructive = true),
    )

    /**
     * Returns the long-press action sheet rows for a session row (Phone 1 Section 7 G2):
     * `Pin` / `Unpin`, `Mark as unread` (when not already unread), `Rename`, `Set Tag`, and `Delete`.
     */
    fun longPressActionsForSession(
        session: SessionInfo,
        isUnread: Boolean = false,
        hasTags: Boolean = true,
    ): List<SessionRowAction> = buildList {
        add(
            if (session.pinned) {
                SessionRowAction(SessionRowActionKind.UNPIN, "Unpin")
            } else {
                SessionRowAction(SessionRowActionKind.PIN, "Pin")
            },
        )
        if (!isUnread) {
            add(SessionRowAction(SessionRowActionKind.MARK_UNREAD, "Mark as unread"))
        }
        add(SessionRowAction(SessionRowActionKind.RENAME, "Rename"))
        if (hasTags) {
            add(SessionRowAction(SessionRowActionKind.SET_TAG, "Set Tag"))
        }
        add(SessionRowAction(SessionRowActionKind.DELETE, "Delete", destructive = true))
    }

    /** Formats the subtitle line of a 68 dp phone session row (Phone 1 Section 7 G1). */
    fun formatSessionSubtitle(
        liveState: SessionLiveState,
        relativeAge: String,
    ): String = when (liveState) {
        SessionLiveState.RUNNING -> "Running · now"
        SessionLiveState.NEEDS_YOU -> if (relativeAge.isNotEmpty()) "Needs you · $relativeAge" else "Needs you"
        SessionLiveState.UNREAD, SessionLiveState.IDLE -> relativeAge.ifEmpty { "Just now" }
    }
}

