package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.OutboundTeamMessage
import com.hienlh.ppm.protocol.TeamActivityFeed
import com.hienlh.ppm.protocol.TeamMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** The most inbox messages a chat holds; the oldest go first. */
const val MAX_TEAM_MESSAGES = 500

/** The most messages the team panel lists. */
const val MAX_TEAM_CONVERSATION = 200

/** How often a shown team's members are read again (`POLL_INTERVAL_MS`). */
const val TEAM_ACTIVITY_POLL_MILLIS = 5_000L

/**
 * The session's agent teams and what their inboxes received (`teamActivityRef` in use-chat.ts).
 * [names] are in the order they were found; [unread] counts the messages pushed live since the
 * team panel was last opened.
 */
data class TeamActivity(
    val names: List<String> = emptyList(),
    val messages: List<TeamMessage> = emptyList(),
    val unread: Int = 0,
) {
    val hasTeams: Boolean get() = names.isNotEmpty()

    /** `primaryTeam`: the team whose members the running agents bar lists. */
    val primary: String? get() = names.firstOrNull()

    fun withTeam(name: String): TeamActivity = if (name in names) this else copy(names = names + name)

    /** `team_inbox`: messages just delivered, every one of them unread. */
    fun received(batch: List<TeamMessage>): TeamActivity =
        copy(messages = (messages + batch).takeLast(MAX_TEAM_MESSAGES), unread = unread + batch.size)

    /**
     * `loadTeamDetail`: a team's inbox history, less what is already held (the same time and
     * sender), merged in time order. History is not news: [unread] stays.
     */
    fun merged(history: List<TeamMessage>): TeamActivity {
        val held = messages.mapTo(HashSet()) { it.timestamp to it.from }
        val added = history.filter { (it.timestamp to it.from) !in held }
        return copy(messages = (messages + added).sortedBy { it.time?.toEpochMilli() ?: 0L }.takeLast(MAX_TEAM_MESSAGES))
    }

    /** `markTeamRead`: the team panel was opened. */
    fun read(): TeamActivity = if (unread == 0) this else copy(unread = 0)
}

/**
 * What the team panel's Messages tab lists (`displayMessages`): the [inbox], which holds only what
 * went to a member, and the replies the members sent back with SendMessage, in time order.
 */
fun teamConversation(inbox: List<TeamMessage>, outbound: List<OutboundTeamMessage>): List<TeamMessage> =
    (inbox + outbound.map { TeamMessage(it.from, it.to, it.text, it.timestamp, it.summary) })
        .sortedBy { it.time?.toEpochMilli() ?: 0L }
        .takeLast(MAX_TEAM_CONVERSATION)

/**
 * Reads a team's members now and every [TEAM_ACTIVITY_POLL_MILLIS] until cancelled
 * (`useTeamActivityFeed`). A failed read keeps the last answer rather than blanking the list.
 */
suspend fun pollTeamActivity(fetch: suspend () -> TeamActivityFeed, onFeed: (TeamActivityFeed) -> Unit): Nothing {
    while (true) {
        try {
            onFeed(fetch())
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
        delay(TEAM_ACTIVITY_POLL_MILLIS)
    }
}
