package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.ChatClientMessage
import com.hienlh.ppm.protocol.ChatFrame
import com.hienlh.ppm.protocol.PromptCacheState
import com.hienlh.ppm.protocol.SessionStateFrame
import com.hienlh.ppm.protocol.TurnOptions
import com.hienlh.ppm.protocol.TurnUsage
import com.hienlh.ppm.protocol.WireJson
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The composer's choices and the prompt cache's clock (`PPMSelfTest/ComposerChoicesTests.swift`). */
class ComposerChoicesTest {
    private fun json(text: String) = WireJson.parse(text)

    @Test
    fun `Shift+Tab steps through the modes and wraps`() {
        assertEquals(PermissionMode.BYPASS_PERMISSIONS, PermissionMode.FALLBACK)
        assertEquals(PermissionMode.DEFAULT, PermissionMode.BYPASS_PERMISSIONS.next)
        assertEquals(PermissionMode.ACCEPT_EDITS, PermissionMode.DEFAULT.next)
        assertEquals(
            listOf("Ask before edits", "Edit automatically", "Plan mode", "Bypass permissions"),
            PermissionMode.entries.map { it.label },
        )
        assertEquals(PermissionMode.PLAN, PermissionMode.of("plan"))
    }

    @Test
    fun `Extra is xhigh, never extra`() {
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), Effort.entries.map { it.value })
        assertEquals("Extra", Effort.label("xhigh"))
        assertEquals("turbo", Effort.label("turbo"))
        assertEquals("", Effort.label(null))
        assertEquals(Effort.HIGH, Effort.DEFAULT)
    }

    @Test
    fun `priority cycles Queue, Later, Interrupt`() {
        assertEquals(MessagePriority.NEXT, MessagePriority.DEFAULT)
        assertEquals(MessagePriority.LATER, MessagePriority.NEXT.following)
        assertEquals(MessagePriority.NOW, MessagePriority.LATER.following)
        assertEquals(MessagePriority.NEXT, MessagePriority.NOW.following)
        assertEquals(listOf("Queue", "Later", "Interrupt"), MessagePriority.entries.map { it.label })
    }

    @Test
    fun `model names read like the web's`() {
        assertEquals("Opus 5.5", ModelNames.displayName("claude-opus-5-5"))
        assertEquals("Sonnet 4", ModelNames.displayName("claude-sonnet-4-20250514"))
        assertEquals("Haiku 5.5", ModelNames.displayName("claude-haiku-5-5-20261001"))
        assertEquals("gpt-5-codex", ModelNames.displayName("gpt-5-codex"))
        assertEquals("Opus 5", ModelNames.shortLabel("Claude Opus 5"))
        assertEquals("Fable 5.1", ModelNames.shortLabel("claude  Fable 5.1"))
        assertEquals("Opus Claude", ModelNames.shortLabel("Opus Claude"))
        assertEquals("Opus 5.5 · Extra", ModelNames.chipLabel("Opus 5.5", "xhigh"))
        assertEquals("Model", ModelNames.chipLabel("Model", null))
    }

    @Test
    fun `prompt cache is silent until a turn cached enough, then counts down, then says why it is gone`() {
        val hour = 3_600_000.0
        assertEquals(PromptCacheStatus.Unknown, PromptCacheStatus.of(null, 0.0))
        assertEquals(PromptCacheStatus.Unknown, PromptCacheStatus.of(PromptCacheState(ttlMs = hour), 0.0))
        assertEquals(
            PromptCacheStatus.Unknown,
            PromptCacheStatus.of(PromptCacheState(ttlMs = hour, lastTurnEndedAt = 0.0, billedPrefixTokens = 19_999.0), 1.0),
        )

        val warm = PromptCacheStatus.of(PromptCacheState(ttlMs = hour, lastTurnEndedAt = 1_000.0, billedPrefixTokens = 50_000.0), 1_000.0)
        assertEquals("60m", warm.label)
        assertEquals("Prompt cache warm, about 60 min left.", warm.detail)
        // A clock behind the server's still shows a full window, not a longer one.
        assertEquals(
            "60m",
            PromptCacheStatus.of(PromptCacheState(ttlMs = hour, lastTurnEndedAt = 5_000.0, billedPrefixTokens = 50_000.0), 0.0).label,
        )
        assertEquals(
            "1m",
            PromptCacheStatus.of(PromptCacheState(ttlMs = hour, lastTurnEndedAt = 0.0, billedPrefixTokens = 50_000.0), hour - 1).label,
        )

        val cold = PromptCacheStatus.of(
            PromptCacheState(ttlMs = hour, lastTurnEndedAt = 0.0, billedPrefixTokens = 50_000.0, contextTokens = 58_700.0),
            2 * hour + 13 * 60_000,
        )
        assertEquals("2h 13m", cold.label)
        assertEquals("Prompt cache expired 2h 13m ago. Your next message re-sends about 59k tokens at full price.", cold.detail)
        val unmeasured = PromptCacheStatus.of(
            PromptCacheState(ttlMs = hour, lastTurnEndedAt = 0.0, billedPrefixTokens = 50_000.0),
            3 * 86_400_000.0 + 1,
        )
        assertEquals("3d", unmeasured.label)
        assertEquals("Prompt cache expired 3d ago. Your next message re-sends the whole transcript at full price.", unmeasured.detail)

        // A compaction is cold at once, whatever was billed.
        val compacted = PromptCacheStatus.of(PromptCacheState(ttlMs = hour, compactedAt = 0.0), 10_000.0)
        assertEquals("1m", compacted.label)
        assertEquals(
            "Prompt cache does not cover the compacted conversation. Compacted 1m ago, so your next message will re-cache it.",
            compacted.detail,
        )
    }

    @Test
    fun `token counts at the precision the decision needs`() {
        assertEquals("999", PromptCacheStatus.tokens(999.0))
        assertEquals("1k", PromptCacheStatus.tokens(1_499.0))
        assertEquals("1.3M", PromptCacheStatus.tokens(1_250_000.0))
        assertEquals("2.0M", PromptCacheStatus.tokens(1_999_999.0))
        assertEquals("12M", PromptCacheStatus.tokens(12_400_000.0))
        assertEquals("1d 2h", PromptCacheStatus.idle(26 * 3_600_000.0))
        assertEquals("1h", PromptCacheStatus.idle(3_600_000.0))
    }

    @Test
    fun `a message carries only the choices that are set, and the set frames`() {
        val full = TurnOptions(permissionMode = "plan", priority = "now", model = "m", effort = "xhigh", thinking = false)
        assertEquals(
            json("""{"type":"message","clientMessageId":"c1","content":"hi","permissionMode":"plan","priority":"now","model":"m","effort":"xhigh","thinking":false}"""),
            ChatClientMessage.Message("c1", "hi", full).json,
        )
        assertEquals(
            json("""{"type":"message","clientMessageId":"c1","content":"hi","effort":"low"}"""),
            ChatClientMessage.Message("c1", "hi", TurnOptions(effort = "low")).json,
        )
        assertEquals(json("""{"type":"set_model","model":"claude-opus-5-5"}"""), ChatClientMessage.SetModel("claude-opus-5-5").json)
        assertEquals(json("""{"type":"set_effort","effort":"max"}"""), ChatClientMessage.SetEffort("max").json)
        assertEquals(json("""{"type":"set_thinking","enabled":false}"""), ChatClientMessage.SetThinking(false).json)
    }

    @Test
    fun `session_state reads the model, effort, thinking and the cache`() {
        val frame = ChatFrame.from(
            json(
                """{"type":"session_state","phase":"idle","model":"claude-opus-5-5","effort":"high","thinking":false,
                   "promptCache":{"ttlMs":3600000,"lastTurnEndedAt":5}}""",
            ),
        )
        val state = (frame as ChatFrame.SessionState).state
        assertEquals("claude-opus-5-5", state.model)
        assertEquals("high", state.effort)
        assertEquals(false, state.thinking)
        assertEquals(PromptCacheState(ttlMs = 3_600_000.0, lastTurnEndedAt = 5.0), state.promptCache)
        val bare = (ChatFrame.from(json("""{"type":"session_state","phase":"idle","effort":""}""")) as ChatFrame.SessionState).state
        assertNull(bare.model)
        assertNull("an empty effort is none", bare.effort)
        assertNull(bare.thinking)
        assertNull(bare.promptCache)
    }

    @Test
    fun `a pick holds against an older report, and a confirmed one follows the server`() {
        val settings = ChatSettings()
        settings.apply(SessionStateFrame(model = "opus", effort = "high", thinking = true))
        assertEquals(TurnOptions(model = "opus", effort = "high"), settings.turnOptions(TurnOptions()))
        assertEquals(ChatClientMessage.SetEffort("max"), settings.pickEffort(Effort.MAX))
        assertEquals(ChatClientMessage.SetThinking(false), settings.pickThinking(false))
        assertEquals(ChatClientMessage.SetModel("sonnet"), settings.pickModel("sonnet"))
        // A greeting sent before the picks arrived reports the old values.
        settings.apply(SessionStateFrame(model = "opus", effort = "high", thinking = true))
        assertEquals(listOf("sonnet", "max"), listOf(settings.model, settings.effort))
        assertFalse(settings.thinking)
        assertEquals(
            TurnOptions(permissionMode = "plan", model = "sonnet", effort = "max", thinking = false),
            settings.turnOptions(TurnOptions(permissionMode = "plan")),
        )
        // Confirmed: from now on the server's word is taken again.
        settings.apply(SessionStateFrame(model = "sonnet", effort = "max", thinking = false))
        settings.apply(SessionStateFrame(model = "haiku", effort = "low", thinking = true))
        assertEquals(listOf("haiku", "low"), listOf(settings.model, settings.effort))
        assertTrue(settings.thinking)
        // Thinking picked once keeps riding on messages, as the web's ref does.
        assertEquals(false, settings.turnOptions(TurnOptions()).thinking)
    }

    @Test
    fun `a report without the cache drops it, as the web does`() {
        val settings = ChatSettings()
        settings.apply(SessionStateFrame(promptCache = PromptCacheState(ttlMs = 1.0)))
        assertNotNull(settings.promptCache)
        settings.apply(SessionStateFrame(model = "opus"))
        assertNull(settings.promptCache)
        assertEquals("opus", settings.model)
    }

    @Test
    fun `a finished turn restarts the cache's clock, as done does on the web`() {
        val settings = ChatSettings()
        val usage = TurnUsage("m", 1_000, 10, 40_000, 9_000, 0.1, 0.8, coldStart = false)
        settings.turnEnded(usage, now = 5)
        assertNull("a cache nobody reported", settings.promptCache)
        settings.apply(
            SessionStateFrame(
                promptCache = PromptCacheState(300_000.0, lastTurnEndedAt = 1.0, billedPrefixTokens = 7.0, contextTokens = 58_700.0, compactedAt = 2.0),
            ),
        )
        settings.turnEnded(usage, now = 9_000)
        // What the turn did not measure keeps the figure in hand; a compaction ends with the turn that re-cached it.
        assertEquals(
            PromptCacheState(300_000.0, lastTurnEndedAt = 9_000.0, billedPrefixTokens = 50_000.0, contextTokens = 58_700.0),
            settings.promptCache,
        )
        settings.turnEnded(usage.copy(contextTokens = 61_000.0, cacheTtlMs = 3_600_000.0, compactedAt = 8_000.0), now = 10_000)
        assertEquals(
            PromptCacheState(3_600_000.0, lastTurnEndedAt = 10_000.0, billedPrefixTokens = 50_000.0, contextTokens = 61_000.0, compactedAt = 8_000.0),
            settings.promptCache,
        )
    }

    @Test
    fun `the prompt cache is read from session_state, absent without a TTL`() {
        val state = PromptCacheState.from(json("""{"ttlMs":300000,"lastTurnEndedAt":1760000000000,"billedPrefixTokens":42000}"""))
        assertEquals(300_000.0, state?.ttlMs)
        assertEquals(42_000.0, state?.billedPrefixTokens)
        assertNull(state?.contextTokens)
        assertNull(PromptCacheState.from(JsonNull))
    }

    @Test
    fun `a sent message carries the transcript's options`() {
        val chat = ChatTranscript()
        val message = chat.send("hello", TurnOptions(permissionMode = "plan"))
        assertEquals("plan", message.options.permissionMode)
        assertEquals(listOf(message.clientMessageId), chat.items.map { it.id })
    }
}
