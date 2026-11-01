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
 * DCH, IL / DL, SU / SD, DECSTBM, RI, RIS, the alternate screen (`?1049`) and OSC. Anything else in the input is ignored,
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

    private var row = 0
    private var col = 0
    private var atPhantom = false
    private var savedRow = 0
    private var savedCol = 0
    private var regionTop = 0
    private var regionBottom = rows

    // The screen layer.
    private var damaged: Rect? = null
    private var pendingScroll: Rect? = null
    private var pendingDown = 0
    private var pendingRight = 0

    /** Every `writeInput` the emulator made on the native, as (bytes). The pieces a write was cut into show here. */
    val writes = mutableListOf<String>()

    private fun blankScreen() = Array(rows) { CharArray(cols) { BLANK } }

    private lateinit var emulator: TerminalEmulatorImpl

    /** The emulator this is the native of: handed over when it first asks for its native, as the JNI is given its callbacks. */
    fun attach(callbacks: TerminalCallbacks): FakeVterm {
        emulator = callbacks as TerminalEmulatorImpl
        return this
    }

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
     * `vterm_set_size`, for the columns only: the cells stay where they are, the whole screen is reported as damaged and the JNI
     * flushes at once. A change of rows pushes rows into the history and pops them back, which the model does not do.
     */
    override fun resize(rows: Int, cols: Int): Int {
        check(rows == this.rows) { "the model resizes columns only" }
        fun refit(old: Array<CharArray>) = Array(rows) { r -> CharArray(cols) { c -> if (c < old[r].size) old[r][c] else BLANK } }
        primary = refit(primary)
        alternate = alternate?.let(::refit)
        screen = if (onAlternate) alternate!! else primary
        this.cols = cols
        col = minOf(col, cols - 1)
        damagerect(Rect(0, rows, 0, cols))
        flush()
        return 0
    }

    override fun dispatchKey(modifiers: Int, key: Int): Boolean = false
    override fun dispatchCharacter(modifiers: Int, character: Int): Boolean = false
    override fun setPaletteColors(colors: IntArray, count: Int): Int = count
    override fun setDefaultColors(foreground: Int, background: Int): Int = 0
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
            leader == '?' && (final == 'h' || final == 'l') -> {
                if (arg(0, 0) == 1049) alternateScreen(final == 'h')
                return
            }
            leader != null -> return
        }
        when (final) {
            'H', 'f' -> { row = (arg(0, 1) - 1).coerceIn(0, rows - 1); col = (arg(1, 1) - 1).coerceIn(0, cols - 1); atPhantom = false }
            'A' -> { row = maxOf(0, row - count()); atPhantom = false }
            'B' -> { row = minOf(rows - 1, row + count()); atPhantom = false }
            'C' -> { col = minOf(cols - 1, col + count()); atPhantom = false }
            'D' -> { col = maxOf(0, col - count()); atPhantom = false }
            'G' -> { col = (arg(0, 1) - 1).coerceIn(0, cols - 1); atPhantom = false }
            'd' -> { row = (arg(0, 1) - 1).coerceIn(0, rows - 1); atPhantom = false }
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
                var top = (arg(0, 1) - 1).coerceIn(0, rows)
                var bottom = arg(1, rows).coerceIn(0, rows)
                if (bottom <= top) { top = 0; bottom = rows }
                regionTop = top; regionBottom = bottom
                // Setting the region puts the cursor home.
                row = 0; col = 0; atPhantom = false
            }
        }
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
        row = 0; col = 0; atPhantom = false; regionTop = 0; regionBottom = rows
        damagerect(Rect(0, rows, 0, cols))
    }

    private fun alternateScreen(on: Boolean) {
        if (on == onAlternate) return
        if (on) { savedRow = row; savedCol = col }
        // screen.c setprop(): the emulator hears of it first, then the buffer is switched, and a screen that is left is damaged.
        emulator.setTermProp(3, TerminalProperty.BoolValue(on))
        onAlternate = on
        if (on) {
            alternate = blankScreen(); screen = alternate!!; alternateContinuation = BooleanArray(rows); continuation = alternateContinuation
            erase(Rect(0, rows, 0, cols))
        } else {
            screen = primary; continuation = primaryContinuation
            damagerect(Rect(0, rows, 0, cols))
            row = savedRow; col = savedCol; atPhantom = false
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
