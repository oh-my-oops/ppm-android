package com.hienlh.ppm.app.ui.settings.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.PpmSwitch
import com.hienlh.ppm.app.ui.SettingsButton
import com.hienlh.ppm.app.ui.SettingsPasswordInput
import com.hienlh.ppm.app.ui.SettingsTextInput
import com.hienlh.ppm.app.ui.assistant.QUIET_SWITCH_LABEL
import com.hienlh.ppm.chat.rememberNow
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.TraceRedactor
import com.hienlh.ppm.client.assistantTelegram
import com.hienlh.ppm.client.clawbotCancelTelegramConnect
import com.hienlh.ppm.client.clawbotDisconnectPairedChat
import com.hienlh.ppm.client.clawbotGetConfig
import com.hienlh.ppm.client.clawbotGetTelegram
import com.hienlh.ppm.client.clawbotPutConfig
import com.hienlh.ppm.client.clawbotPutTelegramToken
import com.hienlh.ppm.client.clawbotStartTelegramConnect
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.AssistantTelegram
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.state.settings.ClawbotTelegramState
import com.hienlh.ppm.state.settings.TelegramPairedChat
import com.hienlh.ppm.state.settings.parseClawbotTelegram
import com.hienlh.ppm.state.settings.parseTelegramConnectLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * The Assistant's own Telegram bot (`assistant-telegram-settings.tsx`), on the `/api/settings/clawbot*`
 * routes the old PPMBot used — same bot, same connected chats — sending only the keys that still
 * mean something there: `enabled`, `show_tool_calls`, `debounce_ms`, `quiet_while_in_ppm`. The bot
 * token is only ever sent, never read back.
 */

internal const val DEBOUNCE_MAX_MS = 30_000
internal const val QUIET_SWITCH_NOTE =
    "While you use PPM (or typed there in the last 5 minutes), messages on Telegram arrive without a sound. " +
        "A card left unanswered for 5 minutes is sent once more with a sound. Quiet hours still apply."

/** A whole number of milliseconds the server accepts, or null (`parseDebounceMs`). */
internal fun parseDebounceMs(text: String): Int? {
    val t = text.trim()
    if (!Regex("^\\d+$").matches(t)) return null
    return t.toIntOrNull()?.takeIf { it <= DEBOUNCE_MAX_MS }
}

private fun runningNote(status: ClawbotTelegramState): String = when {
    status.running -> status.botUsername?.let { "Answering as @$it" } ?: "Running"
    status.enabled -> "Turned on, but not running — check the bot token"
    else -> "Off"
}

/** The bridge options the server keeps under `clawbot`. */
private data class BridgeOptions(val showToolCalls: Boolean, val debounceMs: Int, val quietWhileInPpm: Boolean) {
    companion object {
        fun from(json: kotlinx.serialization.json.JsonElement?) = BridgeOptions(
            showToolCalls = json["show_tool_calls"].bool ?: true,
            debounceMs = json["debounce_ms"].int ?: 2000,
            quietWhileInPpm = json["quiet_while_in_ppm"].bool != false,
        )
    }
}

private fun errorText(e: Exception) = e.message ?: e.toString()

