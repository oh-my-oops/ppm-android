package org.connectbot.terminal

import java.nio.ByteBuffer

/**
 * libvterm's parse and screen, modelled for the engine's JVM tests: its JNI cannot load on the JVM, so the emulator under test
 * gets a native whose `writeInput` reads the bytes and calls the emulator back as the shipped library does, and whose `getCells`
 * answers from a screen of cells the model keeps. The callbacks, their order and what each one says are `vterm.c`, `state.c`,
 * `screen.c` and `Terminal.cpp` of the pinned termlib 0.3.13, `VTERM_DAMAGE_SCROLL` merging included: a row pushed into the
 * history and an OSC reach the emulator as they happen, the one `moverect` of a merged scroll and one `damage` for everything
 * that changed only when the write is flushed, which is the end of each [write] (the JNI flushes after every write).
 *
 * The emulator is given this through `TerminalEmulatorImpl`'s `nativeFactory`. Only what the engine's tests need is modelled: ASCII text with auto-wrap, CR, LF, BS, the cursor movements, ED / EL / ECH, ICH /
 * DCH, IL / DL, SU / SD, DECSTBM, DECOM, RI, RIS, DECSTR, the alternate screen and the saved cursor (`?1047`, `?1048`, `?1049`, each argument of one
 * `CSI ? Pm h` or `l` in turn), OSC and a resize in rows and columns (rows pushed into
 * the history and popped back, as `resize_buffer` does them with reflow off) and the colours a default cell resolves to. Anything else in the input is ignored,
 * and the model refuses wide characters rather than guess. `EngineInstrumentation` runs the same bytes through the real library on
 * a device, so the model cannot drift from it unnoticed.
 */
internal class FakeVterm(rows: Int, cols: Int) : ScreenNative {
    private class Rect(var startRow: Int, var endRow: Int, var startCol: Int, var endCol: Int) {
        fun copy() = Rect(startRow, endRow, startCol, endCol)
        fun intersects(o: Rect) = !(startRow > o.endRow || o.startRow > endRow) && !(startCol > o.endCol || o.startCol > endCol)
        fun equalTo(o: Rect) = startRow == o.startRow && endRow == o.endRow && startCol == o.startCol && endCol == o.endCol
        fun contains(small: Rect) = small.startRow >= startRow && small.startCol >= startCol && small.endRow <= endRow && small.endCol <= endCol
        fun expand(o: Rect) {
            startRow = minOf(startRow, o.startRow); startCol = minOf(startCol, o.startCol)
            endRow = maxOf(endRow, o.endRow); endCol = maxOf(endCol, o.endCol)
        }
        fun clip(bounds: Rect) {
            startRow = maxOf(startRow, bounds.startRow); startCol = maxOf(startCol, bounds.startCol)
            endRow = minOf(endRow, bounds.endRow); endCol = minOf(endCol, bounds.endCol)
            if (endRow < startRow) endRow = startRow
            if (endCol < startCol) endCol = startCol
        }
    }

    var rows = rows
        private set
    var cols = cols
        private set

    private var primary = blankScreen()
    private var alternate: Array<CharArray>? = null
    private var screen = primary
    private var primaryContinuation = BooleanArray(rows)
    private var alternateContinuation = BooleanArray(rows)

    /** `lineinfo[row].continuation` of the buffer in use: the row continues the one above it. */
    private var continuation = primaryContinuation
    private var onAlternate = false

    /** The colours the library resolves a default cell to (`setDefaultColors`): every cell it reports carries them, so a new scheme changes what is read. */
    private var defaultForeground = 0
    private var defaultBackground = 0

    private var row = 0
    private var col = 0
    private var atPhantom = false
    private var savedRow = 0
    private var savedCol = 0
    private var regionTop = 0
    private var regionBottom = rows

    /** `mode.origin` (DECOM): rows are counted from the top of the scroll region, and the cursor stays inside it. */
    private var origin = false

    // The remaining DEC modes `set_dec_mode` and `request_dec_mode` of state.c keep, with the defaults `vterm_state_reset` gives them.
    private var cursorKeyMode = false
    private var reverseScreen = false
    private var autoWrap = true
    private var cursorBlink = true
    private var cursorVisible = true
    private var leftRightMargin = false
    private var mouseFlags = 0
    private var reportFocus = false
    private var mouseProtocol = 0
    private var bracketPaste = false
    private var savedCursorVisible = true
    private var savedCursorBlink = true

    /** `scrollregion_bottom == -1`: the region reaches the last row, whatever the screen's height becomes. */
    private var regionBottomUnset = true

    // The screen layer.
    private var damaged: Rect? = null
    private var pendingScroll: Rect? = null
    private var pendingDown = 0
    private var pendingRight = 0

