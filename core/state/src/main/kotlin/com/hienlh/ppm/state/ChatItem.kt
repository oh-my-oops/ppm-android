package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.ChatEvent
import com.hienlh.ppm.protocol.Compaction
import com.hienlh.ppm.protocol.HistoryMessage
import com.hienlh.ppm.protocol.ToolOutcome
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/** A tool call with its result, as one card. */
data class ToolBlock(
    val toolUseId: String?,
    val name: String,
    val input: JsonElement,
    val result: ToolOutcome? = null,
    /** Tool calls made by the subagent this call started. */
    val steps: Int = 0,
    val lastStep: String? = null,
    /**
     * Set when the card stands for an approval request rather than a call: the web shows each
     * request in the turn as well as in the prompt under it. No result ever comes for it.
     */
    val requestId: String? = null,
)

sealed interface ChatBlock {
    data class Text(val text: String) : ChatBlock
    data class Thinking(val text: String) : ChatBlock
    data class Tool(val tool: ToolBlock) : ChatBlock
    data class Error(val message: String) : ChatBlock
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
                    ChatItem(message.id, Role.ASSISTANT, blocks, message.timestamp, message.sdkUuid, message.timestampText, answerText, message.compaction, taskCalls)
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
                if (event.parent == null) append(event.text, thinking = false)
            }
            is ChatEvent.Thinking -> if (event.parent == null) append(event.text, thinking = true)
            is ChatEvent.ToolUse -> addToolUse(event)
            is ChatEvent.ToolResult -> {
                if (event.parent != null) return
                val id = event.toolUseId
                if (id != null) {
                    toolIndex[id]?.let { setResult(event.outcome, it) }
                } else {
                    val index = list.indexOfLast(::isOpenTool)
                    if (index >= 0) setResult(event.outcome, index)
                }
            }
            is ChatEvent.Error -> list += ChatBlock.Error(event.message)
            is ChatEvent.Approval -> {
                val request = event.request
                // A database change already has its `db_execute` card; its approval is only the prompt.
                if (request.tool == "ppm:db_execute" || requestIndex(request.requestId) >= 0) return
                list += ChatBlock.Tool(ToolBlock(toolUseId = null, name = request.tool, input = request.input, requestId = request.requestId))
            }
            is ChatEvent.Done, is ChatEvent.SessionMigrated, is ChatEvent.Other -> Unit
        }
    }

    private fun addToolUse(use: ChatEvent.ToolUse) {
        val parent = use.parent
        val id = use.toolUseId
        if (use.name in TASK_TOOLS && (id == null || taskCallIds.add(id))) taskCalls++
        val existing = id?.let { toolIndex[it] }
        when {
            parent != null -> addStep(parent, ToolSummary.title(use.name, use.input))
            // As on the web: its approval request already stands for it in the turn. Its result,
            // finding no card with its id, goes nowhere either.
            live && use.name == "AskUserQuestion" -> Unit
            existing != null -> {
                // The same call again, with its input complete or its result attached.
                val block = (list[existing] as? ChatBlock.Tool)?.tool ?: return
                list[existing] = ChatBlock.Tool(block.copy(name = use.name, input = use.input, result = use.result ?: block.result))
            }
            else -> {
                if (id != null) toolIndex[id] = list.size
                list += ChatBlock.Tool(ToolBlock(toolUseId = id, name = use.name, input = use.input, result = use.result))
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

    private fun addStep(parent: String, step: String) {
        val index = toolIndex[parent] ?: return
        val block = (list[index] as? ChatBlock.Tool)?.tool ?: return
        list[index] = ChatBlock.Tool(block.copy(steps = block.steps + 1, lastStep = step))
    }

    private fun isOpenTool(block: ChatBlock) = block is ChatBlock.Tool && block.tool.result == null && block.tool.requestId == null
}

object ToolSummary {
    /** `mcp__ppm-db__db_query` reads as `ppm-db · db_query`. */
    fun displayName(name: String): String {
        if (!name.startsWith("mcp__")) return name
        val parts = name.removePrefix("mcp__").split("__")
        return if (parts.size == 2) "${parts[0]} · ${parts[1]}" else name
    }

    /** The one line that says what a call does: its command, file, pattern, URL…. */
    fun detail(name: String, input: JsonElement): String {
        val keys = when (name) {
            "Bash" -> listOf("command")
            "Read", "Write", "Edit", "MultiEdit" -> listOf("file_path", "path")
            "NotebookEdit" -> listOf("notebook_path")
            "Grep", "Glob" -> listOf("pattern")
            "WebFetch" -> listOf("url")
            "WebSearch" -> listOf("query")
            "Agent", "Task" -> listOf("description", "prompt")
            "Skill" -> listOf("skill", "command")
            else -> emptyList()
        }
        for (key in keys) {
            val value = input[key].string
            if (!value.isNullOrEmpty()) return firstLine(value)
        }
        if (name == "TodoWrite") input["todos"].array?.let { return "${it.size} items" }
        input.obj?.let { fields ->
            for (key in fields.keys.sorted()) {
                val value = fields[key].string
                if (!value.isNullOrEmpty()) return firstLine(value)
            }
        }
        return ""
    }

    fun title(name: String, input: JsonElement): String {
        val detail = detail(name, input)
        return if (detail.isEmpty()) displayName(name) else "${displayName(name)} $detail"
    }

    private fun firstLine(text: String): String {
        // The first line that has anything on it.
        val line = text.split('\n', '\r', '\u000B', '\u000C', '\u0085', ' ', ' ').firstOrNull { it.isNotEmpty() } ?: text
        return if (line.length > 200) line.take(200) + "…" else line
    }
}

/** Claude's task tools: a call to one is what makes a chat's task list worth reading. */
internal val TASK_TOOLS = setOf("TaskCreate", "TaskUpdate", "TaskStop")
