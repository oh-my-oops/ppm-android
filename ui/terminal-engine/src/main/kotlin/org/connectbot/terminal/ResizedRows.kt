package org.connectbot.terminal

/**
 * How libvterm laid the rows of the screen out on the new one when it resized it, with reflow off (the shipped JNI turns it off, and
 * `FakeVterm` is checked against the library on exactly this): every old row stays one row, placed from the bottom up. Blank rows at
 * the bottom are let go as far as that makes room; the rows that still do not fit at the top, the first [pushed] of them, go into the
 * history, and a taller screen takes [popped] rows back from it and puts them above the old ones, the newest first, next to them.
 * The library does one or the other, never both, so the rest is one offset: new row `r` is old row `r + pushed - popped`, and a new row
 * that is no old row is a popped one or blank.
 *
 * The library also clears the continuation flag of every row, so what the emulator knew of the screen's soft wraps is carried here:
 * a wrapped line must read as one line again when the keyboard that squeezed the screen goes away.
 */
internal class ResizedRows(val oldRows: Int, val newRows: Int, val pushed: Int, val popped: Int) {
    /** The new row the old row [old] is: negative when it went to the history, [newRows] or more when the screen let it go. */
    fun newRowOf(old: Int): Int = old - pushed + popped

    /**
     * The lines of the new screen. [before] is the old screen's, [restored] what the library took back from the history in the order it
     * took it, and a line is the one it was with only its row changed. A line keeps its soft wrap only when [keepFlags]: a resize in
     * columns cuts or pads the rows, which then no longer break where they did. A row never wraps onto the one below the screen,
     * whatever a line that was dropped under it said. Every flag that is carried is marked [TerminalLine.wrapKept], the library
     * having none of them any more.
     */
    fun remap(before: List<TerminalLine>, restored: List<TerminalLine>, keepFlags: Boolean): List<TerminalLine> = List(newRows) { row ->
        val old = row + pushed - popped
        val line = before.getOrNull(old) ?: if (row < popped) restored.getOrNull(popped - 1 - row) else null
        if (line == null) {
            TerminalLine.empty(row, 0)
        } else {
            val wrapped = keepFlags && line.softWrapped && row < newRows - 1
            line.copy(row = row, softWrapped = wrapped, wrapKept = wrapped)
        }
    }
}
