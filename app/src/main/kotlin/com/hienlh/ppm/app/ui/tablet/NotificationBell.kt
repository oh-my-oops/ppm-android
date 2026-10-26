package com.hienlh.ppm.app.ui.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.PlacedPopup
import com.hienlh.ppm.app.ui.ShadowLg
import com.hienlh.ppm.app.ui.projectBackground
import com.hienlh.ppm.app.ui.unreadSessions
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ThemeTokens
import com.hienlh.ppm.design.chromeScrollbar
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.SessionUnread

/**
 * The rail's bell (notification-bell-popover.tsx): `size-8 rounded-md`, the count of unread
 * sessions in a red badge over its corner, and the list of them in a popover beside it — its
 * bottom on the bell's, 6 px to the right.
 */
@Composable
internal fun NotificationBell(model: AppModel) {
    val tokens = LocalPpmTokens.current
    val unread = model.unreadSessions()
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    // Lit while open, with no hover of its own then; else `hover:text-foreground hover:bg-surface-elevated`.
    val hover = rememberHover(enabled = !open)
    Box(Modifier.size(32.dp)) {
        Box(
            Modifier
                .size(32.dp)
                .clip(shape)
                .background(if (open) tokens.accent.opacity(0.1).color else Color.Transparent)
                .ppmClickable(
                    pressedColor = if (open) Color.Transparent else tokens.panel2.color,
                    hoverColor = if (open) Color.Transparent else tokens.panel2.color,
                    shape = shape,
                    onClickLabel = "Notifications",
                    source = hover.source,
                ) { open = !open },
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(ProductIcon.Bell, 16.dp, (if (open) tokens.accent else tokens.text.mix(tokens.text3, hover.lit.toDouble())).color)
        }
        if (unread.isNotEmpty()) {
            // `absolute -top-1 -right-1 min-w-[16px] h-4 px-1 rounded-full bg-error text-[9px] font-bold text-white`.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(4.dp, (-4).dp)
                    .height(16.dp)
                    .widthIn(min = 16.dp)
                    .clip(CircleShape)
                    .background(tokens.error.color)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText("${unread.size}", style = ppmText(9f, FontWeight.Bold, lineHeight = 13.5f).copy(color = Color.White))
            }
        }
        if (open) {
            val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
            val place = remember(gap) { { anchor: IntRect, _: IntSize, box: IntSize -> IntOffset(anchor.right + gap, anchor.bottom - box.height) } }
            PlacedPopup(place, onDismiss = { open = false }) { BellPopover(model, unread, tokens) { open = false } }
        }
    }
}

/**
 * `w-72 max-h-80 overflow-y-auto rounded-lg border bg-background shadow-lg`: a head with the
 * count and Clear all, then the sessions under their projects, each tinted by why it is unread.
 */
@Composable
private fun BellPopover(model: AppModel, unread: Map<String, SessionUnread>, tokens: ThemeTokens, close: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val scroll = rememberScrollState()
    Column(
        Modifier
            .width(288.dp)
            .heightIn(max = 320.dp)
            .then(ShadowLg.fold(Modifier as Modifier) { m, s -> m.ppmShadow(s, shape) })
            .chromeScrollbar(scroll, vertical = true, tokens.border.color, border = 1.dp)
            .clip(shape)
            .background(tokens.bgSolid.color)
            .cssBorder(tokens.border.color, shape)
            .verticalScroll(scroll),
    ) {
        Row(
            Modifier.fillMaxWidth().cssBorder(tokens.border.color, sides = CssSides.Bottom).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val count = unread.size
            BasicText(
                if (count > 0) "$count unread session${if (count > 1) "s" else ""}" else "No unread",
                style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
            )
            if (count > 0) {
                val hover = rememberHover()
                val color = tokens.text.mix(tokens.text3, hover.lit.toDouble()).color
                Row(
                    Modifier.ppmClickable(pressedColor = Color.Transparent, source = hover.source) {
                        model.events?.clearAll()
                        close()
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PpmIcon(ProductIcon.BellOff, 12.dp, color)
                    BasicText("Clear all", style = ppmText(10f, lineHeight = 15f).copy(color = color))
                }
            }
        }
        if (unread.isEmpty()) {
            BasicText(
                "All caught up",
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
                style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color, textAlign = TextAlign.Center),
            )
            return@Column
        }
        Column(Modifier.padding(vertical = 4.dp)) {
            val names = model.projects.map { it.name }
            for ((project, sessions) in unread.entries.groupBy { it.value.projectName.ifEmpty { "Unknown" } }) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // The project's colour, or the head's own (`currentColor`) for a project PPM does not know.
                    val index = names.indexOf(project)
                    val known = model.projects.getOrNull(index)
                    val fill = if (known != null) projectBackground(known.color, index) else SolidColor(tokens.text3.color)
                    Box(Modifier.size(8.dp).clip(CircleShape).background(fill))
                    BasicText(
                        project.uppercase(),
                        style = ppmText(10f, FontWeight.Medium, lineHeight = 15f).copy(color = tokens.text3.color, letterSpacing = 0.05.em),
                    )
                }
                for ((sessionId, entry) in sessions) {
                    BasicText(
                        entry.sessionTitle?.takeIf { it.isNotEmpty() } ?: "${sessionId.take(12)}...",
                        Modifier
                            .fillMaxWidth()
                            .background(tint(tokens, entry.type).color)
                            // `hover:bg-surface-elevated` over the tint.
                            .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color) {
                                if (model.openUnreadSession(sessionId, project)) close()
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        style = ppmText(11f, lineHeight = 16.5f).copy(color = tokens.text.color),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** `notificationTint`: an approval's red, a question's amber, a finished turn's accent, at 10 %. */
private fun tint(tokens: ThemeTokens, type: String) = when (type) {
    "question" -> tokens.warning
    "done" -> tokens.accent
    else -> tokens.error
}.opacity(0.1)
