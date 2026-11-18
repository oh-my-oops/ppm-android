package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.ChatEvent
import com.hienlh.ppm.protocol.Compaction
import com.hienlh.ppm.protocol.HistoryMessage
import com.hienlh.ppm.protocol.ToolOutcome
import com.hienlh.ppm.protocol.TurnUsage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/** A tool call with its result, as one card. */
data class ToolBlock(
    val toolUseId: String?,
    val name: String,
    val input: JsonElement,
    val result: ToolOutcome? = null,
    /** Agent/Task only: the calls the subagent this call started has made. */
    val progress: AgentProgress? = null,
    /**
     * Set when the card stands for an approval request rather than a call: the web shows each
     * request in the turn as well as in the prompt under it.
     */
    val requestId: String? = null,
    /** On a request the server replayed after it was answered: what the answer was. */
    val approved: Boolean? = null,
    /** A backgrounded Agent/Task's end once it is reported: `completed`, `failed` or `stopped`. */
    val bgStatus: String? = null,
    /** Agent/Task only: what the card keeps of its subagent's events, for its session window. */
    val children: AgentChildren? = null,
) {
    val steps: Int get() = progress?.total ?: 0
    val lastStep: String? get() = progress?.lastStep
}

sealed interface ChatBlock {
    data class Text(val text: String) : ChatBlock
    data class Thinking(val text: String) : ChatBlock
    data class Tool(val tool: ToolBlock) : ChatBlock
    data class Error(val message: String) : ChatBlock

    /** The turn going on with another account (`account_retry`), which the web quotes into the answer. */
    data class Retry(val reason: String, val accountLabel: String) : ChatBlock
}

/** One message as the chat shows it. */
data class ChatItem(
    val id: String,
    val role: Role,
    val blocks: List<ChatBlock>,
    val timestamp: Instant?,
    /** The SDK's own id for the message, which a reply names when there is one. */
    val sdkUuid: String? = null,
    /** [timestamp] as the server wrote it, which a reply compares. Null for a message made here. */
    val timestampText: String? = null,
    /** An answer's text as the web quotes it: every text event run together, a subagent's too. */
    val answerText: String = blocks.filterIsInstance<ChatBlock.Text>().joinToString("") { it.text },
    /** Set on the compact summary that opens a segment: what that compaction did. */
    val compaction: Compaction? = null,
    /** Calls to Claude's task tools in this message, a subagent's too (`countTaskEvents`). */
    val taskCalls: Int = 0,
    /** The token split of the turn this answer ended, as its `done` reported it; history has none. */
    val usage: TurnUsage? = null,
    /** The turn it belongs to, as history stamps every message; null for one drawn here until history is read again. */
    val turnId: String? = null,
    /** That turn's number in the session. */
    val turnN: Int? = null,
) {
    enum class Role { USER, ASSISTANT, SYSTEM }

    val text: String
        get() = blocks.filterIsInstance<ChatBlock.Text>().joinToString("\n\n") { it.text }

    /** The transcript this message names as a compact summary does, read once (`extractJsonlPath`). */
    val transcriptPath: String? = PreCompact.transcriptPath(if (role == Role.ASSISTANT) answerText else text)

    companion object {
        /** Null for a message with nothing to show (the transcript keeps placeholders for failed turns). */
        fun fromHistory(message: HistoryMessage): ChatItem? = when (message.role) {
            HistoryMessage.Role.USER, HistoryMessage.Role.SYSTEM -> if (message.content.isEmpty()) {
                null
            } else {
                ChatItem(
                    id = message.id,
                    role = if (message.role == HistoryMessage.Role.USER) Role.USER else Role.SYSTEM,
                    blocks = listOf(ChatBlock.Text(message.content)),
                    timestamp = message.timestamp,
                    sdkUuid = message.sdkUuid,
                    timestampText = message.timestampText,
                    compaction = message.compaction,
                    turnId = message.turnId,
                    turnN = message.turnN,
                )
            }
            HistoryMessage.Role.ASSISTANT -> {
                val turn = TurnBuilder(live = false)
                message.events.forEach(turn::add)
                val blocks = if (turn.isEmpty) {
                    if (message.content.isEmpty()) emptyList() else listOf(ChatBlock.Text(message.content))
                } else {
                    turn.blocks
                }
                val answerText = if (message.events.isEmpty()) {
                    message.content
                } else {
                    message.events.filterIsInstance<ChatEvent.Text>().joinToString("") { it.text }
                }
                val taskCalls = message.events.count { it is ChatEvent.ToolUse && it.name in TASK_TOOLS }
                if (blocks.isEmpty()) {
                    null
                } else {
                    ChatItem(
                        message.id, Role.ASSISTANT, blocks, message.timestamp, message.sdkUuid, message.timestampText, answerText, message.compaction, taskCalls,
                        turnId = message.turnId, turnN = message.turnN,
                    )
                }
            }
        }
    }
}

