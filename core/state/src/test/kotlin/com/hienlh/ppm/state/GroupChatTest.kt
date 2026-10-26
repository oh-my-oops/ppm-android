package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.protocol.Group
import com.hienlh.ppm.protocol.GroupMessage
import com.hienlh.ppm.protocol.GroupStatus
import com.hienlh.ppm.protocol.MemberValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A group's feed as the web's `use-group-chat.ts` keeps it, and what it does better after a drop. */
class GroupChatTest {
    private fun message(id: String, from: String = "Leader", turn: Int = 0, at: Long = 1_761_470_000) =
        """{"id":"$id","groupId":"g","fromMember":"$from","toMember":null,"kind":"chat","summary":"text $id","fullSessionRef":null,"data":null,"turnIndex":$turn,"createdAt":$at}"""

    private fun ids(chat: GroupChat) = chat.state.value.messages.map { it.id }

    @Test
    fun `store times are seconds, read as the web reads them, and never 1970`() {
        val message = GroupMessage.from(json(message("m", at = 1_761_470_000)))!!
        assertEquals(Instant.parse("2025-10-26T09:13:20Z"), message.createdAt)
        // Already milliseconds: kept.
        assertEquals(Instant.ofEpochMilli(1_761_470_000_123), GroupMessage.from(json(message("m", at = 1_761_470_000_123)))!!.createdAt)
        val group = Group.from(
            json("""{"id":"g","projectName":"p","projectPath":"/p","name":"Team","leaderSessionId":null,"status":"paused","maxTurns":7,"maxCostUsd":5,"createdAt":1761470000,
                "members":[{"id":"a","groupId":"g","role":"leader","persona":null,"agentType":null,"model":"opus","sessionId":null,"name":"Ann","color":"#6366f1","status":"idle","joinedAt":1761470000}]}"""),
        )!!
        assertEquals(GroupStatus.PAUSED, group.status)
        assertEquals(7, group.maxTurns)
        assertEquals("Ann", group.members.single().name)
        assertTrue(group.members.single().isLeader)
    }

    @Test
    fun `typing lines keep their order, end after 8 s or with the member's reply, and all go when the burst ends`() = runTest {
        val chat = GroupChat(ApiClient("http://127.0.0.1:9".toHttpUrl(), ""), "p", "g", this)
        chat.handle(json("""{"type":"typing","member":"Ann"}"""))
        advanceTimeBy(1_000)
        chat.handle(json("""{"type":"typing","member":"Bob"}"""))
        advanceTimeBy(1_000)
        // Ann again: her line stays first, her 8 s start over.
        chat.handle(json("""{"type":"typing","member":"Ann"}"""))
        runCurrent()
        assertEquals(listOf("Ann", "Bob"), chat.state.value.typing)
        advanceTimeBy(7_001)
        assertEquals(listOf("Ann"), chat.state.value.typing)
        chat.handle(json("""{"type":"group_message","message":${message("m1", from = "Ann")}}"""))
        assertEquals(emptyList<String>(), chat.state.value.typing)
        assertEquals(listOf("m1"), ids(chat))
        // The same message again is not shown twice.
        chat.handle(json("""{"type":"group_message","message":${message("m1", from = "Ann")}}"""))
        assertEquals(listOf("m1"), ids(chat))

        chat.handle(json("""{"type":"group_state","groupId":"g","status":"active","members":[{"id":"a","name":"Ann","role":"leader","status":"idle","color":null}]}"""))
        assertTrue(chat.state.value.running)
        chat.handle(json("""{"type":"member_status","memberId":"a","status":"working"}"""))
        assertEquals("working", chat.state.value.members.single().status.wire)
        chat.handle(json("""{"type":"typing","member":"Bob"}"""))
        chat.handle(json("""{"type":"group_done","reason":"no_more_mentions","turns":2,"costUsd":0}"""))
        assertEquals(emptyList<String>(), chat.state.value.typing)
        assertFalse(chat.state.value.running)
        assertEquals(GroupStatus.IDLE, chat.state.value.status)
        chat.handle(json("""{"type":"error","message":"group has no members"}"""))
        assertEquals("group has no members", chat.state.value.error)
        chat.close()
    }

    @Test
    fun `a running burst reads active, and one the user stopped leaves the group paused, for Resume`() = runTest {
        // A send runs a burst the server tells no socket about: still active, wherever it stood.
        assertEquals(GroupStatus.ACTIVE, GroupChat.State(status = GroupStatus.IDLE, running = true).shownStatus)
        assertEquals(GroupStatus.ACTIVE, GroupChat.State(status = GroupStatus.PAUSED, running = true).shownStatus)
        assertEquals(GroupStatus.PAUSED, GroupChat.State(status = GroupStatus.PAUSED).shownStatus)

        val chat = GroupChat(ApiClient("http://127.0.0.1:9".toHttpUrl(), ""), "p", "g", this)
        chat.handle(json(state))
        chat.handle(json("""{"type":"typing","member":"Ann"}"""))
        chat.handle(json("""{"type":"group_done","reason":"stopped","turns":0,"costUsd":0}"""))
        assertEquals(GroupStatus.PAUSED, chat.state.value.shownStatus)
        assertFalse(chat.state.value.running)
        assertEquals(emptyList<String>(), chat.state.value.typing)
        chat.close()
    }

