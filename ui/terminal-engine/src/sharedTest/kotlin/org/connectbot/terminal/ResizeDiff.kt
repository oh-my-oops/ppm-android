package org.connectbot.terminal

import java.nio.ByteBuffer
import java.util.zip.CRC32
import kotlin.random.Random

/**
 * The same scripted writes and resizes, traced the same way through two hosts: the real library on a device ([EngineInstrumentation]) and
 * the model of it the JVM tests stand in for it ([FakeVterm], through `ResizeModelTest`). A trace is what the library says to its
 * callbacks (the rows it pushes into the history with their flags, the rows it takes back, the cursor) and what it holds after every step,
 * row by row with the continuation flag `getCells` reads. [GOLDEN] is the CRC of each trace as the library produced it, so a model that
 * drifts from the library, or a library that is replaced by one that does not do what the model does, fails on one side or the other.
 *
 * Both sides compile this file (the module's `src/sharedTest/kotlin` is a source directory of the unit tests and of the instrumentation).
 */
internal object ResizeDiff {
    class Row(val text: String, val flag: Boolean, val cells: ByteArray)

    /** What the host's library says: the callbacks of [TerminalCallbacks], recorded, with a history that answers the library's pops. */
    class Rec : TerminalCallbacks {
        val buffer: ByteBuffer = CellData.buffer()
        val pushed = mutableListOf<Row>()
        val history = ArrayDeque<Row>()
        val popped = mutableListOf<String>()
        var cursor = 0 to 0
        private var pendingText = StringBuilder()
        private var pendingCells = ByteArray(0)

        override fun cellBuffer(): ByteBuffer = buffer
        override fun damage(startRow: Int, endRow: Int, startCol: Int, endCol: Int) = 0
        override fun moverect(dest: TermRect, src: TermRect) = 0
        override fun moveCursor(pos: CursorPosition, oldPos: CursorPosition, visible: Boolean): Int { cursor = pos.row to pos.col; return 0 }
        override fun setTermProp(prop: Int, value: TerminalProperty) = 0
        override fun bell() = 0
        override fun clearScrollback(): Int { history.clear(); return 0 }
        override fun onKeyboardInput(data: ByteArray) = 0
        override fun onTextFragment(kind: Int, command: Int, data: ByteArray, initial: Boolean, final: Boolean, cursorRow: Int, cursorCol: Int) = 0

        override fun pushScrollbackLine(cols: Int, start: Int, count: Int, cells: ByteBuffer, softWrapped: Boolean): Int {
            if (start == 0) {
                pendingText = StringBuilder()
                pendingCells = ByteArray(cols * CellData.BYTES)
            }
            for (i in 0 until count) {
                val cp = cells.getInt(i * CellData.BYTES)
                pendingText.append(if (cp == 0) '.' else String(Character.toChars(cp)))
                for (b in 0 until CellData.BYTES) pendingCells[(start + i) * CellData.BYTES + b] = cells.get(i * CellData.BYTES + b)
            }
            if (start + count == cols) {
                val row = Row(pendingText.toString().trimEnd('.'), softWrapped, pendingCells)
                pushed += row
                history.addLast(row)
            }
            return 0
        }

        override fun popScrollbackLine(cols: Int, start: Int, count: Int, cells: ByteBuffer): Int {
            val row = history.lastOrNull() ?: return 0
            // A row that is narrower than the screen it comes back to is padded with blank cells, one column wide.
            for (i in 0 until count) {
                val base = i * CellData.BYTES
                val from = (start + i) * CellData.BYTES
                if (from + CellData.BYTES <= row.cells.size) {
                    for (b in 0 until CellData.BYTES) cells.put(base + b, row.cells[from + b])
                } else {
                    for (b in 0 until CellData.BYTES) cells.put(base + b, 0)
                    cells.putInt(base + CellData.WIDTH, 1)
                }
            }
            if (start + count == cols) {
                history.removeLast()
                popped += row.text
            }
            return 1
        }
    }

    /** The library under trace: what is written to and resized, and where its cursor is. */
    interface Host : AutoCloseable {
        val native: ScreenNative
        val cursor: Pair<Int, Int>
    }

    sealed class Op {
        class Write(val bytes: String) : Op()
        class Resize(val rows: Int, val cols: Int) : Op()
    }

    /** What the scenarios of a set of seeds exercised, counted per scenario: the branches of a resize that a set of seeds has to keep reaching. */
    class Coverage {
        var scenarios = 0
        var withPushes = 0
        var withPops = 0
        var withWrappedPushes = 0
        var inAlternateScreen = 0
        var alternateWithPushes = 0
        var inColumns = 0
        var withWrappedScreen = 0
        override fun toString() = "scenarios=$scenarios pushes=$withPushes pops=$withPops wrappedPushes=$withWrappedPushes alt=$inAlternateScreen altPushes=$alternateWithPushes columns=$inColumns wrappedScreen=$withWrappedScreen"
    }

