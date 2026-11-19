package com.hienlh.ppm.app.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.assistant
import com.hienlh.ppm.app.ui.PpmTextField
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.AssistantCard
import com.hienlh.ppm.state.assistant.AssistantStore
import com.hienlh.ppm.state.assistant.DecisionAnswer
import com.hienlh.ppm.state.assistant.DecisionSetState
import com.hienlh.ppm.state.assistant.SetFlash
import com.hienlh.ppm.state.assistant.activeTabIndex
import com.hienlh.ppm.state.assistant.decisionTabs
import com.hienlh.ppm.state.assistant.nextWaitingTab
import com.hienlh.ppm.state.assistant.answerIsReady
import com.hienlh.ppm.state.assistant.answerLabel
import com.hienlh.ppm.state.assistant.cardTime
import com.hienlh.ppm.state.assistant.currentPick
import com.hienlh.ppm.state.assistant.decisionTiles
import com.hienlh.ppm.state.assistant.escapeHiddenCharacters
import com.hienlh.ppm.state.assistant.isDecisionCard
import com.hienlh.ppm.state.assistant.isStaleForReply
import com.hienlh.ppm.state.assistant.orderWaitingCards
import com.hienlh.ppm.state.assistant.projectCounts
import com.hienlh.ppm.state.assistant.sameAnswer
import com.hienlh.ppm.state.assistant.sendPlan

/**
 * The waiting decisions as one set (`DecisionSet`): a folded line with where they come from, and
 * open, one tab per card, each answered with a tile or words, sent alone or all together after a
 * review. Answers stay on this device until sent.
 *
 * Every tab is a card that still waits (or one whose answer is on its way from here), so the
 * heading, the strip and "N/M" count the same cards the Waiting chip does. A card answered anywhere
 * leaves at once; when it was the one shown, the card now in its place opens. A send from here says
 * how it went in one line above the card shown.
 */
