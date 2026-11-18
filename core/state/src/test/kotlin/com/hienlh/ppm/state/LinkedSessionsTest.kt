package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.ChatFromSession
import com.hienlh.ppm.protocol.ChatReply
import com.hienlh.ppm.protocol.FromSessionReference
import com.hienlh.ppm.protocol.LinkedSessionUnlinks
import com.hienlh.ppm.protocol.ReplyReference
import com.hienlh.ppm.protocol.SessionCollabApprovalPush
import com.hienlh.ppm.protocol.SessionCreatedPush
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.SessionLinkChangedPush
import com.hienlh.ppm.protocol.SessionLinkRelation
import com.hienlh.ppm.protocol.SessionUnread
import com.hienlh.ppm.protocol.ToolOutcome
import com.hienlh.ppm.protocol.WireJson
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Ports `linked-session-unlinks.test.ts`, `linked-sessions-status.test.ts`, and
 * `linked-sessions-panel.test.tsx` (pure collection, status, collab events, and unlink rules).
 */
class LinkedSessionsTest {
    private val me = "aaaaaaaa-0000-4000-8000-000000000001"
    private val parent = "aaaaaaaa-0000-4000-8000-000000000000"
    private val child = "cccccccc-2222-4000-8000-000000000002"
    private val peer = "dddddddd-3333-4000-8000-000000000003"
    private val aId = "aaaaaaaa-1111-4000-8000-00000000000a"
    private val bId = "bbbbbbbb-2222-4000-8000-00000000000b"

    private fun fromUserItem(
        sessionId: String,
        sentAt: String,
        providerId: String = "claude",
        title: String = "T",
        withReply: Boolean = false,
    ): ChatItem {
        val base = ChatFromSession.encode(
            "hi",
            FromSessionReference(
                sessionId = sessionId,
                providerId = providerId,
                projectName = "ppm",
                title = title,
                sentAt = sentAt,
            ),
        )
        val content = if (withReply) {
            ChatReply.encode(
                base,
                ReplyReference(
                    sessionId = me,
                    providerId = "claude",
                    messageId = "m1",
                    role = ReplyReference.Role.ASSISTANT,
                    timestamp = sentAt,
                    quote = "earlier",
                    truncated = false,
                ),
            )
        } else {
            base
        }
        return ChatItem(
            id = "$sessionId-$sentAt",
            role = ChatItem.Role.USER,
            blocks = listOf(ChatBlock.Text(content)),
            timestamp = Instant.parse(sentAt),
            timestampText = sentAt,
        )
    }

    private fun view(id: String, status: LinkedSessionStatus = LinkedSessionStatus.IDLE) = LinkedSessionView(
        sessionId = id,
        title = id,
        providerId = "claude",
        relations = listOf(SessionLinkRelation.MESSAGED),
        status = status,
    )

    @Test
    fun `canUnlink lets idle and finished sessions go, never working or waiting ones`() {
        assertTrue(canUnlink(LinkedSessionStatus.IDLE))
        assertTrue(canUnlink(LinkedSessionStatus.NEW_MESSAGE))
        assertFalse(canUnlink(LinkedSessionStatus.RUNNING))
        assertFalse(canUnlink(LinkedSessionStatus.NEEDS_APPROVAL))
    }

    @Test
    fun `lastMessageFrom keeps the latest message each session sent, ignoring own messages`() {
        val last = lastMessageFrom(
            listOf(
                fromUserItem(aId, "2026-11-01T00:00:00.000Z"),
                fromUserItem(aId, "2026-11-03T00:00:00.000Z", withReply = true),
                fromUserItem(bId, "2026-11-02T00:00:00.000Z"),
                ChatItem(
                    id = "own",
                    role = ChatItem.Role.USER,
                    blocks = listOf(ChatBlock.Text("plain")),
                    timestamp = Instant.parse("2026-11-09T00:00:00.000Z"),
                    timestampText = "2026-11-09T00:00:00.000Z",
                ),
            ),
        )
        assertEquals(Instant.parse("2026-11-03T00:00:00.000Z").toEpochMilli(), last[aId])
        assertEquals(Instant.parse("2026-11-02T00:00:00.000Z").toEpochMilli(), last[bId])
        assertEquals(2, last.size)
    }

