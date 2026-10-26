package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * How the AI's tab tools reach this device (src/shared/tab-open-protocol.ts): the server sends
 * `tab_open` on the chat socket to the device that sent the turn's message (or, when that one is
 * gone, to every device showing the chat) and waits for the first `tab_open_result` — sent once
 * the tab is open and, for a page the AI made, once the page has loaded and been checked.
 * Neither message is replayed: a reconnecting device must not open the tab again.
 */

const val OPEN_URL_TOOL = "open_url"
const val RUN_IN_TERMINAL_TOOL = "run_in_terminal"
const val OPEN_QUERY_TOOL = "open_query"

/** An HTML page `open_preview` shows: check it once it has loaded, with an image when [screenshot]. */
data class TabOpenCheck(val screenshot: Boolean)

/** What the AI asked this device to open. Every one is answered, whatever it is. */
sealed interface TabOpenRequest {
    val requestId: String

    /** `open_file` / `open_preview`. */
    data class File(
        override val requestId: String,
        val tool: String,
        /** As a PPM tab names it: relative to [projectName]'s folder, or absolute outside it. */
        val filePath: String,
        val projectName: String?,
        /** The 1-based line to show; the file opens as code. */
        val line: Int? = null,
        val check: TabOpenCheck? = null,
    ) : TabOpenRequest

    /** `open_query`: a Query tab holding a script (`DbQueryTabOpen`), which the user runs. */
    data class Query(override val requestId: String, val query: JsonObject) : TabOpenRequest

    /** `open_url`: a web server running on the host, in a web-preview tab. */
    data class Url(
        override val requestId: String,
        /** What the tab loads: a forward's address, or the server's own when [via] is `local`. */
        val url: String,
        /** The server's port on the host. */
        val port: Int,
        /** `tailscale`, `cloudflare` or `local`; `local` reaches the server only from the host itself. */
        val via: String,
    ) : TabOpenRequest

    /** `run_in_terminal`: a terminal the server started, the AI's command typed at its prompt. */
    data class Terminal(
        override val requestId: String,
        val terminalId: String,
        val projectName: String?,
        /** Where the shell started. */
        val cwd: String,
    ) : TabOpenRequest

    /** A tool this app does not know, or a request without what its tool needs. */
    data class Other(override val requestId: String, val tool: String?) : TabOpenRequest

    companion object {
        /** Null without a request id: there is nothing to answer. */
        fun from(json: JsonElement): TabOpenRequest? {
            val id = json["requestId"].string ?: return null
            val tool = json["tool"].string
            val other = Other(id, tool)
            return when (tool) {
                "open_file", "open_preview" -> File(
                    requestId = id,
                    tool = tool,
                    filePath = json["filePath"].string?.takeIf { it.isNotEmpty() } ?: return other,
                    projectName = json["projectName"].string,
                    line = json["line"].int?.takeIf { it >= 1 },
                    check = json["check"]?.takeIf { it is JsonObject }?.let { TabOpenCheck(it["screenshot"].bool == true) },
                )
                OPEN_QUERY_TOOL -> (json["query"] as? JsonObject)?.let { Query(id, it) } ?: other
                OPEN_URL_TOOL -> Url(
                    requestId = id,
                    url = json["url"].string ?: return other,
                    port = json["port"].int ?: return other,
                    via = json["via"].string ?: return other,
                )
                RUN_IN_TERMINAL_TOOL -> Terminal(
                    requestId = id,
                    terminalId = json["terminalId"].string ?: return other,
                    projectName = json["projectName"].string,
                    cwd = json["cwd"].string ?: "",
                )
                else -> other
            }
        }
    }
}

/** How a `tab_open` went, before it is sent back under the request's id. */
data class TabOpenAnswer(
    /** False when the device could not open the tab. */
    val opened: Boolean,
    /** Why the tab could not open, or why a check that was asked for did not run. */
    val error: String? = null,
    val report: CanvasCheckReport? = null,
)