@Composable
internal fun AssistantTelegramBlock(model: AppModel, api: ApiClient, sessions: List<SessionInfo>, quietFromEvents: Boolean?) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var status by remember(api) { mutableStateOf<ClawbotTelegramState?>(null) }
    var options by remember(api) { mutableStateOf<BridgeOptions?>(null) }
    var binding by remember(api) { mutableStateOf<AssistantTelegram?>(null) }
    var debounceText by remember { mutableStateOf("") }
    var loadError by remember { mutableStateOf<String?>(null) }
    var switching by remember { mutableStateOf(false) }
    var savingOptions by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var tokenSaving by remember { mutableStateOf(false) }
    var tokenError by remember { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(false) }
    var chatsBeforeConnect by remember { mutableStateOf<Set<String>?>(null) }
    var reloads by remember { mutableIntStateOf(0) }

    suspend fun load(): ClawbotTelegramState? = try {
        parseClawbotTelegram(api.clawbotGetTelegram(), previousConnectUrl = status?.connectUrl, previousChatIds = chatsBeforeConnect).also {
            status = it
            loadError = null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        loadError = errorText(e)
        null
    }

    suspend fun loadBinding() {
        binding = try {
            api.assistantTelegram()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    // Changes announced from elsewhere (`/quiet` on the phone, another device) reload it all.
    val events = model.events
    DisposableEffect(events) {
        val off = events?.onSettingsChanged { section, _ -> if (section == "clawbot" || section == "telegram") reloads++ }
        val offAssistant = events?.onAssistantEvent { type, _ ->
            if (type == "assistant:telegram_binding_changed" || type == "assistant:telegram_quiet_changed") reloads++
        }
        onDispose {
            off?.invoke()
            offAssistant?.invoke()
        }
    }
    LaunchedEffect(api, reloads, quietFromEvents) {
        load()
        loadBinding()
        try {
            val o = BridgeOptions.from(api.clawbotGetConfig())
            options = o
            debounceText = o.debounceMs.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = errorText(e)
        }
    }
    // While a connect link is out, ask every 2 s whether a chat used it.
    val current = status
    LaunchedEffect(api, current?.connectActive, current?.connectUrl) {
        if (current == null || (!current.connectActive && current.connectUrl.isNullOrBlank())) return@LaunchedEffect
        while (isActive) {
            delay(2_000L)
            val before = chatsBeforeConnect
            val next = load() ?: continue
            val added = before?.let { b -> next.chats.firstOrNull { it.chatId !in b } }
            if (added != null) {
                chatsBeforeConnect = null
                status = next.copy(connectActive = false, connectUrl = null)
                loadBinding()
                model.toast(ErrorToastMessage("Connected ${added.name}", kind = ErrorToastMessage.Kind.SUCCESS))
                break
            }
            if (!next.connectActive) {
                chatsBeforeConnect = null
                break
            }
        }
    }

    fun setEnabled(on: Boolean) {
        switching = true
        status = status?.copy(enabled = on)
        scope.launch {
            try {
                api.clawbotPutConfig(buildJsonObject { put("enabled", on) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.toast(ErrorToastMessage(if (on) "Could not turn Telegram on" else "Could not turn Telegram off", errorText(e)))
            } finally {
                switching = false
                load()
                loadBinding()
            }
        }
    }
    fun saveOptions(patch: JsonObject) {
        savingOptions = true
        scope.launch {
            try {
                val saved = BridgeOptions.from(api.clawbotPutConfig(patch))
                options = saved
                debounceText = saved.debounceMs.toString()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.toast(ErrorToastMessage("Could not save", errorText(e)))
                options?.let { debounceText = it.debounceMs.toString() }
            } finally {
                savingOptions = false
            }
        }
    }
    fun disconnect(chat: TelegramPairedChat) {
        scope.launch {
            try {
                api.clawbotDisconnectPairedChat(chat.chatId)
                model.toast(ErrorToastMessage("Disconnected ${chat.name}", "It can no longer reach the Assistant.", kind = ErrorToastMessage.Kind.SUCCESS))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.toast(ErrorToastMessage("Could not disconnect", errorText(e)))
            } finally {
                load()
                loadBinding()
            }
        }
    }
    fun connect() {
        starting = true
        scope.launch {
            try {
                chatsBeforeConnect = status?.chats.orEmpty().map { it.chatId }.toSet()
                val link = parseTelegramConnectLink(api.clawbotStartTelegramConnect())
                val fresh = load()
                status = (fresh ?: status)?.copy(connectActive = true, connectUrl = link?.url, connectExpiresAt = link?.expiresAt, connectError = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.toast(ErrorToastMessage("Could not start connecting", errorText(e)))
            } finally {
                starting = false
            }
        }
    }
    fun cancelConnect() {
        scope.launch {
            runCatching { api.clawbotCancelTelegramConnect() }
            chatsBeforeConnect = null
            status = status?.copy(connectActive = false, connectUrl = null, connectExpiresAt = null)
        }
    }
    fun saveToken() {
        val value = token.trim()
        if (value.isEmpty()) return
        TraceRedactor.Global.remember(value)
        tokenSaving = true
        tokenError = null
        scope.launch {
            try {
                api.clawbotPutTelegramToken(value)
                token = ""
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tokenError = errorText(e)
            } finally {
                tokenSaving = false
            }
        }
    }

    val intro = "Talk to the PPM Assistant from Telegram, through a bot of its own. A connected chat shares one " +
        "Assistant session with PPM, and everything it asks to change still waits for your Allow — connect only your own chat."
    Column(Modifier.fillMaxWidth().testTag("assistant-telegram-settings"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BasicText(intro, style = asstNote())
        loadError?.let { BasicText(it, style = ppmText(12f).copy(color = tokens.error.color)) }
        val s = status
        if (s != null && s.configured) {
            if (s.sharedWithNotifications) {
                val shape = RoundedCornerShape(8.dp)
                Row(
                    Modifier.fillMaxWidth().cssBorder(tokens.warning.opacity(0.4).color, shape).padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PpmIcon(ProductIcon.TriangleAlert, 14.dp, tokens.warning.color)
                    BasicText(
                        "Notifications use this bot too, so alerts arrive in your Assistant chats. Paste a new bot's token below to give the Assistant its own.",
                        style = asstNote().copy(color = tokens.text.color),
                    )
                }
            }
            Bordered {
                AsstLine("Use the Assistant on Telegram", runningNote(s)) {
                    Box(Modifier.testTag("assistant-telegram-enabled")) { PpmSwitch(s.enabled, ::setEnabled, enabled = !switching) }
                }
            }
            if (s.chats.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasicText("Chats that can use the Assistant", style = asstNote())
                    Bordered {
                        s.chats.forEachIndexed { i, chat ->
                            if (i > 0) AsstDivider()
                            val bound = binding?.chats?.firstOrNull { it.chatId == chat.chatId }
                            val detail = if (bound?.sessionId == null) {
                                "No session yet — its next message starts one"
                            } else {
                                // The server knows only a title the session was renamed to; the list knows the provider's.
                                val title = bound.sessionTitle?.takeIf { it.isNotEmpty() } ?: sessions.firstOrNull { it.id == bound.sessionId }?.title
                                "Talks to “${title?.takeIf { it.isNotEmpty() } ?: "Untitled session"}”"
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp).testTag("assistant-telegram-chat-" + chat.chatId),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    BasicText(chat.name, style = asstBody())
                                    BasicText(detail, style = asstNote())
                                }
                                AsstIconButton(ProductIcon.X, "Disconnect ${chat.name}", danger = true) { disconnect(chat) }
                            }
                        }
                    }
                    BasicText(
                        "To move a chat to another conversation, send /sessions or /new there, or choose Use on Telegram on a session in the list above.",
                        style = asstNote(),
                    )
                }
            }
            val url = s.connectUrl
            if (s.connectActive && !url.isNullOrBlank()) {
                ConnectCard(url, s.botUsername, s.connectExpiresAt, s.connectError, onOpen = { runCatching { uriHandler.openUri(url) } }, onCancel = ::cancelConnect)
            } else {
                Box(Modifier.testTag("assistant-telegram-connect")) {
                    SettingsButton(
                        if (starting) "Connecting…" else if (s.chats.isEmpty()) "Connect Telegram" else "Connect another chat",
                        primary = true,
                        enabled = !starting,
                        onClick = ::connect,
                    )
                }
            }
            options?.let { o ->
                Bordered {
                    AsstLine(QUIET_SWITCH_LABEL, QUIET_SWITCH_NOTE) {
                        Box(Modifier.testTag("assistant-telegram-quiet")) {
                            PpmSwitch(o.quietWhileInPpm, { saveOptions(buildJsonObject { put("quiet_while_in_ppm", it) }) }, enabled = !savingOptions)
                        }
                    }
                    AsstDivider()
                    AsstLine("Show tool names", "A line in the reply naming each tool the Assistant uses. Inputs and outputs are never sent.") {
                        Box(Modifier.testTag("assistant-telegram-tools")) {
                            PpmSwitch(o.showToolCalls, { saveOptions(buildJsonObject { put("show_tool_calls", it) }) }, enabled = !savingOptions)
                        }
                    }
                }
                val debounceMs = parseDebounceMs(debounceText)
                val changed = debounceMs != o.debounceMs
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText("Message grouping (ms)", style = asstNote())
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsTextInput(debounceText, { debounceText = it }, "", Modifier.width(128.dp).testTag("assistant-telegram-debounce"), keyboardType = KeyboardType.Number)
                        if (changed) {
                            SettingsButton("Save", primary = true, enabled = !savingOptions && debounceMs != null, modifier = Modifier.testTag("assistant-telegram-debounce-save")) {
                                debounceMs?.let { saveOptions(buildJsonObject { put("debounce_ms", it) }) }
                            }
                        }
                    }
                    BasicText(
                        if (debounceMs == null) "A whole number from 0 to 30,000." else "Messages sent this close together are answered as one. 0 answers each at once.",
                        style = ppmText(12f).copy(color = (if (debounceMs == null) tokens.error else tokens.text3).color),
                    )
                }
            }
        }
        if (s != null && !s.configured) {
            BasicText(
                "Create a new bot for the Assistant rather than reusing the one in Settings → Notifications, so alerts stay out of the chats that can give it orders.",
                style = asstNote(),
            )
        }
        if (s != null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText(if (s.configured) "Bot token — paste a new one to replace it" else "Bot token", style = asstNote())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsPasswordInput(
                        value = token,
                        onValueChange = { token = it; tokenError = null },
                        placeholder = if (s.configured) "••••••  (saved)" else "123456789:AA…",
                        visible = showToken,
                        onToggleVisible = { showToken = !showToken },
                        modifier = Modifier.weight(1f),
                        inputTestTag = "assistant-telegram-token",
                    )
                    SettingsButton(if (tokenSaving) "Checking…" else "Save", primary = true, enabled = token.isNotBlank() && !tokenSaving, modifier = Modifier.testTag("assistant-telegram-token-save"), onClick = ::saveToken)
                }
                tokenError?.let { BasicText(it, style = ppmText(12f).copy(color = tokens.error.color)) }
                if (s.configured && s.botUsername != null) BasicText("Using @${s.botUsername}.", style = asstNote())
            }
        }
    }
}

