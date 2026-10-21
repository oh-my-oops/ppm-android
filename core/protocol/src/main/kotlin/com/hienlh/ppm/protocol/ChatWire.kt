package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The server owns the phase; [IDLE] is the only one in which no turn runs. */
enum class SessionPhase(val wire: String) {
    INITIALIZING("initializing"),
    CONNECTING("connecting"),
    THINKING("thinking"),
    STREAMING("streaming"),
    IDLE("idle");

    val isRunning: Boolean get() = this != IDLE

    companion object {
        /** A phase this app does not know yet still means a turn is running. */
        fun fromWire(wire: String?): SessionPhase = entries.firstOrNull { it.wire == wire } ?: THINKING
    }
}

/**
 * How the last turn ended, when an error ended it. No transcript records it, so it is kept
 * apart from the messages and survives a history reload.
 */
data class TurnStop(val message: String, val subtype: String? = null) {
    companion object {
        fun from(json: JsonElement?): TurnStop? {
            val message = json["message"].string ?: return null
            return TurnStop(message, json["subtype"].string)
        }
    }
}

/** A field some frames carry and others leave out: leaving it out must not clear it. */
sealed interface Reported<out T> {
    data object NotReported : Reported<Nothing>
    data class Value<T>(val value: T?) : Reported<T>
}

/**
 * What the server knows about a session's prompt cache (`PromptCacheState`). Times are the
 * server's epoch milliseconds.
 */
data class PromptCacheState(
    /** An hour on a subscription, five minutes on an API key. */
    val ttlMs: Double,
    /** When the last turn completed: when the cache was last written. */
    val lastTurnEndedAt: Double? = null,
    /** A running session total, only a floor for "has this cached anything"; never shown. */
    val billedPrefixTokens: Double? = null,
    /** What the last turn held; the figure that may be shown. */
    val contextTokens: Double? = null,
    /** Set while a compaction nothing has followed yet makes the cache useless. */
    val compactedAt: Double? = null,
) {
    companion object {
        fun from(json: JsonElement?): PromptCacheState? {
            val ttl = json["ttlMs"].double ?: return null
            return PromptCacheState(
                ttlMs = ttl,
                lastTurnEndedAt = json["lastTurnEndedAt"].double,
                billedPrefixTokens = json["billedPrefixTokens"].double,
                contextTokens = json["contextTokens"].double,
                compactedAt = json["compactedAt"].double,
            )
        }
    }
}

data class SessionStateFrame(
    val sessionId: String? = null,
    val phase: SessionPhase = SessionPhase.IDLE,
    val title: String? = null,
    val pendingApproval: Reported<ApprovalRequest> = Reported.NotReported,
    val turnStop: Reported<TurnStop> = Reported.NotReported,
    val compacting: Boolean = false,
    /** The session's model and effort, its own or else its provider's default. */
    val model: String? = null,
    val effort: String? = null,
    /** Whether thinking is on for it. */
    val thinking: Boolean? = null,
    /**
     * Left out of the answers to a model, effort or thinking change, which the web reads as
     * "nothing known" — and a cache written for another model is no use anyway.
     */
    val promptCache: PromptCacheState? = null,
)

/** `turn_events`: every buffered event of the running turn — a full snapshot, not a delta. */
data class TurnSnapshot(
    val events: List<ChatEvent>,
    val userMessage: String?,
    val streamSeq: Int?,
    /** The buffer is bounded; frames past it come back only with the idle history reload. */
    val truncated: Boolean,
)

/** A frame from `/ws/project/:p/chat/:id`. */
sealed interface ChatFrame {
    data class Event(val event: ChatEvent, val streamSeq: Int?) : ChatFrame
    data class SessionState(val state: SessionStateFrame) : ChatFrame
    data class TurnEvents(val snapshot: TurnSnapshot) : ChatFrame
    /** [elapsed]: seconds the server has waited for a first answer, sent every 5 s while `connecting`. */
    data class PhaseChanged(val phase: SessionPhase, val elapsed: Int? = null) : ChatFrame
    data class Ping(val streamSeq: Int?) : ChatFrame
    data class TurnStopped(val stop: TurnStop?) : ChatFrame
    /** The server refused a message; [content] and [replyTo] are the message as it arrived. */
    data class MessageRejected(
        val clientMessageId: String?,
        val message: String,
        val content: String? = null,
        val replyTo: ReplyReference? = null,
    ) : ChatFrame
    data class UserMessage(val content: String) : ChatFrame
    data class TitleUpdated(val title: String) : ChatFrame

    /** [answers] when a question was answered, from this device or another. */
    data class ApprovalResolved(val requestId: String?, val answers: JsonElement?) : ChatFrame
    data class CompactStatus(val compacting: Boolean) : ChatFrame

    /** The AI asked this device to open a tab (`open_file`, `open_preview`); it waits for an answer. */
    data class TabOpen(val requestId: String) : ChatFrame

