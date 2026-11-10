package com.hienlh.ppm.app.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.PpmTextField
import com.hienlh.ppm.app.ui.osexplorer.HostDialogButton
import com.hienlh.ppm.app.ui.osexplorer.HostExplorerDialog
import com.hienlh.ppm.app.ui.osexplorer.HostPathPicker
import com.hienlh.ppm.client.addProject
import com.hienlh.ppm.client.cloneProject
import com.hienlh.ppm.client.lastCloneDirectory
import com.hienlh.ppm.client.suggestProjectDirectories
import com.hienlh.ppm.design.*
import com.hienlh.ppm.protocol.HostPlace
import com.hienlh.ppm.state.osexplorer.HostPickerMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.random.Random

private val SLUG_ADJECTIVES = listOf(
    "amber", "brisk", "calm", "cobalt", "crisp", "dawn", "emerald", "frost",
    "golden", "lunar", "misty", "nimble", "quiet", "rapid", "silver", "solar",
)

private val SLUG_NOUNS = listOf(
    "beacon", "canyon", "comet", "falcon", "forge", "harbor", "horizon", "meadow",
    "orbit", "otter", "pioneer", "quartz", "river", "sparrow", "summit", "willow",
)

internal fun generateRandomProjectSlug(random: Random = Random.Default): String {
    val adj = SLUG_ADJECTIVES[random.nextInt(SLUG_ADJECTIVES.size)]
    val noun = SLUG_NOUNS[random.nextInt(SLUG_NOUNS.size)]
    return "$adj-$noun"
}

