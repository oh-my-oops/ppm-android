package com.hienlh.ppm.state.terminal

import com.hienlh.ppm.client.SocketConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which of the bytes a server sends are the replay of a shell that already ran, and what a new process does to
 * pick one up. The server answers a connection with `{"type":"session"}` and then, if the shell has printed
 * anything, its whole buffer as one frame, with no marker for where it ends (`src/server/ws/terminal.ts`). Its
 * queries (a cursor position, the device attributes, a window size) were answered when a program made them,
 * and answering them again types the replies at whatever is running now: at a shell prompt they are text that
 * Enter runs. So the replay goes to the owner apart from live output, which it may answer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalReplayTest {
    /** What vim leaves in a buffer: it asks for the cursor position, the attributes, the pixel size and a mode. */
    private val queries = "\u001b[6n\u001b[>c\u001b[14t\u001b[16t\u001b[?12\$p\u001b]10;?\u0007\u001b]11;?\u0007\u001b[?1004h"
    private val session = """{"type":"session","id":"s1"}"""

    @Test
    fun `the first output after a session frame that names the shell asked for is the replay, and nothing after it`() = runTest {
        TerminalFixture(this, initialSessionId = "s1", separateReplay = true).use { f ->
            f.controller.start()
            f.open()
            f.text(session)
            f.text(queries)
            f.text("live one")
            f.text(queries)
            assertEquals(listOf("REPLAY:$queries", "live one", queries), f.delivery)
            assertEquals(1, f.replays.size)
        }
    }

    @Test
    fun `a shell that does not exist yet has no replay, so its first output is live and may ask`() = runTest {
        TerminalFixture(this, separateReplay = true).use { f ->
            f.controller.start()
            f.open()
            f.text(session.replace("s1", "fresh"))
            f.text(queries)
            assertEquals(listOf(queries), f.delivery)
            assertTrue(f.replays.isEmpty())
        }
    }

    @Test
    fun `an id the server no longer knows is a new shell under another id, with no replay either`() = runTest {
        val disk = TerminalValues().apply { put("server-a:ppm:terminal-session:tab-1", "gone") }
        TerminalFixture(this, disk, separateReplay = true).use { f ->
            f.controller.start()
            assertTrue(f.urls.single().contains("/terminal/gone?"))
            f.open()
            f.text(session.replace("s1", "made-instead"))
            f.text(queries)
            assertEquals(listOf(queries), f.delivery)
            assertEquals("made-instead", disk.values["server-a:ppm:terminal-session:tab-1"])
        }
    }

    @Test
    fun `every reconnect replays again, after the reset and before the live output that follows`() = runTest {
        TerminalFixture(this, separateReplay = true).use { f ->
            f.controller.start()
            f.open()
            f.text(session)
            f.text("first prompt")
            f.drop()
            advanceTimeBy(1_000)
            runCurrent()
            f.socket.events.trySend(SocketConnection.Event.Opened)
            f.socket.text(session)
            f.socket.text("first prompt, replayed")
            f.socket.text(queries)
            runCurrent()
            assertEquals(
                listOf("first prompt", "RESET", "REPLAY:first prompt, replayed", queries),
                f.delivery,
            )
        }
    }

    @Test
    fun `a pong or a control frame between the session frame and the replay does not use up the replay`() = runTest {
        TerminalFixture(this, initialSessionId = "s1", separateReplay = true).use { f ->
            f.controller.start()
            f.open()
            f.text(session)
            f.text("\u0001PONG")
            f.text("""{"type":"future-control"}""")
            f.text(queries)
            assertEquals(listOf("REPLAY:$queries"), f.delivery)
        }
    }

    @Test
    fun `a binary replay frame is told apart the same way`() = runTest {
        TerminalFixture(this, initialSessionId = "s1", separateReplay = true).use { f ->
            f.controller.start()
            f.open()
            f.text(session)
            f.socket.events.trySend(SocketConnection.Event.Binary(queries.toByteArray().toByteString()))
            runCurrent()
            f.text("live")
            assertEquals(listOf("REPLAY:$queries", "live"), f.delivery)
        }
    }

    @Test
    fun `a shell that had printed nothing has no replay, so its first live output is taken for one`() = runTest {
        // The limit of a protocol with no end-of-replay marker: nothing tells an empty buffer from a late
        // replay. The other mistake, answering a replay, is the one that types junk at a prompt.
        TerminalFixture(this, initialSessionId = "s1", separateReplay = true).use { f ->
            f.controller.start()
            f.open()
            f.text(session)
            f.text("first live output")
            f.text("second")
            assertEquals(listOf("REPLAY:first live output", "second"), f.delivery)
        }
    }

    @Test
    fun `an error or the end of the shell leaves no replay pending`() = runTest {
        TerminalFixture(this, initialSessionId = "s1", separateReplay = true).use { f ->
            f.controller.start()
            f.open()
            f.text(session)
            f.text("""{"type":"error","message":"Session not found"}""")
            f.text("after the error")
            assertEquals(listOf("\r\n\u001b[31mError: Session not found\u001b[0m\r\n", "after the error"), f.delivery)
        }
        TerminalFixture(this, initialSessionId = "s1", separateReplay = true).use { f ->
            f.controller.start()
            f.open()
            f.text(session)
            f.text("""{"type":"exited"}""")
            f.text("after the end")
            assertEquals(listOf("after the end"), f.delivery)
        }
    }

    @Test
    fun `restarting a shell starts over, and what the new one prints is live`() = runTest {
        TerminalFixture(this, initialSessionId = "s1", separateReplay = true).use { f ->
            f.controller.start()
            f.open()
            f.text(session)
            // Cut off with the replay still to come: the next connection asks for a new shell, not for this one.
            f.controller.restart()
            f.open()
            f.text(session.replace("s1", "new-shell"))
            f.text(queries)
            assertEquals(listOf("RESET", queries), f.delivery)
            assertTrue(f.replays.isEmpty())
        }
    }

    @Test
    fun `an owner with no replay callback gets the replay as ordinary output, so nothing is ever lost`() = runTest {
        TerminalFixture(this, initialSessionId = "s1").use { f ->
            f.controller.start()
            f.open()
            f.text(session)
            f.text("replay")
            f.text("live")
            assertEquals(listOf("replay", "live"), f.delivery)
        }
    }

    @Test
    fun `a new process reattaches to the shell the last one stored for the tab, with no reset and the replay apart`() = runTest {
        val disk = TerminalValues()
        TerminalFixture(this, disk, tabId = "terminal:1").use { before ->
            before.controller.start()
            before.open()
            before.text(session)
            before.text("prompt$ ")
            assertEquals("s1", disk.values["server-a:ppm:terminal-session:terminal:1"])
        }
        // The process is gone: of the first controller only the preferences file is left.
        TerminalFixture(this, disk, tabId = "terminal:1", initialSessionId = "new", separateReplay = true).use { after ->
            after.controller.start()
            assertEquals(1, after.urls.size)
            val url = after.urls.single()
            assertTrue(url, url.contains("/terminal/s1?"))
            assertFalse("cwd is only for a shell not made yet: $url", url.contains("cwd="))
            after.open()
            after.text(session)
            after.text("prompt$ " + queries)
            after.text("live")
            assertEquals(listOf("REPLAY:prompt$ $queries", "live"), after.delivery)
            assertFalse("a new owner has no screen to clear", after.delivery.contains("RESET"))
            assertEquals("s1", disk.values["server-a:ppm:terminal-session:terminal:1"])
        }
    }

    @Test
    fun `a size below 20 by 2 is never sent, before or after a shell is picked up again`() = runTest {
        val disk = TerminalValues().apply { put("server-a:ppm:terminal-session:tab-1", "s1") }
        TerminalFixture(this, disk, measured = false).use { f ->
            f.controller.setVisible(true)
            // A terminal laid out with no room (a hidden dock, a keyboard mid-animation) is measured at nothing.
            f.controller.resize(1, 1)
            f.controller.resize(2, 1)
            f.controller.resize(19, 40)
            f.controller.resize(80, 1)
            f.controller.start()
            f.open()
            f.text(session)
            f.text(queries)
            advanceTimeBy(500)
            runCurrent()
            assertEquals("nothing to send until the view has a usable size", emptyList<String>(), f.socket.sent)
            f.controller.resize(1, 1)
            f.controller.resize(80, 24)
            advanceTimeBy(100)
            runCurrent()
            assertEquals(listOf("\u0001RESIZE:80,24"), f.socket.sent)
            f.drop()
            advanceTimeBy(1_000)
            runCurrent()
            f.controller.resize(2, 2)
            f.open()
            advanceTimeBy(500)
            runCurrent()
            assertEquals("a new connection sends the last usable size, never the unusable one", listOf("\u0001RESIZE:80,24"), f.socket.sent)
        }
    }
}
