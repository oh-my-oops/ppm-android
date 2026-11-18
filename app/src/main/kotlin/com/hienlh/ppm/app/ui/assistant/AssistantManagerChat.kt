package com.hienlh.ppm.app.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.assistant
import com.hienlh.ppm.client.checkManagerChat
import com.hienlh.ppm.client.setManagerChat
import com.hienlh.ppm.client.unsetManagerChat
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.ANSWERABLE_CARD_KINDS
import com.hienlh.ppm.protocol.AssistantCard
import com.hienlh.ppm.protocol.ManagerAssignCheck
import com.hienlh.ppm.protocol.isAssistantProject
import com.hienlh.ppm.state.assistant.MANAGER_PROVIDER_ID
import com.hienlh.ppm.state.assistant.SWITCHED_NOTE_MS
import com.hienlh.ppm.state.assistant.assistantReturnOf
import com.hienlh.ppm.state.assistant.isDecisionCard
import com.hienlh.ppm.state.assistant.orderWaitingCards
import com.hienlh.ppm.state.assistant.switchedNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val WAITING_LABEL = "Waiting on you"
const val MANAGER_WAITING_NOTE = "This chat's reports to the PPM Assistant. Answer them here or in the Assistant."
const val MANAGER_ASSIGN_CONSEQUENCE = "After this, PPM Assistant can message this chat without asking, also while you are away."
const val MANAGER_ASSIGN_BYPASS = "This chat runs without approval prompts."
const val MANAGER_TOOLS_NEXT_START = "Manager tools arrive the next time this chat starts."
const val SET_MANAGER_TITLE = "Set as manager chat"

