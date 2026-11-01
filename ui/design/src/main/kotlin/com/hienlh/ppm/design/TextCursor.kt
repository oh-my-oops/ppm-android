package com.hienlh.ppm.design

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/*
 * Chrome's I-beam over text. A `cursor: auto` is an I-beam only where the pixel under the mouse
 * belongs to a text node that can start a selection (Blink's `EventHandler::SelectAutoCursor`: a
 * Text hit, with no `user-select: none` above it) — not to the block around the text. So a line
 * shorter than its box has the arrow after its last glyph, and the arrow is in the gap between two
 * paragraphs, in the half-leading above and below a line (a text node covers its font's ascent
 * above the baseline to its descent below, rounded as `chromeAscentDescent` rounds them, not the
 * line box `line-height` makes taller), on an inline code's padding and border, a list's marker,
 * a checkbox, a chip in the line. Moving down the lines of a code block alternates I-beam and
 * arrow in Chrome for that reason. The other cursors stay where CSS sets them: the hand over a
 * link, the arrow over a `<button>` (Tailwind 4) and over everything in a `select-none` row.
 */

/**
 * Whether text drawn here shows the I-beam over its glyphs. The chat's rows are `select-none`,
 * so it is off until a [TextCursorRegion] stands for one of the `select-text` islands in them.
 */
val LocalTextCursor = staticCompositionLocalOf { false }

/**
 * A web `select-text` region whose cursor is `auto`: the [ChromeText], [ClampedText] and
 * [CursorText] in [content] show the I-beam over their glyph runs. Not [enabled] it is a
 * `select-none` island (or a `<button>`) inside one, which keeps the arrow.
 */
@Composable
fun TextCursorRegion(enabled: Boolean = true, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTextCursor provides enabled, content = content)
}

/**
 * [BasicText] in a [TextCursorRegion]: the same text, with the I-beam over its glyph runs. The
 * parameters are [BasicText]'s that the app uses, in its order.
 */
@Composable
fun CursorText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    inlineContent: Map<String, InlineTextContent> = emptyMap(),
) {
    val layout = remember { TextLayoutHolder() }
    BasicText(
        text,
        modifier.textRunCursor(layout),
        style,
        onTextLayout = {
            layout.result = it
            onTextLayout?.invoke(it)
        },
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        inlineContent = inlineContent,
    )
}

/** [CursorText] of a string. */
@Composable
fun CursorText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
) {
    val annotated = remember(text) { AnnotatedString(text) }
    CursorText(annotated, modifier, style, onTextLayout, overflow, softWrap, maxLines)
}

/** The layout a text last measured, which a hover asks where its glyphs are. */
internal class TextLayoutHolder {
    var result: TextLayoutResult? = null
}

/**
 * The cursor Chrome shows over a text: the I-beam while the mouse is over one of the text's glyph
 * runs, [overRun] says where, and the arrow over the rest of its box when [enabled] (a
 * [LocalTextCursor] region). A node's icon is the same over its whole box, so the node stays and
 * its icon is swapped as the mouse crosses a run's edge, which shows at once even where the mouse
 * has stopped; a hand or a pointer of a box inside the text still wins over it.
 */
@Composable
internal fun Modifier.hoverTextCursor(enabled: Boolean, overRun: (Offset) -> Boolean): Modifier {
    if (!enabled) return this
    var over by remember { mutableStateOf(false) }
    val hit by rememberUpdatedState(overRun)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: continue
                if (change.type != PointerType.Mouse) continue
                over = event.type != PointerEventType.Exit && hit(change.position)
            }
        }
    }.pointerHoverIcon(if (over) WebCursor.Text else WebCursor.Default)
}

/**
 * [hoverTextCursor] for a [BasicText] whose `onTextLayout` fills [layout]: the I-beam in a
 * [LocalTextCursor] region over its glyph runs, but where [hidden] says the text is not shown.
 */
@Composable
internal fun Modifier.textRunCursor(layout: TextLayoutHolder, hidden: (Offset) -> Boolean = { false }): Modifier =
    hoverTextCursor(LocalTextCursor.current) { position -> !hidden(position) && layout.result?.overRun(position) == true }

/** Whether [position] is over a glyph run of this text, as laid out by [BasicText]: [isTextRun] and in the fragment's box. */
internal fun TextLayoutResult.overRun(position: Offset): Boolean {
    val text = layoutInput.text
    val offset = characterAt(position)
    if (offset < 0 || !text.isTextRun(offset)) return false
    val style = layoutInput.style
    // A span in another font (a `font-mono` value in a line of sans) is a box of its own.
    val family = text.spanStyles.lastOrNull { offset >= it.start && offset < it.end && it.item.fontFamily != null }?.item?.fontFamily ?: style.fontFamily
    val size = if (style.fontSize.isSpecified) style.fontSize else 14.sp
    return overFragment(position.y, getLineBaseline(getLineForOffset(offset)), with(layoutInput.density) { size.toPx() }, chromeFaceOf(family))
}

/**
 * The character whose box holds [position], or -1. The offset [TextLayoutResult.getOffsetForPosition]
 * answers is the nearest edge, the end of the line for a point far past it, so its box and the one before are tried.
 */
internal fun TextLayoutResult.characterAt(position: Offset): Int {
    val offset = getOffsetForPosition(position)
    for (candidate in intArrayOf(offset, offset - 1)) {
        if (candidate < 0 || candidate >= layoutInput.text.length) continue
        val box = getBoundingBox(candidate)
        if (position.x >= box.left && position.x < box.right && position.y >= box.top && position.y < box.bottom) return candidate
    }
    return -1
}

/**
 * Whether the character at [offset] is text a mouse over it shows the I-beam: not an inline box
 * (a checkbox, a chip, a summary's triangle), not the padding an inline code's edges stand for,
 * and not a link, which has the hand.
 */
internal fun AnnotatedString.isTextRun(offset: Int): Boolean {
    if (offset < 0 || offset >= length) return false
    val end = offset + 1
    if (getStringAnnotations(INLINE_CONTENT, offset, end).isNotEmpty()) return false
    if (getStringAnnotations(CHROME_INLINE, offset, end).any { it.item.startsWith(ATOMIC) }) return false
    if (getLinkAnnotations(offset, end).isNotEmpty()) return false
    // `PpmFonts.codeEdge`'s one glyph is private-use; its spans say which of those it is.
    if (text[offset].category == CharCategory.PRIVATE_USE && spanStyles.any { it.item.fontFamily == PpmFonts.codeEdge && offset >= it.start && offset < it.end }) return false
    return true
}

/**
 * Whether [y] is in the box Chrome gives a text fragment of [px] px in [face] on a line whose
 * baseline is at [baseline]: the font's ascent above it and its descent below, not the line's.
 */
internal fun overFragment(y: Float, baseline: Float, px: Float, face: ChromeFace): Boolean {
    val (up, down) = PpmFonts.chromeAscentDescent(px, face.ascent, face.descent)
    return y >= baseline - up && y < baseline + down
}

/** The face Chrome sizes text set in [family] from: Monaspace, the system's sans-serif, else Geist. */
internal fun chromeFaceOf(family: FontFamily?): ChromeFace = when (family) {
    PpmFonts.mono -> ChromeFace.Mono
    FontFamily.SansSerif -> PpmFonts.systemAscentDescent.let { (ascent, descent) -> ChromeFace(ascent, descent) }
    else -> ChromeFace.Sans
}
