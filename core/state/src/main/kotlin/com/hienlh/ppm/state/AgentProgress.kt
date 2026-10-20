package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.ChatEvent
import com.hienlh.ppm.protocol.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * How far a subagent has got, for the one line of its card (`agent-step-summary.ts`). Each
 * distinct call it made is a step; an agent it started counts once for starting it and has its
 * own steps added on top, so the card's number is every call in the subagent's whole tree.
 */
data class AgentProgress(
    /** Ids of the calls counted, each once however often it is sent; a call without one counts by position. */
    val stepIds: List<String> = emptyList(),
    /** What the latest call does, in a few words (`describeStep`). */
    val lastStep: String? = null,
    /** The agents this one started, by call id, each with its own progress. */
    val nested: List<Pair<String?, AgentProgress>> = emptyList(),
) {
    /** `recursiveStepCount`. */
    val total: Int get() = stepIds.size + nested.sumOf { it.second.total }

    /** Every agent id under this one, to route their calls here. */
    val nestedIds: List<String> get() = nested.flatMap { (id, progress) -> listOfNotNull(id) + progress.nestedIds }

    /**
     * [child], a call the agent [parent] made, counted on this card when [parent] is the card's
     * own call [self] or an agent under it (`routeToParent`). Null when it is neither.
     */
    fun route(self: String?, parent: String, child: ChatEvent.ToolUse): AgentProgress? {
        if (self == parent) return withStep(child)
        nested.forEachIndexed { index, (id, progress) ->
            val routed = progress.route(id, parent, child) ?: return@forEachIndexed
            return copy(nested = nested.toMutableList().also { it[index] = id to routed })
        }
        return null
    }

    /** `applyChildToParent`. */
    private fun withStep(child: ChatEvent.ToolUse): AgentProgress {
        val id = child.toolUseId
        val ids = if (id == null || id !in stepIds) stepIds + (id ?: "idx:${stepIds.size}") else stepIds
        val step = describeStep(child.name, child.input)
        val startsAgent = (child.name == "Agent" || child.name == "Task") && id != null && nested.none { it.first == id }
        return AgentProgress(ids, step.ifEmpty { null } ?: lastStep, if (startsAgent) nested + (id to AgentProgress()) else nested)
    }

    companion object {
        /** A history card's progress, from the events its subagent made (`agentStepInfo` over `children`). */
        fun of(children: List<ChatEvent>?): AgentProgress {
            val ids = mutableListOf<String>()
            var last: String? = null
            val nested = mutableListOf<Pair<String?, AgentProgress>>()
            children.orEmpty().forEachIndexed { index, event ->
                if (event !is ChatEvent.ToolUse) return@forEachIndexed
                ids += event.toolUseId ?: "idx:$index"
                describeStep(event.name, event.input).takeIf { it.isNotEmpty() }?.let { last = it }
                if (event.name == "Agent" || event.name == "Task") nested += event.toolUseId to of(event.children)
            }
            return AgentProgress(ids, last, nested)
        }
    }
}

/** A call in a few words, for an agent card's "current step" (`describeStep`). */
fun describeStep(tool: String, input: JsonElement): String {
    fun str(value: JsonElement?): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
    fun str(key: String) = str(input[key])
    // `a || b`: the first that JavaScript reads as true.
    fun either(first: String, second: String): JsonElement? = input[first].takeIf(::truthy) ?: input[second]
    return when (tool) {
        "Read", "Write", "Edit", "MultiEdit", "NotebookEdit" -> {
            val path = str(either("notebook_path", "file_path"))
            if (path.isNotEmpty()) "$tool ${path.split('/', '\\').last()}" else tool
        }
        "Bash", "PowerShell" -> {
            val preview = if (truthy(input["description"])) str("description") else str("command")
            truncate(preview, 60).ifEmpty { tool }
        }
        "Glob" -> "Glob ${str("pattern")}"
        "Grep" -> "Grep ${truncate(str("pattern"), 40)}"
        "WebSearch", "ToolSearch" -> "Search ${truncate(str("query"), 50)}"
        "WebFetch" -> "Fetch ${truncate(str("url"), 50)}"
        "Agent", "Task" -> {
            val name = str("name").trim().ifEmpty { null }
            val task = truncate(str(either("description", "prompt")), 44)
            if (name != null) (if (task.isNotEmpty()) "$name · $task" else name) else task.ifEmpty { tool }
        }
        "SendMessage" -> "Message ${truncate(str("to"), 30)}".trim()
        "TodoWrite" -> "Todo update"
        "AskUserQuestion" -> "Question"
        "ScheduleWakeup" -> "Scheduled wakeup"
        "TaskCreate" -> truncate(str("subject"), 60).ifEmpty { tool }
        "TaskUpdate" -> "Task #${str("taskId")} → ${str("status")}"
        "TaskStop" -> "Task #${str("taskId")} stopped"
        "ImageGen" -> "Image generation"
        else -> tool
    }
}

/** Whether JavaScript reads [value] as true: not absent, null, false, 0, NaN or "". */
fun truthy(value: JsonElement?): Boolean = when (value) {
    null, JsonNull -> false
    is JsonObject, is JsonArray -> true
    is JsonPrimitive -> when {
        value.isString -> value.content.isNotEmpty()
        else -> value.booleanOrNull ?: value.doubleOrNull?.let { it != 0.0 && !it.isNaN() } ?: true
    }
}

private fun truncate(value: String, max: Int): String = if (value.length > max) value.take(max) + "…" else value
