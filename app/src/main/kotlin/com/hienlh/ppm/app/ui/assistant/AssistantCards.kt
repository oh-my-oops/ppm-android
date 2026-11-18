package com.hienlh.ppm.app.ui.assistant

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.assistant
import com.hienlh.ppm.app.ui.contextMenuGesture
import com.hienlh.ppm.chat.MarkdownView
import com.hienlh.ppm.client.designShot
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.AssistantCard
import com.hienlh.ppm.protocol.CardImage
import com.hienlh.ppm.protocol.CardOption
import com.hienlh.ppm.protocol.CardQuestion
import com.hienlh.ppm.protocol.DISMISSABLE_CARD_KINDS
import com.hienlh.ppm.protocol.MANAGER_REPLY_CARD_KINDS
import com.hienlh.ppm.state.assistant.answerSummary
import com.hienlh.ppm.state.assistant.cardStatusLine
import com.hienlh.ppm.state.assistant.cardTime
import com.hienlh.ppm.state.assistant.escapeHiddenCharacters
import com.hienlh.ppm.state.assistant.isAnsweredDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What kind of card it is, as its head chip says (`CardLook`). */
enum class CardTone { NEED, OK, FAIL, INFO, DIGEST }

class CardLook(val label: String, val icon: ProductIcon, val tone: CardTone)

/** One action at a time on a card, its error shown on the card (`useCardAction`). */
@Stable
class CardAction(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun run(block: suspend () -> String?) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                error = block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }
}

@Composable
fun rememberCardAction(): CardAction {
    val scope = rememberCoroutineScope()
    return remember(scope) { CardAction(scope) }
}

/** The chat a card came from: its manager chat, or the other chat of the project it names (`openCardManagerChat`). */
fun AppModel.openCardChat(card: AssistantCard) {
    val project = card.projectName ?: return
    val session = card.sessionId ?: return
    assistant.openManagerChat(project, session, card.payload.chatTitle, card.payload.chatProvider)
}

/** Whether a manager chat's Reply is offered on [card]. */
private fun replyOffered(card: AssistantCard) = card.sessionId != null && card.projectName != null && card.kind in MANAGER_REPLY_CARD_KINDS

/** One PPM Assistant card, drawn by its kind (`AssistantCard`). */
@Composable
fun AssistantCardView(model: AppModel, card: AssistantCard) {
    when (card.kind) {
        "needs_decision", "urgent" -> DecisionCardView(model, card)
        "approval" -> ApprovalMirrorCardView(model, card)
        "question" -> QuestionMirrorCardView(model, card)
        "done", "failed" -> OutcomeCardView(model, card)
        "budget" -> BudgetCardView(model, card)
        "digest" -> DigestFold(card)
        "rules_note" -> RulesNoteCard(card)
        "rules_change" -> RulesChangeCard(model, card)
    }
}

/**
 * The frame every card shares (`CardShell`): the head (kind, project, time), the provenance line,
 * the body, the manager's answer to a reply, and the buttons while it waits or how it ended.
 * A long press opens its menu: Reply, Open manager chat, Dismiss.
 */