    private const val ESC = "\u001b"

    /** The script of one scenario: a size to start at, then writes and resizes. Only what [FakeVterm] models, and ASCII. */
    fun script(seed: Int): Triple<Int, Int, List<Op>> {
        val random = Random(seed)
        val rows0 = random.nextInt(3, 13)
        val cols0 = random.nextInt(5, 21)
        var rows = rows0
        var cols = cols0
        var serial = 0
        var inAlt = false
        val ops = mutableListOf<Op>()
        fun text(maxLength: Int): String {
            serial++
            val length = random.nextInt(0, maxLength + 1)
            return buildString {
                append(serial)
                append(':')
                for (i in 0 until length) append('a' + (i + serial) % 26)
            }.take(maxLength.coerceAtLeast(1))
        }
        repeat(random.nextInt(8, 31)) {
            val pick = random.nextInt(100)
            when {
                pick < 40 -> {
                    // Lines, some of them longer than the screen is wide, most with a line break after them.
                    val b = StringBuilder()
                    repeat(random.nextInt(1, 5)) {
                        b.append(text(cols * random.nextInt(1, 4)))
                        if (random.nextInt(10) < 8) b.append("\r\n")
                    }
                    ops += Op.Write(b.toString())
                }
                pick < 50 -> ops += Op.Write(ESC + "[" + random.nextInt(1, rows + 1) + ";" + random.nextInt(1, cols + 1) + "H")
                pick < 55 -> ops += Op.Write(ESC + "[" + random.nextInt(0, 3) + "K")
                pick < 58 -> ops += Op.Write(ESC + "[" + random.nextInt(0, 3) + "J")
                pick < 61 -> ops += Op.Write(ESC + "[" + random.nextInt(1, 4) + (if (random.nextBoolean()) "S" else "T"))
                pick < 64 -> ops += Op.Write(ESC + "[" + random.nextInt(1, 4) + (if (random.nextBoolean()) "L" else "M"))
                pick < 66 -> ops += Op.Write(ESC + "[" + random.nextInt(1, 6) + "X")
                pick < 68 -> ops += Op.Write(ESC + "[" + random.nextInt(1, 6) + (if (random.nextBoolean()) "@" else "P"))
                // The cursor saved when the alternate screen is entered is put back unclamped when it is left: save it at home.
                pick < 70 -> { ops += Op.Write(if (inAlt) ESC + "[?1049l" else ESC + "[H" + ESC + "[?1049h"); inAlt = !inAlt }
                pick < 71 -> ops += Op.Write(ESC + "[" + random.nextInt(1, rows) + ";" + random.nextInt(1, rows + 1) + "r")
                pick < 72 -> if (!inAlt) ops += Op.Write(ESC + "c")
                pick < 73 -> ops += Op.Write(ESC + "M")
                else -> {
                    // A resize: rows only most of the time, as a keyboard opening and closing is.
                    val kind = random.nextInt(10)
                    val newRows = if (kind < 8) random.nextInt(2, 15) else rows
                    val newCols = if (kind in 5..9) cols else random.nextInt(4, 25)
                    if (newRows != rows || newCols != cols) {
                        // A scroll region whose top is below the new last row is undefined in libvterm: none survives a resize here.
                        ops += Op.Write(ESC + "[r")
                        ops += Op.Resize(newRows, newCols)
                        rows = newRows
                        cols = newCols
                    }
                }
            }
        }
        // Every script ends with a resize back to where it was and one more write: the keyboard's round trip.
        ops += Op.Write(ESC + "[r")
        ops += Op.Resize(rows0, cols0)
        ops += Op.Write("\r\nend")
        return Triple(rows0, cols0, ops)
    }

    private fun crc(text: String): Long = CRC32().also { it.update(text.toByteArray()) }.value

    private fun escape(text: String) = buildString {
        for (c in text) append(if (c == '\u001b') "^[" else if (c == '\r') "\\r" else if (c == '\n') "\\n" else c.toString())
    }