    companion object {
        fun from(json: JsonElement): ChatFrame {
            val seq = json["streamSeq"].int
            return when (json["type"].string ?: "") {
                "session_state" -> SessionState(
                    SessionStateFrame(
                        sessionId = json["sessionId"].string,
                        phase = SessionPhase.fromWire(json["phase"].string),
                        title = json["sessionTitle"].string,
                        pendingApproval = json["pendingApproval"]
                            ?.let { Reported.Value(ApprovalRequest.from(it)) } ?: Reported.NotReported,
                        turnStop = json["turnStop"]?.let { Reported.Value(TurnStop.from(it)) } ?: Reported.NotReported,
                        compacting = json["compactStatus"].string == "compacting",
                        model = json["model"].string?.ifEmpty { null },
                        effort = json["effort"].string?.ifEmpty { null },
                        thinking = json["thinking"].bool,
                        promptCache = PromptCacheState.from(json["promptCache"]),
                    ),
                )
                "turn_events" -> TurnEvents(
                    TurnSnapshot(
                        events = json["events"].array.orEmpty().map(ChatEvent::from),
                        userMessage = json["userMessage"].string,
                        streamSeq = seq,
                        truncated = json["truncated"].bool ?: false,
                    ),
                )
                "phase_changed" -> PhaseChanged(SessionPhase.fromWire(json["phase"].string), json["elapsed"].int)
                "ping" -> Ping(seq)
                "turn_stop" -> TurnStopped(TurnStop.from(json["stop"]))
                "message_rejected" -> MessageRejected(
                    clientMessageId = json["clientMessageId"].string,
                    message = json["message"].string ?: "Message could not be sent",
                    content = json["content"].string,
                    replyTo = ReplyReference.from(json["replyTo"]),
                )
                "user_message" -> UserMessage(json["content"].string ?: "")
                "title_updated" -> TitleUpdated(json["title"].string ?: "")
                "approval_resolved" -> {
                    val answers = if (json["approved"].bool == true) json["answers"] else null
                    ApprovalResolved(json["requestId"].string, answers?.takeIf { it !is JsonNull })
                }
                "compact_status" -> CompactStatus(json["status"].string == "compacting")
                "tab_open" -> json["requestId"].string?.let(::TabOpen) ?: Event(ChatEvent.from(json), seq)
                else -> Event(ChatEvent.from(json), seq)
            }
        }
    }
}

/**
 * The choices a message carries (`TurnSettings` plus the composer's mode and priority); a value
 * left null is left out of the frame.
 */
data class TurnOptions(
    /** `default`, `acceptEdits`, `plan` or `bypassPermissions`; without one the server uses the session's last, then its config. */
    val permissionMode: String? = null,
    /** `now`, `next` or `later`, for a message sent while a turn runs. */
    val priority: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val thinking: Boolean? = null,
)

/** A frame this app sends on the chat socket. */
sealed interface ChatClientMessage {
    val json: JsonObject

    data object Ready : ChatClientMessage {
        override val json get() = buildJsonObject { put("type", "ready") }
    }

    data object Resync : ChatClientMessage {
        override val json get() = buildJsonObject { put("type", "resync") }
    }

    data object Cancel : ChatClientMessage {
        override val json get() = buildJsonObject { put("type", "cancel") }
    }

    /**
     * [replyTo] travels beside the text, which is sent without it; the server checks it belongs to
     * this chat. [images] ride in the frame itself; [imagePaths] name the uploaded copies for a
     * provider that takes a file rather than a payload (codex).
     */
    data class Message(
        val clientMessageId: String,
        val content: String,
        val options: TurnOptions = TurnOptions(),
        val replyTo: ReplyReference? = null,
        val images: List<InlineImage> = emptyList(),
        val imagePaths: List<String> = emptyList(),
    ) : ChatClientMessage {
        override val json
            get() = buildJsonObject {
                put("type", "message")
                put("clientMessageId", clientMessageId)
                put("content", content)
                options.permissionMode?.let { put("permissionMode", it) }
                options.priority?.let { put("priority", it) }
                if (images.isNotEmpty()) {
                    put(
                        "images",
                        buildJsonArray {
                            for (image in images) {
                                add(buildJsonObject {
                                    put("data", image.data)
                                    put("mediaType", image.mediaType)
                                })
                            }
                        },
                    )
                }
                if (imagePaths.isNotEmpty()) put("imagePaths", buildJsonArray { imagePaths.forEach { add(it) } })
                replyTo?.let { put("replyTo", it.toJson()) }
                options.model?.let { put("model", it) }
                options.effort?.let { put("effort", it) }
                options.thinking?.let { put("thinking", it) }
            }
    }

    /** Stored for the session; the server answers with a `session_state`. */
    data class SetModel(val model: String) : ChatClientMessage {
        override val json
            get() = buildJsonObject {
                put("type", "set_model")
                put("model", model)
            }
    }

    data class SetEffort(val effort: String) : ChatClientMessage {
        override val json
            get() = buildJsonObject {
                put("type", "set_effort")
                put("effort", effort)
            }
    }

    data class SetThinking(val enabled: Boolean) : ChatClientMessage {
        override val json
            get() = buildJsonObject {
                put("type", "set_thinking")
                put("enabled", enabled)
            }
    }

    data class ApprovalResponse(val requestId: String, val approved: Boolean, val data: JsonElement?) :
        ChatClientMessage {
        override val json
            get() = buildJsonObject {
                put("type", "approval_response")
                put("requestId", requestId)
                put("approved", approved)
                if (data != null) put("data", data)
            }
    }

    data class TabOpenResult(val requestId: String, val opened: Boolean, val error: String?) : ChatClientMessage {
        override val json
            get() = buildJsonObject {
                put("type", "tab_open_result")
                put("requestId", requestId)
                put("opened", opened)
                if (error != null) put("error", error)
            }
    }
}
