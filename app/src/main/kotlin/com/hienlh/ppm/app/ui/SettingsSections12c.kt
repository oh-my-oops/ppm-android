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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.hienlh.ppm.client.cloudGetAlias
import com.hienlh.ppm.client.cloudGetStatus
import com.hienlh.ppm.client.cloudLink
import com.hienlh.ppm.client.cloudLogin
import com.hienlh.ppm.client.cloudLogout
import com.hienlh.ppm.client.cloudSetAlias
import com.hienlh.ppm.client.cloudUnlink
import com.hienlh.ppm.client.extensionsDevLink
import com.hienlh.ppm.client.extensionsInstall
import com.hienlh.ppm.client.extensionsList
import com.hienlh.ppm.client.extensionsRemove
import com.hienlh.ppm.client.extensionsSetEnabled
import com.hienlh.ppm.client.jiraCancelResult
import com.hienlh.ppm.client.jiraCreateWatcher
import com.hienlh.ppm.client.jiraDebugResult
import com.hienlh.ppm.client.jiraDeleteConfig
import com.hienlh.ppm.client.jiraDeleteResult
import com.hienlh.ppm.client.jiraDeleteWatcher
import com.hienlh.ppm.client.jiraListConfigProjects
import com.hienlh.ppm.client.jiraListConfigs
import com.hienlh.ppm.client.jiraListResults
import com.hienlh.ppm.client.jiraListWatchers
import com.hienlh.ppm.client.jiraMarkResultRead
import com.hienlh.ppm.client.jiraPullWatcher
import com.hienlh.ppm.client.jiraPutConfig
import com.hienlh.ppm.client.jiraResumeResult
import com.hienlh.ppm.client.jiraTestConfig
import com.hienlh.ppm.client.jiraTestJql
import com.hienlh.ppm.client.jiraUpdateWatcher
import com.hienlh.ppm.client.namedTunnelCancelLogin
import com.hienlh.ppm.client.namedTunnelDisable
import com.hienlh.ppm.client.namedTunnelGetStatus
import com.hienlh.ppm.client.namedTunnelSetup
import com.hienlh.ppm.client.namedTunnelStartLogin
import com.hienlh.ppm.client.notificationsCancelTelegramConnect
import com.hienlh.ppm.client.notificationsDeleteNtfy
import com.hienlh.ppm.client.notificationsGetNtfy
import com.hienlh.ppm.client.notificationsGetPush
import com.hienlh.ppm.client.notificationsGetSettings
import com.hienlh.ppm.client.notificationsGetTelegram
import com.hienlh.ppm.client.notificationsPutNtfy
import com.hienlh.ppm.client.notificationsPutSettings
import com.hienlh.ppm.client.notificationsRemoveTelegramChat
import com.hienlh.ppm.client.notificationsStartTelegramConnect
import com.hienlh.ppm.client.notificationsTestNtfy
import com.hienlh.ppm.client.notificationsTestPush
import com.hienlh.ppm.client.notificationsUnsubscribePush
import com.hienlh.ppm.client.schedulesCreate
import com.hienlh.ppm.client.schedulesDelete
import com.hienlh.ppm.client.schedulesExport
import com.hienlh.ppm.client.schedulesGetLogs
import com.hienlh.ppm.client.schedulesImport
import com.hienlh.ppm.client.schedulesList
import com.hienlh.ppm.client.schedulesRunNow
import com.hienlh.ppm.client.schedulesUpdate
import com.hienlh.ppm.client.settingsGetProxy
import com.hienlh.ppm.client.settingsPutProxy
import com.hienlh.ppm.client.settingsPutTelegramToken
import com.hienlh.ppm.client.settingsTestTelegram
import com.hienlh.ppm.client.tailscaleCancelLogin
import com.hienlh.ppm.client.tailscaleConfigureService
import com.hienlh.ppm.client.tailscaleGetState
import com.hienlh.ppm.client.tailscaleStartLogin
import com.hienlh.ppm.client.tunnelGetStatus
import com.hienlh.ppm.client.tunnelSetEnabled
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.settings.CloudStatusState
import com.hienlh.ppm.state.settings.ExtensionItemRow
import com.hienlh.ppm.state.settings.JiraConfigRow
import com.hienlh.ppm.state.settings.JiraProjectOption
import com.hienlh.ppm.state.settings.JiraResultRow
import com.hienlh.ppm.state.settings.JiraWatcherRow
import com.hienlh.ppm.state.settings.NOTIFICATION_DELAY_OPTIONS
import com.hienlh.ppm.state.settings.NOTIFICATION_EVENT_DEFS
import com.hienlh.ppm.state.settings.NamedTunnelState
import com.hienlh.ppm.state.settings.NotificationSettingsState
import com.hienlh.ppm.state.settings.NtfyStatusState
import com.hienlh.ppm.state.settings.ProxySettingsState
import com.hienlh.ppm.state.settings.PublicTunnelState
import com.hienlh.ppm.state.settings.PushStatusState
import com.hienlh.ppm.state.settings.ScheduleLogBufferState
import com.hienlh.ppm.state.settings.ScheduleRowState
import com.hienlh.ppm.state.settings.TailscaleState
import com.hienlh.ppm.state.settings.TailscaleStepId
import com.hienlh.ppm.state.settings.TelegramNotifyState
import com.hienlh.ppm.state.settings.blockingTailscaleSetupStep
import com.hienlh.ppm.state.settings.buildProxyEndpointUrl
import com.hienlh.ppm.state.settings.clampJiraIntervalMs
import com.hienlh.ppm.state.settings.currentTailscaleSetupStep
import com.hienlh.ppm.state.settings.isConnectedViaAddress
import com.hienlh.ppm.state.settings.mergeScheduleLogs
import com.hienlh.ppm.state.settings.normalizeNtfyServer
import com.hienlh.ppm.state.settings.parseCloudStatus
import com.hienlh.ppm.state.settings.parseExtensionsList
import com.hienlh.ppm.state.settings.parseJiraConfigs
import com.hienlh.ppm.state.settings.parseJiraProjectOptions
import com.hienlh.ppm.state.settings.parseJiraResults
import com.hienlh.ppm.state.settings.parseJiraWatchers
import com.hienlh.ppm.state.settings.parseNamedTunnelState
import com.hienlh.ppm.state.settings.parseNotificationSettings
import com.hienlh.ppm.state.settings.parseNtfyStatus
import com.hienlh.ppm.state.settings.parseProxySettings
import com.hienlh.ppm.state.settings.parsePublicTunnelState
import com.hienlh.ppm.state.settings.parsePushStatus
import com.hienlh.ppm.state.settings.parseScheduleLogs
import com.hienlh.ppm.state.settings.parseScheduleTemplateJson
import com.hienlh.ppm.state.settings.parseSchedulesList
import com.hienlh.ppm.state.settings.parseTailscaleState
import com.hienlh.ppm.state.settings.parseTelegramNotifyStatus
import com.hienlh.ppm.state.settings.scheduleFormProblems
import com.hienlh.ppm.state.settings.tailscaleServiceUrl
import com.hienlh.ppm.state.settings.validateNtfyTopic
import com.hienlh.ppm.state.settings.validateTailscaleServiceName
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val PrettyJson12c = Json { prettyPrint = true }

private fun copyToClipboard12c(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

@Composable
private fun IntegrationCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        content()
    }
}

@Composable
private fun FilterChip12c(
    label: String,
    selected: Boolean,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) tokens.accent.opacity(0.16).color else tokens.bgSolid.color)
            .cssBorder((if (selected) tokens.accent else tokens.border).color, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
    ) {
        BasicText(
            label,
            style = ppmText(11f, FontWeight.Medium).copy(
                color = if (selected) tokens.accent.color else tokens.text2.color,
            ),
        )
    }
}

// ===========================================================================
// 1. Notifications Settings Section
// ===========================================================================

