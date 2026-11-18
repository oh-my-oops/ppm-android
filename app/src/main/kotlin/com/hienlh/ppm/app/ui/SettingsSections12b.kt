package com.hienlh.ppm.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.client.accountUsages
import com.hienlh.ppm.client.spendClaudeLimitReset
import com.hienlh.ppm.client.spendCodexResetCredit
import com.hienlh.ppm.state.claudeResetResultMessage
import com.hienlh.ppm.state.resetCreditOutcome
import com.hienlh.ppm.client.accounts
import com.hienlh.ppm.client.accountsAddManual
import com.hienlh.ppm.client.accountsDelete
import com.hienlh.ppm.client.accountsExportEncrypted
import com.hienlh.ppm.client.accountsGetSettings
import com.hienlh.ppm.client.accountsImportEncrypted
import com.hienlh.ppm.client.accountsOAuthExchange
import com.hienlh.ppm.client.accountsOAuthUrl
import com.hienlh.ppm.client.accountsPutSettings
import com.hienlh.ppm.client.accountsTestToken
import com.hienlh.ppm.client.accountsUsageHistory
import com.hienlh.ppm.client.activeAccount
import com.hienlh.ppm.client.aiResourcesCreate
import com.hienlh.ppm.client.aiResourcesDelete
import com.hienlh.ppm.client.aiResourcesDuplicate
import com.hienlh.ppm.client.aiResourcesList
import com.hienlh.ppm.client.aiSettings
import com.hienlh.ppm.client.antigravityAccountsOverview
import com.hienlh.ppm.client.antigravityCancelLogin
import com.hienlh.ppm.client.antigravityDeleteAccount
import com.hienlh.ppm.client.antigravityLoginStatus
import com.hienlh.ppm.client.antigravitySetStrategy
import com.hienlh.ppm.client.antigravityStartLogin
import com.hienlh.ppm.client.antigravitySubmitLoginCode
import com.hienlh.ppm.client.antigravityUsages
import com.hienlh.ppm.client.codexAccounts
import com.hienlh.ppm.client.codexAccountsOverview
import com.hienlh.ppm.client.codexAddApiKey
import com.hienlh.ppm.client.codexBrowserLoginStatus
import com.hienlh.ppm.client.codexCancelBrowserLogin
import com.hienlh.ppm.client.codexCancelDeviceLogin
import com.hienlh.ppm.client.codexDeleteAccount
import com.hienlh.ppm.client.codexDeviceLoginStatus
import com.hienlh.ppm.client.codexExportEncrypted
import com.hienlh.ppm.client.codexImportEncrypted
import com.hienlh.ppm.client.codexSetStrategy
import com.hienlh.ppm.client.codexStartBrowserLogin
import com.hienlh.ppm.client.codexStartDeviceLogin
import com.hienlh.ppm.client.codexSubmitBrowserCallback
import com.hienlh.ppm.client.codexUsages
import com.hienlh.ppm.client.designCreateSystem
import com.hienlh.ppm.client.designDeleteSystem
import com.hienlh.ppm.client.designGetSettings
import com.hienlh.ppm.client.designInstallSkill
import com.hienlh.ppm.client.designListSystems
import com.hienlh.ppm.client.designPutInstructions
import com.hienlh.ppm.client.mcpAddServer
import com.hienlh.ppm.client.mcpDeleteServer
import com.hienlh.ppm.client.mcpImportFromClaude
import com.hienlh.ppm.client.mcpListServers
import com.hienlh.ppm.client.mcpUpdateServer
import com.hienlh.ppm.client.setAccountStatus
import com.hienlh.ppm.client.setAntigravityAccountStatus
import com.hienlh.ppm.client.setCodexAccountStatus
import com.hienlh.ppm.client.setCodexDailyGuard
import com.hienlh.ppm.client.updateAiSettings
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.AccountInfo
import com.hienlh.ppm.protocol.AccountUsage
import com.hienlh.ppm.protocol.AccountUsageEntry
import com.hienlh.ppm.protocol.AiSettingsPatch
import com.hienlh.ppm.protocol.AntigravityAccount
import com.hienlh.ppm.protocol.CodexAccount
import com.hienlh.ppm.protocol.UsageInfo
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.codexPlanLabel
import com.hienlh.ppm.state.currentAntigravityUsage
import com.hienlh.ppm.state.dailyGuardState
import com.hienlh.ppm.state.formatLastUpdated
import com.hienlh.ppm.state.formatResetTime
import com.hienlh.ppm.state.isAntigravityUsageStale
import com.hienlh.ppm.state.isoMillis
import com.hienlh.ppm.state.settings.AntigravityLoginFlow
import com.hienlh.ppm.state.settings.duplicateAiResourceTargetScope
import com.hienlh.ppm.state.settings.AccountRotationSettings
import com.hienlh.ppm.state.settings.AiResourceFilter
import com.hienlh.ppm.state.settings.AiResourceItem
import com.hienlh.ppm.state.settings.AiResourcesOverview
import com.hienlh.ppm.state.settings.CodexBrowserLoginFlow
import com.hienlh.ppm.state.settings.CodexDeviceLoginFlow
import com.hienlh.ppm.state.settings.DESIGN_INSTRUCTIONS_MAX_BYTES
import com.hienlh.ppm.state.settings.DesignSettingsState
import com.hienlh.ppm.state.settings.DesignSkillItem
import com.hienlh.ppm.state.settings.DesignSystemRow
import com.hienlh.ppm.state.settings.McpKeyValuePair
import com.hienlh.ppm.state.settings.McpServerRow
import com.hienlh.ppm.state.settings.McpTransport
import com.hienlh.ppm.state.settings.OAuthCodeFlowStart
import com.hienlh.ppm.state.settings.PPM_TOOL_GROUPS
import com.hienlh.ppm.state.settings.TokenTestReport
import com.hienlh.ppm.state.settings.UsageSnapshotPoint
import com.hienlh.ppm.state.settings.buildMcpConfigJson
import com.hienlh.ppm.state.settings.effectiveClaudeBackupPassword
import com.hienlh.ppm.state.settings.filterAiResources
import com.hienlh.ppm.state.settings.isPpmToolEnabled
import com.hienlh.ppm.state.settings.looksLikeAnthropicOAuthCode
import com.hienlh.ppm.state.settings.looksLikeCodexLoopbackUrl
import com.hienlh.ppm.state.settings.parseAccountRotationSettings
import com.hienlh.ppm.state.settings.parseAiResources
import com.hienlh.ppm.state.settings.parseAntigravityLoginStart
import com.hienlh.ppm.state.settings.parseAntigravityLoginStatus
import com.hienlh.ppm.state.settings.parseCodexBrowserLogin
import com.hienlh.ppm.state.settings.parseCodexDeviceLogin
import com.hienlh.ppm.state.settings.parseCodexLoginPollStatus
import com.hienlh.ppm.state.settings.parseDesignSettings
import com.hienlh.ppm.state.settings.parseDesignSystems
import com.hienlh.ppm.state.settings.parseMcpServers
import com.hienlh.ppm.state.settings.parseOAuthCodeFlowStart
import com.hienlh.ppm.state.settings.parseTokenTestReport
import com.hienlh.ppm.state.settings.parseUsageHistory
import com.hienlh.ppm.state.settings.utf8ByteCount
import com.hienlh.ppm.state.settings.validateAiResourceName
import com.hienlh.ppm.state.settings.validateMcpConfig
import com.hienlh.ppm.state.settings.validateMcpName
import com.hienlh.ppm.state.usagePercent
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ===========================================================================
// 1. AI Provider Section (mounts AiSettingsSection from AiSettingsPanel.kt)
// ===========================================================================

@Composable
internal fun AiProviderSettingsSection(model: AppModel) {
    AiSettingsSection(model)
}

// ===========================================================================
// 2. Tools Section (ppm_tools switches via PUT /api/settings/ai)
// ===========================================================================

@Composable
internal fun ToolsSettingsSection(model: AppModel) {
    val api = model.api
    val scope = rememberCoroutineScope()
    var ppmTools by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var hideBuiltinCron by remember { mutableStateOf<Boolean?>(null) }
    var sessionCollab by remember { mutableStateOf<kotlinx.serialization.json.JsonElement?>(null) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        val client = api ?: return
        try {
            val loaded = client.aiSettings()
            ppmTools = loaded.ppmTools
            hideBuiltinCron = loaded.hideBuiltinCron
            sessionCollab = loaded.sessionCollab
            errorMsg = null
        } catch (t: Throwable) {
            errorMsg = t.message ?: "Failed to load tools settings"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(api) {
        if (api != null) reload() else loading = false
    }

    ToolsSettingsContent(
        ppmTools = ppmTools,
        loading = loading,
        errorMsg = errorMsg,
        onToggleTool = { toolId, enabled ->
            val previous = ppmTools
            ppmTools = com.hienlh.ppm.state.settings.patchPpmToolSwitches(previous, toolId, enabled)
            errorMsg = null
            val client = api ?: return@ToolsSettingsContent
            scope.launch {
                try {
                    val updated = client.updateAiSettings(AiSettingsPatch.ppmTool(toolId, enabled))
                    model.newChats.preparationCache.clear()
                    model.newChatCache.writeSettings(updated.preparation)
                    ppmTools = updated.ppmTools
                } catch (t: Throwable) {
                    ppmTools = previous
                    errorMsg = t.message ?: "Failed to update tool '$toolId'"
                    reload()
                }
            }
        },
        hideBuiltinCron = hideBuiltinCron != false,
        onHideBuiltinCron = { on ->
            val previous = hideBuiltinCron
            hideBuiltinCron = on
            errorMsg = null
            val client = api ?: return@ToolsSettingsContent
            scope.launch {
                try {
                    hideBuiltinCron = client.updateAiSettings(AiSettingsPatch.hideBuiltinCron(on)).hideBuiltinCron
                } catch (t: Throwable) {
                    hideBuiltinCron = previous
                    errorMsg = t.message ?: "Failed to update the cron setting"
                }
            }
        },
        sessionCollab = com.hienlh.ppm.state.settings.SessionCollabSettings.resolve(sessionCollab),
        onSessionCollab = { key, value ->
            errorMsg = null
            val client = api ?: return@ToolsSettingsContent
            scope.launch {
                try {
                    sessionCollab = client.updateAiSettings(com.hienlh.ppm.protocol.AiSettingsPatch.sessionCollab(key, value)).sessionCollab
                } catch (t: Throwable) {
                    errorMsg = t.message ?: "Could not save"
                    reload()
                }
            }
        },
    )
}

@Composable
internal fun ToolsSettingsContent(
    ppmTools: Map<String, Boolean>,
    loading: Boolean = false,
    errorMsg: String? = null,
    onToggleTool: (toolId: String, enabled: Boolean) -> Unit,
    /** `hide_builtin_cron`, on unless set off. */
    hideBuiltinCron: Boolean = true,
    onHideBuiltinCron: (Boolean) -> Unit = {},
    sessionCollab: com.hienlh.ppm.state.settings.SessionCollabSettings = com.hienlh.ppm.state.settings.SessionCollabSettings(),
    onSessionCollab: (key: String, value: kotlinx.serialization.json.JsonElement) -> Unit = { _, _ -> },
) {
    val tokens = LocalPpmTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BasicText(
            "PPM's own tools for the AI chat, with Claude, Codex and Antigravity alike. New chats get the tools that are on; turning one off also stops it in chats already open.",
            style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color),
        )
        if (loading) {
            BasicText("Loading tools…", style = ppmText(11f).copy(color = tokens.text3.color))
        }
        if (errorMsg != null) {
            BasicText(
                errorMsg,
                style = ppmText(11f).copy(color = tokens.error.color),
                modifier = Modifier.testTag("tools-settings-error"),
            )
        }
        for ((index, group) in PPM_TOOL_GROUPS.withIndex()) {
            if (index > 0) Separator()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsSectionTitle(group.title)
                BasicText(group.intro, style = ppmText(11f).copy(color = tokens.text3.color))
                for (tool in group.tools) {
                    val enabled = isPpmToolEnabled(ppmTools, tool.id)
                    SettingsToggleRow(
                        title = "${tool.label} (${tool.id})",
                        subtitle = tool.note,
                        checked = enabled,
                        onCheckedChange = { next -> onToggleTool(tool.id, next) },
                        testTag = "ppm-tool-toggle-${tool.id}",
                    )
                }
                if (group.tools.any { it.id == "create_session" }) {
                    com.hienlh.ppm.app.ui.settings.SessionCollabControls(sessionCollab, onSessionCollab)
                }
                if (group.tools.any { it.id == "session_schedule_create" }) {
                    SettingsToggleRow(
                        title = "Hide Claude's built-in cron tools",
                        subtitle = if (isPpmToolEnabled(ppmTools, "session_schedule_create")) {
                            "Claude chats start without CronCreate, CronList and CronDelete, whose jobs end with the chat's process, so Claude schedules through PPM. /loop keeps working."
                        } else {
                            "Takes effect while “Schedule prompts” is on. Claude only: Codex has no cron tools of its own."
                        },
                        checked = hideBuiltinCron,
                        onCheckedChange = onHideBuiltinCron,
                        testTag = "ppm-hide-builtin-cron",
                    )
                }
            }
        }
    }
}

