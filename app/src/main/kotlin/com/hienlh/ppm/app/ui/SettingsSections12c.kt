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
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.client.cloudGetAlias
import com.hienlh.ppm.client.cloudGetLoginUrl
import com.hienlh.ppm.client.cloudGetStatus
import com.hienlh.ppm.client.cloudLink
import com.hienlh.ppm.client.cloudLogin
import com.hienlh.ppm.client.cloudLogout
import com.hienlh.ppm.client.cloudPollDeviceCode
import com.hienlh.ppm.client.cloudRequestDeviceCode
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
import com.hienlh.ppm.state.settings.CloudDeviceCodeFlowState
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
import com.hienlh.ppm.state.settings.PushDeviceRow
import com.hienlh.ppm.state.settings.PushStatusState
import com.hienlh.ppm.state.settings.ScheduleLogBufferState
import com.hienlh.ppm.state.settings.ScheduleProjectOption
import com.hienlh.ppm.state.settings.ScheduleRowState
import com.hienlh.ppm.state.settings.TAILSCALE_POLL_MS
import com.hienlh.ppm.state.settings.TailscaleState
import com.hienlh.ppm.state.settings.TailscaleStepId
import com.hienlh.ppm.state.settings.TelegramNotifyState
import com.hienlh.ppm.state.settings.TelegramPairedChat
import com.hienlh.ppm.state.settings.applyNamedTunnelDisableResult
import com.hienlh.ppm.state.settings.applyNamedTunnelLoginSnapshot
import com.hienlh.ppm.state.settings.applyNamedTunnelSetupResult
import com.hienlh.ppm.state.settings.applyPublicTunnelEnabledResult
import com.hienlh.ppm.state.settings.applyTailscaleLoginSnapshot
import com.hienlh.ppm.state.settings.blockingTailscaleSetupStep
import com.hienlh.ppm.state.settings.buildProxyEndpointUrl
import com.hienlh.ppm.state.settings.canCancelJiraWatcherResult
import com.hienlh.ppm.state.settings.canDebugJiraWatcherResult
import com.hienlh.ppm.state.settings.canResumeJiraWatcherResult
import com.hienlh.ppm.state.settings.clampJiraIntervalMs
import com.hienlh.ppm.state.settings.currentTailscaleSetupStep
import com.hienlh.ppm.state.settings.isConnectedViaAddress
import com.hienlh.ppm.state.settings.mergeScheduleLogs
import com.hienlh.ppm.state.settings.normalizeNtfyServer
import com.hienlh.ppm.state.settings.parseCloudDeviceCodePoll
import com.hienlh.ppm.state.settings.parseCloudDeviceCodeStart
import com.hienlh.ppm.state.settings.parseCloudLoginUrl
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
import com.hienlh.ppm.state.settings.tailscaleNeedsPolling
import com.hienlh.ppm.state.settings.tailscaleServiceUrl
import com.hienlh.ppm.state.settings.validateNtfyTopic
import com.hienlh.ppm.state.settings.validateTailscaleServiceName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val PrettyJson12c = Json { prettyPrint = true }

private inline fun <T> runCatchingNonCancelled(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

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
        runCatchingNonCancelled { parseNotificationSettings(client.notificationsGetSettings()) }.getOrNull()?.let { settings = it }
        runCatchingNonCancelled { parsePushStatus(client.notificationsGetPush()) }.getOrNull()?.let { pushStatus = it }
        runCatchingNonCancelled { parseTelegramNotifyStatus(client.notificationsGetTelegram()) }.getOrNull()?.let { telegram = it }
        runCatchingNonCancelled { parseNtfyStatus(client.notificationsGetNtfy()) }.getOrNull()?.let { ntfy = it }
    }

    LaunchedEffect(api) {
        reloadAll()
    }

    LaunchedEffect(api, telegram.connectActive) {
        val client = api ?: return@LaunchedEffect
        if (!telegram.connectActive) return@LaunchedEffect
        while (telegram.connectActive) {
            delay(2_000L)
            val next = runCatchingNonCancelled { parseTelegramNotifyStatus(client.notificationsGetTelegram()) }.getOrNull() ?: break
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
                runCatchingNonCancelled {
                    parseNotificationSettings(client.notificationsPutSettings(next.toJsonPatch()))
                }.onSuccess { settings = it }
            }
        },
        onTestPushDevice = { id ->
            val client = api ?: return@NotificationsSettingsContent
            scope.launch {
                runCatchingNonCancelled { client.notificationsTestPush(id) }
                    .onSuccess { statusMessage = "Test push sent" }
                    .onFailure { statusMessage = it.message ?: "Push test failed" }
            }
        },
        onRemovePushDevice = { id, endpoint ->
            val client = api ?: return@NotificationsSettingsContent
            scope.launch {
                runCatchingNonCancelled { client.notificationsUnsubscribePush(endpoint = endpoint, id = id) }
                reloadAll()
            }
        },
        onSaveTelegramToken = { token ->
            val client = api ?: return@NotificationsSettingsContent
            scope.launch {
                runCatchingNonCancelled { client.settingsPutTelegramToken(token) }
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
                runCatchingNonCancelled { client.settingsTestTelegram() }
                    .onSuccess { statusMessage = "Telegram test notification sent" }
                    .onFailure { statusMessage = it.message ?: "Telegram test failed" }
            }
        },
        onStartTelegramConnect = {
            val client = api ?: return@NotificationsSettingsContent
            scope.launch {
                runCatchingNonCancelled { client.notificationsStartTelegramConnect() }
                    .onSuccess { res ->
                        val url = res["url"].string ?: res.obj?.get("connect")?.obj?.get("url").string
                        reloadAll()
                        if (!url.isNullOrBlank()) {
                            runCatchingNonCancelled { uriHandler.openUri(url) }
                        }
                    }
            }
        },
        onCancelTelegramConnect = {
            val client = api ?: return@NotificationsSettingsContent
            scope.launch {
                runCatchingNonCancelled { client.notificationsCancelTelegramConnect() }
                reloadAll()
            }
        },
        onRemoveTelegramChat = { chatId ->
            val client = api ?: return@NotificationsSettingsContent
            scope.launch {
                runCatchingNonCancelled { client.notificationsRemoveTelegramChat(chatId) }
                reloadAll()
            }
        },
        onSaveNtfy = { serverUrl, topic, token ->
            val client = api ?: return@NotificationsSettingsContent
            scope.launch {
                runCatchingNonCancelled { client.notificationsPutNtfy(serverUrl, topic, token) }
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
                runCatchingNonCancelled { client.notificationsTestNtfy() }
                    .onSuccess { statusMessage = "ntfy test notification sent" }
                    .onFailure { statusMessage = it.message ?: "ntfy test failed" }
            }
        },
        onRemoveNtfy = {
            val client = api ?: return@NotificationsSettingsContent
            scope.launch {
                runCatchingNonCancelled { client.notificationsDeleteNtfy() }
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
    var ntfyTokenVisible by remember { mutableStateOf(false) }
    var ntfyValidationError by remember { mutableStateOf<String?>(null) }
    var pendingRemovePushDevice by remember { mutableStateOf<PushDeviceRow?>(null) }
    var pendingRemoveTelegramChat by remember { mutableStateOf<TelegramPairedChat?>(null) }
    var confirmRemoveNtfy by remember { mutableStateOf(false) }

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
                                onClick = { pendingRemovePushDevice = dev },
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
                        modifier = Modifier.testTag("notify-telegram-remove-${chat.chatId}"),
                        onClick = { pendingRemoveTelegramChat = chat },
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
            SettingsPasswordInput(
                value = ntfyTokenDraft,
                onValueChange = { ntfyTokenDraft = it },
                placeholder = if (ntfy.tokenSet) "Access token (configured)" else "Optional access token (tk_…)",
                visible = ntfyTokenVisible,
                onToggleVisible = { ntfyTokenVisible = !ntfyTokenVisible },
                modifier = Modifier.fillMaxWidth(),
                inputTestTag = "notify-ntfy-token",
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
                        onClick = { confirmRemoveNtfy = true },
                    )
                }
            }
        }
    }

    pendingRemovePushDevice?.let { dev ->
        ConfirmDialog(
            title = "Remove push device?",
            message = "Stop sending web push notifications to ${dev.label}?",
            confirm = "Remove",
            confirmTestTag = "push-remove-confirm",
            onDismiss = { pendingRemovePushDevice = null },
            onConfirm = {
                pendingRemovePushDevice = null
                onRemovePushDevice(dev.id, dev.endpoint)
            },
        )
    }

    pendingRemoveTelegramChat?.let { chat ->
        ConfirmDialog(
            title = "Remove Telegram chat?",
            message = "Stop sending notifications to ${chat.name} (${chat.chatId})?",
            confirm = "Remove",
            confirmTestTag = "notify-telegram-remove-confirm",
            onDismiss = { pendingRemoveTelegramChat = null },
            onConfirm = {
                pendingRemoveTelegramChat = null
                onRemoveTelegramChat(chat.chatId)
            },
        )
    }

    if (confirmRemoveNtfy) {
        ConfirmDialog(
            title = "Remove ntfy configuration?",
            message = "Stop publishing notifications to ${ntfy.server}/${ntfy.topic}?",
            confirm = "Remove",
            confirmTestTag = "notify-ntfy-clear-confirm",
            onDismiss = { confirmRemoveNtfy = false },
            onConfirm = {
                confirmRemoveNtfy = false
                onRemoveNtfy()
            },
        )
    }
}

