package com.hienlh.ppm.app.ui.settings.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ui.assistant.AssistantSheet
import com.hienlh.ppm.app.ui.assistant.CardButton
import com.hienlh.ppm.app.ui.assistant.CardButtonVariant
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText

/*
 * The pieces Settings → PPM Assistant is laid out in (`assistant-settings-card.tsx`,
 * `assistant-settings-dialog.tsx`): bordered cards with a header, label-and-controls lines that
 * stack on a phone, and the sheets its dialogs open in.
 */

/** 12 px secondary text, the web's `text-xs text-text-subtle`. */
@Composable
internal fun asstNote(): TextStyle = ppmText(12f, lineHeight = 17f).copy(color = LocalPpmTokens.current.text3.color)

@Composable
internal fun asstBody(): TextStyle = ppmText(13f, lineHeight = 19f).copy(color = LocalPpmTokens.current.text.color)

/** `SettingsCard`: an icon and a title, an optional hint or control on the right, then rows divided by lines. */
@Composable
internal fun AsstCard(
    icon: ProductIcon,
    title: String,
    modifier: Modifier = Modifier,
    aside: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(tokens.panel2.color).cssBorder(tokens.border.color, shape)) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PpmIcon(icon, 16.dp, tokens.text2.color)
                BasicText(title, style = ppmText(14f, FontWeight.SemiBold).copy(color = tokens.text.color))
            }
            if (aside != null) Box(Modifier.align(Alignment.CenterVertically)) { aside() }
        }
        AsstDivider()
        content()
    }
}

@Composable
internal fun AsstDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(LocalPpmTokens.current.borderSoft.color))
}

/** `SettingLine`: the label and its note, then the controls — beside them on a tablet, under them on a phone. */
@Composable
internal fun AsstLine(label: String, note: String? = null, modifier: Modifier = Modifier, controls: (@Composable () -> Unit)? = null) {
    val mobile = LocalPpmMobile.current
    val text = @Composable { m: Modifier ->
        Column(m, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(label, style = asstBody())
            if (note != null) BasicText(note, style = asstNote())
        }
    }
    val pad = modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 10.dp)
    if (mobile) {
        Column(pad, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            text(Modifier.fillMaxWidth())
            if (controls != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { controls() }
        }
    } else {
        Row(pad, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            text(Modifier.weight(1f))
            if (controls != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { controls() }
        }
    }
}

/** A heading and what it is for (`SectionHeader`). */
@Composable
internal fun AsstHeader(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(title, style = ppmText(14f, FontWeight.SemiBold, lineHeight = 20f).copy(color = LocalPpmTokens.current.text.color))
        BasicText(text, style = asstNote())
    }
}

/** An icon-only button named for a screen reader: 44 dp on a phone, 32 dp on a tablet. */
@Composable
internal fun AsstIconButton(icon: ProductIcon, label: String, modifier: Modifier = Modifier, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val size = if (LocalPpmMobile.current) 44.dp else 32.dp
    Box(
        modifier
            .size(size)
            .ppmClickable(enabled = enabled, shape = RoundedCornerShape(6.dp), onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { PpmIcon(icon, 16.dp, (if (danger) tokens.error else tokens.text2).color.copy(alpha = if (enabled) 1f else 0.4f)) }
}

/** A small rounded label ("Main", "Change waiting", "Unverified"). */
@Composable
internal fun AsstPill(label: String, warning: Boolean = false, accent: Boolean = false, icon: ProductIcon? = null, tag: String? = null) {
    val tokens = LocalPpmTokens.current
    val ink = when {
        warning -> tokens.warning
        accent -> tokens.accent
        else -> tokens.text2
    }
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .clip(shape)
            .background(if (warning) tokens.warning.opacity(0.15).color else androidx.compose.ui.graphics.Color.Transparent)
            .then(if (warning) Modifier else Modifier.cssBorder((if (accent) tokens.accent.opacity(0.4) else tokens.border).color, shape))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (icon != null) PpmIcon(icon, 11.dp, ink.color)
        BasicText(label, style = ppmText(11f, FontWeight.Medium, lineHeight = 18f).copy(color = ink.color))
    }
}

/** `AssistantSettingsDialog`: a sheet on a phone, a dialog on a tablet, with a scrolling body and its buttons in the footer. */
@Composable
internal fun AsstDialog(
    title: String,
    onClose: () -> Unit,
    description: String? = null,
    footer: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    AssistantSheet(title = title, subtitle = description, onDismiss = onClose, footer = footer) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** `ConfirmDialog`: the question, Cancel and the confirming button. */
@Composable
internal fun AsstConfirm(title: String, text: String, confirmLabel: String, destructive: Boolean = false, onClose: () -> Unit, onConfirm: () -> Unit) {
    AsstDialog(
        title = title,
        onClose = onClose,
        footer = {
            CardButton("Cancel", onClose, Modifier.weight(1f))
            CardButton(
                confirmLabel,
                onConfirm,
                Modifier.weight(1f).testTag("asst-confirm"),
                variant = if (destructive) CardButtonVariant.DANGER else CardButtonVariant.PRIMARY,
            )
        },
    ) { BasicText(text, style = ppmText(14f, lineHeight = 20f).copy(color = LocalPpmTokens.current.text2.color)) }
}

/** "Loading…" with a spinner, or the error that stopped it. */
@Composable
internal fun AsstLoading(error: String?) {
    val tokens = LocalPpmTokens.current
    if (error != null) {
        BasicText(error, Modifier.testTag("asst-load-error"), ppmText(12f).copy(color = tokens.error.color))
    } else {
        Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.Center) {
            PpmIcon(ProductIcon.Loader2, 16.dp, tokens.text3.color, spinning = true)
        }
    }
}
