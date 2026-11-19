package com.hienlh.ppm.app.ui.phone.settings

import com.hienlh.ppm.state.Effort
import com.hienlh.ppm.state.ModelNames
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.htmlNumberValue
import com.hienlh.ppm.app.ui.jsParseInt
import com.hienlh.ppm.client.aiSettings
import com.hienlh.ppm.client.probeProvider
import com.hienlh.ppm.client.providerStatuses
import com.hienlh.ppm.client.settingsModels
import com.hienlh.ppm.client.updateAiSettings
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.kit.ListRow
import com.hienlh.ppm.design.kit.Sheet
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.AiProviderSettings
import com.hienlh.ppm.protocol.AiSettings
import com.hienlh.ppm.protocol.AiSettingsPatch
import com.hienlh.ppm.protocol.ModelOption
import com.hienlh.ppm.protocol.ProviderProbeStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.hienlh.ppm.app.asksForTextFocus

data class PhoneSelectOption(
    val value: String,
    val label: String,
    val shortLabel: String = label,
    val subtitle: String? = null,
)

val PhoneEffortOptions: List<PhoneSelectOption> = listOf(
    PhoneSelectOption("low", "Low", "Low", "Quick answers for simple tasks."),
    PhoneSelectOption("medium", "Medium", "Medium", "Balanced reasoning and speed."),
    PhoneSelectOption("high", "High", "High", "Balanced depth for everyday coding."),
    PhoneSelectOption("xhigh", "Extra", "Extra", "Extended reasoning for complex refactors."),
    PhoneSelectOption("max", "Max", "Max", "Maximum thinking budget."),
)

val PhoneAntigravityEffortOptions: List<PhoneSelectOption> = listOf(
    PhoneSelectOption("low", "Low", "Low", "Quick answers for simple tasks."),
    PhoneSelectOption("medium", "Medium", "Medium", "Balanced reasoning and speed."),
    PhoneSelectOption("high", "High", "High", "Deep reasoning for complex coding."),
    PhoneSelectOption("xhigh", "Extra", "Extra", "Extended reasoning for complex refactors."),
)

fun phoneEffortOptionsFor(
    providerId: String,
    model: ModelOption?,
    keepEffort: String? = null,
): List<PhoneSelectOption> {
    val isAntigravity = providerId.equals("antigravity", ignoreCase = true)
    val byValue = (if (isAntigravity) PhoneAntigravityEffortOptions else PhoneEffortOptions).associateBy { it.value }
    return Effort.optionsFor(
        providerId,
        model,
        keepLevels = listOfNotNull(keepEffort),
    ).mapNotNull { byValue[it.value] }
}

val PhonePermissionModeOptions: List<PhoneSelectOption> = listOf(
    PhoneSelectOption(
        value = "bypassPermissions",
        label = "Bypass permissions (default)",
        shortLabel = "Bypass",
        subtitle = "Runs edits and commands without asking.",
    ),
    PhoneSelectOption(
        value = "default",
        label = "Ask before edits",
        shortLabel = "Ask",
        subtitle = "Prompts before editing files or running shell commands.",
    ),
    PhoneSelectOption(
        value = "acceptEdits",
        label = "Edit automatically",
        shortLabel = "Auto-edit",
        subtitle = "Applies file edits automatically; asks before commands.",
    ),
    PhoneSelectOption(
        value = "plan",
        label = "Plan mode",
        shortLabel = "Plan",
        subtitle = "Read-only exploration and planning before changes.",
    ),
)

internal fun phoneProviderDisplayName(id: String): String = when (id.lowercase()) {
    "claude" -> "Claude"
    "codex" -> "Codex"
    "antigravity" -> "Antigravity"
    "cursor" -> "Cursor"
    "gemini" -> "Gemini"
    else -> id.replaceFirstChar { it.uppercase() }
}

private const val PHONE_DEFAULT_MODEL = "__default__"

