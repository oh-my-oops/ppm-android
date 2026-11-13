package com.hienlh.ppm.chat

import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.workspace.OPEN_FILE_TOOL
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.floor

/*
 * A chat event read as a call of PPM's own tools, for the cards that show them: the tab tools
 * (src/web/lib/tab-tool-call.ts) and the database tools (db-tool-call.ts). Claude names a tool
 * `mcp__ppm-tabs__open_file`; Codex names it `ppm_tabs:open_file` and wraps the arguments as
 * `{ server, tool, arguments }`, live and in the history read back alike.
 */

/** `open_file` / `open_preview`, with the path as the AI passed it: absolute, `~`-relative, or relative to the chat's project. */
internal data class TabToolCall(val tool: String, val path: String, val line: Int? = null)

/** The tab server's tools that take no file: their calls show in the generic card. */
internal sealed interface DeviceToolCall {
    data class OpenUrl(val url: String) : DeviceToolCall

    /** `open_branch_review`: none of the refs is required, the host picks what is left out. */
    data class OpenBranchReview(val base: String? = null, val head: String? = null, val repo: String? = null) : DeviceToolCall

    data class ReadTerminal(val terminal: String? = null, val lines: Long? = null) : DeviceToolCall

    data class RunInTerminal(val command: String, val cwd: String? = null) : DeviceToolCall
}

data class DbToolCall(
    val tool: String,
    val connection: String,
    val sql: String,
    val database: String? = null,
    val reason: String? = null,
    val expectedRows: Double? = null,
)

internal const val OPEN_URL_TOOL = "open_url"
internal const val OPEN_BRANCH_REVIEW_TOOL = "open_branch_review"
const val OPEN_QUERY_TOOL = "open_query"
const val DB_QUERY_TOOL = "db_query"
const val DB_EXECUTE_TOOL = "db_execute"
const val DB_EXECUTE_APPROVAL = "ppm:db_execute"

private val TAB_TOOL_NAME = Regex("""(?:mcp__ppm-tabs__|ppm_tabs:)(open_file|open_preview)""")
private val DEVICE_TOOL_NAME = Regex("""(?:mcp__ppm-tabs__|ppm_tabs:)(open_url|open_branch_review|read_terminal|run_in_terminal)""")
private val DB_TOOL_NAME = Regex("""(?:mcp__ppm-db__|ppm_db:)(db_query|open_query|db_execute)""")

/** The highest line a tab opens at (`MAX_TAB_LINE`). */
private const val MAX_TAB_LINE = 10_000_000