    /** Every `writeInput` the emulator made on the native, as (bytes). The pieces a write was cut into show here. */
    val writes = mutableListOf<String>()

    private fun blankScreen() = Array(rows) { CharArray(cols) { BLANK } }

    private lateinit var emulator: TerminalCallbacks

    /** The emulator this is the native of: handed over when it first asks for its native, as the JNI is given its callbacks. */
    fun attach(callbacks: TerminalCallbacks): FakeVterm {
        emulator = callbacks
        return this
    }

    /** Where the library's cursor is: its row and column. */
    val cursor: Pair<Int, Int> get() = row to col

    override fun writeInput(buffer: ByteBuffer, length: Int): Int {
        val bytes = ByteArray(length)
        buffer.duplicate().apply { position(0) }.get(bytes)
        write(bytes, 0, length)
        return length
    }

    override fun writeInput(data: ByteArray, offset: Int, length: Int): Int {
        write(data, offset, length)
        return length
    }

    override fun getCells(buffer: ByteBuffer, requests: Int): Int = cellsFor(buffer, requests)
    /**
     * `vterm_set_size`: `on_resize` of state.c has `resize()` of screen.c rebuild both screens with `resize_buffer`, then the whole
     * screen is damaged and the JNI flushes at once. The JNI turns reflow off, so each row stays one row, from the bottom up: rows
     * that are blank at the bottom are let go before anything is pushed, the rows that no longer fit at the top go to the history,
     * the rows a taller screen has room for at the top come back from it, and every line info of the new screens is clear, so
     * nothing is a continuation any more. The rows are pushed while the old line infos are still there, which is all
     * `Terminal::termSbPushline` reads, so every push of the batch carries the flag of row 1 as it was. The cells pushed are those of
     * the screen in use (`sb_pushline_from_row` reads `screen->buffer`), the alternate one when that is: its rows go to the history
     * as the primary screen's would.
     */
    override fun resize(rows: Int, cols: Int): Int {
        val onAlternateNow = onAlternate
        val rebuiltPrimary = resizeBuffer(primary, true, rows, cols, active = !onAlternateNow)
        val rebuiltAlternate = alternate?.let { resizeBuffer(it, false, rows, cols, active = onAlternateNow) }
        val rebuilt = if (onAlternateNow) rebuiltAlternate!! else rebuiltPrimary
        primary = rebuiltPrimary.cells
        primaryContinuation = rebuiltPrimary.continuation
        alternate = rebuiltAlternate?.cells
        alternateContinuation = rebuiltAlternate?.continuation ?: BooleanArray(rows)
        screen = if (onAlternateNow) alternate!! else primary
        continuation = if (onAlternateNow) alternateContinuation else primaryContinuation
        this.rows = rows
        this.cols = cols
        regionBottom = if (regionBottomUnset) rows else minOf(regionBottom, rows)
        row = rebuilt.cursorRow
        col = rebuilt.cursorCol
        if (atPhantom && col < cols - 1) { atPhantom = false; col++ }
        row = row.coerceIn(0, rows - 1)
        col = col.coerceIn(0, cols - 1)
        damagerect(Rect(0, rows, 0, cols))
        flush()
        reportCursor()
        return 0
    }

    /** One screen as `resize_buffer` leaves it: its cells, its line infos, and where the cursor is on it. */
    private class Rebuilt(val cells: Array<CharArray>, val continuation: BooleanArray, val cursorRow: Int, val cursorCol: Int)