@Composable
fun PhoneAiProviderPage(
    model: AppModel,
    onBack: () -> Unit,
    onOpenAccounts: () -> Unit = {},
    /** Opens the tablet's whole AI Provider pane: keys, budgets, MCP, teams and instructions. */
    onOpenAllSettings: (() -> Unit)? = null,
) {
    val api = model.api
    val scope = rememberCoroutineScope()

    var settings by remember { mutableStateOf<AiSettings?>(null) }
    var activeProvider by remember { mutableStateOf("claude") }
    var models by remember { mutableStateOf<List<ModelOption>>(emptyList()) }
    var modelsLoading by remember { mutableStateOf(false) }
    var probeStatuses by remember { mutableStateOf<List<ProviderProbeStatus>>(emptyList()) }
    var probing by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(api) {
        val client = api ?: return@LaunchedEffect
        try {
            val loaded = client.aiSettings()
            settings = loaded
            activeProvider = loaded.defaultProvider.ifEmpty { "claude" }
            errorMsg = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMsg = e.message
        }
        try {
            probeStatuses = client.providerStatuses()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    LaunchedEffect(api, activeProvider) {
        val client = api ?: return@LaunchedEffect
        if (activeProvider.isEmpty()) return@LaunchedEffect
        modelsLoading = true
        try {
            models = client.settingsModels(activeProvider)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            models = emptyList()
        } finally {
            modelsLoading = false
        }
    }

    fun savePatch(patch: JsonObject, local: (AiSettings) -> AiSettings) {
        settings = settings?.let(local)
        val client = api ?: return
        scope.launch {
            try {
                val updated = client.updateAiSettings(patch)
                model.newChats.preparationCache.clear()
                model.newChatCache.writeSettings(updated.preparation)
                settings = updated
                errorMsg = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMsg = e.message
            }
        }
    }

    fun updateProvider(provider: String, change: (AiProviderSettings) -> AiProviderSettings): (AiSettings) -> AiSettings = { current ->
        current.copy(providers = current.providers + (provider to change(current.providers[provider] ?: AiProviderSettings())))
    }

    fun saveField(provider: String, field: String, value: String?) {
        savePatch(
            AiSettingsPatch.field(provider, field, value),
            updateProvider(provider) { prev ->
                when (field) {
                    "model" -> prev.copy(model = value)
                    "effort" -> prev.copy(effort = value)
                    "permission_mode" -> prev.copy(permissionMode = value)
                    else -> prev
                }
            },
        )
    }

    val providerTabs = settings?.providerTabs?.takeIf { it.isNotEmpty() }
        ?: listOf("claude", "codex", "antigravity")
    val currentConfig = settings?.providers?.get(activeProvider)
    val isSdk = AiProviderSettings.isSdk(activeProvider, currentConfig)
    val unavailable = probeStatuses.firstOrNull { it.id == activeProvider && !it.registered }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalPpmTokens.current.bgSolid.color),
    ) {
        PhoneSettingsSubPageHeader(
            title = "AI Provider",
            onBack = onBack,
        )
        PhoneAiProviderContent(
            providerTabs = providerTabs,
            activeProvider = activeProvider,
            onSelectProvider = { activeProvider = it },
            modelValue = if (isSdk) {
                currentConfig?.model ?: models.firstOrNull()?.value ?: ""
            } else {
                currentConfig?.model?.ifEmpty { null } ?: PHONE_DEFAULT_MODEL
            },
            models = models,
            includeAutoDefaultModel = !isSdk,
            modelsLoading = modelsLoading,
            effortValue = currentConfig?.effort ?: "high",
            permissionModeValue = currentConfig?.permissionMode ?: "bypassPermissions",
            unavailableStatus = unavailable,
            probing = probing,
            errorMsg = errorMsg,
            onSelectModel = { chosen ->
                val nextModel = chosen.takeUnless { it == PHONE_DEFAULT_MODEL }
                val target = if (isSdk) {
                    models.firstOrNull { it.value == nextModel }
                } else {
                    models.firstOrNull { it.value == (nextModel ?: models.firstOrNull { m -> m.isDefault }?.value) }
                }
                if (Effort.shouldResetOnModelSwitch(currentConfig?.effort, activeProvider, target)) {
                    savePatch(
                        AiSettingsPatch.provider(
                            activeProvider,
                            mapOf(
                                "model" to (nextModel?.let(::JsonPrimitive) ?: JsonNull),
                                "effort" to JsonPrimitive(Effort.HIGH.value),
                            ),
                        ),
                        updateProvider(activeProvider) { it.copy(model = nextModel, effort = Effort.HIGH.value) },
                    )
                } else {
                    saveField(activeProvider, "model", nextModel)
                }
            },
            onSelectEffort = { chosen ->
                saveField(activeProvider, "effort", chosen)
            },
            onSelectPermissionMode = { chosen ->
                saveField(activeProvider, "permission_mode", chosen)
            },
            onRetryProbe = {
                val client = api ?: return@PhoneAiProviderContent
                probing = true
                scope.launch {
                    try {
                        val updated = client.probeProvider(activeProvider)
                        probeStatuses = probeStatuses.map { if (it.id == updated.id) updated else it }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        errorMsg = e.message
                    } finally {
                        probing = false
                    }
                }
            },
            onOpenAccounts = onOpenAccounts,
            onOpenAllSettings = onOpenAllSettings,
            // Not offered until the settings are read: a field showing the default would save it.
            maxTurns = if (isSdk && settings != null) currentConfig?.maxTurns ?: PHONE_DEFAULT_MAX_TURNS else null,
            onSaveMaxTurns = { turns ->
                val provider = activeProvider
                savePatch(AiSettingsPatch.field(provider, "max_turns", turns), updateProvider(provider) { it.copy(maxTurns = turns) })
            },
            leftoverIdleMinutes = settings?.let { phoneLeftoverIdleMinutes(it.leftoverProcessesIdleMinutes) },
            onSaveLeftoverIdleMinutes = { minutes ->
                savePatch(AiSettingsPatch.leftoverIdleMinutes(minutes)) { it.copy(leftoverProcessesIdleMinutes = minutes) }
            },
        )
    }
}

