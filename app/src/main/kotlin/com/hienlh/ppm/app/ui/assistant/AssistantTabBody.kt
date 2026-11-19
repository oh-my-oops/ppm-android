package com.hienlh.ppm.app.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.assistant
import com.hienlh.ppm.app.ui.ChatPane
import com.hienlh.ppm.app.ui.PpmTextField
import com.hienlh.ppm.chat.ChatTimeline
import com.hienlh.ppm.chat.ChatTimelineBuild
import com.hienlh.ppm.chat.ChatTimelineItem
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.AssistantCard
import com.hienlh.ppm.protocol.ManagerChatSummary
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.ChatItem
import com.hienlh.ppm.state.assistant.AssistantStore
import com.hienlh.ppm.state.assistant.AssistantView
import com.hienlh.ppm.state.assistant.ReplyImage
import com.hienlh.ppm.state.assistant.SendOutcome
import com.hienlh.ppm.state.assistant.collapsedWakeTurns
import com.hienlh.ppm.state.assistant.hiddenByProjectFilter
import com.hienlh.ppm.state.assistant.isDecisionCard
import com.hienlh.ppm.state.assistant.managerDotState
import com.hienlh.ppm.state.assistant.reportsRead
import com.hienlh.ppm.state.assistant.streamCards
import com.hienlh.ppm.state.assistant.wakeTurnText
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.launch

const val QUIET_SWITCH_LABEL = "Quiet while I'm at PPM"
const val FILTER_EXPLAINER = "When filtered, the stream keeps only that project's cards and the Assistant's turns that asked or messaged its manager chat."
const val NO_PROVIDER_TEXT = "No configured AI provider can run the Assistant. Enable Claude or Codex in Settings → AI Provider."
const val PROVIDERS_FAILED_TEXT = "Could not load the AI providers. Reopen the Assistant to try again."
const val PREPARING_TEXT = "Preparing the Assistant…"

/**
 * The PPM Assistant's tab (`AssistantWindow`): its head (name, Telegram's bell, settings), the
 * manager chats' chips and the stream's filter, then its chat, with the cards, the wake-turn lines
 * and the set of decisions placed among the messages, the Waiting chip first in the context row
 * and the reply bar in place of the composer while a card is being answered. The same on a phone
 * (a full-screen tab) and a tablet (usually a floating window).
 */
@Composable
fun AssistantTabBody(model: AppModel, tab: WorkspaceTab) {
    val assistant = model.assistant
    val store = assistant.store
    val tokens = LocalPpmTokens.current
    if (store == null) {
        Notice(PREPARING_TEXT)
        return
    }
    val view by store.view.collectAsState()
    val providers = assistant.providers
    LaunchedEffect(tab.metadata, view.mainLoaded, view.main, providers) { assistant.settle(tab, view) }
    LaunchedEffect(view.open, view.cards) { store.syncDecisionOrder() }
    val chat = model.chat(tab.id)
    Column(Modifier.fillMaxSize().background(tokens.panel.color).testTag("assistant-tab")) {
        AssistantHead(model, store, view, tab)
        ManagerChipRow(model, store, view)
        if (assistant.oneOff(tab, view)) OneOffBar { assistant.backToMain(tab.id) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                assistant.chatReady(tab) && chat != null -> key(chat) { AssistantChat(model, store, view, chat) }
                providers == null && !assistant.providersError -> Notice(PREPARING_TEXT)
                providers.isNullOrEmpty() -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Notice(if (assistant.providersError) PROVIDERS_FAILED_TEXT else NO_PROVIDER_TEXT, fill = false)
                    WaitingList(model, view)
                }
                else -> Notice(PREPARING_TEXT)
            }
        }
    }
}

@Composable
private fun Notice(text: String, fill: Boolean = true) {
    val tokens = LocalPpmTokens.current
    Box(if (fill) Modifier.fillMaxSize() else Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        BasicText(
            text,
            Modifier.padding(horizontal = 24.dp).testTag("assistant-notice"),
            style = ppmText(13f, lineHeight = 19f).copy(color = tokens.text2.color, textAlign = TextAlign.Center),
        )
    }
}

