package com.hienlh.ppm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.client.aiSettings
import com.hienlh.ppm.client.deleteTeam
import com.hienlh.ppm.client.mcpAuthStatus
import com.hienlh.ppm.client.probeProvider
import com.hienlh.ppm.client.providerStatuses
import com.hienlh.ppm.client.settingsModels
import com.hienlh.ppm.client.teams
import com.hienlh.ppm.client.updateAiSettings
import com.hienlh.ppm.design.CssColumn
import com.hienlh.ppm.design.CssGridRow
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFormat
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssBorderPx
import com.hienlh.ppm.design.cssExact
import com.hienlh.ppm.design.cssHeight
import com.hienlh.ppm.design.cssLines
import com.hienlh.ppm.design.cssScrollY
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberCssExact
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.AiProviderSettings
import com.hienlh.ppm.protocol.AiSettings
import com.hienlh.ppm.protocol.AiSettingsPatch
import com.hienlh.ppm.protocol.McpServerState
import com.hienlh.ppm.protocol.ModelOption
import com.hienlh.ppm.protocol.ProviderProbeStatus
import com.hienlh.ppm.protocol.TeamInfo
import com.hienlh.ppm.protocol.jsNumber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs
import kotlin.math.floor

private val EffortOptions = listOf(
    SelectOption("low", "Low"),
    SelectOption("medium", "Medium"),
    SelectOption("high", "High"),
    SelectOption("xhigh", "Extra"),
    SelectOption("max", "Max"),
)

internal val AntigravityEffortOptions = listOf(
    SelectOption("low", "Low"),
    SelectOption("medium", "Medium"),
    SelectOption("high", "High"),
)

private val PermissionModeOptions = listOf(
    SelectOption("bypassPermissions", "Bypass permissions (default)"),
    SelectOption("default", "Ask before edits"),
    SelectOption("acceptEdits", "Edit automatically"),
    SelectOption("plan", "Plan mode"),
)

private val NewChatProviderOptions = listOf(
    SelectOption("follow-focus", "Follow last focused chat"),
    SelectOption("default", "Always use default provider"),
)

private val ProviderNames = mapOf(
    "claude" to "Claude",
    "cursor" to "Cursor",
    "codex" to "Codex",
    "gemini" to "Gemini",
    "antigravity" to "Antigravity",
)

private fun providerName(id: String) = ProviderNames[id] ?: id

/** The model select's "Auto (default)", for a provider that picks its own model. */
private const val DEFAULT_MODEL = "__default__"

private val WindowPresets = listOf(128_000L, 272_000L, 400_000L, 512_000L, 872_000L, 1_000_000L)
private val CompactPresets = listOf(100_000L, 200_000L, 230_000L, 350_000L, 450_000L, 750_000L, 900_000L)

private const val CODEX_CONTEXT_DOCS = "https://learn.chatgpt.com/docs/config-file/config-reference"

/** CSS `text-transform: capitalize`: the first letter of every word in capitals. */
internal fun capitalized(text: String): String = buildString {
    var start = true
    for (c in text) {
        append(if (start) c.uppercaseChar() else c)
        start = !(c.isLetterOrDigit() || c == '_' || c == '\'')
    }
}

private val HtmlFloat = Regex("-?(\\d+(\\.\\d+)?|\\.\\d+)([eE][-+]?\\d+)?")

/** What an `<input type=number>` reports as its value: its text when that is a valid floating-point number, else nothing. */
internal fun htmlNumberValue(text: String): String = if (HtmlFloat.matches(text)) text else ""

private val LeadingInt = Regex("^\\s*([-+]?\\d+)")
private val LeadingFloat = Regex("^\\s*([-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?)")

/** JavaScript's `parseInt(text)`: the whole number it starts with, null where JavaScript has NaN. */
internal fun jsParseInt(text: String): Double? = LeadingInt.find(text)?.groupValues?.get(1)?.toDouble()

/** JavaScript's `parseFloat(text)`: the number it starts with, null where JavaScript has NaN. */
internal fun jsParseFloat(text: String): Double? = LeadingFloat.find(text)?.groupValues?.get(1)?.toDouble()

/** A number as `JSON.stringify` writes it: whole without a fraction, and null when it is not finite. */
internal fun wireNumber(value: Double?): Number? = when {
    value == null || !value.isFinite() -> null
    value == floor(value) && abs(value) < 9.0e18 -> value.toLong()
    else -> value
}

/**
 * `Number.isSafeInteger(Number(text)) && > 0` for a blank-or-number field: null when blank,
 * else the whole number, or [Invalid] when it is anything else.
 */
private sealed interface TokenCount {
    data object Blank : TokenCount

    data object Invalid : TokenCount

    data class Tokens(val value: Long) : TokenCount
}

private fun tokenCount(text: String): TokenCount {
    if (text.isBlank()) return TokenCount.Blank
    val value = text.trim().toDoubleOrNull() ?: return TokenCount.Invalid
    return if (value == floor(value) && value > 0 && value <= 9_007_199_254_740_991.0) TokenCount.Tokens(value.toLong()) else TokenCount.Invalid
}

/**
 * The chat toolbar's AI settings (chat-history-bar.tsx's config panel): `border-t border-border/30
 * bg-surface px-3 py-2 max-h-[280px] overflow-y-auto` around the compact `AISettingsSection`.
 */
