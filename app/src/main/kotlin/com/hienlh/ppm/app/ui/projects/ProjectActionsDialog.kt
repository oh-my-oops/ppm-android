package com.hienlh.ppm.app.ui.projects

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import com.hienlh.ppm.app.ui.BottomSheet
import com.hienlh.ppm.app.ui.SheetLabel
import com.hienlh.ppm.app.ui.SheetRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import com.hienlh.ppm.app.TextInputFocus
import androidx.compose.foundation.text.input.rememberTextFieldState
import com.hienlh.ppm.app.ui.PpmTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.PROJECT_PALETTE
import com.hienlh.ppm.app.ui.projectBackground
import com.hienlh.ppm.app.ui.osexplorer.HostExplorerDialog
import com.hienlh.ppm.app.ui.osexplorer.HostDialogButton
import com.hienlh.ppm.client.*
import com.hienlh.ppm.design.*
import com.hienlh.ppm.protocol.ProjectInfo
import kotlinx.coroutines.*

@Composable
internal fun ProjectActionsDialog(model: AppModel, project: ProjectInfo) {
    val api = model.api ?: return
    val context = LocalContext.current
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    var renameMode by remember { mutableStateOf(false) }
    var deleteMode by remember { mutableStateOf(false) }
    val rename = rememberTextFieldState(project.name)
    var colorMode by remember { mutableStateOf(false) }
    var selectedColor by remember { mutableStateOf(project.color ?: PROJECT_PALETTE[model.projects.indexOfFirst { it.name == project.name }.coerceAtLeast(0) % PROJECT_PALETTE.size]) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun update(work: suspend () -> ProjectInfo) {
        if (busy) return
        busy = true; error = null
        model.scope.launch {
            try {
                val updated = work()
                model.projectAppearanceUpdated(api, updated)
                colorMode = false
                if (mobile) model.projectActions = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not update project" }
            finally { busy = false }
        }
    }
    fun registration(work: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        model.scope.launch {
            try { work() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not update project" }
            finally { busy = false }
        }
    }
    fun submitRename() {
        if (busy || rename.composition != null) return
        val name = rename.text.toString().trim()
        if (name.isEmpty()) error = "Enter a project name"
        else if (name == project.name) renameMode = false
        else registration { model.renameRegisteredProject(api, project, name) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && model.api === api) update {
            val bytes = withContext(Dispatchers.IO) { projectAvatarWebp(context.contentResolver, uri) }
            api.setProjectImage(project.name, bytes)
        }
    }
    val content: @Composable ColumnScope.() -> Unit = {
        error?.let { BasicText(it, style = ppmText(13f).copy(color = tokens.error.color)) }
        if (busy) BasicText("Saving…", style = ppmText(13f).copy(color = tokens.text2.color))
        else if (renameMode) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                rename.edit { selection = TextRange(0, length) }
                TextInputFocus.asked()
                focus.requestFocus()
            }
            PpmTextField(rename, "Project name", Modifier.fillMaxWidth().onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown || rename.composition != null) false
                else when (it.key) {
                    Key.Enter, Key.NumPadEnter -> { submitRename(); true }
                    Key.Escape -> { renameMode = false; true }
                    else -> false
                }
            }, focusRequester = focus, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                onKeyboardAction = { submitRename() })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HostDialogButton("Cancel") { renameMode = false }
                HostDialogButton("Rename", onClick = ::submitRename)
            }
        } else if (deleteMode) {
            BasicText(
                "Remove ${project.name} from PPM? The files on disk won't be deleted.",
                style = ppmText(14f).copy(color = tokens.text.color),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HostDialogButton("Cancel") { deleteMode = false }
                HostDialogButton("Delete", danger = true) { registration { model.removeRegisteredProject(api, project) } }
            }
        } else if (colorMode) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PROJECT_PALETTE.forEach { color ->
                    Box(Modifier.size(36.dp).clip(CircleShape).background(projectBackground(color, 0))
                        .cssBorder(if (selectedColor == color) tokens.accent.color else androidx.compose.ui.graphics.Color.Transparent, CircleShape)
                        .semantics { contentDescription = "Project color ${PROJECT_PALETTE.indexOf(color) + 1}" }
                        .ppmClickable { selectedColor = color }) {
                        if (selectedColor == color && !mobile) PpmIcon(ProductIcon.Check, 24.dp, androidx.compose.ui.graphics.Color.White)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (mobile) {
                    val shape = RoundedCornerShape(6.dp)
                    BasicText("Cancel", Modifier.weight(1f).clip(shape).cssBorder(tokens.border.color, shape)
                        .ppmClickable { colorMode = false; model.projectActions = null }.padding(vertical = 10.dp),
                        style = ppmText(14f).copy(color = tokens.text2.color, textAlign = TextAlign.Center))
                    BasicText("Save", Modifier.weight(1f).clip(shape).background(tokens.accent.color)
                        .ppmClickable { update { api.setProjectColor(project.name, selectedColor) } }.padding(vertical = 10.dp),
                        style = ppmText(14f).copy(color = androidx.compose.ui.graphics.Color.White, textAlign = TextAlign.Center))
                } else {
                    HostDialogButton("Cancel") { colorMode = false }
                    HostDialogButton("Save") { update { api.setProjectColor(project.name, selectedColor) } }
                }
            }
        } else {
            if (!mobile) HostDialogButton("Open") { model.projectActions = null; model.scope.launch { model.openProject(project.name) } }
            ProjectAction("Open in New Window", ProductIcon.ExternalLink) {
                try {
                    com.hienlh.ppm.app.ProjectWindowActivity.open(context, model, project.name)
                    model.projectActions = null
                } catch (failure: Exception) { error = failure.message ?: "Cannot open window" }
            }
            ProjectAction("Rename", ProductIcon.Pencil) {
                if (mobile) {
                    model.phoneProjectRename = project.name
                    model.projectActions = null
                } else renameMode = true
            }
            ProjectAction("Change Color", ProductIcon.Palette) { colorMode = true }
            ProjectAction("Change Image", ProductIcon.Image) {
                try { picker.launch("image/*") } catch (failure: Exception) { error = failure.message }
            }
            if (project.image != null) ProjectAction("Remove Image", ProductIcon.Trash2) { update { api.removeProjectImage(project.name) } }
            ProjectAction("Copy Path", ProductIcon.Copy) {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Project path", project.path))
                model.toast(ErrorToastMessage("Path copied", kind = ErrorToastMessage.Kind.SUCCESS))
                if (mobile) model.projectActions = null
            }
            if (mobile && model.projectActionsCanReorder && model.projectSortMode == "priority") {
                val names = model.sortedProjects.map { it.name }
                val index = names.indexOf(project.name)
                fun move(delta: Int) {
                    val next = names.toMutableList()
                    next.add(index + delta, next.removeAt(index))
                    model.reorderProjects(next)
                    model.projectActions = null
                }
                if (index > 0) ProjectAction("Move Up", ProductIcon.ChevronUp) { move(-1) }
                if (index >= 0 && index < names.lastIndex) ProjectAction("Move Down", ProductIcon.ChevronDown) { move(1) }
            }
            ProjectAction("Delete", ProductIcon.Trash2, danger = true) { deleteMode = true }
            if (!mobile) HostDialogButton("Close") { model.projectActions = null }
        }
    }
    if (mobile && !renameMode && !deleteMode) {
        BottomSheet(onDismiss = { if (!busy) model.projectActions = null }, dismissible = !busy) {
            Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).cssScrollY().padding(if (colorMode) 16.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(if (colorMode) 16.dp else 0.dp)) {
                if (colorMode) BasicText("Change Color", style = ppmText(14f).copy(color = tokens.text.color))
                else SheetLabel(project.name)
                content()
            }
        }
    } else HostExplorerDialog(project.name, when {
        renameMode -> "Rename project"
        deleteMode -> "Remove project"
        colorMode -> "Change Color"
        else -> project.path
    }, { if (!busy) model.projectActions = null }, content)
}

@Composable
private fun ProjectAction(label: String, icon: ProductIcon, danger: Boolean = false, onClick: () -> Unit) {
    if (LocalPpmMobile.current) SheetRow(label, icon, destructive = danger, onClick = onClick)
    else HostDialogButton(label, danger = danger, onClick = onClick)
}
