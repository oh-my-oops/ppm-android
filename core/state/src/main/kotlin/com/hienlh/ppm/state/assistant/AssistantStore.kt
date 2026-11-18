package com.hienlh.ppm.state.assistant

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.CardRefusal
import com.hienlh.ppm.client.answerAssistantCard
import com.hienlh.ppm.client.answerAssistantQuestions
import com.hienlh.ppm.client.assistantBasics
import com.hienlh.ppm.client.assistantCards
import com.hienlh.ppm.client.assistantMainSession
import com.hienlh.ppm.client.assistantOpenCards
import com.hienlh.ppm.client.assistantTelegram
import com.hienlh.ppm.client.assistantWaiting
import com.hienlh.ppm.client.chooseAssistantCardOption
import com.hienlh.ppm.client.decideRulesChange
import com.hienlh.ppm.client.dismissAssistantCard
import com.hienlh.ppm.client.managerChats
import com.hienlh.ppm.client.okAssistantCard
import com.hienlh.ppm.client.replyToAssistantCard
import com.hienlh.ppm.client.setAssistantMainSession
import com.hienlh.ppm.client.setTelegramQuiet
import com.hienlh.ppm.protocol.AssistantCard
import com.hienlh.ppm.protocol.AssistantTelegram
import com.hienlh.ppm.protocol.AssistantWaiting
import com.hienlh.ppm.protocol.InlineImage
import com.hienlh.ppm.protocol.MainSessionRef
import com.hienlh.ppm.protocol.ManagerChatSummary
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/** A tab of the decision set that no longer waits: how it ended, kept so the user sees it. */
data class DoneTab(val card: AssistantCard, val sent: Boolean, val note: String, val at: Long)

/** What the user picked on each waiting decision, and how each send went (`decision-set-store.ts`). */
data class DecisionSetState(
    val picks: Map<String, DecisionPick> = emptyMap(),
    /** A word on a tab: its pick was taken back, or its send failed. */
    val notices: Map<String, String> = emptyMap(),
    /** The server asks before sending this card's reply. */
    val confirms: Map<String, String> = emptyMap(),
    val done: Map<String, DoneTab> = emptyMap(),
    val order: List<String> = emptyList(),
    val sending: Set<String> = emptySet(),
)

/** Everything the Assistant window and its chips show, read from the server and its events. */
data class AssistantView(
    /** The stream's loaded pages, newest first; null until the first page is in. */
    val cards: List<AssistantCard>? = null,
    val nextBefore: Long? = null,
    val loadingEarlier: Boolean = false,
    /** The project the stream is narrowed to; null shows every project. */
    val filter: String? = null,
    /** Every card still waiting on the user, oldest first; null until read. */
    val open: List<AssistantCard>? = null,
    val waiting: AssistantWaiting = AssistantWaiting.NONE,
    val managers: List<ManagerChatSummary>? = null,
    val mainLoaded: Boolean = false,
    val main: MainSessionRef? = null,
    val telegram: AssistantTelegram? = null,
    /** The persona's name, when the user gave one. */
    val name: String? = null,
    val decisions: DecisionSetState = DecisionSetState(),
    /** The card the reply bar answers, in place of the Assistant's composer. */
    val replyTarget: AssistantCard? = null,
    /** A card the Waiting chip, a count or a stub asked to be shown; [jumpNonce] moves each time. */
    val jumpCardId: String? = null,
    val jumpNonce: Int = 0,
) {
    /** The name in the window's head: the persona's, else "Assistant". */
    val shortName: String get() = name ?: "Assistant"

    /** The name in menus and titles: the persona's, else "PPM Assistant". */
    val displayName: String get() = name ?: "PPM Assistant"

    fun isManagerChat(sessionId: String?): Boolean = sessionId != null && managers.orEmpty().any { it.sessionId == sessionId }

    fun managerOf(sessionId: String?): ManagerChatSummary? = sessionId?.let { id -> managers.orEmpty().firstOrNull { it.sessionId == id } }

    /** Waiting cards in the order the user steps through them, narrowed by the filter. */
    val waitingInOrder: List<AssistantCard>
        get() = orderWaitingCards(open.orEmpty()).filter { filter == null || it.projectName == filter }
}

/**
 * The PPM Assistant's state on this device: cards (`assistant-cards-store.ts`), the waiting list
 * and counts (`assistant-open-cards-store.ts`, `assistant-waiting-store.ts`), manager chats, the
 * main session, Telegram's quiet switch, the persona's name and the decision set. Fed by HTTP and
 * by the `assistant:*` events of `/ws/global` ([handleEvent]); call from the main thread.
 */
