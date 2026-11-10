package com.hienlh.ppm.app.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.BuildConfig
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.applyUpgrade
import com.hienlh.ppm.client.dbInstallDriver
import com.hienlh.ppm.client.dbListDrivers
import com.hienlh.ppm.client.dbUninstallDriver
import com.hienlh.ppm.client.lspGlobalInstall
import com.hienlh.ppm.client.lspGlobalServers
import com.hienlh.ppm.client.lspGlobalUninstall
import com.hienlh.ppm.client.listProjectDirectory
import com.hienlh.ppm.client.remoteDesktopRelayInstall
import com.hienlh.ppm.client.remoteDesktopRelayStatus
import com.hienlh.ppm.client.remoteDesktopRelayUninstall
import com.hienlh.ppm.client.settingsChangePassword
import com.hienlh.ppm.client.settingsClearQueryAuditLogs
import com.hienlh.ppm.client.settingsDeleteTheme
import com.hienlh.ppm.client.settingsGlobalFiles
import com.hienlh.ppm.client.settingsImportTheme
import com.hienlh.ppm.client.settingsKeybindings
import com.hienlh.ppm.client.settingsListThemes
import com.hienlh.ppm.client.settingsPatchGlobalFiles
import com.hienlh.ppm.client.settingsPatchProjectFiles
import com.hienlh.ppm.client.settingsProjectFiles
import com.hienlh.ppm.client.settingsPutDeviceName
import com.hienlh.ppm.client.settingsPutKeybindings
import com.hienlh.ppm.client.settingsPutQueryAudit
import com.hienlh.ppm.client.settingsQueryAudit
import com.hienlh.ppm.client.settingsRenameTheme
import com.hienlh.ppm.client.upgradeStatus
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.settings.DbDriverRow
import com.hienlh.ppm.state.settings.EditorTabStyle
import com.hienlh.ppm.state.settings.ExplorerSkinMode
import com.hienlh.ppm.state.settings.FileFiltersState
import com.hienlh.ppm.state.settings.ImportedThemeItem
import com.hienlh.ppm.state.settings.KEY_ACTIONS
import com.hienlh.ppm.state.settings.KeyActionDef
import com.hienlh.ppm.state.settings.KeyCategory
import com.hienlh.ppm.state.settings.LspServerRow
import com.hienlh.ppm.state.settings.LspServersOverview
import com.hienlh.ppm.state.settings.QueryAuditState
import com.hienlh.ppm.state.settings.RelayStatusState
import com.hienlh.ppm.state.settings.ThemeUploadKind
import com.hienlh.ppm.state.settings.addExcludePattern
import com.hienlh.ppm.state.settings.buildThemeFileUploadRequests
import com.hienlh.ppm.state.settings.detectThemeUploadKind
import com.hienlh.ppm.state.settings.effectiveKeyCombo
import com.hienlh.ppm.state.settings.formatAuditByteSize
import com.hienlh.ppm.state.settings.formatDriverBytes
import com.hienlh.ppm.state.settings.formatKeyCombo
import com.hienlh.ppm.state.settings.hasBusyDbDriver
import com.hienlh.ppm.state.settings.isSystemReservedCombo
import com.hienlh.ppm.state.settings.lspOriginLabel
import com.hienlh.ppm.state.settings.parseDbDriverRow
import com.hienlh.ppm.state.settings.parseDbDrivers
import com.hienlh.ppm.state.settings.parseGlobalFileFilters
import com.hienlh.ppm.state.settings.parseImportedThemes
import com.hienlh.ppm.state.settings.parseKeybindingOverrides
import com.hienlh.ppm.state.settings.parseLspServersOverview
import com.hienlh.ppm.state.settings.parseProjectFileFilters
import com.hienlh.ppm.state.settings.parseQueryAuditState
import com.hienlh.ppm.state.settings.parseRelayStatus
import com.hienlh.ppm.state.settings.removeExcludePattern
import com.hienlh.ppm.state.settings.validateDeviceName
import com.hienlh.ppm.state.settings.validatePasswordChange
import com.hienlh.ppm.state.settings.validateQueryAuditInputs
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.hienlh.ppm.app.ui.shell.LearnPpmDialog
import com.hienlh.ppm.app.ui.shell.PreviousExitCrashCard
import com.hienlh.ppm.app.ui.shell.getDismissedCrashTimestamp
import com.hienlh.ppm.app.ui.shell.inspectLatestCrashOrAnrExit
import com.hienlh.ppm.app.ui.shell.loadOnboardingStateFromPrefs
import com.hienlh.ppm.app.ui.shell.saveDismissedCrashTimestamp
import com.hienlh.ppm.app.ui.shell.saveOnboardingStateToPrefs
import com.hienlh.ppm.state.shell.OnboardingEvidence
import com.hienlh.ppm.state.shell.OnboardingState
import com.hienlh.ppm.state.shell.PreviousExitCrashInfo
import com.hienlh.ppm.state.shell.applySuggestedPromptToDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ===========================================================================
// 1. General Settings Section (Device Name, Keep Screen Awake, Password, About)
// ===========================================================================

@Composable
internal fun GeneralSection(model: AppModel) {
    val tokens = LocalPpmTokens.current
    val api = model.api
    val scope = rememberCoroutineScope()

    var deviceName by remember(model.info?.deviceName) {
        mutableStateOf(model.info?.deviceName.orEmpty())
    }
    var savingDeviceName by remember { mutableStateOf(false) }
    var deviceNameMsg by remember { mutableStateOf<String?>(null) }
    var deviceNameErr by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (model.info?.minimalMode != true) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsSectionTitle("Learn PPM")
                BasicText(
                    "Choose your experience and a task. Guidance is saved on this device.",
                    style = ppmText(12f, lineHeight = 17f).copy(color = tokens.text3.color),
                )
                com.hienlh.ppm.app.ui.shell.OnboardingEntryRow(model, compact = false)
            }
            Separator()
        }

        // Device Name
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingsSectionTitle("Device Name")
            BasicText(
                "Friendly name for this machine (shown in header, browser tab, and PPM Cloud)",
                style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsTextInput(
                    value = deviceName,
                    onValueChange = {
                        deviceName = it
                        deviceNameMsg = null
                    },
                    placeholder = "e.g. My MacBook Pro, Home Server...",
                    modifier = Modifier.weight(1f).testTag("settings-device-name-input"),
                )
                SettingsButton(
                    label = if (savingDeviceName) "Saving…" else "Save",
                    primary = true,
                    enabled = !savingDeviceName && api != null,
                    modifier = Modifier.testTag("settings-device-name-save"),
                    onClick = {
                        val validationErr = validateDeviceName(deviceName)
                        if (validationErr != null) {
                            deviceNameErr = true
                            deviceNameMsg = validationErr
                            return@SettingsButton
                        }
                        val client = api ?: return@SettingsButton
                        savingDeviceName = true
                        deviceNameMsg = null
                        scope.launch {
                            try {
                                val res = client.settingsPutDeviceName(deviceName.trim())
                                val cloudSynced = res["cloud_synced"].bool == true
                                val cloudError = res["cloud_error"].string
                                model.updateDeviceNameLocal(deviceName.trim())
                                deviceNameErr = !cloudError.isNullOrBlank()
                                deviceNameMsg = when {
                                    !cloudError.isNullOrBlank() -> "Saved locally, cloud sync failed: $cloudError"
                                    cloudSynced -> "Saved & synced to Cloud"
                                    else -> "Saved"
                                }
                            } catch (t: Throwable) {
                                deviceNameErr = true
                                deviceNameMsg = t.message ?: "Failed to save device name"
                            } finally {
                                savingDeviceName = false
                            }
                        }
                    },
                )
            }
            if (deviceNameMsg != null) {
                BasicText(
                    deviceNameMsg!!,
                    style = ppmText(11f).copy(
                        color = if (deviceNameErr) tokens.error.color else tokens.success.color,
                    ),
                )
            }
        }

        Separator()

        // Keep Screen Awake
        SettingsToggleRow(
            title = "Keep Screen Awake",
            subtitle = "Prevent screen from turning off while AI is streaming",
            checked = model.keepScreenAwake,
            onCheckedChange = { model.setKeepScreenAwakePref(it) },
            testTag = "settings-keep-screen-awake",
        )

        Separator()

        // Change Password
        ChangePasswordBlock(
            onSubmit = { password, confirm ->
                val client = api ?: error("Not connected to a PPM server")
                val res = client.settingsChangePassword(password, confirm)
                val nextToken = res["token"].string ?: password.trim()
                model.updateActiveServerPassword(nextToken)
            },
        )

        Separator()

        // About
        AboutBlock(model)
    }
}

