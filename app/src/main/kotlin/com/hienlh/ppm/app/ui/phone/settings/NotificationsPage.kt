package com.hienlh.ppm.app.ui.phone.settings

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.push.PushDeviceManager
import com.hienlh.ppm.app.ui.Separator
import com.hienlh.ppm.app.ui.SettingsPasswordInput
import com.hienlh.ppm.app.ui.SettingsTextInput
import com.hienlh.ppm.app.ui.SettingsToggleRow
import com.hienlh.ppm.app.ui.PushThisDevice
import com.hienlh.ppm.app.ui.pushViaSubtitle
import com.hienlh.ppm.app.ui.rememberPushThisDevice
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
import com.hienlh.ppm.client.settingsPutTelegramToken
import com.hienlh.ppm.client.settingsTestTelegram
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.kit.ListRow
import com.hienlh.ppm.design.kit.Sheet
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.settings.NOTIFICATION_DELAY_OPTIONS
import com.hienlh.ppm.state.settings.NOTIFICATION_EVENT_DEFS
import com.hienlh.ppm.state.settings.NotificationSettingsState
import com.hienlh.ppm.state.settings.NtfyStatusState
import com.hienlh.ppm.state.settings.PushStatusState
import com.hienlh.ppm.state.settings.TelegramNotifyState
import com.hienlh.ppm.state.settings.normalizeNtfyServer
import com.hienlh.ppm.state.settings.parseNotificationSettings
import com.hienlh.ppm.state.settings.parseNtfyStatus
import com.hienlh.ppm.state.settings.parsePushStatus
import com.hienlh.ppm.state.settings.parseTelegramNotifyStatus
import com.hienlh.ppm.state.settings.validateNtfyTopic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Checks whether system notifications are enabled for this app on Android.
 */
internal fun areSystemNotificationsEnabled(context: Context): Boolean {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    return nm?.areNotificationsEnabled() ?: true
}

/**
 * Opens Android's app notification settings screen for this package.
 */
internal fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

