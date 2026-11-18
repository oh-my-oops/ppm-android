package com.hienlh.ppm.app.ui.assistant

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.assistant
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.kit.DrawerToolTileItem
import com.hienlh.ppm.design.kit.HoverTooltip
import com.hienlh.ppm.design.ppmClickable

/** What waits on the user across every project; 0 before the Assistant is read. */
@Composable
fun assistantWaitingCount(model: AppModel): Int {
    val store = model.assistant.store ?: return 0
    return store.view.collectAsState().value.waiting.total
}

/** The rail's "PPM Assistant" (`nav-section-rail.tsx`): a window in front minimizes, else the Assistant comes forward. */
@Composable
fun AssistantRailButton(model: AppModel) {
    val tokens = LocalPpmTokens.current
    val waiting = assistantWaitingCount(model)
    val label = if (waiting > 0) "PPM Assistant · $waiting waiting" else "PPM Assistant"
    val shape = RoundedCornerShape(10.dp)
    HoverTooltip(label = label) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(shape)
                    .semantics { contentDescription = label }
                    .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, shape = shape) { model.assistant.toggle() }
                    .testTag("rail-assistant"),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.BotMessageSquare, 18.dp, tokens.text.color)
            }
            if (waiting > 0) WaitingBadge(waiting, Modifier.align(Alignment.TopEnd).offset((-2).dp, 2.dp))
        }
    }
}

/** The phone drawer's "Assistant" tile, its subtitle what waits on the user. */
@Composable
fun assistantDrawerTile(model: AppModel, onClick: () -> Unit): DrawerToolTileItem {
    val tokens = LocalPpmTokens.current
    val waiting = assistantWaitingCount(model)
    return DrawerToolTileItem(
        id = "assistant",
        title = "Assistant",
        subtitle = if (waiting > 0) "$waiting waiting" else "PPM",
        icon = ProductIcon.BotMessageSquare,
        dotColor = if (waiting > 0) tokens.error.color else null,
        subtitleColor = if (waiting > 0) tokens.error.color else null,
        onClick = onClick,
    )
}
