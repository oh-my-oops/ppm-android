package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlin.math.roundToLong

/** `GET /api/info` — public, answered before sign-in. */
data class ServerInfo(
    val version: String,
    val deviceName: String?,
    val tunnelActive: Boolean,
    val minimalMode: Boolean = false,
    /** Raised by the server on a breaking change; a server older than the field is 0. */
    val protocol: Int = 0,
    /** Features the server says it has (`chat`, `push.webpush`); none from a server older than the field. */
    val capabilities: Set<String> = emptySet(),
) {
    /** Below [MIN_PROTOCOL] the server sends what this app can no longer read: update PPM there. */
    val isSupported: Boolean get() = protocol >= MIN_PROTOCOL

    /** A feature whose capability the server does not list is hidden, never drawn broken. */
    fun has(capability: String): Boolean = capability in capabilities

    companion object {
        /**
         * The oldest server protocol this app reads (docs/architecture/native-protocol.md). Every
         * server so far works, including those older than the field; raise it only when the app
         * drops what an older server sends.
         */
        const val MIN_PROTOCOL = 0

        fun from(json: JsonElement): ServerInfo? {
            val version = json["version"].string ?: return null
            return ServerInfo(
                version = version,
                deviceName = json["device_name"].string,
                tunnelActive = json["tunnel_active"].bool ?: false,
                minimalMode = json["minimal_mode"].bool ?: json["minimalMode"].bool ?: false,
                protocol = json["protocol"].int ?: 0,
                capabilities = json["capabilities"].array.orEmpty().mapNotNull { it.string }.toSet(),
            )
        }
    }
}

/** An entry of `GET /api/projects`. */
data class ProjectInfo(
    val name: String,
    val path: String,
    /** The avatar colour the user picked: a `#hex` or a palette gradient; null = by position. */
    val color: String? = null,
    /** Set when the project has an uploaded avatar image (its value versions the image URL). */
    val image: String? = null,
) {
    companion object {
        fun from(json: JsonElement): ProjectInfo? {
            val name = json["name"].string ?: return null
            return ProjectInfo(name, json["path"].string ?: "", json["color"].string, json["image"].string)
        }
    }
}

/**
 * One file `POST /api/project/:p/chat/upload` saved: the name it was sent under and where it
 * now lives on the server.
 */
data class UploadedFile(val name: String, val path: String, val type: String, val size: Long) {
    companion object {
        fun from(json: JsonElement): UploadedFile? {
            val path = json["path"].string ?: return null
            return UploadedFile(
                name = json["name"].string ?: path.substringAfterLast('/'),
                path = path,
                type = json["type"].string ?: "",
                size = json["size"].long ?: 0,
            )
        }
    }
}

/**
 * Why a session wants to be looked at (an entry of the web's `notification-store.ts`), from
 * `GET …/chat/sessions/unread` and the `session:unread_changed` broadcast.
 */
data class SessionUnread(
    /** What happened last: `done`, `question` or `approval_request`. */
    val type: String,
    val count: Int,
    val projectName: String,
    val sessionTitle: String?,
    /** Set by "Mark as unread": it stays while the chat is in front, until the chat is picked again. */
    val manual: Boolean = false,
) {
    companion object {
        /** `GET …/chat/sessions/unread`, keyed by session id. */
        fun list(json: JsonElement): Map<String, SessionUnread> {
            val entries = LinkedHashMap<String, SessionUnread>()
            for (row in json.array.orEmpty()) {
                val id = row["sessionId"].string ?: continue
                val count = row["unreadCount"].int ?: continue
                if (count <= 0) continue
                entries[id] = SessionUnread(
                    type = row["unreadType"].string?.takeIf { it.isNotEmpty() } ?: "done",
                    count = count,
                    projectName = row["projectName"].string ?: "",
                    sessionTitle = row["sessionTitle"].string,
                )
            }
            return entries
        }
    }
}

/**
 * `GET …/chat/search`: sessions whose title or messages match, and how far the server's message
 * index has got (it builds itself on the first search).
 */
