package com.hienlh.ppm.app.ui.settings.assistant

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.assistant
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.SelectField
import com.hienlh.ppm.app.ui.SelectOption
import com.hienlh.ppm.app.ui.SettingsButton
import com.hienlh.ppm.app.ui.assistant.CardButton
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.bindAssistantTelegram
import com.hienlh.ppm.client.deleteSession
import com.hienlh.ppm.client.setAssistantMainSession
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ProviderLogo
import com.hienlh.ppm.protocol.ASSISTANT_PROJECT_NAME
import com.hienlh.ppm.protocol.AssistantTelegramChat
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.state.assistant.mainSessionLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Settings → PPM Assistant → Sessions & Telegram (`assistant-sessions-telegram-section.tsx`): the
 * main session (the one Telegram follows), a new one-off session, every Assistant session with
 * Open / Set as main / Use on Telegram / Delete, and the Assistant's Telegram bot below.
 */

private val ASSISTANT_PROVIDER_IDS = listOf("claude", "codex")
private const val NONE = "__none__"

internal const val TELEGRAM_FOLLOWS_MAIN = "Telegram follows the main session."

private val MONTH_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())

/** `formatRelativeDate`: "just now", "5m ago", "3h ago", "2d ago", then the date. */
private fun relative(at: Instant?, now: Long = System.currentTimeMillis()): String {
    val ms = at?.toEpochMilli() ?: return ""
    val min = (now - ms) / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "${min}m ago"
        min < 60 * 24 -> "${min / 60}h ago"
        min < 60 * 24 * 7 -> "${min / (60 * 24)}d ago"
        else -> MONTH_DAY.format(at.atZone(ZoneId.systemDefault()))
    }
}

private fun errorText(e: Exception) = e.message ?: e.toString()

