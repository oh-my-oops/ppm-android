package org.connectbot.terminal

/**
 * Which extra cells [TerminalEmulatorImpl.processPendingUpdates] has to read so that a screen row's soft-wrap flag
 * is never older than the row below it.
 *
 * libvterm sets a row's flag when text is first written to the row under it (`lineinfo(row + 1).continuation`), and
 * the flag reaches Kotlin only with a read of a cell of *that* row. A line written in two chunks, the first ending at the
 * right margin, or a prompt that wraps while it is typed, damages only the row below, so the row above kept the flag it
 * was read with (not wrapped) until something happened to damage a cell in it. Everything built on the flag was wrong
 * meanwhile: a wrapped URL read as two, a selection broke its line where the screen did.
 *
 * [ranges] holds one `[start, end)` column range per row (`row * 2`, `row * 2 + 1`); a row nobody asked for has
 * `start >= end`. For every row that is read and has a row above it that is not, the row above is given the one cell
 * that carries its flag: the last, or the pair it belongs to when that is the second half of a wide character; a row
 * whose stored width differs from [cols] is read whole, as damaged rows are. Rows are taken top to bottom so that a row
 * asked for only because of the one below does not ask for the one above it as well: its own flag is not in question.
 */
internal fun refreshRowsAbove(ranges: IntArray, rows: Int, cols: Int, storedWidth: (row: Int) -> Int, cellWidth: (row: Int, col: Int) -> Int) {
    if (cols < 1) return
    for (row in 1 until rows) {
        val above = row - 1
        if (ranges[row * 2] >= ranges[row * 2 + 1] || ranges[above * 2] < ranges[above * 2 + 1]) continue
        ranges[above * 2] = when {
            storedWidth(above) != cols -> 0
            cols > 1 && cellWidth(above, cols - 1) == 0 -> cols - 2
            else -> cols - 1
        }
        ranges[above * 2 + 1] = cols
    }
}