/** The head: the Assistant's name, Telegram's label and quiet bell, and the settings gear. */
@Composable
private fun AssistantHead(model: AppModel, store: AssistantStore, view: AssistantView, tab: WorkspaceTab) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val scope = rememberCoroutineScope()
    val sessionId = tab.metadata["sessionId"].string
    val telegram = view.telegram
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (mobile) 52.dp else 40.dp)
            .padding(start = 12.dp, end = 4.dp)
            .testTag("assistant-head"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PpmIcon(ProductIcon.BotMessageSquare, if (mobile) 18.dp else 16.dp, tokens.accent.color)
        BasicText(view.shortName, Modifier.weight(1f, fill = false), style = ppmText(if (mobile) 15f else 13f, FontWeight.SemiBold).copy(color = tokens.text.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (telegram != null && sessionId != null && sessionId in telegram.chatSessionIds) {
            BasicText(
                "Telegram",
                Modifier.background(tokens.info.opacity(0.12).color, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 2.dp).testTag("assistant-telegram-label"),
                style = ppmText(11f, FontWeight.Medium).copy(color = tokens.info.color),
            )
        }
        Spacer(Modifier.weight(1f))
        if (telegram?.enabled == true) {
            val on = telegram.quietWhileInPpm
            HeadIconButton(
                if (on) ProductIcon.BellOff else ProductIcon.Bell,
                if (on) "$QUIET_SWITCH_LABEL: on — Telegram stays silent while you are here. Click to turn off."
                else "$QUIET_SWITCH_LABEL: off — Telegram rings for every message. Click to turn on.",
                {
                    scope.launch {
                        val error = store.setQuiet(!on)
                        model.toast(
                            when {
                                error != null -> ErrorToastMessage("Could not change it", error)
                                !on -> ErrorToastMessage("Telegram stays silent while you are at PPM", kind = ErrorToastMessage.Kind.INFO)
                                else -> ErrorToastMessage("Telegram rings for every message again", kind = ErrorToastMessage.Kind.INFO)
                            },
                        )
                    }
                },
                tag = "assistant-quiet-bell",
            )
        }
        HeadIconButton(ProductIcon.Settings, "PPM Assistant settings", { model.openSettings("assistant") }, tag = "assistant-settings")
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.border.color))
}

/** The manager chats as chips, and the stream's project filter (`ManagerChipRow`): 26 dp on a phone, the chip row's height. */
@Composable
private fun ManagerChipRow(model: AppModel, store: AssistantStore, view: AssistantView) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val managers = view.managers ?: return
    var picking by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .semantics { contentDescription = "Manager chats" }
            .testTag("assistant-manager-chips"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val pillShape = RoundedCornerShape(999.dp)
        Row(
            Modifier
                .heightIn(min = if (mobile) 26.dp else 28.dp)
                .background((if (view.filter != null) tokens.accentWash else tokens.panel2).color, pillShape)
                .cssBorder((if (view.filter != null) tokens.accentWashBorder else tokens.border).color, pillShape)
                .ppmClickable(shape = pillShape) { picking = true }
                .padding(horizontal = 10.dp)
                .testTag("assistant-filter-pill"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PpmIcon(ProductIcon.Filter, 13.dp, (if (view.filter != null) tokens.accent else tokens.text2).color)
            BasicText("Filter: ${view.filter ?: "All"}", style = ppmText(12f, FontWeight.Medium).copy(color = (if (view.filter != null) tokens.accent else tokens.text2).color), maxLines = 1)
        }
        if (managers.isEmpty()) {
            BasicText("No manager chats yet — set one from a chat's menu.", style = ppmText(12f).copy(color = tokens.text3.color), maxLines = 1)
        }
        for (m in managers) ManagerChip(model, store, view, m)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.borderSoft.color))
    if (picking) FilterSheet(store, view, managers) { picking = false }
}