@Composable
internal fun AssistantSessionsPane(model: AppModel, api: ApiClient) {
    val scope = rememberCoroutineScope()
    val store = model.assistant.store
    val view = store?.view?.collectAsState()?.value
    val mainId = view?.main?.sessionId
    val telegram = view?.telegram
    var sessions by remember(api) { mutableStateOf<List<SessionInfo>?>(null) }
    var reloads by remember { mutableIntStateOf(0) }
    LaunchedEffect(api, reloads) {
        sessions = try {
            api.sessions(ASSISTANT_PROJECT_NAME).sessions
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            sessions ?: emptyList()
        }
    }
    val events = model.events
    DisposableEffect(events) {
        val off = events?.onSessionsListChanged { project, _ -> if (project == ASSISTANT_PROJECT_NAME) reloads++ }
        onDispose { off?.invoke() }
    }
    var menuFor by remember { mutableStateOf<SessionInfo?>(null) }
    var deleting by remember { mutableStateOf<SessionInfo?>(null) }
    var savingMain by remember { mutableStateOf(false) }

    fun setMain(session: SessionInfo, describe: Boolean) {
        savingMain = true
        scope.launch {
            try {
                api.setAssistantMainSession(session.id)
                model.toast(
                    ErrorToastMessage(
                        "Main session changed",
                        if (describe) "Telegram follows it from the next message." else null,
                        kind = ErrorToastMessage.Kind.SUCCESS,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.toast(ErrorToastMessage("Could not change the main session", errorText(e)))
            } finally {
                savingMain = false
            }
        }
    }
    fun useOnTelegram(session: SessionInfo, chat: AssistantTelegramChat) {
        scope.launch {
            try {
                api.bindAssistantTelegram(session.id, chat.chatId)
                model.toast(ErrorToastMessage("${chat.name} now talks to this session", kind = ErrorToastMessage.Kind.SUCCESS))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.toast(ErrorToastMessage("Could not use this session on Telegram", errorText(e)))
            }
        }
    }
    fun remove(session: SessionInfo) {
        sessions = sessions?.filterNot { it.id == session.id }
        scope.launch {
            try {
                api.deleteSession(ASSISTANT_PROJECT_NAME, session.id, session.providerId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                model.toast(ErrorToastMessage("Could not delete the session"))
            } finally {
                reloads++
            }
        }
    }

    val list = sessions.orEmpty()
    val rows = list.filter { it.providerId in ASSISTANT_PROVIDER_IDS }
    Column(Modifier.fillMaxWidth().testTag("assistant-sessions-section"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AsstCard(ProductIcon.Pin, "Sessions") {
            AsstLine("Main session", "Reports, decisions and the morning digest arrive here. Claude sessions only for now.") {
                val claude = list.filter { it.providerId == "claude" }
                // The main session may sit past the list's first page: it still has to be choosable as itself.
                val missing = mainId != null && claude.none { it.id == mainId }
                val options = buildList {
                    if (mainId == null) add(SelectOption(NONE, "No main session"))
                    if (missing && mainId != null) add(SelectOption(mainId, "Main session"))
                    claude.forEach { add(SelectOption(it.id, mainSessionLabel(it.title, it.createdAt?.toEpochMilli()))) }
                }
                SelectField(
                    value = mainId ?: NONE,
                    options = options,
                    onValueChange = { v -> if (v != NONE && v != mainId) claude.firstOrNull { it.id == v }?.let { setMain(it, describe = true) } },
                    modifier = Modifier.then(if (LocalPpmMobile.current) Modifier.fillMaxWidth() else Modifier.widthIn(min = 220.dp, max = 288.dp)).testTag("assistant-main-session"),
                    enabled = !savingMain,
                )
            }
            AsstDivider()
            AsstLine("New session", "A one-off session for something apart. The main session stays as it is.") {
                val providers = model.assistant.providers
                if (providers.isNullOrEmpty()) {
                    BasicText("No configured provider can run the Assistant.", style = asstNote())
                } else {
                    providers.forEach { p ->
                        Box(Modifier.testTag("assistant-new-session-" + p.id)) {
                            SettingsButton("+ " + if (providers.size > 1) p.name else "New session") { model.assistant.openNewSession(p.id) }
                        }
                    }
                }
            }
            AsstDivider()
            if (sessions == null) {
                AsstLoading(null)
            } else if (rows.isEmpty()) {
                BasicText("No Assistant sessions yet.", Modifier.fillMaxWidth().padding(16.dp), asstNote())
            } else {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    rows.forEach { s ->
                        SessionRow(
                            session = s,
                            isMain = s.id == mainId,
                            onTelegram = telegram?.chatsOn(s.id).orEmpty(),
                            onOpen = { model.assistant.open(s.id, s.providerId) },
                            onMenu = { menuFor = s },
                        )
                    }
                }
            }
        }
        AsstCard(ProductIcon.Send, "Telegram", aside = { BasicText(TELEGRAM_FOLLOWS_MAIN, style = asstNote()) }) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                AssistantTelegramBlock(model, api, sessions.orEmpty(), telegram?.quietWhileInPpm)
            }
        }
    }

    menuFor?.let { s ->
        val bindable = telegram?.bindableChats.orEmpty()
        val on = telegram?.chatsOn(s.id).orEmpty().map { it.chatId }.toSet()
        AsstDialog(title = s.title.ifEmpty { "Untitled session" }, onClose = { menuFor = null }) {
            MenuItem("Open", ProductIcon.ExternalLink, "asst-session-open") { menuFor = null; model.assistant.open(s.id, s.providerId) }
            if (s.providerId == "claude" && s.id != mainId) {
                MenuItem("Set as main session", ProductIcon.Pin, "asst-session-set-main") { menuFor = null; setMain(s, describe = false) }
            }
            bindable.forEach { chat ->
                val label = when {
                    chat.chatId in on -> if (bindable.size == 1) "On Telegram" else "On Telegram: ${chat.name}"
                    bindable.size == 1 -> "Use on Telegram"
                    else -> "Use on Telegram: ${chat.name}"
                }
                MenuItem(label, ProductIcon.Send, "asst-session-telegram-" + chat.chatId, enabled = chat.chatId !in on) {
                    menuFor = null
                    useOnTelegram(s, chat)
                }
            }
            MenuItem("Delete", ProductIcon.Trash2, "asst-session-delete", danger = true) { menuFor = null; deleting = s }
        }
    }
    deleting?.let { s ->
        val text = "“${s.title.ifEmpty { "Untitled session" }}” and its history are deleted. This cannot be undone." +
            if (s.id == mainId) " It is the main session, so Telegram has no session to follow until you pick another." else ""
        AsstConfirm("Delete this session?", text, "Delete", destructive = true, onClose = { deleting = null }) {
            deleting = null
            remove(s)
        }
    }
}

@Composable
private fun SessionRow(session: SessionInfo, isMain: Boolean, onTelegram: List<AssistantTelegramChat>, onOpen: () -> Unit, onMenu: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (LocalPpmMobile.current) 44.dp else 36.dp).testTag("assistant-session-" + session.id),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).combinedClickable(onClickLabel = "Open", onLongClick = onMenu, onClick = onOpen).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProviderLogo(session.providerId, 16.dp, tokens.text2.color)
            BasicText(session.title.ifEmpty { "Untitled session" }, Modifier.weight(1f), asstBody(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (isMain) AsstPill("Main", accent = true, icon = ProductIcon.Pin, tag = "assistant-session-main")
            if (onTelegram.isNotEmpty()) AsstPill("Telegram", icon = ProductIcon.Send, tag = "assistant-session-telegram")
            BasicText(relative(session.updatedAt ?: session.createdAt), style = asstNote())
        }
        AsstIconButton(ProductIcon.MoreHorizontal, "Session actions", Modifier.testTag("assistant-session-menu-" + session.id), onClick = onMenu)
    }
}

@Composable
private fun MenuItem(label: String, icon: ProductIcon, tag: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().testTag(tag)) {
        CardButton(
            label,
            onClick,
            Modifier.fillMaxWidth(),
            icon = icon,
            variant = if (danger) com.hienlh.ppm.app.ui.assistant.CardButtonVariant.DANGER else com.hienlh.ppm.app.ui.assistant.CardButtonVariant.DEFAULT,
            enabled = enabled,
        )
    }
}
