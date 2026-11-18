package com.hienlh.ppm.app.ui.settings.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.hienlh.ppm.app.ui.PpmSwitch
import com.hienlh.ppm.app.ui.SelectField
import com.hienlh.ppm.app.ui.SelectOption
import com.hienlh.ppm.app.ui.SettingsButton
import com.hienlh.ppm.app.ui.assistant.MANAGER_TOOLS_NEXT_START
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.assistantSettings
import com.hienlh.ppm.client.patchAssistantSettings
import com.hienlh.ppm.client.patchManagerChat
import com.hienlh.ppm.client.unsetManagerChat
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.ManagerChatSummary
import com.hienlh.ppm.state.assistant.BYPASS_DEFAULT_CONSEQUENCE
import com.hienlh.ppm.state.assistant.MANAGER_MODE_OPTIONS
import com.hienlh.ppm.state.assistant.ManagerTableRow
import com.hienlh.ppm.state.assistant.RULES_GUARD_HINT
import com.hienlh.ppm.state.assistant.managerModeLabel
import com.hienlh.ppm.state.assistant.managerTableRows
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Settings → PPM Assistant → Manager chats (`assistant-manager-chats-section.tsx`): one row per
 * project — its manager chat, mode, rules, Telegram detail and digest switch — the default mode for
 * new manager chats, and the template new rules files start from. The list follows
 * `assistant:managers_changed` through the Assistant's store.
 */

private data class ManagerDefaults(val mode: String, val template: String, val builtIn: String)

private val TELEGRAM_DETAIL_OPTIONS = listOf(SelectOption("full", "Full"), SelectOption("title", "Title only"))

private fun errorText(e: Exception) = e.message ?: e.toString()