@Composable
private fun ManagerChip(model: AppModel, store: AssistantStore, view: AssistantView, m: ManagerChatSummary) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val dot = managerDotState(m.phase, m.waiting)
    val waiting = view.waiting.byProject[m.projectName] ?: m.waiting
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier
            .heightIn(min = if (mobile) 26.dp else 28.dp)
            .background(tokens.panel2.color, shape)
            .cssBorder(tokens.border.color, shape)
            .testTag("assistant-manager-chip-${m.projectName}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .heightIn(min = if (mobile) 26.dp else 28.dp)
                .ppmClickable(shape = shape) { model.assistant.openManagerChat(m.projectName, m.sessionId, null, m.providerId) }
                .semantics { contentDescription = "Open the ${m.projectName} manager chat · ${dot.label}" + if (waiting > 0) " · $waiting waiting" else "" }
                .padding(start = 10.dp, end = if (waiting > 0) 4.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ManagerDotView(dot)
            BasicText(m.projectName, style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text.color), maxLines = 1)
        }
        if (waiting > 0) {
            Box(
                Modifier
                    .heightIn(min = if (mobile) 26.dp else 28.dp)
                    .ppmClickable(shape = shape) {
                        store.setFilter(m.projectName)
                        view.open.orEmpty().firstOrNull { it.projectName == m.projectName && it.isWaiting }?.let { store.jumpTo(it.id) }
                    }
                    .semantics { contentDescription = "Show the $waiting card${if (waiting == 1) "" else "s"} from ${m.projectName} waiting on you" }
                    .padding(start = 2.dp, end = 8.dp),
                contentAlignment = Alignment.Center,
            ) { WaitingBadge(waiting) }
        }
    }
}

