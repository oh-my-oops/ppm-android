package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.SessionTag
import com.hienlh.ppm.protocol.SessionUnread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * `/ws/global` frames into running and unread sessions, without a server
 * (`PPMSelfTest/GlobalEventsTests.swift`). No project is set, so nothing here reaches the network.
 */
class GlobalEventsTest {
    private fun events() = GlobalEvents(ApiClient("http://ppm.test".toHttpUrl(), "t"), CoroutineScope(Job()))

    @Test
    fun `an unread grows by one per -1 and keeps what a later frame leaves out`() {
        val e = events()
        e.handle(json("""{"type":"session:unread_changed","sessionId":"s","unreadCount":-1,"unreadType":"question","projectName":"app","sessionTitle":"Fix it"}"""))
        e.handle(json("""{"type":"session:unread_changed","sessionId":"s","unreadCount":-1,"unreadType":"done","projectName":"","sessionTitle":null}"""))
        assertEquals(SessionUnread("done", 2, "app", "Fix it"), e.unread.value["s"])
        e.handle(json("""{"type":"session:unread_changed","sessionId":"s","unreadCount":5,"unreadType":"","projectName":"app"}"""))
        assertEquals(5, e.unread.value["s"]?.count)
        assertEquals("done", e.unread.value["s"]?.type)
        e.handle(json("""{"type":"session:unread_changed","sessionId":"s","unreadCount":0,"unreadType":null,"projectName":""}"""))
        assertNull("a read session is still unread", e.unread.value["s"])
    }

    @Test
    fun `a hand-made unread stays sticky through later broadcasts`() {
        val e = events()
        e.handle(json("""{"type":"session:unread_changed","sessionId":"s","unreadCount":1,"unreadType":"done","projectName":"app","manual":true}"""))
        e.handle(json("""{"type":"session:unread_changed","sessionId":"s","unreadCount":-1,"unreadType":"done","projectName":"app"}"""))
        assertEquals(true, e.unread.value["s"]?.manual)
    }

    @Test
    fun `the chat in front loses its unread at once, unless it was marked by hand`() {
        val e = events()
        e.isShown = { it == "front" }
        e.handle(json("""{"type":"session:unread_changed","sessionId":"front","unreadCount":-1,"unreadType":"done","projectName":"app"}"""))
        e.handle(json("""{"type":"session:unread_changed","sessionId":"back","unreadCount":-1,"unreadType":"done","projectName":"app"}"""))
        assertNull("the chat in front is unread", e.unread.value["front"])
        assertNotNull("a chat in the back was cleared", e.unread.value["back"])
        e.handle(json("""{"type":"session:unread_changed","sessionId":"front","unreadCount":1,"unreadType":"done","projectName":"app","manual":true}"""))
        assertNotNull("a hand-made unread was cleared while in front", e.unread.value["front"])
        e.seen("front")
        assertNotNull("being seen cleared a hand-made unread", e.unread.value["front"])
        e.markRead("front")
        assertNull("picking the chat did not clear it", e.unread.value["front"])
    }

    @Test
    fun `phases mark sessions running, and a migrated id is let go`() {
        val e = events()
        e.handle(json("""{"type":"session:phase_changed","sessionId":"a","phase":"thinking","projectName":"app"}"""))
        e.handle(json("""{"type":"session:phase_changed","sessionId":"b","phase":"streaming","projectName":"app"}"""))
        assertEquals(setOf("a", "b"), e.running.value)
        e.handle(json("""{"type":"session:phase_changed","sessionId":"a","phase":"idle","projectName":"app"}"""))
        e.handle(json("""{"type":"session:migrated","oldSessionId":"b","newSessionId":"c"}"""))
        assertEquals(emptySet<String>(), e.running.value)
        e.handle(json("""{"type":"file:changed","projectName":"app","path":"a.ts"}"""))
        assertEquals(emptySet<String>(), e.running.value)
    }
}

class SessionSearchTest {
    @Test
    fun `with nothing typed, the shared page, pinned first, under its tag chip`() {
        val list = HistoryList(ApiClient("http://127.0.0.1:9".toHttpUrl(), ""), "p", CoroutineScope(Job()))
        val old = SessionInfo("old", "claude", "Old", updatedAt = Instant.ofEpochSecond(1))
        val new = SessionInfo("new", "claude", "New", updatedAt = Instant.ofEpochSecond(2))
        val pinned = SessionInfo(
            "pinned", "claude", "Pinned",
            pinned = true, tag = SessionTag.from(json("""{"id":1,"name":"Todo","color":"#ff0000"}""")),
        )
        val all = list.sessions(listOf(old, pinned, new))
        assertEquals(listOf("pinned", "new", "old"), all.map { it.id })
        assertEquals(true, list.hasMore(shared = true))
        list.tag = 1
        assertEquals(listOf("pinned"), list.shown(all).map { it.id })
    }
}