@Composable
internal fun ChangePasswordBlock(
    onSubmit: suspend (password: String, confirm: String) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionTitle("Change Password")
        BasicText(
            "Update the password used to access PPM",
            style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color),
        )
        SettingsPasswordInput(
            value = password,
            onValueChange = {
                password = it
                statusMsg = null
            },
            placeholder = "New password",
            visible = showPassword,
            onToggleVisible = { showPassword = !showPassword },
            modifier = Modifier.fillMaxWidth(),
            inputTestTag = "settings-password-input",
        )
        SettingsPasswordInput(
            value = confirm,
            onValueChange = {
                confirm = it
                statusMsg = null
            },
            placeholder = "Confirm new password",
            visible = showConfirm,
            onToggleVisible = { showConfirm = !showConfirm },
            modifier = Modifier.fillMaxWidth(),
            inputTestTag = "settings-password-confirm",
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SettingsButton(
                label = if (saving) "Updating…" else "Update Password",
                primary = true,
                enabled = !saving && password.isNotBlank() && confirm.isNotBlank(),
                modifier = Modifier.testTag("settings-password-submit"),
                onClick = {
                    val err = validatePasswordChange(password, confirm)
                    if (err != null) {
                        isError = true
                        statusMsg = err
                        return@SettingsButton
                    }
                    saving = true
                    statusMsg = null
                    scope.launch {
                        try {
                            onSubmit(password, confirm)
                            password = ""
                            confirm = ""
                            isError = false
                            statusMsg = "Password updated"
                        } catch (t: Throwable) {
                            isError = true
                            statusMsg = t.message ?: "Failed to update password"
                        } finally {
                            saving = false
                        }
                    }
                },
            )
            if (statusMsg != null) {
                BasicText(
                    statusMsg!!,
                    style = ppmText(11f).copy(color = if (isError) tokens.error.color else tokens.success.color),
                    modifier = Modifier.testTag("settings-password-status"),
                )
            }
        }
    }
}

@Composable
private fun AboutBlock(model: AppModel) {
    val tokens = LocalPpmTokens.current
    val context = LocalContext.current
    val api = model.api
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var upgrading by remember { mutableStateOf(false) }
    var reconnecting by remember { mutableStateOf(false) }
    var confirmUpgradeOpen by remember { mutableStateOf(false) }
    var availableVersion by remember { mutableStateOf<String?>(null) }
    var statusNote by remember { mutableStateOf<String?>(null) }
    var learnPpmOpen by remember { mutableStateOf(false) }
    fun currentWorkspaceEvidence(fileCount: Int = 0): OnboardingEvidence {
        val openTabs = model.layout?.panels?.values?.flatMap { panel -> panel.tabs }.orEmpty()
        return OnboardingEvidence(
            hasProjectSelected = model.selectedProject != null,
            projectFileCount = fileCount,
            chatSessionCount = model.sessions.size,
            hasOpenedFileTab = openTabs.any { tab -> tab.type == "editor" || tab.type == "file" },
            hasOpenedTerminalTab = openTabs.any { tab -> tab.type == "terminal" },
            hasOpenedGitTab = openTabs.any { tab -> tab.type == "git-status" || tab.type == "git-graph" },
        )
    }
    var onboardingState by remember {
        val saved = loadOnboardingStateFromPrefs(context)
        val initial = saved.mergeEvidence(currentWorkspaceEvidence())
        saveOnboardingStateToPrefs(context, initial)
        mutableStateOf(initial)
    }
    fun updateOnboarding(next: OnboardingState) {
        onboardingState = next
        saveOnboardingStateToPrefs(context, next)
    }
    fun refreshOnboardingEvidence() {
        val baseMerged = onboardingState.mergeEvidence(currentWorkspaceEvidence())
        updateOnboarding(baseMerged)
        val client = api ?: return
        val proj = model.selectedProject ?: return
        scope.launch {
            val fileCount = withContext(Dispatchers.IO) {
                runCatching { client.listProjectDirectory(proj, "").size }.getOrDefault(0)
            }
            updateOnboarding(onboardingState.mergeEvidence(currentWorkspaceEvidence(fileCount)))
        }
    }
    var crashExitInfo by remember { mutableStateOf<PreviousExitCrashInfo?>(null) }

    LaunchedEffect(Unit) {
        val dismissedTs = getDismissedCrashTimestamp(context)
        crashExitInfo = withContext(Dispatchers.IO) {
            inspectLatestCrashOrAnrExit(context, dismissedTimestampMs = dismissedTs)
        }
    }

    LaunchedEffect(learnPpmOpen) {
        if (learnPpmOpen) {
            refreshOnboardingEvidence()
        }
    }

    LaunchedEffect(api) {
        if (api == null) return@LaunchedEffect
        runCatching { api.upgradeStatus(refresh = false) }.getOrNull()?.let { st ->
            availableVersion = st.availableVersion
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionTitle("About")
        val shape = RoundedCornerShape(10.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(tokens.panel.color)
                .cssBorder(tokens.border.color, shape)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BasicText(
                        "PPM Server",
                        style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text.color),
                    )
                    BasicText(
                        model.info?.version?.let { "v$it" } ?: "Connected",
                        style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text3.color),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!availableVersion.isNullOrBlank()) {
                        SettingsButton(
                            label = when {
                                reconnecting -> "Updating, reconnecting…"
                                upgrading -> "Updating…"
                                else -> "Update to v$availableVersion"
                            },
                            primary = true,
                            enabled = !upgrading && !reconnecting && api != null,
                            modifier = Modifier.testTag("about-upgrade-btn"),
                            onClick = { confirmUpgradeOpen = true },
                        )
                    } else {
                        SettingsButton(
                            label = if (checking) "Checking…" else "Check for updates",
                            enabled = !checking && api != null,
                            onClick = {
                                val client = api ?: return@SettingsButton
                                checking = true
                                statusNote = null
                                scope.launch {
                                    try {
                                        val st = client.upgradeStatus(refresh = true)
                                        availableVersion = st.availableVersion
                                        if (st.availableVersion.isNullOrBlank()) {
                                            statusNote = "Server is up to date (v${st.currentVersion})"
                                        }
                                    } catch (t: Throwable) {
                                        statusNote = t.message ?: "Check failed"
                                    } finally {
                                        checking = false
                                    }
                                }
                            },
                        )
                    }
                }
            }
            if (statusNote != null) {
                BasicText(statusNote!!, style = ppmText(11f).copy(color = tokens.text2.color))
            }
            if (confirmUpgradeOpen && !availableVersion.isNullOrBlank()) {
                val targetVersion = availableVersion!!
                ConfirmDialog(
                    title = "Update PPM Server to v$targetVersion?",
                    message = "The server will install v$targetVersion and restart. Active connections will briefly reconnect.",
                    confirm = "Update now",
                    confirmTestTag = "about-upgrade-confirm",
                    onDismiss = { confirmUpgradeOpen = false },
                    onConfirm = {
                        confirmUpgradeOpen = false
                        val client = api ?: return@ConfirmDialog
                        upgrading = true
                        statusNote = "Updating to v$targetVersion…"
                        val serverUpgrade = model.upgrade
                        if (serverUpgrade != null) {
                            serverUpgrade.update(targetVersion)
                        }
                        scope.launch {
                            var shouldPollRestart = false
                            try {
                                if (serverUpgrade == null) {
                                    val res = client.applyUpgrade()
                                    if (res.restart) {
                                        shouldPollRestart = true
                                    } else {
                                        statusNote = res.message ?: "Upgrade installed. Restart PPM manually."
                                        availableVersion = null
                                    }
                                } else {
                                    shouldPollRestart = true
                                }
                            } catch (e: Exception) {
                                val failure = com.hienlh.ppm.state.UpgradeOffer.failure(e)
                                if (failure == com.hienlh.ppm.state.UpgradeFailure.Restarting) {
                                    shouldPollRestart = true
                                } else {
                                    statusNote = e.message ?: "Upgrade failed"
                                }
                            }
                            if (shouldPollRestart) {
                                reconnecting = true
                                statusNote = "Server restarting, reconnecting…"
                                val deadline = System.currentTimeMillis() + 60_000L
                                while (System.currentTimeMillis() < deadline) {
                                    val alive = runCatching { client.health() }.isSuccess
                                    if (alive) break
                                    delay(2_000L)
                                }
                                runCatching { model.loadProjects() }
                                availableVersion = null
                                statusNote = "Updated to v$targetVersion"
                            }
                            upgrading = false
                            reconnecting = false
                        }
                    },
                )
            }
            Separator()
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText("PPM Android App", style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text.color))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsButton(
                        label = "Learn PPM",
                        modifier = Modifier.testTag("settings-learn-ppm-btn"),
                        onClick = { model.learnPpmMode = false },
                    )
                    BasicText(
                        "v${BuildConfig.VERSION_NAME}",
                        style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text3.color),
                    )
                }
            }
        }
        crashExitInfo?.let { info ->
            PreviousExitCrashCard(
                crashInfo = info,
                onCopyReport = { md ->
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("PPM Crash Report", md))
                    model.toast(ErrorToastMessage("Crash report copied", "You can paste it into Report Bug.", kind = ErrorToastMessage.Kind.INFO))
                },
                onDismiss = {
                    saveDismissedCrashTimestamp(context, info.timestampMs)
                    crashExitInfo = null
                },
            )
        }
        if (learnPpmOpen) {
            LearnPpmDialog(
                state = onboardingState,
                onStateChange = { updateOnboarding(it) },
                onRefreshEvidence = { refreshOnboardingEvidence() },
                onInsertSuggestedPrompt = { prompt ->
                    val openTabs = model.layout?.panels?.values?.flatMap { panel -> panel.tabs }.orEmpty()
                    val existingChat = model.activeTab?.let { model.chat(it.id) }
                        ?: openTabs.firstNotNullOfOrNull { tab -> model.chat(tab.id) }
                    if (existingChat != null) {
                        val updated = applySuggestedPromptToDraft(existingChat.draft.text.toString(), prompt)
                        existingChat.draft.setTextAndPlaceCursorAtEnd(updated)
                        model.activate(existingChat.tabId)
                        existingChat.requestFocus()
                    } else if (model.selectedProject != null) {
                        model.newChat(pendingMessage = prompt)
                    } else {
                        model.terminalToChat(prompt, newTab = true, asContext = false)
                    }
                    updateOnboarding(onboardingState.markStepCompleted("ai.send"))
                    model.toast(
                        ErrorToastMessage(
                            "Prompt inserted into composer",
                            "Review the prompt in your chat composer before sending.",
                            kind = ErrorToastMessage.Kind.INFO,
                        ),
                    )
                    learnPpmOpen = false
                },
                onDismiss = { learnPpmOpen = false },
            )
        }
    }
}

