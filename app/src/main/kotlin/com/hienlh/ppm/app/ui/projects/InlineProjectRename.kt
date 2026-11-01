package com.hienlh.ppm.app.ui.projects

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.TextInputFocus
import com.hienlh.ppm.app.ui.PpmTextField
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.ProjectInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Mobile web project-bottom-sheet: Enter/Done or blur saves, Escape cancels. */
@Composable
internal fun InlineProjectRename(model: AppModel, project: ProjectInfo) {
    val api = model.api ?: return
    val field = rememberTextFieldState(project.name)
    val focus = remember { FocusRequester() }
    val accent = LocalPpmTokens.current.accent.color
    var focusedOnce by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    fun finish(save: Boolean) {
        if (finished) return
        finished = true
        val name = field.text.toString().trim()
        model.phoneProjectRename = null
        if (save && name.isNotEmpty() && name != project.name) model.scope.launch {
            try { model.renameRegisteredProject(api, project, name) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                model.toast(ErrorToastMessage(failure.message ?: "Could not rename project"))
            }
        }
    }
    LaunchedEffect(Unit) {
        TextInputFocus.asked()
        focus.requestFocus()
    }
    PpmTextField(field, "Project name", Modifier.fillMaxWidth().drawBehind {
        drawLine(accent, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
    }.onPreviewKeyEvent {
        if (it.type != KeyEventType.KeyDown || field.composition != null) false
        else when (it.key) {
            Key.Enter, Key.NumPadEnter -> { finish(true); true }
            Key.Escape -> { finish(false); true }
            else -> false
        }
    }, style = ppmText(14f, lineHeight = 20f), focusRequester = focus,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
        onKeyboardAction = { finish(true) },
        onFocusChanged = {
            if (it) focusedOnce = true
            else if (focusedOnce) finish(true)
        })
}
