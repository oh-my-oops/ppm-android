package com.hienlh.ppm.state.assistant

import com.hienlh.ppm.protocol.AssistantCard
import com.hienlh.ppm.protocol.AssistantWaiting
import com.hienlh.ppm.protocol.ChatMessageBlocks
import com.hienlh.ppm.protocol.REPORT_KINDS
import com.hienlh.ppm.protocol.WAITING_CARD_KINDS
import com.hienlh.ppm.protocol.isNoReplyText
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.ChatBlock
import com.hienlh.ppm.state.ChatItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The PPM Assistant's stream as data, pure so the order is tested without a screen:
 * `assistant-timeline-merge.ts` (cards among the messages by time), `assistant-stream-filter.ts`
 * (the project filter and folded wake turns), `use-assistant-stream-timeline.tsx` (which cards the
 * stream draws), and the card-shell helpers that name a card's time and how it ended.
 */

/** Where items land among loaded rows: before a row's index, after every row, or held back. */
data class TimelinePlaced<T>(val before: Map<Int, List<T>>, val trailing: List<T>, val held: Int)

/** The time of each row in ms; a row with no time takes the previous row's (`rowTimes`). */
fun rowTimes(rows: List<ChatItem>): List<Long> {
    var last = Long.MIN_VALUE
    return rows.map { row ->
        row.timestamp?.toEpochMilli()?.let { last = maxOf(it, last) }
        last
    }
}

/**
 * An item goes right before the first row newer than it; one newer than every row trails. One
 * older than the oldest loaded row is held while older history exists, unless [keep] says it must
 * stay reachable, when it goes before the oldest row (`placeTimelineItems`).
 */
fun <T> placeTimelineItems(
    rows: List<ChatItem>,
    items: List<T>,
    at: (T) -> Long,
    olderHidden: Boolean,
    keep: (T) -> Boolean = { false },
): TimelinePlaced<T> {
    val sorted = items.withIndex().sortedWith(compareBy<IndexedValue<T>>({ at(it.value) }, { it.index })).map { it.value }
    val times = rowTimes(rows)
    val before = LinkedHashMap<Int, MutableList<T>>()
    val trailing = mutableListOf<T>()
    var held = 0
    var row = 0
    for (item in sorted) {
        val t = at(item)
        while (row < times.size && times[row] <= t) row++
        if (row >= times.size) {
            trailing += item
            continue
        }
        if (row == 0 && olderHidden && !keep(item)) {
            held++
            continue
        }
        before.getOrPut(row) { mutableListOf() } += item
    }
    return TimelinePlaced(before, trailing, held)
}

/** Rows grouped by turn; rows before the first user message form a turn of their own. */
fun groupTurns(rows: List<ChatItem>): List<List<ChatItem>> {
    val turns = mutableListOf<MutableList<ChatItem>>()
    for (row in rows) {
        if (row.role == ChatItem.Role.USER || turns.isEmpty()) turns += mutableListOf(row) else turns.last() += row
    }
    return turns
}

/** `mcp__ppm-sessions__send_to_session` and `ppm_sessions.send_to_session` alike. */
fun bareToolName(tool: String): String {
    val cut = maxOf(tool.lastIndexOf("__"), tool.lastIndexOf("."))
    if (cut < 0) return tool
    return tool.substring(cut + if (tool[cut] == '.') 1 else 2)
}

/** Whether one tool call reaches [projectName]'s manager chat, [managerSessionId]. */
fun callTouchesManager(tool: String, input: kotlinx.serialization.json.JsonElement?, projectName: String, managerSessionId: String?): Boolean {
    val args = input as? kotlinx.serialization.json.JsonObject ?: kotlinx.serialization.json.JsonObject(emptyMap())
    return when (bareToolName(tool)) {
        "create_session" -> args["project"].string == projectName
        "send_to_session" -> managerSessionId != null && args["session_id"].string == managerSessionId
        "session_status" -> managerSessionId != null &&
            (args["session_ids"] as? kotlinx.serialization.json.JsonArray)?.any { it.string == managerSessionId } == true
        else -> false
    }
}

