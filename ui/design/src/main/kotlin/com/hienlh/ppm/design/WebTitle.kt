package com.hienlh.ppm.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
 */
@Composable
fun Modifier.webTitle(title: String): Modifier {
    val view = LocalView.current
    val text by rememberUpdatedState(title)
    // Only ever cleared while it is still this element's: the next one may have set its own.
    DisposableEffect(view) {
        onDispose { if (view.tooltipText == text) view.tooltipText = null }
    }
    return pointerInput(view) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.firstOrNull()?.type != PointerType.Mouse) continue
                when (event.type) {
                    PointerEventType.Enter -> view.tooltipText = text
                    PointerEventType.Exit -> if (view.tooltipText == text) view.tooltipText = null
                }
            }
        }
    }
}
