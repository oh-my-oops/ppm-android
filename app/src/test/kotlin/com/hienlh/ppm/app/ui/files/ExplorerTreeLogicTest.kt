package com.hienlh.ppm.app.ui.files

import com.hienlh.ppm.protocol.FileDirectoryEntry
import com.hienlh.ppm.protocol.FileDirectoryResult
import com.hienlh.ppm.state.files.FileTreeBackend
import com.hienlh.ppm.state.files.ProjectFileTree
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ExplorerTreeLogicTest {
    private val visible = listOf("src", "src/app.kt", "src/test.kt", "README.md")

    @Test fun plainClickClearsSelectionAndActivates() {
        assertEquals(ExplorerSelection("README.md", emptyList(), true),
            explorerSelection(visible, listOf("src"), "src", "README.md"))
    }

    @Test fun toggleDoesNotOpenAndWinsOverRange() {
        assertEquals(ExplorerSelection("README.md", listOf("src", "README.md"), false),
            explorerSelection(visible, listOf("src"), "src", "README.md", toggle = true, range = true))
        assertEquals(ExplorerSelection("src", emptyList(), false),
            explorerSelection(visible, listOf("src"), "README.md", "src", toggle = true))
    }

    @Test fun reverseRangeIncludesDirectoriesAndKeepsAnchor() {
        assertEquals(ExplorerSelection("src/test.kt", visible.take(3), false),
            explorerSelection(visible, listOf("README.md"), "src/test.kt", "src", range = true))
    }

    @Test fun shiftWithoutAnchorActsAsPlainClickButHiddenAnchorDoesNotOpen() {
        assertEquals(ExplorerSelection("README.md", emptyList(), true),
            explorerSelection(visible, listOf("src"), null, "README.md", range = true))
        assertEquals(ExplorerSelection("hidden/file", listOf("src"), false),
            explorerSelection(visible, listOf("src"), "hidden/file", "README.md", range = true))
    }

    @Test fun revealPathsPreserveNamesAndRejectOutsideProjectPaths() {
        assertEquals(emptyList<String>(), explorerParents(".gitignore"))
        assertEquals(listOf("src", "src/name with space"), explorerParents("src/name with space/日本語.kt"))
        for (invalid in listOf("", "/etc/passwd", "C:/temp/a", "C:\\temp\\a", "\\\\host\\a", "../a", "src/../a", "src//a", "./a", "src/")) {
            assertNull(invalid, explorerParents(invalid))
        }
    }

    @Test fun revealLoadsOnlyAncestorsInOrderAndFocusesFile() = runBlocking {
        val calls = mutableListOf<String>()
        val tree = tree { _, path ->
            calls += path
            when (path) {
                "" -> listOf(dir("src"), dir("unrelated"))
                "src" -> listOf(dir("nested"))
                "src/nested" -> listOf(file("app.kt"))
                else -> error("Unexpected list: $path")
            }
        }
        try {
            tree.open("project")?.join()
            assertTrue(revealExplorerFile(tree, "src/nested/app.kt"))
            assertEquals(listOf("", "src", "src/nested"), calls)
            assertEquals(setOf("src", "src/nested"), tree.state.value.expandedPaths)
            assertEquals("src/nested/app.kt", tree.state.value.focusedPath)
            assertTrue(revealExplorerFile(tree, "src/nested/app.kt"))
            assertEquals(3, calls.size)
        } finally { tree.close() }
    }

    @Test fun revealFailureIsRetryableAndDoesNotFocusMissingFile() = runBlocking {
        var fail = true
        val tree = tree { _, path ->
            if (path.isEmpty()) listOf(dir("src")) else {
                if (fail) error("Permission denied")
                listOf(file("app.kt"))
            }
        }
        try {
            tree.open("project")?.join()
            assertFalse(revealExplorerFile(tree, "src/app.kt"))
            assertNull(tree.state.value.focusedPath)
            assertEquals("Permission denied", tree.state.value.errors["src"])
            fail = false
            assertTrue(revealExplorerFile(tree, "src/app.kt"))
            assertFalse(revealExplorerFile(tree, "src/missing.kt"))
            assertEquals("src/app.kt", tree.state.value.focusedPath)
        } finally { tree.close() }
    }

    @Test fun projectChangeDuringRevealDoesNotFocusNewProject() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val tree = tree { _, path ->
            if (path.isEmpty()) listOf(dir("src")) else {
                started.complete(Unit)
                gate.await()
                listOf(file("app.kt"))
            }
        }
        try {
            tree.open("first")?.join()
            val reveal = async { revealExplorerFile(tree, "src/app.kt") }
            started.await()
            tree.open("second")?.join()
            gate.complete(Unit)
            assertFalse(reveal.await())
            assertEquals("second", tree.state.value.project)
            assertNull(tree.state.value.focusedPath)
            assertTrue(tree.state.value.expandedPaths.isEmpty())
        } finally { tree.close() }
    }

    @Test fun collapsingAncestorWhileRevealLoadsChildDoesNotRefocusHiddenFile() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val tree = tree { _, path ->
            when (path) {
                "" -> listOf(dir("src"))
                "src" -> listOf(dir("nested"))
                else -> { started.complete(Unit); gate.await(); listOf(file("app.kt")) }
            }
        }
        try {
            tree.open("project")?.join()
            val reveal = async { revealExplorerFile(tree, "src/nested/app.kt") }
            started.await()
            tree.setExpanded("src", false)
            gate.complete(Unit)
            assertFalse(reveal.await())
            assertNull(tree.state.value.focusedPath)
            assertFalse("src" in tree.state.value.expandedPaths)
        } finally { tree.close() }
    }

    private fun CoroutineScope.tree(read: suspend (String, String) -> List<FileDirectoryEntry>) = ProjectFileTree(
        object : FileTreeBackend {
            override suspend fun list(project: String, path: String) = read(project, path)
            override suspend fun listBatch(project: String, paths: List<String>): List<FileDirectoryResult> = error("Unexpected batch")
        }, this, idleMillis = 60_000,
    )

    private fun dir(name: String) = FileDirectoryEntry(name, isDirectory = true)
    private fun file(name: String) = FileDirectoryEntry(name, isDirectory = false)
}
