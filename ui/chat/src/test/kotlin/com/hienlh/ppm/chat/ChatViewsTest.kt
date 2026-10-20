package com.hienlh.ppm.chat

import com.hienlh.ppm.protocol.Compaction
import com.hienlh.ppm.protocol.SessionPhase
import com.hienlh.ppm.protocol.ToolOutcome
import com.hienlh.ppm.protocol.TurnUsage
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.state.ChatBlock
import com.hienlh.ppm.state.ChatItem
import com.hienlh.ppm.state.ChatState
import com.hienlh.ppm.state.ToolBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chat views' rules, without a device (`PPMSelfTest/ChatUITests.swift`). */
class ChatViewsTest {
    @Test
    fun `a costly turn is judged as assessTurnCost judges it`() {
        val idle = TurnUsage("claude-sonnet-4-5", 1200, 850, 18_000, 52_000, 0.2841, 18_000.0 / 71_200, coldStart = true, coldReason = "idle_timeout")
        assertEquals(
            TurnCostVerdict(
                TurnCostVerdict.Level.BAD,
                53_200,
                "53k of this session's transcript was re-sent uncached because the session sat idle with no tab open long enough for PPM " +
                    "to release its subprocess.",
            ),
            TurnCost.assess(idle),
        )
        assertEquals("9.6", fixed(TurnCost.multiplier(idle), 1))
        val model = TurnUsage("claude-opus-4-1", 800, 1430, 52_000, 9_000, 0.0721, 52_000.0 / 61_800, coldStart = true, coldReason = "set_model")
        assertEquals(
            TurnCostVerdict(
                TurnCostVerdict.Level.WARN,
                9_800,
                "9.8k of this session's transcript was re-sent uncached because the model was changed, which restarts the session.",
            ),
            TurnCost.assess(model),
        )
        assertEquals("2.8", fixed(TurnCost.multiplier(model), 1))
        // A reason PPM has no words for is named as it came; none, or an empty one, is left out.
        assertEquals(
            "53k of this session's transcript was re-sent uncached because the session restarted (foo).",
            TurnCost.assess(idle.copy(coldReason = "foo")).reason,
        )
        assertEquals("53k of this session's transcript was re-sent uncached.", TurnCost.assess(idle.copy(coldReason = "")).reason)
        assertEquals("53k of this session's transcript was re-sent uncached.", TurnCost.assess(idle.copy(coldReason = null)).reason)
        // Half from the cache is a warning, nine tenths nothing; so is a warm turn, and a prefix under 20k.
        assertEquals(TurnCostVerdict.Level.WARN, TurnCost.assess(idle.copy(cacheHitRate = 0.5)).level)
        assertEquals(TurnCostVerdict.Level.OK, TurnCost.assess(idle.copy(cacheHitRate = 0.9)).level)
        assertEquals(TurnCostVerdict.Level.OK, TurnCost.assess(idle.copy(coldStart = false)).level)
        val small = idle.copy(inputTokens = 100, cacheReadTokens = 900, cacheWriteTokens = 18_999, cacheHitRate = 0.045)
        assertEquals(TurnCostVerdict.Level.OK, TurnCost.assess(small).level)
        assertEquals(TurnCostVerdict.Level.BAD, TurnCost.assess(small.copy(cacheWriteTokens = 19_000)).level)
        assertEquals("12.0", fixed(TurnCost.multiplier(small), 1))
        assertEquals(1.0, TurnCost.multiplier(idle.copy(inputTokens = 0, cacheReadTokens = 0, cacheWriteTokens = 0)), 0.0)
    }

    @Test
    fun `edit diff pairs removed and added lines`() {
        val diff = EditDiff.rows("a\nb\nc", "a\nB\nc")
        assertEquals(
            listOf(
                EditDiff.Row(EditDiff.Kind.EQUAL, "a"),
                EditDiff.Row(EditDiff.Kind.DEL, "b", listOf(0 to 1)),
                EditDiff.Row(EditDiff.Kind.INS, "B", listOf(0 to 1)),
                EditDiff.Row(EditDiff.Kind.EQUAL, "c"),
            ),
            diff.rows,
        )
        assertEquals(0, diff.extra)
        assertEquals(listOf(8 to 9) to listOf(8 to 9), EditDiff.wordRanges("let x = 1", "let x = 2"))
        val long = EditDiff.rows("", (1..20).joinToString("\n"))
        assertEquals(12, long.rows.size)
        assertEquals(9, long.extra)
    }

    @Test
    fun `edit diff marks each changed word, as diffWordsWithSpace does`() {
        val (removed, added) = EditDiff.wordRanges("val a = foo(1)", "val b = foo(2)")
        assertEquals(listOf(4 to 5, 12 to 13), removed)
        assertEquals(listOf(4 to 5, 12 to 13), added)
        // An inserted line in the middle leaves the lines around it equal.
        val rows = EditDiff.rows("one\nthree", "one\ntwo\nthree").rows
        assertEquals(listOf(EditDiff.Kind.EQUAL, EditDiff.Kind.INS, EditDiff.Kind.EQUAL), rows.map { it.kind })
        assertEquals("two", rows[1].text)
    }

    @Test
    fun `myers finds the shortest script`() {
        val (removed, inserted) = EditDiff.diff("ABCABBA".toList(), "CBABAC".toList())
        // Myers' own example: an edit distance of 5.
        assertEquals(5, removed.count { it } + inserted.count { it })
    }

