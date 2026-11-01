package com.hienlh.ppm.state.terminal

import com.hienlh.ppm.protocol.isJsSpace
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

/** Pure text helpers from use-terminal.ts, terminal-selection-chat.tsx and terminal-tab.tsx. */
object TerminalText {
    /** Text already translated from terminal cells, without unused right-hand padding. */
    data class BufferLine(val text: String, val isWrapped: Boolean = false)
    data class ChatText(val label: String, val text: String)
    /** Null [data] means send nothing; [ctrlMode] is the next sticky-Ctrl state. */
    data class KeyInput(val data: String?, val ctrlMode: Boolean)

    // The web includes the cursor row and the 5,000 rows preceding it.
    private const val URL_SCAN_MAX_ROWS = 5000
    // Java's \b and \s differ from JavaScript's for Unicode; spell out the JS sets.
    private val urlPattern = Regex("""(?<![A-Za-z0-9_])https?://[^\x09-\x0D\x20\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF<>"'`]+""")
    private const val URL_TRAILING_PUNCT = ").,;:'\"]}>"
    private val leadingBlankLines = Regex("""^(?:[ \t]*\r?\n)+""")
    private val backticks = Regex("`+")
    private val loopbackIpv4 = Regex("""127\.\d{1,3}\.\d{1,3}\.\d{1,3}""")

    /** HTTP(S) links in one string, in reading order, with the web's trailing punctuation removed. */
    fun extractUrls(text: String): List<String> = urlPattern.findAll(text).map {
        it.value.trimEnd { char -> char in URL_TRAILING_PUNCT }
    }.toList()

    /**
     * Oldest-to-newest logical lines in; unique links in reverse discovery order out.
     * Like the web, keeps the FIRST occurrence before reversing, so a repeated URL does not move.
     * Scans only the last 5,001 logical lines; use [bufferUrlsFromRows] for a physical-row bound.
     */
    fun bufferUrls(logicalLines: List<String>): List<String> = collectUrls(
        logicalLines.subList((logicalLines.size - URL_SCAN_MAX_ROWS - 1).coerceAtLeast(0), logicalLines.size),
    )

    /**
     * [BufferLine.isWrapped] means continuation of the previous row. Joins only such breaks,
     * through [cursorRow] inclusive. Rows before the scan window or after the cursor are ignored.
     */
    fun bufferUrlsFromRows(rows: List<BufferLine>, cursorRow: Int = rows.lastIndex): List<String> {
        val end = cursorRow.coerceAtMost(rows.lastIndex)
        if (end < 0) return emptyList()
        val logical = mutableListOf<String>()
        val current = StringBuilder()
        for (i in (end - URL_SCAN_MAX_ROWS).coerceAtLeast(0)..end) {
            if (!rows[i].isWrapped && current.isNotEmpty()) {
                logical += current.toString()
                current.setLength(0)
            }
            current.append(rows[i].text)
        }
        if (current.isNotEmpty()) logical += current.toString()
        return collectUrls(logical)
    }

    private fun collectUrls(lines: List<String>): List<String> {
        val seen = linkedSetOf<String>()
        for (line in lines) seen.addAll(extractUrls(line))
        return seen.toList().asReversed()
    }

    /** The selection action's context chip, including its exact heading and collision-safe fence. */
    fun selectionContext(selection: String): ChatText? {
        val text = leadingBlankLines.replaceFirst(selection, "").trimEnd(::isJsSpace)
        if (text.isEmpty()) return null
        val fence = "`".repeat(maxOf(2, backticks.findAll(text).maxOfOrNull { it.value.length } ?: 0) + 1)
        return ChatText("Terminal selection", "Selected text from the terminal\n$fence\n$text\n$fence")
    }

    /**
     * The toolbar draft: a nonempty selection wins verbatim, otherwise the prompt and output.
     * [command] and [output] are already read from the terminal; keeps the web's fixed bash fence.
     * A whitespace-only selection suppresses the output fallback, just as JS `selection || output`.
     */
    fun chatDraft(selection: String = "", command: String = "", output: String = ""): ChatText? {
        val body = selection.ifEmpty { output }
        if (body.all(::isJsSpace)) return null
        val transcript = if (selection.isEmpty() && command.isNotEmpty()) "$command\n$body" else body
        return ChatText(if (selection.isNotEmpty()) "Terminal selection" else "Terminal output", "```bash\n$transcript\n```")
    }

    /**
     * terminal-tab.tsx's sendKey: one UTF-16 unit consumes Ctrl, even when unmappable (no send).
     * Multi-unit input passes through with Ctrl still armed. Lowercase the STRING, as JS does:
     * e.g. capital dotted I becomes `i` + a combining dot, and maps from that first unit to ^I.
     */
    fun mobileKey(value: String, ctrlMode: Boolean): KeyInput {
        if (ctrlMode && value.length == 1) {
            val code = value.lowercase()[0].code - 96
            return KeyInput(if (code in 1..26) code.toChar().toString() else null, false)
        }
        return KeyInput(value, ctrlMode)
    }

    /** oauth-loopback-url.ts: hostname-only check; parsing/validating an address is separate. */
    fun isLoopbackHostname(hostname: String): Boolean {
        val host = hostname.removePrefix("[").removeSuffix("]").lowercase()
        return host == "localhost" || host == "::1" || loopbackIpv4.matches(host)
    }

