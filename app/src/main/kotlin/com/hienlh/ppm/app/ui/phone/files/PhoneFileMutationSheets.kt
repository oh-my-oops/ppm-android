package com.hienlh.ppm.app.ui.phone.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ui.files.explorerRenameSelectionEnd
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.kit.KitSpec
import com.hienlh.ppm.design.kit.Sheet
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import kotlinx.coroutines.flow.drop
import com.hienlh.ppm.app.tracksTextFocus

/**
 * The name prompt for New File, New Folder and Rename on a phone: the old explorer's inline input
 * (`ExplorerInlineInput`) as a sheet, since a phone row is too narrow to type in. [error] is the server's
 * answer to the last try; editing clears it ([onEdited]).
 */
@Composable
internal fun PhoneFileNameSheet(
    title: String,
    initialName: String,
    confirmLabel: String,
    busy: Boolean,
    error: String?,
    onSubmit: (String) -> Unit,
    onEdited: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val field = remember(title, initialName) {
        TextFieldState(initialName, TextRange(0, explorerRenameSelectionEnd(initialName)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(field) { snapshotFlow { field.text.toString() }.drop(1).collect { onEdited() } }
    Sheet(
        title = title,
        onDismiss = { if (!busy) onDismiss() },
        forceBottomSheet = true,
        modifier = Modifier.testTag("phone-file-name-sheet"),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val shape = RoundedCornerShape(12.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = KitSpec.MinTouchTarget)
                    .clip(shape)
                    .background(tokens.panel.color, shape)
                    .cssBorder((if (error != null) tokens.error else tokens.accent).color, shape, width = 1.dp)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    state = field,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).tracksTextFocus(field).testTag("phone-file-name-input"),
                    textStyle = ppmText(15f, lineHeight = 22f).copy(color = tokens.text.color),
                    cursorBrush = SolidColor(tokens.accent.color),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    onKeyboardAction = { if (!busy) onSubmit(field.text.toString()) },
                )
            }
            if (error != null) {
                BasicText(
                    error,
                    Modifier.testTag("phone-file-name-error"),
                    style = ppmText(12.5f, lineHeight = 17f).copy(color = tokens.error.color),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PhoneSheetButton("Cancel", primary = false, enabled = !busy, testTag = "phone-file-name-cancel", modifier = Modifier.weight(1f), onClick = onDismiss)
                PhoneSheetButton(
                    if (busy) "Saving…" else confirmLabel,
                    primary = true,
                    enabled = !busy,
                    testTag = "phone-file-name-confirm",
                    modifier = Modifier.weight(1f),
                    onClick = { onSubmit(field.text.toString()) },
                )
            }
        }
    }
}

@Composable
internal fun PhoneSheetButton(
    label: String,
    primary: Boolean,
    enabled: Boolean,
    testTag: String,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)
    val bg = when {
        destructive -> tokens.error.color
        primary -> tokens.accent.color
        else -> tokens.panel2.color
    }
    val fg = if (primary || destructive) tokens.accentFg.color else tokens.text.color
    Box(
        modifier
            .height(KitSpec.MinTouchTarget)
            .clip(shape)
            .background(bg, shape)
            .cssBorder(tokens.border.color, shape, width = 1.dp)
            .ppmClickable(enabled = enabled, shape = shape, onClickLabel = label, onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = ppmText(14f, FontWeight.SemiBold, lineHeight = 18f).copy(color = fg))
    }
}
