package com.hienlh.ppm.state.assistant

import com.hienlh.ppm.client.CardRefusal
import com.hienlh.ppm.protocol.AssistantCard
import com.hienlh.ppm.protocol.InlineImage
import kotlinx.coroutines.CancellationException

/*
 * The PPM Assistant's set of decisions (`decision-set-model.ts`, `decision-set-send.ts`): what the
 * user picked on each waiting decision card, what "Send all" would send, and how each send went.
 * A pick remembers the card as it was picked ([cardVersion]); a card that changes loses its pick,
 * so an OK never goes out for a draft the user did not see.
 */

/** Most pictures one reply carries; the server refuses more. */
const val MAX_REPLY_IMAGES = 5

/** A reply to a card older than this asks "send anyway?" on the server (`STALE_REPLY_MS`). */
const val STALE_REPLY_MS = 24 * 60 * 60_000L

/** A picture attached to a reply, ready to send. */
data class ReplyImage(val id: String, val name: String, val image: InlineImage)

sealed interface DecisionAnswer {
    data class Ok(val draftSha256: String?) : DecisionAnswer
    data class Choose(val optionId: String) : DecisionAnswer
    data class Reply(val text: String, val images: List<ReplyImage> = emptyList()) : DecisionAnswer
}

data class DecisionPick(val answer: DecisionAnswer, val version: String)

/** One pressable answer on a card: one of its choices, or one OK. */
data class DecisionTile(val key: String, val label: String, val description: String, val answer: DecisionAnswer)

/** What a pick depends on, and only that: the words read, the draft and who it goes to, the choices. */
fun cardVersion(card: AssistantCard): String {
    val draft = card.payload.draft
    val options = card.payload.options.joinToString("\u0001") { "${it.id}\u0002${it.label}\u0002${it.description.orEmpty()}" }
    return listOf(card.title, card.body, draft?.let { "${it.sha256}\u0002${it.to}" } ?: "-", options, card.okOffered.toString()).joinToString("\u0000")
}

/**
 * Characters a reader cannot see, written as `⟨U+202E⟩` (`escapeHiddenCharacters`): controls and
 * format characters other than tab and newline, the common default-ignorables, and the line and
 * paragraph separators.
 */
fun escapeHiddenCharacters(text: String): String = buildString {
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        val type = Character.getType(cp)
        val hidden = cp != '\t'.code && cp != '\n'.code && (
            type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() ||
                cp == 0x2028 || cp == 0x2029 || cp == 0x034F || cp == 0x115F || cp == 0x1160 || cp == 0x3164 || cp == 0xFFA0 ||
                cp in 0x180B..0x180F || cp in 0xFE00..0xFE0F || cp in 0xE0000..0xE0FFF
            )
        if (hidden) append("⟨U+").append(Integer.toHexString(cp).uppercase().padStart(4, '0')).append('⟩') else appendCodePoint(cp)
        i += Character.charCount(cp)
    }
}

/** The tiles a card offers; none when the server says it is not shown whole here. */
fun decisionTiles(card: AssistantCard): List<DecisionTile> {
    if (!card.okOffered) return emptyList()
    val options = card.payload.options
    if (options.isNotEmpty()) return options.map { DecisionTile("choose:${it.id}", it.label, it.description.orEmpty(), DecisionAnswer.Choose(it.id)) }
    val draft = card.payload.draft
    if (draft != null) {
        return listOf(
            DecisionTile(
                "ok",
                "OK, send this draft",
                "Sends the draft above to ${escapeHiddenCharacters(draft.to)}, character for character.",
                DecisionAnswer.Ok(draft.sha256),
            ),
        )
    }
    return listOf(DecisionTile("ok", "OK, go ahead", "Tells the manager to go ahead as the card describes.", DecisionAnswer.Ok(null)))
}

fun sameAnswer(a: DecisionAnswer?, b: DecisionAnswer?): Boolean = when {
    a is DecisionAnswer.Ok && b is DecisionAnswer.Ok -> a.draftSha256 == b.draftSha256
    a is DecisionAnswer.Choose && b is DecisionAnswer.Choose -> a.optionId == b.optionId
    else -> false
}

