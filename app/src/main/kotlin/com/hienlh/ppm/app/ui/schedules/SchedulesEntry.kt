package com.hienlh.ppm.app.ui.schedules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.AppSchedules
import com.hienlh.ppm.app.schedules
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.kit.DrawerToolTileItem
import com.hienlh.ppm.design.kit.HoverTooltip
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.schedules.RailSummary
import com.hienlh.ppm.state.schedules.railLabel
import com.hienlh.ppm.state.schedules.railSummary
import com.hienlh.ppm.state.windows.WindowVisualState
import com.hienlh.ppm.state.workspace.isWindowPanelId
import com.hienlh.ppm.state.workspace.windowIdFromPanelId

/**
 * Keeps the shared list read while the caller is shown (`useSchedulesRailSummary`) and answers its
 * numbers: what runs in the next 24 h and the problems not dismissed.
 */
@Composable
fun schedulesRailSummary(model: AppModel, enabled: Boolean = true): RailSummary {
    val sched = model.schedules
    LaunchedEffect(sched, model.events) { sched.bindEvents(model.events) }
    DisposableEffect(sched, enabled) {
        val release = if (enabled) sched.use() else null
        onDispose { release?.invoke() }
    }
    val state = sched.feed?.state?.collectAsState()?.value ?: return RailSummary(0, 0)
    return railSummary(state.list, state.now.takeIf { it > 0 } ?: System.currentTimeMillis())
}

/** Whether the Schedules window is on screen (not minimized): the rail button is lit. */
private fun schedulesShown(model: AppModel): Boolean {
    val panel = model.layout?.panels?.values?.firstOrNull { p -> p.tabs.any { it.id == AppSchedules.TAB_ID } } ?: return false
    if (!isWindowPanelId(panel.id)) return false
    val id = windowIdFromPanelId(panel.id) ?: return false
    val win = model.windowState.windows[id] ?: return false
    return win.state != WindowVisualState.Minimized
}

/**
 * The rail's Schedules button (`nav-section-rail.tsx`): the count of runs in the next 24 h in the
 * accent colour in one corner, a red dot in the other when something needs a look.
 */
@Composable
fun SchedulesRailButton(model: AppModel) {
    val tokens = LocalPpmTokens.current
    val summary = schedulesRailSummary(model, enabled = model.info?.minimalMode != true)
    val label = railLabel(summary)
    val active = schedulesShown(model)
    val shape = RoundedCornerShape(10.dp)
    HoverTooltip(label = label) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(shape)
                    .background(if (active) tokens.accentWash.color else androidx.compose.ui.graphics.Color.Transparent)
                    .semantics { contentDescription = label }
                    .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, shape = shape) { model.schedules.open() }
                    .testTag("rail-schedules"),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.CalendarClock, 18.dp, if (active) tokens.accent.color else tokens.text.color)
                if (summary.attention > 0) {
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(bottom = 5.dp, end = 5.dp).size(8.dp).clip(CircleShape)
                            .background(tokens.error.color).testTag("rail-schedules-dot"),
                    )
                }
            }
            if (summary.upcoming > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset((-2).dp, 2.dp)
                        .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                        .background(tokens.accent.color, RoundedCornerShape(999.dp))
                        .padding(horizontal = 4.dp)
                        .testTag("rail-schedules-count"),
                    contentAlignment = Alignment.Center,
                ) {
                    val n = summary.upcoming
                    BasicText(if (n > 99) "99+" else "$n", style = ppmText(9.5f, FontWeight.SemiBold).copy(color = tokens.accentFg.color))
                }
            }
        }
    }
}

/** The phone drawer's Schedules tile (`mobile-drawer.tsx`): its runs in the next 24 h, red when something needs a look. */
@Composable
fun schedulesDrawerTile(model: AppModel, onClick: () -> Unit): DrawerToolTileItem {
    val tokens = LocalPpmTokens.current
    val summary = schedulesRailSummary(model, enabled = model.info?.minimalMode != true)
    val attention = summary.attention
    return DrawerToolTileItem(
        id = "schedules",
        title = "Schedules",
        subtitle = if (attention > 0) "$attention need${if (attention == 1) "s" else ""} attention" else "${summary.upcoming} in 24 h",
        icon = ProductIcon.CalendarClock,
        dotColor = if (attention > 0) tokens.error.color else null,
        subtitleColor = if (attention > 0) tokens.error.color else null,
        onClick = onClick,
    )
}