@Composable
internal fun AiSettingsPanel(model: AppModel) {
    val tokens = LocalPpmTokens.current
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .background(tokens.panel.color)
            .line(top = true, tokens.border.opacity(0.3).color)
            .cssScrollY()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        AiSettingsSection(model)
    }
}

/** What `AISettingsSection` keeps: the settings as last read or saved, the provider tab open, and what that tab loaded. */
private class AiSettingsState(private val model: AppModel) {
    var settings by mutableStateOf<AiSettings?>(null)
    var activeTab by mutableStateOf("")
    var models by mutableStateOf<List<ModelOption>>(emptyList())
    var modelsLoading by mutableStateOf(false)
    var saving by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    /** Every save bumps it, as the web's `revision`, so the fields show what was saved. */
    var revision by mutableIntStateOf(0)
    var probeStatuses by mutableStateOf<List<ProviderProbeStatus>>(emptyList())
    var probing by mutableStateOf(false)

    suspend fun load() {
        try {
            val loaded = api().aiSettings()
            settings = loaded
            activeTab = loaded.defaultProvider.ifEmpty { "claude" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message
        }
    }

    /**
     * `handleSettingsSave`: one change, the settings coming back as saved. What a new chat was
     * prepared from is stale after it, and the provider part is kept for the next one, as
     * `updateAISettings` does. It finishes even when the panel closes first.
     */
    fun save(patch: JsonObject) {
        if (settings == null) return
        saving = true
        error = null
        model.scope.launch {
            try {
                val updated = api().updateAiSettings(patch)
                model.newChats.preparationCache.clear()
                model.newChatCache.writeSettings(updated.preparation)
                settings = updated
                revision++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message
            } finally {
                saving = false
            }
        }
    }

    /** `handleSave`: one field of the provider open, read when the change is made. */
    fun saveField(provider: String, field: String, value: String?) = save(AiSettingsPatch.field(provider, field, value))

    fun saveField(provider: String, field: String, value: Number?) = save(AiSettingsPatch.field(provider, field, value))

    fun saveField(provider: String, field: String, value: Boolean) = save(AiSettingsPatch.field(provider, field, value))

    suspend fun readStatuses() {
        probeStatuses = try {
            api().providerStatuses()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun readModels(provider: String) {
        modelsLoading = true
        try {
            models = api().settingsModels(provider)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            models = emptyList()
        } finally {
            modelsLoading = false
        }
    }

    /** `retryProbe`: looks for the provider again now, ahead of the server's own schedule. */
    suspend fun probe(provider: String) {
        probing = true
        try {
            val updated = api().probeProvider(provider)
            probeStatuses = probeStatuses.map { if (it.id == updated.id) updated else it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message
        } finally {
            probing = false
        }
    }

    private fun api() = model.api ?: throw IllegalStateException("Not connected")
}

/**
 * ai-settings-section.tsx as the chat toolbar shows it (`compact`): the new-chat provider, the
 * default one, shared context, then the open provider's own settings under its tab. A select
 * saves as it changes, a field as it loses the focus.
 */
@Composable
internal fun AiSettingsSection(model: AppModel) {
    val tokens = LocalPpmTokens.current
    val state = remember(model) { AiSettingsState(model) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) { state.load() }
    // A provider keeps its tab once configured, whatever chat can reach now: asked again on each
    // tab, so going back to one shows a retry that has since worked.
    LaunchedEffect(state, state.activeTab) {
        if (state.activeTab.isNotEmpty()) state.readStatuses()
    }
    val activeTab = state.activeTab
    val unavailable = state.probeStatuses.firstOrNull { it.id == activeTab && !it.registered }
    // A retry that worked reads the models again: the list was empty while the provider was missing.
    LaunchedEffect(state, activeTab, unavailable == null) {
        if (activeTab.isNotEmpty()) state.readModels(activeTab)
    }
    val heading = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text2.color)
    val settings = state.settings
    if (settings == null) {
        CssColumn(Modifier.fillMaxWidth(), gap = 6.dp) {
            BasicText("AI Settings", style = heading)
            BasicText(
                state.error?.let { "Error: $it" } ?: "Loading...",
                Modifier.cssLines(16.5f),
                style = ppmText(11f, lineHeight = 16.5f).copy(color = tokens.text3.color),
            )
        }
        return
    }
    val providerTabs = settings.providerTabs
    val config = settings.providers[activeTab]
    val isSdk = AiProviderSettings.isSdk(activeTab, config)
    val followFocus = settings.newChatProviderMode == "follow-focus"
    CssColumn(Modifier.fillMaxWidth(), gap = 8.dp) {
        BasicText("AI Settings", style = heading)
        val newChatTarget = rememberLabelTarget()
        Field(
            "New chat provider",
            newChatTarget,
            help = if (followFocus) {
                "New tabs follow the last chat you focused in this project, across panels. Existing tabs keep their provider."
            } else {
                "New tabs always use the default provider. Existing tabs keep their provider."
            },
        ) {
            SelectField(
                settings.newChatProviderMode ?: "default",
                NewChatProviderOptions,
                onValueChange = { state.save(AiSettingsPatch.newChatProviderMode(it)) },
                enabled = !state.saving,
                label = newChatTarget,
            )
        }
        val defaultTarget = rememberLabelTarget()
        Field("Default provider", defaultTarget, help = if (followFocus) "Used when no chat has been focused in this project yet." else null) {
            SelectField(
                settings.defaultProvider,
                providerTabs.map { SelectOption(it, providerName(it)) },
                onValueChange = { state.save(AiSettingsPatch.defaultProvider(it)) },
                enabled = !state.saving,
                label = defaultTarget,
            )
        }
        ShareContextBox(settings.shareProviderContext ?: true, enabled = !state.saving) { state.save(AiSettingsPatch.shareProviderContext(it)) }
        if (providerTabs.size > 1) ProviderTabs(providerTabs, activeTab) { state.activeTab = it }
        // Each provider's fields are its own: read again from its settings when its tab opens.
        key(activeTab) {
            ProviderSettings(model, state, activeTab, config, isSdk, unavailable) { scope.launch { state.probe(activeTab) } }
        }
        if (state.saving) BasicText("Saving...", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color))
        state.error?.let { BasicText(it, style = ppmText(12f, lineHeight = 16f).copy(color = tokens.error.color)) }
    }
}

/** The open provider's settings (`space-y-1.5`): why chat cannot reach it, its model, its own fields, then the ones every provider has. */
@Composable
private fun ProviderSettings(
    model: AppModel,
    state: AiSettingsState,
    provider: String,
    config: AiProviderSettings?,
    isSdk: Boolean,
    unavailable: ProviderProbeStatus?,
    retryProbe: () -> Unit,
) {
    val revision = state.revision
    CssColumn(Modifier.fillMaxWidth(), gap = 6.dp) {
        if (unavailable != null) UnavailableBox(provider, unavailable, state.probing, retryProbe)
        // Codex's and Antigravity's sign-ins live with the Claude ones in Settings, Accounts.
        if (provider == "codex" || provider == "antigravity") {
            ProviderAccountsLink(
                if (provider == "antigravity") "Antigravity account & quota" else "Codex accounts & login",
            ) { model.openSettings("accounts") }
        }
        val models = state.models
        if (models.isNotEmpty()) {
            val target = rememberLabelTarget()
            Field("Model", target) {
                val options = models.map { SelectOption(it.value, it.label) }
                SelectField(
                    if (isSdk) config?.model ?: models.first().value else config?.model?.ifEmpty { null } ?: DEFAULT_MODEL,
                    if (isSdk) options else listOf(SelectOption(DEFAULT_MODEL, "Auto (default)")) + options,
                    onValueChange = { state.saveField(provider, "model", it.takeUnless { v -> v == DEFAULT_MODEL }) },
                    enabled = !state.modelsLoading,
                    placeholder = if (state.modelsLoading) "Loading models..." else "Select model",
                    label = target,
                )
            }
        }
        if (provider == "antigravity") {
            val effortTarget = rememberLabelTarget()
            Field("Effort", effortTarget) {
                SelectField(
                    config?.effort ?: "high",
                    AntigravityEffortOptions,
                    onValueChange = { state.saveField(provider, "effort", it) },
                    label = effortTarget,
                )
            }
        }
        if (provider == "codex") {
            key(config?.modelContextWindow, config?.modelAutoCompactTokenLimit) {
                CodexContextForm(config, state.saving) { state.save(it) }
            }
        }
        if (isSdk) {
            TextSetting(
                "Base URL",
                rememberFieldText(revision, config?.baseUrl.orEmpty()),
                "https://api.anthropic.com (default)",
                InputKind.Url,
            ) { state.saveField(provider, "base_url", it.trim().ifEmpty { null }) }
            TextSetting(
                "API Key / Token",
                rememberFieldText(revision, config?.apiKey.orEmpty()),
                "sk-ant-... (optional, overrides accounts)",
                InputKind.Password,
                help = "Direct API key or OAuth token. Leave empty to use connected accounts.",
            ) { typed -> AiSettingsPatch.apiKey(provider, typed)?.let(state::save) }
            val effortTarget = rememberLabelTarget()
            Field("Effort", effortTarget) {
                SelectField(config?.effort ?: "high", EffortOptions, onValueChange = { state.saveField(provider, "effort", it) }, label = effortTarget)
            }
            val turns = config?.maxTurns
            TextSetting(
                "Max Turns (up to 1000, 0 = no limit)",
                rememberFieldText(revision, (turns ?: 100).toString()),
                "",
                InputKind.Integer,
                below = if (turns == 0 || (turns ?: 0) > 500) {
                    { CostWarning() }
                } else {
                    null
                },
            ) { typed -> jsParseInt(htmlNumberValue(typed))?.let { state.saveField(provider, "max_turns", wireNumber(it)) } }
            TextSetting(
                "Max Budget (USD)",
                rememberFieldText(revision, config?.maxBudgetUsd?.let(::jsNumber).orEmpty()),
                "No limit",
                InputKind.Decimal,
            ) { typed -> state.saveField(provider, "max_budget_usd", wireNumber(jsParseFloat(htmlNumberValue(typed)))) }
            TextSetting(
                "Thinking Budget (tokens)",
                rememberFieldText(revision, config?.thinkingBudgetTokens?.toString().orEmpty()),
                "Adaptive (model decides)",
                InputKind.Integer,
            ) { typed -> state.saveField(provider, "thinking_budget_tokens", wireNumber(jsParseInt(htmlNumberValue(typed)))) }
            SwitchRow(
                "Agent Teams",
                "Experimental. Enables multi-agent collaboration with shared tasks and messaging. Uses ~7x more tokens.",
                config?.agentTeams ?: false,
            ) { state.saveField(provider, "agent_teams", it) }
            if (config?.agentTeams == true) TeamList(model)
            SwitchRow(
                "1M Context Window",
                "Requires an entitled account (Max/Team/Enterprise) and an Opus 4 / Sonnet 4 model. Other accounts will error.",
                config?.context1m ?: false,
            ) { state.saveField(provider, "context_1m", it) }
            SwitchRow(
                "Inherit Claude Code MCP",
                "Auto-load MCP servers from Claude Code's ~/.claude.json (global + per-project). Your own MCP servers override inherited ones.",
                config?.inheritClaudeMcp ?: true,
            ) { state.saveField(provider, "inherit_claude_mcp", it) }
            // The help sits between the label and the list here.
            CssColumn(Modifier.fillMaxWidth(), gap = 4.dp) {
                FieldLabel("MCP sign-in")
                HelpText(
                    "Servers that use OAuth stay unavailable to Claude until someone signs in. " +
                        "Project-scoped servers are listed in the project's AI Resources panel.",
                )
                McpSignInList(model, Modifier.fillMaxWidth().bleed(8.dp))
            }
        }
        val permissionTarget = rememberLabelTarget()
        Field("Default Permission Mode", permissionTarget) {
            SelectField(
                config?.permissionMode ?: "bypassPermissions",
                PermissionModeOptions,
                onValueChange = { state.saveField(provider, "permission_mode", it) },
                label = permissionTarget,
            )
        }
        val promptTarget = rememberLabelTarget()
        val prompt = rememberFieldText(revision, config?.systemPrompt.orEmpty())
        Field(
            "Additional Instructions",
            promptTarget,
            help = if (provider == "antigravity") {
                "Antigravity reads these only when PPM starts a new chat, and a chat keeps the ones it started with."
            } else {
                null
            },
        ) {
            FormTextArea(
                prompt.state,
                "Enter additional instructions for $provider...",
                label = promptTarget,
                onFocusChanged = { now ->
                    prompt.focused = now
                    if (!now) state.saveField(provider, "system_prompt", prompt.state.text.toString().trim().ifEmpty { null })
                },
            )
        }
    }
}

/** `space-y-1`: a label over its control, and the line of help under it when there is one. */
@Composable
private fun Field(label: String, target: LabelTarget?, help: String? = null, content: @Composable () -> Unit) {
    CssColumn(Modifier.fillMaxWidth(), gap = 4.dp) {
        FieldLabel(label, target = target)
        content()
        if (help != null) HelpText(help)
    }
}

/** A field's text, kept while its provider's tab is open, and whether it is being typed in. */
private class FieldText(initial: String) {
    val state = TextFieldState(initial)
    var focused = false
}

/**
 * The text of a field showing [saved]: each save puts what was saved back in it, as the web
 * mounts its fields again (`key={…revision}`). The field being typed in keeps its text: the web
 * remounts that one too and loses what is typed there while another field saves.
 */
@Composable
private fun rememberFieldText(revision: Int, saved: String): FieldText {
    val field = remember { FieldText(saved) }
    LaunchedEffect(field, revision) {
        if (!field.focused && field.state.text.toString() != saved) field.state.setTextAndPlaceCursorAtEnd(saved)
    }
    return field
}

/** A labelled input (`Input` with `h-7`) that saves what it holds, through [onBlur], as it loses the focus. */
@Composable
private fun TextSetting(
    label: String,
    field: FieldText,
    placeholder: String,
    kind: InputKind,
    help: String? = null,
    below: (@Composable () -> Unit)? = null,
    onBlur: (String) -> Unit,
) {
    val target = rememberLabelTarget()
    Field(label, target, help) {
        FormInput(
            field.state,
            placeholder,
            kind = kind,
            label = target,
            onFocusChanged = { now ->
                field.focused = now
                if (!now) onBlur(field.state.text.toString())
            },
        )
        below?.invoke()
    }
}

/** Under Max Turns at 0 or past 500 (`flex items-start gap-1.5 text-[9px]`, a 12 px warning triangle `mt-px`). */
@Composable
private fun CostWarning() {
    val tokens = LocalPpmTokens.current
    // As tall as its text.
    val exact = rememberCssExact()
    Row(Modifier.cssExact(exact), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PpmIcon(ProductIcon.AlertTriangle, 12.dp, tokens.warning.color, Modifier.padding(top = 1.dp))
        CssColumn(exact = exact) {
            BasicText(
                "Every step is a paid model call. With a limit this high, or none, a task left running unattended can go on for hours and run up a large bill.",
                Modifier.cssLines(13.5f),
                style = ppmText(9f, lineHeight = 13.5f).copy(color = tokens.text2.color),
            )
        }
    }
}

/**
 * `flex items-center justify-between gap-2`: a label with its help, and the switch level with
 * their middle. The label flips it.
 */
@Composable
private fun SwitchRow(label: String, help: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val target = rememberLabelTarget()
    // As tall as its text.
    val exact = rememberCssExact()
    Row(Modifier.fillMaxWidth().cssExact(exact), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        CssColumn(Modifier.weight(1f, fill = false).padding(end = 8.dp), exact = exact) {
            FieldLabel(label, target = target)
            HelpText(help)
        }
        PpmSwitch(checked, onChange, label = target)
    }
}

/** `flex items-start justify-between gap-3 rounded-md border border-border p-3`: sharing rules and memory between providers. */
@Composable
private fun ShareContextBox(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val tokens = LocalPpmTokens.current
    val target = rememberLabelTarget()
    val shape = RoundedCornerShape(8.dp)
    // As tall as its text with the padding, which the text's column keeps in fractions.
    val exact = rememberCssExact()
    Row(
        Modifier.fillMaxWidth().cssExact(exact).cssBorder(tokens.border.color, shape).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        CssColumn(Modifier.weight(1f, fill = false).padding(end = 12.dp), gap = 4.dp, top = 12.dp, bottom = 12.dp, exact = exact) {
            FieldLabel("Share rules and memory between providers", target = target)
            HelpText(
                "Share project rules and memory, plus global instruction files, across providers. " +
                    "Applies from the next project-chat message. Turning off keeps context already sent.",
            )
        }
        Box(Modifier.padding(top = 12.dp)) { PpmSwitch(checked, onChange, enabled = enabled, label = target) }
    }
}

/**
 * A negative side margin (`-mx-*`): laid out [by] wider on each side than its parent gives it,
 * and shifted out by as much.
 */
private fun Modifier.bleed(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx()
    val wider = if (constraints.hasBoundedWidth) {
        constraints.copy(minWidth = constraints.minWidth + 2 * extra, maxWidth = constraints.maxWidth + 2 * extra)
    } else {
        constraints
    }
    val placeable = measurable.measure(wider)
    layout((placeable.width - 2 * extra).coerceAtLeast(0), placeable.height) { placeable.place(-extra, 0) }
}

/**
 * The provider tabs (`flex gap-0.5 border-b border-border/50 -mx-1 px-1`): each `px-2 py-1
 * text-[11px]` with the provider's logo, the open one in the accent over a 2 px accent line, the
 * others `text-text-subtle`, as tall as it and centred.
 */
@Composable
private fun ProviderTabs(tabs: List<String>, active: String, onPick: (String) -> Unit) {
    val tokens = LocalPpmTokens.current
    // A tab's 16.5 px line and `py-1`, its 2 px line and the row's own, each snapped as Chrome snaps a border.
    val height = with(LocalDensity.current) { (24.5.dp.toPx() + cssBorderPx(2.dp) + cssBorderPx(1.dp)).toDp() }
    Row(
        Modifier
            .fillMaxWidth()
            .cssHeight(height)
            .bleed(4.dp)
            .line(top = false, tokens.border.opacity(0.5).color)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (id in tabs) {
            val open = id == active
            val hover = rememberHover(enabled = !open)
            val color = if (open) tokens.accent else tokens.text2.mix(tokens.text3, hover.lit.toDouble())
            Row(
                Modifier
                    .fillMaxHeight()
                    .then(if (open) Modifier.cssBorder(tokens.accent.color, width = 2.dp, sides = CssSides.Bottom) else Modifier)
                    .ppmClickable(pressedColor = Color.Transparent, source = hover.source) { onPick(id) }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ProviderBadge(id, foreground = color.color)
                BasicText(
                    capitalized(providerName(id)),
                    style = ppmText(11f, if (open) FontWeight.Medium else FontWeight.Normal, lineHeight = 16.5f).copy(color = color.color),
                )
            }
        }
    }
}

/**
 * Why chat is not offering the open provider (`rounded-md border border-warning/30 bg-warning/5
 * px-2.5 py-2`): what the server saw, when it looks again, and a button to look now.
 */
@Composable
private fun UnavailableBox(provider: String, status: ProviderProbeStatus, probing: Boolean, retry: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    val small = ppmText(11f, lineHeight = 16.5f)
    // As tall as its text with the padding, which the text's column keeps in fractions.
    val exact = rememberCssExact()
    Row(
        Modifier
            .fillMaxWidth()
            .cssExact(exact)
            .clip(shape)
            .background(tokens.warning.opacity(0.05).color)
            .cssBorder(tokens.warning.opacity(0.3).color, shape)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PpmIcon(ProductIcon.AlertTriangle, 14.dp, tokens.warning.color, Modifier.padding(top = 10.dp))
        CssColumn(Modifier.weight(1f), gap = 6.dp, top = 8.dp, bottom = 8.dp, exact = exact) {
            BasicText("${providerName(provider)} is not available in chat — PPM could not start it.", Modifier.cssLines(16.5f), style = small.copy(color = tokens.text2.color))
            // An empty paragraph still takes its gap.
            val reason = status.reason
            if (reason.isNullOrEmpty()) Spacer(Modifier) else BasicText(reason, Modifier.cssLines(16.5f), style = small.copy(color = tokens.text2.color))
            status.nextProbeAt?.let {
                BasicText("Checking again at ${PpmFormat.timeOfDay(it)}", Modifier.cssLines(16.5f), style = small.copy(color = tokens.text3.color))
            }
            CheckAgainButton(probing, retry)
        }
    }
}

/**
 * `Button variant="outline" size="sm"` with `min-h-11 md:min-h-8`: `bg-input/30` outlined in
 * `border-input` when dark; light, `bg-background` in a border of the text colour, the class
 * naming no colour for it.
 */
@Composable
private fun CheckAgainButton(probing: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    // `dark:` is the device's dark mode on the web, not PPM's theme.
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(8.dp)
    val hover = rememberHover(enabled = !probing)
    val fill = if (dark) tokens.border.opacity(0.3 + 0.2 * hover.lit) else tokens.panel2.mix(tokens.bgSolid, hover.lit.toDouble())
    Row(
        Modifier
            .cssHeight(if (LocalPpmMobile.current) 44.dp else 32.dp)
            .alpha(if (probing) 0.5f else 1f)
            .ppmShadow(ShadowXs, shape)
            .clip(shape)
            .background(fill.color)
            .cssBorder((if (dark) tokens.border else tokens.text).color, shape)
            .ppmClickable(enabled = !probing, pressedColor = Color.Transparent, source = hover.source, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PpmIcon(ProductIcon.RefreshCw, 14.dp, tokens.text.color, spinning = probing)
        BasicText(
            if (probing) "Checking..." else "Check again",
            style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color),
        )
    }
}

/** `w-full flex items-center justify-between gap-2 rounded-md border px-2.5 py-2`, `hover:bg-surface-elevated`. */
@Composable
private fun ProviderAccountsLink(label: String, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    // `py-2` around a 16.5 px line, and the border snapped as Chrome snaps it.
    val height = with(LocalDensity.current) { (32.5.dp.toPx() + 2 * cssBorderPx(1.dp)).toDp() }
    Row(
        Modifier
            .fillMaxWidth()
            .cssHeight(height)
            .clip(shape)
            .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, shape = shape, onClick = onClick)
            .cssBorder(tokens.border.color, shape)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            label,
            Modifier.weight(1f, fill = false).padding(end = 8.dp),
            style = ppmText(11f, lineHeight = 16.5f).copy(color = tokens.text2.color),
        )
        PpmIcon(ProductIcon.ExternalLink, 14.dp, tokens.text3.color)
    }
}

