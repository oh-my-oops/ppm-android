package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement
import java.time.Instant

/*
 * Group chat (`/api/group-chat`, `/ws/project/:p/group/:id`): AI members a user talks to in one
 * feed, a leader among them. The server stores its times as SQLite's `unixepoch()`, in seconds.
 */

/** A store time read as the web's `formatTime` reads it: below 1e12 it is seconds, else milliseconds. */
fun groupTime(value: Long?): Instant? = when {
    value == null -> null
    value < 1_000_000_000_000L -> Instant.ofEpochSecond(value)
    else -> Instant.ofEpochMilli(value)
}

enum class GroupStatus(val wire: String) {
    ACTIVE("active"), PAUSED("paused"), IDLE("idle");

    companion object {
        fun of(text: String?): GroupStatus = entries.firstOrNull { it.wire == text } ?: IDLE
    }
}

enum class MemberStatus(val wire: String) {
    IDLE("idle"), WORKING("working"), DONE("done"), ERROR("error");

    companion object {
        fun of(text: String?): MemberStatus = entries.firstOrNull { it.wire == text } ?: IDLE
    }
}

/** `GET /api/group-chat?projectPath=` lists these; `GET /:id` adds [members] (`GroupDetail`). */
data class Group(
    val id: String,
    val projectName: String,
    val projectPath: String,
    val name: String,
    val status: GroupStatus = GroupStatus.IDLE,
    /** The reply cap: AI turns per user message, 1–50. */
    val maxTurns: Int = DEFAULT_MAX_TURNS,
    val maxCostUsd: Double = 5.0,
    val leaderSessionId: String? = null,
    val createdAt: Instant? = null,
    val members: List<GroupMember> = emptyList(),
) {
    companion object {
        const val DEFAULT_MAX_TURNS = 10

        fun from(json: JsonElement?): Group? {
            val id = json["id"].string ?: return null
            return Group(
                id = id,
                projectName = json["projectName"].string.orEmpty(),
                projectPath = json["projectPath"].string.orEmpty(),
                name = json["name"].string.orEmpty(),
                status = GroupStatus.of(json["status"].string),
                maxTurns = json["maxTurns"].int ?: DEFAULT_MAX_TURNS,
                maxCostUsd = json["maxCostUsd"].double ?: 5.0,
                leaderSessionId = json["leaderSessionId"].string,
                createdAt = groupTime(json["createdAt"].long),
                members = json["members"].array.orEmpty().mapNotNull(GroupMember::from),
            )
        }
    }
}

data class GroupMember(
    val id: String,
    val name: String,
    /** `leader` or `member`: a group has exactly one leader. */
    val role: String = "member",
    val persona: String? = null,
    val model: String? = null,
    val color: String? = null,
    val status: MemberStatus = MemberStatus.IDLE,
    /** The member's session, once a reply burst has made one: its transcript is "View full transcript". */
    val sessionId: String? = null,
) {
    val isLeader: Boolean get() = role == "leader"

    companion object {
        fun from(json: JsonElement?): GroupMember? {
            val id = json["id"].string ?: return null
            return GroupMember(
                id = id,
                name = json["name"].string.orEmpty(),
                role = json["role"].string ?: "member",
                persona = json["persona"].string,
                model = json["model"].string,
                color = json["color"].string,
                status = MemberStatus.of(json["status"].string),
                sessionId = json["sessionId"].string,
            )
        }
    }
}

/** One message of the feed: the user's (`fromMember` "user") or a member's reply. */
data class GroupMessage(
    val id: String,
    val fromMember: String,
    /** Whom a reply pulls in next; "all" or null for nobody in particular. */
    val toMember: String? = null,
    val kind: String = "chat",
    val summary: String? = null,
    /** The replying member's session: its transcript is the message's "full transcript". */
    val fullSessionRef: String? = null,
    val turnIndex: Int = 0,
    val createdAt: Instant? = null,
) {
    val isUser: Boolean get() = fromMember == "user"

    companion object {
        fun from(json: JsonElement?): GroupMessage? {
            val id = json["id"].string ?: return null
            return GroupMessage(
                id = id,
                fromMember = json["fromMember"].string.orEmpty(),
                toMember = json["toMember"].string,
                kind = json["kind"].string ?: "chat",
                summary = json["summary"].string,
                fullSessionRef = json["fullSessionRef"].string,
                turnIndex = json["turnIndex"].int ?: 0,
                createdAt = groupTime(json["createdAt"].long),
            )
        }
    }
}

/** A member as `group_state` carries it: the live status and colour, nothing to edit. */
data class RosterMember(
    val id: String,
    val name: String,
    val role: String,
    val status: MemberStatus,
    val color: String?,
) {
    companion object {
        fun from(json: JsonElement?): RosterMember? {
            val id = json["id"].string ?: return null
            return RosterMember(
                id = id,
                name = json["name"].string.orEmpty(),
                role = json["role"].string ?: "member",
                status = MemberStatus.of(json["status"].string),
                color = json["color"].string,
            )
        }
    }
}

/** A member as the create and edit forms send it (`CreateMemberInput`, `MemberFormValues`). */
data class MemberValues(
    val name: String,
    val role: String = "member",
    val persona: String? = null,
    val model: String? = null,
    val color: String? = null,
)

/** What a member's session ran with (`TranscriptConfig`), read from its transcript. */
data class TranscriptConfig(
    val model: String? = null,
    val cwd: String? = null,
    val gitBranch: String? = null,
    val version: String? = null,
    val permissionMode: String? = null,
) {
    companion object {
        fun from(json: JsonElement?): TranscriptConfig? {
            if (json.obj == null) return null
            return TranscriptConfig(
                model = json["model"].string,
                cwd = json["cwd"].string,
                gitBranch = json["gitBranch"].string,
                version = json["version"].string,
                permissionMode = json["permissionMode"].string,
            )
        }
    }
}

/** A member session's transcript, parsed as chat history is (`GET /:id/transcript`). */
data class GroupTranscript(val messages: List<HistoryMessage>, val config: TranscriptConfig?) {
    companion object {
        fun from(json: JsonElement?): GroupTranscript =
            GroupTranscript(json["messages"].array.orEmpty().mapNotNull(HistoryMessage::from), TranscriptConfig.from(json["config"]))
    }
}
