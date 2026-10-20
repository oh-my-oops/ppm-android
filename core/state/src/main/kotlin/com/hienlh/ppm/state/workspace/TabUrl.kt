package com.hienlh.ppm.state.workspace

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * use-url-sync.ts: the web keeps the open project and tab in its address,
 * `/project/{name}/{type}/{identifier}`, and opens a tab from it at start and on the browser's
 * Back. The app has no address bar; it reads the same address out of a `ppm://` link and
 * rebuilds a tab it showed earlier from it, for Android's Back.
 */
data class UrlState(
    val projectName: String?,
    val tabType: String?,
    val tabIdentifier: String?,
    /** The legacy `?openChat=` parameter: a chat to open, `provider/session` or a session alone. */
    val openChat: String? = null,
)

/** The tab types an address can name (`VALID_TAB_TYPES`). */
val URL_TAB_TYPES = setOf(
    "terminal", "chat", "editor", "database", "db-structure", "db-sql", "sqlite",
    "git-diff", "settings",
    "extension", "group", "design",
)

private val PROJECT_PATH = Regex("""^/project/([^/]+)(?:/([^/]+)(/.*)?)?""")

/** `parseUrlState`: [path] as the address holds it, escapes and all. Only the project is decoded, as on the web. */
fun parseUrlState(path: String, openChat: String? = null): UrlState {
    val match = PROJECT_PATH.find(path) ?: return UrlState(null, null, null, openChat)
    val projectName = decodePathSegment(match.groupValues[1])
    val rawType = match.groups[2]?.value
    val rawIdentifier = match.groups[3]?.value?.substring(1)
    // Legacy: /project/{name}/tab/{tabId}
    if (rawType == "tab") return UrlState(projectName, null, null, openChat)
    return UrlState(projectName, rawType?.takeIf { it in URL_TAB_TYPES }, rawIdentifier, openChat)
}

/** `tabIdFromUrl`: the id the tab an address names has. */
fun tabIdFromUrl(type: String, identifier: String?): String = if (identifier.isNullOrEmpty()) type else "$type:$identifier"

private val SESSION_UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)

/**
 * `buildMetadataFromUrl`: what a tab opened from an address holds, or null when the address
 * names nothing to open. Database tabs are read back with `dbTabMetadataFromId`, which comes with
 * the database phase; until then they are not opened from an address.
 */
fun buildMetadataFromUrl(type: String, identifier: String?, projectName: String): JsonObject? {
    val id = identifier?.takeIf { it.isNotEmpty() }
    return when (type) {
        "editor", "git-diff", "sqlite" -> id?.let { buildJsonObject { put("filePath", it); put("projectName", projectName) } }
        "chat" -> {
            if (id == null) return null
            val slash = id.indexOf('/')
            val rawSessionId = if (slash == -1) id else id.substring(slash + 1)
            val providerId = if (slash == -1) null else id.substring(0, slash)
            buildJsonObject {
                // Short or random ids from tab derivation must not be used as session ids.
                if (SESSION_UUID.matches(rawSessionId)) put("sessionId", rawSessionId)
                if (!providerId.isNullOrEmpty()) put("providerId", providerId)
                put("projectName", projectName)
            }
        }
        "terminal" -> {
            // `parseInt(identifier ?? "1", 10)`: the leading digits.
            val index = Regex("""^\s*([+-]?\d+)""").find(identifier ?: "1")?.groupValues?.get(1)?.toIntOrNull() ?: return null
            buildJsonObject { put("terminalIndex", index); put("projectName", projectName) }
        }
        "settings" -> EMPTY_OBJECT
        "extension" -> id?.let { buildJsonObject { put("viewType", it); put("projectName", projectName) } }
        "group" -> id?.let { buildJsonObject { put("groupId", it); put("projectName", projectName) } }
        // `designTabMetadata`: the design tab resolves its provider itself, so it is pending.
        "design" -> id?.takeIf(::isValidDesignSlug)?.let {
            buildJsonObject { put("projectName", projectName); put("designSlug", it); put("providerPending", true) }
        }
        else -> null
    }
}

/** `buildTitleFromUrl`: the title a tab opened from an address starts with. */
fun buildTitleFromUrl(type: String, identifier: String?): String = when (type) {
    "editor" -> identifier?.substringAfterLast('/') ?: "File"
    "chat" -> "Chat"
    "terminal" -> "Terminal ${identifier ?: "1"}"
    "git-diff" -> identifier?.substringAfterLast('/') ?: "Diff"
    "settings" -> "Settings"
    "sqlite" -> identifier?.substringAfterLast('/') ?: "SQLite"
    // "git-graph" → "Git Graph"
    "extension" -> identifier?.split("-")?.joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) } ?: "Extension"
    "group" -> "Group"
    "design" -> "Design"
    else -> type
}