@Composable
internal fun AssistantManagersPane(model: AppModel, api: ApiClient) {
    val scope = rememberCoroutineScope()
    val store = model.assistant.store
    val managers = store?.view?.collectAsState()?.value?.managers
    val rows = managerTableRows(model.projects.map { it.name }, managers.orEmpty())
    var defaults by remember(api) { mutableStateOf<ManagerDefaults?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var assigning by remember { mutableStateOf<String?>(null) }
    var editingRules by remember { mutableStateOf<String?>(null) }
    var unsetting by remember { mutableStateOf<ManagerChatSummary?>(null) }
    var menuFor by remember { mutableStateOf<ManagerTableRow?>(null) }
    var confirmBypass by remember { mutableStateOf(false) }
    var editingTemplate by remember { mutableStateOf(false) }

    LaunchedEffect(api) {
        // Each visit asks again: whether a chat's tools still wait for its next start is not announced.
        store?.refreshManagers()
        try {
            val r = api.assistantSettings()
            defaults = ManagerDefaults(r.settings.managerDefaultMode, r.settings.managerTemplate, r.managerTemplateBuiltIn)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            model.toast(ErrorToastMessage("Could not load the Assistant's settings", errorText(e)))
        }
    }
    fun saveMode(mode: String) {
        scope.launch {
            try {
                val r = api.patchAssistantSettings(buildJsonObject { put("manager_default_mode", mode) })
                defaults = defaults?.copy(mode = r.settings.managerDefaultMode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.toast(ErrorToastMessage("Could not save the default mode", errorText(e)))
            }
        }
    }
    fun patch(m: ManagerChatSummary, digest: Boolean? = null, telegramDetail: String? = null) {
        busy = m.projectName
        scope.launch {
            try {
                api.patchManagerChat(m.projectName, digest, telegramDetail)
                store?.refreshManagers()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.toast(ErrorToastMessage("Could not save", errorText(e)))
            } finally {
                busy = null
            }
        }
    }
    fun unset(m: ManagerChatSummary) {
        scope.launch {
            try {
                api.unsetManagerChat(m.projectName)
                store?.refreshManagers()
                model.toast(ErrorToastMessage("${m.projectName} has no manager chat now", "The chat keeps its history.", kind = ErrorToastMessage.Kind.SUCCESS))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.toast(ErrorToastMessage("Could not unset the manager chat", errorText(e)))
            }
        }
    }

    val mobile = LocalPpmMobile.current
    Column(Modifier.fillMaxWidth().testTag("assistant-managers-section"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AsstCard(
            ProductIcon.Users,
            "Manager chats",
            aside = {
                defaults?.let { d ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BasicText("Default mode for new manager chats", style = asstNote())
                        SelectField(
                            value = d.mode,
                            options = MANAGER_MODE_OPTIONS.map { SelectOption(it.first, it.second) },
                            onValueChange = { v -> if (v == "bypassPermissions") confirmBypass = true else saveMode(v) },
                            modifier = Modifier.width(176.dp).testTag("asst-manager-default-mode"),
                        )
                    }
                }
            },
        ) {
            if (!mobile) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for ((label, weight) in COLUMNS) HeadCell(label, weight)
                    Box(Modifier.width(32.dp))
                }
                AsstDivider()
            }
            when {
                managers == null -> BasicText("Loading…", Modifier.padding(14.dp), asstNote())
                rows.isEmpty() -> BasicText("No projects yet.", Modifier.padding(14.dp), asstNote())
                else -> rows.forEachIndexed { i, row ->
                    if (i > 0) AsstDivider()
                    ManagerRow(
                        row = row,
                        busy = busy == row.projectName,
                        onOpen = { m -> model.assistant.openManagerChat(m.projectName, m.sessionId, m.title) },
                        onEditRules = { editingRules = row.projectName },
                        onAssign = { assigning = row.projectName },
                        onMenu = { menuFor = row },
                        onPatch = ::patch,
                    )
                }
            }
            AsstDivider()
            AsstLine(
                "Template for new manager rules",
                "Copied into .ppm/manager.md when a project gets a manager chat. The manager may tighten its rules; loosening them waits for you.",
            ) {
                SettingsButton("Edit template", enabled = defaults != null, modifier = Modifier.testTag("asst-edit-template")) { editingTemplate = true }
            }
        }
        BasicText(RULES_GUARD_HINT, Modifier.padding(horizontal = 4.dp), asstNote())
    }

    menuFor?.let { row ->
        val m = row.manager
        AsstDialog(title = row.projectName, onClose = { menuFor = null }) {
            if (row.registered) {
                Box(Modifier.testTag("asst-manager-replace")) {
                    com.hienlh.ppm.app.ui.assistant.CardButton("Replace…", { menuFor = null; assigning = row.projectName }, Modifier.fillMaxWidth())
                }
            }
            if (m != null) {
                Box(Modifier.testTag("asst-manager-unset")) {
                    com.hienlh.ppm.app.ui.assistant.CardButton(
                        "Unset",
                        { menuFor = null; unsetting = m },
                        Modifier.fillMaxWidth(),
                        variant = com.hienlh.ppm.app.ui.assistant.CardButtonVariant.DANGER,
                    )
                }
            }
        }
    }
    val d = defaults
    assigning?.let { project ->
        if (d != null) {
            AssignManagerDialog(model, api, project, managers.orEmpty().firstOrNull { it.projectName == project }, d.mode) { assigning = null }
        }
    }
    editingRules?.let { project -> ManagerRulesDialog(model, api, project) { editingRules = null } }
    if (editingTemplate && d != null) {
        ManagerTemplateDialog(model, api, d.template, d.builtIn, onSaved = { defaults = d.copy(template = it) }) { editingTemplate = false }
    }
    if (confirmBypass) {
        AsstConfirm(
            "Bypass permissions for new manager chats?",
            "$BYPASS_DEFAULT_CONSEQUENCE.",
            "Use bypass",
            destructive = true,
            onClose = { confirmBypass = false },
        ) {
            confirmBypass = false
            saveMode("bypassPermissions")
        }
    }
    unsetting?.let { m ->
        AsstConfirm(
            "Unset the ${m.projectName} manager chat?",
            "PPM Assistant stops messaging “${m.title?.takeIf { it.isNotEmpty() } ?: "this chat"}” about the project. The chat and its rules file stay.",
            "Unset",
            onClose = { unsetting = null },
        ) {
            unsetting = null
            unset(m)
        }
    }
}

private val COLUMNS = listOf("Project" to 0.8f, "Manager chat" to 1.2f, "Mode" to 0.7f, "Rules" to 1.3f, "Telegram" to 0.9f, "Digest" to 0.5f)

