package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.ChatEvent
import com.hienlh.ppm.protocol.ParsedUserMessage
import com.hienlh.ppm.protocol.jsTrim
import java.time.Instant

/**
 * One turn of a chat session — a prompt the user sent and everything the agent did for it — with
 * the ids of the calls it made (`session-turns.ts`). A change block names the calls that wrote
 * it; this turns them into "Turn 3 · 14:02", and is what reverting a turn names.
 */
data class SessionTurn(
    /** 1-based from the session's last compaction on; 0 for a turn from before it. */
    val n: Int,
    /** The user message that asked for it. */
    val messageId: String,
    val at: Instant?,
    /** What the user typed. */
    val prompt: String,
    /** Every call the turn made, its sub-agents' included, in the order they appear. */
    val calls: List<String>,
)

object SessionTurns {
    /**
     * The chat's turns. Numbered from the last compaction on, the way the conversation reads
     * after it: a turn from before it is "earlier", not given a number that would move each time
     * an older segment is loaded back in.
     */
    fun of(items: List<ChatItem>): List<SessionTurn> {
        val boundary = items.indexOfLast { it.compaction != null }
        val turns = mutableListOf<SessionTurn>()
        var n = 0
        var prompt: Pair<SessionTurn, LinkedHashSet<String>>? = null
        fun close() {
            prompt?.let { (turn, calls) -> turns += turn.copy(calls = calls.toList()) }
        }
        items.forEachIndexed { index, item ->
            val text = promptOf(item)
            if (text != null) {
                close()
                prompt = SessionTurn(if (index > boundary) ++n else 0, item.id, item.timestamp, text, emptyList()) to LinkedHashSet()
            } else if (item.role == ChatItem.Role.ASSISTANT) {
                prompt?.second?.addAll(callsOf(item))
            }
        }
        close()
        return turns
    }

    /** Each call's turn. */
    fun byCall(turns: List<SessionTurn>): Map<String, SessionTurn> = buildMap { for (turn in turns) for (call in turn.calls) put(call, turn) }

    /** What the user typed, or null for a user message that is not a prompt. */
    private fun promptOf(item: ChatItem): String? {
        if (item.role != ChatItem.Role.USER || item.compaction != null || item.text.jsTrim().isEmpty()) return null
        val parsed = ParsedUserMessage.parse(item.text)
        if (parsed.text.isEmpty() && parsed.command == null && parsed.files.isEmpty() && parsed.terminalBlocks.isEmpty()) return null
        return ParsedUserMessage.composerDraft(item.text).second.ifEmpty { null }
            ?: parsed.terminalBlocks.joinToString("\n\n").ifEmpty { null }
            ?: "${parsed.files.size} attached file${if (parsed.files.size == 1) "" else "s"}"
    }

    /**
     * Every call in an answer. A sub-agent's card keeps only some of its steps' events, but every
     * step's id in its progress, so both are read.
     */
    private fun callsOf(item: ChatItem): List<String> {
        val out = LinkedHashSet<String>()
        fun steps(progress: AgentProgress?) {
            progress ?: return
            for (id in progress.stepIds) if (!id.startsWith("idx:")) out += id
        }
        fun visit(events: List<ChatEvent>) {
            for (event in events) {
                if (event !is ChatEvent.ToolUse) continue
                event.toolUseId?.let { out += it }
                visit(event.children.orEmpty())
            }
        }
        fun nested(progress: AgentProgress?) {
            progress ?: return
            steps(progress)
            progress.nested.forEach { nested(it.second) }
        }
        for (block in item.blocks) {
            val tool = (block as? ChatBlock.Tool)?.tool ?: continue
            tool.toolUseId?.let { out += it }
            nested(tool.progress)
            visit(tool.children?.kept?.map { it.event }.orEmpty())
        }
        return out.toList()
    }

    /** "Turn 3", or "Earlier turn" for one from before the last compaction. */
    fun label(turn: SessionTurn): String = if (turn.n > 0) "Turn ${turn.n}" else "Earlier turn"
}
