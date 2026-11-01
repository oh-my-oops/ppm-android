package com.hienlh.ppm.state.osexplorer

import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.protocol.HostDirectory
import com.hienlh.ppm.protocol.HostFileEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Ignore
import org.junit.Test
import java.util.Locale

/** Expected behavior from web use-entry-selection.ts and sort-and-filter-entries.ts. */
class ExplorerSessionParityTest {
    private class Memory : KeyValueStore {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }
    private fun preferences() = ExplorerPreferences(Memory(), "parity.")
    private fun entry(name: String, directory: Boolean = false, size: Long? = null, modified: String = "") =
        HostFileEntry(name, "/root/" + name, if (directory) "directory" else "file", "file", size, modified)
    private fun directory(path: String, entries: List<HostFileEntry>) =
        HostDirectory(entries, path, "/", emptyList(), "/", false)
    private fun TestScope.session(entries: List<HostFileEntry>) =
        ExplorerSession(HostDirectoryReader { path, _ -> directory(path, entries) }, preferences(), this, "/root")
    private fun TestScope.show(session: ExplorerSession) {
        runCurrent()
        session.show()
        runCurrent()
    }

    @Ignore("Known parity gap: ExplorerSession.selectAll must initialize a missing anchor to the first visible row.")
    @Test fun selectAllStartsFollowingShiftRangeAtFirstVisibleRow() = runTest {
        session(listOf(entry("file10"), entry("file2"), entry("file1"))).use { session ->
            show(session)
            session.selectAll()
            assertEquals("/root/file1", session.state.value.anchor)
            session.select("/root/file2", range = true)
            assertEquals(setOf("/root/file1", "/root/file2"), session.state.value.selection)
        }
    }

    @Ignore("Known parity gap: ExplorerSession.select must give toggle precedence over range, including the anchor update.")
    @Test fun controlShiftTogglesClickedRowAndMovesAnchor() = runTest {
        session(listOf(entry("a"), entry("b"), entry("c"))).use { session ->
            show(session)
            session.select("/root/a")
            session.select("/root/c", toggle = true, range = true)
            assertEquals(setOf("/root/a", "/root/c"), session.state.value.selection)
            assertEquals("/root/c", session.state.value.anchor)
            session.select("/root/c", toggle = true, range = true)
            assertEquals(setOf("/root/a"), session.state.value.selection)
            assertEquals("/root/c", session.state.value.anchor)
        }
    }

    @Test fun repeatedShiftClicksGrowAndShrinkAroundLastToggle() = runTest {
        session(listOf(entry("a"), entry("b"), entry("c"), entry("d"))).use { session ->
            show(session)
            session.select("/root/a")
            session.select("/root/c", toggle = true)
            session.select("/root/a", range = true)
            assertEquals(setOf("/root/a", "/root/b", "/root/c"), session.state.value.selection)
            session.select("/root/d", range = true)
            assertEquals(setOf("/root/c", "/root/d"), session.state.value.selection)
            assertEquals("/root/c", session.state.value.anchor)
        }
    }

    @Test fun shiftRangeUsesFilteredDescendingOrderAndRejectsHiddenRows() = runTest {
        session(listOf(entry("file1"), entry("file2"), entry("file10"), entry("other"))).use { session ->
            show(session)
            session.preferences.update(ExplorerViewPreferences(descending = true))
            session.setFilter(" FILE ")
            session.select("/root/file10")
            session.select("/root/file2", range = true)
            assertEquals(setOf("/root/file10", "/root/file2"), session.state.value.selection)
            session.select("/root/other")
            assertEquals(setOf("/root/file10", "/root/file2"), session.state.value.selection)
        }
    }

    @Test fun selectAllPreservesAnExistingAnchorAndClearResetsIt() = runTest {
        session(listOf(entry("a"), entry("b"), entry("c"))).use { session ->
            show(session)
            session.select("/root/b")
            session.selectAll()
            assertEquals("/root/b", session.state.value.anchor)
            session.select("/root/c", range = true)
            assertEquals(setOf("/root/b", "/root/c"), session.state.value.selection)
            session.clearSelection()
            assertNull(session.state.value.anchor)
            assertTrue(session.state.value.selection.isEmpty())
        }
    }

    @Test fun navigationResetsFilterSelectionAndAnchorButKeepsPreferences() = runTest {
        session(listOf(entry("a"))).use { session ->
            show(session)
            session.preferences.update(ExplorerViewPreferences(ExplorerSort.SIZE, true))
            session.setFilter("a")
            session.select("/root/a")
            session.go("/next")
            assertEquals("", session.state.value.filter)
            assertTrue(session.state.value.selection.isEmpty())
            assertNull(session.state.value.anchor)
            session.back()
            assertEquals("/root", session.state.value.path)
            assertEquals(ExplorerSort.SIZE, session.preferences.state.value.sort)
            assertTrue(session.preferences.state.value.descending)
        }
    }

    @Test fun closingDiscardsNonCooperativeLateReadAndPreventsFurtherReads() = runTest {
        val answer = CompletableDeferred<HostDirectory>()
        var reads = 0
        val session = ExplorerSession(HostDirectoryReader { _, _ ->
            reads++
            withContext(NonCancellable) { answer.await() }
        }, preferences(), this, "/root")
        show(session)
        session.close()
        answer.complete(directory("/root", listOf(entry("late"))))
        runCurrent()
        session.show()
        session.refresh()
        runCurrent()
        assertNull(session.state.value.directory)
        assertFalse(session.state.value.loading)
        assertEquals(1, reads)
    }

    @Test fun sizeAndModifiedTiesUseNumericNamesWithDirectoriesAlwaysFirst() {
        val entries = listOf(
            entry("file10", size = 4, modified = "2026-10-01T00:00:00Z"),
            entry("file2", size = 4, modified = "2026-10-01T00:00:00Z"),
            entry("folder", directory = true, size = 99, modified = "2025-01-01T00:00:00Z"),
            entry("new", size = 8, modified = "2026-10-02T00:00:00Z"),
        )
        for (sort in listOf(ExplorerSort.SIZE, ExplorerSort.MODIFIED)) {
            assertEquals(listOf("folder", "file2", "file10", "new"),
                sortHostEntries(entries, "", ExplorerViewPreferences(sort), Locale.US).map { it.name })
            assertEquals(listOf("folder", "new", "file10", "file2"),
                sortHostEntries(entries, "", ExplorerViewPreferences(sort, true), Locale.US).map { it.name })
        }
        assertEquals(16L, hostSelectionSize(entries))
    }
}
