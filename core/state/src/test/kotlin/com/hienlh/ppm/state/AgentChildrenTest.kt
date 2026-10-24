package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.ChatEvent
import com.hienlh.ppm.protocol.ToolOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What an Agent card keeps of its subagent's events, for the session window. */
class AgentChildrenTest {
    private fun use(name: String, id: String, parent: String? = null, children: List<ChatEvent>? = null) =
        ChatEvent.from(json("""{"type":"tool_use","tool":"$name","toolUseId":"$id","input":{"file_path":"/a.ts"}}""")).let {
            (it as ChatEvent.ToolUse).copy(parent = parent, children = children)
        }

    private fun result(id: String, output: String = "ok", parent: String? = null) = ChatEvent.ToolResult(ToolOutcome(output, false), id, parent)

    private fun text(text: String, parent: String? = null) = ChatEvent.Text(text, parent)

    @Test
    fun `file changes and nested agents are kept with their results, everything else goes in the ring, all in arrival order`() {
        var children = AgentChildren()
        listOf(
            text("Looking", "a1"),
            use("Read", "r1", "a1"),
            result("r1", parent = "a1"),
            use("Edit", "e1", "a1"),
            result("e1", "edited", "a1"),
            use("Agent", "n1", "a1"),
        ).forEach { children = children.route("a1", "a1", it)!! }
        assertEquals(listOf("e1", "e1", "n1"), children.kept.map { (it.event as? ChatEvent.ToolUse)?.toolUseId ?: (it.event as ChatEvent.ToolResult).toolUseId })
        assertEquals(3, children.recent.size)
        assertEquals(6, children.nextSeq)
        assertEquals(
            listOf("Looking", "Read", "r1", "Edit", "e1", "Agent"),
            children.events.map {
                when (it) {
                    is ChatEvent.Text -> it.text
                    is ChatEvent.ToolUse -> it.name
                    is ChatEvent.ToolResult -> it.toolUseId
                    else -> "?"
                }
            },
        )
    }

    @Test
    fun `the same call again replaces its entry in place`() {
        var children = AgentChildren().route("a1", "a1", use("Write", "w1", "a1"))!!
        children = children.route("a1", "a1", text("between", "a1"))!!
        val complete = use("Write", "w1", "a1").copy(result = ToolOutcome("written", false))
        children = children.route("a1", "a1", complete)!!
        assertEquals(listOf(AgentChildren.Child(complete, 0)), children.kept)
        assertEquals(listOf(complete, text("between", "a1")), children.events)
    }

    @Test
    fun `a nested agent's events go on that agent, and only the ones its card lists`() {
        var children = AgentChildren().route("a1", "a1", use("Agent", "n1", "a1"))!!
        children = children.route("a1", "n1", use("Grep", "g1", "n1"))!!
        children = children.route("a1", "n1", use("MultiEdit", "m1", "n1"))!!
        children = children.route("a1", "n1", result("m1", parent = "n1"))!!
        children = children.route("a1", "n1", use("Task", "n2", "n1"))!!
        children = children.route("a1", "n2", use("NotebookEdit", "nb1", "n2"))!!
        val nested = children.kept.single().event as ChatEvent.ToolUse
        assertEquals(listOf("m1", "m1", "n2"), nested.children!!.map { (it as? ChatEvent.ToolUse)?.toolUseId ?: (it as ChatEvent.ToolResult).toolUseId })
        assertEquals("nb1", ((nested.children!!.last() as ChatEvent.ToolUse).children!!.single() as ChatEvent.ToolUse).toolUseId)
        // The card's own ring holds none of them.
        assertEquals(emptyList<AgentChildren.Child>(), children.recent)
        assertNull(children.route("a1", "unknown", text("lost", "unknown")))
    }

    @Test
    fun `the ring keeps the newest two hundred, and less when they are large`() {
        var children = AgentChildren()
        repeat(MAX_RECENT_CHILDREN + 10) { children = children.route("a1", "a1", text("step $it", "a1"))!! }
        assertEquals(MAX_RECENT_CHILDREN, children.recent.size)
        assertEquals(text("step 10", "a1"), children.recent.first().event)
        val big = "x".repeat(100 * 1024)
        repeat(3) { children = children.route("a1", "a1", text(big + it, "a1"))!! }
        assertEquals(listOf(text(big + 1, "a1"), text(big + 2, "a1")), children.recent.map { it.event })
        // One event larger than the cap still stays: the newest is always kept.
        children = children.route("a1", "a1", text("y".repeat(MAX_RECENT_BYTES + 1), "a1"))!!
        assertEquals(1, children.recent.size)
    }

    @Test
    fun `a history card keeps every event unless the hub can stream them`() {
        val history = listOf(
            text("Looking"),
            use("Read", "r1"),
            result("r1"),
            use("Edit", "e1"),
            result("e1"),
            use("Agent", "n1", children = listOf(use("Bash", "b1"), use("Write", "w1"))),
        )
        assertEquals(history, AgentChildren.ofHistory(history, transcriptAvailable = false).events)
        val slim = AgentChildren.ofHistory(history, transcriptAvailable = true).events
        assertEquals(listOf(use("Edit", "e1"), result("e1"), use("Agent", "n1", children = listOf(use("Write", "w1")))), slim)
        assertEquals(emptyList<ChatEvent>(), AgentChildren.ofHistory(null, transcriptAvailable = true).events)
    }
}
