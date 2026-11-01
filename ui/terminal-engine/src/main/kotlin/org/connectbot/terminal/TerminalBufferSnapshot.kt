package org.connectbot.terminal

/** [isWrapped] means this physical row continues on the next row (libvterm soft-wrap flag). */
data class TerminalBufferLine(val text: String, val isWrapped: Boolean)

/** One coherent read. All row indices refer to [lines], including retained history. */
data class TerminalBufferSnapshot(
    val lines: List<TerminalBufferLine>,
    val cursorRow: Int,
    val cursorColumn: Int,
    val screenStartRow: Int,
    val alternateScreen: Boolean,
) {
    /** Start inclusive, end exclusive; indices are bounded to this snapshot. */
    fun getTextBetweenRows(start: Int, endExclusive: Int): String {
        require(start >= 0 && endExclusive >= start)
        return buildString {
            val end = endExclusive.coerceAtMost(lines.size)
            for (row in start.coerceAtMost(end) until end) {
                val line = lines[row]
                append(if (line.isWrapped) line.text else line.text.trimEnd())
                if (row + 1 < end && !line.isWrapped) append('\n')
            }
        }.trimEnd('\n')
    }
}