/** Ids of the rows a project filter hides: every turn that did not touch that project's manager. */
fun hiddenByProjectFilter(rows: List<ChatItem>, projectName: String, managerSessionId: String?): Set<String> {
    val hidden = HashSet<String>()
    for (turn in groupTurns(rows)) {
        val touches = turn.any { row ->
            row.role == ChatItem.Role.ASSISTANT && row.blocks.any { block ->
                block is ChatBlock.Tool && callTouchesManager(block.tool.name, block.tool.input, projectName, managerSessionId)
            }
        }
        if (!touches) turn.forEach { hidden += it.id }
    }
    return hidden
}

/** A wake turn folded to one line: the rows it replaces, its time, and when the turn before opened. */
data class CollapsedWakeTurn(val ids: List<String>, val at: Long, val since: Long)

private fun finalText(turn: List<ChatItem>): String? {
    for (row in turn.asReversed()) {
        if (row.role != ChatItem.Role.ASSISTANT) continue
        row.blocks.filterIsInstance<ChatBlock.Text>().lastOrNull()?.let { return it.text }
        if (row.answerText.isNotBlank()) return row.answerText
    }
    return null
}

/**
 * Turns PPM started on its own (`[PPM] News …`: manager reports, restart news) that the Assistant
 * answered `[no reply]`: nothing in them is for the user, so each shows as one muted line.
 */
fun collapsedWakeTurns(rows: List<ChatItem>): List<CollapsedWakeTurn> {
    val out = mutableListOf<CollapsedWakeTurn>()
    var previous = 0L
    for (turn in groupTurns(rows)) {
        val opener = turn.first()
        val at = opener.timestamp?.toEpochMilli()
        val fromPpm = opener.role == ChatItem.Role.USER && ChatMessageBlocks.decode(opener.text).content.trimStart().startsWith("[PPM] News")
        if (fromPpm && turn.size > 1 && isNoReplyText(finalText(turn))) {
            out += CollapsedWakeTurn(turn.map { it.id }, at ?: previous, previous)
        }
        if (opener.role == ChatItem.Role.USER && at != null) previous = at
    }
    return out
}

/** The folded wake turn's line (`wakeTurnText`). */
fun wakeTurnText(reports: Int): String =
    if (reports <= 0) "Assistant read the manager news" else "Assistant read $reports report${if (reports == 1) "" else "s"}"

/** Report cards that landed in a wake turn's window: what that turn read. */
fun reportsRead(cards: List<AssistantCard>, since: Long, at: Long): Int =
    cards.count { it.kind in REPORT_KINDS && it.createdAt > since && it.createdAt <= at }

/** The loaded messages reach further back than the oldest loaded card, and the server has older ones. */
fun needsEarlierCards(oldestRowAt: Long?, cards: List<AssistantCard>, nextBefore: Long?): Boolean {
    if (nextBefore == null || oldestRowAt == null || cards.isEmpty()) return false
    return oldestRowAt < cards.last().createdAt
}

/** The loaded pages, plus every waiting card the filter lets in that no page holds yet. */
fun streamCards(loaded: List<AssistantCard>, waiting: List<AssistantCard>, filter: String?): List<AssistantCard> {
    val have = loaded.mapTo(HashSet()) { it.id }
    val extra = waiting.filter { it.id !in have && (filter == null || it.projectName == filter) }
    return loaded + extra
}

/** A card that waited, and no longer does: drawn folded. */
fun isAnsweredDecision(card: AssistantCard): Boolean = card.kind in WAITING_CARD_KINDS && card.status != "open"

fun isDecisionCard(card: AssistantCard): Boolean = card.kind == "needs_decision" || card.kind == "urgent"

/** Newest first, the card replacing its older copy (`upsertCard`). */
fun upsertCard(cards: List<AssistantCard>, card: AssistantCard): List<AssistantCard> =
    (cards.filter { it.id != card.id } + card).sortedByDescending { it.createdAt }

/** The waiting list after [card] changed: in it while it waits, oldest first (`applyOpenCard`). */
fun applyOpenCard(cards: List<AssistantCard>, card: AssistantCard): List<AssistantCard> {
    val rest = cards.filter { it.id != card.id }
    return if (card.isWaiting) (rest + card).sortedBy { it.createdAt } else rest
}

