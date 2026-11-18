package com.hienlh.ppm.app.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.AssistantCard
import com.hienlh.ppm.state.assistant.ManagerDot

/** Where a card's Reply goes: the Assistant's reply bar, or one under the card in a manager chat (`CardReplyHost`). */
class CardReplyHost(val inManagerChat: Boolean, val replyTo: (AssistantCard) -> Unit)

val LocalCardReplyHost = compositionLocalOf<CardReplyHost?> { null }

/** The red count on chips, filter rows and the waiting chip (`WaitingBadge`). */
@Composable
fun WaitingBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    val tokens = LocalPpmTokens.current
    Box(
        modifier
            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
            .background(tokens.error.color, RoundedCornerShape(999.dp))
            .padding(horizontal = 5.dp)
            .testTag("assistant-waiting-badge"),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(if (count > 99) "99+" else "$count", style = ppmText(10.5f, FontWeight.SemiBold).copy(color = Color.White))
    }
}

/** A manager chat's state as a dot (`ManagerDot`): working, waiting on you, idle. */
@Composable
fun ManagerDotView(state: ManagerDot) {
    val tokens = LocalPpmTokens.current
    val color = when (state) {
        ManagerDot.RUNNING -> tokens.success
        ManagerDot.NEEDS -> tokens.error
        ManagerDot.IDLE -> tokens.text3
    }
    Box(Modifier.size(8.dp).background(color.color, CircleShape))
}

enum class CardButtonVariant { PRIMARY, DEFAULT, DANGER, GHOST }

/** A card's button (`CardButton`): 44 dp on a phone, 32 dp on a tablet. */
@Composable
fun CardButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ProductIcon? = null,
    variant: CardButtonVariant = CardButtonVariant.DEFAULT,
    enabled: Boolean = true,
    secondary: Boolean = false,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val shape = RoundedCornerShape(if (mobile) 8.dp else 6.dp)
    val (fill, ink, border) = when (variant) {
        CardButtonVariant.PRIMARY -> Triple(tokens.accent, tokens.accentFg, null)
        CardButtonVariant.DEFAULT -> Triple(tokens.panel, tokens.text, tokens.border)
        CardButtonVariant.DANGER -> Triple(null, tokens.error, tokens.error.opacity(0.4))
        CardButtonVariant.GHOST -> Triple(null, if (secondary) tokens.text2 else tokens.text, null)
    }
    Row(
        modifier
            .height(if (mobile) 44.dp else 32.dp)
            .then(if (fill != null) Modifier.background(fill.color, shape) else Modifier)
            .then(if (border != null) Modifier.cssBorder(border.color, shape) else Modifier)
            .ppmClickable(enabled = enabled, shape = shape, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = if (secondary && !mobile) 8.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) PpmIcon(icon, 14.dp, ink.color)
        BasicText(
            label,
            style = ppmText(if (mobile) (if (secondary) 13f else 14f) else 12f, FontWeight.Medium).copy(color = ink.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A pill of the head row: tinted, with an icon and a label (`TONE_CHIP`). */
@Composable
fun TonePill(label: String, icon: ProductIcon, fill: Rgba, ink: Rgba) {
    Row(
        Modifier.background(fill.color, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PpmIcon(icon, 14.dp, ink.color)
        BasicText(label, style = ppmText(12f, FontWeight.SemiBold).copy(color = ink.color), maxLines = 1)
    }
}

/** A project's name as a neutral chip. */
@Composable
fun ProjectPill(name: String, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    BasicText(
        name,
        modifier
            .background(tokens.panel2.color, RoundedCornerShape(999.dp))
            .cssBorder(tokens.border.color, RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text.color),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A 44 dp (32 dp on a tablet) icon button of the Assistant's head. */
@Composable
fun HeadIconButton(icon: ProductIcon, label: String, onClick: () -> Unit, tag: String? = null) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    Box(
        Modifier
            .size(if (mobile) 44.dp else 32.dp)
            .ppmClickable(shape = RoundedCornerShape(8.dp), onClick = onClick)
            .semantics { contentDescription = label }
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(icon, if (mobile) 18.dp else 16.dp, tokens.text2.color)
    }
}

/** A row of buttons, spaced as the card's action row spaces them. */
@Composable
fun ButtonRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

/**
 * A kit [Sheet] in a window of its own, so a card, a chip or a menu row can open one from
 * wherever it sits: a bottom sheet on a phone, a centred card on a tablet.
 */
@Composable
fun AssistantSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    tabletAlignment: Alignment = Alignment.Center,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        androidx.compose.runtime.LaunchedEffect(window) { window?.setDimAmount(0f) }
        com.hienlh.ppm.design.kit.Sheet(
            title = title,
            onDismiss = onDismiss,
            modifier = modifier,
            subtitle = subtitle,
            tabletAlignment = tabletAlignment,
            footer = footer,
            content = content,
        )
    }
}
