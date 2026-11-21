package com.hienlh.ppm.state

import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.protocol.SessionBookmark
import com.hienlh.ppm.protocol.SessionInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Archive and its filter, bookmarks, Rewind code's calls, view modes and reopening closed chats. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatHistoryExtrasTest {
    private val now = Instant.parse("2026-11-21T12:00:00Z")
    private fun session(id: String, idleDays: Long = 0, archive: String? = null, pinned: Boolean = false, manager: Boolean = false) =
        SessionInfo(id, "claude", id, updatedAt = now.minus(Duration.ofDays(idleDays)), pinned = pinned, archive = archive, manager = manager)

    @Test
    fun `archived by hand always, by age only when the rule is on and nothing keeps it`() {
        assertTrue(SessionArchive.isArchived(session("a", archive = "archived"), 0, now))
        assertFalse(SessionArchive.isArchived(session("b", idleDays = 40), 0, now))
        assertTrue(SessionArchive.isArchived(session("c", idleDays = 30), 30, now))
        assertFalse(SessionArchive.isArchived(session("d", idleDays = 29), 30, now))
        assertFalse(SessionArchive.isArchived(session("e", idleDays = 40, pinned = true), 30, now))
        assertFalse(SessionArchive.isArchived(session("f", idleDays = 40, manager = true), 30, now))
        assertFalse(SessionArchive.isArchived(session("g", idleDays = 40, archive = "kept"), 30, now))
        assertFalse(SessionArchive.isArchived(SessionInfo("h", "claude", "h"), 7, now))
    }

    @Test
    fun `Active hides archived chats unless running or unread, each status shows its own`() {
        val list = listOf(session("live"), session("old", archive = "archived"), session("run", archive = "archived"), session("new", archive = "archived"))
        val running = { id: String -> id == "run" }
        val unread = { id: String -> id == "new" }
        fun ids(f: SessionStatusFilter, searching: Boolean = false) =
            SessionArchive.filter(list, f, 0, searching, running, unread, now).map { it.id }
        assertEquals(listOf("live", "run", "new"), ids(SessionStatusFilter.ACTIVE))
        assertEquals(listOf("run"), ids(SessionStatusFilter.RUNNING))
        assertEquals(listOf("new"), ids(SessionStatusFilter.UNREAD))
        assertEquals(listOf("old", "run", "new"), ids(SessionStatusFilter.ARCHIVED))
        assertEquals(listOf("live", "old", "run", "new"), ids(SessionStatusFilter.ALL))
        // A search under Active looks at every chat.
        assertEquals(4, ids(SessionStatusFilter.ACTIVE, searching = true).size)
        assertEquals(listOf("run"), ids(SessionStatusFilter.RUNNING, searching = true))
    }

    @Test
    fun `the empty list says which status it is empty of`() {
        assertEquals("No sessions yet", SessionArchive.emptyText(SessionStatusFilter.ACTIVE, false))
        assertEquals("No sessions yet", SessionArchive.emptyText(SessionStatusFilter.ALL, false))
        assertEquals("No archived sessions", SessionArchive.emptyText(SessionStatusFilter.ARCHIVED, false))
        assertEquals("No matching sessions", SessionArchive.emptyText(SessionStatusFilter.RUNNING, true))
        assertEquals(SessionStatusFilter.UNREAD, SessionStatusFilter.of("unread"))
        assertNull(SessionStatusFilter.of("nope"))
    }

    @Test
    fun `rewind takes the calls of the message's turn and every later one`() {
        val turns = listOf(
            SessionTurn(1, "m1", null, "a", listOf("c1")),
            SessionTurn(2, "m2", null, "b", emptyList()),
            SessionTurn(3, "m3", null, "c", listOf("c3", "c4")),
            SessionTurn(4, "m4", null, "d", listOf("c5")),
        )
        assertEquals(SessionTurns.Rewind(listOf("c3", "c4", "c5"), 2), SessionTurns.rewindFrom(turns, "m2"))
        assertEquals(SessionTurns.Rewind(listOf("c1", "c3", "c4", "c5"), 3), SessionTurns.rewindFrom(turns, "m1"))
        assertNull(SessionTurns.rewindFrom(turns, "zzz"))
    }

    @Test
    fun `view modes cycle Normal, Thinking, Verbose and read anything else as Normal`() {
        assertEquals(ChatViewMode.THINKING, ChatViewMode.NORMAL.next())
        assertEquals(ChatViewMode.VERBOSE, ChatViewMode.THINKING.next())
        assertEquals(ChatViewMode.NORMAL, ChatViewMode.VERBOSE.next())
        assertEquals(ChatViewMode.VERBOSE, ChatViewMode.of("verbose"))
        assertEquals(ChatViewMode.NORMAL, ChatViewMode.of(null))
        assertEquals(ChatViewMode.NORMAL, ChatViewMode.of("loud"))
    }

    @Test
    fun `only a chat with a session comes back, newest first, once, at most twenty`() {
        val closed = ClosedChats()
        closed.remember("chat", "p", null, "claude", "Draft")
        closed.remember("design", "p", "d1", "claude", "Design")
        closed.remember("chat", "p", "s1", null, "One")
        closed.remember("chat", "p", "s2", "codex", null)
        closed.remember("chat", "p", "s1", "claude", "One again")
        assertEquals(ClosedChat("p", "s1", "claude", "One again"), closed.takeLast())
        assertEquals(ClosedChat("p", "s2", "codex", null), closed.takeLast())
        assertNull(closed.takeLast())
        repeat(25) { closed.remember("chat", "p", "s$it", "claude", null) }
        assertEquals(20, closed.size)
        assertEquals("s24", closed.takeLast()?.sessionId)
    }

    private class FakeBookmarks : SessionBookmarksBackend {
        val server = mutableListOf<SessionBookmark>()
        var fail = false
        var hold: CompletableDeferred<Unit>? = null
        var asked = 0
        override suspend fun list(project: String, sessionId: String): List<SessionBookmark> {
            asked++
            if (fail) throw PpmError.Server(404, "Not found")
            return server.toList()
        }
        override suspend fun put(project: String, sessionId: String, turnId: String, turnN: Int?, label: String): List<SessionBookmark> {
            hold?.await()
            if (fail) throw PpmError.Server(500, "No")
            server += SessionBookmark(turnId, turnN, label, "saved")
            return server.sortedBy { it.turnN ?: Int.MAX_VALUE }
        }
        override suspend fun delete(project: String, sessionId: String, turnId: String): List<SessionBookmark> {
            if (fail) throw PpmError.Server(500, "No")
            server.removeAll { it.turnId == turnId }
            return server.toList()
        }
    }

    @Test
    fun `bookmarks load once, toggle at once, and a refused save is put back`() = runTest {
        val fake = FakeBookmarks().apply { server += SessionBookmark("t5", 5, "Five", "x") }
        val errors = mutableListOf<String>()
        val store = SessionBookmarks({ fake }, this, errors::add) { now }
        assertNull(store.list("p", "s"))
        store.load("p", "s")
        store.load("p", "s")
        runCurrent()
        assertEquals(1, fake.asked)
        assertEquals(listOf("t5"), store.list("p", "s")?.map { it.turnId })

        fake.hold = CompletableDeferred()
        store.toggle("p", "s", "t2", 2, "Two")
        // Shown before the server answers, in turn order.
        assertEquals(listOf("t2", "t5"), store.list("p", "s")?.map { it.turnId })
        fake.hold!!.complete(Unit)
        runCurrent()
        assertEquals(listOf("t2", "t5"), store.list("p", "s")?.map { it.turnId })
        assertEquals("saved", store.list("p", "s")?.first()?.createdAt)

        // Toggling a bookmarked turn takes it off.
        store.toggle("p", "s", "t2", 2, "Two")
        runCurrent()
        assertEquals(listOf("t5"), store.list("p", "s")?.map { it.turnId })

        fake.fail = true
        store.toggle("p", "s", "t9", 9, "Nine")
        assertEquals(2, store.list("p", "s")?.size)
        runCurrent()
        assertEquals(listOf("t5"), store.list("p", "s")?.map { it.turnId })
        assertEquals(listOf("Could not save the bookmark"), errors)
    }

    @Test
    fun `a server without bookmarks leaves the list unloaded, to be asked again`() = runTest {
        val fake = FakeBookmarks().apply { fail = true }
        val store = SessionBookmarks({ fake }, this)
        store.load("p", "s")
        runCurrent()
        assertNull(store.list("p", "s"))
        store.load("p", "s")
        runCurrent()
        assertEquals(2, fake.asked)
    }
}
