package com.hienlh.ppm.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ppmText

/**
 * The compact Edit/MultiEdit preview (`edit-diff-compute.ts`): removed lines before the added
 * ones in each changed run, and the words that changed marked inside a paired line. Lines and
 * words are diffed as jsdiff does it, with Myers' algorithm; the web's syntax colours are not here.
 */
internal object EditDiff {
    enum class Kind { EQUAL, DEL, INS }

    /** [changed] holds `[start, end)` offsets of the words that differ from the paired line. */
    data class Row(val kind: Kind, val text: String, val changed: List<Pair<Int, Int>> = emptyList())

    data class Rows(val rows: List<Row>, val extra: Int)

    /** A chunk's lines, one trailing line break dropped. */
    fun lines(text: String): List<String> = (if (text.endsWith("\n")) text.dropLast(1) else text).split("\n")

    fun rows(old: String, new: String, maxLines: Int = 12): Rows {
        val a = lines(old)
        val b = lines(new)
        val (removed, inserted) = diff(a, b)
        val rows = mutableListOf<Row>()
        val deleted = mutableListOf<String>()
        val added = mutableListOf<String>()

        fun flush() {
            val paired = minOf(deleted.size, added.size)
            deleted.forEachIndexed { k, line ->
                rows += Row(Kind.DEL, line, if (k < paired) wordRanges(line, added[k]).first else emptyList())
            }
            added.forEachIndexed { k, line ->
                rows += Row(Kind.INS, line, if (k < paired) wordRanges(deleted[k], line).second else emptyList())
            }
            deleted.clear()
            added.clear()
        }

        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            if (i < a.size && removed[i]) {
                deleted += a[i++]
            } else if (j < b.size && inserted[j]) {
                added += b[j++]
            } else {
                flush()
                if (i >= a.size || j >= b.size) break
                rows += Row(Kind.EQUAL, a[i])
                i++
                j++
            }
        }
        flush()
        val shown = rows.take(maxLines)
        return Rows(shown, rows.size - shown.size)
    }

    /** `diffWordsWithSpace`'s tokens: a run of word characters, a run of whitespace, or one other character. */
    private val token = Regex("[\\p{L}\\p{N}_]+|\\s+|[^\\p{L}\\p{N}_\\s]")

    /** The changed words of a removed line and of the added line that replaces it. */
    fun wordRanges(deleted: String, added: String): Pair<List<Pair<Int, Int>>, List<Pair<Int, Int>>> {
        val x = token.findAll(deleted).map { it.value }.toList()
        val y = token.findAll(added).map { it.value }.toList()
        val (removed, inserted) = diff(x, y)
        return ranges(x, removed) to ranges(y, inserted)
    }

    private fun ranges(tokens: List<String>, marked: BooleanArray): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var offset = 0
        tokens.forEachIndexed { index, token ->
            val end = offset + token.length
            if (marked[index]) {
                // Neighbouring changed tokens are one range, as jsdiff joins them into one part.
                val last = out.lastOrNull()
                if (last != null && last.second == offset) out[out.lastIndex] = last.first to end else out += offset to end
            }
            offset = end
        }
        return out
    }

    /**
     * Which items of [a] are removed and which of [b] are inserted. The shared head and tail are
     * set aside first; past [LIMIT] items in between, it is all one replacement — the preview
     * shows twelve rows, and Myers' trace would cost megabytes.
     */
    fun <T> diff(a: List<T>, b: List<T>): Pair<BooleanArray, BooleanArray> {
        val removed = BooleanArray(a.size)
        val inserted = BooleanArray(b.size)
        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var endA = a.size
        var endB = b.size
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) {
            endA--
            endB--
        }
        val n = endA - start
        val m = endB - start
        if (n + m > LIMIT) {
            for (k in start until endA) removed[k] = true
            for (k in start until endB) inserted[k] = true
            return removed to inserted
        }
        myers(a.subList(start, endA), b.subList(start, endB), removed, inserted, start)
        return removed to inserted
    }

    private const val LIMIT = 2_000

    /** Myers' O(ND) shortest edit script, marking into [removed] and [inserted] from [base]. */
    private fun <T> myers(a: List<T>, b: List<T>, removed: BooleanArray, inserted: BooleanArray, base: Int) {
        val n = a.size
        val m = b.size
        if (n == 0 && m == 0) return
        val max = n + m
        val offset = max + 1
        val v = IntArray(2 * max + 3)
        val trace = mutableListOf<IntArray>()
        var found = false
        for (d in 0..max) {
            trace += v.copyOf()
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) v[offset + k + 1] else v[offset + k - 1] + 1
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) {
                    x++
                    y++
                }
                v[offset + k] = x
                if (x >= n && y >= m) {
                    found = true
                    break
                }
                k += 2
            }
            if (found) break
        }
        // Walk the trace back from the end: each step down is an insertion, each step right a removal.
        var x = n
        var y = m
        for (d in trace.lastIndex downTo 1) {
            val prev = trace[d]
            val k = x - y
            val down = k == -d || (k != d && prev[offset + k - 1] < prev[offset + k + 1])
            val prevK = if (down) k + 1 else k - 1
            val prevX = prev[offset + prevK]
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) {
                x--
                y--
            }
            if (down) inserted[base + prevY] = true else removed[base + prevX] = true
            x = prevX
            y = prevY
        }
    }
}

/** An edit as removed and added lines, the changed words marked (`edit-diff-preview.tsx`). */
@Composable
internal fun EditDiffView(old: String, new: String) {
    val tokens = LocalPpmTokens.current
    val diff = remember(old, new) { EditDiff.rows(old, new) }
    // `font-mono text-[11px]` inside the card's `text-xs`, whose line height is a ratio.
    val style = ppmText(11f, kind = FontKind.MONO, lineHeight = 11f * 16f / 12f)
    SelectionContainer {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).padding(vertical = 4.dp)) {
            for (row in diff.rows) {
                val (tint, gutter, prefix) = when (row.kind) {
                    EditDiff.Kind.DEL -> Triple(tokens.error.opacity(0.16).color, tokens.error.color, "-")
                    EditDiff.Kind.INS -> Triple(tokens.success.opacity(0.16).color, tokens.success.color, "+")
                    EditDiff.Kind.EQUAL -> Triple(Color.Transparent, tokens.text3.color, " ")
                }
                val word = (if (row.kind == EditDiff.Kind.DEL) tokens.error else tokens.success).opacity(0.4).color
                Row(Modifier.fillMaxWidth().background(tint)) {
                    BasicText(prefix, Modifier.padding(start = 6.dp).width(6.dp), style.copy(color = gutter))
                    val text = buildAnnotatedString {
                        append(row.text.ifEmpty { " " })
                        if (row.text.isNotEmpty()) for ((start, end) in row.changed) addStyle(SpanStyle(background = word), start, end)
                    }
                    BasicText(text, Modifier.padding(end = 6.dp), style.copy(color = tokens.text.color))
                }
            }
            if (diff.extra > 0) {
                BasicText(
                    "… +${diff.extra} more line${if (diff.extra == 1) "" else "s"}",
                    Modifier.padding(start = 6.dp),
                    style.copy(color = tokens.text3.color),
                )
            }
        }
    }
}