/** `config?.max_turns ?? 100` of the web's field. */
internal const val PHONE_DEFAULT_MAX_TURNS = 100
internal const val PHONE_MAX_TURNS_LIMIT = 1000

/** `MAX_LEFTOVER_IDLE_MINUTES` and `LOG_ONLY_IDLE_MINUTES` of `src/shared/leftover-processes.ts`. */
internal const val PHONE_MAX_LEFTOVER_IDLE_MINUTES = 1440
private const val PHONE_LOG_ONLY_IDLE_MINUTES = 30

/** `leftoverIdleMinutes(settings)`: the saved value when it is a valid one, else the default, 0. */
internal fun phoneLeftoverIdleMinutes(saved: Int?): Int = saved?.takeIf { it in 0..PHONE_MAX_LEFTOVER_IDLE_MINUTES } ?: 0

/**
 * What the web's number field saves on blur: the typed number brought into [min]..[max], or null for
 * a field that holds no number (which shows the saved value again rather than looking saved).
 */
internal fun phoneClampedNumber(typed: String, min: Int, max: Int): Int? =
    jsParseInt(htmlNumberValue(typed.trim()))?.coerceIn(min.toDouble(), max.toDouble())?.toInt()

/** The web's warning under Max Turns, at 0 (no limit) or above 500. */
internal fun phoneMaxTurnsCostWarning(turns: Int): Boolean = turns == 0 || turns > 500

