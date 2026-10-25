package com.hienlh.ppm.state

/** `revertBlocks` (server side on the web): [current] with [blocks] put back to the base's lines. */
internal fun revertBlocks(base: String, current: String, blocks: List<ReviewBlocks.Block>): String {
    val old = ReviewBlocks.splitLines(base)
    val now = ReviewBlocks.splitLines(current).toMutableList()
    for (b in blocks.sortedByDescending { it.newFrom }) {
        repeat(b.newTo - b.newFrom) { now.removeAt(b.newFrom) }
        now.addAll(b.newFrom, old.subList(b.oldFrom, b.oldTo))
    }
    return now.joinToString("")
}
