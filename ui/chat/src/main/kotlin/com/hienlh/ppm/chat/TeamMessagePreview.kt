package com.hienlh.ppm.chat

import com.hienlh.ppm.protocol.WireJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A team message as the team panel heads it (`previewTeamMessage` in team-message-preview.ts): a
 * teammate's text is usually a JSON protocol payload, a task assignment's `{ type, taskId,
 * subject, description, assignedBy }`, which the panel never shows raw.
 */
internal data class TeamMessagePreview(val title: String, val detail: String? = null, val taskId: String? = null) {
    companion object {
        /** The first of these with anything in it heads the message. */
        private val TITLE_FIELDS = listOf("subject", "summary", "text", "message", "content", "description", "reason", "feedback")

        /** What may say more under the headline. */
        private val DETAIL_FIELDS = listOf("description", "message", "content", "reason", "feedback")

        /** The envelope's keys, which never say what the message does. */
        private val ENVELOPE_KEYS = setOf("type", "taskId", "task_id", "assignedBy", "assigned_by", "timestamp", "request_id", "requestId")

        /** [raw] reduced to a headline and maybe a body; the author's own [summary] outranks anything in it. */
        fun of(raw: String, summary: String? = null): TeamMessagePreview {
            val authored = summary.orEmpty().trim()
            val text = raw.trim()
            if (text.isEmpty()) return TeamMessagePreview(authored)
            val parsed = WireJson.parseOrNull(text)
                ?: return TeamMessagePreview(
                    // Prose: its first line heads it, and the rest is the body.
                    authored.ifEmpty { text.substringBefore('\n').trim() },
                    text.substringAfter('\n', "").trim().ifEmpty { null },
                )
            val fields = parsed as? JsonObject ?: return TeamMessagePreview(authored.ifEmpty { text })
            val taskId = str(fields["taskId"]).ifEmpty { str(fields["task_id"]) }
            val head = pick(fields, TITLE_FIELDS, skip = null)
            // Never the headline again as the body.
            val body = pick(fields, DETAIL_FIELDS, skip = head?.first)
            val title = authored.ifEmpty { null } ?: head?.second ?: unknownFields(fields).ifEmpty { null } ?: str(fields["type"]).ifEmpty { text }
            return TeamMessagePreview(title, body?.second, taskId.ifEmpty { null })
        }

        /** `str`: a string trimmed, anything else as JavaScript prints it. */
        private fun str(value: JsonElement?): String = if (value is JsonPrimitive && value.isString) value.content.trim() else jsString(value)

        private fun pick(fields: JsonObject, keys: List<String>, skip: String?): Pair<String, String>? {
            for (key in keys) {
                if (key == skip) continue
                val value = str(fields[key])
                if (value.isNotEmpty()) return key to value
            }
            return null
        }

        /** `key: value` pairs, for a payload none of the known fields matched. */
        private fun unknownFields(fields: JsonObject): String =
            fields.entries
                .filter { (key, value) -> key !in ENVELOPE_KEYS && str(value).isNotEmpty() }
                .joinToString(" · ") { (key, value) -> "$key: ${str(value)}" }
    }
}
