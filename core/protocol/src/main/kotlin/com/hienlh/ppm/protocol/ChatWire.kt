package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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

data class SessionStateFrame(
    val sessionId: String?,
    val phase: SessionPhase,
    val title: String?,
    val pendingApproval: Reported<ApprovalRequest>,
    val turnStop: Reported<TurnStop>,
    val compacting: Boolean,
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
    data class PhaseChanged(val phase: SessionPhase) : ChatFrame
    data class Ping(val streamSeq: Int?) : ChatFrame
    data class TurnStopped(val stop: TurnStop?) : ChatFrame
    data class MessageRejected(val clientMessageId: String?, val message: String) : ChatFrame
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
                "phase_changed" -> PhaseChanged(SessionPhase.fromWire(json["phase"].string))
                "ping" -> Ping(seq)
                "turn_stop" -> TurnStopped(TurnStop.from(json["stop"]))
                "message_rejected" -> MessageRejected(
                    clientMessageId = json["clientMessageId"].string,
                    message = json["message"].string ?: "Message could not be sent",
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

    data class Message(val clientMessageId: String, val content: String) : ChatClientMessage {
        override val json
            get() = buildJsonObject {
                put("type", "message")
                put("clientMessageId", clientMessageId)
                put("content", content)
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