    /** `resize_buffer` of screen.c with reflow off, over the screen [old] of the old size; the model's own size is still the old one. */
    private fun resizeBuffer(old: Array<CharArray>, primaryScreen: Boolean, newRows: Int, newCols: Int, active: Boolean): Rebuilt {
        val oldRows = rows
        val oldCols = cols
        val cells = Array(newRows) { CharArray(newCols) { BLANK } }
        val lineInfo = BooleanArray(newRows)
        var oldRow = oldRows - 1
        var newRow = newRows - 1
        val oldCursorRow = row
        val oldCursorCol = col
        var newCursorRow = -1
        var newCursorCol = -1
        // The topmost row of the blank rows at the bottom, which are what a shrinking screen scrolls into before it pushes anything.
        var finalBlankRow = newRows

        while (oldRow >= 0) {
            val oldRowStart = oldRow
            var width = oldCols - 1
            while (width >= 0 && old[oldRowStart][width] == BLANK) width--
            width++
            if (finalBlankRow == newRow + 1 && width == 0) finalBlankRow = newRow
            var newRowStart = newRow
            val spareRows = newRows - finalBlankRow
            if (newRowStart < 0 && spareRows >= 0 && (!active || newCursorRow == -1 || (newCursorRow - newRowStart) < newRows)) {
                // Scroll what is placed down into the blank rows at the bottom to make this one fit.
                var downwards = -newRowStart
                if (downwards > spareRows) downwards = spareRows
                val rowCount = newRows - downwards
                for (i in rowCount - 1 downTo 0) { cells[downwards + i] = cells[i]; lineInfo[downwards + i] = lineInfo[i] }
                for (r in 0 until downwards) cells[r] = CharArray(newCols) { BLANK }
                newRow += downwards
                newRowStart += downwards
                if (newCursorRow >= 0) newCursorRow += downwards
                finalBlankRow += downwards
            }
            if (newRowStart < 0) break

            var takenRow = oldRowStart
            var oldCol = 0
            var count = if (width >= newCols) newCols else width
            var newCol = 0
            while (count > 0) {
                cells[newRowStart][newCol] = old[takenRow][oldCol]
                if (oldCursorRow == takenRow && oldCursorCol == oldCol) { newCursorRow = newRowStart; newCursorCol = newCol }
                oldCol++
                if (oldCol == oldCols) { takenRow++; newCol++; break }
                newCol++
                count--
            }
            if (oldCursorRow == takenRow && oldCursorCol >= oldCol) {
                newCursorRow = newRowStart
                newCursorCol = minOf(oldCursorCol - oldCol + newCol, newCols - 1)
            }
            lineInfo[newRowStart] = false
            oldRow = oldRowStart - 1
            newRow = newRowStart - 1
        }
        if (oldCursorRow <= oldRow) {
            newCursorRow = 0
            newCursorCol = minOf(oldCursorCol, newCols - 1)
        }
        check(!active || (newCursorRow != -1 && newCursorCol != -1)) { "screen_resize failed to update cursor position" }

        if (oldRow >= 0 && primaryScreen) {
            for (r in 0..oldRow) pushRow(r)
        }
        if (newRow >= 0 && primaryScreen) {
            while (newRow >= 0) {
                if (!popRow(cells[newRow], oldCols, newCols)) break
                newRow--
            }
        }
        if (newRow >= 0) {
            // Scroll the rows that were placed back up to the top, and leave blanks at the bottom.
            val moveRows = newRows - newRow - 1
            for (i in 0 until moveRows) { cells[i] = cells[newRow + 1 + i]; lineInfo[i] = lineInfo[newRow + 1 + i] }
            newCursorRow -= newRow + 1
            for (r in moveRows until newRows) { cells[r] = CharArray(newCols) { BLANK }; lineInfo[r] = false }
        }
        return Rebuilt(cells, lineInfo, newCursorRow, newCursorCol)
    }

    /** `sb_popline` and the copy that follows it: the row comes back with the cells both widths hold, and the rest of the new row blank. */
    private fun popRow(into: CharArray, oldCols: Int, newCols: Int): Boolean {
        val buffer = CellData.buffer()
        if (emulator.popScrollbackLine(oldCols, 0, oldCols, buffer) == 0) return false
        for (c in 0 until newCols) {
            into[c] = if (c < oldCols) buffer.getInt(c * CellData.BYTES).let { if (it == 0) BLANK else it.toChar() } else BLANK
        }
        return true
    }

    override fun dispatchKey(modifiers: Int, key: Int): Boolean = false
    override fun dispatchCharacter(modifiers: Int, character: Int): Boolean = false
    override fun setPaletteColors(colors: IntArray, count: Int): Int = count
    override fun setDefaultColors(foreground: Int, background: Int): Int {
        defaultForeground = foreground
        defaultBackground = background
        return 0
    }
    override fun setBoldHighbright(enabled: Boolean): Int = 0
    override fun pasteText(data: ByteArray) = Unit
    override fun close() = Unit

    // ---------------------------------------------------------------- the library's reading of a cell range, as Terminal::getCells

    private fun cellsFor(buffer: ByteBuffer, requests: Int): Int {
        var total = 0
        var offset = CellData.HEADER_BYTES
        for (request in 0 until requests) {
            val header = request * 16
            val r = buffer.getInt(header)
            val start = buffer.getInt(header + 4)
            val count = buffer.getInt(header + 8)
            // The row's flag is the one of the row *below* it, and the last row has none.
            buffer.putInt(header + 12, if (r + 1 < rows && continuation[r + 1]) 1 else 0)
            for (c in start until start + count) {
                for (slot in 0 until CellData.STRIDE) buffer.putInt(offset + slot * 4, 0)
                buffer.putInt(offset, screen[r][c].code)
                buffer.putInt(offset + CellData.WIDTH, 1)
                buffer.putInt(offset + CellData.FOREGROUND, defaultForeground)
                buffer.putInt(offset + CellData.BACKGROUND, defaultBackground)
                offset += CellData.BYTES
            }
            total += count
        }
        return total
    }

    // ---------------------------------------------------------------- the parser

