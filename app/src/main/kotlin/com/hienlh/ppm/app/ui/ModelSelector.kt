package com.hienlh.ppm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.design.CssRow
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.WebCursor
import com.hienlh.ppm.design.brightness
import com.hienlh.ppm.design.colorsFade
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssIcon
import com.hienlh.ppm.design.cssLines
import com.hienlh.ppm.design.cssShrink
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.ModelOption
import com.hienlh.ppm.state.ModelNames

/**
 * The web's `ModelSelector` (chat/model-selector.tsx) as a form holds it: the model chip, its name
 * `max-w-[90px]` without "Claude ", and over it the provider's models. Nothing at all while the
 * provider offers one model or none, which is also how it starts, until the list comes.
 *
 * On the web the list is a box inside the form, so the scrolling form clips it; [clip] is that
 * form on screen, and the list is cut where it would be.
 */
@Composable
internal fun ModelSelector(model: AppModel, value: String?, providerId: String, clip: Rect?, onChange: (String) -> Unit) {
    val catalog = model.models ?: return
    LaunchedEffect(catalog, providerId) { catalog.load(providerId) }
    val entries by catalog.entries.collectAsState()
    val models = entries[providerId]?.models.orEmpty()
    var open by remember { mutableStateOf(false) }
    if (models.size <= 1) return
    val current = models.firstOrNull { it.value == value }
    val title = current?.label
    Box {
        FormModelChip(
            current?.let { ModelNames.shortLabel(it.label) } ?: value ?: "Model",
            formModelChipName(title, value),
            // No `title` for a model the provider does not list, as `title={undefined}`.
            modifier = if (title != null) Modifier.webTitle(title) else Modifier,
            labelMax = 90.dp,
        ) { open = !open }
        if (open) {
            ModelPopover(models, value, clip, onDismiss = { open = false }) {
                onChange(it)
                open = false
            }
        }
    }
}

@Composable
private fun FormModelChip(
    text: String,
    name: String,
    modifier: Modifier = Modifier,
    labelMax: Dp = 90.dp,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    val lit = if (pressed) 1f else colorsFade(hovered)
    CssRow(
        modifier
            .clearAndSetSemantics {
                contentDescription = name
                role = Role.Button
                onClick {
                    onClick()
                    true
                }
            }
            .brightness(1f + 0.1f * lit)
            .clip(CircleShape)
            .background(tokens.accentWash.color)
            .pointerHoverIcon(WebCursor.Default)
            .hoverable(source)
            .clickable(source, indication = null, onClick = onClick)
            .cssBorder(tokens.accentWashBorder.color, CircleShape),
        gap = 6.dp,
        start = 9.dp,
        end = 9.dp,
        top = 4.dp,
        bottom = 4.dp,
    ) {
        PpmIcon(ProductIcon.Sparkles, 14.dp, tokens.accent.color, Modifier.cssIcon(14.dp))
        BasicText(
            text,
            Modifier.cssShrink().widthIn(max = labelMax).cssLines(17.25f),
            style = ppmText(11.5f, lineHeight = 17.25f).copy(color = tokens.accent.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The form chip's `aria-label`, and its `title` too while it has a [label]: the catalog's whole label
 * ("Claude Opus 4.7"), where the chip's text drops the "Claude ". [value] is the id when the
 * provider does not list the model.
 */
internal fun formModelChipName(label: String?, value: String?) = "Model: ${label ?: value ?: "default"}"

/**
 * The models (`w-56 rounded-lg border popover-solid shadow-panel`) over the chip, its left edge on
 * the chip's and 4 px clear: "Model", then a row each (`px-3 py-2 gap-3`), the picked one lit and
 * checked. A press anywhere else closes it and goes on.
 */
@Composable
private fun ModelPopover(models: List<ModelOption>, value: String?, clip: Rect?, onDismiss: () -> Unit, pick: (String) -> Unit) {
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    PlacedPopup(remember(gap) { aboveStart(gap) }, onDismiss, passThrough = true) { ModelPanel(models, value, clip, pick) }
}

/** What [ModelPopover] opens: "Model" over a row for each of the provider's [models]. */
@Composable
internal fun ModelPanel(models: List<ModelOption>, value: String?, clip: Rect?, pick: (String) -> Unit) {
    val tokens = LocalPpmTokens.current
    var at by remember { mutableStateOf(Offset.Zero) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .width(224.dp)
            .onGloballyPositioned { at = it.positionOnScreen() }
            // Cut, shadow and all, by the form it sits in.
            .drawWithContent {
                if (clip == null) drawContent() else clipRect(clip.left - at.x, clip.top - at.y, clip.right - at.x, clip.bottom - at.y) { this@drawWithContent.drawContent() }
            }
            .ppmShadow(tokens.shadowPanel, shape)
            .clip(shape)
            .background(tokens.panel2.over(tokens.bgSolid).color)
            .cssBorder(tokens.border.color, shape),
    ) {
        PopoverHeader("Model")
        Column(Modifier.padding(vertical = 4.dp)) {
            for (option in models) {
                val active = option.value == value
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (active) tokens.panel2.color else Color.Transparent)
                        .ppmClickable(pressedColor = tokens.surfaceHover.color, hoverColor = tokens.surfaceHover.color, litOnFocus = true) { pick(option.value) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BasicText(
                        option.label,
                        Modifier.weight(1f),
                        style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color),
                    )
                    if (active) PpmIcon(ProductIcon.Check, 16.dp, tokens.accent.color)
                }
            }
        }
    }
}
