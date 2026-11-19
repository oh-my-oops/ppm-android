package com.hienlh.ppm.app.ui.settings.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.assistant
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.SettingsTextInput
import com.hienlh.ppm.app.ui.assistant.CardButton
import com.hienlh.ppm.app.ui.assistant.CardButtonVariant
import com.hienlh.ppm.app.ui.assistant.MANAGER_ASSIGN_CONSEQUENCE
import com.hienlh.ppm.app.ui.assistant.SetManagerChatDialog
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.createManagerChat
import com.hienlh.ppm.client.decideManagerRules
import com.hienlh.ppm.client.managerRules
import com.hienlh.ppm.client.patchAssistantSettings
import com.hienlh.ppm.client.putManagerRules
import com.hienlh.ppm.client.utf8Bytes
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.ManagerChatSummary
import com.hienlh.ppm.protocol.ManagerRulesView
import com.hienlh.ppm.protocol.RULES_FILE_MAX_BYTES
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.state.assistant.MANAGER_DELEGATE_RULE_PROSE
import com.hienlh.ppm.state.assistant.RULES_GUARD_HINT
import com.hienlh.ppm.state.assistant.hasManagerDelegateRule
import com.hienlh.ppm.state.assistant.withManagerDelegateRule
import com.hienlh.ppm.state.assistant.defaultManagerTitle
import com.hienlh.ppm.state.assistant.managerModeLabel
import com.hienlh.ppm.state.assistant.templateToStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/*
 * The Manager chats pane's dialogs: assign or create a project's manager
 * (`assign-manager-dialog.tsx`), edit its rules with a held change's Approve / Revert
 * (`manager-rules-dialog.tsx`), and the template new rules files start from
 * (`manager-template-dialog.tsx`). Sizes are UTF-8 bytes against the 16 KB cap, as the server counts.
 */

private fun errorText(e: Exception) = e.message ?: e.toString()

/** "3.2 KB / 16 KB", red past the cap (`SizeCounter`). */
@Composable
private fun SizeCounter(bytes: Int) {
    val over = bytes > RULES_FILE_MAX_BYTES
    val tokens = LocalPpmTokens.current
    BasicText(
        String.format(Locale.US, "%.1f KB / %d KB", bytes / 1024.0, RULES_FILE_MAX_BYTES / 1024) + if (over) " — too large to save" else "",
        Modifier.testTag("asst-size-counter"),
        ppmText(12f).copy(color = (if (over) tokens.error else tokens.text3).color),
    )
}

@Composable
private fun RulesTextarea(label: String, value: String, enabled: Boolean, tag: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(label, style = ppmText(12f, kind = FontKind.MONO).copy(color = LocalPpmTokens.current.text3.color))
        SettingsTextInput(value, { if (enabled) onChange(it) }, "", Modifier.fillMaxWidth().heightIn(min = 220.dp).testTag(tag), mono = true, singleLine = false)
    }
}

/**
 * "Assign / create" (and "Replace…") for one project: one of its Claude chats, handed to the same
 * "Set as manager chat" dialog the chat list uses, or a new manager chat.
 */