@Composable
fun PhoneAiProviderContent(
    providerTabs: List<String>,
    activeProvider: String,
    onSelectProvider: (String) -> Unit,
    modelValue: String,
    models: List<ModelOption>,
    includeAutoDefaultModel: Boolean = false,
    modelsLoading: Boolean = false,
    effortValue: String,
    permissionModeValue: String,
    unavailableStatus: ProviderProbeStatus? = null,
    probing: Boolean = false,
    errorMsg: String? = null,
    onSelectModel: (String) -> Unit,
    onSelectEffort: (String) -> Unit,
    onSelectPermissionMode: (String) -> Unit,
    onRetryProbe: () -> Unit = {},
    onOpenAccounts: () -> Unit = {},
    modifier: Modifier = Modifier,
    /** The active provider's Max Turns; null hides the field (not an SDK provider, or nothing read yet). */
    maxTurns: Int? = null,
    onSaveMaxTurns: (Int) -> Unit = {},
    /** `leftover_processes_idle_minutes`, for every provider; null hides the field. */
    leftoverIdleMinutes: Int? = null,
    onSaveLeftoverIdleMinutes: (Int) -> Unit = {},
    /** Opens the provider's other settings (keys, budgets, MCP, teams, instructions); null leaves the row out. */
    onOpenAllSettings: (() -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    var modelSheetOpen by remember { mutableStateOf(false) }
    var permissionSheetOpen by remember { mutableStateOf(false) }

    val modelOptions = remember(models, includeAutoDefaultModel) {
        val mapped = models.map { PhoneSelectOption(it.value, it.label) }
        if (includeAutoDefaultModel) {
            listOf(PhoneSelectOption(PHONE_DEFAULT_MODEL, ModelNames.autoModelLabel(models), "Auto")) + mapped
        } else {
            // Only the models the server lists for this provider; none listed means none offered, not Claude's.
            mapped
        }
    }
    val selectedModelOption = modelOptions.firstOrNull { it.value == modelValue }
        ?: when {
            modelValue == PHONE_DEFAULT_MODEL -> PhoneSelectOption(modelValue, ModelNames.autoModelLabel(models), "Auto")
            modelValue.isBlank() -> PhoneSelectOption(modelValue, "Not set", "Not set")
            else -> PhoneSelectOption(modelValue, modelValue, modelValue)
        }

    val effectiveModel = if (modelValue == PHONE_DEFAULT_MODEL || modelValue.isBlank()) {
        models.firstOrNull { it.isDefault }
    } else {
        models.firstOrNull { it.value == modelValue }
    }
    val effortOptions = phoneEffortOptionsFor(
        activeProvider,
        effectiveModel,
        keepEffort = effortValue.takeIf { activeProvider.equals("antigravity", ignoreCase = true) && it == Effort.XHIGH.value },
    )
    val effectiveEffortValue = Effort.effectiveEffort(effortValue, activeProvider, effectiveModel) ?: Effort.DEFAULT.value
    val selectedEffortOption = effortOptions.firstOrNull { it.value == effectiveEffortValue }
        ?: effortOptions.firstOrNull { it.value == "high" }
        ?: effortOptions.first()

    val selectedPermissionOption = PhonePermissionModeOptions.firstOrNull { it.value == permissionModeValue }
        ?: PhonePermissionModeOptions.first()

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
        // Provider Segmented Control
        if (providerTabs.isNotEmpty()) {
            PhoneSegmentedControl(
                items = providerTabs.map { it to phoneProviderDisplayName(it) },
                selectedId = activeProvider,
                onSelect = onSelectProvider,
                tagPrefix = "phone-ai-provider-tab",
            )
        }

        if (!errorMsg.isNullOrBlank()) {
            BasicText(
                text = errorMsg,
                style = ppmText(12f).copy(color = tokens.error.color),
                modifier = Modifier.testTag("phone-ai-error"),
            )
        }

        // Unavailable provider banner
        if (unavailableStatus != null) {
            val bannerShape = RoundedCornerShape(12.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(bannerShape)
                    .background(tokens.warning.opacity(0.10).color)
                    .cssBorder(tokens.warning.opacity(0.32).color, bannerShape)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicText(
                    text = "${phoneProviderDisplayName(activeProvider)} CLI not found on server",
                    style = ppmText(13f, FontWeight.SemiBold).copy(color = tokens.warning.color),
                )
                BasicText(
                    text = unavailableStatus.reason ?: "Install or sign in on the server, then check again.",
                    style = ppmText(12f, lineHeight = 17f).copy(color = tokens.text2.color),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhonePillButton(
                        label = if (probing) "Checking…" else "Check again",
                        onClick = onRetryProbe,
                        modifier = Modifier.testTag("phone-ai-probe-retry"),
                    )
                    PhonePillButton(
                        label = "Accounts",
                        onClick = onOpenAccounts,
                        modifier = Modifier.testTag("phone-ai-open-accounts"),
                    )
                }
            }
        }

        // 1. Model Row -> Options Sheet
        PhoneSectionCard {
            ListRow(
                title = "Model",
                subtitle = if (modelsLoading) "Loading models…" else "Default model for new ${phoneProviderDisplayName(activeProvider)} chats.",
                modifier = Modifier.testTag("phone-ai-model-row"),
                trailingContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BasicText(
                            text = selectedModelOption.shortLabel,
                            style = ppmText(13f, FontWeight.Medium).copy(color = tokens.text2.color),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        PpmIcon(ProductIcon.ChevronRight, 16.dp, tokens.text3.color)
                    }
                },
                onClick = { modelSheetOpen = true },
            )
        }

        // 2. Effort Segmented Card
        PhoneSectionCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        text = "Effort",
                        style = ppmText(14f, FontWeight.Medium).copy(color = tokens.text.color),
                    )
                    BasicText(
                        text = selectedEffortOption.label,
                        style = ppmText(12f, FontWeight.SemiBold).copy(color = tokens.accent.color),
                    )
                }
                PhoneSegmentedControl(
                    items = effortOptions.map { it.value to it.label },
                    selectedId = selectedEffortOption.value,
                    onSelect = onSelectEffort,
                    tagPrefix = "phone-ai-effort",
                )
                selectedEffortOption.subtitle?.let { note ->
                    BasicText(
                        text = note,
                        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
                    )
                }
            }
        }

        // 3. Default Permission Mode Row -> Options Sheet
        PhoneSectionCard {
            ListRow(
                title = "Default Permission Mode",
                subtitle = selectedPermissionOption.subtitle ?: "Applies to new chats.",
                modifier = Modifier.testTag("phone-ai-permission-row"),
                trailingContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BasicText(
                            text = selectedPermissionOption.shortLabel,
                            style = ppmText(13f, FontWeight.Medium).copy(color = tokens.text2.color),
                            maxLines = 1,
                        )
                        PpmIcon(ProductIcon.ChevronRight, 16.dp, tokens.text3.color)
                    }
                },
                onClick = { permissionSheetOpen = true },
            )
        }

        if (maxTurns != null) {
            PhoneSectionCard {
                PhoneNumberSetting(
                    label = "Max Turns (up to 1000, 0 = no limit)",
                    saved = maxTurns,
                    min = 0,
                    max = PHONE_MAX_TURNS_LIMIT,
                    // A field per provider: switching provider shows that provider's value.
                    key = activeProvider,
                    tag = "phone-ai-max-turns",
                    onSave = onSaveMaxTurns,
                ) {
                    if (phoneMaxTurnsCostWarning(maxTurns)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.testTag("phone-ai-max-turns-warning"),
                        ) {
                            PpmIcon(ProductIcon.AlertTriangle, 13.dp, tokens.warning.color)
                            BasicText(
                                text = "Every step is a paid model call. With a limit this high, or none, a task left running unattended can go on for hours and run up a large bill.",
                                style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
                            )
                        }
                    }
                }
            }
        }

        if (leftoverIdleMinutes != null) {
            PhoneSectionCard {
                PhoneNumberSetting(
                    label = "Stop leftover processes after (idle minutes, 0 = log only)",
                    saved = leftoverIdleMinutes,
                    min = 0,
                    max = PHONE_MAX_LEFTOVER_IDLE_MINUTES,
                    key = "leftover",
                    tag = "phone-ai-leftover-idle",
                    onSave = onSaveLeftoverIdleMinutes,
                ) {
                    BasicText(
                        text = "Servers, emulators and other processes a chat's commands started and left running are stopped once " +
                            "the chat has been idle this long, and at once when the chat is deleted. At 0 nothing is stopped: the " +
                            "server log names what $PHONE_LOG_ONLY_IDLE_MINUTES idle minutes would have stopped. Linux with systemd only.",
                        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
                    )
                }
            }
        }

        // The provider's other settings, one level down.
        if (onOpenAllSettings != null) {
            PhoneAllSettingsRow(
                title = PHONE_AI_ALL_TITLE,
                subtitle = "Keys, budgets, MCP, teams and instructions",
                tag = "phone-ai-all-settings",
                onClick = onOpenAllSettings,
            )
        }

        Spacer(Modifier.height(16.dp))
    }

    if (modelSheetOpen) {
        Sheet(
            title = "Model",
            subtitle = phoneProviderDisplayName(activeProvider),
            onDismiss = { modelSheetOpen = false },
            forceBottomSheet = true,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (modelOptions.isEmpty()) {
                    BasicText(
                        text = if (modelsLoading) "Loading models…" else "The server lists no models for ${phoneProviderDisplayName(activeProvider)}.",
                        style = ppmText(13f, lineHeight = 18f).copy(color = tokens.text2.color),
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 12.dp)
                            .testTag("phone-ai-model-empty"),
                    )
                }
                for (opt in modelOptions) {
                    val isSelected = opt.value == selectedModelOption.value
                    ListRow(
                        title = opt.label,
                        subtitle = opt.subtitle,
                        selected = isSelected,
                        modifier = Modifier.testTag("phone-ai-model-option-${opt.value}"),
                        trailingContent = if (isSelected) {
                            { PpmIcon(ProductIcon.Check, 16.dp, tokens.accent.color) }
                        } else {
                            null
                        },
                        onClick = {
                            modelSheetOpen = false
                            onSelectModel(opt.value)
                        },
                    )
                }
            }
        }
    }

    if (permissionSheetOpen) {
        Sheet(
            title = "Default Permission Mode",
            subtitle = "Applies to new chats",
            onDismiss = { permissionSheetOpen = false },
            forceBottomSheet = true,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (opt in PhonePermissionModeOptions) {
                    val isSelected = opt.value == selectedPermissionOption.value
                    ListRow(
                        title = opt.label,
                        subtitle = opt.subtitle,
                        selected = isSelected,
                        modifier = Modifier.testTag("phone-ai-permission-option-${opt.value}"),
                        trailingContent = if (isSelected) {
                            { PpmIcon(ProductIcon.Check, 16.dp, tokens.accent.color) }
                        } else {
                            null
                        },
                        onClick = {
                            permissionSheetOpen = false
                            onSelectPermissionMode(opt.value)
                        },
                    )
                }
            }
        }
    }
    }
}