@Composable
internal fun NotificationsSettingsSection(model: AppModel) {
    val api = model.api
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var settings by remember { mutableStateOf(NotificationSettingsState()) }
    var pushStatus by remember { mutableStateOf(PushStatusState()) }
    var telegram by remember { mutableStateOf(TelegramNotifyState()) }
    var ntfy by remember { mutableStateOf(NtfyStatusState()) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    suspend fun reloadAll() {
        val client = api ?: return
        runCatching { parseNotificationSettings(client.notificationsGetSettings()) }.getOrNull()?.let { settings = it }
        runCatching { parsePushStatus(client.notificationsGetPush()) }.getOrNull()?.let { pushStatus = it }
        runCatching { parseTelegramNotifyStatus(client.notificationsGetTelegram()) }.getOrNull()?.let { telegram = it }
        runCatching { parseNtfyStatus(client.notificationsGetNtfy()) }.getOrNull()?.let { ntfy = it }
    }

    LaunchedEffect(api) {
        reloadAll()
    }

    LaunchedEffect(api, telegram.connectActive) {
        val client = api ?: return@LaunchedEffect
        if (!telegram.connectActive) return@LaunchedEffect
        while (telegram.connectActive) {
            delay(2_000L)
            val next = runCatching { parseTelegramNotifyStatus(client.notificationsGetTelegram()) }.getOrNull() ?: break
            telegram = next
        }
    }

    NotificationsSettingsContent(
        settings = settings,
        pushStatus = pushStatus,
        telegram = telegram,
        ntfy = ntfy,
        statusMessage = statusMessage,
        onUpdateSettings = { next ->
            settings = next
            val client = api ?: return@NotificationsSettingsContent
            scope.launch {
                runCatching {
                    parseNotificationSettings(client.notificationsPutSettings(next.toJsonPatch()))
                }.onSuccess { settings = it }
            }
        },
        onTestPushDevice = { id ->
            val client = api ?: return@NotificationsSettingsContent
            scope.launch {
                runCatching { client.notificationsTestPush(id) }
                    .onSuccess { statusMessage = "Test push sent" }
                    .onFailure { statusMessage = it.message ?: "Push test failed" }
            }
        },
        onRemovePushDevice = { id, endpoint ->
            val client = api ?: return@NotificationsSettingsContent
            scope.launch {
                runCatching { client.notificationsUnsubscribePush(endpoint = endpoint, id = id) }
                reloadAll()
            }
        },
        onSaveTelegramToken = { token ->
            val client = api ?: return@NotificationsSettingsContent
            scope.launch {
                runCatching { client.settingsPutTelegramToken(token) }
                    .onSuccess {
                        statusMessage = "Telegram bot token saved"
                        reloadAll()
                    }
                    .onFailure { statusMessage = it.message ?: "Failed to save Telegram token" }
            }
        },
        onTestTelegram = {
            val client = api ?: return@NotificationsSettingsContent
            scope.launch {
                runCatching { client.settingsTestTelegram() }
                    .onSuccess { statusMessage = "Telegram test notification sent" }
                    .onFailure { statusMessage = it.message ?: "Telegram test failed" }
            }
        },
        onStartTelegramConnect = {
            val client = api ?: return@NotificationsSettingsContent
            scope.launch {
                runCatching { client.notificationsStartTelegramConnect() }
                    .onSuccess { res ->
                        val url = res["url"].string ?: res.obj?.get("connect")?.obj?.get("url").string
                        reloadAll()
                        if (!url.isNullOrBlank()) {
                            runCatching { uriHandler.openUri(url) }
                        }
                    }
            }
        },
        onCancelTelegramConnect = {
            val client = api ?: return@NotificationsSettingsContent
            scope.launch {
                runCatching { client.notificationsCancelTelegramConnect() }
                reloadAll()
            }
        },
        onRemoveTelegramChat = { chatId ->
            val client = api ?: return@NotificationsSettingsContent
            scope.launch {
                runCatching { client.notificationsRemoveTelegramChat(chatId) }
                reloadAll()
            }
        },
        onSaveNtfy = { serverUrl, topic, token ->
            val client = api ?: return@NotificationsSettingsContent
            scope.launch {
                runCatching { client.notificationsPutNtfy(serverUrl, topic, token) }
                    .onSuccess {
                        statusMessage = "ntfy configuration saved"
                        reloadAll()
                    }
                    .onFailure { statusMessage = it.message ?: "Failed to save ntfy settings" }
            }
        },
        onTestNtfy = {
            val client = api ?: return@NotificationsSettingsContent
            scope.launch {
                runCatching { client.notificationsTestNtfy() }
                    .onSuccess { statusMessage = "ntfy test notification sent" }
                    .onFailure { statusMessage = it.message ?: "ntfy test failed" }
            }
        },
        onRemoveNtfy = {
            val client = api ?: return@NotificationsSettingsContent
            scope.launch {
                runCatching { client.notificationsDeleteNtfy() }
                reloadAll()
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NotificationsSettingsContent(
    settings: NotificationSettingsState,
    pushStatus: PushStatusState,
    telegram: TelegramNotifyState,
    ntfy: NtfyStatusState,
    statusMessage: String? = null,
    onUpdateSettings: (NotificationSettingsState) -> Unit,
    onTestPushDevice: (String) -> Unit,
    onRemovePushDevice: (String, String) -> Unit,
    onSaveTelegramToken: (String) -> Unit,
    onTestTelegram: () -> Unit,
    onStartTelegramConnect: () -> Unit,
    onCancelTelegramConnect: () -> Unit,
    onRemoveTelegramChat: (String) -> Unit,
    onSaveNtfy: (String, String, String?) -> Unit,
    onTestNtfy: () -> Unit,
    onRemoveNtfy: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var telegramTokenDraft by remember { mutableStateOf("") }
    var telegramTokenVisible by remember { mutableStateOf(false) }
    var ntfyServerDraft by remember(ntfy.server) { mutableStateOf(ntfy.server) }
    var ntfyTopicDraft by remember(ntfy.topic) { mutableStateOf(ntfy.topic) }
    var ntfyTokenDraft by remember { mutableStateOf("") }
    var ntfyValidationError by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!statusMessage.isNullOrBlank()) {
            BasicText(
                statusMessage,
                style = ppmText(11f).copy(color = tokens.accent.color),
                modifier = Modifier.testTag("notifications-status-msg"),
            )
        }

        // Event Toggles & Idle Delay
        IntegrationCard {
            SettingsSectionTitle("Notification Events")
            NOTIFICATION_EVENT_DEFS.forEachIndexed { idx, def ->
                if (idx > 0) Separator()
                val checked = settings.events[def.key] ?: true
                SettingsToggleRow(
                    title = def.label,
                    subtitle = def.description,
                    checked = checked,
                    testTag = "notify-event-${def.key}",
                    onCheckedChange = { next ->
                        onUpdateSettings(settings.copy(events = settings.events + (def.key to next)))
                    },
                )
            }
            Separator()
            BasicText("Idle Delay", style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text.color))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for ((sec, label) in NOTIFICATION_DELAY_OPTIONS) {
                    FilterChip12c(
                        label = label,
                        selected = settings.delaySeconds == sec,
                        testTag = "notify-delay-$sec",
                        onClick = { onUpdateSettings(settings.copy(delaySeconds = sec)) },
                    )
                }
            }
        }

        // Web Push Devices
        IntegrationCard {
            SettingsSectionTitle("Subscribed Push Devices (${pushStatus.devices.size})")
            if (pushStatus.devices.isEmpty()) {
                BasicText(
                    "No browser push devices registered.",
                    style = ppmText(11f).copy(color = tokens.text3.color),
                )
            } else {
                for (dev in pushStatus.devices) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            BasicText(dev.label, style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text.color))
                            if (!dev.lastError.isNullOrBlank()) {
                                BasicText(dev.lastError.orEmpty(), style = ppmText(10f).copy(color = tokens.error.color))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SettingsButton(
                                label = "Test",
                                modifier = Modifier.testTag("push-test-${dev.id}"),
                                onClick = { onTestPushDevice(dev.id) },
                            )
                            SettingsButton(
                                label = "Remove",
                                danger = true,
                                modifier = Modifier.testTag("push-remove-${dev.id}"),
                                onClick = { onRemovePushDevice(dev.id, dev.endpoint) },
                            )
                        }
                    }
                }
            }
        }

        // Telegram Channel
        IntegrationCard {
            SettingsToggleRow(
                title = "Telegram Notifications",
                subtitle = if (telegram.botUsername != null) "Connected to @${telegram.botUsername}" else "Send notifications to paired Telegram chats",
                checked = settings.telegram,
                testTag = "notify-telegram-toggle",
                onCheckedChange = { onUpdateSettings(settings.copy(telegram = it)) },
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsPasswordInput(
                    value = telegramTokenDraft,
                    onValueChange = { telegramTokenDraft = it },
                    placeholder = if (telegram.configured) "•••••••• (configured)" else "Bot token from @BotFather",
                    visible = telegramTokenVisible,
                    onToggleVisible = { telegramTokenVisible = !telegramTokenVisible },
                    modifier = Modifier.weight(1f),
                    inputTestTag = "notify-telegram-token-input",
                )
                SettingsButton(
                    label = "Save",
                    primary = true,
                    enabled = telegramTokenDraft.isNotBlank(),
                    modifier = Modifier.testTag("notify-telegram-save"),
                    onClick = {
                        onSaveTelegramToken(telegramTokenDraft.trim())
                        telegramTokenDraft = ""
                    },
                )
            }
            if (telegram.configured) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (telegram.connectActive) {
                        SettingsButton(
                            label = "Cancel Connect",
                            onClick = onCancelTelegramConnect,
                        )
                    } else {
                        SettingsButton(
                            label = "Connect Telegram",
                            primary = true,
                            modifier = Modifier.testTag("notify-telegram-connect"),
                            onClick = onStartTelegramConnect,
                        )
                    }
                    SettingsButton(
                        label = "Send Test",
                        modifier = Modifier.testTag("notify-telegram-test"),
                        onClick = onTestTelegram,
                    )
                }
            }
            for (chat in telegram.chats) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        "${chat.name} (${chat.chatId})",
                        style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text2.color),
                    )
                    SettingsButton(
                        label = "Remove",
                        danger = true,
                        onClick = { onRemoveTelegramChat(chat.chatId) },
                    )
                }
            }
        }

        // ntfy Channel
        IntegrationCard {
            SettingsToggleRow(
                title = "ntfy Notifications",
                subtitle = "Publish push alerts to an ntfy.sh or self-hosted topic",
                checked = settings.ntfy,
                testTag = "notify-ntfy-toggle",
                onCheckedChange = { onUpdateSettings(settings.copy(ntfy = it)) },
            )
            SettingsTextInput(
                value = ntfyServerDraft,
                onValueChange = {
                    ntfyServerDraft = it
                    ntfyValidationError = null
                },
                placeholder = "https://ntfy.sh",
                mono = true,
                modifier = Modifier.fillMaxWidth().testTag("notify-ntfy-server"),
            )
            SettingsTextInput(
                value = ntfyTopicDraft,
                onValueChange = {
                    ntfyTopicDraft = it
                    ntfyValidationError = null
                },
                placeholder = "Topic name (letters, numbers, - or _)",
                mono = true,
                modifier = Modifier.fillMaxWidth().testTag("notify-ntfy-topic"),
            )
            SettingsTextInput(
                value = ntfyTokenDraft,
                onValueChange = { ntfyTokenDraft = it },
                placeholder = if (ntfy.tokenSet) "Access token (configured)" else "Optional access token (tk_…)",
                mono = true,
                modifier = Modifier.fillMaxWidth().testTag("notify-ntfy-token"),
            )
            if (!ntfyValidationError.isNullOrBlank()) {
                BasicText(
                    ntfyValidationError!!,
                    style = ppmText(11f).copy(color = tokens.error.color),
                    modifier = Modifier.testTag("notify-ntfy-error"),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsButton(
                    label = "Save ntfy",
                    primary = true,
                    modifier = Modifier.testTag("notify-ntfy-save"),
                    onClick = {
                        val normalizedServer = normalizeNtfyServer(ntfyServerDraft)
                        if (normalizedServer == null) {
                            ntfyValidationError = "Enter a valid http:// or https:// server URL without query or credentials"
                            return@SettingsButton
                        }
                        val topicErr = validateNtfyTopic(ntfyTopicDraft)
                        if (topicErr != null) {
                            ntfyValidationError = topicErr
                            return@SettingsButton
                        }
                        ntfyValidationError = null
                        onSaveNtfy(
                            normalizedServer,
                            ntfyTopicDraft.trim(),
                            ntfyTokenDraft.trim().takeIf { it.isNotEmpty() },
                        )
                    },
                )
                if (ntfy.configured) {
                    SettingsButton(
                        label = "Test",
                        modifier = Modifier.testTag("notify-ntfy-test"),
                        onClick = onTestNtfy,
                    )
                    SettingsButton(
                        label = "Clear",
                        danger = true,
                        modifier = Modifier.testTag("notify-ntfy-clear"),
                        onClick = onRemoveNtfy,
                    )
                }
            }
        }
    }
}

