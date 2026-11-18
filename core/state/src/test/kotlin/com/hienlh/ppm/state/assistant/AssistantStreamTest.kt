package com.hienlh.ppm.state.assistant

import com.hienlh.ppm.protocol.AssistantCard
import com.hienlh.ppm.protocol.AssistantWaiting
import com.hienlh.ppm.protocol.CardAnswer
import com.hienlh.ppm.protocol.CardPayload
import com.hienlh.ppm.state.ChatBlock
import com.hienlh.ppm.state.ChatItem
import com.hienlh.ppm.state.ToolBlock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

internal fun card(
    id: String,
    kind: String = "needs_decision",
    at: Long = 0,
    status: String = "open",
    project: String? = "web",
    payload: CardPayload = CardPayload(),
    okOffered: Boolean = true,
    answer: CardAnswer? = null,
    sessionId: String? = "m1",
) = AssistantCard(id, kind, project, project, sessionId, "Title $id", "Body", payload, status, answer, null, null, okOffered, at, at)

private fun user(id: String, at: Long, text: String = "hi") = ChatItem(id, ChatItem.Role.USER, listOf(ChatBlock.Text(text)), Instant.ofEpochMilli(at))

private fun reply(id: String, at: Long, vararg blocks: ChatBlock) = ChatItem(id, ChatItem.Role.ASSISTANT, blocks.toList(), Instant.ofEpochMilli(at))

/** `assistant-timeline-merge.ts`, `assistant-stream-filter.ts` and the stream's helpers. */
class AssistantStreamTest {
    @Test
    fun `an item goes before the first newer row, trails past the last, and is held above unloaded history`() {
        val rows = listOf(user("u1", 100), reply("a1", 200, ChatBlock.Text("x")), user("u2", 300))
        val placed = placeTimelineItems(rows, listOf(50L, 150L, 200L, 400L), { it }, olderHidden = true)
        assertEquals(1, placed.held)
        assertEquals(mapOf(1 to listOf(150L), 2 to listOf(200L)), placed.before)
        assertEquals(listOf(400L), placed.trailing)
        // A card that waits is never held: it goes before the oldest loaded row.
        val kept = placeTimelineItems(rows, listOf(50L), { it }, olderHidden = true, keep = { true })
        assertEquals(mapOf(0 to listOf(50L)), kept.before)
        // A row with no time takes the one before it.
        assertEquals(listOf(100L, 100L), rowTimes(listOf(user("a", 100), ChatItem("b", ChatItem.Role.USER, emptyList(), null))))
    }

    @Test
    fun `a project filter keeps only the turns that reached its manager`() {
        val send = ToolBlock("t1", "mcp__ppm-sessions__send_to_session", buildJsonObject { put("session_id", "m1") })
        val status = ToolBlock("t2", "ppm_sessions.session_status", buildJsonObject { put("session_ids", JsonArray(listOf(JsonPrimitive("m1")))) })
        val create = ToolBlock("t3", "create_session", buildJsonObject { put("project", "web") })
        val rows = listOf(
            user("u1", 1), reply("a1", 2, ChatBlock.Tool(send)),
            user("u2", 3), reply("a2", 4, ChatBlock.Text("nothing")),
            user("u3", 5), reply("a3", 6, ChatBlock.Tool(status)),
            user("u4", 7), reply("a4", 8, ChatBlock.Tool(create)),
        )
        assertEquals(setOf("u2", "a2"), hiddenByProjectFilter(rows, "web", "m1"))
        // With no manager, only creating one counts.
        assertEquals(setOf("u1", "a1", "u2", "a2", "u3", "a3"), hiddenByProjectFilter(rows, "web", null))
        assertEquals("send_to_session", bareToolName("mcp__ppm-sessions__send_to_session"))
        assertEquals("session_status", bareToolName("ppm_sessions.session_status"))
    }

    @Test
    fun `a wake turn answered no reply folds to one line counting the reports it read`() {
        val rows = listOf(
            user("u1", 1000), reply("a1", 1100, ChatBlock.Text("Sure")),
            user("u2", 2000, "[PPM] News from web"), reply("a2", 2100, ChatBlock.Text("[no reply]")),
            user("u3", 3000, "[PPM] News again"), reply("a3", 3100, ChatBlock.Text("Something to tell")),
        )
        val folded = collapsedWakeTurns(rows)
        assertEquals(listOf(CollapsedWakeTurn(listOf("u2", "a2"), 2000, 1000)), folded)
        val cards = listOf(card("r1", "done", at = 1500), card("r2", "needs_decision", at = 1800), card("x", "approval", at = 1600), card("old", "done", at = 900))
        assertEquals(2, reportsRead(cards, folded[0].since, folded[0].at))
        assertEquals("Assistant read 2 reports", wakeTurnText(2))
        assertEquals("Assistant read 1 report", wakeTurnText(1))
        assertEquals("Assistant read the manager news", wakeTurnText(0))
    }

