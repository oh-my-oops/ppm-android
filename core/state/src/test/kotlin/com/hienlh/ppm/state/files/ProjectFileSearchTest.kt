package com.hienlh.ppm.state.files

import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.protocol.FileContent
import com.hienlh.ppm.protocol.FileSearchAnswer
import com.hienlh.ppm.protocol.FileSearchMatch
import com.hienlh.ppm.protocol.FileSearchOptions
import com.hienlh.ppm.protocol.FileSearchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectFileSearchTest {
    private class Backend(var late: Boolean = false) : FileSearchBackend {
        data class Call(val project: String, val options: FileSearchOptions, val reply: CompletableDeferred<FileSearchAnswer> = CompletableDeferred())
        val calls = mutableListOf<Call>()
        val reads = mutableListOf<Pair<String, String>>()
        val writes = mutableListOf<Triple<String, String, String>>()
        val contents = mutableMapOf<String, FileContent>()
        var writeGate: CompletableDeferred<Unit>? = null
        var failWrite: String? = null
        override suspend fun search(project: String, options: FileSearchOptions): FileSearchAnswer {
            val call = Call(project, options).also(calls::add)
            return if (late) withContext(NonCancellable) { call.reply.await() } else call.reply.await()
        }
        override suspend fun read(project: String, path: String): FileContent {
            reads += project to path
            return contents[path] ?: FileContent("hit hit hit hit hit hit")
        }
        override suspend fun write(project: String, path: String, content: String) {
            writeGate?.await()
            if (path == failWrite) throw PpmError.Server(403, "write denied")
            writes += Triple(project, path, content)
        }
    }
    private class Regex : FileSearchRegex {
        var validation: String? = null
        var gate: CompletableDeferred<Unit>? = null
        val replacements = mutableListOf<Pair<FileSearchOptions, String>>()
        override suspend fun validate(options: FileSearchOptions): String? {
            gate?.let { withContext(NonCancellable) { it.await() } }
            return validation
        }
        override suspend fun replace(content: String, options: FileSearchOptions, replacement: String): FileSearchReplacement {
            replacements += options to replacement
            return FileSearchReplacement(content.replace(options.query, replacement), content.split(options.query).size - 1)
        }
    }
    private fun answer(vararg files: String) = FileSearchAnswer(files.map { FileSearchResult(it, listOf(FileSearchMatch(17, "hit"))) }, files.size)
    private fun TestScope.ready(doc: ProjectFileSearch, backend: Backend, vararg files: String) {
        doc.setProject("app"); doc.setQuery("hit"); advanceTimeBy(300); runCurrent()
        backend.calls.last().reply.complete(answer(*files)); runCurrent()
    }

    @Test fun `literal minimum and 300ms debounce have no eager requests`() = runTest {
        val backend = Backend()
        ProjectFileSearch(backend, Regex(), this).use { doc ->
            doc.setProject("app"); runCurrent(); assertTrue(backend.calls.isEmpty())
            doc.setQuery("a"); advanceTimeBy(500); runCurrent(); assertTrue(backend.calls.isEmpty())
            doc.setQuery("ab"); advanceTimeBy(299); runCurrent(); assertTrue(backend.calls.isEmpty())
            assertTrue(doc.state.value.pending)
            advanceTimeBy(1); runCurrent(); assertEquals(1, backend.calls.size)
            assertTrue(doc.state.value.loading)
            backend.calls.last().reply.complete(answer("a.txt")); runCurrent()
            assertTrue(doc.state.value.searched)
            assertEquals(1, doc.state.value.total)
        }
    }

    @Test fun `new input clears visible results synchronously during debounce and rejects obsolete success`() = runTest {
        val backend = Backend(late = true)
        ProjectFileSearch(backend, Regex(), this).use { doc ->
            ready(doc, backend, "old.txt")
            doc.retry(); runCurrent()
            val old = backend.calls.last()
            doc.setQuery("next")
            assertTrue(doc.state.value.results.isEmpty())
            assertFalse(doc.state.value.loading)
            assertFalse(doc.state.value.searched)
            old.reply.complete(answer("obsolete.txt")); runCurrent()
            assertTrue(doc.state.value.results.isEmpty())
            assertTrue(doc.state.value.pending)
            advanceTimeBy(300); runCurrent()
            backend.calls.last().reply.complete(answer("current.txt")); runCurrent()
            assertEquals("current.txt", doc.state.value.results.single().file)
        }
    }

    @Test fun `project switch and clearing query reject late errors and responses`() = runTest {
        val backend = Backend(late = true)
        ProjectFileSearch(backend, Regex(), this).use { doc ->
            doc.setProject("a"); doc.setQuery("hit"); advanceTimeBy(300); runCurrent()
            val first = backend.calls.single()
            doc.setProject("b")
            first.reply.completeExceptionally(PpmError.Server(504, "old failure")); runCurrent()
            assertNull(doc.state.value.error)
            advanceTimeBy(300); runCurrent()
            assertEquals("b", backend.calls.last().project)
            doc.setQuery("")
            backend.calls.last().reply.complete(answer("stale")); runCurrent()
            assertTrue(doc.state.value.results.isEmpty())
            assertFalse(doc.state.value.pending)
            assertFalse(doc.state.value.loading)
        }
    }

    @Test fun `regex minimum is one validation precedes network and word regex exclude each other`() = runTest {
        val backend = Backend()
        val regex = Regex().apply { validation = "invalid regex" }
        ProjectFileSearch(backend, regex, this).use { doc ->
            doc.setProject("app"); doc.setQuery("["); doc.setWholeWord(true); doc.setRegex(true)
            assertFalse(doc.state.value.options.wholeWord)
            advanceTimeBy(300); runCurrent()
            assertTrue(backend.calls.isEmpty())
            assertEquals("invalid regex", doc.state.value.regexError)
            regex.validation = null
            doc.setQuery("x"); advanceTimeBy(300); runCurrent()
            assertEquals(1, backend.calls.size)
            doc.setWholeWord(true)
            assertFalse(doc.state.value.options.regex)
            assertFalse(doc.state.value.pending) // one char no longer eligible
            doc.setCaseSensitive(true)
            assertTrue(doc.state.value.options.wholeWord)
        }
    }

    @Test fun `late regex validation cannot mark new input invalid`() = runTest {
        val backend = Backend()
        val regex = Regex().apply { validation = "bad old query"; gate = CompletableDeferred() }
        ProjectFileSearch(backend, regex, this).use { doc ->
            doc.setProject("app"); doc.setRegex(true); doc.setQuery("["); advanceTimeBy(300); runCurrent()
            doc.setRegex(false); doc.setQuery("good")
            regex.gate!!.complete(Unit); runCurrent()
            assertNull(doc.state.value.regexError)
            advanceTimeBy(300); runCurrent()
            assertEquals("good", backend.calls.single().options.query)
        }
    }

    @Test fun `include and all options invalidate immediately but replacement text does not search`() = runTest {
        val backend = Backend()
        ProjectFileSearch(backend, Regex(), this).use { doc ->
            ready(doc, backend, "src/a.kt")
            doc.toggleCollapse("src/a.kt")
            assertEquals(setOf("src/a.kt"), doc.state.value.collapsed)
            doc.setReplacement("new"); advanceTimeBy(500); runCurrent()
            assertEquals(1, backend.calls.size)
            doc.setInclude(" *.kt, src/** ")
            assertTrue(doc.state.value.results.isEmpty())
            doc.setCaseSensitive(true); doc.setWholeWord(true)
            advanceTimeBy(300); runCurrent()
            assertEquals(FileSearchOptions("hit", caseSensitive = true, wholeWord = true, include = " *.kt, src/** "), backend.calls.last().options)
            doc.setProject(null)
            assertTrue(doc.state.value.collapsed.isEmpty())
        }
    }

    @Test fun `400 503 and 504 retain server message and status and retry without debounce`() = runTest {
        for (status in listOf(400, 503, 504)) {
            val backend = Backend()
            ProjectFileSearch(backend, Regex(), this).use { doc ->
                doc.setProject("app"); doc.setQuery("hit"); advanceTimeBy(300); runCurrent()
                backend.calls.last().reply.completeExceptionally(PpmError.Server(status, "failure $status")); runCurrent()
                assertEquals(status, doc.state.value.errorStatus)
                assertEquals("failure $status", doc.state.value.error)
                doc.retry(); runCurrent()
                assertEquals(2, backend.calls.size)
                backend.calls.last().reply.complete(answer()); runCurrent()
                assertTrue(doc.state.value.searched)
                assertNull(doc.state.value.error)
            }
        }
    }

    @Test fun `replace all reads full files sequentially counts only acknowledged writes and searches again`() = runTest {
        val backend = Backend().apply { writeGate = CompletableDeferred() }
        val regex = Regex()
        val reports = mutableListOf<FileReplaceReport>()
        ProjectFileSearch(backend, regex, this, reports::add).use { doc ->
            ready(doc, backend, "a", "b")
            doc.setReplacement("saved")
            doc.replaceAll(); doc.replaceAll(); runCurrent()
            assertEquals(listOf("app" to "a"), backend.reads)
            assertTrue(backend.writes.isEmpty())
            assertEquals(0, doc.state.value.replaceReport!!.count)
            backend.writeGate!!.complete(Unit); runCurrent()
            assertEquals(listOf("a", "b"), backend.writes.map { it.second })
            assertEquals(12, reports.single().count) // Six actual matches per file; only one displayed row.
            assertEquals(listOf("a", "b"), reports.single().paths)
            assertEquals(2, backend.calls.size)
            assertTrue(doc.state.value.loading)
            backend.calls.last().reply.complete(answer()); runCurrent()
            assertTrue(doc.state.value.results.isEmpty())
            assertEquals(12, doc.state.value.replaceReport!!.count)
        }
    }

    @Test fun `partial failure reports saved paths and stops before subsequent files`() = runTest {
        val backend = Backend().apply { failWrite = "b" }
        val reports = mutableListOf<FileReplaceReport>()
        ProjectFileSearch(backend, Regex(), this, reports::add).use { doc ->
            ready(doc, backend, "a", "b", "c")
            doc.setReplacement("new"); doc.replaceAll(); runCurrent()
            val report = reports.single()
            assertEquals(6, report.count)
            assertEquals(listOf("a"), report.paths)
            assertEquals("b", report.failedPath)
            assertEquals("write denied", report.error)
            assertEquals(listOf("a", "b"), backend.reads.map { it.second })
            assertEquals(2, backend.calls.size) // Re-search also after a partial write.
        }
    }

    @Test fun `binary file is never fed to regex or written and zero matches skip write`() = runTest {
        val backend = Backend().apply {
            contents["a"] = FileContent("no matches")
            contents["b"] = FileContent("aGl0", "base64")
        }
        val regex = Regex()
        val reports = mutableListOf<FileReplaceReport>()
        ProjectFileSearch(backend, regex, this, reports::add).use { doc ->
            ready(doc, backend, "a", "b", "c")
            doc.replaceAll(); runCurrent()
            assertTrue(backend.writes.isEmpty())
            assertEquals(1, regex.replacements.size)
            assertEquals("b", reports.single().failedPath)
            assertEquals(0, reports.single().count)
        }
    }

    @Test fun `project switch waits for current write acknowledgment then cancels before next file`() = runTest {
        val backend = Backend().apply { writeGate = CompletableDeferred() }
        val reports = mutableListOf<FileReplaceReport>()
        ProjectFileSearch(backend, Regex(), this, reports::add).use { doc ->
            ready(doc, backend, "a", "b")
            doc.replaceAll(); runCurrent()
            doc.setProject("next")
            backend.writeGate!!.complete(Unit); runCurrent()
            assertEquals(listOf("a"), backend.writes.map { it.second })
            assertEquals("app", reports.single().project)
            assertTrue(reports.single().cancelled)
            assertEquals(listOf("a"), reports.single().paths)
            assertNull(doc.state.value.replaceReport)
            assertFalse(doc.state.value.replacing)
            assertEquals("next", doc.state.value.project)
        }
    }

    @Test fun `cancellation before first replace dispatch does not leave replacement locked`() = runTest {
        val backend = Backend()
        val reports = mutableListOf<FileReplaceReport>()
        ProjectFileSearch(backend, Regex(), this, reports::add).use { doc ->
            ready(doc, backend, "a")
            doc.replaceAll(); doc.setProject("next"); runCurrent()
            assertFalse(doc.state.value.replacing)
            assertTrue(backend.writes.isEmpty())
            assertTrue(reports.single().cancelled)
        }
    }

    @Test fun `close rejects late search and stops replacement after an already sent write`() = runTest {
        val backend = Backend().apply { writeGate = CompletableDeferred() }
        val reports = mutableListOf<FileReplaceReport>()
        val doc = ProjectFileSearch(backend, Regex(), this, reports::add)
        ready(doc, backend, "a", "b")
        doc.replaceAll(); runCurrent(); doc.close(); doc.close()
        backend.writeGate!!.complete(Unit); runCurrent()
        assertEquals(1, backend.writes.size)
        assertTrue(reports.single().cancelled)
        assertEquals(6, reports.single().count)
        doc.retry(); doc.setQuery("new"); doc.replaceAll(); advanceTimeBy(500); runCurrent()
        assertEquals(1, backend.calls.size)
    }

    @Test fun `closed search cannot publish even when the backend ignores cancellation`() = runTest {
        val backend = Backend(late = true)
        val doc = ProjectFileSearch(backend, Regex(), this)
        doc.setProject("app"); doc.setQuery("hit"); advanceTimeBy(300); runCurrent()
        doc.close()
        val closed = doc.state.value
        backend.calls.single().reply.complete(answer("late.txt")); runCurrent()
        assertEquals(closed, doc.state.value)
        assertFalse(doc.state.value.loading)
        assertTrue(doc.state.value.results.isEmpty())
    }
}