// ===========================================================================
// 2. Remote Access Section (Tailscale, Public Link, Named Tunnel)
// ===========================================================================

@Composable
internal fun RemoteAccessSettingsSection(model: AppModel) {
    val api = model.api
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var tailscale by remember { mutableStateOf(TailscaleState()) }
    var publicTunnel by remember { mutableStateOf(PublicTunnelState()) }
    var namedTunnel by remember { mutableStateOf(NamedTunnelState()) }

    suspend fun reloadAll() {
        val client = api ?: return
        runCatching { parseTailscaleState(client.tailscaleGetState()) }.getOrNull()?.let { tailscale = it }
        runCatching { parsePublicTunnelState(client.tunnelGetStatus()) }.getOrNull()?.let { publicTunnel = it }
        runCatching { parseNamedTunnelState(client.namedTunnelGetStatus()) }.getOrNull()?.let { namedTunnel = it }
    }

    LaunchedEffect(api) {
        reloadAll()
    }

    val activeServerUrl: String? = model.debug?.url?.toString()
        ?: model.store.servers.value.firstOrNull { it.id == model.store.selectedId.value }?.url

    RemoteAccessSettingsContent(
        currentServerUrl = activeServerUrl,
        tailscale = tailscale,
        publicTunnel = publicTunnel,
        namedTunnel = namedTunnel,
        onRunInTerminal = { cmd -> model.runInTerminal(cmd) },
        onStartTailscaleLogin = {
            val client = api ?: return@RemoteAccessSettingsContent
            scope.launch {
                val res = runCatching { parseTailscaleState(client.tailscaleStartLogin()) }.getOrNull()
                if (res != null) {
                    tailscale = res
                    res.login.authUrl?.let { runCatching { uriHandler.openUri(it) } }
                }
            }
        },
        onCancelTailscaleLogin = {
            val client = api ?: return@RemoteAccessSettingsContent
            scope.launch {
                runCatching { parseTailscaleState(client.tailscaleCancelLogin()) }.getOrNull()?.let { tailscale = it }
            }
        },
        onConfigureTailscaleService = { enabled, name, replace ->
            val client = api ?: return@RemoteAccessSettingsContent
            scope.launch {
                runCatching {
                    parseTailscaleState(client.tailscaleConfigureService(enabled = enabled, name = name, replace = replace))
                }.getOrNull()?.let { tailscale = it }
            }
        },
        onTogglePublicTunnel = { enabled ->
            publicTunnel = publicTunnel.copy(enabled = enabled)
            val client = api ?: return@RemoteAccessSettingsContent
            scope.launch {
                runCatching { parsePublicTunnelState(client.tunnelSetEnabled(enabled)) }.getOrNull()?.let {
                    publicTunnel = it
                }
            }
        },
        onStartNamedTunnelLogin = { relogin ->
            val client = api ?: return@RemoteAccessSettingsContent
            scope.launch {
                val res = runCatching { parseNamedTunnelState(client.namedTunnelStartLogin(relogin)) }.getOrNull()
                if (res != null) {
                    namedTunnel = res
                    res.loginUrl?.let { runCatching { uriHandler.openUri(it) } }
                }
            }
        },
        onCancelNamedTunnelLogin = {
            val client = api ?: return@RemoteAccessSettingsContent
            scope.launch {
                runCatching { parseNamedTunnelState(client.namedTunnelCancelLogin()) }.getOrNull()?.let { namedTunnel = it }
            }
        },
        onSetupNamedTunnel = { hostname ->
            val client = api ?: return@RemoteAccessSettingsContent
            scope.launch {
                runCatching { parseNamedTunnelState(client.namedTunnelSetup(hostname)) }.getOrNull()?.let { namedTunnel = it }
            }
        },
        onDisableNamedTunnel = {
            val client = api ?: return@RemoteAccessSettingsContent
            scope.launch {
                runCatching { parseNamedTunnelState(client.namedTunnelDisable()) }.getOrNull()?.let { namedTunnel = it }
                reloadAll()
            }
        },
    )
}