/**
 * codex-context-settings.tsx: Codex's context window and auto-compact threshold, each a native
 * select of presets with Codex's default and a custom number, saved together by the form's
 * button. The custom field's `required min=1 step=1` stops a save as Chrome's validation does,
 * its message in the form's error line rather than Chrome's bubble.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CodexContextForm(config: AiProviderSettings?, saving: Boolean, onSave: (JsonObject) -> Unit) {
    val tokens = LocalPpmTokens.current
    val density = LocalDensity.current
    val uriHandler = LocalUriHandler.current
    val wide = with(density) { LocalWindowInfo.current.containerSize.width.toDp() } >= 640.dp
    val window = remember { TokenField(config?.modelContextWindow?.toString().orEmpty(), WindowPresets) }
    val compact = remember { TokenField(config?.modelAutoCompactTokenLimit?.toString().orEmpty(), CompactPresets) }
    var error by remember { mutableStateOf<String?>(null) }
    fun submit() {
        val invalid = listOf(window, compact).firstNotNullOfOrNull { it.constraintError() }
        if (invalid != null) {
            error = invalid
            return
        }
        val windowValue = tokenCount(window.value)
        val compactValue = tokenCount(compact.value)
        if (windowValue == TokenCount.Invalid || compactValue == TokenCount.Invalid) {
            error = "Enter positive whole numbers of tokens, or leave blank for Codex defaults."
            return
        }
        if (windowValue is TokenCount.Tokens && compactValue is TokenCount.Tokens && compactValue.value > windowValue.value) {
            error = "Auto-compact threshold must not exceed the context window."
            return
        }
        error = null
        onSave(AiSettingsPatch.codexContext((windowValue as? TokenCount.Tokens)?.value, (compactValue as? TokenCount.Tokens)?.value))
    }
    val shape = RoundedCornerShape(8.dp)
    CssColumn(Modifier.fillMaxWidth().cssBorder(tokens.border.color, shape).padding(horizontal = 12.dp), gap = 8.dp, top = 12.dp, bottom = 12.dp) {
        val clearError = { error = null }
        if (wide) {
            CssGridRow(Modifier.fillMaxWidth(), gap = 12.dp) {
                TokenLimitSelect("Context window (tokens)", window, saving, wide, clearError)
                TokenLimitSelect("Auto-compact threshold (tokens)", compact, saving, wide, clearError)
            }
        } else {
            CssColumn(Modifier.fillMaxWidth(), gap = 12.dp) {
                TokenLimitSelect("Context window (tokens)", window, saving, wide, clearError)
                TokenLimitSelect("Auto-compact threshold (tokens)", compact, saving, wide, clearError)
            }
        }
        HelpText(
            "Choose Codex default to use Codex configuration and model defaults, or Custom to enter a value. " +
                "Presets are shortcuts, not a list of limits supported by your model; " +
                "increasing these values does not unlock a larger context window. " +
                "Applies to new sessions and sessions resumed after a PPM restart.",
        )
        error?.let { BasicText(it, Modifier.cssLines(16.5f), style = ppmText(11f, lineHeight = 16.5f).copy(color = tokens.error.color)) }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                "Codex context documentation",
                Modifier
                    .padding(end = 8.dp)
                    .ppmClickable(pressedColor = Color.Transparent) {
                        runCatching { uriHandler.openUri(CODEX_CONTEXT_DOCS) }
                    },
                style = ppmText(9f, lineHeight = 13.5f).copy(color = tokens.accent.color, textDecoration = TextDecoration.Underline),
            )
            val hover = rememberHover(enabled = !saving)
            Box(
                Modifier
                    .then(if (wide) Modifier else Modifier.heightIn(min = 44.dp))
                    .alpha(if (saving) 0.5f else 1f)
                    .clip(shape)
                    .ppmClickable(enabled = !saving, pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, shape = shape, source = hover.source, onClick = ::submit)
                    .cssBorder(tokens.border.color, shape)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(if (saving) "Saving..." else "Save context settings", style = ppmText(11f, lineHeight = 16.5f).copy(color = tokens.text.color))
            }
        }
    }
}

/**
 * One of the form's token limits: [value] as the web's form state holds it (what the number
 * field reports, which is nothing for text that is not a number), the select on Custom or not,
 * and the custom field's own text.
 */