/** The "Manager" chip beside a session's title in Chat History (`ManagerChatBadge`). */
@Composable
fun ManagerChatBadge(model: AppModel, sessionId: String) {
    val store = model.assistant.store ?: return
    val view by store.view.collectAsState()
    if (!view.isManagerChat(sessionId)) return
    val tokens = LocalPpmTokens.current
    BasicText(
        "Manager",
        Modifier
            .background(tokens.accent.opacity(0.12).color, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .semantics { contentDescription = "PPM Assistant talks to this chat about the project" }
            .testTag("manager-badge-$sessionId"),
        style = ppmText(10.5f, FontWeight.SemiBold).copy(color = tokens.accent.color),
        maxLines = 1,
    )
}

/** Whether a chat may be offered the manager role (`canOfferManagerRole`). */
fun canOfferManagerRole(providerId: String?, designSlug: String?, projectName: String): Boolean =
    providerId == MANAGER_PROVIDER_ID && designSlug.isNullOrEmpty() && !isAssistantProject(projectName)

/** A row of the chat's ··· menu: "Set as manager chat" or "Unset manager chat". */
class MoreMenuManagerRole(val label: String, val unset: Boolean, val onClick: () -> Unit)

/**
 * The manager row of [tab]'s ··· menu, or null when the chat cannot take the role; draws the
 * "Set as manager chat" dialog while it is open.
 */
@Composable
fun managerRoleMenu(model: AppModel, tab: ChatTab): MoreMenuManagerRole? {
    val session = tab.session ?: return null
    val store = model.assistant.store ?: return null
    val view by store.view.collectAsState()
    var assigning by remember(session.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (assigning) SetManagerChatDialog(model, tab.project, session.id, session.title) { assigning = false }
    if (view.isManagerChat(session.id)) {
        return MoreMenuManagerRole("Unset manager chat", unset = true) {
            val api = model.api ?: return@MoreMenuManagerRole
            scope.launch {
                try {
                    api.unsetManagerChat(tab.project)
                    store.refreshManagers()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    model.toast(ErrorToastMessage("Could not unset the manager chat: ${e.message ?: e}"))
                }
            }
        }
    }
    val design = (model.tabMetadata(tab.tabId)?.get("designSlug") as? kotlinx.serialization.json.JsonPrimitive)?.content
    if (!canOfferManagerRole(tab.providerId, design, tab.project)) return null
    return MoreMenuManagerRole(SET_MANAGER_TITLE, unset = false) { assigning = true }
}

/** "Set as manager chat": what the role means before it is given, and which chat it replaces (`SetManagerChatDialog`). */
@Composable
fun SetManagerChatDialog(model: AppModel, projectName: String, sessionId: String, sessionTitle: String, onClose: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    var check by remember { mutableStateOf<ManagerAssignCheck?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(projectName, sessionId) {
        val api = model.api ?: return@LaunchedEffect
        try {
            check = api.checkManagerChat(projectName, sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }
    val c = check
    val replaces = c?.replacesSessionId != null
    AssistantSheet(
        title = SET_MANAGER_TITLE,
        onDismiss = onClose,
        modifier = Modifier.testTag("set-manager-dialog"),
        footer = {
            CardButton("Cancel", onClose, Modifier.weight(1f))
            CardButton(
                if (replaces) "Replace manager" else SET_MANAGER_TITLE,
                {
                    val api = model.api ?: return@CardButton
                    saving = true
                    error = null
                    scope.launch {
                        try {
                            api.setManagerChat(projectName, sessionId)
                            model.assistant.store?.refreshManagers()
                            onClose()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: e.toString()
                            saving = false
                        }
                    }
                },
                Modifier.weight(1f).testTag("set-manager-confirm"),
                variant = CardButtonVariant.PRIMARY,
                enabled = c != null && !c.already && !saving,
            )
        },
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicText(
                "${sessionTitle.ifEmpty { "This chat" }} becomes the $projectName manager: the chat PPM Assistant talks to about this project.",
                style = ppmText(14f).copy(color = tokens.text.color),
            )
            if (c == null && error == null) BasicText("Checking the chat…", style = ppmText(14f).copy(color = tokens.text3.color))
            if (c?.already == true) BasicText("This chat already is the $projectName manager.", style = ppmText(14f).copy(color = tokens.text3.color))
            if (replaces) {
                BasicText(
                    "This replaces the current manager, “${c?.replacesTitle?.ifEmpty { null } ?: "Untitled"}”. That chat keeps its history.",
                    Modifier
                        .fillMaxWidth()
                        .background(tokens.warning.opacity(0.1).color, RoundedCornerShape(6.dp))
                        .cssBorder(tokens.warning.opacity(0.4).color, RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    style = ppmText(14f).copy(color = tokens.text.color),
                )
            }
            if (c != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText("•  $MANAGER_ASSIGN_CONSEQUENCE", style = ppmText(14f).copy(color = tokens.text2.color))
                    if (c.permissionMode == "bypassPermissions") BasicText("•  $MANAGER_ASSIGN_BYPASS", style = ppmText(14f).copy(color = tokens.warning.color))
                    BasicText("•  $MANAGER_TOOLS_NEXT_START", style = ppmText(14f).copy(color = tokens.text2.color))
                }
            }
            error?.let { BasicText(it, style = ppmText(14f).copy(color = tokens.error.color)) }
        }
    }
}

/** What a manager chat shows above its conversation: the phone's way back to the Assistant, and its waiting cards. */
@Composable
fun ManagerChatBars(model: AppModel, tab: ChatTab) {
    AssistantBackBar(model, tab)
    ManagerChatWaitingCards(model, tab.session?.id)
}

/** "‹ Assistant", the chat's name, and once after the switch, a note saying what happened (`AssistantBackBar`). */
@Composable
private fun AssistantBackBar(model: AppModel, tab: ChatTab) {
    val metadata = model.tabMetadata(tab.tabId)
    val back = assistantReturnOf(metadata) ?: return
    val tokens = LocalPpmTokens.current
    val note = (metadata?.get("returnNote") as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
    LaunchedEffect(note, tab.tabId) {
        if (!note) return@LaunchedEffect
        delay(SWITCHED_NOTE_MS)
        model.assistant.dropReturnNote(tab.tabId)
    }
    Column(Modifier.fillMaxWidth().testTag("assistant-back-bar")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier
                    .height(44.dp)
                    .ppmClickable(shape = RoundedCornerShape(8.dp), pressedColor = tokens.accentWash.color) { model.assistant.returnToAssistant(tab.tabId, back) }
                    .padding(start = 4.dp, end = 8.dp)
                    .testTag("assistant-back"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PpmIcon(ProductIcon.ChevronLeft, 18.dp, tokens.accent.color)
                BasicText("Assistant", style = ppmText(14f, FontWeight.Medium).copy(color = tokens.accent.color))
            }
            BasicText("${tab.project} · Manager", style = ppmText(14f, FontWeight.SemiBold).copy(color = tokens.text.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (note) {
            BasicText(
                switchedNote(tab.project),
                Modifier
                    .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                    .fillMaxWidth()
                    .background(tokens.accentWash.color, RoundedCornerShape(8.dp))
                    .cssBorder(tokens.accentWashBorder.color, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                style = ppmText(12f, lineHeight = 18f).copy(color = tokens.text2.color),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.border.color))
    }
}

/** The cards a manager chat shows: its own, minus its requests, in the order the user steps through (`managerChatWaitingCards`). */
fun managerChatWaitingCards(cards: List<AssistantCard>, sessionId: String): List<AssistantCard> =
    orderWaitingCards(cards.filter { it.sessionId == sessionId && it.kind !in ANSWERABLE_CARD_KINDS })

/**
 * A manager chat's own cards still waiting on the user, pinned above its conversation
 * (`ManagerChatWaitingCards`): one at a time with ‹ ›, its decisions as one set. A tablet shows
 * the card under the header; a phone names it on one row and opens it in a sheet.
 */
@Composable
fun ManagerChatWaitingCards(model: AppModel, sessionId: String?) {
    val store = model.assistant.store ?: return
    val view by store.view.collectAsState()
    if (sessionId == null || !view.isManagerChat(sessionId)) return
    val mine = managerChatWaitingCards(view.open.orEmpty(), sessionId)
    if (mine.isEmpty()) return
    val decisions = mine.filter(::isDecisionCard)
    val entries = if (decisions.isNotEmpty()) listOf(decisions.first()) + mine.filterNot(::isDecisionCard) else mine
    val keyOf = { c: AssistantCard -> if (isDecisionCard(c)) "decision-set" else c.id }
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    var currentKey by remember { mutableStateOf<String?>(null) }
    var open by remember(mobile) { mutableStateOf(!mobile) }
    var replyId by remember { mutableStateOf<String?>(null) }
    val index = entries.indexOfFirst { keyOf(it) == currentKey }.coerceAtLeast(0)
    val current = entries[index]
    val step = { by: Int -> currentKey = keyOf(entries[(index + by + entries.size) % entries.size]) }
    val summary = if (isDecisionCard(current)) "${decisions.size} decision${if (decisions.size == 1) "" else "s"} waiting" else listOfNotNull(current.projectName, current.title).joinToString(" · ")
    val host = remember { CardReplyHost(inManagerChat = true) { card -> replyId = card.id } }
    val body: @Composable () -> Unit = {
        CompositionLocalProvider(LocalCardReplyHost provides host) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isDecisionCard(current)) DecisionSetView(model, store, scope = { it.sessionId == sessionId }, startOpen = true)
                else AssistantCardView(model, current)
                if (replyId == current.id) CardReplyBar(model, current, onClose = { replyId = null })
            }
        }
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .padding(horizontal = if (mobile) 8.dp else 12.dp, vertical = 6.dp)
            .fillMaxWidth()
            .background(tokens.panel2.opacity(0.6).color, shape)
            .cssBorder(tokens.border.color, shape)
            .testTag("manager-waiting-cards"),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).ppmClickable(shape = shape) { open = !open }.padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(24.dp).background(tokens.error.opacity(0.1).color, CircleShape), contentAlignment = Alignment.Center) {
                PpmIcon(ProductIcon.BellRing, 14.dp, tokens.error.color)
            }
            BasicText(WAITING_LABEL, style = ppmText(13f, FontWeight.SemiBold).copy(color = tokens.text.color))
            WaitingBadge(entries.size)
            BasicText(if (mobile || !open) summary else "", Modifier.weight(1f), style = ppmText(12f).copy(color = tokens.text2.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (entries.size > 1) {
                HeadIconButton(ProductIcon.ChevronLeft, "Previous waiting card", { step(-1) })
                BasicText("${index + 1}/${entries.size}", style = ppmText(11f, FontWeight.Medium).copy(color = tokens.text2.color))
                HeadIconButton(ProductIcon.ChevronRight, "Next waiting card", { step(1) })
            } else {
                Spacer(Modifier.size(4.dp))
            }
        }
        BasicText(MANAGER_WAITING_NOTE, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp), style = ppmText(11f).copy(color = tokens.text3.color))
        if (!mobile && open) {
            Box(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) { body() }
        }
    }
    if (mobile && open) {
        AssistantSheet(title = WAITING_LABEL, onDismiss = { open = false }, subtitle = if (entries.size > 1) "${index + 1}/${entries.size}" else null) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) { body() }
        }
    }
}
