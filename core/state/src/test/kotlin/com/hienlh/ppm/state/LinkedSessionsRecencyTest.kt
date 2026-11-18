package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.SessionCreatedPush
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.SessionLink
import com.hienlh.ppm.protocol.SessionLinkChangedPush
import com.hienlh.ppm.protocol.SessionLinkRelation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The Sessions list's "last talked" times, its order and its filter (`linked-sessions.test.ts`). */
class LinkedSessionsRecencyTest {
    private val me = "aaaaaaaa-0000-4000-8000-000000000001"
    private val child = "cccccccc-2222-4000-8000-000000000002"
    private val peer = "dddddddd-3333-4000-8000-000000000003"

    private fun view(id: String, status: LinkedSessionStatus, at: Long?) =
        LinkedSessionView(id, "T $id", "claude", listOf(SessionLinkRelation.MESSAGED), status, at)

    @Test
    fun `waiting on the person comes first, then the newest interaction, then sessions with no time`() {
        val ordered = byRecentInteraction(
            listOf(
                view("old", LinkedSessionStatus.IDLE, 1_000),
                view("none", LinkedSessionStatus.RUNNING, null),
                view("new", LinkedSessionStatus.IDLE, 5_000),
                view("asks", LinkedSessionStatus.NEEDS_APPROVAL, 10),
                view("mid", LinkedSessionStatus.NEW_MESSAGE, 3_000),
            ),
        )
        assertEquals(listOf("asks", "new", "mid", "old", "none"), ordered.map { it.sessionId })
    }

    @Test
    fun `the filter matches every word in any order, ignoring case and accents`() {
        assertTrue(matchesLinkedFilter("Tự động deploy", "tu dong"))
        assertTrue(matchesLinkedFilter("Fix the login page", "LOGIN fix"))
        assertTrue(matchesLinkedFilter("Đổi tên", "doi"))
        assertTrue(matchesLinkedFilter("anything", "   "))
        assertFalse(matchesLinkedFilter("Fix the login page", "login signup"))
        assertEquals(8, LINKED_FILTER_MIN_ROWS)
    }

    @Test
    fun `the server's seeded times and live events stamp both sessions`() {
        var state = SessionCollabState()
        state = state.seedLinks(me, listOf(SessionLink(child, listOf(SessionLinkRelation.CHILD), lastInteractionAt = 2_000)))
        assertEquals(2_000L, state.interactedAt[me]?.get(child))
        assertEquals(2_000L, state.interactedAt[child]?.get(me))
        // An older time never replaces a newer one.
        state = state.seedLinks(me, listOf(SessionLink(child, listOf(SessionLinkRelation.CHILD), lastInteractionAt = 1_000)))
        assertEquals(2_000L, state.interactedAt[me]?.get(child))
        state = state.onLinkChanged(SessionLinkChangedPush("ppm", listOf(me, peer)), now = 9_000)
        assertEquals(9_000L, state.interactedAt[peer]?.get(me))
        val created = state.onSessionCreated(
            SessionCreatedPush(sessionId = "eeeeeeee-0000-4000-8000-000000000005", providerId = "claude", projectName = "ppm", title = "New", spawnedBy = me, createdAt = "2026-11-18T08:00:00Z"),
            now = 1,
        )
        assertEquals(Instant.parse("2026-11-18T08:00:00Z").toEpochMilli(), created.interactedAt[me]?.get("eeeeeeee-0000-4000-8000-000000000005"))
    }

    @Test
    fun `a session's time is the latest of its creation, its messages and what the server said`() {
        val sessions = listOf(
            SessionInfo(id = me, providerId = "claude", title = "Me"),
            SessionInfo(id = child, providerId = "claude", title = "Kid", spawnedBy = me, createdAt = Instant.ofEpochMilli(4_000)),
            SessionInfo(id = peer, providerId = "claude", title = "Peer"),
        )
        val linked = collectLinkedSessions(
            sessionId = me,
            list = sessions,
            items = emptyList(),
            liveLinks = mapOf(peer to listOf(SessionLinkRelation.MESSAGED)),
            interactedAt = mapOf(child to 3_000L, peer to 7_000L),
        )
        assertEquals(4_000L, linked.single { it.sessionId == child }.lastInteractionAt)
        assertEquals(7_000L, linked.single { it.sessionId == peer }.lastInteractionAt)
        val unknown = collectLinkedSessions(me, sessions, emptyList(), liveLinks = mapOf(peer to listOf(SessionLinkRelation.MESSAGED)))
        assertNull(unknown.single { it.sessionId == peer }.lastInteractionAt)
    }
}