@Composable
fun NotificationsPage(
    model: AppModel,
    onBack: () -> Unit,
    notificationsEnabledOverride: Boolean? = null,
    distributorAvailableOverride: Boolean? = null,
    onOpenSystemNotificationSettings: (() -> Unit)? = null,
) {
    val api = model.api
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    val selectedServerId by model.store.selectedId.collectAsState()
    val servers by model.store.servers.collectAsState()
    val activeServer = servers.firstOrNull { it.id == selectedServerId }
    val pushInstance = PushDeviceManager.normalizeInstance(selectedServerId)
    val push = rememberPushThisDevice(
        api = api,
        instance = pushInstance,
        serverUrl = activeServer?.url,
        notificationsEnabledOverride = notificationsEnabledOverride,
        distributorAvailableOverride = distributorAvailableOverride,
    )

    var settings by remember { mutableStateOf(NotificationSettingsState()) }
    var pushStatus by remember { mutableStateOf(PushStatusState()) }
    var telegram by remember { mutableStateOf(TelegramNotifyState()) }
    var ntfy by remember { mutableStateOf(NtfyStatusState()) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val deviceNotificationsEnabled = notificationsEnabledOverride ?: push.permissionGranted

    suspend fun reloadAll() {
        val client = api ?: return
        try {
            runCatching { parseNotificationSettings(client.notificationsGetSettings()) }.getOrNull()?.let { settings = it }
            runCatching { parsePushStatus(client.notificationsGetPush()) }.getOrNull()?.let { pushStatus = it }
            runCatching { parseTelegramNotifyStatus(client.notificationsGetTelegram()) }.getOrNull()?.let { telegram = it }
            runCatching { parseNtfyStatus(client.notificationsGetNtfy()) }.getOrNull()?.let { ntfy = it }
        } catch (e: CancellationException) {
            throw e
        }
    }

    LaunchedEffect(api) {
        reloadAll()
    }

    LaunchedEffect(push) {
        push.resolveTransport()
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalPpmTokens.current.bgSolid.color),
    ) {
        PhoneSettingsSubPageHeader(
            title = "Notifications",
            onBack = onBack,
            trailingAction = null,
        )
        PhoneNotificationsContent(
            settings = settings,
            pushStatus = pushStatus,
            telegram = telegram,
            ntfy = ntfy,
            notificationsEnabledOnDevice = deviceNotificationsEnabled,
            notificationPermissionGranted = push.permissionGranted,
            distributorAvailable = push.available,
            distributorName = push.viaName,
            firebaseInUseElsewhere = push.firebaseInUseElsewhere,
            pushEnabledOnThisDevice = push.enabledOnThisDevice,
            statusMessage = statusMessage,
            onOpenSystemNotificationSettings = onOpenSystemNotificationSettings ?: {
                openAppNotificationSettings(context)
            },
            onRequestNotificationPermission = push::requestPermission,
            onTogglePushOnDevice = { enabled ->
                if (enabled) {
                    scope.launch {
                        statusMessage = push.switchOn(pushStatus.publicKey)
                        reloadAll()
                    }
                } else {
                    scope.launch {
                        statusMessage = push.disable()
                        reloadAll()
                    }
                }
            },
            onUpdateSettings = { next ->
                settings = next
                val client = api ?: return@PhoneNotificationsContent
                scope.launch {
                    runCatching {
                        parseNotificationSettings(client.notificationsPutSettings(next.toJsonPatch()))
                    }.onSuccess { settings = it }
                }
            },
            onTestPush = {
                val client = api ?: return@PhoneNotificationsContent
                val devId = push.refreshSavedDeviceId()
                    ?: pushStatus.devices.firstOrNull()?.id
                scope.launch {
                    runCatching { client.notificationsTestPush(devId) }
                        .onSuccess { statusMessage = "Test push sent" }
                        .onFailure { statusMessage = it.message ?: "Push test failed" }
                }
            },
            onSaveTelegramToken = { token ->
                val client = api ?: return@PhoneNotificationsContent
                scope.launch {
                    runCatching { client.settingsPutTelegramToken(token) }
                        .onSuccess {
                            statusMessage = "Telegram bot token saved"
                            reloadAll()
                        }
                        .onFailure { statusMessage = it.message ?: "Failed to save Telegram token" }
                }
            },
            onStartTelegramConnect = {
                val client = api ?: return@PhoneNotificationsContent
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
                val client = api ?: return@PhoneNotificationsContent
                scope.launch {
                    runCatching { client.notificationsCancelTelegramConnect() }
                    reloadAll()
                }
            },
            onTestTelegram = {
                val client = api ?: return@PhoneNotificationsContent
                scope.launch {
                    runCatching { client.settingsTestTelegram() }
                        .onSuccess { statusMessage = "Telegram test notification sent" }
                        .onFailure { statusMessage = it.message ?: "Telegram test failed" }
                }
            },
            onRemoveTelegramChat = { chatId ->
                val client = api ?: return@PhoneNotificationsContent
                scope.launch {
                    runCatching { client.notificationsRemoveTelegramChat(chatId) }
                    reloadAll()
                }
            },
            onSaveNtfy = { serverUrl, topic, token ->
                val client = api ?: return@PhoneNotificationsContent
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
                val client = api ?: return@PhoneNotificationsContent
                scope.launch {
                    runCatching { client.notificationsTestNtfy() }
                        .onSuccess { statusMessage = "ntfy test notification sent" }
                        .onFailure { statusMessage = it.message ?: "ntfy test failed" }
                }
            },
            onRemoveNtfy = {
                val client = api ?: return@PhoneNotificationsContent
                scope.launch {
                    runCatching { client.notificationsDeleteNtfy() }
                    reloadAll()
                }
            },
        )
    }
}

