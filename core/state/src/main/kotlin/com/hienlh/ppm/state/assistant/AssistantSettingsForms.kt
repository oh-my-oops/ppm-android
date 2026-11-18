package com.hienlh.ppm.state.assistant

import com.hienlh.ppm.protocol.AssistantMcpServer
import com.hienlh.ppm.protocol.ManagerChatSummary
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The pure half of Settings → PPM Assistant: the MCP server form's checks (`assistant-settings.ts`,
 * `assistant-mcp-server-dialog.tsx`), the manager table's rows and labels
 * (`assistant-manager-table.ts`), and the small texts the panes show.
 */

private const val MCP_NAME_MAX = 48
private const val MCP_COMMAND_MAX = 1024
private const val MCP_ARGS_MAX = 64
private const val MCP_ARG_MAX = 4096
private const val MCP_PAIRS_MAX = 32
private const val MCP_VALUE_MAX = 8192
private val MCP_NAME_RE = Regex("^[A-Za-z0-9][A-Za-z0-9_-]*$")
private val ENV_KEY_RE = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
private val HEADER_KEY_RE = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
private val RESERVED_NAMES = listOf("ppm-assistant", "ppm_assistant")
private val SESSION_TOOL_NAMES = listOf("ppm-sessions", "ppm_sessions")

/** Case and `-`/`_` folded, the way two names are compared for clashes. */
fun foldMcpName(name: String): String = name.lowercase(Locale.ROOT).replace('_', '-')

/** Why a server name cannot be used, or null (`assistantMcpNameError`). */
fun assistantMcpNameError(name: String): String? {
    if (name.isEmpty()) return "Name is required"
    if (name.length > MCP_NAME_MAX) return "Name must be at most $MCP_NAME_MAX characters"
    if (!MCP_NAME_RE.matches(name)) return "Name must start with a letter or digit and use only letters, digits, - and _"
    if ("__" in name) return "Name cannot contain two underscores in a row"
    val bare = foldMcpName(name).trimEnd('-', '_')
    if (RESERVED_NAMES.any { foldMcpName(it) == bare }) return "\"$name\" is the Assistant's own tool server"
    if (SESSION_TOOL_NAMES.any { foldMcpName(it) == bare }) return "\"$name\" is PPM's session tool server"
    return null
}

/** Whether two URLs share scheme, host and port; an unparsable one shares nothing. */
fun sameOrigin(a: String, b: String): Boolean {
    fun origin(raw: String): Triple<String, String, Int>? = try {
        val uri = URI(raw.trim())
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val port = if (uri.port != -1) uri.port else if (scheme == "https") 443 else if (scheme == "http") 80 else -1
        Triple(scheme, host, port)
    } catch (_: Exception) {
        null
    }
    val left = origin(a) ?: return false
    return left == origin(b)
}

/** The MCP dialog's fields as typed. */
data class McpServerForm(
    val name: String = "",
    val transport: String = AssistantMcpServer.STDIO,
    val command: String = "",
    val args: String = "",
    val url: String = "",
    val pairs: List<Pair<String, String>> = emptyList(),
) {
    val stdio: Boolean get() = transport == AssistantMcpServer.STDIO

    companion object {
        fun of(server: AssistantMcpServer?): McpServerForm = if (server == null) McpServerForm() else McpServerForm(
            name = server.name,
            transport = server.transport,
            command = if (server.stdio) server.command else "",
            args = if (server.stdio) server.args.joinToString("\n") else "",
            url = if (server.stdio) "" else server.url,
            pairs = server.pairs.toList(),
        )
    }
}

/** What a saved value's field says: typed fresh, kept while blank, or to type again for a moved URL. */
fun mcpValuePlaceholder(saved: Boolean, keepsSaved: Boolean): String = when {
    !saved -> "value"
    keepsSaved -> "•••••• saved — blank keeps it"
    else -> "saved for the old URL — type it again"
}

