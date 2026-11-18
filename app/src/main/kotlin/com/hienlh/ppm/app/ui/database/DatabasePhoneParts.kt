package com.hienlh.ppm.app.ui.database

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.kit.Sheet
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText

/** `max-md:min-h-11`: a 44 dp touch target on a phone, the control's own height elsewhere. */
@Composable
internal fun dbPhoneTouchTarget(): Modifier =
    if (LocalPpmMobile.current) Modifier.heightIn(min = 44.dp) else Modifier

internal const val WRITE_ONCE_TITLE = "Run with write access (once)?"

/** What the dialog says about [connectionName] (`write-once-dialog.tsx`). */
internal fun writeOnceExplanation(connectionName: String): String =
    "$connectionName is readonly. This runs the script below once with write access; the connection stays readonly afterwards."

/**
 * "Run with write access (once)" on a readonly connection (`write-once-dialog.tsx`): the script that
 * was refused runs once more with the connection writable, for that run alone, once PPM's password
 * is typed. The server checks the password; a wrong one fails the run and the action is offered
 * again. A centred card on a tablet, a bottom sheet on a phone. [passwordRequired] is false only for
 * a PPM that signs in with no password, which then asks for nothing.
 */
@Composable
internal fun WriteOnceDialog(
    connectionName: String,
    sql: String,
    passwordRequired: Boolean,
    onCancel: () -> Unit,
    onRun: (password: String) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    var password by remember { mutableStateOf("") }
    val canRun = !passwordRequired || password.isNotEmpty()
    val submit = { if (canRun) onRun(password) }

    Sheet(
        title = WRITE_ONCE_TITLE,
        onDismiss = onCancel,
        forceBottomSheet = mobile,
        modifier = Modifier.testTag("db-query-write-once-dialog"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText(
                writeOnceExplanation(connectionName),
                style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text2.color),
            )
            // `max-h-40 overflow-auto whitespace-pre-wrap break-all rounded border bg-background p-2 font-mono text-xs`
            val preShape = RoundedCornerShape(4.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .clip(preShape)
                    .background(tokens.bgSolid.color)
                    .cssBorder(tokens.border.color, preShape)
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp)
                    .testTag("db-query-write-once-sql"),
            ) {
                BasicText(sql, style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text2.color))
            }
            if (passwordRequired) {
                val shape = RoundedCornerShape(6.dp)
                // `h-11 md:h-9`, 16 px on a phone.
                val text = if (mobile) ppmText(16f, lineHeight = 24f) else ppmText(14f, lineHeight = 20f)
                BasicTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    textStyle = text.copy(color = tokens.text.color),
                    cursorBrush = SolidColor(tokens.accent.color),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = if (mobile) 44.dp else 36.dp)
                        .clip(shape)
                        .background(tokens.bgSolid.color)
                        .cssBorder(tokens.border.color, shape)
                        .padding(horizontal = 12.dp)
                        .semantics { contentDescription = "PPM password" }
                        .testTag("db-query-write-once-password"),
                    decorationBox = { inner ->
                        Box(Modifier.heightIn(min = if (mobile) 44.dp else 36.dp), contentAlignment = Alignment.CenterStart) {
                            if (password.isEmpty()) BasicText("PPM password", style = text.copy(color = tokens.text3.color))
                            inner()
                        }
                    },
                )
            }
            // `flex flex-col-reverse gap-2 pt-1 md:flex-row md:justify-end`
            if (mobile) {
                Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WriteOnceButton("Run once", destructive = true, enabled = canRun, tag = "db-query-write-once-submit", onClick = submit)
                    WriteOnceButton("Cancel", destructive = false, enabled = true, tag = "db-query-write-once-cancel", onClick = onCancel)
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    WriteOnceButton("Cancel", destructive = false, enabled = true, tag = "db-query-write-once-cancel", onClick = onCancel)
                    WriteOnceButton("Run once", destructive = true, enabled = canRun, tag = "db-query-write-once-submit", onClick = submit)
                }
            }
        }
    }
}

/** The dialog's `outline` / `destructive` button: `min-h-11 md:min-h-9`, full width on a phone. */
@Composable
private fun WriteOnceButton(label: String, destructive: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val shape = RoundedCornerShape(6.dp)
    val bg = if (destructive) tokens.error.color else tokens.panel.color
    val fg = if (destructive) Color.White else tokens.text.color
    Box(
        modifier = Modifier
            .testTag(tag)
            .then(if (mobile) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = if (mobile) 44.dp else 36.dp)
            .clip(shape)
            .background(if (enabled) bg else bg.copy(alpha = 0.5f))
            .ppmClickable(enabled = enabled, shape = shape, onClickLabel = label, onClick = onClick)
            .then(if (destructive) Modifier else Modifier.cssBorder(tokens.border.color, shape))
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = if (enabled) fg else fg.copy(alpha = 0.5f)))
    }
}
