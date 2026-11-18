package com.hienlh.ppm.app.ui.design.share

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ui.PpmTextField
import com.hienlh.ppm.app.ui.assistant.CardButton
import com.hienlh.ppm.app.ui.assistant.CardButtonVariant
import com.hienlh.ppm.design.ChromeCheckbox
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText

/*
 * The small pieces the Share dialog and Settings → Review links are built from, in the app's own
 * tokens: the web's `text-sm` body, `text-xs` captions, its bordered boxes and 44 dp (phone) /
 * 32–36 dp (tablet) controls.
 */

/** `text-sm`: the dialog's body text. */
internal val shareBody: TextStyle get() = ppmText(14f, lineHeight = 20f)

/** `text-xs`: captions, fact lines. */
internal val shareCaption: TextStyle get() = ppmText(12f, lineHeight = 16f)

/** A section's heading (`text-xs font-semibold uppercase tracking-wide text-text-subtle`). */
@Composable
internal fun ShareSectionHeading(text: String, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    BasicText(
        text.uppercase(),
        modifier,
        style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text3.color, letterSpacing = ppmText(12f).fontSize * 0.025f),
    )
}

@Composable
internal fun ShareText(text: String, modifier: Modifier = Modifier, color: Color? = null, weight: FontWeight? = null, style: TextStyle = shareBody) {
    val tokens = LocalPpmTokens.current
    BasicText(text, modifier, style = style.copy(color = color ?: tokens.text2.color, fontWeight = weight ?: style.fontWeight))
}

enum class NoticeTone { PLAIN, WARN }

/** The address block's box: an icon beside lines of text and actions, amber when it warns. */
@Composable
internal fun ShareNoticeBox(
    icon: ProductIcon,
    testTag: String,
    tone: NoticeTone = NoticeTone.PLAIN,
    spinning: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    val warn = tone == NoticeTone.WARN
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .background((if (warn) tokens.warning.opacity(0.1) else tokens.panel2.opacity(0.4)).color, shape)
            .cssBorder((if (warn) tokens.warning.opacity(0.4) else tokens.border).color, shape)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.padding(top = 2.dp)) { PpmIcon(icon, 16.dp, tokens.text3.color, spinning = spinning) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** A bordered card (`rounded-lg border border-border p-3`). */
@Composable
internal fun ShareCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier.fillMaxWidth().cssBorder(tokens.border.color, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** The dialog's buttons: the assistant's card button, which is 44 dp on a phone and 32 dp on a tablet. */
@Composable
internal fun ShareButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ProductIcon? = null,
    variant: CardButtonVariant = CardButtonVariant.DEFAULT,
    enabled: Boolean = true,
    busy: Boolean = false,
    testTag: String? = null,
) {
    CardButton(
        label = label,
        onClick = onClick,
        modifier = if (testTag != null) modifier.testTag(testTag) else modifier,
        icon = if (busy) ProductIcon.Loader2 else icon,
        variant = variant,
        enabled = enabled && !busy,
    )
}

/** A spinner beside a line (`Loader2 animate-spin` + text). */
@Composable
internal fun ShareProgressLine(text: String, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        PpmIcon(ProductIcon.Loader2, 16.dp, tokens.text3.color, spinning = true)
        ShareText(text, color = tokens.text3.color)
    }
}

/** A one-line text field (`min-h-11 md:min-h-9 rounded-md border bg-background px-3`). */
@Composable
internal fun ShareTextInput(
    state: TextFieldState,
    placeholder: String,
    modifier: Modifier = Modifier,
    maxChars: Int? = null,
    capitalize: Boolean = true,
    testTag: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(if (mobile) 44.dp else 36.dp)
            .background(tokens.bgSolid.color, shape)
            .cssBorder(tokens.border.color, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            PpmTextField(
                state,
                placeholder,
                Modifier.fillMaxWidth().then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
                desktopStyle = ppmText(14f, lineHeight = 20f),
                keyboardOptions = KeyboardOptions(
                    capitalization = if (capitalize) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
                    autoCorrectEnabled = capitalize,
                ),
                inputTransformation = maxChars?.let { InputTransformation.maxLength(it) },
                cssStart = 12.dp,
            )
        }
        trailing?.invoke()
    }
}

/** A labelled checkbox (`Check`): the whole row toggles it. */
@Composable
internal fun ShareCheck(checked: Boolean, onChange: (Boolean) -> Unit, label: String, testTag: String? = null) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (mobile) 44.dp else 32.dp)
            .ppmClickable(pressedColor = Color.Transparent, role = Role.Checkbox) { onChange(!checked) }
            .semantics {
                toggleableState = ToggleableState(checked)
                contentDescription = label
            }
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChromeCheckbox(checked, Modifier.size(16.dp), accent = tokens.accent.color)
        ShareText(label, color = tokens.text.color)
    }
}

/** A row of choice chips (`role=radio`): the chosen one in the accent. */
@Composable
internal fun ShareChoiceChip(label: String, selected: Boolean, onClick: () -> Unit, testTag: String? = null) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .height(if (mobile) 44.dp else 32.dp)
            .background(if (selected) tokens.accent.opacity(0.1).color else Color.Transparent, shape)
            .cssBorder((if (selected) tokens.accent else tokens.border).color, shape)
            .ppmClickable(shape = shape, role = Role.RadioButton, onClick = onClick)
            .semantics {
                toggleableState = ToggleableState(selected)
            }
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = shareBody.copy(color = (if (selected) tokens.text else tokens.text2).color), maxLines = 1)
    }
}
