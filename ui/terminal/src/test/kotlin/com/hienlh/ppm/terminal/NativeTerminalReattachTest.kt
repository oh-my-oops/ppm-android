package com.hienlh.ppm.terminal

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.connectbot.terminal.TerminalBufferLine
import org.connectbot.terminal.TerminalBufferSnapshot
import org.connectbot.terminal.TerminalEmulator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a terminal keeps when its socket comes back. Android cuts the socket of an app that has been away for a
 * minute, and the server then replays the shell's buffer into a new emulator. The rows it builds need not be the old
 * ones, so the last command is carried as its text, which Send to Chat and Copy find again in the replay. The emulator
 * here is the real one, built and closed without the JNI that only a parse needs, so the rows the old emulator held
 * and the rows the replay draws are both handed over as snapshots.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeTerminalReattachTest {
    private val terminals = mutableListOf<NativeTerminal>()

    private val store = object : KeyValueStore {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    @After
    fun close() = terminals.forEach { it.close() }

    private fun terminal(shown: Boolean = true) = NativeTerminal(
        ApiClient("http://127.0.0.1:9/".toHttpUrl(), "token"), "project", "tab", CoroutineScope(Dispatchers.Unconfined), store, "server:",
        copyToClipboard = {},
    ).also {
        terminals += it
        if (shown) it.setShown(true)
    }

    private fun snapshot(vararg rows: String, cursor: Int = rows.lastIndex) = TerminalBufferSnapshot(
        rows.map { TerminalBufferLine(it, false) }, cursor, 0, 0, alternateScreen = false,
    )

    @Test
    fun `the last command is still the last command once the server has replayed the shell into a new emulator`() {
        val terminal = terminal()
        // Enter at the prompt of `seq 1 3`, row 2, under an earlier command's output.
        terminal.history.send("\r", { snapshot("$ echo old", "old", "$ seq 1 3", cursor = 2) }) { true }

        terminal.replaceEmulator { snapshot("$ echo old", "old", "$ seq 1 3", "1", "2", "3", "$ ") }
        // The replay has the shell's banner above, which the old buffer did not.
        val replayed = snapshot("banner", "$ echo old", "old", "$ seq 1 3", "1", "2", "3", "$ ")

        assertEquals("$ seq 1 3", terminal.history.lastCommand(replayed))
        assertEquals("1\n2\n3\n$", terminal.history.lastOutput(replayed))
    }

    @Test
    fun `the rows carried over are those of the emulator being replaced, read before it closes`() {
        val terminal = terminal()
        terminal.history.send("\r", { snapshot("$ seq 1 3", cursor = 0) }) { true }
        val old = terminal.emulator
        var read: TerminalEmulator? = null

        terminal.replaceEmulator {
            read = it
            // Not yet closed, not yet swapped for the new one.
            assertFalse(it.isClosed)
            assertSame(old, terminal.emulator)
            snapshot("$ seq 1 3", "1", "2", "3", "$ ")
        }

        assertSame(old, read)
        assertNotSame(old, terminal.emulator)
    }

    @Test
    fun `the emulator's own rows are what is read by default, and a blank command row is not searched for`() {
        val terminal = terminal()
        terminal.history.send("\r", { snapshot("$ x", cursor = 0) }) { true }

        // The real emulator holds nothing a unit test could have parsed into it: its command row is blank.
        terminal.replaceEmulator()

        assertEquals("", terminal.history.lastCommand(snapshot("$ x", "out", "$ ")))
    }

    @Test
    fun `a restarted shell does not inherit the row the old one's last command was on`() {
        val terminal = terminal()
        terminal.history.send("\r", { snapshot("$ echo old", "old", "$ seq 1 3", cursor = 2) }) { true }

        terminal.restart()
        // Until the new shell's replay comes, the old emulator is still what is on screen.
        assertEquals("", terminal.history.lastCommand(snapshot("$ echo old", "old", "$ seq 1 3", "1", "2", "3", "$ ")))
        terminal.replaceEmulator { snapshot("$ echo old", "old", "$ seq 1 3", "1", "2", "3", "$ ") }
        // The new shell's banner reaches the row the old command was on.
        val banner = snapshot("Welcome", "Last login: today", "message of the day", "", "$ ")

        assertEquals("", terminal.history.lastCommand(banner))
    }

    @Test
    fun `a hidden terminal starts with no emulator, stays parked across a reconnect, and builds one only when shown`() {
        val terminal = terminal(shown = false)
        assertNull(terminal.emulator)
        assertEquals("", terminal.lastCommand())
        assertEquals("", terminal.lastOutput())
        assertTrue(terminal.bufferUrls().isEmpty())

        // Reconnect while parked must not allocate an emulator for a hidden tab.
        terminal.replaceEmulator()
        assertNull(terminal.emulator)

        terminal.setShown(true)
        assertNotNull(terminal.emulator)
    }

    @Test
    fun `parking calls history replaced before closing the parked emulator so the last command survives unpark and reconnect`() {
        val terminal = terminal()
        terminal.history.send("\r", { snapshot("$ echo old", "old", "$ seq 1 3", cursor = 2) }) { true }
        val old = terminal.emulator
        var readBeforeClose: TerminalEmulator? = null

        terminal.parkEmulator {
            readBeforeClose = it
            assertFalse("buffer snapshot must be read before closing the parked emulator", it.isClosed)
            assertSame(old, terminal.emulator)
            snapshot("$ echo old", "old", "$ seq 1 3", "1", "2", "3", "$ ")
        }

        assertSame(old, readBeforeClose)
        assertNull(terminal.emulator)
        assertTrue(old!!.isClosed)

        // Even if a reconnect happens while parked, the carried command text is preserved until shown again.
        terminal.replaceEmulator()
        assertNull(terminal.emulator)

        val replayed = snapshot("banner", "$ echo old", "old", "$ seq 1 3", "1", "2", "3", "$ ")
        assertEquals("$ seq 1 3", terminal.history.lastCommand(replayed))
        assertEquals("1\n2\n3\n$", terminal.history.lastOutput(replayed))
    }

    @Test
    fun `canPark refuses when alternate screen or mouse tracking is active or when the ring trimmed`() {
        val terminal = terminal()
        assertTrue(terminal.canPark { false to false })
        assertFalse("must not park on alternate screen", terminal.canPark { true to false })
        assertFalse("must not park when mouse tracking is on", terminal.canPark { false to true })
    }
}