/**
 * Whether [form]'s saved values still apply: the transport is the server's own and, for HTTP, the
 * URL still points at the same origin (a header saved for one origin is never sent to another).
 */
fun mcpKeepsSaved(server: AssistantMcpServer?, form: McpServerForm): Boolean {
    if (server == null || server.transport != form.transport) return false
    return form.stdio || sameOrigin(server.url, form.url)
}

sealed interface McpServerCheck {
    data class Ok(val server: AssistantMcpServer) : McpServerCheck

    data class Problem(val error: String) : McpServerCheck
}

/**
 * The dialog's Done (`AssistantMcpServerDialog.done`): the server's own checks, a name another
 * server already uses, then a variable or header with no value that has none saved to keep.
 */
fun checkMcpServer(server: AssistantMcpServer?, form: McpServerForm, savedKeys: Set<String>, takenNames: Set<String>): McpServerCheck {
    val name = form.name.trim()
    assistantMcpNameError(name)?.let { return McpServerCheck.Problem("MCP server 1: $it") }
    val at = "MCP server \"$name\""
    val values = LinkedHashMap<String, String>()
    for ((key, value) in form.pairs) if (key.trim().isNotEmpty()) values[key.trim()] = value
    val built = if (form.stdio) {
        val command = form.command.trim()
        if (command.isEmpty()) return McpServerCheck.Problem("$at: command is required")
        if (command.length > MCP_COMMAND_MAX) return McpServerCheck.Problem("$at: command is too long")
        val args = form.args.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (args.size > MCP_ARGS_MAX || args.any { it.length > MCP_ARG_MAX }) {
            return McpServerCheck.Problem("$at: arguments must be at most $MCP_ARGS_MAX pieces of text")
        }
        AssistantMcpServer(server?.id.orEmpty(), name, server?.enabled ?: true, AssistantMcpServer.STDIO, command = command, args = args, pairs = values)
    } else {
        val url = form.url.trim()
        val scheme = try { URI(url).takeIf { it.host != null }?.scheme?.lowercase(Locale.ROOT) } catch (_: Exception) { null }
        if (scheme != "http" && scheme != "https") return McpServerCheck.Problem("$at: URL must be an http:// or https:// address")
        AssistantMcpServer(server?.id.orEmpty(), name, server?.enabled ?: true, AssistantMcpServer.HTTP, url = url, pairs = values)
    }
    val pairsWhat = if (form.stdio) "variables" else "headers"
    if (values.size > MCP_PAIRS_MAX) return McpServerCheck.Problem("$at: at most $MCP_PAIRS_MAX $pairsWhat")
    val keyRe = if (form.stdio) ENV_KEY_RE else HEADER_KEY_RE
    val keyWhat = if (form.stdio) "variable name" else "header name"
    for ((key, value) in values) {
        if (!keyRe.matches(key)) return McpServerCheck.Problem("$at: \"$key\" is not a valid $keyWhat")
        if (value.length > MCP_VALUE_MAX) return McpServerCheck.Problem("$at: $key is too long")
        if (!form.stdio && (value.contains('\r') || value.contains('\n'))) return McpServerCheck.Problem("$at: $key cannot contain a line break")
    }
    if (foldMcpName(name) in takenNames) return McpServerCheck.Problem("Another server is already named \"$name\"")
    val keeps = mcpKeepsSaved(server, form)
    val urlMoved = !form.stdio && server != null && !server.stdio && !sameOrigin(server.url, form.url)
    values.entries.firstOrNull { (key, value) -> value.isEmpty() && !(keeps && key in savedKeys) }?.let { (key, _) ->
        val moved = if (urlMoved && key in savedKeys) ": the URL now points at a different server, so the saved value is not sent there" else ""
        return McpServerCheck.Problem("$key needs a value$moved")
    }
    return McpServerCheck.Ok(built)
}

/** The instructions with [content] added as a line of its own (`appendToInstructions`). */
fun appendToInstructions(instructions: String, content: String): String {
    val base = instructions.trimEnd()
    return if (base.isEmpty()) content else "$base\n$content"
}