private val DESIGN_SLUG = Regex("^[a-z0-9][a-z0-9-]{0,62}$")

/** `isValidDesignSlug`: one safe path segment; `systems` is the apps folder. */
fun isValidDesignSlug(value: String): Boolean = DESIGN_SLUG.matches(value) && value != "systems"

/**
 * The tab [tabId] was, rebuilt from its address as the web's Back rebuilds a tab closed since —
 * only when it comes back under the same id: a chat that never had a session would come back as
 * yet another new chat each time.
 */
fun reopenedTab(tabId: String, projectName: String): NewTab? {
    // The panel suffix (`@panel-…`) is not part of an address.
    val clean = tabId.substringBefore('@')
    val type = clean.substringBefore(':')
    if (type !in URL_TAB_TYPES) return null
    val identifier = if (':' in clean) clean.substringAfter(':') else null
    val metadata = buildMetadataFromUrl(type, identifier, projectName) ?: return null
    if (deriveTabId(type, metadata) { "\u0000" } != clean) return null
    return NewTab(type, buildTitleFromUrl(type, identifier), projectId = projectName.takeIf { type != "settings" }, metadata = metadata)
}

/** A `ppm://<host>[:<port>]/project/{name}/{type}/{id}` link: the server it is for, and the web's address on it. */
data class PpmLink(val host: String, val port: Int?, val state: UrlState)

/**
 * Reads a `ppm://` link; null for anything else. Unlike the web's own address, the tab's part is
 * decoded too: a link is written by hand or by another app, which escapes a space or a `#` in a
 * file's name, where the web puts the name in its address as it is.
 */
fun parsePpmLink(link: String): PpmLink? {
    if (!link.startsWith("ppm://", ignoreCase = true)) return null
    val rest = link.substring("ppm://".length).substringBefore('#')
    val authority = rest.takeWhile { it != '/' && it != '?' }
    val path = rest.substring(authority.length).substringBefore('?')
    val query = rest.substring(authority.length).substringAfter('?', "")
    val hostPort = authority.substringAfterLast('@')
    val (host, port) = if (hostPort.startsWith("[")) {
        // An IPv6 address: `[fd7a::1]:8080`.
        hostPort.substringAfter('[').substringBefore(']') to hostPort.substringAfter("]", "").removePrefix(":")
    } else {
        hostPort.substringBefore(':') to hostPort.substringAfter(':', "")
    }
    if (host.isEmpty()) return null
    val openChat = query.split('&').firstOrNull { it.substringBefore('=') == "openChat" }
        ?.substringAfter('=', "")?.let { decodeQueryValue(it) }?.takeIf { it.isNotEmpty() }
    val state = parseUrlState(path, openChat).let { it.copy(tabIdentifier = it.tabIdentifier?.let(::decodePathSegment)) }
    return PpmLink(host.lowercase(), port.toIntOrNull(), state)
}

/** `decodePathSegment`: `decodeURIComponent`, or the segment as it is when that would throw. */
fun decodePathSegment(segment: String): String = decodeUriComponent(segment) ?: segment

/** `URLSearchParams` reads `+` as a space. */
private fun decodeQueryValue(value: String): String = value.replace('+', ' ').let { decodeUriComponent(it) ?: it }

/** `decodeURIComponent`: null where it throws (a `%` that is not an escape, or escapes that are not UTF-8). */
private fun decodeUriComponent(text: String): String? {
    if ('%' !in text) return text
    val out = StringBuilder()
    val bytes = ByteArrayOutputStream()
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    fun flush(): Boolean {
        if (bytes.size() == 0) return true
        try {
            out.append(decoder.decode(ByteBuffer.wrap(bytes.toByteArray())))
        } catch (_: CharacterCodingException) {
            return false
        }
        bytes.reset()
        return true
    }
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '%') {
            if (i + 3 > text.length) return null
            val high = hexDigit(text[i + 1])
            val low = hexDigit(text[i + 2])
            if (high < 0 || low < 0) return null
            bytes.write(high * 16 + low)
            i += 3
        } else {
            if (!flush()) return null
            out.append(c)
            i++
        }
    }
    return if (flush()) out.toString() else null
}

private fun hexDigit(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}
