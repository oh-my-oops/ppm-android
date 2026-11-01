package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.protocol.CanvasCheckReport
import com.hienlh.ppm.protocol.ChatClientMessage
import com.hienlh.ppm.protocol.CheckSize
import com.hienlh.ppm.protocol.LayoutCheckReport
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.TabOpenAnswer
import com.hienlh.ppm.protocol.TabOpenRequest
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.state.preview.PreviewLoads
import com.hienlh.ppm.state.preview.answerTabOpen
import com.hienlh.ppm.state.preview.tabOpenRefusal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** `tab_open` on the chat socket: every request is answered on it, open or not, and none holds up the frames behind it. */
class TabOpenAnswerTest {
    private class Server : Dispatcher() {
        val sockets = LinkedBlockingQueue<WebSocket>()
        val received = LinkedBlockingQueue<String>()

        override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.headers["Upgrade"].equals("websocket", ignoreCase = true) -> MockResponse.Builder().webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        sockets.put(webSocket)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        received.put(text)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                    }
                },
            ).build()
            request.target.contains("/messages") -> MockResponse.Builder().body("""{"ok":true,"data":{"messages":[],"start":0,"total":0}}""").build()
            else -> MockResponse.Builder().code(404).build()
        }
    }

    private fun withChat(block: suspend CoroutineScope.(Server, WebSocket, ChatController) -> Unit) = runBlocking {
        val dispatcher = Server()
        val server = MockWebServer()
        server.dispatcher = dispatcher
        server.start()
        try {
            val chat = ChatController(ApiClient(server.url("/"), "t"), "p", SessionInfo(id = "s-1", providerId = "mock", title = ""), this)
            block(dispatcher, startChat(dispatcher, chat), chat)
            chat.stop()
        } finally {
            server.close()
        }
    }

    private suspend fun startChat(server: Server, chat: ChatController): WebSocket {
        chat.start()
        val socket = withContext(Dispatchers.IO) { server.sockets.poll(3, TimeUnit.SECONDS) } ?: fail("no socket") as Nothing
        assertEquals(ChatClientMessage.Ready.json.toString(), server.next())
        return socket
    }

    private suspend fun Server.next(): String? = withContext(Dispatchers.IO) { received.poll(3, TimeUnit.SECONDS) }

    private suspend fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            delay(10)
        }
    }

    private val openFile = """{"type":"tab_open","requestId":"q1","tool":"open_file","filePath":"a.html","projectName":"p"}"""
    private val terminalRequest = TabOpenRequest.Terminal("terminal-1", "pty-😀", "project", "/tmp/a folder/道")

    @Test
    fun `without an opener the request is refused at once`() = withChat { server, socket, _ ->
        socket.send(openFile)
        assertEquals(
            WireJson.parse(ChatClientMessage.TabOpenResult("q1", opened = false, error = ChatController.TAB_OPEN_UNSUPPORTED).json.toString()),
            server.next()?.let(WireJson::parse),
        )
    }

    @Test
    fun `the opener's answer goes back under the request's id, report and all`() = withChat { server, socket, chat ->
        val asked = mutableListOf<TabOpenRequest>()
        val report = CanvasCheckReport(LayoutCheckReport(CheckSize(390, 844), CheckSize(390, 900), emptyList(), emptyMap()), "a.html", null, "Phone")
        chat.tabOpener = { request ->
            asked += request
            TabOpenAnswer(opened = true, report = report)
        }
        socket.send(openFile)
        assertEquals(
            WireJson.parse(ChatClientMessage.TabOpenResult("q1", opened = true, error = null, report = report).json.toString()),
            server.next()?.let(WireJson::parse),
        )
        assertEquals(listOf(TabOpenRequest.File("q1", "open_file", "a.html", "p")), asked)
    }

    @Test
    fun `an opener that throws is a refusal saying why`() = withChat { server, socket, chat ->
        chat.tabOpener = { throw IllegalStateException("PPM has no panel to open the tab in") }
        socket.send(openFile)
        assertEquals(
            WireJson.parse("""{"type":"tab_open_result","requestId":"q1","opened":false,"error":"PPM has no panel to open the tab in"}"""),
            server.next()?.let(WireJson::parse),
        )
    }

    @Test
    fun `a page still being checked does not hold up the frames behind it`() = withChat { server, socket, chat ->
        val checked = CompletableDeferred<TabOpenAnswer>()
        chat.tabOpener = { checked.await() }
        socket.send(openFile)
        socket.send("""{"type":"title_updated","title":"After the request"}""")
        waitFor("the title") { chat.state.value.title == "After the request" }
        checked.complete(TabOpenAnswer(opened = true, error = "the page did not start loading"))
        assertEquals(
            WireJson.parse("""{"type":"tab_open_result","requestId":"q1","opened":true,"error":"the page did not start loading"}"""),
            server.next()?.let(WireJson::parse),
        )
    }

    @Test
    fun `terminal opener receives the full request and answers only once attached without a preview`() = runTest {
        assertNull(tabOpenRefusal(terminalRequest))
        val calls = mutableListOf<TabOpenRequest.Terminal>()
        val attached = CompletableDeferred<Unit>()
        val answer = async {
            answerTabOpen(
                terminalRequest, PreviewLoads(),
                openFile = { error("a terminal must not open a file") },
                openUrl = { error("a terminal must not open a URL") },
                frame = { error("a terminal has no preview frame") },
                settle = { error("a terminal must not settle a preview") },
                openTerminal = { calls += it; attached.await() },
            )
        }
        runCurrent()
        assertEquals(listOf(terminalRequest), calls)
        assertFalse(answer.isCompleted)
        attached.complete(Unit)
        assertEquals(TabOpenAnswer(opened = true), answer.await())
        assertEquals("no preview load timeout", 0L, testScheduler.currentTime)
    }

    @Test
    fun `terminal opener failures return their reason including exceptions without a message`() = runTest {
        for (failure in listOf(IllegalStateException("terminal session is gone"), IllegalStateException())) {
            val answer = answerTabOpen(
                terminalRequest, PreviewLoads(), { error("not a file") }, { error("not a URL") }, { error("not a preview") },
                openTerminal = { throw failure },
            )
            assertEquals(TabOpenAnswer(opened = false, error = failure.message ?: failure.toString()), answer)
        }
        // Existing callers that have no terminal callback still receive the same unsupported answer.
        assertEquals(
            TabOpenAnswer(opened = false, error = "the PPM Android app has no terminal yet"),
            answerTabOpen(terminalRequest, PreviewLoads(), { error("not a file") }, {}, { "Phone" }),
        )
    }

    @Test
    fun `terminal opener cancellation propagates instead of becoming a refusal`() = runTest {
        val cancelled = CancellationException("terminal owner closed")
        try {
            answerTabOpen(
                terminalRequest, PreviewLoads(), { error("not a file") }, { error("not a URL") }, { error("not a preview") },
                openTerminal = { throw cancelled },
            )
            fail("cancellation must propagate")
        } catch (caught: CancellationException) {
            assertSame(cancelled, caught)
        }
    }

    @Test
    fun `AI run in terminal is opened and acknowledged on the chat socket without a preview report`() = withChat { server, socket, chat ->
        val opened = mutableListOf<TabOpenRequest.Terminal>()
        chat.tabOpener = { request ->
            answerTabOpen(
                request, PreviewLoads(), { error("not a file") }, { error("not a URL") }, { error("not a preview") },
                openTerminal = { opened += it },
            )
        }
        socket.send("""{"type":"tab_open","requestId":"terminal-1","tool":"run_in_terminal","terminalId":"pty-😀","projectName":"project","cwd":"/tmp/a folder/道"}""")
        assertEquals(
            WireJson.parse(ChatClientMessage.TabOpenResult("terminal-1", opened = true, error = null).json.toString()),
            server.next()?.let(WireJson::parse),
        )
        assertEquals(listOf(terminalRequest), opened)
    }

    @Test
    fun `AI terminal open failure is acknowledged under the original request id`() = withChat { server, socket, chat ->
        chat.tabOpener = { request ->
            answerTabOpen(
                request, PreviewLoads(), { error("not a file") }, { error("not a URL") }, { error("not a preview") },
                openTerminal = { throw IllegalStateException("no terminal panel") },
            )
        }
        socket.send("""{"type":"tab_open","requestId":"terminal-2","tool":"run_in_terminal","terminalId":"pty-2","cwd":"/"}""")
        assertEquals(
            WireJson.parse(ChatClientMessage.TabOpenResult("terminal-2", opened = false, error = "no terminal panel").json.toString()),
            server.next()?.let(WireJson::parse),
        )
    }
}
