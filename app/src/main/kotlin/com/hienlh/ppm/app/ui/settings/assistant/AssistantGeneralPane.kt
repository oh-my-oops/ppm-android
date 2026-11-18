package com.hienlh.ppm.app.ui.settings.assistant

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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.assistant
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.PpmSwitch
import com.hienlh.ppm.app.ui.SelectField
import com.hienlh.ppm.app.ui.SelectOption
import com.hienlh.ppm.app.ui.SettingsButton
import com.hienlh.ppm.app.ui.SettingsTextInput
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.assistantSettings
import com.hienlh.ppm.client.legacyMemories
import com.hienlh.ppm.client.patchAssistantSettings
import com.hienlh.ppm.client.saveAssistantGeneral
import com.hienlh.ppm.client.settingsModels
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.ASSISTANT_EFFORTS
import com.hienlh.ppm.protocol.AssistantAddressForm
import com.hienlh.ppm.protocol.AssistantGeneralDraft
import com.hienlh.ppm.protocol.AssistantMcpServer
import com.hienlh.ppm.protocol.AssistantPersona
import com.hienlh.ppm.protocol.AssistantProviderDefaults
import com.hienlh.ppm.protocol.AssistantSettingsResponse
import com.hienlh.ppm.protocol.LegacyMemory
import com.hienlh.ppm.protocol.ModelOption
import com.hienlh.ppm.protocol.PERSONA_ADDRESS_FIELD_MAX_CHARS
import com.hienlh.ppm.protocol.PERSONA_ADDRESS_WHEN_MAX_CHARS
import com.hienlh.ppm.protocol.PERSONA_MAX_ADDRESS_FORMS
import com.hienlh.ppm.protocol.PERSONA_NAME_MAX_CHARS
import com.hienlh.ppm.protocol.PERSONA_SOUL_MAX_CHARS
import com.hienlh.ppm.state.assistant.appendToInstructions
import com.hienlh.ppm.state.assistant.foldMcpName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.NumberFormat

/*
 * Settings → PPM Assistant → General (`assistant-general-settings.tsx`): the provider new sessions
 * start on, each provider's model and effort, the persona, the instructions (with what the old
 * PPMBot remembered, copied in only on request), the MCP servers — one Save for all of it — and
 * the permission switches below, which save as they flip.
 */

private const val CHAT_DEFAULT = "__chat__"

internal const val READONLY_COMMANDS_LABEL = "Run read-only commands without asking"
internal const val READONLY_COMMANDS_HINT = "Commands PPM can prove only read — git status, listing files in your projects, process and memory info — run without a card. Anything else still asks. Never reaches your keys, credential folders or PPM's own data."
internal const val ACT_ON_OWN_LABEL = "Let the Assistant act on its own"
internal const val ACT_ON_OWN_HINT = "Edits files and runs commands without asking, in conversations you start. PPM's own approvals and your private folders still ask."
internal const val ACT_ON_OWN_CONFIRM = "The Assistant will edit files in your projects and run any command without asking, in conversations you start (not in watch or scheduled turns). It reads other chats, logs and web results, and text in them can try to steer it. PPM's own approvals (database changes, git, messages to chats) and your private folders still ask. Edits become available from the Assistant's next session start."

/** The General pane's draft, kept by the section so switching sub-tabs keeps it unsaved. */
@Stable
internal class AssistantGeneralState {
    var loaded by mutableStateOf<AssistantSettingsResponse?>(null)
    var draft by mutableStateOf<AssistantGeneralDraft?>(null)
    var error by mutableStateOf<String?>(null)
    var saving by mutableStateOf(false)

    val dirty: Boolean get() = loaded != null && draft != null && loaded?.settings?.general != draft

    fun take(res: AssistantSettingsResponse) {
        loaded = res
        draft = res.settings.general
    }
}

private fun count(n: Int): String = NumberFormat.getIntegerInstance().format(n)

