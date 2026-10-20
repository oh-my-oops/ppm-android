package com.hienlh.ppm.design

import android.icu.text.BreakIterator
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import java.text.Bidi

/**
 * `line-clamp-N` as Chrome draws it (LayoutNG's `NGLineTruncator`): the text wraps as it would
 * unclamped and "…" goes after what the last line shown holds, a `pre-wrap` line's hanging spaces
 * included; only when the "…" does not fit there is that line cut back, a character at a time.
 * The "…" is in the text's own style, after a link too. Android's own ellipsis instead runs the
 * rest of the text into that line and cuts it wherever the width ends, mid-word.
 *
 * The whole text is laid out and its last line clipped at the cut, so the box keeps the width the
 * whole text asks for (a bubble is as wide as its widest line, a hidden one too) and a selection
 * is of the text itself, never of the "…". Right-to-left text keeps Android's ellipsis.
 */
@Composable
fun ClampedText(
    text: AnnotatedString,
    style: TextStyle,
    maxLines: Int,
    modifier: Modifier = Modifier,
    /** `white-space: pre-wrap`: spaces ending a line stay on it, so the "…" comes after them. */
    preWrap: Boolean = false,
    inlineContent: Map<String, InlineTextContent> = emptyMap(),
    /** Whether any of the text is left out, as `scrollHeight > clientHeight` tells the web. */
    onClamped: (Boolean) -> Unit = {},
) {
    val bidi = remember(text) { Bidi.requiresBidi(text.text.toCharArray(), 0, text.length) }
    if (bidi) {
        BasicText(
            text,
            modifier,
            style,
            onTextLayout = { onClamped(it.hasVisualOverflow) },
            overflow = TextOverflow.Ellipsis,
            maxLines = maxLines,
            inlineContent = inlineContent,
        )
        return
    }
    val measurer = rememberTextMeasurer()
    val ellipsis = remember(measurer, style) { measurer.measure("…", style, maxLines = 1) }
    // How wide spaces are, measured before a character so that none of them ends the line.
    val spaces = remember(measurer, style) {
        { run: String -> measurer.measure("$run|", style, softWrap = false).getHorizontalPosition(run.length, usePrimaryDirection = true) }
    }
    var cut by remember { mutableStateOf<LineClamp.Cut?>(null) }
    BasicText(
        text,
        modifier.drawWithContent {
            val at = cut
            if (at == null) {
                drawContent()
            } else {
                clipRect(bottom = at.top) { this@drawWithContent.drawContent() }
                clipRect(top = at.top, right = at.x) { this@drawWithContent.drawContent() }
                drawText(ellipsis, topLeft = Offset(at.x, at.baseline - ellipsis.firstBaseline))
            }
        },
        style,
        onTextLayout = { layout ->
            cut = LineClamp.cut(layout, maxLines, preWrap, ellipsis.getLineRight(0), spaces)
            onClamped(cut != null)
        },
        overflow = TextOverflow.Clip,
        maxLines = maxLines,
        inlineContent = inlineContent,
    )
}

internal object LineClamp {
    /** The "…" goes at [x] on the last line shown, whose top is [top]; the text after [x] is hidden. */
    data class Cut(val x: Float, val top: Float, val baseline: Float)

    /**
     * Null when no text is left out: a final line break ends the last line, it does not start one.
     * [spaces] measures a run of spaces.
     */
    fun cut(layout: TextLayoutResult, maxLines: Int, preWrap: Boolean, ellipsis: Float, spaces: (String) -> Float): Cut? {
        val text = layout.layoutInput.text.text
        val line = maxLines - 1
        if (layout.lineCount < maxLines || layout.getLineEnd(line) >= text.length) return null
        val start = layout.getLineStart(line)
        val visible = layout.getLineEnd(line, visibleEnd = true)
        // Spaces where a line breaks hang in `pre-wrap`, so the "…" goes after them; otherwise they are gone.
        val end = if (preWrap) layout.getLineEnd(line).let { if (it > visible && text[it - 1] == '\n') it - 1 else it } else visible
        val visibleRight = if (visible > start) layout.getBoundingBox(visible - 1).right else layout.getLineLeft(line)
        // Compose ends a line at its last visible character, whatever hangs after it.
        val right = { k: Int -> if (k > visible) visibleRight + spaces(text.substring(visible, k)) else layout.getBoundingBox(k - 1).right }
        val characters = BreakIterator.getCharacterInstance().apply { setText(text) }
        val kept = keep(start, end, layout.layoutInput.constraints.maxWidth.toFloat(), ellipsis, right, characters::preceding, characters::following)
        return Cut(x = if (kept > start) right(kept) else layout.getLineLeft(line), top = layout.getLineTop(line), baseline = layout.getLineBaseline(line))
    }

    /**
     * How much of the last line shown, [start] to [end], stays (`NGLineTruncator::EllipsizeChild`):
     * all of it when the "…" fits after it, else up to the last character boundary that leaves the
     * "…" room — one character at least, nothing of an empty line. [right] is where the text before
     * an offset ends; [previous] and [next] step one character, so a pair or an emoji goes whole.
     */
    fun keep(start: Int, end: Int, width: Float, ellipsis: Float, right: (Int) -> Float, previous: (Int) -> Int, next: (Int) -> Int): Int {
        var k = end
        while (k > start) {
            if (right(k) + ellipsis <= width) return k
            k = previous(k)
        }
        return if (end > start) next(start) else start
    }
}
