package com.hienlh.ppm.protocol

import com.hienlh.ppm.protocol.generated.TurnReference
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLDecoder
import java.net.URLEncoder

/** One turn of a chat session, as a turn link names it (`TurnLinkTarget` in `turn-link.ts`). */
data class TurnLinkTarget(
    val projectName: String,
    /** `claude` or `codex`. */
    val providerId: String,
    val sessionId: String,
    val turnId: String,
) {
    /** The turn's stable key, for dedupe: one chip per turn (`turnKey`). */
    val key: String get() = turnKey(providerId, sessionId, turnId)
}

/** A turn link found in pasted text, and the link exactly as it appeared there. */
data class ParsedTurnLink(val target: TurnLinkTarget, val text: String)

/** What `parseTurnLinks` answers: the links in order, no repeats, and the text without them. */
data class TurnLinkScan(val links: List<ParsedTurnLink>, val rest: String)

fun turnKey(providerId: String, sessionId: String, turnId: String): String = "$providerId/$sessionId/$turnId"

/** A reference's key, matching [TurnLinkTarget.key]. */
val TurnReference.key: String get() = turnKey(providerId, sessionId, turnId)

/**
 * Turn links (`turn-link.ts`): the URL of a chat session with `?turn=<turnId>`, as Copy turn link
 * writes it and as a composer recognises it on paste.
 *
 * Recognition goes by the path alone: the origin a link was copied from — localhost, a tunnel, a
 * Tailscale name — says nothing about which PPM it belongs to. Every decoded segment is checked
 * against the shapes the server accepts, and only a project this PPM has counts; a link that
 * fails any check stays the text it was.
 */
object TurnLinks {
    private val CANDIDATE = Regex("""(?<=^|[\s(<\[{"'`])(?:https?://[^\s/?#]+)?/project/\S+""")
    private val PATH = Regex("""^(?:https?://[^/?#]+)?/project/([^/?#]+)/chat/(claude|codex)/([^/?#]+)/?$""")
    private val TRAILING = Regex("""[.,;:!?)\]}>'"`]+$""")
    private val CONTROL = Regex("[\\u0000-\\u001f\\u007f]")
    private const val MAX_PROJECT_NAME = 200

