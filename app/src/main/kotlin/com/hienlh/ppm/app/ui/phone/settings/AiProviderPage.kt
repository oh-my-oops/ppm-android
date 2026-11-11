package com.hienlh.ppm.app.ui.phone.settings

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.phone.OpenOnTabletNotice
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
)

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

    fun saveField(provider: String, field: String, value: String?) {
        val current = settings
        if (current != null) {
            val prevProvider = current.providers[provider] ?: AiProviderSettings()
            val nextProvider = when (field) {
                "model" -> prevProvider.copy(model = value)
                "effort" -> prevProvider.copy(effort = value)
                "permission_mode" -> prevProvider.copy(permissionMode = value)
                else -> prevProvider
            }
            settings = current.copy(providers = current.providers + (provider to nextProvider))
        }
        val client = api ?: return
        scope.launch {
            try {
                val updated = client.updateAiSettings(AiSettingsPatch.field(provider, field, value))
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
                saveField(activeProvider, "model", chosen.takeUnless { it == PHONE_DEFAULT_MODEL })
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
        )
    }
}

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
) {
    val tokens = LocalPpmTokens.current
    var modelSheetOpen by remember { mutableStateOf(false) }
    var permissionSheetOpen by remember { mutableStateOf(false) }

    val modelOptions = remember(models, includeAutoDefaultModel) {
        val mapped = models.map { PhoneSelectOption(it.value, it.label) }
        if (includeAutoDefaultModel) {
            listOf(PhoneSelectOption(PHONE_DEFAULT_MODEL, "Auto (default)", "Auto")) + mapped
        } else {
            // Only the models the server lists for this provider; none listed means none offered, not Claude's.
            mapped
        }
    }
    val selectedModelOption = modelOptions.firstOrNull { it.value == modelValue }
        ?: when {
            modelValue == PHONE_DEFAULT_MODEL -> PhoneSelectOption(modelValue, "Auto (default)", "Auto")
            modelValue.isBlank() -> PhoneSelectOption(modelValue, "Not set", "Not set")
            else -> PhoneSelectOption(modelValue, modelValue, modelValue)
        }

    val effortOptions = if (activeProvider.equals("antigravity", ignoreCase = true)) {
        PhoneAntigravityEffortOptions
    } else {
        PhoneEffortOptions
    }
    val selectedEffortOption = effortOptions.firstOrNull { it.value == effortValue }
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

        // Tablet-only notice for advanced AI settings
        OpenOnTabletNotice(
            text = "Keys, limits, MCP, teams and instructions open on a tablet.",
        )

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