/** Urgent first, then the one that has waited longest. */
fun orderWaitingCards(cards: List<AssistantCard>): List<AssistantCard> =
    cards.sortedWith(compareBy<AssistantCard>({ if (it.kind == "urgent") 0 else 1 }, { it.createdAt }))

/** Whether counts the server announced describe the list held here. */
fun countsMatch(cards: List<AssistantCard>, counts: AssistantWaiting): Boolean {
    if (cards.size != counts.total) return false
    val mine = cards.mapNotNull { it.projectName }.groupingBy { it }.eachCount()
    return (mine.keys + counts.byProject.keys).all { (mine[it] ?: 0) == (counts.byProject[it] ?: 0) }
}

/** The dot on a manager chip: working wins, then something waiting, else idle. */
enum class ManagerDot(val label: String) { RUNNING("working"), NEEDS("waiting on you"), IDLE("idle") }

fun managerDotState(phase: String, waiting: Int): ManagerDot = when {
    phase.isNotEmpty() && phase != "idle" -> ManagerDot.RUNNING
    waiting > 0 -> ManagerDot.NEEDS
    else -> ManagerDot.IDLE
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
private val DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US)

/** "14:05" today, "Nov 3, 14:05" another day (`cardTime`). */
fun cardTime(ms: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    val d = Instant.ofEpochMilli(ms).atZone(zone)
    val time = TIME.format(d)
    return if (Instant.ofEpochMilli(now).atZone(zone).toLocalDate() == d.toLocalDate()) time else "${DAY.format(d)}, $time"
}

/** How a card that no longer waits ended; null while it waits (`cardStatusLine`). */
fun cardStatusLine(card: AssistantCard, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String? {
    val at = card.answer?.let { " · ${cardTime(it.at, now, zone)}" } ?: ""
    return when (card.status) {
        "open", "info" -> null
        "dismissed" -> "Dismissed$at"
        "gone" -> "No longer waiting"
        "resolved" -> "${card.answer?.note ?: "Resolved"}$at"
        "answered" -> when {
            card.answer?.note != null -> "${card.answer?.note}$at"
            card.kind == "approval" -> "${if (card.answer?.approved == true) "Allowed" else "Denied"} in PPM$at"
            else -> "Answered in PPM$at"
        }
        else -> null
    }
}

/** How the card ended, without the time (`answerSummary`). */
fun answerSummary(card: AssistantCard, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    val line = cardStatusLine(card, now, zone) ?: "Answered"
    val at = card.answer?.let { " · ${cardTime(it.at, now, zone)}" } ?: ""
    return if (at.isNotEmpty() && line.endsWith(at)) line.dropLast(at.length) else line
}

/** A session named in an Assistant link (`/assistant?session=<provider>/<id>`). */
data class AssistantDeepLink(val sessionId: String? = null, val providerId: String? = null)

private val SESSION_ID = Regex("^[\\w.:-]{1,256}$")
val ASSISTANT_PROVIDER_IDS = listOf("claude", "codex")

/** `parseAssistantSessionRef`: `<provider>/<id>` or a bare id; anything else names nothing. */
fun parseAssistantSessionRef(raw: String?): AssistantDeepLink {
    if (raw.isNullOrEmpty()) return AssistantDeepLink()
    val slash = raw.indexOf('/')
    val provider = if (slash < 0) null else raw.substring(0, slash)
    val sessionId = if (slash < 0) raw else raw.substring(slash + 1)
    if (!SESSION_ID.matches(sessionId)) return AssistantDeepLink()
    return if (provider != null && provider in ASSISTANT_PROVIDER_IDS) AssistantDeepLink(sessionId, provider) else AssistantDeepLink(sessionId)
}

/** Only a move away from the main session makes the branch the main one (`branchTakesMain`). */
fun branchTakesMain(from: String?, to: String, mainSessionId: String?): Boolean =
    from != null && mainSessionId != null && from == mainSessionId && to != mainSessionId