@Composable
fun CardShell(
    model: AppModel,
    card: AssistantCard,
    look: CardLook,
    error: String? = null,
    meta: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    secondary: (@Composable RowScope.() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val status = cardStatusLine(card)
    val waiting = card.status == "open" && look.tone == CardTone.NEED
    val host = LocalCardReplyHost.current
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.panel.color, shape)
            .cssBorder((if (waiting) tokens.error.opacity(0.35) else tokens.border).color, shape)
            .contextMenuGesture { menu = true }
            .testTag("assistant-card-${card.kind}"),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val (fill, ink) = toneColors(look.tone)
            TonePill(look.label, look.icon, fill, ink)
            if (card.projectName != null) ProjectPill(card.projectName!!, Modifier.weight(1f, fill = false))
            else if (meta != null) BasicText(meta, Modifier.weight(1f, fill = false), style = ppmText(12f).copy(color = tokens.text2.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            BasicText(cardTime(card.createdAt), style = ppmText(11f).copy(color = tokens.text3.color))
        }
        card.provenance?.let {
            BasicText(it, Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp), style = ppmText(11f, italic = true).copy(color = tokens.text3.color))
        }
        if (content != null) {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
        card.followUp?.let { follow ->
            Column(
                Modifier
                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp)
                    .fillMaxWidth()
                    .background(tokens.panel2.color, RoundedCornerShape(6.dp))
                    .cssBorder(tokens.border.color, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .testTag("assistant-card-followup"),
            ) {
                if (follow.newCards.isNotEmpty()) {
                    BasicText("The manager answered with ${follow.newCards.size} new card${if (follow.newCards.size == 1) "" else "s"} · ${cardTime(follow.at)}", style = ppmText(11f, FontWeight.Medium).copy(color = tokens.text3.color))
                    for ((id, title) in follow.newCards) {
                        BasicText(
                            title,
                            Modifier.padding(top = 4.dp).ppmClickable { model.assistant.store?.jumpTo(id) },
                            style = ppmText(13f, FontWeight.Medium).copy(color = tokens.accent.color),
                        )
                    }
                } else {
                    BasicText("The manager's answer to your reply · ${cardTime(follow.at)}", Modifier.padding(bottom = 4.dp), style = ppmText(11f, FontWeight.Medium).copy(color = tokens.text3.color))
                    CardText(follow.text, fold = true)
                    if (follow.truncated) BasicText("The rest is in the manager chat.", Modifier.padding(top = 4.dp), style = ppmText(12f).copy(color = tokens.text3.color))
                }
            }
        }
        val note = card.answer?.note
        if (card.status == "open" && note != null) {
            BasicText(
                note,
                Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp).fillMaxWidth().background(tokens.error.opacity(0.1).color, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                style = ppmText(12f).copy(color = tokens.error.color),
            )
        }
        if (status != null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.border.color))
            BasicText(status, Modifier.padding(horizontal = 14.dp, vertical = 8.dp).testTag("assistant-card-status"), style = ppmText(12f).copy(color = tokens.text3.color))
            if (error != null) CardError(error, Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp))
        } else if (actions != null || secondary != null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.border.color))
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (error != null) CardError(error)
                // On a phone the lesser actions sit above, the answers under the thumb, first on the right.
                if (mobile) {
                    if (secondary != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = secondary)
                    if (actions != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), content = actions)
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (actions != null) actions()
                        Spacer(Modifier.weight(1f))
                        if (secondary != null) secondary()
                    }
                }
            }
        } else if (error != null) {
            CardError(error, Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp))
        }
    }
    if (menu) CardMenuSheet(model, card, host, onDismiss = { menu = false })
}

@Composable
private fun toneColors(tone: CardTone): Pair<Rgba, Rgba> {
    val tokens = LocalPpmTokens.current
    return when (tone) {
        CardTone.NEED, CardTone.FAIL -> tokens.error.opacity(0.1) to tokens.error
        CardTone.OK -> tokens.success.opacity(0.1) to tokens.success
        CardTone.INFO -> tokens.panel2 to tokens.text2
        CardTone.DIGEST -> tokens.accentWash to tokens.accent
    }
}

@Composable
private fun CardError(text: String, modifier: Modifier = Modifier) {
    BasicText(text, modifier.testTag("assistant-card-error"), style = ppmText(12f).copy(color = LocalPpmTokens.current.error.color))
}

/** A card's menu (`cardMenuItems`): Reply, Open manager chat, Dismiss. */
@Composable
private fun CardMenuSheet(model: AppModel, card: AssistantCard, host: CardReplyHost?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val items = buildList<Pair<String, () -> Unit>> {
        val hasManager = card.sessionId != null && card.projectName != null
        if (replyOffered(card)) add("Reply" to { (host?.replyTo ?: { c -> model.assistant.store?.replyTo(c) })(card) })
        if (hasManager && host?.inManagerChat != true) add("Open manager chat" to { model.openCardChat(card) })
        if (card.status == "open" && card.kind in DISMISSABLE_CARD_KINDS) {
            add("Dismiss" to {
                scope.launch {
                    model.assistant.store?.dismiss(card)?.let { model.toast(com.hienlh.ppm.app.ErrorToastMessage(it)) }
                }
                Unit
            })
        }
    }
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    AssistantSheet(title = card.title, onDismiss = onDismiss) {
        val tokens = LocalPpmTokens.current
        for ((label, run) in items) {
            BasicText(
                label,
                Modifier.fillMaxWidth().heightIn(min = 48.dp).ppmClickable { onDismiss(); run() }.padding(horizontal = 16.dp, vertical = 14.dp),
                style = ppmText(15f).copy(color = (if (label == "Dismiss") tokens.error else tokens.text).color),
            )
        }
    }
}

