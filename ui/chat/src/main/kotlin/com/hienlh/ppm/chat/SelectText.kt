package com.hienlh.ppm.chat

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Selecting part of an answer. A user's message, a tool's output and an error are BasicText in a
 * SelectionContainer and select in place, as on the web. An answer's markdown cannot: ChromeText
 * lays it out and draws it itself to sit on Chrome's pixels, and a SelectionContainer only selects
 * the text of a BasicText (its registrar is internal to Compose). So a long press on markdown opens
 * the block in [SelectTextDialog], where the same markdown is laid out by BasicText
 * ([LocalPlainTextLayout]) and selects with the platform's handles and Copy.
 */

/** What a long press on a markdown block asks for: that block's text in [SelectTextDialog]. Null where no host shows it. */
val LocalSelectText = compositionLocalOf<((String) -> Unit)?> { null }

/** Markdown laid out by BasicText rather than ChromeText, so a SelectionContainer around it selects it. */
internal val LocalPlainTextLayout = staticCompositionLocalOf { false }

/**
 * A long press that leaves everything else alone: no event is consumed until the press has lasted
 * the long-press timeout without moving past the touch slop, so a tap on a link, a scroll and a
 * button inside go on as before; a press a child took (a button's) does not start one.
 */
internal fun Modifier.longPressToSelect(text: String, onSelect: (String) -> Unit): Modifier = pointerInput(text, onSelect) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        val endedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var ended = false
            while (!ended) {
                val change = awaitPointerEvent(PointerEventPass.Main).changes.firstOrNull { it.id == down.id }
                ended = change == null || !change.pressed || change.isConsumed ||
                    (change.position - down.position).getDistance() > viewConfiguration.touchSlop
            }
            true
        }
        if (endedEarly != null) return@awaitEachGesture
        onSelect(text)
        // The rest of this press is the long press's: its release clicks nothing under it.
        do {
            val event = awaitPointerEvent(PointerEventPass.Main)
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * A markdown block, selectable: the same markdown laid out by BasicText in a SelectionContainer,
 * with "Copy all" for the whole block. Over the whole window, as a dialog.
 */
@Composable
fun SelectTextDialog(text: String, onDismiss: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val dismiss by rememberUpdatedState(onDismiss)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .ppmClickable(pressedColor = Color.Transparent, onClick = dismiss)
                .windowInsetsPadding(WindowInsets.navigationBars),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .clip(shape)
                    .background(tokens.panel2.color)
                    .cssBorder(tokens.border.color, shape)
                    // Taps inside stay inside: only the dim area around closes it.
                    .ppmClickable(pressedColor = Color.Transparent) {}
                    .testTag("select-text-dialog"),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BasicText(
                        "Select text",
                        Modifier.weight(1f),
                        style = ppmText(15f, FontWeight.SemiBold, lineHeight = 20f).copy(color = tokens.text.color),
                    )
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .ppmClickable(pressedColor = tokens.panel.color, onClickLabel = "Copy all") {
                                scope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", text)))
                                    copied = true
                                    delay(1_500)
                                    copied = false
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PpmIcon(if (copied) ProductIcon.Check else ProductIcon.Copy, 15.dp, tokens.text2.color)
                        BasicText(
                            if (copied) "Copied" else "Copy all",
                            style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.text2.color),
                        )
                    }
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .ppmClickable(pressedColor = tokens.panel.color, onClickLabel = "Close", onClick = dismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        PpmIcon(ProductIcon.X, 16.dp, tokens.text2.color)
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                ) {
                    WithoutOnCopy(MARKDOWN_MARKS) {
                        SelectionContainer(Modifier.testTag("select-text-body")) {
                            androidx.compose.runtime.CompositionLocalProvider(LocalPlainTextLayout provides true) {
                                MarkdownContent(text)
                            }
                        }
                    }
                }
            }
        }
    }
}
