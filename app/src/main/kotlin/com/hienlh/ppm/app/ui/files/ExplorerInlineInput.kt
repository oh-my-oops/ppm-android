package com.hienlh.ppm.app.ui.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.TextInputFocus
import com.hienlh.ppm.app.ui.PpmTextField
import com.hienlh.ppm.design.FileIcon
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.StartTruncatedText
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmText
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

@Composable
internal fun ExplorerInlineInput(
    row: ExplorerDisplayRow.Input, field: TextFieldState, busy: Boolean, error: String?,
    onSubmit: () -> Unit, onCancel: () -> Unit, onEdit: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val submit by rememberUpdatedState(onSubmit)
    val cancel by rememberUpdatedState(onCancel)
    val changed by rememberUpdatedState(onEdit)
    val pending by rememberUpdatedState(busy)
    var focused by remember { mutableStateOf(false) }
    val lifetime = remember { InputLifetime() }
    DisposableEffect(Unit) { lifetime.mounted = true; onDispose { lifetime.mounted = false } }
    LaunchedEffect(row.edit.id, busy) {
        if (!busy) {
            withFrameNanos { }
            TextInputFocus.asked()
            focus.requestFocus()
        }
    }
    LaunchedEffect(field) { snapshotFlow { field.text.toString() }.drop(1).collect { changed() } }
    BackHandler(enabled = !busy) { cancel() }
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(start = (row.depth * 16 + 8).dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(14.dp)) {
                if (busy) PpmIcon(ProductIcon.Loader2, 14.dp, tokens.text3.color, spinning = true)
            }
            FileIcon(field.text.toString(), folder = row.edit.directory)
            val shape = RoundedCornerShape(4.dp)
            Box(Modifier.weight(1f).background(tokens.panel.color, shape)
                .cssBorder((if (error != null) tokens.error else tokens.accent).color, shape)
                .padding(horizontal = 6.dp, vertical = 2.dp)) {
                if (busy) StartTruncatedText(field.text.toString(), ppmText(14f, lineHeight = 20f).copy(color = tokens.text2.color), Modifier.fillMaxWidth())
                else PpmTextField(
                    field, if (row.edit.directory) "folder-name" else "filename.ts",
                    Modifier.fillMaxWidth().semantics { contentDescription = if (row.edit.node == null) "New name" else "Rename ${row.edit.node.name}" }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                                Key.Enter, Key.NumPadEnter -> if (field.composition != null) false else { submit(); true }
                                Key.Escape -> { cancel(); true }
                                else -> false
                            }
                        },
                    style = ppmText(16f, lineHeight = 24f), desktopStyle = ppmText(14f, lineHeight = 20f),
                    focusRequester = focus,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    onKeyboardAction = { submit() },
                    onFocusChanged = { now ->
                        val lost = focused && !now
                        focused = now
                        if (lost && !pending) scope.launch {
                            // A disappearing drawer/project cancels; an ordinary blur confirms.
                            withFrameNanos { }
                            if (lifetime.mounted && !pending && !focused) submit()
                        }
                    },
                )
            }
        }
        if (error != null) BasicText(error, Modifier.fillMaxWidth().padding(start = (row.depth * 16 + 44).dp, end = 8.dp, bottom = 4.dp),
            style = ppmText(10f, lineHeight = 14f).copy(color = tokens.error.color))
    }
}

private class InputLifetime { var mounted = true }