class AssistantStore(
    private val api: ApiClient,
    private val scope: CoroutineScope,
    initialFilter: String? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The filter is kept per device; the app stores it. */
    private val onFilterChanged: (String?) -> Unit = {},
) {
    private val _view = MutableStateFlow(AssistantView(filter = initialFilter))
    val view: StateFlow<AssistantView> = _view.asStateFlow()

    private var cardsJob: Job? = null

    /** Card events since the waiting list was asked for: an older answer must not overwrite them. */
    private var openChanges = 0

    /** Reads everything once; again after a reconnect ([refresh]). */
    fun start() = refresh()

    fun refresh() {
        reloadCards()
        refreshOpen()
        launchQuiet { val counts = api.assistantWaiting(); _view.update { it.copy(waiting = counts) } }
        refreshManagers()
        launchQuiet {
            val main = api.assistantMainSession()
            _view.update { it.copy(mainLoaded = true, main = main) }
        }.invokeOnCompletion { if (!_view.value.mainLoaded) _view.update { it.copy(mainLoaded = true) } }
        launchQuiet { val telegram = api.assistantTelegram(); _view.update { it.copy(telegram = telegram) } }
        launchQuiet { val name = api.assistantBasics().name; _view.update { it.copy(name = name) } }
    }

    fun refreshManagers() = launchQuiet { val managers = api.managerChats(); _view.update { it.copy(managers = managers) } }

    /** The persona's name as Settings just saved it (`rememberAssistantName`); empty is none. */
    fun rememberName(name: String?) = _view.update { it.copy(name = name?.takeIf { n -> n.isNotEmpty() }) }

    private fun refreshOpen() {
        val at = openChanges
        launchQuiet {
            val cards = api.assistantOpenCards()
            if (at == openChanges) _view.update { it.copy(open = cards) }
        }
    }

    /** The first page again, for the filter as it is now. */
    private fun reloadCards() {
        cardsJob?.cancel()
        val filter = _view.value.filter
        cardsJob = launchQuiet {
            val page = api.assistantCards(project = filter)
            if (_view.value.filter == filter) _view.update { it.copy(cards = page.cards, nextBefore = page.nextBefore) }
        }
    }

    fun loadEarlier() {
        val v = _view.value
        val before = v.nextBefore ?: return
        if (v.loadingEarlier) return
        _view.update { it.copy(loadingEarlier = true) }
        launchQuiet {
            try {
                val page = api.assistantCards(before = before, project = v.filter)
                if (_view.value.filter == v.filter) {
                    _view.update { s ->
                        val merged = page.cards.fold(s.cards.orEmpty()) { acc, c -> upsertCard(acc, c) }
                        s.copy(cards = merged, nextBefore = page.nextBefore)
                    }
                }
            } finally {
                _view.update { it.copy(loadingEarlier = false) }
            }
        }
    }

    fun setFilter(project: String?) {
        if (_view.value.filter == project) return
        _view.update { it.copy(filter = project, cards = null, nextBefore = null) }
        onFilterChanged(project)
        reloadCards()
    }

    /** One `assistant:*` frame of `/ws/global`. */
    fun handleEvent(type: String, frame: JsonElement) {
        when (type) {
            "assistant:card" -> AssistantCard.from(frame["card"])?.let(::take)
            "assistant:waiting_changed" -> {
                val counts = AssistantWaiting.from(frame)
                _view.update { it.copy(waiting = counts) }
                val open = _view.value.open
                if (open == null || !countsMatch(open, counts)) refreshOpen()
            }
            "assistant:managers_changed" -> _view.update { it.copy(managers = ManagerChatSummary.list(frame["managers"])) }
            "assistant:main_session_changed" -> _view.update { it.copy(mainLoaded = true, main = MainSessionRef.from(frame)) }
            "assistant:telegram_quiet_changed" -> frame["on"].bool?.let { on ->
                _view.update { v -> v.copy(telegram = (v.telegram ?: AssistantTelegram(false, false, false)).copy(quietWhileInPpm = on)) }
            }
            "assistant:telegram_binding_changed" -> launchQuiet { val telegram = api.assistantTelegram(); _view.update { it.copy(telegram = telegram) } }
        }
    }

    /** A card as it is now, from an event or an answer: the stream and the waiting list take it. */
    fun take(card: AssistantCard) {
        openChanges++
        _view.update { v ->
            val inFilter = v.filter == null || card.projectName == v.filter
            v.copy(
                cards = if (v.cards != null && inFilter) upsertCard(v.cards, card) else v.cards,
                open = v.open?.let { applyOpenCard(it, card) },
                replyTarget = v.replyTarget?.takeIf { it.id != card.id || card.isOpen },
            )
        }
    }

    // Answers. Each returns null when it went, else the server's sentence.

    suspend fun answer(card: AssistantCard, allow: Boolean): String? = act { api.answerAssistantCard(card.id, allow) }

    suspend fun answerQuestions(card: AssistantCard, answers: Map<String, List<String>>): String? =
        act { api.answerAssistantQuestions(card.id, answers) }

    suspend fun dismiss(card: AssistantCard): String? = act { api.dismissAssistantCard(card.id) }

    suspend fun decideRules(card: AssistantCard, approve: Boolean): String? {
        val project = card.projectName ?: return "This card names no project."
        val hash = card.payload.rulesHash ?: return "This change can only be decided in Settings."
        return try {
            api.decideRulesChange(project, approve, hash)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
    }

    private suspend fun act(call: suspend () -> AssistantCard?): String? = try {
        call()?.let(::take)
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val refusal = CardRefusal.of(e)
        refusal.card?.let(::take)
        refusal.question ?: if (refusal.error == "already_answered") ALREADY_ANSWERED_ELSEWHERE else refusal.error
    }

    /** OK on a decision card, bound to the draft it shows. */
    suspend fun ok(card: AssistantCard): String? = act { api.okAssistantCard(card.id, card.payload.draft?.sha256).card }

    /** One of a decision card's choices. */
    suspend fun choose(card: AssistantCard, optionId: String): String? = act { api.chooseAssistantCardOption(card.id, optionId).card }

    /** The routes a decision's send goes through. */
    val sendApi: DecisionSendApi = object : DecisionSendApi {
        override suspend fun ok(cardId: String, draftSha256: String?) = api.okAssistantCard(cardId, draftSha256).card
        override suspend fun choose(cardId: String, optionId: String) = api.chooseAssistantCardOption(cardId, optionId).card
        override suspend fun reply(cardId: String, text: String, confirmAge: Boolean, images: List<InlineImage>) =
            api.replyToAssistantCard(cardId, text, images = images, confirmAge = confirmAge).card
    }

    // The reply bar

    fun replyTo(card: AssistantCard?) = _view.update { it.copy(replyTarget = card) }

    /** The reply bar's send: [force] once the user confirmed the server's question. */
    suspend fun sendReply(text: String, force: Boolean, images: List<InlineImage> = emptyList()): SendOutcome {
        val card = _view.value.replyTarget ?: return SendOutcome.Failed("No card to reply to.", null)
        return sendReplyTo(card, text, force, images)
    }

    /** A reply to [card] from a bar of its own (a manager chat's, under the card). */
    suspend fun sendReplyTo(card: AssistantCard, text: String, force: Boolean, images: List<InlineImage> = emptyList()): SendOutcome {
        return try {
            val press = api.replyToAssistantCard(card.id, text, force = force, images = images)
            press.card?.let(::take)
            _view.update { if (it.replyTarget?.id == card.id) it.copy(replyTarget = null) else it }
            SendOutcome.Sent(press.card)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val refusal = CardRefusal.of(e)
            if (refusal.error == "confirm" && refusal.question != null) SendOutcome.Confirm(refusal.question!!, refusal.card) else SendOutcome.Failed(refusal.error, refusal.card)
        }
    }

    // Telegram

    suspend fun setQuiet(on: Boolean): String? = try {
        api.setTelegramQuiet(on)
        _view.update { v -> v.copy(telegram = v.telegram?.copy(quietWhileInPpm = on)) }
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.message ?: e.toString()
    }

    /** Makes [sessionId] the main Assistant session (a branch taken from it). */
    suspend fun setMain(sessionId: String): String? = try {
        api.setAssistantMainSession(sessionId)
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.message ?: e.toString()
    }

    // The waiting jump

    fun jumpTo(cardId: String) = _view.update { it.copy(jumpCardId = cardId, jumpNonce = it.jumpNonce + 1) }

    // The decision set

    /** The waiting decisions, in the set's order: new cards at the end, sent ones kept. */
    fun syncDecisionOrder() {
        val v = _view.value
        val waiting = orderWaitingCards(v.open ?: return).filter(::isDecisionCard)
        val d = v.decisions
        val waitingIds = waiting.map { it.id }.toSet()
        // A card that left without being sent from here was answered elsewhere.
        val departed = d.order.filter { it !in waitingIds && it !in d.done && it !in d.sending }
        val history = v.cards.orEmpty()
        val done = d.done.toMutableMap()
        for (id in departed) {
            val latest = history.firstOrNull { it.id == id } ?: continue
            done[id] = DoneTab(latest, false, if (latest.isOpen) "No longer waiting" else answerSummary(latest), clock())
        }
        val keep = done.keys + d.sending
        val outdated = outdatedPicks(waiting, d.picks)
        val notices = d.notices + outdated.associateWith { "This card changed since you picked; look again." }
        val order = nextTabOrder(d.order, waiting.map { it.id }, keep)
        val next = if (waiting.isEmpty() && d.sending.isEmpty()) {
            DecisionSetState()
        } else {
            d.copy(picks = d.picks - outdated.toSet(), notices = notices, done = trimDone(done), order = order)
        }
        if (next != d) _view.update { it.copy(decisions = next) }
    }

    private fun trimDone(done: Map<String, DoneTab>): Map<String, DoneTab> =
        if (done.size <= MAX_DONE) done else done.entries.sortedByDescending { it.value.at }.take(MAX_DONE).associate { it.key to it.value }

    fun pick(card: AssistantCard, answer: DecisionAnswer?) = updateDecisions { d ->
        if (answer == null) d.copy(picks = d.picks - card.id, notices = d.notices - card.id, confirms = d.confirms - card.id)
        else d.copy(picks = d.picks + (card.id to DecisionPick(answer, cardVersion(card))), notices = d.notices - card.id, confirms = d.confirms - card.id)
    }

    private fun updateDecisions(change: (DecisionSetState) -> DecisionSetState) = _view.update { it.copy(decisions = change(it.decisions)) }

    /** A decision set aside from the set: its tab then says "Dismissed". Null when it went, else why not. */
    suspend fun dismissDecision(card: AssistantCard): String? = try {
        val now = api.dismissAssistantCard(card.id)
        updateDecisions { d -> d.copy(done = trimDone(d.done + (card.id to DoneTab(now ?: card, false, "Dismissed", clock())))) }
        now?.let(::take)
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val refusal = CardRefusal.of(e)
        refusal.card?.let(::take)
        refusal.error
    }

    /** "Send this one": the card's pick, alone. */
    fun sendOne(card: AssistantCard, confirmAge: Boolean = false) {
        val answer = currentPick(card, _view.value.decisions.picks)?.answer ?: return
        updateDecisions { it.copy(sending = it.sending + card.id, confirms = it.confirms - card.id) }
        scope.launch { land(card, answer, sendDecision(card, answer, sendApi, confirmAge = confirmAge)) }
    }

    /** "Send all answered": every ready pick, one after another. */
    fun sendAll(cards: List<AssistantCard>) {
        val plan = sendPlan(cards, _view.value.decisions.picks)
        if (plan.isEmpty()) return
        updateDecisions { it.copy(sending = it.sending + plan.map { p -> p.first.id }) }
        scope.launch {
            val byId = plan.associateBy { it.first.id }
            sendAllDecisions(plan, sendApi) { id, outcome -> byId[id]?.let { (card, answer) -> land(card, answer, outcome) } }
        }
    }

    private fun land(card: AssistantCard, answer: DecisionAnswer, outcome: SendOutcome) {
        updateDecisions { it.copy(sending = it.sending - card.id) }
        val now = clock()
        when (outcome) {
            is SendOutcome.Sent -> {
                updateDecisions { d -> d.copy(done = trimDone(d.done + (card.id to DoneTab(outcome.card ?: card, true, answerLabel(card, answer), now))), picks = d.picks - card.id) }
                outcome.card?.let(::take)
            }
            is SendOutcome.Elsewhere -> {
                updateDecisions { d -> d.copy(done = trimDone(d.done + (card.id to DoneTab(outcome.card ?: card, false, outcome.message, now)))) }
                outcome.card?.let(::take)
            }
            is SendOutcome.Confirm -> updateDecisions { it.copy(confirms = it.confirms + (card.id to outcome.question)) }
            is SendOutcome.Changed -> {
                updateDecisions { it.copy(notices = it.notices + (card.id to outcome.message), picks = it.picks - card.id) }
                outcome.card?.let(::take)
            }
            is SendOutcome.Failed -> {
                updateDecisions { it.copy(notices = it.notices + (card.id to outcome.message)) }
                outcome.card?.let(::take)
            }
        }
    }

    private fun launchQuiet(block: suspend () -> Unit): Job = scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // An older server, or none: the window shows what it has.
        }
    }

    companion object {
        /** Finished tabs the set keeps. */
        const val MAX_DONE = 6
    }
}
