package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.AgentSource
import com.hienlh.ppm.protocol.ChatEvent
import com.hienlh.ppm.protocol.ToolOutcome
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.state.windows.Bounds
import com.hienlh.ppm.state.windows.MAX_WINDOWS
import com.hienlh.ppm.state.windows.Rect
import com.hienlh.ppm.state.windows.WindowKind
import com.hienlh.ppm.state.windows.WindowRuntimeState
import com.hienlh.ppm.state.windows.WindowVisualState
import com.hienlh.ppm.state.windows.WindowsState
import com.hienlh.ppm.state.windows.portraitSpawnRect
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Opening an agent's session: its payload, where it opens, the steps it starts with, and how they read. */
class AgentSessionTest {
    private val identity = AgentSessionIdentity("demo", "claude", "s1")

    private fun payload(cardId: String, sessionId: String = "s1") = AgentSessionPayload("demo", "claude", sessionId, AgentSource.Card(cardId), "Agent session")

    private fun window(id: String, rank: Int, kind: WindowKind = WindowKind.AgentSession, payload: JsonObject? = null, state: WindowVisualState = WindowVisualState.Normal) =
        WindowRuntimeState(id, kind, Rect(rank * 10.0, 0.0, 400.0, 300.0), rank, rank.toDouble(), state, payload = payload)

    private fun windows(vararg list: WindowRuntimeState) = WindowsState(list.associateBy { it.id }, Bounds(1280.0, 800.0))

    private fun agent(input: String, id: String? = "toolu_1") = ToolBlock(id, "Agent", json(input))

    @Test
    fun `a payload is written as the web's window payload and read back, junk being none`() {
        val member = AgentSessionPayload("demo", "codex", "s1", AgentSource.Member("t", "tester"), "Session — tester")
        assertEquals(
            WireJson.parse("""{"projectName":"demo","providerId":"codex","sessionId":"s1","source":{"kind":"member","teamName":"t","memberName":"tester"},"title":"Session — tester"}"""),
            member.toJson(),
        )
        assertEquals(member, AgentSessionPayload.from(member.toJson()))
        val card = payload("toolu_1").copy(prompt = "Look around")
        assertEquals(card, AgentSessionPayload.from(card.toJson()))
        assertEquals("s1:toolu_1", card.key)
        // A provider the hubs do not know is read as Claude, and a missing name as none.
        assertEquals(
            AgentSessionPayload("", "claude", "s1", AgentSource.Card("x")),
            AgentSessionPayload.from(json("""{"providerId":"mock","sessionId":"s1","source":{"kind":"card","cardId":"x"}}""") as JsonObject),
        )
        assertNull(AgentSessionPayload.from(null))
        assertNull(AgentSessionPayload.from(json("""{"sessionId":"s1","source":{"kind":"member","teamName":"t"}}""") as JsonObject))
        assertNull(AgentSessionPayload.from(json("""{"source":{"kind":"card","cardId":"x"}}""") as JsonObject))
    }

    @Test
    fun `a card opens its own session, named by its handle or its description`() {
        assertEquals(
            AgentSessionPayload("demo", "claude", "s1", AgentSource.Card("toolu_1"), "Session — scout", "Map the router"),
            agentCardPayload(agent("""{"name":" scout ","description":"Explore","prompt":"Map the router"}"""), identity, "other"),
        )
        val long = "d".repeat(61)
        assertEquals(
            AgentSessionPayload("demo", "claude", "s1", AgentSource.Card("toolu_1"), "d".repeat(60) + "…", long),
            agentCardPayload(agent("""{"description":"$long"}"""), identity, null),
        )
        assertEquals("Agent session", agentCardPayload(agent("{}"), identity, null).title)
        assertEquals("", agentCardPayload(agent("{}"), identity, null).prompt)
        // With no identity, a session the hub can never find: the card's own steps are all there is.
        assertEquals(AgentSessionPayload("demo", "claude", "local:toolu_1", AgentSource.Card("toolu_1"), "Agent session", ""), agentCardPayload(agent("{}"), null, "demo"))
        assertEquals("local:card", agentCardPayload(agent("{}", id = null), null, null).sessionId)
    }

    @Test
    fun `a running agent opens its card's session, a teammate its own by name`() {
        val card = RunningAgentView(RunningAgentRow("card:a1", "a1", "scout"), "scout", "Look around", null, null)
        assertEquals(AgentSessionPayload("demo", "claude", "s1", AgentSource.Card("a1"), "Session — scout", "Look around"), card.payload(identity, "s1"))
        val member = RunningAgentView(RunningAgentRow("member:tester", memberName = "tester"), "tester", null, "Bash", "5s")
        assertEquals(AgentSessionPayload("demo", "claude", "s1", AgentSource.Member("s1", "tester"), "Session — tester"), member.payload(identity, "s1"))
    }

