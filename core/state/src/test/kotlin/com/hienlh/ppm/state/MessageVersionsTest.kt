package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.HistoryPage
import com.hienlh.ppm.protocol.VersionGroup
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The web's version numbering and fork anchors (`message-ordinals.ts`, `message-list.tsx`, `chat-history-page.ts`). */
class MessageVersionsTest {
    private fun user(id: String, text: String, sdkUuid: String? = null) = ChatItem(id, ChatItem.Role.USER, listOf(ChatBlock.Text(text)), null, sdkUuid)

    private fun answer(id: String, vararg blocks: ChatBlock, sdkUuid: String? = null) = ChatItem(id, ChatItem.Role.ASSISTANT, blocks.toList(), null, sdkUuid)

    private fun page(text: String): HistoryPage = HistoryPage.from(json(text))!!

    @Test
    fun `user messages are numbered among those the web shows, after the ones above the window`() {
        val items = listOf(
            user("u1", "first"),
            answer("a1", ChatBlock.Text("reply")),
            user("u2", " \n "),
            user("u3", "second"),
            ChatItem("s1", ChatItem.Role.SYSTEM, listOf(ChatBlock.Text("note")), null),
            user("u4", "third"),
        )
        assertArrayEquals(intArrayOf(1, 0, 0, 2, 0, 3), MessageVersions.ordinals(items, 0))
        assertArrayEquals(intArrayOf(5, 0, 0, 6, 0, 7), MessageVersions.ordinals(items, 4))
    }

    @Test
    fun `the conversation from before a compaction takes none of the session's numbers`() {
        val prefix = PreCompact.idPrefix("/home/u/.claude/projects/p/old.jsonl")
        val items = listOf(
            user(prefix + "u1", "old question", sdkUuid = "old-1"),
            answer(prefix + "a1", ChatBlock.Text("old answer"), sdkUuid = "old-2"),
            user("summary", "This session is being continued from a previous conversation."),
            answer("a1", ChatBlock.Text("ok")),
            user("u1", "new question"),
        )
        assertArrayEquals(intArrayOf(0, 0, 1, 0, 2), MessageVersions.ordinals(items, 0))
        // A fork still continues from what is shown above, as the web's does.
        assertEquals("old-2", MessageVersions.anchor(items, 2, predecessor = null))
    }

    @Test
    fun `a fork continues from the message shown above, by its SDK id where it has one`() {
        val items = listOf(
            user("u1", "first", sdkUuid = "sdk-u1"),
            answer("a1", ChatBlock.Text("reply")),
            answer("a2", ChatBlock.Text("  ")),
            user("u2", "   "),
            user("u3", "second"),
            answer("t1", ChatBlock.Thinking("hmm"), sdkUuid = "sdk-t1"),
            user("u4", "third"),
        )
        assertEquals("a1", MessageVersions.anchor(items, 4, predecessor = "before"))
        assertEquals("sdk-u1", MessageVersions.anchor(items, 1, predecessor = "before"))
        assertEquals("sdk-t1", MessageVersions.anchor(items, 6, predecessor = "before"))
        assertEquals("before", MessageVersions.anchor(items, 0, predecessor = "before"))
        assertNull("the first message of a chat forks into an empty session", MessageVersions.anchor(items, 0, predecessor = null))
    }

    @Test
    fun `history carries the window's numbering, its anchor and the version groups`() {
        val chat = ChatTranscript()
        chat.applyHistory(
            page(
                """{"messages":[{"id":"u5","role":"user","content":"hi","timestamp":"2026-10-19T08:00:00Z"}],
                "start":40,"total":41,"userOrdinalOffset":4,"predecessorId":"sdk-39",
                "versionMap":{"5":{"ids":["s-root","s-edit"],"currentIndex":1},"x":{"ids":["a"]},"7":{"currentIndex":0}}}""",
            ),
            chat.activity,
        )
        assertEquals(4, chat.userOrdinalOffset)
        assertEquals("sdk-39", chat.historyPredecessorId)
        assertEquals(mapOf(5 to VersionGroup(listOf("s-root", "s-edit"), 1)), chat.versionMap)
        // An older page moves the window and its anchor; the groups stay, as the web keeps them.
        chat.prependHistory(
            page(
                """{"messages":[{"id":"u1","role":"user","content":"first","timestamp":"2026-10-19T08:00:00Z"}],
                "start":0,"total":41,"userOrdinalOffset":0,"predecessorId":"ignored"}""",
            ),
            before = 40,
        )
        assertEquals(0, chat.userOrdinalOffset)
        assertNull("nothing is above the first message", chat.historyPredecessorId)
        assertEquals(setOf(5), chat.versionMap.keys)
        // A page without groups keeps those known; one with them replaces them.
        chat.applyHistory(page("""{"messages":[],"start":0,"total":0}"""), chat.activity)
        assertEquals(setOf(5), chat.versionMap.keys)
        chat.applyHistory(page("""{"messages":[],"start":0,"total":0,"versionMap":{}}"""), chat.activity)
        assertEquals(emptyMap<Int, VersionGroup>(), chat.versionMap)
    }
}
