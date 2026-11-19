package com.hienlh.ppm.state.assistant

import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.protocol.AssistantCard
import com.hienlh.ppm.protocol.CardDraft
import com.hienlh.ppm.protocol.CardOption
import com.hienlh.ppm.protocol.CardPayload
import com.hienlh.ppm.protocol.InlineImage
import com.hienlh.ppm.protocol.WireJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `decision-set-model.ts` and `decision-set-send.ts`. */
class DecisionSetTest {
    private val draft = CardPayload(draft = CardDraft("api‮", "go", "h1"))
    private val choices = CardPayload(options = listOf(CardOption("a", "Alpha", "first"), CardOption("b", "Beta")))

    @Test
    fun `a card offers its choices, or one OK for its draft, or none when not shown whole`() {
        assertEquals(listOf("Alpha", "Beta"), decisionTiles(card("c", payload = choices)).map { it.label })
        val ok = decisionTiles(card("c", payload = draft)).single()
        assertEquals("OK, send this draft", ok.label)
        assertEquals("Sends the draft above to api⟨U+202E⟩, character for character.", ok.description)
        assertEquals("OK, go ahead", decisionTiles(card("c")).single().label)
        assertTrue(decisionTiles(card("c", okOffered = false)).isEmpty())
    }

    @Test
    fun `a pick is for the card as it was, and a changed card loses it and is left out of Send all`() {
        val c = card("c", payload = draft)
        val picks = mapOf("c" to DecisionPick(DecisionAnswer.Ok("h1"), cardVersion(c)))
        assertEquals(listOf(c to DecisionAnswer.Ok("h1")), sendPlan(listOf(c), picks))
        val changed = c.copy(payload = CardPayload(draft = CardDraft("api", "go now", "h2")))
        assertNull(currentPick(changed, picks))
        assertEquals(listOf("c"), outdatedPicks(listOf(changed), picks))
        assertTrue(sendPlan(listOf(changed), picks).isEmpty())
        // A reply needs words or a picture, and a manager chat to go to.
        assertTrue(answerIsReady(c, DecisionAnswer.Reply("yes")))
        assertTrue(!answerIsReady(c, DecisionAnswer.Reply("  ")))
        assertTrue(!answerIsReady(c.copy(sessionId = null), DecisionAnswer.Reply("yes")))
    }

    @Test
    fun `answers in a few words, project counts and the tab order`() {
        val c = card("c", payload = choices)
        assertEquals("Chose “Beta”", answerLabel(c, DecisionAnswer.Choose("b")))
        assertEquals("OK — go ahead", answerLabel(card("x"), DecisionAnswer.Ok(null)))
        val image = ReplyImage("i", "a.png", InlineImage("QQ==", "image/png"))
        assertEquals("Reply: “yes” + 1 picture", answerLabel(c, DecisionAnswer.Reply(" yes ", listOf(image))))
        assertEquals("Reply with 2 pictures", answerLabel(c, DecisionAnswer.Reply("", listOf(image, image))))
        assertEquals("web 2 · api 1", projectCounts(listOf(card("a"), card("b", project = "api"), card("c"))))
        assertEquals(listOf("a", "c", "d"), nextTabOrder(listOf("a", "b", "c"), listOf("c", "a", "d"), emptySet()))
        assertEquals(listOf("a", "b", "c"), nextTabOrder(listOf("a", "b"), listOf("a", "c"), setOf("b")))
    }

    @Test
    fun `the tabs are the cards that wait, plus one on its way from here, and nothing answered elsewhere stays`() {
        val (a, c, d) = listOf("a", "c", "d").map { card(it) }
        val all = { _: AssistantCard -> true }
        // b was answered on Telegram, d dismissed: neither keeps a tab, whatever the order still says.
        assertEquals(listOf("a", "c"), decisionTabs(listOf("a", "b", "c", "d"), listOf(a, c), emptyMap(), all).map { it.id })
        // c is being sent from here: off the waiting list already, its tab stays until the send lands.
        assertEquals(listOf("a", "c"), decisionTabs(listOf("a", "c"), listOf(a), mapOf("c" to c), all).map { it.id })
        // A card the order has not caught up with yet still gets its tab, at the end.
        assertEquals(listOf("a", "d"), decisionTabs(listOf("a"), listOf(a, d), emptyMap(), all).map { it.id })
        // The surface's scope (a manager chat: its own cards) applies to the held card too.
        val other = card("o").copy(sessionId = "elsewhere")
        assertEquals(listOf("a"), decisionTabs(listOf("a", "o"), listOf(a), mapOf("o" to other)) { it.sessionId != "elsewhere" }.map { it.id })
        assertTrue(decisionTabs(emptyList(), emptyList(), emptyMap(), all).isEmpty())
    }

