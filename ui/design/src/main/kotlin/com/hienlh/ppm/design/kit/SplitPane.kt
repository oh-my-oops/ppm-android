package com.hienlh.ppm.design.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.cssBorder

/**
 * State holder for a resizable [SplitPane] (Foundation Section 1.5 & 7).
 */
@Stable
class SplitPaneState(
    initialFraction: Float = 0.5f,
    val defaultFraction: Float = initialFraction,
    val minFraction: Float = 0.2f,
    val maxFraction: Float = 0.8f,
) {
    var fraction: Float by mutableFloatStateOf(initialFraction.coerceIn(minFraction, maxFraction))
        private set

    fun snapTo(nextFraction: Float) {
        fraction = nextFraction.coerceIn(minFraction, maxFraction)
    }

    fun dragBy(deltaPx: Float, totalPx: Float) {
        if (totalPx <= 0f) return
        snapTo(fraction + deltaPx / totalPx)
    }

    fun reset() {
        snapTo(defaultFraction)
    }
}

@Composable
fun rememberSplitPaneState(
    initialFraction: Float = 0.5f,
    minFraction: Float = 0.2f,
    maxFraction: Float = 0.8f,
): SplitPaneState = remember(minFraction, maxFraction) {
    SplitPaneState(
        initialFraction = initialFraction,
        defaultFraction = initialFraction,
        minFraction = minFraction,
        maxFraction = maxFraction,
    )
}

/**
 * Resizable two-pane layout with a 12 dp touch/pointer handle, 2 dp visual divider line, and
 * 6 x 40 dp center pill grip (Foundation Sections 1.5, 5 & 7).
 *
 * Double-tapping / double-clicking the divider resets [state] to its default ratio (50 / 50).
 */
@Composable
fun SplitPane(
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    state: SplitPaneState = rememberSplitPaneState(),
    vertical: Boolean = false,
) {
    val tokens = LocalPpmTokens.current
    val density = LocalDensity.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val active = hovered || dragging
    val lineColor = if (active) tokens.accent.color else tokens.border.color
    val gripShape = RoundedCornerShape(999.dp)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val totalSpanPx = with(density) { (if (vertical) maxHeight else maxWidth).toPx() }
        val f = state.fraction.coerceIn(state.minFraction, state.maxFraction)

        if (!vertical) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(f)
                        .fillMaxHeight(),
                ) {
                    first()
                }

                // 12 dp wide drag handle with 2 dp line and 6 x 40 dp pill grip
                Box(
                    modifier = Modifier
                        .width(KitSpec.SplitHandleHitSize)
                        .fillMaxHeight()
                        .hoverable(interactionSource)
                        .pointerInput(state) {
                            detectTapGestures(onDoubleTap = { state.reset() })
                        }
                        .pointerInput(state, totalSpanPx) {
                            detectDragGestures(
                                onDragStart = { dragging = true },
                                onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    state.dragBy(dragAmount.x, totalSpanPx)
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(KitSpec.SplitLineThickness)
                            .fillMaxHeight()
                            .background(lineColor),
                    )
                    Box(
                        modifier = Modifier
                            .size(width = KitSpec.SplitGripShort, height = KitSpec.SplitGripLong)
                            .background(tokens.panel2.color, gripShape)
                            .cssBorder(lineColor, gripShape, width = 1.dp),
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f - f)
                        .fillMaxHeight(),
                ) {
                    second()
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(f)
                        .fillMaxWidth(),
                ) {
                    first()
                }

                // 12 dp tall drag handle with 2 dp line and 40 x 6 dp pill grip
                Box(
                    modifier = Modifier
                        .height(KitSpec.SplitHandleHitSize)
                        .fillMaxWidth()
                        .hoverable(interactionSource)
                        .pointerInput(state) {
                            detectTapGestures(onDoubleTap = { state.reset() })
                        }
                        .pointerInput(state, totalSpanPx) {
                            detectDragGestures(
                                onDragStart = { dragging = true },
                                onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    state.dragBy(dragAmount.y, totalSpanPx)
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .height(KitSpec.SplitLineThickness)
                            .fillMaxWidth()
                            .background(lineColor),
                    )
                    Box(
                        modifier = Modifier
                            .size(width = KitSpec.SplitGripLong, height = KitSpec.SplitGripShort)
                            .background(tokens.panel2.color, gripShape)
                            .cssBorder(lineColor, gripShape, width = 1.dp),
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f - f)
                        .fillMaxWidth(),
                ) {
                    second()
                }
            }
        }
    }
}