/** What `manager_template` is stored as for [text]: empty when it is the built-in template. */
fun templateToStore(text: String, builtIn: String): String = if (text.trim() == builtIn.trim()) "" else text

/** A 24-hour "HH:MM" (`isClockTime`). */
fun isClockTime(text: String): Boolean = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$").matches(text)

/** The read-only line naming the zone the digest and quiet hours are read in (`zoneLine`). */
fun zoneLine(saved: String?, device: String?): String {
    if (saved.isNullOrEmpty()) return "Times are in the server's time zone until you save one of them here."
    val base = "Times are in $saved (this device's zone when you saved)"
    return if (!device.isNullOrEmpty() && device != saved) "$base. This device is on $device; saving again switches to it." else "$base."
}

/** "1,400 / 4,000 characters". */
fun notebookMeterLabel(used: Int, cap: Int): String = String.format(Locale.US, "%,d / %,d characters", used, cap)

private val SHORT_DATE = DateTimeFormatter.ofPattern("dd/MM", Locale.UK)

/** "10/11" for [epochMs] in this device's zone; empty for none. */
fun shortDate(epochMs: Long?, zone: ZoneId = ZoneId.systemDefault()): String =
    if (epochMs == null || epochMs <= 0) "" else SHORT_DATE.format(Instant.ofEpochMilli(epochMs).atZone(zone))

/** "You · 04/11" or "Assistant · 10/11". */
fun noteSourceLabel(source: String, updatedAt: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val date = shortDate(updatedAt, zone)
    return (if (source == "user") "You" else "Assistant") + if (date.isEmpty()) "" else " · $date"
}

/** `{title} · since {date}`, the label a main-session choice carries. */
fun mainSessionLabel(title: String, createdAt: Long?, zone: ZoneId = ZoneId.systemDefault()): String {
    val since = shortDate(createdAt, zone)
    return title.ifEmpty { "Untitled session" } + if (since.isEmpty()) "" else " · since $since"
}

/** One project of the manager table; [registered] false for a manager whose project was removed. */
data class ManagerTableRow(val projectName: String, val manager: ManagerChatSummary?, val registered: Boolean)

/** Every registered project with its manager, then any manager whose project is gone (`managerTableRows`). */
fun managerTableRows(projects: List<String>, managers: List<ManagerChatSummary>): List<ManagerTableRow> {
    val byProject = managers.associateBy { it.projectName }
    val rows = projects.filter { it != com.hienlh.ppm.protocol.ASSISTANT_PROJECT_NAME }
        .map { ManagerTableRow(it, byProject[it], registered = true) }
        .toMutableList()
    val listed = rows.map { it.projectName }.toSet()
    managers.filter { it.projectName !in listed }.forEach { rows += ManagerTableRow(it.projectName, it, registered = false) }
    return rows
}

/** The modes a new manager chat may start in, as the composer chip names them. */
val MANAGER_MODE_OPTIONS: List<Pair<String, String>> = listOf(
    "plan" to "Plan", "default" to "Ask", "acceptEdits" to "Accept edits", "bypassPermissions" to "Bypass permissions",
)

fun managerModeLabel(mode: String): String = MANAGER_MODE_OPTIONS.firstOrNull { it.first == mode }?.second ?: mode

/** The title a new manager chat gets when nobody names it (`defaultManagerTitle`). */
fun defaultManagerTitle(projectName: String): String {
    val name = projectName.replace(Regex("[\\x00-\\x1f\\x7f\\s]+"), " ").trim()
    val text = "QL $name"
    val points = text.codePoints().limit(60).toArray()
    return String(points, 0, points.size).trim()
}

const val BYPASS_DEFAULT_CONSEQUENCE =
    "New manager chats will run without approval prompts, and PPM Assistant messages them without asking"
const val RULES_GUARD_HINT = "PPM guards only .ppm/manager.md. Files it points to are the manager's own working files."
