package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement

/** One sign-in to an MCP server, as `/api/mcp-auth` reports it (`McpAuthFlow`). */
data class McpAuthFlow(
    val id: String,
    val serverName: String,
    val status: Status,
    /** Open this to sign in. Absent once the flow has settled. */
    val authUrl: String? = null,
    /** False when nothing redirects back: the user says when they are done instead. */
    val callbackExpected: Boolean = false,
    val redirectScheme: RedirectScheme? = null,
    val error: String? = null,
) {
    enum class Status(val wire: String) { WAITING("waiting"), COMPLETING("completing"), DONE("done"), FAILED("failed"), EXPIRED("expired"), CANCELLED("cancelled") }

    /** How the authorization server returns to whoever is waiting for it. */
    enum class RedirectScheme(val wire: String) {
        /** To the CLI's own listener on the host's loopback, which only that machine can reach. */
        LOCALHOST("localhost"),

        /** To PPM's public callback, at the https address this app is connected through. */
        CUSTOM("custom"),
    }

    /** Still waiting for the user, or for the CLI to finish with what they gave it. */
    val isOpen: Boolean get() = status == Status.WAITING || status == Status.COMPLETING

    companion object {
        fun from(json: JsonElement?): McpAuthFlow? {
            val id = json["id"].string ?: return null
            val status = Status.entries.firstOrNull { it.wire == json["status"].string } ?: return null
            return McpAuthFlow(
                id = id,
                serverName = json["serverName"].string.orEmpty(),
                status = status,
                authUrl = json["authUrl"].string?.ifEmpty { null },
                callbackExpected = json["callbackExpected"].bool ?: false,
                redirectScheme = RedirectScheme.entries.firstOrNull { it.wire == json["redirectScheme"].string },
                error = json["error"].string?.ifEmpty { null },
            )
        }
    }
}

object McpServerName {
    /**
     * A plugin's server is `plugin:<plugin>:<server>`: the bar shows the last part, and keeps the
     * whole name for the tooltip (`shortMcpServerName`).
     */
    fun short(name: String): String {
        if (!name.startsWith("plugin:")) return name
        return name.substringAfterLast(':').ifEmpty { name }
    }

    /**
     * A connector added on claude.ai reaches Claude through the account, named `claude.ai <display
     * name>`: it is connected there, not through a flow here (`isClaudeAiConnector`).
     */
    fun isClaudeAiConnector(name: String, scope: String? = null): Boolean = scope == "claudeai" || name.startsWith("claude.ai ")

    const val CLAUDE_AI_CONNECTORS_URL = "https://claude.ai/settings/connectors"
}

/**
 * The servers whose sign-in bar was hidden, a UI preference every device shares
 * (`mcpSignInDismissed`): hidden per server, not per chat, so a plugin's dozen unused connectors
 * stay quiet in every new chat.
 */
object McpSignInDismissals {
    /** The server's bound on the list, and on each name. */
    const val LIMIT = 200

    /** The list as stored, or null when it is not one (`sanitizeNameList`). */
    fun sanitized(json: JsonElement?): List<String>? {
        val items = json.array ?: return null
        return items.mapNotNull { it.string }.filter { it.isNotEmpty() && it.length <= LIMIT }.takeLast(LIMIT)
    }

    /** [names] added after the ones already hidden, the oldest dropped past the limit. */
    fun dismissing(names: List<String>, current: List<String>): List<String> =
        (current + names.filter { it !in current }).takeLast(LIMIT)

    /** Without [name], or null when it was not hidden and nothing changes. */
    fun undismissing(name: String, current: List<String>): List<String>? =
        if (name in current) current.filter { it != name } else null
}