@Composable
internal fun RemoteAccessSettingsContent(
    currentServerUrl: String?,
    tailscale: TailscaleState,
    publicTunnel: PublicTunnelState,
    namedTunnel: NamedTunnelState,
    onRunInTerminal: (String) -> Unit,
    onStartTailscaleLogin: () -> Unit,
    onCancelTailscaleLogin: () -> Unit,
    onConfigureTailscaleService: (Boolean?, String?, Boolean?) -> Unit,
    onTogglePublicTunnel: (Boolean) -> Unit,
    onStartNamedTunnelLogin: (Boolean) -> Unit,
    onCancelNamedTunnelLogin: () -> Unit,
    onSetupNamedTunnel: (String) -> Unit,
    onDisableNamedTunnel: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val context = LocalContext.current
    val currentStep = currentTailscaleSetupStep(tailscale)
    val blockingStep = blockingTailscaleSetupStep(tailscale)
    var serviceNameDraft by remember(tailscale.service.name) { mutableStateOf(tailscale.service.name) }
    val serviceNameError = validateTailscaleServiceName(serviceNameDraft)
    var namedHostnameDraft by remember(namedTunnel.hostname) { mutableStateOf(namedTunnel.hostname.orEmpty()) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // 1. Tailscale Card
        IntegrationCard(Modifier.testTag("remote-tailscale-card")) {
            SettingsSectionTitle("Tailscale Private Network")
            BasicText(
                "Status: ${tailscale.backendState}" + (tailscale.tailnetName?.let { " ($it)" } ?: ""),
                style = ppmText(11f).copy(color = tokens.text2.color),
            )
            if (!tailscale.selfDnsName.isNullOrBlank()) {
                val connected = isConnectedViaAddress(currentServerUrl, tailscale.selfDnsName)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        "https://${tailscale.selfDnsName}",
                        style = ppmText(12f, kind = FontKind.MONO).copy(color = tokens.text.color),
                    )
                    if (connected) {
                        BasicText("Connected via this address", style = ppmText(10f, FontWeight.SemiBold).copy(color = tokens.accent.color))
                    }
                }
            }

            // 7-step setup checklist
            for (step in TailscaleStepId.entries) {
                val isCurrent = currentStep == step
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        "${step.ordinal + 1}. ${step.title}",
                        style = ppmText(11f, if (isCurrent) FontWeight.SemiBold else FontWeight.Normal).copy(
                            color = if (isCurrent) tokens.accent.color else tokens.text2.color,
                        ),
                    )
                    if (isCurrent) {
                        BasicText(
                            if (blockingStep == step) "Action Required" else "Optional",
                            style = ppmText(10f).copy(color = tokens.warning.color),
                        )
                    }
                }
            }

            if (currentStep == TailscaleStepId.Operator && !tailscale.operatorCommand.isNullOrBlank()) {
                val cmd = tailscale.operatorCommand!!
                BasicText(
                    cmd,
                    style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text.color),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsButton(
                        label = "Run in Terminal",
                        primary = true,
                        modifier = Modifier.testTag("tailscale-run-operator-cmd"),
                        onClick = { onRunInTerminal(cmd) },
                    )
                    SettingsButton(
                        label = "Copy Command",
                        onClick = { copyToClipboard12c(context, "Tailscale Operator Command", cmd) },
                    )
                }
            }

            if (currentStep == TailscaleStepId.SignIn) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsButton(
                        label = "Sign In to Tailscale",
                        primary = true,
                        modifier = Modifier.testTag("tailscale-signin-btn"),
                        onClick = onStartTailscaleLogin,
                    )
                    if (tailscale.login.status == "starting" || tailscale.login.status == "url_ready") {
                        SettingsButton(
                            label = "Cancel",
                            onClick = onCancelTailscaleLogin,
                        )
                    }
                }
            }

            // Service configuration
            Separator()
            BasicText("Tailscale Service Name", style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text.color))
            SettingsTextInput(
                value = serviceNameDraft,
                onValueChange = { serviceNameDraft = it },
                placeholder = "ppm",
                mono = true,
                modifier = Modifier.fillMaxWidth().testTag("tailscale-service-name"),
            )
            if (serviceNameError != null) {
                BasicText(serviceNameError, style = ppmText(10f).copy(color = tokens.error.color))
            } else {
                tailscaleServiceUrl(tailscale, serviceNameDraft)?.let { preview ->
                    BasicText("Service URL: $preview", style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text3.color))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val isSvcEnabled = tailscale.service.status != "disabled"
                SettingsButton(
                    label = if (isSvcEnabled) "Update Service" else "Enable Service",
                    primary = true,
                    enabled = blockingStep == null && serviceNameError == null,
                    modifier = Modifier.testTag("tailscale-service-enable"),
                    onClick = { onConfigureTailscaleService(true, serviceNameDraft.trim(), null) },
                )
                if (isSvcEnabled) {
                    SettingsButton(
                        label = "Disable",
                        onClick = { onConfigureTailscaleService(false, null, null) },
                    )
                }
                if (tailscale.service.status == "conflict") {
                    SettingsButton(
                        label = "Replace Existing",
                        danger = true,
                        modifier = Modifier.testTag("tailscale-service-replace"),
                        onClick = { onConfigureTailscaleService(true, serviceNameDraft.trim(), true) },
                    )
                }
            }
        }

        // 2. Public Link (Quick Tunnel)
        IntegrationCard(Modifier.testTag("remote-public-tunnel-card")) {
            SettingsToggleRow(
                title = "Public Link (Quick Tunnel)",
                subtitle = "Expose PPM via a temporary *.trycloudflare.com HTTPS URL",
                checked = publicTunnel.enabled,
                testTag = "public-tunnel-toggle",
                onCheckedChange = onTogglePublicTunnel,
            )
            if (!publicTunnel.url.isNullOrBlank()) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        publicTunnel.url!!,
                        style = ppmText(12f, kind = FontKind.MONO).copy(color = tokens.accent.color),
                        modifier = Modifier.weight(1f),
                    )
                    SettingsButton(
                        label = "Copy",
                        onClick = { copyToClipboard12c(context, "Public Tunnel URL", publicTunnel.url!!) },
                    )
                }
            }
        }

        // 3. Named Cloudflare Tunnel
        IntegrationCard(Modifier.testTag("remote-named-tunnel-card")) {
            SettingsSectionTitle("Custom Domain (Cloudflare Named Tunnel)")
            BasicText(
                "Mode: ${namedTunnel.mode} · Certificate: ${namedTunnel.certState}",
                style = ppmText(11f).copy(color = tokens.text2.color),
            )
            if (!namedTunnel.tunnelWarning.isNullOrBlank()) {
                BasicText(namedTunnel.tunnelWarning!!, style = ppmText(11f).copy(color = tokens.warning.color))
            }
            SettingsTextInput(
                value = namedHostnameDraft,
                onValueChange = { namedHostnameDraft = it },
                placeholder = "ppm.example.com",
                mono = true,
                modifier = Modifier.fillMaxWidth().testTag("named-tunnel-hostname"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsButton(
                    label = if (namedTunnel.certState == "valid") "Re-login Cloudflare" else "Sign in with Cloudflare",
                    onClick = { onStartNamedTunnelLogin(namedTunnel.certState == "valid") },
                )
                if (namedTunnel.loginState == "starting" || namedTunnel.loginState == "url_ready") {
                    SettingsButton(label = "Cancel Login", onClick = onCancelNamedTunnelLogin)
                }
                SettingsButton(
                    label = "Save Domain",
                    primary = true,
                    enabled = namedHostnameDraft.isNotBlank(),
                    modifier = Modifier.testTag("named-tunnel-save"),
                    onClick = { onSetupNamedTunnel(namedHostnameDraft.trim()) },
                )
                if (namedTunnel.mode == "named") {
                    SettingsButton(
                        label = "Switch to Quick Tunnel",
                        danger = true,
                        onClick = onDisableNamedTunnel,
                    )
                }
            }
        }
    }
}

// ===========================================================================
// 3. Cloud & Share Section
// ===========================================================================

@Composable
internal fun CloudSettingsSection(model: AppModel) {
    val api = model.api
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf(CloudStatusState()) }
    var aliasSlug by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        val client = api ?: return
        runCatching { parseCloudStatus(client.cloudGetStatus()) }.getOrNull()?.let { status = it }
        runCatching { client.cloudGetAlias() }.getOrNull()?.let { res ->
            aliasSlug = res["slug"].string ?: res.obj?.get("alias")?.obj?.get("slug").string
        }
    }

    LaunchedEffect(api) {
        reload()
    }

    CloudSettingsContent(
        status = status,
        aliasSlug = aliasSlug,
        statusMessage = statusMessage,
        onLogin = { email, token, cloudUrl ->
            val client = api ?: return@CloudSettingsContent
            scope.launch {
                runCatching { client.cloudLogin(accessToken = token, email = email, cloudUrl = cloudUrl) }
                    .onSuccess {
                        statusMessage = "Signed in to PPM Cloud"
                        reload()
                    }
                    .onFailure { statusMessage = it.message ?: "Cloud login failed" }
            }
        },
        onLogout = {
            val client = api ?: return@CloudSettingsContent
            scope.launch {
                runCatching { client.cloudLogout() }
                reload()
            }
        },
        onLinkDevice = { name ->
            val client = api ?: return@CloudSettingsContent
            scope.launch {
                runCatching { client.cloudLink(name) }
                reload()
            }
        },
        onUnlinkDevice = {
            val client = api ?: return@CloudSettingsContent
            scope.launch {
                runCatching { client.cloudUnlink() }
                reload()
            }
        },
        onSaveAlias = { slugOrNull ->
            val client = api ?: return@CloudSettingsContent
            scope.launch {
                runCatching { client.cloudSetAlias(slugOrNull) }
                    .onSuccess {
                        aliasSlug = slugOrNull
                        statusMessage = if (slugOrNull == null) "Alias cleared" else "Alias saved"
                    }
                    .onFailure { statusMessage = it.message ?: "Failed to update alias" }
            }
        },
    )
}

