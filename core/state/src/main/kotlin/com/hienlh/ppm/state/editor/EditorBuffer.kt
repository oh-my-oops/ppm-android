package com.hienlh.ppm.state.editor

import kotlin.math.max
import kotlin.math.min

/** 0-based line and UTF-16 column inside [EditorBuffer]. */
data class LineCol(
    val line: Int,
    val column: Int,
)

/**Detected indentation style for a file. */
data class IndentStyle(
    val useTabs: Boolean = false,
    val size: Int = 4,
) {
    val unit: String get() = if (useTabs) "\t" else " ".repeat(size)
}

/** Detects the dominant line-ending sequence (`"\r\n"`, `"\r"`, or `"\n"`) in [text]. */
fun detectEol(text: String, fallback: String = "\n"): String {
    var crlf = 0
    var lf = 0
    var cr = 0
    var i = 0
    val len = text.length
    while (i < len) {
        val c = text[i]
        if (c == '\r') {
            if (i + 1 < len && text[i + 1] == '\n') {
                crlf++
                i += 2
                continue
            } else {
                cr++
            }
        } else if (c == '\n') {
            lf++
        }
        i++
    }
    if (crlf == 0 && lf == 0 && cr == 0) return fallback
    if (crlf >= lf && crlf >= cr && crlf > 0) return "\r\n"
    if (cr > lf && cr > crlf) return "\r"
    return "\n"
}

/**
 * Piece-table text buffer with a lazily maintained line-start index (`O(log N)` line/offset lookup
 * and bounded `subSequence` slices that never copy the entire document on keystrokes or IME IPC).
 */
class EditorBuffer(initialText: String = "") : CharSequence {

    private data class Piece(
        val isAdd: Boolean,
        val start: Int,
        val length: Int,
    )

    /** Dominant line-ending sequence detected on load (`"\n"`, `"\r\n"`, or `"\r"`), restored on save. */
    var eol: String = detectEol(initialText)
        internal set

    private val original: String = initialText.replace("\r\n", "\n").replace('\r', '\n')
    private val addBuffer = StringBuilder()
    private val pieces = ArrayList<Piece>(32).apply {
        if (original.isNotEmpty()) {
            add(Piece(isAdd = false, start = 0, length = original.length))
        }
    }

    private var totalLength: Int = original.length
    private var cachedLineStarts: IntArray? = null

    /** Monotonically increasing version bumped on every text mutation. */
    var version: Long = 0L
        private set

    override val length: Int
        get() = totalLength

    val lineCount: Int
        get() = ensureLineStarts().size

    private fun pieceCharSequence(piece: Piece): CharSequence =
        if (piece.isAdd) addBuffer else original

    override fun get(index: Int): Char {
        if (index < 0 || index >= totalLength) {
            throw IndexOutOfBoundsException("Index $index out of bounds for length $totalLength")
        }
        var offset = index
        for (i in 0 until pieces.size) {
            val p = pieces[i]
            if (offset < p.length) {
                return pieceCharSequence(p)[p.start + offset]
            }
            offset -= p.length
        }
        throw IndexOutOfBoundsException("Index $index not found")
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
        val s = startIndex.coerceIn(0, totalLength)
        val e = endIndex.coerceIn(s, totalLength)
        val needed = e - s
        if (needed == 0) return ""
        val out = StringBuilder(needed)
        var pos = 0
        for (i in 0 until pieces.size) {
            val p = pieces[i]
            val pEnd = pos + p.length
            if (pEnd > s && pos < e) {
                val localStart = max(0, s - pos)
                val localEnd = min(p.length, e - pos)
                out.append(pieceCharSequence(p), p.start + localStart, p.start + localEnd)
                if (out.length >= needed) break
            }
            pos = pEnd
        }
        return out.toString()
    }

    fun substring(startIndex: Int, endIndex: Int): String =
        subSequence(startIndex, endIndex).toString()