@Composable
fun DecisionSetView(
    model: AppModel,
    store: AssistantStore,
    modifier: Modifier = Modifier,
    /** Which waiting decisions the set holds: a manager chat's own; else the stream's filter. */
    scope: ((AssistantCard) -> Boolean)? = null,
    startOpen: Boolean = false,
) {
    val view by store.view.collectAsState()
    val waiting = remember(view.open, view.filter, scope) {
        orderWaitingCards(view.open.orEmpty()).filter { isDecisionCard(it) && (scope?.invoke(it) ?: (view.filter == null || it.projectName == view.filter)) }
    }
    LaunchedEffect(view.open, view.cards) { store.syncDecisionOrder() }
    val d = view.decisions
    val tabs = decisionTabs(d.order, waiting, d.sending) { c -> scope?.invoke(c) ?: (view.filter == null || c.projectName == view.filter) }
    var open by remember { mutableStateOf(startOpen) }
    var activeId by remember { mutableStateOf<String?>(null) }
    var lastIndex by remember { mutableIntStateOf(0) }
    var review by remember { mutableStateOf(false) }
    val ids = tabs.map { it.id }
    val index = activeTabIndex(ids, activeId, lastIndex)
    /** The user moved: the line about the last send is about a card no longer in view. */
    fun show(id: String) {
        store.clearFlash()
        activeId = id
    }
    // A jump to a card of the set opens the set on it.
    LaunchedEffect(view.jumpNonce) {
        val id = view.jumpCardId ?: return@LaunchedEffect
        if (id in ids) {
            open = true
            show(id)
        }
    }
    // The tab shown left (answered elsewhere): the card now in its place is the one shown, and stays
    // shown when other cards come and go.
    val shownId = tabs.getOrNull(index)?.id
    LaunchedEffect(index, shownId) {
        if (index >= 0) lastIndex = index
        if (shownId != null && activeId != null && shownId != activeId) activeId = shownId
    }
    if (tabs.isEmpty()) return
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val current = tabs[index]
    val plan = sendPlan(waiting.filter { it.id !in d.sending }, d.picks)
    val count = tabs.size
    val picked = plan.size
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .fillMaxWidth()
            .background(tokens.panel.color, shape)
            .cssBorder(tokens.warning.opacity(0.4).color, shape)
            .testTag("assistant-decision-set"),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .ppmClickable(shape = shape) { open = !open }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PpmIcon(ProductIcon.BellRing, 16.dp, tokens.warning.color)
            Column(Modifier.weight(1f)) {
                BasicText(
                    "$count decision${if (count == 1) "" else "s"} waiting",
                    Modifier.testTag("assistant-decision-heading"),
                    style = ppmText(13f, FontWeight.SemiBold).copy(color = tokens.text.color),
                )
                BasicText(
                    projectCounts(waiting) + if (picked > 0) " · $picked picked, not sent" else "",
                    style = ppmText(12f).copy(color = tokens.text2.color), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            BasicText(if (open) "Fold" else "Open", style = ppmText(12f, FontWeight.Medium).copy(color = tokens.accent.color))
        }
        if (!open) return@Column
        Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.border.color))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tabs.forEachIndexed { i, t ->
                val on = t.id == current.id
                val pick = currentPick(t, d.picks)
                val mark = when {
                    t.kind == "urgent" -> "!"
                    pick != null && answerIsReady(t, pick.answer) -> "●"
                    else -> "${i + 1}"
                }
                val tabShape = RoundedCornerShape(8.dp)
                Row(
                    Modifier
                        .heightIn(min = if (mobile) 44.dp else 32.dp)
                        .background((if (on) tokens.accentWash else tokens.panel2).color, tabShape)
                        .cssBorder((if (on) tokens.accent else tokens.border).color, tabShape)
                        .ppmClickable(shape = tabShape) { show(t.id) }
                        .padding(horizontal = 10.dp)
                        .testTag("assistant-decision-tab-${t.id}"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(mark, style = ppmText(12f, FontWeight.SemiBold).copy(color = (if (t.kind == "urgent") tokens.error else tokens.text2).color))
                    BasicText(t.projectName ?: "Assistant", style = ppmText(12f).copy(color = tokens.text.color), maxLines = 1)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.border.color))
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            d.flash?.let { FlashLine(it) }
            DecisionTabBody(model, store, current, d)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.border.color))
        val currentReady = currentPick(current, d.picks)?.let { answerIsReady(current, it.answer) } == true
        val sending = current.id in d.sending
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeadIconButton(ProductIcon.ChevronLeft, "Previous card", { show(ids[(index - 1).coerceAtLeast(0)]) })
            HeadIconButton(ProductIcon.ChevronRight, "Next card", { show(ids[(index + 1).coerceAtMost(ids.size - 1)]) })
            Spacer(Modifier.weight(1f))
            CardButton(
                if (sending) "Sending…" else "Send this one",
                {
                    // Held on this card while it goes; once it leaves, the next card that waits opens.
                    val next = nextWaitingTab(ids, current.id, d.sending.keys)
                    show(current.id)
                    store.sendOne(current) { if (next != null) activeId = next }
                },
                icon = ProductIcon.Send,
                enabled = currentReady && !sending,
                modifier = Modifier.testTag("assistant-decision-send-one"),
            )
            CardButton(
                "Send all answered (${plan.size}/$count)",
                { review = true },
                variant = CardButtonVariant.PRIMARY,
                enabled = plan.isNotEmpty() && d.sending.isEmpty(),
                modifier = Modifier.testTag("assistant-decision-send-all"),
            )
        }
    }
    if (review) {
        val left = waiting.filter { c -> plan.none { it.first.id == c.id } }
        ReviewSheet(plan, left, onBack = { review = false }, onSend = {
            review = false
            store.sendAll(plan.map { it.first })
        })
    }
}

