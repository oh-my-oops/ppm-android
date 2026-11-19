package com.hienlh.ppm.chat

import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.state.tasks.parseTaskRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/*
 * A chat event read as a call of one of PPM's own MCP tools — the session server (`ppm-sessions`:
 * tasks, schedules, reports), the tab server (`ppm-tabs`: session history), the PPM Assistant's and
 * the design server's — turned into the one line its card shows: a readable name, what the call was
 * about, and what it answered (`ppm-tool-call.ts`, `ppm-tool-summaries-tasks.ts`,
 * `ppm-tool-summaries-sessions.ts`, `ppm-tool-summary-types.ts`). Pure, so it is tested on the JVM.
 *
 * Tools with a card of their own (`create_session`, `send_to_session`, `session_status`, the tab,
 * device, database and vault tools, design screenshots) are read by their own files; this one gives
 * the ordinary card its line for the others, and for any of those whose input their own card could
 * not read. Everything here comes from an AI or from a tool's output, so every field is checked:
 * anything missing or malformed is left out of the line, never shown as "null".
 */

/** Which PPM server a tool belongs to. */
enum class PpmToolServer { SESSIONS, TABS, DB, ASSISTANT, DESIGN }

data class PpmToolName(val server: PpmToolServer, val tool: String)

/** A chat the card can open. */
data class PpmToolSessionLink(
    val sessionId: String,
    val providerId: String? = null,
    val turnId: String? = null,
    /** Set only when the tool named another project. */
    val project: String? = null,
)

/** What one card's header says. */
data class PpmToolSummary(
    /** The tool's readable name, in the form that fits where the call stands ("Create task", "Created task"). */
    val label: String,
    /** What the call was about and what it answered, one line. */
    val detail: String? = null,
    /** A short word on the right ("3 results", "4 schedules"). */
    val badge: String? = null,
    /** The task code to open (`ppm/12`, `#12`). */
    val task: String? = null,
    val session: PpmToolSessionLink? = null,
)

/** The icon family a PPM tool's chip shows. */
enum class PpmToolIcon { TASK, SEARCH, TURNS, SCHEDULE, REPORT, ASSISTANT, DESIGN, TOOL }

/** Each server under Claude's name (hyphen) and Codex's (underscore: a hyphen is not safe in its config key). */
private val SERVERS = mapOf(
    "ppm-sessions" to PpmToolServer.SESSIONS, "ppm_sessions" to PpmToolServer.SESSIONS,
    "ppm-tabs" to PpmToolServer.TABS, "ppm_tabs" to PpmToolServer.TABS,
    "ppm-db" to PpmToolServer.DB, "ppm_db" to PpmToolServer.DB,
    "ppm-assistant" to PpmToolServer.ASSISTANT, "ppm_assistant" to PpmToolServer.ASSISTANT,
    "ppm-design" to PpmToolServer.DESIGN, "ppm_design" to PpmToolServer.DESIGN,
)

/** Claude: `mcp__ppm-sessions__ppm_task_create`. Codex: `ppm_sessions:ppm_task_create`. Antigravity: `mcp_ppm-sessions_ppm_task_create`. */
private val CLAUDE_NAME = Regex("""^mcp__(ppm-[a-z]+)__([a-z0-9_]+)$""")
private val CODEX_NAME = Regex("""^(ppm_[a-z]+):([a-z0-9_]+)$""")
private val ANTIGRAVITY_NAME = Regex("""^mcp_(ppm-[a-z]+)_([a-z0-9_]+)$""")

/** The PPM server and tool a tool name is, or null for any other tool. */
fun ppmToolName(toolName: String): PpmToolName? {
    val m = CLAUDE_NAME.matchEntire(toolName) ?: CODEX_NAME.matchEntire(toolName) ?: ANTIGRAVITY_NAME.matchEntire(toolName) ?: return null
    val server = SERVERS[m.groupValues[1]] ?: return null
    return PpmToolName(server, m.groupValues[2])
}

/** The arguments of a call: Codex wraps them as `{ server, tool, arguments }`. */
internal fun ppmToolArgs(input: JsonElement?): JsonObject {
    if (input !is JsonObject) return JsonObject(emptyMap())
    return (input["arguments"] as? JsonObject) ?: input
}

