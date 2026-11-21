package com.hienlh.ppm.state

import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.long
import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Comments a person leaves on single lines — of a chat's changes in the Review tab, or of a plan
 * waiting for approval — and the one message they become (`line-comments.ts`). The AI reads each
 * comment beside the line it is about, quoted, so it never has to guess which line "this" meant.
 */
data class ReviewLineComment(
    val id: String,
    val path: String,
    /** The line's number in the file now, or in the base for a removed line. */
    val line: Int,
    /** `old` for a removed line: its number is the base's. */
    val side: Side,
    /** The line as it reads, so the comment still makes sense after the file moves on. */
    val quote: String,
    val body: String,
) {
    enum class Side(val wire: String) { NEW("new"), OLD("old") }

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("path", path)
        put("line", line)
        put("side", side.wire)
        put("quote", quote)
        put("body", body)
    }

    companion object {
        fun from(json: JsonElement?): ReviewLineComment? {
            val id = json["id"].string ?: return null
            val path = json["path"].string ?: return null
            val line = json["line"].long?.toInt() ?: return null
            val side = if (json["side"].string == "old") Side.OLD else Side.NEW
            return ReviewLineComment(id, path, line, side, json["quote"].string.orEmpty(), json["body"].string.orEmpty())
        }
    }
}

object LineComments {
    /** Most characters of a line quoted beside its comment. */
    private const val MAX_QUOTE = 300

    /** The id of the comment on one line (`reviewCommentId`): one comment per line and side. */
    fun reviewCommentId(path: String, side: ReviewLineComment.Side, line: Int): String = path + "\n" + side.wire + "\n" + line

    private fun quoteOf(text: String): String {
        val t = text.trim()
        return if (t.length > MAX_QUOTE) t.substring(0, MAX_QUOTE - 1) + "…" else t
    }

    /** Text that cannot close the code span it is put in. */
    private fun inlineCode(text: String): String {
        val longest = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(longest + 1)
        val pad = if (text.startsWith("`") || text.endsWith("`")) " " else ""
        return fence + pad + text + pad + fence
    }

    /** Every comment with a body, as one message (`formatReviewComments`): grouped by file, in line order. */
    fun formatReviewComments(comments: List<ReviewLineComment>): String {
        val live = comments.filter { it.body.isNotBlank() }
        if (live.isEmpty()) return ""
        val byFile = LinkedHashMap<String, MutableList<ReviewLineComment>>()
        for (c in live) byFile.getOrPut(c.path) { mutableListOf() } += c
        val parts = mutableListOf("Review comments on the changes in this chat — please address each one:")
        for ((path, list) in byFile) {
            parts += "**$path**"
            for (c in list.sortedBy { it.line }) {
                val where = if (c.side == ReviewLineComment.Side.OLD) "removed line ${c.line}" else "line ${c.line}"
                val quote = quoteOf(c.quote)
                parts += "- " + where + (if (quote.isNotEmpty()) " " + inlineCode(quote) else "") + "\n  " + c.body.trim().replace("\n", "\n  ")
            }
        }
        return parts.joinToString("\n\n")
    }

    /**
     * What the AI is told when a plan is sent back with comments (`formatPlanComments`): each
     * commented line quoted with its number, the comment under it, then whatever the person added
     * overall. [comments] is keyed by the line's 0-based index.
     */
    fun formatPlanComments(planLines: List<String>, comments: Map<Int, String>, overall: String = ""): String {
        val entries = comments.entries
            .map { (i, body) -> i to body.trim() }
            .filter { (i, body) -> body.isNotEmpty() && i >= 0 && i < planLines.size }
            .sortedBy { it.first }
        val parts = mutableListOf<String>()
        if (entries.isNotEmpty()) {
            parts += "Comments on the plan — revise it to address each one, then present it again:"
            for ((i, body) in entries) parts += "> " + (i + 1) + ": " + quoteOf(planLines[i]) + "\n\n" + body
        }
        val rest = overall.trim()
        if (rest.isNotEmpty()) parts += if (entries.isNotEmpty()) "Also: $rest" else rest
        return parts.joinToString("\n\n")
    }

    /** The plan an `ExitPlanMode` call carries (`planOf`), or null when the input is not that shape. */
    fun planOf(tool: String, input: JsonElement?): String? {
        if (tool != "ExitPlanMode" || input !is JsonObject) return null
        val plan = input["plan"].string ?: return null
        return plan.takeIf { it.isNotBlank() }
    }

    /** The plan's lines as the card shows them, one per row. */
    fun planLines(plan: String): List<String> = plan.replace("\r\n", "\n").split("\n")
}

/**
 * Line comments left in a chat's Review tab, per session, kept on this device until they are sent
 * to the chat or cleared (`review-comments-store.ts`), so leaving the tab or the app loses none.
 * The session most recently commented on is kept last, and only the newest [MAX_SESSIONS] are kept.
 */
class ReviewComments(private val store: KeyValueStore? = null) {
    private val all = MutableStateFlow(read())
    val state: StateFlow<Map<String, List<ReviewLineComment>>> = all.asStateFlow()

    fun of(sessionId: String): List<ReviewLineComment> = all.value[sessionId].orEmpty()

    /** Adds a comment, or replaces the one with the same id; an empty body removes it. */
    fun save(sessionId: String, comment: ReviewLineComment) {
        val list = of(sessionId).filter { it.id != comment.id }
        put(sessionId, if (comment.body.isNotBlank()) list + comment else list)
    }

    fun remove(sessionId: String, id: String) = put(sessionId, of(sessionId).filter { it.id != id })

    fun clear(sessionId: String) = put(sessionId, emptyList())

    private fun put(sessionId: String, list: List<ReviewLineComment>) {
        val next = LinkedHashMap(all.value)
        // Re-inserted, so the session most recently commented on is the last one trimmed.
        next.remove(sessionId)
        if (list.isNotEmpty()) next[sessionId] = list
        val kept = if (next.size > MAX_SESSIONS) LinkedHashMap(next.entries.toList().takeLast(MAX_SESSIONS).associate { it.key to it.value }) else next
        write(kept)
        all.value = kept
    }

    private fun read(): Map<String, List<ReviewLineComment>> {
        val json = store?.get(KEY)?.let(WireJson::parseOrNull) as? JsonObject ?: return emptyMap()
        val out = LinkedHashMap<String, List<ReviewLineComment>>()
        for ((session, list) in json) {
            val comments = (list as? JsonArray)?.mapNotNull(ReviewLineComment::from).orEmpty()
            if (comments.isNotEmpty()) out[session] = comments
        }
        return out
    }

    private fun write(value: Map<String, List<ReviewLineComment>>) {
        val store = store ?: return
        store.put(KEY, JsonObject(value.mapValues { (_, list) -> JsonArray(list.map { it.toJson() }) }).toString())
    }

    companion object {
        const val KEY = "ppm.reviewComments"
        const val MAX_SESSIONS = 50
    }
}

/** What a tool approval card sends back besides yes or no (`ApprovalCardChoice`). */
object ApprovalChoice {
    /** "Allow … for this chat" / "Always in this project". */
    fun scope(scope: String): JsonObject = buildJsonObject { put("scope", scope) }

    /** A denial's instructions, which reach Claude as the reason; none when nothing was written. */
    fun message(text: String): JsonElement? = text.trim().takeIf { it.isNotEmpty() }?.let { m -> buildJsonObject { put("message", m) } }
}