    private enum class Mode { TEXT, ESC, CSI, OSC_COMMAND, OSC, OSC_ESC }

    private var mode = Mode.TEXT
    private val params = StringBuilder()
    private val osc = StringBuilder()
    private var oscCommand = -1

    /** Bytes taken since this was made, and the index just past the terminator of every OSC 8 the library finished: the places a write has to be cut at. */
    private var consumed = 0
    val oscEnds = mutableListOf<Int>()

    /** `vterm_input_write`, then the JNI's `vterm_screen_flush_damage`. */
    fun write(data: ByteArray, offset: Int, length: Int) {
        writes += String(data, offset, length, Charsets.ISO_8859_1)
        for (i in offset until offset + length) {
            feed(data[i].toInt() and 0xFF)
            consumed++
        }
        flush()
        reportCursor()
    }

    private var reportedRow = 0
    private var reportedCol = 0

    /**
     * `updatecursor`: the emulator hears where the cursor is. The library says so at every move and the emulator keeps the last, so
     * the model says it once a write or a resize is over, and only when the cursor is elsewhere than it was last said to be.
     */
    private fun reportCursor() {
        if (row == reportedRow && col == reportedCol) return
        emulator.moveCursor(CursorPosition(row, col), CursorPosition(reportedRow, reportedCol), true)
        reportedRow = row
        reportedCol = col
    }

    /**
     * `vterm_input_write` of parser.c, in UTF-8 mode, as far as strings go: CAN and SUB cancel anything open, only BEL and ESC \
     * end a string, an ESC followed by anything else cancels it (and the character is the end of an escape sequence of its own,
     * which is not modelled), and the other control characters inside a string run without ending it (not modelled either).
     */
    private fun feed(byte: Int) {
        if (mode != Mode.TEXT && (byte == 0x18 || byte == 0x1A)) {
            mode = Mode.TEXT
            return
        }
        when (mode) {
            Mode.TEXT -> when {
                byte == 0x1B -> mode = Mode.ESC
                byte == '\r'.code -> moveTo(row, 0)
                byte == '\n'.code -> { val old = row to col; linefeed(); cursorChanged(old) }
                byte == 0x08 -> moveTo(row, maxOf(0, col - 1))
                byte == 0x07 -> Unit
                byte >= 0x20 -> { check(byte < 0x7F) { "the model takes ASCII text only" }; print(byte.toChar()) }
            }
            Mode.ESC -> when {
                byte == '['.code -> { params.clear(); mode = Mode.CSI }
                byte == ']'.code -> { osc.clear(); oscCommand = -1; mode = Mode.OSC_COMMAND }
                byte == 'M'.code -> { reverseIndex(); mode = Mode.TEXT }
                byte == 'c'.code -> { reset(); mode = Mode.TEXT }
                byte == 0x1B || byte < 0x20 || byte == 0x7F -> Unit
                else -> mode = Mode.TEXT
            }
            Mode.CSI -> if (byte in 0x40..0x7E) { csi(byte.toChar(), params.toString()); mode = Mode.TEXT } else params.append(byte.toChar())
            Mode.OSC_COMMAND -> when {
                byte in '0'.code..'9'.code -> oscCommand = (if (oscCommand == -1) 0 else oscCommand * 10) + (byte - '0'.code)
                byte == ';'.code -> mode = Mode.OSC
                byte == 0x07 -> { oscDone(); mode = Mode.TEXT }
                byte == 0x1B -> mode = Mode.OSC_ESC
                byte < 0x20 || byte == 0x7F -> Unit
                // Neither a digit nor `;`: the library takes the character as the start of the string.
                else -> { osc.append(byte.toChar()); mode = Mode.OSC }
            }
            Mode.OSC -> when {
                byte == 0x07 -> { oscDone(); mode = Mode.TEXT }
                byte == 0x1B -> mode = Mode.OSC_ESC
                byte < 0x20 || byte == 0x7F -> Unit
                else -> osc.append(byte.toChar())
            }
            Mode.OSC_ESC -> when {
                byte == '\\'.code -> { oscDone(); mode = Mode.TEXT }
                byte == 0x1B || byte < 0x20 && byte != 0x07 || byte == 0x7F -> Unit
                // Cancelled, with the ESC still pending for the control character.
                byte == 0x07 -> mode = Mode.ESC
                else -> mode = Mode.TEXT
            }
        }
    }

    /**
     * `Terminal::termOscFallback`: the string without its number, with the cursor as it is when the OSC ends. Only the numbers the
     * tests write are passed on: 8 (a link, the one [oscEnds] is for) and 133 (a prompt).
     */
    private fun oscDone() {
        if (oscCommand != 8 && oscCommand != 133) return
        if (oscCommand == 8) oscEnds += consumed + 1
        emulator.onTextFragment(TerminalTextDecoder.OSC, oscCommand, osc.toString().toByteArray(Charsets.ISO_8859_1), true, true, row, col)
    }