    @Test
    fun `splitUnlinked hides unlinked sessions and brings them back on a newer message or while busy`() {
        val linked = listOf(view("a"), view("b"), view("c"))
        val at = Instant.parse("2026-11-05T00:00:00.000Z").toEpochMilli()

        val first = splitUnlinked(linked, mapOf("b" to at), emptyMap())
        assertEquals(listOf("a", "c"), first.visible.map { it.sessionId })
        assertEquals(listOf("b"), first.hidden.map { it.sessionId })

        assertEquals(listOf("b"), splitUnlinked(linked, mapOf("b" to at), mapOf("b" to at - 1)).hidden.map { it.sessionId })
        assertEquals(emptyList<String>(), splitUnlinked(linked, mapOf("b" to at), mapOf("b" to at + 1)).hidden.map { it.sessionId })

        assertEquals(1, splitUnlinked(listOf(view("b", LinkedSessionStatus.IDLE)), mapOf("b" to at), emptyMap()).hidden.size)
        assertEquals(1, splitUnlinked(listOf(view("b", LinkedSessionStatus.NEW_MESSAGE)), mapOf("b" to at), emptyMap()).hidden.size)
        assertEquals(1, splitUnlinked(listOf(view("b", LinkedSessionStatus.RUNNING)), mapOf("b" to at), emptyMap()).visible.size)
        assertEquals(1, splitUnlinked(listOf(view("b", LinkedSessionStatus.NEEDS_APPROVAL)), mapOf("b" to at), emptyMap()).visible.size)
    }

    @Test
    fun `updateUnlinks records per-session timestamps, drops empty chats, and enforces both caps`() {
        val last = mapOf("a" to 500L)
        val map = updateUnlinks(emptyMap(), "chat", listOf("a", "b"), unlink = true) { last[it] ?: 0L }
        assertEquals(mapOf("chat" to mapOf("a" to 500L, "b" to 0L)), map)
        assertEquals(1, splitUnlinked(listOf(view("b")), map["chat"], mapOf("b" to 1L)).visible.size)
        assertEquals(1, splitUnlinked(listOf(view("a")), map["chat"], mapOf("a" to 500L)).hidden.size)

        val one = updateUnlinks(emptyMap(), "chat", listOf("a", "b"), unlink = true) { 10L }
        assertEquals(mapOf("chat" to mapOf("a" to 10L, "b" to 10L)), one)
        assertEquals(mapOf("chat" to mapOf("b" to 10L)), updateUnlinks(one, "chat", listOf("a"), unlink = false))
        assertEquals(emptyMap<String, Map<String, Long>>(), updateUnlinks(one, "chat", listOf("a", "b"), unlink = false))

        var perChat = emptyMap<String, Map<String, Long>>()
        for (i in 0 until LinkedSessionUnlinks.MAX_UNLINKS_PER_CHAT + 5) {
            perChat = updateUnlinks(perChat, "chat", listOf("s$i"), unlink = true) { i.toLong() }
        }
        assertEquals(LinkedSessionUnlinks.MAX_UNLINKS_PER_CHAT, perChat.getValue("chat").size)
        assertNull(perChat.getValue("chat")["s0"])

        var chats = emptyMap<String, Map<String, Long>>()
        for (i in 0 until LinkedSessionUnlinks.MAX_UNLINK_CHATS + 3) {
            chats = updateUnlinks(chats, "c$i", listOf("x"), unlink = true) { i.toLong() }
        }
        assertEquals(LinkedSessionUnlinks.MAX_UNLINK_CHATS, chats.size)
        assertNull(chats["c0"])
        assertTrue("c${LinkedSessionUnlinks.MAX_UNLINK_CHATS + 2}" in chats)
    }