// ===========================================================================
// 3. Accounts Section (Claude + Codex + Antigravity Accounts)
// ===========================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AccountsSettingsSection(model: AppModel) {
    // An account the chat asked to be shown: its provider's tab opens on it.
    var focus by remember { mutableStateOf(AccountFocus.take()) }
    var activeTab by remember { mutableStateOf(focus?.provider ?: "claude") }
    val pendingFocus = AccountFocus.pending
    LaunchedEffect(pendingFocus) {
        AccountFocus.take()?.let {
            activeTab = it.provider
            focus = it
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for ((id, label) in listOf(
                "claude" to "Claude Accounts",
                "codex" to "Codex Accounts",
                "antigravity" to "Antigravity Accounts",
            )) {
                val selected = activeTab == id
                SettingsButton(
                    label = label,
                    primary = selected,
                    modifier = Modifier.testTag("accounts-tab-$id"),
                    onClick = { activeTab = id },
                )
            }
        }
        Separator()
        when (activeTab) {
            "claude" -> ClaudeAccountsPane(model, focus)
            "codex" -> CodexAccountsPane(model, focus)
            "antigravity" -> AntigravityAccountsPane(model)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClaudeAccountsPane(model: AppModel, focus: AccountFocusRequest? = null) {
    val tokens = LocalPpmTokens.current
    val api = model.api
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    var accounts by remember { mutableStateOf<List<AccountInfo>>(emptyList()) }
    var usages by remember { mutableStateOf<List<AccountUsageEntry>>(emptyList()) }
    var activeId by remember { mutableStateOf<String?>(null) }
    var rotation by remember { mutableStateOf(AccountRotationSettings()) }
    var loading by remember { mutableStateOf(true) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var togglingId by remember { mutableStateOf<String?>(null) }

    // Dialog states
    var oauthFlow by remember { mutableStateOf<OAuthCodeFlowStart?>(null) }
    var oauthCodeInput by remember { mutableStateOf("") }
    var oauthBusy by remember { mutableStateOf(false) }
    var showAddKeyDialog by remember { mutableStateOf(false) }
    var showRotationDialog by remember { mutableStateOf(false) }
    var showExportImportDialog by remember { mutableStateOf(false) }
    var deletingAccount by remember { mutableStateOf<AccountInfo?>(null) }
    var testingAccount by remember { mutableStateOf<AccountInfo?>(null) }
    var testReport by remember { mutableStateOf<TokenTestReport?>(null) }
    var testingBusy by remember { mutableStateOf(false) }
    var historyAccount by remember { mutableStateOf<AccountInfo?>(null) }
    var historyPoints by remember { mutableStateOf<List<UsageSnapshotPoint>>(emptyList()) }

    suspend fun reload() {
        val client = api ?: return
        loading = true
        try {
            accounts = client.accounts()
            usages = client.accountUsages()
            activeId = client.activeAccount()?.id
            rotation = parseAccountRotationSettings(client.accountsGetSettings())
        } catch (t: Throwable) {
            isError = true
            statusMsg = t.message ?: "Failed to load Claude accounts"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(api) {
        if (api != null) reload() else loading = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsButton(
                label = "+ Sign in (OAuth)",
                primary = true,
                enabled = api != null && !oauthBusy,
                modifier = Modifier.testTag("claude-oauth-start-btn"),
                onClick = {
                    val client = api ?: return@SettingsButton
                    oauthBusy = true
                    statusMsg = null
                    scope.launch {
                        try {
                            val start = parseOAuthCodeFlowStart(client.accountsOAuthUrl())
                            if (start != null) {
                                oauthFlow = start
                                oauthCodeInput = ""
                                runCatching { uriHandler.openUri(start.url) }
                            } else {
                                isError = true
                                statusMsg = "Server did not return an OAuth URL"
                            }
                        } catch (t: Throwable) {
                            isError = true
                            statusMsg = t.message ?: "Failed to start OAuth"
                        } finally {
                            oauthBusy = false
                        }
                    }
                },
            )
            SettingsButton(
                label = "+ Add API Key",
                enabled = api != null,
                modifier = Modifier.testTag("claude-add-key-btn"),
                onClick = { showAddKeyDialog = true },
            )
            SettingsButton(
                label = "Rotation (${rotation.strategy})",
                enabled = api != null,
                modifier = Modifier.testTag("claude-rotation-btn"),
                onClick = { showRotationDialog = true },
            )
            SettingsButton(
                label = "Export / Import",
                enabled = api != null,
                modifier = Modifier.testTag("claude-export-import-btn"),
                onClick = { showExportImportDialog = true },
            )
            SettingsButton(
                label = if (loading) "Refreshing…" else "Refresh",
                enabled = api != null && !loading,
                onClick = { scope.launch { reload() } },
            )
        }

        if (statusMsg != null) {
            BasicText(
                statusMsg!!,
                style = ppmText(11f).copy(color = if (isError) tokens.error.color else tokens.success.color),
            )
        }

        val infoById = remember(accounts) { accounts.associateBy { it.id } }
        if (usages.isEmpty() && !loading) {
            BasicText(
                "No Claude accounts connected yet. Sign in with OAuth or add an API key.",
                style = ppmText(12f).copy(color = tokens.text3.color),
            )
        }

        for (entry in usages) {
            val info = infoById[entry.accountId]
            val shape = RoundedCornerShape(10.dp)
            val reveal = rememberAccountReveal(focus, entry.accountId)
            Column(
                Modifier
                    .bringIntoViewRequester(reveal.requester)
                    .fillMaxWidth()
                    .clip(shape)
                    .background(tokens.panel.color)
                    .cssBorder(tokens.border.color, shape)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AccountCard(
                    entry = entry,
                    isActive = entry.accountId == activeId,
                    accountInfo = info,
                    layout = AccountCardLayout.Grid,
                    flash = reveal.flash,
                    resetCreditAction = {
                        val label = entry.accountLabel ?: entry.accountId.take(8)
                        ClaudeLimitResetsButton(
                            accountLabel = label,
                            resets = entry.usage.limitResets,
                            spend = { grant ->
                                val client = api ?: error("Not connected")
                                claudeResetResultMessage(label, client.spendClaudeLimitReset(entry.accountId, grant.id))
                            },
                            onDone = { message, failed ->
                                statusMsg = message
                                isError = failed
                                scope.launch { reload() }
                            },
                        )
                    },
                    toggling = togglingId == entry.accountId,
                    onToggle = { id, currentStatus ->
                        val client = api ?: return@AccountCard
                        val next = if (currentStatus == "disabled") "active" else "disabled"
                        togglingId = id
                        if (next == "active") {
                            isError = false
                            statusMsg = "Verifying account… this can take up to a minute."
                        }
                        scope.launch {
                            try {
                                client.setAccountStatus(id, next)
                                reload()
                                statusMsg = if (next == "active") "Account enabled" else "Account disabled"
                                isError = false
                            } catch (t: Throwable) {
                                isError = true
                                statusMsg = t.message ?: "Failed to change account status"
                            } finally {
                                togglingId = null
                            }
                        }
                    },
                    onReauth = {
                        val client = api ?: return@AccountCard
                        scope.launch {
                            try {
                                val start = parseOAuthCodeFlowStart(client.accountsOAuthUrl())
                                if (start != null) {
                                    oauthFlow = start
                                    oauthCodeInput = ""
                                    runCatching { uriHandler.openUri(start.url) }
                                }
                            } catch (t: Throwable) {
                                isError = true
                                statusMsg = t.message ?: "Failed to start sign-in"
                            }
                        }
                    },
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (info != null) {
                        SettingsButton(
                            label = "Test Token",
                            onClick = {
                                testingAccount = info
                                testReport = null
                            },
                        )
                        SettingsButton(
                            label = "Usage History",
                            onClick = {
                                val client = api ?: return@SettingsButton
                                historyAccount = info
                                scope.launch {
                                    historyPoints = runCatching {
                                        parseUsageHistory(client.accountsUsageHistory(info.id))
                                    }.getOrDefault(emptyList())
                                }
                            },
                        )
                        SettingsButton(
                            label = "Delete",
                            danger = true,
                            onClick = { deletingAccount = info },
                        )
                    }
                }
            }
        }
    }

    // OAuth Code Exchange Dialog
    oauthFlow?.let { flow ->
        val clipboardText = remember(flow) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
        }
        Dialog(onDismissRequest = { if (!oauthBusy) oauthFlow = null }) {
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(tokens.bgSolid.color)
                    .cssBorder(tokens.border.color, shape)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsSectionTitle("Complete Claude OAuth Sign-In")
                BasicText(
                    "1. Approve access in your browser.\n" +
                        "2. Copy the authorization code shown on Anthropic's callback page (keep any #state suffix intact).\n" +
                        "3. Paste it below. Note: signing in to a different Claude account updates or adds that account.",
                    style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text2.color),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsButton(
                        label = "Open Sign-In URL Again",
                        onClick = { runCatching { uriHandler.openUri(flow.url) } },
                    )
                    if (looksLikeAnthropicOAuthCode(clipboardText)) {
                        SettingsButton(
                            label = "Paste from Clipboard",
                            primary = true,
                            onClick = { oauthCodeInput = clipboardText },
                        )
                    }
                }
                SettingsTextInput(
                    value = oauthCodeInput,
                    onValueChange = { oauthCodeInput = it },
                    placeholder = "Paste authorization code (e.g. code#state)",
                    mono = true,
                    modifier = Modifier.fillMaxWidth().testTag("claude-oauth-code-input"),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsButton(
                        label = "Cancel",
                        enabled = !oauthBusy,
                        onClick = { oauthFlow = null },
                    )
                    Spacer(Modifier.width(8.dp))
                    SettingsButton(
                        label = if (oauthBusy) "Exchanging…" else "Exchange Code",
                        primary = true,
                        enabled = !oauthBusy && looksLikeAnthropicOAuthCode(oauthCodeInput),
                        modifier = Modifier.testTag("claude-oauth-submit-btn"),
                        onClick = {
                            val client = api ?: return@SettingsButton
                            oauthBusy = true
                            scope.launch {
                                try {
                                    client.accountsOAuthExchange(code = oauthCodeInput.trim(), state = flow.state)
                                    oauthFlow = null
                                    reload()
                                    isError = false
                                    statusMsg = "Claude account connected"
                                } catch (t: Throwable) {
                                    isError = true
                                    statusMsg = t.message ?: "OAuth exchange failed"
                                } finally {
                                    oauthBusy = false
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    // Add Manual API Key Dialog
    if (showAddKeyDialog) {
        var apiKey by remember { mutableStateOf("") }
        var label by remember { mutableStateOf("") }
        var showKey by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        Dialog(onDismissRequest = { if (!busy) showAddKeyDialog = false }) {
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(tokens.bgSolid.color)
                    .cssBorder(tokens.border.color, shape)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsSectionTitle("Add Claude API Key / Token")
                SettingsTextInput(
                    value = label,
                    onValueChange = { label = it },
                    placeholder = "Label (e.g. Team Key)",
                    modifier = Modifier.fillMaxWidth(),
                )
                SettingsPasswordInput(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    placeholder = "sk-ant-...",
                    visible = showKey,
                    onToggleVisible = { showKey = !showKey },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SettingsButton(label = "Cancel", enabled = !busy, onClick = { showAddKeyDialog = false })
                    Spacer(Modifier.width(8.dp))
                    SettingsButton(
                        label = if (busy) "Adding…" else "Add",
                        primary = true,
                        enabled = !busy && apiKey.isNotBlank(),
                        onClick = {
                            val client = api ?: return@SettingsButton
                            busy = true
                            scope.launch {
                                try {
                                    client.accountsAddManual(apiKey.trim(), label.trim().ifEmpty { null })
                                    showAddKeyDialog = false
                                    reload()
                                    isError = false
                                    statusMsg = "API key added"
                                } catch (t: Throwable) {
                                    isError = true
                                    statusMsg = t.message ?: "Failed to add API key"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    // Rotation Settings Dialog
    if (showRotationDialog) {
        var strategy by remember(rotation) { mutableStateOf(rotation.strategy) }
        var maxRetryText by remember(rotation) { mutableStateOf(rotation.maxRetry.toString()) }
        var cooldownEnabled by remember(rotation) { mutableStateOf(rotation.cooldownEnabled) }
        var busy by remember { mutableStateOf(false) }
        Dialog(onDismissRequest = { if (!busy) showRotationDialog = false }) {
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(tokens.bgSolid.color)
                    .cssBorder(tokens.border.color, shape)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsSectionTitle("Account Rotation Settings")
                for ((id, title, desc) in listOf(
                    Triple("round-robin", "Round-Robin", "Cycle evenly across active accounts"),
                    Triple("fill-first", "Fill-First", "Use one account until rate-limited, then switch"),
                    Triple("lowest-usage", "Lowest Usage", "Pick the account with the lowest utilization"),
                )) {
                    SettingsRadioCard(
                        title = title,
                        subtitle = desc,
                        selected = strategy == id,
                        onClick = { strategy = id },
                    )
                }
                SettingsTextInput(
                    value = maxRetryText,
                    onValueChange = { maxRetryText = it },
                    placeholder = "Max retries (e.g. 3)",
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.fillMaxWidth(),
                )
                SettingsToggleRow(
                    title = "Cooldown on rate limit",
                    subtitle = "Temporarily skip accounts that hit 429 rate limits",
                    checked = cooldownEnabled,
                    onCheckedChange = { cooldownEnabled = it },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SettingsButton(label = "Cancel", enabled = !busy, onClick = { showRotationDialog = false })
                    Spacer(Modifier.width(8.dp))
                    SettingsButton(
                        label = if (busy) "Saving…" else "Save",
                        primary = true,
                        enabled = !busy,
                        onClick = {
                            val client = api ?: return@SettingsButton
                            busy = true
                            scope.launch {
                                try {
                                    val updated = client.accountsPutSettings(
                                        strategy = strategy,
                                        maxRetry = maxRetryText.trim().toIntOrNull() ?: rotation.maxRetry,
                                        cooldownEnabled = cooldownEnabled,
                                    )
                                    rotation = parseAccountRotationSettings(updated)
                                    showRotationDialog = false
                                    isError = false
                                    statusMsg = "Rotation settings saved"
                                } catch (t: Throwable) {
                                    isError = true
                                    statusMsg = t.message ?: "Failed to save rotation settings"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    // Export / Import Dialog
    if (showExportImportDialog) {
        var password by remember { mutableStateOf("") }
        var showPassword by remember { mutableStateOf(false) }
        var ppmPassword by remember { mutableStateOf("") }
        var showPpmPassword by remember { mutableStateOf(false) }
        var includeRefresh by remember { mutableStateOf(true) }
        var refreshBefore by remember { mutableStateOf(false) }
        var payloadText by remember { mutableStateOf("") }
        var dialogMsg by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        Dialog(onDismissRequest = { if (!busy) showExportImportDialog = false }) {
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(tokens.bgSolid.color)
                    .cssBorder(tokens.border.color, shape)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsSectionTitle("Export / Import Claude Accounts")
                BasicText(
                    "Warning: if you export refresh tokens and another machine refreshes them, this server's access token expires in ~1 hour.",
                    style = ppmText(11f, lineHeight = 16f).copy(color = tokens.warning.color),
                )
                SettingsPasswordInput(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = "Leave empty for default",
                    visible = showPassword,
                    onToggleVisible = { showPassword = !showPassword },
                    modifier = Modifier.fillMaxWidth(),
                    inputTestTag = "claude-backup-password-input",
                )
                SettingsPasswordInput(
                    value = ppmPassword,
                    onValueChange = { ppmPassword = it },
                    placeholder = "PPM server password (required when auth is enabled)",
                    visible = showPpmPassword,
                    onToggleVisible = { showPpmPassword = !showPpmPassword },
                    modifier = Modifier.fillMaxWidth(),
                    inputTestTag = "claude-backup-ppm-password-input",
                )
                SettingsToggleRow(
                    title = "Include refresh tokens",
                    subtitle = "Allow destination server to renew OAuth tokens",
                    checked = includeRefresh,
                    onCheckedChange = { includeRefresh = it },
                )
                SettingsToggleRow(
                    title = "Refresh before export",
                    subtitle = "Mint fresh tokens before bundling",
                    checked = refreshBefore,
                    onCheckedChange = { refreshBefore = it },
                )
                SettingsTextInput(
                    value = payloadText,
                    onValueChange = { payloadText = it },
                    placeholder = "Encrypted JSON bundle (populated on Export, or paste to Import)",
                    mono = true,
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                )
                if (dialogMsg != null) {
                    BasicText(dialogMsg!!, style = ppmText(11f).copy(color = tokens.text2.color))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsButton(
                        label = "Export",
                        enabled = !busy,
                        onClick = {
                            val client = api ?: return@SettingsButton
                            busy = true
                            scope.launch {
                                try {
                                    val data = client.accountsExportEncrypted(
                                        password = effectiveClaudeBackupPassword(password),
                                        ppmPassword = ppmPassword.ifBlank { null },
                                        includeRefreshToken = includeRefresh,
                                        refreshBeforeExport = refreshBefore,
                                    )
                                    payloadText = data
                                    copySensitiveClip(context, "PPM Claude Accounts", data)
                                    dialogMsg = "Exported & copied to clipboard"
                                } catch (t: Throwable) {
                                    dialogMsg = t.message ?: "Export failed"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    )
                    SettingsButton(
                        label = "Import",
                        primary = true,
                        enabled = !busy && payloadText.isNotBlank(),
                        onClick = {
                            val client = api ?: return@SettingsButton
                            busy = true
                            scope.launch {
                                try {
                                    client.accountsImportEncrypted(
                                        payloadText.trim(),
                                        effectiveClaudeBackupPassword(password),
                                    )
                                    reload()
                                    dialogMsg = "Imported successfully"
                                } catch (t: Throwable) {
                                    dialogMsg = t.message ?: "Import failed"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    SettingsButton(label = "Close", enabled = !busy, onClick = { showExportImportDialog = false })
                }
            }
        }
    }

    // Token Test Dialog
    testingAccount?.let { acc ->
        var testRefresh by remember { mutableStateOf(false) }
        Dialog(onDismissRequest = { if (!testingBusy) testingAccount = null }) {
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(tokens.bgSolid.color)
                    .cssBorder(tokens.border.color, shape)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsSectionTitle("Test Token: ${acc.label ?: acc.id.take(8)}")
                SettingsToggleRow(
                    title = "Test refresh token",
                    subtitle = "Also test minting a new access token via OAuth refresh",
                    checked = testRefresh,
                    onCheckedChange = { testRefresh = it },
                )
                testReport?.let { rep ->
                    val ok = rep.accessTokenStatus == "ok" || rep.accessTokenStatus == "valid"
                    BasicText(
                        "Access Token: ${rep.accessTokenStatus}" +
                            (rep.accessTokenCode?.let { " (HTTP $it)" } ?: "") +
                            (rep.accessTokenError?.let { " — $it" } ?: ""),
                        style = ppmText(12f, FontWeight.Medium).copy(color = if (ok) tokens.success.color else tokens.error.color),
                    )
                    if (rep.refreshTokenStatus != null) {
                        BasicText(
                            "Refresh Token: ${rep.refreshTokenStatus}" + (rep.refreshTokenError?.let { " — $it" } ?: ""),
                            style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text2.color),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SettingsButton(label = "Close", enabled = !testingBusy, onClick = { testingAccount = null })
                    Spacer(Modifier.width(8.dp))
                    SettingsButton(
                        label = if (testingBusy) "Testing…" else "Run Test",
                        primary = true,
                        enabled = !testingBusy,
                        onClick = {
                            val client = api ?: return@SettingsButton
                            testingBusy = true
                            scope.launch {
                                try {
                                    testReport = parseTokenTestReport(client.accountsTestToken(acc.id, testRefresh))
                                } catch (t: Throwable) {
                                    testReport = TokenTestReport(accessTokenStatus = "error", accessTokenError = t.message)
                                } finally {
                                    testingBusy = false
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    // Usage History Dialog
    historyAccount?.let { acc ->
        Dialog(onDismissRequest = { historyAccount = null }) {
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(tokens.bgSolid.color)
                    .cssBorder(tokens.border.color, shape)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsSectionTitle("Usage History: ${acc.label ?: acc.id.take(8)}")
                if (historyPoints.isEmpty()) {
                    BasicText("No usage snapshots recorded yet.", style = ppmText(11f).copy(color = tokens.text3.color))
                } else {
                    for (pt in historyPoints.takeLast(12)) {
                        val sPct = "${(pt.fiveHour * 100).toInt()}%"
                        val wPct = "${(pt.sevenDay * 100).toInt()}%"
                        BasicText(
                            "${pt.recordedAt} · 5h: $sPct · 7d: $wPct",
                            style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text2.color),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SettingsButton(label = "Close", onClick = { historyAccount = null })
                }
            }
        }
    }

    // Delete Confirmation
    deletingAccount?.let { acc ->
        ConfirmDialog(
            title = "Delete ${acc.label ?: acc.id.take(8)}?",
            message = "This removes the Claude account from PPM.",
            confirm = "Delete",
            onConfirm = {
                val client = api ?: return@ConfirmDialog
                scope.launch {
                    runCatching { client.accountsDelete(acc.id) }
                    deletingAccount = null
                    reload()
                }
            },
            onDismiss = { deletingAccount = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CodexAccountsPane(model: AppModel, focus: AccountFocusRequest? = null) {
    val tokens = LocalPpmTokens.current
    val api = model.api
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val mobile = LocalPpmMobile.current

    var accounts by remember { mutableStateOf<List<CodexAccount>>(emptyList()) }
    var usages by remember { mutableStateOf<Map<String, UsageInfo>>(emptyMap()) }
    var strategy by remember { mutableStateOf("round-robin") }
    var loading by remember { mutableStateOf(true) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    // Dialog states
    var deviceFlow by remember { mutableStateOf<CodexDeviceLoginFlow?>(null) }
    var browserFlow by remember { mutableStateOf<CodexBrowserLoginFlow?>(null) }
    var browserCallbackInput by remember { mutableStateOf("") }
    var browserBusy by remember { mutableStateOf(false) }
    var showAddKeyDialog by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var deletingAccount by remember { mutableStateOf<CodexAccount?>(null) }

    suspend fun reload() {
        val client = api ?: return
        loading = true
        try {
            val overview = client.codexAccountsOverview()
            strategy = overview["strategy"].string ?: "round-robin"
            accounts = client.codexAccounts()
            usages = client.codexUsages()
        } catch (t: Throwable) {
            isError = true
            statusMsg = t.message ?: "Failed to load Codex accounts"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(api) {
        if (api != null) reload() else loading = false
    }

    // Poll active device login flow every 2s up to 210s
    LaunchedEffect(deviceFlow?.id) {
        val current = deviceFlow ?: return@LaunchedEffect
        val client = api ?: return@LaunchedEffect
        val deadline = System.currentTimeMillis() + 210_000L
        while (isActive && System.currentTimeMillis() < deadline) {
            delay(2_000L)
            val polled = runCatching { parseCodexLoginPollStatus(client.codexDeviceLoginStatus(current.id)) }.getOrNull()
            if (polled != null) {
                deviceFlow = current.copy(status = polled.state, error = polled.error)
                if (polled.isDone) {
                    deviceFlow = null
                    reload()
                    isError = false
                    statusMsg = "Codex account signed in via device code"
                    break
                } else if (polled.isError) {
                    isError = true
                    statusMsg = polled.error ?: "Device login failed"
                    break
                }
            }
        }
    }

    // Poll active browser login flow every 2s up to 600s
    LaunchedEffect(browserFlow?.id) {
        val current = browserFlow ?: return@LaunchedEffect
        val client = api ?: return@LaunchedEffect
        val deadline = System.currentTimeMillis() + 600_000L
        while (isActive && System.currentTimeMillis() < deadline) {
            delay(2_000L)
            val polled = runCatching { parseCodexLoginPollStatus(client.codexBrowserLoginStatus(current.id)) }.getOrNull()
            if (polled != null) {
                browserFlow = current.copy(status = polled.state, error = polled.error)
                if (polled.isDone) {
                    browserFlow = null
                    browserBusy = false
                    reload()
                    isError = false
                    statusMsg = "Codex account connected via browser login"
                    break
                } else if (polled.isError) {
                    browserBusy = false
                    isError = true
                    statusMsg = polled.error ?: "Browser login failed"
                    break
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (mobile) {
            BasicText(
                "Tip: Device Login is recommended on phones. Browser Login ends on localhost:1455 and requires copying the redirect URL.",
                style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color),
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsButton(
                label = "+ Device Login",
                primary = true,
                enabled = api != null,
                modifier = Modifier.testTag("codex-device-login-btn"),
                onClick = {
                    val client = api ?: return@SettingsButton
                    scope.launch {
                        try {
                            val started = parseCodexDeviceLogin(client.codexStartDeviceLogin())
                            if (started != null) {
                                deviceFlow = started
                            }
                        } catch (t: Throwable) {
                            isError = true
                            statusMsg = t.message ?: "Failed to start device login"
                        }
                    }
                },
            )
            SettingsButton(
                label = "+ Browser Login",
                enabled = api != null,
                modifier = Modifier.testTag("codex-browser-login-btn"),
                onClick = {
                    val client = api ?: return@SettingsButton
                    scope.launch {
                        try {
                            val started = parseCodexBrowserLogin(client.codexStartBrowserLogin())
                            if (started != null) {
                                browserFlow = started
                                browserCallbackInput = ""
                                runCatching { uriHandler.openUri(started.authUrl) }
                            }
                        } catch (t: Throwable) {
                            isError = true
                            statusMsg = t.message ?: "Failed to start browser login"
                        }
                    }
                },
            )
            SettingsButton(
                label = "+ Add API Key",
                enabled = api != null,
                onClick = { showAddKeyDialog = true },
            )
            SettingsButton(
                label = "Backup / Restore",
                enabled = api != null,
                onClick = { showBackupDialog = true },
            )
        }

        // Strategy selector row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText("Strategy:", style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text2.color))
            for (strat in listOf("round-robin", "fill-first", "lowest-usage")) {
                SettingsButton(
                    label = strat,
                    primary = strategy == strat,
                    onClick = {
                        val client = api ?: return@SettingsButton
                        strategy = strat
                        scope.launch {
                            runCatching { client.codexSetStrategy(strat) }
                        }
                    },
                )
            }
        }

        if (statusMsg != null) {
            BasicText(
                statusMsg!!,
                style = ppmText(11f).copy(color = if (isError) tokens.error.color else tokens.success.color),
            )
        }

        val now = System.currentTimeMillis()
        for (account in accounts) {
            val u = usages[account.id] ?: UsageInfo()
            val guard = if (u.session == null) dailyGuardState(u.weekly, now) else null
            val shape = RoundedCornerShape(10.dp)
            val reveal = rememberAccountReveal(focus, account.id)
            Column(
                Modifier
                    .bringIntoViewRequester(reveal.requester)
                    .fillMaxWidth()
                    .clip(shape)
                    .background(tokens.panel.color)
                    .cssBorder(tokens.border.color, shape)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AccountCard(
                    entry = AccountUsageEntry(
                        account.id,
                        account.label,
                        account.status,
                        account.type != "apiKey",
                        AccountUsage(session = u.session, weekly = u.weekly),
                    ),
                    isActive = false,
                    accountInfo = null,
                    layout = AccountCardLayout.Grid,
                    planLabel = codexPlanLabel(account.planType),
                    dailyGuard = guard?.let { CardDailyGuard(account.dailyGuardEnabled, it) },
                    onDailyGuardToggle = guard?.let {
                        {
                            val client = api ?: return@let
                            scope.launch {
                                runCatching { client.setCodexDailyGuard(account.id, !account.dailyGuardEnabled) }
                                reload()
                            }
                        }
                    },
                    onToggle = { id, currentStatus ->
                        val client = api ?: return@AccountCard
                        val next = if (currentStatus == "disabled") "active" else "disabled"
                        scope.launch {
                            runCatching { client.setCodexAccountStatus(id, next) }
                            reload()
                        }
                    },
                    flash = reveal.flash,
                    resetCreditAction = {
                        CodexResetCreditButton(
                            accountLabel = account.label,
                            usage = u,
                            spend = { credit ->
                                val client = api ?: error("Not connected")
                                resetCreditOutcome(client.spendCodexResetCredit(account.id, credit.id), account.label)
                            },
                            onDone = { message, failed ->
                                statusMsg = message
                                isError = failed
                                scope.launch { reload() }
                            },
                        )
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsButton(
                        label = "Delete",
                        danger = true,
                        onClick = { deletingAccount = account },
                    )
                }
            }
        }
    }

    // Device Login Dialog
    deviceFlow?.let { flow ->
        Dialog(onDismissRequest = {
            val client = api
            val id = flow.id
            deviceFlow = null
            if (client != null) scope.launch { runCatching { client.codexCancelDeviceLogin(id) } }
        }) {
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(tokens.bgSolid.color)
                    .cssBorder(tokens.border.color, shape)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsSectionTitle("Codex Device Login")
                BasicText(
                    "1. Copy your one-time code below.\n2. Open the verification page and paste the code.",
                    style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text2.color),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(tokens.panel2.color)
                        .padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        flow.userCode.ifEmpty { "Generating…" },
                        style = ppmText(18f, FontWeight.Bold, kind = FontKind.MONO).copy(color = tokens.accent.color),
                        modifier = Modifier.testTag("codex-device-user-code"),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsButton(
                        label = "Copy Code",
                        onClick = {
                            copySensitiveClip(context, "Codex Device Code", flow.userCode)
                        },
                    )
                    SettingsButton(
                        label = "Open Verification Page",
                        primary = true,
                        onClick = { runCatching { uriHandler.openUri(flow.verificationUrl) } },
                    )
                }
                BasicText(
                    "Status: ${flow.status}" + (flow.error?.let { " ($it)" } ?: " — polling every 2s…"),
                    style = ppmText(11f).copy(color = tokens.text3.color),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SettingsButton(
                        label = "Cancel",
                        onClick = {
                            val client = api
                            val id = flow.id
                            deviceFlow = null
                            if (client != null) scope.launch { runCatching { client.codexCancelDeviceLogin(id) } }
                        },
                    )
                }
            }
        }
    }

    // Browser Login Dialog
    browserFlow?.let { flow ->
        Dialog(onDismissRequest = {
            if (!browserBusy) {
                val client = api
                val id = flow.id
                browserFlow = null
                if (client != null) scope.launch { runCatching { client.codexCancelBrowserLogin(id) } }
            }
        }) {
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(tokens.bgSolid.color)
                    .cssBorder(tokens.border.color, shape)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsSectionTitle("Codex Browser Login")
                BasicText(
                    "1. Sign in on the OpenAI page that opens.\n" +
                        "2. After approving, the browser redirects to http://localhost:1455/auth/callback?code=… (which will not load on your phone).\n" +
                        "3. Copy that full localhost:1455 address from the browser's address bar and paste it below.",
                    style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text2.color),
                )
                SettingsButton(
                    label = "Open Browser Sign-In Page",
                    onClick = { runCatching { uriHandler.openUri(flow.authUrl) } },
                )
                SettingsTextInput(
                    value = browserCallbackInput,
                    onValueChange = { browserCallbackInput = it },
                    placeholder = "http://localhost:1455/auth/callback?code=...&state=...",
                    mono = true,
                    modifier = Modifier.fillMaxWidth().testTag("codex-browser-callback-input"),
                )
                flow.error?.let { errText ->
                    BasicText(
                        errText,
                        style = ppmText(11f).copy(color = tokens.error.color),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SettingsButton(
                        label = "Cancel",
                        enabled = !browserBusy,
                        onClick = {
                            val client = api
                            val id = flow.id
                            browserFlow = null
                            if (client != null) scope.launch { runCatching { client.codexCancelBrowserLogin(id) } }
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    SettingsButton(
                        label = if (browserBusy) "Submitting…" else "Submit Callback URL",
                        primary = true,
                        enabled = !browserBusy && looksLikeCodexLoopbackUrl(browserCallbackInput),
                        modifier = Modifier.testTag("codex-browser-callback-submit"),
                        onClick = {
                            val client = api ?: return@SettingsButton
                            browserBusy = true
                            scope.launch {
                                try {
                                    client.codexSubmitBrowserCallback(flow.id, browserCallbackInput.trim())
                                    val deadline = System.currentTimeMillis() + 60_000L
                                    while (isActive && System.currentTimeMillis() < deadline && browserFlow?.id == flow.id) {
                                        val polled = parseCodexLoginPollStatus(client.codexBrowserLoginStatus(flow.id))
                                        if (polled != null) {
                                            browserFlow = flow.copy(status = polled.state, error = polled.error)
                                            if (polled.isDone) {
                                                browserFlow = null
                                                reload()
                                                isError = false
                                                statusMsg = "Codex account connected via browser login"
                                                break
                                            } else if (polled.isError) {
                                                isError = true
                                                statusMsg = polled.error ?: "Browser login failed"
                                                break
                                            }
                                        }
                                        delay(1_000L)
                                    }
                                } catch (t: Throwable) {
                                    isError = true
                                    statusMsg = t.message ?: "Browser callback failed"
                                } finally {
                                    browserBusy = false
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    // Add Codex API Key Dialog
    if (showAddKeyDialog) {
        var apiKey by remember { mutableStateOf("") }
        var label by remember { mutableStateOf("") }
        var visible by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        Dialog(onDismissRequest = { if (!busy) showAddKeyDialog = false }) {
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(tokens.bgSolid.color)
                    .cssBorder(tokens.border.color, shape)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsSectionTitle("Add Codex API Key")
                SettingsTextInput(
                    value = label,
                    onValueChange = { label = it },
                    placeholder = "Label (optional)",
                    modifier = Modifier.fillMaxWidth(),
                )
                SettingsPasswordInput(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    placeholder = "sk-...",
                    visible = visible,
                    onToggleVisible = { visible = !visible },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SettingsButton(label = "Cancel", enabled = !busy, onClick = { showAddKeyDialog = false })
                    Spacer(Modifier.width(8.dp))
                    SettingsButton(
                        label = if (busy) "Adding…" else "Add",
                        primary = true,
                        enabled = !busy && apiKey.isNotBlank(),
                        onClick = {
                            val client = api ?: return@SettingsButton
                            busy = true
                            scope.launch {
                                try {
                                    client.codexAddApiKey(apiKey.trim(), label.trim().ifEmpty { null })
                                    showAddKeyDialog = false
                                    reload()
                                } catch (t: Throwable) {
                                    isError = true
                                    statusMsg = t.message ?: "Failed to add Codex API key"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    // Backup / Restore Dialog
    if (showBackupDialog) {
        var password by remember { mutableStateOf("") }
        var showPassword by remember { mutableStateOf(false) }
        var bundleText by remember { mutableStateOf("") }
        var dialogMsg by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        Dialog(onDismissRequest = { if (!busy) showBackupDialog = false }) {
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(tokens.bgSolid.color)
                    .cssBorder(tokens.border.color, shape)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsSectionTitle("Backup / Restore Codex Accounts")
                SettingsPasswordInput(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = "Encryption password (required)",
                    visible = showPassword,
                    onToggleVisible = { showPassword = !showPassword },
                    modifier = Modifier.fillMaxWidth(),
                    inputTestTag = "codex-backup-password-input",
                )
                SettingsTextInput(
                    value = bundleText,
                    onValueChange = { bundleText = it },
                    placeholder = "Encrypted backup JSON",
                    mono = true,
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                )
                if (dialogMsg != null) {
                    BasicText(dialogMsg!!, style = ppmText(11f).copy(color = tokens.text2.color))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsButton(
                        label = "Export",
                        enabled = !busy && password.isNotBlank(),
                        onClick = {
                            val client = api ?: return@SettingsButton
                            busy = true
                            scope.launch {
                                try {
                                    val data = client.codexExportEncrypted(password.trim())
                                    bundleText = data
                                    copySensitiveClip(context, "ppm-codex-accounts-backup.json", data)
                                    dialogMsg = "Exported & copied to clipboard"
                                } catch (t: Throwable) {
                                    dialogMsg = t.message ?: "Export failed"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    )
                    SettingsButton(
                        label = "Import",
                        primary = true,
                        enabled = !busy && bundleText.isNotBlank() && password.isNotBlank(),
                        onClick = {
                            val client = api ?: return@SettingsButton
                            busy = true
                            scope.launch {
                                try {
                                    client.codexImportEncrypted(bundleText.trim(), password.trim())
                                    reload()
                                    dialogMsg = "Imported successfully"
                                } catch (t: Throwable) {
                                    dialogMsg = t.message ?: "Import failed"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    SettingsButton(label = "Close", enabled = !busy, onClick = { showBackupDialog = false })
                }
            }
        }
    }

    // Delete Codex Account Confirmation
    deletingAccount?.let { acc ->
        ConfirmDialog(
            title = "Delete ${acc.label}?",
            message = "This removes the Codex account from PPM.",
            confirm = "Delete",
            onConfirm = {
                val client = api ?: return@ConfirmDialog
                scope.launch {
                    runCatching { client.codexDeleteAccount(acc.id) }
                    deletingAccount = null
                    reload()
                }
            },
            onDismiss = { deletingAccount = null },
        )
    }
}

private data class AntigravityStrategyOption(
    val value: String,
    val label: String,
    val hint: String,
)

private val ANTIGRAVITY_STRATEGIES = listOf(
    AntigravityStrategyOption("round-robin", "Round-robin", "Take the next account each time."),
    AntigravityStrategyOption("fill-first", "Fill-first", "Stay on one account until it runs out."),
    AntigravityStrategyOption("lowest-usage", "Lowest usage", "Pick whichever has the most left."),
)

@Composable
private fun AntigravityAccountsPane(model: AppModel) {
    val api = model.api
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var accounts by remember { mutableStateOf<List<AntigravityAccount>>(emptyList()) }
    var usages by remember { mutableStateOf<Map<String, UsageInfo>>(emptyMap()) }
    var strategy by remember { mutableStateOf("round-robin") }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    // Add / Re-login dialog state
    var showAddDialog by remember { mutableStateOf(false) }
    var reloginAccount by remember { mutableStateOf<AntigravityAccount?>(null) }
    var loginLabel by remember { mutableStateOf("") }
    var loginFlow by remember { mutableStateOf<AntigravityLoginFlow?>(null) }
    var startingLogin by remember { mutableStateOf(false) }
    var authCode by remember { mutableStateOf("") }
    var submittingCode by remember { mutableStateOf(false) }

    suspend fun reload(showSpinner: Boolean = false) {
        val client = api ?: return
        if (showSpinner) refreshing = true
        try {
            val overview = client.antigravityAccountsOverview()
            accounts = overview["accounts"].array.orEmpty().mapNotNull(AntigravityAccount::from)
            strategy = overview["strategy"].string ?: "round-robin"
            usages = runCatching { client.antigravityUsages() }.getOrDefault(emptyMap())
        } catch (t: Throwable) {
            isError = true
            statusMsg = t.message ?: "Failed to load Antigravity accounts"
        } finally {
            loading = false
            refreshing = false
        }
    }

    LaunchedEffect(api) {
        if (api != null) reload() else loading = false
    }

    // Poll pending Google OAuth PKCE login every 2s up to 610s (matching antigravity-accounts-section.tsx)
    LaunchedEffect(loginFlow?.id) {
        val current = loginFlow ?: return@LaunchedEffect
        val client = api ?: return@LaunchedEffect
        val deadline = System.currentTimeMillis() + 610_000L
        while (isActive && System.currentTimeMillis() < deadline) {
            delay(2_000L)
            val polled = runCatching { parseAntigravityLoginStatus(client.antigravityLoginStatus(current.id)) }.getOrNull()
            if (polled != null) {
                if (polled.isDone) {
                    loginFlow = null
                    showAddDialog = false
                    reloginAccount = null
                    loginLabel = ""
                    authCode = ""
                    isError = false
                    statusMsg = "Antigravity Google account connected."
                    reload()
                    break
                } else if (polled.isError) {
                    loginFlow = null
                    isError = true
                    statusMsg = polled.error ?: "Login failed."
                    break
                }
            }
        }
    }

    fun resetDialogState(cancelRemote: Boolean) {
        val pendingId = loginFlow?.id
        val client = api
        loginFlow = null
        showAddDialog = false
        reloginAccount = null
        loginLabel = ""
        authCode = ""
        if (cancelRemote && pendingId != null && client != null) {
            scope.launch { runCatching { client.antigravityCancelLogin(pendingId) } }
        }
    }

    AntigravityAccountsContent(
        accounts = accounts,
        usages = usages,
        strategy = strategy,
        loading = loading,
        refreshing = refreshing,
        statusMsg = statusMsg,
        isError = isError,
        enabled = api != null,
        onDismissStatus = { statusMsg = null },
        onRefresh = {
            scope.launch { reload(showSpinner = true) }
        },
        onSelectStrategy = { next ->
            val client = api ?: return@AntigravityAccountsContent
            val prev = strategy
            strategy = next
            scope.launch {
                try {
                    client.antigravitySetStrategy(next)
                } catch (t: Throwable) {
                    strategy = prev
                    isError = true
                    statusMsg = t.message ?: "Failed to update strategy."
                }
            }
        },
        onToggleAccount = { account ->
            val client = api ?: return@AntigravityAccountsContent
            val next = if (account.status == "disabled") "active" else "disabled"
            accounts = accounts.map { if (it.id == account.id) it.copy(status = next) else it }
            scope.launch {
                try {
                    val updated = client.setAntigravityAccountStatus(account.id, next)
                    if (updated != null) {
                        accounts = accounts.map { if (it.id == account.id) updated else it }
                    }
                } catch (t: Throwable) {
                    isError = true
                    statusMsg = t.message ?: "Failed to update account."
                    reload()
                }
            }
        },
        onDeleteAccount = { account ->
            val client = api ?: return@AntigravityAccountsContent
            scope.launch {
                try {
                    client.antigravityDeleteAccount(account.id)
                    accounts = accounts.filterNot { it.id == account.id }
                    isError = false
                    statusMsg = "Account removed."
                } catch (t: Throwable) {
                    isError = true
                    statusMsg = t.message ?: "Failed to remove account."
                }
            }
        },
        onOpenAddAccount = {
            reloginAccount = null
            loginLabel = ""
            loginFlow = null
            authCode = ""
            showAddDialog = true
        },
        onOpenRelogin = { account ->
            reloginAccount = account
            loginLabel = ""
            loginFlow = null
            authCode = ""
            showAddDialog = true
        },
        showAddDialog = showAddDialog,
        reloginAccount = reloginAccount,
        loginLabel = loginLabel,
        onLoginLabelChange = { loginLabel = it },
        loginFlow = loginFlow,
        startingLogin = startingLogin,
        authCode = authCode,
        onAuthCodeChange = { authCode = it },
        submittingCode = submittingCode,
        onStartLogin = {
            val client = api ?: return@AntigravityAccountsContent
            startingLogin = true
            isError = false
            statusMsg = null
            scope.launch {
                try {
                    val started = parseAntigravityLoginStart(
                        client.antigravityStartLogin(
                            label = loginLabel.trim().ifEmpty { null },
                            accountId = reloginAccount?.id,
                        ),
                    )
                    if (started != null) {
                        loginFlow = started
                        authCode = ""
                        runCatching { uriHandler.openUri(started.authUrl) }
                    } else {
                        isError = true
                        statusMsg = "Failed to start Google sign-in."
                    }
                } catch (t: Throwable) {
                    isError = true
                    statusMsg = t.message ?: "Failed to start Google sign-in."
                } finally {
                    startingLogin = false
                }
            }
        },
        onOpenAuthUrl = { url -> runCatching { uriHandler.openUri(url) } },
        onStartOver = {
            val pendingId = loginFlow?.id
            val client = api
            loginFlow = null
            authCode = ""
            if (pendingId != null && client != null) {
                scope.launch { runCatching { client.antigravityCancelLogin(pendingId) } }
            }
        },
        onSubmitAuthCode = {
            val client = api ?: return@AntigravityAccountsContent
            val flow = loginFlow ?: return@AntigravityAccountsContent
            val trimmed = authCode.trim()
            if (trimmed.isEmpty()) return@AntigravityAccountsContent
            submittingCode = true
            isError = false
            statusMsg = null
            scope.launch {
                try {
                    val polled = parseAntigravityLoginStatus(client.antigravitySubmitLoginCode(flow.id, trimmed))
                    if (polled?.isDone == true) {
                        resetDialogState(cancelRemote = false)
                        isError = false
                        statusMsg = "Antigravity Google account connected."
                        reload()
                    } else if (polled?.isError == true) {
                        loginFlow = null
                        isError = true
                        statusMsg = polled.error ?: "Login failed."
                    }
                } catch (t: Throwable) {
                    isError = true
                    statusMsg = t.message ?: "Failed to exchange authorization code."
                } finally {
                    submittingCode = false
                }
            }
        },
        onCancelDialog = { resetDialogState(cancelRemote = true) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AntigravityAccountsContent(
    accounts: List<AntigravityAccount>,
    usages: Map<String, UsageInfo>,
    strategy: String,
    loading: Boolean = false,
    refreshing: Boolean = false,
    statusMsg: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    onDismissStatus: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onSelectStrategy: (String) -> Unit = {},
    onToggleAccount: (AntigravityAccount) -> Unit = {},
    onDeleteAccount: (AntigravityAccount) -> Unit = {},
    onOpenAddAccount: () -> Unit = {},
    onOpenRelogin: (AntigravityAccount) -> Unit = {},
    showAddDialog: Boolean = false,
    reloginAccount: AntigravityAccount? = null,
    loginLabel: String = "",
    onLoginLabelChange: (String) -> Unit = {},
    loginFlow: AntigravityLoginFlow? = null,
    startingLogin: Boolean = false,
    authCode: String = "",
    onAuthCodeChange: (String) -> Unit = {},
    submittingCode: Boolean = false,
    onStartLogin: () -> Unit = {},
    onOpenAuthUrl: (String) -> Unit = {},
    onStartOver: () -> Unit = {},
    onSubmitAuthCode: () -> Unit = {},
    onCancelDialog: () -> Unit = {},
    now: Long = System.currentTimeMillis(),
) {
    val tokens = LocalPpmTokens.current

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BasicText(
            "Each Antigravity account keeps its own Google OAuth login (~/.ppm/antigravity-accounts) alongside your local IDE login (~/.gemini). One is picked per chat by the rotation setting.",
            style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color),
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsButton(
                label = "+ Add account",
                primary = true,
                enabled = enabled,
                modifier = Modifier.testTag("antigravity-add-account-btn"),
                onClick = onOpenAddAccount,
            )
            SettingsButton(
                label = if (refreshing) "Refreshing…" else "Refresh",
                enabled = enabled && !refreshing,
                modifier = Modifier.testTag("antigravity-refresh-btn"),
                onClick = onRefresh,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText("Rotation", style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text2.color))
                for (opt in ANTIGRAVITY_STRATEGIES) {
                    SettingsButton(
                        label = opt.label,
                        primary = strategy == opt.value,
                        enabled = enabled,
                        modifier = Modifier.testTag("antigravity-strategy-${opt.value}"),
                        onClick = { onSelectStrategy(opt.value) },
                    )
                }
            }
            val activeHint = ANTIGRAVITY_STRATEGIES.firstOrNull { it.value == strategy }?.hint
            if (activeHint != null) {
                BasicText(activeHint, style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color))
            }
        }

        if (statusMsg != null) {
            val bannerShape = RoundedCornerShape(8.dp)
            val bannerBorder = if (isError) tokens.error.opacity(0.4).color else tokens.border.color
            val bannerBg = if (isError) tokens.error.opacity(0.1).color else tokens.panel.color
            val bannerFg = if (isError) tokens.error.color else tokens.text.color
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(bannerShape)
                    .background(bannerBg)
                    .cssBorder(bannerBorder, bannerShape)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("antigravity-status-banner"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicText(
                    statusMsg,
                    Modifier.weight(1f),
                    style = ppmText(11f, lineHeight = 16f).copy(color = bannerFg),
                )
                BasicText(
                    "×",
                    Modifier
                        .clickable(onClick = onDismissStatus)
                        .padding(horizontal = 4.dp)
                        .testTag("antigravity-dismiss-msg"),
                    style = ppmText(14f, FontWeight.Medium).copy(color = tokens.text3.color),
                )
            }
        }

        if (loading && accounts.isEmpty()) {
            BasicText(
                "Loading Antigravity accounts…",
                style = ppmText(11f).copy(color = tokens.text3.color),
            )
        } else if (!loading && accounts.isEmpty()) {
            val emptyShape = RoundedCornerShape(10.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(emptyShape)
                    .background(tokens.panel.color)
                    .cssBorder(tokens.border.color, emptyShape)
                    .padding(16.dp)
                    .testTag("antigravity-empty-state"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                BasicText(
                    "No Antigravity accounts yet.",
                    style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text2.color),
                )
                BasicText(
                    "Click Add account to sign in with Google, or start Antigravity Desktop to auto-detect your local account.",
                    style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color),
                )
            }
        }

        for (account in accounts) {
            val u = currentAntigravityUsage(usages[account.id] ?: UsageInfo(), now)
            val unread = u.session == null && u.weekly == null && u.weeklyScoped.isEmpty()
            val cardShape = RoundedCornerShape(10.dp)
            val badgeShape = RoundedCornerShape(999.dp)
            val disabled = account.status == "disabled"

            Column(
                Modifier
                    .fillMaxWidth()
                    .alpha(if (disabled) 0.6f else 1f)
                    .clip(cardShape)
                    .background(tokens.panel.color)
                    .cssBorder(tokens.border.color, cardShape)
                    .padding(12.dp)
                    .testTag("antigravity-account-card-${account.id}"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            account.label,
                            style = ppmText(13f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!account.email.isNullOrEmpty() && account.email != account.label) {
                            BasicText(
                                account.email!!,
                                style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Box(
                            Modifier
                                .clip(badgeShape)
                                .background(tokens.panel2.color)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                .testTag("antigravity-source-${account.id}"),
                        ) {
                            BasicText(
                                if (account.isDefault) "~/.gemini" else "Google",
                                style = ppmText(10f, FontWeight.Medium).copy(color = tokens.text3.color),
                            )
                        }
                        if (!account.planType.isNullOrEmpty()) {
                            Box(
                                Modifier
                                    .clip(badgeShape)
                                    .background(tokens.accent.opacity(0.1).color)
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                    .testTag("antigravity-plan-${account.id}"),
                            ) {
                                BasicText(
                                    account.planType!!,
                                    style = ppmText(10f, FontWeight.Medium).copy(color = tokens.accent.color),
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.testTag("antigravity-toggle-${account.id}")) {
                            PpmSwitch(checked = !disabled, enabled = enabled) {
                                onToggleAccount(account)
                            }
                        }
                        if (!account.isDefault) {
                            SettingsButton(
                                label = "Sign in again",
                                enabled = enabled,
                                modifier = Modifier.testTag("antigravity-relogin-${account.id}"),
                                onClick = { onOpenRelogin(account) },
                            )
                            SettingsButton(
                                label = "Delete",
                                danger = true,
                                enabled = enabled,
                                modifier = Modifier.testTag("antigravity-delete-${account.id}"),
                                onClick = { onDeleteAccount(account) },
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (unread || u.session != null) {
                        AccountUsageBar(
                            label = "Gemini · 5-Hour Session",
                            pct = u.fiveHour?.let(::usagePercent),
                            reset = formatResetTime(u.session, now),
                        )
                    }
                    if (unread || u.weekly != null) {
                        AccountUsageBar(
                            label = "Gemini · Weekly",
                            pct = u.sevenDay?.let(::usagePercent),
                            reset = formatResetTime(u.weekly, now),
                        )
                    }
                    for (b in u.weeklyScoped) {
                        AccountUsageBar(
                            label = b.label.orEmpty(),
                            pct = usagePercent(b.utilization),
                            reset = formatResetTime(b, now),
                        )
                    }
                }

                if (u.lastFetchedAt != null && isAntigravityUsageStale(u.lastFetchedAt, now)) {
                    val fetchedMs = isoMillis(u.lastFetchedAt!!) ?: now
                    BasicText(
                        "Last read ${formatLastUpdated(fetchedMs, now)}. Neither agy nor the Antigravity app has answered since.",
                        style = ppmText(11f, lineHeight = 16f).copy(color = tokens.warning.color),
                        modifier = Modifier.testTag("antigravity-stale-${account.id}"),
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        Dialog(onDismissRequest = {
            if (!startingLogin && !submittingCode) onCancelDialog()
        }) {
            AntigravityLoginDialogContent(
                reloginAccount = reloginAccount,
                loginLabel = loginLabel,
                onLoginLabelChange = onLoginLabelChange,
                loginFlow = loginFlow,
                startingLogin = startingLogin,
                authCode = authCode,
                onAuthCodeChange = onAuthCodeChange,
                submittingCode = submittingCode,
                onStartLogin = onStartLogin,
                onOpenAuthUrl = onOpenAuthUrl,
                onStartOver = onStartOver,
                onSubmitAuthCode = onSubmitAuthCode,
                onCancelDialog = onCancelDialog,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AntigravityLoginDialogContent(
    reloginAccount: AntigravityAccount? = null,
    loginLabel: String = "",
    onLoginLabelChange: (String) -> Unit = {},
    loginFlow: AntigravityLoginFlow? = null,
    startingLogin: Boolean = false,
    authCode: String = "",
    onAuthCodeChange: (String) -> Unit = {},
    submittingCode: Boolean = false,
    onStartLogin: () -> Unit = {},
    onOpenAuthUrl: (String) -> Unit = {},
    onStartOver: () -> Unit = {},
    onSubmitAuthCode: () -> Unit = {},
    onCancelDialog: () -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.bgSolid.color)
            .cssBorder(tokens.border.color, shape)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsSectionTitle(
            if (reloginAccount != null) {
                "Sign in again — ${reloginAccount.label}"
            } else {
                "Add Antigravity Account"
            },
        )
        BasicText(
            if (reloginAccount != null) {
                "Sign in with Google to refresh this account's OAuth credentials."
            } else {
                "Sign in with a Google account using Antigravity's OAuth PKCE flow. Each account is stored in its own isolated directory."
            },
            style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color),
        )

        if (reloginAccount == null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText("Label (optional)", style = ppmText(11f, FontWeight.Medium).copy(color = tokens.text2.color))
                SettingsTextInput(
                    value = loginLabel,
                    onValueChange = onLoginLabelChange,
                    placeholder = "e.g. Personal, Work, Shopee 2",
                    modifier = Modifier.fillMaxWidth().testTag("antigravity-login-label-input"),
                )
            }
        }

        if (loginFlow == null) {
            SettingsButton(
                label = if (startingLogin) "Preparing…" else "Sign in with Google",
                primary = true,
                enabled = !startingLogin,
                modifier = Modifier.fillMaxWidth().testTag("antigravity-start-login-btn"),
                onClick = onStartLogin,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsButton(
                    label = "Open Google Sign-In page",
                    modifier = Modifier.testTag("antigravity-open-auth-url-btn"),
                    onClick = { onOpenAuthUrl(loginFlow.authUrl) },
                )
                SettingsButton(
                    label = "Start over",
                    modifier = Modifier.testTag("antigravity-start-over-btn"),
                    onClick = onStartOver,
                )
            }
            BasicText(
                "1. Open the link above and sign in with your Google account.\n" +
                    "2. Copy the authorization code (or the full callback URL) shown after signing in and paste it below:",
                style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color),
            )
            SettingsTextInput(
                value = authCode,
                onValueChange = onAuthCodeChange,
                placeholder = "4/0A... or https://antigravity.google/oauth-callback?code=...",
                mono = true,
                modifier = Modifier.fillMaxWidth().testTag("antigravity-auth-code-input"),
            )
            SettingsButton(
                label = if (submittingCode) "Verifying…" else "Verify & Save Account",
                primary = true,
                enabled = !submittingCode && authCode.isNotBlank(),
                modifier = Modifier.fillMaxWidth().testTag("antigravity-submit-code-btn"),
                onClick = onSubmitAuthCode,
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SettingsButton(
                label = "Cancel",
                enabled = !startingLogin && !submittingCode,
                modifier = Modifier.testTag("antigravity-cancel-login-btn"),
                onClick = onCancelDialog,
            )
        }
    }
}

// ===========================================================================
// 4. MCP Servers Section (CRUD + Import from ~/.claude.json + MCP sign-in)
// ===========================================================================

@Composable
internal fun McpSettingsSection(model: AppModel) {
    val api = model.api
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf<List<McpServerRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<McpServerRow?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var deletingServer by remember { mutableStateOf<McpServerRow?>(null) }

    suspend fun reload() {
        val client = api ?: return
        loading = true
        try {
            servers = parseMcpServers(client.mcpListServers())
        } catch (t: Throwable) {
            isError = true
            statusMsg = t.message ?: "Failed to load MCP servers"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(api) {
        if (api != null) reload() else loading = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        McpSignInList(model, Modifier.fillMaxWidth())
        Separator()
        McpSettingsContent(
            servers = servers,
            loading = loading,
            statusMsg = statusMsg,
            isError = isError,
            onAddClick = { showAddDialog = true },
            onEditClick = { editingServer = it },
            onDeleteClick = { deletingServer = it },
            onImportClaudeClick = {
                val client = api ?: return@McpSettingsContent
                scope.launch {
                    try {
                        val res = client.mcpImportFromClaude()
                        val imported = res["imported"].int ?: 0
                        val skipped = res["skipped"].int ?: 0
                        reload()
                        isError = false
                        statusMsg = "Imported $imported server(s) ($skipped skipped)"
                    } catch (t: Throwable) {
                        isError = true
                        statusMsg = t.message ?: "Import failed"
                    }
                }
            },
        )
    }

    if (showAddDialog || editingServer != null) {
        val existing = editingServer
        Dialog(onDismissRequest = {
            showAddDialog = false
            editingServer = null
        }) {
            val tokens = LocalPpmTokens.current
            val shape = RoundedCornerShape(12.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(tokens.bgSolid.color)
                    .cssBorder(tokens.border.color, shape)
                    .padding(16.dp),
            ) {
                McpServerFormContent(
                    initial = existing,
                    onCancel = {
                        showAddDialog = false
                        editingServer = null
                    },
                    onSave = { name, transport, command, argsText, url, kvPairs ->
                        val client = api ?: return@McpServerFormContent
                        val cfg = buildMcpConfigJson(transport, command, argsText, url, kvPairs)
                        scope.launch {
                            try {
                                if (existing == null) {
                                    client.mcpAddServer(name, cfg)
                                } else {
                                    client.mcpUpdateServer(existing.name, cfg)
                                }
                                showAddDialog = false
                                editingServer = null
                                reload()
                                isError = false
                                statusMsg = "Saved MCP server '$name'"
                            } catch (t: Throwable) {
                                isError = true
                                statusMsg = t.message ?: "Failed to save MCP server"
                            }
                        }
                    },
                )
            }
        }
    }

    deletingServer?.let { srv ->
        ConfirmDialog(
            title = "Delete MCP server '${srv.name}'?",
            message = "This removes '${srv.name}' from PPM's MCP configuration.",
            confirm = "Delete",
            onConfirm = {
                val client = api ?: return@ConfirmDialog
                scope.launch {
                    runCatching { client.mcpDeleteServer(srv.name) }
                    deletingServer = null
                    reload()
                }
            },
            onDismiss = { deletingServer = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun McpSettingsContent(
    servers: List<McpServerRow>,
    loading: Boolean = false,
    statusMsg: String? = null,
    isError: Boolean = false,
    onAddClick: () -> Unit,
    onEditClick: (McpServerRow) -> Unit,
    onDeleteClick: (McpServerRow) -> Unit,
    onImportClaudeClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsButton(
                label = "+ Add MCP Server",
                primary = true,
                modifier = Modifier.testTag("mcp-add-server-btn"),
                onClick = onAddClick,
            )
            SettingsButton(
                label = "Import from ~/.claude.json",
                modifier = Modifier.testTag("mcp-import-claude-btn"),
                onClick = onImportClaudeClick,
            )
        }
        if (statusMsg != null) {
            BasicText(
                statusMsg,
                style = ppmText(11f).copy(color = if (isError) tokens.error.color else tokens.success.color),
            )
        }
        if (servers.isEmpty() && !loading) {
            BasicText(
                "No custom MCP servers configured.",
                style = ppmText(12f).copy(color = tokens.text3.color),
            )
        }
        for (srv in servers) {
            val shape = RoundedCornerShape(8.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(tokens.panel.color)
                    .cssBorder(tokens.border.color, shape)
                    .padding(10.dp)
                    .testTag("mcp-server-row-${srv.name}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BasicText(srv.name, style = ppmText(13f, FontWeight.Medium).copy(color = tokens.text.color))
                        BasicText(
                            srv.transport.uppercase(),
                            style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.accent.color),
                        )
                    }
                    val summary = if (srv.transport == "stdio") {
                        buildString {
                            append(srv.command)
                            if (srv.args.isNotEmpty()) append(" ").append(srv.args.joinToString(" "))
                        }
                    } else {
                        srv.url
                    }
                    BasicText(
                        summary,
                        style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text3.color),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsButton(label = "Edit", onClick = { onEditClick(srv) })
                    SettingsButton(label = "Delete", danger = true, onClick = { onDeleteClick(srv) })
                }
            }
        }
    }
}

@Composable
internal fun McpServerFormContent(
    initial: McpServerRow? = null,
    onCancel: () -> Unit,
    onSave: (
        name: String,
        transport: String,
        command: String,
        argsText: String,
        url: String,
        kvPairs: List<McpKeyValuePair>,
    ) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val isEditing = initial != null
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var transport by remember(initial) { mutableStateOf(initial?.transport ?: "stdio") }
    var command by remember(initial) { mutableStateOf(initial?.command.orEmpty()) }
    var argsText by remember(initial) { mutableStateOf(initial?.args?.joinToString(" ").orEmpty()) }
    var url by remember(initial) { mutableStateOf(initial?.url.orEmpty()) }
    var kvText by remember(initial) {
        val map = if ((initial?.transport ?: "stdio") == "stdio") initial?.env.orEmpty() else initial?.headers.orEmpty()
        mutableStateOf(map.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }
    var validationError by remember { mutableStateOf<String?>(null) }

    fun parseKvLines(raw: String): List<McpKeyValuePair> =
        raw.lines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val idx = trimmed.indexOf('=')
            if (idx <= 0) McpKeyValuePair(trimmed, "")
            else McpKeyValuePair(trimmed.substring(0, idx).trim(), trimmed.substring(idx + 1).trim())
        }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsSectionTitle(if (isEditing) "Edit MCP Server: ${initial!!.name}" else "Add MCP Server")
        if (!isEditing) {
            SettingsTextInput(
                value = name,
                onValueChange = {
                    name = it
                    validationError = null
                },
                placeholder = "Server name (letters, digits, hyphens, underscores)",
                mono = true,
                modifier = Modifier.fillMaxWidth().testTag("mcp-form-name-input"),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in McpTransport.entries) {
                SettingsButton(
                    label = t.wire.uppercase(),
                    primary = transport == t.wire,
                    modifier = Modifier.testTag("mcp-form-transport-${t.wire}"),
                    onClick = {
                        if (transport != t.wire) {
                            transport = t.wire
                            // Switching transport clears the other transport's fields as web does
                            command = ""
                            argsText = ""
                            url = ""
                            kvText = ""
                            validationError = null
                        }
                    },
                )
            }
        }
        if (transport == "stdio") {
            SettingsTextInput(
                value = command,
                onValueChange = {
                    command = it
                    validationError = null
                },
                placeholder = "Command (e.g. npx, uvx, bunx)",
                mono = true,
                modifier = Modifier.fillMaxWidth().testTag("mcp-form-command-input"),
            )
            SettingsTextInput(
                value = argsText,
                onValueChange = { argsText = it },
                placeholder = "Arguments separated by space",
                mono = true,
                modifier = Modifier.fillMaxWidth().testTag("mcp-form-args-input"),
            )
            SettingsTextInput(
                value = kvText,
                onValueChange = { kvText = it },
                placeholder = "Environment variables (KEY=VALUE per line)",
                mono = true,
                singleLine = false,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            )
        } else {
            SettingsTextInput(
                value = url,
                onValueChange = {
                    url = it
                    validationError = null
                },
                placeholder = "https://example.com/mcp",
                mono = true,
                modifier = Modifier.fillMaxWidth().testTag("mcp-form-url-input"),
            )
            SettingsTextInput(
                value = kvText,
                onValueChange = { kvText = it },
                placeholder = "Headers (KEY=VALUE per line)",
                mono = true,
                singleLine = false,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            )
        }
        if (validationError != null) {
            BasicText(
                validationError!!,
                style = ppmText(11f).copy(color = tokens.error.color),
                modifier = Modifier.testTag("mcp-form-error"),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SettingsButton(label = "Cancel", onClick = onCancel)
            Spacer(Modifier.width(8.dp))
            SettingsButton(
                label = "Save",
                primary = true,
                modifier = Modifier.testTag("mcp-form-save-btn"),
                onClick = {
                    if (!isEditing) {
                        val nameErr = validateMcpName(name)
                        if (nameErr != null) {
                            validationError = nameErr
                            return@SettingsButton
                        }
                    }
                    val cfgErrors = validateMcpConfig(transport, command, url)
                    if (cfgErrors.isNotEmpty()) {
                        validationError = cfgErrors.first()
                        return@SettingsButton
                    }
                    onSave(name.trim(), transport, command, argsText, url, parseKvLines(kvText))
                },
            )
        }
    }
}

// ===========================================================================
// 5. AI Resources Section (Skills, Agents, Commands, MCP)
// ===========================================================================

@Composable
internal fun AiResourcesSettingsSection(model: AppModel) {
    val api = model.api
    val scope = rememberCoroutineScope()
    val activeProjectPath = remember(model.projects, model.selectedProject) {
        model.projects.firstOrNull { it.name == model.selectedProject }?.path.orEmpty()
    }
    var overview by remember { mutableStateOf(AiResourcesOverview()) }
    var filter by remember { mutableStateOf(AiResourceFilter.ALL) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var deletingItem by remember { mutableStateOf<AiResourceItem?>(null) }

    suspend fun reload() {
        val client = api ?: return
        loading = true
        try {
            overview = parseAiResources(client.aiResourcesList(activeProjectPath))
        } catch (t: Throwable) {
            isError = true
            statusMsg = t.message ?: "Failed to load AI resources"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(api, activeProjectPath) {
        if (api != null) reload() else loading = false
    }

    AiResourcesContent(
        overview = overview,
        filter = filter,
        query = query,
        loading = loading,
        statusMsg = statusMsg,
        isError = isError,
        onFilterChange = { filter = it },
        onQueryChange = { query = it },
        onNewResourceClick = { showCreateDialog = true },
        onOpenResource = { item -> model.openHostFile(item.filePath) },
        onDuplicateResource = { item ->
            val client = api ?: return@AiResourcesContent
            scope.launch {
                try {
                    client.aiResourcesDuplicate(
                        path = item.filePath,
                        type = item.type,
                        scope = duplicateAiResourceTargetScope(item.scope, activeProjectPath),
                        name = "${item.name}-copy",
                        projectPath = activeProjectPath,
                    )
                    reload()
                    isError = false
                    statusMsg = "Duplicated '${item.name}'"
                } catch (t: Throwable) {
                    isError = true
                    statusMsg = t.message ?: "Duplicate failed"
                }
            }
        },
        onDeleteResource = { deletingItem = it },
    )

    if (showCreateDialog) {
        var type by remember { mutableStateOf("skill") }
        var resScope by remember { mutableStateOf(if (activeProjectPath.isNotBlank()) "project" else "user") }
        var name by remember { mutableStateOf("") }
        var formErr by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        val tokens = LocalPpmTokens.current
        Dialog(onDismissRequest = { if (!busy) showCreateDialog = false }) {
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(tokens.bgSolid.color)
                    .cssBorder(tokens.border.color, shape)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsSectionTitle("Create AI Resource")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (t in listOf("skill", "agent", "command")) {
                        SettingsButton(label = t, primary = type == t, onClick = { type = t })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (sc in listOf("user", "project")) {
                        SettingsButton(
                            label = "Scope: $sc",
                            primary = resScope == sc,
                            enabled = sc == "user" || activeProjectPath.isNotBlank(),
                            onClick = { resScope = sc },
                        )
                    }
                }
                SettingsTextInput(
                    value = name,
                    onValueChange = {
                        name = it
                        formErr = null
                    },
                    placeholder = "Resource name (e.g. my-skill)",
                    mono = true,
                    modifier = Modifier.fillMaxWidth().testTag("ai-resource-create-name"),
                )
                if (formErr != null) {
                    BasicText(formErr!!, style = ppmText(11f).copy(color = tokens.error.color))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SettingsButton(label = "Cancel", enabled = !busy, onClick = { showCreateDialog = false })
                    Spacer(Modifier.width(8.dp))
                    SettingsButton(
                        label = if (busy) "Creating…" else "Create",
                        primary = true,
                        enabled = !busy,
                        onClick = {
                            val err = validateAiResourceName(name)
                            if (err != null) {
                                formErr = err
                                return@SettingsButton
                            }
                            val client = api ?: return@SettingsButton
                            busy = true
                            scope.launch {
                                try {
                                    val res = client.aiResourcesCreate(
                                        type = type,
                                        scope = resScope,
                                        name = name.trim(),
                                        projectPath = activeProjectPath,
                                    )
                                    val createdPath = res["filePath"].string ?: res["path"].string
                                    showCreateDialog = false
                                    reload()
                                    if (!createdPath.isNullOrBlank()) {
                                        model.openHostFile(createdPath)
                                    }
                                } catch (t: Throwable) {
                                    formErr = t.message ?: "Failed to create resource"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    deletingItem?.let { item ->
        ConfirmDialog(
            title = "Delete '${item.name}'?",
            message = "This permanently deletes ${item.filePath}.",
            confirm = "Delete",
            onConfirm = {
                val client = api ?: return@ConfirmDialog
                scope.launch {
                    runCatching {
                        client.aiResourcesDelete(
                            path = item.filePath,
                            type = item.type,
                            projectPath = activeProjectPath,
                        )
                    }
                    deletingItem = null
                    reload()
                }
            },
            onDismiss = { deletingItem = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiResourcesContent(
    overview: AiResourcesOverview,
    filter: AiResourceFilter,
    query: String,
    loading: Boolean = false,
    statusMsg: String? = null,
    isError: Boolean = false,
    onFilterChange: (AiResourceFilter) -> Unit,
    onQueryChange: (String) -> Unit,
    onNewResourceClick: () -> Unit,
    onOpenResource: (AiResourceItem) -> Unit,
    onDuplicateResource: (AiResourceItem) -> Unit,
    onDeleteResource: (AiResourceItem) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val filtered = remember(overview.items, filter, query) {
        filterAiResources(overview.items, filter, query)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsTextInput(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "Search skills, agents, commands, MCP…",
                modifier = Modifier.weight(1f).testTag("ai-resources-search-input"),
            )
            SettingsButton(
                label = "+ New",
                primary = true,
                modifier = Modifier.testTag("ai-resources-new-btn"),
                onClick = onNewResourceClick,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (f in AiResourceFilter.entries) {
                SettingsButton(
                    label = f.label,
                    primary = filter == f,
                    modifier = Modifier.testTag("ai-resources-filter-${f.id}"),
                    onClick = { onFilterChange(f) },
                )
            }
        }
        if (statusMsg != null) {
            BasicText(
                statusMsg,
                style = ppmText(11f).copy(color = if (isError) tokens.error.color else tokens.success.color),
            )
        }
        if (filtered.isEmpty() && !loading) {
            BasicText(
                "No AI resources match the current filter.",
                style = ppmText(12f).copy(color = tokens.text3.color),
            )
        }
        for (item in filtered) {
            val shape = RoundedCornerShape(8.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(tokens.panel.color)
                    .cssBorder(tokens.border.color, shape)
                    .padding(10.dp)
                    .testTag("ai-resource-row-${item.name}"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BasicText(item.name, style = ppmText(13f, FontWeight.Medium).copy(color = tokens.text.color))
                        BasicText(item.type.uppercase(), style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.accent.color))
                        BasicText(item.scope, style = ppmText(10f).copy(color = tokens.text3.color))
                        if (item.readOnly) {
                            BasicText("read-only", style = ppmText(10f).copy(color = tokens.warning.color))
                        }
                        if (item.shadowed) {
                            BasicText("shadowed", style = ppmText(10f).copy(color = tokens.error.color))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SettingsButton(
                            label = if (item.readOnly) "Open" else "Edit",
                            onClick = { onOpenResource(item) },
                        )
                        if (item.type != "mcp") {
                            SettingsButton(
                                label = "Duplicate",
                                onClick = { onDuplicateResource(item) },
                            )
                        }
                        if (!item.readOnly && item.type != "mcp") {
                            SettingsButton(
                                label = "Delete",
                                danger = true,
                                modifier = Modifier.testTag("ai-resource-delete-${item.name}"),
                                onClick = { onDeleteResource(item) },
                            )
                        }
                    }
                }
                if (item.description.isNotBlank()) {
                    BasicText(item.description, style = ppmText(11f).copy(color = tokens.text2.color))
                }
            }
        }
        Separator()
        val st = overview.stats
        BasicText(
            "Active: ${st.active} · Project: ${st.project} · User: ${st.user} · Bundled: ${st.bundled} · Shadowed: ${st.shadowed}",
            style = ppmText(11f).copy(color = tokens.text3.color),
            modifier = Modifier.testTag("ai-resources-stats-footer"),
        )
    }
}

// ===========================================================================
// 7. Design Section (designWindows pref, Declared Apps, Instructions, Skill)
// ===========================================================================

@Composable
internal fun DesignSettingsSection(model: AppModel) {
    val api = model.api
    val scope = rememberCoroutineScope()
    val activeProjectName = model.selectedProject

    var state by remember { mutableStateOf(DesignSettingsState()) }
    var systems by remember { mutableStateOf<List<DesignSystemRow>>(emptyList()) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    suspend fun reload() {
        val client = api ?: return
        runCatching { state = parseDesignSettings(client.designGetSettings(activeProjectName)) }
        if (!activeProjectName.isNullOrBlank()) {
            runCatching { systems = parseDesignSystems(client.designListSystems(activeProjectName)) }
        }
    }

    LaunchedEffect(api, activeProjectName) {
        if (api != null) reload()
    }

    DesignSettingsContent(
        designWindows = model.designWindows,
        onToggleDesignWindows = { model.setDesignWindowsPref(it) },
        state = state,
        systems = systems,
        hasActiveProject = !activeProjectName.isNullOrBlank(),
        statusMsg = statusMsg,
        isError = isError,
        onSaveInstructions = { text ->
            val client = api ?: return@DesignSettingsContent
            scope.launch {
                try {
                    client.designPutInstructions(text)
                    state = state.copy(instructions = text)
                    isError = false
                    statusMsg = "Design instructions saved"
                } catch (t: Throwable) {
                    isError = true
                    statusMsg = t.message ?: "Failed to save instructions"
                }
            }
        },
        onInstallSkill = {
            val client = api ?: return@DesignSettingsContent
            scope.launch {
                try {
                    client.designInstallSkill()
                    reload()
                    isError = false
                    statusMsg = "Design skill installed"
                } catch (t: Throwable) {
                    isError = true
                    statusMsg = t.message ?: "Failed to install design skill"
                }
            }
        },
        onAddSystem = { label, platform, root ->
            val client = api ?: return@DesignSettingsContent
            val proj = activeProjectName ?: return@DesignSettingsContent
            scope.launch {
                try {
                    client.designCreateSystem(proj, label, platform, root)
                    reload()
                    isError = false
                    statusMsg = "Declared app '$label'"
                } catch (t: Throwable) {
                    isError = true
                    statusMsg = t.message ?: "Failed to declare app"
                }
            }
        },
        onRemoveSystem = { id, deleteFiles ->
            val client = api ?: return@DesignSettingsContent
            val proj = activeProjectName ?: return@DesignSettingsContent
            scope.launch {
                try {
                    client.designDeleteSystem(proj, id, deleteFiles)
                    reload()
                } catch (t: Throwable) {
                    isError = true
                    statusMsg = t.message ?: "Failed to remove app"
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesignSettingsContent(
    designWindows: Boolean,
    onToggleDesignWindows: (Boolean) -> Unit,
    state: DesignSettingsState,
    systems: List<DesignSystemRow>,
    hasActiveProject: Boolean,
    statusMsg: String? = null,
    isError: Boolean = false,
    onSaveInstructions: (String) -> Unit,
    onInstallSkill: () -> Unit,
    onAddSystem: (label: String, platform: String, root: String) -> Unit,
    onRemoveSystem: (id: String, deleteFiles: Boolean) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var instructionsText by remember(state.instructions) { mutableStateOf(state.instructions) }
    val byteCount = remember(instructionsText) { utf8ByteCount(instructionsText) }
    val overLimit = byteCount > DESIGN_INSTRUCTIONS_MAX_BYTES

    var appLabel by remember { mutableStateOf("") }
    var appPlatform by remember { mutableStateOf("web") }
    var appRoot by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsToggleRow(
            title = "Floating Design Canvas windows",
            subtitle = "Open Design Canvas in floating windows on tablet (phones always use fullscreen)",
            checked = designWindows,
            onCheckedChange = onToggleDesignWindows,
            testTag = "design-windows-toggle",
        )

        if (statusMsg != null) {
            BasicText(
                statusMsg,
                style = ppmText(11f).copy(color = if (isError) tokens.error.color else tokens.success.color),
            )
        }

        Separator()

        // Declared Apps
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsSectionTitle("Declared Apps")
            if (!hasActiveProject) {
                BasicText(
                    "Open a project to declare web or mobile apps for Design Canvas.",
                    style = ppmText(11f).copy(color = tokens.text3.color),
                )
            } else {
                for (sys in systems) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            BasicText("${sys.label} (${sys.platform})", style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text.color))
                            BasicText(sys.root.ifEmpty { "." }, style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text3.color))
                        }
                        SettingsButton(
                            label = "Remove",
                            danger = true,
                            onClick = { onRemoveSystem(sys.id, false) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsTextInput(
                        value = appLabel,
                        onValueChange = { appLabel = it },
                        placeholder = "App label",
                        modifier = Modifier.weight(1f),
                    )
                    SettingsTextInput(
                        value = appRoot,
                        onValueChange = { appRoot = it },
                        placeholder = "Root path (e.g. apps/web)",
                        mono = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (plat in listOf("web", "mobile")) {
                        SettingsButton(
                            label = plat.uppercase(),
                            primary = appPlatform == plat,
                            onClick = { appPlatform = plat },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    SettingsButton(
                        label = "+ Declare App",
                        primary = true,
                        enabled = appLabel.isNotBlank(),
                        onClick = {
                            onAddSystem(appLabel.trim(), appPlatform, appRoot.trim().ifEmpty { "." })
                            appLabel = ""
                            appRoot = ""
                        },
                    )
                }
            }
        }

        Separator()

        // Design Instructions & Skills
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsSectionTitle("Design Instructions")
                BasicText(
                    "$byteCount / $DESIGN_INSTRUCTIONS_MAX_BYTES bytes",
                    style = ppmText(11f, kind = FontKind.MONO).copy(
                        color = if (overLimit) tokens.error.color else tokens.text3.color,
                    ),
                    modifier = Modifier.testTag("design-instructions-byte-count"),
                )
            }

            val allSkills: List<DesignSkillItem> = remember(state.providers) {
                state.providers.flatMap { it.items }.distinctBy { it.name }
            }
            if (allSkills.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (skill in allSkills) {
                        val chipShape = RoundedCornerShape(6.dp)
                        Box(
                            Modifier
                                .clip(chipShape)
                                .background(tokens.panel2.color)
                                .cssBorder(tokens.border.color, chipShape)
                                .clickable {
                                    val suffix = if (instructionsText.isEmpty() || instructionsText.endsWith(" ")) "" else " "
                                    instructionsText = "$instructionsText$suffix/${skill.name}"
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("design-skill-chip-${skill.name}"),
                        ) {
                            BasicText("/${skill.name}", style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.accent.color))
                        }
                    }
                }
            }

            SettingsTextInput(
                value = instructionsText,
                onValueChange = { instructionsText = it },
                placeholder = "Describe design system rules or mention /skills…",
                singleLine = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 84.dp)
                    .testTag("design-instructions-input"),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsButton(
                    label = "Save Instructions",
                    primary = true,
                    enabled = !overLimit,
                    modifier = Modifier.testTag("design-instructions-save-btn"),
                    onClick = { onSaveInstructions(instructionsText) },
                )
                SettingsButton(
                    label = "Install Suggested Skill",
                    modifier = Modifier.testTag("design-install-skill-btn"),
                    onClick = onInstallSkill,
                )
            }
        }
    }
}
