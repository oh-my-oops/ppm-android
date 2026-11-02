/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 *
 * Derived from termlib 0.3.13's PackedCells.kt: the same methods over a smaller layout. The upstream file is not generated (see REPLACED in
 * android/scripts/patch-terminal-engine.py), and src/test/.../LegacyPackedCells.kt keeps its storage to prove that this one answers alike.
 */
package org.connectbot.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * One row of cells, immutable and addressed by column.
 *
 * Upstream keeps four arrays as long as the row is wide whatever it holds: 2,198 B a row at 105 columns, which is 104 MB for the 50,000
 * rows of one terminal's history and the same for a row of `seq` output as for a full one. This keeps what the row has in it:
 *  - the text of the cells up to the row's trailing run of one plain character (width 1, one UTF-16 unit, in whatever style: the blank tail of
 *    a line, a rule of dashes), which is kept once, as [tail];
 *  - the style (colours and flags) as runs, [RUN_INTS] ints each: where it starts, then the foreground, the background and the flags;
 *  - where each cell's text starts and its width, only once a cell is something other than one character of width 1 (a wide or combining
 *    character, an emoji, the continuation column of a wide one). Until then a cell's text starts at its own column, and [offsets] is null.
 *
 * The form is canonical: the same cells always give the same arrays, which is what [contentEquals] relies on. Every method answers as the
 * upstream one did, for the row as if each cell had its own text, style and width; `PackedCellsTest` checks that against upstream's class.
 */