// ===========================================================================
// 2. Appearance Extras (Imported Themes, Wrap Tabs, Word Wrap, Tab Style, Skin)
// ===========================================================================

@Composable
internal fun AppearanceExtrasSection(model: AppModel) {
    val tokens = LocalPpmTokens.current
    val api = model.api
    val scope = rememberCoroutineScope()

    var importedThemes by remember { mutableStateOf<List<ImportedThemeItem>>(emptyList()) }
    var importDialogOpen by remember { mutableStateOf(false) }
    var editingThemeId by remember { mutableStateOf<String?>(null) }
    var editingName by remember { mutableStateOf("") }

    fun reloadCustomThemes() {
        val client = api ?: return
        scope.launch {
            runCatching { parseImportedThemes(client.settingsListThemes()) }.getOrNull()?.let {
                importedThemes = it
                model.updateCustomThemes(it)
            }
        }
    }

    LaunchedEffect(api) {
        reloadCustomThemes()
    }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        // Custom Themes manager
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsSectionTitle("Custom Themes")
                SettingsButton(
                    label = "+ Import",
                    enabled = api != null,
                    modifier = Modifier.testTag("settings-import-theme-btn"),
                    onClick = { importDialogOpen = true },
                )
            }
            if (importedThemes.isEmpty()) {
                val shape = RoundedCornerShape(8.dp)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .cssBorder(tokens.border.color, shape)
                        .padding(14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        "Import a theme from OpenVSX or a VS Code theme JSON file",
                        style = ppmText(11f).copy(color = tokens.text3.color),
                    )
                }
            } else {
                for (custom in importedThemes) {
                    val active = model.themeSelection.style == "custom" && model.themeSelection.customThemeId == custom.id
                    val shape = RoundedCornerShape(10.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(tokens.panel.color)
                            .cssBorder((if (active) tokens.accent else tokens.border).color, shape)
                            .clickable {
                                scope.launch { model.pickCustomTheme(custom.id, custom.mode == "dark") }
                            }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (custom.swatch.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                for (hex in custom.swatch.take(3)) {
                                    val c = Rgba.parse(hex)?.color ?: tokens.accent.color
                                    Box(
                                        Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(c)
                                            .cssBorder(tokens.border.color, CircleShape),
                                    )
                                }
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (editingThemeId == custom.id) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    SettingsTextInput(
                                        value = editingName,
                                        onValueChange = { editingName = it },
                                        placeholder = custom.name,
                                        modifier = Modifier.weight(1f),
                                    )
                                    SettingsButton(
                                        label = "Save",
                                        primary = true,
                                        onClick = {
                                            val client = api ?: return@SettingsButton
                                            val nextName = editingName.trim()
                                            editingThemeId = null
                                            if (nextName.isNotEmpty() && nextName != custom.name) {
                                                scope.launch {
                                                    runCatching { client.settingsRenameTheme(custom.id, nextName) }
                                                    reloadCustomThemes()
                                                }
                                            }
                                        },
                                    )
                                }
                            } else {
                                BasicText(
                                    custom.name,
                                    style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text.color),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                BasicText(
                                    custom.mode,
                                    style = ppmText(10f).copy(color = tokens.text3.color),
                                )
                            }
                        }
                        if (active) {
                            PpmIcon(ProductIcon.Check, 14.dp, tokens.accent.color)
                        }
                        SettingsButton(
                            label = "Rename",
                            onClick = {
                                editingThemeId = custom.id
                                editingName = custom.name
                            },
                        )
                        SettingsButton(
                            label = "Delete",
                            danger = true,
                            onClick = {
                                val client = api ?: return@SettingsButton
                                scope.launch {
                                    runCatching { client.settingsDeleteTheme(custom.id) }
                                    if (active) {
                                        model.pickTheme(com.hienlh.ppm.design.BuiltInThemes.auroraDark)
                                    }
                                    reloadCustomThemes()
                                }
                            },
                        )
                    }
                }
            }
        }

        Separator()

        // Wrap Tabs
        SettingsToggleRow(
            title = "Wrap Tabs",
            subtitle = "Wrap tabs into multiple rows instead of scrolling horizontally",
            checked = model.tabWrap,
            onCheckedChange = { model.setTabWrapPref(it) },
            testTag = "settings-wrap-tabs",
        )

        // Word Wrap (phone local vs tablet shared)
        val effectiveWordWrap = if (model.wide) model.wordWrap else model.mobileWordWrap
        SettingsToggleRow(
            title = "Word Wrap",
            subtitle = "Wrap long lines in the code editor and diff viewer",
            checked = effectiveWordWrap,
            onCheckedChange = { model.setWordWrapPref(it) },
            testTag = "settings-word-wrap",
        )

        Separator()

        // Tab Style
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsSectionTitle("Tab Style")
            BasicText(
                "Visual style for workspace and dock tab bars",
                style = ppmText(11f).copy(color = tokens.text3.color),
            )
            for (opt in EditorTabStyle.entries) {
                val active = model.editorTabStyle == opt.wire
                SettingsRadioCard(
                    title = opt.label,
                    subtitle = opt.description,
                    selected = active,
                    testTag = "settings-tab-style-${opt.wire}",
                    onClick = { model.setEditorTabStylePref(opt.wire) },
                )
            }
        }

        Separator()

        // Explorer Skin
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsSectionTitle("Explorer Skin")
            BasicText(
                "Window frame and toolbar style for the OS File Explorer",
                style = ppmText(11f).copy(color = tokens.text3.color),
            )
            val currentSkin = model.explorerSkin ?: "auto"
            for (skin in ExplorerSkinMode.entries) {
                val active = currentSkin == skin.wire
                SettingsRadioCard(
                    title = skin.label,
                    subtitle = skin.description,
                    selected = active,
                    testTag = "settings-explorer-skin-${skin.wire}",
                    onClick = { model.setExplorerSkinPref(skin.wire) },
                )
            }
        }
    }

    if (importDialogOpen && api != null) {
        ThemeImportDialog(
            api = api,
            onDismiss = { importDialogOpen = false },
            onImported = { imported ->
                importDialogOpen = false
                if (imported.isNotEmpty()) {
                    val merged = (importedThemes.filterNot { existing -> imported.any { it.id == existing.id } } + imported)
                    importedThemes = merged
                    model.updateCustomThemes(merged)
                }
                reloadCustomThemes()
                val first = imported.firstOrNull()
                if (first != null) {
                    scope.launch { model.pickCustomTheme(first.id, first.mode == "dark") }
                }
            },
        )
    }
}