    private fun csi(final: Char, raw: String) {
        val leader = raw.firstOrNull()?.takeIf { it == '?' || it == '>' || it == '!' }
        val numbers = raw.removePrefix(leader?.toString() ?: "").split(';').map { it.toIntOrNull() }
        fun arg(index: Int, default: Int) = numbers.getOrNull(index) ?: default
        fun count() = maxOf(1, arg(0, 1))
        val old = row to col
        when {
            // `INTERMED('!', 0x70)`: the soft reset (`vterm_state_reset(state, 0)`) puts the modes, saved cursor and scroll region back and leaves the cursor and the screens alone.
            raw == "!" && final == 'p' -> {
                regionTop = 0; regionBottom = rows; regionBottomUnset = true; origin = false
                cursorKeyMode = false; autoWrap = true; cursorBlink = true; cursorVisible = true
                leftRightMargin = false; mouseFlags = 0; reportFocus = false; bracketPaste = false
                savedRow = 0; savedCol = 0; savedCursorVisible = true; savedCursorBlink = true
                return
            }
            // `CSI 6 n` (DSR) and `CSI > c` (DA2): `state.c` answers through `vterm_push_output_sprintf_ctrl` as it reads them.
            raw == "6" && final == 'n' -> {
                emulator.onKeyboardInput("\u001b[${row - originOffset() + 1};${col + 1}R".toByteArray(Charsets.ISO_8859_1))
                return
            }
            raw == ">" && final == 'c' -> {
                emulator.onKeyboardInput("\u001b[>0;100;0c".toByteArray(Charsets.ISO_8859_1))
                return
            }
            // `INTERMED('$', 0x70)` with `?`: `request_dec_mode` of `state.c`. It knows the sixteen DEC modes below (including 1047,
            // `mode.alt_screen`, set by 1047 and 1049 alike) and answers 0 ("not recognised") to every other, 47 and 1049 included.
            leader == '?' && raw.endsWith("$") && final == 'p' -> {
                val mode = raw.removePrefix("?").removeSuffix("$").substringBefore(';').substringBefore(':').toIntOrNull() ?: return
                val status = when (mode) {
                    1 -> if (cursorKeyMode) 1 else 2
                    5 -> if (reverseScreen) 1 else 2
                    6 -> if (origin) 1 else 2
                    7 -> if (autoWrap) 1 else 2
                    12 -> if (cursorBlink) 1 else 2
                    25 -> if (cursorVisible) 1 else 2
                    69 -> if (leftRightMargin) 1 else 2
                    1000 -> if (mouseFlags == 1) 1 else 2
                    1002 -> if (mouseFlags == 3) 1 else 2
                    1003 -> if (mouseFlags == 5) 1 else 2
                    1004 -> if (reportFocus) 1 else 2
                    1005 -> if (mouseProtocol == 1) 1 else 2
                    1006 -> if (mouseProtocol == 2) 1 else 2
                    1015 -> if (mouseProtocol == 3) 1 else 2
                    1047 -> if (onAlternate) 1 else 2
                    2004 -> if (bracketPaste) 1 else 2
                    else -> 0
                }
                emulator.onKeyboardInput("\u001b[?$mode;$status\$y".toByteArray(Charsets.ISO_8859_1))
                return
            }
            leader == '?' && (final == 'h' || final == 'l') -> {
                // `set_dec_mode` of state.c for every argument in turn: 1047 is the alternate screen, 1048 the saved cursor and 1049 both.
                // 47, the one older programs use, is not a mode of this library, so it changes nothing.
                val on = final == 'h'
                for (mode in numbers) when (mode) {
                    1 -> cursorKeyMode = on
                    5 -> reverseScreen = on
                    6 -> setOrigin(on)
                    7 -> autoWrap = on
                    12 -> cursorBlink = on
                    25 -> cursorVisible = on
                    69 -> leftRightMargin = on
                    1000 -> mouseFlags = if (on) 1 else 0
                    1002 -> mouseFlags = if (on) 3 else 0
                    1003 -> mouseFlags = if (on) 5 else 0
                    1004 -> reportFocus = on
                    1005 -> mouseProtocol = if (on) 1 else 0
                    1006 -> mouseProtocol = if (on) 2 else 0
                    1015 -> mouseProtocol = if (on) 3 else 0
                    1047 -> setAlternate(on)
                    1048 -> saveCursor(on)
                    1049 -> { setAlternate(on); saveCursor(on) }
                    2004 -> bracketPaste = on
                }
                return
            }
            leader != null -> return
        }
        when (final) {
            'H', 'f' -> { row = (arg(0, 1) - 1 + originOffset()).coerceIn(0, rows - 1); col = (arg(1, 1) - 1).coerceIn(0, cols - 1); atPhantom = false }
            'A' -> { row = maxOf(0, row - count()); atPhantom = false }
            'B' -> { row = minOf(rows - 1, row + count()); atPhantom = false }
            'C' -> { col = minOf(cols - 1, col + count()); atPhantom = false }
            'D' -> { col = maxOf(0, col - count()); atPhantom = false }
            'G' -> { col = (arg(0, 1) - 1).coerceIn(0, cols - 1); atPhantom = false }
            'd' -> { row = (arg(0, 1) - 1 + originOffset()).coerceIn(0, rows - 1); atPhantom = false }
            'J' -> when (arg(0, 0)) {
                0 -> {
                    if (cols > col) erase(Rect(row, row + 1, col, cols))
                    if (rows > row + 1) erase(Rect(row + 1, rows, 0, cols))
                }
                1 -> {
                    if (row > 0) erase(Rect(0, row, 0, cols))
                    erase(Rect(row, row + 1, 0, col + 1))
                }
                2 -> erase(Rect(0, rows, 0, cols))
                3 -> emulator.clearScrollback()
            }
            'K' -> {
                val rect = when (arg(0, 0)) {
                    0 -> Rect(row, row + 1, col, cols)
                    1 -> Rect(row, row + 1, 0, col + 1)
                    else -> Rect(row, row + 1, 0, cols)
                }
                if (rect.endCol > rect.startCol) erase(rect)
            }
            'X' -> erase(Rect(row, row + 1, col, minOf(cols, col + count())))
            '@' -> if (inRegion()) scroll(Rect(row, row + 1, col, cols), 0, -count())
            'P' -> if (inRegion()) scroll(Rect(row, row + 1, col, cols), 0, count())
            'L' -> if (inRegion()) scroll(Rect(row, regionBottom, 0, cols), -count(), 0)
            'M' -> if (inRegion()) scroll(Rect(row, regionBottom, 0, cols), count(), 0)
            'S' -> scroll(Rect(regionTop, regionBottom, 0, cols), count(), 0)
            'T' -> scroll(Rect(regionTop, regionBottom, 0, cols), -count(), 0)
            'r' -> {
                // `DECSTBM` of state.c: a bottom that is missing is none (the region follows the screen's height), one that is the screen's height
                // is none too, but only when the top is the first row; any other is kept, cut down to the height the screen has now and no taller
                // when it grows. A region that has no height is dropped. Setting one puts the cursor home.
                var top = (arg(0, 1) - 1).coerceIn(0, rows)
                var bottom = if (numbers.size < 2) -1 else maxOf(-1, numbers[1] ?: -1)
                if (top == 0 && bottom == rows) bottom = -1 else if (bottom > rows) bottom = rows
                if ((if (bottom > -1) bottom else rows) <= top) { top = 0; bottom = -1 }
                regionTop = top
                regionBottomUnset = bottom == -1
                regionBottom = if (bottom == -1) rows else bottom
                row = originOffset(); col = 0; atPhantom = false
            }
        }
        // The end of `on_csi`: in the origin mode every command leaves the cursor inside the scroll region.
        if (origin) row = row.coerceIn(regionTop, regionBottom - 1)
        cursorChanged(old)
    }

