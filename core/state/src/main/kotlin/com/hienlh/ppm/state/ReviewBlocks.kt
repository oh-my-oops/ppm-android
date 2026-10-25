package com.hienlh.ppm.state

/**
 * Change blocks for the session review (`src/shared/review-blocks.ts`): the hunks of a line diff
 * between the state a file is compared with and the file now.
 *
 * The server works the blocks out again from the disk before it keeps or reverts anything, and
 * finds the one the user answered by its key — so a key worked out any differently here is one
 * the server never finds. Every step is the web's: jsdiff's change order, the context merging,
 * and cyrb53 over the same UTF-16 code units.
 *
 * A block is a unified-diff hunk: its changed lines plus up to [BLOCK_CONTEXT] unchanged lines on
 * each side, with changes closer than twice that merged into one block. Its key is its place in
 * the base and its lines, so it survives edits elsewhere in the file and changes the moment the
 * agent touches the block again.
 */
object ReviewBlocks {
    /** Unchanged lines shown on each side of a change, as git shows them. */
    const val BLOCK_CONTEXT = 3

    enum class Kind(val sign: Char) { CONTEXT(' '), REMOVED('-'), ADDED('+') }

    data class Row(
        val kind: Kind,
        /** The line without its terminator. */
        val text: String,
        /** 1-based line number in the base, or null for an added line. */
        val o: Int?,
        /** 1-based line number in the file now, or null for a removed line. */
        val n: Int?,
    )

    data class Block(
        val key: String,
        /** Line indexes the block covers, context included: 0-based, end exclusive. */
        val oldFrom: Int,
        val oldTo: Int,
        val newFrom: Int,
        val newTo: Int,
        val rows: List<Row>,
        val added: Int,
        val removed: Int,
    )

    data class Diff(
        val blocks: List<Block>,
        val additions: Int,
        val deletions: Int,
        /** Lines in the base and in the file now. */
        val oldLines: Int,
        val newLines: Int,
    )

    /** Lines with their terminators; a last line without one is kept as it is. */
    fun splitLines(text: String): List<String> = JsDiff.lines(text)

    private fun stripEol(line: String): String = when {
        line.endsWith("\r\n") -> line.dropLast(2)
        line.endsWith("\n") -> line.dropLast(1)
        else -> line
    }

    /** cyrb53: a fast 53-bit string hash, the same in the browser and on the server. */
    internal fun hash53(s: String): String {
        var h1 = 0xdeadbeef.toInt()
        var h2 = 0x41c6ce57
        for (ch in s) {
            val c = ch.code
            h1 = (h1 xor c) * -1640531535 // Math.imul(…, 2654435761)
            h2 = (h2 xor c) * 1597334677
        }
        h1 = ((h1 xor (h1 ushr 16)) * -2048144789) xor ((h2 xor (h2 ushr 13)) * -1028477387) // 2246822507, 3266489909
        h2 = ((h2 xor (h2 ushr 16)) * -2048144789) xor ((h1 xor (h1 ushr 13)) * -1028477387)
        return ((h2.toLong() and 0x1FFFFF) * 4294967296L + (h1.toLong() and 0xFFFFFFFFL)).toString(36)
    }

    private class Op(val kind: Kind, val line: String, val o: Int, val n: Int)

    /**
     * The blocks between [base] and [current], or null when the diff would take too long — a large
     * file rewritten wholesale, which the web answers whole rather than block by block.
     */
    fun computeBlocks(base: String, current: String, context: Int = BLOCK_CONTEXT): Diff? {
        val old = splitLines(base)
        val now = splitLines(current)
        val runs = JsDiff.runs(old, now) ?: return null
        val ops = ArrayList<Op>(maxOf(old.size, now.size))
        var o = 0
        var n = 0
        var additions = 0
        var deletions = 0
        for (run in runs) {
            val kind = if (run.added) Kind.ADDED else if (run.removed) Kind.REMOVED else Kind.CONTEXT
            repeat(run.count) {
                ops += Op(kind, if (kind == Kind.REMOVED) old[o] else now[n], o, n)
                if (kind != Kind.ADDED) o++
                if (kind != Kind.REMOVED) n++
                if (kind == Kind.ADDED) additions++
                if (kind == Kind.REMOVED) deletions++
            }
        }

        val changed = ops.indices.filter { ops[it].kind != Kind.CONTEXT }
        val blocks = mutableListOf<Block>()
        var i = 0
        while (i < changed.size) {
            val first = changed[i]
            var last = first
            var j = i + 1
            while (j < changed.size && changed[j] - last - 1 <= 2 * context) last = changed[j++]
            val from = maxOf(0, first - context)
            val to = minOf(ops.size, last + context + 1)
            blocks += toBlock(ops.subList(from, to))
            i = j
        }
        return Diff(blocks, additions, deletions, o, n)
    }

    private fun toBlock(ops: List<Op>): Block {
        val head = ops[0]
        var oldLen = 0
        var newLen = 0
        var added = 0
        var removed = 0
        val rows = ArrayList<Row>(ops.size)
        val hashed = StringBuilder()
        for (op in ops) {
            if (op.kind != Kind.ADDED) oldLen++
            if (op.kind != Kind.REMOVED) newLen++
            if (op.kind == Kind.ADDED) added++
            if (op.kind == Kind.REMOVED) removed++
            rows += Row(op.kind, stripEol(op.line), if (op.kind == Kind.ADDED) null else op.o + 1, if (op.kind == Kind.REMOVED) null else op.n + 1)
            hashed.append(op.kind.sign).append(op.line)
        }
        return Block(
            key = "${head.o}.${hash53(hashed.toString())}",
            oldFrom = head.o,
            oldTo = head.o + oldLen,
            newFrom = head.n,
            newTo = head.n + newLen,
            rows = rows,
            added = added,
            removed = removed,
        )
    }

    private val leadingInt = Regex("^[+-]?\\d+")

    /** The base line a block starts at, read off its key (`<line>.<hash>`) as `parseInt` reads it. */
    fun blockStart(key: String): Int = leadingInt.find(key.trimStart())?.value?.toIntOrNull() ?: 0

    /**
     * Where each line of [from] sits in [to] (0-based), or -1 where it is not there unchanged; null
     * when the diff would take too long.
     */
    fun lineMap(from: String, to: String): IntArray? {
        val old = splitLines(from)
        val runs = JsDiff.runs(old, splitLines(to)) ?: return null
        val at = IntArray(old.size) { -1 }
        var o = 0
        var n = 0
        for (run in runs) {
            when {
                run.added -> n += run.count
                run.removed -> o += run.count
                else -> repeat(run.count) { at[o++] = n++ }
            }
        }
        return at
    }
}
