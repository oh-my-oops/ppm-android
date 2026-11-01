package com.hienlh.ppm.state.files

import com.hienlh.ppm.protocol.FileDirectoryEntry
import com.hienlh.ppm.protocol.FileDirectoryResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileTreeLoadingTest {
    @Test fun `construction and reconnect before open make no requests then root joins one load`() = runTest {
        val server = TreeServer()
        val root = CompletableDeferred<List<FileDirectoryEntry>>()
        server.listing = { _, _ -> root.await() }
        val tree = ProjectFileTree(server, backgroundScope)
        tree.reconnected(); runCurrent()
        assertTrue(server.requests.isEmpty()); assertTrue(server.batches.isEmpty())
        val first = tree.open("p")
        assertSame(first, tree.loadChildren(""))
        assertTrue(tree.state.value.loading)
        runCurrent()
        root.complete(listOf(dir("src"), file("link-to-directory")))
        runCurrent()
        assertEquals(listOf("p" to ""), server.requests)
        tree.toggleExpand("link-to-directory")
        assertNull(tree.loadChildren("link-to-directory"))
        assertFalse("link-to-directory" in tree.state.value.expandedPaths)
    }

    @Test fun `late cancelled project response cannot replace the new project's root`() = runTest {
        val server = TreeServer()
        val old = CompletableDeferred<List<FileDirectoryEntry>>()
        server.listing = { p, _ -> if (p == "old") withContext(NonCancellable) { old.await() } else listOf(file("new")) }
        val tree = ProjectFileTree(server, backgroundScope)
        tree.open("old"); runCurrent(); tree.open("new"); runCurrent()
        old.complete(listOf(file("old"))); runCurrent()
        assertEquals("new", tree.state.value.project)
        assertEquals(listOf("new"), tree.state.value.tree.map { it.name })
        assertFalse(tree.state.value.loading)
    }

    @Test fun `collapse during load keeps cache but never reopens a folder`() = runTest {
        val server = TreeServer()
        val child = CompletableDeferred<List<FileDirectoryEntry>>()
        server.listing = { _, path -> if (path.isEmpty()) listOf(dir("src")) else child.await() }
        val tree = ProjectFileTree(server, backgroundScope)
        tree.open("p"); runCurrent(); tree.toggleExpand("src"); runCurrent(); tree.collapseAll()
        child.complete(listOf(file("a.kt"))); runCurrent()
        assertEquals(listOf("src"), tree.state.value.visibleRows.map { it.node.path })
        tree.toggleExpand("src")
        assertEquals(listOf("src", "src/a.kt"), tree.state.value.visibleRows.map { it.node.path })
        assertEquals(2, server.requests.size)
    }

    @Test fun `failed child retry and failed refresh retain cached content and report errors`() = runTest {
        val server = TreeServer()
        var refused = true
        server.listing = { _, path -> if (path.isEmpty()) listOf(dir("src")) else {
            if (refused) error("Permission denied") else listOf(file("kept"))
        } }
        val tree = ProjectFileTree(server, backgroundScope)
        tree.open("p"); runCurrent(); tree.toggleExpand("src"); runCurrent()
        assertEquals("Permission denied", tree.state.value.errors["src"])
        assertFalse("src" in tree.state.value.loadedPaths)
        refused = false; tree.loadChildren("src"); runCurrent()
        server.batching = { _, _ -> error("offline") }
        tree.refresh(); runCurrent()
        assertEquals("offline", tree.state.value.error)
        assertEquals(listOf("src", "src/kept"), tree.state.value.visibleRows.map { it.node.path })
        assertTrue(tree.state.value.loadingPaths.isEmpty())
    }

    @Test fun `removed directory or directory replaced by symlink drops cached descendants and late answers`() = runTest {
        val server = TreeServer()
        server.directories[""] = listOf(dir("src")); server.directories["src"] = listOf(dir("deep"))
        val tree = ProjectFileTree(server, backgroundScope)
        tree.open("p"); runCurrent(); tree.toggleExpand("src"); runCurrent()
        val late = CompletableDeferred<List<FileDirectoryEntry>>()
        server.listing = { _, path -> if (path == "src/deep") withContext(NonCancellable) { late.await() } else listOf(file("src")) }
        tree.toggleExpand("src/deep"); runCurrent()
        tree.invalidateFolder("p", ""); runCurrent()
        late.complete(listOf(file("ghost"))); runCurrent()
        assertFalse(tree.state.value.tree.single().isDirectory)
        assertFalse("src" in tree.state.value.directories)
        assertFalse("src/deep" in tree.state.value.loadedPaths)
        assertEquals(1, tree.state.value.visibleRows.size)
    }

    @Test fun `batch failure of one path does not discard its siblings and missing replies are retryable`() = runTest {
        val server = TreeServer()
        val memory = MemoryTreeValues()
        val prefs = FileTreePersistence(memory, "s:")
        prefs.saveExpanded("p", linkedSetOf("good", "gone", "omitted"))
        server.batching = { _, _ -> listOf(FileDirectoryResult("good", listOf(file("a"))),
            FileDirectoryResult("gone", error = "deleted"), FileDirectoryResult("", listOf(dir("good"), dir("omitted")))) }
        val tree = ProjectFileTree(server, backgroundScope, prefs)
        tree.open("p"); runCurrent()
        assertEquals(setOf("good"), tree.state.value.expandedPaths)
        assertEquals(listOf("good", "good/a", "omitted"), tree.state.value.visibleRows.map { it.node.path })
        assertFalse("omitted" in tree.state.value.loadedPaths)
        assertEquals("deleted", tree.state.value.errors["gone"])
        assertTrue(tree.state.value.errors.containsKey("omitted"))
    }
}
