package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** A PPM task a user message is about (`TaskReference`, `shared/chat-task-ref.ts`), drawn as a chip in its bubble. */
data class TaskReference(
    val taskId: String,
    /** `ppm/12`. */
    val ref: String,
    val number: Int,
    val projectName: String,
    val title: String,
    val status: String,
    /** The effective urgency. */
    val urgency: String,
    val due: String?,
    val link: String,
)

/**
 * The tasks block after a user message's text (`decodeTaskRefs`). Only the server writes one; the
 * decoder rebuilds the whole block from its base64 line and keeps it as text unless the two match
 * byte for byte, so the readable lines cannot say anything the chip does not.
 */
object ChatTaskRefs {
    data class Decoded(val content: String, val taskRefs: List<TaskReference>?)

    const val MAX = 5
    private const val BLOCK_START = "\n\n<ppm-taskref-v1>\n" +
        "The user's message above is about the PPM task below; PPM attached it (the user opened this chat from the task, " +
        "or picked the task on Telegram). Read \"this task\", \"it\" and the like in the message as this task unless the " +
        "user says otherwise. For more than these fields, call ppm_task_get with the task id.\n"
    private const val BLOCK_END = "\n</ppm-taskref-v1>"
    private const val DISPLAY_PREFIX = "Display data for the user's chip (base64; the same facts as above, nothing more to read here): "
    private val URGENCIES = listOf("now", "today", "week", "later")
    private val URGENCY_TEXT = mapOf("now" to "Now", "today" to "Today", "week" to "This week", "later" to "Later")
    private val TASK_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)
    private val YMD = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val LINE_BREAKS = Regex("[\\u0000-\\u001f\\u007f\\u2028\\u2029]")
    private val LINK = Regex("^https?://[^\\s<>\"]+$")
    private val BASE64 = Regex("^[A-Za-z0-9+/]*={0,2}$")
    private val KEYS = setOf("version", "taskId", "ref", "number", "projectName", "title", "status", "urgency", "due", "link")

    private fun line(v: JsonElement?, max: Int): String? {
        val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (s.isBlank() || s.codePointCount(0, s.length) > max || LINE_BREAKS.containsMatchIn(s)) return null
        return s
    }

    private fun validate(v: JsonElement): TaskReference? {
        val o = v as? JsonObject ?: return null
        if (o.keys.any { it !in KEYS }) return null
        if (o["version"].double != 1.0) return null
        val id = o["taskId"].string?.takeIf { TASK_ID.matches(it) } ?: return null
        val ref = line(o["ref"], 212) ?: return null
        val project = line(o["projectName"], 200) ?: return null
        val title = line(o["title"], 300) ?: return null
        val status = line(o["status"], 80) ?: return null
        val number = o["number"].long?.takeIf { it in 1..10_000_000 }?.toInt() ?: return null
        val urgency = o["urgency"].string?.takeIf { it in URGENCIES } ?: return null
        val dueRaw = o["due"]
        val due = if (dueRaw == null || dueRaw is JsonNull) null else dueRaw.string?.takeIf { YMD.matches(it) } ?: return null
        if (dueRaw == null) return null
        val link = line(o["link"], 600)?.takeIf { LINK.matches(it) } ?: return null
        return TaskReference(id.lowercase(), ref, number, project, title, status, urgency, due, link)
    }

    /** JSON with `<`, `>`, `&`, U+2028 and U+2029 escaped (`quoted`). */
    private fun quoted(text: String): String {
        val raw = StringBuilder().apply { appendJsString(text) }.toString()
        val out = StringBuilder(raw.length)
        for (c in raw) {
            if (c == '<' || c == '>' || c == '&' || c == ' ' || c == ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
        }
        return out.toString()
    }

    private fun json(refs: List<TaskReference>): String = buildString {
        append('[')
        refs.forEachIndexed { i, r ->
            if (i > 0) append(',')
            append("{\"version\":1,\"taskId\":"); appendJsString(r.taskId)
            append(",\"ref\":"); appendJsString(r.ref)
            append(",\"number\":").append(r.number)
            append(",\"projectName\":"); appendJsString(r.projectName)
            append(",\"title\":"); appendJsString(r.title)
            append(",\"status\":"); appendJsString(r.status)
            append(",\"urgency\":"); appendJsString(r.urgency)
            append(",\"due\":"); if (r.due == null) append("null") else appendJsString(r.due)
            append(",\"link\":"); appendJsString(r.link)
            append('}')
        }
        append(']')
    }

    private fun lines(r: TaskReference): String = listOf(
        "Task ${quoted(r.ref)} (task_id ${r.taskId}): ${quoted(r.title)}",
        "Project ${quoted(r.projectName)} · status ${quoted(r.status)} · urgency ${URGENCY_TEXT[r.urgency]} · due ${r.due ?: "none"}",
        "Open it: ${r.link}",
    ).joinToString("\n")

    private fun block(refs: List<TaskReference>): String =
        BLOCK_START + refs.joinToString("\n", transform = ::lines) + "\n" + DISPLAY_PREFIX +
            Base64.getEncoder().encodeToString(json(refs).toByteArray(Charsets.UTF_8)) + BLOCK_END

    private fun fromBase64(line: String): String? {
        if (line.isEmpty() || line.length % 4 != 0 || !BASE64.matches(line)) return null
        val bytes = runCatching { Base64.getDecoder().decode(line) }.getOrNull() ?: return null
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    fun decode(content: String): Decoded {
        if (!content.endsWith(BLOCK_END)) return Decoded(content, null)
        val at = content.lastIndexOf(BLOCK_START)
        if (at < 0) return Decoded(content, null)
        val inner = content.substring(at + BLOCK_START.length, content.length - BLOCK_END.length)
        val last = inner.substring(inner.lastIndexOf('\n') + 1)
        if (!last.startsWith(DISPLAY_PREFIX)) return Decoded(content, null)
        val text = fromBase64(last.substring(DISPLAY_PREFIX.length)) ?: return Decoded(content, null)
        val list = (WireJson.parseOrNull(text) as? JsonArray) ?: return Decoded(content, null)
        if (list.isEmpty() || list.size > MAX) return Decoded(content, null)
        val refs = list.map { validate(it) ?: return Decoded(content, null) }
        if (refs.map { it.taskId }.toSet().size != refs.size) return Decoded(content, null)
        if (block(refs) != content.substring(at)) return Decoded(content, null)
        return Decoded(content.substring(0, at), refs)
    }

    /** What [decode] reads back, for tests: [content] followed by the block the server writes. */
    fun encode(content: String, refs: List<TaskReference>): String = if (refs.isEmpty()) content else content + block(refs)
}