/** A card's long text as Markdown, no HTML; one that no longer waits folds past about eight lines (`CardText`). */
@Composable
fun CardText(text: String, fold: Boolean) {
    val tokens = LocalPpmTokens.current
    val long = remember(text) { text.lines().size > 8 || text.length > 640 }
    var open by remember(text) { mutableStateOf(false) }
    val folded = fold && long && !open
    Column {
        Box(if (folded) Modifier.heightIn(max = 180.dp).clip(RoundedCornerShape(0.dp)) else Modifier) {
            MarkdownView(text, rawHtml = false)
        }
        if (fold && long) {
            BasicText(
                if (open) "Show less" else "Show more",
                Modifier.heightIn(min = if (LocalPpmMobile.current) 44.dp else 28.dp).ppmClickable { open = !open }.padding(top = 6.dp).testTag("assistant-card-more"),
                style = ppmText(12f, FontWeight.Medium).copy(color = tokens.accent.color),
            )
        }
    }
}

@Composable
private fun CardTitle(text: String) {
    BasicText(text, Modifier.testTag("assistant-card-title"), style = ppmText(15f, FontWeight.SemiBold, lineHeight = 20f).copy(color = LocalPpmTokens.current.text.color))
}

@Composable
private fun Subtle(text: String, warning: Boolean = false) {
    val tokens = LocalPpmTokens.current
    BasicText(text, style = ppmText(12f, lineHeight = 16f).copy(color = (if (warning) tokens.warning else tokens.text3).color))
}

/** The design screenshots on a report card (`CardImages`), fetched with the sign-in. */
@Composable
fun CardImages(model: AppModel, images: List<CardImage>) {
    if (images.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        images.forEachIndexed { i, image -> CardImageTile(model, image, i) }
    }
}

private val shots = mutableStateMapOf<String, ImageBitmap?>()