@Composable
private fun RowScope.HeadCell(label: String, weight: Float) {
    BasicText(label.uppercase(), Modifier.weight(weight), ppmText(11f, FontWeight.SemiBold).copy(color = LocalPpmTokens.current.text3.color))
}

/** One project of the table: a row of cells on a tablet, the same cells stacked with labels on a phone. */
@Composable
private fun ManagerRow(
    row: ManagerTableRow,
    busy: Boolean,
    onOpen: (ManagerChatSummary) -> Unit,
    onEditRules: () -> Unit,
    onAssign: () -> Unit,
    onMenu: () -> Unit,
    onPatch: (ManagerChatSummary, Boolean?, String?) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val m = row.manager
    val tag = Modifier.testTag("asst-manager-row-" + row.projectName)
    val project = @Composable { mod: Modifier ->
        BasicText(row.projectName, mod, ppmText(14f, FontWeight.Medium).copy(color = tokens.text.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (m == null) {
        Row(tag.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            project(Modifier.weight(1f))
            if (!mobile) BasicText("No manager chat", Modifier.weight(2f), asstNote())
            Box(Modifier.testTag("asst-manager-assign-" + row.projectName)) { SettingsButton("+ Assign / create", onClick = onAssign) }
        }
        return
    }
    val chat = @Composable {
        Column {
            BasicText(
                m.title?.takeIf { it.isNotEmpty() } ?: "Untitled chat",
                Modifier.heightIn(min = if (mobile) 44.dp else 32.dp).ppmClickable(onClickLabel = "Open the ${row.projectName} manager chat") { onOpen(m) }.padding(vertical = 6.dp),
                ppmText(14f).copy(color = tokens.accent.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (m.toolsPending) BasicText(MANAGER_TOOLS_NEXT_START, style = ppmText(12f).copy(color = tokens.warning.color))
        }
    }
    val mode = @Composable { BasicText(managerModeLabel(m.permissionMode), style = asstBody().copy(color = tokens.text2.color)) }
    val rules = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText(".ppm/manager.md", style = ppmText(12f, kind = FontKind.MONO).copy(color = tokens.text2.color), maxLines = 1)
            Box(Modifier.testTag("asst-manager-rules-" + row.projectName)) { SettingsButton("Edit", enabled = row.registered, onClick = onEditRules) }
            if (m.rulesPending) AsstPill("Change waiting", warning = true, tag = "asst-rules-pending-" + row.projectName)
        }
    }
    val telegram = @Composable {
        SelectField(
            value = m.telegramDetail,
            options = TELEGRAM_DETAIL_OPTIONS,
            onValueChange = { onPatch(m, null, it) },
            modifier = Modifier.width(120.dp).testTag("asst-manager-telegram-" + row.projectName),
            enabled = !busy && row.registered,
        )
    }
    val digest = @Composable {
        Box(Modifier.testTag("asst-manager-digest-" + row.projectName)) {
            PpmSwitch(m.digest, { onPatch(m, it, null) }, enabled = !busy && row.registered)
        }
    }
    val menu = @Composable { AsstIconButton(ProductIcon.MoreHorizontal, "Manager chat actions for ${row.projectName}", Modifier.testTag("asst-manager-menu-" + row.projectName), onClick = onMenu) }
    if (mobile) {
        Column(tag.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                project(Modifier.weight(1f))
                menu()
            }
            chat()
            Cell("Mode") { mode() }
            Cell("Rules") { rules() }
            Cell("Telegram") { telegram() }
            Cell("Digest") { digest() }
        }
    } else {
        Row(tag.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            project(Modifier.weight(COLUMNS[0].second))
            Box(Modifier.weight(COLUMNS[1].second)) { chat() }
            Box(Modifier.weight(COLUMNS[2].second)) { mode() }
            Box(Modifier.weight(COLUMNS[3].second)) { rules() }
            Box(Modifier.weight(COLUMNS[4].second)) { telegram() }
            Box(Modifier.weight(COLUMNS[5].second)) { digest() }
            menu()
        }
    }
}

@Composable
private fun Cell(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        BasicText(label, style = asstNote())
        content()
    }
}