/** Words a tool name abbreviates, as a person writes them. */
private val WORDS = mapOf("ui" to "UI", "db" to "database", "cli" to "CLI", "mcp" to "MCP", "ppm" to "PPM", "pr" to "PR", "url" to "URL")

/** "chat_list_sessions" → "Chat list sessions", "ui_open_tab" → "UI open tab". */
fun humanizeToolName(tool: String): String {
    val words = tool.removePrefix("ppm_").split("_").filter { it.isNotEmpty() }.joinToString(" ") { WORDS[it] ?: it }
    return if (words.isEmpty()) tool else words[0].uppercaseChar() + words.substring(1)
}

/**
 * PPM tools whose card is the ordinary one under a readable name, with no line of their own, and
 * why. Every tool of the PPM Assistant's own server (`ppm-assistant`) is named only too.
 */
val PPM_TOOLS_NAMED_ONLY: Map<String, String> = mapOf(
    "design_screenshot" to "its pictures are the card: the ordinary card previews them",
    "design_check" to "a design chat's check of its own canvas; the report it returns is the output",
)

/** One call, as a reader of a tool family sees it. */
internal class PpmToolContext(
    val tool: String,
    val args: JsonObject,
    /** The answer's text (image stand-ins dropped), "" before there is one. */
    val text: String,
    val isError: Boolean,
    /** Answered without an error: the line may say what came of it. */
    val ok: Boolean,
)

/** Whether a PPM tool has a line of its own read from its input and output. */
fun ppmToolHasOwnLine(tool: String): Boolean {
    val ctx = PpmToolContext(tool, JsonObject(emptyMap()), "", isError = false, ok = false)
    return (taskToolSummary(ctx) ?: sessionToolSummary(ctx)) != null
}

/** Arguments that name what a call is about, in the order a generic line prefers them. */
private val HINT_KEYS = listOf("title", "query", "name", "project", "session_id", "path", "branch", "url", "connection", "task", "id", "message")

