package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `GET /files/search`'s query (`search-client.ts` `searchUrl`). Query text is deliberately
 * untrimmed: the server trims search, JS replacement does not. [replace] is sent only while the
 * replace field is open (null otherwise), since the server computes each match's replacement then.
 */
data class FileSearchOptions(
    val query: String = "",
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val regex: Boolean = false,
    val include: String = "",
    val exclude: String = "",
    /** "Use Exclude Settings and Ignore Files". */
    val useIgnore: Boolean = true,
    val replace: String? = null,
    val preserveCase: Boolean = false,
) {
    val eligible: Boolean get() = query.length >= if (regex) 1 else 2
}

/**
 * One match (`SearchMatch` in `search-options.ts`): [line] 1-based, [column] 0-based UTF-16 in the
 * full line, [before]/[text]/[after] the preview around it. A server older than the search host
 * sent `{lineNum, content}`: that row has no column and its whole line in [before].
 */
data class FileSearchMatch(
    val line: Int,
    val column: Int?,
    val length: Int,
    val before: String,
    val cutBefore: Boolean,
    val text: String,
    val after: String,
    val replacement: String? = null,
) {
    /** An old server's row: a line and its text, nothing known about where the match is in it. */
    constructor(line: Int, content: String) : this(line, null, 0, content, false, "", "")

    val lineNum: Int get() = line
    /** The preview line as one string. */
    val content: String get() = before + text + after

    companion object {
        fun from(value: JsonElement?): FileSearchMatch? {
            if (value !is JsonObject) return null
            if (value["line"] == null && value["lineNum"] != null) {
                val line = value["lineNum"].int?.takeIf { it > 0 } ?: return null
                return FileSearchMatch(line, value["content"].string ?: return null)
            }
            val line = value["line"].int?.takeIf { it > 0 } ?: return null
            val column = value["column"].int?.takeIf { it >= 0 } ?: return null
            val text = value["text"].string ?: return null
            return FileSearchMatch(
                line = line,
                column = column,
                length = value["length"].int?.takeIf { it >= 0 } ?: text.length,
                before = value["before"].string ?: "",
                cutBefore = value["cutBefore"].bool == true,
                text = text,
                after = value["after"].string ?: "",
                replacement = value["replacement"].string,
            )
        }
    }
}

data class FileSearchResult(val file: String, val matches: List<FileSearchMatch>)

/** `SearchResponse`: every match counted, sorted by path; [limitHit] at 20,000, [incomplete] `"timeout"` after 30 s. */
data class FileSearchAnswer(
    val results: List<FileSearchResult>,
    val total: Int,
    val files: Int = results.size,
    val limitHit: Boolean = false,
    val incomplete: String? = null,
    /** `"rg"` or `"grep"`; null from an old server. */
    val engine: String? = null,
    val replaceUnavailable: Boolean = false,
) {
    companion object {
        fun from(value: JsonElement?): FileSearchAnswer? {
            val rows = value["results"].array ?: return null
            val total = value["total"].int?.takeIf { it >= 0 } ?: return null
            val results = rows.map { row ->
                val file = row["file"].string?.takeIf { it.isNotEmpty() } ?: return null
                val matches = row["matches"].array?.map { FileSearchMatch.from(it) ?: return null } ?: return null
                FileSearchResult(file, matches)
            }
            return FileSearchAnswer(
                results = results,
                total = total,
                files = value["files"].int?.takeIf { it >= 0 } ?: results.size,
                limitHit = value["limitHit"].bool == true,
                incomplete = value["incomplete"].string,
                engine = value["engine"].string,
                replaceUnavailable = value["replaceUnavailable"].bool == true,
            )
        }
    }
}

/** One match of a `POST /files/replace` body (`ReplaceMatch`): the server checks [text] is still at [line]:[column]. */
data class FileReplaceMatch(val line: Int, val column: Int, val text: String, val replacement: String)

data class FileReplaceFile(val file: String, val matches: List<FileReplaceMatch>)

fun fileReplaceBody(files: List<FileReplaceFile>): JsonElement = buildJsonObject {
    put("files", buildJsonArray {
        for (f in files) add(buildJsonObject {
            put("file", f.file)
            put("matches", buildJsonArray {
                for (m in f.matches) add(buildJsonObject {
                    put("line", m.line)
                    put("column", m.column)
                    put("text", m.text)
                    put("replacement", m.replacement)
                })
            })
        })
    })
}

data class FileReplaceSkip(val file: String, val reason: String)

/** `ReplaceReport`: [skipped] files changed on disk since the search, [failed] files that could not be written. */
data class FileReplaceAnswer(
    val replaced: Int,
    val files: Int,
    val skipped: List<FileReplaceSkip>,
    val failed: List<FileReplaceSkip>,
) {
    companion object {
        fun from(value: JsonElement?): FileReplaceAnswer? {
            val replaced = value["replaced"].int?.takeIf { it >= 0 } ?: return null
            fun list(key: String): List<FileReplaceSkip> = value[key].array.orEmpty().mapNotNull { row ->
                val file = row["file"].string ?: return@mapNotNull null
                FileReplaceSkip(file, row["reason"].string ?: "")
            }
            return FileReplaceAnswer(replaced, value["files"].int ?: 0, list("skipped"), list("failed"))
        }
    }
}
