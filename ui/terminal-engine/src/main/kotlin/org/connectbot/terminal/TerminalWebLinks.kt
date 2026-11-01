package org.connectbot.terminal

/**
 * A link as xterm's `ILink` has it. The range is an `IBufferRange`: 1-based, columns and rows both, the end included, and
 * rows are rows of the whole buffer (history first). [osc8] links come from an escape sequence and ask before they open.
 */
internal data class TerminalLink(
    val url: String,
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val osc8: Boolean = false,
) {
    /** `Linkifier._linkAtPosition`; [x] and [y] are 1-based, as xterm's `IBufferCellPosition`. */
    fun covers(cols: Int, x: Int, y: Int): Boolean {
        val current = y * cols + x
        return startY * cols + startX <= current && current <= endY * cols + endX
    }
}

/**
 * The links xterm shows in the web's terminal, over [lineAt] (rows of the whole buffer, history first; null past either end) at
 * [cols] columns: `@xterm/addon-web-links` for plain `http(s)://` text, `OscLinkProvider` for OSC 8, and the Linkifier's rule for
 * which of them is under a cell. Their answers come out of the same buffer, in the same coordinates, so a test can hold them
 * against the real thing (`gen-terminal-link-cases.ts`).
 *
 * Differences from xterm, both in what the engine's cells cannot say:
 * - A cell nothing was written to and a cell holding a space read the same. xterm ends a row's text at its last written cell and so
 *   joins a URL across a margin that a wide character skipped; here a row is taken to have skipped its last cell when that is blank,
 *   the row continues onto one that starts with a wide character, and nothing else in the row says otherwise.
 * - The engine records an OSC 8 link only when it starts and ends in one row, so a wrapped one is not a link.
 */
internal class TerminalWebLinks(private val cols: Int, private val lineAt: (Int) -> TerminalLine?) {
    /** The link under the cell [col] of buffer row [row] (both from 0), if any. */
    fun linkAt(row: Int, col: Int): TerminalLink? {
        val y = row + 1
        val occupied = HashSet<Int>()
        // _removeIntersectingLinks: a link of a later provider that touches a cell of an earlier one's is dropped, whole.
        fun keeps(link: TerminalLink): Boolean {
            val first = if (link.startY < y) 0 else link.startX
            val last = if (link.endY > y) cols else link.endX
            for (x in first..last) if (!occupied.add(x)) return false
            return true
        }
        val links = oscLinks(y).filter(::keeps) + webLinks(y).filter(::keeps)
        return links.firstOrNull { it.covers(cols, col + 1, y) }
    }

    /** `OscLinkProvider.provideLinks(y)`; [y] is 1-based. */
    fun oscLinks(y: Int): List<TerminalLink> {
        val line = lineAt(y - 1) ?: return emptyList()
        return line.semanticSegments
            .filter { it.semanticType == SemanticType.HYPERLINK && it.metadata != null && it.startCol < it.endCol }
            .sortedBy { it.startCol }
            .mapNotNull { segment ->
                val uri = segment.metadata!!
                if (WebUrl.isHttpUrl(uri)) TerminalLink(uri, segment.startCol + 1, y, minOf(segment.endCol, line.cells.size), y, osc8 = true) else null
            }
    }

    /** `LinkComputer.computeLink(y, strictUrlRegex, ...)`; [y] is 1-based. */
    fun webLinks(y: Int): List<TerminalLink> {
        val (lines, startLineIndex) = windowedLineStrings(y - 1)
        val joined = lines.joinToString("")
        val result = mutableListOf<TerminalLink>()
        for (match in URL_REGEX.findAll(joined)) {
            val text = match.value
            if (!WebUrl.isUrl(text)) continue
            val (startY, startX) = mapStringIndex(startLineIndex, 0, match.range.first)
            val (endY, endX) = mapStringIndex(startY, startX, text.length)
            if (startY == -1 || startX == -1 || endY == -1 || endX == -1) continue
            result += TerminalLink(text, startX + 1, startY + 1, endX, endY + 1)
        }
        return result
    }

    /** xterm's `isWrapped` for [index]: the row continues the one above it. */
    private fun continuesAbove(index: Int): Boolean = index > 0 && lineAt(index - 1)?.softWrapped == true

