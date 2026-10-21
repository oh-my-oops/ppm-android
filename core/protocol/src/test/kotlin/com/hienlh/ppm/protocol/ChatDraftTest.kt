package com.hienlh.ppm.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A draft as the server keeps it (`draft.service.ts`) and as `use-draft.ts` writes it. */
class ChatDraftTest {
    private val reply = ReplyReference(
        sessionId = "s-1", providerId = "claude", messageId = "m-9", sdkUuid = "u-7", role = ReplyReference.Role.ASSISTANT,
        timestamp = "2026-10-20T01:02:03.000Z", quote = "a <b> & c", truncated = false,
    )

    @Test
    fun `the server's row reads into text, files and the reply riding in the text`() {
        val stored = ChatReply.encode("half a thought", reply)
        val row = WireJson.parse(
            """{"content":${jsString(stored)},"attachments":"[{\"name\":\"a.png\",\"path\":\"/up/1-a.png\"},{\"bad\":1}]","updatedAt":"2026-10-20 01:02:03"}""",
        )
        val draft = ChatDraft.from(row)
        assertEquals(ChatDraft("half a thought", listOf(DraftAttachment("a.png", "/up/1-a.png")), reply), draft)
        assertEquals(stored, draft?.storedContent)
        assertEquals("""[{"name":"a.png","path":"/up/1-a.png"}]""", draft?.storedAttachments)
    }

    @Test
    fun `no row is no draft, and an unreadable file list is no files`() {
        assertNull(ChatDraft.from(WireJson.parse("null")))
        assertEquals(emptyList<DraftAttachment>(), ChatDraft.from(WireJson.parse("""{"content":"x","attachments":"oops"}"""))?.attachments)
        assertEquals("[]", ChatDraft("x").storedAttachments)
    }

    @Test
    fun `file names are written as JSON stringify writes them`() {
        val draft = ChatDraft("", listOf(DraftAttachment("q\"uote\\ é.txt", "/up/\n")))
        assertEquals("""[{"name":"q\"uote\\ é.txt","path":"/up/\n"}]""", draft.storedAttachments)
        assertTrue(ChatDraft("").isEmpty)
    }

    private fun jsString(text: String) = buildString { appendJsString(text) }
}
