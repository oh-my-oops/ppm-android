package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A session schedule's run block, read as `decodeMessageBlocks` and `parseUserMessage` read it. The
 * fixtures are what the web's own encoders wrote (`schedule-run-blocks.json`, made with bun from
 * `src/shared/chat-schedule-run.ts` and `chat-message-blocks.ts`).
 */
class ChatScheduleRunTest {
    private val fixtures = WireJson.parse(checkNotNull(javaClass.getResource("/schedule-run-blocks.json")).readText()) as JsonObject

    private fun fixture(name: String) = fixtures.getValue(name).jsonPrimitive.content

    private val morning = ScheduleRunReference(
        scheduleId = 7,
        name = "Morning <check> & \"PRs\"",
        cron = "0 8 * * 1-5",
        timezone = "Europe/London",
        scheduledFor = "2026-11-16T08:00:00.000Z",
        firedAt = "2026-11-16T08:03:10.500Z",
        catchUp = true,
        coalesced = false,
    )

    @Test
    fun `a block in front of the prompt comes off with its reference`() {
        val decoded = ChatMessageBlocks.decode(fixture("before"))
        assertEquals("Check the open PRs\nand summarise.", decoded.content)
        assertEquals(morning, decoded.scheduleRun)
        val parsed = ParsedUserMessage.parse(fixture("before"))
        assertEquals(morning, parsed.scheduleRun)
        assertEquals("Check the open PRs\nand summarise.", parsed.typedText)
    }

    @Test
    fun `a block after a slash command leaves the command as the text`() {
        val decoded = ChatMessageBlocks.decode(fixture("after"))
        assertEquals("/review 42", decoded.content)
        assertEquals(morning.copy(timezone = null, catchUp = false, coalesced = true), decoded.scheduleRun)
    }

    @Test
    fun `the block sits under a reply block, and a lone closing tag in the prompt stays text`() {
        val decoded = ChatMessageBlocks.decode(fixture("withReply"))
        assertEquals("Ping </ppm-schedule-run-v1>", decoded.content)
        assertEquals("m1", decoded.replyTo?.messageId)
        assertEquals(morning, decoded.scheduleRun)
    }

    @Test
    fun `a block whose header does not match, that is not first, or that another session sent stays text`() {
        for (name in listOf("forged", "midText")) {
            val decoded = ChatMessageBlocks.decode(fixture(name))
            assertNull(name, decoded.scheduleRun)
            assertEquals(name, fixture(name), decoded.content)
        }
        val from = ChatMessageBlocks.decode(fixture("fromSession"))
        assertEquals("sender-session-1", from.fromSession?.sessionId)
        assertNull(from.scheduleRun)
        assertTrue(from.content.startsWith(ChatScheduleRun.BLOCK_START))
    }

    @Test
    fun `a scheduled command as Claude records it keeps its name and loses the block from its args`() {
        val parsed = ParsedUserMessage.parse(fixture("cmdRecord"))
        assertEquals(morning.copy(timezone = null, catchUp = false, coalesced = true), parsed.scheduleRun)
        assertEquals(ParsedUserMessage.SlashCommand("/review", "42"), parsed.command)
        assertEquals("42", parsed.text)
        assertEquals("/review 42", parsed.typedText)

        val bare = ParsedUserMessage.parse(fixture("cmdNoArgsRecord"))
        assertEquals("Standup", bare.scheduleRun?.name)
        assertEquals(ParsedUserMessage.SlashCommand("/standup", null), bare.command)
        assertEquals("", bare.text)
        assertEquals("/standup", bare.typedText)
    }

    @Test
    fun `args are read for a block only from a message that is Claude's own command record`() {
        val parsed = ParsedUserMessage.parse(fixture("cmdNotRecord"))
        assertNull(parsed.scheduleRun)
        assertTrue(parsed.command?.args.orEmpty().contains("<ppm-schedule-run-v1>"))
    }

    @Test
    fun `text is put on one line as the block carries it`() {
        assertEquals("a b c d", ChatScheduleRun.normalizeText("  a\t\tb c\u0001  d  "))
        assertEquals("ab", ChatScheduleRun.normalizeText("abc", max = 2))
    }

    @Test
    fun `a reference with a field the encoder would never write is no reference`() {
        val good = """{"version":1,"scheduleId":3,"name":"n","cron":"* * * * *","timezone":null,"scheduledFor":"2026-11-16T08:00:00Z","firedAt":"2026-11-16T08:00:00Z","catchUp":false,"coalesced":false}"""
        val ref = checkNotNull(ScheduleRunReference.from(WireJson.parse(good)))
        assertEquals("2026-11-16T08:00:00.000Z", ref.scheduledFor)
        for ((from, to) in listOf(
            "\"version\":1" to "\"version\":2",
            "\"scheduleId\":3" to "\"scheduleId\":0",
            "\"scheduleId\":3" to "\"scheduleId\":\"3\"",
            "\"name\":\"n\"" to "\"name\":\" n\"",
            "\"timezone\":null" to "\"timezone\":\"Europe London\"",
            "\"timezone\":null," to "",
            "\"catchUp\":false" to "\"catchUp\":0",
            "\"firedAt\":\"2026-11-16T08:00:00Z\"" to "\"firedAt\":\"soon\"",
        )) {
            assertNull("$from → $to", ScheduleRunReference.from(WireJson.parse(good.replace(from, to))))
        }
        assertFalse(ChatScheduleRun.startsWithSlashCommand("// note"))
        assertFalse(ChatScheduleRun.startsWithSlashCommand("/ x"))
        assertTrue(ChatScheduleRun.startsWithSlashCommand("/tmp/x.log check"))
    }
}
