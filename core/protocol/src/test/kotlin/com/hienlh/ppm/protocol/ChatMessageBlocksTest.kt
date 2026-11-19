package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A stored user message's blocks, read as `decodeMessageBlocks` reads them. The fixtures are what
 * the web's own `encodeMessageBlocks` wrote (`wp2-message-blocks.json`, made with bun from
 * `src/shared/chat-message-blocks.ts`), so both clients must agree byte for byte.
 */
class ChatMessageBlocksTest {
    private val fixtures = WireJson.parse(checkNotNull(javaClass.getResource("/wp2-message-blocks.json")).readText()) as JsonObject

    private fun fixture(name: String) = fixtures.getValue(name).jsonPrimitive.content

    @Test
    fun `a message from another session gives back its body and sender`() {
        val decoded = ChatMessageBlocks.decode(fixture("fromOnly"))
        assertEquals("Please run the tests.", decoded.content)
        val from = checkNotNull(decoded.fromSession)
        assertEquals("sender-session-1", from.sessionId)
        assertEquals("codex", from.providerId)
        assertEquals("Fix the login bug <now>", from.title)
        assertEquals("turn-abcdef12", from.turnId)
        assertNull(decoded.turnRefs)
        // The bubble's parse strips it too: no markup is left for the text.
        val parsed = ParsedUserMessage.parse(fixture("fromOnly"))
        assertEquals("Please run the tests.", parsed.text)
        assertEquals(from, parsed.fromSession)
    }

    @Test
    fun `every block comes off in the web's order`() {
        val decoded = ChatMessageBlocks.decode(fixture("all"))
        assertEquals("Body text", decoded.content)
        assertEquals("m1", decoded.replyTo?.messageId)
        assertEquals("sender-session-1", decoded.fromSession?.sessionId)
        val refs = decoded.turnRefs!!
        assertEquals(listOf("u:12345678-aaaa", "019a-turn-0001"), refs.map { it.turnId })
        assertEquals(listOf(3L, 12L), refs.map { it.n.toLong() })
        assertEquals("Review – café", refs[0].title)
        assertEquals("other", refs[1].projectName)
        assertEquals("2026-11-08T01:02:03.004Z", refs[1].at)

        val parsed = ParsedUserMessage.parse(fixture("all"))
        assertEquals("Body text", parsed.text)
        assertEquals(refs, parsed.turnRefs)
        assertEquals(null to "Body text", ParsedUserMessage.composerDraft(fixture("all")))
    }

    @Test
    fun `turn references decode whichever project the header was written for`() {
        for (name in listOf("refsOwn", "refsUnknown")) {
            val decoded = ChatTurnRefs.decode(fixture(name))
            assertEquals(name, "See these", decoded.content)
            assertEquals(name, 2, decoded.turnRefs?.size)
        }
        val legacy = ChatTurnRefs.decode(fixture("legacy"))
        assertEquals("Old one", legacy.content)
        assertEquals("Old chip", legacy.turnRefs?.single()?.title)
    }

    @Test
    fun `a block whose header does not match its data stays text`() {
        val raw = fixture("mismatch")
        assertEquals(ChatTurnRefs.Decoded(raw, null), ChatTurnRefs.decode(raw))
        // Typed markers are broken by the encoder and never decode.
        val forged = fixture("forged")
        assertNull(ChatMessageBlocks.decode(forged).fromSession)
        assertEquals(forged, ParsedUserMessage.parse(forged).text)
    }

    @Test
    fun `an empty block whose start and end share a line break stays text`() {
        val from = "\n\n<ppm-from-session-v1>\n</ppm-from-session-v1>"
        assertEquals(from, ChatMessageBlocks.decode(from).content)
        val refs = "x\n\n<ppm-turnrefs-v1>\n</ppm-turnrefs-v1>"
        assertEquals(refs, ChatMessageBlocks.decode(refs).content)
    }

    @Test
    fun `a session tool's card is read by its kind`() {
        val create = SessionCollabApprovalInput.from(
            SESSION_COLLAB_APPROVAL,
            WireJson.parse("""{"kind":"create","title":"Write tests","providerId":"codex","model":null,"task":"a\nb","depth":2}"""),
        )
        assertEquals(SessionCollabApprovalInput.Create("Write tests", "codex", null, "a\nb", 2), create)
        assertEquals(SessionCollabAnswer.entries, create!!.answers)
        val limit = SessionCollabApprovalInput.from(
            SESSION_COLLAB_APPROVAL,
            WireJson.parse("""{"kind":"pair_limit","targetSessionId":"t1","targetTitle":"Other","sent":10,"limit":10,"grant":5}"""),
        )
        assertEquals(listOf(SessionCollabAnswer.ALLOW_ONCE, SessionCollabAnswer.DENY), limit!!.answers)
        // The PPM Assistant messaging a chat: once or not at all, never for the whole session.
        val message = SessionCollabApprovalInput.from(
            SESSION_COLLAB_APPROVAL,
            WireJson.parse(
                """{"kind":"message","targetSessionId":"s1","targetTitle":"Fix login","projectName":"web","providerId":"claude",""" +
                    """"permissionMode":"bypassPermissions","modeSource":"chat","message":"Go on\nplease"}""",
            ),
        )
        assertEquals(SessionCollabApprovalInput.Message("s1", "Fix login", "web", "claude", "bypassPermissions", "Go on\nplease"), message)
        assertEquals(listOf(SessionCollabAnswer.ALLOW_ONCE, SessionCollabAnswer.DENY), message!!.answers)
        assertNull(SessionCollabApprovalInput.from("Bash", WireJson.parse("""{"kind":"create"}""")))
        assertNull(SessionCollabApprovalInput.from(SESSION_COLLAB_APPROVAL, WireJson.parse("""{"kind":"other"}""")))
    }

    @Test
    fun `option previews line up with the questions as they are read`() {
        val input = WireJson.parse(
            """{"questions":[
                {"question":"Which plan?","options":[{"label":"A","preview":"step 1\n  step 2"},{"label":"B"},{"description":"no label"}]},
                {"header":"no question"},
                {"question":"Second?","options":[{"label":"X"}]}
            ]}""",
        )
        val previews = ChatQuestions.optionPreviews(input)
        assertEquals(listOf(listOf("step 1\n  step 2", null), listOf<String?>(null)), previews)
        assertEquals(true, ChatQuestions.hasOptionPreviews(previews[0]))
        assertEquals(false, ChatQuestions.hasOptionPreviews(previews[1]))
        val options = listOf(Question.Option("A", null), Question.Option("B", null))
        assertEquals(1, ChatQuestions.previewOptionIndex(options, 1, emptyList()))
        // "Other" focused: what is selected.
        assertEquals(1, ChatQuestions.previewOptionIndex(options, 2, listOf("B")))
        assertEquals(-1, ChatQuestions.previewOptionIndex(options, 2, emptyList()))
    }
}
