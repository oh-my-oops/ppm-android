package com.hienlh.ppm.design.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import kotlin.math.roundToInt

/**
 * Adaptive sheet container (Foundation Sections 1.3, 3.6 & 7):
 * - Under 600 dp (or when [forceBottomSheet] is true): bottom sheet with 24 dp top corners,
 *   36 x 4 dp drag handle, 56 dp header row (17 sp SemiBold title, optional 48 dp Back button for
 *   single-step drill-in, 48 dp close button), and optional footer with 48 dp buttons.
 * - From 600 dp up: 400 dp wide anchored / side card with 20 dp corners and 52 dp header row
 *   (15 sp SemiBold title).
 */
@Composable
fun Sheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    forceBottomSheet: Boolean = false,
    tabletAlignment: Alignment = Alignment.Center,
    tabletWidth: Dp = KitSpec.SheetTabletWidth,
    showScrim: Boolean = true,
    applyWindowInsets: Boolean = true,
    headerActions: (@Composable RowScope.() -> Unit)? = null,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // A sheet opened inside a page leaves the strip above it (app bar, status bar) to the shell's layer.
    val scrimLayer = if (showScrim) LocalSheetScrimLayer.current else null
    val currentDismiss by rememberUpdatedState(onDismiss)
    val aboveEntry = remember(scrimLayer) { scrimLayer?.let { SheetScrimLayer.Entry { currentDismiss() } } }
    DisposableEffect(aboveEntry) {
        if (aboveEntry != null) scrimLayer?.add(aboveEntry)
        onDispose { if (aboveEntry != null) scrimLayer?.remove(aboveEntry) }
    }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .then(if (aboveEntry != null) Modifier.onGloballyPositioned { aboveEntry.topPx = it.positionInWindow().y } else Modifier)
            .then(
                if (applyWindowInsets) {
                    Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                } else {
                    Modifier
                },
            ),
    ) {
        val isPhoneSheet = forceBottomSheet || maxWidth < KitSpec.TabletBreakpoint
        val maxSheetHeight = maxHeight * 0.88f

        if (showScrim) {
            val scrimAlpha = if (isPhoneSheet) 0.52f else 0.36f
            if (aboveEntry != null) SideEffect { aboveEntry.alpha = scrimAlpha }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0f, 0f, 0f, scrimAlpha))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "Dismiss sheet",
                        onClick = onDismiss,
                    ),
            )
        }

        if (isPhoneSheet) {
            var dragOffsetPx by remember { mutableFloatStateOf(0f) }
            SheetSurface(
                title = title,
                subtitle = subtitle,
                onDismiss = onDismiss,
                onBack = onBack,
                isPhone = true,
                headerActions = headerActions,
                footer = footer,
                content = content,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = maxSheetHeight)
                    .offset { IntOffset(0, dragOffsetPx.roundToInt().coerceAtLeast(0)) }
                    .pointerInput(onDismiss) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (dragOffsetPx > 64.dp.toPx()) onDismiss()
                                dragOffsetPx = 0f
                            },
                            onDragCancel = { dragOffsetPx = 0f },
                            onVerticalDrag = { _, dragAmount ->
                                dragOffsetPx = (dragOffsetPx + dragAmount).coerceAtLeast(0f)
                            },
                        )
                    },
            )
        } else {
            SheetSurface(
                title = title,
                subtitle = subtitle,
                onDismiss = onDismiss,
                onBack = onBack,
                isPhone = false,
                headerActions = headerActions,
                footer = footer,
                content = content,
                modifier = Modifier
                    .align(tabletAlignment)
                    .padding(16.dp)
                    .width(tabletWidth.coerceAtMost(maxWidth - 32.dp))
                    .heightIn(max = maxSheetHeight.coerceAtMost(560.dp)),
            )
        }
    }
}

/**
 * The inner visual surface of a [Sheet], usable standalone in previews or inline layouts.
 */
@Composable
fun SheetSurface(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    isPhone: Boolean = true,
    headerActions: (@Composable RowScope.() -> Unit)? = null,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = if (isPhone) {
        RoundedCornerShape(
            topStart = KitSpec.SheetPhoneCornerRadius,
            topEnd = KitSpec.SheetPhoneCornerRadius,
            bottomStart = 0.dp,
            bottomEnd = 0.dp,
        )
    } else {
        RoundedCornerShape(KitSpec.SheetTabletCornerRadius)
    }
    val headerHeight = if (isPhone) KitSpec.SheetPhoneHeaderHeight else KitSpec.SheetTabletHeaderHeight
    val titleFontSize = if (isPhone) 17f else 15f
    val buttonShape = RoundedCornerShape(12.dp)

    Column(
        modifier = modifier
            .ppmShadow(tokens.shadowFloat, shape)
            .clip(shape)
            .background(tokens.panel2.color, shape)
            .cssBorder(tokens.border.color, shape, width = 1.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        // Grab handle on phone bottom sheet (36 x 4 dp)
        if (isPhone) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = KitSpec.SheetHandleWidth, height = KitSpec.SheetHandleHeight)
                        .background(tokens.border.color, RoundedCornerShape(999.dp)),
                )
            }
        }

        // Header row (56 dp phone / 52 dp tablet)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(headerHeight)
                .cssBorder(tokens.borderSoft.color, width = 1.dp, sides = CssSides.Bottom)
                .padding(
                    start = if (onBack != null) 6.dp else if (isPhone) 20.dp else 16.dp,
                    end = 6.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (onBack != null) {
                Box(
                    modifier = Modifier
                        .size(KitSpec.MinTouchTarget)
                        .clip(buttonShape)
                        .ppmClickable(
                            shape = buttonShape,
                            hoverColor = tokens.surfaceHover.color,
                            onClickLabel = "Back",
                            onClick = onBack,
                        )
                        .semantics { contentDescription = "Back" },
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(
                        icon = ProductIcon.ArrowLeft,
                        size = 20.dp,
                        tint = tokens.text2.color,
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                BasicText(
                    text = title,
                    style = ppmText(titleFontSize, FontWeight.SemiBold, lineHeight = titleFontSize + 4f)
                        .copy(color = tokens.text.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrEmpty()) {
                    BasicText(
                        text = subtitle,
                        style = ppmText(12f, FontWeight.Normal, lineHeight = 15f).copy(color = tokens.text2.color),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (headerActions != null) {
                headerActions()
            }

            Box(
                modifier = Modifier
                    .size(KitSpec.MinTouchTarget)
                    .clip(buttonShape)
                    .ppmClickable(
                        shape = buttonShape,
                        hoverColor = tokens.surfaceHover.color,
                        onClickLabel = "Close",
                        onClick = onDismiss,
                    )
                    .semantics { contentDescription = "Close" },
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(
                    icon = ProductIcon.X,
                    size = if (isPhone) 20.dp else 18.dp,
                    tint = tokens.text2.color,
                )
            }
        }

        // Body content
        Column(
            modifier = Modifier.weight(1f, fill = false),
            content = content,
        )

        // Optional footer with 48 dp actions
        if (footer != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .cssBorder(tokens.borderSoft.color, width = 1.dp, sides = CssSides.Top)
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = footer,
            )
        }
    }
}