/** Builds an assistant message's blocks from its events. */
class TurnBuilder(
    /** Live text arrives as deltas of one block; history gives each block whole. */
    private val live: Boolean,
) {
    private val list = mutableListOf<ChatBlock>()
    private val toolIndex = HashMap<String, Int>()

    /** Every agent id in the turn, nested ones too, to the index of the card that counts its calls. */
    private val agentCards = HashMap<String, Int>()

    /** Live `AskUserQuestion` calls, which their approval requests stand for: their results go nowhere. */
    private val askCalls = HashSet<String>()
    private val allText = StringBuilder()
    private val taskCallIds = HashSet<String>()

    /** [ChatItem.answerText] of the message being built. */
    val answerText: String get() = allText.toString()

    /** [ChatItem.taskCalls] of the message being built: each call once, however often it streams in. */
    var taskCalls = 0
        private set

    /** A copy: what is shown must not change under the UI as more events arrive. */
    val blocks: List<ChatBlock> get() = list.toList()

    val isEmpty: Boolean get() = list.isEmpty()

    fun add(event: ChatEvent) {
        when (event) {
            is ChatEvent.Text -> {
                allText.append(event.text)
                val parent = event.parent
                if (parent == null) append(event.text, thinking = false) else addChild(parent, event)
            }
            is ChatEvent.Thinking -> event.parent.let { parent ->
                if (parent == null) append(event.text, thinking = true) else addChild(parent, event)
            }
            is ChatEvent.ToolUse -> addToolUse(event)
            is ChatEvent.ToolResult -> {
                val id = event.toolUseId
                if (id in askCalls) return
                event.parent?.let { return addChild(it, event) }
                // By id; otherwise, or when no card has it, the first card still without a result.
                val index = id?.let { toolIndex[it] } ?: list.indexOfFirst { it is ChatBlock.Tool && it.tool.result == null }
                if (index >= 0) setResult(event.outcome, index)
            }
            is ChatEvent.Error -> event.parent.let { parent ->
                if (parent == null) list += ChatBlock.Error(event.message) else addChild(parent, event)
            }
            is ChatEvent.Approval -> {
                val request = event.request
                // A database change already has its `db_execute` card; its approval is only the prompt.
                if (request.tool == "ppm:db_execute") return
                val index = requestIndex(request.requestId)
                if (index >= 0) {
                    // Sent again, as a replay sends it once it was answered.
                    val block = (list[index] as ChatBlock.Tool).tool
                    list[index] = ChatBlock.Tool(block.copy(name = request.tool, input = request.input, approved = request.approved))
                    return
                }
                list += ChatBlock.Tool(
                    ToolBlock(toolUseId = null, name = request.tool, input = request.input, requestId = request.requestId, approved = request.approved),
                )
            }
            is ChatEvent.AccountRetry -> list += ChatBlock.Retry(event.reason ?: "Auth failed", event.accountLabel ?: "another account")
            is ChatEvent.SubagentStatus -> setBgStatus(event.toolUseId, event.status)
            is ChatEvent.Done, is ChatEvent.SessionMigrated, is ChatEvent.StatusUpdate, is ChatEvent.AccountInfo, is ChatEvent.BashOutput,
            is ChatEvent.TeamDetected, is ChatEvent.TeamInbox, is ChatEvent.Other -> Unit
        }
    }

    /** This turn's own call [toolUseId] while it still waits for its result, or null. */
    fun unanswered(toolUseId: String): ToolBlock? =
        toolIndex[toolUseId]?.let { (list[it] as? ChatBlock.Tool)?.tool }?.takeIf { it.result == null }

    /** Whether a call of the agent [id] counts on a card of this turn. */
    fun hasAgent(id: String): Boolean = id in agentCards

    /** A backgrounded agent of this turn ended; false when no card of the turn is its call. */
    fun setBgStatus(toolUseId: String, status: String): Boolean {
        val index = toolIndex[toolUseId] ?: return false
        val block = (list[index] as? ChatBlock.Tool)?.tool?.takeIf { it.isAgent } ?: return false
        list[index] = ChatBlock.Tool(block.copy(bgStatus = status))
        return true
    }

    private fun addToolUse(use: ChatEvent.ToolUse) {
        val parent = use.parent
        val id = use.toolUseId
        if (use.name in TASK_TOOLS && (id == null || taskCallIds.add(id))) taskCalls++
        val existing = id?.let { toolIndex[it] }
        when {
            // As on the web: its approval request already stands for it in the turn, a subagent's
            // question too, and its result is dropped with it.
            live && use.name == "AskUserQuestion" -> if (id != null) askCalls += id
            parent != null -> addChild(parent, use)
            existing != null -> {
                // The same call again, with its input complete or its result attached.
                val block = (list[existing] as? ChatBlock.Tool)?.tool ?: return
                list[existing] = ChatBlock.Tool(
                    block.copy(name = use.name, input = use.input, result = use.result ?: block.result, bgStatus = use.bgStatus ?: block.bgStatus),
                )
            }
            else -> {
                val isAgent = use.name == "Agent" || use.name == "Task"
                val progress = if (isAgent) AgentProgress.of(use.children) else null
                if (id != null) {
                    toolIndex[id] = list.size
                    if (progress != null) (listOf(id) + progress.nestedIds).forEach { agentCards[it] = list.size }
                }
                list += ChatBlock.Tool(
                    ToolBlock(
                        toolUseId = id,
                        name = use.name,
                        input = use.input,
                        result = use.result,
                        progress = progress,
                        bgStatus = use.bgStatus,
                        children = if (isAgent) AgentChildren.ofHistory(use.children, use.transcriptAvailable) else null,
                    ),
                )
            }
        }
    }

    /** What the user answered a question, shown on its card as the web merges it in. */
    fun addAnswers(answers: JsonElement, requestId: String) {
        val index = requestIndex(requestId)
        if (index < 0) return
        val block = (list[index] as ChatBlock.Tool).tool
        val input = block.input as? JsonObject ?: return
        if (block.name != "AskUserQuestion") return
        list[index] = ChatBlock.Tool(block.copy(input = JsonObject(input + ("answers" to answers))))
    }

    private fun requestIndex(requestId: String): Int =
        list.indexOfFirst { it is ChatBlock.Tool && it.tool.requestId == requestId }

    private fun append(text: String, thinking: Boolean) {
        if (text.isEmpty()) return
        if (live && list.isNotEmpty()) {
            val last = list.last()
            if (!thinking && last is ChatBlock.Text) {
                list[list.lastIndex] = ChatBlock.Text(last.text + text)
                return
            }
            if (thinking && last is ChatBlock.Thinking) {
                list[list.lastIndex] = ChatBlock.Thinking(last.text + text)
                return
            }
        }
        list += if (thinking) ChatBlock.Thinking(text) else ChatBlock.Text(text)
    }

    private fun setResult(outcome: ToolOutcome, index: Int) {
        val block = (list[index] as? ChatBlock.Tool)?.tool ?: return
        list[index] = ChatBlock.Tool(block.copy(result = outcome))
    }

    /** An event the agent [parent] made, on the card of this turn that holds that agent. */
    private fun addChild(parent: String, event: ChatEvent) {
        val index = agentCards[parent] ?: return
        val block = (list[index] as? ChatBlock.Tool)?.tool ?: return
        list[index] = ChatBlock.Tool(block.withChild(parent, event) ?: return)
        val id = (event as? ChatEvent.ToolUse)?.takeIf { it.name == "Agent" || it.name == "Task" }?.toolUseId
        if (id != null) agentCards[id] = index
    }
}

