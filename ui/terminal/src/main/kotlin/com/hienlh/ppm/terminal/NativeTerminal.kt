package com.hienlh.ppm.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.design.PpmTheme
import com.hienlh.ppm.protocol.isJsSpace
import com.hienlh.ppm.state.terminal.TerminalController
import com.hienlh.ppm.state.terminal.TerminalText
import kotlinx.coroutines.CoroutineScope
import org.connectbot.terminal.TerminalBufferSnapshot
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory

/** Kept by the workspace, not by a composable: hiding a tab must not end its shell. */
class NativeTerminal(
    api: ApiClient,
    val project: String,
    tabId: String,
    scope: CoroutineScope,
    store: KeyValueStore,
    keyPrefix: String,
    cwd: String? = null,
    initialSessionId: String = "new",
    private val copyToClipboard: (String) -> Unit,
) {
    private var theme: PpmTheme? = null
    private var generation = 0
    private var closed = false
    private val history = NativeTerminalHistory()
    var pendingCommand by mutableStateOf<String?>(null)
    var emulator by mutableStateOf(newEmulator())
        private set

    val controller = TerminalController(
        api = api, project = project, tabId = tabId, scope = scope, store = store,
        keyPrefix = keyPrefix, cwd = cwd, initialSessionId = initialSessionId,
        onOutput = { bytes -> if (!closed) emulator.writeInput(bytes) },
        // Clear screen alone leaves scrollback behind. A new parser also resets alternate
        // screen/cursor modes before the server's replay, without creating another PTY.
        onReset = ::replaceEmulator,
    )

    private fun replaceEmulator() {
        if (closed) return
        val previous = emulator
        val dimensions = previous.dimensions
        // Invalidate posted callbacks before draining/closing the old parser's work queue.
        generation++
        previous.close()
        history.reset()
        emulator = newEmulator(dimensions.rows, dimensions.columns).also { applyTheme(it) }
    }

    private fun newEmulator(rows: Int = 24, columns: Int = 80): TerminalEmulator {
        val ownGeneration = generation
        return TerminalEmulatorFactory.create(
            initialRows = rows, initialCols = columns,
            onKeyboardInput = { if (!closed && ownGeneration == generation) sendBytes(it) },
            onResize = { if (!closed && ownGeneration == generation) controller.resize(it.columns, it.rows) },
            onClipboardCopy = { if (!closed && ownGeneration == generation) copyToClipboard(it) },
            autoDetectUrls = true,
            maxScrollbackLines = 50_000,
        )
    }

    /** Shared by the native keyboard, toolbar and injected commands; never queues rejected input. */
    fun send(text: String): Boolean = !closed && history.send(text, emulator::getBufferSnapshot, controller::send)

    /** A complete UTF-8 input chunk, as emitted by the native keyboard callback. */
    fun sendBytes(bytes: ByteArray): Boolean = send(bytes.toString(Charsets.UTF_8))

    /** The prompt row where Enter was last sent. Works without shell OSC integration. */
    fun lastCommand(): String = if (closed) "" else history.lastCommand(emulator.getBufferSnapshot())

    /** Rows after that Enter through the cursor; before any Enter, the whole buffer to the cursor. */
    fun lastOutput(): String = if (closed) "" else history.lastOutput(emulator.getBufferSnapshot())

    /** The web's physical-row scan, with native soft-wrap flags translated to xterm's direction. */
    fun bufferUrls(): List<String> = if (closed) emptyList() else history.bufferUrls(emulator.getBufferSnapshot())

    fun theme(value: PpmTheme) {
        if (closed || theme == value) return
        theme = value
        applyTheme(emulator)
    }

    private fun applyTheme(target: TerminalEmulator) {
        val current = theme ?: return
        target.applyColorScheme(terminalPalette(current), current.tokens.text.color.toArgb(), current.tokens.bgSolid.color.toArgb())
    }

    fun close() {
        if (closed) return
        closed = true
        generation++
        controller.close()
        pendingCommand = null
        history.reset()
        emulator.close()
    }
}

/** Pure row accounting from use-terminal.ts; no OSC markers or Android/JNI needed. */
internal class NativeTerminalHistory {
    private var commandRow = -1
    private var outputStartRow = 0

    fun reset() {
        commandRow = -1
        outputStartRow = 0
    }

    fun send(text: String, snapshot: () -> TerminalBufferSnapshot, transmit: (String) -> Boolean): Boolean {
        // Read before sending: a later echo may already have moved the cursor to another row.
        val row = if ('\r' in text || '\n' in text) snapshot().cursorRow else null
        if (!transmit(text)) return false
        if (row != null) {
            commandRow = row
            outputStartRow = row + 1
        }
        return true
    }

    fun lastCommand(snapshot: TerminalBufferSnapshot): String =
        snapshot.lines.getOrNull(commandRow)?.text?.trimEnd(::isJsSpace).orEmpty()

    fun lastOutput(snapshot: TerminalBufferSnapshot): String {
        val end = snapshot.cursorRow.coerceAtMost(snapshot.lines.lastIndex)
        if (end < outputStartRow) return ""
        val rows = (outputStartRow..end).map { snapshot.lines[it].text.trimEnd(::isJsSpace) }.toMutableList()
        while (rows.lastOrNull()?.all(::isJsSpace) == true) rows.removeAt(rows.lastIndex)
        // As on the web, output keeps physical row breaks, even where the terminal soft-wrapped.
        return rows.joinToString("\n")
    }

    fun bufferUrls(snapshot: TerminalBufferSnapshot): List<String> = TerminalText.bufferUrlsFromRows(
        snapshot.lines.mapIndexed { index, line ->
            TerminalText.BufferLine(
                text = line.text.trimEnd(::isJsSpace),
                isWrapped = index > 0 && snapshot.lines[index - 1].isWrapped,
            )
        },
        snapshot.cursorRow,
    )
}
