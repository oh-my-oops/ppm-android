package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement

/** A bookmarked turn of a chat (`src/shared/session-bookmarks.ts`). */
data class SessionBookmark(
    val turnId: String,
    /** The turn's number when it was bookmarked, for the list's order and its label. */
    val turnN: Int? = null,
    /** What the list shows: the prompt's first line unless the person named it. */
    val label: String,
    val createdAt: String = "",
) {
    companion object {
        /** Most characters a bookmark's label keeps (`MAX_BOOKMARK_LABEL`). */
        const val MAX_LABEL = 200

        fun from(json: JsonElement): SessionBookmark? {
            val turnId = json["turnId"].string ?: return null
            return SessionBookmark(turnId, json["turnN"].int, json["label"].string ?: "", json["createdAt"].string ?: "")
        }

        /** The server's list: each bookmark it could read. */
        fun listFrom(json: JsonElement): List<SessionBookmark> = json.array.orEmpty().mapNotNull(::from)

        /** A prompt's first non-empty line, as a bookmark's default label (`bookmarkLabelOf`). */
        fun labelOf(text: String): String {
            val line = text.split("\n").map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
            return if (line.length > MAX_LABEL) line.substring(0, MAX_LABEL - 1) + "…" else line
        }
    }
}