/** An Agent/Task call rather than an approval request for one. */
val ToolBlock.isAgent: Boolean get() = (name == "Agent" || name == "Task") && requestId == null

/**
 * This agent card with [event], made by its agent [parent] or one under it: counted when it is a
 * call, and kept for the card's session window. Null when neither agent is this card's.
 */
fun ToolBlock.withChild(parent: String, event: ChatEvent): ToolBlock? {
    if (!isAgent) return null
    val counted = if (event is ChatEvent.ToolUse) withStep(parent, event) ?: return null else this
    val children = (children ?: AgentChildren()).route(toolUseId, parent, event)
    return when {
        children != null -> counted.copy(children = children)
        event is ChatEvent.ToolUse -> counted
        else -> null
    }
}

/** This agent card with [use], a call its agent [parent] or one under it made, counted; null when it is neither's. */
fun ToolBlock.withStep(parent: String, use: ChatEvent.ToolUse): ToolBlock? {
    if (!isAgent) return null
    val progress = (progress ?: AgentProgress()).route(toolUseId, parent, use) ?: return null
    return copy(progress = progress)
}

/** Claude's task tools: a call to one is what makes a chat's task list worth reading. */
internal val TASK_TOOLS = setOf("TaskCreate", "TaskUpdate", "TaskStop")