    @Test
    fun `the stream draws its pages and every waiting card the filter lets in`() {
        val loaded = listOf(card("a", at = 5))
        val waiting = listOf(card("a", at = 5), card("b", at = 1, project = "api"), card("c", at = 2))
        assertEquals(listOf("a", "b", "c"), streamCards(loaded, waiting, null).map { it.id })
        assertEquals(listOf("a", "c"), streamCards(loaded, waiting, "web").map { it.id })
        assertTrue(needsEarlierCards(1, listOf(card("x", at = 5)), nextBefore = 5))
        assertFalse(needsEarlierCards(9, listOf(card("x", at = 5)), nextBefore = 5))
        assertFalse(needsEarlierCards(1, listOf(card("x", at = 5)), nextBefore = null))
    }

    @Test
    fun `waiting cards step urgent first, then oldest, and counts say when to read again`() {
        val list = listOf(card("a", at = 3), card("b", "urgent", at = 9), card("c", at = 1))
        assertEquals(listOf("b", "c", "a"), orderWaitingCards(list).map { it.id })
        val applied = applyOpenCard(list.sortedBy { it.createdAt }, card("a", at = 3, status = "answered"))
        assertEquals(listOf("c", "b"), applied.map { it.id })
        assertTrue(countsMatch(applied, AssistantWaiting(2, mapOf("web" to 2))))
        assertFalse(countsMatch(applied, AssistantWaiting(2, mapOf("web" to 1, "api" to 1))))
        assertEquals(listOf("b", "a"), upsertCard(listOf(card("a", at = 1)), card("b", at = 2)).map { it.id })
    }

    @Test
    fun `a card's time and how it ended`() {
        val zone = ZoneOffset.UTC
        val now = Instant.parse("2026-11-18T12:00:00Z").toEpochMilli()
        assertEquals("09:05", cardTime(Instant.parse("2026-11-18T09:05:00Z").toEpochMilli(), now, zone))
        assertEquals("Nov 3, 14:05", cardTime(Instant.parse("2026-11-03T14:05:00Z").toEpochMilli(), now, zone))
        val at = Instant.parse("2026-11-18T09:05:00Z").toEpochMilli()
        assertEquals("Dismissed · 09:05", cardStatusLine(card("a", status = "dismissed", answer = CardAnswer("user", "ppm", at)), now, zone))
        assertEquals("Allowed in PPM · 09:05", cardStatusLine(card("a", "approval", status = "answered", answer = CardAnswer("user", "ppm", at, approved = true)), now, zone))
        assertEquals("Sent with your OK", answerSummary(card("a", status = "answered", answer = CardAnswer("user", "ppm", at, note = "Sent with your OK")), now, zone))
        assertEquals("No longer waiting", cardStatusLine(card("a", status = "gone"), now, zone))
        assertEquals(null, cardStatusLine(card("a"), now, zone))
        assertTrue(isAnsweredDecision(card("a", status = "answered")))
        assertFalse(isAnsweredDecision(card("a", "done", status = "info")))
    }

    @Test
    fun `manager dots, deep links and which branch takes over as main`() {
        assertEquals(ManagerDot.RUNNING, managerDotState("running", 2))
        assertEquals(ManagerDot.NEEDS, managerDotState("idle", 2))
        assertEquals(ManagerDot.IDLE, managerDotState("idle", 0))
        assertEquals(AssistantDeepLink("abc-1", "codex"), parseAssistantSessionRef("codex/abc-1"))
        assertEquals(AssistantDeepLink("abc-1"), parseAssistantSessionRef("abc-1"))
        assertEquals(AssistantDeepLink("abc-1"), parseAssistantSessionRef("other/abc-1"))
        assertEquals(AssistantDeepLink(), parseAssistantSessionRef("claude/../etc"))
        assertTrue(branchTakesMain("main", "b", "main"))
        assertFalse(branchTakesMain("oneoff", "b", "main"))
        assertFalse(branchTakesMain("main", "main", "main"))
    }
}
