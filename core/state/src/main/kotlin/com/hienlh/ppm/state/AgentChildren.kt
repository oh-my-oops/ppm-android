package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.ChatEvent

/** Tools whose calls a card keeps for good, with their results (`isKeptToolUse`). */
private val FILE_MUTATION_TOOLS = setOf("Write", "Edit", "MultiEdit", "NotebookEdit")

private fun isKeptTool(name: String) = name == "Agent" || name == "Task" || name in FILE_MUTATION_TOOLS

private fun isAgentTool(name: String) = name == "Agent" || name == "Task"

/** The ring of a card's other events holds this many (`MAX_RECENT_CHILDREN`)… */
const val MAX_RECENT_CHILDREN = 200

/** …and drops its oldest past about this much JSON (`MAX_RECENT_BYTES`). */
const val MAX_RECENT_BYTES = 256 * 1024

/**
 * What an Agent/Task card keeps of its subagent's events (`children` + `recentChildren`), for its
 * session window to show before the transcript hub answers, or when it has no transcript: nested
 * Agent/Task calls and file changes with their results in [kept], everything else in [recent], a
 * ring of the last [MAX_RECENT_CHILDREN] events and [MAX_RECENT_BYTES]. [events] puts them back in
 * the order they came.
 */
data class AgentChildren(
    val kept: List<Child> = emptyList(),
    val recent: List<Child> = emptyList(),
    /** `childSeq`: the place the next routed event arrives in. */
    val nextSeq: Int = 0,
) {
    /** An event and the place it arrived in; null for one from history, which keeps its place in its list. */
    data class Child(val event: ChatEvent, val seq: Int? = null)

    /** `mergeFallbackEvents`. */
    val events: List<ChatEvent>
        get() {
            fun tag(list: List<Child>) = list.mapIndexed { index, child -> (child.seq ?: index) to child.event }
            return (tag(kept) + tag(recent)).sortedBy { it.first }.map { it.second }
        }

    /**
     * [event], made by the agent [parent], on the card whose own call is [self]: this card's own
     * when [parent] is [self], else the nested agent's [parent] among the calls kept (`routeToParent`).
     * Null when neither is it.
     */
    fun route(self: String?, parent: String, event: ChatEvent): AgentChildren? {
        if (self == parent) return add(event)
        kept.forEachIndexed { index, child ->
            val use = child.event as? ChatEvent.ToolUse ?: return@forEachIndexed
            if (!isAgentTool(use.name)) return@forEachIndexed
            val routed = nested(use, parent, event) ?: return@forEachIndexed
            return copy(kept = kept.toMutableList().also { it[index] = child.copy(event = routed) })
        }
        return null
    }

    /** `applyChildToParent`: kept when it is a kept call or the result of one, else into the ring. */
    private fun add(event: ChatEvent): AgentChildren {
        val seq = nextSeq
        val keptResult = event is ChatEvent.ToolResult && event.toolUseId != null &&
            kept.any { (it.event as? ChatEvent.ToolUse)?.toolUseId == event.toolUseId }
        return if ((event is ChatEvent.ToolUse && isKeptTool(event.name)) || keptResult) {
            copy(kept = upsert(kept, event, seq), nextSeq = seq + 1)
        } else {
            copy(recent = capped(upsert(recent, event, seq)), nextSeq = seq + 1)
        }
    }

    companion object {
        /**
         * A card read from history: slimmed to what is kept when the server says the hub can
         * stream its transcript (`slimHistoryEvents`), otherwise every event, being the only
         * record there is.
         */
        fun ofHistory(children: List<ChatEvent>?, transcriptAvailable: Boolean): AgentChildren =
            AgentChildren(kept = (if (transcriptAvailable) slimmed(children) else children.orEmpty()).map { Child(it) })

        /** `slimAgentChildren(...).kept`: nested agents (slimmed too) and file changes with their results. */
        private fun slimmed(children: List<ChatEvent>?): List<ChatEvent> {
            val kept = mutableListOf<ChatEvent>()
            val keptIds = HashSet<String>()
            for (event in children.orEmpty()) {
                when {
                    event is ChatEvent.ToolUse && isKeptTool(event.name) -> {
                        kept += if (isAgentTool(event.name)) event.copy(children = slimmed(event.children)) else event
                        event.toolUseId?.let { keptIds += it }
                    }
                    event is ChatEvent.ToolResult && event.toolUseId in keptIds -> kept += event
                }
            }
            return kept
        }

        /**
         * A nested agent [use], with [event] of agent [parent] in it when it is [parent] or holds it.
         * Only kept events go on a nested agent: its card in the window lists those alone.
         */
        private fun nested(use: ChatEvent.ToolUse, parent: String, event: ChatEvent): ChatEvent.ToolUse? {
            val children = use.children.orEmpty()
            if (use.toolUseId == parent) {
                val keep = (event is ChatEvent.ToolUse && isKeptTool(event.name)) ||
                    (event is ChatEvent.ToolResult && event.toolUseId != null && children.any { (it as? ChatEvent.ToolUse)?.toolUseId == event.toolUseId })
                if (!keep) return use
                return use.copy(children = upsert(children.map { Child(it) }, event, null).map { it.event })
            }
            children.forEachIndexed { index, child ->
                if (child !is ChatEvent.ToolUse || !isAgentTool(child.name)) return@forEachIndexed
                val routed = nested(child, parent, event) ?: return@forEachIndexed
                return use.copy(children = children.toMutableList().also { it[index] = routed })
            }
            return null
        }

        /** The same call or result again replaces its entry, keeping its place; anything else is added. */
        private fun upsert(list: List<Child>, event: ChatEvent, seq: Int?): List<Child> {
            val id = toolUseIdOf(event)
            val index = if (id == null) -1 else list.indexOfFirst { it.event::class == event::class && toolUseIdOf(it.event) == id }
            if (index == -1) return list + Child(event, seq)
            return list.toMutableList().also { it[index] = Child(event, list[index].seq) }
        }

        private fun toolUseIdOf(event: ChatEvent): String? = when (event) {
            is ChatEvent.ToolUse -> event.toolUseId
            is ChatEvent.ToolResult -> event.toolUseId
            else -> null
        }

        /** The ring after its count cap, then its size cap, which always leaves the newest (`capByBytes`). */
        private fun capped(list: List<Child>): List<Child> {
            val counted = if (list.size > MAX_RECENT_CHILDREN) list.subList(list.size - MAX_RECENT_CHILDREN, list.size) else list
            var total = counted.sumOf { approxSize(it.event) }
            var start = 0
            while (total > MAX_RECENT_BYTES && start < counted.size - 1) {
                total -= approxSize(counted[start].event)
                start++
            }
            return if (start > 0) counted.subList(start, counted.size).toList() else counted.toList()
        }

        /** About `JSON.stringify(event).length`: the text it carries and a little for its keys. */
        private fun approxSize(event: ChatEvent): Int = when (event) {
            is ChatEvent.Text -> event.text.length + 60
            is ChatEvent.Thinking -> event.text.length + 60
            is ChatEvent.ToolUse -> event.input.toString().length + event.name.length + 80
            is ChatEvent.ToolResult -> event.outcome.output.length + 80
            is ChatEvent.Error -> event.message.length + 40
            else -> 40
        }
    }
}
