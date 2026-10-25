package com.hienlh.ppm.protocol

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** The reply a Codex question card sends (`shared/chat-questions.ts`), byte for byte. */
class ChatQuestionsTest {
    private fun questions(json: String) = Json.parseToJsonElement(json).let { it as kotlinx.serialization.json.JsonArray }.mapNotNull(Question::from)

    @Test
    fun `encodes as encodeURIComponent does`() {
        // Expected values printed by node's encodeURIComponent.
        assertEquals("item_01", encodeUriComponent("item_01"))
        assertEquals("a%2Fb%20c%3F%26%3D%23%2B", encodeUriComponent("a/b c?&=#+"))
        assertEquals("-_.!~*'()", encodeUriComponent("-_.!~*'()"))
        assertEquals("%C3%A9%F0%9F%99%82", encodeUriComponent("é🙂"))
        assertEquals("msg%3A1%3Bx%40y%2Cz%24", encodeUriComponent("msg:1;x@y,z$"))
    }

    @Test
    fun `a question keeps Codex's id, and is keyed by its text without one`() {
        val (withId, withoutId) = questions("""[{"id":"q1","question":"Which?","options":[]},{"question":"Why?","options":[{"label":"A"}]}]""")
        assertEquals("q1" to "q1", withId.id to withId.key)
        assertEquals(null to "Why?", withoutId.id to withoutId.key)
    }

    @Test
    fun `answers go out in the questions' order, keyed by id, and a skip says so`() {
        val asked = questions("""[{"id":"scope","question":"Which files?","options":[{"label":"All"}]},{"question":"Run tests?","options":[]}]""")
        assertEquals("Answers to Codex question call%2F7:\n", ChatQuestions.replyPrefix("call/7"))
        assertEquals(
            "Answers to Codex question call%2F7:\nWhich files?\nAll, src\n\nRun tests?\nyes",
            ChatQuestions.formatReply("call/7", asked, mapOf("Run tests?" to "yes", "scope" to "All, src", "Which files?" to "ignored")),
        )
        // A question left out of the answers is still listed, with nothing under it.
        assertEquals("Answers to Codex question i:\nWhich files?\n\n\nRun tests?\nno", ChatQuestions.formatReply("i", asked, mapOf("Run tests?" to "no")))
        assertEquals("Answers to Codex question i:\nSkipped by the user.", ChatQuestions.formatReply("i", asked, null))
    }
}