@Composable
private fun CardImageTile(model: AppModel, image: CardImage, index: Int) {
    val tokens = LocalPpmTokens.current
    var failed by remember(image.id) { mutableStateOf(false) }
    val bitmap = shots[image.id]
    LaunchedEffect(image.id) {
        if (shots.containsKey(image.id)) return@LaunchedEffect
        val api = model.api ?: return@LaunchedEffect
        try {
            val bytes = api.designShot(image.id)
            shots[image.id] = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    val name = if (image.label.isNotEmpty()) "${image.file} · ${image.label}" else image.file
    Column(
        Modifier.width(180.dp).clip(RoundedCornerShape(6.dp)).background(tokens.panel2.color).cssBorder(tokens.border.color, RoundedCornerShape(6.dp)),
    ) {
        Box(Modifier.fillMaxWidth().height(128.dp), contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                Image(bitmap, "${index + 1}. ${image.designTitle} — $name", Modifier.fillMaxWidth().height(128.dp), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
            } else {
                BasicText(if (failed) "Picture no longer kept" else "Loading…", style = ppmText(12f).copy(color = tokens.text2.color))
            }
        }
        BasicText("${index + 1}. $name", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = ppmText(12f).copy(color = tokens.text2.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A manager's draft, whole and verbatim, hidden characters made visible (`CardDraft`). */
@Composable
fun CardDraftView(draft: com.hienlh.ppm.protocol.CardDraft) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(tokens.panel2.color).cssBorder(tokens.border.color, shape).testTag("assistant-card-draft")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            PpmIcon(ProductIcon.Send, 12.dp, tokens.text3.color)
            BasicText("DRAFT", style = ppmText(11f, FontWeight.Medium).copy(color = tokens.text3.color))
            BasicText("to ${escapeHiddenCharacters(draft.to)}", style = ppmText(11f).copy(color = tokens.text3.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.border.color))
        BasicText(escapeHiddenCharacters(draft.text), Modifier.padding(horizontal = 12.dp, vertical = 10.dp), style = ppmText(12.5f, kind = FontKind.MONO, lineHeight = 19f).copy(color = tokens.text.color))
    }
}

/** A decision card's choices, one button each, numbered as Telegram numbers them (`CardChoices`). */
@Composable
fun CardChoices(options: List<CardOption>, pressable: Boolean, chosenId: String?, disabled: Boolean, onChoose: (String) -> Unit) {
    val tokens = LocalPpmTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(if (pressable) "CHOOSE ONE" else "CHOICES", style = ppmText(11f, FontWeight.Medium).copy(color = tokens.text3.color))
        options.forEachIndexed { i, o ->
            val chosen = o.id == chosenId
            val shape = RoundedCornerShape(8.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .background((if (chosen) tokens.accentWash else tokens.panel).color, shape)
                    .cssBorder((if (chosen) tokens.accent else tokens.border).color, shape)
                    .ppmClickable(enabled = pressable && !disabled, shape = shape) { onChoose(o.id) }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag("assistant-card-choice-${o.id}"),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier.size(24.dp).background((if (chosen) tokens.accent else tokens.panel2).color, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (chosen) PpmIcon(ProductIcon.Check, 14.dp, tokens.accentFg.color)
                    else BasicText("${i + 1}", style = ppmText(11f, FontWeight.SemiBold).copy(color = tokens.text2.color))
                }
                Column(Modifier.weight(1f)) {
                    BasicText(escapeHiddenCharacters(o.label), style = ppmText(14f, FontWeight.Medium).copy(color = tokens.text.color))
                    o.description?.let { BasicText(escapeHiddenCharacters(it), style = ppmText(13f, lineHeight = 19f).copy(color = tokens.text2.color)) }
                }
            }
        }
    }
}

/** A manager asking the user to decide (`DecisionCard`). */
@Composable
fun DecisionCardView(model: AppModel, card: AssistantCard) {
    val action = rememberCardAction()
    val host = LocalCardReplyHost.current
    val store = model.assistant.store
    val urgent = card.kind == "urgent"
    val draft = card.payload.draft
    val options = card.payload.options
    val open = card.status == "open"
    CardShell(
        model, card,
        CardLook(if (urgent) "Urgent" else "Needs your decision", if (urgent) ProductIcon.TriangleAlert else ProductIcon.BellRing, CardTone.NEED),
        error = action.error,
        actions = {
            if (card.okOffered && options.isEmpty()) {
                CardButton(if (draft != null) "OK, send" else "OK", { action.run { store?.ok(card) } }, icon = ProductIcon.Check, variant = CardButtonVariant.PRIMARY, enabled = !action.busy)
            }
            CardButton("Reply", { (host?.replyTo ?: { c -> store?.replyTo(c) })(card) }, icon = ProductIcon.Undo2, enabled = card.sessionId != null)
        },
        secondary = {
            if (host?.inManagerChat != true) CardButton("Open chat", { model.openCardChat(card) }, icon = ProductIcon.ExternalLink, variant = CardButtonVariant.GHOST, secondary = true, enabled = card.sessionId != null)
            CardButton("Dismiss", { action.run { store?.dismiss(card) } }, icon = ProductIcon.X, variant = CardButtonVariant.GHOST, secondary = true, enabled = !action.busy)
        },
    ) {
        CardTitle(card.title)
        if (card.body.isNotEmpty()) CardText(card.body, fold = card.status != "open")
        CardImages(model, card.payload.images)
        if (options.isNotEmpty()) {
            CardChoices(options, pressable = open && card.okOffered, chosenId = card.answer?.choice?.id, disabled = action.busy) { id -> action.run { store?.choose(card, id) } }
            if (!card.okOffered && open) Subtle("These choices are too long or hold a hidden value, so they cannot be pressed here. Answer with Reply, or open the manager chat.")
        }
        if (draft != null) {
            CardDraftView(draft)
            if (!card.okOffered && open) Subtle("This draft is too long or holds a hidden value, so it cannot be approved here. Answer with Reply, or open the manager chat.")
        }
        if (card.payload.urgentCapped) Subtle("Held for the morning: this manager already raised its urgent reports tonight.")
    }
}

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Who is asking: a manager chat, or another chat of the project (`Asker`). */
private fun asker(card: AssistantCard): String {
    val project = card.projectName ?: "project"
    val chat = card.payload.chatTitle ?: return "The $project manager chat"
    return "Chat “${escapeHiddenCharacters(chat)}” in $project"
}

@Composable
private fun OpenChatButton(model: AppModel, card: AssistantCard, primary: Boolean = false) {
    CardButton(
        if (primary) "Open in PPM" else "Open chat",
        { model.openCardChat(card) },
        icon = ProductIcon.ExternalLink,
        variant = if (primary) CardButtonVariant.PRIMARY else CardButtonVariant.GHOST,
        secondary = !primary,
        enabled = card.sessionId != null,
    )
}

/** A chat's approval, mirrored (`ApprovalMirrorCard`): Allow only when the card shows everything that would run. */
@Composable
fun ApprovalMirrorCardView(model: AppModel, card: AssistantCard) {
    val tokens = LocalPpmTokens.current
    val action = rememberCardAction()
    val store = model.assistant.store
    val d = card.payload.deciding
    val complete = d?.complete == true
    if (card.payload.answerInPpm != null) {
        CardShell(model, card, CardLook("Needs you in PPM", ProductIcon.ShieldAlert, CardTone.NEED), error = action.error, actions = { OpenChatButton(model, card, primary = true) }) {
            BasicText("${asker(card)} waits: ${card.title}.", style = ppmText(13f).copy(color = tokens.text2.color))
            d?.incompleteReason?.let { Subtle(it) }
        }
        return
    }
    CardShell(
        model, card, CardLook("Needs your approval", ProductIcon.ShieldAlert, CardTone.NEED),
        error = action.error,
        actions = {
            if (complete) CardButton("Allow", { action.run { store?.answer(card, true) } }, icon = ProductIcon.Check, variant = CardButtonVariant.PRIMARY, enabled = !action.busy)
            CardButton("Deny", { action.run { store?.answer(card, false) } }, icon = ProductIcon.X, variant = CardButtonVariant.DANGER, enabled = !action.busy)
        },
        secondary = { OpenChatButton(model, card) },
    ) {
        BasicText("${asker(card)} wants to use ${d?.title ?: card.title}:", style = ppmText(13f).copy(color = tokens.text2.color))
        if (d != null && d.facts.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (f in d.facts) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BasicText(f.label, style = ppmText(12f).copy(color = tokens.text3.color))
                        BasicText(f.value, Modifier.weight(1f), style = ppmText(12f).copy(color = tokens.text.color))
                    }
                }
            }
        }
        if (!d?.text.isNullOrEmpty()) {
            val shape = RoundedCornerShape(8.dp)
            BasicText(
                d!!.text,
                Modifier.fillMaxWidth().heightIn(max = 192.dp).background(tokens.panel2.color, shape).cssBorder(tokens.border.color, shape).padding(horizontal = 12.dp, vertical = 8.dp),
                style = ppmText(12f, kind = FontKind.MONO).copy(color = tokens.text.color),
            )
        }
        if (!complete) Subtle(d?.incompleteReason ?: "Open the ${if (card.payload.chatTitle != null) "chat" else "manager chat"} to see all of it before allowing.", warning = true)
        val at = card.payload.expiresAt
        if (at != null && card.status == "open") {
            Subtle("Expires at ${CLOCK.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))}: if nobody answers by then, nothing is done.", warning = true)
        }
    }
}

