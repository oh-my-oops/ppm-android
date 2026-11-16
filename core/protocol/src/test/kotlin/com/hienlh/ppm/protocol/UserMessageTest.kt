package com.hienlh.ppm.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `tests/unit/web/user-message-parse.test.ts`: the bubble's parse and the composer's recall. */
class UserMessageTest {
    private fun parse(content: String) = ParsedUserMessage.parse(content)

    private fun draft(content: String) = ParsedUserMessage.composerDraft(content)

    private fun inline(path: String) = "[Attached image (contents included in this message): $path]"

    @Test
    fun `leaves plain text untouched`() {
        val p = parse("just a question")
        assertEquals("just a question", p.text)
        assertNull(p.agent)
        assertNull(p.command)
        assertEquals(emptyList<String>(), p.files)
    }

    @Test
    fun `splits the agent delegation prefix off as a chip`() {
        val p = parse("Use the planner agent to draft the migration")
        assertEquals("planner", p.agent)
        assertEquals("draft the migration", p.text)
    }

    @Test
    fun `pulls single and multi attachment markers out of the body`() {
        assertEquals(listOf("/tmp/a.png"), parse("[Attached file: /tmp/a.png]\nlook at this").files)
        val multi = parse("[Attached files:\n/tmp/a.png\n/tmp/b.png\n]\ncompare these")
        assertEquals(listOf("/tmp/a.png", "/tmp/b.png"), multi.files)
        assertEquals("compare these", multi.text)
    }

    @Test
    fun `the typed text leaves the attachments out and brings a command back as typed`() {
        assertEquals("look at this", parse(inline("/u/1c5040cb-image.png") + "\n\nlook at this").typedText)
        assertEquals("", parse(inline("/u/1c5040cb-image.png")).typedText)
        assertEquals("/review src\n\nplease", parse("<command-name>review</command-name><command-args>src</command-args>\nplease").typedText)
    }

    @Test
    fun `extracts the IDE-opened-file context tag`() {
        val p = parse("<ide_opened_file>The user opened the file /repo/src/app.ts in the IDE.</ide_opened_file>\nexplain this")
        assertEquals("/repo/src/app.ts", p.idePath)
        assertEquals("explain this", p.text)
    }

    @Test
    fun `collects system-injected tags as badges, not body text`() {
        val p = parse("<system-reminder>be brief</system-reminder>hello")
        assertEquals("hello", p.text)
        assertEquals(listOf("Context"), p.tags.map { it.label })
    }

    @Test
    fun `folds slash-command args into the display text but keeps body separate`() {
        val p = parse("<command-name>/ak:plan</command-name><command-args>add auth</command-args>")
        assertEquals(ParsedUserMessage.SlashCommand("/ak:plan", "add auth"), p.command)
        assertEquals("add auth", p.text)
        assertEquals("", p.body)
    }

    @Test
    fun `takes the reply off first and terminal fences before the text`() {
        val reply = ReplyReference(
            sessionId = "s", providerId = "claude", messageId = "m", role = ReplyReference.Role.USER,
            timestamp = "2026-10-01T00:00:00Z", quote = "earlier", truncated = false,
        )
        val p = parse(ChatReply.encode("```bash\nls -la\n```\n\nwhat is this <local-command-stdout>out</local-command-stdout>", reply))
        assertEquals(reply, p.replyTo)
        assertEquals(listOf("ls -la"), p.terminalBlocks)
        assertEquals("what is this out", p.text)
    }

    @Test
    fun `labels an unlisted namespaced tag by its name and leaves other tags as text`() {
        val ns = "antml"
        val p = parse("<task-notification>done</task-notification><$ns:some-thing x=\"1\">v</$ns:some-thing><other>kept</other>")
        assertEquals(listOf("Task Result" to "done", "some thing" to "v"), p.tags.map { it.label to it.content })
        assertEquals("<other>kept</other>", p.text)
    }

    @Test
    fun `composer draft returns plain text as-is`() {
        assertEquals(null to "just a question", draft("just a question"))
    }

    @Test
    fun `composer draft restores the agent as a chip instead of inline prose`() {
        assertEquals("planner" to "draft the migration", draft("Use the planner agent to draft the migration"))
    }

    @Test
    fun `composer draft drops attachment markers`() {
        assertEquals(null to "look at this", draft("[Attached file: /tmp/a.png]\nlook at this"))
    }

    @Test
    fun `composer draft drops injected IDE context`() {
        val raw = "<ide_opened_file>The user opened the file /repo/src/app.ts in the IDE.</ide_opened_file>\nexplain this"
        assertEquals(null to "explain this", draft(raw))
    }

    @Test
    fun `composer draft rebuilds a slash command into its typed form`() {
        assertEquals(null to "/ak:plan add auth", draft("<command-name>ak:plan</command-name><command-args>add auth</command-args>"))
    }

    @Test
    fun `composer draft does not double the slash when the stored name already has one`() {
        assertEquals(null to "/ak:plan add auth", draft("<command-name>/ak:plan</command-name><command-args>add auth</command-args>"))
    }

    @Test
    fun `composer draft keeps an argument-less command usable`() {
        assertEquals(null to "/clear", draft("<command-name>/clear</command-name>"))
    }

    @Test
    fun `composer draft handles an attachment and an agent together`() {
        assertEquals("tester" to "check this", draft("[Attached file: /tmp/a.png]\nUse the tester agent to check this"))
    }

    @Test
    fun `composer draft gives back an empty draft for an attachment-only message`() {
        assertEquals(null to "", draft("[Attached file: /tmp/a.png]"))
    }

    @Test
    fun `extracts the path from an inline-image marker`() {
        assertEquals(listOf("C:/up/a.png") to "what is this?", ParsedUserMessage.attachments("${inline("C:/up/a.png")}\n\nwhat is this?"))
    }

    @Test
    fun `handles several inline images on one message`() {
        assertEquals(listOf("/up/a.png", "/up/b.png") to "both", ParsedUserMessage.attachments("${inline("/up/a.png")}\n${inline("/up/b.png")}\n\nboth"))
    }

    @Test
    fun `mixes an inline image with a plain file attachment`() {
        assertEquals(listOf("/up/a.png", "/up/b.heic") to "hi", ParsedUserMessage.attachments("${inline("/up/a.png")}\n\n[Attached file: /up/b.heic]\n\nhi"))
    }

    @Test
    fun `an inline image with no text leaves the text empty`() {
        assertEquals(listOf("/up/a.png") to "", ParsedUserMessage.attachments(inline("/up/a.png")))
    }

    @Test
    fun `the plain file marker still parses as before`() {
        assertEquals(listOf("/up/a.txt") to "read it", ParsedUserMessage.attachments("[Attached file: /up/a.txt]\n\nread it"))
    }
}
