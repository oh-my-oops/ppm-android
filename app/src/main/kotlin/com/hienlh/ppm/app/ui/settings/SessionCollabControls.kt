package com.hienlh.ppm.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ui.SettingsToggleRow
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.settings.SESSION_COLLAB_NUMBERS
import com.hienlh.ppm.state.settings.SessionCollabNumber
import com.hienlh.ppm.state.settings.SessionCollabSettings
import com.hienlh.ppm.state.settings.commitSessionCollabNumber
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Settings → Tools → Session collaboration, below its tool switches (`SessionCollabSettingsControls`):
 * whether an AI asks before creating a session, and the four limits (`ai.session_collab`). Each
 * number is clamped to the range the server enforces before it is sent, so what the field shows is
 * what is saved.
 */
@Composable
fun SessionCollabControls(settings: SessionCollabSettings, onChange: (key: String, value: JsonElement) -> Unit) {
    val tokens = LocalPpmTokens.current
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("session-collab-settings")
            .cssBorder(tokens.border.color, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsToggleRow(
            title = "Ask before creating sessions",
            subtitle = "An AI asks you before it opens a new session. Messaging a session that already exists never asks.",
            checked = settings.askBeforeCreate,
            onCheckedChange = { onChange("askBeforeCreate", JsonPrimitive(it)) },
            testTag = "session-collab-ask",
        )
        for (n in SESSION_COLLAB_NUMBERS) NumberRow(n, settings.number(n.key)) { onChange(n.key, JsonPrimitive(it)) }
    }
}

@Composable
private fun NumberRow(setting: SessionCollabNumber, value: Int, onCommit: (Int) -> Unit) {
    val tokens = LocalPpmTokens.current
    var draft by remember { mutableStateOf(value.toString()) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value) { draft = value.toString() }
    val commit = {
        val next = commitSessionCollabNumber(setting.key, draft, value)
        draft = next.toString()
        if (next != value) onCommit(next)
    }
    val text: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(setting.label, style = ppmText(13f).copy(color = tokens.text.color))
            BasicText(
                "${setting.note} ${setting.min}–${setting.max}${setting.unit?.let { " $it" } ?: ""}.",
                style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color),
            )
        }
    }
    val field: @Composable (Modifier) -> Unit = { m ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val shape = RoundedCornerShape(8.dp)
            BasicTextField(
                value = draft,
                onValueChange = { draft = it.filter { c -> c.isDigit() || c == '.' || c == '-' } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                textStyle = ppmText(if (LocalPpmMobile.current) 16f else 12f).copy(color = tokens.text.color),
                cursorBrush = SolidColor(tokens.accent.color),
                modifier = m
                    .testTag("session-collab-" + setting.key)
                    .semantics { contentDescription = setting.label }
                    .onFocusChanged {
                        if (focused && !it.isFocused) commit()
                        focused = it.isFocused
                    }
                    .clip(shape)
                    .background(tokens.bgSolid.color)
                    .cssBorder(tokens.border.color, shape)
                    .padding(horizontal = 10.dp, vertical = if (LocalPpmMobile.current) 12.dp else 8.dp),
            )
            setting.unit?.let { BasicText(it, style = ppmText(11f).copy(color = tokens.text3.color)) }
        }
    }
    if (LocalPpmMobile.current) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            text(Modifier.fillMaxWidth())
            field(Modifier.width(160.dp))
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            text(Modifier.weight(1f))
            field(Modifier.width(96.dp))
        }
    }
}