/** What each database tool does, as its card names it (`DB_TOOL_LABELS`). */
val DB_TOOL_LABELS = mapOf("db_query" to "Database query", "open_query" to "Open query", "db_execute" to "Database change")

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A JSON number, as `typeof v === "number"` takes one. */
private fun JsonElement?.number(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

/** The tab tool call an event is, under either provider's name, or null. */
internal fun tabToolCall(name: String, input: JsonElement?): TabToolCall? {
    val tool = TAB_TOOL_NAME.matchEntire(name)?.groupValues?.get(1) ?: return null
    if (input !is JsonObject) return null
    val args = if ("arguments" in input) input["arguments"] else input
    if (args !is JsonObject) return null
    val path = args["path"].text()?.takeIf { it.isNotBlank() } ?: return null
    val line = args["line"].number()
    val valid = tool == OPEN_FILE_TOOL && line != null && line == floor(line) && line >= 1 && line <= MAX_TAB_LINE
    return TabToolCall(tool, path, if (valid) line!!.toInt() else null)
}

/** The `open_url` / `open_branch_review` / `read_terminal` / `run_in_terminal` tool a tool name is, or null: the card's icon needs only that. */
internal fun deviceToolOf(name: String): String? = DEVICE_TOOL_NAME.matchEntire(name)?.groupValues?.get(1)

/** The `open_url` / `open_branch_review` / `read_terminal` / `run_in_terminal` call an event is, under either provider's name, or null. */
internal fun deviceToolCall(name: String, input: JsonElement?): DeviceToolCall? {
    val tool = deviceToolOf(name) ?: return null
    if (input !is JsonObject) return null
    val args = (input["arguments"] as? JsonObject) ?: input
    fun text(key: String) = args[key].text()?.takeIf { it.isNotBlank() }
    return when (tool) {
        OPEN_URL_TOOL -> text("url")?.let(DeviceToolCall::OpenUrl)
        OPEN_BRANCH_REVIEW_TOOL -> DeviceToolCall.OpenBranchReview(text("base"), text("head"), text("repo"))
        "run_in_terminal" -> text("command")?.let { DeviceToolCall.RunInTerminal(it, text("cwd")) }
        else -> {
            val lines = args["lines"].number()?.takeIf { it == floor(it) && it > 0 }
            DeviceToolCall.ReadTerminal(text("terminal"), lines?.toLong())
        }
    }
}

/** The database tool a tool name is, under either provider's name, or null. */
fun dbToolOf(name: String): String? = DB_TOOL_NAME.matchEntire(name)?.groupValues?.get(1)

/** The database tool call an event is, under either provider's name, or null. */
fun dbToolCall(name: String, input: JsonElement?): DbToolCall? {
    val tool = dbToolOf(name) ?: return null
    if (input !is JsonObject) return null
    val args = (input["arguments"] as? JsonObject) ?: input
    val connection = args["connection"].text() ?: return null
    val sql = args["sql"].text() ?: return null
    return DbToolCall(
        tool,
        connection,
        sql,
        database = args["database"].text()?.takeIf { it.isNotEmpty() },
        reason = args["reason"].text()?.takeIf { it.isNotEmpty() },
        expectedRows = args["expected_rows"].number(),
    )
}

data class DbExecuteApprovalInput(
    val connection: String,
    val database: String? = null,
    val sql: String,
    val reason: String,
    val expectedRows: Double? = null,
    val dbType: String? = null,
)

/** Extract approval card payload for `ppm:db_execute` (`db-execute-approval-card.tsx`). */
fun dbExecuteApprovalInput(input: JsonElement?): DbExecuteApprovalInput? {
    if (input !is JsonObject) return null
    val args = (input["arguments"] as? JsonObject) ?: input
    val connection = args["connection"].text()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val sql = args["sql"].text()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val reason = args["reason"].text()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return DbExecuteApprovalInput(
        connection = connection,
        database = args["database"].text()?.trim()?.takeIf { it.isNotEmpty() },
        sql = sql,
        reason = reason,
        expectedRows = args["expected_rows"].number() ?: args["expectedRows"].number(),
        dbType = args["dbType"].text()?.trim()?.takeIf { it.isNotEmpty() },
    )
}

private val DDL_SELF_COMMIT_AFTER = Regex(
    """(?:^|;)\s*(?:CREATE|ALTER|DROP|TRUNCATE|RENAME)\b[\s\S]*?;\s*\S""",
    RegexOption.IGNORE_CASE,
)

/** Whether a script contains DDL statements that auto-commit before a subsequent statement on MySQL/MariaDB. */
fun commitsByItself(sql: String): Boolean = DDL_SELF_COMMIT_AFTER.containsMatchIn(sql)

/** Warning note shown on `ppm:db_execute` approval cards for MySQL/MariaDB scripts with auto-committing DDL. */
fun selfCommitNote(dbType: String?, sql: String): String? {
    val normalized = dbType?.trim()?.lowercase()
    if (normalized != "mysql" && normalized != "mariadb") return null
    if (!commitsByItself(sql)) return null
    return "MySQL and MariaDB commit CREATE, ALTER, DROP, TRUNCATE and RENAME by themselves, with everything before them. If a later statement fails, only what follows the last one is rolled back."
}

/** How Codex's events label an image block of a result (`mcpResultText`). */
private val CODEX_IMAGE_LABEL = Regex("""\[image(?: [^\]\s]+)?]""")

/** The stand-in a stored result has for an image block (`isImagePlaceholderText`). */
private val PLACEHOLDER_BLOCK = Regex("""\[image(?: · [\d.]+(?:B|KB|MB))?]""")

/** A tool result's text as the AI read it, without the stand-in for the screenshot (`tabToolResultText`). */
internal fun tabToolResultText(output: String): String {
    fun plain() = output.split("\n").filterNot(CODEX_IMAGE_LABEL::matches).joinToString("\n").trimEnd()
    if (!output.startsWith("[")) return plain()
    val blocks = WireJson.parseOrNull(output) as? JsonArray ?: return plain()
    return blocks
        .filter { it is JsonObject && it["type"].string == "text" && it["text"].text() != null }
        .map { it["text"].text()!! }
        .filterNot(PLACEHOLDER_BLOCK::matches)
        .joinToString("\n\n")
}

private val PROBLEMS_FOUND = Regex("""^(\d+) problems? found\b""")

/**
 * How many problems an `open_preview` check reported, or null for any other result. Only the
 * line `formatPreviewCheck` writes it on is read: the findings below it quote the page, which
 * could otherwise put a count of its own there.
 */
internal fun previewProblemCount(text: String): Long? {
    val line = text.split("\n").getOrNull(1) ?: ""
    if (line.startsWith("No layout problems or runtime errors were found")) return 0
    return PROBLEMS_FOUND.find(line)?.groupValues?.get(1)?.let { it.toLongOrNull() ?: Long.MAX_VALUE }
}