    @Test
    fun `collectLinkedSessions finds parent, children, and delivered sends while ignoring self and refused sends`() {
        val list = listOf(
            SessionInfo(id = me, providerId = "claude", title = "Me", spawnedBy = parent),
            SessionInfo(id = parent, providerId = "claude", title = "Planner"),
            SessionInfo(id = child, providerId = "claude", title = "Tests", spawnedBy = me),
        )
        val items = listOf(
            fromUserItem(peer, "2026-11-06T00:00:00.000Z", providerId = "codex", title = "Peer"),
            ChatItem(
                id = "2",
                role = ChatItem.Role.ASSISTANT,
                blocks = listOf(
                    ChatBlock.Tool(
                        ToolBlock(
                            toolUseId = "t1",
                            name = "mcp__ppm-sessions__send_to_session",
                            input = buildJsonObject {
                                put("session_id", child)
                                put("message", "go")
                            },
                            result = null,
                        ),
                    ),
                ),
                timestamp = null,
            ),
        )
        val linked = collectLinkedSessions(me, list, items)
        assertEquals(
            listOf(
                Triple(parent, "Planner", listOf(SessionLinkRelation.PARENT)),
                Triple(child, "Tests", listOf(SessionLinkRelation.CHILD)),
                Triple(peer, "Peer", listOf(SessionLinkRelation.MESSAGED)),
            ),
            linked.map { Triple(it.sessionId, it.title, it.relations) },
        )
        assertEquals("codex", linked.first { it.sessionId == peer }.providerId)
        assertEquals(emptyList<LinkedSession>(), collectLinkedSessions(me, listOf(SessionInfo(me, "claude", "Me", spawnedBy = me)), emptyList()))

        val refused = ChatItem(
            id = "refused",
            role = ChatItem.Role.ASSISTANT,
            blocks = listOf(
                ChatBlock.Tool(
                    ToolBlock(
                        toolUseId = "t2",
                        name = "mcp__ppm-sessions__send_to_session",
                        input = buildJsonObject {
                            put("session_id", "dddddddd-3333-4000-8000-000000000099")
                            put("message", "hi")
                        },
                        result = ToolOutcome("No PPM session with that id is known in this project.", isError = true),
                    ),
                ),
            ),
            timestamp = null,
        )
        assertEquals(emptyList<LinkedSession>(), collectLinkedSessions(me, emptyList(), listOf(refused)))

        val deliveredJson = """{"delivered":"started","status":{"sessionId":"$child","title":"Tests","providerId":"codex","phase":"idle"}}"""
        val accepted = ChatItem(
            id = "ok",
            role = ChatItem.Role.ASSISTANT,
            blocks = listOf(
                ChatBlock.Tool(
                    ToolBlock(
                        toolUseId = "t3",
                        name = "mcp__ppm-sessions__send_to_session",
                        input = buildJsonObject {
                            put("session_id", child)
                            put("message", "hi")
                        },
                        result = ToolOutcome(deliveredJson, isError = false),
                    ),
                ),
            ),
            timestamp = null,
        )
        assertEquals(
            listOf(LinkedSession(child, "Tests", "codex", listOf(SessionLinkRelation.MESSAGED))),
            collectLinkedSessions(me, emptyList(), listOf(accepted)),
        )
    }

    @Test
    fun `an Antigravity session links as a sender, and its session tools as calls`() {
        // An agy chat sends with PPM's session tools now: its block names "antigravity", and its
        // tool calls are named `mcp_ppm-sessions_<tool>`.
        val from = fromUserItem(peer, "2026-11-15T00:00:00.000Z", providerId = "antigravity", title = "Agy")
        val created = """{"sessionId":"$child","title":"Tests","providerId":"antigravity"}"""
        val call = ChatItem(
            id = "agy",
            role = ChatItem.Role.ASSISTANT,
            blocks = listOf(
                ChatBlock.Tool(
                    ToolBlock(
                        toolUseId = "t4",
                        name = "mcp_ppm-sessions_create_session",
                        input = buildJsonObject {
                            put("title", "Tests")
                            put("task", "run them")
                        },
                        result = ToolOutcome(created, isError = false),
                    ),
                ),
            ),
            timestamp = null,
        )
        assertEquals(
            listOf(
                LinkedSession(child, "Tests", "antigravity", listOf(SessionLinkRelation.CHILD)),
                // Last talked when its message was sent.
                LinkedSession(peer, "Agy", "antigravity", listOf(SessionLinkRelation.MESSAGED), java.time.Instant.parse("2026-11-15T00:00:00Z").toEpochMilli()),
            ),
            collectLinkedSessions(me, emptyList(), listOf(call, from)),
        )
    }

