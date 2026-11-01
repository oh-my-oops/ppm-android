package com.hienlh.ppm.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.design.PpmTheme
import com.hienlh.ppm.protocol.isJsSpace
import com.hienlh.ppm.state.terminal.Keystroke
import com.hienlh.ppm.state.terminal.NamedKey
import com.hienlh.ppm.state.terminal.TerminalController
import com.hienlh.ppm.state.terminal.TerminalText
import kotlinx.coroutines.CoroutineScope
import org.connectbot.terminal.TerminalBufferSnapshot
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import org.connectbot.terminal.VTermKey

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
    internal val history = NativeTerminalHistory()
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
        // The replay is shown but never answered: the engine drops what it asks (cursor position, device
        // attributes, window size), which would otherwise be typed at the prompt it comes back to.
        onReplay = { bytes -> if (!closed) emulator.writeReplay(bytes) },
    )

    /**
     * What a reconnect asks for, ahead of the server replaying the shell's buffer into the new emulator. The command
     * boundary is kept, as the web keeps its refs: it is a row number, and the replay builds the same rows again. Dropped
     * here, a Send to Chat or a Copy with nothing selected had no command to start from and took the whole scrollback.
     * [restart] drops it, for a shell that is another one.
     */
    internal fun replaceEmulator() {
        if (closed) return
        val previous = emulator
        val dimensions = previous.dimensions
        // Invalidate posted callbacks before draining/closing the old parser's work queue.
        generation++
        previous.close()
        emulator = newEmulator(dimensions.rows, dimensions.columns).also { applyTheme(it) }
    }

    /** A fresh shell, which the old one's rows say nothing about: its command boundary goes too. */
    fun restart() {
        history.reset()
        controller.restart()
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

    /**
     * A key of the toolbar's row, handed to the emulator to encode as a keyboard's would be. Two
     * things need that over writing bytes to the shell. The emulator's queue is the one the soft
     * keyboard commits through, so the key lands after the text the IME was just made to flush
     * (`finishBeforeExternalInput`), which a direct write would overtake. And only libvterm knows
     * whether the program running asked for application cursor keys, so an arrow is `ESC O A` in vim
     * and htop and `ESC [ A` at a prompt, where the web sent `ESC [ A` always. The bytes come back
     * through `onKeyboardInput` like any typed key's. False when the terminal is closed.
     */
    fun sendKey(keystroke: Keystroke): Boolean {
        if (closed) return false
        when (val key = engineKey(keystroke)) {
            is EngineKey.Special -> emulator.dispatchKey(key.modifiers, key.code)
            is EngineKey.Text -> emulator.dispatchCharacter(key.modifiers, key.codepoint)
        }
        return true
    }

    /** The prompt row where Enter was last sent. Works without shell OSC integration. */
    fun lastCommand(): String = if (closed) "" else history.lastCommand(emulator.getBufferSnapshot())

    /** Rows after that Enter through the cursor; before any Enter, the screen to the cursor, not the scrollback above it. */
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

/** libvterm's `VTERM_MOD_CTRL`. The engine keeps its own copy of the modifier bits private. */
private const val VTERM_MOD_CTRL = 4

/** A [Keystroke] as one of the two calls the emulator takes: a `VTermKey` or a character, and libvterm's modifier bits. */
internal sealed interface EngineKey {
    val modifiers: Int

    data class Special(override val modifiers: Int, val code: Int) : EngineKey

    data class Text(override val modifiers: Int, val codepoint: Int) : EngineKey
}

internal fun engineKey(keystroke: Keystroke): EngineKey {
    val modifiers = if (keystroke.ctrl) VTERM_MOD_CTRL else 0
    return when (keystroke) {
        is Keystroke.Character -> EngineKey.Text(modifiers, keystroke.char.code)
        is Keystroke.Named -> EngineKey.Special(
            modifiers,
            when (keystroke.key) {
                NamedKey.Tab -> VTermKey.TAB
                NamedKey.Escape -> VTermKey.ESCAPE
                NamedKey.Up -> VTermKey.UP
                NamedKey.Down -> VTermKey.DOWN
                NamedKey.Left -> VTermKey.LEFT
                NamedKey.Right -> VTermKey.RIGHT
            },
        )
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

    /**
     * The rows after the last Enter through the cursor. With no Enter seen there is no command to start from: the web
     * starts at row 0, which sends whatever the server replayed (up to 1 MB) into a chat chip, so here it is the
     * screen, from its top row.
     */
    fun lastOutput(snapshot: TerminalBufferSnapshot): String {
        val start = if (commandRow < 0) snapshot.screenStartRow else outputStartRow
        val end = snapshot.cursorRow.coerceAtMost(snapshot.lines.lastIndex)
        if (end < start) return ""
        val rows = (start..end).map { snapshot.lines[it].text.trimEnd(::isJsSpace) }.toMutableList()
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
