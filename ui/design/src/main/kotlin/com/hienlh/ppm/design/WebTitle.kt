package com.hienlh.ppm.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView

/**
 * The web's `title`. Chrome on Android shows one only to a mouse: while the pointer is over the
 * element it hands the text to the page's view (`View.setTooltipText`), and Android draws its own
 * tooltip under the pointer once it rests there. This hands it to the Compose view the same way,
 * so the tooltip is Android's, drawn and timed as Chrome's (the activity's light theme gives it
 * the same dark box Chrome's has in either system mode). A finger gets nothing, as from Chrome.
 * A title that changes under a pointer that has not moved (a toggle's, as the priority chip's) is
 * the one shown next, as Chrome reads the attribute when its tooltip comes up.
 */
@Composable
fun Modifier.webTitle(title: String): Modifier {
    val view = LocalView.current
    val slot = remember(view) { WebTitleSlot({ view.tooltipText }, { view.tooltipText = it }) }
    val text by rememberUpdatedState(title)
    LaunchedEffect(slot, title) { slot.retitle(title) }
    DisposableEffect(slot) {
        onDispose { slot.exit() }
    }
    return pointerInput(slot) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.firstOrNull()?.type != PointerType.Mouse) continue
                when (event.type) {
                    PointerEventType.Enter -> slot.enter(text)
                    PointerEventType.Exit -> slot.exit()
                }
            }
        }
    }
}

/**
 * What one element has put on the view's tooltip, through [read] and [write]. Only that is ever
 * taken back: the next element the pointer reaches may have set its own before this one's exit
 * arrives, and a title that changed meanwhile must not be mistaken for someone else's, which left
 * the old one on the whole window.
 */
internal class WebTitleSlot(private val read: () -> CharSequence?, private val write: (CharSequence?) -> Unit) {
    private var given: String? = null

    fun enter(title: String) {
        write(title)
        given = title
    }

    /** The element's title is now [title]: shown if the pointer is on it, nothing otherwise. */
    fun retitle(title: String) {
        if (given != null) enter(title)
    }

    fun exit() {
        if (given != null && read()?.toString() == given) write(null)
        given = null
    }
}
