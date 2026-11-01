package com.hienlh.ppm.state.osexplorer

import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.protocol.HostDirectory
import com.hienlh.ppm.protocol.HostFileEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class ExplorerSessionTest {
    private class Memory : KeyValueStore {
        val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
    }
    private fun prefs() = ExplorerPreferences(Memory(), "server.")
    private fun directory(path: String, entries: List<HostFileEntry> = emptyList()) = HostDirectory(entries, path, path.substringBeforeLast('/').ifEmpty { "/" }, emptyList(), "/", false)
    private class Delayed : HostDirectoryReader {
        data class Read(val path: String, val hidden: Boolean, val answer: CompletableDeferred<HostDirectory> = CompletableDeferred())
        val reads = mutableListOf<Read>()
        override suspend fun read(path: String, hidden: Boolean): HostDirectory {
            val item = Read(path, hidden); reads += item
            // Model a backend that completes after its caller cancelled.
            return withContext(NonCancellable) { item.answer.await() }
        }
    }

    @Test fun `slow old navigation cannot repaint the new folder`() = runTest {
        val reader = Delayed()
        ExplorerSession(reader, prefs(), this, "/a").use { session ->
            runCurrent(); session.show(); runCurrent()
            session.go("/b"); runCurrent()
            assertEquals(listOf("/a", "/b"), reader.reads.map { it.path })
            reader.reads[1].answer.complete(directory("/b")); runCurrent()
            reader.reads[0].answer.complete(directory("/a")); runCurrent()
            assertEquals("/b", session.state.value.directory!!.current)
            assertFalse(session.state.value.loading)
        }
    }

    @Test fun `hidden windows fetch nothing until shown and late hidden answers stay stale`() = runTest {
        val reader = Delayed()
        ExplorerSession(reader, prefs(), this, "/a").use { session ->
            runCurrent(); assertEquals(0, reader.reads.size)
            session.show(); runCurrent(); session.hide()
            reader.reads.single().answer.complete(directory("/a")); runCurrent()
            assertNull(session.state.value.directory)
            session.show(); runCurrent(); assertEquals(2, reader.reads.size)
            reader.reads.last().answer.complete(directory("/a")); runCurrent()
            assertFalse(session.state.value.stale)
        }
    }

    @Test fun `history is capped and navigation after back drops the old forward branch`() = runTest {
        ExplorerSession(HostDirectoryReader { path, _ -> directory(path) }, prefs(), this, "/0").use { session ->
            for (n in 1..130) session.go("/$n")
            assertEquals(100, session.state.value.history.size)
            assertEquals("/31", session.state.value.history.first())
            session.back(); assertEquals("/129", session.state.value.path)
            session.go("/new"); assertFalse(session.state.value.canForward)
            session.go("/replace", replace = true)
            assertFalse("/new" in session.state.value.history)
            session.back(); assertEquals("/129", session.state.value.path)
            session.forward(); assertEquals("/replace", session.state.value.path)
        }
    }

    @Test fun `hidden preference and explicit directory invalidation revalidate without losing selection`() = runTest {
        val p = prefs(); val reader = Delayed()
        val entry = HostFileEntry("a", "/home/a", "file", "file")
        ExplorerSession(reader, p, this, "~").use { session ->
            runCurrent(); session.show(); runCurrent()
            reader.reads[0].answer.complete(directory("/home", listOf(entry))); runCurrent()
            session.select(entry.path)
            p.update(p.state.value.copy(showHidden = true)); runCurrent()
            assertTrue(reader.reads[1].hidden)
            reader.reads[1].answer.complete(directory("/home", listOf(entry))); runCurrent()
            assertEquals(setOf(entry.path), session.state.value.selection)
            session.directoryChanged("/other"); runCurrent(); assertEquals(2, reader.reads.size)
            session.directoryChanged("/home"); runCurrent(); assertEquals(3, reader.reads.size)
            reader.reads[2].answer.complete(directory("/home")); runCurrent()
            assertTrue(session.state.value.selection.isEmpty())
        }
    }

    @Test fun `errors retain host advice and can be retried`() = runTest {
        var failed = true
        ExplorerSession(HostDirectoryReader { path, _ -> if (failed) throw PpmError.Server(403, "Denied", "EPERM", "Host settings") else directory(path) }, prefs(), this, "/a").use { session ->
            runCurrent(); session.show(); runCurrent()
            assertEquals("Host settings", session.state.value.error?.hint)
            failed = false; session.refresh(); runCurrent()
            assertNull(session.state.value.error); assertEquals("/a", session.state.value.directory!!.current)
        }
    }

    @Test fun `preferences and bounded pins remain isolated per server`() {
        val memory = Memory(); val first = ExplorerPreferences(memory, "a.")
        first.update(ExplorerViewPreferences(ExplorerSort.SIZE, true, ExplorerView.COLUMNS, true))
        repeat(55) { first.pin("$it", "/$it") }; first.pin("duplicate", "/1")
        assertEquals(50, first.pins.value.size)
        assertEquals(first.state.value, ExplorerPreferences(memory, "a.").state.value)
        assertEquals(50, ExplorerPreferences(memory, "a.").pins.value.size)
        assertEquals(ExplorerViewPreferences(), ExplorerPreferences(memory, "b.").state.value)
    }

    @Test fun `sort keeps directories first and orders numeric names with base sensitivity`() {
        val names = listOf("file10", "file2", "folder9", "folder10", "File1")
        val entries = names.map { HostFileEntry(it, "/$it", if (it.startsWith("folder")) "directory" else "file", "file") }
        assertEquals(listOf("folder9", "folder10", "File1", "file2", "file10"),
            sortHostEntries(entries, "", ExplorerViewPreferences(), Locale.US).map { it.name })
        assertEquals(listOf("folder10", "folder9", "file10", "file2", "File1"),
            sortHostEntries(entries, "", ExplorerViewPreferences(descending = true), Locale.US).map { it.name })
        assertEquals(listOf("file10"), sortHostEntries(entries, " FILE10 ", ExplorerViewPreferences(), Locale.US).map { it.name })
    }

    @Test fun `name validation follows the host OS and refuses separators on every OS`() {
        for (name in listOf("", ".", "..", "a/b", "a\\b", "a\u0000b")) assertNotNull(validateHostEntryName(name, "linux"))
        for (name in listOf("CON.txt", "lpt9", "bad?", "trailing.", "trailing ")) assertNotNull(validateHostEntryName(name, "win32"))
        assertNull(validateHostEntryName("CON.txt", "linux")); assertNull(validateHostEntryName("Tiếng Việt.txt", "win32"))
    }
}