/** One line about the last send from here, the card itself having left the set (`FlashLine`). */
@Composable
private fun FlashLine(flash: SetFlash) {
    val tokens = LocalPpmTokens.current
    val ink = if (flash.sent) tokens.success else tokens.text2
    Row(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag("assistant-decision-flash"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PpmIcon(if (flash.sent) ProductIcon.Check else ProductIcon.Info, 14.dp, ink.color)
        BasicText(flash.text, Modifier.weight(1f), style = ppmText(12f).copy(color = ink.color))
    }
}

/** One waiting card in the set: what it asks, its tiles, and the words field (`DecisionSetCard`). */
@Composable
private fun DecisionTabBody(model: AppModel, store: AssistantStore, card: AssistantCard, d: DecisionSetState) {
    val tokens = LocalPpmTokens.current
    val pick = currentPick(card, d.picks)?.answer
    val tiles = decisionTiles(card)
    val action = rememberCardAction()
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val urgent = card.kind == "urgent"
        TonePill(if (urgent) "Urgent" else "Needs your decision", if (urgent) ProductIcon.TriangleAlert else ProductIcon.BellRing, tokens.error.opacity(0.1), tokens.error)
        card.projectName?.let { ProjectPill(it, Modifier.weight(1f, fill = false)) }
        Spacer(Modifier.weight(1f))
        BasicText(cardTime(card.createdAt), style = ppmText(11f).copy(color = tokens.text3.color))
    }
    card.provenance?.let { BasicText(it, style = ppmText(11f, italic = true).copy(color = tokens.text3.color)) }
    BasicText(card.title, Modifier.testTag("assistant-card-title"), style = ppmText(15f, FontWeight.SemiBold, lineHeight = 20f).copy(color = tokens.text.color))
    if (card.body.isNotEmpty()) CardText(card.body, fold = false)
    CardImages(model, card.payload.images)
    card.payload.draft?.let { draft ->
        CardDraftView(draft)
        if (tiles.isNotEmpty()) BasicText("Bound to this draft (sha256 ${draft.sha256.take(8)}…)", style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text3.color))
    }
    BasicText(if (tiles.isNotEmpty()) "CHOOSE ONE" else "YOUR ANSWER", style = ppmText(11f, FontWeight.Medium).copy(color = tokens.text3.color))
    tiles.forEachIndexed { i, tile ->
        val on = sameAnswer(pick, tile.answer)
        val shape = RoundedCornerShape(8.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .background((if (on) tokens.accentWash else tokens.panel).color, shape)
                .cssBorder((if (on) tokens.accent else tokens.border).color, shape)
                .ppmClickable(shape = shape) { store.pick(card, if (on) null else tile.answer) }
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("assistant-decision-tile-${tile.key}"),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(24.dp).background((if (on) tokens.accent else tokens.panel2).color, CircleShape), contentAlignment = Alignment.Center) {
                if (on) PpmIcon(ProductIcon.Check, 14.dp, tokens.accentFg.color)
                else BasicText("${i + 1}", style = ppmText(11f, FontWeight.SemiBold).copy(color = tokens.text2.color))
            }
            Column(Modifier.weight(1f)) {
                BasicText(escapeHiddenCharacters(tile.label), style = ppmText(14f, FontWeight.Medium).copy(color = tokens.text.color))
                if (tile.description.isNotEmpty()) BasicText(escapeHiddenCharacters(tile.description), style = ppmText(13f).copy(color = tokens.text2.color))
            }
        }
    }
    if (tiles.isEmpty() && card.status == "open") {
        val note = when {
            card.payload.draft != null -> "This draft is too long or holds a hidden value, so it cannot be approved here. Answer with Reply, or open the manager chat."
            card.payload.options.isNotEmpty() -> "These choices are too long or hold a hidden value, so they cannot be pressed here. Answer with Reply, or open the manager chat."
            else -> "This card is not shown whole here. Answer in words, or open the manager chat."
        }
        BasicText(note, style = ppmText(12f).copy(color = tokens.text3.color))
    }
    if (card.sessionId != null) OtherAnswerField(store, card, pick, hasTiles = tiles.isNotEmpty())
    d.notices[card.id]?.let { BasicText(it, style = ppmText(12f).copy(color = tokens.warning.color)) }
    d.confirms[card.id]?.let { question ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText(question, style = ppmText(12f).copy(color = tokens.warning.color))
            CardButton("Send anyway", { store.sendOne(card, confirmAge = true) }, variant = CardButtonVariant.PRIMARY)
        }
    }
    action.error?.let { BasicText(it, style = ppmText(12f).copy(color = tokens.error.color)) }
    ButtonRow {
        CardButton("Open chat", { model.openCardChat(card) }, icon = ProductIcon.ExternalLink, variant = CardButtonVariant.GHOST, secondary = true, enabled = card.sessionId != null)
        CardButton("Dismiss", { action.run { store.dismissDecision(card) } }, icon = ProductIcon.X, variant = CardButtonVariant.GHOST, secondary = true, enabled = !action.busy)
    }
}