    @Test
    fun `the tab shown is the one chosen, once it leaves the one now in its place, at first the first`() {
        assertEquals(0, activeTabIndex(listOf("a", "b", "c"), null, 0))
        assertEquals(2, activeTabIndex(listOf("a", "b", "c"), "c", 0))
        // b (index 1) was answered elsewhere: c now stands where it stood, and opens.
        assertEquals(1, activeTabIndex(listOf("a", "c"), "b", 1))
        // The last tab left: the one before it opens, never the first by default.
        assertEquals(1, activeTabIndex(listOf("a", "b"), "c", 2))
        assertEquals(-1, activeTabIndex(emptyList(), "a", 0))
        // After a send the next card that still waits opens, wrapping round, never one being sent.
        assertEquals("c", nextWaitingTab(listOf("a", "b", "c"), "b", emptySet()))
        assertEquals("a", nextWaitingTab(listOf("a", "b", "c"), "c", emptySet()))
        assertEquals("c", nextWaitingTab(listOf("a", "b", "c"), "a", setOf("b")))
        assertNull(nextWaitingTab(listOf("a"), "a", emptySet()))
    }

    private class FakeApi(val failWith: (String) -> Exception? = { null }) : DecisionSendApi {
        val calls = mutableListOf<String>()
        override suspend fun ok(cardId: String, draftSha256: String?): AssistantCard? {
            calls += "ok:$cardId:$draftSha256"
            failWith(cardId)?.let { throw it }
            return card(cardId, status = "answered")
        }

        override suspend fun choose(cardId: String, optionId: String): AssistantCard? {
            calls += "choose:$cardId:$optionId"
            failWith(cardId)?.let { throw it }
            return card(cardId, status = "answered")
        }

        override suspend fun reply(cardId: String, text: String, confirmAge: Boolean, images: List<InlineImage>): AssistantCard? {
            calls += "reply:$cardId:$confirmAge:${images.size}"
            if (!confirmAge) failWith(cardId)?.let { throw it }
            return card(cardId, status = "answered")
        }
    }

    private fun refusal(error: String, data: String? = null) = PpmError.Server(409, error, data = data?.let(WireJson::parse))

    private val openCard = """{"id":"b","kind":"needs_decision","status":"open","title":"t","body":"","payload":{},"createdAt":1,"updatedAt":1}"""

    @Test
    fun `Send all goes on past a card answered elsewhere and confirms a stale reply only while it waits`() = runBlocking {
        val api = FakeApi { id ->
            when (id) {
                "a" -> refusal("already_answered")
                "b" -> refusal("confirm", """{"question":"It is a day old. Send anyway?","card":$openCard}""")
                "c" -> refusal("The draft is not the one this OK was given for")
                else -> null
            }
        }
        val plan = listOf(
            card("a") to DecisionAnswer.Ok(null),
            card("b") to DecisionAnswer.Reply("late words"),
            card("c") to DecisionAnswer.Ok("h"),
            card("d") to DecisionAnswer.Choose("x"),
        )
        val seen = mutableListOf<String>()
        val results = sendAllDecisions(plan, api) { id, _ -> seen += id }
        assertEquals(listOf("a", "b", "c", "d"), seen)
        assertEquals(ALREADY_ANSWERED_ELSEWHERE, (results["a"] as SendOutcome.Elsewhere).message)
        assertTrue(results["b"] is SendOutcome.Sent)
        assertTrue("reply:b:true:0" in api.calls)
        assertTrue(results["c"] is SendOutcome.Changed)
        assertTrue(results["d"] is SendOutcome.Sent)
        // A single send asks instead.
        assertEquals("It is a day old. Send anyway?", (sendDecision(card("b"), DecisionAnswer.Reply("x"), api) as SendOutcome.Confirm).question)
    }
}