@Composable
fun AddProjectDialog(model: AppModel) {
    val api = model.api ?: return
    val tokens = LocalPpmTokens.current
    val path = rememberTextFieldState()
    val name = rememberTextFieldState()
    val gitUrl = rememberTextFieldState()
    val cloneDir = rememberTextFieldState("~/Projects")
    val cloneName = rememberTextFieldState()
    var clone by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var suggestions by remember { mutableStateOf<List<HostPlace>>(emptyList()) }
    var showSuggestions by remember { mutableStateOf(true) }
    var browse by remember { mutableStateOf(false) }
    LaunchedEffect(api) {
        try { api.lastCloneDirectory()?.let { if (cloneDir.text.toString() == "~/Projects") cloneDir.setTextAndPlaceCursorAtEnd(it) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { }
    }
    LaunchedEffect(api, path) {
        snapshotFlow { path.text.toString() }.collectLatest { value ->
            suggestions = emptyList(); error = null
            if (value.isNotBlank()) {
                delay(250)
                try { suggestions = api.suggestProjectDirectories(value); showSuggestions = true }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { }
            }
        }
    }
    LaunchedEffect(gitUrl) {
        snapshotFlow { gitUrl.text.toString() }.collect { url ->
            val parsed = Regex("[/:]([^/:]+?)(?:\\.git)?\\s*$").find(url)?.groupValues?.get(1)
            if (url.isBlank()) cloneName.setTextAndPlaceCursorAtEnd("") else parsed?.let(cloneName::setTextAndPlaceCursorAtEnd)
        }
    }
    fun submit() {
        if (busy) return
        val localPath = path.text.toString().trim(); val url = gitUrl.text.toString().trim(); val directory = cloneDir.text.toString().trim()
        error = when { !clone && localPath.isEmpty() -> "Path is required"; clone && url.isEmpty() -> "Git URL is required"
            clone && directory.isEmpty() -> "Clone directory is required"; else -> null }
        if (error != null) return
        busy = true
        val isClone = clone
        val displayName = (if (isClone) cloneName else name).text.toString().trim().ifEmpty { null }
        model.scope.launch {
            try {
                val result = if (isClone) api.cloneProject(url, directory, displayName).project else api.addProject(localPath, displayName)
                model.projectRegistered(api, result)
                if (model.api === api) model.addingProject = false
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: if (isClone) "Clone failed" else "Failed to add project" }
            finally { busy = false }
        }
    }
    HostExplorerDialog("Add Project", onDismiss = { if (!busy) model.addingProject = false }) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((isClone, label) in listOf(false to "Local folder", true to "Clone from Git")) {
                BasicText(label, Modifier.ppmClickable(enabled = !busy) { clone = isClone; error = null }.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = ppmText(14f, FontWeight.Medium).copy(color = (if (clone == isClone) tokens.accent else tokens.text2).color))
            }
        }
        if (!clone) {
            ProjectFormField("Project path", path, "/path/to/project", busy, onSubmit = ::submit, onBrowse = { browse = true })
            if (showSuggestions && suggestions.isNotEmpty() && !busy) Column(Modifier.fillMaxWidth().heightIn(max = 192.dp).cssScrollY()) {
                for (dir in suggestions) Column(Modifier.fillMaxWidth().ppmClickable {
                    path.setTextAndPlaceCursorAtEnd(dir.path)
                    if (name.text.isEmpty()) name.setTextAndPlaceCursorAtEnd(dir.name)
                    showSuggestions = false
                }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    BasicText(dir.name, style = ppmText(14f, FontWeight.Medium).copy(color = tokens.text.color))
                    BasicText(dir.path, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ppmText(12f).copy(color = tokens.text3.color))
                }
            }
            ProjectFormField("Display name (optional)", name, "my-project", busy, ::submit)
        } else {
            ProjectFormField("Git URL", gitUrl, "https://github.com/user/repo.git", busy, ::submit)
            ProjectFormField("Clone to", cloneDir, "~/Projects", busy, onSubmit = ::submit, onBrowse = { browse = true })
            ProjectFormField(
                label = "Name (auto-parsed from URL)",
                value = cloneName,
                placeholder = "repo-name",
                busy = busy,
                onSubmit = ::submit,
                onBrowse = { cloneName.setTextAndPlaceCursorAtEnd(generateRandomProjectSlug()) },
                browseLabel = "Random",
            )
        }
        error?.let { BasicText(it, style = ppmText(12f).copy(color = tokens.error.color)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
            HostDialogButton("Cancel", !busy) { model.addingProject = false }
            HostDialogButton(if (busy) (if (clone) "Cloning…" else "Adding…") else (if (clone) "Clone & Add" else "Add Project"),
                !busy && if (clone) gitUrl.text.isNotBlank() && cloneDir.text.isNotBlank() else path.text.isNotBlank(), onClick = ::submit)
        }
    }
    if (browse) HostPathPicker(model, HostPickerMode.FOLDER, gitInitNewFolder = !clone, onSelect = { selected ->
        if (clone) cloneDir.setTextAndPlaceCursorAtEnd(selected)
        else {
            path.setTextAndPlaceCursorAtEnd(selected)
            if (name.text.isEmpty()) name.setTextAndPlaceCursorAtEnd(selected.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\'))
        }
        browse = false; error = null
    }, onCancel = { browse = false })
}

@Composable
internal fun ProjectFormField(label: String, value: TextFieldState, placeholder: String, busy: Boolean = false,
                              onSubmit: () -> Unit = {}, onBrowse: (() -> Unit)? = null, browseLabel: String = "Browse") {
    val tokens = LocalPpmTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(label, style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text.color))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            val modifier = Modifier.weight(1f).background(tokens.bgSolid.color, RoundedCornerShape(6.dp))
                .cssBorder(tokens.border.color, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 8.dp)
            if (busy) BasicText(value.text.toString(), modifier, style = ppmText(14f).copy(color = tokens.text2.color))
            else PpmTextField(value, placeholder, modifier, desktopStyle = ppmText(14f),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done), onKeyboardAction = { onSubmit() })
            if (onBrowse != null) HostDialogButton(browseLabel, !busy, onClick = onBrowse)
        }
    }
}
