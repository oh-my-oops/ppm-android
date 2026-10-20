package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What a tool answered. [exitCode]: a shell tool's, as Codex reports it. */
data class ToolOutcome(val output: String, val isError: Boolean, val exitCode: Int? = null) {
    companion object {
        fun from(json: JsonElement): ToolOutcome =
            ToolOutcome(output = text(json["output"]), isError = json["isError"].bool ?: false, exitCode = json["exitCode"].int)

        /** The output is a string today; a list of content blocks is read as their text. */
        fun text(value: JsonElement?): String = when {
            value == null || value is JsonNull -> ""
            value is JsonPrimitive && value.isString -> value.content
            value is JsonArray -> value.mapNotNull { it["text"].string ?: it.string }.joinToString("\n")
            else -> value.toString()
        }
    }
}

/**
 * A tool waiting for the user (`approval_request`, or `session_state.pendingApproval`). [approved]
 * is stamped by the server on a request it replays after it was answered.
 */
data class ApprovalRequest(val requestId: String, val tool: String, val input: JsonElement, val approved: Boolean? = null) {
    /** The questions of an `AskUserQuestion` request. */
    val questions: List<Question>
        get() = input["questions"].array.orEmpty().mapNotNull(Question::from)

    companion object {
        fun from(json: JsonElement): ApprovalRequest? {
            val requestId = json["requestId"].string ?: return null
            return ApprovalRequest(requestId, json["tool"].string ?: "unknown", json["input"] ?: JsonNull, json["approved"].bool)
        }
    }
}

data class Question(
    val question: String,
    val header: String?,
    val options: List<Option>,
    val multiSelect: Boolean,
) {
    data class Option(val label: String, val description: String?)

    companion object {
        fun from(json: JsonElement): Question? {
            val question = json["question"].string ?: return null
            return Question(
                question = question,
                header = json["header"].string,
                options = json["options"].array.orEmpty().mapNotNull { option ->
                    option["label"].string?.let { Option(it, option["description"].string) }
                },
                multiSelect = json["multiSelect"].bool ?: false,
            )
        }
    }
}

/**
 * One event of a turn, as both the chat socket streams it and the history route returns it
 * inside an assistant message.
 */
sealed interface ChatEvent {
    data class Text(val text: String, val parent: String? = null) : ChatEvent
    data class Thinking(val text: String, val parent: String? = null) : ChatEvent

    data class ToolUse(
        val name: String,
        val input: JsonElement,
        val toolUseId: String?,
        /** Set when a subagent made the call: the id of the tool call that started it. */
        val parent: String? = null,
        /** The server copies a result onto its call in the replay buffer. */
        val result: ToolOutcome? = null,
        /** A backgrounded Agent/Task's end in history: `completed`, `failed` or `stopped`. */
        val bgStatus: String? = null,
        /** An Agent/Task's own events in history, the subagent's calls among them. */
        val children: List<ChatEvent>? = null,
    ) : ChatEvent

    data class ToolResult(val outcome: ToolOutcome, val toolUseId: String?, val parent: String? = null) : ChatEvent
    data class Approval(val request: ApprovalRequest) : ChatEvent
    data class Error(val message: String) : ChatEvent
    /**
     * The turn ended; [lastMessageUuid] is the SDK's id for its last message, [usage] its token
     * split. A subagent's own end names the call that started it in [parent].
     */
    data class Done(val lastMessageUuid: String? = null, val usage: TurnUsage? = null, val parent: String? = null) : ChatEvent

    /** The provider moved the turn to another account: a rate limit, a usage cap or a failed sign-in. */
    data class AccountRetry(val reason: String?, val accountLabel: String?) : ChatEvent

    /** What PPM does before the provider answers: picking an account, refreshing its token, retrying. */
    data class StatusUpdate(val message: String, val accountLabel: String?) : ChatEvent

    /** The account the turn runs on. */
    data object AccountInfo : ChatEvent

    /** A backgrounded Agent/Task ended ([status]: `completed`, `failed` or `stopped`), maybe turns after its call. */
    data class SubagentStatus(val toolUseId: String, val status: String) : ChatEvent
    data class SessionMigrated(val newSessionId: String) : ChatEvent

    /** Anything else (`team_*`, …): not shown yet. */
    data class Other(val type: String) : ChatEvent

    companion object {
        fun from(json: JsonElement): ChatEvent {
            val parent = json["parentToolUseId"].string
            return when (val type = json["type"].string ?: "") {
                "text" -> Text(json["content"].string ?: "", parent)
                "thinking" -> Thinking(json["content"].string ?: "", parent)
                "tool_use" -> ToolUse(
                    name = json["tool"].string ?: "unknown",
                    input = json["input"] ?: JsonObject(emptyMap()),
                    toolUseId = json["toolUseId"].string,
                    parent = parent,
                    result = json["result"]?.takeIf { it is JsonObject }?.let(ToolOutcome::from),
                    bgStatus = json["bgStatus"].string,
                    children = json["children"].array?.map(::from),
                )
                "tool_result" -> ToolResult(ToolOutcome.from(json), json["toolUseId"].string, parent)
                "approval_request" -> ApprovalRequest.from(json)?.let(::Approval) ?: Other(type)
                "error" -> Error(json["message"].string ?: "Unknown error")
                "done" -> Done(json["lastMessageUuid"].string, TurnUsage.from(json["usage"]), parent)
                "account_retry" -> AccountRetry(json["reason"].string, json["accountLabel"].string)
                "status_update" -> StatusUpdate(json["message"].string ?: "", json["accountLabel"].string)
                "account_info" -> AccountInfo
                "subagent_status" -> {
                    val id = json["toolUseId"].string
                    val status = json["status"].string
                    if (id.isNullOrEmpty() || status.isNullOrEmpty()) Other(type) else SubagentStatus(id, status)
                }
                "session_migrated" -> json["newSessionId"].string?.let(::SessionMigrated) ?: Other(type)
                else -> Other(type)
            }
        }
    }
}