private class TokenField(initial: String, val presets: List<Long>) {
    var value by mutableStateOf(initial)
    var custom by mutableStateOf(initial.isNotEmpty() && presets.none { it.toDouble() == initial.trim().toDoubleOrNull() })

    /** The custom field's text, as typed. */
    var typed: TextFieldState? = null

    /** What Chrome's validation says of the custom field (`required min=1 step=1`), null when it would let the form go. */
    fun constraintError(): String? {
        if (!custom) return null
        val raw = typed?.text?.toString() ?: value
        if (raw.isNotEmpty() && value.isEmpty()) return "Please enter a number."
        if (raw.isEmpty()) return "Please fill out this field."
        val number = value.toDoubleOrNull() ?: return "Please enter a number."
        if (number < 1) return "Value must be greater than or equal to 1."
        if (number != floor(number)) {
            val below = floor(number)
            return "Please enter a valid value. The two nearest valid values are ${jsNumber(below)} and ${jsNumber(below + 1)}."
        }
        return null
    }
}

/**
 * `TokenLimitSelect`: a label over a native select of Codex's default, the presets and Custom,
 * and on Custom a number field (`min-h-11 sm:min-h-0 h-7`) holding the value.
 */
@Composable
private fun TokenLimitSelect(label: String, field: TokenField, disabled: Boolean, wide: Boolean, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    val target = rememberLabelTarget()
    val options = remember(field) {
        listOf(SelectOption("default", "Codex default")) +
            field.presets.map { SelectOption(it.toString(), if (it == 1_000_000L) "1M tokens" else "${it / 1000}K tokens") } +
            SelectOption("custom", "Custom...")
    }
    CssColumn(modifier.fillMaxWidth(), gap = 4.dp) {
        FieldLabel(label, target = target)
        NativeSelect(
            if (field.custom) "custom" else field.value.ifEmpty { "default" },
            options,
            onChange = { next ->
                field.custom = next == "custom"
                if (next != "custom") {
                    field.value = if (next == "default") "" else next
                    onEdit()
                }
            },
            enabled = !disabled,
            label = target,
        )
        if (field.custom) {
            val typed = remember { TextFieldState(field.value) }
            LaunchedEffect(typed) {
                field.typed = typed
                snapshotFlow { typed.text.toString() }.drop(1).collect {
                    field.value = htmlNumberValue(it)
                    onEdit()
                }
            }
            FormInput(typed, "Enter tokens", Modifier.alpha(if (disabled) 0.5f else 1f), kind = InputKind.Integer, height = if (wide) 28.dp else 44.dp)
        } else {
            LaunchedEffect(Unit) { field.typed = null }
        }
    }
}