@Composable
private fun Bordered(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.fillMaxWidth().cssBorder(LocalPpmTokens.current.border.color, shape)) { content() }
}

/** `ConnectCard`: the one-time link, a button that opens it, and the countdown until it expires. */
@Composable
private fun ConnectCard(url: String, botUsername: String?, expiresAt: Long?, error: String?, onOpen: () -> Unit, onCancel: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val now = rememberNow(true)
    val left = ((expiresAt ?: 0) - now).coerceAtLeast(0) / 1000
    val countdown = "${left / 60}:${(left % 60).toString().padStart(2, '0')}"
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.fillMaxWidth().background(tokens.panel.color, shape).cssBorder(tokens.border.color, shape).padding(12.dp).testTag("assistant-telegram-connect-card"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            "Open the link in Telegram and tap Start" + (botUsername?.let { " in the chat with @$it" } ?: "") + ". It connects one chat, once.",
            style = asstBody(),
        )
        SettingsButton("Open Telegram", primary = true, onClick = onOpen)
        BasicText(url, style = asstNote())
        BasicText(
            error ?: "Waiting for Telegram… the link expires in $countdown",
            style = ppmText(12f).copy(color = (if (error != null) tokens.error else tokens.text3).color),
        )
        SettingsButton("Cancel", onClick = onCancel)
    }
}