    private fun originOffset() = if (origin) regionTop else 0

    /** `DECOM`: the cursor goes to the home of the new mode, which is the top of the region when the rows are counted from it. */
    private fun setOrigin(on: Boolean) {
        val old = row to col
        origin = on
        row = originOffset(); col = 0
        cursorChanged(old)
    }

    private fun inRegion() = row >= regionTop && row < regionBottom

    private fun cursorChanged(old: Pair<Int, Int>) {
        if (old != (row to col)) atPhantom = false
    }

    private fun moveTo(newRow: Int, newCol: Int) {
        val old = row to col
        row = newRow; col = newCol
        cursorChanged(old)
    }

    // ---------------------------------------------------------------- the state layer (state.c)

    private fun print(char: Char) {
        if (atPhantom) {
            linefeed()
            col = 0
            continuation[row] = true
            atPhantom = false
        }
        screen[row][col] = char
        damagerect(Rect(row, row + 1, col, col + 1))
        if (col + 1 >= cols) atPhantom = true else col++
    }

    private fun linefeed() {
        if (row == regionBottom - 1) scroll(Rect(regionTop, regionBottom, 0, cols), 1, 0)
        else if (row < rows - 1) row++
    }

    private fun reverseIndex() {
        if (row == regionTop) scroll(Rect(regionTop, regionBottom, 0, cols), -1, 0)
        else if (row > 0) row--
        atPhantom = false
    }

