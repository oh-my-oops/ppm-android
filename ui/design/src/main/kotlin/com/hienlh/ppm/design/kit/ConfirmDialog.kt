package com.hienlh.ppm.design.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText

/**
 * Confirmation dialog / sheet with 48 dp Cancel and Confirm buttons (Foundation Section 3.6 & 7).
 *
 * Used for Delete project, Discard file changes, Replace All across files, and HTTP password
 * warnings.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    cancelLabel: String = "Cancel",
    destructive: Boolean = true,
    confirmEnabled: Boolean = true,
    dismissOnConfirm: Boolean = true,
    extraContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        ConfirmDialogCard(
            title = title,
            message = message,
            confirmLabel = confirmLabel,
            onConfirm = {
                onConfirm()
                if (dismissOnConfirm) onDismiss()
            },
            onDismiss = onDismiss,
            modifier = modifier.padding(horizontal = 20.dp),
            cancelLabel = cancelLabel,
            destructive = destructive,
            confirmEnabled = confirmEnabled,
            extraContent = extraContent,
        )
    }
}

/**
 * Standalone confirmation card surface (48 dp buttons, 20 dp corner radius), usable inside a
 * [Dialog], [Sheet], or preview.
 */
@Composable
fun ConfirmDialogCard(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    cancelLabel: String = "Cancel",
    destructive: Boolean = true,
    confirmEnabled: Boolean = true,
    extraContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    val cardShape = RoundedCornerShape(20.dp)
    val buttonShape = RoundedCornerShape(12.dp)

    Column(
        modifier = modifier
            .widthIn(min = 280.dp, max = 380.dp)
            .fillMaxWidth()
            .ppmShadow(tokens.shadowFloat, cardShape)
            .clip(cardShape)
            .background(tokens.panel2.color, cardShape)
            .cssBorder(tokens.border.color, cardShape, width = 1.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText(
                text = title,
                style = ppmText(17f, FontWeight.SemiBold, lineHeight = 22f).copy(color = tokens.text.color),
            )
            if (message.isNotEmpty()) {
                BasicText(
                    text = message,
                    style = ppmText(14f, FontWeight.Normal, lineHeight = 20f).copy(color = tokens.text2.color),
                )
            }
        }

        if (extraContent != null) {
            extraContent()
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 48 dp Cancel button
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(KitSpec.MinTouchTarget)
                    .clip(buttonShape)
                    .background(tokens.panel.color, buttonShape)
                    .cssBorder(tokens.border.color, buttonShape, width = 1.dp)
                    .ppmClickable(
                        shape = buttonShape,
                        hoverColor = tokens.surfaceHover.color,
                        onClickLabel = cancelLabel,
                        onClick = onDismiss,
                    )
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = cancelLabel,
                    style = ppmText(14f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 48 dp Confirm / Destructive button
            val confirmBg = when {
                !confirmEnabled -> tokens.panel.color
                destructive -> tokens.error.color
                else -> tokens.accent.color
            }
            val confirmFg = when {
                !confirmEnabled -> tokens.text3.color
                destructive -> Color.White
                else -> tokens.accentFg.color
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(KitSpec.MinTouchTarget)
                    .clip(buttonShape)
                    .background(confirmBg, buttonShape)
                    .then(
                        if (!confirmEnabled) {
                            Modifier.cssBorder(tokens.border.color, buttonShape, width = 1.dp)
                        } else {
                            Modifier
                        },
                    )
                    .ppmClickable(
                        enabled = confirmEnabled,
                        shape = buttonShape,
                        pressedColor = confirmFg.copy(alpha = 0.18f),
                        hoverColor = confirmFg.copy(alpha = 0.12f),
                        onClickLabel = confirmLabel,
                        onClick = onConfirm,
                    )
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = confirmLabel,
                    style = ppmText(14f, FontWeight.SemiBold, lineHeight = 18f).copy(color = confirmFg),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