    /** `encodeURIComponent`: what a browser writes into a path segment or a query value. */
    fun encodeComponent(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
        .replace("+", "%20")
        .replace("%21", "!").replace("%27", "'").replace("%28", "(").replace("%29", ")").replace("%7E", "~")

    private fun decodeComponent(segment: String): String? = try {
        // `URLDecoder` reads `+` as a space, which `decodeURIComponent` does not.
        URLDecoder.decode(segment.replace("+", "%2B"), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }

    /** The link for one turn; [origin] is the server's address as this device reaches it (`buildTurnLink`). */
    fun build(origin: String, target: TurnLinkTarget): String {
        val base = origin.trimEnd('/')
        return "$base/project/${encodeComponent(target.projectName)}/chat/${target.providerId}/${target.sessionId}?turn=${encodeComponent(target.turnId)}"
    }

    /** The `turn` value of a query string, as `URLSearchParams.get` reads it. */
    private fun turnParam(query: String): String? {
        for (part in query.split('&')) {
            val eq = part.indexOf('=')
            val name = if (eq < 0) part else part.substring(0, eq)
            if (decodeComponent(name.replace("+", " ")) != "turn") continue
            val raw = if (eq < 0) "" else part.substring(eq + 1)
            return decodeComponent(raw.replace("+", "%20"))
        }
        return null
    }

    /** One candidate, checked; null when it is not a turn link this PPM can show. */
    private fun parseOne(raw: String, isProject: (String) -> Boolean): ParsedTurnLink? {
        val text = raw.replace(TRAILING, "")
        val hashAt = text.indexOf('#')
        val beforeHash = if (hashAt < 0) text else text.substring(0, hashAt)
        val queryAt = beforeHash.indexOf('?')
        if (queryAt < 0) return null
        val path = PATH.matchEntire(beforeHash.substring(0, queryAt)) ?: return null
        val turnId = turnParam(beforeHash.substring(queryAt + 1))
        val projectName = decodeComponent(path.groupValues[1])
        val sessionId = decodeComponent(path.groupValues[3])
        if (turnId.isNullOrEmpty() || !FromSessionReference.TURN_ID_RE.matches(turnId)) return null
        if (sessionId == null || !FromSessionReference.SESSION_ID_RE.matches(sessionId)) return null
        if (projectName.isNullOrEmpty() || projectName.codePointCount(0, projectName.length) > MAX_PROJECT_NAME || CONTROL.containsMatchIn(projectName)) return null
        if (!isProject(projectName)) return null
        return ParsedTurnLink(TurnLinkTarget(projectName, path.groupValues[2], sessionId, turnId), text)
    }

    /**
     * Every turn link in [text], in order and without repeats, and the text with those links taken
     * out (`parseTurnLinks`). Anything that is not a valid turn link stays exactly where it was, and
     * so does a valid one [take] declines (one the composer has no room left to show as a chip).
     */
    fun parse(text: String, isProject: (String) -> Boolean, take: (ParsedTurnLink) -> Boolean = { true }): TurnLinkScan {
        val links = ArrayList<ParsedTurnLink>()
        val seen = HashSet<String>()
        val rest = StringBuilder()
        var last = 0
        for (match in CANDIDATE.findAll(text)) {
            val link = parseOne(match.value, isProject) ?: continue
            if (!take(link)) continue
            val start = match.range.first
            rest.append(text, last, start)
            last = start + link.text.length
            if (!seen.add(link.target.key)) continue
            links += link
        }
        rest.append(text, last, text.length)
        return TurnLinkScan(links, rest.toString())
    }

    /**
     * The text a paste should insert once its turn links are taken as chips, or null when the
     * composer took none of them (the paste then goes in as it is). [addLinks] adds the chips and
     * answers the links it had no room for, which stay in the text (`pasteWithoutTurnLinks`).
     */
    fun pasteWithout(text: String, isProject: (String) -> Boolean, addLinks: (List<TurnLinkTarget>) -> List<TurnLinkTarget>): String? {
        val links = parse(text, isProject).links
        if (links.isEmpty()) return null
        val overflow = addLinks(links.map { it.target }).map { it.key }.toSet()
        if (overflow.size == links.size) return null
        val rest = parse(text, isProject) { it.target.key !in overflow }.rest
        // A paste that was only links (and the whitespace around them) inserts nothing.
        return if (rest.isBlank()) "" else rest
    }

    /**
     * The reference a chip sends, built from the link and the server's answer about its turn
     * (`turnRefFromMeta`): `GET …/turns/:turnId` answers `{ sessionId, n, startedAt, prompt, sessionTitle }`.
     */
    fun refFromMeta(link: TurnLinkTarget, meta: JsonElement?): TurnReference? {
        val prompt = meta["prompt"].string?.split("\n", limit = 2)?.first()?.jsTrim().orEmpty()
        val title = meta["sessionTitle"].string?.jsTrim()?.takeIf { it.isNotEmpty() } ?: prompt.ifEmpty { "Untitled chat" }
        // The id that owns the transcript: a Codex session renamed since the link was copied.
        val sessionId = meta["sessionId"].string?.takeIf { FromSessionReference.SESSION_ID_RE.matches(it) } ?: link.sessionId
        val n = meta["n"]
        val at = meta["startedAt"]
        return ChatTurnRefs.validate(
            buildJsonObject {
                put("version", 1)
                put("projectName", link.projectName)
                put("providerId", link.providerId)
                put("sessionId", sessionId)
                put("turnId", link.turnId)
                put("n", (n as? JsonPrimitive) ?: JsonPrimitive(null as Number?))
                put("title", clip(title, 200))
                put("prompt", clip(prompt, 300))
                put("at", (at as? JsonPrimitive) ?: JsonPrimitive(null as String?))
            },
        )
    }

    /** The first [max] code points (`Array.from(text).slice(0, max)`). */
    private fun clip(text: String, max: Int): String {
        if (text.codePointCount(0, text.length) <= max) return text
        return text.substring(0, text.offsetByCodePoints(0, max))
    }
}
