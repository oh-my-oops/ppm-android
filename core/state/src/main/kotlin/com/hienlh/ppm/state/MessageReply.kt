package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.ParsedUserMessage
import com.hienlh.ppm.protocol.ReplyReference
import com.hienlh.ppm.protocol.jsTrim
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Finding a quoted message again (`message-reply-reference.ts`). The quote has to come out
 * exactly as the web's does: either client finds the message by comparing it.
 */
object MessageReply {
    /** Only text the chat shows: never thinking, tools or injected context (`replyMessageText`). */
    fun text(item: ChatItem): String = when (item.role) {
        ChatItem.Role.ASSISTANT -> item.answerText.jsTrim()
        ChatItem.Role.USER -> {
            val parsed = ParsedUserMessage.parse(item.text)
            if (parsed.isSystemContext) {
                ""
            } else {
                val parts = listOfNotNull(parsed.command?.name, parsed.text).filter { it.isNotEmpty() }.toMutableList()
                if (parsed.files.isNotEmpty()) parts += "Attached files: " + parsed.files.joinToString(", ") { it.split('/', '\\').last() }
                parts.joinToString("\n").jsTrim()
            }
        }
        ChatItem.Role.SYSTEM -> ""
    }

    /**
     * The quoted message among [items] (`resolveReplyMessage`), only when exactly one fits: by
     * its SDK id or its own id and time, else by its time alone — always with the same text.
     */
    fun resolve(reply: ReplyReference, items: List<ChatItem>, sessionId: String?, providerId: String?): ChatItem? {
        if (reply.sessionId != sessionId || reply.providerId != providerId) return null
        fun matchesQuote(item: ChatItem): Boolean {
            if (role(item) != reply.role) return false
            val text = text(item)
            return if (reply.truncated) clipped(text) == reply.quote else text == reply.quote
        }
        val native = items.filter {
            ((reply.sdkUuid != null && it.sdkUuid == reply.sdkUuid) || (it.id == reply.messageId && stamp(it) == reply.timestamp)) && matchesQuote(it)
        }
        if (native.size == 1) return native[0]
        if (native.size > 1) return null
        return items.filter { stamp(it) == reply.timestamp && matchesQuote(it) }.singleOrNull()
    }

    /**
     * What a Reply under [item] quotes (`createReplyReference`): its text up to
     * [ReplyReference.MAX_QUOTE_LENGTH] code points. Null when it shows none to quote.
     */
    fun create(item: ChatItem, sessionId: String, providerId: String): ReplyReference? {
        val role = role(item) ?: return null
        val text = text(item)
        if (text.isEmpty()) return null
        val timestamp = stamp(item) ?: return null
        val quote = clipped(text)
        return ReplyReference(sessionId, providerId, item.id, item.sdkUuid, role, timestamp, quote, truncated = quote.length < text.length)
    }

    /** `message.timestamp`: as the server wrote it, or, for a message made here, as `toISOString` writes one. */
    private fun stamp(item: ChatItem): String? = item.timestampText ?: item.timestamp?.let(ISO_MILLIS::format)

    /** `Date.prototype.toISOString`: always milliseconds, always UTC. */
    private val ISO_MILLIS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    private fun role(item: ChatItem): ReplyReference.Role? = when (item.role) {
        ChatItem.Role.USER -> ReplyReference.Role.USER
        ChatItem.Role.ASSISTANT -> ReplyReference.Role.ASSISTANT
        ChatItem.Role.SYSTEM -> null
    }

    /** The first [ReplyReference.MAX_QUOTE_LENGTH] code points, as `Array.from(text).slice(…)` takes them. */
    internal fun clipped(text: String): String {
        if (text.codePointCount(0, text.length) <= ReplyReference.MAX_QUOTE_LENGTH) return text
        return text.substring(0, text.offsetByCodePoints(0, ReplyReference.MAX_QUOTE_LENGTH))
    }
}
