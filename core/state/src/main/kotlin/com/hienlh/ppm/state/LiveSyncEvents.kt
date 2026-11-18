package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonElement

/** A chat renamed anywhere, as `sessions:list_changed` carries it (`RenamedChat`). */
data class RenamedChat(val sessionId: String, val title: String) {
    companion object {
        /** The `renamed` field as it came off the wire, or null when it is absent or malformed (`parseRenamedChat`). */
        fun from(raw: JsonElement?): RenamedChat? {
            val sessionId = raw["sessionId"].string?.takeIf { it.isNotEmpty() } ?: return null
            val title = raw["title"].string?.takeIf { it.isNotBlank() } ?: return null
            return RenamedChat(sessionId, title)
        }
    }
}

/** The sections `settings:changed` names (`SETTINGS_EVENT_SECTIONS`); only these are ever announced. */
object SettingsSections {
    val ALL = setOf(
        "theme", "themes", "ai", "keybindings", "proxy", "query-audit", "session-trace", "files",
        "notifications", "telegram", "clawbot", "mcp", "accounts",
    )

    private val CLIENT_ID = Regex("^[A-Za-z0-9-]{8,64}$")

    /** A page id as sent in the settings header, or null when it is missing or malformed (`settingsClientIdFrom`). */
    fun clientIdFrom(raw: String?): String? = raw?.takeIf { CLIENT_ID.matches(it) }
}

/** A set of listeners, each added with the function that removes it. */
internal class Listeners<T> {
    private val set = LinkedHashSet<T>()

    fun add(listener: T): () -> Unit {
        set += listener
        return { set -= listener }
    }

    fun each(call: (T) -> Unit) = set.toList().forEach(call)
}

/**
 * The `/ws/global` broadcasts that ask an open app to read something again because it changed
 * elsewhere — another device, the `ppm` CLI, the Assistant (`use-global-events.ts`).
 */
internal class LiveSyncListeners {
    val sessionsList = Listeners<(String, RenamedChat?) -> Unit>()
    val projects = Listeners<() -> Unit>()
    val settings = Listeners<(String, String?) -> Unit>()
    val dbConnections = Listeners<() -> Unit>()
    val extensions = Listeners<() -> Unit>()
    val designList = Listeners<(String) -> Unit>()

    fun handle(frame: JsonElement) {
        when (frame["type"].string) {
            "sessions:list_changed" -> {
                val project = frame["projectName"].string?.takeIf { it.isNotEmpty() } ?: return
                val renamed = RenamedChat.from(frame["renamed"])
                sessionsList.each { it(project, renamed) }
            }
            "projects:changed" -> projects.each { it() }
            "settings:changed" -> {
                val section = frame["section"].string?.takeIf { it in SettingsSections.ALL } ?: return
                val clientId = SettingsSections.clientIdFrom(frame["clientId"].string)
                settings.each { it(section, clientId) }
            }
            "db:connections_changed" -> dbConnections.each { it() }
            "extensions:changed" -> extensions.each { it() }
            "design:list_changed" -> {
                val project = frame["projectName"].string?.takeIf { it.isNotEmpty() } ?: return
                designList.each { it(project) }
            }
        }
    }
}
