package com.hienlh.ppm.state.files

import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.protocol.FileDirectoryResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileTreeRestoreTest {
    @Test fun `fifty saved folders plus root restore in bounded parent-first batches`() = runTest {
        val memory = MemoryTreeValues()
        val prefs = FileTreePersistence(memory, "host:")
        val paths = (1..50).map { n -> (1..n).joinToString("/") { "d" } }
        prefs.saveExpanded("p", paths.reversed().toSet())
        val server = TreeServer()
        server.directories[""] = listOf(dir("d"))
        paths.forEachIndexed { i, path -> server.directories[path] = if (i == 49) listOf(file("last")) else listOf(dir("d")) }
        val tree = ProjectFileTree(server, backgroundScope, prefs)
        tree.open("p"); runCurrent()
        assertEquals(listOf(50, 1), server.batches.map { it.second.size })
        assertEquals(listOf("") + paths, server.batches.flatMap { it.second })
        assertEquals(51, tree.state.value.loadedPaths.size)
        assertEquals((0..50).toList(), tree.state.value.visibleRows.map { it.depth })
        assertEquals(paths.last() + "/last", tree.state.value.visibleRows.last().node.path)
    }

    @Test fun `selection and expansion persist per server and project while collapsed descendants stay remembered`() = runTest {
        val memory = MemoryTreeValues()
        val prefs = FileTreePersistence(memory, "a:")
        val server = TreeServer()
        server.directories[""] = listOf(dir("src"), file("readme"))
        server.directories["src"] = listOf(dir("nested")); server.directories["src/nested"] = listOf(file("a"))
        val tree = ProjectFileTree(server, backgroundScope, prefs)
        tree.open("p"); runCurrent(); tree.toggleExpand("src"); runCurrent(); tree.toggleExpand("src/nested"); runCurrent()
        tree.setSelectedFiles(listOf("readme", "src/nested/a")); tree.toggleFileSelect("readme")
        tree.setFocusedPath("src/nested/a"); tree.toggleExpand("src")
        assertEquals(setOf("src/nested"), tree.state.value.expandedPaths)
        assertEquals(listOf("src", "readme"), tree.state.value.visibleRows.map { it.node.path })
        tree.toggleExpand("src"); tree.close()
        val restored = ProjectFileTree(server, backgroundScope, prefs)
        restored.open("p"); runCurrent()
        assertEquals(listOf("src/nested/a"), restored.state.value.selectedFiles)
        assertNull(restored.state.value.focusedPath)
        assertEquals(listOf("src", "src/nested", "src/nested/a", "readme"), restored.state.value.visibleRows.map { it.node.path })
        restored.open("other"); runCurrent()
        assertTrue(restored.state.value.selectedFiles.isEmpty())
        assertTrue(FileTreePersistence(memory, "b:").expanded("p").isEmpty())
    }

    @Test fun `corrupt preferences and unavailable storage cannot block opening`() = runTest {
        val memory = MemoryTreeValues()
        val prefs = FileTreePersistence(memory, "s:")
        memory.data["s:ppm-explorer-expanded:p"] = "broken json"
        assertTrue(prefs.expanded("p").isEmpty())
        memory.data["s:ppm-explorer-expanded:p"] = """[null,42,"","a","a","b"]"""
        assertEquals(setOf("a", "b"), prefs.expanded("p"))
        prefs.saveExpanded("p", (listOf("") + (1..60).map(Int::toString)).toSet())
        assertEquals(50, prefs.expanded("p").size)
        val failing = FileTreePersistence(object : KeyValueStore {
            override fun get(key: String): String? = error("unavailable")
            override fun put(key: String, value: String?) { error("unavailable") }
        }, "s:")
        val tree = ProjectFileTree(TreeServer(), backgroundScope, failing)
        tree.open("p"); runCurrent(); tree.setSelectedFiles(listOf("a")); tree.collapseAll()
        assertEquals(listOf("a"), tree.state.value.selectedFiles)
    }

    @Test fun `collapse and selection changes while restoring are not overwritten by the batch`() = runTest {
        val prefs = FileTreePersistence(MemoryTreeValues(), "s:")
        prefs.saveExpanded("p", setOf("src"))
        val answer = CompletableDeferred<List<FileDirectoryResult>>()
        val server = TreeServer().apply { batching = { _, _ -> answer.await() } }
        val tree = ProjectFileTree(server, backgroundScope, prefs)
        tree.open("p"); runCurrent(); tree.collapseAll(); tree.setSelectedFiles(listOf("new-selection"))
        answer.complete(listOf(FileDirectoryResult("src", listOf(file("a"))), FileDirectoryResult("", listOf(dir("src")))))
        runCurrent()
        assertTrue(tree.state.value.expandedPaths.isEmpty())
        assertTrue(prefs.expanded("p").isEmpty())
        assertEquals(listOf("new-selection"), tree.state.value.selectedFiles)
        assertEquals(listOf("src"), tree.state.value.visibleRows.map { it.node.path })
    }

    @Test fun `reconnect restores expanded levels while invalidating collapsed cached directories`() = runTest {
        val server = TreeServer()
        server.directories[""] = listOf(dir("a"), dir("b")); server.directories["a"] = listOf(file("old")); server.directories["b"] = listOf(file("b"))
        val tree = ProjectFileTree(server, backgroundScope)
        tree.open("p"); runCurrent(); tree.toggleExpand("a"); runCurrent(); tree.loadChildren("b"); runCurrent()
        tree.setSelectedFiles(listOf("a/old"))
        server.directories["a"] = listOf(file("new"))
        tree.reconnected(); runCurrent()
        assertEquals(listOf("", "a"), server.batches.single().second)
        assertEquals(listOf("a", "a/new", "b"), tree.state.value.visibleRows.map { it.node.path })
        assertFalse("b" in tree.state.value.loadedPaths)
        assertEquals(listOf("a/old"), tree.state.value.selectedFiles)
    }
}