@Composable
private fun ThemeImportDialog(
    api: ApiClient,
    onDismiss: () -> Unit,
    onImported: (List<ImportedThemeItem>) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf("url") } // "url" | "file" | "paste"
    var urlInput by remember { mutableStateOf("") }
    var pasteInput by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            loading = true
            error = null
            try {
                val (fileName, requests) = withContext(Dispatchers.IO) {
                    var resolvedName = "theme.json"
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) resolvedName = c.getString(idx) ?: resolvedName
                        }
                    }
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Could not read selected file")
                    resolvedName to buildThemeFileUploadRequests(
                        fileName = resolvedName,
                        bytes = bytes,
                        nameHint = nameInput.trim().ifEmpty { null },
                    )
                }
                val importedAll = mutableListOf<ImportedThemeItem>()
                for (req in requests) {
                    val res = api.settingsImportTheme(
                        source = req.source,
                        value = req.value,
                        name = req.name ?: fileName.substringBeforeLast('.'),
                    )
                    importedAll.addAll(com.hienlh.ppm.state.settings.parseThemeImportResponse(res))
                }
                onImported(importedAll)
            } catch (t: Throwable) {
                error = t.message ?: "Import failed"
            } finally {
                loading = false
            }
        }
    }

    Dialog(onDismissRequest = { if (!loading) onDismiss() }) {
        val shape = RoundedCornerShape(12.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(tokens.panel.color)
                .cssBorder(tokens.border.color, shape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BasicText("Import Theme", style = ppmText(14f, FontWeight.SemiBold).copy(color = tokens.text.color))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((id, label) in listOf("url" to "From URL", "file" to "Upload File", "paste" to "Paste JSON")) {
                    SettingsButton(
                        label = label,
                        primary = tab == id,
                        onClick = {
                            tab = id
                            error = null
                        },
                    )
                }
            }
            SettingsTextInput(
                value = nameInput,
                onValueChange = { nameInput = it },
                placeholder = "Theme name (optional)",
                modifier = Modifier.fillMaxWidth(),
            )
            when (tab) {
                "url" -> {
                    SettingsTextInput(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        placeholder = "https://open-vsx.org/extension/... or raw JSON URL",
                        modifier = Modifier.fillMaxWidth(),
                        mono = true,
                    )
                }
                "file" -> {
                    SettingsButton(
                        label = if (loading) "Importing…" else "Choose .json / .vsix file…",
                        primary = true,
                        enabled = !loading,
                        onClick = { fileLauncher.launch(arrayOf("*/*")) },
                    )
                }
                "paste" -> {
                    SettingsTextInput(
                        value = pasteInput,
                        onValueChange = { pasteInput = it },
                        placeholder = "{ \"name\": \"My Theme\", \"colors\": { ... } }",
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                        mono = true,
                        singleLine = false,
                    )
                }
            }
            if (error != null) {
                BasicText(error!!, style = ppmText(11f).copy(color = tokens.error.color))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SettingsButton("Cancel", enabled = !loading, onClick = onDismiss)
                if (tab != "file") {
                    Spacer(Modifier.width(8.dp))
                    SettingsButton(
                        label = if (loading) "Importing…" else "Import",
                        primary = true,
                        enabled = !loading && (if (tab == "url") urlInput.isNotBlank() else pasteInput.isNotBlank()),
                        onClick = {
                            loading = true
                            error = null
                            scope.launch {
                                try {
                                    val res = api.settingsImportTheme(
                                        source = if (tab == "url") "url" else "json",
                                        value = if (tab == "url") urlInput.trim() else pasteInput.trim(),
                                        name = nameInput.trim().ifEmpty { null },
                                    )
                                    onImported(com.hienlh.ppm.state.settings.parseThemeImportResponse(res))
                                } catch (t: Throwable) {
                                    error = t.message ?: "Import failed"
                                } finally {
                                    loading = false
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

// ===========================================================================
// 3. Keyboard Shortcuts Section (Phase 12a.2)
// ===========================================================================

@Composable
internal fun KeyboardShortcutsSection(model: AppModel) {
    val api = model.api
    var overrides by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(api) {
        if (api == null) return@LaunchedEffect
        runCatching { parseKeybindingOverrides(api.settingsKeybindings()) }.getOrNull()?.let {
            overrides = it
        }
    }

    val extShortcuts = remember(model.extensions) {
        val contributes = model.extensions?.contributions?.value
        contributes?.commands.orEmpty().mapNotNull { cmd ->
            val kb = contributes?.keybindings.orEmpty().firstOrNull { it.command == cmd.command }
                ?: return@mapNotNull null
            Triple(cmd.command, cmd.title, kb.key)
        }
    }

    KeyboardShortcutsContent(
        overrides = overrides,
        minimalMode = model.info?.minimalMode == true,
        extensionShortcuts = extShortcuts,
        onSetBinding = { actionId, combo ->
            val next = overrides + (actionId to combo)
            overrides = next
            val client = api ?: return@KeyboardShortcutsContent
            scope.launch {
                runCatching { client.settingsPutKeybindings(mapOf(actionId to combo)) }
                model.reloadKeybindings()
            }
        },
        onResetBinding = { actionId ->
            val next = overrides - actionId
            overrides = next
            val client = api ?: return@KeyboardShortcutsContent
            scope.launch {
                runCatching { client.settingsPutKeybindings(mapOf(actionId to null)) }
                model.reloadKeybindings()
            }
        },
        onResetAll = {
            val resetPatch = overrides.keys.associateWith { null as String? }
            overrides = emptyMap()
            val client = api ?: return@KeyboardShortcutsContent
            scope.launch {
                runCatching { client.settingsPutKeybindings(resetPatch) }
                model.reloadKeybindings()
            }
        },
    )
}

@Composable
internal fun KeyboardShortcutsContent(
    overrides: Map<String, String>,
    minimalMode: Boolean = false,
    extensionShortcuts: List<Triple<String, String, String>> = emptyList(),
    onSetBinding: (String, String) -> Unit,
    onResetBinding: (String) -> Unit,
    onResetAll: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var recordingActionId by remember { mutableStateOf<String?>(null) }
    val visibleActions = remember(minimalMode) {
        if (minimalMode) KEY_ACTIONS.filter { it.minimalSafe } else KEY_ACTIONS
    }
    val hasOverrides = visibleActions.any { it.id in overrides }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                "Click a shortcut badge and press a new key combination on a hardware keyboard. Press Escape to cancel.",
                style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color),
                modifier = Modifier.weight(1f),
            )
            if (hasOverrides) {
                Spacer(Modifier.width(8.dp))
                SettingsButton(
                    label = "Reset All",
                    modifier = Modifier.testTag("shortcuts-reset-all"),
                    onClick = onResetAll,
                )
            }
        }

        for (cat in KeyCategory.entries) {
            val actions = visibleActions.filter { it.category == cat }
            if (actions.isEmpty()) continue
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText(
                    cat.label.uppercase(),
                    style = ppmText(11f, FontWeight.SemiBold).copy(color = tokens.text3.color),
                )
                val shape = RoundedCornerShape(10.dp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(tokens.panel.color)
                        .cssBorder(tokens.border.color, shape),
                ) {
                    actions.forEachIndexed { idx, action ->
                        if (idx > 0) Separator()
                        ShortcutActionRow(
                            action = action,
                            combo = effectiveKeyCombo(action, overrides),
                            isCustom = action.id in overrides,
                            recording = recordingActionId == action.id,
                            onStartRecording = { recordingActionId = action.id },
                            onCancelRecording = { recordingActionId = null },
                            onRecordCombo = { combo ->
                                recordingActionId = null
                                onSetBinding(action.id, combo)
                            },
                            onReset = { onResetBinding(action.id) },
                        )
                    }
                }
            }
        }

        if (!minimalMode && extensionShortcuts.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText(
                    "EXTENSIONS",
                    style = ppmText(11f, FontWeight.SemiBold).copy(color = tokens.text3.color),
                )
                val shape = RoundedCornerShape(10.dp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(tokens.panel.color)
                        .cssBorder(tokens.border.color, shape),
                ) {
                    extensionShortcuts.forEachIndexed { idx, (_, title, key) ->
                        if (idx > 0) Separator()
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicText(title, style = ppmText(12f).copy(color = tokens.text.color))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(tokens.bgSolid.color)
                                    .cssBorder(tokens.border.color, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                BasicText(
                                    formatKeyCombo(key),
                                    style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text3.color),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortcutActionRow(
    action: KeyActionDef,
    combo: String,
    isCustom: Boolean,
    recording: Boolean,
    onStartRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onRecordCombo: (String) -> Unit,
    onReset: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val reserved = combo.isNotBlank() && isSystemReservedCombo(combo)

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .onPreviewKeyEvent { ev ->
                if (!recording || ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (ev.key == Key.Escape) {
                    onCancelRecording()
                    return@onPreviewKeyEvent true
                }
                val recorded = hardwareKeyEventToCombo(
                    key = ev.key,
                    ctrl = ev.isCtrlPressed,
                    meta = ev.isMetaPressed,
                    alt = ev.isAltPressed,
                    shift = ev.isShiftPressed,
                ) ?: return@onPreviewKeyEvent false
                onRecordCombo(recorded)
                true
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText(action.label, style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text.color))
                if (isCustom) {
                    BasicText("(modified)", style = ppmText(10f).copy(color = tokens.accent.color))
                }
            }
            val noteText = action.note
            if (!noteText.isNullOrBlank()) {
                BasicText(noteText, style = ppmText(10f).copy(color = tokens.text3.color))
            }
            if (reserved) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PpmIcon(ProductIcon.TriangleAlert, 11.dp, tokens.warning.color)
                    BasicText(
                        "Used by browser — may not work",
                        style = ppmText(10f).copy(color = tokens.warning.color),
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (isCustom && !action.locked) {
                SettingsButton(
                    label = "Reset",
                    modifier = Modifier.testTag("shortcut-reset-${action.id}"),
                    onClick = onReset,
                )
            }
            val badgeShape = RoundedCornerShape(6.dp)
            Box(
                Modifier
                    .clip(badgeShape)
                    .background(if (recording) tokens.accent.opacity(0.15).color else tokens.bgSolid.color)
                    .cssBorder((if (recording) tokens.accent else tokens.border).color, badgeShape)
                    .clickable(enabled = !action.locked) {
                        if (recording) onCancelRecording() else onStartRecording()
                    }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .testTag("shortcut-badge-${action.id}"),
            ) {
                val text = when {
                    recording -> "Press keys…"
                    combo.isBlank() -> "Not set"
                    else -> formatKeyCombo(combo)
                }
                BasicText(
                    text,
                    style = ppmText(11f, kind = FontKind.MONO).copy(
                        color = when {
                            recording -> tokens.accent.color
                            action.locked -> tokens.text3.color
                            else -> tokens.text.color
                        },
                    ),
                )
            }
        }
    }
}

private fun hardwareKeyEventToCombo(
    key: Key,
    ctrl: Boolean,
    meta: Boolean,
    alt: Boolean,
    shift: Boolean,
): String? {
    val keyName = when (key) {
        Key.CtrlLeft, Key.CtrlRight,
        Key.ShiftLeft, Key.ShiftRight,
        Key.AltLeft, Key.AltRight,
        Key.MetaLeft, Key.MetaRight -> return null
        Key.F1 -> "F1"
        Key.F2 -> "F2"
        Key.F3 -> "F3"
        Key.F4 -> "F4"
        Key.F5 -> "F5"
        Key.A -> "A"
        Key.B -> "B"
        Key.C -> "C"
        Key.D -> "D"
        Key.E -> "E"
        Key.F -> "F"
        Key.G -> "G"
        Key.H -> "H"
        Key.I -> "I"
        Key.J -> "J"
        Key.K -> "K"
        Key.L -> "L"
        Key.M -> "M"
        Key.N -> "N"
        Key.O -> "O"
        Key.P -> "P"
        Key.Q -> "Q"
        Key.R -> "R"
        Key.S -> "S"
        Key.T -> "T"
        Key.U -> "U"
        Key.V -> "V"
        Key.W -> "W"
        Key.X -> "X"
        Key.Y -> "Y"
        Key.Z -> "Z"
        Key.One -> "1"
        Key.Two -> "2"
        Key.Three -> "3"
        Key.Four -> "4"
        Key.Five -> "5"
        Key.Six -> "6"
        Key.Seven -> "7"
        Key.Eight -> "8"
        Key.Nine -> "9"
        Key.Zero -> "0"
        Key.Comma -> ","
        Key.Apostrophe -> "'"
        Key.LeftBracket -> "["
        Key.RightBracket -> "]"
        Key.DirectionUp -> "ArrowUp"
        Key.DirectionDown -> "ArrowDown"
        Key.Spacebar -> "Space"
        else -> return null
    }
    val parts = mutableListOf<String>()
    if (ctrl || meta) parts.add("Mod")
    if (alt) parts.add("Alt")
    if (shift) parts.add("Shift")
    parts.add(keyName)
    return parts.joinToString("+")
}

// ===========================================================================
// 4. File Filters Section (Phase 12a.3)
// ===========================================================================

@Composable
internal fun FilesSettingsSection(model: AppModel) {
    val api = model.api
    val activeProject = model.selectedProject
    val scope = rememberCoroutineScope()

    var scopeSelection by remember { mutableStateOf("global") } // "global" or projectName
    var globalFilters by remember { mutableStateOf(FileFiltersState()) }
    var projectFilters by remember { mutableStateOf(FileFiltersState()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(api, activeProject) {
        if (api == null) return@LaunchedEffect
        loading = true
        try {
            val loadedGlobal = parseGlobalFileFilters(api.settingsGlobalFiles())
            globalFilters = loadedGlobal
            if (!activeProject.isNullOrBlank()) {
                val loadedProj = runCatching {
                    parseProjectFileFilters(api.settingsProjectFiles(activeProject), loadedGlobal)
                }.getOrDefault(loadedGlobal)
                projectFilters = loadedProj
            }
        } catch (_: Throwable) {
        } finally {
            loading = false
        }
    }

    val isGlobal = scopeSelection == "global"
    val current = if (isGlobal) globalFilters else projectFilters

    fun applyPatch(
        filesExclude: List<String>? = null,
        searchExclude: List<String>? = null,
        useIgnoreFiles: Boolean? = null,
    ) {
        val next = current.copy(
            filesExclude = filesExclude ?: current.filesExclude,
            searchExclude = searchExclude ?: current.searchExclude,
            useIgnoreFiles = useIgnoreFiles ?: current.useIgnoreFiles,
        )
        if (isGlobal) globalFilters = next else projectFilters = next
        val client = api ?: return
        scope.launch {
            runCatching {
                if (isGlobal) {
                    client.settingsPatchGlobalFiles(filesExclude, searchExclude, useIgnoreFiles)
                    model.invalidateFileIndexes(null)
                } else {
                    client.settingsPatchProjectFiles(scopeSelection, filesExclude, searchExclude, useIgnoreFiles)
                    model.invalidateFileIndexes(scopeSelection)
                }
            }
        }
    }

    FileFiltersContent(
        activeProject = activeProject,
        scopeSelection = scopeSelection,
        onScopeChange = { scopeSelection = it },
        filters = current,
        loading = loading,
        onToggleIgnoreFiles = { applyPatch(useIgnoreFiles = it) },
        onUpdateFilesExclude = { applyPatch(filesExclude = it) },
        onUpdateSearchExclude = { applyPatch(searchExclude = it) },
        onResetProjectToGlobal = {
            projectFilters = globalFilters
            applyPatch(
                filesExclude = globalFilters.filesExclude,
                searchExclude = globalFilters.searchExclude,
                useIgnoreFiles = globalFilters.useIgnoreFiles,
            )
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FileFiltersContent(
    activeProject: String?,
    scopeSelection: String,
    onScopeChange: (String) -> Unit,
    filters: FileFiltersState,
    loading: Boolean,
    onToggleIgnoreFiles: (Boolean) -> Unit,
    onUpdateFilesExclude: (List<String>) -> Unit,
    onUpdateSearchExclude: (List<String>) -> Unit,
    onResetProjectToGlobal: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val isGlobal = scopeSelection == "global"

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (!activeProject.isNullOrBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingsSectionTitle("Scope")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsButton(
                        label = "Global (all projects)",
                        primary = isGlobal,
                        modifier = Modifier.testTag("files-scope-global"),
                        onClick = { onScopeChange("global") },
                    )
                    SettingsButton(
                        label = "Project: $activeProject",
                        primary = !isGlobal,
                        modifier = Modifier.testTag("files-scope-project"),
                        onClick = { onScopeChange(activeProject) },
                    )
                }
                BasicText(
                    if (isGlobal) {
                        "Applies to all projects unless overridden in .ppm/settings.json"
                    } else {
                        "Saved in $activeProject/.ppm/settings.json — overrides global settings"
                    },
                    style = ppmText(11f).copy(color = tokens.text3.color),
                )
            }
            Separator()
        }

        if (loading) {
            BasicText("Loading file filter settings…", style = ppmText(12f).copy(color = tokens.text3.color))
        }

        SettingsToggleRow(
            title = "Use .gitignore / .ignore files",
            subtitle = "Respect .gitignore and .ignore rules when searching and indexing files",
            checked = filters.useIgnoreFiles,
            onCheckedChange = onToggleIgnoreFiles,
            testTag = "files-use-ignore-toggle",
        )

        Separator()

        PatternListEditor(
            title = "Explorer Exclude Patterns",
            description = "Files and folders matching these names or globs are hidden in the File Explorer tree.",
            patterns = filters.filesExclude,
            placeholder = "e.g. .git, *.log, coverage",
            testTagPrefix = "files-exclude",
            onChange = onUpdateFilesExclude,
        )

        Separator()

        PatternListEditor(
            title = "Search Exclude Patterns",
            description = "Excluded from Quick Open (Ctrl+P) and content search (Ctrl+Shift+F).",
            patterns = filters.searchExclude,
            placeholder = "e.g. node_modules, dist, *.lock",
            testTagPrefix = "search-exclude",
            onChange = onUpdateSearchExclude,
        )

        if (!isGlobal) {
            SettingsButton(
                label = "Reset project filters to global defaults",
                onClick = onResetProjectToGlobal,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PatternListEditor(
    title: String,
    description: String,
    patterns: List<String>,
    placeholder: String,
    testTagPrefix: String,
    onChange: (List<String>) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var draft by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionTitle(title)
        BasicText(description, style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color))
        if (patterns.isEmpty()) {
            BasicText("No patterns configured", style = ppmText(11f).copy(color = tokens.text3.color))
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (pat in patterns) {
                    val chipShape = RoundedCornerShape(6.dp)
                    Row(
                        Modifier
                            .clip(chipShape)
                            .background(tokens.panel.color)
                            .cssBorder(tokens.border.color, chipShape)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BasicText(pat, style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text.color))
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .clickable { onChange(removeExcludePattern(patterns, pat)) }
                                .padding(2.dp)
                                .testTag("$testTagPrefix-remove-$pat"),
                        ) {
                            PpmIcon(ProductIcon.X, 11.dp, tokens.text3.color)
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsTextInput(
                value = draft,
                onValueChange = { draft = it },
                placeholder = placeholder,
                modifier = Modifier.weight(1f).testTag("$testTagPrefix-input"),
                mono = true,
            )
            SettingsButton(
                label = "+ Add",
                enabled = draft.trim().isNotEmpty(),
                modifier = Modifier.testTag("$testTagPrefix-add"),
                onClick = {
                    val updated = addExcludePattern(patterns, draft)
                    if (updated != null) {
                        onChange(updated)
                        draft = ""
                    }
                },
            )
        }
    }
}

// ===========================================================================
// 5. Query Audit Log Section (Phase 12a.3)
// ===========================================================================

@Composable
internal fun QueryAuditSection(model: AppModel) {
    val api = model.api
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(QueryAuditState()) }
    var retentionInput by remember { mutableStateOf("60") }
    var maxSizeInput by remember { mutableStateOf("500") }
    var saving by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    LaunchedEffect(api) {
        if (api == null) return@LaunchedEffect
        runCatching { parseQueryAuditState(api.settingsQueryAudit()) }.getOrNull()?.let { loaded ->
            state = loaded
            retentionInput = loaded.retentionDays.toString()
            maxSizeInput = loaded.maxSizeMb.toString()
        }
    }

    QueryAuditContent(
        state = state,
        retentionInput = retentionInput,
        maxSizeInput = maxSizeInput,
        saving = saving,
        clearing = clearing,
        statusMsg = statusMsg,
        isError = isError,
        onRetentionChange = {
            retentionInput = it
            statusMsg = null
        },
        onMaxSizeChange = {
            maxSizeInput = it
            statusMsg = null
        },
        onSave = {
            val validated = validateQueryAuditInputs(retentionInput, maxSizeInput)
            if (validated == null) {
                isError = true
                statusMsg = "Retention must be at least 1 day and max size at least 10 MB"
                return@QueryAuditContent
            }
            val client = api ?: return@QueryAuditContent
            saving = true
            scope.launch {
                try {
                    val updated = parseQueryAuditState(
                        client.settingsPutQueryAudit(validated.first, validated.second),
                    )
                    state = updated
                    retentionInput = updated.retentionDays.toString()
                    maxSizeInput = updated.maxSizeMb.toString()
                    isError = false
                    statusMsg = "Saved"
                } catch (t: Throwable) {
                    isError = true
                    statusMsg = t.message ?: "Save failed"
                } finally {
                    saving = false
                }
            }
        },
        onClearLogs = {
            val client = api ?: return@QueryAuditContent
            clearing = true
            scope.launch {
                try {
                    client.settingsClearQueryAuditLogs()
                    val fresh = parseQueryAuditState(client.settingsQueryAudit())
                    state = fresh
                    isError = false
                    statusMsg = "Audit log cleared"
                } catch (t: Throwable) {
                    isError = true
                    statusMsg = t.message ?: "Failed to clear log"
                } finally {
                    clearing = false
                }
            }
        },
    )
}

@Composable
internal fun QueryAuditContent(
    state: QueryAuditState,
    retentionInput: String,
    maxSizeInput: String,
    saving: Boolean,
    clearing: Boolean,
    statusMsg: String?,
    isError: Boolean,
    onRetentionChange: (String) -> Unit,
    onMaxSizeChange: (String) -> Unit,
    onSave: () -> Unit,
    onClearLogs: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var confirmingClear by remember { mutableStateOf(false) }
    val validPair = validateQueryAuditInputs(retentionInput, maxSizeInput)
    val dirty = validPair != null &&
        (validPair.first != state.retentionDays || validPair.second != state.maxSizeMb)

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BasicText(
            "Every SQL statement PPM runs is recorded locally with its connection, duration, row count, and error.",
            style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText("Keep for (days)", style = ppmText(11f, FontWeight.Medium).copy(color = tokens.text2.color))
                SettingsTextInput(
                    value = retentionInput,
                    onValueChange = onRetentionChange,
                    placeholder = "60",
                    modifier = Modifier.fillMaxWidth().testTag("query-audit-retention"),
                    mono = true,
                    keyboardType = KeyboardType.Number,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText("Max log size (MB)", style = ppmText(11f, FontWeight.Medium).copy(color = tokens.text2.color))
                SettingsTextInput(
                    value = maxSizeInput,
                    onValueChange = onMaxSizeChange,
                    placeholder = "500",
                    modifier = Modifier.fillMaxWidth().testTag("query-audit-maxsize"),
                    mono = true,
                    keyboardType = KeyboardType.Number,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsButton(
                label = if (saving) "Saving…" else "Save",
                primary = true,
                enabled = dirty && !saving,
                modifier = Modifier.testTag("query-audit-save"),
                onClick = onSave,
            )
            if (statusMsg != null) {
                BasicText(
                    statusMsg,
                    style = ppmText(11f).copy(color = if (isError) tokens.error.color else tokens.success.color),
                )
            }
        }

        val shape = RoundedCornerShape(10.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(tokens.panel.color)
                .cssBorder(tokens.border.color, shape)
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BasicText(
                    "${state.entryCount} ${if (state.entryCount == 1L) "statement" else "statements"} · ${formatAuditByteSize(state.sizeBytes)} on disk",
                    style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text.color),
                )
                BasicText("Stored in ~/.ppm/query-audit.db", style = ppmText(10f).copy(color = tokens.text3.color))
            }
            if (confirmingClear) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingsButton(
                        label = "Cancel",
                        onClick = { confirmingClear = false },
                    )
                    SettingsButton(
                        label = if (clearing) "Clearing…" else "Confirm",
                        danger = true,
                        enabled = !clearing,
                        modifier = Modifier.testTag("query-audit-clear-confirm"),
                        onClick = {
                            confirmingClear = false
                            onClearLogs()
                        },
                    )
                }
            } else {
                SettingsButton(
                    label = "Clear Audit Log",
                    enabled = state.entryCount > 0L && !clearing,
                    modifier = Modifier.testTag("query-audit-clear"),
                    onClick = { confirmingClear = true },
                )
            }
        }
    }
}

// ===========================================================================
// 6. Language Servers Section (Phase 12a.3)
// ===========================================================================

@Composable
internal fun LanguageServersSection(model: AppModel) {
    val api = model.api
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var overview by remember { mutableStateOf(LspServersOverview()) }
    var loading by remember { mutableStateOf(false) }
    var busyServerId by remember { mutableStateOf<String?>(null) }
    var busyAction by remember { mutableStateOf<String?>(null) } // "install" | "uninstall"
    var confirmRemove by remember { mutableStateOf<LspServerRow?>(null) }

    fun reload() {
        val client = api ?: return
        scope.launch {
            loading = true
            try {
                overview = parseLspServersOverview(client.lspGlobalServers())
            } catch (_: Throwable) {
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(api) {
        reload()
    }

    LanguageServersContent(
        lspEnabled = model.lspEnabled,
        overview = overview,
        loading = loading,
        busyServerId = busyServerId,
        busyAction = busyAction,
        onToggleLspEnabled = { model.setLspEnabledPref(it) },
        onRefresh = ::reload,
        onInstall = { server ->
            val client = api ?: return@LanguageServersContent
            busyServerId = server.id
            busyAction = "install"
            scope.launch {
                try {
                    client.lspGlobalInstall(server.id)
                    model.toast(ErrorToastMessage("Installed ${server.displayName}", kind = ErrorToastMessage.Kind.SUCCESS))
                    reload()
                } catch (t: Throwable) {
                    model.toast(ErrorToastMessage("Could not install ${server.displayName}", t.message))
                } finally {
                    busyServerId = null
                    busyAction = null
                }
            }
        },
        onRequestUninstall = { confirmRemove = it },
        onCopyHint = { hint ->
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("LSP install command", hint))
            model.toast(ErrorToastMessage("Copied install command", kind = ErrorToastMessage.Kind.INFO))
        },
    )

    confirmRemove?.let { target ->
        val extra = if (target.alsoRemoves.isNotEmpty()) {
            " It shares its package with ${target.alsoRemoves.joinToString(", ")}, so those will be removed too."
        } else {
            ""
        }
        ConfirmDialog(
            title = "Remove ${target.displayName}?",
            message = "Active editor sessions using it will stop.$extra",
            confirm = "Remove",
            onConfirm = {
                val client = api ?: return@ConfirmDialog
                confirmRemove = null
                busyServerId = target.id
                busyAction = "uninstall"
                scope.launch {
                    try {
                        client.lspGlobalUninstall(target.id)
                        model.toast(ErrorToastMessage("Removed ${target.displayName}", kind = ErrorToastMessage.Kind.SUCCESS))
                        reload()
                    } catch (t: Throwable) {
                        model.toast(ErrorToastMessage("Could not remove ${target.displayName}", t.message))
                    } finally {
                        busyServerId = null
                        busyAction = null
                    }
                }
            },
            onDismiss = { confirmRemove = null },
        )
    }
}

@Composable
internal fun LanguageServersContent(
    lspEnabled: Boolean,
    overview: LspServersOverview,
    loading: Boolean,
    busyServerId: String?,
    busyAction: String?,
    onToggleLspEnabled: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onInstall: (LspServerRow) -> Unit,
    onRequestUninstall: (LspServerRow) -> Unit,
    onCopyHint: (String) -> Unit,
) {
    val tokens = LocalPpmTokens.current

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsToggleRow(
            title = "Enable in editor",
            subtitle = "Hover types, go-to-definition, and live diagnostics in the code editor (this device)",
            checked = lspEnabled,
            onCheckedChange = onToggleLspEnabled,
            testTag = "lsp-enable-toggle",
        )

        Separator()

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsSectionTitle("Language Servers (${overview.servers.size})")
            SettingsButton(label = if (loading) "Refreshing…" else "Refresh", onClick = onRefresh)
        }

        val shape = RoundedCornerShape(10.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(tokens.panel.color)
                .cssBorder(tokens.border.color, shape),
        ) {
            overview.servers.forEachIndexed { idx, srv ->
                if (idx > 0) Separator()
                val isBusy = busyServerId == srv.id
                val originText = lspOriginLabel(srv.origin, overview.installDir)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .testTag("lsp-row-${srv.id}"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (srv.installed) tokens.success.color else tokens.text3.opacity(0.4).color),
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                BasicText(
                                    srv.displayName,
                                    style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text.color),
                                )
                                BasicText(
                                    srv.languages.joinToString(", "),
                                    style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.text3.color),
                                )
                            }
                            BasicText(
                                if (srv.installed) originText.ifBlank { "Installed" } else "Not installed",
                                style = ppmText(11f).copy(color = tokens.text3.color),
                            )
                        }
                    }

                    when {
                        srv.installed && srv.removable -> {
                            SettingsButton(
                                label = if (isBusy && busyAction == "uninstall") "Removing…" else "Remove",
                                enabled = busyServerId == null,
                                modifier = Modifier.testTag("lsp-remove-${srv.id}"),
                                onClick = { onRequestUninstall(srv) },
                            )
                        }
                        !srv.installed && srv.installable -> {
                            SettingsButton(
                                label = if (isBusy && busyAction == "install") "Installing…" else "Install",
                                primary = true,
                                enabled = busyServerId == null,
                                modifier = Modifier.testTag("lsp-install-${srv.id}"),
                                onClick = { onInstall(srv) },
                            )
                        }
                        !srv.installed && srv.installHint.isNotBlank() -> {
                            SettingsButton(
                                label = srv.installHint,
                                onClick = { onCopyHint(srv.installHint) },
                            )
                        }
                    }
                }
            }
        }

        if (overview.running.isNotEmpty()) {
            SettingsSectionTitle("Active Processes (${overview.running.size})")
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(tokens.panel.color)
                    .cssBorder(tokens.border.color, shape),
            ) {
                overview.running.forEachIndexed { idx, proc ->
                    if (idx > 0) Separator()
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(proc.serverId, style = ppmText(11f, FontWeight.Medium).copy(color = tokens.text.color))
                        BasicText(
                            proc.rootPath,
                            style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.text3.color),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// ===========================================================================
// 7. Database Drivers Section (Phase 12a.3)
// ===========================================================================

@Composable
internal fun DatabaseDriversSection(model: AppModel) {
    val api = model.api
    val scope = rememberCoroutineScope()
    var drivers by remember { mutableStateOf<List<DbDriverRow>>(emptyList()) }
    var busyIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmUninstall by remember { mutableStateOf<DbDriverRow?>(null) }

    LaunchedEffect(api) {
        if (api == null) return@LaunchedEffect
        runCatching { parseDbDrivers(api.dbListDrivers()) }.getOrNull()?.let { drivers = it }
    }

    val serverBusy = hasBusyDbDriver(drivers)
    LaunchedEffect(api, serverBusy) {
        if (api == null || !serverBusy) return@LaunchedEffect
        while (isActive) {
            delay(2_000L)
            runCatching { parseDbDrivers(api.dbListDrivers()) }.getOrNull()?.let { drivers = it }
            if (!hasBusyDbDriver(drivers)) break
        }
    }

    DatabaseDriversContent(
        drivers = drivers,
        busyIds = busyIds,
        onInstall = { driver ->
            val client = api ?: return@DatabaseDriversContent
            busyIds = busyIds + driver.id
            scope.launch {
                try {
                    val updated = parseDbDriverRow(client.dbInstallDriver(driver.id))
                    if (updated != null) {
                        drivers = drivers.map { if (it.id == driver.id) updated else it }
                    }
                    model.toast(ErrorToastMessage("Installed the ${driver.displayName} driver", kind = ErrorToastMessage.Kind.SUCCESS))
                } catch (t: Throwable) {
                    model.toast(ErrorToastMessage("Could not install the ${driver.displayName} driver", t.message))
                    runCatching { parseDbDrivers(client.dbListDrivers()) }.getOrNull()?.let { drivers = it }
                } finally {
                    busyIds = busyIds - driver.id
                }
            }
        },
        onRequestUninstall = { confirmUninstall = it },
    )

    confirmUninstall?.let { target ->
        ConfirmDialog(
            title = "Remove the ${target.displayName} driver",
            message = "Open ${target.usedFor.ifBlank { "${target.displayName} connections" }} close now, and none can connect until the driver is installed again. Saved connections stay.",
            confirm = "Remove",
            onConfirm = {
                val client = api ?: return@ConfirmDialog
                confirmUninstall = null
                busyIds = busyIds + target.id
                scope.launch {
                    try {
                        val updated = parseDbDriverRow(client.dbUninstallDriver(target.id))
                        if (updated != null) {
                            drivers = drivers.map { if (it.id == target.id) updated else it }
                        }
                        model.toast(ErrorToastMessage("Removed the ${target.displayName} driver", kind = ErrorToastMessage.Kind.SUCCESS))
                    } catch (t: Throwable) {
                        model.toast(ErrorToastMessage("Could not remove the ${target.displayName} driver", t.message))
                    } finally {
                        busyIds = busyIds - target.id
                    }
                }
            },
            onDismiss = { confirmUninstall = null },
        )
    }
}

@Composable
internal fun DatabaseDriversContent(
    drivers: List<DbDriverRow>,
    busyIds: Set<String>,
    onInstall: (DbDriverRow) -> Unit,
    onRequestUninstall: (DbDriverRow) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BasicText(
            "PostgreSQL and SQLite are built in. Other databases need a driver, which PPM downloads when you press Install: the exact release PPM was tested with, every package checked against the digest recorded for it, into PPM's own folder. Nothing you installed yourself is used or changed, and Remove deletes exactly that folder.",
            style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color),
        )
        for (driver in drivers) {
            val isWorking = driver.id in busyIds || driver.installing || driver.removing
            val isRemoving = isWorking && driver.removing
            val shape = RoundedCornerShape(10.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(tokens.panel.color)
                    .cssBorder(tokens.border.color, shape)
                    .padding(12.dp)
                    .testTag("db-driver-${driver.id}"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        BasicText(driver.displayName, style = ppmText(13f, FontWeight.Medium).copy(color = tokens.text.color))
                        val usedForLabel = driver.usedFor.replaceFirstChar { it.uppercase() }
                        val metaParts = listOfNotNull(
                            usedForLabel.takeIf { it.isNotBlank() },
                            "${driver.pkg} ${driver.version}".trim().takeIf { it.isNotBlank() },
                            driver.license.takeIf { it.isNotBlank() },
                        )
                        BasicText(
                            metaParts.joinToString(" · "),
                            style = ppmText(11f).copy(color = tokens.text3.color),
                        )
                        val installedInfo = driver.installed
                        if (driver.state == "outdated" && installedInfo != null) {
                            BasicText(
                                "${installedInfo.version} is installed; this PPM was tested with ${driver.version}.",
                                style = ppmText(11f).copy(color = tokens.warning.color),
                            )
                        }
                    }
                    if (driver.state == "missing") {
                        SettingsButton(
                            label = if (isWorking) "Installing…" else "Install",
                            primary = true,
                            enabled = !isWorking && busyIds.isEmpty(),
                            modifier = Modifier.testTag("db-driver-install-${driver.id}"),
                            onClick = { onInstall(driver) },
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (driver.state == "outdated") {
                                SettingsButton(
                                    label = "Update",
                                    enabled = !isWorking && busyIds.isEmpty(),
                                    modifier = Modifier.testTag("db-driver-install-${driver.id}"),
                                    onClick = { onInstall(driver) },
                                )
                            } else {
                                BasicText(
                                    driver.installed?.let { formatDriverBytes(it.bytes) } ?: "Installed",
                                    style = ppmText(11f).copy(color = tokens.text3.color),
                                )
                            }
                            SettingsButton(
                                label = if (isRemoving) "Removing…" else "Remove",
                                enabled = !isWorking && busyIds.isEmpty(),
                                modifier = Modifier.testTag("db-driver-uninstall-${driver.id}"),
                                onClick = { onRequestUninstall(driver) },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ===========================================================================
// 8. Remote Desktop WebRTC Relay Section (Phase 12a.3)
// ===========================================================================

@Composable
internal fun RemoteDesktopSettingsSection(model: AppModel) {
    val api = model.api
    val scope = rememberCoroutineScope()
    var relay by remember { mutableStateOf(RelayStatusState()) }
    var busy by remember { mutableStateOf(false) }

    fun reload() {
        val client = api ?: return
        scope.launch {
            runCatching { parseRelayStatus(client.remoteDesktopRelayStatus()) }.getOrNull()?.let { relay = it }
        }
    }

    LaunchedEffect(api) {
        if (api == null) return@LaunchedEffect
        runCatching { parseRelayStatus(api.remoteDesktopRelayStatus()) }.getOrNull()?.let { relay = it }
    }

    LaunchedEffect(api, relay.isDownloading) {
        if (api == null || !relay.isDownloading) return@LaunchedEffect
        while (isActive) {
            delay(700L)
            val next = runCatching { parseRelayStatus(api.remoteDesktopRelayStatus()) }.getOrNull() ?: break
            relay = next
            if (!next.isDownloading) break
        }
    }

    RemoteDesktopRelayContent(
        relay = relay,
        busy = busy,
        onInstall = {
            val client = api ?: return@RemoteDesktopRelayContent
            busy = true
            scope.launch {
                try {
                    client.remoteDesktopRelayInstall()
                    runCatching { parseRelayStatus(client.remoteDesktopRelayStatus()) }.getOrNull()?.let { relay = it }
                } catch (t: Throwable) {
                    model.toast(ErrorToastMessage("Could not start the download", t.message))
                } finally {
                    busy = false
                }
            }
        },
        onUninstall = {
            val client = api ?: return@RemoteDesktopRelayContent
            busy = true
            scope.launch {
                try {
                    client.remoteDesktopRelayUninstall()
                    runCatching { parseRelayStatus(client.remoteDesktopRelayStatus()) }.getOrNull()?.let { relay = it }
                } catch (t: Throwable) {
                    model.toast(ErrorToastMessage("Could not remove the relay", t.message))
                } finally {
                    busy = false
                }
            }
        },
    )
}

@Composable
internal fun RemoteDesktopRelayContent(
    relay: RelayStatusState,
    busy: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(10.dp)
    val job = relay.job
    val percent = relay.progressPercent

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BasicText(
            "Streams the desktop over WebRTC instead of a WebSocket. It repackages the video this host already encoded — it never re-encodes, so the picture and the CPU cost are unchanged.",
            style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color),
        )
        if (!relay.available && !relay.installed) {
            BasicText(
                "MediaMTX publishes no build for this platform. You can install it yourself and put mediamtx on this host's PATH — PPM generates its own configuration either way.",
                style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(tokens.panel.color)
                .cssBorder(tokens.border.color, shape)
                .padding(14.dp)
                .testTag("rd-relay-card"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BasicText("WebRTC relay", style = ppmText(13f, FontWeight.Medium).copy(color = tokens.text.color))
                    val statusText = when {
                        relay.installed && relay.source == "system" -> "Installed on this host (not by PPM)"
                        relay.installed -> "Installed — MediaMTX ${relay.pinnedVersion}".trim()
                        else -> "Not installed"
                    }
                    BasicText(
                        statusText,
                        style = ppmText(11f).copy(color = tokens.text3.color),
                    )
                    if (relay.isDownloading && job != null) {
                        val mbReceived = Math.round(job.receivedBytes / 1_000_000.0)
                        val mbTotal = if (job.totalBytes > 0L) " of ${Math.round(job.totalBytes / 1_000_000.0)} MB" else ""
                        BasicText("${mbReceived} MB$mbTotal", style = ppmText(11f).copy(color = tokens.text3.color))
                    }
                    if (!job?.error.isNullOrBlank()) {
                        BasicText(job!!.error!!, style = ppmText(11f).copy(color = tokens.error.color))
                    }
                    if (!relay.path.isNullOrBlank()) {
                        BasicText(relay.path!!, style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text3.color))
                    }
                }

                when {
                    relay.canUninstall -> {
                        SettingsButton(
                            label = "Remove",
                            enabled = !busy && !relay.isDownloading,
                            modifier = Modifier.testTag("rd-relay-remove"),
                            onClick = onUninstall,
                        )
                    }
                    !relay.installed && relay.available -> {
                        SettingsButton(
                            label = if (relay.isDownloading) {
                                "Downloading${if (percent != null) " $percent%" else ""}"
                            } else {
                                "Install"
                            },
                            primary = true,
                            enabled = !busy && !relay.isDownloading,
                            modifier = Modifier.testTag("rd-relay-download"),
                            onClick = onInstall,
                        )
                    }
                }
            }
        }
    }
}

// ===========================================================================
// Shared Settings UI Primitives
// ===========================================================================

@Composable
internal fun SettingsSectionTitle(title: String) {
    val tokens = LocalPpmTokens.current
    BasicText(
        title,
        style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.text.color),
    )
}

@Composable
internal fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String? = null,
) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(title, style = ppmText(13f, FontWeight.Medium).copy(color = tokens.text.color))
            BasicText(subtitle, style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color))
        }
        val pillShape = RoundedCornerShape(12.dp)
        Box(
            Modifier
                .width(40.dp)
                .height(22.dp)
                .clip(pillShape)
                .background(if (checked) tokens.accent.color else tokens.panel2.color)
                .cssBorder((if (checked) tokens.accent else tokens.border).color, pillShape)
                .padding(horizontal = 3.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (checked) tokens.accentFg.color else tokens.text2.color),
            )
        }
    }
}

@Composable
internal fun SettingsRadioCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) tokens.accent.opacity(0.08).color else tokens.panel.color)
            .cssBorder((if (selected) tokens.accent else tokens.border).color, shape)
            .clickable(onClick = onClick)
            .padding(12.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(title, style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text.color))
            BasicText(subtitle, style = ppmText(11f).copy(color = tokens.text3.color))
        }
        if (selected) {
            PpmIcon(ProductIcon.Check, 15.dp, tokens.accent.color)
        }
    }
}

