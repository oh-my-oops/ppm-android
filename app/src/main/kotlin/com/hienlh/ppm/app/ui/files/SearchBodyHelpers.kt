package com.hienlh.ppm.app.ui.files

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
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
