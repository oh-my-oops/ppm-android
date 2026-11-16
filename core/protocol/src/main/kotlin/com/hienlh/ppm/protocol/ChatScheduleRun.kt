package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Marks a user message as a session schedule's run (`ScheduleRunReference` in
 * `shared/chat-schedule-run.ts`): PPM sent the schedule's prompt into the chat on its own, at a
 * time the user chose earlier. Only the server writes one; this side only reads it back.
 */
data class ScheduleRunReference(
    /** The schedule that fired (`schedules.id`). */
    val scheduleId: Long,
    /** Its name, on one line. */
    val name: String,
    /** The cron it fires on, on one line. */
    val cron: String,
    /** The IANA zone the cron is read in; null for the PPM host's own. */
    val timezone: String?,
    /** When the fire came due, as `toISOString()` writes it. */
    val scheduledFor: String,
    /** When PPM sent it into the chat. */
    val firedAt: String,
    /** Sent well after it came due. */
    val catchUp: Boolean,
    /** Later fires came due before it was sent and were folded into it. */
    val coalesced: Boolean,
) {
    companion object {
        private const val MAX_NAME = 200
        private const val MAX_CRON = 200
        private const val MAX_ZONE = 64
        private val ZONE_RE = Regex("^[A-Za-z0-9_+\\-/]+$")

        /** A reference, checked (`validateScheduleRun`); null when any field is not one PPM's encoder would write. */
        fun from(value: JsonElement?): ScheduleRunReference? {
            if (value !is JsonObject || value["version"].long != 1L) return null
            val id = value["scheduleId"].long?.takeIf { it > 0 } ?: return null
            val name = value["name"].string?.takeIf { it.isNotEmpty() && ChatScheduleRun.normalizeText(it, MAX_NAME) == it } ?: return null
            val cron = value["cron"].string?.takeIf { it.isNotEmpty() && ChatScheduleRun.normalizeText(it, MAX_CRON) == it } ?: return null
            val zone = value["timezone"]
            val timezone = when {
                zone is JsonNull -> null
                else -> zone.string?.takeIf { it.length <= MAX_ZONE && ZONE_RE.matches(it) } ?: return null
            }
            val scheduledFor = iso(value["scheduledFor"]) ?: return null
            val firedAt = iso(value["firedAt"]) ?: return null
            val catchUp = value["catchUp"].bool ?: return null
            val coalesced = value["coalesced"].bool ?: return null
            return ScheduleRunReference(id, name, cron, timezone, scheduledFor, firedAt, catchUp, coalesced)
        }

        private fun iso(value: JsonElement?): String? {
            val text = value.string?.takeIf { it.length <= 64 } ?: return null
            return FromSessionReference.parseJsIso(text)?.let(FromSessionReference.ISO_MILLIS::format)
        }
    }
}

/**
 * Reads `<ppm-schedule-run-v1>` blocks (`shared/chat-schedule-run.ts`): one in front of a prompt,
 * or one after a slash command that must stay the message's first characters.
 */
object ChatScheduleRun {
    const val BLOCK_START = "<ppm-schedule-run-v1>\n"
    const val BLOCK_END = "\n</ppm-schedule-run-v1>\n\n"
    const val BLOCK_END_AFTER = "\n</ppm-schedule-run-v1>"
    private const val AFTER_GAP = "\n\n"
    private const val MAX_NAME = 200

    data class Decoded(val content: String, val scheduleRun: ScheduleRunReference?)

    data class DecodedArgs(val args: String, val scheduleRun: ScheduleRunReference?)

    /** `startsWithSlashCommand`: `/name`, not a bare slash, a space or a `//` comment. */
    fun startsWithSlashCommand(content: String): Boolean =
        content.length >= 2 && content[0] == '/' && content[1] != '/' && !isJsSpace(content[1])

    private fun breaksLine(c: Char) = isJsSpace(c) || c.code in 0x00..0x1f || c.code == 0x7f

    /** `normalizeScheduleRunText`: one line, single spaces, at most [max] code points. */
    fun normalizeText(text: String, max: Int = MAX_NAME): String {
        val flat = buildString {
            var inBreak = false
            for (c in text) {
                if (breaksLine(c)) {
                    if (!inBreak) append(' ')
                    inBreak = true
                } else {
                    append(c)
                    inBreak = false
                }
            }
        }.jsTrim()
        val count = flat.codePointCount(0, flat.length)
        return if (count <= max) flat else flat.substring(0, flat.offsetByCodePoints(0, max)).jsTrim()
    }