/** Every question can be answered on the card: each has options and none asks for a secret (`answerableOnCard`). */
fun answerableOnCard(questions: List<CardQuestion>): Boolean = questions.isNotEmpty() && questions.all { it.options.isNotEmpty() }

/** A chat's question, mirrored and answered on the card (`QuestionMirrorCard`). */
@Composable
fun QuestionMirrorCardView(model: AppModel, card: AssistantCard) {
    val tokens = LocalPpmTokens.current
    val action = rememberCardAction()
    val store = model.assistant.store
    val questions = card.payload.questions
    val picked = remember(card.id) { mutableStateMapOf<String, List<String>>() }
    val open = card.status == "open"
    val answerable = answerableOnCard(questions)
    val answered = questions.count { picked[it.id].orEmpty().isNotEmpty() }
    val many = questions.size > 1
    CardShell(
        model, card, CardLook(if (many) "${questions.size} questions" else "Needs your answer", ProductIcon.CircleHelp, CardTone.NEED),
        error = action.error,
        actions = if (answerable) {
            {
                CardButton(
                    if (many) "Send answers ($answered/${questions.size})" else "Send answer",
                    { action.run { store?.answerQuestions(card, picked.toMap()) } },
                    icon = ProductIcon.Check, variant = CardButtonVariant.PRIMARY, enabled = !action.busy && answered >= questions.size,
                )
            }
        } else {
            null
        },
        secondary = { OpenChatButton(model, card) },
    ) {
        questions.forEachIndexed { i, q ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (q.header != null || many) {
                    BasicText(
                        listOfNotNull(if (many) "${i + 1}/${questions.size}" else null, q.header?.let(::escapeHiddenCharacters)).joinToString("  ").uppercase(),
                        style = ppmText(11f, FontWeight.SemiBold).copy(color = tokens.text3.color),
                    )
                }
                BasicText(escapeHiddenCharacters(q.question), style = ppmText(14f, FontWeight.Medium).copy(color = tokens.text.color))
                q.options.forEachIndexed { j, o ->
                    val chosen = o.label in picked[q.id].orEmpty()
                    val shape = RoundedCornerShape(8.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .background((if (chosen) tokens.accentWash else tokens.panel).color, shape)
                            .cssBorder((if (chosen) tokens.accent else tokens.border).color, shape)
                            .ppmClickable(enabled = open && answerable && !action.busy, shape = shape) {
                                val now = picked[q.id].orEmpty()
                                picked[q.id] = if (!q.multiSelect) listOf(o.label) else if (o.label in now) now - o.label else now + o.label
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        BasicText(('A' + j).toString(), style = ppmText(12f, FontWeight.SemiBold).copy(color = (if (chosen) tokens.accent else tokens.text2).color))
                        Column(Modifier.weight(1f)) {
                            BasicText(escapeHiddenCharacters(o.label), style = ppmText(14f, FontWeight.Medium).copy(color = tokens.text.color))
                            o.description?.let { BasicText(escapeHiddenCharacters(it), style = ppmText(13f).copy(color = tokens.text2.color)) }
                        }
                    }
                }
            }
        }
        if (open && !answerable) Subtle("Answer it in the ${if (card.payload.chatTitle != null) "chat" else "manager chat"}.")
        if (card.payload.chatTitle != null) Subtle("Asked by ${asker(card)}.")
    }
}

