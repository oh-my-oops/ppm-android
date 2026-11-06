package com.hienlh.ppm.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.design.BuiltInThemes
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
        onReset = { replaceEmulator() },
        // The replay is shown but never answered: the engine drops what it asks (cursor position, device
        // attributes, window size), which would otherwise be typed at the prompt it comes back to.
        onReplay = { bytes -> if (!closed) emulator.writeReplay(bytes) },
    )

    /**
     * What a reconnect asks for, ahead of the server replaying the shell's buffer into the new emulator. The last
     * command is carried over as its text, read from [bufferOf] the emulator being replaced before it closes (its row as
     * the shell finally left it, which is what the replay draws), and looked for in the replay: a row number of the old
     * buffer says nothing about the new one, which has more rows above the command or fewer. With fewer the old number
     * is past the replay's cursor and Send to Chat and Copy answered nothing, as on a themed zsh; with more it points at
     * other rows. Dropped, they had no command to start from and took the screen. [restart] drops it, for a shell that
     * is another one.
     */
    internal fun replaceEmulator(bufferOf: (TerminalEmulator) -> TerminalBufferSnapshot = TerminalEmulator::getBufferSnapshot) {
        if (closed) return
        val previous = emulator
        val dimensions = previous.dimensions
        history.replaced(bufferOf(previous))
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
        val current = theme ?: BuiltInThemes.auroraDark
        return TerminalEmulatorFactory.create(
            initialRows = rows, initialCols = columns,
            defaultForeground = current.tokens.text.color,
            defaultBackground = current.tokens.bgSolid.color,
            defaultCursorColor = current.tokens.accent.color,
            onKeyboardInput = { if (!closed && ownGeneration == generation) sendBytes(it) },
            // The first report, made when the view is laid out, is what lets the controller write into this emulator: the server
            // replays the shell's buffer at once and an emulator wraps what it is written at the width it has then, so the
            // controller holds the output until it has the real one.
            onResize = { if (!closed && ownGeneration == generation) controller.resize(it.columns, it.rows) },
            onClipboardCopy = { if (!closed && ownGeneration == generation) copyToClipboard(it) },
            // The engine's own scan underlined every URL and opened one on any tap. A link is found where the web finds one, for a mouse.
            autoDetectUrls = false,
            maxScrollbackLines = 50_000,
        )
    }

    /**
     * Shared by the native keyboard, toolbar and injected commands; never queues rejected input. The rows it reads before a key
     * are the screen's, not the history's: a read of 50,000 lines of history, under the parser's lock, held a first key back.
     */
    fun send(text: String): Boolean = !closed && history.send(text, emulator::getScreenSnapshot, controller::send)

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

    /**
     * The prompt row where Enter was last sent; where the text typed is now, when the shell redrew its prompt after it
     * (a themed zsh collapses it to one row). Works without shell OSC integration.
     */
    fun lastCommand(): String = if (closed) "" else history.lastCommand(emulator.getBufferSnapshot())

    /**
     * Rows after that command through the cursor; with no command known (no Enter yet, or one the buffer does not hold any
     * more, as after a reconnect's replay), the screen to the cursor, not the scrollback above it.
     */
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
        target.applyColorScheme(
            terminalPalette(current),
            current.tokens.text.color.toArgb(),
            current.tokens.bgSolid.color.toArgb(),
            current.tokens.accent.color.toArgb(),
        )
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

/**
 * Pure row accounting from use-terminal.ts; no OSC markers or Android/JNI needed.
 *
 * The web takes the row the cursor is on when Enter is sent for the command's, and so does the first rule here. A shell that
 * redraws its prompt after Enter leaves that row holding something else: a themed zsh collapses its four-row prompt to
 * `❯ seq 1 3` and moves everything below it up three, so the row holds the last row of the output, or the next prompt's
 * banner when the output is one row. So what was typed is remembered too, and looked for once the row no longer starts as it did.
 */
internal class NativeTerminalHistory {
    private var commandRow = -1

    /**
     * What [commandRow] held up to the cursor when Enter was sent: the prompt, and what had been typed after it. The row is
     * the command's for as long as it still starts with this. Only the start is compared, because the rest of the row can
     * change without the row moving: the echo of what was typed may not have come back when Enter was pressed, and a
     * suggestion shown after the cursor is gone once it has.
     */
    private var enterText = ""

    /**
     * What was typed for that command: the keys, when the line ends with them ([typed]), else what the line holds from the end of the
     * prompt to the cursor. Empty when neither could say.
     */
    private var commandText = ""