    private fun dump(rec: Rec, host: Host, rows: Int, cols: Int, out: StringBuilder): Boolean {
        out.append("  cur=").append(host.cursor.first).append(',').append(host.cursor.second)
            .append(" hist=").append(rec.history.size).append(':').append(crc(rec.history.joinToString("|") { it.text + "/" + (if (it.flag) 1 else 0) }))
            .append(" pushed=").append(rec.pushed.joinToString(",") { it.text + "/" + (if (it.flag) 1 else 0) })
            .append(" popped=").append(rec.popped.joinToString(",")).append('\n')
        rec.pushed.clear()
        rec.popped.clear()
        val ranges = IntArray(rows * 2) { if (it % 2 == 0) 0 else cols }
        val lines = Array(rows) { "" }
        var wrapped = false
        ScreenTransfer().fetch(host.native, ranges) { row, _, count, buffer, offset, softWrapped ->
            val b = StringBuilder()
            for (i in 0 until count) {
                val cp = buffer.getInt(offset + i * CellData.BYTES)
                b.append(if (cp == 0) '.' else String(Character.toChars(cp)))
            }
            if (softWrapped) wrapped = true
            lines[row] = (if (softWrapped) "W " else "  ") + b
        }
        for (r in 0 until rows) out.append("   ").append(r.toString().padStart(2)).append(' ').append(lines[r]).append('\n')
        return wrapped
    }

    /** The trace of one scenario. [make] builds the host (the library, or the model) for a size, which calls [Rec] back. */
    fun trace(seed: Int, coverage: Coverage? = null, make: (Rec, Int, Int) -> Host): String {
        val (rows0, cols0, ops) = script(seed)
        return traceOps("seed $seed", rows0, cols0, ops, coverage, make)
    }

    fun traceOps(name: String, rows0: Int, cols0: Int, ops: List<Op>, coverage: Coverage? = null, make: (Rec, Int, Int) -> Host): String {
        val rec = Rec()
        val out = StringBuilder()
        var pushes = false
        var pops = false
        var wrappedPushes = false
        var alternate = false
        var alternateResize = false
        var alternatePushes = false
        var inColumns = false
        var wrappedScreen = false
        make(rec, rows0, cols0).use { host ->
            var rows = rows0
            var cols = cols0
            rec.pushed.clear()
            rec.popped.clear()
            out.append(name).append(' ').append(rows0).append('x').append(cols0).append('\n')
            for ((index, op) in ops.withIndex()) {
                when (op) {
                    is Op.Write -> {
                        val bytes = op.bytes.toByteArray(Charsets.ISO_8859_1)
                        host.native.writeInput(bytes, 0, bytes.size)
                        out.append('#').append(index).append(" W ").append(escape(op.bytes)).append('\n')
                        if (op.bytes.endsWith("[?1049h")) alternate = true else if (op.bytes.endsWith("[?1049l")) alternate = false
                    }
                    is Op.Resize -> {
                        host.native.resize(op.rows, op.cols)
                        out.append('#').append(index).append(" R ").append(rows).append('x').append(cols).append(" -> ").append(op.rows).append('x').append(op.cols).append('\n')
                        if (rec.pushed.isNotEmpty()) { pushes = true; if (alternate) alternatePushes = true }
                        if (rec.pushed.any { it.flag }) wrappedPushes = true
                        if (rec.popped.isNotEmpty()) pops = true
                        if (alternate) alternateResize = true
                        if (op.cols != cols) inColumns = true
                        rows = op.rows
                        cols = op.cols
                    }
                }
                if (dump(rec, host, rows, cols, out) && op is Op.Resize) wrappedScreen = true
            }
        }
        coverage?.let {
            it.scenarios++
            if (pushes) it.withPushes++
            if (pops) it.withPops++
            if (wrappedPushes) it.withWrappedPushes++
            if (alternateResize) it.inAlternateScreen++
            if (alternatePushes) it.alternateWithPushes++
            if (inColumns) it.inColumns++
            if (wrappedScreen) it.withWrappedScreen++
        }
        return out.toString()
    }

    fun checksum(trace: String): Long = crc(trace)

    /** The seeds both sides run: 50 of the scripts above, and two more for the alternate screen. */
    val SEEDS: List<Int> = (1..50).toList() + listOf(57, 60)

    /** The CRC of the trace of each of [SEEDS] as the real library produced it, in the same order. */
    val GOLDEN: List<Long> = listOf(
        3640993740L, 942334459L, 1992194098L, 2289900164L, 3155510907L, 960699100L, 111783078L, 2450040353L,
        421639540L, 2588658534L, 3219676127L, 696938439L, 1557597673L, 2134446045L, 1392468460L, 2868699840L,
        3872049737L, 3843817778L, 2800272481L, 770884927L, 3104674397L, 579168913L, 3217358003L, 3313562366L,
        4226180152L, 3475191357L, 3200047886L, 958240347L, 3860544362L, 315792993L, 3391171790L, 1373130244L,
        2361624448L, 2386255268L, 835611780L, 3496974257L, 2549219233L, 3714426204L, 1555616878L, 2631263753L,
        217249662L, 1171797321L, 741338144L, 1948933714L, 3894708261L, 1843965695L, 4000358286L, 758347805L,
        3118736560L, 3950311296L, 4162647860L, 312077731L,
    )
}