@Composable
internal fun AssignManagerDialog(model: AppModel, api: ApiClient, projectName: String, current: ManagerChatSummary?, defaultMode: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var sessions by remember(projectName) { mutableStateOf<List<SessionInfo>?>(null) }
    var picked by remember { mutableStateOf<SessionInfo?>(null) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(api, projectName) {
        sessions = try {
            api.sessions(projectName).sessions
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }
    picked?.let { s ->
        SetManagerChatDialog(model, projectName, s.id, s.title, onClose)
        return
    }
    val chats = sessions.orEmpty().filter { it.providerId == "claude" && it.id != current?.sessionId }
    fun create() {
        creating = true
        scope.launch {
            try {
                api.createManagerChat(projectName)
                model.assistant.store?.refreshManagers()
                model.toast(ErrorToastMessage("Created the $projectName manager chat", kind = ErrorToastMessage.Kind.SUCCESS))
                onClose()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.toast(ErrorToastMessage("Could not create the manager chat", errorText(e)))
                creating = false
            }
        }
    }
    val tokens = LocalPpmTokens.current
    AsstDialog(
        title = if (current != null) "Replace the $projectName manager" else "Manager chat for $projectName",
        onClose = onClose,
        footer = {
            CardButton("Cancel", onClose, Modifier.weight(1f))
            CardButton(
                "Create new manager chat",
                ::create,
                Modifier.weight(1f).testTag("asst-create-manager"),
                icon = ProductIcon.MessageSquarePlus,
                variant = CardButtonVariant.PRIMARY,
                enabled = !creating,
            )
        },
    ) {
        BasicText(MANAGER_ASSIGN_CONSEQUENCE, style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text2.color))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText("Use one of the project's Claude chats", style = asstNote())
            when {
                sessions == null -> BasicText("Loading chats…", style = asstNote())
                chats.isEmpty() -> BasicText("No Claude chat in this project can take the role yet.", style = asstNote())
                else -> {
                    val shape = RoundedCornerShape(8.dp)
                    Column(Modifier.fillMaxWidth().cssBorder(tokens.border.color, shape)) {
                        chats.forEachIndexed { i, s ->
                            if (i > 0) AsstDivider()
                            BasicText(
                                s.title.ifEmpty { "Untitled chat" },
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 44.dp)
                                    .ppmClickable(onClickLabel = "Choose") { picked = s }
                                    .padding(horizontal = 12.dp, vertical = 12.dp)
                                    .testTag("asst-assign-chat-" + s.id),
                                asstBody(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        val shape = RoundedCornerShape(8.dp)
        Column(Modifier.fillMaxWidth().cssBorder(tokens.border.color, shape).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText("Create new manager chat", style = ppmText(14f, FontWeight.Medium).copy(color = tokens.text.color))
            BasicText(
                "A new Claude chat titled “${defaultManagerTitle(projectName)}”, in ${managerModeLabel(defaultMode)} mode" +
                    (if (defaultMode == "bypassPermissions") ", which runs without approval prompts" else "") +
                    ". Its rules start from your template.",
                style = asstNote(),
            )
        }
    }
}

/** A project's `.ppm/manager.md`: the text in force, editable, and a held change to approve or revert. */
@Composable
internal fun ManagerRulesDialog(model: AppModel, api: ApiClient, projectName: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var rules by remember(projectName) { mutableStateOf<ManagerRulesView?>(null) }
    var text by remember(projectName) { mutableStateOf("") }
    var error by remember(projectName) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(api, projectName) {
        try {
            val r = api.managerRules(projectName)
            rules = r
            text = r.inForce
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = errorText(e)
        }
    }
    fun run(done: String, close: Boolean, work: suspend () -> ManagerRulesView) {
        busy = true
        scope.launch {
            try {
                val next = work()
                model.toast(ErrorToastMessage(done, kind = ErrorToastMessage.Kind.SUCCESS))
                model.assistant.store?.refreshManagers()
                if (close) {
                    onClose()
                } else {
                    rules = next
                    text = next.inForce
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.toast(ErrorToastMessage("Could not change the rules", errorText(e)))
            } finally {
                busy = false
            }
        }
    }
    val r = rules
    val bytes = utf8Bytes(text)
    AsstDialog(
        title = "$projectName manager rules",
        description = RULES_GUARD_HINT,
        onClose = onClose,
        footer = {
            CardButton("Cancel", onClose, Modifier.weight(1f))
            CardButton(
                if (busy) "Saving…" else "Save",
                { run("Rules saved", close = true) { api.putManagerRules(projectName, text) } },
                Modifier.weight(1f).testTag("asst-rules-save"),
                variant = CardButtonVariant.PRIMARY,
                enabled = r != null && !busy && bytes <= RULES_FILE_MAX_BYTES && text != r.inForce,
            )
        },
    ) {
        val tokens = LocalPpmTokens.current
        error?.let { BasicText(it, style = ppmText(13f).copy(color = tokens.error.color)) }
        if (r == null && error == null) BasicText("Loading the rules…", style = asstNote())
        if (r != null) {
            val hash = r.pendingHash
            if (hash != null && r.proposed != null) {
                HeldChange(r, busy) { approve ->
                    run(if (approve) "Change approved" else "Change reverted", close = false) { api.decideManagerRules(projectName, approve, hash) }
                }
            }
            if (!hasManagerDelegateRule(r.inForce)) {
                NewDefaultRule(busy, dirty = text != r.inForce) {
                    run("Rule added", close = false) { api.putManagerRules(projectName, withManagerDelegateRule(r.inForce)) }
                }
            }
            RulesTextarea(".ppm/manager.md", text, !busy, "asst-rules-text") { text = it }
            SizeCounter(bytes)
            if (r.addedSince.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText("Added by the manager since", style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text3.color))
                    val shape = RoundedCornerShape(6.dp)
                    Column(Modifier.fillMaxWidth().background(tokens.panel2.color, shape).cssBorder(tokens.border.color, shape).padding(10.dp).testTag("asst-added-since")) {
                        r.addedSince.forEachIndexed { i, line ->
                            // Where the line sits decides what it means: one under "Allowed without asking" is a permission.
                            val under = r.addedSinceUnder.getOrNull(i)
                            BasicText(line + (under?.let { " — under $it" } ?: ""), style = ppmText(12f, kind = FontKind.MONO).copy(color = tokens.text2.color))
                        }
                    }
                }
            }
        }
    }
}

/**
 * A default rule PPM's template gained after this project's rules were seeded. PPM never writes it
 * into the user's file by itself; Add saves it as the user's own edit. Held back while the text has
 * unsaved edits, which Add would otherwise either save unseen or throw away.
 */
@Composable
private fun NewDefaultRule(busy: Boolean, dirty: Boolean, onAdd: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.fillMaxWidth().background(tokens.panel2.color, shape).cssBorder(tokens.border.color, shape).padding(12.dp).testTag("asst-new-default-rule"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText("PPM has a new default rule", style = ppmText(14f, FontWeight.Medium).copy(color = tokens.text.color))
        BasicText(
            "New manager chats start with it. This project’s rules were written before it, so they do not have it yet.",
            style = asstNote().copy(color = tokens.text2.color),
        )
        val inner = RoundedCornerShape(6.dp)
        // As a paragraph: the file's own line breaks, wrapped again at this width, read as ragged fragments.
        BasicText(
            MANAGER_DELEGATE_RULE_PROSE,
            Modifier.fillMaxWidth().background(tokens.panel.color, inner).cssBorder(tokens.border.color, inner).padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("asst-new-default-rule-text"),
            style = ppmText(12f, lineHeight = 19f).copy(color = tokens.text2.color),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CardButton("Add", onAdd, Modifier.testTag("asst-new-default-rule-add"), icon = ProductIcon.Plus, variant = CardButtonVariant.PRIMARY, enabled = !busy && !dirty)
            if (dirty) BasicText("Save or undo your edits first.", style = ppmText(12f).copy(color = tokens.text3.color))
        }
    }
}

@Composable
private fun HeldChange(rules: ManagerRulesView, busy: Boolean, onDecide: (approve: Boolean) -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.fillMaxWidth().background(tokens.warning.opacity(0.1).color, shape).cssBorder(tokens.warning.opacity(0.4).color, shape).padding(12.dp).testTag("asst-rules-change"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText("Rules change waiting", style = ppmText(14f, FontWeight.Medium).copy(color = tokens.text.color))
        BasicText(
            "The manager changed its rules in a way PPM does not accept on its own. The rules in force stay as they are until you choose.",
            style = asstNote().copy(color = tokens.text2.color),
        )
        val added = rules.proposedAdded.orEmpty()
        val removed = rules.proposedRemoved.orEmpty()
        if (added.isNotEmpty() || removed.isNotEmpty()) {
            val inner = RoundedCornerShape(6.dp)
            Column(Modifier.fillMaxWidth().background(tokens.panel2.color, inner).cssBorder(tokens.border.color, inner).padding(10.dp)) {
                removed.forEach { BasicText("- $it", style = ppmText(12f, kind = FontKind.MONO).copy(color = tokens.error.color)) }
                added.forEach { BasicText("+ $it", style = ppmText(12f, kind = FontKind.MONO).copy(color = tokens.success.color)) }
            }
        } else {
            BasicText("The change is too large to show line by line.", style = asstNote())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).testTag("asst-rules-approve")) {
                CardButton("Approve change", { onDecide(true) }, Modifier.fillMaxWidth(), icon = ProductIcon.Check, variant = CardButtonVariant.PRIMARY, enabled = !busy)
            }
            Box(Modifier.weight(1f).testTag("asst-rules-revert")) {
                CardButton("Revert", { onDecide(false) }, Modifier.fillMaxWidth(), icon = ProductIcon.Undo2, variant = CardButtonVariant.DANGER, enabled = !busy)
            }
        }
    }
}

/** What a project's `.ppm/manager.md` starts as; saving the built-in text stores nothing, so PPM keeps it current. */
@Composable
internal fun ManagerTemplateDialog(model: AppModel, api: ApiClient, template: String, builtIn: String, onSaved: (String) -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val initial = template.ifEmpty { builtIn }
    var text by remember { mutableStateOf(initial) }
    var saving by remember { mutableStateOf(false) }
    val bytes = utf8Bytes(text)
    fun save() {
        saving = true
        scope.launch {
            try {
                val res = api.patchAssistantSettings(buildJsonObject { put("manager_template", templateToStore(text, builtIn)) })
                onSaved(res.settings.managerTemplate)
                model.toast(ErrorToastMessage("Template saved", "New manager chats start from it. Existing rules files stay as they are.", kind = ErrorToastMessage.Kind.SUCCESS))
                onClose()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.toast(ErrorToastMessage("Could not save the template", errorText(e)))
                saving = false
            }
        }
    }
    AsstDialog(
        title = "Template for new manager rules",
        description = "Copied into .ppm/manager.md when a project gets a manager chat and has no rules file yet.",
        onClose = onClose,
        footer = {
            CardButton("Cancel", onClose, Modifier.weight(1f))
            CardButton(
                if (saving) "Saving…" else "Save",
                ::save,
                Modifier.weight(1f).testTag("asst-template-save"),
                variant = CardButtonVariant.PRIMARY,
                enabled = !saving && bytes <= RULES_FILE_MAX_BYTES && text != initial,
            )
        },
    ) {
        RulesTextarea("Template", text, !saving, "asst-template-text") { text = it }
        SizeCounter(bytes)
        Box(Modifier.testTag("asst-template-reset")) {
            CardButton("Reset to built-in", { text = builtIn }, icon = ProductIcon.RotateCcw, enabled = !saving && text != builtIn)
        }
    }
}
