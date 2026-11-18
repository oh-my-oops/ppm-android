package com.hienlh.ppm.app.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.hienlh.ppm.app.ui.BottomSheet
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssScrollY
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.design.DesignDeviceFrame
import com.hienlh.ppm.state.design.DesignPane
import com.hienlh.ppm.state.design.DesignVariant
import com.hienlh.ppm.state.design.variantDisplayName

/**
 * Phone: the thumb-zone bar under the single pane (`DesignMobilePaneBar`, design-mobile-layout.tsx)
 * — the canvas, the chat, or the canvas's More sheet. Each is a 44px column of icon over label.
 */
@Composable
internal fun DesignPhonePaneBar(pane: DesignPane, moreOpen: Boolean, onPaneChange: (DesignPane) -> Unit, onMore: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, sides = CssSides.Top)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("design-phone-pane-bar"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PaneButton("Canvas", ProductIcon.Palette, pane == DesignPane.CANVAS, "design-phone-pane-canvas") { onPaneChange(DesignPane.CANVAS) }
        PaneButton("Chat", ProductIcon.MessageSquare, pane == DesignPane.CHAT, "design-phone-pane-chat") { onPaneChange(DesignPane.CHAT) }
        PaneButton("More", ProductIcon.MoreHorizontal, moreOpen, "design-phone-pane-more", onClick = onMore)
    }
}

@Composable
private fun RowScope.PaneButton(label: String, icon: ProductIcon, active: Boolean, tag: String, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val color = if (active) tokens.accent.color else tokens.text3.color
    val shape = RoundedCornerShape(6.dp)
    Column(
        Modifier
            .weight(1f)
            .heightIn(min = 44.dp)
            .clip(shape)
            .ppmClickable(pressedColor = tokens.panel2.color, shape = shape, onClickLabel = label, onClick = onClick)
            .semantics { selected = active }
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        PpmIcon(icon, 20.dp, color)
        BasicText(label, style = ppmText(11f, FontWeight.Medium, lineHeight = 14f).copy(color = color))
    }
}

/** A row of the More sheet: one of the canvas toolbar's buttons (`DESIGN_TOOLBAR_ITEMS`). */
internal data class DesignMoreItem(
    val id: String,
    val label: String,
    val icon: ProductIcon,
    val active: Boolean = false,
    val enabled: Boolean = true,
    val badge: Int = 0,
    val run: () -> Unit,
)

/**
 * The phone's More sheet (`DesignToolbarList`): the device frames as four tiles, the variants when
 * there are two or more, then the canvas toolbar's items as full-width 44px rows. A pick closes
 * the sheet first, then runs.
 */
@Composable
internal fun DesignPhoneMoreSheet(
    frame: DesignDeviceFrame,
    onFrame: (DesignDeviceFrame) -> Unit,
    variants: List<DesignVariant>,
    activeVariant: String,
    onVariant: (String) -> Unit,
    items: List<DesignMoreItem>,
    onDismiss: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    BottomSheet(onDismiss) {
        Column(
            Modifier.fillMaxWidth().cssScrollY().padding(start = 8.dp, end = 8.dp, bottom = 8.dp).testTag("design-phone-more-sheet"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SheetHeading("Device frame")
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (f in DesignDeviceFrame.entries) {
                    val on = frame == f
                    val shape = RoundedCornerShape(6.dp)
                    Column(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp)
                            .clip(shape)
                            .cssBorder((if (on) tokens.accent else tokens.border).color, shape)
                            .ppmClickable(pressedColor = tokens.panel2.color, shape = shape, role = Role.RadioButton, onClickLabel = f.label) {
                                onFrame(f)
                                onDismiss()
                            }
                            .semantics { selected = on }
                            .testTag("design-phone-frame-${f.id}"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    ) {
                        val color = if (on) tokens.text.color else tokens.text3.color
                        PpmIcon(frameIcon(f), 20.dp, color)
                        BasicText(f.label, style = ppmText(12f, lineHeight = 16f).copy(color = color))
                    }
                }
            }
            SheetDividerLine()
            if (variants.size >= 2) {
                SheetHeading("Variant")
                for (v in variants) {
                    val on = v.file == activeVariant
                    MoreRow(variantDisplayName(v), ProductIcon.Layers, enabled = true, tag = "design-phone-variant-${v.file}", onClick = {
                        onVariant(v.file)
                        onDismiss()
                    }) {
                        if (on) PpmIcon(ProductIcon.Check, 16.dp, tokens.accent.color)
                    }
                }
                SheetDividerLine()
            }
            for (item in items) {
                MoreRow(item.label, item.icon, enabled = item.enabled, tag = "design-phone-more-${item.id}", onClick = {
                    onDismiss()
                    item.run()
                }) {
                    if (item.badge > 0) Badge(item.badge)
                }
            }
        }
    }
}

private fun frameIcon(frame: DesignDeviceFrame): ProductIcon = when (frame) {
    DesignDeviceFrame.DESKTOP -> ProductIcon.Monitor
    DesignDeviceFrame.TABLET -> ProductIcon.Tablet
    DesignDeviceFrame.PHONE -> ProductIcon.Smartphone
    DesignDeviceFrame.SLIDE -> ProductIcon.Presentation
}

/** `px-3 pt-1 text-xs font-semibold uppercase tracking-wide text-text-subtle`. */
@Composable
private fun SheetHeading(text: String) {
    val tokens = LocalPpmTokens.current
    BasicText(
        text.uppercase(),
        Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp),
        style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text3.color, letterSpacing = 0.025.em),
    )
}

/** `my-1 h-px bg-border`. */
@Composable
private fun SheetDividerLine() {
    val tokens = LocalPpmTokens.current
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(tokens.border.color))
}

/** `flex min-h-11 w-full items-center gap-3 rounded-md px-3 text-sm disabled:opacity-40`, a 20px subtle icon. */
@Composable
private fun MoreRow(label: String, icon: ProductIcon, enabled: Boolean, tag: String, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .ppmClickable(enabled = enabled, pressedColor = tokens.panel2.color, shape = shape, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 12.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PpmIcon(icon, 20.dp, tokens.text3.color)
        BasicText(
            label,
            Modifier.weight(1f),
            style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing()
    }
}

/** `ToolbarBadge`: `h-4 min-w-4 rounded-full bg-primary px-1 text-[10px] font-semibold`, 99+ past 99. */
@Composable
private fun Badge(count: Int) {
    val tokens = LocalPpmTokens.current
    Box(
        Modifier.heightIn(min = 16.dp).widthIn(min = 16.dp).clip(CircleShape).background(tokens.accent.color).padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(if (count > 99) "99+" else count.toString(), style = ppmText(10f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.accentFg.color))
    }
}

/**
 * What the phone's History, Comments and Export panels sit in: a sheet 70% of the screen high
 * (`h-[70vh]`, a `vh` being everything under the status bar), the panel filling it.
 */
@Composable
internal fun DesignPhonePanelSheet(tag: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val vh = with(density) { (LocalWindowInfo.current.containerSize.height - WindowInsets.statusBars.getTop(density)).toDp() }
    BottomSheet(onDismiss, bottomPadding = WindowInsets.navigationBars) {
        Box(Modifier.fillMaxWidth().height(vh * 0.7f).testTag(tag)) { content() }
    }
}