    @Test
    fun `linkedSessionStatus ranks approval above running above unread, and tracks collab events`() {
        assertEquals(LinkedSessionStatus.IDLE, linkedSessionStatus(child, emptySet(), emptyMap(), emptyMap()))
        assertEquals(LinkedSessionStatus.RUNNING, linkedSessionStatus(child, setOf(child), emptyMap(), emptyMap()))
        assertEquals(
            LinkedSessionStatus.NEW_MESSAGE,
            linkedSessionStatus(child, emptySet(), mapOf(child to SessionUnread("done", 1, "ppm", null)), emptyMap()),
        )
        assertEquals(
            LinkedSessionStatus.NEEDS_APPROVAL,
            linkedSessionStatus(child, setOf(child), emptyMap(), mapOf(child to "r1")),
        )
        assertEquals(
            LinkedSessionStatus.NEEDS_APPROVAL,
            linkedSessionStatus(child, emptySet(), mapOf(child to SessionUnread("collab_approval", 1, "ppm", null)), emptyMap()),
        )

        var state = SessionCollabState()
        val fresh = "eeeeeeee-4444-4000-8000-000000000004"
        state = state.onSessionCreated(
            SessionCreatedPush(
                sessionId = fresh,
                providerId = "codex",
                projectName = "ppm",
                title = "Fresh",
                spawnedBy = me,
                parentTitle = "Planner",
                parentProviderId = "claude",
                depth = 1,
                createdAt = "2026-11-06T01:00:00.000Z",
            ),
        )
        assertEquals(listOf(SessionLinkRelation.CHILD), state.links[me]?.get(fresh))
        assertEquals(listOf(SessionLinkRelation.PARENT), state.links[fresh]?.get(me))
        assertEquals(AnnouncedSession("Planner", "claude", "ppm"), state.announced[me])

        state = state.onLinkChanged(SessionLinkChangedPush("ppm", listOf(me, peer)))
        assertEquals(listOf(SessionLinkRelation.MESSAGED), state.links[me]?.get(peer))

        state = state.onApprovalChanged(SessionCollabApprovalPush("ppm", me, "r1", "pending"))
        assertEquals("r1", state.pendingApprovals[me])
        state = state.onApprovalChanged(SessionCollabApprovalPush("ppm", me, "r1", "resolved"))
        assertNull(state.pendingApprovals[me])

        val malformed = ChatFromSession.decode("plain\n\n<ppm-from-session-v1>\nwrong\n${WireJson.parse("{}")}\n</ppm-from-session-v1>")
        assertNull(malformed.fromSession)
    }

    @Test
    fun `byAttention lists needs_approval first, then running, then new_message, then idle stably`() {
        fun kid(n: Int, status: LinkedSessionStatus) = LinkedSessionView(
            sessionId = "cccccccc-0000-4000-8000-00000000000$n",
            title = "Kid $n",
            providerId = "claude",
            relations = listOf(SessionLinkRelation.CHILD),
            status = status,
        )
        val ordered = byAttention(
            listOf(
                kid(1, LinkedSessionStatus.IDLE),
                kid(2, LinkedSessionStatus.NEW_MESSAGE),
                kid(3, LinkedSessionStatus.RUNNING),
                kid(4, LinkedSessionStatus.IDLE),
                kid(5, LinkedSessionStatus.NEEDS_APPROVAL),
            ),
        )
        assertEquals(listOf("Kid 5", "Kid 3", "Kid 2", "Kid 1", "Kid 4"), ordered.map { it.title })
    }
}
