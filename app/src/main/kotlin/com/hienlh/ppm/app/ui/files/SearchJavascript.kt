package com.hienlh.ppm.app.ui.files

import com.hienlh.ppm.design.RegexOperation
import com.hienlh.ppm.design.RegexOperationsRequest
import com.hienlh.ppm.design.SyntaxHighlighter
import com.hienlh.ppm.protocol.FileSearchOptions
import com.hienlh.ppm.state.files.FileSearchRegex
import com.hienlh.ppm.state.files.FileSearchReplacement

/** Search uses the highlighter's existing process/isolate; it never creates a sandbox. */
internal class SearchJavascript(private val engine: SyntaxHighlighter) : FileSearchRegex {
    override suspend fun validate(options: FileSearchOptions) = engine.evaluateRegexOperations(options.request(RegexOperation.VALIDATE)).error

    override suspend fun replace(content: String, options: FileSearchOptions, replacement: String): FileSearchReplacement {
        val result = engine.evaluateRegexOperations(options.request(RegexOperation.REPLACE).copy(text = content, replacement = replacement))
        check(result.error == null) { result.error.orEmpty() }
        return FileSearchReplacement(checkNotNull(result.content) { "JavaScript replacement returned no content." }, result.count)
    }

    suspend fun highlights(text: String, options: FileSearchOptions) =
        engine.evaluateRegexOperations(options.request(RegexOperation.HIGHLIGHT).copy(texts = listOf(text))).highlights.firstOrNull().orEmpty()

    private fun FileSearchOptions.request(operation: RegexOperation) = RegexOperationsRequest(operation, query, caseSensitive, wholeWord, regex)
}
