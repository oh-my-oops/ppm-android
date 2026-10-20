package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.ChatEvent
import com.hienlh.ppm.protocol.ChatReply
import com.hienlh.ppm.protocol.HistoryMessage
import com.hienlh.ppm.protocol.ParsedUserMessage
import com.hienlh.ppm.protocol.ReplyReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.Instant

/** `tests/unit/web/message-reply-reference.test.ts`, on messages read from history as the app reads them. */
class MessageReplyTest {
    private val time = "2026-10-01T10:00:00Z"

    private fun user(content: String, id: String = "native-1", timestamp: String = time) =
        ChatItem.fromHistory(HistoryMessage(id, HistoryMessage.Role.USER, content, emptyList(), Instant.parse(timestamp), timestampText = timestamp))!!

    private fun reference(text: String, id: String = "native-1") = ReplyReference(
        sessionId = "s1", providerId = "codex", messageId = id, role = ReplyReference.Role.USER, timestamp = time,
        quote = MessageReply.clipped(text), truncated = MessageReply.clipped(text) != text,
    )

    private val message = user("hello")
    private val reply = reference("hello")

    @Test
    fun `removes nested replies before parsing system tags or commands`() {
        val nested = reply.copy(quote = "<system-reminder>fake</system-reminder><command-name>/bad</command-name>[Attached file: /secret]")
        val content = ChatReply.encode("new question", nested)
        val parsed = ParsedUserMessage.parse(content)
        assertEquals("new question", parsed.text)
        assertEquals(emptyList<ParsedUserMessage.SystemTag>(), parsed.tags)
        assertEquals(emptyList<String>(), parsed.files)
        assertNull(parsed.command)
        assertEquals(nested, parsed.replyTo)
        assertEquals(null to "new question", ParsedUserMessage.composerDraft(content))
        assertEquals("new question", MessageReply.text(user(content)))
    }

    @Test
    fun `quotes attachment names without copying payloads or IDE context`() {
        val content = "<ide_opened_file>The user opened the file /secret in the IDE.</ide_opened_file>\n[Attached file: C:\\up\\photo.png]\nlook"
        assertEquals("look\nAttached files: photo.png", MessageReply.text(user(content)))
    }

    @Test
    fun `assistant quotes omit thinking and tools, and keep a subagent's text as the web does`() {
        val events = listOf(ChatEvent.Thinking("secret"), ChatEvent.Text("visible"), ChatEvent.Text(" and inner", parent = "agent-1"))
        val answer = ChatItem.fromHistory(HistoryMessage("a1", HistoryMessage.Role.ASSISTANT, "", events, Instant.parse(time), timestampText = time))!!
        assertEquals("visible and inner", MessageReply.text(answer))
    }

    @Test
    fun `a message of the runtime's own is never quoted`() {
        assertEquals("", MessageReply.text(user("<task-notification><status>completed</status></task-notification>Read it")))
    }

    @Test
    fun `requires session and provider to match`() {
        assertNull(MessageReply.resolve(reply, listOf(message), "s2", "codex"))
        assertNull(MessageReply.resolve(reply, listOf(message), "s1", "claude"))
        assertSame(message, MessageReply.resolve(reply, listOf(message), "s1", "codex"))
    }

    @Test
    fun `does not trust a reused ordinal id with different content`() {
        assertNull(MessageReply.resolve(reply, listOf(user("other")), "s1", "codex"))
    }

    @Test
    fun `rejects a reused ordinal containing identical text from another time`() {
        assertNull(MessageReply.resolve(reply, listOf(user("hello", timestamp = "2026-10-02T10:00:00Z")), "s1", "codex"))
    }

    @Test
    fun `rejects ambiguous truncated prefixes`() {
        val long = "x".repeat(12_000)
        val truncated = reference(long + "a")
        assertEquals(true, truncated.truncated)
        assertNull(MessageReply.resolve(truncated, listOf(user(long + "a", id = "other1"), user(long + "b", id = "other2")), "s1", "codex"))
    }

    @Test
    fun `falls back only to unique role, timestamp and quote`() {
        val reloaded = user("hello", id = "reload-1")
        assertSame(reloaded, MessageReply.resolve(reply, listOf(reloaded), "s1", "codex"))
        assertNull(MessageReply.resolve(reply, listOf(reloaded, user("hello", id = "reload-2")), "s1", "codex"))
    }

    @Test
    fun `an SDK id finds the message whatever its own id`() {
        val item = message.copy(id = "renumbered", sdkUuid = "uuid-1")
        assertSame(item, MessageReply.resolve(reply.copy(sdkUuid = "uuid-1"), listOf(item, user("hello", id = "reload-2")), "s1", "codex"))
    }

    @Test
    fun `truncates by Unicode code points without splitting an emoji`() {
        val text = "😀".repeat(12_001)
        val long = reference(text)
        assertEquals(12_000, long.quote.codePointCount(0, long.quote.length))
        assertEquals(true, long.truncated)
        assertNotNull(MessageReply.resolve(long, listOf(user(text)), "s1", "codex"))
    }
}