/** A manager's done or failed report (`OutcomeCard`). */
@Composable
fun OutcomeCardView(model: AppModel, card: AssistantCard) {
    val action = rememberCardAction()
    val store = model.assistant.store
    val failed = card.kind == "failed"
    CardShell(
        model, card,
        if (failed) CardLook("Failed", ProductIcon.XCircle, CardTone.FAIL) else CardLook("Done", ProductIcon.CircleCheckIcon, CardTone.OK),
        error = action.error,
        actions = { CardButton("Open chat", { model.openCardChat(card) }, icon = ProductIcon.ExternalLink, enabled = card.sessionId != null) },
        secondary = { CardButton("Dismiss", { action.run { store?.dismiss(card) } }, icon = ProductIcon.X, variant = CardButtonVariant.GHOST, secondary = true, enabled = !action.busy) },
    ) {
        CardTitle(card.title)
        val changes = card.payload.taskChanges
        if (card.body.isNotEmpty() && changes.isEmpty()) CardText(card.body, fold = true)
        if (changes.isNotEmpty()) {
            Subtle("Made in a turn PPM started, not one you wrote in.")
            for (c in changes) {
                BasicText(listOfNotNull(c.ref, c.summary).joinToString("  "), style = ppmText(13f).copy(color = LocalPpmTokens.current.text.color))
            }
        }
        CardImages(model, card.payload.images)
        if (card.payload.taskChangesMore > 0) Subtle("…and ${card.payload.taskChangesMore} more: see each task's History in PPM.")
        if (card.payload.detected) Subtle("Seen by PPM: the manager chat has not reported on it.")
    }
}

/** The Assistant pausing its own messages (`BudgetCard`). */
@Composable
fun BudgetCardView(model: AppModel, card: AssistantCard) {
    val action = rememberCardAction()
    CardShell(
        model, card, CardLook("Assistant paused messaging", ProductIcon.Pause, CardTone.NEED),
        error = action.error,
        actions = { CardButton("Dismiss", { action.run { model.assistant.store?.dismiss(card) } }, icon = ProductIcon.X, variant = CardButtonVariant.GHOST, enabled = !action.busy) },
    ) {
        BasicText(card.body.ifEmpty { card.title }, style = ppmText(14f).copy(color = LocalPpmTokens.current.text2.color))
    }
}

private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

/** "3 projects, 4 items" (`digestSummary`). */
fun digestSummary(card: AssistantCard): String {
    val projects = card.payload.projects
    if (projects.isEmpty()) return "${plural(1, "project")}, ${plural(card.payload.items.size, "item")}"
    return "${plural(projects.size, "project")}, ${plural(projects.sumOf { it.items.size }, "item")}"
}

