package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.ParsedUserMessage
import com.hienlh.ppm.protocol.ReplyReference
import com.hienlh.ppm.protocol.jsTrim

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
            ((reply.sdkUuid != null && it.sdkUuid == reply.sdkUuid) || (it.id == reply.messageId && it.timestampText == reply.timestamp)) && matchesQuote(it)
        }
        if (native.size == 1) return native[0]
        if (native.size > 1) return null
        return items.filter { it.timestampText == reply.timestamp && matchesQuote(it) }.singleOrNull()
    }

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