// ===========================================================================
// 2. Remote Access Section (Tailscale, Public Link, Named Tunnel)
// ===========================================================================

@Composable
internal fun RemoteAccessSettingsSection(model: AppModel) {
    val activeServerUrl: String? = model.debug?.url?.toString()
        ?: model.store.servers.value.firstOrNull { it.id == model.store.selectedId.value }?.url
    RemoteAccessSettingsSection(
        api = model.api,
        currentServerUrl = activeServerUrl,
        onRunInTerminal = { cmd -> model.runInTerminal(cmd) },
    )
}

@Composable
internal fun RemoteAccessSettingsSection(
    api: ApiClient?,
    currentServerUrl: String? = null,
    onRunInTerminal: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var tailscale by remember { mutableStateOf(TailscaleState()) }
    var publicTunnel by remember { mutableStateOf(PublicTunnelState()) }
    var namedTunnel by remember { mutableStateOf(NamedTunnelState()) }

    var tailscaleError by remember { mutableStateOf<String?>(null) }
    var tailscaleServiceConflict by remember { mutableStateOf(false) }
    var publicTunnelError by remember { mutableStateOf<String?>(null) }
    var namedTunnelError by remember { mutableStateOf<String?>(null) }

    suspend fun reloadAll() {
        val client = api ?: return
        runCatchingNonCancelled { parseTailscaleState(client.tailscaleGetState()) }.getOrNull()?.let { tailscale = it }
        runCatchingNonCancelled { parsePublicTunnelState(client.tunnelGetStatus()) }.getOrNull()?.let { publicTunnel = it }
        runCatchingNonCancelled { parseNamedTunnelState(client.namedTunnelGetStatus()) }.getOrNull()?.let { namedTunnel = it }
    }

    LaunchedEffect(api) {
        reloadAll()
    }

    // Public Link polling: every 2s while starting (`enabled && !active`), 10s otherwise (`public-link-pane.tsx:58-67`)
    val publicTunnelStarting = publicTunnel.enabled && !publicTunnel.active
    LaunchedEffect(api, publicTunnelStarting) {
        val client = api ?: return@LaunchedEffect
        val intervalMs = if (publicTunnelStarting) 2_000L else 10_000L
        while (true) {
            delay(intervalMs)
            runCatchingNonCancelled { parsePublicTunnelState(client.tunnelGetStatus()) }.getOrNull()?.let { publicTunnel = it }
        }
    }

    // Tailscale state polling every 4s while visible and `tailscaleNeedsPolling(tailscale)` (`tailscale-settings-section.tsx:34, 110`)
    val tailscalePolling = tailscaleNeedsPolling(tailscale)
    LaunchedEffect(api, tailscalePolling) {
        val client = api ?: return@LaunchedEffect
        if (!tailscalePolling) return@LaunchedEffect
        while (tailscaleNeedsPolling(tailscale)) {
            delay(TAILSCALE_POLL_MS)
            val fresh = runCatchingNonCancelled { parseTailscaleState(client.tailscaleGetState()) }.getOrNull() ?: continue
            tailscale = fresh
        }
    }

    // Named Tunnel login polling while `starting`, `waiting`, or `slow` (`use-named-tunnel-setup.ts`)
    val namedLoginActive = namedTunnel.loginState in setOf("starting", "waiting", "slow")
    LaunchedEffect(api, namedLoginActive) {
        val client = api ?: return@LaunchedEffect
        if (!namedLoginActive) return@LaunchedEffect
        while (namedTunnel.loginState in setOf("starting", "waiting", "slow")) {
            delay(2_000L)
            val fresh = runCatchingNonCancelled { parseNamedTunnelState(client.namedTunnelGetStatus()) }.getOrNull() ?: continue
            namedTunnel = fresh
        }
    }

    RemoteAccessSettingsContent(
        currentServerUrl = currentServerUrl,
        tailscale = tailscale,
        publicTunnel = publicTunnel,
        namedTunnel = namedTunnel,
        tailscaleError = tailscaleError,
        tailscaleServiceConflict = tailscaleServiceConflict,
        publicTunnelError = publicTunnelError,
        namedTunnelError = namedTunnelError,
        onRunInTerminal = onRunInTerminal,
        onOpenUrl = { url -> runCatchingNonCancelled { uriHandler.openUri(url) } },
        onStartTailscaleLogin = {
            val client = api ?: return@RemoteAccessSettingsContent
            tailscaleError = null
            scope.launch {
                runCatchingNonCancelled { applyTailscaleLoginSnapshot(tailscale, client.tailscaleStartLogin()) }
                    .onSuccess { res ->
                        tailscale = res
                        if (res.login.state == "error") {
                            tailscaleError = res.login.message ?: "Sign-in failed."
                        }
                    }
                    .onFailure { err ->
                        val msg = err.message ?: "Sign-in failed."
                        tailscaleError = msg
                        tailscale = tailscale.copy(
                            login = tailscale.login.copy(state = "error", url = null, message = msg),
                        )
                    }
            }
        },
        onCancelTailscaleLogin = {
            val client = api ?: return@RemoteAccessSettingsContent
            tailscaleError = null
            scope.launch {
                runCatchingNonCancelled { applyTailscaleLoginSnapshot(tailscale, client.tailscaleCancelLogin()) }
                    .onSuccess { tailscale = it }
                    .onFailure { err -> tailscaleError = err.message ?: "Could not cancel sign-in." }
            }
        },
        onConfigureTailscaleService = { enabled, name, replace ->
            val client = api ?: return@RemoteAccessSettingsContent
            tailscaleError = null
            scope.launch {
                runCatchingNonCancelled {
                    parseTailscaleState(client.tailscaleConfigureService(enabled = enabled, name = name, replace = replace))
                }.onSuccess {
                    tailscale = it
                    tailscaleServiceConflict = false
                }.onFailure { err ->
                    if (enabled == true && err is PpmError.Server && (err.status == 409 || err.code == "conflict")) {
                        tailscaleServiceConflict = true
                    }
                    tailscaleError = err.message ?: if (enabled == true) {
                        "Could not turn the address on."
                    } else {
                        "Could not turn the address off."
                    }
                    runCatchingNonCancelled { parseTailscaleState(client.tailscaleGetState()) }
                        .getOrNull()
                        ?.let { tailscale = it }
                }
            }
        },
        onTogglePublicTunnel = { enabled ->
            val previous = publicTunnel
            publicTunnelError = null
            publicTunnel = publicTunnel.copy(enabled = enabled)
            val client = api ?: return@RemoteAccessSettingsContent
            scope.launch {
                runCatchingNonCancelled { applyPublicTunnelEnabledResult(publicTunnel, client.tunnelSetEnabled(enabled)) }
                    .onSuccess { updated ->
                        publicTunnel = updated
                        runCatchingNonCancelled { parsePublicTunnelState(client.tunnelGetStatus()) }
                            .getOrNull()
                            ?.let { publicTunnel = it }
                    }
                    .onFailure { err ->
                        publicTunnel = previous
                        publicTunnelError = err.message ?: "Could not update public link."
                    }
            }
        },
        onStartNamedTunnelLogin = { relogin ->
            val client = api ?: return@RemoteAccessSettingsContent
            namedTunnelError = null
            scope.launch {
                runCatchingNonCancelled {
                    applyNamedTunnelLoginSnapshot(namedTunnel, client.namedTunnelStartLogin(relogin))
                }.onSuccess { res ->
                    namedTunnel = res
                    if (res.loginState == "error" && !res.loginError.isNullOrBlank()) {
                        namedTunnelError = res.loginError
                    }
                }.onFailure { err ->
                    val msg = err.message ?: "Could not start Cloudflare login"
                    namedTunnelError = msg
                    namedTunnel = namedTunnel.copy(loginState = "error", loginError = msg)
                }
            }
        },
        onCancelNamedTunnelLogin = {
            val client = api ?: return@RemoteAccessSettingsContent
            namedTunnelError = null
            scope.launch {
                runCatchingNonCancelled { applyNamedTunnelLoginSnapshot(namedTunnel, client.namedTunnelCancelLogin()) }
                    .onSuccess { namedTunnel = it }
                    .onFailure { err -> namedTunnelError = err.message ?: "Could not cancel Cloudflare login" }
            }
        },
        onSetupNamedTunnel = { hostname ->
            val client = api ?: return@RemoteAccessSettingsContent
            namedTunnelError = null
            scope.launch {
                runCatchingNonCancelled { applyNamedTunnelSetupResult(namedTunnel, client.namedTunnelSetup(hostname)) }
                    .onSuccess { updated ->
                        namedTunnel = updated
                        runCatchingNonCancelled { parseNamedTunnelState(client.namedTunnelGetStatus()) }
                            .getOrNull()
                            ?.let { namedTunnel = it }
                        runCatchingNonCancelled { parsePublicTunnelState(client.tunnelGetStatus()) }
                            .getOrNull()
                            ?.let { publicTunnel = it }
                    }
                    .onFailure { err ->
                        namedTunnelError = err.message ?: "setup failed"
                    }
            }
        },
        onDisableNamedTunnel = {
            val client = api ?: return@RemoteAccessSettingsContent
            namedTunnelError = null
            scope.launch {
                runCatchingNonCancelled { applyNamedTunnelDisableResult(namedTunnel, client.namedTunnelDisable()) }
                    .onSuccess {
                        namedTunnel = it
                        reloadAll()
                    }
                    .onFailure { err ->
                        namedTunnelError = err.message ?: "Could not switch to temporary address"
                    }
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
    tailscaleError: String? = null,
    tailscaleServiceConflict: Boolean = false,
    publicTunnelError: String? = null,
    namedTunnelError: String? = null,
    onRunInTerminal: (String) -> Unit,
    onOpenUrl: (String) -> Unit = {},
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
    val selfDnsName = tailscale.device?.dnsName
    val operatorCmd = tailscale.operatorCommand
    val connectedViaTailscale = isConnectedViaAddress(currentServerUrl, tailscale.service.url ?: selfDnsName)
    val connectedViaPublic = isConnectedViaAddress(currentServerUrl, publicTunnel.url)
    val connectedViaNamed = isConnectedViaAddress(currentServerUrl, namedTunnel.hostname)
    var confirmTurnOffTailscale by remember { mutableStateOf(false) }
    var confirmTurnOffPublic by remember { mutableStateOf(false) }
    var confirmSwitchQuickTunnel by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // 1. Tailscale Card
        IntegrationCard(Modifier.testTag("remote-tailscale-card")) {
            SettingsSectionTitle("Tailscale Private Network")
            BasicText(
                "Status: ${tailscale.backendState ?: "Unknown"}" + (tailscale.tailnet?.let { " ($it)" } ?: ""),
                style = ppmText(11f).copy(color = tokens.text2.color),
            )
            if (!selfDnsName.isNullOrBlank()) {
                val connected = isConnectedViaAddress(currentServerUrl, selfDnsName)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        "https://$selfDnsName",
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

            if (currentStep == TailscaleStepId.Operator && !operatorCmd.isNullOrBlank()) {
                BasicText(
                    operatorCmd,
                    style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text.color),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsButton(
                        label = "Run in Terminal",
                        primary = true,
                        modifier = Modifier.testTag("tailscale-run-operator-cmd"),
                        onClick = { onRunInTerminal(operatorCmd) },
                    )
                    SettingsButton(
                        label = "Copy Command",
                        onClick = { copyToClipboard12c(context, "Tailscale Operator Command", operatorCmd) },
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
                    if (tailscale.login.state == "starting" || tailscale.login.state == "waiting") {
                        SettingsButton(
                            label = "Cancel",
                            onClick = onCancelTailscaleLogin,
                        )
                    }
                }
            }

            if (!tailscale.login.url.isNullOrBlank() && (tailscale.login.state == "starting" || tailscale.login.state == "waiting")) {
                val loginUrl = tailscale.login.url!!
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(tokens.bgSolid.color)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BasicText(
                        loginUrl,
                        style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text.color),
                        modifier = Modifier.testTag("tailscale-login-url"),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsButton(
                            label = "Copy",
                            onClick = { copyToClipboard12c(context, "Tailscale Sign-In URL", loginUrl) },
                        )
                        SettingsButton(
                            label = "Open sign-in page",
                            primary = true,
                            modifier = Modifier.testTag("tailscale-open-login-url"),
                            onClick = { onOpenUrl(loginUrl) },
                        )
                    }
                }
            }

            val displayedTailscaleError = tailscaleError
                ?: tailscale.login.message?.takeIf { tailscale.login.state == "error" }
            if (!displayedTailscaleError.isNullOrBlank()) {
                BasicText(
                    displayedTailscaleError,
                    style = ppmText(11f).copy(color = tokens.error.color),
                    modifier = Modifier.testTag("tailscale-error"),
                )
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
                val isSvcEnabled = tailscale.enabled
                val hasServiceConflict = tailscaleServiceConflict || (tailscale.service.defined && !tailscale.service.pointsAtPpm)
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
                        modifier = Modifier.testTag("tailscale-service-disable"),
                        onClick = {
                            if (connectedViaTailscale) {
                                confirmTurnOffTailscale = true
                            } else {
                                onConfigureTailscaleService(false, null, null)
                            }
                        },
                    )
                }
                if (hasServiceConflict) {
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
                onCheckedChange = { next ->
                    if (!next && connectedViaPublic) {
                        confirmTurnOffPublic = true
                    } else {
                        onTogglePublicTunnel(next)
                    }
                },
            )
            if (!publicTunnelError.isNullOrBlank()) {
                BasicText(
                    publicTunnelError,
                    style = ppmText(11f).copy(color = tokens.error.color),
                    modifier = Modifier.testTag("public-tunnel-error"),
                )
            }
            if (publicTunnel.enabled && !publicTunnel.active && publicTunnel.url.isNullOrBlank()) {
                BasicText(
                    "Starting public tunnel…",
                    style = ppmText(11f).copy(color = tokens.text3.color),
                    modifier = Modifier.testTag("public-tunnel-starting"),
                )
            }
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
            val displayedNamedError = namedTunnelError ?: namedTunnel.loginError
            if (!displayedNamedError.isNullOrBlank()) {
                BasicText(
                    displayedNamedError,
                    style = ppmText(11f).copy(color = tokens.error.color),
                    modifier = Modifier.testTag("named-tunnel-error"),
                )
            }
            if (!namedTunnel.loginUrl.isNullOrBlank() && namedTunnel.loginState in setOf("starting", "waiting", "slow", "url_ready")) {
                val cfLoginUrl = namedTunnel.loginUrl!!
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(tokens.bgSolid.color)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BasicText(
                        cfLoginUrl,
                        style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text.color),
                        modifier = Modifier.testTag("named-tunnel-login-url"),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsButton(
                            label = "Copy",
                            onClick = { copyToClipboard12c(context, "Cloudflare Login URL", cfLoginUrl) },
                        )
                        SettingsButton(
                            label = "Open",
                            primary = true,
                            modifier = Modifier.testTag("named-tunnel-open-login-url"),
                            onClick = { onOpenUrl(cfLoginUrl) },
                        )
                    }
                }
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
                    modifier = Modifier.testTag("named-tunnel-login-btn"),
                    onClick = { onStartNamedTunnelLogin(namedTunnel.certState == "valid") },
                )
                if (namedTunnel.loginState in setOf("starting", "waiting", "slow", "url_ready")) {
                    SettingsButton(label = "Cancel Login", onClick = onCancelNamedTunnelLogin)
                }
                SettingsButton(
                    label = "Save Domain",
                    primary = true,
                    modifier = Modifier.testTag("named-tunnel-save"),
                    onClick = { onSetupNamedTunnel(namedHostnameDraft.trim()) },
                )
                if (namedTunnel.mode == "named") {
                    SettingsButton(
                        label = "Switch to Quick Tunnel",
                        danger = true,
                        enabled = !connectedViaNamed,
                        modifier = Modifier.testTag("named-tunnel-switch-quick"),
                        onClick = { confirmSwitchQuickTunnel = true },
                    )
                }
            }
        }
    }

    if (confirmTurnOffTailscale) {
        val activeUrl = tailscale.service.url ?: selfDnsName?.let { "https://$it" } ?: "Tailscale"
        ConfirmDialog(
            title = "Turn off the address you are using?",
            message = "This page is open at $activeUrl. Turning the address off disconnects it; reach PPM another way to turn it back on.",
            confirm = "Turn off",
            confirmTestTag = "tailscale-turn-off-confirm",
            onDismiss = { confirmTurnOffTailscale = false },
            onConfirm = {
                confirmTurnOffTailscale = false
                onConfigureTailscaleService(false, null, null)
            },
        )
    }

    if (confirmTurnOffPublic) {
        ConfirmDialog(
            title = "Turn off the link you are using?",
            message = "This page is open at ${publicTunnel.url ?: "your public link"}. Turning the link off disconnects it; reach PPM another way to turn it back on.",
            confirm = "Turn off",
            confirmTestTag = "public-tunnel-turn-off-confirm",
            onDismiss = { confirmTurnOffPublic = false },
            onConfirm = {
                confirmTurnOffPublic = false
                onTogglePublicTunnel(false)
            },
        )
    }

    if (confirmSwitchQuickTunnel) {
        val domainText = namedTunnel.hostname?.let { "https://$it" } ?: "your domain"
        ConfirmDialog(
            title = "Switch to a temporary address?",
            message = "PPM stops answering at $domainText and gets a new random address instead. Links that use your domain stop working until you set it up again.",
            confirm = "Switch",
            confirmTestTag = "named-tunnel-switch-confirm",
            onDismiss = { confirmSwitchQuickTunnel = false },
            onConfirm = {
                confirmSwitchQuickTunnel = false
                onDisableNamedTunnel()
            },
        )
    }
}

