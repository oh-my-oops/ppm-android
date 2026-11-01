package com.hienlh.ppm.state.files

import com.hienlh.ppm.protocol.FileDirectoryEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileTreePrefetchTest {
    @Test fun `idle prefetch skips ignored and files caps twenty candidates and three active requests`() = runTest {
        val server = TreeServer()
        val replies = mutableMapOf<String, CompletableDeferred<List<FileDirectoryEntry>>>()
        server.listing = { _, path -> if (path.isEmpty()) listOf(dir("ignored", true), file("link")) + (1..30).map { dir("d$it") }
            else replies.getOrPut(path) { CompletableDeferred() }.await() }
        val tree = ProjectFileTree(server, backgroundScope)
        tree.open("p"); runCurrent()
        advanceTimeBy(199); runCurrent(); assertEquals(1, server.requests.size)
        advanceTimeBy(1); runCurrent(); assertEquals(3, replies.size)
        assertEquals(setOf("d1", "d2", "d3"), replies.keys)
        while (replies.size < 20) {
            replies.values.toList().forEach { it.complete(listOf(dir("nested"))) }
            runCurrent(); advanceTimeBy(200); runCurrent()
            assertTrue(replies.values.count { !it.isCompleted } <= 3)
        }
        replies.values.forEach { it.complete(listOf(dir("nested"))) }
        runCurrent(); advanceTimeBy(1000); runCurrent()
        assertEquals(21, server.requests.size)
        assertFalse(server.requests.any { '/' in it.second || it.second == "ignored" || it.second == "link" })
    }

    @Test fun `expanding an in-flight prefetch joins and promotes it to prefetch one further level`() = runTest {
        val server = TreeServer()
        val answer = CompletableDeferred<List<FileDirectoryEntry>>()
        server.listing = { _, path -> when (path) {
            "" -> listOf(dir("a"))
            "a" -> answer.await()
            "a/b" -> listOf(dir("c"))
            else -> error("unexpected cascade $path")
        } }
        val tree = ProjectFileTree(server, backgroundScope)
        tree.open("p"); runCurrent(); advanceTimeBy(200); runCurrent()
        tree.toggleExpand("a")
        answer.complete(listOf(dir("b"))); runCurrent(); advanceTimeBy(200); runCurrent()
        assertEquals(listOf("", "a", "a/b"), server.requests.map { it.second })
        advanceTimeBy(1000); runCurrent()
        assertEquals(3, server.requests.size)
        tree.toggleExpand("a/b"); advanceTimeBy(200); runCurrent()
        assertEquals("a/b/c", server.requests.last().second)
    }

    @Test fun `reset cancels queued work and project switch cannot prefetch the previous root`() = runTest {
        val server = TreeServer()
        server.listing = { project, path -> if (path.isEmpty()) listOf(dir(project)) else emptyList() }
        val tree = ProjectFileTree(server, backgroundScope)
        tree.open("old"); runCurrent(); tree.reset()
        advanceTimeBy(1000); runCurrent(); assertEquals(listOf("old" to ""), server.requests)
        tree.open("old"); runCurrent(); tree.open("new"); runCurrent()
        advanceTimeBy(1000); runCurrent()
        assertEquals("new" to "new", server.requests.last())
        assertFalse(server.requests.contains("old" to "old"))
    }
}
