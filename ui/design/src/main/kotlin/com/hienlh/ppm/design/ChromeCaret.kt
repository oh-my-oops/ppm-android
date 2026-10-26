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

    private var layout by mutableStateOf<(() -> TextLayoutResult?)?>(null)
    internal var style by mutableStateOf(TextStyle.Default)
    internal var shown by mutableStateOf(false)
    internal var lit by mutableStateOf(true)

    internal fun DrawScope.drawCaret() {
        if (!shown || !lit || !state.selection.collapsed) return
        val result = layout?.invoke() ?: return
        val offset = state.selection.start.coerceIn(0, result.layoutInput.text.length)
        val line = result.getLineForOffset(offset)
        val width = 1.dp.toPx()
        val scrolled = scroll.value.toFloat()
        var left = result.getHorizontalPosition(offset, usePrimaryDirection = true) - width / 2
        left = max(left, min(0f, result.getLineLeft(line)))
        left = min(left, max(size.width + if (multiline) 0f else scrolled, result.getLineRight(line)) - width)
        left = floor(left + 0.5f)
        val px = style.fontSize.toPx()
        val (up, down) = if (style.fontFamily == PpmFonts.mono) {
            PpmFonts.chromeAscentDescent(px, PpmFonts.MONO_ASCENT, PpmFonts.MONO_DESCENT)
        } else {
            PpmFonts.chromeAscentDescent(px, PpmFonts.SANS_ASCENT, PpmFonts.SANS_DESCENT)
        }
        val lineHeight = if (style.lineHeight.isSpecified) style.lineHeight.toPx() else up + down
        val top = result.getLineTop(line) + floor((lineHeight - up - down) / 2)
        val origin = if (multiline) Offset(left, top - scrolled) else Offset(left - scrolled, top)
        clipRect { drawRect(style.color, origin, Size(width, up + down)) }
    }
}

/** The caret of the field editing [state] in [style] (its colour the text's), [multiline] for a textarea. */
@Composable
fun rememberChromeCaret(state: TextFieldState, style: TextStyle, multiline: Boolean = false): ChromeCaret {
    val caret = remember(state, multiline) { ChromeCaret(state, multiline) }
    val focused by caret.interactions.collectIsFocusedAsState()
    val window = LocalWindowInfo.current
    SideEffect { caret.style = style }
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