    /**
     * `_getWindowedLineStrings`: the row's text with the rows of its wrapped line around it, stopping at a row that has a space or
     * once 2048 characters were added, and the index of the first row of the window. The loops are xterm's own, including the
     * index that ends one row short at the top of the buffer and past the row actually added after 2048 characters.
     */
    private fun windowedLineStrings(lineIndex: Int): Pair<List<String>, Int> {
        var topIdx = lineIndex
        var bottomIdx = lineIndex
        val lines = ArrayList<String>()
        if (lineAt(lineIndex) == null) return lines to topIdx
        val currentContent = rowText(lineIndex)
        if (continuesAbove(lineIndex) && currentContent.firstOrNull() != ' ') {
            var length = 0
            while (true) {
                topIdx--
                if (lineAt(topIdx) == null || length >= 2048) break
                val content = rowText(topIdx)
                length += content.length
                lines.add(content)
                if (!continuesAbove(topIdx) || content.indexOf(' ') != -1) break
            }
            lines.reverse()
        }
        lines.add(currentContent)
        var length = 0
        while (true) {
            bottomIdx++
            if (lineAt(bottomIdx) == null || !continuesAbove(bottomIdx) || length >= 2048) break
            val content = rowText(bottomIdx)
            length += content.length
            lines.add(content)
            if (content.indexOf(' ') != -1) break
        }
        return lines to topIdx
    }

    /**
     * `translateToString(true)`: the text of the cells of a row that are not wide characters' second halves. The text of a row that
     * goes on in the next one is all of it, since a row that wraps is full; the text of the last row of a line stops at its last
     * written cell. xterm cuts the empty cell a wide character skipped off the end of the first kind as well, see [skippedCell].
     */
    private fun rowText(index: Int): String {
        val line = lineAt(index)!!
        val cells = line.cells
        val end = when {
            line.softWrapped && lineAt(index + 1) != null -> if (skippedCell(index)) cells.size - 1 else cells.size
            else -> trimmedLength(cells)
        }
        val text = StringBuilder()
        var col = 0
        while (col < end) {
            val width = cells.width(col)
            if (width != 0) text.append(cells.text(col, col + 1))
            col += maxOf(width, 1)
        }
        return text.toString()
    }

    private fun trimmedLength(cells: PackedCells): Int {
        for (col in cells.size - 1 downTo 0) if (cells.width(col) != 0 && !cells.blank(col)) return col + cells.width(col)
        return 0
    }

    /** Whether the last cell of the row was left empty because the next row starts with a wide character that did not fit. */
    private fun skippedCell(index: Int): Boolean {
        val line = lineAt(index) ?: return false
        val cells = line.cells
        val last = cells.size - 1
        if (last < 1 || !line.softWrapped || cells.width(last) != 1 || !cells.blank(last)) return false
        val next = lineAt(index + 1) ?: return false
        return next.cells.size > 0 && next.cells.width(0) == 2
    }

    /**
     * `_mapStrIdx`: the buffer position of a character of the window's text, as (row, column) from 0, starting from the cell
     * ([lineIndex], [rowIndex]). (-1, -1) when the text runs out of rows.
     */
    private fun mapStringIndex(lineIndex: Int, rowIndex: Int, stringIndex: Int): Pair<Int, Int> {
        var row = lineIndex
        var start = rowIndex
        var remaining = stringIndex
        while (remaining != 0) {
            val line = lineAt(row) ?: return -1 to -1
            val cells = line.cells
            for (i in start until cells.size) {
                if (cells.width(i) != 0) {
                    remaining -= maxOf(cells.text(i, i + 1).length, 1)
                    // The cell a wide character skipped is in no row's text but was counted just now.
                    if (i == cells.size - 1 && skippedCell(row)) remaining += 1
                }
                if (remaining < 0) return row to i
            }
            row++
            start = 0
        }
        return row to start
    }

    private companion object {
        /** What `\s` is in JavaScript: Java's is ASCII only. */
        const val JS_SPACE = "\t\n\u000B\u000C\r \u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff"

        /** `strictUrlRegex` of `@xterm/addon-web-links`. */
        val URL_REGEX = Regex("(https?|HTTPS?):[/]{2}[^$JS_SPACE\"'!*(){}|\\\\\\^<>`]*[^$JS_SPACE\"':,.!?{}|\\\\\\^~\\[\\]`()<>]")
    }
}

/** The link at a cell of the viewport: [row] is a row of what is shown, [col] a column of it. */
internal fun TerminalScreenState.linkAt(row: Int, col: Int): TerminalLink? {
    if (row !in 0 until snapshot.rows || col !in 0 until snapshot.cols) return null
    val total = totalLines
    return TerminalWebLinks(snapshot.cols) { index -> if (index in 0 until total) getLine(index) else null }.linkAt(visibleLineIndex(row), col)
}