    @Test
    fun `a phone opens the sheet, an open session comes forward, a new one opens portrait`() {
        assertEquals(AgentSessionOpening.Sheet, agentSessionOpening(windows(), "s1:a", sheet = true))
        val open = windows(
            window("w1", 0, WindowKind.Settings),
            window("w2", 1, payload = payload("a").toJson(), state = WindowVisualState.Minimized),
        )
        assertEquals(AgentSessionOpening.Focus("w2"), agentSessionOpening(open, "s1:a", sheet = false))
        assertEquals(
            AgentSessionOpening.Open(portraitSpawnRect(open.windows.values.map { it.rect }, open.bounds)),
            agentSessionOpening(open, "s1:b", sheet = false),
        )
        // The same card in another session is another session.
        assertTrue(agentSessionOpening(open, "s2:a", sheet = false) is AgentSessionOpening.Open)
    }

    @Test
    fun `at the cap the oldest agent window gives way, and with none the open is refused`() {
        val others = (0 until MAX_WINDOWS - 2).map { window("o$it", it + 2, WindowKind.Explorer) }
        // Rank, not the map's order, says which agent window is oldest.
        val full = windows(window("a2", 1, payload = payload("b").toJson()), window("a1", 0, payload = payload("a").toJson()), *others.toTypedArray())
        val remaining = full.windows.values.filter { it.id != "a1" }.map { it.rect }
        assertEquals(AgentSessionOpening.Open(portraitSpawnRect(remaining, full.bounds), closeFirst = "a1"), agentSessionOpening(full, "s1:c", sheet = false))
        // A session already open is focused even at the cap.
        assertEquals(AgentSessionOpening.Focus("a2"), agentSessionOpening(full, "s1:b", sheet = false))
        val noAgents = windows(*(0 until MAX_WINDOWS).map { window("o$it", it, WindowKind.TabHost) }.toTypedArray())
        assertEquals(AgentSessionOpening.Refused, agentSessionOpening(noAgents, "s1:c", sheet = false))
    }

    @Test
    fun `a session's held steps last while a window or the sheet shows it`() {
        val list = listOf(
            window("w1", 0, payload = payload("a").toJson()),
            window("w2", 1, WindowKind.Settings, payload = payload("x").toJson()),
            window("w3", 2, payload = json("""{"title":"broken"}""") as JsonObject),
        )
        val sheet = payload("b", sessionId = "s2")
        assertEquals(setOf("s1:a", "s2:b"), agentSessionKeysOnScreen(list, sheet))
        assertEquals(setOf("s1:a"), agentSessionKeysOnScreen(list, null))

        val steps = listOf<ChatEvent>(ChatEvent.Text("Looking"))
        var held = AgentSessionFallbacks().with("s1:a", steps).with("s1:gone", steps)
        assertEquals(steps, held["s1:a"])
        held = held.pruned(setOf("s1:a"))
        assertEquals(setOf("s1:a"), held.entries.keys)
        assertTrue(held.pruned(setOf("s1:a", "s2:b")) === held)
    }

    @Test
    fun `the header reads loading, running, failed on a failed last result, else done`() {
        val failed = listOf(ChatEvent.ToolUse("Bash", json("{}"), "b1"), ChatEvent.ToolResult(ToolOutcome("exit 1", true), "b1"))
        assertEquals(AgentSessionStatus.LOADING, agentSessionStatus(failed, loading = true, running = true))
        assertEquals(AgentSessionStatus.RUNNING, agentSessionStatus(failed, loading = false, running = true))
        assertEquals(AgentSessionStatus.FAILED, agentSessionStatus(failed, loading = false, running = false))
        assertEquals(AgentSessionStatus.DONE, agentSessionStatus(failed + ChatEvent.Text("Gave up"), loading = false, running = false))
        assertEquals(AgentSessionStatus.DONE, agentSessionStatus(emptyList(), loading = false, running = false))
    }

    @Test
    fun `steps read as joined text and calls with their results`() {
        val read = ChatEvent.ToolUse("Read", json("{}"), "r1")
        val again = ChatEvent.ToolUse("Read", json("{}"), "r1")
        val bash = ChatEvent.ToolUse("Bash", json("{}"), null)
        val ok = ToolOutcome("ok", false)
        val rows = agentStepRows(
            listOf(
                ChatEvent.Text("Look"),
                ChatEvent.Thinking("hmm"),
                ChatEvent.Text("ing"),
                read,
                again,
                ChatEvent.ToolResult(ok, "r1"),
                ChatEvent.ToolResult(ToolOutcome("second", false), "r1"),
                ChatEvent.ToolResult(ToolOutcome("nobody's", false), "zz"),
                bash,
                ChatEvent.Error("lost"),
                ChatEvent.ToolResult(ToolOutcome("done", false), null),
                ChatEvent.Text("All good"),
            ),
        )
        assertEquals(
            listOf(
                AgentStepRow.Text("Looking"),
                AgentStepRow.Tool(read, ok),
                AgentStepRow.Tool(again, ToolOutcome("second", false)),
                AgentStepRow.Tool(bash, ToolOutcome("done", false)),
                AgentStepRow.Text("All good"),
            ),
            rows,
        )
        // A result with no id goes on the newest call still waiting.
        assertEquals(
            listOf(AgentStepRow.Tool(read), AgentStepRow.Tool(bash, ok)),
            agentStepRows(listOf(read, bash, ChatEvent.ToolResult(ok, null))),
        )
    }
}
