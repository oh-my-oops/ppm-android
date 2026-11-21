package com.hienlh.ppm.state

/**
 * How much of a turn the chat shows, Claude Code's transcript view modes (`chat-view-mode-store.ts`;
 * Ctrl+O cycles them): Normal folds thinking once it is done and keeps tool cards to one line
 * unless they are a diff, Thinking keeps every thinking block open, Verbose opens every tool card too.
 */
enum class ChatViewMode(val wire: String, val label: String) {
    NORMAL("normal", "Normal"),
    THINKING("thinking", "Thinking"),
    VERBOSE("verbose", "Verbose"),
    ;

    /** Normal → Thinking → Verbose → Normal. */
    fun next(): ChatViewMode = entries[(ordinal + 1) % entries.size]

    companion object {
        /** A stored choice; Normal for anything else. */
        fun of(wire: String?): ChatViewMode = entries.firstOrNull { it.wire == wire } ?: NORMAL
    }
}

/** A chat tab closed on this device that Reopen Closed Chat can bring back (`closed-chats.ts`). */
data class ClosedChat(val projectName: String, val sessionId: String, val providerId: String, val title: String? = null)

/**
 * The chat tabs closed on this device, newest last, for "Reopen Closed Chat" (Alt+Shift+T). Only
 * a plain chat tab that has a session can come back; in memory, as a browser keeps its own.
 */
class ClosedChats {
    private val closed = ArrayList<ClosedChat>()

    /** Called with every tab that closes: [type] `chat` and a session, or it is ignored. */
    fun remember(type: String, projectName: String?, sessionId: String?, providerId: String?, title: String?) {
        if (type != "chat" || sessionId.isNullOrEmpty() || projectName.isNullOrEmpty()) return
        closed.removeAll { it.sessionId == sessionId }
        closed += ClosedChat(projectName, sessionId, providerId?.takeIf { it.isNotEmpty() } ?: "claude", title?.takeIf { it.isNotEmpty() })
        if (closed.size > MAX_CLOSED) closed.removeAt(0)
    }

    /** The chat closed last, taken off the list; null when none is left. */
    fun takeLast(): ClosedChat? = closed.removeLastOrNull()

    val size: Int get() = closed.size

    companion object {
        const val MAX_CLOSED = 20
    }
}