@Composable
internal fun SettingsButton(
    label: String,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val bg: Rgba = when {
        !enabled -> tokens.panel2.opacity(0.45)
        danger -> tokens.error.opacity(0.15)
        primary -> tokens.accent
        else -> tokens.panel2
    }
    val fg: Color = when {
        !enabled -> tokens.text3.opacity(0.5).color
        danger -> tokens.error.color
        primary -> tokens.accentFg.color
        else -> tokens.text.color
    }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .heightIn(min = 32.dp)
            .clip(shape)
            .background(bg.color)
            .cssBorder((if (primary && enabled) tokens.accent else tokens.border).color, shape)
            .ppmClickable(
                enabled = enabled,
                pressedColor = tokens.panel2.color,
                hoverColor = tokens.panel2.opacity(0.7).color,
                shape = shape,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = ppmText(12f, FontWeight.Medium).copy(color = fg), maxLines = 1)
    }
}

@Composable
internal fun SettingsTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = ppmText(12f, kind = if (mono) FontKind.MONO else FontKind.SANS).copy(color = tokens.text.color),
        cursorBrush = SolidColor(tokens.accent.color),
        modifier = modifier
            .clip(shape)
            .background(tokens.bgSolid.color)
            .cssBorder(tokens.border.color, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    BasicText(
                        placeholder,
                        style = ppmText(12f, kind = if (mono) FontKind.MONO else FontKind.SANS).copy(color = tokens.text3.color),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                inner()
            }
        },
    )
}