internal class PackedCells private constructor(
    override val size: Int,
    /** The columns whose text [text] holds. From here to [size] the cells are the trailing run, each [tail]; [size] when there is none. */
    private val explicit: Int,
    private val text: CharArray,
    /** Per column up to [explicit]: the cell's width above [WIDTH_SHIFT], where its text starts below. Null while every cell is one plain character. */
    private val offsets: IntArray?,
    private val styles: IntArray,
    private val tail: Char,
    private val placeholderIds: Map<Int, Int>,
) : AbstractList<TerminalLine.Cell>() {
    private fun outOfRange(col: Int): Nothing = throw IndexOutOfBoundsException("Column $col outside 0 until $size")

    /**
     * Where the text of [col] starts in the row's whole text: [text], then one [tail] for each cell of the trailing run. That is upstream's
     * `offsets[col]`, for `0..size`, and everything below that reads text goes through it.
     */
    private fun offsetAt(col: Int): Int = when {
        col >= explicit -> text.size + (col - explicit)
        offsets == null -> col
        else -> offsets[col] and OFFSET_MASK
    }

    private fun charAtOffset(index: Int): Char = if (index < text.size) text[index] else tail

    /** `text.getOrElse(index) { NUL }` of the whole text. */
    private fun charOrNul(index: Int): Char = when {
        index < text.size -> text[index]
        index < text.size + size - explicit -> tail
        else -> '\u0000'
    }

    /** The code point at [index] of the whole text, where only what is before [limit] belongs to the cell. */
    private fun codePointAtOffset(index: Int, limit: Int): Int = if (index >= text.size) tail.code else Character.codePointAt(text, index, minOf(limit, text.size))

    /** The whole text between [from] and [to]. */
    private fun slice(from: Int, to: Int): String = when {
        to <= text.size -> String(text, from, to - from)
        from >= text.size -> String(CharArray(to - from) { tail })
        else -> String(text, from, text.size - from) + String(CharArray(to - text.size) { tail })
    }

    /** The whole text, as the shaper wants it: a copy only when there is a trailing run to write out. */
    private fun wholeText(): CharArray = if (explicit == size) {
        text
    } else {
        text.copyOf(text.size + size - explicit).also { it.fill(tail, text.size, it.size) }
    }

    /** Where in [styles] the run holding [col] starts. */
    private fun run(col: Int): Int {
        var low = 0
        var high = styles.size / RUN_INTS - 1
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (styles[middle * RUN_INTS] <= col) low = middle else high = middle - 1
        }
        return low * RUN_INTS
    }

    private fun foregroundOf(col: Int): Int = styles[run(col) + 1]
    private fun backgroundOf(col: Int): Int = styles[run(col) + 2]

    fun needsShaping(): Boolean {
        if (measuredSizeAndScript.isNaN()) {
            var complex = isComplex(tail.code) && explicit < size
            var i = 0
            while (!complex && i < text.size) {
                val cp = Character.codePointAt(text, i, text.size)
                if (isComplex(cp)) complex = true
                i += Character.charCount(cp)
            }
            measuredSizeAndScript = if (complex) Float.NEGATIVE_INFINITY else Float.POSITIVE_INFINITY
        }
        return measuredSizeAndScript.toRawBits() < 0
    }

    fun sameShaping(other: PackedCells): Boolean {
        if (size != other.size) return false
        for (col in 0..size) if (offsetAt(col) != other.offsetAt(col)) return false
        for (col in 0 until size) {
            val start = offsetAt(col)
            val end = offsetAt(col + 1)
            // Single-cell ASCII is always a shaping boundary and is drawn by
            // the legacy renderer. Updating a counter beside Arabic must not
            // allocate an entirely new set of native glyph/font objects.
            if (end == start + 1 && charAtOffset(start) < '\u0080' && other.charAtOffset(start) < '\u0080') continue
            for (index in start until end) if (charAtOffset(index) != other.charAtOffset(index)) return false
        }
        for (col in 0 until size) if (width(col) != other.width(col) || (flags(col) xor other.flags(col)) and 9 != 0) return false
        return true
    }

    // Account for the packed row retained by a cache entry, including array headers.
    val shapingRetentionBytes: Int get() = 128 + text.size * 2 + (offsets?.size ?: 0) * 4 + styles.size * 4

    @androidx.annotation.RequiresApi(31)
    fun shape(
        shaper: TerminalShaping,
        logicalToVisual: IntArray? = null,
        visualToLogical: IntArray? = null,
        levels: ByteArray? = null,
        mirrors: IntArray? = null,
        contextBefore: String = "",
        contextAfter: String = "",
    ): ShapedLine {
        val whole = wholeText()
        val wholeOffsets = IntArray(size + 1) { offsetAt(it) }
        if (contextBefore.isEmpty() && contextAfter.isEmpty()) {
            return shaper.shape(whole, wholeOffsets, this, logicalToVisual, visualToLogical, levels, mirrors)
        }
        val before = contextBefore.toCharArray()
        val after = contextAfter.toCharArray()
        val contextual = CharArray(before.size + whole.size + after.size)
        before.copyInto(contextual)
        whole.copyInto(contextual, before.size)
        after.copyInto(contextual, before.size + whole.size)
        val shiftedOffsets = IntArray(wholeOffsets.size) { wholeOffsets[it] + before.size }
        return shaper.shape(contextual, shiftedOffsets, this, logicalToVisual, visualToLogical, levels, mirrors)
    }

    fun width(col: Int): Int {
        if (col < 0 || col >= size) outOfRange(col)
        val offsets = offsets
        return if (offsets == null || col >= explicit) 1 else offsets[col] ushr WIDTH_SHIFT
    }

    fun placeholder(col: Int): Boolean = offsets != null && offsetAt(col + 1) - offsetAt(col) >= 2 && codePointAtOffset(offsetAt(col), offsetAt(col + 1)) == 0x10EEEE
    fun placeholderPlacement(col: Int): Long = (placeholderIds[col] ?: 0).toLong() and 0xFFFFFF
    fun placeholderImage(col: Int): Long = foregroundOf(col).toLong() and 0xFFFFFF
    fun placeholderMarks(col: Int): List<Int> {
        var index = offsetAt(col) + 2
        val end = offsetAt(col + 1)
        return buildList {
            while (index < end) {
                val cp = codePointAtOffset(index, end)
                add(cp)
                index += Character.charCount(cp)
            }
        }
    }
    fun flags(col: Int): Int {
        if (col < 0 || col >= size) outOfRange(col)
        return styles[run(col) + 3]
    }
    fun foreground(col: Int): Color {
        if (col < 0 || col >= size) outOfRange(col)
        return Color(foregroundOf(col))
    }
    fun background(col: Int): Color {
        if (col < 0 || col >= size) outOfRange(col)
        return Color(backgroundOf(col))
    }
    fun charAt(col: Int): Char = if (width(col) == 0 && col > 0) charAt(col - 1) else charOrNul(offsetAt(col))
    fun blank(col: Int): Boolean = width(col) != 0 && offsetAt(col + 1) - offsetAt(col) <= 1 && charAt(col).let { it == ' ' || it == '\u0000' }
    fun text(start: Int = 0, end: Int = size): String {
        require(start in 0..end && end <= size)
        if (start == end) return ""
        val first = if (start < size && width(start) == 0 && start > 0) start - 1 else start
        if (offsets == null || (first until end).none(::placeholder)) return slice(offsetAt(first), offsetAt(end))
        return buildString {
            for (col in first until end) {
                if (placeholder(col)) append(' ') else append(slice(offsetAt(col), offsetAt(col + 1)))
            }
        }
    }

    fun columnText(): String {
        val columns = CharArray(size)
        if (offsets == null) {
            text.copyInto(columns, 0, 0, explicit)
        } else {
            for (col in 0 until explicit) columns[col] = if (placeholder(col)) ' ' else charAt(col)
        }
        columns.fill(tail, explicit, size)
        return String(columns)
    }

    fun boxCharacter(col: Int): Char? = if (col in indices && offsetAt(col + 1) - offsetAt(col) == 1) {
        charAtOffset(offsetAt(col)).takeIf { it in '\u2500'..'\u259F' }
    } else {
        null
    }

    // One lazily allocated measurement array per retained row, replaced on font changes.
    private var measuredTypeface: android.graphics.Typeface? = null

    // One word holds both the measurement size (magnitude) and script flag (sign).
    // NaN means unclassified; +/-infinity means classified but not measured yet.
    // Avoid an extra field on every parsed/scrollback row, including ASCII-only rows.
    private var measuredSizeAndScript = Float.NaN

    // One advance per column up to [explicit], and the trailing run's in the one after: its cells are all alike.
    private var advances: FloatArray? = null

    fun draw(canvas: android.graphics.Canvas, col: Int, x: Float, baseline: Float, paint: android.graphics.Paint, cellWidth: Float) {
        if (measuredTypeface !== paint.typeface || abs(measuredSizeAndScript) != paint.textSize || advances == null) {
            measuredTypeface = paint.typeface
            measuredSizeAndScript = if (needsShaping()) -paint.textSize else paint.textSize
            advances = FloatArray(explicit + 1) { Float.NaN }
        }
        val measurements = advances!!
        val inTail = col >= explicit
        val chars = if (inTail) tailChars() else text
        val index = if (inTail) 0 else offsetAt(col)
        val count = offsetAt(col + 1) - offsetAt(col)
        val slot = if (inTail) explicit else col
        var advance = measurements[slot]
        if (advance.isNaN()) {
            val skew = paint.textSkewX
            paint.textSkewX = 0f
            advance = if (paint is TerminalTextPaint) {
                paint.measureCell(chars, index, count)
            } else {
                paint.measureText(chars, index, count)
            }
            paint.textSkewX = skew
            measurements[slot] = advance
        }
        if (advance <= cellWidth || cellWidth <= 0f) {
            canvas.drawText(chars, index, count, x, baseline, paint)
            return
        }
        val saved = canvas.save()
        try {
            canvas.translate(x, baseline)
            if (advance > cellWidth && cellWidth > 0f) canvas.scale(cellWidth / advance, 1f)
            canvas.drawText(chars, index, count, 0f, 0f, paint)
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    private fun tailChars(): CharArray = if (tail.code < ASCII.size) ASCII[tail.code] else charArrayOf(tail)

    // Compatibility for test fixtures and cold callers; rendering and snapshot construction
    // use primitive accessors, never this allocating List view.
    override fun get(index: Int): TerminalLine.Cell {
        if (index !in indices) throw IndexOutOfBoundsException("Column $index outside 0 until $size")
        val value = text(index, index + 1)
        val flags = flags(index)
        return TerminalLine.Cell(
            char = value.firstOrNull() ?: '\u0000', combiningChars = value.drop(1).toList(),
            fgColor = foreground(index), bgColor = background(index),
            bold = flags and 1 != 0, underline = (flags ushr 1) and 3,
            italic = flags and 8 != 0, blink = flags and 16 != 0,
            reverse = flags and 32 != 0, strike = flags and 128 != 0, width = width(index),
        )
    }

    fun contentEquals(other: PackedCells): Boolean = this === other ||
        (
            size == other.size && explicit == other.explicit && (explicit == size || tail == other.tail) &&
                text.contentEquals(other.text) && styles.contentEquals(other.styles) && offsets.contentEquals(other.offsets)
            )

    override fun equals(other: Any?): Boolean = if (other is PackedCells) contentEquals(other) else super.equals(other)
    override fun hashCode(): Int = super.hashCode()

    fun matches(buffer: ByteBuffer, offset: Int, start: Int, count: Int): Boolean {
        if (start < 0 || count > size - start) return false
        for (i in 0 until count) {
            val col = start + i
            val base = offset + i * CellData.BYTES
            if (width(col) != buffer.getInt(base + CellData.WIDTH) || flags(col) != buffer.getInt(base + CellData.FLAGS)) return false
            // Continuation cells carry no independently rendered content or attributes.
            if (width(col) == 0) continue
            if (placeholder(col) && placeholderIds[col] != buffer.getInt(base + 14 * 4)) return false
            if (foregroundOf(col) != buffer.getInt(base + CellData.FOREGROUND) or (0xFF shl 24) ||
                backgroundOf(col) != buffer.getInt(base + CellData.BACKGROUND) or (0xFF shl 24)
            ) {
                return false
            }
            var index = offsetAt(col)
            val end = offsetAt(col + 1)
            for (slot in 0 until CellData.CODE_POINTS) {
                val raw = buffer.getInt(base + slot * 4)
                if (slot > 0 && raw == 0) break
                val cp = if (raw == 0) {
                    32
                } else if (CellData.isScalar(raw)) {
                    raw
                } else {
                    0xFFFD
                }
                if (index >= end || codePointAtOffset(index, end) != cp) return false
                index += Character.charCount(cp)
            }
            if (index != end) return false
        }
        return true
    }

    fun writeRecord(buffer: ByteBuffer, offset: Int, col: Int) {
        for (i in 0 until CellData.STRIDE) buffer.putInt(offset + i * 4, 0)
        var index = offsetAt(col)
        val end = offsetAt(col + 1)
        var slot = 0
        while (index < end && slot < CellData.CODE_POINTS) {
            val cp = codePointAtOffset(index, end)
            buffer.putInt(offset + slot++ * 4, if (CellData.isScalar(cp)) cp else 0xFFFD)
            index += Character.charCount(cp)
        }
        buffer.putInt(offset + CellData.WIDTH, width(col))
        buffer.putInt(offset + CellData.FOREGROUND, foregroundOf(col))
        buffer.putInt(offset + CellData.BACKGROUND, backgroundOf(col))
        buffer.putInt(offset + CellData.FLAGS, flags(col))
        if (placeholder(col)) buffer.putInt(offset + 14 * 4, placeholderIds[col] ?: 0)
    }

    /** Cells added one after another, column by column, until the row is whole. */
    class Builder(private val columns: Int) {
        private var text = CharArray(minOf(columns, 32))
        private var used = 0
        private var offsets: IntArray? = null
        private var runs = IntArray(RUN_INTS * 4)
        private var runCount = 0
        private var column = 0
        private var placeholderIds: MutableMap<Int, Int>? = null

        // The row's trailing run of plain cells with one character so far: where it starts and the character, or -1 while the last cell is not plain.
        private var tailStart = 0
        private var tailChar = -1

        private fun append(char: Char) {
            if (used == text.size) text = text.copyOf(maxOf(16, used * 2))
            text[used++] = char
        }

        private fun placeholder(id: Int) {
            (placeholderIds ?: mutableMapOf<Int, Int>().also { placeholderIds = it })[column] = id
        }

        /** The next cell, whose text is what was appended since [start]. */
        private fun cell(start: Int, width: Int, foreground: Int, background: Int, flags: Int) {
            require(column < columns)
            val plain = width == 1 && used - start == 1
            var offsets = offsets
            if (offsets == null && !plain) {
                // Every cell so far was one character at its own column: the offsets that say so.
                offsets = IntArray(columns)
                for (col in 0 until column) offsets[col] = (1 shl WIDTH_SHIFT) or col
                this.offsets = offsets
            }
            if (offsets != null) offsets[column] = (width shl WIDTH_SHIFT) or start
            val style = flags and FLAGS_MASK
            val last = (runCount - 1) * RUN_INTS
            val newRun = runCount == 0 || runs[last + 1] != foreground || runs[last + 2] != background || runs[last + 3] != style
            if (newRun) {
                if (runCount * RUN_INTS == runs.size) runs = runs.copyOf(runs.size * 2)
                val at = runCount++ * RUN_INTS
                runs[at] = column
                runs[at + 1] = foreground
                runs[at + 2] = background
                runs[at + 3] = style
            }
            val char = if (plain) text[start].code else -1
            if (char != tailChar || char < 0) {
                tailStart = column
                tailChar = char
            }
            column++
        }

        fun copy(source: PackedCells, start: Int, end: Int) {
            for (col in start until end) {
                source.placeholderIds[col]?.let { placeholder(it) }
                val at = used
                for (index in source.offsetAt(col) until source.offsetAt(col + 1)) append(source.charAtOffset(index))
                cell(at, source.width(col), source.foregroundOf(col), source.backgroundOf(col), source.flags(col))
            }
        }

        fun read(buffer: ByteBuffer, offset: Int, count: Int) {
            require(count >= 0 && count <= columns - column)
            for (i in 0 until count) {
                val base = offset + i * CellData.BYTES
                val width = buffer.getInt(base + CellData.WIDTH)
                if (buffer.getInt(base) == 0x10EEEE) placeholder(buffer.getInt(base + 14 * 4))
                require(width in 0..2 && width <= columns - column)
                val at = used
                if (width != 0) {
                    for (slot in 0 until CellData.CODE_POINTS) {
                        val raw = buffer.getInt(base + slot * 4)
                        if (slot > 0 && raw == 0) break
                        val cp = if (raw == 0) {
                            32
                        } else if (CellData.isScalar(raw)) {
                            raw
                        } else {
                            0xFFFD
                        }
                        if (cp <= 0xFFFF) {
                            append(cp.toChar())
                        } else {
                            append(Character.highSurrogate(cp))
                            append(Character.lowSurrogate(cp))
                        }
                    }
                }
                cell(at, width, buffer.getInt(base + CellData.FOREGROUND) or (0xFF shl 24), buffer.getInt(base + CellData.BACKGROUND) or (0xFF shl 24), buffer.getInt(base + CellData.FLAGS))
            }
        }

        /** A cell given whole, as [from] has them; [width] 0 is the continuation column of a wide one. */
        internal fun add(chars: String, width: Int, foreground: Int, background: Int, flags: Int) {
            val at = used
            for (char in chars) append(char)
            cell(at, width, foreground, background, flags)
        }

        fun build(): PackedCells {
            check(column == columns)
            // A run of one cell saves nothing: only two or more are kept as a tail.
            val explicit = if (tailChar >= 0 && columns - tailStart >= 2) tailStart else columns
            val offsets = offsets
            val textEnd = when {
                explicit == columns -> used
                offsets == null -> explicit
                else -> offsets[explicit] and OFFSET_MASK
            }
            return PackedCells(
                columns,
                explicit,
                text.copyOf(textEnd),
                offsets?.copyOf(explicit),
                runs.copyOf(runCount * RUN_INTS),
                if (explicit < columns) tailChar.toChar() else '\u0000',
                placeholderIds?.takeIf { it.isNotEmpty() }?.toMap() ?: emptyMap(),
            )
        }
    }

    companion object {
        /** Ints per style run: where it starts, its foreground, its background, its flags. */
        private const val RUN_INTS = 4

        /** An offset keeps the cell's width above this bit and where its text starts below it. */
        private const val WIDTH_SHIFT = 28
        private const val OFFSET_MASK = (1 shl WIDTH_SHIFT) - 1
        private const val FLAGS_MASK = 0xFFFFFF

        private val ASCII = Array(128) { charArrayOf(it.toChar()) }

        private fun isComplex(cp: Int): Boolean {
            val direction = Character.getDirectionality(cp)
            return TerminalShaping.script(cp) != 0 ||
                direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
        }

        fun from(cells: List<TerminalLine.Cell>): PackedCells {
            if (cells is PackedCells) return cells
            val builder = Builder(cells.sumOf { it.width.coerceIn(1, 2) })
            for (cell in cells) {
                val width = cell.width.coerceIn(1, 2)
                builder.add(
                    buildString {
                        append(cell.char)
                        cell.combiningChars.forEach { append(it) }
                    },
                    width,
                    cell.fgColor.toArgb(),
                    cell.bgColor.toArgb(),
                    (if (cell.bold) 1 else 0) or (cell.underline shl 1) or
                        (if (cell.italic) 8 else 0) or (if (cell.blink) 16 else 0) or
                        (if (cell.reverse) 32 else 0) or (if (cell.strike) 128 else 0),
                )
                // The column a wide cell covers beside its own has no text, colours or flags of its own.
                if (width == 2) builder.add("", 0, 0, 0, 0)
            }
            return builder.build()
        }

        fun empty(columns: Int, fg: Color, bg: Color): PackedCells {
            val style = intArrayOf(0, fg.toArgb(), bg.toArgb(), 0)
            // The same cells as a Builder would be given: one NUL each, which is a tail from two columns on.
            return if (columns >= 2) {
                PackedCells(columns, 0, CharArray(0), null, style, '\u0000', emptyMap())
            } else {
                PackedCells(columns, columns, CharArray(columns), null, if (columns == 0) IntArray(0) else style, '\u0000', emptyMap())
            }
        }
    }
}