    /**
     * What the cursor's line held, up to the cursor, at the first input after the last Enter (or Ctrl-C or Ctrl-Z): the
     * prompt. It is text, not a position, because the rows move when the shell redraws the prompt. Null until that input
     * comes; only that input looks at the buffer, the rest is typed after the prompt.
     */
    private var prompt: String? = null

    /**
     * The prompt of the line a Ctrl-C or a Ctrl-Z was pressed on. Ctrl-C draws a new prompt, and so does a Ctrl-Z that stops a job,
     * but a Ctrl-Z at an idle prompt draws nothing: the line stays as it was, with what was typed on it. Nothing is drawn yet when the
     * key is sent, so the next input decides: a line that starts with this is still that line, and its prompt is this, not whatever
     * the line holds now. A blank prompt is not kept, it says nothing about the one that follows.
     */
    private var kept: String? = null

    /**
     * What the keys sent since the last Enter spell: text, less what Backspace and Ctrl-U took off. Any other key stays in it as the
     * character it sent, which no row holds, so a line the keys did more to than type on (Tab, an arrow, Ctrl-W, a paste) is not
     * guessed at. A key typed while a command runs reads the row where the tty echoes it, which holds no prompt for [prompt] to be, and
     * the shell takes what was typed ahead into its next line: that line ends with this, and is the command when it does.
     */
    private var typed = ""

    /**
     * What the last command's row said when its emulator was replaced ([replaced]). The row number is of a buffer that
     * is gone, so while this is set the row is looked for by this text, above the cursor, on every read, and it is
     * never taken for found: a read while the replay is still arriving can match an earlier row of the same text.
     * A new Enter or a [reset] ends it.
     */
    private var carried: String? = null

    fun reset() {
        commandRow = -1
        enterText = ""
        commandText = ""
        prompt = null
        kept = null
        typed = ""
        carried = null
    }

    fun send(text: String, snapshot: () -> TerminalBufferSnapshot, transmit: (String) -> Boolean): Boolean {
        val enter = '\r' in text || '\n' in text
        // A stopped command usually gives way to a new prompt, as an Enter does, but a Ctrl-Z at an idle prompt does not ([kept]).
        val interrupt = text.any { it == '\u0003' || it == '\u001a' }
        // Read before sending: a later echo may already have moved the cursor to another row.
        val buffer = if (enter || (prompt == null && !interrupt)) snapshot() else null
        if (!transmit(text)) return false
        if (buffer != null) {
            val line = lineToCursor(buffer)
            if (enter) {
                // The read may be the screen alone ([TerminalBufferSnapshot.firstRow]): the row is kept as the whole buffer numbers it.
                commandRow = buffer.firstRow + buffer.cursorRow
                enterText = buffer.lines.getOrNull(buffer.cursorRow)?.text?.take(buffer.cursorColumn)?.trimEnd(::isJsSpace).orEmpty()
                commandText = commandIn(line)
                carried = null
            } else if (!buffer.alternateScreen) {
                // In the alternate screen the keys are an editor's, not typed at a prompt.
                prompt = kept?.takeIf(line::startsWith) ?: line
            }
        }
        typed = typedAfter(text)
        if (enter) {
            prompt = null
            kept = null
        } else if (interrupt) {
            prompt?.let { kept = it.takeUnless { word -> word.all(::isJsSpace) } }
            prompt = null
        }
        return true
    }

    /**
     * What was typed for the command whose Enter is being sent, from the cursor's [line]. What the keys spelled, when the line ends
     * with it ([endsWithCommand]); that is the one that holds when the keys were typed before the prompt was there. Otherwise what
     * follows the prompt.
     */
    private fun commandIn(line: String): String {
        val spelled = typed.trim(::isJsSpace)
        if (spelled.isNotEmpty() && endsWithCommand(line, spelled)) return spelled
        return (prompt ?: kept)?.takeIf(line::startsWith)?.let { line.substring(it.length).trim(::isJsSpace) }.orEmpty()
    }

    /** What [typed] is once [text] has been sent. An Enter starts the next line, so only what follows the last one is on it. */
    private fun typedAfter(text: String): String {
        val enter = text.lastIndexOfAny(charArrayOf('\r', '\n'))
        var line = if (enter >= 0) "" else typed
        for (char in text.substring(enter + 1)) {
            line = when (char) {
                '\u007f', '\b' -> if (line.isEmpty()) line else line.dropLast(Character.charCount(line.codePointBefore(line.length)))
                '\u0015' -> ""
                else -> line + char
            }
        }
        return line
    }

