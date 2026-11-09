package com.hienlh.ppm.app.ui

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.state.terminal.TerminalController
import com.hienlh.ppm.state.terminal.TerminalState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class TerminalKeyboardStateTest {
    private fun isInputReady(visible: Boolean, state: TerminalState): Boolean =
        visible && state.connected && state.shellReady && !state.exited && state.error == null

    @Test
    fun `a reconnect that drops inputReady and sets state error keeps keyboardRequested and re-shows the soft keyboard when inputReady returns`() = runBlocking {
        val peers = LinkedBlockingQueue<WebSocket>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        peers.put(webSocket)
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                }).build()
        }
        server.start()
        val store = object : KeyValueStore {
            private val map = mutableMapOf<String, String>()
            override fun get(key: String) = map[key]
            override fun put(key: String, value: String?) {
                if (value == null) map.remove(key) else map[key] = value
            }
        }
        val controller = TerminalController(
            api = ApiClient(server.url("/"), "token"),
            project = "project",
            tabId = "tab-1",
            scope = this,
            store = store,
            keyPrefix = "server:",
            onOutput = {},
            onReset = {},
        )
        try {
            val kb = TerminalKeyboardState()
            var asks = 0
            var focusRequests = 0
            var shows = 0
            var hides = 0
            var releases = 0

            controller.setVisible(true)
            controller.resize(80, 24)
            controller.start()

            val firstPeer = withContext(Dispatchers.IO) {
                checkNotNull(peers.poll(3, TimeUnit.SECONDS)) { "Timed out waiting for first terminal socket" }
            }
            firstPeer.send("""{"type":"session","id":"s1"}""")
            firstPeer.send("$ ")
            withTimeout(3_000) {
                while (!isInputReady(visible = true, controller.state.value)) delay(10)
            }

            kb.onTerminalTap(askFocus = { asks++ }, showKeyboard = { shows++ })
            kb.onImeVisibilityChanged(imeVisible = true, inputReady = isInputReady(visible = true, controller.state.value))
            assertTrue(kb.terminalFocused)
            assertTrue(kb.keyboardRequested)
            assertTrue(kb.showSoftKeyboard(inputReady = isInputReady(visible = true, controller.state.value)))
            assertEquals(1, shows)

            // Drop the socket with a reason so TerminalController.scheduleReconnect sets state.reconnecting = true and state.error != null.
            firstPeer.close(1011, "Socket dropped")
            withTimeout(3_000) {
                while (!controller.state.value.reconnecting || controller.state.value.error == null) delay(10)
            }
            val reconnectingState = controller.state.value
            assertTrue(reconnectingState.reconnecting)
            assertNotNull("reconnect sets state.error from close reason", reconnectingState.error)
            assertFalse(isInputReady(visible = true, reconnectingState))

            // Drive TerminalKeyboardState with the real controller's reconnecting state (where error != null).
            kb.onTerminalLivenessChanged(
                visible = true,
                exited = reconnectingState.exited,
                hideKeyboard = { hides++ },
                releaseFocus = { releases++ },
            )
            kb.onReadyStateChanged(
                inputReady = isInputReady(visible = true, reconnectingState),
                requestFocus = { focusRequests++ },
                showKeyboard = { shows++ },
            )
            kb.onImeVisibilityChanged(imeVisible = false, inputReady = isInputReady(visible = true, reconnectingState))
            assertTrue("keyboardRequested survives reconnect even when state.error != null", kb.keyboardRequested)
            assertTrue("terminalFocused survives reconnect", kb.terminalFocused)
            assertEquals("reconnect does not hide keyboard", 0, hides)
            assertEquals(0, releases)

            // Trigger immediate reconnect via foreground() and wait for the replacement socket to become inputReady.
            controller.foreground()
            val secondPeer = withContext(Dispatchers.IO) {
                checkNotNull(peers.poll(3, TimeUnit.SECONDS)) { "Timed out waiting for reconnected terminal socket" }
            }
            secondPeer.send("""{"type":"session","id":"s1"}""")
            secondPeer.send("$ ")
            withTimeout(3_000) {
                while (!isInputReady(visible = true, controller.state.value)) delay(10)
            }
            val readyState = controller.state.value
            assertNull(readyState.error)
            assertTrue(isInputReady(visible = true, readyState))

            kb.onReadyStateChanged(
                inputReady = isInputReady(visible = true, readyState),
                requestFocus = { focusRequests++ },
                showKeyboard = { shows++ },
            )
            assertTrue(kb.showSoftKeyboard(inputReady = isInputReady(visible = true, readyState)))
            assertEquals(1, focusRequests)
            assertEquals(2, shows)
        } finally {
            controller.close()
            server.close()
        }
    }

    @Test
    fun `process exit or hidden tab clears keyboardRequested and hides the soft keyboard`() {
        val kb = TerminalKeyboardState()
        var hides = 0
        var releases = 0

        kb.onTerminalTap(askFocus = {}, showKeyboard = {})
        assertTrue(kb.keyboardRequested)

        kb.onTerminalLivenessChanged(
            visible = false,
            exited = false,
            hideKeyboard = { hides++ },
            releaseFocus = { releases++ },
        )
        assertFalse(kb.keyboardRequested)
        assertEquals(1, hides)
        assertEquals(0, releases)

        kb.onTerminalTap(askFocus = {}, showKeyboard = {})
        kb.onTerminalLivenessChanged(
            visible = true,
            exited = true,
            hideKeyboard = { hides++ },
            releaseFocus = { releases++ },
        )
        assertFalse(kb.keyboardRequested)
        assertFalse(kb.terminalFocused)
        assertEquals(2, hides)
        assertEquals(1, releases)
    }

    @Test
    fun `select mode raises nothing on toggle, tap, drag or exit`() {
        val kb = TerminalKeyboardState()
        var asks = 0
        var focusRequests = 0
        var shows = 0
        var hides = 0

        // Enter select mode from an unfocused terminal: raises nothing, requests no focus.
        kb.onToggleSelect(hideKeyboard = { hides++ })
        assertTrue(kb.selecting)
        assertFalse(kb.keyboardEnabled(inputReady = true))
        assertFalse(kb.showSoftKeyboard(inputReady = true))
        assertEquals(0, asks)
        assertEquals(0, focusRequests)
        assertEquals(0, shows)
        assertEquals(1, hides)

        // Tapping the terminal while in select mode raises nothing and does not focus.
        kb.onTerminalTap(askFocus = { asks++ }, showKeyboard = { shows++ })
        assertFalse(kb.terminalFocused)
        assertFalse(kb.keyboardRequested)
        assertEquals(0, asks)
        assertEquals(0, shows)

        // Leaving select mode does not raise the keyboard or focus an unfocused terminal.
        kb.onToggleSelect(hideKeyboard = { hides++ })
        kb.onReadyStateChanged(
            inputReady = true,
            requestFocus = { focusRequests++ },
            showKeyboard = { shows++ },
        )
        assertFalse(kb.selecting)
        assertFalse(kb.showSoftKeyboard(inputReady = true))
        assertEquals(0, focusRequests)
        assertEquals(0, shows)
    }
}