data class ChatSearchPage(
    val hits: List<ChatSearchHit> = emptyList(),
    val indexed: Int = 0,
    val total: Int = 0,
    val indexing: Boolean = false,
) {
    companion object {
        fun from(json: JsonElement): ChatSearchPage {
            val status = json["indexing"]
            return ChatSearchPage(
                hits = json["results"].array.orEmpty().mapNotNull(ChatSearchHit::from),
                indexed = status["indexed"].int ?: 0,
                total = status["total"].int ?: 0,
                indexing = status["running"].bool ?: false,
            )
        }
    }
}

data class ChatSearchHit(
    val sessionId: String,
    val providerId: String,
    val title: String?,
    /** The words around the match, the matching ones wrapped in `<mark>…</mark>`; for a title match, the title. */
    val snippet: String,
    val matchedInContent: Boolean,
) {
    data class Run(val text: String, val marked: Boolean)

    companion object {
        fun from(json: JsonElement): ChatSearchHit? {
            val id = json["sessionId"].string ?: return null
            return ChatSearchHit(
                sessionId = id,
                providerId = json["providerId"].string ?: "claude",
                title = json["title"].string,
                snippet = json["snippet"].string ?: "",
                matchedInContent = json["matchedIn"].string == "content",
            )
        }

        /**
         * The snippet in runs, [Run.marked] where it matched. Only the exact `<mark>` delimiters
         * are read: everything else is text, as the web's `parseSnippet` does.
         */
        fun runs(snippet: String): List<Run> {
            val runs = mutableListOf<Run>()
            var rest = snippet
            while (true) {
                val open = rest.indexOf("<mark>")
                if (open < 0) break
                val close = rest.indexOf("</mark>", open + 6)
                if (close < 0) break
                if (open > 0) runs += Run(rest.substring(0, open), false)
                runs += Run(rest.substring(open + 6, close), true)
                rest = rest.substring(close + 7)
            }
            if (rest.isNotEmpty()) runs += Run(rest, false)
            return runs
        }
    }
}

/** A project's session tag (`GET /api/project/:p/tags`): Todo, In Progress, Review, Done…. */
data class SessionTag(val id: Int, val name: String, /** `#rrggbb`. */ val color: String) {
    companion object {
        fun from(json: JsonElement): SessionTag? {
            val id = json["id"].int ?: return null
            val name = json["name"].string ?: return null
            return SessionTag(id, name, json["color"].string ?: "#888888")
        }
    }
}

/** `GET /api/project/:p/tags`. */
data class ProjectTags(
    val tags: List<SessionTag> = emptyList(),
    /** Sessions per tag id. */
    val counts: Map<Int, Int> = emptyMap(),
    val defaultTagId: Int? = null,
) {
    companion object {
        fun from(json: JsonElement): ProjectTags = ProjectTags(
            tags = json["tags"].array.orEmpty().mapNotNull(SessionTag::from),
            counts = json["counts"].obj.orEmpty().mapNotNull { (key, value) ->
                val id = key.toIntOrNull() ?: return@mapNotNull null
                val count = value.int ?: return@mapNotNull null
                id to count
            }.toMap(),
            defaultTagId = json["defaultTagId"].int,
        )
    }
}

/** `GET /api/settings/theme`: the theme picked on any device, which every client follows. */
data class ThemeSelection(
    /** `aurora`, `slate`, `precision` or `custom`. */
    val style: String,
    /** `dark`, `light` or `system`. */
    val mode: String,
    val customThemeId: String? = null,
) {
    val json: JsonElement
        get() = buildJsonObject {
            put("style", style)
            put("mode", mode)
            if (customThemeId != null) put("customThemeId", customThemeId)
        }

    companion object {
        fun from(json: JsonElement?): ThemeSelection? {
            val style = json["style"].string ?: return null
            return ThemeSelection(style, json["mode"].string ?: "system", json["customThemeId"].string)
        }
    }
}