/** The morning digest as one line that opens into its per-project lists (`DigestFold`). */
@Composable
fun DigestFold(card: AssistantCard) {
    val tokens = LocalPpmTokens.current
    var open by remember(card.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (LocalPpmMobile.current) 44.dp else 36.dp)
                .background(tokens.panel.opacity(0.6).color, shape)
                .cssBorder(tokens.border.color, shape)
                .ppmClickable(shape = shape) { open = !open }
                .padding(horizontal = 12.dp)
                .testTag("assistant-digest-fold"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PpmIcon(ProductIcon.Coffee, 14.dp, tokens.text2.color)
            BasicText("Morning digest · ${digestSummary(card)}", Modifier.weight(1f), style = ppmText(12f).copy(color = tokens.text2.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(cardTime(card.createdAt), style = ppmText(11f).copy(color = tokens.text3.color))
            BasicText(if (open) "Fold" else "Open", style = ppmText(12f).copy(color = tokens.text3.color))
        }
        if (open) DigestCardBody(card)
    }
}

@Composable
private fun DigestCardBody(card: AssistantCard) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().background(tokens.panel.color, shape).cssBorder(tokens.border.color, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (card.payload.resurfaced.isNotEmpty()) {
            BasicText("Back from snooze today", style = ppmText(13f, FontWeight.SemiBold).copy(color = tokens.text.color))
            for ((_, ref, title) in card.payload.resurfaced) BasicText("$ref  $title", style = ppmText(13f).copy(color = tokens.text.color))
        }
        val projects = card.payload.projects
        if (projects.isEmpty()) {
            BasicText(card.projectName ?: "project", style = ppmText(13f, FontWeight.SemiBold).copy(color = tokens.text.color))
            card.payload.items.forEachIndexed { i, item -> BasicText("${i + 1}. $item", style = ppmText(13f).copy(color = tokens.text.color)) }
        }
        for (p in projects) {
            BasicText(p.name, style = ppmText(13f, FontWeight.SemiBold).copy(color = tokens.text.color))
            when {
                !p.answered -> BasicText("No answer yet (busy)", style = ppmText(13f, italic = true).copy(color = tokens.text3.color))
                p.items.isEmpty() -> BasicText("Nothing new", style = ppmText(13f).copy(color = tokens.text2.color))
                else -> p.items.forEachIndexed { i, item -> BasicText("${i + 1}. $item", style = ppmText(13f).copy(color = tokens.text.color)) }
            }
            p.waitingSummary?.let { BasicText("$it · from Tasks", style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text2.color)) }
        }
    }
}

/** A manager rules note: one muted line (`RulesNoteCard`). */
@Composable
fun RulesNoteCard(card: AssistantCard) {
    val tokens = LocalPpmTokens.current
    var open by remember(card.id) { mutableStateOf(false) }
    val lines = card.payload.removed.map { "- $it" } + card.payload.added.map { "+ $it" }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag("assistant-card-rules_note")) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            PpmIcon(ProductIcon.ScrollText, 14.dp, tokens.text3.color)
            BasicText(
                listOfNotNull("Rules updated", card.projectName, card.title).joinToString(" · "),
                Modifier.weight(1f),
                style = ppmText(12f).copy(color = tokens.text3.color), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            BasicText(cardTime(card.createdAt), style = ppmText(12f).copy(color = tokens.text3.color))
        }
        if (card.body.isNotEmpty()) BasicText(card.body, Modifier.padding(start = 20.dp), style = ppmText(12f).copy(color = tokens.text3.color))
        if (lines.isNotEmpty()) {
            BasicText(if (open) "Hide the lines" else "Show the lines", Modifier.padding(start = 20.dp, top = 4.dp).ppmClickable { open = !open }, style = ppmText(12f).copy(color = tokens.text3.color))
            if (open) DiffLines(card.payload.removed.map { false to it } + card.payload.added.map { true to it })
        }
    }
}

@Composable
private fun DiffLines(lines: List<Pair<Boolean, String>>) {
    if (lines.isEmpty()) return
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.fillMaxWidth().heightIn(max = 192.dp).background(tokens.panel2.color, shape).cssBorder(tokens.border.color, shape).padding(horizontal = 12.dp, vertical = 8.dp)) {
        for ((add, line) in lines) {
            BasicText(if (add) "+ $line" else "- $line", style = ppmText(12f, kind = FontKind.MONO).copy(color = (if (add) tokens.success else tokens.error).color))
        }
    }
}

/** A held rules change waiting on the user (`RulesChangeCard`). */
@Composable
fun RulesChangeCard(model: AppModel, card: AssistantCard) {
    val action = rememberCardAction()
    val store = model.assistant.store
    val hash = card.payload.rulesHash
    val whole = card.payload.rulesComplete
    CardShell(
        model, card, CardLook("Rules change waiting", ProductIcon.ScrollText, CardTone.NEED),
        error = action.error,
        actions = {
            if (whole) CardButton("Approve change", { action.run { store?.decideRules(card, true) } }, icon = ProductIcon.Check, variant = CardButtonVariant.PRIMARY, enabled = !action.busy && hash != null)
            else CardButton("Review in Settings", { model.openSettings("assistant") }, icon = ProductIcon.Settings, variant = CardButtonVariant.PRIMARY)
            CardButton("Revert", { action.run { store?.decideRules(card, false) } }, icon = ProductIcon.Undo2, variant = CardButtonVariant.DANGER, enabled = !action.busy && hash != null)
        },
    ) {
        BasicText(card.title, style = ppmText(14f, FontWeight.SemiBold).copy(color = LocalPpmTokens.current.text.color))
        if (card.body.isNotEmpty()) BasicText(card.body, style = ppmText(14f).copy(color = LocalPpmTokens.current.text2.color))
        DiffLines(card.payload.changes.map { it.add to it.line })
        if (!whole) Subtle("This card does not show the whole change; review and approve it in Settings.")
        Subtle("The rules in force stay as they are until you choose.")
    }
}