    /**
     * Replaces `[start, end)` with [replacement] (normalizing `\r\n` and `\r` to `\n`).
     */
    fun replace(start: Int, end: Int, replacement: String) {
        val s = start.coerceIn(0, totalLength)
        val e = end.coerceIn(s, totalLength)
        val norm = if ('\r' in replacement) {
            replacement.replace("\r\n", "\n").replace('\r', '\n')
        } else {
            replacement
        }
        if (s == e && norm.isEmpty()) return

        val nextPieces = ArrayList<Piece>(pieces.size + 2)
        var pos = 0
        var inserted = false

        for (i in 0 until pieces.size) {
            val p = pieces[i]
            val pStart = pos
            val pEnd = pos + p.length

            if (pEnd <= s) {
                nextPieces.add(p)
            } else if (pStart >= e) {
                if (!inserted) {
                    appendNewPiece(nextPieces, norm)
                    inserted = true
                }
                nextPieces.add(p)
            } else {
                // Piece overlaps [s, e)
                if (pStart < s) {
                    val leftLen = s - pStart
                    nextPieces.add(Piece(p.isAdd, p.start, leftLen))
                }
                if (!inserted) {
                    appendNewPiece(nextPieces, norm)
                    inserted = true
                }
                if (pEnd > e) {
                    val rightOffset = e - pStart
                    val rightLen = pEnd - e
                    nextPieces.add(Piece(p.isAdd, p.start + rightOffset, rightLen))
                }
            }
            pos = pEnd
        }

        if (!inserted) {
            appendNewPiece(nextPieces, norm)
        }

        pieces.clear()
        pieces.addAll(nextPieces)
        totalLength += norm.length - (e - s)
        version++
        cachedLineStarts = null
    }

    private fun appendNewPiece(target: MutableList<Piece>, text: String) {
        if (text.isEmpty()) return
        val addStart = addBuffer.length
        addBuffer.append(text)
        val last = target.lastOrNull()
        if (last != null && last.isAdd && last.start + last.length == addStart) {
            target[target.lastIndex] = last.copy(length = last.length + text.length)
        } else {
            target.add(Piece(isAdd = true, start = addStart, length = text.length))
        }
    }

    private fun ensureLineStarts(): IntArray {
        cachedLineStarts?.let { return it }
        var newlineCount = 0
        for (i in 0 until pieces.size) {
            val p = pieces[i]
            val src = pieceCharSequence(p)
            val end = p.start + p.length
            for (idx in p.start until end) {
                if (src[idx] == '\n') newlineCount++
            }
        }
        val starts = IntArray(newlineCount + 1)
        var lineIdx = 1
        var globalIdx = 0
        for (i in 0 until pieces.size) {
            val p = pieces[i]
            val src = pieceCharSequence(p)
            val end = p.start + p.length
            for (idx in p.start until end) {
                if (src[idx] == '\n') {
                    starts[lineIdx++] = globalIdx + 1
                }
                globalIdx++
            }
        }
        cachedLineStarts = starts
        return starts
    }

    fun lineStart(row: Int): Int {
        val starts = ensureLineStarts()
        val r = row.coerceIn(0, starts.lastIndex)
        return starts[r]
    }

    fun lineEnd(row: Int): Int {
        val starts = ensureLineStarts()
        val r = row.coerceIn(0, starts.lastIndex)
        return if (r + 1 < starts.size) starts[r + 1] - 1 else totalLength
    }

    fun line(row: Int): String = substring(lineStart(row), lineEnd(row))

    fun offsetToLineCol(offset: Int): LineCol {
        val starts = ensureLineStarts()
        val clamped = offset.coerceIn(0, totalLength)
        val bs = starts.binarySearch(clamped)
        val row = if (bs >= 0) bs else (-bs - 2).coerceAtLeast(0)
        return LineCol(line = row, column = clamped - starts[row])
    }

    fun lineColToOffset(line: Int, column: Int): Int {
        val starts = ensureLineStarts()
        val row = line.coerceIn(0, starts.lastIndex)
        val start = starts[row]
        val end = lineEnd(row)
        return (start + max(0, column)).coerceAtMost(end)
    }

    /** Detects whether the file predominantly indents with tabs or 2/4 spaces (scanning first 200 lines). */
    fun detectIndentation(): IndentStyle {
        val limit = min(200, lineCount)
        var tabLines = 0
        var twoSpaceLines = 0
        var fourSpaceLines = 0
        for (r in 0 until limit) {
            val text = line(r)
            if (text.isEmpty()) continue
            if (text.startsWith("\t")) {
                tabLines++
            } else if (text.startsWith("    ")) {
                fourSpaceLines++
            } else if (text.startsWith("  ") && !text.startsWith("   ")) {
                twoSpaceLines++
            }
        }
        if (tabLines > twoSpaceLines + fourSpaceLines) {
            return IndentStyle(useTabs = true, size = 4)
        }
        val size = if (twoSpaceLines > fourSpaceLines) 2 else 4
        return IndentStyle(useTabs = false, size = size)
    }

    override fun toString(): String = subSequence(0, totalLength).toString()

    /** Returns the document text with the file's original [eol] (`"\n"`, `"\r\n"`, or `"\r"`) restored. */
    fun toDiskString(): String {
        val lfText = toString()
        return if (eol == "\n") lfText else lfText.replace("\n", eol)
    }
}