    /** A group server: REST from [rest], every socket it accepts and what each was sent. */
    private class Server(val rest: (RecordedRequest) -> MockResponse) : Dispatcher() {
        val sockets = LinkedBlockingQueue<WebSocket>()
        val received = List(3) { LinkedBlockingQueue<String>() }
        val requests = CopyOnWriteArrayList<String>()
        private var upgrades = 0

        override fun dispatch(request: RecordedRequest): MockResponse {
            if (!request.headers["Upgrade"].equals("websocket", ignoreCase = true)) {
                requests += "${request.method} ${request.target}" + (request.body?.utf8()?.let { " $it" } ?: "")
                return rest(request)
            }
            val frames = received[upgrades++]
            return MockResponse.Builder().webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        sockets.put(webSocket)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        frames.put(text)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                    }
                },
            ).build()
        }
    }

    private fun withServer(rest: (RecordedRequest) -> MockResponse, block: suspend CoroutineScope.(Server, ApiClient) -> Unit) = runBlocking {
        val dispatcher = Server(rest)
        val server = MockWebServer()
        server.dispatcher = dispatcher
        server.start()
        try {
            block(dispatcher, ApiClient(server.url("/"), "t"))
        } finally {
            server.close()
        }
    }

    private fun ok(data: String) = MockResponse.Builder().body("""{"ok":true,"data":$data}""").build()

    private suspend fun <T> LinkedBlockingQueue<T>.next(): T? = withContext(Dispatchers.IO) { poll(3, TimeUnit.SECONDS) }

    private suspend fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            delay(10)
        }
    }

    private fun field(frame: String?, name: String) = frame?.let { Json.parseToJsonElement(it).jsonObject[name]?.jsonPrimitive?.content }

    private val state = """{"type":"group_state","groupId":"g","status":"active","members":[]}"""

    @Test
    fun `a message that comes live before the feed stays after it, and the feed's copy is not doubled`() {
        val release = CountDownLatch(1)
        withServer({ request ->
            when {
                request.target.startsWith("/api/group-chat/g/feed") -> {
                    release.await(3, TimeUnit.SECONDS)
                    ok("""{"messages":[${message("m1")},${message("m2")}]}""")
                }
                request.target == "/api/group-chat/g" -> ok("""{"id":"g","name":"Team","status":"idle","maxTurns":4,"members":[]}""")
                else -> MockResponse.Builder().code(404).build()
            }
        }) { server, api ->
            val chat = GroupChat(api, "p", "g", this)
            chat.start()
            val socket = server.sockets.next() ?: return@withServer fail("no socket")
            assertEquals("ready", field(server.received[0].next(), "type"))
            socket.send(state)
            socket.send("""{"type":"group_message","message":${message("m2")}}""")
            socket.send("""{"type":"group_message","message":${message("m3")}}""")
            waitFor("the live messages") { chat.state.value.messages.size == 2 }
            assertTrue(chat.state.value.loading)
            release.countDown()
            waitFor("the feed") { !chat.state.value.loading }
            assertEquals(listOf("m1", "m2", "m3"), ids(chat))
            waitFor("the roster") { chat.state.value.replyCap == 4 }
            chat.close()
        }
    }

    @Test
    fun `a socket back after a drop reads the feed again, so a burst that ended meanwhile is not lost`() {
        var feed = listOf("m1", "m2")
        withServer({ request ->
            when {
                request.target.startsWith("/api/group-chat/g/feed") -> ok("""{"messages":[${feed.joinToString(",") { message(it) }}]}""")
                request.target == "/api/group-chat/g" -> ok("""{"id":"g","name":"Team","members":[]}""")
                else -> MockResponse.Builder().code(404).build()
            }
        }) { server, api ->
            val chat = GroupChat(api, "p", "g", this)
            chat.start()
            val first = server.sockets.next() ?: return@withServer fail("no socket")
            assertEquals("ready", field(server.received[0].next(), "type"))
            first.send(state)
            waitFor("the feed") { ids(chat) == listOf("m1", "m2") }

            first.close(1001, "going away")
            waitFor("the drop") { !chat.socketOpen }
            // Sent while the socket is down: it waits for the next one, and goes out once.
            chat.send("@Ann hello")
            // Two replies and a done land while nobody listens; only the feed has them.
            feed = listOf("m1", "m2", "m3", "m4")

            val second = server.sockets.next() ?: return@withServer fail("no second socket")
            assertEquals("ready", field(server.received[1].next(), "type"))
            val sent = server.received[1].next()
            assertEquals("message" to "@Ann hello", field(sent, "type") to field(sent, "content"))
            second.send("""{"type":"group_state","groupId":"g","status":"idle","members":[]}""")
            // A burst still running replays what it already sent: no duplicates.
            second.send("""{"type":"group_message","message":${message("m4")}}""")
            waitFor("the gap filled") { ids(chat) == listOf("m1", "m2", "m3", "m4") }
            assertNull(withContext(Dispatchers.IO) { server.received[1].poll(300, TimeUnit.MILLISECONDS) })
            assertEquals(2, server.requests.count { it.startsWith("GET /api/group-chat/g/feed") })
            chat.close()
        }
    }

    @Test
    fun `before the socket says hello, sending and stopping go over REST, and resume always does`() {
        withServer({ request ->
            when (request.target) {
                "/api/group-chat/g/message" -> MockResponse.Builder().code(400).body("""{"ok":false,"error":"group has no members"}""").build()
                "/api/group-chat/g/stop" -> ok("""{"status":"paused"}""")
                "/api/group-chat/g/resume" -> ok("""{"status":"active"}""")
                "/api/group-chat/g/members" -> ok("""{"id":"b","name":"Bea","role":"member"}""")
                "/api/group-chat/g/members/a" -> MockResponse.Builder().code(400).body("""{"ok":false,"error":"reassign the leader before removing this member"}""").build()
                "/api/group-chat/g" -> ok("""{"id":"g","name":"Team","maxTurns":3,"members":[{"id":"a","name":"Ann","role":"leader"},{"id":"b","name":"Bea","role":"member"}]}""")
                else -> ok("""{"messages":[]}""")
            }
        }) { server, api ->
            val chat = GroupChat(api, "p", "g", this)
            chat.send("  hi  ")
            assertTrue(chat.state.value.running)
            waitFor("the refusal") { chat.state.value.error != null }
            assertEquals("group has no members", chat.state.value.error)
            assertFalse(chat.state.value.running)

            chat.stop()
            assertEquals(GroupStatus.PAUSED, chat.state.value.status)
            waitFor("the stop") { server.requests.any { it.startsWith("POST /api/group-chat/g/stop") } }
            chat.resume()
            waitFor("the resume") { chat.state.value.running }
            assertEquals(GroupStatus.ACTIVE, chat.state.value.status)

            chat.saveMember(chat.state.value.roster.firstOrNull(), MemberValues("Bea", persona = null))
            assertEquals(listOf("Ann", "Bea"), chat.state.value.roster.map { it.name })
            assertEquals(3, chat.state.value.replyCap)
            chat.removeMember(chat.state.value.roster.first())
            waitFor("the refusal") { chat.state.value.rosterError != null }
            assertEquals("reassign the leader before removing this member", chat.state.value.rosterError)
            assertFalse(chat.state.value.rosterBusy)

            chat.setReplyCap(80)
            assertEquals(50, chat.state.value.replyCap)
            waitFor("the cap") { server.requests.any { it.startsWith("PATCH /api/group-chat/g ") } }

            assertEquals(
                listOf(
                    """POST /api/group-chat/g/message {"content":"hi"}""",
                    "POST /api/group-chat/g/stop",
                    "POST /api/group-chat/g/resume",
                    """POST /api/group-chat/g/members {"role":"member","name":"Bea","persona":null,"model":null}""",
                    "GET /api/group-chat/g",
                    "DELETE /api/group-chat/g/members/a",
                    """PATCH /api/group-chat/g {"maxTurns":50}""",
                ),
                server.requests.map { it.removeSuffix(" ") },
            )
            chat.close()
        }
    }

    @Test
    fun `a feed longer than the window keeps what was shown before it`() {
        // m0 was read before a drop and has since left the last-200 window.
        var calls = 0
        withServer({ request ->
            if (request.target.startsWith("/api/group-chat/g/feed")) {
                calls++
                ok(if (calls == 1) """{"messages":[${message("m0")},${message("m1")}]}""" else """{"messages":[${message("m1")},${message("m2")}]}""")
            } else {
                ok("""{"id":"g","name":"Team","members":[]}""")
            }
        }) { server, api ->
            val chat = GroupChat(api, "p", "g", this)
            chat.start()
            val first = server.sockets.next() ?: return@withServer fail("no socket")
            first.send(state)
            waitFor("the feed") { ids(chat) == listOf("m0", "m1") }
            first.close(1001, "going away")
            val second = server.sockets.next() ?: return@withServer fail("no second socket")
            second.send(state)
            waitFor("the feed again") { ids(chat) == listOf("m0", "m1", "m2") }
            chat.close()
        }
    }
}
