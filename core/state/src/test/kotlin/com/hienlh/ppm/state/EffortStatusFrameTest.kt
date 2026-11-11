package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.EffortSettingReport
import com.hienlh.ppm.protocol.EffortStatusReport
import com.hienlh.ppm.protocol.ObservedEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What decides a Claude session's effort (`effort_status`, `session_state.effortStatus`), as `use-chat.ts` keeps it. */
class EffortStatusFrameTest {
    private val maxFromSettings = EffortStatusReport(
        env = EffortSettingReport("max", "userSettings", "~/.claude/settings.json"),
        observed = ObservedEffort(requested = "high", applied = "max", model = "claude-opus-5-5[1m]"),
        supported = listOf("low", "medium", "high", "xhigh", "max"),
    )

    @Test
    fun `effort_status carries the whole report, and null means nothing is known`() {
        val chat = ChatTranscript()
        chat.handle(frame("""{"type":"effort_status","status":{"env":{"value":"max","source":"userSettings","file":"~/.claude/settings.json"},"observed":{"requested":"high","applied":"max","model":"claude-opus-5-5[1m]"},"supported":["low","medium","high","xhigh","max"]}}"""))
        assertEquals(maxFromSettings, chat.effortStatus)
        chat.handle(frame("""{"type":"effort_status","status":null}"""))
        assertNull(chat.effortStatus)
    }

    @Test
    fun `a greeting sets it only when it carries the key`() {
        val chat = ChatTranscript()
        chat.handle(frame("""{"type":"session_state","sessionId":"s","phase":"idle","effortStatus":{"cap":{"value":"medium","source":"projectSettings"}}}"""))
        assertEquals(EffortStatusReport(cap = EffortSettingReport("medium", "projectSettings")), chat.effortStatus)
        // An older server, or a builder that leaves it out: what was known stays.
        chat.handle(frame("""{"type":"session_state","sessionId":"s","phase":"idle"}"""))
        assertEquals("medium", chat.effortStatus?.cap?.value)
        // A model change answers with null: the old model's limits no longer hold.
        chat.handle(frame("""{"type":"session_state","sessionId":"s","phase":"idle","effortStatus":null}"""))
        assertNull(chat.effortStatus)
    }
}