@Composable
internal fun AssistantGeneralPane(model: AppModel, api: ApiClient, state: AssistantGeneralState) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(api) {
        if (state.loaded != null) return@LaunchedEffect
        try {
            state.take(api.assistantSettings())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.error = e.message?.takeIf { it.isNotEmpty() } ?: "Could not load the Assistant's settings"
        }
    }
    val loaded = state.loaded
    val draft = state.draft
    if (loaded == null || draft == null) {
        AsstLoading(state.error)
        return
    }
    var editing by remember { mutableStateOf<Int?>(null) }
    var adding by remember { mutableStateOf(false) }
    fun patch(next: AssistantGeneralDraft) {
        state.draft = next
    }
    val saving = state.saving
    val servers = draft.mcpServers
    val persona = draft.persona
    val tooLong = draft.instructions.length > loaded.instructionsMaxChars || persona.soul.length > PERSONA_SOUL_MAX_CHARS

    Column(Modifier.fillMaxWidth().testTag("assistant-general"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        AsstHeader(
            "PPM Assistant",
            "Assistant sessions use only these settings — none of your chats' settings, MCP servers, hooks, plugins, skills or memory. " +
                "Changes apply from the next message on Claude, and from the next time a Codex Assistant session starts. " +
                "The chat box can still change the model and effort of one session.",
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText("New sessions start on", style = asstNote())
            SelectField(
                value = draft.defaultProvider ?: CHAT_DEFAULT,
                options = listOf(SelectOption(CHAT_DEFAULT, "Same as chats")) + loaded.providers.map { SelectOption(it.first, it.second) },
                onValueChange = { v -> patch(draft.copy(defaultProvider = v.takeIf { it != CHAT_DEFAULT })) },
                modifier = Modifier.fillMaxWidth().testTag("asst-default-provider"),
                enabled = !saving,
            )
        }
        for ((id, name) in loaded.providers) {
            ProviderDefaultsRow(api, id, name, draft.providers[id] ?: AssistantProviderDefaults(), enabled = !saving) { next ->
                patch(draft.copy(providers = if (next.isEmpty) draft.providers - id else draft.providers + (id to next)))
            }
        }
        PersonaFields(persona, enabled = !saving) { patch(draft.copy(persona = it)) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText("Your instructions", style = asstNote())
            SettingsTextInput(
                draft.instructions,
                { patch(draft.copy(instructions = it)) },
                "Added after PPM's own rules, which still come first. For example: answer in Vietnamese.",
                Modifier.fillMaxWidth().heightIn(min = 88.dp).testTag("asst-instructions"),
                singleLine = false,
            )
            val over = draft.instructions.length > loaded.instructionsMaxChars
            BasicText(
                "${count(draft.instructions.length)} / ${count(loaded.instructionsMaxChars)} characters",
                style = ppmText(12f).copy(color = (if (over) tokens.error else tokens.text3).color),
            )
            LegacyMemories(api, draft.instructions, enabled = !saving) { content ->
                patch(draft.copy(instructions = appendToInstructions(draft.instructions, content)))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AsstHeader(
                "MCP servers",
                "Tool servers for Assistant sessions only. Every tool they offer asks you before it runs. " +
                    "Variable and header values stay on this machine and are never shown again.",
            )
            if (servers.isEmpty()) BasicText("No MCP servers yet.", style = asstNote())
            if (servers.isNotEmpty()) {
                val shape = RoundedCornerShape(8.dp)
                Column(Modifier.fillMaxWidth().cssBorder(tokens.border.color, shape)) {
                    servers.forEachIndexed { i, s ->
                        if (i > 0) AsstDivider()
                        McpServerRow(s, enabled = !saving, onEdit = { editing = i }, onToggle = { on ->
                            patch(draft.copy(mcpServers = servers.mapIndexed { idx, x -> if (idx == i) x.copy(enabled = on) else x }))
                        }, onRemove = { patch(draft.copy(mcpServers = servers.filterIndexed { idx, _ -> idx != i })) })
                    }
                }
            }
            SettingsButton(
                "+ Add MCP server",
                enabled = !saving && servers.size < loaded.maxServers,
                modifier = Modifier.testTag("asst-add-mcp"),
            ) { adding = true }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(if (state.dirty) "Unsaved changes" else "", Modifier.weight(1f), asstNote())
            SettingsButton("Discard", enabled = state.dirty && !saving, modifier = Modifier.testTag("asst-general-discard")) { state.draft = loaded.settings.general }
            SettingsButton(
                if (saving) "Saving…" else "Save",
                primary = true,
                enabled = state.dirty && !saving && !tooLong,
                modifier = Modifier.testTag("asst-general-save"),
            ) {
                state.saving = true
                scope.launch {
                    try {
                        val res = api.saveAssistantGeneral(loaded.settings.raw, draft)
                        state.take(res)
                        model.assistant.store?.rememberName(res.settings.general.persona.name)
                        model.toast(ErrorToastMessage("Assistant settings saved", kind = ErrorToastMessage.Kind.SUCCESS))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        model.toast(ErrorToastMessage("Could not save", e.message))
                    } finally {
                        state.saving = false
                    }
                }
            }
        }
        PermissionsCard(model, api)
    }

    val target = editing?.let { servers.getOrNull(it) }
    if (adding || target != null) {
        val index = editing
        val savedServer = target?.let { t -> loaded.settings.general.mcpServers.firstOrNull { it.id.isNotEmpty() && it.id == t.id } }
        AssistantMcpServerDialog(
            server = target,
            savedKeys = savedServer?.pairs?.keys.orEmpty(),
            takenNames = servers.filterIndexed { i, _ -> i != index }.map { foldMcpName(it.name) }.toSet(),
            onClose = { editing = null; adding = false },
            onDone = { server ->
                patch(draft.copy(mcpServers = if (index == null) servers + server else servers.mapIndexed { i, s -> if (i == index) server else s }))
                editing = null
                adding = false
            },
        )
    }
}

@Composable
private fun McpServerRow(server: AssistantMcpServer, enabled: Boolean, onEdit: () -> Unit, onToggle: (Boolean) -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 12.dp, end = 4.dp).testTag("asst-mcp-" + server.name),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f).ppmClickable(onClickLabel = "Edit " + server.name, onClick = onEdit).padding(vertical = 8.dp)) {
            BasicText(server.name, style = asstBody(), maxLines = 1)
            BasicText(server.summary, style = asstNote(), maxLines = 1)
        }
        PpmSwitch(server.enabled, onToggle, enabled = enabled)
        AsstIconButton(ProductIcon.Pencil, "Edit " + server.name, enabled = enabled, onClick = onEdit)
        AsstIconButton(ProductIcon.Trash2, "Remove " + server.name, danger = true, enabled = enabled, onClick = onRemove)
    }
}

