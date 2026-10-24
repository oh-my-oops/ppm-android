package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.AgentRunningEntry
import com.hienlh.ppm.protocol.ChatEvent
import com.hienlh.ppm.protocol.TeamMemberActivity
import com.hienlh.ppm.protocol.ToolOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** The running agents bar: who runs, who finished beside them, and how each row reads. */
class RunningAgentsTest {
    private val start = Instant.parse("2026-10-25T08:00:00Z")

    private fun agent(id: String, input: String, result: ToolOutcome? = null, bgStatus: String? = null) =
        ChatBlock.Tool(ToolBlock(id, "Agent", json(input), result = result, bgStatus = bgStatus))

    private fun assistant(vararg blocks: ChatBlock, at: Instant = start) = ChatItem("m-${blocks.size}-$at", ChatItem.Role.ASSISTANT, blocks.toList(), at)

    @Test
    fun `the hub says who runs, the member poll adds what it knows and a member it already sees working`() {
        val members = listOf(
            TeamMemberActivity("tester", "working", agentType = "ak-engineer:tester", startedAt = start, lastTool = "Bash", lastToolArg = "bun test"),
            TeamMemberActivity("scout", "working", lastNarrative = "Reading the router"),
            TeamMemberActivity("idle", "paused"),
        )
        val running = listOf(
            AgentRunningEntry("toolu_1", "tester", 100, null),
            AgentRunningEntry("toolu_2", null, 200, "Read a.ts"),
        )
        assertEquals(
            listOf(
                RunningAgentRow("card:toolu_1", "toolu_1", "tester", "Bash: bun test", "ak-engineer:tester", start, 100),
                RunningAgentRow("card:toolu_2", "toolu_2", null, "Read a.ts", lastWriteAt = 200),
                RunningAgentRow("member:scout", memberName = "scout", lastStep = "Reading the router"),
            ),
            buildRunningRows(running, members),
        )
        assertEquals(emptyList<RunningAgentRow>(), buildRunningRows(emptyList(), emptyList()))
    }

    @Test
    fun `the other agents of a launch with one still running are listed as done, failed when they failed`() {
        val items = listOf(
            assistant(
                ChatBlock.Text("Fanning out"),
                agent("a1", """{"description":"One"}"""),
                agent("a2", """{"description":"Two","name":" scout "}""", result = ToolOutcome("Done.", false)),
                agent("a3", """{"description":"Three"}""", result = ToolOutcome("boom", true)),
                agent("a4", """{"description":"Four"}""", result = ToolOutcome("Async agent launched successfully.", true)),
                agent("a5", """{"description":"Five"}""", bgStatus = "stopped"),
            ),
            // A launch with nothing left running stays off.
            assistant(agent("b1", """{"description":"Old"}""")),
        )
        val running = buildRunningRows(listOf(AgentRunningEntry("a1", null, 1, null)), emptyList())
        assertEquals(
            listOf(
                RunningAgentRow("card:a2", "a2", "scout", done = true),
                RunningAgentRow("card:a3", "a3", done = true, failed = true),
                RunningAgentRow("card:a4", "a4", done = true),
                RunningAgentRow("card:a5", "a5", done = true, failed = true),
            ),
            finishedSiblingRows(items, running),
        )
        assertEquals(emptyList<RunningAgentRow>(), finishedSiblingRows(items, buildRunningRows(listOf(AgentRunningEntry(null, "tester", 1, null)), emptyList())))
    }

    @Test
    fun `a card's label is found newest first, a nested card too`() {
        val nested = ChatEvent.ToolUse("Task", json("""{"prompt":"Check the tests","name":"checker"}"""), "n1", parent = "a1")
        val card = ToolBlock("a1", "Agent", json("""{"description":"Review"}"""), children = AgentChildren().route("a1", "a1", nested))
        val later = start.plusSeconds(60)
        val items = listOf(
            assistant(agent("a0", """{"description":"First run"}""")),
            assistant(ChatBlock.Tool(card), at = later),
        )
        assertEquals(CardLabel(null, "Review", later), findCardLabel(items, "a1"))
        assertEquals(CardLabel("checker", "Check the tests", later), findCardLabel(items, "n1"))
        assertEquals(CardLabel(null, "First run", start), findCardLabel(items, "a0"))
        assertNull(findCardLabel(items, "missing"))
    }

    @Test
    fun `a row reads its name, prompt, step and time as the bar shows them`() {
        val now = start.plusSeconds(3 * 60 + 5)
        val label = CardLabel("scout", "Look around", start)
        assertEquals(
            RunningAgentView(RunningAgentRow("card:a1", "a1"), "scout", "Look around", null, "3m"),
            RunningAgentRow("card:a1", "a1").view(label, now),
        )
        val member = RunningAgentRow("member:tester", memberName = "tester", agentType = "ak-engineer:tester", startedAt = start.plusSeconds(180))
        assertEquals(RunningAgentView(member, "tester", null, "tester", "5s"), member.view(null, now))
        val done = RunningAgentRow("card:a2", "a2", done = true)
        assertEquals(RunningAgentView(done, "Two", "Two", null, null), done.view(CardLabel(null, "Two", start), now))
        assertEquals("Agent", RunningAgentRow("card:a3", "a3").view(CardLabel(null, "", null), now).name)
    }

    @Test
    fun `the wording shared with the team panel`() {
        assertEquals("tester", shortAgentType("ak-engineer:tester"))
        assertEquals("general-purpose", shortAgentType("general-purpose"))
        assertEquals("x:general-purpose", shortAgentType("x:general-purpose"))
        assertNull(shortAgentType(""))
        assertEquals("1h 4m", formatDuration(start, start.plusSeconds(3840), start))
        assertEquals("12m", formatDuration(start, null, start.plusSeconds(12 * 60 + 59)))
        assertEquals("45s", formatDuration(start, start.plusMillis(45_999), start))
        assertNull(formatDuration(start, start.minusSeconds(1), start))
        assertNull(formatDuration(null, null, start))
        assertEquals("Bash: bun test", currentStep(TeamMemberActivity("t", "working", lastTool = "Bash", lastToolArg = "bun test")))
        assertEquals("Bash", currentStep(TeamMemberActivity("t", "working", lastTool = "Bash", lastToolArg = "")))
        assertEquals("Fix the bug", currentStep(TeamMemberActivity("t", "working", description = "Fix the bug")))
        assertNull(currentStep(TeamMemberActivity("t", "paused")))
    }
}