@Composable
internal fun CloudSettingsContent(
    status: CloudStatusState,
    aliasSlug: String?,
    statusMessage: String? = null,
    onLogin: (String, String, String?) -> Unit,
    onLogout: () -> Unit,
    onLinkDevice: (String?) -> Unit,
    onUnlinkDevice: () -> Unit,
    onSaveAlias: (String?) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var emailDraft by remember { mutableStateOf("") }
    var tokenDraft by remember { mutableStateOf("") }
    var deviceNameDraft by remember(status.deviceName) { mutableStateOf(status.deviceName.orEmpty()) }
    var aliasDraft by remember(aliasSlug) { mutableStateOf(aliasSlug.orEmpty()) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!statusMessage.isNullOrBlank()) {
            BasicText(statusMessage, style = ppmText(11f).copy(color = tokens.accent.color))
        }

        IntegrationCard {
            SettingsSectionTitle("PPM Cloud Account")
            if (status.loggedIn) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        BasicText(status.email ?: "Signed in", style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text.color))
                        BasicText(status.cloudUrl, style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text3.color))
                    }
                    SettingsButton(
                        label = "Sign Out",
                        danger = true,
                        modifier = Modifier.testTag("cloud-logout-btn"),
                        onClick = onLogout,
                    )
                }
            } else {
                SettingsTextInput(
                    value = emailDraft,
                    onValueChange = { emailDraft = it },
                    placeholder = "Email address",
                    modifier = Modifier.fillMaxWidth().testTag("cloud-email-input"),
                )
                SettingsTextInput(
                    value = tokenDraft,
                    onValueChange = { tokenDraft = it },
                    placeholder = "Cloud access token",
                    mono = true,
                    modifier = Modifier.fillMaxWidth().testTag("cloud-token-input"),
                )
                SettingsButton(
                    label = "Sign In",
                    primary = true,
                    enabled = emailDraft.isNotBlank() && tokenDraft.isNotBlank(),
                    modifier = Modifier.testTag("cloud-login-btn"),
                    onClick = { onLogin(emailDraft.trim(), tokenDraft.trim(), null) },
                )
            }
        }

        IntegrationCard {
            SettingsSectionTitle("Device Link & Permanent Alias")
            if (status.linked) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        BasicText("Linked as ${status.deviceName ?: status.deviceId ?: "device"}", style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text.color))
                        if (!status.tunnelUrl.isNullOrBlank()) {
                            BasicText(status.tunnelUrl!!, style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.accent.color))
                        }
                    }
                    SettingsButton(
                        label = "Unlink Device",
                        danger = true,
                        modifier = Modifier.testTag("cloud-unlink-btn"),
                        onClick = onUnlinkDevice,
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingsTextInput(
                        value = deviceNameDraft,
                        onValueChange = { deviceNameDraft = it },
                        placeholder = "Device name (optional)",
                        modifier = Modifier.weight(1f),
                    )
                    SettingsButton(
                        label = "Link Device",
                        primary = true,
                        enabled = status.loggedIn,
                        modifier = Modifier.testTag("cloud-link-btn"),
                        onClick = { onLinkDevice(deviceNameDraft.trim().ifEmpty { null }) },
                    )
                }
            }

            Separator()
            BasicText("Permanent Share Slug", style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text.color))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingsTextInput(
                    value = aliasDraft,
                    onValueChange = { aliasDraft = it },
                    placeholder = "my-workstation",
                    mono = true,
                    modifier = Modifier.weight(1f).testTag("cloud-alias-input"),
                )
                SettingsButton(
                    label = "Save Alias",
                    primary = true,
                    modifier = Modifier.testTag("cloud-alias-save"),
                    onClick = {
                        // Server rejects empty string; pass null to clear.
                        onSaveAlias(aliasDraft.trim().ifEmpty { null })
                    },
                )
            }
        }
    }
}

// ===========================================================================
// 4. Proxy Settings Section
// ===========================================================================

@Composable
internal fun ProxySettingsSection(model: AppModel) {
    val api = model.api
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(ProxySettingsState()) }

    LaunchedEffect(api) {
        if (api == null) return@LaunchedEffect
        runCatching { parseProxySettings(api.settingsGetProxy()) }.getOrNull()?.let { state = it }
    }

    ProxySettingsContent(
        state = state,
        onToggleEnabled = { enabled ->
            state = state.copy(enabled = enabled)
            val client = api ?: return@ProxySettingsContent
            scope.launch {
                runCatching { parseProxySettings(client.settingsPutProxy(enabled = enabled)) }.getOrNull()?.let { state = it }
            }
        },
        onGenerateKey = {
            val client = api ?: return@ProxySettingsContent
            scope.launch {
                runCatching { parseProxySettings(client.settingsPutProxy(generateKey = true)) }.getOrNull()?.let { state = it }
            }
        },
    )
}

@Composable
internal fun ProxySettingsContent(
    state: ProxySettingsState,
    onToggleEnabled: (Boolean) -> Unit,
    onGenerateKey: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val context = LocalContext.current
    var provider by remember { mutableStateOf("anthropic") } // "anthropic" | "openai"
    var useTunnel by remember { mutableStateOf(false) }

    val endpointUrl = buildProxyEndpointUrl(state, provider, useTunnel)
    val envSnippet = if (provider == "openai") {
        "export OPENAI_BASE_URL=\"$endpointUrl\"\nexport OPENAI_API_KEY=\"${state.authKey}\""
    } else {
        "export ANTHROPIC_BASE_URL=\"$endpointUrl\"\nexport ANTHROPIC_API_KEY=\"${state.authKey}\""
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        IntegrationCard {
            SettingsToggleRow(
                title = "Enable API Proxy",
                subtitle = "Forward Anthropic & OpenAI SDK requests through PPM (${state.requestCount} requests served)",
                checked = state.enabled,
                testTag = "proxy-enable-toggle",
                onCheckedChange = onToggleEnabled,
            )
            Separator()
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    BasicText("Proxy Auth Key", style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text.color))
                    BasicText(
                        state.authKey.ifBlank { "Not generated" },
                        style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text2.color),
                        modifier = Modifier.testTag("proxy-auth-key"),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsButton(
                        label = "Generate Key",
                        primary = true,
                        modifier = Modifier.testTag("proxy-generate-key"),
                        onClick = onGenerateKey,
                    )
                    if (state.authKey.isNotBlank()) {
                        SettingsButton(
                            label = "Copy Key",
                            onClick = { copyToClipboard12c(context, "Proxy Auth Key", state.authKey) },
                        )
                    }
                }
            }
        }

        IntegrationCard {
            SettingsSectionTitle("Endpoint & Environment Snippet")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip12c(
                    label = "Anthropic",
                    selected = provider == "anthropic",
                    testTag = "proxy-provider-anthropic",
                    onClick = { provider = "anthropic" },
                )
                FilterChip12c(
                    label = "OpenAI / Codex",
                    selected = provider == "openai",
                    testTag = "proxy-provider-openai",
                    onClick = { provider = "openai" },
                )
                if (!state.tunnelUrl.isNullOrBlank()) {
                    FilterChip12c(
                        label = if (useTunnel) "Public Tunnel" else "Local URL",
                        selected = useTunnel,
                        onClick = { useTunnel = !useTunnel },
                    )
                }
            }
            BasicText(
                endpointUrl.ifBlank { "http://localhost:3210/proxy" },
                style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.accent.color),
                modifier = Modifier.testTag("proxy-endpoint-url"),
            )
            BasicText(
                envSnippet,
                style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text2.color),
            )
            SettingsButton(
                label = "Copy Env Snippet",
                onClick = { copyToClipboard12c(context, "Proxy Env Snippet", envSnippet) },
            )
        }
    }
}

// ===========================================================================
// 5. Extensions Settings Section
// ===========================================================================

@Composable
internal fun ExtensionsSettingsSection(model: AppModel) {
    val api = model.api
    val scope = rememberCoroutineScope()
    var extensions by remember { mutableStateOf<List<ExtensionItemRow>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        val client = api ?: return
        runCatching { parseExtensionsList(client.extensionsList()) }.getOrNull()?.let { extensions = it }
    }

    LaunchedEffect(api) {
        reload()
    }

    ExtensionsSettingsContent(
        extensions = extensions,
        busy = busy,
        statusMessage = statusMessage,
        onInstall = { pkg ->
            val client = api ?: return@ExtensionsSettingsContent
            busy = true
            scope.launch {
                runCatching { client.extensionsInstall(pkg) }
                    .onSuccess {
                        statusMessage = "Installed $pkg"
                        reload()
                    }
                    .onFailure { statusMessage = it.message ?: "Install failed" }
                busy = false
            }
        },
        onDevLink = { path ->
            val client = api ?: return@ExtensionsSettingsContent
            busy = true
            scope.launch {
                runCatching { client.extensionsDevLink(path) }
                    .onSuccess {
                        statusMessage = "Linked $path"
                        reload()
                    }
                    .onFailure { statusMessage = it.message ?: "Dev-link failed" }
                busy = false
            }
        },
        onToggleEnabled = { id, enabled ->
            extensions = extensions.map { if (it.id == id) it.copy(enabled = enabled) else it }
            val client = api ?: return@ExtensionsSettingsContent
            scope.launch {
                runCatching { client.extensionsSetEnabled(id, enabled) }
                reload()
            }
        },
        onRemove = { id ->
            val client = api ?: return@ExtensionsSettingsContent
            scope.launch {
                runCatching { client.extensionsRemove(id) }
                reload()
            }
        },
    )
}