// ===========================================================================
// 3. Cloud & Share Section
// ===========================================================================

@Composable
internal fun CloudSettingsSection(model: AppModel) {
    val api = model.api
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var status by remember { mutableStateOf(CloudStatusState()) }
    var deviceCodeFlow by remember { mutableStateOf(CloudDeviceCodeFlowState()) }
    var aliasSlug by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        val client = api ?: return
        runCatchingNonCancelled { parseCloudStatus(client.cloudGetStatus()) }.getOrNull()?.let { status = it }
        runCatchingNonCancelled { client.cloudGetAlias() }.getOrNull()?.let { res ->
            aliasSlug = res["slug"].string ?: res.obj?.get("alias")?.obj?.get("slug").string
        }
    }

    LaunchedEffect(api) {
        reload()
    }

    // Poll device-code flow while `status == "waiting"` (`cloud-share-popover.tsx:125-162`)
    LaunchedEffect(api, deviceCodeFlow.deviceCode, deviceCodeFlow.status) {
        val client = api ?: return@LaunchedEffect
        if (deviceCodeFlow.status != "waiting" || deviceCodeFlow.deviceCode.isBlank()) return@LaunchedEffect
        val activeFlow = deviceCodeFlow
        val intervalMs = (activeFlow.intervalSec.coerceAtLeast(1)) * 1_000L
        val deadlineMs = System.currentTimeMillis() + (activeFlow.expiresInSec.coerceAtLeast(10)) * 1_000L
        while (deviceCodeFlow.status == "waiting" && deviceCodeFlow.deviceCode == activeFlow.deviceCode) {
            delay(intervalMs)
            if (System.currentTimeMillis() > deadlineMs) {
                deviceCodeFlow = deviceCodeFlow.copy(status = "expired", error = "Device code expired. Please try again.")
                break
            }
            val poll = runCatchingNonCancelled {
                parseCloudDeviceCodePoll(client.cloudPollDeviceCode(activeFlow.cloudUrl, activeFlow.deviceCode))
            }.getOrNull() ?: continue
            when (poll.status) {
                "approved" -> {
                    val token = poll.accessToken
                    val email = poll.email
                    if (!token.isNullOrBlank() && !email.isNullOrBlank()) {
                        runCatchingNonCancelled {
                            client.cloudLogin(accessToken = token, email = email, cloudUrl = activeFlow.cloudUrl)
                        }.onSuccess {
                            deviceCodeFlow = CloudDeviceCodeFlowState()
                            statusMessage = "Signed in to PPM Cloud"
                            reload()
                        }.onFailure { err ->
                            deviceCodeFlow = deviceCodeFlow.copy(
                                status = "error",
                                error = err.message ?: "Cloud login failed",
                            )
                        }
                    } else {
                        deviceCodeFlow = deviceCodeFlow.copy(
                            status = "error",
                            error = "Cloud sign-in did not return credentials",
                        )
                    }
                    break
                }
                "expired" -> {
                    deviceCodeFlow = deviceCodeFlow.copy(
                        status = "expired",
                        error = poll.error ?: "Device code expired. Please try again.",
                    )
                    break
                }
                "error" -> {
                    deviceCodeFlow = deviceCodeFlow.copy(
                        status = "error",
                        error = poll.error ?: "Cloud sign-in failed",
                    )
                    break
                }
            }
        }
    }

    CloudSettingsContent(
        status = status,
        deviceCodeFlow = deviceCodeFlow,
        aliasSlug = aliasSlug,
        statusMessage = statusMessage,
        onStartDeviceLogin = {
            val client = api ?: return@CloudSettingsContent
            deviceCodeFlow = CloudDeviceCodeFlowState(status = "starting")
            scope.launch {
                val cloudUrl = runCatchingNonCancelled { parseCloudLoginUrl(client.cloudGetLoginUrl()) }
                    .getOrDefault(status.cloudUrl.ifBlank { "https://ppm.bot" })
                runCatchingNonCancelled {
                    parseCloudDeviceCodeStart(client.cloudRequestDeviceCode(cloudUrl), cloudUrl)
                }.onSuccess { started ->
                    deviceCodeFlow = started
                }.onFailure { err ->
                    deviceCodeFlow = CloudDeviceCodeFlowState(
                        cloudUrl = cloudUrl,
                        status = "error",
                        error = err.message ?: "Failed to start device code sign-in",
                    )
                }
            }
        },
        onCancelDeviceLogin = {
            deviceCodeFlow = CloudDeviceCodeFlowState()
        },
        onLogout = {
            val client = api ?: return@CloudSettingsContent
            val wasLinked = status.linked
            scope.launch {
                if (wasLinked) {
                    runCatchingNonCancelled { client.cloudUnlink() }
                }
                runCatchingNonCancelled { client.cloudLogout() }
                reload()
            }
        },
        onLinkDevice = { name ->
            val client = api ?: return@CloudSettingsContent
            scope.launch {
                runCatchingNonCancelled { client.cloudLink(name) }
                reload()
            }
        },
        onUnlinkDevice = {
            val client = api ?: return@CloudSettingsContent
            scope.launch {
                runCatchingNonCancelled { client.cloudUnlink() }
                reload()
            }
        },
        onSaveAlias = { slugOrNull ->
            val client = api ?: return@CloudSettingsContent
            scope.launch {
                runCatchingNonCancelled { client.cloudSetAlias(slugOrNull) }
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
    deviceCodeFlow: CloudDeviceCodeFlowState = CloudDeviceCodeFlowState(),
    aliasSlug: String?,
    statusMessage: String? = null,
    onStartDeviceLogin: () -> Unit = {},
    onCancelDeviceLogin: () -> Unit = {},
    onLogout: () -> Unit,
    onLinkDevice: (String?) -> Unit,
    onUnlinkDevice: () -> Unit,
    onSaveAlias: (String?) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var deviceNameDraft by remember(status.deviceName) { mutableStateOf(status.deviceName.orEmpty()) }
    var aliasDraft by remember(aliasSlug) { mutableStateOf(aliasSlug.orEmpty()) }
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmUnlink by remember { mutableStateOf(false) }

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
                        onClick = { confirmLogout = true },
                    )
                }
            } else {
                BasicText(
                    "Sign in to PPM Cloud (${status.cloudUrl}) using a browser device code.",
                    style = ppmText(11f).copy(color = tokens.text2.color),
                )
                if (deviceCodeFlow.status == "waiting" && deviceCodeFlow.userCode.isNotBlank()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(tokens.bgSolid.color)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BasicText("Enter this code on the verification page:", style = ppmText(11f).copy(color = tokens.text2.color))
                        BasicText(
                            deviceCodeFlow.userCode,
                            style = ppmText(16f, FontWeight.Bold, FontKind.MONO).copy(color = tokens.accent.color),
                            modifier = Modifier.testTag("cloud-device-user-code"),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingsButton(
                                label = "Copy Code",
                                onClick = { copySensitiveClip(context, "PPM Cloud Device Code", deviceCodeFlow.userCode) },
                            )
                            if (deviceCodeFlow.verificationUri.isNotBlank()) {
                                SettingsButton(
                                    label = "Open Verification Page",
                                    primary = true,
                                    modifier = Modifier.testTag("cloud-device-open-url"),
                                    onClick = { runCatchingNonCancelled { uriHandler.openUri(deviceCodeFlow.verificationUri) } },
                                )
                            }
                            SettingsButton(
                                label = "Cancel",
                                onClick = onCancelDeviceLogin,
                            )
                        }
                    }
                } else {
                    deviceCodeFlow.error?.let { errText ->
                        BasicText(
                            errText,
                            style = ppmText(11f).copy(color = tokens.error.color),
                            modifier = Modifier.testTag("cloud-device-error"),
                        )
                    }
                    SettingsButton(
                        label = if (deviceCodeFlow.status == "starting") "Requesting Code…" else "Sign In with Device Code",
                        primary = true,
                        enabled = deviceCodeFlow.status != "starting",
                        modifier = Modifier.testTag("cloud-login-btn"),
                        onClick = onStartDeviceLogin,
                    )
                }
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
                        onClick = { confirmUnlink = true },
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

    if (confirmLogout) {
        ConfirmDialog(
            title = "Sign out from PPM Cloud?",
            message = if (status.linked) "This will also unlink your device." else "Sign out from your PPM Cloud account on this server.",
            confirm = "Sign Out",
            confirmTestTag = "cloud-logout-confirm",
            onDismiss = { confirmLogout = false },
            onConfirm = {
                confirmLogout = false
                onLogout()
            },
        )
    }

    if (confirmUnlink) {
        ConfirmDialog(
            title = "Unlink this device from PPM Cloud?",
            message = "It will no longer appear on your cloud dashboard.",
            confirm = "Unlink",
            confirmTestTag = "cloud-unlink-confirm",
            onDismiss = { confirmUnlink = false },
            onConfirm = {
                confirmUnlink = false
                onUnlinkDevice()
            },
        )
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
        runCatchingNonCancelled { parseProxySettings(api.settingsGetProxy()) }.getOrNull()?.let { state = it }
    }

    ProxySettingsContent(
        state = state,
        onToggleEnabled = { enabled ->
            state = state.copy(enabled = enabled)
            val client = api ?: return@ProxySettingsContent
            scope.launch {
                runCatchingNonCancelled { parseProxySettings(client.settingsPutProxy(enabled = enabled)) }.getOrNull()?.let { state = it }
            }
        },
        onGenerateKey = {
            val client = api ?: return@ProxySettingsContent
            scope.launch {
                runCatchingNonCancelled { parseProxySettings(client.settingsPutProxy(generateKey = true)) }.getOrNull()?.let { state = it }
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
                            modifier = Modifier.testTag("proxy-copy-key"),
                            onClick = { copySensitiveClip(context, "Proxy Auth Key", state.authKey) },
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
                modifier = Modifier.testTag("proxy-copy-env"),
                onClick = { copySensitiveClip(context, "Proxy Env Snippet", envSnippet) },
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
        runCatchingNonCancelled { parseExtensionsList(client.extensionsList()) }.getOrNull()?.let { extensions = it }
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
                runCatchingNonCancelled { client.extensionsInstall(pkg) }
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
                runCatchingNonCancelled { client.extensionsDevLink(path) }
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
                runCatchingNonCancelled { client.extensionsSetEnabled(id, enabled) }
                reload()
            }
        },
        onRemove = { id ->
            val client = api ?: return@ExtensionsSettingsContent
            scope.launch {
                runCatchingNonCancelled { client.extensionsRemove(id) }
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
        val loadedProjects = runCatchingNonCancelled { parseJiraProjectOptions(client.jiraListConfigProjects()) }.getOrDefault(emptyList())
        projects = loadedProjects
        val loadedConfigs = runCatchingNonCancelled { parseJiraConfigs(client.jiraListConfigs()) }.getOrDefault(emptyList())
        configs = loadedConfigs
        val allWatchers = mutableListOf<JiraWatcherRow>()
        for (cfg in loadedConfigs) {
            val w = runCatchingNonCancelled { parseJiraWatchers(client.jiraListWatchers(cfg.id)) }.getOrDefault(emptyList())
            allWatchers.addAll(w)
        }
        watchers = allWatchers
        results = runCatchingNonCancelled { parseJiraResults(client.jiraListResults()) }.getOrDefault(emptyList())
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
                runCatchingNonCancelled { client.jiraPutConfig(projectId, baseUrl, email, token) }
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
                runCatchingNonCancelled { client.jiraTestConfig(projectId) }
                    .onSuccess { statusMessage = "Jira connection verified" }
                    .onFailure { statusMessage = it.message ?: "Jira connection failed" }
            }
        },
        onDeleteConfig = { projectId ->
            val client = api ?: return@JiraSettingsContent
            scope.launch {
                runCatchingNonCancelled { client.jiraDeleteConfig(projectId) }
                reloadAll()
            }
        },
        onCreateWatcher = { configId, name, jql, prompt, intervalMs, mode ->
            val client = api ?: return@JiraSettingsContent
            scope.launch {
                runCatchingNonCancelled {
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
                runCatchingNonCancelled { client.jiraUpdateWatcher(id, enabled = enabled) }
                reloadAll()
            }
        },
        onPullWatcher = { id ->
            val client = api ?: return@JiraSettingsContent
            scope.launch {
                runCatchingNonCancelled { client.jiraPullWatcher(id) }
                    .onSuccess {
                        statusMessage = "Watcher triggered"
                        reloadAll()
                    }
            }
        },
        onDeleteWatcher = { id ->
            val client = api ?: return@JiraSettingsContent
            scope.launch {
                runCatchingNonCancelled { client.jiraDeleteWatcher(id) }
                reloadAll()
            }
        },
        onTestJql = { configId, jql ->
            val client = api ?: return@JiraSettingsContent
            scope.launch {
                runCatchingNonCancelled { client.jiraTestJql(configId, jql) }
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
                runCatchingNonCancelled { client.jiraDebugResult(id) }
                reloadAll()
            }
        },
        onResumeResult = { id ->
            val client = api ?: return@JiraSettingsContent
            scope.launch {
                runCatchingNonCancelled { client.jiraResumeResult(id) }
                reloadAll()
            }
        },
        onCancelResult = { id ->
            val client = api ?: return@JiraSettingsContent
            scope.launch {
                runCatchingNonCancelled { client.jiraCancelResult(id) }
                reloadAll()
            }
        },
        onMarkResultRead = { id ->
            val client = api ?: return@JiraSettingsContent
            scope.launch {
                runCatchingNonCancelled { client.jiraMarkResultRead(id) }
                reloadAll()
            }
        },
        onDeleteResult = { id ->
            val client = api ?: return@JiraSettingsContent
            scope.launch {
                runCatchingNonCancelled { client.jiraDeleteResult(id) }
                reloadAll()
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
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
    var selectedWatcherConfigId by remember(configs) {
        mutableIntStateOf(configs.firstOrNull()?.id ?: 0)
    }
    var baseUrlDraft by remember { mutableStateOf("") }
    var emailDraft by remember { mutableStateOf("") }
    var tokenDraft by remember { mutableStateOf("") }
    var tokenVisible by remember { mutableStateOf(false) }
    var pendingDeleteConfig by remember { mutableStateOf<JiraConfigRow?>(null) }

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
            if (projects.isNotEmpty()) {
                BasicText("Target Project", style = ppmText(11f, FontWeight.Medium).copy(color = tokens.text2.color))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (proj in projects) {
                        FilterChip12c(
                            label = proj.name,
                            selected = selectedProjectId == proj.id,
                            testTag = "jira-project-${proj.id}",
                            onClick = {
                                selectedProjectId = proj.id
                                val existing = configs.firstOrNull { it.projectId == proj.id }
                                if (existing != null) {
                                    baseUrlDraft = existing.baseUrl
                                    emailDraft = existing.email
                                }
                            },
                        )
                    }
                }
            }
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
                        SettingsButton(
                            label = "Delete",
                            danger = true,
                            modifier = Modifier.testTag("jira-delete-config-${cfg.projectId}"),
                            onClick = { pendingDeleteConfig = cfg },
                        )
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
            SettingsPasswordInput(
                value = tokenDraft,
                onValueChange = { tokenDraft = it },
                placeholder = "Atlassian API token",
                visible = tokenVisible,
                onToggleVisible = { tokenVisible = !tokenVisible },
                modifier = Modifier.fillMaxWidth(),
                inputTestTag = "jira-token",
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
                val activeConfigId = configs.firstOrNull { it.id == selectedWatcherConfigId }?.id ?: configs.first().id
                Separator()
                if (configs.size > 1) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (cfg in configs) {
                            FilterChip12c(
                                label = cfg.projectName ?: "Config #${cfg.id}",
                                selected = activeConfigId == cfg.id,
                                testTag = "jira-watcher-config-${cfg.id}",
                                onClick = { selectedWatcherConfigId = cfg.id },
                            )
                        }
                    }
                }
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
                            if (canDebugJiraWatcherResult(r.status, r.sessionId)) {
                                SettingsButton(
                                    label = "Debug",
                                    modifier = Modifier.testTag("jira-result-debug-${r.id}"),
                                    onClick = { onDebugResult(r.id) },
                                )
                            }
                            if (canCancelJiraWatcherResult(r.status)) {
                                SettingsButton(
                                    label = "Cancel",
                                    modifier = Modifier.testTag("jira-result-cancel-${r.id}"),
                                    onClick = { onCancelResult(r.id) },
                                )
                            }
                            if (canResumeJiraWatcherResult(r.status, r.sessionId)) {
                                SettingsButton(
                                    label = "Resume",
                                    modifier = Modifier.testTag("jira-result-resume-${r.id}"),
                                    onClick = { onResumeResult(r.id) },
                                )
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

    pendingDeleteConfig?.let { cfg ->
        ConfirmDialog(
            title = "Delete Jira credentials?",
            message = "Remove Jira credentials for ${cfg.projectName ?: "Project #${cfg.projectId}"}?",
            confirm = "Delete",
            confirmTestTag = "jira-delete-config-confirm",
            onDismiss = { pendingDeleteConfig = null },
            onConfirm = {
                pendingDeleteConfig = null
                onDeleteConfig(cfg.projectId)
            },
        )
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
        runCatchingNonCancelled { parseSchedulesList(client.schedulesList(enabledOnly)) }.getOrNull()?.let { schedules = it }
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
            val poll = runCatchingNonCancelled {
                parseScheduleLogs(client.schedulesGetLogs(target.id, after = logBuffer.afterSeq))
            }.getOrNull()
            if (poll != null) {
                logBuffer = mergeScheduleLogs(logBuffer, poll)
            }
            delay(2_000L)
        }
    }

    val projects = remember(model.projects, model.selectedProject) {
        val list = model.projects.map { ScheduleProjectOption(name = it.name, path = it.path) }.toMutableList()
        val sel = model.selectedProject
        if (!sel.isNullOrBlank() && list.none { it.name == sel }) {
            list.add(0, ScheduleProjectOption(name = sel, path = sel))
        }
        list.toList()
    }

    SchedulesSettingsContent(
        schedules = schedules,
        projects = projects,
        enabledOnly = enabledOnly,
        statusMessage = statusMessage,
        activeLogsSchedule = activeLogsSchedule,
        logBuffer = logBuffer,
        onToggleEnabledOnly = { enabledOnly = it },
        onRunNow = { id ->
            val client = api ?: return@SchedulesSettingsContent
            scope.launch {
                runCatchingNonCancelled { client.schedulesRunNow(id) }
                reload()
            }
        },
        onToggleScheduleEnabled = { id, enabled ->
            schedules = schedules.map { if (it.id == id) it.copy(enabled = enabled) else it }
            val client = api ?: return@SchedulesSettingsContent
            scope.launch {
                runCatchingNonCancelled { client.schedulesUpdate(id, buildJsonObject { put("enabled", enabled) }) }
                reload()
            }
        },
        onDeleteSchedule = { id ->
            val client = api ?: return@SchedulesSettingsContent
            scope.launch {
                runCatchingNonCancelled { client.schedulesDelete(id) }
                reload()
            }
        },
        onCreateSchedule = { payload ->
            val client = api ?: return@SchedulesSettingsContent
            scope.launch {
                runCatchingNonCancelled { client.schedulesCreate(payload) }
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
                runCatchingNonCancelled { client.schedulesExport(id) }
                    .onSuccess { json ->
                        val templateJson = json.obj?.get("template") ?: json
                        copyToClipboard12c(
                            context,
                            "Schedule Template",
                            PrettyJson12c.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), templateJson),
                        )
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
                runCatchingNonCancelled {
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
        onImportTemplateWithTrust = { rawJson, projectName, trust ->
            val client = api ?: return@SchedulesSettingsContent
            val parsed = parseScheduleTemplateJson(rawJson).getOrElse {
                statusMessage = it.message ?: "Invalid template JSON"
                return@SchedulesSettingsContent
            }
            scope.launch {
                runCatchingNonCancelled {
                    client.schedulesImport(
                        template = parsed.rawJson,
                        projectName = projectName,
                        trust = trust,
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
    projects: List<ScheduleProjectOption>,
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
    onImportTemplateWithTrust: (String, String, Boolean) -> Unit = { raw, proj, _ -> onImportTemplate(raw, proj) },
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
                val projectDisplay = projects.firstOrNull { it.path == row.projectPath }?.name
                    ?: row.projectPath.substringAfterLast('/').ifBlank { row.projectPath }
                IntegrationCard(Modifier.testTag("schedule-card-${row.id}")) {
                    SettingsToggleRow(
                        title = row.name,
                        subtitle = "$projectDisplay · ${row.kind}/${row.mode} · ${if (row.mode == "always-on") "daemon" else row.cronExpr}",
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
                            buildString {
                                append("Status: ${row.status.state}")
                                row.lastRunAt?.let { append(" · Last: $it") }
                                row.nextFireAt?.let { append(" · Next: $it") }
                            },
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
            projects = projects,
            onDismiss = { showCreateDialog = false },
            onSubmit = { payload ->
                showCreateDialog = false
                onCreateSchedule(payload)
            },
        )
    }

    if (showImportDialog) {
        ScheduleImportDialog(
            projects = projects,
            onDismiss = { showImportDialog = false },
            onImport = { json, proj, trust ->
                showImportDialog = false
                onImportTemplateWithTrust(json, proj, trust)
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
    projects: List<ScheduleProjectOption>,
    onDismiss: () -> Unit,
    onSubmit: (kotlinx.serialization.json.JsonObject) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        ScheduleCreateFormContent(
            projects = projects,
            onDismiss = onDismiss,
            onSubmit = onSubmit,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScheduleCreateFormContent(
    projects: List<ScheduleProjectOption>,
    onDismiss: () -> Unit,
    onSubmit: (kotlinx.serialization.json.JsonObject) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var name by remember { mutableStateOf("") }
    var selectedProjectPath by remember(projects) { mutableStateOf(projects.firstOrNull()?.path.orEmpty()) }
    var kind by remember { mutableStateOf("prompt") } // "prompt" | "script"
    var mode by remember { mutableStateOf("tick") } // "tick" | "always-on"
    var cron by remember { mutableStateOf("0 * * * *") }
    var prompt by remember { mutableStateOf("") }
    var script by remember { mutableStateOf("") }

    val problems = scheduleFormProblems(
        name = name,
        projectPath = selectedProjectPath,
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
        if (projects.isNotEmpty()) {
            BasicText("Project", style = ppmText(11f, FontWeight.Medium).copy(color = tokens.text2.color))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (proj in projects) {
                    FilterChip12c(
                        label = proj.name,
                        selected = selectedProjectPath == proj.path,
                        testTag = "schedule-project-${proj.name}",
                        onClick = { selectedProjectPath = proj.path },
                    )
                }
            }
        }
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
                            put("project_path", selectedProjectPath)
                            put("kind", kind)
                            put("mode", mode)
                            put("cron_expr", cron.trim())
                            put("timeout_ms", 900_000)
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
    projects: List<ScheduleProjectOption>,
    onDismiss: () -> Unit,
    onImport: (String, String, Boolean) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        ScheduleImportFormContent(
            projects = projects,
            onDismiss = onDismiss,
            onImport = onImport,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScheduleImportFormContent(
    projects: List<ScheduleProjectOption>,
    onDismiss: () -> Unit,
    onImport: (String, String, Boolean) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var jsonText by remember { mutableStateOf("") }
    var projectName by remember(projects) { mutableStateOf(projects.firstOrNull()?.name ?: "default") }
    var trusted by remember { mutableStateOf(false) }

    val parsedResult = remember(jsonText) {
        if (jsonText.isBlank()) null else parseScheduleTemplateJson(jsonText)
    }
    val parsed = parsedResult?.getOrNull()
    val parseError = parsedResult?.exceptionOrNull()?.message

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
        SettingsSectionTitle("Import Schedule Template")
        if (projects.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (proj in projects) {
                    FilterChip12c(
                        label = proj.name,
                        selected = projectName == proj.name,
                        onClick = { projectName = proj.name },
                    )
                }
            }
        }
        SettingsTextInput(
            value = jsonText,
            onValueChange = {
                jsonText = it
                trusted = false
            },
            placeholder = "{\"ppmScheduleTemplate\": 1, ...}",
            mono = true,
            singleLine = false,
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp).testTag("schedule-import-json"),
        )
        if (!parseError.isNullOrBlank()) {
            BasicText(
                parseError,
                style = ppmText(11f).copy(color = tokens.error.color),
                modifier = Modifier.testTag("schedule-import-error"),
            )
        }
        if (parsed != null) {
            val codePreview = parsed.script ?: parsed.prompt.orEmpty()
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(tokens.bgSolid.color)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                BasicText(
                    "${parsed.name} (${parsed.kind}/${parsed.mode})",
                    style = ppmText(11f, FontWeight.SemiBold).copy(color = tokens.text.color),
                )
                BasicText(
                    codePreview,
                    style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.text2.color),
                    modifier = Modifier.testTag("schedule-import-preview"),
                )
            }
        }
        SettingsToggleRow(
            title = "I have read this code and trust it to run on this machine",
            subtitle = "Schedules run commands or AI sessions on the host with your server's permissions.",
            checked = trusted,
            testTag = "schedule-import-trust",
            onCheckedChange = { trusted = it },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SettingsButton(label = "Cancel", onClick = onDismiss)
            Spacer(Modifier.width(8.dp))
            SettingsButton(
                label = "Import",
                primary = true,
                enabled = parsed != null && trusted,
                modifier = Modifier.testTag("schedule-import-confirm"),
                onClick = { onImport(jsonText.trim(), projectName, trusted) },
            )
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
