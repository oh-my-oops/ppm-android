package com.hienlh.ppm.state.terminal

import com.hienlh.ppm.client.SocketConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalControllerTest {
    @Test
    fun `status exposes the confirmed session id across reconnect and clears it for restart`() = runTest {
        TerminalFixture(this, initialSessionId = "metadata").use { f ->
            assertNull(f.state.sessionId)
            f.controller.start()
            f.open()
            f.text("""{"type":"session","id":"s1"}""")
            assertEquals("s1", f.state.sessionId)
            f.drop()
            assertEquals("s1", f.state.sessionId)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals("s1", f.state.sessionId)
            f.open()
            assertEquals("s1", f.state.sessionId)
            f.text("""{"type":"session","id":""}""")
            assertEquals("s1", f.state.sessionId)
            f.text("""{"type":"session","id":"replacement-after-server-restart"}""")
            assertEquals("replacement-after-server-restart", f.state.sessionId)
            f.controller.restart()
            assertNull(f.state.sessionId)
            f.open()
            assertNull(f.state.sessionId)
            f.text("""{"type":"session","id":"fresh"}""")
            assertEquals("fresh", f.state.sessionId)
            f.controller.close()
            assertEquals("fresh", f.state.sessionId)
        }
    }

    @Test
    fun `session cache is stable per tab and server and wins over tab metadata`() = runTest {
        val values = TerminalValues()
        val key = "server-a:ppm:terminal-session:tab-1"
        values.put(key, "cached /+雪")
        TerminalFixture(this, values, initialSessionId = "metadata").use { f ->
            assertEquals(0, f.sockets.size)
            f.controller.start()
            f.controller.start()
            assertEquals(1, f.sockets.size)
            val url = f.urls.single().replaceFirst("wss:", "https:").toHttpUrl()
            assertEquals(listOf("base", "ws", "project", "p /+ 雪", "terminal", "cached /+雪"), url.pathSegments)
            assertEquals("t +/&?#", url.queryParameter("token"))
            assertNull(url.queryParameter("obsolete"))
            assertNull(url.queryParameter("cwd"))
            assertNull(url.fragment)
            f.open()
            f.text("""{"type":"session","id":"assigned"}""")
            assertEquals(mapOf(key to "assigned"), values.values)
        }
        TerminalFixture(this, values, initialSessionId = "metadata").use { f ->
            f.controller.start()
            assertTrue(f.urls.single().contains("/terminal/assigned?"))
        }
        TerminalFixture(this, values, prefix = "server-b:", initialSessionId = "metadata").use { f ->
            f.controller.start()
            assertTrue(f.urls.single().contains("/terminal/metadata?"))
        }
        TerminalFixture(this, values, tabId = "tab-2").use { f ->
            f.controller.start()
            val url = f.urls.single().replaceFirst("wss:", "https:").toHttpUrl()
            assertEquals("new", url.pathSegments.last())
            assertEquals("/work/a +& 雪", url.queryParameter("cwd"))
        }
    }

    @Test
    fun `a shell with no project is named by an empty segment, as the web names it, and starts where its tab says`() = runTest {
        TerminalFixture(this, project = "").use { f ->
            f.controller.start()
            val url = f.urls.single().replaceFirst("wss:", "https:").toHttpUrl()
            assertEquals(listOf("base", "ws", "project", "", "terminal", "new"), url.pathSegments)
            assertTrue(f.urls.single().contains("/ws/project//terminal/new?"))
            assertEquals("/work/a +& 雪", url.queryParameter("cwd"))
        }
    }

    @Test
    fun `storage failures do not interrupt output or in-memory reconnect`() = runTest {
        val values = TerminalValues().apply { fail = true }
        TerminalFixture(this, values, initialSessionId = "metadata").use { f ->
            f.controller.start()
            assertTrue(f.urls.single().contains("/terminal/metadata?"))
            f.open()
            f.text("""{"type":"session","id":"live"}""")
            f.text("prompt")
            f.drop()
            advanceTimeBy(1_000)
            runCurrent()
            assertTrue(f.urls.last().contains("/terminal/live?"))
            assertEquals(listOf("prompt"), f.delivery)
            f.controller.restart()
            assertTrue(f.urls.last().contains("/terminal/new?"))
        }
        TerminalFixture(this, initialSessionId = " ").use { f ->
            f.controller.start()
            assertTrue(f.urls.single().contains("/terminal/new?"))
        }
    }

    @Test
    fun `output stays raw including malformed JSON and binary while controls never leak`() = runTest {
        TerminalFixture(this).use { f ->
            f.controller.start()
            f.open()
            val raw = listOf("\u001b[32m雪$ \u001b[0m", "{not-json", "{\"value\":1}", "{\"type\":null}", "{\"type\":false}", "{\"type\":0}", "{\"type\":\"\"}")
            raw.forEach(f::text)
            f.text("\u0001PONG")
            f.text("""{"type":"session","id":"s1"}""")
            f.text("""{"type":"future-control","content":"never output"}""")
            f.text("""{"type":"text","content":"leaked chat frame"}""")
            f.text("""{"type":true}""")
            assertEquals(raw, f.delivery)
            val bytes = byteArrayOf(0x1b, 0x5b, 0x31, 0xff.toByte())
            f.socket.events.trySend(SocketConnection.Event.Binary(bytes.toByteString()))
            runCurrent()
            assertArrayEquals(bytes, f.output.last())
            f.socket.events.trySend(SocketConnection.Event.Binary("\u0001PONG".toByteArray().toByteString()))
            runCurrent()
            assertEquals(raw.size + 1, f.output.size)
        }
    }

    @Test
    fun `reset is delivered before immediate replay on every reconnect without a collector`() = runTest {
        TerminalFixture(this).use { f ->
            f.controller.start()
            f.open()
            f.text("old buffer")
            f.text("""{"type":"session","id":"s1"}""")
            repeat(2) {
                f.drop()
                advanceTimeBy(1_000)
                runCurrent()
                f.socket.events.trySend(SocketConnection.Event.Opened)
                f.socket.text("""{"type":"session","id":"s1"}""")
                f.socket.text("replayed buffer")
                f.socket.text("live output")
                runCurrent()
            }
            assertEquals(listOf("old buffer", "RESET", "replayed buffer", "live output", "RESET", "replayed buffer", "live output"), f.delivery)
            assertTrue(f.state.connected)
            assertFalse(f.state.reconnecting)
        }
    }

    @Test
    fun `failed connections back off 1 2 4 8 16 30 seconds without heartbeat bypass`() = runTest {
        TerminalFixture(this).use { f ->
            f.controller.start()
            for (wait in listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)) {
                val count = f.sockets.size
                f.drop()
                assertTrue(f.state.reconnecting)
                assertFalse(f.state.connected)
                assertEquals("Network lost", f.state.error)
                advanceTimeBy(wait - 1)
                runCurrent()
                assertEquals(count, f.sockets.size)
                advanceTimeBy(1)
                runCurrent()
                assertEquals(count + 1, f.sockets.size)
            }
            f.open()
            assertNull(f.state.error)
            val count = f.sockets.size
            f.drop()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(count + 1, f.sockets.size)
        }
    }

    @Test
    fun `heartbeat runs every fifteen seconds and replaces a socket silent over thirty five`() = runTest {
        TerminalFixture(this).use { f ->
            f.controller.start()
            f.open()
            val first = f.socket
            advanceTimeBy(14_999)
            runCurrent()
            assertTrue(first.sent.isEmpty())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf("\u0001PING"), first.sent)
            advanceTimeBy(15_000)
            runCurrent()
            assertEquals(listOf("\u0001PING", "\u0001PING"), first.sent)
            assertEquals(1, f.sockets.size)
            advanceTimeBy(15_000)
            runCurrent()
            assertTrue(first.closed)
            assertEquals(2, f.sockets.size)
            assertFalse(f.state.connected)
            assertTrue(f.state.reconnecting)
        }
    }

    @Test
    fun `PONG and PTY output refresh activity and foreground probes healthy sockets`() = runTest {
        TerminalFixture(this).use { f ->
            f.controller.start()
            f.open()
            advanceTimeBy(30_000)
            runCurrent()
            f.text("\u0001PONG")
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(1, f.sockets.size)
            f.text("still alive")
            advanceTimeBy(30_000)
            runCurrent()
            val count = f.socket.sent.size
            f.controller.foreground()
            assertEquals(count + 1, f.socket.sent.size)
            assertEquals("\u0001PING", f.socket.sent.last())
            assertEquals(1, f.sockets.size)
            assertEquals(listOf("still alive"), f.delivery)
        }
    }

    @Test
    fun `foreground bypasses backoff and does not duplicate an in-flight connection`() = runTest {
        TerminalFixture(this).use { f ->
            f.controller.foreground()
            assertTrue(f.sockets.isEmpty())
            f.controller.start()
            f.controller.foreground()
            assertEquals(1, f.sockets.size)
            f.open()
            f.drop()
            f.controller.foreground()
            f.controller.foreground()
            assertEquals(2, f.sockets.size)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, f.sockets.size)
            f.open()
        }
    }

    @Test
    fun `foreground reconnects at the silence threshold before the next heartbeat`() = runTest {
        TerminalFixture(this).use { f ->
            f.controller.start()
            f.open()
            advanceTimeBy(35_000)
            f.controller.foreground()
            assertEquals(1, f.sockets.size)
            advanceTimeBy(1)
            f.controller.foreground()
            assertEquals(2, f.sockets.size)
            f.controller.foreground()
            assertEquals(2, f.sockets.size)
        }
    }

    @Test
    fun `shellReady waits for 250 ms of output quiet and ignores control traffic`() = runTest {
        TerminalFixture(this).use { f ->
            f.controller.start()
            f.open()
            f.text("""{"type":"session","id":"s1"}""")
            f.text("\u0001PONG")
            advanceTimeBy(500)
            runCurrent()
            assertFalse(f.state.shellReady)
            f.text("loading")
            advanceTimeBy(249)
            runCurrent()
            assertFalse(f.state.shellReady)
            f.text("prompt")
            advanceTimeBy(249)
            runCurrent()
            assertFalse(f.state.shellReady)
            advanceTimeBy(1)
            runCurrent()
            assertTrue(f.state.shellReady)
            f.text("command output")
            assertTrue(f.state.shellReady)
            f.drop()
            assertFalse(f.state.shellReady)
        }
    }

    @Test
    fun `shellReady has a three second cap for both silent and noisy shells`() = runTest {
        for (noisy in listOf(false, true)) {
            TerminalFixture(this).use { f ->
                f.controller.start()
                f.open()
                repeat(14) {
                    advanceTimeBy(200)
                    if (noisy) f.text("startup")
                }
                advanceTimeBy(199)
                runCurrent()
                assertFalse(f.state.shellReady)
                advanceTimeBy(1)
                runCurrent()
                assertTrue(f.state.shellReady)
            }
        }
    }

    @Test
    fun `resize settles for 100 ms keeps the latest size and respects visibility and the floor`() = runTest {
        TerminalFixture(this).use { f ->
            f.controller.start()
            f.open()
            f.controller.resize(80, 24)
            advanceTimeBy(100)
            runCurrent()
            assertTrue(f.socket.sent.isEmpty())
            f.controller.setVisible(true)
            advanceTimeBy(99)
            runCurrent()
            assertTrue(f.socket.sent.isEmpty())
            f.controller.resize(100, 30)
            advanceTimeBy(99)
            runCurrent()
            assertTrue(f.socket.sent.isEmpty())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf("\u0001RESIZE:100,30"), f.socket.sent)
            f.controller.resize(2, 1)
            f.controller.resize(19, 30)
            f.controller.resize(100, 1)
            advanceTimeBy(100)
            runCurrent()
            assertEquals(1, f.socket.sent.size)
            f.controller.resize(20, 2)
            advanceTimeBy(100)
            runCurrent()
            assertEquals("\u0001RESIZE:20,2", f.socket.sent.last())
            f.controller.resize(20, 2)
            advanceTimeBy(100)
            runCurrent()
            assertEquals(2, f.socket.sent.size)
            f.controller.resize(120, 40)
            f.controller.setVisible(false)
            advanceTimeBy(100)
            runCurrent()
            assertEquals(2, f.socket.sent.size)
            f.controller.setVisible(true)
            advanceTimeBy(100)
            runCurrent()
            assertEquals("\u0001RESIZE:120,40", f.socket.sent.last())
            f.drop()
            f.controller.foreground()
            f.open()
            advanceTimeBy(100)
            runCurrent()
            assertEquals(listOf("\u0001RESIZE:120,40"), f.socket.sent)
        }
    }

    @Test
    fun `raw input is sent only on an open shell and failed sends reconnect without replaying input`() = runTest {
        TerminalFixture(this).use { f ->
            assertFalse(f.controller.send("before start"))
            f.controller.start()
            assertFalse(f.controller.send("before open"))
            f.open()
            assertTrue(f.controller.send("\u0003ls\r"))
            assertTrue(f.controller.sendBytes("雪\r".toByteArray()))
            assertEquals(listOf("\u0003ls\r", "雪\r"), f.socket.sent)
            f.socket.acceptSend = false
            assertFalse(f.controller.send("rejected"))
            assertTrue(f.state.reconnecting)
            assertFalse(f.controller.send("disconnected"))
            advanceTimeBy(1_000)
            runCurrent()
            f.open()
            assertTrue(f.socket.sent.isEmpty())
        }
    }

    @Test
    fun `error is status and formatted output without making the shell ready`() = runTest {
        TerminalFixture(this).use { f ->
            f.controller.start()
            f.open()
            f.text("startup")
            f.text("""{"type":"error","message":"Not a directory"}""")
            assertEquals("Not a directory", f.state.error)
            assertEquals("\r\n\u001b[31mError: Not a directory\u001b[0m\r\n", f.delivery.last())
            advanceTimeBy(3_000)
            runCurrent()
            assertFalse(f.state.shellReady)
            assertFalse(f.controller.send("ls\r"))
            f.drop()
            advanceTimeBy(1_000)
            runCurrent()
            f.open()
            assertNull(f.state.error)
        }
    }

    @Test
    fun `exited cancels readiness and reconnect while restart forgets only this tab and uses new with cwd`() = runTest {
        val values = TerminalValues()
        val key = "server-a:ppm:terminal-session:tab-1"
        values.put("other-tab", "keep")
        TerminalFixture(this, values).use { f ->
            f.controller.start()
            f.open()
            f.text("""{"type":"session","id":"s1"}""")
            f.text("prompt")
            f.text("""{"type":"exited"}""")
            val first = f.socket
            assertTrue(f.state.exited)
            assertFalse(f.controller.send("ls\r"))
            f.drop()
            advanceTimeBy(60_000)
            runCurrent()
            f.controller.foreground()
            assertFalse(f.state.shellReady)
            assertFalse(f.state.reconnecting)
            assertEquals(1, f.sockets.size)
            f.controller.restart()
            assertTrue(first.closed)
            assertNull(values.get(key))
            assertEquals("keep", values.get("other-tab"))
            assertFalse(f.state.exited)
            assertFalse(f.state.shellReady)
            val url = f.urls.last().replaceFirst("wss:", "https:").toHttpUrl()
            assertEquals("new", url.pathSegments.last())
            assertEquals("/work/a +& 雪", url.queryParameter("cwd"))
            f.open()
            assertEquals(listOf("prompt", "RESET"), f.delivery)
        }
    }

    @Test
    fun `restart cancels pending retries and ignores queued frames from the old socket`() = runTest {
        val values = TerminalValues()
        TerminalFixture(this, values).use { f ->
            f.controller.start()
            f.open()
            f.text("""{"type":"session","id":"s1"}""")
            val first = f.socket
            first.text("old output")
            first.text("""{"type":"session","id":"must-not-persist"}""")
            f.controller.restart()
            runCurrent()
            assertTrue(first.closed)
            assertTrue(f.delivery.isEmpty())
            assertTrue(values.values.isEmpty())
            assertEquals(2, f.sockets.size)
            f.open()
            f.drop()
            f.controller.restart()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(3, f.sockets.size)
        }
    }

    @Test
    fun `close is permanent cancels all timers preserves cache and never reconnects`() = runTest {
        val values = TerminalValues()
        TerminalFixture(this, values).use { f ->
            f.controller.start()
            f.open()
            f.text("""{"type":"session","id":"s1"}""")
            f.text("prompt")
            f.controller.setVisible(true)
            f.controller.resize(80, 24)
            f.controller.close()
            f.controller.close()
            f.controller.start()
            f.controller.restart()
            f.controller.foreground()
            f.controller.setVisible(true)
            f.controller.resize(100, 30)
            advanceTimeBy(90_000)
            runCurrent()
            assertEquals(1, f.sockets.size)
            assertTrue(f.socket.closed)
            assertTrue(f.socket.sent.isEmpty())
            assertFalse(f.state.connected)
            assertFalse(f.state.shellReady)
            assertFalse(f.state.reconnecting)
            assertFalse(f.controller.send("never sent"))
            assertEquals("s1", values.get("server-a:ppm:terminal-session:tab-1"))
        }
        TerminalFixture(this).use { f ->
            f.controller.start()
            f.drop()
            f.controller.close()
            advanceTimeBy(90_000)
            runCurrent()
            assertEquals(1, f.sockets.size)
        }
    }

    @Test
    fun `parent cancellation disposes the socket and a cancelled parent cannot start one`() = runTest {
        val parentJob = Job(coroutineContext[Job])
        val parent = CoroutineScope(coroutineContext + parentJob)
        TerminalFixture(this, scope = parent).use { f ->
            f.controller.start()
            f.open()
            parentJob.cancel()
            runCurrent()
            assertTrue(f.socket.closed)
            assertFalse(f.state.connected)
            f.controller.foreground()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, f.sockets.size)
        }
        TerminalFixture(this, scope = parent).use { f ->
            f.controller.start()
            assertTrue(f.sockets.isEmpty())
        }
    }

    @Test
    fun `closing from reset prevents queued replay and readiness from reaching a disposed owner`() = runTest {
        lateinit var controller: TerminalController
        TerminalFixture(this, onReset = { controller.close() }).use { f ->
            controller = f.controller
            controller.start()
            f.open()
            f.drop()
            advanceTimeBy(1_000)
            runCurrent()
            f.socket.events.trySend(SocketConnection.Event.Opened)
            f.socket.text("must not be delivered")
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(listOf("RESET"), f.delivery)
            assertEquals(2, f.sockets.size)
            assertFalse(f.state.connected)
            assertFalse(f.state.shellReady)
        }
    }

    @Test
    fun `close then immediately reopen the same tab restores session without old callbacks or retries`() = runTest {
        val values = TerminalValues()
        TerminalFixture(this, values).use { old ->
            old.controller.start()
            old.open()
            old.text("""{"type":"session","id":"keep-this-shell"}""")
            old.text("old prompt")
            old.socket.text("old queued bytes")
            old.socket.text("""{"type":"exited"}""")
            old.controller.close()
            TerminalFixture(this, values).use { fresh ->
                fresh.controller.start()
                fresh.open()
                fresh.text("replay for the new owner")
                old.controller.foreground()
                old.controller.start()
                advanceTimeBy(3_000)
                runCurrent()
                assertTrue(fresh.urls.single().contains("/terminal/keep-this-shell?"))
                assertEquals(listOf("old prompt"), old.delivery)
                assertEquals(listOf("replay for the new owner"), fresh.delivery)
                assertFalse(fresh.state.exited)
                assertTrue(fresh.state.connected)
                assertTrue(fresh.state.shellReady)
                assertEquals(1, old.sockets.size)
                assertEquals(1, fresh.sockets.size)
            }
        }
    }

    @Test
    fun `replay sees reset completed and fresh readiness while resize keeps actual view dimensions`() = runTest {
        lateinit var controller: TerminalController
        val statuses = mutableListOf<TerminalState>()
        var resets = 0
        TerminalFixture(
            this,
            onReset = {
                assertFalse(controller.state.value.connected)
                assertFalse(controller.state.value.shellReady)
                // The owner may synchronously recreate its emulator at the measured view size.
                controller.resize(132, 46)
                resets++
            },
            onOutput = {
                statuses += controller.state.value
                if (statuses.size > 1) assertEquals(1, resets)
            },
        ).use { f ->
            controller = f.controller
            controller.setVisible(true)
            controller.resize(132, 46)
            controller.start()
            f.open()
            f.text("prompt")
            advanceTimeBy(250)
            runCurrent()
            assertTrue(f.state.shellReady)
            assertEquals(listOf("\u0001RESIZE:132,46"), f.socket.sent)
            f.drop()
            controller.foreground()
            f.socket.events.trySend(SocketConnection.Event.Opened)
            f.socket.text("replay")
            runCurrent()
            assertEquals(listOf("prompt", "RESET", "replay"), f.delivery)
            assertTrue(statuses.all { it.connected && !it.reconnecting && !it.exited && !it.shellReady && it.error == null })
            advanceTimeBy(100)
            runCurrent()
            assertEquals(listOf("\u0001RESIZE:132,46"), f.socket.sent)
            assertFalse(f.state.shellReady)
            advanceTimeBy(150)
            runCurrent()
            assertTrue(f.state.shellReady)
        }
    }
}
