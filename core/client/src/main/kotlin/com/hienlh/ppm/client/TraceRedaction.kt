package com.hienlh.ppm.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What the app takes out of a trace entry before it is queued, so a secret reaches neither the
 * queue file nor the server. The server redacts again on the way in (`redact-secrets.ts`); this
 * side adds the one thing only the app knows: the literal secrets it holds — every password it
 * has sent as a token (raw and as `?token=` escapes it), and any value [remember]ed by a feature
 * that handles one (a vault value, if the app ever shows one).
 */
class TraceRedactor {
    private val secrets = LinkedHashSet<String>()

    /** A secret to scrub wherever it appears. Shorter than [MIN_SECRET_CHARS] is left: replacing it would rewrite ordinary words. */
    fun remember(secret: String?) {
        if (secret == null || secret.length < MIN_SECRET_CHARS) return
        synchronized(secrets) {
            for (form in setOf(secret, ApiClient.escape(secret))) {
                secrets.remove(form)
                secrets.add(form)
            }
            while (secrets.size > MAX_SECRETS) secrets.remove(secrets.first())
        }
    }

    fun redact(text: String): String {
        var out = text
        val known = synchronized(secrets) { secrets.sortedByDescending { it.length } }
        for (secret in known) out = out.replace(secret, REDACTED)
        for ((pattern, replacement) in RULES) out = pattern.replace(out, replacement)
        return out
    }

    /** Every string inside [value], one at a time (the server's `redactDeep`). Keys are kept. */
    fun redact(value: JsonElement, depth: Int = 0): JsonElement = when {
        value is JsonPrimitive && value.isString -> JsonPrimitive(redact(value.content))
        depth > 8 -> value
        value is JsonArray -> JsonArray(value.map { redact(it, depth + 1) })
        value is JsonObject -> JsonObject(value.mapValues { (_, v) -> redact(v, depth + 1) })
        else -> value
    }

    fun redact(value: JsonObject): JsonObject = redact(value as JsonElement) as JsonObject

    companion object {
        const val REDACTED = "[REDACTED]"
        const val MIN_SECRET_CHARS = 4
        private const val MAX_SECRETS = 64

        /** The one the app uses: [ApiClient] remembers every token it is made with. */
        val Global = TraceRedactor()

        /** `redact-secrets.ts`'s rules, in its order. */
        private val RULES: List<Pair<Regex, String>> = listOf(
            Regex("Token:\\s*\\S+", RegexOption.IGNORE_CASE) to "Token: [REDACTED]",
            Regex("Bearer\\s+\\S+", RegexOption.IGNORE_CASE) to "Bearer [REDACTED]",
            Regex("password['\":\\s]+\\S+", RegexOption.IGNORE_CASE) to "password: [REDACTED]",
            Regex("api[_-]?key['\":\\s]+\\S+", RegexOption.IGNORE_CASE) to "api_key: [REDACTED]",
            Regex("ANTHROPIC_API_KEY=\\S+", RegexOption.IGNORE_CASE) to "ANTHROPIC_API_KEY=[REDACTED]",
            Regex("secret['\":\\s]+\\S+", RegexOption.IGNORE_CASE) to "secret: [REDACTED]",
            // `?token=…` in a socket URL, `--token=abc`, `DB_PASSWORD=abc`.
            Regex("((?:token|api[_-]?key|secret|password)=)\\S+", RegexOption.IGNORE_CASE) to "$1[REDACTED]",
            Regex("(--?(?:token|api[_-]?key|secret|password)\\s+)\\S+", RegexOption.IGNORE_CASE) to "$1[REDACTED]",
            Regex("(://[^/\\s:@]+:)[^@\\s]+@") to "$1[REDACTED]@",
            Regex("(\\d{6,}):[A-Za-z0-9_-]{30,}") to "$1:[REDACTED]",
            Regex("(publish to \")[^\"]*\"", RegexOption.IGNORE_CASE) to "$1[topic]\"",
            Regex("\\bs[0-9a-f]{32}\\b") to "[stream]",
        )
    }
}
