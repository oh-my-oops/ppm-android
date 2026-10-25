package com.hienlh.ppm.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.JsDiff

/**
 * The compact Edit/MultiEdit preview (`edit-diff-compute.ts`): removed lines before the added
 * ones in each changed run, and the words that changed marked inside a paired line. Lines and
 * words are diffed as jsdiff 9 does it (`diffLines`, `diffWordsWithSpace`), down to which of two
 * equally short diffs it picks. The web marks each line up with highlight.js classes that no
 * stylesheet of its colours, so its code is plain too.
 */
internal object EditDiff {
    enum class Kind { EQUAL, DEL, INS }

    /** [changed] holds `[start, end)` offsets of the words that differ from the paired line. */
    data class Row(val kind: Kind, val text: String, val changed: List<Pair<Int, Int>> = emptyList())

    data class Rows(val rows: List<Row>, val extra: Int)

    /** `diffLines`' tokens: each line with the line break that ends it, and none for an empty text. */
    fun lines(text: String): List<String> = JsDiff.lines(text)

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
                deleted += a[i++].removeSuffix("\n")
            } else if (j < b.size && inserted[j]) {
                added += b[j++].removeSuffix("\n")
            } else {
                flush()
                if (i >= a.size || j >= b.size) break
                rows += Row(Kind.EQUAL, a[i].removeSuffix("\n"))
                i++
                j++
            }
        }
        flush()
        val shown = rows.take(maxLines)
        return Rows(shown, rows.size - shown.size)
    }

    /** jsdiff's word characters: ASCII ones and the Latin letters with diacritics. */
    private const val WORD = "a-zA-Z0-9_\\u00AD\\u00C0-\\u00D6\\u00D8-\\u00F6\\u00F8-\\u02C6\\u02C8-\\u02D7\\u02DE-\\u02FF\\u1E00-\\u1EFF"

    /** JavaScript's `\s` but the line breaks (`[^\S\n\r]`). */
    private const val SPACE = "\\t\\u000B\\f \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"

    /** `diffWordsWithSpace`'s tokens: a line break, a run of word characters, a run of spaces, or one other character. */
    private val token = Regex("\\r?\\n|[$WORD]+|[$SPACE]+|[^$WORD]")

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
                // Neighbouring changed tokens are one range, as they are one run of highlight.
                val last = out.lastOrNull()
                if (last != null && last.second == offset) out[out.lastIndex] = last.first to end else out += offset to end
            }
            offset = end
        }
        return out
    }

    /** Which items of [a] are removed and which of [b] are inserted, as jsdiff's `Diff.diff` finds them ([JsDiff.diff]). */
    fun <T> diff(a: List<T>, b: List<T>): Pair<BooleanArray, BooleanArray> = JsDiff.diff(a, b)
}

/** An edit as removed and added lines, the changed words marked (`edit-diff-preview.tsx`). */
@Composable
internal fun EditDiffView(old: String, new: String) {
    val tokens = LocalPpmTokens.current
    val diff = remember(old, new) { EditDiff.rows(old, new) }
    // `font-mono text-[11px]` inside the card's `text-xs`, whose line height is a ratio.
    val style = ppmText(11f, kind = FontKind.MONO, lineHeight = 11f * 16f / 12f)
    // `break-all`, as `Mono` does it: not in a diff holding a U+200B of its own, so a copy keeps it.
    val breaking = diff.rows.none { BREAK in it.text }
    WithoutBreaksOnCopy(breaking) {
        SelectionContainer {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).padding(vertical = 4.dp)) {
                for (row in diff.rows) {
                    // An empty context line has nothing to stand on: its gutter is a space that collapses.
                    if (row.kind == EditDiff.Kind.EQUAL && row.text.isEmpty()) continue
                    val (tint, gutter, prefix) = when (row.kind) {
                        EditDiff.Kind.DEL -> Triple(tokens.error.opacity(0.16).color, tokens.error.color, "-")
                        EditDiff.Kind.INS -> Triple(tokens.success.opacity(0.16).color, tokens.success.color, "+")
                        EditDiff.Kind.EQUAL -> Triple(Color.Transparent, tokens.text3.color, " ")
                    }
                    val word = SpanStyle(background = (if (row.kind == EditDiff.Kind.DEL) tokens.error else tokens.success).opacity(0.4).color)
                    Row(Modifier.fillMaxWidth().background(tint)) {
                        // `select-none`: a copy is the code alone.
                        DisableSelection {
                            BasicText(prefix, Modifier.padding(start = 6.dp).width(6.dp), style.copy(color = gutter), overflow = TextOverflow.Visible, softWrap = false)
                        }
                        val text = remember(row, word, breaking) { codeLine(row, word, breaking) }
                        BasicText(text, Modifier.padding(end = 6.dp), style.copy(color = tokens.text.color))
                    }
                }
                if (diff.extra > 0) {
                    DisableSelection {
                        BasicText(
                            "… +${diff.extra} more line${if (diff.extra == 1) "" else "s"}",
                            Modifier.padding(start = 6.dp),
                            style.copy(color = tokens.text3.color),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A row's code as `<code class="whitespace-pre-wrap break-all">` shows it once the HTML parser
 * has made each carriage return a line break: one at the end starts no line.
 */
private fun codeLine(row: EditDiff.Row, word: SpanStyle, breaking: Boolean): AnnotatedString {
    val shown = row.text.replace('\r', '\n').removeSuffix("\n")
    return preText(shown, breaking, row.changed.map { (start, end) -> start to minOf(end, shown.length) }, word)
}
