package com.hienlh.ppm.terminal

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.connectbot.terminal.TerminalBufferLine
import org.connectbot.terminal.TerminalBufferSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a terminal keeps when its socket comes back. Android cuts the socket of an app that has been away for a
 * minute, and the server then replays the shell's whole buffer into a new emulator, rows and all. The web clears its
 * terminal and keeps the refs that mark the last command, so Send to Chat and Copy still hold that command and
 * nothing before it. The emulator here is the real one, built and closed without the JNI that only a parse needs, so
 * the rows the replay would draw are handed over as snapshots.
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

    private fun terminal() = NativeTerminal(
        ApiClient("http://127.0.0.1:9/".toHttpUrl(), "token"), "project", "tab", CoroutineScope(Dispatchers.Unconfined), store, "server:",
        copyToClipboard = {},
    ).also { terminals += it }

    private fun snapshot(vararg rows: String, cursor: Int = rows.lastIndex) = TerminalBufferSnapshot(
        rows.map { TerminalBufferLine(it, false) }, cursor, 0, 0, alternateScreen = false,
    )

    @Test
    fun `the last command is still the last command once the server has replayed the shell into a new emulator`() {
        val terminal = terminal()
        // Enter at the prompt of `seq 1 3`, row 2, under an earlier command's output.
        terminal.history.send("\r", { snapshot("$ echo old", "old", "$ seq 1 3", cursor = 2) }) { true }

        terminal.replaceEmulator()
        val replayed = snapshot("$ echo old", "old", "$ seq 1 3", "1", "2", "3", "$ ")

        assertEquals("$ seq 1 3", terminal.history.lastCommand(replayed))
        assertEquals("1\n2\n3\n$", terminal.history.lastOutput(replayed))
    }

    @Test
    fun `a restarted shell does not inherit the row the old one's last command was on`() {
        val terminal = terminal()
        terminal.history.send("\r", { snapshot("$ echo old", "old", "$ seq 1 3", cursor = 2) }) { true }

        terminal.restart()
        terminal.replaceEmulator()
        // The new shell's banner reaches the row the old command was on.
        val banner = snapshot("Welcome", "Last login: today", "message of the day", "", "$ ")

        assertEquals("", terminal.history.lastCommand(banner))
    }
}
