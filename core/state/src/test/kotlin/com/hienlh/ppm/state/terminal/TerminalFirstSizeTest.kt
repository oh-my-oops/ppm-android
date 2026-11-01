package com.hienlh.ppm.state.terminal

import com.hienlh.ppm.client.SocketConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a terminal does with its shell's output while the owner has not yet said how big the view is. A server replays its
 * buffer the moment a socket attaches, the shell made that buffer for the size it was last given, and an emulator wraps what
 * it is written at the width it has then. The app starts the controller in the composition and the view is laid out after it,
 * so an emulator that was written the replay at once wrapped it at its default 80 columns: on a tablet whose shell had 105, a
 * typed line that took three rows came back cut, a `printf` of 100 characters came back wrapped at 80 with the shell's `%`
 * marker rows between and everything under it pushed down, and nothing reflowed when the real width arrived. The web fits first
 * and connects after. The controller cannot wait to connect, though: a terminal that is not laid out yet, or never is (a window
 * that is minimized, a dock that is collapsed), still has to attach its shell, or the server ends it once its grace
 * period after the last socket has run out. So it connects at start as ever and holds the output until the first usable size,
 * and every test here starts it with no size (`measured = false`) as the app does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalFirstSizeTest {
    private val session = """{"type":"session","id":"s1"}"""

    /** What vim leaves in a buffer: it asks for the cursor position, the attributes, the pixel size and a mode. */
    private val queries = "\u001b[6n\u001b[>c\u001b[14t\u001b[16t\u001b[?12\$p\u001b]10;?\u0007\u001b]11;?\u0007\u001b[?1004h"

    private fun disk() = TerminalValues().apply { put("server-a:ppm:terminal-session:tab-1", "s1") }

    /** [n] letters that run through the alphabet, so a cut at the wrong place shows. */
    private fun ramp(n: Int, from: Int = 0) = String(CharArray(n) { 'a' + (from + it) % 26 })

    private fun delivered(f: TerminalFixture) = f.delivery.joinToString("") { it.removePrefix("REPLAY:") }

    @Test
    fun `a terminal nobody has laid out yet connects at start, for the shell it had, and is attached`() = runTest {
        TerminalFixture(this, disk(), measured = false).use { f ->
            f.controller.start()
            assertEquals(1, f.sockets.size)
            assertTrue(f.urls.single().contains("/terminal/s1?"))
            f.open()
            f.text(session)
            assertTrue(f.state.connected)
            assertEquals("s1", f.state.sessionId)
            // There is no size to say yet.
            assertEquals(emptyList<String>(), f.socket.sent)
        }
    }

    @Test
    fun `it keeps its socket alive as one that has been laid out does, by heartbeat, on foreground and when the server goes silent`() = runTest {
        TerminalFixture(this, disk(), measured = false).use { f ->
            f.controller.start()
            f.open()
            val first = f.socket
            advanceTimeBy(15_000)
            runCurrent()
            assertEquals(listOf("\u0001PING"), first.sent)
            f.controller.foreground()
            assertEquals(listOf("\u0001PING", "\u0001PING"), first.sent)
            advanceTimeBy(15_000)
            runCurrent()
            assertEquals(listOf("\u0001PING", "\u0001PING", "\u0001PING"), first.sent)
            assertEquals(1, f.sockets.size)
            // 45 seconds without a word from the server: the socket is replaced.
            advanceTimeBy(15_000)
            runCurrent()
            assertTrue(first.closed)
            assertEquals(2, f.sockets.size)
        }
    }

    @Test
    fun `what the shell prints before there is a size is held, not written`() = runTest {
        TerminalFixture(this, disk(), measured = false, separateReplay = true).use { f ->
            f.controller.start()
            f.open()
            f.text(session)
            f.text("the buffer")
            f.text("live")
            assertEquals(emptyList<String>(), f.delivery)
            assertTrue(f.output.isEmpty())
            assertTrue(f.replays.isEmpty())
            assertEquals("the buffer".length + "live".length, f.controller.heldBytes)
            // The controller still follows the shell meanwhile.
            assertEquals("s1", f.state.sessionId)
            assertTrue(f.state.connected)
        }
    }

    @Test
    fun `the first usable size writes what was held into an emulator of that size, in order, the replay apart from the live output`() = runTest {
        TerminalFixture(this, disk(), measured = false, separateReplay = true).use { f ->
            f.controller.setVisible(true)
            f.controller.start()
            f.open()
            f.text(session)
            f.text("the buffer the shell made for 105 columns")
            f.text("live one")
            f.text("live two")
            // What the owner does: its emulator is laid out at 105 columns, still empty, and then it says so.
            f.delivery += "the emulator is laid out at 105 columns"
            f.controller.resize(105, 30)
            assertEquals(
                listOf("the emulator is laid out at 105 columns", "REPLAY:the buffer the shell made for 105 columns", "live one", "live two"),
                f.delivery,
            )
            assertEquals(1, f.replays.size)
            assertEquals("nothing is kept once it is written", 0, f.controller.heldBytes)
            // The shell hears the size at once, with no debounce to wait out, and from then on output is written as it comes.
            assertEquals(listOf("\u0001RESIZE:105,30"), f.socket.sent)
            f.text("live three")
            assertEquals("live three", f.delivery.last())
            // Said once: the view reporting the same size again, or a heartbeat, adds nothing.
            f.controller.resize(105, 30)
            advanceTimeBy(500)
            runCurrent()
            assertEquals(listOf("\u0001RESIZE:105,30"), f.socket.sent)
        }
    }

    @Test
    fun `the replay's queries are still not answered once released, and a new shell's first output is still live`() = runTest {
        TerminalFixture(this, disk(), measured = false, separateReplay = true).use { f ->
            f.controller.start()
            f.open()
            f.text(session)
            f.text("prompt$ $queries")
            f.text(queries)
            assertEquals(emptyList<String>(), f.delivery)
            f.controller.resize(105, 30)
            // The first piece reached the owner as a replay, which it shows and never answers; the second as live output.
            assertEquals(listOf("REPLAY:prompt$ $queries", queries), f.delivery)
            assertEquals(1, f.replays.size)
            assertEquals(1, f.output.size)
        }
        TerminalFixture(this, measured = false, separateReplay = true).use { f ->
            f.controller.start()
            f.open()
            f.text(session.replace("s1", "fresh"))
            f.text(queries)
            f.controller.resize(105, 30)
            assertEquals(listOf(queries), f.delivery)
            assertTrue(f.replays.isEmpty())
        }
    }

    @Test
    fun `an error is held with the output, and the status shows it at once`() = runTest {
        TerminalFixture(this, measured = false).use { f ->
            f.controller.start()
            f.open()
            f.text("""{"type":"error","message":"Session not found"}""")
            assertEquals("Session not found", f.state.error)
            assertEquals(emptyList<String>(), f.delivery)
            f.controller.resize(105, 30)
            assertEquals(listOf("\r\n\u001b[31mError: Session not found\u001b[0m\r\n"), f.delivery)
        }
    }

    @Test
    fun `only a usable size counts, so nothing is written or said to the shell for a view with no room`() = runTest {
        TerminalFixture(this, disk(), measured = false).use { f ->
            f.controller.setVisible(true)
            f.controller.start()
            f.open()
            f.text(session)
            f.text("replay")
            f.controller.resize(2, 1)
            f.controller.resize(19, 30)
            f.controller.resize(100, 1)
            assertEquals(emptyList<String>(), f.delivery)
            assertEquals(emptyList<String>(), f.socket.sent)
            f.controller.resize(20, 2)
            assertEquals(listOf("replay"), f.delivery)
            assertEquals(listOf("\u0001RESIZE:20,2"), f.socket.sent)
        }
    }

    @Test
    fun `a hidden terminal is written at its first size and says nothing of it until it is shown`() = runTest {
        TerminalFixture(this, disk(), measured = false).use { f ->
            f.controller.start()
            f.open()
            f.text(session)
            f.text("replay")
            f.controller.resize(105, 30)
            assertEquals(listOf("replay"), f.delivery)
            advanceTimeBy(500)
            runCurrent()
            assertEquals(emptyList<String>(), f.socket.sent)
            f.controller.setVisible(true)
            advanceTimeBy(100)
            runCurrent()
            assertEquals(listOf("\u0001RESIZE:105,30"), f.socket.sent)
        }
    }

    @Test
    fun `a size known before anything arrives holds nothing, and goes to the shell as the socket opens`() = runTest {
        TerminalFixture(this, disk(), measured = false).use { f ->
            f.controller.setVisible(true)
            f.controller.resize(105, 30)
            f.controller.start()
            assertEquals(1, f.sockets.size)
            f.open()
            assertEquals(listOf("\u0001RESIZE:105,30"), f.socket.sent)
            f.text(session)
            f.text("replay")
            f.text("live")
            assertEquals(listOf("replay", "live"), f.delivery)
        }
    }

    @Test
    fun `a first size that reaches a socket still opening is sent as it opens`() = runTest {
        TerminalFixture(this, disk(), measured = false).use { f ->
            f.controller.setVisible(true)
            f.controller.start()
            f.controller.resize(105, 30)
            assertEquals(emptyList<String>(), f.socket.sent)
            f.open()
            assertEquals(listOf("\u0001RESIZE:105,30"), f.socket.sent)
        }
    }

    @Test
    fun `a server that answers the moment a socket exists is answered by a view that has been laid out`() = runTest {
        // What the real one does: the session frame and the buffer follow the open at once, whatever the app is doing.
        val server = { socket: TestTerminalSocket ->
            socket.events.trySend(SocketConnection.Event.Opened)
            socket.text(session)
            socket.text("the buffer the shell made for 105 columns")
        }
        TerminalFixture(this, disk(), measured = false, separateReplay = true, server = server).use { f ->
            f.controller.start()
            runCurrent()
            // The composition has started it; the layout has not come yet.
            assertEquals(emptyList<String>(), f.delivery)
            f.delivery += "the emulator is laid out at 105 columns"
            f.controller.resize(105, 30)
            assertEquals(listOf("the emulator is laid out at 105 columns", "REPLAY:the buffer the shell made for 105 columns"), f.delivery)
        }
    }

    @Test
    fun `at most one megabyte is held, the newest, as the server keeps`() = runTest {
        // Two pieces that together are over: the front of the older is cut, the newer is whole, and each is what it was.
        TerminalFixture(this, disk(), measured = false, separateReplay = true).use { f ->
            f.controller.start()
            f.open()
            f.text(session)
            f.text("a".repeat(700_000))
            f.text("b".repeat(700_000))
            assertEquals(MAX_HELD_BYTES, f.controller.heldBytes)
            f.controller.resize(105, 30)
            assertEquals(MAX_HELD_BYTES - 700_000, f.replays.single().size)
            assertTrue(f.replays.single().all { it == 'a'.code.toByte() })
            assertEquals(700_000, f.output.single().size)
            assertTrue(f.output.single().all { it == 'b'.code.toByte() })
        }
        // One piece that is over on its own: its tail.
        TerminalFixture(this, disk(), measured = false, separateReplay = true).use { f ->
            val big = ramp(1_500_000)
            f.controller.start()
            f.open()
            f.text(session)
            f.text(big)
            assertEquals(MAX_HELD_BYTES, f.controller.heldBytes)
            f.controller.resize(105, 30)
            assertEquals(big.takeLast(MAX_HELD_BYTES), String(f.replays.single(), Charsets.UTF_8))
        }
        // Many small ones: exactly the last megabyte of the stream, however the pieces fall.
        TerminalFixture(this, disk(), measured = false, separateReplay = true).use { f ->
            val stream = (0 until 2_000).joinToString("") { ramp(1_000, it) }
            f.controller.start()
            f.open()
            f.text(session)
            for (i in 0 until 2_000) f.text(ramp(1_000, i))
            assertEquals(MAX_HELD_BYTES, f.controller.heldBytes)
            f.controller.resize(105, 30)
            assertEquals(stream.takeLast(MAX_HELD_BYTES), delivered(f))
        }
        // Not over: all of it.
        TerminalFixture(this, disk(), measured = false, separateReplay = true).use { f ->
            f.controller.start()
            f.open()
            f.text(session)
            f.text("a".repeat(MAX_HELD_BYTES - 10))
            f.text("b".repeat(10))
            f.controller.resize(105, 30)
            assertEquals(MAX_HELD_BYTES, delivered(f).length)
        }
    }

    @Test
    fun `the cut never starts inside a character`() = runTest {
        // 3-byte characters, and a number of them that leaves the cap falling on each of the three bytes in turn.
        for (count in 400_000..400_002) {
            TerminalFixture(this, disk(), measured = false, separateReplay = true).use { f ->
                f.controller.start()
                f.open()
                f.text(session)
                f.text("雪".repeat(count))
                f.controller.resize(105, 30)
                val written = f.replays.single()
                val text = String(written, Charsets.UTF_8)
                assertFalse("count $count: a character was cut", text.contains('�'))
                assertEquals(count.toString(), "雪".repeat(text.length), text)
                assertTrue("count $count: ${written.size}", written.size in (MAX_HELD_BYTES - 2)..MAX_HELD_BYTES)
            }
        }
    }

    @Test
    fun `a reconnect before the first size drops what was held, since the server replays the shell again`() = runTest {
        TerminalFixture(this, disk(), measured = false, separateReplay = true).use { f ->
            f.controller.start()
            f.open()
            f.text(session)
            f.text("replay of the first connection")
            f.drop()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, f.sockets.size)
            f.open()
            assertEquals("the first connection's output is dropped when the second opens", 0, f.controller.heldBytes)
            f.text(session)
            f.text("replay of the second connection")
            f.delivery += "the emulator is laid out"
            f.controller.resize(105, 30)
            assertEquals(listOf("RESET", "the emulator is laid out", "REPLAY:replay of the second connection"), f.delivery)
        }
    }

    @Test
    fun `a restart before the first size is for another shell, connects at once, and drops what the old one printed`() = runTest {
        TerminalFixture(this, disk(), measured = false).use { f ->
            f.controller.start()
            f.open()
            f.text(session)
            f.text("output of the old shell")
            f.controller.restart()
            assertEquals(0, f.controller.heldBytes)
            assertEquals(2, f.sockets.size)
            assertTrue(f.urls.last().contains("/terminal/new?"))
            assertTrue(f.urls.last().contains("cwd="))
            // The size arrives before the new socket has opened: the old shell's output is not written then.
            f.controller.resize(105, 30)
            assertEquals(emptyList<String>(), f.delivery)
            f.open()
            f.text(session.replace("s1", "n2"))
            f.text("prompt of the new shell")
            assertEquals(listOf("RESET", "prompt of the new shell"), f.delivery)
        }
    }

    @Test
    fun `a retry and the app coming back to the foreground connect as before, with no size`() = runTest {
        TerminalFixture(this, disk(), measured = false).use { f ->
            f.controller.start()
            f.open()
            f.drop()
            advanceTimeBy(999)
            runCurrent()
            assertEquals(1, f.sockets.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, f.sockets.size)
            f.drop()
            f.controller.foreground()
            assertEquals(3, f.sockets.size)
        }
    }

    @Test
    fun `a controller closed while it holds output writes nothing when a size comes, and the owner closing it mid-write stops the rest`() = runTest {
        TerminalFixture(this, disk(), measured = false).use { f ->
            f.controller.start()
            f.open()
            f.text(session)
            f.text("held")
            f.controller.close()
            f.controller.resize(105, 30)
            assertEquals(emptyList<String>(), f.delivery)
        }
        lateinit var fixture: TerminalFixture
        fixture = TerminalFixture(this, disk(), measured = false, onOutput = { fixture.controller.close() })
        fixture.use { f ->
            f.controller.start()
            f.open()
            f.text(session)
            f.text("one")
            f.text("two")
            f.text("three")
            f.controller.resize(105, 30)
            assertEquals(1, f.output.size)
        }
    }
}
