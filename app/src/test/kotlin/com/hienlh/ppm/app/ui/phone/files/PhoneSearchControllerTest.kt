package com.hienlh.ppm.app.ui.phone.files

import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.protocol.FileReplaceAnswer
import com.hienlh.ppm.protocol.FileReplaceFile
import com.hienlh.ppm.protocol.FileReplaceMatch
import com.hienlh.ppm.protocol.FileReplaceSkip
import com.hienlh.ppm.protocol.FileSearchAnswer
import com.hienlh.ppm.protocol.FileSearchMatch
import com.hienlh.ppm.protocol.FileSearchOptions
import com.hienlh.ppm.protocol.FileSearchResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone Search's state, against `search-store.ts`, `search-client.ts` and `search-rows.ts`. */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneSearchControllerTest {
    private class FakeBackend : PhoneSearchBackend {
        val searches = mutableListOf<Pair<String, FileSearchOptions>>()
        val replaces = mutableListOf<Pair<String, List<FileReplaceFile>>>()
        var answer: (FileSearchOptions) -> FileSearchAnswer = { FileSearchAnswer(emptyList(), 0) }
        var report: FileReplaceAnswer = FileReplaceAnswer(0, 0, emptyList(), emptyList())
        override suspend fun search(project: String, options: FileSearchOptions): FileSearchAnswer {
            searches += project to options
            return answer(options)
        }
        override suspend fun replace(project: String, files: List<FileReplaceFile>): FileReplaceAnswer {
            replaces += project to files
            return report
        }
    }

    private fun match(line: Int, column: Int, text: String = "foo", replacement: String? = null) =
        FileSearchMatch(line, column, text.length, "x ", false, text, " y", replacement)

    private val twoFiles = FileSearchAnswer(
        listOf(
            FileSearchResult("src/a.ts", listOf(match(1, 2, replacement = "bar"), match(4, 0, replacement = "bar"))),
            FileSearchResult("src/b.ts", listOf(match(9, 5, replacement = "bar"))),
        ),
        total = 3,
    )

    private fun TestScope.controller(
        backend: FakeBackend,
        unsaved: Set<String> = emptySet(),
        replaced: MutableList<List<String>> = mutableListOf(),
    ) = PhoneSearchController(backend, backgroundScope, { _, file -> file in unsaved }, { _, files -> replaced += files }).also { it.setProject("app") }

    @Test fun `typing is debounced into one search carrying every option the web sends`() = runTest {
        val backend = FakeBackend()
        val search = controller(backend)
        search.change { it.copy(query = "f") }
        search.change { it.copy(query = "fo") }
        search.toggleWholeWord()
        search.toggleRegex()
        search.updateFields { it.copy(showDetails = true) }
        search.change { it.copy(include = "*.ts", exclude = "dist/**") }
        search.toggleUseIgnore()
        assertEquals(PhoneSearchStatus.SEARCHING, search.state.value.status)
        advanceTimeBy(299)
        runCurrent()
        assertTrue(backend.searches.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        val (project, options) = backend.searches.single()
        assertEquals("app", project)
        // Whole Word and Regex are independent, as on the web.
        assertEquals(FileSearchOptions("fo", false, true, true, "*.ts", "dist/**", useIgnore = false), options)
        assertEquals(PhoneSearchStatus.DONE, search.state.value.status)
    }

    @Test fun `an open replace field asks the server for replacements, preserve case included`() = runTest {
        val backend = FakeBackend()
        val search = controller(backend)
        search.change { it.copy(query = "foo") }
        search.toggleReplace()
        search.change { it.copy(replace = "") }
        search.togglePreserveCase()
        advanceTimeBy(400)
        runCurrent()
        val options = backend.searches.single().second
        assertEquals("", options.replace)
        assertTrue(options.preserveCase)
    }

    @Test fun `an empty query searches nothing and Clear keeps the options`() = runTest {
        val backend = FakeBackend()
        val search = controller(backend)
        search.toggleCaseSensitive()
        advanceTimeBy(400)
        runCurrent()
        assertTrue(backend.searches.isEmpty())
        backend.answer = { twoFiles }
        search.change { it.copy(query = "foo") }
        search.searchNow()
        runCurrent()
        assertEquals(3, search.state.value.counts.total)
        search.clear()
        assertEquals("", search.state.value.fields.query)
        assertTrue(search.state.value.fields.caseSensitive)
        assertNull(search.state.value.response)
    }

    @Test fun `the engine refusing a pattern marks the field, any other failure is a banner`() = runTest {
        val backend = FakeBackend()
        val search = controller(backend)
        backend.answer = { throw PpmError.Server(400, "regex parse error: unclosed group") }
        search.change { it.copy(query = "(", regex = true) }
        search.searchNow()
        runCurrent()
        assertEquals("regex parse error: unclosed group", search.state.value.fieldError)
        assertNull(search.state.value.bannerError)
        backend.answer = { throw PpmError.Server(503, "Search is unavailable") }
        search.searchNow()
        runCurrent()
        assertNull(search.state.value.fieldError)
        assertEquals(503, search.state.value.bannerError?.status)
    }

    @Test fun `each project keeps its own search`() = runTest {
        val backend = FakeBackend()
        val search = controller(backend)
        search.change { it.copy(query = "one") }
        search.setProject("other")
        assertEquals("", search.state.value.fields.query)
        search.setProject("app")
        assertEquals("one", search.state.value.fields.query)
    }

    @Test fun `replacing one match sends exactly it, drops its row and reports`() = runTest {
        val backend = FakeBackend()
        val replaced = mutableListOf<List<String>>()
        val search = controller(backend, replaced = replaced)
        backend.answer = { twoFiles }
        search.change { it.copy(query = "foo", showReplace = true, replace = "bar") }
        search.searchNow()
        runCurrent()
        backend.report = FileReplaceAnswer(1, 1, emptyList(), emptyList())
        search.replace(PhoneReplaceScope.Match("src/a.ts:4:0"))
        runCurrent()
        assertEquals(listOf(FileReplaceFile("src/a.ts", listOf(FileReplaceMatch(4, 0, "foo", "bar")))), backend.replaces.single().second)
        assertEquals(2, search.state.value.counts.total)
        assertEquals(PhoneSearchNote(PhoneSearchTone.SUCCESS, "1 replacement made"), search.state.value.note)
        assertEquals(listOf(listOf("src/a.ts")), replaced)
    }

    @Test fun `a file with unsaved edits is never sent, and a file changed on disk keeps its rows`() = runTest {
        val backend = FakeBackend()
        val search = controller(backend, unsaved = setOf("src/b.ts"))
        backend.answer = { twoFiles }
        search.change { it.copy(query = "foo", showReplace = true, replace = "bar") }
        search.searchNow()
        runCurrent()
        val built = search.replaceAllFiles()
        assertEquals(listOf("src/b.ts"), built.unsaved)
        assertEquals(listOf("src/a.ts"), built.files.map { it.file })
        backend.report = FileReplaceAnswer(0, 0, listOf(FileReplaceSkip("src/a.ts", "changed")), emptyList())
        search.replace(PhoneReplaceScope.File("src/a.ts"))
        runCurrent()
        assertEquals(3, search.state.value.counts.total)
        assertEquals(PhoneSearchTone.WARNING, search.state.value.note?.tone)
        assertEquals("Skipped src/a.ts: changed on disk since the search.", search.state.value.note?.text)
    }

    @Test fun `dismissed matches leave the counts and the replace`() = runTest {
        val backend = FakeBackend()
        val search = controller(backend)
        backend.answer = { twoFiles }
        search.change { it.copy(query = "foo", showReplace = true, replace = "bar") }
        search.searchNow()
        runCurrent()
        search.dismiss("src/a.ts:1:2")
        search.dismiss("src/b.ts")
        assertEquals(PhoneSearchCounts(1, 1), search.state.value.counts)
        assertEquals("1 result in 1 file", searchResultSummary(search.state.value.counts))
        assertEquals(listOf(FileReplaceMatch(4, 0, "foo", "bar")), search.replaceAllFiles().files.single().matches)
    }
}