    /**
     * The emulator holding [old] is being replaced by one the server's replay will fill. Its row of the last command is
     * read now, as the shell left it: a collapsed prompt's row is the collapsed one, found as [commandRowIn] finds it. A
     * command whose row has no text cannot be told from the blank rows of a replay, and one already carried over an
     * earlier replacement is kept: [commandRow] is of a buffer that is already gone.
     */
    fun replaced(old: TerminalBufferSnapshot) {
        if (carried != null) return
        carried = old.lines.getOrNull(commandRowIn(old))?.text?.trimEnd(::isJsSpace)?.takeUnless { it.all(::isJsSpace) }
        commandRow = -1
    }

    /**
     * The row of the last command in [snapshot]; -1 for none. The row Enter was sent on, while it still starts as it did
     * then. Or, once a replacement carried the command's text over, the nearest row above the cursor with that text. Failing
     * either, the last row of the nearest line that ends with what was typed ([rowEndingWith]).
     */
    private fun commandRowIn(snapshot: TerminalBufferSnapshot): Int {
        val lines = snapshot.lines
        val carried = carried
        if (carried != null) {
            for (row in minOf(snapshot.cursorRow, lines.size) - 1 downTo 0) {
                if (lines[row].text.trimEnd(::isJsSpace) == carried) return row
            }
        } else if (lines.getOrNull(commandRow)?.text?.startsWith(enterText) == true) {
            return commandRow
        }
        return rowEndingWith(snapshot)
    }

    /**
     * The last row of the nearest line above the cursor that ends with what was typed; -1 for none. A line is a row and the
     * rows that wrap into it, so a command that wrapped, and wraps elsewhere after a redraw, is still one line. Its last row
     * is the command row, as the web's is, and the output starts under it. The cursor's own row is the prompt being typed at,
     * which may hold the same text (the last command recalled with Up). The text must have something before it that is not a
     * letter or a digit, a prompt: `tools` is not an `ls`, and a row of the output that is only `ls` is not the command.
     */
    private fun rowEndingWith(snapshot: TerminalBufferSnapshot): Int {
        if (commandText.isEmpty()) return -1
        val lines = snapshot.lines
        var end = minOf(snapshot.cursorRow, lines.size) - 1
        while (end >= 0) {
            var first = end
            while (first > 0 && lines[first - 1].isWrapped) first--
            val line = buildString { for (row in first..end) append(lines[row].text) }.trimEnd(::isJsSpace)
            if (endsWithCommand(line, commandText)) return end
            end = first - 1
        }
        return -1
    }

    /**
     * Whether [line] ends with [text] as a command does: with a prompt before it, and not in the middle of a word. `tools` is not an
     * `ls`, and a row that is only the command is not a prompt followed by it.
     */
    private fun endsWithCommand(line: String, text: String): Boolean {
        val before = line.length - text.length - 1
        return before >= 0 && line.endsWith(text) && !line[before].isLetterOrDigit()
    }

    /**
     * The cursor's line, from its first row with the soft-wrapped rows joined, up to the cursor: the prompt and what is typed
     * after it, without a suggestion or a right prompt further along the row. Empty in the alternate screen. A character is a
     * column only when it is narrow, so a wide one before the cursor puts the cut later than the cursor; what that takes in
     * is blanks, unless a suggestion starts right there.
     */
    private fun lineToCursor(buffer: TerminalBufferSnapshot): String {
        val lines = buffer.lines
        val cursor = buffer.cursorRow
        if (buffer.alternateScreen || cursor !in lines.indices) return ""
        var first = cursor
        while (first > 0 && lines[first - 1].isWrapped) first--
        return buildString {
            for (row in first until cursor) append(lines[row].text)
            append(lines[cursor].text.take(buffer.cursorColumn))
        }.trimEnd(::isJsSpace)
    }

    fun lastCommand(snapshot: TerminalBufferSnapshot): String =
        snapshot.lines.getOrNull(commandRowIn(snapshot))?.text?.trimEnd(::isJsSpace).orEmpty()

    /**
     * The rows after the last command through the cursor. With no command known there is nothing to start from: the web
     * starts at row 0, which sends whatever the server replayed (up to 1 MB) into a chat chip, so here it is the
     * screen, from its top row. That is a shell no Enter was sent to, and a command carried over a reconnect that the
     * replay, so far, does not hold.
     */
    fun lastOutput(snapshot: TerminalBufferSnapshot): String {
        val row = commandRowIn(snapshot)
        val start = if (row < 0) snapshot.screenStartRow else row + 1
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