/** `AssistantProviderDefaultsRow`: the model and effort new sessions on one provider start with. */
@Composable
private fun ProviderDefaultsRow(api: ApiClient, id: String, name: String, value: AssistantProviderDefaults, enabled: Boolean, onChange: (AssistantProviderDefaults) -> Unit) {
    val tokens = LocalPpmTokens.current
    var models by remember(id) { mutableStateOf<List<ModelOption>>(emptyList()) }
    LaunchedEffect(api, id) {
        models = try {
            api.settingsModels(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }
    // A saved model the list does not hold (a renamed or retired one) still shows as chosen.
    val options = models.map { SelectOption(it.value, it.label) } +
        listOfNotNull(value.model?.takeIf { m -> models.none { it.value == m } }?.let { SelectOption(it, it) })
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.fillMaxWidth().cssBorder(tokens.border.color, shape).padding(12.dp).testTag("asst-provider-$id"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(name, style = ppmText(14f, FontWeight.Medium).copy(color = tokens.text.color))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText("Model", style = asstNote())
            SelectField(
                value = value.model ?: CHAT_DEFAULT,
                options = listOf(SelectOption(CHAT_DEFAULT, "Same as chats")) + options,
                onValueChange = { v -> onChange(value.copy(model = v.takeIf { it != CHAT_DEFAULT })) },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText("Effort", style = asstNote())
            SelectField(
                value = value.effort ?: CHAT_DEFAULT,
                options = listOf(SelectOption(CHAT_DEFAULT, "Same as chats")) + ASSISTANT_EFFORTS.map { SelectOption(it.first, it.second) },
                onValueChange = { v -> onChange(value.copy(effort = v.takeIf { it != CHAT_DEFAULT })) },
                modifier = Modifier.fillMaxWidth().testTag("asst-effort-$id"),
                enabled = enabled,
            )
        }
    }
}

/** `AssistantPersonaSettings`: the name, the ways it may address the user, and its personality. */
@Composable
private fun PersonaFields(value: AssistantPersona, enabled: Boolean, onChange: (AssistantPersona) -> Unit) {
    val tokens = LocalPpmTokens.current
    val forms = value.addressForms
    fun setForm(i: Int, next: AssistantAddressForm) = onChange(value.copy(addressForms = forms.mapIndexed { idx, f -> if (idx == i) next else f }))
    Column(Modifier.fillMaxWidth().testTag("asst-persona"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AsstHeader("Name and personality", "How the Assistant talks to you. This changes only its voice: every rule, approval and limit stays the same.")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText("Name", style = asstNote())
            SettingsTextInput(value.name, { onChange(value.copy(name = it.take(PERSONA_NAME_MAX_CHARS))) }, "PPM Assistant", Modifier.fillMaxWidth().testTag("asst-persona-name"))
            BasicText("Shown on its window, its cards and its Telegram messages. Empty keeps “PPM Assistant”.", style = asstNote())
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column {
                BasicText("Ways to address you", style = asstNote())
                BasicText("It picks one by the moment — say when each fits — and keeps it for the whole message.", style = asstNote())
            }
            if (forms.isEmpty()) BasicText("None yet: it addresses you however it sees fit.", style = asstNote())
            forms.forEachIndexed { i, form ->
                val shape = RoundedCornerShape(8.dp)
                Column(
                    Modifier.fillMaxWidth().cssBorder(tokens.border.color, shape).padding(8.dp).testTag("asst-address-form-$i"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                        LabeledInput("Calls you", form.user, "anh", Modifier.weight(1f)) { setForm(i, form.copy(user = it.take(PERSONA_ADDRESS_FIELD_MAX_CHARS))) }
                        LabeledInput("Calls itself", form.self, "em", Modifier.weight(1f)) { setForm(i, form.copy(self = it.take(PERSONA_ADDRESS_FIELD_MAX_CHARS))) }
                        AsstIconButton(ProductIcon.Trash2, "Remove form of address ${i + 1}", danger = true, enabled = enabled) {
                            onChange(value.copy(addressForms = forms.filterIndexed { idx, _ -> idx != i }))
                        }
                    }
                    LabeledInput("When", form.whenToUse, "e.g. urgent news, asking for approval", Modifier.fillMaxWidth()) {
                        setForm(i, form.copy(whenToUse = it.take(PERSONA_ADDRESS_WHEN_MAX_CHARS)))
                    }
                }
            }
            SettingsButton(
                "+ Add a way to address you",
                enabled = enabled && forms.size < PERSONA_MAX_ADDRESS_FORMS,
                modifier = Modifier.testTag("asst-add-address-form"),
            ) { onChange(value.copy(addressForms = forms + AssistantAddressForm())) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText("Personality", style = asstNote())
            SettingsTextInput(
                value.soul,
                { onChange(value.copy(soul = it)) },
                "For example: cheerful and warm, jokes a little when things go well, serious and brief when something breaks.",
                Modifier.fillMaxWidth().heightIn(min = 88.dp).testTag("asst-persona-soul"),
                singleLine = false,
            )
            val over = value.soul.length > PERSONA_SOUL_MAX_CHARS
            BasicText(
                "${count(value.soul.length)} / ${count(PERSONA_SOUL_MAX_CHARS)} characters",
                style = ppmText(12f).copy(color = (if (over) tokens.error else tokens.text3).color),
            )
        }
    }
}

@Composable
private fun LabeledInput(label: String, value: String, placeholder: String, modifier: Modifier, onChange: (String) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(label, style = ppmText(11f).copy(color = LocalPpmTokens.current.text3.color))
        SettingsTextInput(value, onChange, placeholder, Modifier.fillMaxWidth())
    }
}

/**
 * `AssistantLegacyMemories`: what the old PPMBot remembered, shown beside the instructions. None is
 * used unless the user copies it into the unsaved draft; hidden when there is nothing to show.
 */
@Composable
private fun LegacyMemories(api: ApiClient, instructions: String, enabled: Boolean, onCopy: (String) -> Unit) {
    val tokens = LocalPpmTokens.current
    var memories by remember(api) { mutableStateOf<List<LegacyMemory>>(emptyList()) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(api) {
        memories = try {
            api.legacyMemories()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }
    if (memories.isEmpty()) return
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).cssBorder(tokens.border.color, shape).testTag("assistant-legacy-memories")) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).ppmClickable(onClickLabel = if (open) "Hide" else "Show") { open = !open }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                BasicText("Remembered by PPMBot (${memories.size})", style = asstBody())
                BasicText("Written by the old bot's AI, so none is used unless you copy it into your instructions.", style = asstNote())
            }
            PpmIcon(if (open) ProductIcon.ChevronUp else ProductIcon.ChevronDown, 16.dp, tokens.text3.color)
        }
        if (open) {
            memories.forEach { m ->
                AsstDivider()
                val copied = m.content in instructions
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        BasicText(m.content, style = asstBody())
                        val where = listOfNotNull(m.category.takeIf { it.isNotEmpty() }, m.project.takeIf { it != "_global" }).joinToString(" · ")
                        if (where.isNotEmpty()) BasicText(where, style = asstNote())
                    }
                    Box(Modifier.testTag("asst-memory-copy-" + m.id)) {
                        SettingsButton(if (copied) "Copied" else "Copy to instructions", enabled = enabled && !copied) { onCopy(m.content) }
                    }
                }
            }
        }
    }
}

