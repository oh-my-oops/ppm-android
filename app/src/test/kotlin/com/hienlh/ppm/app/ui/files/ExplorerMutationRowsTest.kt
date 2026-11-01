package com.hienlh.ppm.app.ui.files

import com.hienlh.ppm.state.files.FileMutationItem
import com.hienlh.ppm.state.files.FileMutationOperation
import com.hienlh.ppm.state.files.FileMutationOutcome
import com.hienlh.ppm.state.files.FileMutationReport
import com.hienlh.ppm.state.files.FileTreeNode
import com.hienlh.ppm.state.files.FileTreeRow
import org.junit.Assert.*
import org.junit.Test

class ExplorerMutationRowsTest {
    private val folder = FileTreeNode("src", "src", true, false)
    private val child = FileTreeNode("app.kt", "src/app.kt", false, false)
    private val rows = listOf(FileTreeRow(folder, 0), FileTreeRow(child, 1))

    @Test fun rootCreateIsFirstAndHasNoKeyCollisionWithFileNames() {
        val file = FileTreeRow(FileTreeNode("input:1", "input:1", false, false), 0)
        val edit = ExplorerInlineEdit(1, "", false)
        val result = explorerMutationRows(listOf(file), emptySet(), edit)
        assertEquals(ExplorerDisplayRow.Input(edit, 0), result.first())
        assertEquals(2, result.map { it.key }.toSet().size)
    }

    @Test fun createAppearsBeforeChildrenEvenBeforeDirectoryLoadCompletes() {
        val edit = ExplorerInlineEdit(2, "src", true)
        val loaded = explorerMutationRows(rows, setOf("src"), edit)
        val loading = explorerMutationRows(rows.take(1), setOf("src"), edit)
        assertEquals(ExplorerDisplayRow.Input(edit, 1), loaded[1])
        assertEquals(loaded.take(2), loading)
        assertEquals(listOf(ExplorerDisplayRow.Node(rows[0])), explorerMutationRows(rows.take(1), emptySet(), edit))
    }

    @Test fun renameExpandedFolderReplacesOnlyItsOwnRow() {
        val edit = ExplorerInlineEdit(3, "", true, folder)
        assertEquals(listOf(ExplorerDisplayRow.Input(edit, 0), ExplorerDisplayRow.Node(rows[1])),
            explorerMutationRows(rows, setOf("src"), edit))
    }

    @Test fun renameSelectionUsesLastExtensionAndUtf16Offsets() {
        for ((name, end) in mapOf("README" to 6, ".gitignore" to 10, "archive.tar.gz" to 11, "😀.kt" to 2, "name." to 4)) {
            assertEquals(name, end, explorerRenameSelectionEnd(name))
        }
    }

    @Test fun clipboardMenuPreservesSelectionOnlyWhenTargetBelongsToIt() {
        val selected = listOf("src", "README.md")
        assertEquals(selected, explorerClipboardTargets("src", selected))
        assertEquals(listOf("outside.txt"), explorerClipboardTargets("outside.txt", selected))
        assertEquals(listOf("src"), explorerClipboardTargets("src", emptyList()))
    }

    @Test fun partialTransferReportsCompletedCountAndSourceRemovalFailure() {
        val ok = FileMutationItem(FileMutationOperation.MOVE, "p", "a", "dest/a", FileMutationOutcome.SUCCEEDED)
        val partial = FileMutationItem(FileMutationOperation.MOVE, "p", "b", "dest/b", FileMutationOutcome.PARTIAL, "Copied, but source was not removed")
        val failed = FileMutationItem(FileMutationOperation.MOVE, "p", "c", "dest/c", FileMutationOutcome.FAILED, "Permission denied")
        val text = explorerMutationError(FileMutationReport(listOf(ok, partial, failed)))!!
        assertTrue(text.startsWith("1 completed; 2 incomplete."))
        assertTrue(text.contains("b: Copied, but source was not removed"))
        assertTrue(text.contains("c: Permission denied"))
        assertNull(explorerMutationError(FileMutationReport(listOf(ok))))
        assertTrue(explorerMutationError(FileMutationReport(cancelled = true))!!.contains("may already have reached the server"))
    }
}
