package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.FileSearchAnswer
import com.hienlh.ppm.protocol.FileSearchOptions

/** Bounded content search, never the recursive tree or file index. Preserve server error status. */
suspend fun ApiClient.searchProjectFiles(project: String, options: FileSearchOptions): FileSearchAnswer {
    val query = buildList {
        add("q" to options.query)
        add("caseSensitive" to options.caseSensitive.toString())
        add("wholeWord" to options.wholeWord.toString())
        add("regex" to options.regex.toString())
        if (options.include.isNotEmpty()) add("include" to options.include)
    }
    return FileSearchAnswer.from(call("GET", "/api/project/${ApiClient.escape(project)}/files/search", query))
        ?: throw PpmError.BadResponse("The file search results could not be read.")
}
