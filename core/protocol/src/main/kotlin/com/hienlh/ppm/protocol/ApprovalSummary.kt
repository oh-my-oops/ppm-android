package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * What an approval card says will happen (`ApprovalSummary` in `shared/assistant-approval.ts`),
 * built by the server from input it already checked, never from words the AI chose: the PPM
 * Assistant's endpoint cards carry one beside the raw input, and a session tool's schedule card
 * carries one as its whole body.
 */
data class ApprovalSummary(
    /** One line saying what will happen. */
    val headline: String,
    val facts: List<Fact>,
    /** The exact text that will run or be sent, shown in full and wrapped. */
    val body: Body? = null,
    /** How many SQL statements [body] holds. */
    val statementCount: Int? = null,
    /** A line shown highlighted above the buttons. */
    val warning: String? = null,
    /**
     * Set when the card may be allowed only in PPM: away from PPM (Telegram, a mirror, the
     * Assistant answering for the user) the server takes its Deny only. Shown on the server's
     * mirrors as their reason; PPM's own chat, which this app is, offers Allow.
     */
    val reviewInPpm: String? = null,
) {
    /** One labelled fact; [warning] is drawn highlighted (`tone: "warning"`). A label can repeat. */
    data class Fact(val label: String, val value: String, val warning: Boolean = false)

    /** [format] is `sql`, `text` or `code`: only `text` is set in the sans face. */
    data class Body(val label: String, val text: String, val format: String)

    companion object {
        /** A summary on the wire, or null when it has no headline (`sessionCollabApprovalInput`'s check). */
        fun from(json: JsonElement?): ApprovalSummary? {
            if (json !is JsonObject) return null
            val headline = json["headline"].string ?: return null
            val facts = json["facts"].array.orEmpty().mapNotNull { f ->
                val label = f["label"].string ?: return@mapNotNull null
                Fact(label, f["value"].string.orEmpty(), f["tone"].string == "warning")
            }
            val body = json["body"]?.takeIf { it is JsonObject }?.let { b ->
                Body(b["label"].string.orEmpty(), b["text"].string.orEmpty(), b["format"].string ?: "text")
            }
            return ApprovalSummary(
                headline = headline,
                facts = facts,
                body = body,
                statementCount = json["statementCount"].int,
                warning = json["warning"].string?.takeIf { it.isNotEmpty() },
                reviewInPpm = json["reviewInPpm"].string?.takeIf { it.isNotEmpty() },
            )
        }
    }
}
