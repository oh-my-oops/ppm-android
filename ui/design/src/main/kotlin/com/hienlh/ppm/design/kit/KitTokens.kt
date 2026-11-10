package com.hienlh.ppm.design.kit

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.cssBorder

/**
 * Shared layout dimensions and touch-target constants from the Foundation design page
 * (`designs/ppm-mobile-tablet-redesign/foundation/index.html`, Section 7).
 */
object KitSpec {
    /** Minimum touch target on phone and tablet (`hit.min` = 48 dp, Requirement 1.3). */
    const val MIN_TOUCH_TARGET_DP: Int = 48
    val MinTouchTarget: Dp = 48.dp
    val HitMin: Dp = 48.dp

    /** Window width breakpoint separating Phone (< 600 dp) and Tablet (>= 600 dp). */
    const val TABLET_BREAKPOINT_DP: Int = 600
    val TabletBreakpoint: Dp = 600.dp

    /** Compact window height threshold (< 480 dp stays on Phone layout). */
    const val COMPACT_HEIGHT_DP: Int = 480
    val CompactHeight: Dp = 480.dp

    // App bar (`appBar`)
    val AppBarHeight: Dp = 56.dp
    val AppBarSlotSize: Dp = 48.dp
    val AppBarIconSize: Dp = 20.dp
    val AppBarBadgeDotSize: Dp = 9.dp
    val AppBarSlotDotSize: Dp = 8.dp
    const val APP_BAR_MAX_ACTIONS: Int = 2

    // Phone drawer (`drawer`)
    val DrawerWidth: Dp = 320.dp
    val DrawerCornerRadius: Dp = 24.dp
    val DrawerProjectCardHeight: Dp = 64.dp
    val DrawerProjectCardRadius: Dp = 16.dp
    val DrawerRowHeight: Dp = 52.dp
    val DrawerRowRadius: Dp = 26.dp
    val DrawerToolTileHeight: Dp = 72.dp
    val DrawerToolTileRadius: Dp = 14.dp
    val DrawerBadgeHeight: Dp = 20.dp

    // Tab switcher (`tabCard`)
    val TabCardMinHeight: Dp = 64.dp
    val TabCardRadius: Dp = 16.dp
    val TabCardIconBoxSize: Dp = 36.dp
    val TabCardIconBoxRadius: Dp = 10.dp
    val NewTabTileHeight: Dp = 64.dp
    val NewTabTileRadius: Dp = 14.dp

    // Bottom action card (`actionCard`)
    val ActionPrimaryHeight: Dp = 48.dp
    val ActionPrimaryPillRadius: Dp = 24.dp

    // Sheets (`sheet`)
    val SheetPhoneCornerRadius: Dp = 24.dp
    val SheetHandleWidth: Dp = 36.dp
    val SheetHandleHeight: Dp = 4.dp
    val SheetPhoneHeaderHeight: Dp = 56.dp
    val SheetTabletWidth: Dp = 400.dp
    val SheetTabletCornerRadius: Dp = 20.dp
    val SheetTabletHeaderHeight: Dp = 52.dp

    // List rows (`row`)
    val RowPhoneOneLineHeight: Dp = 52.dp
    val RowPhoneTwoLineHeight: Dp = 64.dp
    val RowTabletOneLineHeight: Dp = 48.dp
    val RowTabletTwoLineHeight: Dp = 60.dp
    val RowTabletSessionHeight: Dp = 56.dp
    val RowSwipeActionWidth: Dp = 72.dp

    // Chips (`chip`)
    val ChipVisualHeight: Dp = 36.dp
    val ChipHitHeight: Dp = 48.dp

    // Undo bar / snackbar (`snackbar`)
    val UndoBarMinHeight: Dp = 52.dp
    val UndoBarRadius: Dp = 14.dp
    val UndoBarActionHeight: Dp = 40.dp
    const val UNDO_BAR_DURATION_MS: Long = 8_000L
    const val UNDO_BAR_SECONDS: Int = 8

    // Needs-you pill
    val NeedsYouPillVisualHeight: Dp = 36.dp
    val NeedsYouPillHitHeight: Dp = 48.dp

    // Terminal key row (`keyRow`)
    val KeyRowHeight: Dp = 48.dp
    val KeyButtonMinWidth: Dp = 44.dp
    val KeyButtonHeight: Dp = 40.dp
    val KeyButtonGap: Dp = 6.dp
    val KeyButtonRadius: Dp = 10.dp

    // Split pane (`splitPane`)
    val SplitHandleHitSize: Dp = 12.dp
    val SplitLineThickness: Dp = 2.dp
    val SplitGripShort: Dp = 6.dp
    val SplitGripLong: Dp = 40.dp

    // Hover tooltip (`hoverTooltip`)
    val HoverTooltipHeight: Dp = 30.dp
    val HoverTooltipRadius: Dp = 8.dp
    const val HOVER_TOOLTIP_DELAY_MS: Long = 500L
}

/** Top-level minimum touch target constant (48 dp) so screens never hardcode smaller targets. */
val MinTouchTarget: Dp = KitSpec.MinTouchTarget

/** Enforces at least [minWidth] x [minHeight] (both defaulting to 48 dp). */
fun Modifier.minTouchTarget(
    minWidth: Dp = MinTouchTarget,
    minHeight: Dp = MinTouchTarget,
): Modifier = defaultMinSize(minWidth = minWidth, minHeight = minHeight)

/**
 * Keyboard focus ring (Foundation Section 5, Note 29): 2 dp solid accent outline when focused via
 * keyboard/Tab navigation, never sticky on touch.
 */
@Composable
fun Modifier.kitFocusRing(
    focused: Boolean,
    shape: Shape = RoundedCornerShape(8.dp),
    color: Color = LocalPpmTokens.current.accent.color,
): Modifier = if (focused) cssBorder(color = color, shape = shape, width = 2.dp) else this