/** The two numbers the status bar shows from a metrics snapshot. */
data class ResourceSample(/** Whole-machine CPU, percent. */ val cpu: Double, val memoryUsedMB: Double) {
    companion object {
        fun from(json: JsonElement): ResourceSample? {
            val system = json["system"] ?: return null
            val cpu = system["cpu"]["total"].double ?: return null
            val used = system["mem"]["usedMB"].double ?: return null
            return ResourceSample(cpu, used)
        }
    }
}

/** An entry of `GET /api/project/:p/chat/providers`. */
data class ProviderInfo(val id: String, val name: String) {
    companion object {
        fun from(json: JsonElement): ProviderInfo? {
            val id = json["id"].string ?: return null
            return ProviderInfo(id, json["name"].string ?: id)
        }
    }
}

/** A chat session as `GET /api/project/:p/chat/sessions` lists it. */
data class SessionInfo(
    val id: String,
    val providerId: String,
    val title: String,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val pinned: Boolean = false,
    val tag: SessionTag? = null,
    val spawnedBy: String? = null,
    val parentTitle: String? = null,
) {
    /** When the session was last active, for sorting and display. */
    val lastActive: Instant? get() = updatedAt ?: createdAt

    companion object {
        fun from(json: JsonElement): SessionInfo? {
            val id = json["id"].string ?: return null
            return SessionInfo(
                id = id,
                providerId = json["providerId"].string ?: "claude",
                title = json["title"].string ?: "",
                createdAt = WireDate.parse(json["createdAt"]),
                updatedAt = WireDate.parse(json["updatedAt"]),
                pinned = json["pinned"].bool ?: false,
                tag = json["tag"]?.let(SessionTag::from),
                spawnedBy = json["spawnedBy"].string,
                parentTitle = json["parentTitle"].string,
            )
        }
    }
}

data class SessionPage(val sessions: List<SessionInfo>, val hasMore: Boolean) {
    companion object {
        fun from(json: JsonElement): SessionPage? {
            val list = json["sessions"].array ?: return null
            return SessionPage(list.mapNotNull(SessionInfo::from), json["hasMore"].bool ?: false)
        }
    }
}

/**
 * A message of `GET …/chat/sessions/:id/messages`. History only ever comes from this route;
 * the chat socket carries the turn that is running.
 */
data class HistoryMessage(
    val id: String,
    val role: Role,
    val content: String,
    val events: List<ChatEvent>,
    val timestamp: Instant?,
    /** The SDK's own id for the message, which a reply names when there is one. */
    val sdkUuid: String? = null,
    /** [timestamp] as the server wrote it: a reply finds its message again by comparing this text. */
    val timestampText: String? = null,
    /** Set on the compact summary that opens a segment: what that compaction did. */
    val compaction: Compaction? = null,
    /** The turn the message belongs to, as the server stamps every history message (`turnId`). */
    val turnId: String? = null,
    /** That turn's number in the session (`turnN`). */
    val turnN: Int? = null,
) {
    enum class Role(val wire: String) { USER("user"), ASSISTANT("assistant"), SYSTEM("system") }

    companion object {
        fun from(json: JsonElement): HistoryMessage? {
            val id = json["id"].string ?: return null
            val role = Role.entries.firstOrNull { it.wire == json["role"].string } ?: return null
            return HistoryMessage(
                id = id,
                role = role,
                content = json["content"].string ?: "",
                events = json["events"].array.orEmpty().map(ChatEvent::from),
                timestamp = WireDate.parse(json["timestamp"]),
                sdkUuid = json["sdkUuid"].string,
                timestampText = json["timestamp"].string,
                compaction = json["compaction"]?.let(Compaction::from),
                turnId = json["turnId"].string?.takeIf { FromSessionReference.TURN_ID_RE.matches(it) },
                turnN = json["turnN"].int,
            )
        }
    }
}