    private fun quote(text: String): String = escapeUnsafeJson(buildString { appendJsString(text) })

    private fun escapeUnsafeJson(json: String): String = buildString(json.length) {
        for (c in json) when (c) {
            '<' -> append("\\u003c")
            '>' -> append("\\u003e")
            '&' -> append("\\u0026")
            ' ' -> append("\\u2028")
            ' ' -> append("\\u2029")
            else -> append(c)
        }
    }

    /** `scheduleRunHeaderLine`: the block's first line, which the decoder rebuilds and compares. */
    fun headerLine(ref: ScheduleRunReference, after: Boolean = false): String {
        val parts = buildList {
            add("cron ${quote(ref.cron)}" + (ref.timezone?.let { " in $it" } ?: ""))
            add("due ${ref.scheduledFor}")
            if (ref.catchUp) add("sent late")
            if (ref.coalesced) add("later fires that came due meanwhile were folded into this one")
        }
        return "Automatic scheduled run from PPM schedule ${quote(ref.name)} (${parts.joinToString("; ")}). " +
            "This is not a message the user just typed: PPM sent the schedule's ${if (after) "command above" else "prompt below"} on its own, " +
            "on a schedule the user set up earlier, and the user may not be watching."
    }

    /** The reference in a block's inside (header line, then JSON), or null unless the two match. */
    private fun readBlock(inner: String, after: Boolean): ScheduleRunReference? {
        val split = inner.indexOf('\n')
        if (split < 0) return null
        val ref = ScheduleRunReference.from(WireJson.parseOrNull(inner.substring(split + 1))) ?: return null
        return ref.takeIf { headerLine(it, after) == inner.substring(0, split) }
    }

    /** A block closing [text]: where it starts and its reference. */
    private fun trailingBlock(text: String): Pair<Int, ScheduleRunReference>? {
        if (!text.endsWith(BLOCK_END_AFTER)) return null
        val at = text.lastIndexOf(BLOCK_START)
        if (at < 0) return null
        val from = at + BLOCK_START.length
        val to = text.length - BLOCK_END_AFTER.length
        if (from > to) return null
        val ref = readBlock(text.substring(from, to), after = true) ?: return null
        return at to ref
    }

    /**
     * `decodeScheduleRun`: a block leading the prompt, or one closing a slash command. A block that
     * is malformed, elsewhere, or whose header does not match its own JSON stays text.
     */
    fun decode(content: String): Decoded {
        if (content.startsWith(BLOCK_START)) {
            val end = content.indexOf(BLOCK_END, BLOCK_START.length)
            val ref = if (end < 0) null else readBlock(content.substring(BLOCK_START.length, end), after = false)
            return if (ref != null) Decoded(content.substring(end + BLOCK_END.length), ref) else Decoded(content, null)
        }
        val (at, ref) = trailingBlock(content) ?: return Decoded(content, null)
        if (at < AFTER_GAP.length || content.substring(at - AFTER_GAP.length, at) != AFTER_GAP) return Decoded(content, null)
        val command = content.substring(0, at - AFTER_GAP.length)
        return if (startsWithSlashCommand(command)) Decoded(command, ref) else Decoded(content, null)
    }

    /**
     * `decodeScheduleRunInCommandArgs`: the block off a slash command's arguments as Claude Code
     * records them (`<command-args>…</command-args>`, trimmed) — the arguments then the block, or
     * the block alone for a command sent with none.
     */
    fun decodeInCommandArgs(args: String): DecodedArgs {
        val (at, ref) = trailingBlock(args) ?: return DecodedArgs(args, null)
        if (at == 0) return DecodedArgs("", ref)
        if (at < AFTER_GAP.length || args.substring(at - AFTER_GAP.length, at) != AFTER_GAP) return DecodedArgs(args, null)
        return DecodedArgs(args.substring(0, at - AFTER_GAP.length).jsTrimEnd(), ref)
    }

    private fun String.jsTrimEnd(): String = trimEnd { it == '﻿' || (it.isWhitespace() && it !in '\u001C'..'\u001F') }
}