/**
 * mcp-sign-in-list.tsx with `showWhenEmpty`: the MCP servers a chat in the home directory would
 * see waiting for a sign-in, under a "Needs sign-in" heading with their count and a button to ask
 * again; each one's own sign-in comes with the MCP sign-in dialog.
 */
@Composable
internal fun McpSignInList(model: AppModel, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf<List<McpServerState>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // Only the latest request writes.
    val sequence = remember { intArrayOf(0) }
    fun load(fresh: Boolean) {
        val mine = ++sequence[0]
        loading = true
        scope.launch {
            try {
                val next = (model.api ?: throw IllegalStateException("Not connected")).mcpAuthStatus(null, fresh)
                if (mine == sequence[0]) servers = next
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failed probe hides the list rather than showing a stale one.
                if (mine == sequence[0]) servers = emptyList()
            } finally {
                if (mine == sequence[0]) loading = false
            }
        }
    }
    LaunchedEffect(Unit) { load(fresh = false) }
    val pending = servers.filter { it.status == "needs-auth" }
    CssColumn(modifier, bottom = 4.dp) {
        // `flex items-center gap-1 px-2 py-1 text-[10px] font-semibold uppercase tracking-wide text-text-subtle`,
        // as tall as its button.
        val caption = ppmText(10f, FontWeight.SemiBold, lineHeight = 15f).copy(letterSpacing = 0.025.em)
        Row(
            Modifier.fillMaxWidth().cssHeight(if (mobile) 44.dp else 32.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BasicText("NEEDS SIGN-IN", style = caption.copy(color = tokens.text3.color))
            BasicText("${pending.size}", style = caption.copy(color = tokens.text3.opacity(0.6).color))
            Spacer(Modifier.weight(1f))
            val shape = RoundedCornerShape(4.dp)
            Box(
                Modifier
                    .size(if (mobile) 36.dp else 24.dp)
                    .alpha(if (loading) 0.5f else 1f)
                    .clip(shape)
                    .ppmClickable(enabled = !loading, pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, shape = shape) { load(fresh = true) },
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(if (loading) ProductIcon.Loader2 else ProductIcon.RefreshCw, 12.dp, tokens.text3.color, spinning = loading)
            }
        }
        if (pending.isEmpty()) {
            CssColumn(Modifier.padding(horizontal = 8.dp), top = 6.dp, bottom = 6.dp) {
                BasicText(
                    if (loading) "Checking MCP servers…" else "No MCP server is waiting for a sign-in.",
                    Modifier.cssLines(16.5f),
                    style = ppmText(11f, lineHeight = 16.5f).copy(color = tokens.text3.color),
                )
            }
        }
        for (server in pending) {
            McpSignInRow(server, mobile) {
                model.openMcpSignIn(null, server.name)
            }
        }
    }
}

/** `flex items-center gap-2 px-2 py-1.5`: a key, the server's name over where it was configured, and its sign-in button. */
@Composable
private fun McpSignInRow(server: McpServerState, mobile: Boolean, onSignIn: () -> Unit) {
    val tokens = LocalPpmTokens.current
    // `py-1.5` around the taller of the name with its origin and the button.
    val content = maxOf(if (server.originLabel != null) 36f else 19.5f, if (mobile) 36f else 24.5f)
    Row(
        Modifier.fillMaxWidth().cssHeight((12f + content).dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PpmIcon(ProductIcon.KeyRound, 16.dp, tokens.warning.color)
        Column(Modifier.weight(1f)) {
            BasicText(
                server.name,
                style = ppmText(13f, FontWeight.Medium, lineHeight = 19.5f).copy(color = tokens.text.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            server.originLabel?.let {
                BasicText(it, style = ppmText(11f, lineHeight = 16.5f).copy(color = tokens.text3.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // `rounded-md bg-primary px-2.5 py-1 text-[11px] font-medium`, `max-md:min-h-9 max-md:px-3`.
        val shape = RoundedCornerShape(8.dp)
        val hover = rememberHover()
        Box(
            Modifier
                .then(if (mobile) Modifier.heightIn(min = 36.dp) else Modifier)
                .clip(shape)
                .background(tokens.accent.opacity(1 - 0.1 * hover.lit).color)
                .ppmClickable(pressedColor = Color.Black.copy(alpha = 0.1f), shape = shape, source = hover.source, onClick = onSignIn)
                .padding(horizontal = if (mobile) 12.dp else 10.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                if (server.isClaudeAiConnector) "Connect" else "Sign in",
                style = ppmText(11f, FontWeight.Medium, lineHeight = 16.5f).copy(color = tokens.accentFg.color),
            )
        }
    }
}

/**
 * The agent teams under Agent Teams (`TeamListSection`): each with its members and when it was
 * made, deleted after a second tap. Nothing at all once the list comes back empty.
 */
@Composable
private fun TeamList(model: AppModel) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    var teams by remember { mutableStateOf<List<TeamInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<String?>(null) }
    fun fetch() {
        scope.launch {
            loading = true
            try {
                teams = model.api?.teams() ?: teams
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            loading = false
        }
    }
    LaunchedEffect(Unit) { fetch() }
    if (teams.isEmpty() && !loading) return
    CssColumn(Modifier.fillMaxWidth(), gap = 6.dp) {
        // As tall as its 20 px button.
        Row(Modifier.fillMaxWidth().cssHeight(20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            FieldLabel("Teams (${teams.size})")
            val hover = rememberHover()
            Box(Modifier.wrapContentHeight(unbounded = true).ppmClickable(pressedColor = Color.Transparent, source = hover.source) { fetch() }.padding(4.dp)) {
                PpmIcon(ProductIcon.RefreshCw, 12.dp, tokens.text.mix(tokens.text3, hover.lit.toDouble()).color, spinning = loading)
            }
        }
        val text = ppmText(12f, lineHeight = 16f)
        for (team in teams) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(tokens.panel2.color).padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f, fill = false)) {
                    BasicText(team.name, style = text.copy(color = tokens.text.color, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    team.description?.takeIf { it.isNotEmpty() }?.let {
                        BasicText(it, style = text.copy(color = tokens.text3.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    BasicText(
                        "${team.memberCount} members" + (team.createdAt?.let { " · ${PpmFormat.numericDate(it)}" } ?: ""),
                        style = text.copy(color = tokens.text3.color),
                    )
                }
                if (confirming == team.name) {
                    Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TeamButton("Delete", tokens.error.color, Color.White) {
                            scope.launch {
                                try {
                                    model.api?.deleteTeam(team.name) ?: return@launch
                                    teams = teams.filter { it.name != team.name }
                                    confirming = null
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                }
                            }
                        }
                        TeamButton("Cancel", tokens.panel2.color, tokens.text.color) { confirming = null }
                    }
                } else {
                    val hover = rememberHover()
                    Box(
                        Modifier
                            .padding(start = 8.dp)
                            .ppmClickable(pressedColor = Color.Transparent, source = hover.source) { confirming = team.name }
                            .padding(4.dp),
                    ) {
                        PpmIcon(ProductIcon.Trash2, 14.dp, tokens.error.mix(tokens.text3, hover.lit.toDouble()).color)
                    }
                }
            }
        }
    }
}

/** `px-2 py-1 rounded text-[10px]`, filled. */
@Composable
private fun TeamButton(label: String, fill: Color, text: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .clip(shape)
            .background(fill)
            .ppmClickable(pressedColor = Color.Transparent, shape = shape, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        BasicText(label, style = ppmText(10f, lineHeight = 15f).copy(color = text))
    }
}
