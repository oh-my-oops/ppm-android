package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A command, skill or agent the message box offers after `/` (`SlashItem`). */
data class SlashItem(
    /** `skill`, `command`, `builtin` or `agent`. */
    val type: String,
    override val name: String,
    override val description: String = "",
    val argumentHint: String? = null,
    /** `project`, `user` or `bundled`. */
    val scope: String? = null,
    val aliases: List<String> = emptyList(),
    /** A built-in's: `ppm`, `sdk` or `client`, the layer that runs it. */
    val handler: String? = null,
    /** What it is typed after: `$` for a Codex skill, else `/`. */
    val invokeSigil: String? = null,
    /** A label the runtime gives it beside its name (Codex's own skills). */
    val displayName: String? = null,
    /** The runtime's own icon, as a data URI. */
    val iconDataUri: String? = null,
) : FuzzySearchable {
    /** What goes in the box: its sigil and its name. */
    val invocation: String get() = (invokeSigil ?: "/") + name

    /** The row's right-hand label, before CSS capitalises it. */
    val sourceLabel: String
        get() = when {
            invokeSigil == "$" -> "Codex"
            scope == "bundled" -> "PPM"
            scope == "user" -> "global"
            else -> type
        }

    companion object {
        fun from(value: JsonElement?): SlashItem? {
            val type = value["type"].string ?: return null
            val name = value["name"].string ?: return null
            return SlashItem(
                type = type,
                name = name,
                description = value["description"].string ?: "",
                argumentHint = value["argumentHint"].string,
                scope = value["scope"].string,
                aliases = value["aliases"].array.orEmpty().mapNotNull { it.string },
                handler = value["handler"].string,
                invokeSigil = value["invokeSigil"].string,
                displayName = value["displayName"].string,
                iconDataUri = value["iconDataUri"].string,
            )
        }
    }
}

/** `GET /chat/slash-items`: the list, and the names picked lately (most recent first). */
data class SlashItemsPayload(val items: List<SlashItem>, val recentNames: List<String> = emptyList()) {
    companion object {
        /** Null for no answer (`null`), not for an empty list. */
        fun from(value: JsonElement?): SlashItemsPayload? {
            if (value !is JsonObject) return null
            return SlashItemsPayload(
                items = value["items"].array.orEmpty().mapNotNull(SlashItem::from),
                recentNames = value["recentNames"].array.orEmpty().mapNotNull { it.string },
            )
        }
    }
}

/**
 * The `/` that opens the picker (`slash-trigger.ts`): one at the start of the text or after
 * whitespace, with no whitespace after it up to the cursor. Strings are UTF-16 here as in
 * JavaScript, and whitespace is JavaScript's `\s` ([isJsSpace]).
 */
object SlashTrigger {
    /** What is typed after the `/` that ends [textBefore] (the text left of the cursor), or null. */
    fun queryBefore(textBefore: String): String? = queryStart(textBefore)?.let { textBefore.substring(it + 1) }

    /** [textBefore] with its trailing `/query` replaced by [token] and a space. */
    fun replaceQuery(textBefore: String, token: String): String =
        queryStart(textBefore)?.let { textBefore.substring(0, it) + token + " " } ?: textBefore

    /** [textBefore] with its trailing `/query` taken out. */
    fun stripQuery(textBefore: String): String = queryStart(textBefore)?.let { textBefore.substring(0, it) } ?: textBefore

    /** The last run of non-space characters, when it starts with `/`. */
    private fun queryStart(text: String): Int? {
        val start = lastRunStart(text)
        return if (start < text.length && text[start] == '/') start else null
    }
}

/** Where the run of non-space characters that ends [text] starts. */
internal fun lastRunStart(text: String): Int {
    var start = text.length
    while (start > 0 && !isJsSpace(text[start - 1])) start--
    return start
}


/**
 * A message that is one command (`/^\/(\S+)(?:\s+([\s\S]+))?$/` in chat-tab.tsx): its [name],
 * and what follows it after the whitespace, when anything does.
 */
data class SlashCommandLine(val name: String, val argument: String? = null) {
    companion object {
        fun parse(text: String): SlashCommandLine? {
            if (!text.startsWith('/')) return null
            var end = 1
            while (end < text.length && !isJsSpace(text[end])) end++
            if (end == 1) return null
            val name = text.substring(1, end)
            if (end == text.length) return SlashCommandLine(name)
            var start = end
            while (start < text.length && isJsSpace(text[start])) start++
            return when {
                start < text.length -> SlashCommandLine(name, text.substring(start))
                // Only whitespace after the name: `\s+` gives its last character to `[\s\S]+`, which needs one.
                start - end >= 2 -> SlashCommandLine(name, text.substring(start - 1))
                else -> null
            }
        }
    }
}