    @Test
    fun `tool summaries and delays`() {
        assertEquals("30m", formatDelay(1800.0))
        assertEquals("1m 30s", formatDelay(90.0))
        assertEquals("1h 1m 40s", formatDelay(3700.0))
        assertEquals("0s", formatDelay(0.0))
        val bash = toolSummary("Bash", WireJson.parse("""{"command":"rm -rf /tmp/test"}"""))
        assertEquals("rm -rf /tmp/test", bash.detail)
        assertTrue("a command without a description is shown as code", bash.monoDetail)
        assertEquals("c.swift", toolSummary("Read", WireJson.parse("""{"file_path":"/a/b/c.swift"}""")).detail)
        assertEquals("ppm-db · db_query", toolSummary("mcp__ppm-db__db_query", WireJson.parse("{}")).name)
        assertEquals("1/2 done", toolSummary("TodoWrite", WireJson.parse("""{"todos":[{"status":"completed"},{"status":"pending"}]}""")).detail)
        assertEquals("#7 → done", toolSummary("TaskUpdate", WireJson.parse("""{"taskId":7,"status":"done"}""")).detail)
    }

    private fun message(id: String, role: ChatItem.Role, vararg blocks: ChatBlock) = ChatItem(id, role, blocks.toList(), null)

    @Test
    fun `a turn's last answer carries the actions and the turn's text`() {
        val tool = ToolBlock(toolUseId = null, name = "Read", input = WireJson.parse("{}"))
        val items = listOf(
            message("u1", ChatItem.Role.USER, ChatBlock.Text("q1")),
            message("a1", ChatItem.Role.ASSISTANT, ChatBlock.Text("A")),
            message("a2", ChatItem.Role.ASSISTANT, ChatBlock.Tool(tool)),
            message("u2", ChatItem.Role.USER, ChatBlock.Text("q2")),
            message("a3", ChatItem.Role.ASSISTANT, ChatBlock.Text("B")),
        )
        val done = TranscriptRows.of(items, running = false)
        assertEquals(listOf(false, false, true, false, true), done.map { it.actions != null })
        assertEquals(listOf(null, null, "A", null, "B"), done.map { it.actions?.copyText })
        // The running turn's actions wait until it ends.
        assertEquals(listOf(false, false, true, false, false), TranscriptRows.of(items, running = true).map { it.actions != null })
        // Reply quotes a user's message itself, and for an answer the turn's last message with text.
        assertEquals(listOf("u1", null, "a1", "u2", "a3"), done.map { it.replyTarget?.id })
    }

    @Test
    fun `a turn is the run of answers since anything else`() {
        val tool = ToolBlock(toolUseId = null, name = "Read", input = WireJson.parse("{}"))
        val items = listOf(
            message("u1", ChatItem.Role.USER, ChatBlock.Text("q")),
            message("a1", ChatItem.Role.ASSISTANT, ChatBlock.Text("A")),
            message("e1", ChatItem.Role.SYSTEM, ChatBlock.Text("Error")),
            message("a2", ChatItem.Role.ASSISTANT, ChatBlock.Tool(tool)),
        )
        val rows = TranscriptRows.of(items, running = false)
        assertEquals(listOf(null, "A", null, ""), rows.map { it.actions?.copyText })
        // A turn of tool calls alone has nothing to quote.
        assertEquals(listOf("u1", "a1", null, null), rows.map { it.replyTarget?.id })
    }

    @Test
    fun `the thinking line shows only while nothing streams`() {
        val idle = ChatState(sessionId = "s")
        assertNull(indicatorLabel(idle))
        assertEquals("Initializing", indicatorLabel(idle.copy(phase = SessionPhase.INITIALIZING)))
        val streaming = idle.copy(phase = SessionPhase.STREAMING, live = listOf(ChatBlock.Text("hi")))
        assertNull(indicatorLabel(streaming))
        val tool = ToolBlock(toolUseId = "t", name = "Read", input = WireJson.parse("{}"), result = ToolOutcome("x", false))
        assertEquals("Processing", indicatorLabel(streaming.copy(live = listOf(ChatBlock.Tool(tool)))))
        assertEquals("Compacting messages...", indicatorLabel(streaming.copy(compacting = true)))
    }

    @Test
    fun `long output folds`() {
        assertTrue(isLongOutput("1\n2\n3\n4"))
        assertTrue(isLongOutput("x".repeat(201)))
        assertEquals(false, isLongOutput("1\n2\n3"))
    }

    @Test
    fun `token counts read as fmtTokens writes them, toFixed's rounding included`() {
        val web = mapOf(
            0L to "0", 950L to "950", 1_000L to "1.0k", 1_049L to "1.0k", 1_050L to "1.1k", 1_250L to "1.3k",
            9_950L to "9.9k", 9_999L to "10.0k", 10_000L to "10k", 10_500L to "11k", 15_500L to "16k",
            999_999L to "1000k", 1_000_000L to "1.0M", 1_050_000L to "1.1M", 1_250_000L to "1.3M", 123_456_789L to "123.5M",
        )
        for ((n, text) in web) assertEquals("$n", text, fmtTokens(n))
    }

    @Test
    fun `the compaction divider's title says how it ran, the context before and after, and how long`() {
        assertEquals(
            "Ran with /compact · 152k → 12k tokens · took 1m 5s",
            compactionDetail(Compaction(manual = true, preTokens = 152_000, postTokens = 12_000, savedTokens = 140_000, durationMs = 64_600)),
        )
        assertEquals(
            "Ran automatically on a full context window · 9.5k → 950 tokens",
            compactionDetail(Compaction(manual = false, preTokens = 9_500, postTokens = 950, savedTokens = 8_550)),
        )
        assertEquals("took 42s", compactionDetail(Compaction(true, 1, 1, 0, 41_500)).substringAfterLast(" · "))
    }
}
