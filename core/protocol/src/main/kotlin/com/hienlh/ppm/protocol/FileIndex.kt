package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement

/** One entry of a project's flat file list (`FileEntry`), what the `@` picker and the command palette search. */
data class FileEntry(
    /** From the project's folder, `/`-separated. */
    val path: String,
    val name: String,
    val isDirectory: Boolean = false,
    /** Excluded by `.gitignore` but still listed for discoverability (a `.env`); the palette draws it dimmed. */
    val isIgnored: Boolean = false,
) {
    companion object {
        fun from(value: JsonElement?): FileEntry? {
            val path = value["path"].string ?: return null
            val name = value["name"].string ?: return null
            return FileEntry(path, name, value["type"].string == "directory", value["isIgnored"].bool == true)
        }
    }
}

/**
 * `GET …/files/index?max=`: the list, or only how long it is when it is too long to send
 * (`FileIndexTooLarge`), to be searched on the server instead.
 */
sealed interface FileIndexAnswer {
    data class Entries(val entries: List<FileEntry>) : FileIndexAnswer

    data class TooLarge(val count: Int) : FileIndexAnswer

    companion object {
        fun from(value: JsonElement?): FileIndexAnswer? {
            value.array?.let { list -> return Entries(list.mapNotNull(FileEntry::from)) }
            if (value["tooLarge"].bool == true) return TooLarge(value["count"].int ?: 0)
            return null
        }
    }
}

/**
 * The `@` that opens the file picker (`/@(\S*)$/` in message-input.tsx): an `@` anywhere in the
 * run of non-space text that ends at the cursor, an address's included, the first of them when
 * there are more. Strings are UTF-16 here as in JavaScript, and whitespace is JavaScript's `\s`.
 */
object FileMention {
    /** The box after a pick: its text, and where the cursor goes (a UTF-16 offset). */
    data class Picked(val text: String, val caret: Int)

    /** What is typed after the `@` that ends [textBefore] (the text left of the cursor), or null. */
    fun queryBefore(textBefore: String): String? = atIndex(textBefore)?.let { textBefore.substring(it + 1) }

    /**
     * The box after [path] is picked with the cursor at [caret]: the `@query` left of the cursor
     * becomes `@path ` and the cursor goes after the space; with no `@` there, `@path ` goes at the
     * very end.
     */
    fun pick(path: String, text: String, caret: Int): Picked {
        val at = caret.coerceIn(0, text.length)
        val inserted = "@$path "
        val start = atIndex(text.substring(0, at)) ?: return (text + inserted).let { Picked(it, it.length) }
        val head = text.substring(0, start) + inserted
        return Picked(head + text.substring(at), head.length)
    }

    private fun atIndex(text: String): Int? = text.indexOf('@', lastRunStart(text)).takeIf { it >= 0 }
}
