package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.jsTrim

/**
 * Where each user message shown sits among the versions the server keeps of it, and what a fork
 * or an edit of it continues from (`userMessageOrdinals`, and `prevMsgId` in `message-list.tsx`).
 * Both are read over the messages the web's list shows, which leaves out a user message with no
 * text and any other with neither text nor events.
 */
object MessageVersions {
    /**
     * The 1-based ordinal of each user message, plus [offset] for those above the loaded window;
     * 0 for every other item. The server numbers the session's own messages, and so does this: the
     * web numbers the conversation loaded from before a compaction too, which moves every switcher
     * below it onto another message's versions.
     */
    fun ordinals(items: List<ChatItem>, offset: Int): IntArray {
        val out = IntArray(items.size)
        var seen = 0
        items.forEachIndexed { index, item ->
            if (item.role == ChatItem.Role.USER && shown(item) && !PreCompact.isExpanded(item.id)) out[index] = offset + ++seen
        }
        return out
    }

    /**
     * What a fork or an edit of the message at [index] continues from: the message shown above it,
     * by the SDK's id where it has one, else [predecessor], the nearest one above the loaded window.
     * Null for the first message of a chat, which a fork starts over in an empty session.
     */
    fun anchor(items: List<ChatItem>, index: Int, predecessor: String?): String? {
        for (i in index - 1 downTo 0) {
            val item = items[i]
            if (shown(item)) return item.sdkUuid ?: item.id
        }
        return predecessor
    }

    private fun shown(item: ChatItem): Boolean = when (item.role) {
        ChatItem.Role.USER -> item.text.jsTrim().isNotEmpty()
        else -> item.blocks.any { it !is ChatBlock.Text || it.text.jsTrim().isNotEmpty() }
    }
}