/** One compaction, as read from its `compact_boundary` record (`CompactionInfo`). */
data class Compaction(
    /** Run with `/compact`, or by itself on a full context window. */
    val manual: Boolean,
    val preTokens: Long,
    val postTokens: Long,
    val savedTokens: Long,
    val durationMs: Long? = null,
) {
    companion object {
        fun from(json: JsonElement): Compaction? {
            val saved = json["savedTokens"].long ?: return null
            return Compaction(
                manual = json["trigger"].string == "manual",
                preTokens = json["preTokens"].long ?: 0,
                postTokens = json["postTokens"].long ?: 0,
                savedTokens = saved,
                durationMs = json["durationMs"].long,
            )
        }
    }
}

/** One of Claude's tasks in a chat, rebuilt by the server from the whole transcript (`TaskItem`). */
data class TaskItem(val id: String, val subject: String, val status: Status) {
    enum class Status(val wire: String) { PENDING("pending"), IN_PROGRESS("in_progress"), COMPLETED("completed"), STOPPED("stopped") }

    /** Completed or stopped: nothing left to do on it. */
    val finished: Boolean get() = status == Status.COMPLETED || status == Status.STOPPED

    companion object {
        fun from(json: JsonElement): TaskItem? {
            val id = json["id"].string ?: return null
            return TaskItem(
                id = id,
                subject = json["subject"].string ?: "",
                status = Status.entries.firstOrNull { it.wire == json["status"].string } ?: Status.PENDING,
            )
        }
    }
}

data class HistoryPage(
    val messages: List<HistoryMessage>,
    /** Index of the first returned message in the whole transcript; 0 means nothing older. */
    val start: Int,
    val total: Int,
    /** Set when the transcript belongs to another id now: read and continue under that one. */
    val canonicalSessionId: String?,
    /** The user messages shown before [start], so that a window numbers its own as the whole transcript would. */
    val userOrdinalOffset: Int = 0,
    /** What a fork or edit of the first message continues from: the nearest earlier message shown, which is not in the page. */
    val predecessorId: String? = null,
    /** The edited versions in this session's tree, by user-message ordinal; null when the server sent none. */
    val versionMap: Map<Int, VersionGroup>? = null,
) {
    companion object {
        fun from(json: JsonElement): HistoryPage? {
            val list = json["messages"].array ?: return null
            val messages = list.mapNotNull(HistoryMessage::from)
            return HistoryPage(
                messages = messages,
                start = json["start"].int ?: 0,
                total = json["total"].int ?: messages.size,
                canonicalSessionId = json["canonicalSessionId"].string,
                userOrdinalOffset = json["userOrdinalOffset"].int ?: 0,
                predecessorId = json["predecessorId"].string,
                versionMap = json["versionMap"].obj?.let { map ->
                    buildMap { for ((key, value) in map) VersionGroup.from(value)?.let { group -> key.toIntOrNull()?.let { put(it, group) } } }
                },
            )
        }
    }
}

/** The versions of one user message (`VersionGroup`): the sessions that hold one each, the original first, and which is shown. */
data class VersionGroup(val ids: List<String>, val currentIndex: Int) {
    companion object {
        fun from(json: JsonElement): VersionGroup? {
            val ids = json["ids"].array?.mapNotNull { it.string } ?: return null
            return VersionGroup(ids, json["currentIndex"].int ?: 0)
        }
    }
}

object WireDate {
    /** ISO 8601, with or without fractional seconds or an offset, or milliseconds since the epoch. */
    fun parse(value: JsonElement?): Instant? {
        value.double?.let { return Instant.ofEpochMilli(it.roundToLong()) }
        val text = value.string ?: return null
        return try {
            Instant.parse(text)
        } catch (_: DateTimeParseException) {
            try {
                OffsetDateTime.parse(text).toInstant()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}

/** Pinned first, then the most recently active (`sortSessions` on the web). */
fun List<SessionInfo>.sortedForList(): List<SessionInfo> = sortedWith(
    compareByDescending<SessionInfo> { it.pinned }.thenByDescending { it.lastActive ?: Instant.MIN },
)

/**
 * A model a provider offers (`GET …/chat/providers/:id/models`). [isDefault] marks the model the
 * provider itself picks when the user has not chosen one ("Auto").
 */
data class ModelOption(val value: String, val label: String, val isDefault: Boolean = false)
