package com.hienlh.ppm.app.ui.files

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import com.hienlh.ppm.design.RegexHighlight
import com.hienlh.ppm.protocol.FileSearchMatch
import com.hienlh.ppm.protocol.FileSearchResult
import com.hienlh.ppm.protocol.isJsSpace
import com.hienlh.ppm.state.files.FileReplaceReport
import com.hienlh.ppm.state.files.FileMutationItem
import com.hienlh.ppm.state.files.FileMutationOperation
import com.hienlh.ppm.state.files.FileMutationOutcome
import com.hienlh.ppm.state.files.FileMutationReport
import com.hienlh.ppm.state.workspace.absoluteProjectPath

internal sealed interface SearchBodyRow {
    data class File(val result: FileSearchResult) : SearchBodyRow
    data class Match(val file: String, val match: FileSearchMatch, val index: Int) : SearchBodyRow
}

internal fun searchBodyRows(results: List<FileSearchResult>, collapsed: Set<String>): List<SearchBodyRow> = buildList {
    for (result in results) {
        add(SearchBodyRow.File(result))
        if (result.file !in collapsed) result.matches.forEachIndexed { index, match -> add(SearchBodyRow.Match(result.file, match, index)) }
    }
}

internal fun searchDisplayText(content: String) = content.trimStart(::isJsSpace)

internal fun searchResultSummary(total: Int, files: Int): String =
    "$total result${if (total == 1) "" else "s"} in $files file${if (files == 1) "" else "s"}"

internal fun searchHighlightedText(text: String, ranges: List<RegexHighlight>, background: Color): AnnotatedString = AnnotatedString(
    text, ranges.filter { it.start >= 0 && it.end > it.start && it.end <= text.length }
        .map { AnnotatedString.Range(SpanStyle(background = background), it.start, it.end) },
)

internal fun searchReplaceSummary(report: FileReplaceReport): String {
    val prefix = if (report.error != null || report.cancelled) "Stopped: " else ""
    return "$prefix${report.count} replacement${if (report.count == 1) "" else "s"} saved in ${report.paths.size} file${if (report.paths.size == 1) "" else "s"}"
}

/** Confirmed writes invalidate their absolute parents through AppModel's shared mutation hook. */
internal fun searchMutationReport(report: FileReplaceReport, projectRoot: String): FileMutationReport = FileMutationReport(
    items = report.paths.map { FileMutationItem(FileMutationOperation.WRITE, report.project, it, outcome = FileMutationOutcome.SUCCEEDED) } +
        listOfNotNull(report.failedPath?.let { FileMutationItem(FileMutationOperation.WRITE, report.project, it,
            outcome = FileMutationOutcome.FAILED, error = report.error) }),
    affectedDirectories = report.paths.map { absoluteProjectPath(projectRoot, it.substringBeforeLast('/', "")) }.toSet(),
    cancelled = report.cancelled,
)

/**
 * Confirmation summary before running Replace All (Requirement 8.4, Tablet 3 S2):
 * names how many matches in how many files will change.
 */
data class ReplaceAllConfirmation(
    val matchCount: Int,
    val fileCount: Int,
) {
    val title: String get() = replaceAllConfirmTitle(matchCount, fileCount)
    val detail: String get() = REPLACE_ALL_CONFIRM_DETAIL
}

internal const val REPLACE_ALL_CONFIRM_DETAIL =
    "Files open with unsaved edits are skipped. Each changed file can be undone from its tab with Ctrl+Z."

internal fun replaceAllConfirmTitle(matchCount: Int, fileCount: Int): String =
    "Replace $matchCount ${if (matchCount == 1) "match" else "matches"} in $fileCount ${if (fileCount == 1) "file" else "files"}?"

internal fun replaceAllConfirmation(
    results: List<FileSearchResult>,
    total: Int = results.sumOf { it.matches.size },
): ReplaceAllConfirmation? {
    if (results.isEmpty()) return null
    val matches = results.sumOf { it.matches.size }.let { sum -> if (total > 0) maxOf(total, sum) else sum }
    if (matches <= 0) return null
    return ReplaceAllConfirmation(matchCount = matches, fileCount = results.size)
}

/**
 * Inline replacement preview in result rows when the Replace box is non-empty (Tablet 3 S2 Note 69):
 * each matched span is struck through and followed by [replacement].
 */
internal fun searchReplacementPreviewText(
    text: String,
    highlights: List<RegexHighlight>,
    replacement: String,
    matchHighlight: Color,
    deleteColor: Color,
    deleteBackground: Color,
    insertColor: Color,
    insertBackground: Color,
): AnnotatedString {
    if (replacement.isEmpty() || highlights.isEmpty()) {
        return searchHighlightedText(text, highlights, matchHighlight)
    }
    val valid = highlights
        .filter { it.start >= 0 && it.end > it.start && it.end <= text.length }
        .sortedBy { it.start }
    if (valid.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var cursor = 0
        for (range in valid) {
            if (range.start < cursor) continue
            if (range.start > cursor) {
                append(text.substring(cursor, range.start))
            }
            pushStyle(
                SpanStyle(
                    color = deleteColor,
                    background = deleteBackground,
                    textDecoration = TextDecoration.LineThrough,
                ),
            )
            append(text.substring(range.start, range.end))
            pop()
            pushStyle(
                SpanStyle(
                    color = insertColor,
                    background = insertBackground,
                ),
            )
            append(replacement)
            pop()
            cursor = range.end
        }
        if (cursor < text.length) {
            append(text.substring(cursor))
        }
    }
}