/** "Other" / "Your answer": words, and up to five pictures, for the manager chat word for word. */
@Composable
private fun OtherAnswerField(store: AssistantStore, card: AssistantCard, pick: DecisionAnswer?, hasTiles: Boolean) {
    val tokens = LocalPpmTokens.current
    val reply = pick as? DecisionAnswer.Reply
    val state = rememberTextFieldState(reply?.text.orEmpty())
    var tooMany by remember(card.id) { mutableStateOf(false) }
    val images = reply?.images.orEmpty()
    // What is typed is the pick; clearing it takes the pick back unless pictures remain.
    LaunchedEffect(card.id) {
        snapshotFlow { state.text.toString() }.collect { text ->
            val now = (store.view.value.decisions.picks[card.id]?.answer as? DecisionAnswer.Reply)
            val pics = now?.images.orEmpty()
            if (text.isBlank() && pics.isEmpty()) {
                if (now != null) store.pick(card, null)
            } else if (now?.text != text) {
                store.pick(card, DecisionAnswer.Reply(text, pics))
            }
        }
    }
    val attach = rememberReplyImagePicker(
        have = { (store.view.value.decisions.picks[card.id]?.answer as? DecisionAnswer.Reply)?.images.orEmpty().size },
        onPicked = { added ->
            if (added.isEmpty()) return@rememberReplyImagePicker
            tooMany = false
            val now = store.view.value.decisions.picks[card.id]?.answer as? DecisionAnswer.Reply
            store.pick(card, DecisionAnswer.Reply(now?.text ?: state.text.toString(), now?.images.orEmpty() + added))
        },
        onTooMany = { tooMany = true },
    )
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(tokens.panel2.color, shape)
            .cssBorder((if (reply != null) tokens.accent else tokens.border).color, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PpmTextField(
            state,
            if (hasTiles) "Other — your own words go to the manager chat as written…" else "Your answer, to the manager chat word for word…",
            Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("assistant-decision-other"),
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 6),
        )
        ReplyImageStrip(images) { gone ->
            val rest = images - gone
            if (state.text.isBlank() && rest.isEmpty()) store.pick(card, null) else store.pick(card, DecisionAnswer.Reply(state.text.toString(), rest))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeadIconButton(ProductIcon.Paperclip, "Attach a picture", attach, tag = "assistant-decision-attach")
            if (tooMany) BasicText(TOO_MANY_PICTURES, style = ppmText(12f).copy(color = tokens.warning.color))
        }
    }
}

/** "Send N answers?": what each card gets, and which are left waiting (`ReviewStep`). */
@Composable
private fun ReviewSheet(plan: List<Pair<AssistantCard, DecisionAnswer>>, left: List<AssistantCard>, onBack: () -> Unit, onSend: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val now = System.currentTimeMillis()
    val n = plan.size
    AssistantSheet(
        title = "Send $n answer${if (n == 1) "" else "s"}?",
        onDismiss = onBack,
        footer = {
            CardButton("Back", onBack, Modifier.weight(1f))
            CardButton("Send $n answer${if (n == 1) "" else "s"}", onSend, Modifier.weight(1f).testTag("assistant-decision-review-send"), variant = CardButtonVariant.PRIMARY)
        },
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BasicText(
                "Each goes to its own manager chat, one card at a time. A card answered elsewhere meanwhile is skipped and says so.",
                style = ppmText(13f).copy(color = tokens.text2.color),
            )
            for ((card, answer) in plan) {
                Column {
                    BasicText(listOfNotNull(card.projectName, card.title).joinToString(" · "), style = ppmText(13f, FontWeight.Medium).copy(color = tokens.text.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(answerLabel(card, answer), style = ppmText(12f).copy(color = tokens.text2.color))
                    if (answer is DecisionAnswer.Reply && isStaleForReply(card, now)) BasicText("From over a day ago: sending confirms it anyway.", style = ppmText(12f).copy(color = tokens.warning.color))
                }
            }
            for (card in left) {
                Column {
                    BasicText(listOfNotNull(card.projectName, card.title).joinToString(" · "), style = ppmText(13f, FontWeight.Medium).copy(color = tokens.text3.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText("No answer yet — keeps waiting", style = ppmText(12f).copy(color = tokens.text3.color))
                }
            }
        }
    }
}
