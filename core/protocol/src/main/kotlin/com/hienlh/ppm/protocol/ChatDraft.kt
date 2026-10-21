package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement

/** A file a draft names (`DraftAttachment` of `use-draft.ts`): uploaded already, so its name and where the server keeps it. */
data class DraftAttachment(val name: String, val path: String) {
    companion object {
        fun from(value: JsonElement?): DraftAttachment? {
            val name = value["name"].string ?: return null
            val path = value["path"].string ?: return null
            return DraftAttachment(name, path)
        }
    }
}

/** A message not sent yet (`use-draft.ts`): its text, the files it names and the message it answers. */
data class ChatDraft(
    /** The text, with any quoted reply split off into [replyTo]. */
    val content: String,
    val attachments: List<DraftAttachment> = emptyList(),
    val replyTo: ReplyReference? = null,
) {
    /** The text as stored: the reply rides inside it (`encodeReply`). */
    val storedContent: String get() = ChatReply.encode(content, replyTo)

    /** `JSON.stringify(attachments)`, in its key order. */
    val storedAttachments: String
        get() = buildString {
            append('[')
            attachments.forEachIndexed { index, file ->
                if (index > 0) append(',')
                append("{\"name\":")
                appendJsString(file.name)
                append(",\"path\":")
                appendJsString(file.path)
                append('}')
            }
            append(']')
        }

    val isEmpty: Boolean get() = content.isEmpty() && attachments.isEmpty() && replyTo == null

    companion object {
        /** The server's row, `{ content, attachments, updatedAt }`: an unreadable list is no files, not no draft. */
        fun from(value: JsonElement?): ChatDraft? {
            val stored = value["content"].string ?: return null
            val files = value["attachments"].string?.let(WireJson::parseOrNull).array.orEmpty().mapNotNull(DraftAttachment::from)
            return stored(stored, files)
        }

        /** From the text as stored, its reply block included (`decodeReply`). */
        fun stored(content: String, attachments: List<DraftAttachment>): ChatDraft {
            val decoded = ChatReply.decode(content)
            return ChatDraft(decoded.content, attachments, decoded.replyTo)
        }
    }
}