/** `AssistantPermissionsSettings`: each switch saves as it flips; turning "act on its own" on asks first. */
@Composable
private fun PermissionsCard(model: AppModel, api: ApiClient) {
    val scope = rememberCoroutineScope()
    var readonly by remember(api) { mutableStateOf<Boolean?>(null) }
    var actOnOwn by remember(api) { mutableStateOf(false) }
    var error by remember(api) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(api) {
        try {
            val s = api.assistantSettings().settings
            readonly = s.autoReadonlyCommands
            actOnOwn = s.actOnOwn
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Could not load the Assistant's settings"
        }
    }
    fun save(key: String, on: Boolean) {
        saving = true
        scope.launch {
            try {
                val s = api.patchAssistantSettings(buildJsonObject { put(key, on) }).settings
                readonly = s.autoReadonlyCommands
                actOnOwn = s.actOnOwn
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.toast(ErrorToastMessage("Could not save", e.message))
            } finally {
                saving = false
            }
        }
    }
    val current = readonly
    Box(Modifier.testTag("assistant-permissions")) {
        if (current == null) {
            AsstLoading(error)
        } else {
            AsstCard(ProductIcon.ShieldCheck, "Permissions") {
                AsstLine(READONLY_COMMANDS_LABEL, READONLY_COMMANDS_HINT) {
                    Box(Modifier.testTag("asst-readonly-switch")) { PpmSwitch(current, { save("auto_readonly_commands", it) }, enabled = !saving) }
                }
                AsstDivider()
                AsstLine(ACT_ON_OWN_LABEL, ACT_ON_OWN_HINT) {
                    Box(Modifier.testTag("asst-act-on-own-switch")) {
                        PpmSwitch(actOnOwn, { on -> if (on) confirming = true else save("act_on_own", false) }, enabled = !saving)
                    }
                }
            }
        }
    }
    if (confirming) {
        AsstConfirm("$ACT_ON_OWN_LABEL?", ACT_ON_OWN_CONFIRM, "Turn on", onClose = { confirming = false }) {
            confirming = false
            save("act_on_own", true)
        }
    }
}