    private fun reset() {
        primary = blankScreen(); screen = primary; alternate = null; onAlternate = false
        primaryContinuation = BooleanArray(rows); alternateContinuation = BooleanArray(rows); continuation = primaryContinuation
        row = 0; col = 0; atPhantom = false; regionTop = 0; regionBottom = rows; regionBottomUnset = true; origin = false
        cursorKeyMode = false; reverseScreen = false; autoWrap = true; cursorBlink = true; cursorVisible = true
        leftRightMargin = false; mouseFlags = 0; reportFocus = false; mouseProtocol = 0; bracketPaste = false
        savedRow = 0; savedCol = 0; savedCursorVisible = true; savedCursorBlink = true
        damagerect(Rect(0, rows, 0, cols))
    }

    /**
     * `VTERM_PROP_ALTSCREEN`, every time a mode says so, whether or not the screen in use changes: the emulator hears of it, the alternate
     * screen is erased when it is entered (again, if it already was), and the shell's is damaged when it is left (even if it never was left).
     */
    private fun setAlternate(on: Boolean) {
        emulator.setTermProp(3, TerminalProperty.BoolValue(on))
        onAlternate = on
        if (on) {
            alternate = blankScreen(); screen = alternate!!; alternateContinuation = BooleanArray(rows); continuation = alternateContinuation
            erase(Rect(0, rows, 0, cols))
        } else {
            screen = primary; continuation = primaryContinuation
            damagerect(Rect(0, rows, 0, cols))
        }
    }

    /** `savecursor`: the position and cursor modes are saved, or put back as they were saved (home if never saved), unclamped; `updatecursor` leaves a pending wrap alone when the cursor did not move. */
    private fun saveCursor(save: Boolean) {
        if (save) {
            savedRow = row; savedCol = col; savedCursorVisible = cursorVisible; savedCursorBlink = cursorBlink
        } else {
            val old = row to col
            row = savedRow; col = savedCol; cursorVisible = savedCursorVisible; cursorBlink = savedCursorBlink
            cursorChanged(old)
        }
    }

    private fun erase(rect: Rect) {
        if (rect.endCol == cols) for (r in rect.startRow + 1 until minOf(rect.endRow + 1, rows)) continuation[r] = false
        eraseInternal(rect)
        damagerect(rect)
    }

    /** `scroll()` of state.c: the line infos move first, then the screen layer is told. */
    private fun scroll(rect: Rect, downwardIn: Int, rightwardIn: Int) {
        if (downwardIn == 0 && rightwardIn == 0) return
        val downward = downwardIn.coerceIn(-(rect.endRow - rect.startRow), rect.endRow - rect.startRow)
        val rightward = rightwardIn.coerceIn(-(rect.endCol - rect.startCol), rect.endCol - rect.startCol)
        if (rect.startCol == 0 && rect.endCol == cols && rightward == 0) {
            val height = rect.endRow - rect.startRow - kotlin.math.abs(downward)
            if (downward > 0) {
                for (i in 0 until height) continuation[rect.startRow + i] = continuation[rect.startRow + downward + i]
                for (r in rect.endRow - downward until rect.endRow) continuation[r] = false
            } else {
                for (i in height - 1 downTo 0) continuation[rect.startRow - downward + i] = continuation[rect.startRow + i]
                for (r in rect.startRow until rect.startRow - downward) continuation[r] = false
            }
        }
        scrollrect(rect, downward, rightward)
    }

    // ---------------------------------------------------------------- the screen layer (screen.c, VTERM_DAMAGE_SCROLL)

    private fun damagerect(rect: Rect) {
        val existing = damaged
        if (existing == null) damaged = rect.copy() else existing.expand(rect)
    }

    private fun scrollrect(rect: Rect, downward: Int, rightward: Int) {
        val hurt = damaged
        if (hurt != null && !rect.intersects(hurt)) flush()
        val current = pendingScroll
        if (current == null) {
            pendingScroll = rect.copy(); pendingDown = downward; pendingRight = rightward
        } else if (current.equalTo(rect) && ((pendingDown == 0 && downward == 0) || (pendingRight == 0 && rightward == 0))) {
            pendingDown += downward; pendingRight += rightward
        } else {
            flush()
            pendingScroll = rect.copy(); pendingDown = downward; pendingRight = rightward
        }

        scrollRect(rect, downward, rightward, ::moverectInternal, ::eraseInternal)

        val d = damaged ?: return
        if (rect.contains(d)) {
            d.startRow -= downward; d.endRow -= downward; d.startCol -= rightward; d.endCol -= rightward
            d.clip(rect)
        } else if (rect.startCol <= d.startCol && rect.endCol >= d.endCol && rightward == 0) {
            if (d.startRow >= rect.startRow && d.startRow < rect.endRow) {
                d.startRow = (d.startRow - downward).coerceIn(rect.startRow, rect.endRow)
            }
            if (d.endRow >= rect.startRow && d.endRow < rect.endRow) {
                d.endRow = (d.endRow - downward).coerceIn(rect.startRow, rect.endRow)
            }
        }
    }

