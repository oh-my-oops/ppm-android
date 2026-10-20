package com.hienlh.ppm.chat

import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A payload a teammate sends instead of prose: a shutdown, a plan approval. */
internal data class SendMessageProtocol(
    val type: String,
    val requestId: String? = null,
    val approve: Boolean? = null,
    val reason: String? = null,
    val feedback: String? = null,
)

/**
 * A `SendMessage` call as the card reads it (`normalizeSendMessage` in send-message-parse.ts):
 * the canonical `{ to, message, summary?, notify_when_idle? }` and the older
 * `{ type, recipient, content }` it may arrive alongside.
 */
internal data class NormalizedSendMessage(
    val to: String,
    /** The author's own label for the row; the recipient never sees it. */
    val summary: String?,
    /** The prose; empty for a protocol payload. */
    val text: String,
    /** What the recipient previews: the first line with anything on it. */
    val firstLine: String,
    val protocol: SendMessageProtocol?,
    /** `message` for prose, else what the badge names. */
    val kind: String,
    val notifyWhenIdle: Boolean,
    /** Keys the card does not know, so a new field shows instead of vanishing. */
    val extras: List<Pair<String, String>>,
) {
    companion object {
        private val KNOWN_KEYS = setOf("to", "message", "summary", "notify_when_idle", "recipient", "content", "type")

        fun of(input: JsonElement): NormalizedSendMessage {
            val to = jsString(input["to"]).ifEmpty { jsString(input["recipient"]) }
            // `message ?? content`: the legacy copy only fills in when the canonical one is absent.
            val rawBody = input["message"]?.takeUnless { it is JsonNull } ?: input["content"]
            val protocol = asProtocol(rawBody) ?: protocolFromJsonText(jsString(rawBody))
            val text = if (protocol != null) "" else jsString(rawBody)
            val legacyType = jsString(input["type"])
            val kind = protocol?.type ?: if (legacyType.isNotEmpty() && legacyType != "message") legacyType else "message"
            val extras = (input as? JsonObject).orEmpty()
                .filterKeys { it !in KNOWN_KEYS }
                .map { (key, value) -> key to ((value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: value.toString()) }
            return NormalizedSendMessage(
                to = to,
                summary = jsString(input["summary"]).trim().ifEmpty { null },
                text = text,
                firstLine = text.split("\n").map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "",
                protocol = protocol,
                kind = kind,
                notifyWhenIdle = input["notify_when_idle"].bool == true,
                extras = extras,
            )
        }

        private fun asProtocol(value: JsonElement?): SendMessageProtocol? {
            val fields = value as? JsonObject ?: return null
            val type = fields["type"].string?.takeIf { it.isNotEmpty() } ?: return null
            return SendMessageProtocol(type, fields["request_id"].string, fields["approve"].bool, fields["reason"].string, fields["feedback"].string)
        }

        private fun protocolFromJsonText(text: String): SendMessageProtocol? {
            val trimmed = text.trim()
            if (!trimmed.startsWith("{")) return null
            return asProtocol(WireJson.parseOrNull(trimmed))
        }
    }
}

/** What the tool answered happened to the message (`parseSendMessageResult`). */
internal data class SendMessageOutcome(val ok: Boolean, val detail: String?, val peer: String?, val ref: String?) {
    companion object {
        /** Null for anything but the runtime's status object, maybe inside a text block, so the raw output shows instead. */
        fun parse(output: String): SendMessageOutcome? {
            if (output.isEmpty()) return null
            var payload = WireJson.parseOrNull(output) ?: return null
            if (payload is JsonArray) {
                val inner = jsString(payload.firstOrNull { it is JsonObject && it["type"].string == "text" }?.get("text"))
                if (inner.isEmpty()) return null
                payload = WireJson.parseOrNull(inner) ?: return null
            }
            val fields = payload as? JsonObject ?: return null
            val ok = fields["success"].bool ?: return null
            val pin = fields["pin"] as? JsonObject
            return SendMessageOutcome(
                ok = ok,
                detail = jsString(fields["message"]).ifEmpty { jsString(fields["error"]) }.ifEmpty { null },
                peer = pin?.let { jsString(it["name"]).ifEmpty { null } },
                ref = pin?.let { jsString(it["ref"]).ifEmpty { null } },
            )
        }
    }
}

/** The badge a message kind wears (`TYPE_BADGES` in team-message-badges.ts); prose wears none. */
internal enum class TeamBadgeTone { PRIMARY, WARNING, SUCCESS, ERROR, MUTED }

internal val TEAM_BADGES: Map<String, Pair<String, TeamBadgeTone>> = mapOf(
    "task_assignment" to ("task" to TeamBadgeTone.PRIMARY),
    "idle_notification" to ("idle" to TeamBadgeTone.WARNING),
    "completion" to ("done" to TeamBadgeTone.SUCCESS),
    "shutdown_request" to ("shutdown" to TeamBadgeTone.ERROR),
    "shutdown_approved" to ("shutdown ✓" to TeamBadgeTone.MUTED),
    "shutdown_response" to ("shutdown reply" to TeamBadgeTone.MUTED),
    "plan_approval_request" to ("plan review" to TeamBadgeTone.PRIMARY),
    "plan_approval_response" to ("plan reply" to TeamBadgeTone.PRIMARY),
)

/** JavaScript's `String(v ?? "")` for a JSON value. */
internal fun jsString(value: JsonElement?): String = when (value) {
    null, JsonNull -> ""
    is JsonPrimitive -> if (value.isString) value.content else jsNumber(value) ?: value.content
    is JsonArray -> value.joinToString(",") { if (it is JsonNull) "" else jsString(it) }
    is JsonObject -> "[object Object]"
}

private fun jsNumber(value: JsonPrimitive): String? = value.content.toDoubleOrNull()?.let(::jsNumberText)

/** A number as JavaScript prints it: no ".0" on a whole one. */
internal fun jsNumberText(number: Double): String =
    if (number == Math.floor(number) && Math.abs(number) < 9e15) number.toLong().toString() else number.toString()
