package com.hienlh.ppm.state.tasks

import com.hienlh.ppm.protocol.TaskConst
import com.hienlh.ppm.protocol.TaskHistoryChange
import com.hienlh.ppm.protocol.TaskHistoryItem
import com.hienlh.ppm.protocol.TaskMember
import com.hienlh.ppm.protocol.TaskStatusDef
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.double
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.jsNumber
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * A history row in words (the web's `task-history-text.ts`): "Manager nxsys changed urgency
 * Today → Now", "You marked Done", "Assistant created this", values named as the task page names them.
 */

private val FIELD_LABEL = mapOf(
    "title" to "title", "type" to "type", "statusId" to "status", "urgency" to "urgency", "importance" to "importance", "why" to "why",
    "nextStep" to "next step", "notes" to "notes", "dueDate" to "due date", "tags" to "tags", "assigneeMemberId" to "assignee",
    "waitingOnMemberId" to "waiting on", "parentId" to "parent", "workingSessionId" to "working chat", "links" to "links",
    "result" to "result", "decision" to "decision", "rank" to "order", "deletedAt" to "deleted", "snoozedUntil" to "snooze",
    "snoozeUntilChange" to "snooze until a link changes", "resurfacedAt" to "back from snooze",
)

fun isAiEntry(e: TaskHistoryItem): Boolean = e.actor.kind in TaskConst.AI_ACTOR_KINDS

private fun fieldLabel(field: String) = if (field.startsWith("fields.")) field.drop(7) else FIELD_LABEL[field] ?: field

/** A JSON value as `String(v)` reads it. */
private fun plain(v: JsonElement): String = when {
    v is JsonPrimitive && v.isString -> v.content
    v is JsonPrimitive -> v.double?.let(::jsNumber) ?: v.content
    v is JsonArray -> v.joinToString(",") { plain(it) }
    else -> "[object Object]"
}

/** One value as the task page would show it; null for "nothing". */
fun historyValueText(field: String, v: JsonElement?, statuses: List<TaskStatusDef>?, members: List<TaskMember>?): String? {
    if (v == null || (v is JsonPrimitive && v.isString && v.content.isEmpty())) return null
    return when (field) {
        "statusId" -> statuses?.firstOrNull { it.id == v.string }?.name ?: plain(v)
        "urgency" -> URGENCY_LABEL[v.string] ?: plain(v)
        "importance" -> IMPORTANCE_LABEL[v.string] ?: plain(v)
        "type" -> typeLabel(plain(v))
        "assigneeMemberId", "waitingOnMemberId" -> memberName(plain(v), members)
        "tags" -> v.array?.joinToString(", ") { plain(it) }?.ifEmpty { null } ?: plain(v)
        "links" -> v.array?.let { "${it.size} link${if (it.size == 1) "" else "s"}" }
        "result" -> if (v is JsonObject) v["text"]?.let(::plain) ?: "" else plain(v)
        "decision" -> null
        else -> if (v is JsonObject || v is JsonArray) v.toString() else plain(v)
    }
}

private fun clipped(s: String, n: Int = 60) = if (s.length > n) s.take(n - 1) + "…" else s

/** "urgency Today → Now", "set due date to 2026-11-18", "cleared notes". */
fun historyChangeText(c: TaskHistoryChange, statuses: List<TaskStatusDef>?, members: List<TaskMember>?): String {
    val label = fieldLabel(c.field)
    val before = historyValueText(c.field, c.before, statuses, members)
    val after = historyValueText(c.field, c.after, statuses, members)
    if (c.field == "why" || c.field == "nextStep" || c.field == "notes") return if (after != null) "edited $label" else "cleared $label"
    val beforeLinks = c.before.array
    val afterLinks = c.after.array
    if (c.field == "links" && beforeLinks != null && afterLinks != null) {
        val had = beforeLinks.mapNotNull { it["ref"].string }
        val now = afterLinks.mapNotNull { it["ref"].string }
        val added = now.filter { it !in had }
        val removed = had.filter { it !in now }.distinct()
        return listOfNotNull(
            added.takeIf { it.isNotEmpty() }?.let { "linked ${it.joinToString(", ")}" },
            removed.takeIf { it.isNotEmpty() }?.let { "unlinked ${it.joinToString(", ")}" },
        ).joinToString("; ").ifEmpty { "changed links" }
    }
    return when {
        before != null && after != null -> "changed $label ${clipped(before, 40)} → ${clipped(after, 40)}"
        after != null -> "set $label to ${clipped(after)}"
        before != null -> "cleared $label"
        else -> "changed $label"
    }
}

/** The row's sentence after the actor's name. */
fun historyEntryText(e: TaskHistoryItem, statuses: List<TaskStatusDef>?, members: List<TaskMember>?): String = when (e.kind) {
    "created" -> "created this"
    "closed" -> {
        val status = e.changes.firstOrNull { it.field == "statusId" }
        "closed it" + (status?.let { " as ${historyValueText("statusId", it.after, statuses, members)}" } ?: "")
    }
    "reopened" -> "reopened it"
    "reordered" -> "changed its order"
    "decided" -> {
        val d = e.changes.firstOrNull { it.field == "decision" }?.after
        val chosen = d["chosen"]["optionId"].string
        val opt = d["options"].array?.firstOrNull { it["id"].string == chosen }
        if (opt != null) "chose ${opt["id"].string.orEmpty().uppercase()} — ${clipped(opt["label"].string.orEmpty())}" else "made the decision"
    }
    "decision" -> "set the decision options"
    "result" -> {
        val text = e.changes.firstOrNull { it.field == "result" }?.after?.let { it["text"].string }
        if (!text.isNullOrEmpty()) "recorded the result: ${clipped(text)}" else "cleared the result"
    }
    "deleted" -> "deleted it"
    "undo" -> "undid a change"
    "snoozed" -> snoozeEntryText(e)
    "resurfaced" -> "brought it back from its snooze"
    "close_proposed" -> {
        val as_ = e.changes.firstOrNull { it.field == "closeProposal" }?.after.string
        "proposed closing it as ${if (as_ == "dropped") "Dropped" else "Done"}"
    }
    else -> e.changes.joinToString("; ") { historyChangeText(it, statuses, members) }.ifEmpty { "changed it" }
}

/** "snoozed it until Thu 20 Nov", "snoozed it until a link changes", "unsnoozed it". */
fun snoozeEntryText(e: TaskHistoryItem): String {
    val until = e.changes.firstOrNull { it.field == "snoozedUntil" }?.after
    val change = e.changes.firstOrNull { it.field == "snoozeUntilChange" }?.after
    val parts = listOfNotNull(until?.let { shortDay(plain(it)) }, "a link changes".takeIf { change.bool == true })
    return if (parts.isNotEmpty()) "snoozed it until ${parts.joinToString(" or ")}" else "unsnoozed it"
}

/** "from Telegram", "through the API"; null for PPM itself. */
fun viaText(via: String): String? = when (via) {
    "telegram" -> "from Telegram"
    "api" -> "through the API"
    else -> null
}