    /** `vterm_scroll_rect`: [moverect] with (dest, src), then [eraser] over what the scroll vacated. */
    private fun scrollRect(rectIn: Rect, downward: Int, rightward: Int, moverect: (Rect, Rect) -> Unit, eraser: (Rect) -> Unit) {
        val rect = rectIn.copy()
        if (kotlin.math.abs(downward) >= rect.endRow - rect.startRow || kotlin.math.abs(rightward) >= rect.endCol - rect.startCol) {
            eraser(rect)
            return
        }
        val dest = Rect(0, 0, 0, 0)
        val src = Rect(0, 0, 0, 0)
        if (rightward >= 0) {
            dest.startCol = rect.startCol; dest.endCol = rect.endCol - rightward
            src.startCol = rect.startCol + rightward; src.endCol = rect.endCol
        } else {
            val leftward = -rightward
            dest.startCol = rect.startCol + leftward; dest.endCol = rect.endCol
            src.startCol = rect.startCol; src.endCol = rect.endCol - leftward
        }
        if (downward >= 0) {
            dest.startRow = rect.startRow; dest.endRow = rect.endRow - downward
            src.startRow = rect.startRow + downward; src.endRow = rect.endRow
        } else {
            val upward = -downward
            dest.startRow = rect.startRow + upward; dest.endRow = rect.endRow
            src.startRow = rect.startRow; src.endRow = rect.endRow - upward
        }
        moverect(dest, src)
        if (downward > 0) rect.startRow = rect.endRow - downward else if (downward < 0) rect.endRow = rect.startRow - downward
        if (rightward > 0) rect.startCol = rect.endCol - rightward else if (rightward < 0) rect.endCol = rect.startCol - rightward
        eraser(rect)
    }

    /** `moverect_internal`: a scroll of the whole width off the top of the primary screen pushes the rows that leave, then the cells move. */
    private fun moverectInternal(dest: Rect, src: Rect) {
        if (dest.startRow == 0 && dest.startCol == 0 && dest.endCol == cols && !onAlternate) {
            for (r in 0 until src.startRow) pushRow(r)
        }
        val width = src.endCol - src.startCol
        val downward = src.startRow - dest.startRow
        val rowsToMove = if (downward < 0) (dest.endRow - 1 downTo dest.startRow) else (dest.startRow until dest.endRow)
        for (r in rowsToMove) System.arraycopy(screen[r + downward], src.startCol, screen[r], dest.startCol, width)
    }

    /** `Terminal::termSbPushline`: the flag sent is that of the row *after* the one pushed, the second row, as the library reads it. */
    private fun pushRow(r: Int) {
        val buffer = CellData.buffer()
        for (c in 0 until cols) {
            val base = c * CellData.BYTES
            for (slot in 0 until CellData.STRIDE) buffer.putInt(base + slot * 4, 0)
            buffer.putInt(base, screen[r][c].code)
            buffer.putInt(base + CellData.WIDTH, 1)
            buffer.putInt(base + CellData.FOREGROUND, defaultForeground)
            buffer.putInt(base + CellData.BACKGROUND, defaultBackground)
        }
        emulator.pushScrollbackLine(cols, 0, cols, buffer, rows > 1 && continuation[1])
    }

    private fun eraseInternal(rect: Rect) {
        for (r in rect.startRow until minOf(rect.endRow, rows)) {
            for (c in rect.startCol until minOf(rect.endCol, cols)) screen[r][c] = BLANK
        }
    }

    /** `moverect_user`: the callback, and when it does not say it handled the move (the emulator's answers 0) the destination is damaged too. */
    private fun moverectUser(dest: Rect, src: Rect) {
        val handled = emulator.moverect(TermRect(dest.startRow, dest.endRow, dest.startCol, dest.endCol), TermRect(src.startRow, src.endRow, src.startCol, src.endCol))
        if (handled == 0) damagerect(dest.copy())
    }

    /** `vterm_screen_flush_damage`: the merged scroll as one moverect, then the damage as one rectangle. */
    private fun flush() {
        pendingScroll?.let { scroll ->
            scrollRect(scroll, pendingDown, pendingRight, ::moverectUser) { damagerect(it) }
            pendingScroll = null
        }
        damaged?.let { emulator.damage(it.startRow, it.endRow, it.startCol, it.endCol) }
        damaged = null
    }

    private companion object {
        /** libvterm's erased cell: no character. */
        const val BLANK = '\u0000'
    }
}