@Composable
internal fun ExtensionsSettingsContent(
    extensions: List<ExtensionItemRow>,
    busy: Boolean = false,
    statusMessage: String? = null,
    onInstall: (String) -> Unit,
    onDevLink: (String) -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var installNameDraft by remember { mutableStateOf("") }
    var devLinkPathDraft by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!statusMessage.isNullOrBlank()) {
            BasicText(statusMessage, style = ppmText(11f).copy(color = tokens.accent.color))
        }

        IntegrationCard {
            SettingsSectionTitle("Install or Dev-Link Extension")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingsTextInput(
                    value = installNameDraft,
                    onValueChange = { installNameDraft = it },
                    placeholder = "@ppm/ext-git-graph or npm package",
                    mono = true,
                    modifier = Modifier.weight(1f).testTag("ext-install-input"),
                )
                SettingsButton(
                    label = if (busy) "Installing…" else "Install",
                    primary = true,
                    enabled = !busy && installNameDraft.isNotBlank(),
                    modifier = Modifier.testTag("ext-install-btn"),
                    onClick = {
                        onInstall(installNameDraft.trim())
                        installNameDraft = ""
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingsTextInput(
                    value = devLinkPathDraft,
                    onValueChange = { devLinkPathDraft = it },
                    placeholder = "/path/to/local/extension",
                    mono = true,
                    modifier = Modifier.weight(1f).testTag("ext-devlink-input"),
                )
                SettingsButton(
                    label = "Dev-Link",
                    enabled = !busy && devLinkPathDraft.isNotBlank(),
                    modifier = Modifier.testTag("ext-devlink-btn"),
                    onClick = {
                        onDevLink(devLinkPathDraft.trim())
                        devLinkPathDraft = ""
                    },
                )
            }
        }

        IntegrationCard {
            SettingsSectionTitle("Installed Extensions (${extensions.size})")
            if (extensions.isEmpty()) {
                BasicText("No extensions installed.", style = ppmText(11f).copy(color = tokens.text3.color))
            } else {
                extensions.forEachIndexed { idx, ext ->
                    if (idx > 0) Separator()
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SettingsToggleRow(
                            title = "${ext.displayName} (${ext.version})",
                            subtitle = ext.description.ifBlank { ext.id },
                            checked = ext.enabled,
                            testTag = "ext-toggle-${ext.id}",
                            onCheckedChange = { onToggleEnabled(ext.id, it) },
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicText(
                                if (ext.activated) "${ext.id} · Active" else ext.id,
                                style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.text3.color),
                            )
                            SettingsButton(
                                label = "Uninstall",
                                danger = true,
                                modifier = Modifier.testTag("ext-remove-${ext.id}"),
                                onClick = { onRemove(ext.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ===========================================================================
// 6. Jira Settings Section
// ===========================================================================

@Composable
internal fun JiraSettingsSection(model: AppModel) {
    val api = model.api
    val scope = rememberCoroutineScope()

    var projects by remember { mutableStateOf<List<JiraProjectOption>>(emptyList()) }
    var configs by remember { mutableStateOf<List<JiraConfigRow>>(emptyList()) }
    var watchers by remember { mutableStateOf<List<JiraWatcherRow>>(emptyList()) }
    var results by remember { mutableStateOf<List<JiraResultRow>>(emptyList()) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    suspend fun reloadAll() {
        val client = api ?: return
        val loadedProjects = runCatching { parseJiraProjectOptions(client.jiraListConfigProjects()) }.getOrDefault(emptyList())
        projects = loadedProjects
        val loadedConfigs = runCatching { parseJiraConfigs(client.jiraListConfigs()) }.getOrDefault(emptyList())
        configs = loadedConfigs
        val allWatchers = mutableListOf<JiraWatcherRow>()
        for (cfg in loadedConfigs) {
            val w = runCatching { parseJiraWatchers(client.jiraListWatchers(cfg.id)) }.getOrDefault(emptyList())
            allWatchers.addAll(w)
        }
        watchers = allWatchers
        results = runCatching { parseJiraResults(client.jiraListResults()) }.getOrDefault(emptyList())
    }

    LaunchedEffect(api) {
        reloadAll()
    }

    JiraSettingsContent(
        jiraEnabled = model.jiraEnabled,
        projects = projects,
        configs = configs,
        watchers = watchers,
        results = results,
        statusMessage = statusMessage,
        onToggleJiraEnabled = { model.setJiraEnabledPref(it) },
        onSaveConfig = { projectId, baseUrl, email, token ->
            val client = api ?: return@JiraSettingsContent
            scope.launch {
                runCatching { client.jiraPutConfig(projectId, baseUrl, email, token) }
                    .onSuccess {
                        statusMessage = "Jira credentials saved"
                        reloadAll()
                    }
                    .onFailure { statusMessage = it.message ?: "Failed to save Jira config" }
            }
        },
        onTestConfig = { projectId ->
            val client = api ?: return@JiraSettingsContent
            scope.launch {
                runCatching { client.jiraTestConfig(projectId) }
                    .onSuccess { statusMessage = "Jira connection verified" }
                    .onFailure { statusMessage = it.message ?: "Jira connection failed" }
            }
        },
        onDeleteConfig = { projectId ->
            val client = api ?: return@JiraSettingsContent
            scope.launch {
                runCatching { client.jiraDeleteConfig(projectId) }
                reloadAll()
            }
        },
        onCreateWatcher = { configId, name, jql, prompt, intervalMs, mode ->
            val client = api ?: return@JiraSettingsContent
            scope.launch {
                runCatching {
                    client.jiraCreateWatcher(
                        configId = configId,
                        name = name,
                        jql = jql,
                        promptTemplate = prompt,
                        intervalMs = clampJiraIntervalMs(intervalMs),
                        mode = mode,
                    )
                }.onSuccess {
                    statusMessage = "Watcher created"
                    reloadAll()
                }.onFailure { statusMessage = it.message ?: "Failed to create watcher" }
            }
        },
        onToggleWatcher = { id, enabled ->
            val client = api ?: return@JiraSettingsContent
            scope.launch {
                runCatching { client.jiraUpdateWatcher(id, enabled = enabled) }
                reloadAll()
            }
        },
        onPullWatcher = { id ->
            val client = api ?: return@JiraSettingsContent
            scope.launch {
                runCatching { client.jiraPullWatcher(id) }
                    .onSuccess {
                        statusMessage = "Watcher triggered"
                        reloadAll()
                    }
            }
        },
        onDeleteWatcher = { id ->
            val client = api ?: return@JiraSettingsContent
            scope.launch {
                runCatching { client.jiraDeleteWatcher(id) }
                reloadAll()
            }
        },
        onTestJql = { configId, jql ->
            val client = api ?: return@JiraSettingsContent
            scope.launch {
                runCatching { client.jiraTestJql(configId, jql) }
                    .onSuccess { res ->
                        val total = res["total"]?.int ?: 0
                        statusMessage = "JQL matched $total issue(s)"
                    }
                    .onFailure { statusMessage = it.message ?: "JQL test failed" }
            }
        },
        onDebugResult = { id ->
            val client = api ?: return@JiraSettingsContent
            scope.launch {
                runCatching { client.jiraDebugResult(id) }
                reloadAll()
            }
        },
        onResumeResult = { id ->
            val client = api ?: return@JiraSettingsContent
            scope.launch {
                runCatching { client.jiraResumeResult(id) }
                reloadAll()
            }
        },
        onCancelResult = { id ->
            val client = api ?: return@JiraSettingsContent
            scope.launch {
                runCatching { client.jiraCancelResult(id) }
                reloadAll()
            }
        },
        onMarkResultRead = { id ->
            val client = api ?: return@JiraSettingsContent
            scope.launch {
                runCatching { client.jiraMarkResultRead(id) }
                reloadAll()
            }
        },
        onDeleteResult = { id ->
            val client = api ?: return@JiraSettingsContent
            scope.launch {
                runCatching { client.jiraDeleteResult(id) }
                reloadAll()
            }
        },
    )
}

@Composable
internal fun JiraSettingsContent(
    jiraEnabled: Boolean,
    projects: List<JiraProjectOption>,
    configs: List<JiraConfigRow>,
    watchers: List<JiraWatcherRow>,
    results: List<JiraResultRow>,
    statusMessage: String? = null,
    onToggleJiraEnabled: (Boolean) -> Unit,
    onSaveConfig: (Int, String, String, String?) -> Unit,
    onTestConfig: (Int) -> Unit,
    onDeleteConfig: (Int) -> Unit,
    onCreateWatcher: (Int, String, String, String?, Int, String) -> Unit,
    onToggleWatcher: (Int, Boolean) -> Unit,
    onPullWatcher: (Int) -> Unit,
    onDeleteWatcher: (Int) -> Unit,
    onTestJql: (Int, String) -> Unit,
    onDebugResult: (Int) -> Unit,
    onResumeResult: (Int) -> Unit,
    onCancelResult: (Int) -> Unit,
    onMarkResultRead: (Int) -> Unit,
    onDeleteResult: (Int) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var selectedProjectId by remember(projects, configs) {
        mutableIntStateOf(projects.firstOrNull()?.id ?: configs.firstOrNull()?.projectId ?: 1)
    }
    var baseUrlDraft by remember { mutableStateOf("") }
    var emailDraft by remember { mutableStateOf("") }
    var tokenDraft by remember { mutableStateOf("") }

    var watcherNameDraft by remember { mutableStateOf("") }
    var watcherJqlDraft by remember { mutableStateOf("") }
    var watcherPromptDraft by remember { mutableStateOf("") }
    var watcherMode by remember { mutableStateOf("notify") }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!statusMessage.isNullOrBlank()) {
            BasicText(statusMessage, style = ppmText(11f).copy(color = tokens.accent.color))
        }

        IntegrationCard {
            SettingsToggleRow(
                title = "Enable Jira Integration",
                subtitle = "Show Jira watcher results and issue debug shortcuts in the workspace",
                checked = jiraEnabled,
                testTag = "jira-enable-toggle",
                onCheckedChange = onToggleJiraEnabled,
            )
        }

        IntegrationCard {
            SettingsSectionTitle("Jira Project Credentials (${configs.size})")
            for (cfg in configs) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        BasicText(cfg.projectName ?: "Project #${cfg.projectId}", style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text.color))
                        BasicText("${cfg.baseUrl} · ${cfg.email}", style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text3.color))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SettingsButton(label = "Test", onClick = { onTestConfig(cfg.projectId) })
                        SettingsButton(label = "Delete", danger = true, onClick = { onDeleteConfig(cfg.projectId) })
                    }
                }
            }
            Separator()
            SettingsTextInput(
                value = baseUrlDraft,
                onValueChange = { baseUrlDraft = it },
                placeholder = "https://your-org.atlassian.net",
                mono = true,
                modifier = Modifier.fillMaxWidth().testTag("jira-base-url"),
            )
            SettingsTextInput(
                value = emailDraft,
                onValueChange = { emailDraft = it },
                placeholder = "Jira account email",
                modifier = Modifier.fillMaxWidth().testTag("jira-email"),
            )
            SettingsTextInput(
                value = tokenDraft,
                onValueChange = { tokenDraft = it },
                placeholder = "Atlassian API token",
                mono = true,
                modifier = Modifier.fillMaxWidth().testTag("jira-token"),
            )
            SettingsButton(
                label = "Save Credentials",
                primary = true,
                enabled = baseUrlDraft.isNotBlank() && emailDraft.isNotBlank(),
                modifier = Modifier.testTag("jira-save-config"),
                onClick = {
                    onSaveConfig(selectedProjectId, baseUrlDraft.trim(), emailDraft.trim(), tokenDraft.trim().ifEmpty { null })
                    tokenDraft = ""
                },
            )
        }

        // Watchers
        IntegrationCard {
            SettingsSectionTitle("Issue Watchers (${watchers.size})")
            for (w in watchers) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        BasicText("${w.name} (${w.mode})", style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text.color))
                        BasicText(w.jql, style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text3.color))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SettingsButton(label = "Pull", onClick = { onPullWatcher(w.id) })
                        SettingsButton(
                            label = if (w.enabled) "Disable" else "Enable",
                            onClick = { onToggleWatcher(w.id, !w.enabled) },
                        )
                        SettingsButton(label = "Delete", danger = true, onClick = { onDeleteWatcher(w.id) })
                    }
                }
            }
            if (configs.isNotEmpty()) {
                val activeConfigId = configs.first().id
                Separator()
                SettingsTextInput(
                    value = watcherNameDraft,
                    onValueChange = { watcherNameDraft = it },
                    placeholder = "Watcher name",
                    modifier = Modifier.fillMaxWidth().testTag("jira-watcher-name"),
                )
                SettingsTextInput(
                    value = watcherJqlDraft,
                    onValueChange = { watcherJqlDraft = it },
                    placeholder = "project = PROJ AND status = 'In Progress'",
                    mono = true,
                    modifier = Modifier.fillMaxWidth().testTag("jira-watcher-jql"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsButton(
                        label = "Test JQL",
                        enabled = watcherJqlDraft.isNotBlank(),
                        onClick = { onTestJql(activeConfigId, watcherJqlDraft.trim()) },
                    )
                    SettingsButton(
                        label = "Add Watcher",
                        primary = true,
                        enabled = watcherNameDraft.isNotBlank() && watcherJqlDraft.isNotBlank(),
                        modifier = Modifier.testTag("jira-watcher-add"),
                        onClick = {
                            onCreateWatcher(
                                activeConfigId,
                                watcherNameDraft.trim(),
                                watcherJqlDraft.trim(),
                                watcherPromptDraft.trim().ifEmpty { null },
                                120_000,
                                watcherMode,
                            )
                            watcherNameDraft = ""
                            watcherJqlDraft = ""
                        },
                    )
                }
            }
        }

        // Results
        if (results.isNotEmpty()) {
            IntegrationCard {
                SettingsSectionTitle("Recent Watcher Results (${results.size})")
                for (r in results.take(10)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            BasicText("${r.issueKey} · ${r.status}", style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text.color))
                            if (!r.issueSummary.isNullOrBlank()) {
                                BasicText(r.issueSummary!!, style = ppmText(11f).copy(color = tokens.text2.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            SettingsButton(label = "Debug", onClick = { onDebugResult(r.id) })
                            if (r.status == "running") {
                                SettingsButton(label = "Cancel", onClick = { onCancelResult(r.id) })
                            } else if (r.sessionId != null) {
                                SettingsButton(label = "Resume", onClick = { onResumeResult(r.id) })
                            }
                            if (r.readAt == null) {
                                SettingsButton(label = "Read", onClick = { onMarkResultRead(r.id) })
                            }
                            SettingsButton(label = "Delete", danger = true, onClick = { onDeleteResult(r.id) })
                        }
                    }
                }
            }
        }
    }
}