/** Cards opened again from their fold, by id: the stream re-places a card as messages arrive. */
private val unfolded = mutableStateMapOf<String, Boolean>()

/** A decision the user no longer has to make: one line that opens the card again (`AnsweredCardFold`). */
@Composable
fun AnsweredCardFold(model: AppModel, card: AssistantCard) {
    val tokens = LocalPpmTokens.current
    val open = unfolded[card.id] == true
    val setAside = card.status == "dismissed" || card.status == "gone"
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .background(tokens.panel.opacity(0.6).color, shape)
                .cssBorder(tokens.border.color, shape)
                .ppmClickable(shape = shape) { if (open) unfolded.remove(card.id) else unfolded[card.id] = true }
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("assistant-answered-${card.id}"),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(24.dp).background((if (setAside) tokens.panel2 else tokens.success.opacity(0.1)).color, CircleShape),
                contentAlignment = Alignment.Center,
            ) { PpmIcon(if (setAside) ProductIcon.X else ProductIcon.Check, 14.dp, (if (setAside) tokens.text2 else tokens.success).color) }
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(card.title, Modifier.weight(1f, fill = false), style = ppmText(13f, FontWeight.Medium).copy(color = tokens.text.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    card.projectName?.let { ProjectPill(it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText(answerSummary(card), Modifier.weight(1f, fill = false), style = ppmText(12f).copy(color = tokens.text2.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (card.followUp != null) BasicText("manager answered", style = ppmText(12f).copy(color = tokens.accent.color))
                }
            }
            BasicText(cardTime(card.answer?.at ?: card.updatedAt), style = ppmText(11f).copy(color = tokens.text3.color))
            PpmIcon(if (open) ProductIcon.ChevronUp else ProductIcon.ChevronDown, 16.dp, tokens.text3.color)
        }
        if (open) AssistantCardView(model, card)
    }
}

/** Where a waiting decision sits in the stream: one line that leads to the set (`DecisionSetStub`). */
@Composable
fun DecisionSetStub(model: AppModel, card: AssistantCard) {
    val tokens = LocalPpmTokens.current
    val urgent = card.kind == "urgent"
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .background(tokens.warning.opacity(0.05).color, shape)
            .cssBorder(tokens.warning.opacity(0.4).color, shape)
            .ppmClickable(shape = shape) { model.assistant.store?.jumpTo(card.id) }
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("assistant-decision-stub-${card.id}"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (urgent) tokens.error else tokens.warning
        Box(Modifier.size(24.dp).background(ink.opacity(0.1).color, CircleShape), contentAlignment = Alignment.Center) {
            PpmIcon(if (urgent) ProductIcon.TriangleAlert else ProductIcon.BellRing, 14.dp, ink.color)
        }
        Column(Modifier.weight(1f)) {
            BasicText(card.title, style = ppmText(13f, FontWeight.Medium).copy(color = tokens.text.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(
                "${card.projectName?.let { "$it · " }.orEmpty()}${if (urgent) "urgent · " else ""}waiting in the set below",
                style = ppmText(12f).copy(color = tokens.text2.color), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        BasicText(cardTime(card.createdAt), style = ppmText(11f).copy(color = tokens.text3.color))
        if (!LocalPpmMobile.current) BasicText("Answer ↓", style = ppmText(11.5f, FontWeight.Medium).copy(color = tokens.warning.color))
    }
}

/** A wake turn answered `[no reply]`, folded to one muted line (`WakeTurnLine`). */
@Composable
fun WakeTurnLine(text: String) {
    val tokens = LocalPpmTokens.current
    Row(Modifier.padding(horizontal = 4.dp).testTag("assistant-wake-turn"), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        PpmIcon(ProductIcon.Bot, 14.dp, tokens.text3.color)
        BasicText(text, style = ppmText(12f).copy(color = tokens.text3.color))
    }
}

/** A card in the stream as it should show: whole while it waits, folded once answered. */
@Composable
fun StreamCard(model: AppModel, card: AssistantCard) {
    if (isAnsweredDecision(card)) AnsweredCardFold(model, card) else AssistantCardView(model, card)
}