/** Whether [answer] can be sent for [card] as the card is now. */
fun answerIsReady(card: AssistantCard, answer: DecisionAnswer?): Boolean = when (answer) {
    null -> false
    is DecisionAnswer.Reply -> (answer.text.isNotBlank() || answer.images.isNotEmpty()) && card.sessionId != null
    else -> decisionTiles(card).any { sameAnswer(it.answer, answer) }
}

fun currentPick(card: AssistantCard, picks: Map<String, DecisionPick>): DecisionPick? =
    picks[card.id]?.takeIf { it.version == cardVersion(card) }

/** The picks that no longer fit their card; picks of cards not in [cards] are left alone. */
fun outdatedPicks(cards: List<AssistantCard>, picks: Map<String, DecisionPick>): List<String> =
    cards.filter { c -> picks[c.id]?.let { it.version != cardVersion(c) } == true }.map { it.id }

/** What "Send all" sends: every open card with a ready pick, in the set's order. */
fun sendPlan(cards: List<AssistantCard>, picks: Map<String, DecisionPick>): List<Pair<AssistantCard, DecisionAnswer>> =
    cards.mapNotNull { card ->
        if (card.status != "open") return@mapNotNull null
        val pick = currentPick(card, picks) ?: return@mapNotNull null
        if (answerIsReady(card, pick.answer)) card to pick.answer else null
    }

fun isStaleForReply(card: AssistantCard, now: Long): Boolean = now - card.createdAt > STALE_REPLY_MS

/** The answer in a few words, as the review step and the line after a send name it. */
fun answerLabel(card: AssistantCard, answer: DecisionAnswer): String = when (answer) {
    is DecisionAnswer.Choose -> "Chose “${card.payload.options.firstOrNull { it.id == answer.optionId }?.label ?: answer.optionId}”"
    is DecisionAnswer.Ok -> card.payload.draft?.let { "OK — send the draft to ${escapeHiddenCharacters(it.to)}" } ?: "OK — go ahead"
    is DecisionAnswer.Reply -> {
        val n = answer.images.size
        val pictures = if (n > 0) " + $n picture${if (n == 1) "" else "s"}" else ""
        val text = answer.text.trim()
        if (text.isNotEmpty()) "Reply: “$text”$pictures" else "Reply with $n picture${if (n == 1) "" else "s"}"
    }
}

/** "alpha 2 · beta 1": where the waiting cards come from. */
fun projectCounts(cards: List<AssistantCard>): String =
    cards.groupingBy { it.projectName ?: "Assistant" }.eachCount().entries.joinToString(" · ") { "${it.key} ${it.value}" }

/**
 * The order the set's tabs keep: the order it had, with cards that arrived since added at the end
 * (a new card never moves the tab the user is on), and ids that are neither waiting nor kept (a
 * card being sent from here) dropped.
 */
fun nextTabOrder(previous: List<String>, waitingInOrder: List<String>, keep: Set<String>): List<String> {
    val waiting = waitingInOrder.toSet()
    val kept = previous.filter { it in waiting || it in keep }.toMutableList()
    val known = kept.toHashSet()
    for (id in waitingInOrder) if (known.add(id)) kept += id
    return kept
}

/**
 * The set's tabs (`decisionTabs`): every card that still waits, plus a card whose answer is on its
 * way from here. That one leaves the waiting list the moment the server marks it answered, before
 * the answer comes back, and keeps its tab until the send says how it went (a failed forward puts
 * it back). A card answered anywhere else — Telegram, another window, dismissed, closed by its
 * manager — is simply gone: what happened to it is told where the conversation is, never in the set.
 */
fun decisionTabs(
    order: List<String>,
    waiting: List<AssistantCard>,
    inFlight: Map<String, AssistantCard>,
    scope: (AssistantCard) -> Boolean,
): List<AssistantCard> {
    val byId = waiting.associateBy { it.id }
    val tabs = mutableListOf<AssistantCard>()
    val placed = HashSet<String>()
    for (id in order) {
        val card = byId[id] ?: inFlight[id]
        if (card != null && id !in placed && scope(card)) tabs += card
        placed += id
    }
    // The order catches up a moment later; a card shown before that still gets its tab.
    for (card in waiting) if (card.id !in placed && scope(card)) {
        tabs += card
        placed += card.id
    }
    return tabs
}