// ===========================================================================
// 7. Schedules Settings Section
// ===========================================================================

@Composable
internal fun SchedulesSettingsSection(model: AppModel) {
    val api = model.api
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var schedules by remember { mutableStateOf<List<ScheduleRowState>>(emptyList()) }
    var enabledOnly by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var activeLogsSchedule by remember { mutableStateOf<ScheduleRowState?>(null) }
    var logBuffer by remember { mutableStateOf(ScheduleLogBufferState()) }

    suspend fun reload() {
        val client = api ?: return
        runCatching { parseSchedulesList(client.schedulesList(enabledOnly)) }.getOrNull()?.let { schedules = it }
    }

    LaunchedEffect(api, enabledOnly) {
        if (api == null) return@LaunchedEffect
        reload()
        while (true) {
            delay(10_000L)
            reload()
        }
    }

    // Live script log ring-buffer polling (every 2s while dialog is open)
    LaunchedEffect(api, activeLogsSchedule?.id) {
        val client = api ?: return@LaunchedEffect
        val target = activeLogsSchedule ?: return@LaunchedEffect
        logBuffer = ScheduleLogBufferState()
        while (activeLogsSchedule?.id == target.id) {
            val poll = runCatching {
                parseScheduleLogs(client.schedulesGetLogs(target.id, after = logBuffer.afterSeq))
            }.getOrNull()
            if (poll != null) {
                logBuffer = mergeScheduleLogs(logBuffer, poll)
            }
            delay(2_000L)
        }
    }

    val projectNames = remember(model.projects, model.selectedProject) {
        val names = model.projects.map { it.name }.distinct().toMutableList()
        val sel = model.selectedProject
        if (!sel.isNullOrBlank() && sel !in names) names.add(0, sel)
        if (names.isEmpty()) listOf("default") else names
    }

    SchedulesSettingsContent(
        schedules = schedules,
        projectNames = projectNames,
        enabledOnly = enabledOnly,
        statusMessage = statusMessage,
        activeLogsSchedule = activeLogsSchedule,
        logBuffer = logBuffer,
        onToggleEnabledOnly = { enabledOnly = it },
        onRunNow = { id ->
            val client = api ?: return@SchedulesSettingsContent
            scope.launch {
                runCatching { client.schedulesRunNow(id) }
                reload()
            }
        },
        onToggleScheduleEnabled = { id, enabled ->
            schedules = schedules.map { if (it.id == id) it.copy(enabled = enabled) else it }
            val client = api ?: return@SchedulesSettingsContent
            scope.launch {
                runCatching { client.schedulesUpdate(id, buildJsonObject { put("enabled", enabled) }) }
                reload()
            }
        },
        onDeleteSchedule = { id ->
            val client = api ?: return@SchedulesSettingsContent
            scope.launch {
                runCatching { client.schedulesDelete(id) }
                reload()
            }
        },
        onCreateSchedule = { payload ->
            val client = api ?: return@SchedulesSettingsContent
            scope.launch {
                runCatching { client.schedulesCreate(payload) }
                    .onSuccess {
                        statusMessage = "Schedule created"
                        reload()
                    }
                    .onFailure { statusMessage = it.message ?: "Failed to create schedule" }
            }
        },
        onExportTemplate = { id ->
            val client = api ?: return@SchedulesSettingsContent
            scope.launch {
                runCatching { client.schedulesExport(id) }
                    .onSuccess { json ->
                        copyToClipboard12c(context, "Schedule Template", PrettyJson12c.encodeToString( kotlinx.serialization.json.JsonElement.serializer(), json))
                        statusMessage = "Schedule template copied to clipboard"
                    }
            }
        },
        onImportTemplate = { rawJson, projectName ->
            val client = api ?: return@SchedulesSettingsContent
            val parsed = parseScheduleTemplateJson(rawJson).getOrElse {
                statusMessage = it.message ?: "Invalid template JSON"
                return@SchedulesSettingsContent
            }
            scope.launch {
                runCatching {
                    client.schedulesImport(
                        template = parsed.rawJson,
                        projectName = projectName,
                        trust = true,
                    )
                }.onSuccess {
                    statusMessage = "Imported '${parsed.name}'"
                    reload()
                }.onFailure { statusMessage = it.message ?: "Import failed" }
            }
        },
        onOpenLogs = { activeLogsSchedule = it },
        onCloseLogs = { activeLogsSchedule = null },
    )
}

