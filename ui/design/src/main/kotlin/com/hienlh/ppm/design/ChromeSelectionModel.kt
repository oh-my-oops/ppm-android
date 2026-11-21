package com.hienlh.ppm.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextRange
import java.text.BreakIterator

/*
 * What a selection over [ChromeText] is, apart from any layout: places in a document of blocks
 * (the paragraphs, list items, headings and code blocks of one answer, each one ChromeText, in
 * document order), the word a press lands on, the part of each block a selection covers, and the
 * plain text it copies. ChromeSelection.kt hit-tests and draws it.
 */

/** A caret place in a selection: [offset] in the text of block [block], blocks in document order. */
@Immutable
data class ChromeAnchor(val block: Int, val offset: Int) : Comparable<ChromeAnchor> {
    override fun compareTo(other: ChromeAnchor): Int =
        if (block != other.block) block.compareTo(other.block) else offset.compareTo(other.offset)
}

object ChromeSelectionModel {
    /** What [packed] holds when a block has nothing selected. */
    const val NONE = -1L

    /** [start] to [end] of one block, as [ChromeText] keeps it to draw. */
    fun pack(start: Int, end: Int): Long = (start.toLong() shl 32) or (end.toLong() and 0xFFFFFFFFL)

    fun packedStart(packed: Long): Int = (packed shr 32).toInt()

    fun packedEnd(packed: Long): Int = packed.toInt()

    /**
     * The word [offset] of [text] is in or just after, as a long press or a double click selects it:
     * letters and digits as the platform's word iterator groups them. Off a word, the one character
     * there; an empty text gives an empty range.
     */
    fun wordAt(text: CharSequence, offset: Int): TextRange {
        val s = text.toString()
        if (s.isEmpty()) return TextRange(0)
        val at = offset.coerceIn(0, s.length)
        val probe = when {
            at < s.length && isWordChar(s, at) -> at
            at > 0 && isWordChar(s, at - 1) -> at - 1
            else -> -1
        }
        if (probe < 0) {
            var i = if (at < s.length) at else at - 1
            if (i > 0 && Character.isLowSurrogate(s[i])) i--
            return TextRange(i, i + Character.charCount(s.codePointAt(i)))
        }
        val words = BreakIterator.getWordInstance()
        words.setText(s)
        val start = if (words.isBoundary(probe)) probe else words.preceding(probe)
        val end = words.following(probe)
        return TextRange(start.coerceAtLeast(0), if (end == BreakIterator.DONE) s.length else end)
    }

    private fun isWordChar(s: String, i: Int): Boolean {
        val c = s.codePointAt(if (Character.isLowSurrogate(s[i]) && i > 0) i - 1 else i)
        return Character.isLetterOrDigit(c) || c == '_'.code
    }

    /** The part of block [block] (of [length] characters) that [start] to [end] covers, packed; [NONE] when it covers nothing. */
    fun rangeIn(block: Int, length: Int, start: ChromeAnchor, end: ChromeAnchor): Long {
        if (block < start.block || block > end.block) return NONE
        val from = if (block == start.block) start.offset.coerceIn(0, length) else 0
        val to = if (block == end.block) end.offset.coerceIn(0, length) else length
        return if (to > from) pack(from, to) else NONE
    }

    /**
     * The text [start] to [end] copies, as a browser copies a selection over blocks: each block's
     * part in document order with a newline between blocks, leaving out what [skipped] names in a
     * block (an inline box's placeholder: a checkbox, a picture).
     */
    fun plainText(blocks: List<CharSequence>, start: ChromeAnchor, end: ChromeAnchor, skipped: (Int) -> List<TextRange> = { emptyList() }): String = buildString {
        if (blocks.isEmpty() || end <= start) return@buildString
        val last = end.block.coerceAtMost(blocks.lastIndex)
        for (block in start.block.coerceAtLeast(0)..last) {
            val text = blocks[block]
            val from = if (block == start.block) start.offset.coerceIn(0, text.length) else 0
            val to = if (block == end.block) end.offset.coerceIn(from, text.length) else text.length
            if (block > start.block) append('\n')
            val skip = skipped(block)
            var i = from
            while (i < to) {
                val over = skip.firstOrNull { i >= it.min && i < it.max }
                if (over != null) {
                    i = over.max
                } else {
                    append(text[i])
                    i++
                }
            }
        }
    }

    /**
     * The block a point [x], [y] goes to: of [count] boxes laid out as left, top, right, bottom in
     * [boxes], the nearest by its vertical distance first and its horizontal second, so a point in
     * the gap between two paragraphs, or beside a line, goes to the block on its level. -1 for none.
     */
    fun blockAt(boxes: FloatArray, count: Int, x: Float, y: Float): Int {
        var best = -1
        var bestDy = Float.MAX_VALUE
        var bestDx = Float.MAX_VALUE
        for (i in 0 until count) {
            val left = boxes[i * 4]
            val top = boxes[i * 4 + 1]
            val right = boxes[i * 4 + 2]
            val bottom = boxes[i * 4 + 3]
            val dy = if (y < top) top - y else if (y > bottom) y - bottom else 0f
            val dx = if (x < left) left - x else if (x > right) x - right else 0f
            if (dy < bestDy || (dy == bestDy && dx < bestDx)) {
                best = i
                bestDy = dy
                bestDx = dx
            }
        }
        return best
    }

    /**
     * The line of a [ChromeText] a point [y] px down its box is on: the line whose band, from the
     * pixel [edges] says it starts on down to where the next starts, holds it. Lines are drawn
     * where Chrome puts them, not where the paragraph Compose laid out has them, so the band and
     * not the paragraph's own line box decides.
     */
    fun lineAt(edges: IntArray, y: Float): Int {
        var line = 0
        while (line + 1 < edges.size && edges[line + 1] <= y) line++
        return line
    }
}
