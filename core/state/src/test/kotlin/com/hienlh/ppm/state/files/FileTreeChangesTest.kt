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
class FileTreeChangesTest {
    @Test fun `change bursts debounce every affected parent and filter other projects`() = runTest {
        val server = TreeServer()
        server.directories[""] = listOf(dir("a"), dir("b")); server.directories["a"] = listOf(file("old")); server.directories["b"] = emptyList()
        val tree = ProjectFileTree(server, backgroundScope, idleMillis = 10_000)
        tree.open("p"); runCurrent(); tree.toggleExpand("a"); tree.toggleExpand("b"); runCurrent()
        server.requests.clear()
        tree.onFileChanged("other", "a/no")
        tree.onFileChanged("p", "a/new")
        advanceTimeBy(200); tree.onFileChanged("p", "b/new")
        advanceTimeBy(299); runCurrent(); assertTrue(server.requests.isEmpty())
        server.directories["a"] = listOf(file("new")); server.directories["b"] = listOf(file("new"))
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("a", "b"), server.requests.map { it.second })
        assertEquals(listOf("a", "a/new", "b", "b/new"), tree.state.value.visibleRows.map { it.node.path })
    }

    @Test fun `collapsed invalidation keeps old children without loading until expanded again`() = runTest {
        val server = TreeServer()
        server.directories[""] = listOf(dir("a")); server.directories["a"] = listOf(file("old"))
        val tree = ProjectFileTree(server, backgroundScope, idleMillis = 10_000)
        tree.open("p"); runCurrent(); tree.toggleExpand("a"); runCurrent(); tree.toggleExpand("a")
        server.requests.clear(); tree.onFileChanged("p", "a/new"); advanceTimeBy(300); runCurrent()
        assertTrue(server.requests.isEmpty()); assertFalse("a" in tree.state.value.loadedPaths)
        server.directories["a"] = listOf(file("new")); tree.toggleExpand("a"); runCurrent()
        assertEquals(listOf("p" to "a"), server.requests)
        assertEquals("a/new", tree.state.value.visibleRows.last().node.path)
    }

    @Test fun `late cancelled listing cannot overwrite a path change refresh or clear its loading state`() = runTest {
        val server = TreeServer()
        val old = CompletableDeferred<List<FileDirectoryEntry>>()
        val fresh = CompletableDeferred<List<FileDirectoryEntry>>()
        var reads = 0
        server.listing = { _, _ -> if (reads++ == 0) withContext(NonCancellable) { old.await() } else fresh.await() }
        val tree = ProjectFileTree(server, backgroundScope)
        tree.open("p"); runCurrent(); tree.onFileChanged("p", "new")
        advanceTimeBy(300); runCurrent()
        old.complete(listOf(file("old"))); runCurrent()
        assertTrue(tree.state.value.loading); assertTrue(tree.state.value.tree.isEmpty())
        fresh.complete(listOf(file("new"))); runCurrent()
        assertEquals("new", tree.state.value.tree.single().name)
        assertFalse(tree.state.value.loading)
    }

    @Test fun `root refresh preserves loaded descendants and null path means root`() = runTest {
        val server = TreeServer()
        server.directories[""] = listOf(dir("a")); server.directories["a"] = listOf(file("cached"))
        val tree = ProjectFileTree(server, backgroundScope, idleMillis = 10_000)
        tree.open("p"); runCurrent(); tree.toggleExpand("a"); runCurrent()
        tree.onFileChanged("p", null); advanceTimeBy(300); runCurrent()
        assertEquals(listOf("a", "a/cached"), tree.state.value.visibleRows.map { it.node.path })
        assertTrue("a" in tree.state.value.loadedPaths)
        tree.onFileChanged("p", "a/cached"); tree.open("q"); runCurrent()
        val count = server.requests.size
        advanceTimeBy(300); runCurrent(); assertEquals(count, server.requests.size)
    }

    @Test fun `change during initial batch restore preserves expansion and reloads the affected view`() = runTest {
        val server = TreeServer()
        val prefs = FileTreePersistence(MemoryTreeValues(), "s:")
        prefs.saveExpanded("p", setOf("a", "a/b"))
        val first = CompletableDeferred<List<FileDirectoryResult>>()
        var calls = 0
        server.directories[""] = listOf(dir("a"))
        server.directories["a"] = listOf(dir("b"))
        server.directories["a/b"] = listOf(file("fresh"))
        server.batching = { _, paths ->
            if (calls++ == 0) withContext(NonCancellable) { first.await() } else paths.map { FileDirectoryResult(it, server.directories[it]) }
        }
        val tree = ProjectFileTree(server, backgroundScope, prefs, idleMillis = 10_000)
        tree.open("p"); runCurrent(); tree.onFileChanged("p", "root-change")
        first.complete(listOf(FileDirectoryResult("", listOf(dir("a"))),
            FileDirectoryResult("a", listOf(dir("b"))),
            FileDirectoryResult("a/b", listOf(file("old")))))
        runCurrent(); advanceTimeBy(300); runCurrent()
        assertEquals(setOf("a", "a/b"), tree.state.value.expandedPaths)
        assertEquals(listOf("a", "a/b", "a/b/fresh"), tree.state.value.visibleRows.map { it.node.path })
    }
}