/** "Filter by project": All, then each project with a manager chat or a waiting card. */
@Composable
private fun FilterSheet(store: AssistantStore, view: AssistantView, managers: List<ManagerChatSummary>, onDismiss: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val projects = (managers.map { it.projectName } + view.waiting.byProject.keys).distinct().sorted()
    AssistantSheet(
        title = "Filter by project",
        onDismiss = onDismiss,
        modifier = Modifier.testTag("assistant-filter-sheet"),
        tabletAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.fillMaxWidth().then(if (mobile) Modifier.fillMaxSize() else Modifier).verticalScroll(rememberScrollState())) {
            BasicText(FILTER_EXPLAINER, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = ppmText(12f, lineHeight = 17f).copy(color = tokens.text3.color))
            FilterRow("All", view.filter == null, view.waiting.total) {
                store.setFilter(null)
                onDismiss()
            }
            for (p in projects) {
                FilterRow(p, view.filter == p, view.waiting.byProject[p] ?: 0) {
                    store.setFilter(p)
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun FilterRow(label: String, on: Boolean, waiting: Int, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .ppmClickable(onClick = onClick)
            .padding(horizontal = 16.dp)
            .testTag("assistant-filter-$label"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { if (on) PpmIcon(ProductIcon.Check, 16.dp, tokens.accent.color) }
        BasicText(label, Modifier.weight(1f), style = ppmText(15f).copy(color = tokens.text.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
        WaitingBadge(waiting)
    }
}

/** "One-off session · Back to main" while the tab shows a session other than the main one. */
@Composable
private fun OneOffBar(onBack: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier.fillMaxWidth().background(tokens.warning.opacity(0.08).color).padding(horizontal = 12.dp, vertical = 4.dp).testTag("assistant-one-off"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText("One-off session", Modifier.weight(1f), style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text2.color))
        CardButton("Back to main", onBack, icon = ProductIcon.ArrowLeft, variant = CardButtonVariant.GHOST, modifier = Modifier.testTag("assistant-back-to-main"))
    }
}

/** Every waiting card, when there is no chat to place them in (`AssistantWaitingCards`). */
@Composable
private fun WaitingList(model: AppModel, view: AssistantView) {
    val cards = view.waitingInOrder
    if (cards.isEmpty()) return
    val tokens = LocalPpmTokens.current
    Column(Modifier.fillMaxWidth().padding(12.dp).testTag("assistant-waiting-list"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BasicText("Waiting on you · ${cards.size}", style = ppmText(13f, FontWeight.SemiBold).copy(color = tokens.text.color))
        for (card in cards) key(card.id) { AssistantCardView(model, card) }
    }
}

/** The Assistant's chat, with the cards placed among its messages. */
@Composable
private fun AssistantChat(model: AppModel, store: AssistantStore, view: AssistantView, chat: com.hienlh.ppm.app.ChatTab) {
    val timeline = remember(view.cards, view.open, view.filter, view.managers, view.nextBefore) { assistantTimeline(model, store, view) }
    val target = view.replyTarget
    val host = remember(store) { CardReplyHost(inManagerChat = false) { card -> store.replyTo(card) } }
    CompositionLocalProvider(LocalCardReplyHost provides host) {
        ChatPane(
            model,
            chat,
            timeline = timeline,
            composerOverride = target?.let { card -> { CardReplyBar(model, card, onClose = { store.replyTo(null) }) } },
            contextChip = { WaitingChip(store, view) },
        )
    }
}

/** The stream: every card by its time, waiting decisions as stubs leading to the set, wake turns folded to a line. */
internal fun assistantTimeline(model: AppModel, store: AssistantStore, view: AssistantView): ChatTimeline {
    val filter = view.filter
    val cards = streamCards(view.cards.orEmpty(), view.open.orEmpty(), filter)
    return ChatTimeline(
        build = { rows: List<ChatItem> ->
            val hidden = HashSet<String>()
            val items = ArrayList<ChatTimelineItem>()
            if (filter != null) hidden += hiddenByProjectFilter(rows, filter, view.managers.orEmpty().firstOrNull { it.projectName == filter }?.sessionId)
            for (wake in collapsedWakeTurns(rows)) {
                if (wake.ids.all { it in hidden }) continue
                hidden += wake.ids
                val text = wakeTurnText(reportsRead(cards, wake.since, wake.at))
                items += ChatTimelineItem("wake-${wake.ids.first()}", wake.at) { WakeTurnLine(text) }
            }
            for (card in cards) {
                val waitingDecision = card.isOpen && isDecisionCard(card)
                items += ChatTimelineItem("card-${card.id}", card.createdAt, keep = card.isWaiting) {
                    if (waitingDecision) DecisionSetStub(model, card) else StreamCard(model, card)
                }
            }
            if (cards.any { it.isOpen && isDecisionCard(it) }) {
                items += ChatTimelineItem("decision-set", Long.MAX_VALUE, keep = true) { DecisionSetView(model, store) }
            }
            ChatTimelineBuild(items, hidden)
        },
        manualPaging = true,
        hasEarlier = view.nextBefore != null,
        onLoadEarlier = store::loadEarlier,
    )
}

/** The context row's "Waiting" chip: the count, ‹ › through the waiting cards, and the ones the filter hides (`WaitingChip`). */
@Composable
private fun WaitingChip(store: AssistantStore, view: AssistantView) {
    val cards = view.waitingInOrder
    val elsewhere = if (view.filter == null) 0 else view.waiting.total - cards.size
    if (cards.isEmpty() && elsewhere <= 0) return
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    var index by remember { mutableIntStateOf(0) }
    val at = if (cards.isEmpty()) 0 else index.coerceIn(0, cards.size - 1)
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier
            .heightIn(min = if (mobile) 32.dp else 26.dp)
            .background(tokens.error.opacity(0.08).color, shape)
            .cssBorder(tokens.error.opacity(0.3).color, shape)
            .testTag("assistant-waiting-chip"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (cards.isNotEmpty()) {
            Row(
                Modifier.ppmClickable(shape = shape) { store.jumpTo(cards[at].id) }.padding(start = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PpmIcon(ProductIcon.BellRing, 12.dp, tokens.error.color)
                BasicText("Waiting", style = ppmText(11f, FontWeight.Medium).copy(color = tokens.error.color))
                WaitingBadge(cards.size)
            }
            if (cards.size > 1) {
                ChipStep(ProductIcon.ChevronLeft, "Previous waiting card") {
                    index = (at - 1 + cards.size) % cards.size
                    store.jumpTo(cards[index].id)
                }
                BasicText("${at + 1}/${cards.size}", style = ppmText(11f).copy(color = tokens.text2.color))
                ChipStep(ProductIcon.ChevronRight, "Next waiting card") {
                    index = (at + 1) % cards.size
                    store.jumpTo(cards[index].id)
                }
            }
        }
        if (elsewhere > 0) {
            BasicText(
                "+$elsewhere",
                Modifier
                    .ppmClickable(shape = shape) { store.setFilter(null) }
                    .semantics { contentDescription = "$elsewhere more waiting in other projects: show all projects" }
                    .padding(horizontal = 8.dp),
                style = ppmText(11f, FontWeight.Medium).copy(color = tokens.text2.color),
            )
        }
    }
}

@Composable
private fun ChipStep(icon: ProductIcon, label: String, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Box(
        Modifier.size(if (LocalPpmMobile.current) 32.dp else 22.dp).ppmClickable(shape = RoundedCornerShape(999.dp), onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { PpmIcon(icon, 12.dp, tokens.text2.color) }
}

/**
 * The reply bar (`CardReplyBar`): what the user writes goes to the card's manager chat word for
 * word, with up to five pictures; the server may ask first ("Send anyway").
 */
@Composable
fun CardReplyBar(model: AppModel, card: AssistantCard, onClose: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val store = model.assistant.store ?: return
    val scope = rememberCoroutineScope()
    val state = rememberTextFieldState()
    var images by remember(card.id) { mutableStateOf<List<ReplyImage>>(emptyList()) }
    var question by remember(card.id) { mutableStateOf<String?>(null) }
    var error by remember(card.id) { mutableStateOf<String?>(null) }
    var tooMany by remember(card.id) { mutableStateOf(false) }
    var sending by remember(card.id) { mutableStateOf(false) }
    val attach = rememberReplyImagePicker(
        have = { images.size },
        onPicked = { added ->
            tooMany = false
            images = images + added
        },
        onTooMany = { tooMany = true },
    )
    fun send(force: Boolean) {
        val text = state.text.toString()
        if ((text.isBlank() && images.isEmpty()) || sending) return
        sending = true
        error = null
        scope.launch {
            when (val outcome = store.sendReplyTo(card, text, force, images.map { it.image })) {
                is SendOutcome.Sent -> {
                    state.clearText()
                    images = emptyList()
                    question = null
                    onClose()
                }
                is SendOutcome.Confirm -> question = outcome.question
                is SendOutcome.Elsewhere -> error = outcome.message
                is SendOutcome.Changed -> error = outcome.message
                is SendOutcome.Failed -> error = outcome.message
            }
            sending = false
        }
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .fillMaxWidth()
            .background(tokens.panel.color, shape)
            .cssBorder(tokens.accent.opacity(0.5).color, shape)
            .padding(10.dp)
            .testTag("assistant-reply-bar"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PpmIcon(ProductIcon.Undo2, 13.dp, tokens.accent.color)
            Column(Modifier.weight(1f)) {
                BasicText("Replying to card", style = ppmText(11f, FontWeight.Medium).copy(color = tokens.accent.color))
                BasicText(listOfNotNull(card.projectName, card.title).joinToString(" · "), style = ppmText(12f).copy(color = tokens.text2.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HeadIconButton(ProductIcon.X, "Stop replying to this card", onClose, tag = "assistant-reply-close")
        }
        PpmTextField(
            state,
            "Your words go to the manager chat word for word…",
            Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("assistant-reply-input"),
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 6),
        )
        ReplyImageStrip(images) { gone -> images = images - gone }
        question?.let { BasicText(it, style = ppmText(12f).copy(color = tokens.warning.color)) }
        error?.let { BasicText(it, style = ppmText(12f).copy(color = tokens.error.color)) }
        if (tooMany) BasicText(TOO_MANY_PICTURES, style = ppmText(12f).copy(color = tokens.warning.color))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeadIconButton(ProductIcon.Paperclip, "Attach a picture", attach, tag = "assistant-reply-attach")
            Spacer(Modifier.weight(1f))
            if (question != null) {
                CardButton("Send anyway", { send(force = true) }, variant = CardButtonVariant.PRIMARY, enabled = !sending)
            } else {
                CardButton(
                    "Send to the manager chat",
                    { send(force = false) },
                    icon = ProductIcon.Send,
                    variant = CardButtonVariant.PRIMARY,
                    enabled = !sending && (state.text.isNotBlank() || images.isNotEmpty()),
                    modifier = Modifier.testTag("assistant-reply-send"),
                )
            }
        }
    }
}