@Composable
internal fun SettingsPasswordInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    visible: Boolean,
    onToggleVisible: () -> Unit,
    modifier: Modifier = Modifier,
    inputTestTag: String? = null,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .clip(shape)
            .background(tokens.bgSolid.color)
            .cssBorder(tokens.border.color, shape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            textStyle = ppmText(12f).copy(color = tokens.text.color),
            cursorBrush = SolidColor(tokens.accent.color),
            modifier = Modifier
                .weight(1f)
                .then(if (inputTestTag != null) Modifier.testTag(inputTestTag) else Modifier),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        BasicText(
                            placeholder,
                            style = ppmText(12f).copy(color = tokens.text3.color),
                            maxLines = 1,
                        )
                    }
                    inner()
                }
            },
        )
        Box(
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onToggleVisible)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            BasicText(
                if (visible) "Hide" else "Show",
                style = ppmText(10f, FontWeight.Medium).copy(color = tokens.text3.color),
            )
        }
    }
}

/**
 * Copies a secret/token/backup to the system clipboard with `ClipDescription.EXTRA_IS_SENSITIVE = true`
 * (`"android.content.extra.IS_SENSITIVE"` on pre-Tiramisu) so Android masks the clipboard preview.
 */
internal fun copySensitiveClip(context: Context, label: String, text: String): ClipData {
    val clip = ClipData.newPlainText(label, text)
    val extras = PersistableBundle().apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        } else {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    }
    clip.description.extras = extras
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(clip)
    return clip
}
