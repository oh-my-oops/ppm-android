package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.AgentRunningEntry
import com.hienlh.ppm.protocol.ChatEvent
import com.hienlh.ppm.protocol.TeamMemberActivity
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonElement
import java.time.Duration
import java.time.Instant

/*
 * The running agents bar's rows (`running-agent-rows.ts`), and the wording every surface uses for
 * what an agent is doing (`team-member-activity-format.ts`).
 */

/** One agent on the bar: a card this session spawned, a teammate, or a finished sibling of a running card. */
data class RunningAgentRow(
    val key: String,
    val cardId: String? = null,
    val memberName: String? = null,
    val lastStep: String? = null,
    val agentType: String? = null,
    /** Known only for a teammate the member poll has read. */
    val startedAt: Instant? = null,
    val lastWriteAt: Long? = null,
    /** A sibling that has finished, listed beside the agents of its batch still at work. */
    val done: Boolean = false,
    val failed: Boolean = false,
)

/**
 * The hub's feed decides who is running (whose transcript was written to lately); the member
 * poll only adds an agent type and a start time to a name the hub reported, except a member the
 * poll already sees working before the hub catches up, so a teammate never drops out of the bar
 * between polls.
 */
fun buildRunningRows(running: List<AgentRunningEntry>, members: List<TeamMemberActivity>): List<RunningAgentRow> {
    val byName = members.associateBy { it.name }
    val seen = HashSet<String>()
    val rows = mutableListOf<RunningAgentRow>()
    for (entry in running) {
        val cardId = entry.cardId
        val memberName = entry.memberName
        if (cardId == null && memberName == null) continue
        // A named card is a teammate too: one row, keyed by the card.
        if (memberName != null) seen += memberName
        val member = memberName?.let(byName::get)
        rows += RunningAgentRow(
            key = if (cardId != null) "card:$cardId" else "member:$memberName",
            cardId = cardId,
            memberName = memberName,
            lastStep = entry.lastStep ?: member?.let(::currentStep),
            agentType = member?.agentType,
            startedAt = member?.startedAt,
            lastWriteAt = entry.lastWriteAt,
        )
    }
    for (member in members) {
        if (!member.isWorking || member.name in seen) continue
        rows += RunningAgentRow(
            key = "member:${member.name}",
            memberName = member.name,
            lastStep = currentStep(member),
            agentType = member.agentType,
            startedAt = member.startedAt,
        )
    }
    return rows
}

/**
 * Agents that finished beside ones still running: the other Agent/Task calls of a message that
 * launched a running card, so a fan-out reads "5 agents running · 1 done". The hub alone says who
 * runs, so a sibling it does not report is done; the call only says whether it failed. A launch
 * with nothing left running stays off the bar.
 */
fun finishedSiblingRows(items: List<ChatItem>, running: List<RunningAgentRow>): List<RunningAgentRow> {
    val runningCards = running.mapNotNullTo(HashSet()) { it.cardId }
    if (runningCards.isEmpty()) return emptyList()
    val rows = mutableListOf<RunningAgentRow>()
    val seen = HashSet<String>()
    for (item in items) {
        val calls = item.blocks.mapNotNull { block -> (block as? ChatBlock.Tool)?.tool?.takeIf { it.isAgent && it.toolUseId != null } }
        if (calls.none { it.toolUseId in runningCards }) continue
        for (call in calls) {
            val id = call.toolUseId!!
            if (id in runningCards || !seen.add(id)) continue
            rows += RunningAgentRow(key = "card:$id", cardId = id, memberName = addressableName(call.input), done = true, failed = call.agentFailed)
        }
    }
    return rows
}

/** A backgrounded agent failed when it ended any other way than completed; a foreground one when its call errored. */
private val ToolBlock.agentFailed: Boolean
    get() {
        bgStatus?.takeIf { it.isNotEmpty() }?.let { return it != "completed" }
        val result = result ?: return false
        return result.isError && !isAsyncAgentLaunchAck(result.output)
    }

