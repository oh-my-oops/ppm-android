package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/*
 * An agent team's traffic: what its inboxes received (`team_inbox`, `GET /api/teams/:name`) and
 * what its members are doing (`GET /api/teams/:name/activity`), read by the server from
 * `~/.claude/teams/` and the members' transcripts.
 */

private fun isoTime(text: String?): Instant? = text?.let { WireDate.parse(JsonPrimitive(it)) }

/** A message one teammate's inbox received (`TeamMessageItem`); [to] is that inbox's owner. */
data class TeamMessage(
    val from: String,
    val to: String,
    val text: String,
    /** As the server wrote it: with [from], what tells one message from another (`loadTeamDetail`). */
    val timestamp: String,
    val summary: String? = null,
    /** What the server read the text as (`inferMessageType`): `idle_notification`, `shutdown_approved`… */
    val parsedType: String? = null,
    val color: String? = null,
) {
    val time: Instant? = isoTime(timestamp)

    companion object {
        fun from(value: JsonElement?): TeamMessage? {
            if (value.obj == null) return null
            return TeamMessage(
                from = value["from"].string.orEmpty(),
                to = value["to"].string.orEmpty(),
                text = value["text"].string.orEmpty(),
                timestamp = value["timestamp"].string.orEmpty(),
                summary = value["summary"].string,
                parsedType = value["parsedType"].string,
                color = value["color"].string,
            )
        }
    }
}

/** One member as its transcript tells it (`TeamMemberActivity`). */
data class TeamMemberActivity(
    val name: String,
    /** `working`, `paused` or `no-transcript`. */
    val workState: String,
    val agentType: String? = null,
    val model: String? = null,
    val description: String? = null,
    val toolUseId: String? = null,
    val startedAt: Instant? = null,
    val lastEventAt: Instant? = null,
    val lastTool: String? = null,
    val lastToolArg: String? = null,
    val lastNarrative: String? = null,
    val sizeBytes: Long = 0,
) {
    val isWorking: Boolean get() = workState == "working"

    companion object {
        fun from(value: JsonElement?): TeamMemberActivity? {
            val name = value["name"].string ?: return null
            return TeamMemberActivity(
                name = name,
                workState = value["workState"].string ?: "no-transcript",
                agentType = value["agentType"].string,
                model = value["model"].string,
                description = value["description"].string,
                toolUseId = value["toolUseId"].string,
                startedAt = isoTime(value["startedAt"].string),
                lastEventAt = isoTime(value["lastEventAt"].string),
                lastTool = value["lastTool"].string,
                lastToolArg = value["lastToolArg"].string,
                lastNarrative = value["lastNarrative"].string,
                sizeBytes = value["sizeBytes"].long ?: 0,
            )
        }
    }
}

/** A reply a teammate sent with SendMessage, which no inbox holds (`OutboundTeamMessage`). */
data class OutboundTeamMessage(
    val from: String,
    val to: String,
    val text: String,
    val timestamp: String,
    val summary: String? = null,
) {
    val time: Instant? = isoTime(timestamp)

    companion object {
        fun from(value: JsonElement?): OutboundTeamMessage? {
            if (value.obj == null) return null
            return OutboundTeamMessage(
                from = value["from"].string.orEmpty(),
                to = value["to"].string.orEmpty(),
                text = value["text"].string.orEmpty(),
                timestamp = value["timestamp"].string.orEmpty(),
                summary = value["summary"].string,
            )
        }
    }
}

/** `GET /api/teams/:name/activity`: who is working, and the replies sent back. */
data class TeamActivityFeed(val members: List<TeamMemberActivity>, val outbound: List<OutboundTeamMessage>) {
    companion object {
        fun from(value: JsonElement?): TeamActivityFeed = TeamActivityFeed(
            members = value["members"].array.orEmpty().mapNotNull(TeamMemberActivity::from),
            outbound = value["outbound"].array.orEmpty().mapNotNull(OutboundTeamMessage::from),
        )
    }
}
