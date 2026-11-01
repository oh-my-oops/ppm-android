package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement

/** Query text is deliberately untrimmed: the server trims search, JS replacement does not. */
data class FileSearchOptions(
    val query: String = "",
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val regex: Boolean = false,
    val include: String = "",
) {
    val eligible: Boolean get() = query.length >= if (regex) 1 else 2
}

data class FileSearchMatch(val lineNum: Int, val content: String)
data class FileSearchResult(val file: String, val matches: List<FileSearchMatch>)
data class FileSearchAnswer(val results: List<FileSearchResult>, val total: Int) {
    companion object {
        fun from(value: JsonElement?): FileSearchAnswer? {
            val rows = value["results"].array ?: return null
            val total = value["total"].int?.takeIf { it >= 0 } ?: return null
            val results = rows.map { row ->
                val file = row["file"].string?.takeIf { it.isNotEmpty() } ?: return null
                val matches = row["matches"].array?.map { match ->
                    val line = match["lineNum"].int?.takeIf { it > 0 } ?: return null
                    FileSearchMatch(line, match["content"].string ?: return null)
                } ?: return null
                FileSearchResult(file, matches)
            }
            return FileSearchAnswer(results, total)
        }
    }
}
