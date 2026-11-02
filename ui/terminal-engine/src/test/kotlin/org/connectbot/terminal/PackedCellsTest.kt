package org.connectbot.terminal

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [PackedCells] against termlib's own class, which kept four arrays as long as the row was wide (2,198 B a row at 105 columns, 104 MB for
 * the 50,000 rows of a terminal's history) where this one keeps what the row holds. Rows are made from the cell records the library hands
 * over, by the rules the emulator uses (one read per row, a read in pieces as `pushScrollbackLine` gets them, a copy of the cells around
 * the ones that changed as `updateLine` builds them), and every method must answer as the upstream one does: that is what lets the
 * renderer, the selection, the links and the shaper keep reading rows as before.
 *
 * Rows are drawn to be hard on the layout: blank tails and tails of dashes or box characters, runs of one style and rows that change style
 * at every cell, wide and combining characters, emoji, characters the library hands over as nothing valid, right-to-left text, Kitty
 * placeholders, and cells of sixteen code points.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PackedCellsTest {
    private class Rec(val cps: IntArray, val width: Int, val fg: Int, val bg: Int, val flags: Int, val id: Int = 0)

    private class Style(val fg: Int, val bg: Int, val flags: Int)

    private val palette = intArrayOf(0x00FFFFFF, 0x00000000, 0x00FF0000, 0x0000FF00, 0x000000FF, 0x7F123456, 0x00AABBCC)
    private val flagSets = intArrayOf(0, 0, 0, 0, 1, 8, 32, 2, 130, 0x10000, 0xFFFFFF)
    private val repeats = intArrayOf('-'.code, '='.code, 0x2500, 0x2591, ' '.code, 'x'.code, 0xE9, 0x627)

    // ------------------------------------------------------------------------------------------------- rows

    private fun randomStyle(random: Random) = Style(palette[random.nextInt(palette.size)], palette[random.nextInt(palette.size)], flagSets[random.nextInt(flagSets.size)])

    private fun row(random: Random, columns: Int): List<Rec> {
        val out = ArrayList<Rec>(columns)
        var style = Style(0x00FFFFFF, 0x00000000, 0)
        fun cell(cps: IntArray, width: Int = 1, id: Int = 0) {
            out += Rec(cps, width, style.fg, style.bg, style.flags, id)
        }
        // How much of the row is a tail of one cell repeated, and how much is made of everything else.
        val tail = if (random.nextInt(3) != 0) random.nextInt(columns + 1) else 0
        val body = columns - tail
        while (out.size < body) {
            val room = body - out.size
            when (random.nextInt(16)) {
                0, 1, 2 -> repeat(minOf(room, 1 + random.nextInt(20))) { cell(intArrayOf('a'.code + random.nextInt(26))) }
                3 -> repeat(minOf(room, 1 + random.nextInt(60))) { cell(intArrayOf(if (random.nextBoolean()) 0 else 32)) }
                4 -> {
                    val cp = repeats[random.nextInt(repeats.size)]
                    repeat(minOf(room, 2 + random.nextInt(40))) { cell(intArrayOf(cp)) }
                }
                5 -> if (room >= 2) {
                    cell(intArrayOf(0x65E5 + random.nextInt(50)), 2)
                    cell(IntArray(0), 0)
                }
                6 -> cell(intArrayOf('e'.code, 0x301, 0x302))
                7 -> if (room >= 2) {
                    cell(intArrayOf(0x1F600 + random.nextInt(40)), 2)
                    cell(IntArray(0), 0)
                }
                8 -> cell(intArrayOf(intArrayOf(0xD800, 0x110000, -5, 0xDFFF)[random.nextInt(4)]))
                9 -> cell(intArrayOf(if (random.nextBoolean()) 0x627 else 0x915))
                10 -> cell(intArrayOf(0x10EEEE, 0x305, 0x30D), 1, 1 + random.nextInt(0xFFFFFF))
                11 -> cell(IntArray(16) { if (it == 0) 'o'.code else 0x300 + it })
                else -> style = randomStyle(random)
            }
        }
        val tailCell = if (random.nextInt(3) == 0) intArrayOf(repeats[random.nextInt(repeats.size)]) else intArrayOf(if (random.nextBoolean()) 0 else 32)
        if (random.nextInt(3) == 0) style = randomStyle(random)
        repeat(tail) { cell(tailCell) }
        check(out.size == columns) { "a row of $columns cells, not ${out.size}" }
        return out
    }

    private fun put(buffer: ByteBuffer, index: Int, rec: Rec) {
        val base = index * CellData.BYTES
        for (slot in 0 until CellData.STRIDE) buffer.putInt(base + slot * 4, 0)
        rec.cps.forEachIndexed { slot, cp -> buffer.putInt(base + slot * 4, cp) }
        if (rec.id != 0) buffer.putInt(base + 14 * 4, rec.id)
        buffer.putInt(base + CellData.WIDTH, rec.width)
        buffer.putInt(base + CellData.FOREGROUND, rec.fg)
        buffer.putInt(base + CellData.BACKGROUND, rec.bg)
        buffer.putInt(base + CellData.FLAGS, rec.flags)
    }

    private fun buffer(records: List<Rec>): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(maxOf(records.size, 1) * CellData.BYTES).order(ByteOrder.nativeOrder())
        records.forEachIndexed { index, rec -> put(buffer, index, rec) }
        return buffer
    }

    /** What the library does to a row that has changed a little: a few of its cells are other cells. */
    private fun mutate(random: Random, records: List<Rec>): List<Rec> {
        val out = records.toMutableList()
        repeat(random.nextInt(3)) {
            if (out.isEmpty()) return@repeat
            val col = random.nextInt(out.size)
            val old = out[col]
            out[col] = when (random.nextInt(7)) {
                0 -> Rec(intArrayOf('a'.code + random.nextInt(26)), old.width, old.fg, old.bg, old.flags)
                1 -> Rec(old.cps, old.width, old.fg, old.bg, old.flags xor (1 shl random.nextInt(8)), old.id)
                2 -> Rec(old.cps, old.width, old.fg xor 0x10, old.bg, old.flags, old.id)
                3 -> Rec(old.cps, old.width, old.fg, old.bg xor 0x20, old.flags, old.id)
                4 -> Rec(old.cps, minOf(random.nextInt(3), out.size - col), old.fg, old.bg, old.flags, old.id)
                5 -> Rec(intArrayOf(if (random.nextBoolean()) 0 else 32), 1, old.fg, old.bg, old.flags)
                else -> old
            }
        }
        return out
    }

    private fun legacy(records: List<Rec>, pieces: Int = 1): LegacyPackedCells {
        val buffer = buffer(records)
        val builder = LegacyPackedCells.Builder(records.size)
        var at = 0
        for (piece in 0 until pieces) {
            val end = if (piece == pieces - 1) records.size else at + (records.size - at) / 2
            builder.read(buffer, at * CellData.BYTES, end - at)
            at = end
        }
        return builder.build()
    }

    private fun compact(records: List<Rec>, pieces: Int = 1): PackedCells {
        val buffer = buffer(records)
        val builder = PackedCells.Builder(records.size)
        var at = 0
        for (piece in 0 until pieces) {
            val end = if (piece == pieces - 1) records.size else at + (records.size - at) / 2
            builder.read(buffer, at * CellData.BYTES, end - at)
            at = end
        }
        return builder.build()
    }

    private val widths = intArrayOf(0, 1, 2, 3, 10, 40, 105, 200)

    private fun forRows(seed: Long, rows: Int = 600, body: (Random, List<Rec>) -> Unit) {
        val random = Random(seed)
        repeat(rows) {
            val columns = widths[random.nextInt(widths.size)]
            body(random, row(random, columns))
        }
    }

    // ------------------------------------------------------------------------------------------------- the comparison

    private fun ranges(random: Random, size: Int): List<Pair<Int, Int>> {
        if (size <= 12) return (0..size).flatMap { start -> (start..size).map { start to it } }
        return listOf(0 to size, 0 to 0, size to size, 1 to size, 0 to size - 1) + List(40) {
            val start = random.nextInt(size + 1)
            start to start + random.nextInt(size - start + 1)
        }
    }

    private fun assertSame(legacy: LegacyPackedCells, compact: PackedCells, label: String, random: Random) {
        assertEquals("$label: size", legacy.size, compact.size)
        for (col in 0 until legacy.size) {
            val at = "$label: column $col"
            assertEquals("$at width", legacy.width(col), compact.width(col))
            assertEquals("$at flags", legacy.flags(col), compact.flags(col))
            assertEquals("$at foreground", legacy.foreground(col), compact.foreground(col))
            assertEquals("$at background", legacy.background(col), compact.background(col))
            assertEquals("$at charAt", legacy.charAt(col), compact.charAt(col))
            assertEquals("$at blank", legacy.blank(col), compact.blank(col))
            assertEquals("$at placeholder", legacy.placeholder(col), compact.placeholder(col))
            assertEquals("$at placeholderPlacement", legacy.placeholderPlacement(col), compact.placeholderPlacement(col))
            assertEquals("$at placeholderImage", legacy.placeholderImage(col), compact.placeholderImage(col))
            assertEquals("$at placeholderMarks", legacy.placeholderMarks(col), compact.placeholderMarks(col))
            assertEquals("$at boxCharacter", legacy.boxCharacter(col), compact.boxCharacter(col))
            assertEquals("$at cell", legacy[col], compact[col])
        }
        for ((start, end) in ranges(random, legacy.size)) assertEquals("$label: text($start, $end)", legacy.text(start, end), compact.text(start, end))
        assertEquals("$label: text", legacy.text(), compact.text())
        assertEquals("$label: columnText", legacy.columnText(), compact.columnText())
        assertEquals("$label: needsShaping", legacy.needsShaping(), compact.needsShaping())
        assertEquals("$label: boxCharacter off the row", legacy.boxCharacter(legacy.size), compact.boxCharacter(compact.size))
        assertEquals("$label: boxCharacter before the row", legacy.boxCharacter(-1), compact.boxCharacter(-1))
        assertTrue("$label: equal to itself", compact.contentEquals(compact) && compact == compact)
        // What the renderer, the shaper and the screen's compare would be handed, record by record.
        val oldRecord = ByteBuffer.allocateDirect(CellData.BYTES).order(ByteOrder.nativeOrder())
        val newRecord = ByteBuffer.allocateDirect(CellData.BYTES).order(ByteOrder.nativeOrder())
        for (col in 0 until legacy.size) {
            legacy.writeRecord(oldRecord, 0, col)
            compact.writeRecord(newRecord, 0, col)
            for (slot in 0 until CellData.STRIDE) assertEquals("$label: column $col record, slot $slot", oldRecord.getInt(slot * 4), newRecord.getInt(slot * 4))
        }
    }

    // ------------------------------------------------------------------------------------------------- tests

    @Test fun `rows read in one piece answer as upstream's do`() {
        forRows(1) { random, records -> assertSame(legacy(records), compact(records), "one piece", random) }
    }

    @Test fun `rows read in pieces, as the library hands the scrolled row over, answer alike`() {
        forRows(2) { random, records ->
            for (pieces in 2..3) assertSame(legacy(records, pieces), compact(records, pieces), "$pieces pieces", random)
        }
    }

    @Test fun `a row compared with the screen's cells says what upstream says`() {
        forRows(3) { random, records ->
            val old = legacy(records)
            val new = compact(records)
            val columns = records.size
            // The row itself, in windows of it, and then with the screen having changed one cell.
            repeat(8) {
                val changed = if (random.nextBoolean() || records.isEmpty()) records else mutate(random, records)
                val buffer = buffer(changed)
                val start = random.nextInt(columns + 1)
                val count = random.nextInt(columns - start + 1)
                val oldAnswer = old.matches(buffer, start * CellData.BYTES, start, count)
                assertEquals("matches($start, $count)", oldAnswer, new.matches(buffer, start * CellData.BYTES, start, count))
            }
            val whole = buffer(records)
            assertTrue("a row matches what it was read from", new.matches(whole, 0, 0, columns))
            assertFalse("past the end", new.matches(whole, 0, 1, columns))
            assertFalse("before the start", new.matches(whole, 0, -1, 1))
        }
    }

    @Test fun `rows that differ are told apart as upstream tells them apart, so a changed cell is seen and an unchanged row is not rebuilt`() {
        forRows(4, rows = 1500) { random, records ->
            val changed = mutate(random, records)
            val oldA = legacy(records)
            val oldB = legacy(changed)
            val newA = compact(records)
            val newB = compact(changed)
            assertEquals("contentEquals", oldA.contentEquals(oldB), newA.contentEquals(newB))
            assertEquals("equals", oldA == oldB, newA == newB)
            assertEquals("sameShaping", oldA.sameShaping(oldB), newA.sameShaping(newB))
            assertTrue("a row is the same as one read again", newA.contentEquals(compact(records)) && newA.sameShaping(compact(records)))
            // A canonical layout: however the same cells arrive, the same row results.
            assertTrue("in pieces", newA.contentEquals(compact(records, 2)) && newA.contentEquals(compact(records, 3)))
            val copied = PackedCells.Builder(records.size).apply { copy(newA, 0, records.size) }.build()
            assertTrue("copied whole", newA.contentEquals(copied))
            val halves = PackedCells.Builder(records.size).apply {
                val half = records.size / 2
                copy(newA, 0, half)
                copy(newA, half, records.size)
            }.build()
            assertTrue("copied in halves", newA.contentEquals(halves))
        }
    }

    @Test fun `the cells around the ones that changed are copied across as updateLine does`() {
        forRows(5) { random, records ->
            val columns = records.size
            val other = mutate(random, records).let { if (it.size == columns) it else records }
            val start = random.nextInt(columns + 1)
            val end = start + random.nextInt(columns - start + 1)
            val buffer = buffer(other)
            val oldPrevious = legacy(records)
            val newPrevious = compact(records)
            val old = LegacyPackedCells.Builder(columns).apply {
                copy(oldPrevious, 0, start)
                read(buffer, start * CellData.BYTES, end - start)
                copy(oldPrevious, end, columns)
            }.build()
            val new = PackedCells.Builder(columns).apply {
                copy(newPrevious, 0, start)
                read(buffer, start * CellData.BYTES, end - start)
                copy(newPrevious, end, columns)
            }.build()
            assertSame(old, new, "rebuilt $start to $end", random)
        }
    }

    @Test fun `rows made from cells, and blank rows, are upstream's`() {
        val random = Random(6)
        repeat(300) {
            val cells = ArrayList<TerminalLine.Cell>()
            var covered = 0
            val target = widths[random.nextInt(widths.size)]
            while (covered < target) {
                val wide = random.nextInt(6) == 0 && covered + 2 <= target
                cells += TerminalLine.Cell(
                    char = if (random.nextInt(5) == 0) ' ' else 'a' + random.nextInt(26),
                    combiningChars = if (random.nextInt(8) == 0) listOf('́') else emptyList(),
                    fgColor = Color(palette[random.nextInt(palette.size)] or (0xFF shl 24)),
                    bgColor = Color(palette[random.nextInt(palette.size)] or (0xFF shl 24)),
                    bold = random.nextInt(4) == 0, italic = random.nextInt(4) == 0, underline = random.nextInt(4),
                    blink = random.nextInt(5) == 0, reverse = random.nextInt(5) == 0, strike = random.nextInt(5) == 0,
                    width = if (wide) 2 else 1,
                )
                covered += if (wide) 2 else 1
            }
            assertSame(LegacyPackedCells.from(cells), PackedCells.from(cells), "from ${cells.size} cells", random)
        }
        for (columns in listOf(0, 1, 2, 3, 80, 105)) {
            assertSame(LegacyPackedCells.empty(columns, Color.White, Color.Black), PackedCells.empty(columns, Color.White, Color.Black), "empty $columns", random)
            // A screen that was cleared hands the rows over as blank cells, which come out as spaces where an empty row's are NUL.
            val cleared = List(columns) { Rec(IntArray(0), 1, Color.White.toArgb(), Color.Black.toArgb(), 0) }
            assertSame(legacy(cleared), compact(cleared), "cleared $columns", random)
        }
    }

    @Test fun `rows that differ only in what their tail repeats are different rows`() {
        fun row(tail: Int) = List(20) { Rec(intArrayOf(if (it < 3) 'a'.code else tail), 1, 0, 0, 0) }
        val rows = listOf('-'.code, '='.code, 0, 0x2500, 'a'.code).map { row(it) }
        for (a in rows.indices) {
            for (b in rows.indices) {
                val expected = legacy(rows[a]).contentEquals(legacy(rows[b]))
                assertEquals("$a against $b", a == b, expected)
                assertEquals("$a against $b", expected, compact(rows[a]).contentEquals(compact(rows[b])))
            }
        }
    }

    @Test fun `rows whose cells break the same text in other places are not the same shaping`() {
        val a = listOf(Rec(intArrayOf('e'.code, 0x301), 1, 0, 0, 0), Rec(intArrayOf('x'.code), 1, 0, 0, 0))
        val b = listOf(Rec(intArrayOf('e'.code), 1, 0, 0, 0), Rec(intArrayOf(0x301, 'x'.code), 1, 0, 0, 0))
        assertFalse("upstream", legacy(a).sameShaping(legacy(b)))
        assertFalse(compact(a).sameShaping(compact(b)))
    }

    @Test fun `a row of NUL cells is the empty row, however it is made`() {
        for (columns in listOf(0, 1, 2, 3, 105)) {
            val cells = List(columns) { TerminalLine.Cell(char = '\u0000', fgColor = Color.White, bgColor = Color.Black) }
            assertTrue("$columns columns", PackedCells.from(cells).contentEquals(PackedCells.empty(columns, Color.White, Color.Black)))
        }
    }

    @Test fun `a row asked for a column it does not have fails as upstream's did`() {
        val records = row(Random(7), 20)
        val old = legacy(records)
        val new = compact(records)
        for (col in listOf(-1, 20, 21)) {
            for ((name, call) in listOf<Pair<String, (Int) -> Any?>>(
                "width" to { old.width(it) to new.width(it) },
                "flags" to { old.flags(it) to new.flags(it) },
                "foreground" to { old.foreground(it) to new.foreground(it) },
                "charAt" to { old.charAt(it) to new.charAt(it) },
            )) {
                val result = runCatching { call(col) }.exceptionOrNull()
                assertTrue("$name($col) throws an index error, not an answer", result is IndexOutOfBoundsException)
            }
        }
        try {
            compact(records).text(5, 21)
            fail("text past the end")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            PackedCells.Builder(4).build()
            fail("a row that is not whole")
        } catch (expected: IllegalStateException) {
        }
        try {
            PackedCells.Builder(4).read(buffer(List(5) { Rec(intArrayOf('a'.code), 1, 0, 0, 0) }), 0, 5)
            fail("more cells than the row has")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            PackedCells.Builder(2).read(buffer(listOf(Rec(intArrayOf('a'.code), 1, 0, 0, 0), Rec(intArrayOf('b'.code), 2, 0, 0, 0))), 0, 2)
            fail("a wide cell in the last column")
        } catch (expected: IllegalArgumentException) {
        }
    }

    /** What the renderer is asked, cell by cell, and what it draws: through a canvas that writes down its calls. */
    private class Recording : Canvas() {
        val calls = ArrayList<String>()
        override fun drawText(text: CharArray, index: Int, count: Int, x: Float, y: Float, paint: Paint) {
            calls += "text ${String(text, index, count)} at $x,$y"
        }
        override fun save(): Int {
            calls += "save"
            return 1
        }
        override fun translate(dx: Float, dy: Float) {
            calls += "translate $dx,$dy"
        }
        override fun scale(sx: Float, sy: Float) {
            calls += "scale $sx,$sy"
        }
        override fun restoreToCount(saveCount: Int) {
            calls += "restore $saveCount"
        }
    }

    /** A font whose width depends on the text, so that a cell drawn from the wrong characters is told by the scaling it gets. */
    private class Font : Paint() {
        override fun measureText(text: CharArray, index: Int, count: Int): Float = 3f + (0 until count).sumOf { text[index + it].code % 11 }
    }

    @Test fun `every cell is drawn with the same text and scale as upstream draws it`() {
        forRows(8, rows = 250) { _, records ->
            val old = legacy(records)
            val new = compact(records)
            val paint = Font().apply { textSize = 14f }
            for (pass in 0 until 3) {
                if (pass == 2) paint.textSize = 17f
                for (cellWidth in floatArrayOf(6f, 40f, 0f)) {
                    for (col in 0 until old.size) {
                        if (old.width(col) == 0) continue
                        val expected = Recording()
                        val actual = Recording()
                        old.draw(expected, col, col * 8f, 20f, paint, cellWidth)
                        new.draw(actual, col, col * 8f, 20f, paint, cellWidth)
                        assertEquals("column $col, width $cellWidth, pass $pass", expected.calls, actual.calls)
                    }
                }
            }
        }
    }

    @Test fun `a row that holds a script that needs shaping says so wherever the script is, tail included`() {
        fun cells(vararg cps: Int): List<Rec> = cps.map { Rec(intArrayOf(it), 1, 0, 0, 0) }
        assertFalse(compact(cells('a'.code, 'b'.code, 0, 0, 0)).needsShaping())
        assertTrue("in the body", compact(cells('a'.code, 0x627, 0, 0, 0)).needsShaping())
        assertTrue("in a tail", compact(cells('a'.code, 0x627, 0x627, 0x627)).needsShaping())
        assertTrue("devanagari", compact(cells(0x915, 0x915, 'a'.code)).needsShaping())
        assertTrue("the tail alone", compact(List(10) { Rec(intArrayOf(0x627), 1, 0, 0, 0) }).needsShaping())
    }

    // ------------------------------------------------------------------------------------------------- what a row costs

    private fun plain(text: String, columns: Int = 105, runs: Int = 1) = compact(
        List(columns) { col ->
            val style = if (runs > 1) (col * runs / columns) % palette.size else 0
            Rec(intArrayOf(if (col < text.length) text[col].code else 0), 1, palette[style], 0, 0)
        },
    )

    /**
     * The history keeps 50,000 of these in a heap of 192 MB. Upstream's cost 2,022 B each by this count whatever was in the row; these rows
     * cost what they hold, which is what keeps one terminal's history within a few tens of MB, so a layout that grows back with the width
     * of the row fails here before it fails a tablet.
     */
    @Test fun `a row costs what it holds, not what it is wide`() {
        val seq = plain("12345")
        val full = plain("0123456789".repeat(11).take(104))
        val coloured = plain("0123456789".repeat(11).take(104), runs = 20)
        val blank = plain("")
        val upstream = legacy(List(105) { Rec(intArrayOf('1'.code), 1, 0, 0, 0) }).shapingRetentionBytes
        assertEquals("what the layout it replaces cost a row", 2_022, upstream)
        assertTrue("a row of seq output: ${seq.shapingRetentionBytes} B", seq.shapingRetentionBytes <= 160)
        assertTrue("a blank row: ${blank.shapingRetentionBytes} B", blank.shapingRetentionBytes <= 150)
        assertTrue("a full row in one style: ${full.shapingRetentionBytes} B", full.shapingRetentionBytes <= 360)
        assertTrue("a full row of twenty colours: ${coloured.shapingRetentionBytes} B", coloured.shapingRetentionBytes <= 700)
    }
}
