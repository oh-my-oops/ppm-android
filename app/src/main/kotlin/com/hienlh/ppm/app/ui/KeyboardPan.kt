package com.hienlh.ppm.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import com.hienlh.ppm.app.TextInputFocus
import kotlin.math.roundToInt

/**
 * A page under the keyboard as Chrome keeps one: the layout keeps its height and the view over it
 * pans up (the visual viewport). The focused field is centred in what the keyboard leaves unless
 * it is all in view already, Blink's `CenterIfNeeded`, and the page goes no further than puts its
 * bottom — the top of the navigation bar, which the keyboard covers too — on the keyboard. So the
 * nav under a message box rides on the keyboard, and a field near the top leaves the page where it
 * is, the keyboard over its bottom. The pan follows the keyboard as it slides, as Chrome's does,
 * and what goes up passes under the status bar, as a page goes under Chrome's toolbar.
 *
 * A terminal's field is its cursor's row, and the pan follows the cursor as it moves: a line typed
 * below the keyboard's edge brings the page up, as Chrome does for xterm's textarea, which stays on
 * the cursor cell. The key row under the terminal is shown too whenever it and the cursor's row fit
 * above the keyboard together; when they do not, the cursor's row is the one that shows ([panFor]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyboardPannedPage(content: @Composable BoxScope.() -> Unit) {
    val density = LocalDensity.current
    PannedPage(
        keyboard = WindowInsets.ime.getBottom(density),
        opening = WindowInsets.imeAnimationTarget.getBottom(density),
        navigationBar = WindowInsets.navigationBars.getBottom(density),
        statusBar = WindowInsets.statusBars.getTop(density),
        content = content,
    )
}

/** [KeyboardPannedPage] with the four insets it reads handed in: the keyboard now, the keyboard it is opening to, and the two bars. */
@Composable
internal fun PannedPage(keyboard: Int, opening: Int, navigationBar: Int, statusBar: Int, content: @Composable BoxScope.() -> Unit) {
    var wanted by remember { mutableIntStateOf(0) }
    val pan = minOf(wanted, maxOf(0, keyboard - navigationBar))
    val panNow by rememberUpdatedState(pan)
    val page = remember { Placed() }
    val field = TextInputFocus.field
    LaunchedEffect(opening, field) {
        // The keyboard going, or the focus between two fields: the pan stays, and follows the
        // keyboard down.
        if (opening == 0 || field == null) return@LaunchedEffect
        val at = page.coordinates ?: return@LaunchedEffect
        val viewBottom = (at.size.height - opening).toFloat()
        val max = maxOf(0, opening - navigationBar)
        // Where the field is now, and again whenever it moves: a terminal's cursor does with every line.
        snapshotFlow { TextInputFocus.panTargetIn(at) }.collect { target ->
            // A field elsewhere (a sheet's) leaves the page where it was.
            wanted = if (target == null) {
                0
            } else {
                panFor(target.field.top, target.field.bottom, target.keep?.top, target.keep?.bottom, statusBar.toFloat(), viewBottom, panNow, max)
            }
        }
    }
    Box(Modifier.fillMaxSize().then(if (pan > 0) Modifier.clip(BelowTop(statusBar.toFloat())) else Modifier)) {
        Box(Modifier.fillMaxSize().offset { IntOffset(0, -pan) }.onPlaced { page.coordinates = it }, content = content)
    }
}

/**
 * Blink's `CenterIfNeeded` for the visual viewport, in the page's pixels: the pan [now] stays
 * while [fieldTop]..[fieldBottom] is all inside [viewTop]..[viewBottom] seen from it, else the
 * field's middle goes to the view's; either way inside 0..[max].
 */
internal fun centredIfNeeded(fieldTop: Float, fieldBottom: Float, viewTop: Float, viewBottom: Float, now: Int, max: Int): Int {
    val inView = fieldTop >= viewTop + now && fieldBottom <= viewBottom + now
    val to = if (inView) now else ((fieldTop + fieldBottom - viewTop - viewBottom) / 2).roundToInt()
    return to.coerceIn(0, max)
}

/**
 * [centredIfNeeded] for a field with something beside it that is worth seeing too: the key row under a terminal's cursor. The two together are
 * what is centred, and the pan is theirs when both are in view at it. They are not when they do not fit the view together, or when the limits of
 * the pan (none, or the page's bottom on the keyboard) stop short of it; then it is the field alone, as for every field with nothing beside it:
 * the cursor's row is the one that must show. [keepTop] and [keepBottom] are both null for no such thing.
 */
internal fun panFor(fieldTop: Float, fieldBottom: Float, keepTop: Float?, keepBottom: Float?, viewTop: Float, viewBottom: Float, now: Int, max: Int): Int {
    if (keepTop != null && keepBottom != null) {
        val top = minOf(fieldTop, keepTop)
        val bottom = maxOf(fieldBottom, keepBottom)
        val pan = centredIfNeeded(top, bottom, viewTop, viewBottom, now, max)
        if (top >= viewTop + pan && bottom <= viewBottom + pan) return pan
    }
    return centredIfNeeded(fieldTop, fieldBottom, viewTop, viewBottom, now, max)
}

/** The page's layout, read when the keyboard comes rather than kept as state. */
private class Placed {
    var coordinates: LayoutCoordinates? = null
}

/** All of the box below [top], the status bar's height. */
private data class BelowTop(private val top: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        Outline.Rectangle(Rect(0f, top, size.width, size.height))
}
