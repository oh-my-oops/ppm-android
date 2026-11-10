package com.hienlh.ppm.design.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import kotlinx.coroutines.delay

/**
 * Mouse/trackpad-only hover tooltip (Foundation Sections 5 & 7, Requirement 16.2).
 *
 * Appears after [delayMs] (default 500 ms) of mouse hover, rendered as a 30 dp inverse-surface pill
 * with 8 dp corner radius, 12.5 sp label, and optional monospace [shortcut] badge. Never triggers
 * or sticks on touch input.
 */
@Composable
fun HoverTooltip(
    label: String,
    modifier: Modifier = Modifier,
    shortcut: String? = null,
    enabled: Boolean = true,
    delayMs: Long = KitSpec.HOVER_TOOLTIP_DELAY_MS,
    content: @Composable () -> Unit,
) {
    var mouseHovered by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    LaunchedEffect(mouseHovered, enabled, label, delayMs) {
        if (mouseHovered && enabled && label.isNotEmpty()) {
            delay(delayMs)
            visible = true
        } else {
            visible = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            mouseHovered = false
            visible = false
        }
    }

    Box(
        modifier = modifier.pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val pointerType = event.changes.firstOrNull()?.type
                    if (pointerType == PointerType.Touch) {
                        mouseHovered = false
                        visible = false
                        continue
                    }
                    when (event.type) {
                        PointerEventType.Enter -> mouseHovered = true
                        PointerEventType.Exit, PointerEventType.Press -> {
                            mouseHovered = false
                            visible = false
                        }
                    }
                }
            }
        },
    ) {
        content()

        if (visible && enabled && label.isNotEmpty()) {
            val offsetY = with(density) { (-36).dp.roundToPx() }
            Popup(
                alignment = Alignment.TopCenter,
                offset = IntOffset(0, offsetY),
                properties = PopupProperties(focusable = false),
            ) {
                HoverTooltipPill(label = label, shortcut = shortcut)
            }
        }
    }
}

/**
 * Visual 30 dp inverse-surface pill of [HoverTooltip], usable standalone in previews.
 */
@Composable
fun HoverTooltipPill(
    label: String,
    modifier: Modifier = Modifier,
    shortcut: String? = null,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(KitSpec.HoverTooltipRadius)
    val kbdShape = RoundedCornerShape(4.dp)
    val bgColor = tokens.text.color
    val fgColor = tokens.panel2.color

    Row(
        modifier = modifier
            .height(KitSpec.HoverTooltipHeight)
            .ppmShadow(tokens.shadowFloat, shape)
            .clip(shape)
            .background(bgColor, shape)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            text = label,
            style = ppmText(12.5f, FontWeight.Medium, lineHeight = 16f).copy(color = fgColor),
            maxLines = 1,
        )
        if (!shortcut.isNullOrEmpty()) {
            Box(
                modifier = Modifier
                    .background(fgColor.copy(alpha = 0.16f), kbdShape)
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = shortcut,
                    style = ppmText(11f, FontWeight.Normal, FontKind.MONO, lineHeight = 13f)
                        .copy(color = fgColor.copy(alpha = 0.88f)),
                    maxLines = 1,
                )
            }
        }
    }
}