@Composable
fun PhoneNotificationsContent(
    settings: NotificationSettingsState,
    pushStatus: PushStatusState = PushStatusState(),
    telegram: TelegramNotifyState = TelegramNotifyState(),
    ntfy: NtfyStatusState = NtfyStatusState(),
    notificationsEnabledOnDevice: Boolean = true,
    notificationPermissionGranted: Boolean = notificationsEnabledOnDevice,
    distributorAvailable: Boolean = true,
    distributorName: String? = null,
    firebaseInUseElsewhere: Boolean = false,
    pushEnabledOnThisDevice: Boolean? = null,
    statusMessage: String? = null,
    onOpenSystemNotificationSettings: () -> Unit = {},
    onRequestNotificationPermission: ((Boolean) -> Unit) -> Unit = { callback ->
        callback(notificationPermissionGranted && notificationsEnabledOnDevice)
    },
    onTogglePushOnDevice: (Boolean) -> Unit = {},
    onUpdateSettings: (NotificationSettingsState) -> Unit,
    onTestPush: () -> Unit = {},
    onSaveTelegramToken: (String) -> Unit = {},
    onStartTelegramConnect: () -> Unit = {},
    onCancelTelegramConnect: () -> Unit = {},
    onTestTelegram: () -> Unit = {},
    onRemoveTelegramChat: (String) -> Unit = {},
    onSaveNtfy: (serverUrl: String, topic: String, token: String?) -> Unit = { _, _, _ -> },
    onTestNtfy: () -> Unit = {},
    onRemoveNtfy: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    var delaySheetOpen by remember { mutableStateOf(false) }
    var telegramTokenSheetOpen by remember { mutableStateOf(false) }
    var telegramTokenDraft by remember { mutableStateOf("") }
    var telegramTokenVisible by remember { mutableStateOf(false) }

    val initialPushState = pushEnabledOnThisDevice ?: pushStatus.devices.isNotEmpty()
    var pushOnThisDevice by remember(pushEnabledOnThisDevice, pushStatus.devices, distributorAvailable) {
        mutableStateOf(if (!distributorAvailable) false else initialPushState)
    }
    var permissionDeniedBanner by remember(notificationsEnabledOnDevice) {
        mutableStateOf(!notificationsEnabledOnDevice)
    }

    var ntfyServerDraft by remember(ntfy.server) { mutableStateOf(ntfy.server.ifBlank { "https://ntfy.sh" }) }
    var ntfyTopicDraft by remember(ntfy.topic) { mutableStateOf(ntfy.topic) }
    var ntfyTokenDraft by remember { mutableStateOf("") }
    var ntfyTokenVisible by remember { mutableStateOf(false) }
    var ntfyError by remember { mutableStateOf<String?>(null) }

    val selectedDelayLabel = NOTIFICATION_DELAY_OPTIONS
        .firstOrNull { it.first == settings.delaySeconds }?.second
        ?: "Immediately"

    val showNotificationsOffBanner = !notificationsEnabledOnDevice || permissionDeniedBanner

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (showNotificationsOffBanner) {
                val bannerShape = RoundedCornerShape(12.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(bannerShape)
                        .background(tokens.warning.opacity(0.12).color)
                        .cssBorder(tokens.warning.opacity(0.35).color, bannerShape)
                        .ppmClickable(onClick = onOpenSystemNotificationSettings)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .testTag("phone-notifications-off-banner"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        BasicText(
                            text = "Notifications are off",
                            style = ppmText(13f, FontWeight.SemiBold).copy(color = tokens.warning.color),
                        )
                        BasicText(
                            text = "Tap to enable notifications for PPM in Android settings.",
                            style = ppmText(12f).copy(color = tokens.text2.color),
                        )
                    }
                    PpmIcon(ProductIcon.ChevronRight, 16.dp, tokens.warning.color)
                }
            }

            if (!statusMessage.isNullOrBlank()) {
                BasicText(
                    text = statusMessage,
                    style = ppmText(12f).copy(color = tokens.accent.color),
                    modifier = Modifier.testTag("phone-notifications-status"),
                )
            }

            // 1. When to notify
            PhoneGroupLabel("When to notify")
            PhoneSectionCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    NOTIFICATION_EVENT_DEFS.forEachIndexed { idx, def ->
                        if (idx > 0) Separator()
                        val checked = settings.events[def.key] ?: true
                        SettingsToggleRow(
                            title = def.label,
                            subtitle = def.description,
                            checked = checked,
                            testTag = "phone-notify-event-${def.key}",
                            onCheckedChange = { next ->
                                onUpdateSettings(settings.copy(events = settings.events + (def.key to next)))
                            },
                        )
                    }
                }
            }

            // 2. Notification Delay
            PhoneSectionCard {
                ListRow(
                    title = "Send a notification",
                    subtitle = "Wait for idle before alerting",
                    modifier = Modifier.testTag("phone-notify-delay-row"),
                    trailingContent = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            BasicText(
                                text = selectedDelayLabel,
                                style = ppmText(13f, FontWeight.Medium).copy(color = tokens.text2.color),
                            )
                            PpmIcon(ProductIcon.ChevronRight, 16.dp, tokens.text3.color)
                        }
                    },
                    onClick = { delaySheetOpen = true },
                )
            }

            // 3. Push on this device
            PhoneGroupLabel("Push")
            PhoneSectionCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val pushSubtitle = when {
                        !distributorAvailable -> "UnifiedPush distributor required (install ntfy)"
                        // Firebase, or the distributor Firebase had to give way to, is named first.
                        distributorName != null && (distributorName == PushThisDevice.FIREBASE_NAME || firebaseInUseElsewhere) ->
                            pushViaSubtitle("this phone", distributorName, firebaseInUseElsewhere)
                        pushStatus.devices.isNotEmpty() -> "${pushStatus.devices.size} subscribed device(s)"
                        !distributorName.isNullOrBlank() -> pushViaSubtitle("this phone", distributorName)
                        else -> "Receive alerts on this phone when a chat finishes"
                    }
                    PushDeviceToggleRow(
                        title = "Push notifications on this device",
                        subtitle = pushSubtitle,
                        checked = pushOnThisDevice,
                        enabled = distributorAvailable,
                        testTag = "phone-notify-push-toggle",
                        onCheckedChange = { wantEnabled ->
                            if (!distributorAvailable) {
                                pushOnThisDevice = false
                                return@PushDeviceToggleRow
                            }
                            if (wantEnabled) {
                                // Requirement 7.1: Request POST_NOTIFICATIONS only when turning on this switch
                                onRequestNotificationPermission { granted ->
                                    if (granted && notificationsEnabledOnDevice) {
                                        permissionDeniedBanner = false
                                        pushOnThisDevice = true
                                        onTogglePushOnDevice(true)
                                    } else {
                                        pushOnThisDevice = false
                                        permissionDeniedBanner = true
                                    }
                                }
                            } else {
                                pushOnThisDevice = false
                                onTogglePushOnDevice(false)
                            }
                        },
                    )
                    if (!distributorAvailable) {
                        val noticeShape = RoundedCornerShape(10.dp)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(noticeShape)
                                .background(tokens.panel2.color)
                                .cssBorder(tokens.border.color, noticeShape)
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .testTag("phone-notify-no-distributor-notice"),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            BasicText(
                                text = "UnifiedPush distributor required",
                                style = ppmText(12f, FontWeight.SemiBold).copy(color = tokens.text.color),
                            )
                            BasicText(
                                text = "Install ntfy (or another UnifiedPush distributor app) on this device to enable push notifications.",
                                style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text2.color),
                            )
                        }
                    }
                    if (pushStatus.devices.isNotEmpty() || pushOnThisDevice) {
                        PhonePillButton(
                            label = "Send a test",
                            onClick = onTestPush,
                            modifier = Modifier.testTag("phone-notify-push-test"),
                        )
                    }
                }
            }

            // 4. Telegram
            PhoneGroupLabel("Telegram")
            PhoneSectionCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SettingsToggleRow(
                        title = "Enable Telegram",
                        subtitle = if (telegram.botUsername != null) {
                            "Connected to @${telegram.botUsername}"
                        } else {
                            "Send alerts to a Telegram chat via your bot"
                        },
                        checked = settings.telegram,
                        testTag = "phone-notify-telegram-toggle",
                        onCheckedChange = { onUpdateSettings(settings.copy(telegram = it)) },
                    )

                    if (settings.telegram) {
                        Separator()
                        if (!telegram.configured) {
                            BasicText(
                                text = "1. Message @BotFather on Telegram and run /newbot.\n2. Copy the bot token and save it below.\n3. Tap Connect Telegram to pair your chat.",
                                style = ppmText(12f, lineHeight = 17f).copy(color = tokens.text2.color),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PhonePillButton(
                                label = if (telegram.configured) "Change bot token" else "Set bot token",
                                primary = !telegram.configured,
                                onClick = { telegramTokenSheetOpen = true },
                                modifier = Modifier.testTag("phone-notify-telegram-token-btn"),
                            )
                            if (telegram.configured) {
                                if (telegram.connectActive) {
                                    PhonePillButton(
                                        label = "Cancel connect",
                                        onClick = onCancelTelegramConnect,
                                    )
                                } else {
                                    PhonePillButton(
                                        label = "Connect Telegram",
                                        primary = true,
                                        onClick = onStartTelegramConnect,
                                        modifier = Modifier.testTag("phone-notify-telegram-connect"),
                                    )
                                }
                                PhonePillButton(
                                    label = "Test",
                                    onClick = onTestTelegram,
                                    modifier = Modifier.testTag("phone-notify-telegram-test"),
                                )
                            }
                        }

                        for (chat in telegram.chats) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                BasicText(
                                    text = "${chat.name} (${chat.chatId})",
                                    style = ppmText(12f, kind = FontKind.MONO).copy(color = tokens.text2.color),
                                )
                                PhonePillButton(
                                    label = "Remove",
                                    danger = true,
                                    onClick = { onRemoveTelegramChat(chat.chatId) },
                                )
                            }
                        }
                    }
                }
            }

            // 5. ntfy
            PhoneGroupLabel("ntfy")
            PhoneSectionCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SettingsToggleRow(
                        title = "Enable ntfy",
                        subtitle = "Push notifications via ntfy.sh or your self-hosted server",
                        checked = settings.ntfy,
                        testTag = "phone-notify-ntfy-toggle",
                        onCheckedChange = { onUpdateSettings(settings.copy(ntfy = it)) },
                    )

                    if (settings.ntfy) {
                        Separator()
                        SettingsTextInput(
                            value = ntfyServerDraft,
                            onValueChange = {
                                ntfyServerDraft = it
                                ntfyError = null
                            },
                            placeholder = "https://ntfy.sh",
                            mono = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("phone-notify-ntfy-server"),
                        )
                        SettingsTextInput(
                            value = ntfyTopicDraft,
                            onValueChange = {
                                ntfyTopicDraft = it
                                ntfyError = null
                            },
                            placeholder = "Topic name (e.g. ppm-alerts)",
                            mono = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("phone-notify-ntfy-topic"),
                        )
                        SettingsPasswordInput(
                            value = ntfyTokenDraft,
                            onValueChange = { ntfyTokenDraft = it },
                            placeholder = if (ntfy.tokenSet) "•••••••• (token saved)" else "Access token (optional)",
                            visible = ntfyTokenVisible,
                            onToggleVisible = { ntfyTokenVisible = !ntfyTokenVisible },
                            modifier = Modifier.fillMaxWidth(),
                            inputTestTag = "phone-notify-ntfy-token",
                        )
                        if (!ntfyError.isNullOrBlank()) {
                            BasicText(
                                text = ntfyError!!,
                                style = ppmText(11f).copy(color = tokens.error.color),
                                modifier = Modifier.testTag("phone-notify-ntfy-error"),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PhonePillButton(
                                label = "Send a test",
                                enabled = ntfy.configured,
                                onClick = onTestNtfy,
                                modifier = Modifier.testTag("phone-notify-ntfy-test"),
                            )
                            PhonePillButton(
                                label = "Save",
                                primary = true,
                                onClick = {
                                    val normalizedServer = normalizeNtfyServer(ntfyServerDraft)
                                    val err = validateNtfyTopic(ntfyTopicDraft)
                                        ?: if (normalizedServer == null) "Enter a valid http:// or https:// server URL" else null
                                    if (err != null || normalizedServer == null) {
                                        ntfyError = err
                                    } else {
                                        ntfyError = null
                                        onSaveNtfy(
                                            normalizedServer,
                                            ntfyTopicDraft.trim(),
                                            ntfyTokenDraft.trim().ifEmpty { null },
                                        )
                                        ntfyTokenDraft = ""
                                    }
                                },
                                modifier = Modifier.testTag("phone-notify-ntfy-save"),
                            )
                            if (ntfy.configured) {
                                PhonePillButton(
                                    label = "Clear",
                                    danger = true,
                                    onClick = onRemoveNtfy,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }

        if (delaySheetOpen) {
            Sheet(
                title = "Send a notification",
                subtitle = "Wait for idle before alerting",
                onDismiss = { delaySheetOpen = false },
                forceBottomSheet = true,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for ((sec, label) in NOTIFICATION_DELAY_OPTIONS) {
                        val selected = settings.delaySeconds == sec
                        ListRow(
                            title = label,
                            selected = selected,
                            modifier = Modifier.testTag("phone-notify-delay-option-$sec"),
                            trailingContent = if (selected) {
                                { PpmIcon(ProductIcon.Check, 16.dp, tokens.accent.color) }
                            } else {
                                null
                            },
                            onClick = {
                                delaySheetOpen = false
                                onUpdateSettings(settings.copy(delaySeconds = sec))
                            },
                        )
                    }
                }
            }
        }

        if (telegramTokenSheetOpen) {
            Sheet(
                title = "Telegram Bot Token",
                subtitle = "Token from @BotFather",
                onDismiss = { telegramTokenSheetOpen = false },
                forceBottomSheet = true,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SettingsPasswordInput(
                        value = telegramTokenDraft,
                        onValueChange = { telegramTokenDraft = it },
                        placeholder = "123456:ABC-DEF...",
                        visible = telegramTokenVisible,
                        onToggleVisible = { telegramTokenVisible = !telegramTokenVisible },
                        modifier = Modifier.fillMaxWidth(),
                        inputTestTag = "phone-telegram-sheet-token-input",
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PhonePillButton(
                            label = "Cancel",
                            onClick = { telegramTokenSheetOpen = false },
                            modifier = Modifier.weight(1f),
                        )
                        PhonePillButton(
                            label = "Save",
                            primary = true,
                            enabled = telegramTokenDraft.isNotBlank(),
                            onClick = {
                                val token = telegramTokenDraft.trim()
                                telegramTokenDraft = ""
                                telegramTokenSheetOpen = false
                                onSaveTelegramToken(token)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("phone-telegram-sheet-save"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PushDeviceToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f)
            .semantics {
                selected = checked
                if (!enabled) disabled()
            }
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .testTag(testTag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            BasicText(
                text = title,
                style = ppmText(13f, FontWeight.Medium).copy(color = tokens.text.color),
            )
            BasicText(
                text = subtitle,
                style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color),
            )
        }
        val pillShape = RoundedCornerShape(12.dp)
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(22.dp)
                .clip(pillShape)
                .background(if (checked && enabled) tokens.accent.color else tokens.panel2.color)
                .cssBorder((if (checked && enabled) tokens.accent else tokens.border).color, pillShape)
                .padding(horizontal = 3.dp),
            contentAlignment = if (checked && enabled) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (checked && enabled) tokens.accentFg.color else tokens.text2.color),
            )
        }
    }
}

@Composable
internal fun PhoneGroupLabel(text: String) {
    val tokens = LocalPpmTokens.current
    BasicText(
        text = text.uppercase(),
        style = ppmText(11f, FontWeight.SemiBold).copy(color = tokens.text3.color),
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}