/**
 * Which tab is open (`activeTabIndex`). The card the user chose while it is still there; else the
 * tab now standing where the open one stood — the next waiting card, or the last one when it was at
 * the end — so a card answered elsewhere under the user's eyes moves them on rather than back to
 * the start; else (a set just shown) the first. -1 with no tab.
 */
fun activeTabIndex(tabIds: List<String>, activeId: String?, lastIndex: Int): Int {
    if (tabIds.isEmpty()) return -1
    val at = activeId?.let(tabIds::indexOf) ?: -1
    if (at >= 0) return at
    if (activeId == null) return 0
    return lastIndex.coerceAtLeast(0).coerceAtMost(tabIds.size - 1)
}

/** The next card after [fromId] that still waits (not being sent), wrapping round; null when none. */
fun nextWaitingTab(tabIds: List<String>, fromId: String, sending: Set<String>): String? {
    val at = tabIds.indexOf(fromId)
    for (i in 1..tabIds.size) {
        val next = tabIds[Math.floorMod(at + i, tabIds.size)]
        if (next != fromId && next !in sending) return next
    }
    return null
}

/** One line about the last send from here, the card itself having left the set (`SetFlash`). */
data class SetFlash(val sent: Boolean, val text: String)

/** The routes a send goes through; the app passes its API client, a test a fake. */
interface DecisionSendApi {
    suspend fun ok(cardId: String, draftSha256: String?): AssistantCard?
    suspend fun choose(cardId: String, optionId: String): AssistantCard?
    suspend fun reply(cardId: String, text: String, confirmAge: Boolean, images: List<InlineImage>): AssistantCard?
}

sealed interface SendOutcome {
    val card: AssistantCard?

    data class Sent(override val card: AssistantCard?) : SendOutcome
    data class Elsewhere(val message: String, override val card: AssistantCard?) : SendOutcome
    data class Confirm(val question: String, override val card: AssistantCard?) : SendOutcome
    data class Changed(val message: String, override val card: AssistantCard?) : SendOutcome
    data class Failed(val message: String, override val card: AssistantCard?) : SendOutcome
}

const val ALREADY_ANSWERED_ELSEWHERE = "This card was already answered elsewhere."
private val CHANGED = Regex("not the one this OK was given for|not one of this card's choices|not shown in full", RegexOption.IGNORE_CASE)

/** Sends one card's answer and says how it went. Never throws (but for cancellation). */
suspend fun sendDecision(
    card: AssistantCard,
    answer: DecisionAnswer,
    api: DecisionSendApi,
    confirmAge: Boolean = false,
    confirmStale: Boolean = false,
): SendOutcome = try {
    SendOutcome.Sent(
        when (answer) {
            is DecisionAnswer.Ok -> api.ok(card.id, answer.draftSha256)
            is DecisionAnswer.Choose -> api.choose(card.id, answer.optionId)
            is DecisionAnswer.Reply -> api.reply(card.id, answer.text, confirmAge, answer.images.map { it.image })
        },
    )
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    val refusal = CardRefusal.of(e)
    val now = refusal.card
    when {
        refusal.error == "already_answered" -> SendOutcome.Elsewhere(refusal.question ?: ALREADY_ANSWERED_ELSEWHERE, now)
        refusal.error == "confirm" -> when {
            now != null && now.status != "open" -> SendOutcome.Elsewhere(refusal.question ?: ALREADY_ANSWERED_ELSEWHERE, now)
            confirmStale && !confirmAge && answer is DecisionAnswer.Reply && now?.status == "open" ->
                sendDecision(card, answer, api, confirmAge = true)
            else -> SendOutcome.Confirm(refusal.question ?: "Send anyway?", now)
        }
        CHANGED.containsMatchIn(refusal.error) -> SendOutcome.Changed(refusal.error, now)
        else -> SendOutcome.Failed(refusal.error, now)
    }
}

/** Sends every planned answer one after another; one that fails does not stop the rest. */
suspend fun sendAllDecisions(
    plan: List<Pair<AssistantCard, DecisionAnswer>>,
    api: DecisionSendApi,
    onEach: (String, SendOutcome) -> Unit,
): Map<String, SendOutcome> {
    val results = LinkedHashMap<String, SendOutcome>()
    for ((card, answer) in plan) {
        val outcome = sendDecision(card, answer, api, confirmStale = true)
        results[card.id] = outcome
        onEach(card.id, outcome)
    }
    return results
}