@Composable
internal fun PhoneSettingsSubPageHeader(
    title: String,
    onBack: () -> Unit,
    trailingAction: (@Composable () -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .ppmClickable(onClickLabel = "Back to Settings", onClick = onBack)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .testTag("phone-settings-back"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PpmIcon(ProductIcon.ArrowLeft, 18.dp, tokens.text.color)
                BasicText(
                    text = title,
                    style = ppmText(16f, FontWeight.SemiBold, lineHeight = 20f).copy(color = tokens.text.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            if (trailingAction != null) {
                trailingAction()
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(tokens.borderSoft.color),
        )
    }
}

@Composable
internal fun PhoneSectionCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.panel.color)
            .cssBorder(tokens.borderSoft.color, shape),
    ) {
        content()
    }
}

@Composable
internal fun PhoneSegmentedControl(
    items: List<Pair<String, String>>,
    selectedId: String,
    onSelect: (String) -> Unit,
    tagPrefix: String,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val outerShape = RoundedCornerShape(11.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(outerShape)
            .background(tokens.panel2.color)
            .cssBorder(tokens.borderSoft.color, outerShape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for ((id, label) in items) {
            val selected = id == selectedId
            val pillShape = RoundedCornerShape(8.dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 38.dp)
                    .clip(pillShape)
                    .background(if (selected) tokens.panel.color else tokens.panel2.opacity(0.0).color)
                    .then(
                        if (selected) Modifier.cssBorder(tokens.borderSoft.color, pillShape) else Modifier,
                    )
                    .ppmClickable(onClick = { onSelect(id) })
                    .padding(horizontal = 6.dp, vertical = 6.dp)
                    .testTag("$tagPrefix-$id"),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = label,
                    style = ppmText(
                        size = 12f,
                        weight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    ).copy(color = if (selected) tokens.text.color else tokens.text2.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun PhonePillButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(10.dp)
    val bgColor = when {
        danger -> tokens.error.opacity(0.14).color
        primary -> tokens.accent.color
        else -> tokens.panel2.color
    }
    val borderColor = when {
        danger -> tokens.error.opacity(0.35).color
        primary -> tokens.accent.color
        else -> tokens.border.color
    }
    val textColor = when {
        danger -> tokens.error.color
        primary -> tokens.accentFg.color
        else -> tokens.text.color
    }
    Box(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(bgColor)
            .cssBorder(borderColor, shape)
            .then(
                if (enabled) Modifier.ppmClickable(onClick = onClick) else Modifier,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style = ppmText(13f, FontWeight.SemiBold).copy(
                color = if (enabled) textColor else textColor.copy(alpha = 0.5f),
            ),
            maxLines = 1,
        )
    }
}

/**
 * A number setting saved the way the web's `onBlur` saves it: when the field loses focus or the
 * keyboard's Done is pressed, the typed number is brought into [min]..[max] and saved, and a
 * field holding no number shows [saved] again. [key] gives each provider a field of its own.
 */
@Composable
internal fun PhoneNumberSetting(
    label: String,
    saved: Int,
    min: Int,
    max: Int,
    key: String,
    tag: String,
    onSave: (Int) -> Unit,
    below: @Composable () -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    val focusManager = LocalFocusManager.current
    var text by remember(key, saved) { mutableStateOf(saved.toString()) }
    var focused by remember(key) { mutableStateOf(false) }
    // Done clears focus, which commits again before [saved] has caught up: one save per value.
    var sent by remember(key, saved) { mutableStateOf<Int?>(null) }
    fun commit() {
        val value = phoneClampedNumber(text, min, max)
        text = (value ?: saved).toString()
        if (value != null && value != saved && value != sent) {
            sent = value
            onSave(value)
        }
    }
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(label, style = ppmText(14f, FontWeight.Medium).copy(color = tokens.text.color))
        BasicTextField(
            value = text,
            onValueChange = { typed -> text = typed.filter { it.isDigit() || it == '-' }.take(6) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit(); focusManager.clearFocus() }),
            textStyle = ppmText(13f).copy(color = tokens.text.color),
            cursorBrush = SolidColor(tokens.accent.color),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .asksForTextFocus()
                .onFocusChanged { state ->
                    if (focused && !state.isFocused) commit()
                    focused = state.isFocused
                }
                .clip(shape)
                .background(tokens.bgSolid.color)
                .cssBorder(tokens.border.color, shape)
                .padding(horizontal = 10.dp, vertical = 12.dp)
                .testTag(tag),
        )
        below()
    }
}
