package com.hienlh.ppm.design

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A text field's caret as Chrome paints it, which Compose's cannot be made to match: in the text's
 * own colour (`caret-color: auto`), one CSS pixel wide and centred on the gap it marks, its left
 * edge rounded to a whole pixel and kept on the line and in the box (LayoutNG
 * `ComputeLocalCaretRectAtTextOffset`), and as tall as the text — the font's ascent and descent as
 * Chrome rounds them, under the half-leading floored — not the line. It blinks 500 ms on and 500 ms
 * off, lit again at every edit and move. Compose's is 2dp wide and the line's height, in whatever
 * brush it is handed: the web's dark 2 px caret, 36 px tall in a 14px input at 2x, was a 4 px blue
 * bar 40 px tall.
 *
 * Chrome paints it on whole pixels, both edges rounded from where its text really starts, so at
 * 2.625x its 2.625 px are 2 or 3 wide by that start's fraction of a pixel: 2 in a `px-3` textarea
 * whose text starts at 56.5 px, 3 with the padding made 10px (51.25), measured. Compose puts the
 * field on a whole pixel, so a field gives [rememberChromeCaret] the CSS length before its text,
 * whose fraction is the one Chrome keeps.
 *
 * A field hands its BasicTextField [brush] (transparent, so Compose still scrolls its own caret into
 * view and this one moves with it), [onTextLayout], [scroll] and [interactions], and wraps the inner
 * field its decorator is given in [Modifier.chromeCaret].
 */
@Stable
class ChromeCaret internal constructor(private val state: TextFieldState, private val multiline: Boolean) {
    val interactions = MutableInteractionSource()
    val scroll = ScrollState(0)
    val brush: Brush = SolidColor(Color.Transparent)
    val onTextLayout: Density.(getResult: () -> TextLayoutResult?) -> Unit = { layout = it }

    internal var layout by mutableStateOf<(() -> TextLayoutResult?)?>(null)
    internal var style by mutableStateOf(TextStyle.Default)
    internal var cssStart by mutableStateOf(0.dp)
    internal var shown by mutableStateOf(false)
    internal var lit by mutableStateOf(true)

    /** Where an offset into the field's text is in the text laid out, which a [ChromeTextArea] marks. */
    internal var toLayout: (Int) -> Int = { it }

    /**
     * Draws the caret; given [lineTop] — where a line exactly starts in this box, as a
     * [ChromeTextArea] stacks them — it stands on that line, its edges each rounded.
     */
    internal fun DrawScope.drawCaret(lineTop: ((Int) -> Float)? = null) {
        if (!shown || !lit || !state.selection.collapsed) return
        val result = layout?.invoke() ?: return
        val offset = toLayout(state.selection.start).coerceIn(0, result.layoutInput.text.length)
        val line = result.getLineForOffset(offset)
        val width = 1.dp.toPx()
        val scrolled = scroll.value.toFloat()
        var left = result.getHorizontalPosition(offset, usePrimaryDirection = true) - width / 2
        left = max(left, min(0f, result.getLineLeft(line)))
        left = min(left, max(size.width + if (multiline) 0f else scrolled, result.getLineRight(line)) - width)
        left = floor(left + 0.5f)
        // Where Chrome's text starts, against this field's whole pixel.
        val start = cssStart.toPx() - cssStart.roundToPx()
        val right = floor(start + left + width + 0.5f)
        left = floor(start + left + 0.5f)
        val px = style.fontSize.toPx()
        val (up, down) = if (style.fontFamily == PpmFonts.mono) {
            PpmFonts.chromeAscentDescent(px, PpmFonts.MONO_ASCENT, PpmFonts.MONO_DESCENT)
        } else {
            PpmFonts.chromeAscentDescent(px, PpmFonts.SANS_ASCENT, PpmFonts.SANS_DESCENT)
        }
        val lineHeight = if (style.lineHeight.isSpecified) style.lineHeight.toPx() else up + down
        if (lineTop != null) {
            val exact = lineTop(line) + floor((lineHeight - up - down) / 2)
            val y = floor(exact + 0.5f)
            clipRect { drawRect(style.color, Offset(left, y), Size(right - left, floor(exact + up + down + 0.5f) - y)) }
            return
        }
        val top = result.getLineTop(line) + floor((lineHeight - up - down) / 2)
        val origin = if (multiline) Offset(left, top - scrolled) else Offset(left - scrolled, top)
        clipRect { drawRect(style.color, origin, Size(right - left, up + down)) }
    }
}

/**
 * The caret of the field editing [state] in [style] (its colour the text's), [multiline] for a
 * textarea. [cssStart] is the CSS length from a whole pixel to where the text starts (the field's
 * padding, or a row's padding, icon and gap before it); only its fraction of a pixel counts.
 */
@Composable
fun rememberChromeCaret(state: TextFieldState, style: TextStyle, multiline: Boolean = false, cssStart: Dp = 0.dp): ChromeCaret {
    val caret = remember(state, multiline) { ChromeCaret(state, multiline) }
    val focused by caret.interactions.collectIsFocusedAsState()
    val window = LocalWindowInfo.current
    SideEffect {
        caret.style = style
        caret.cssStart = cssStart
    }
    LaunchedEffect(caret) {
        snapshotFlow { Triple(focused && window.isWindowFocused, state.selection, state.text.toString()) }.collectLatest { (shown) ->
            caret.shown = shown
            caret.lit = true
            while (shown) {
                delay(BLINK_MS)
                caret.lit = !caret.lit
            }
        }
    }
    return caret
}

/** Draws [caret] over the inner text field this wraps, in that field's own coordinates. */
fun Modifier.chromeCaret(caret: ChromeCaret): Modifier = drawWithContent {
    drawContent()
    with(caret) { drawCaret() }
}

/** Chrome's caret blink, half of `LayoutTheme::CaretBlinkInterval`'s second on Android. */
private const val BLINK_MS = 500L
