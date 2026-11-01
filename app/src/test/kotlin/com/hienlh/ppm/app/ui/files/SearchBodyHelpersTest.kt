package com.hienlh.ppm.app.ui.files

import androidx.compose.ui.graphics.Color
import com.hienlh.ppm.design.RegexHighlight
import com.hienlh.ppm.protocol.FileSearchMatch
import com.hienlh.ppm.protocol.FileSearchResult
import com.hienlh.ppm.state.files.FileReplaceReport
import com.hienlh.ppm.state.files.FileMutationOutcome
import org.junit.Assert.*
import org.junit.Test

class SearchBodyHelpersTest {
    @Test fun `groups retain exact paths and line numbers through collapse`() {
        val a = FileSearchResult("src/a.kt", listOf(FileSearchMatch(17, "first"), FileSearchMatch(81, "second")))
        val b = FileSearchResult("test/a.kt", listOf(FileSearchMatch(7, "test")))
        val rows = searchBodyRows(listOf(a, b), setOf(a.file))
        assertEquals(3, rows.size)
        assertEquals(SearchBodyRow.File(a), rows[0])
        assertEquals(SearchBodyRow.Match(b.file, b.matches.single(), 0), rows[2])
        assertEquals(5, searchBodyRows(listOf(a, b), emptySet()).size)
    }
    @Test fun `display trim is JS trimStart and does not alter retained source`() {
        assertEquals("foo  ", searchDisplayText("\uFEFF\u00A0\t foo  "))
        assertEquals("\u0085foo", searchDisplayText("\u0085foo"))
    }
    @Test fun `highlight ranges never duplicate captures or accept stale invalid offsets`() {
        val text = "😀foo foo"
        val shown = searchHighlightedText(text, listOf(RegexHighlight(2, 5), RegexHighlight(6, 9), RegexHighlight(-1, 2), RegexHighlight(0, 100)), Color.Yellow)
        assertEquals(text, shown.text)
        assertEquals(listOf(2, 6), shown.spanStyles.map { it.start })
    }
    @Test fun `partial result describes confirmed saved changes and never claims all succeeded`() {
        assertEquals("Stopped: 3 replacements saved in 1 file", searchReplaceSummary(FileReplaceReport("app", 3, listOf("a"), "b", "denied")))
        assertEquals("Stopped: 0 replacements saved in 0 files", searchReplaceSummary(FileReplaceReport("app", cancelled = true)))
        assertEquals("1 replacement saved in 1 file", searchReplaceSummary(FileReplaceReport("app", 1, listOf("a"))))
    }

    @Test fun `successful paths invalidate unique absolute parents even after a partial failure`() {
        val report = FileReplaceReport("app", 3, listOf("root.txt", "src/a.kt", "src/b.kt"), "other/failed.kt", "denied")
        val mutation = searchMutationReport(report, "/workspace/app/")
        assertEquals(setOf("/workspace/app/", "/workspace/app/src"), mutation.affectedDirectories)
        assertEquals(3, mutation.items.count { it.outcome == FileMutationOutcome.SUCCEEDED })
        assertTrue(mutation.partial)
        assertEquals("other/failed.kt", mutation.failures.single().path)
        assertEquals(setOf("C:\\work\\app\\", "C:\\work\\app\\src"), searchMutationReport(report, "C:\\work\\app\\").affectedDirectories)
        assertEquals(setOf("/"), searchMutationReport(report.copy(paths = listOf("root.txt")), "/").affectedDirectories)
    }
}