    /** A pasted HTTP(S) callback addressed to this machine; does not resolve DNS or send anything. */
    fun isDeliverableCallback(url: String): Boolean {
        val parsed = parseUrl(url.trim(::isJsSpace)) ?: return false
        return parsed.scheme in setOf("http", "https") && isLoopbackHostname(parsed.host)
    }

    /** OAuth redirect_uri (or redirectUri) loopback port, with the web's 443/80 default. */
    fun loopbackRedirectPort(url: String): String? {
        val parsed = parseUrl(url) ?: return null
        val redirect = parsed.query.queryParameter("redirect_uri") ?: parsed.query.queryParameter("redirectUri") ?: return null
        val target = parseUrl(redirect) ?: return null
        if (!isLoopbackHostname(target.host)) return null
        return target.port ?: if (target.scheme == "https") "443" else "80"
    }

    private data class ParsedUrl(val scheme: String, val host: String, val port: String?, val query: HttpUrl)

    /** URL parsing only; HttpUrl supplies tolerant percent decoding and IPv6 canonicalization. */
    private fun parseUrl(value: String): ParsedUrl? {
        val text = value.trim { it <= ' ' }.filterNot { it == '\t' || it == '\r' || it == '\n' }
        val scheme = text.substringBefore(':', "").lowercase()
        if (scheme in setOf("http", "https", "ws", "wss", "ftp")) {
            // Keep the port separate: WHATWG permits port 0, which HttpUrl refuses. This also
            // preserves each scheme's default port when using HTTP to parse ws/wss/ftp.
            val rest = text.substringAfter(':').trimStart('/', '\\')
            val authority = rest.takeWhile { it !in "/\\?#" }
            val hostPort = authority.substringAfterLast('@')
            val hostEnd = if (hostPort.startsWith('[')) hostPort.indexOf(']') + 1
                else hostPort.indexOf(':').takeIf { it >= 0 } ?: hostPort.length
            if (hostEnd == 0) return null
            val rawHost = hostPort.substring(0, hostEnd)
            val suffix = hostPort.substring(hostEnd)
            if (suffix.isNotEmpty() && !suffix.startsWith(':')) return null
            val rawPort = suffix.removePrefix(":")
            val port = if (rawPort.isEmpty()) null else {
                if (rawPort.any { it !in '0'..'9' }) return null
                (rawPort.trimStart('0').ifEmpty { "0" }.toIntOrNull() ?: return null).also {
                    if (it > 65535) return null
                }
            }
            val transport = if (scheme == "https" || scheme == "wss") "https" else "http"
            val parsed = ("$transport://" + authority.dropLast(suffix.length) + rest.substring(authority.length)).toHttpUrlOrNull() ?: return null
            val host = if (rawHost.startsWith('[')) {
                // OkHttp collapses IPv4-mapped IPv6 to IPv4; browsers keep it an IPv6 host.
                parsed.host.takeIf { ':' in it } ?: rawHost
            } else browserIpv4Host(parsed.host) ?: return null
            val defaultPort = when (scheme) { "https", "wss" -> 443; "ftp" -> 21; else -> 80 }
            return ParsedUrl(scheme, host, port?.takeUnless { it == defaultPort }?.toString(), parsed)
        }
        // URL also accepts non-HTTP authorization/redirect schemes. Callback delivery still refuses them.
        return try {
            val uri = URI(text)
            if (!uri.isAbsolute || uri.port > 65535) return null
            if (uri.rawAuthority != null && uri.host == null) return null
            if (scheme == "file" && (uri.port >= 0 || uri.rawUserInfo != null)) return null
            val query = HttpUrl.Builder().scheme("http").host("localhost").encodedQuery(uri.rawQuery).build()
            var host = uri.host.orEmpty()
            if (host.startsWith('[')) host = ("http://$host/").toHttpUrlOrNull()?.host?.takeIf { ':' in it } ?: host
            if (scheme == "file" && host.equals("localhost", ignoreCase = true)) host = ""
            ParsedUrl(scheme, host, uri.port.takeIf { it >= 0 }?.toString(), query)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: java.net.URISyntaxException) {
            null
        }
    }

    /** WHATWG's numeric IPv4 hosts (127.1, integer/hex/octal forms); HttpUrl leaves them as names. */
    private fun browserIpv4Host(host: String): String? {
        val parts = host.removeSuffix(".").split('.')
        val last = parts.last()
        if (ipv4Number(last) == null && (last.isEmpty() || last.any { it !in '0'..'9' })) return host
        if (parts.size > 4) return null
        val numbers = parts.map { ipv4Number(it) ?: return null }
        if (numbers.dropLast(1).any { it > 255 } || numbers.last() >= (1L shl (8 * (5 - numbers.size)))) return null
        var address = numbers.last()
        for (i in 0 until numbers.lastIndex) address += numbers[i] shl (8 * (3 - i))
        return (3 downTo 0).joinToString(".") { ((address shr (8 * it)) and 255).toString() }
    }

    private fun ipv4Number(part: String): Long? {
        if (part.isEmpty()) return null
        val radix = when { part.startsWith("0x", true) -> 16; part.length > 1 && part[0] == '0' -> 8; else -> 10 }
        val digits = when (radix) { 16 -> part.drop(2); 8 -> part.drop(1); else -> part }
        if (digits.isEmpty()) return 0
        if (digits.any { it.digitToIntOrNull(radix) == null || it.code > 127 }) return null
        return digits.trimStart('0').ifEmpty { "0" }.toLongOrNull(radix)
    }
}