/** A line for a PPM tool with no reading of its own: its name and the first argument that says what it is about. */
private fun genericSummary(ctx: PpmToolContext): PpmToolSummary {
    for (key in HINT_KEYS) {
        val v = ctx.args[key]
        val number = (v as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.doubleOrNull?.takeIf { it.isFinite() }
        val text = if (number != null) (v as JsonPrimitive).content else str(v)
        if (text != null) return PpmToolSummary(humanizeToolName(ctx.tool), oneLine(text, 60))
    }
    return PpmToolSummary(humanizeToolName(ctx.tool))
}

/**
 * The header line of a PPM tool's card, or null when the tool is not one of PPM's own (or is a
 * database tool, which has its own line). [output] is the tool's raw answer, null before it came.
 * Never throws: an output it cannot read gives a line from the input alone.
 */
fun ppmToolSummary(toolName: String, input: JsonElement?, output: String?, isError: Boolean): PpmToolSummary? {
    val name = ppmToolName(toolName) ?: return null
    if (name.server == PpmToolServer.DB) return null
    return try {
        val text = output?.let(::tabToolResultText).orEmpty()
        val ctx = PpmToolContext(name.tool, ppmToolArgs(input), text, isError, ok = output != null && !isError)
        taskToolSummary(ctx) ?: sessionToolSummary(ctx) ?: genericSummary(ctx)
    } catch (_: RuntimeException) {
        PpmToolSummary(humanizeToolName(name.tool))
    }
}

fun ppmToolIcon(toolName: String): PpmToolIcon? {
    val name = ppmToolName(toolName) ?: return null
    if (name.server == PpmToolServer.DB) return null
    val t = name.tool
    return when {
        t.startsWith("ppm_task_") -> PpmToolIcon.TASK
        t == "search_sessions" -> PpmToolIcon.SEARCH
        t == "list_session_turns" || t == "read_session_turns" -> PpmToolIcon.TURNS
        t.startsWith("session_schedule_") -> PpmToolIcon.SCHEDULE
        t == "report_to_assistant" -> PpmToolIcon.REPORT
        name.server == PpmToolServer.DESIGN || t.startsWith("design_") -> PpmToolIcon.DESIGN
        name.server == PpmToolServer.ASSISTANT -> PpmToolIcon.ASSISTANT
        else -> PpmToolIcon.TOOL
    }
}

/**
 * The line under a failed PPM tool's header: the first line of its answer, or — for an answer that
 * is a JSON object, as the schedule tools' refusals are — its note, message or outcome.
 */
fun ppmToolErrorLine(output: String): String {
    val text = tabToolResultText(output)
    val answer = jsonAnswer(text)
    if (answer != null) {
        val said = str(answer["note"]) ?: str(answer["message"]) ?: str(answer["error"]) ?: str(answer["outcome"])
        if (said != null) return oneLine(said, 300)
    }
    return text.split("\n").firstOrNull { it.isNotBlank() }?.trim().orEmpty()
}

private val ROWS_RETURNED = Regex(""" returned (no rows|(\d+) rows?)\b""")

/** How many rows a `db_query` answer says its statements returned, or null when it says none or cannot be read. */
fun dbRowsBadge(output: String?): String? {
    if (output == null) return null
    val text = tabToolResultText(output)
    var rows = 0L
    var found = false
    for (m in ROWS_RETURNED.findAll(text)) {
        found = true
        rows += m.groups[2]?.value?.toLongOrNull() ?: 0
    }
    return if (found) "$rows ${if (rows == 1L) "row" else "rows"}" else null
}

// Shared helpers (`ppm-tool-summary-types.ts`)

/** A non-blank string, trimmed, or null. */
private fun str(v: JsonElement?): String? =
    (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

/** A positive whole number, or null. */
private fun posInt(v: JsonElement?): Long? {
    val d = (v as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.doubleOrNull ?: return null
    return if (d > 0 && d == Math.floor(d) && d < 9e15) d.toLong() else null
}

private val SPACES = Regex("""\s+""")

/** One line of at most [max] characters. */
private fun oneLine(text: String, max: Int = 80): String {
    val flat = text.replace(SPACES, " ").trim()
    return if (flat.length > max) flat.take(max - 1) + "…" else flat
}

private fun plural(n: Long, word: String): String = "$n $word${if (n == 1L) "" else "s"}"

/** Parts that are present, joined by " · ". */
private fun joinParts(vararg parts: String?): String? =
    parts.filter { !it.isNullOrEmpty() }.takeIf { it.isNotEmpty() }?.joinToString(" · ")

/** A JSON object answer, or null when the text is not one. */
private fun jsonAnswer(text: String): JsonObject? {
    val raw = text.trim()
    if (!raw.startsWith("{")) return null
    return WireJson.parseOrNull(raw) as? JsonObject
}

/** The label for where a call stands: the plain name while it runs or failed, the past form once it answered. */
private fun labelFor(ctx: PpmToolContext, present: String, past: String): String = if (ctx.ok) past else present

/** `String(v)` as JavaScript prints a value. */
private fun jsText(v: JsonElement?): String = when (v) {
    null, JsonNull -> "null"
    is JsonPrimitive -> v.content
    is JsonArray -> v.joinToString(",") { if (it is JsonNull) "" else jsText(it) }
    is JsonObject -> "[object Object]"
}

// Task tools (`ppm-tool-summaries-tasks.ts`)

/** The first line of a write's answer: `Created: ppm/151 "Fix login" — now revision 1.` */
private val WRITE_LINE = Regex("""^(Created|Updated|Closed(?: as ([a-z]+))?|Moved|Undid #(\d+) on): (\S+) "(.*)" — now revision \d+\.?$""")

/** A proposal's answer: `Proposed closing ppm/151 "Fix login" as done. It stays open…` */
private val PROPOSAL_LINE = Regex("""^Proposed closing (\S+) "(.*)" as ([a-z]+)\.""")

/** An update that changed nothing: `Nothing changed: ppm/151 already holds those values (revision 3).` */
private val UNCHANGED_LINE = Regex("""^Nothing changed: (\S+) already holds\b""")

/** A task's first line inside the fence: `ppm/151 · week/normal · In progress · rev 3 · "Fix login" · …` */
private val TASK_LINE = Regex("""^(\S+) · [^·]+ · (.+?) · rev \d+ · "(.*?)"(?: · |$)""")

/** A list's head: `3 of 12 tasks, what to do first at the top…` */
private val TASK_LIST_HEAD = Regex("""^(\d+) of (\d+) tasks?\b""")

/**
 * A code the card can open, or null: `ppm/12`, `#12`, or a bare number read as `#12` — in the
 * project the call named, when it named one and the code did not.
 */
private fun taskCode(v: JsonElement?, project: String? = null): String? {
    val s = str(v) ?: return null
    val ref = parseTaskRef(s) ?: return null
    val named = ref.project ?: project?.takeIf { parseTaskRef("$it/${ref.number}") != null }
    return if (named != null) "$named/${ref.number}" else "#${ref.number}"
}

/** What `task` names, for the line: its code when it is one, else a short id. */
private fun taskNamed(v: JsonElement?): String? {
    val s = str(v) ?: return null
    return taskCode(v) ?: oneLine(s, 24)
}

private class WriteAnswer(val kind: String, val code: String, val title: String, val closedAs: String? = null)

/** The code and title a write answered with, when the answer is the one the tool writes. */
private fun writeAnswer(text: String): WriteAnswer? {
    val first = text.substringBefore('\n').trim()
    WRITE_LINE.find(first)?.let { m ->
        val head = m.groupValues[1]
        val kind = when {
            head.startsWith("Closed") -> "Closed"
            head.startsWith("Undid") -> "Undid"
            else -> head
        }
        return WriteAnswer(kind, m.groupValues[4], m.groupValues[5], m.groups[2]?.value)
    }
    PROPOSAL_LINE.find(first)?.let { return WriteAnswer("Proposed", it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
    return UNCHANGED_LINE.find(first)?.let { WriteAnswer("Unchanged", it.groupValues[1], "") }
}

/** The task line inside a `ppm_task_get` answer. */
private fun taskAnswer(text: String): Triple<String, String, String>? {
    for (line in text.split("\n")) {
        val m = TASK_LINE.find(line.trim()) ?: continue
        return Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
    }
    return null
}

/** Fields an update names, as the line shows them: `status → Waiting`, then the names of the rest. */
private val SHOWN_VALUES = listOf("status", "urgency", "importance", "assignee", "waiting_on", "due_date")
private val OTHER_FIELDS = mapOf(
    "title" to "title", "type" to "type", "why" to "why", "next_step" to "next step", "notes" to "notes", "tags" to "tags", "parent" to "parent",
    "working_session" to "working chat", "fields" to "fields", "snoozed_until" to "snooze", "snooze_until_change" to "snooze",
    "add_links" to "links", "remove_links" to "links", "result" to "result",
)

private fun changesOf(args: JsonObject): String? {
    val parts = mutableListOf<String>()
    for (key in SHOWN_VALUES) {
        if (key !in args) continue
        val v = args[key]
        parts += "${key.replaceFirst("_", " ")} → ${if (v is JsonNull) "none" else oneLine(str(v) ?: jsText(v), 30)}"
    }
    val others = args.keys.mapNotNull { OTHER_FIELDS[it] }.distinct()
    val all = parts + others
    if (all.isEmpty()) return null
    return if (all.size > 3) "${all.take(3).joinToString(", ")} +${all.size - 3}" else all.joinToString(", ")
}

/** Filters a list call named, for its line. */
private fun listFilters(args: JsonObject): String? {
    val query = str(args["query"])
    return joinParts(
        str(args["project"]),
        query?.let { "“${oneLine(it, 30)}”" },
        str(args["status_group"]), str(args["urgency"]), str(args["person"])?.let { "person ${oneLine(it, 20)}" },
        str(args["tag"])?.let { "tag ${oneLine(it, 20)}" },
    )
}

internal fun taskToolSummary(ctx: PpmToolContext): PpmToolSummary? {
    val tool = ctx.tool
    val args = ctx.args
    if (!tool.startsWith("ppm_task_")) return null
    val write = if (ctx.ok) writeAnswer(ctx.text) else null
    val inputCode = taskCode(args["task"], str(args["project"]))

    return when (tool) {
        "ppm_task_create" -> {
            if (write != null) PpmToolSummary("Created task", joinParts(write.code, oneLine(write.title, 80)), task = write.code)
            else PpmToolSummary("Create task", str(args["title"])?.let { oneLine(it, 80) })
        }
        "ppm_task_update" -> {
            val changes = changesOf(args)
            when {
                write?.kind == "Proposed" -> PpmToolSummary(
                    "Proposed closing task",
                    joinParts(write.code, write.closedAs?.let { "as $it" }, oneLine(write.title, 60)),
                    task = write.code,
                )
                write != null -> PpmToolSummary(
                    if (write.kind == "Unchanged") "Task unchanged" else "Updated task",
                    joinParts(if (changes != null) "${write.code}: $changes" else write.code, oneLine(write.title, 50)),
                    task = write.code,
                )
                else -> {
                    val named = taskNamed(args["task"])
                    PpmToolSummary("Update task", if (named != null && changes != null) "$named: $changes" else named ?: changes, task = inputCode)
                }
            }
        }
        "ppm_task_close" -> {
            val asked = str(args["as"]) ?: str(args["status"])?.let { "“${oneLine(it, 20)}”" }
            if (write != null) {
                val closedAs = write.closedAs ?: asked
                PpmToolSummary("Closed task", joinParts(write.code, closedAs?.let { "as $it" }, oneLine(write.title, 60)), task = write.code)
            } else {
                PpmToolSummary("Close task", joinParts(taskNamed(args["task"]), asked?.let { "as $it" }), task = inputCode)
            }
        }
        "ppm_task_get" -> {
            val found = if (ctx.ok) taskAnswer(ctx.text) else null
            if (found != null) {
                val (code, status, title) = found
                PpmToolSummary("Read task", joinParts(code, oneLine(title, 60), oneLine(status, 24)), task = code)
            } else {
                PpmToolSummary("Read task", taskNamed(args["task"]), task = inputCode)
            }
        }
        "ppm_task_list" -> {
            val filters = listFilters(args)
            if (ctx.ok) {
                val trimmed = ctx.text.trim()
                val head = TASK_LIST_HEAD.find(trimmed)
                val badge = when {
                    head != null -> if (head.groupValues[1] == head.groupValues[2]) plural(head.groupValues[1].toLong(), "task") else "${head.groupValues[1]} of ${head.groupValues[2]} tasks"
                    trimmed.startsWith("No tasks match") -> "0 tasks"
                    else -> null
                }
                PpmToolSummary("Listed tasks", filters, badge)
            } else {
                PpmToolSummary("List tasks", filters)
            }
        }
        "ppm_task_reorder" -> {
            if (write != null) {
                PpmToolSummary("Moved task", joinParts(write.code, oneLine(write.title, 60)), task = write.code)
            } else {
                val anchor = taskNamed(args["before"])?.let { "before $it" } ?: taskNamed(args["after"])?.let { "after $it" }
                PpmToolSummary("Move task", joinParts(taskNamed(args["task"]), anchor), task = inputCode)
            }
        }
        "ppm_task_undo" -> {
            val id = posInt(args["history_id"])
            if (write != null) PpmToolSummary("Undid task change", joinParts(write.code, id?.let { "#$it" }, oneLine(write.title, 60)), task = write.code)
            else PpmToolSummary("Undo task change", id?.let { "#$it" })
        }
        "ppm_task_project" -> PpmToolSummary(labelFor(ctx, "Read task setup", "Read task setup"), str(args["project"]))
        "ppm_task_project_update" -> PpmToolSummary(labelFor(ctx, "Change task setup", "Changed task setup"), str(args["project"]))
        "ppm_task_notify_manager" -> PpmToolSummary(labelFor(ctx, "Tell the manager", "Told the manager"), str(args["project"]))
        else -> null
    }
}

// Session history, report and schedule tools (`ppm-tool-summaries-sessions.ts`)

/** A search hit: `- <session id> · <provider> · …`, one per title or content match. */
private val SEARCH_HIT = Regex("""^- \S+ · \S+ · """)

/** `Session <id> · <provider> · <project> · <n> turns` (list) or `… · Turn <n> · …` (a read turn). */
private val TURNS_LIST_HEAD = Regex("""^Session (\S+) · (\S+) · .*? · (\d+) turns?\b""")
private val TURN_HEAD = Regex("""^Session (\S+) · (\S+) · (.*?) · Turn (\d+) · """)
private val OTHER_PROJECT = Regex("""^project (.+?)(?:, not this chat's project)?$""")

/**
 * The project a read turn's header names (`turnProjectLabel`): null for the chat's own
 * ("this project (demo)"), the name for another one ("project web, not this chat's project").
 */
private fun otherProjectOf(label: String?): String? {
    if (label == null || label.startsWith("this project")) return null
    return OTHER_PROJECT.find(label)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
}

private val REPORT_KINDS = mapOf("done" to "Done", "failed" to "Failed", "needs_decision" to "Needs decision", "urgent" to "Urgent", "digest" to "Digest")

/** A provider id the card can open a chat with. */
private fun provider(v: String?): String? = v?.takeIf { it.isNotEmpty() && it != "?" }

private fun searchSessions(ctx: PpmToolContext): PpmToolSummary {
    val detail = str(ctx.args["query"])?.let { "“${oneLine(it, 40)}”" }
    if (!ctx.ok) return PpmToolSummary("Search sessions", detail)
    val hits = ctx.text.split("\n").count { SEARCH_HIT.containsMatchIn(it.trim()) }.toLong()
    val none = Regex("""No session matches\b""").containsMatchIn(ctx.text)
    return PpmToolSummary("Searched sessions", detail, if (hits > 0 || none) plural(hits, "result") else null)
}

private fun listTurns(ctx: PpmToolContext): PpmToolSummary {
    val sessionId = str(ctx.args["session_id"])
    val head = if (ctx.ok) TURNS_LIST_HEAD.find(ctx.text.trim()) else null
    return PpmToolSummary(
        labelFor(ctx, "List turns", "Listed turns"),
        badge = head?.let { plural(it.groupValues[3].toLong(), "turn") },
        session = sessionId?.let { PpmToolSessionLink(it, provider(head?.groupValues?.get(2))) },
    )
}

private fun readTurns(ctx: PpmToolContext): PpmToolSummary {
    val args = ctx.args
    val sessionId = str(args["session_id"])
    val ids = (args["turn_ids"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString && p.content.isNotBlank() }?.content }
    val from = posInt(args["from_turn"])
    val to = posInt(args["to_turn"])
    val read = if (ctx.ok) ctx.text.split("\n").mapNotNull { TURN_HEAD.find(it.trim()) } else emptyList()
    val numbers = read.map { it.groupValues[4].toLong() }.distinct()
    // "Read turns 490, 491", "Read turns 3–5", "Read 2 turns" (before the answer names them).
    val (label, turns) = when {
        numbers.isNotEmpty() -> (if (numbers.size == 1) "Read turn" else "Read turns") to
            (numbers.take(4).joinToString(", ") + if (numbers.size > 4) "…" else "")
        from != null && to != null -> if (from == to) "Read turn" to "$from" else "Read turns" to "$from–$to"
        else -> (if (ids.size == 1) "Read turn" else if (ids.size > 1) "Read ${ids.size} turns" else "Read turns") to null
    }
    // A turn the user linked from another project is read without `project`: its header says where it is.
    val project = str(args["project"]) ?: otherProjectOf(read.firstOrNull()?.groupValues?.get(3))
    val providerId = provider(read.firstOrNull()?.groupValues?.get(2))
    val session = sessionId?.let { PpmToolSessionLink(it, providerId, ids.singleOrNull(), project) }
    return PpmToolSummary(label, joinParts(turns, project?.let { "in ${oneLine(it, 30)}" }), session = session)
}

private fun report(ctx: PpmToolContext): PpmToolSummary {
    val answer = if (ctx.ok) jsonAnswer(ctx.text) else null
    val kind = str(answer?.get("kind")) ?: str(ctx.args["kind"])
    val title = str(ctx.args["title"])
    val kindLabel = kind?.let { REPORT_KINDS[it] ?: oneLine(it, 20) }
    return PpmToolSummary(labelFor(ctx, "Report to Assistant", "Reported to Assistant"), joinParts(kindLabel, title?.let { oneLine(it, 80) }))
}

private class ScheduleNamed(val id: Long? = null, val name: String? = null, val next: String? = null)

/** A schedule's name and next fire as an answer gives them. */
private fun scheduleOf(v: JsonElement?): ScheduleNamed {
    if (v !is JsonObject) return ScheduleNamed()
    return ScheduleNamed(posInt(v["id"]), str(v["name"]), str((v["nextFire"] as? JsonObject)?.get("local")))
}

private fun named(s: ScheduleNamed): String? = joinParts(s.id?.let { "#$it" }, s.name?.let { "“${oneLine(it, 50)}”" })

private fun outcomeBadge(v: String?): String? = v?.let { oneLine(it.replace("_", " "), 20) }

private fun schedule(ctx: PpmToolContext): PpmToolSummary? {
    val args = ctx.args
    // Read on an error too: a declined or stale card answers `{ done: false, outcome }` as an error.
    val answer = jsonAnswer(ctx.text)
    fun bool(key: String): Boolean? = (answer?.get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
    return when (ctx.tool) {
        "session_schedule_create" -> {
            val s = scheduleOf(answer)
            val inputName = str(args["name"]) ?: str(args["prompt"])?.let { oneLine(it, 50) }
            if (bool("created") == true) PpmToolSummary("Scheduled prompt", joinParts(named(s) ?: inputName, s.next?.let { "next $it" }))
            else PpmToolSummary("Schedule prompt", joinParts(inputName?.let { "“$it”" }, str(args["cron"])))
        }
        "session_schedule_list" -> {
            if (!ctx.ok || answer == null) return PpmToolSummary("List schedules")
            val own = (answer["schedules"] as? JsonArray)?.size?.toLong()
            val project = (answer["projectSchedules"] as? JsonArray)?.size ?: 0
            PpmToolSummary("Listed schedules", if (project > 0) "$project more in the project" else null, own?.let { plural(it, "schedule") })
        }
        "session_schedule_delete" -> {
            val id = posInt(args["id"]) ?: str(args["id"])?.takeIf { it.all(Char::isDigit) }?.toLongOrNull()?.takeIf { it > 0 }
            if (bool("deleted") == true) PpmToolSummary("Deleted schedule", named(ScheduleNamed(posInt(answer?.get("id")) ?: id, str(answer?.get("name")))))
            else PpmToolSummary("Delete schedule", id?.let { "#$it" })
        }
        "session_schedule_set_enabled" -> {
            val enabled = (args["enabled"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
            val on = enabled == true
            val id = posInt(args["id"])
            if (bool("updated") == true) {
                val changed = scheduleOf(answer?.get(if (on) "turnedOn" else "paused"))
                return PpmToolSummary(if (on) "Turned on schedule" else "Paused schedule", named(changed))
            }
            val stale = if (bool("done") == false) str(answer?.get("outcome")) else null
            PpmToolSummary(if (enabled == false) "Pause schedule" else "Turn on schedule", id?.let { "#$it" }, outcomeBadge(stale))
        }
        "session_schedule_propose_script" -> {
            if (bool("created") == true) return PpmToolSummary("Created script schedule", named(scheduleOf(answer?.get("schedule"))))
            val declined = if (bool("done") == false) str(answer?.get("outcome")) else null
            PpmToolSummary("Propose script schedule", str(args["name"])?.let { "“${oneLine(it, 50)}”" }, outcomeBadge(declined))
        }
        else -> null
    }
}

internal fun sessionToolSummary(ctx: PpmToolContext): PpmToolSummary? = when (ctx.tool) {
    "search_sessions" -> searchSessions(ctx)
    "list_session_turns" -> listTurns(ctx)
    "read_session_turns" -> readTurns(ctx)
    "report_to_assistant" -> report(ctx)
    else -> if (ctx.tool.startsWith("session_schedule_")) schedule(ctx) else null
}
