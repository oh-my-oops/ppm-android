package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.protocol.ApprovalRequest
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.SessionUnread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * What a chat not in front of the user calls for (use-chat.ts and notification-sounds.ts), and the
 * bell's Clear all (notification-store.ts).
 */
class AttentionTest {
    private fun attention(effects: List<ChatEffect>) = effects.filterIsInstance<ChatEffect.Attention>().map { it.attention }

    @Test
    fun `a live request, its answer and the turn's end call for attention, a replay and a second done do not`() {
        val chat = ChatTranscript()
        chat.handle(frame("""{"type":"phase_changed","phase":"thinking"}"""))
        val asked = attention(chat.handle(frame("""{"type":"approval_request","requestId":"r1","tool":"Bash","input":{"command":"rm x"}}""")))
        assertEquals(listOf<ChatAttention>(ChatAttention.Asked(ApprovalRequest("r1", "Bash", json("""{"command":"rm x"}""")))), asked)
        assertEquals(listOf<ChatAttention>(ChatAttention.Answered), attention(chat.handle(frame("""{"type":"approval_resolved","requestId":"r1","approved":true}"""))))
        // A reconnect replays the request: `session_state` says whether it still waits, and nothing sounds.
        val replay = chat.handle(
            frame("""{"type":"turn_events","userMessage":"go","streamSeq":3,"events":[{"type":"approval_request","requestId":"r2","tool":"Bash","input":{},"streamSeq":3}]}"""),
        )
        assertEquals(emptyList<ChatAttention>(), attention(replay))
        assertEquals(listOf<ChatAttention>(ChatAttention.Done), attention(chat.handle(frame("""{"type":"done","sessionId":"s"}"""))))
        chat.handle(frame("""{"type":"phase_changed","phase":"idle"}"""))
        assertEquals(emptyList<ChatAttention>(), attention(chat.handle(frame("""{"type":"done","sessionId":"s"}"""))))
        // A subagent's end is not the turn's.
        chat.handle(frame("""{"type":"phase_changed","phase":"streaming"}"""))
        assertEquals(emptyList<ChatAttention>(), attention(chat.handle(frame("""{"type":"done","parentToolUseId":"t1"}"""))))
    }

    @Test
    fun `sending, answering or stopping here takes the toast back, as the web does without waiting for the server`() = runBlocking {
        val chat = ChatController(ApiClient("http://127.0.0.1:9".toHttpUrl(), ""), "p", SessionInfo(id = "s", providerId = "mock", title = ""), this)
        val heard = mutableListOf<ChatAttention>()
        chat.onAttention = { heard += it }
        chat.respond(ApprovalRequest("r", "Bash", json("{}")), approved = false)
        chat.send("go on")
        chat.cancel()
        // Nothing is sent: no toast to take back.
        chat.send("  ")
        assertEquals(List(3) { ChatAttention.Answered }, heard)
        chat.stop()
    }

    @Test
    fun `the toast names a question, a database change or the tool, and the project or the session`() {
        fun notice(tool: String, input: String = "{}", project: String = "app") =
            AttentionNotice.of(ApprovalRequest("r", tool, json(input)), project, "0123456789abcdef")
        assertEquals(AttentionNotice("AI has a question", "app", warning = false, sound = "question"), notice("AskUserQuestion"))
        assertEquals(AttentionNotice("Bash needs permission", "Session 01234567", warning = true, sound = "approval_request"), notice("Bash", project = ""))
        assertEquals(
            "AI wants to change payroll",
            notice("ppm:db_execute", """{"connectionId":3,"connectionName":"payroll","sql":"delete from t"}""").title,
        )
        // Without the fields a db_execute approval carries, it is named as any tool is.
        assertEquals("ppm:db_execute needs permission", notice("ppm:db_execute", """{"connectionName":"payroll"}""").title)
    }

    @Test
    fun `the three sounds are the web's tones, each decaying to 0_001`() {
        val rate = 48_000
        val done = NotificationTones.pcm("done", rate)!!
        assertEquals((0.32 * rate).toInt(), done.size)
        assertEquals((0.45 * rate).toInt(), NotificationTones.pcm("approval_request", rate)!!.size)
        assertEquals((0.38 * rate).toInt(), NotificationTones.pcm("question", rate)!!.size)
        assertNull(NotificationTones.pcm("approval", rate))

        // C5 at 0.15 for its first 0.12 s: a quarter period in, the sine's crest at nearly full gain.
        val crest = (rate / 523.0 / 4).toInt()
        assertTrue(abs(done[crest] / 32767.0 - 0.15) < 0.003)
        // Zero crossings of the C5 alone: two per period.
        val first = done.copyOfRange(0, (0.1 * rate).toInt())
        val crossings = (1 until first.size).count { (first[it - 1] < 0) != (first[it] < 0) }
        assertTrue("$crossings crossings", abs(crossings - 2 * 523 * 0.1) <= 2)
        // E5's last samples are down at the ramp's 0.001.
        val tail = done.copyOfRange(done.size - 100, done.size).maxOf { abs(it.toInt()) } / 32767.0
        assertTrue("$tail", tail < 0.0011)

        // The square alert peaks at its level, band-limited, never past it by more than a hair.
        val alert = NotificationTones.pcm("approval_request", rate)!!
        val peak = alert.copyOfRange(0, (0.01 * rate).toInt()).maxOf { abs(it.toInt()) } / 32767.0
        assertTrue("$peak", peak in 0.17..0.185)
    }

    @Test
    fun `Clear all empties the list at once, and a refused request puts back what it cleared`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().code(500).body("""{"ok":false,"error":"down"}""").build())
        server.enqueue(MockResponse.Builder().body("""{"ok":true,"data":{"cleared":1}}""").build())
        server.start()
        try {
            val events = GlobalEvents(ApiClient(server.url("/"), "t"), CoroutineScope(Job()))
            events.handle(json("""{"type":"session:unread_changed","sessionId":"a","unreadCount":2,"unreadType":"question","projectName":"app","sessionTitle":"A"}"""))
            events.handle(json("""{"type":"session:unread_changed","sessionId":"b","unreadCount":1,"unreadType":"done","projectName":"app"}"""))
            events.clearAll()
            assertEquals(emptyMap<String, SessionUnread>(), events.unread.value)
            // What came in meanwhile is kept, ahead of what comes back.
            events.handle(json("""{"type":"session:unread_changed","sessionId":"c","unreadCount":1,"unreadType":"done","projectName":"app"}"""))
            assertEquals("""{"sessionIds":["a","b"]}""", server.takeRequest().body?.utf8())
            withTimeout(5_000) { while (events.unread.value.size < 3) yield() }
            assertEquals(listOf("c", "a", "b"), events.unread.value.keys.toList())
            assertEquals(SessionUnread("question", 2, "app", "A"), events.unread.value["a"])

            events.clearAll()
            assertEquals("""{"sessionIds":["c","a","b"]}""", server.takeRequest().body?.utf8())
            assertEquals(emptyMap<String, SessionUnread>(), events.unread.value)
        } finally {
            server.close()
        }
    }
}