/** A backgrounded agent's call answers this the moment the agent starts (`isAsyncAgentLaunchAck`). */
fun isAsyncAgentLaunchAck(output: String): Boolean = output.contains("Async agent launched successfully")

/** A card's label from what the chat itself holds, which beats a bare id (`CardLabel`). */
data class CardLabel(
    /** The `name` it was launched with, by which SendMessage reaches it. */
    val handle: String?,
    val description: String,
    /** When the message that launched it was written: the nearest thing to a start time. */
    val launchedAt: Instant?,
)

/** The card [cardId] in [items], the newest first, nested cards included (`findCardLabel`). */
fun findCardLabel(items: List<ChatItem>, cardId: String): CardLabel? {
    for (item in items.asReversed()) {
        val input = agentInput(item.blocks, cardId) ?: continue
        return CardLabel(addressableName(input), input["description"].string ?: input["prompt"].string ?: "", item.timestamp)
    }
    return null
}

private fun isAgentName(name: String) = name == "Agent" || name == "Task"

private fun agentInput(blocks: List<ChatBlock>, cardId: String): JsonElement? {
    for (block in blocks) {
        val tool = (block as? ChatBlock.Tool)?.tool ?: continue
        if (isAgentName(tool.name) && tool.toolUseId == cardId) return tool.input
        tool.children?.kept?.let { kept -> agentInput(kept.map { it.event }, cardId) }?.let { return it }
    }
    return null
}

@JvmName("agentInputInEvents")
private fun agentInput(events: List<ChatEvent>, cardId: String): JsonElement? {
    for (event in events) {
        val use = event as? ChatEvent.ToolUse ?: continue
        if (isAgentName(use.name) && use.toolUseId == cardId) return use.input
        use.children?.let { agentInput(it, cardId) }?.let { return it }
    }
    return null
}

private fun addressableName(input: JsonElement): String? = input["name"].string?.trim()?.takeIf { it.isNotEmpty() }

/** What a row of the bar shows (`AgentView`): who, the prompt it was given, its step, and for how long it has run. */
data class RunningAgentView(val row: RunningAgentRow, val name: String, val prompt: String?, val step: String?, val elapsed: String?)

fun RunningAgentRow.view(label: CardLabel?, now: Instant): RunningAgentView {
    val description = label?.description?.takeIf { it.isNotEmpty() }
    val started = startedAt ?: label?.launchedAt
    return RunningAgentView(
        row = this,
        name = memberName ?: label?.handle ?: description ?: "Agent",
        prompt = description,
        step = lastStep ?: shortAgentType(agentType),
        elapsed = if (done || started == null) null else formatDuration(started, null, now),
    )
}

/** The trailing segment of an agent type, `ak-engineer:tester` reading as `tester`. */
fun shortAgentType(agentType: String?): String? {
    if (agentType.isNullOrEmpty()) return null
    val tail = agentType.substringAfterLast(':')
    return if (tail.isNotEmpty() && tail != "general-purpose") tail else agentType
}

/** `1h 4m`, `12m` or `45s`, from [from] to [to] (else [now]); null when there is no start or it is later. */
fun formatDuration(from: Instant?, to: Instant?, now: Instant): String? {
    if (from == null) return null
    val end = to ?: now
    if (end.isBefore(from)) return null
    val seconds = Duration.between(from, end).toMillis() / 1000
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${seconds}s"
    }
}

/** The best one-line answer to "what is it doing", from the tail of its transcript. */
fun currentStep(member: TeamMemberActivity): String? {
    val tool = member.lastTool?.takeIf { it.isNotEmpty() }
    if (tool != null) return member.lastToolArg?.takeIf { it.isNotEmpty() }?.let { "$tool: $it" } ?: tool
    return member.lastNarrative ?: member.description
}