@Composable
internal fun SchedulesSettingsContent(
    schedules: List<ScheduleRowState>,
    projectNames: List<String>,
    enabledOnly: Boolean = false,
    statusMessage: String? = null,
    activeLogsSchedule: ScheduleRowState? = null,
    logBuffer: ScheduleLogBufferState = ScheduleLogBufferState(),
    onToggleEnabledOnly: (Boolean) -> Unit,
    onRunNow: (Int) -> Unit,
    onToggleScheduleEnabled: (Int, Boolean) -> Unit,
    onDeleteSchedule: (Int) -> Unit,
    onCreateSchedule: (kotlinx.serialization.json.JsonObject) -> Unit,
    onExportTemplate: (Int) -> Unit,
    onImportTemplate: (String, String) -> Unit,
    onOpenLogs: (ScheduleRowState) -> Unit,
    onCloseLogs: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var showCreateDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!statusMessage.isNullOrBlank()) {
            BasicText(
                statusMessage,
                style = ppmText(11f).copy(color = tokens.accent.color),
                modifier = Modifier.testTag("schedules-status-msg"),
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip12c(
                    label = "All (${schedules.size})",
                    selected = !enabledOnly,
                    onClick = { onToggleEnabledOnly(false) },
                )
                FilterChip12c(
                    label = "Enabled Only",
                    selected = enabledOnly,
                    onClick = { onToggleEnabledOnly(true) },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsButton(
                    label = "Import JSON",
                    modifier = Modifier.testTag("schedules-import-btn"),
                    onClick = { showImportDialog = true },
                )
                SettingsButton(
                    label = "New Schedule",
                    primary = true,
                    modifier = Modifier.testTag("schedules-create-btn"),
                    onClick = { showCreateDialog = true },
                )
            }
        }

        if (schedules.isEmpty()) {
            IntegrationCard {
                BasicText("No scheduled tasks configured.", style = ppmText(11f).copy(color = tokens.text3.color))
            }
        } else {
            for (row in schedules) {
                IntegrationCard(Modifier.testTag("schedule-card-${row.id}")) {
                    SettingsToggleRow(
                        title = row.name,
                        subtitle = "${row.projectName} · ${row.kind}/${row.mode} · ${if (row.mode == "always-on") "daemon" else row.cron}",
                        checked = row.enabled,
                        testTag = "schedule-toggle-${row.id}",
                        onCheckedChange = { onToggleScheduleEnabled(row.id, it) },
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            "Status: ${row.status.state}" + (row.lastStatus?.let { " · Last: $it" } ?: ""),
                            style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text3.color),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SettingsButton(
                                label = if (row.mode == "always-on") "Restart" else "Run Now",
                                primary = true,
                                modifier = Modifier.testTag("schedule-run-${row.id}"),
                                onClick = { onRunNow(row.id) },
                            )
                            if (row.kind == "script") {
                                SettingsButton(
                                    label = "Logs",
                                    modifier = Modifier.testTag("schedule-logs-${row.id}"),
                                    onClick = { onOpenLogs(row) },
                                )
                            }
                            SettingsButton(
                                label = "Export",
                                onClick = { onExportTemplate(row.id) },
                            )
                            SettingsButton(
                                label = "Delete",
                                danger = true,
                                modifier = Modifier.testTag("schedule-delete-${row.id}"),
                                onClick = { onDeleteSchedule(row.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        ScheduleCreateDialog(
            projectNames = projectNames,
            onDismiss = { showCreateDialog = false },
            onSubmit = { payload ->
                showCreateDialog = false
                onCreateSchedule(payload)
            },
        )
    }

    if (showImportDialog) {
        ScheduleImportDialog(
            projectNames = projectNames,
            onDismiss = { showImportDialog = false },
            onImport = { json, proj ->
                showImportDialog = false
                onImportTemplate(json, proj)
            },
        )
    }

    if (activeLogsSchedule != null) {
        ScheduleLogsDialog(
            schedule = activeLogsSchedule,
            buffer = logBuffer,
            onDismiss = onCloseLogs,
        )
    }
}

@Composable
private fun ScheduleCreateDialog(
    projectNames: List<String>,
    onDismiss: () -> Unit,
    onSubmit: (kotlinx.serialization.json.JsonObject) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        ScheduleCreateFormContent(
            projectNames = projectNames,
            onDismiss = onDismiss,
            onSubmit = onSubmit,
        )
    }
}

@Composable
internal fun ScheduleCreateFormContent(
    projectNames: List<String>,
    onDismiss: () -> Unit,
    onSubmit: (kotlinx.serialization.json.JsonObject) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var name by remember { mutableStateOf("") }
    var projectName by remember { mutableStateOf(projectNames.firstOrNull() ?: "default") }
    var kind by remember { mutableStateOf("prompt") } // "prompt" | "script"
    var mode by remember { mutableStateOf("tick") } // "tick" | "always-on"
    var cron by remember { mutableStateOf("0 * * * *") }
    var prompt by remember { mutableStateOf("") }
    var script by remember { mutableStateOf("") }

    val problems = scheduleFormProblems(
        name = name,
        projectName = projectName,
        kind = kind,
        mode = mode,
        cron = cron,
        prompt = prompt,
        script = script,
        timeoutMs = 900_000,
    )

    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, shape)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SettingsSectionTitle("New Scheduled Task")
        SettingsTextInput(
            value = name,
            onValueChange = { name = it },
            placeholder = "Schedule name",
            modifier = Modifier.fillMaxWidth().testTag("schedule-form-name"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip12c(
                label = "Prompt",
                selected = kind == "prompt",
                testTag = "schedule-kind-prompt",
                onClick = {
                    kind = "prompt"
                    mode = "tick"
                },
            )
            FilterChip12c(
                label = "Script",
                selected = kind == "script",
                testTag = "schedule-kind-script",
                onClick = { kind = "script" },
            )
            if (kind == "script") {
                FilterChip12c(
                    label = if (mode == "always-on") "Always-On" else "Cron Tick",
                    selected = mode == "always-on",
                    onClick = { mode = if (mode == "always-on") "tick" else "always-on" },
                )
            }
        }
        if (mode != "always-on") {
            SettingsTextInput(
                value = cron,
                onValueChange = { cron = it },
                placeholder = if (kind == "script") "*/10 * * * * * (5 or 6 fields)" else "0 * * * * (5 fields)",
                mono = true,
                modifier = Modifier.fillMaxWidth().testTag("schedule-form-cron"),
            )
        }
        if (kind == "prompt") {
            SettingsTextInput(
                value = prompt,
                onValueChange = { prompt = it },
                placeholder = "Prompt to send to AI session",
                singleLine = false,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("schedule-form-prompt"),
            )
        } else {
            SettingsTextInput(
                value = script,
                onValueChange = { script = it },
                placeholder = "#!/usr/bin/env bash\necho 'Running job'",
                mono = true,
                singleLine = false,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("schedule-form-script"),
            )
        }
        if (problems.isNotEmpty()) {
            BasicText(
                problems.first(),
                style = ppmText(11f).copy(color = tokens.error.color),
                modifier = Modifier.testTag("schedule-form-error"),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SettingsButton(label = "Cancel", onClick = onDismiss)
            Spacer(Modifier.width(8.dp))
            SettingsButton(
                label = "Create",
                primary = true,
                enabled = problems.isEmpty(),
                modifier = Modifier.testTag("schedule-form-submit"),
                onClick = {
                    onSubmit(
                        buildJsonObject {
                            put("name", name.trim())
                            put("projectName", projectName)
                            put("kind", kind)
                            put("mode", mode)
                            put("cron", cron.trim())
                            if (kind == "prompt") put("prompt", prompt.trim()) else put("script", script.trim())
                            put("enabled", true)
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun ScheduleImportDialog(
    projectNames: List<String>,
    onDismiss: () -> Unit,
    onImport: (String, String) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var jsonText by remember { mutableStateOf("") }
    var projectName by remember { mutableStateOf(projectNames.firstOrNull() ?: "default") }

    Dialog(onDismissRequest = onDismiss) {
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
            SettingsSectionTitle("Import Schedule Template")
            SettingsTextInput(
                value = jsonText,
                onValueChange = { jsonText = it },
                placeholder = "{\"ppmScheduleTemplate\": 1, ...}",
                mono = true,
                singleLine = false,
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp).testTag("schedule-import-json"),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SettingsButton(label = "Cancel", onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                SettingsButton(
                    label = "Import",
                    primary = true,
                    enabled = jsonText.isNotBlank(),
                    modifier = Modifier.testTag("schedule-import-confirm"),
                    onClick = { onImport(jsonText.trim(), projectName) },
                )
            }
        }
    }
}

@Composable
private fun ScheduleLogsDialog(
    schedule: ScheduleRowState,
    buffer: ScheduleLogBufferState,
    onDismiss: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(12.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(tokens.panel.color)
                .cssBorder(tokens.border.color, shape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsSectionTitle("Logs · ${schedule.name}")
            BasicText(
                "State: ${buffer.status.state}" + (buffer.status.pid?.let { " (PID $it)" } ?: ""),
                style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text2.color),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(tokens.bgSolid.color)
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (buffer.lines.isEmpty()) {
                    BasicText("No log lines yet.", style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text3.color))
                } else {
                    for (line in buffer.lines) {
                        val color = when (line.stream) {
                            "stderr" -> tokens.error.color
                            "meta" -> tokens.accent.color
                            else -> tokens.text.color
                        }
                        BasicText(
                            line.text,
                            style = ppmText(10f, kind = FontKind.MONO).copy(color = color),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SettingsButton(label = "Close", onClick = onDismiss)
            }
        }
    }
}
