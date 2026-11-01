package com.hienlh.ppm.state.files

import com.hienlh.ppm.client.RawFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileDocumentControllerTest {
    private class Reader(private val ignoreCancellation: Boolean = false) : FileDocumentReader {
        data class Read(val project: String?, val path: String, val viewer: FileDocumentViewer,
                        val answer: CompletableDeferred<FileDocumentLoaded> = CompletableDeferred())
        val reads = mutableListOf<Read>()
        var cancellations = 0
        override suspend fun read(project: String?, path: String, viewer: FileDocumentViewer): FileDocumentLoaded {
            val read = Read(project, path, viewer).also(reads::add)
            return try {
                if (ignoreCancellation) withContext(NonCancellable) { read.answer.await() } else read.answer.await()
            } catch (error: CancellationException) { cancellations++; throw error }
        }
        fun answer(text: String) { reads.last().answer.complete(FileDocumentLoaded(content = text)) }
    }

    @Test fun `construction hidden invalidations and hide before dispatch issue no reads`() = runTest {
        val reader = Reader()
        FileDocumentController(reader, "app", "src/a.kt", this).use { doc ->
            doc.refresh(); doc.reconnected(); doc.fileChanged("app", "src/a.kt")
            runCurrent()
            assertTrue(reader.reads.isEmpty())
            doc.show(); doc.hide()
            runCurrent()
            assertTrue(reader.reads.isEmpty())
            assertFalse(doc.state.value.loading)
            doc.show(); doc.show()
            runCurrent()
            assertEquals(1, reader.reads.size)
            assertTrue(doc.state.value.loading)
            reader.answer("hello"); runCurrent()
            assertEquals("hello", doc.state.value.content)
            assertFalse(doc.state.value.stale)
            doc.hide(); doc.show(); runCurrent()
            assertEquals(1, reader.reads.size)
        }
    }

    @Test fun `hidden tab retains last content and coalesces changes into one read on show`() = runTest {
        val reader = Reader()
        FileDocumentController(reader, "app", "README.md", this).use { doc ->
            doc.show(); runCurrent(); reader.answer("old"); runCurrent()
            doc.hide()
            repeat(4) { doc.fileChanged("app", "README.md"); doc.reconnected() }
            runCurrent()
            assertEquals(1, reader.reads.size)
            assertEquals("old", doc.state.value.content)
            assertTrue(doc.state.value.stale)
            doc.show(); runCurrent()
            assertEquals(2, reader.reads.size)
            reader.answer("new"); runCurrent()
            assertEquals("new", doc.state.value.content)
            assertEquals(2, doc.state.value.revision)
        }
    }

    @Test fun `watch changes match both project and exact path and ignore missing path`() = runTest {
        val reader = Reader()
        FileDocumentController(reader, "app", "dir/a.txt", this).use { doc ->
            doc.show(); runCurrent(); reader.answer("one"); runCurrent()
            doc.fileChanged("other", "dir/a.txt")
            doc.fileChanged("app", "dir/b.txt")
            doc.fileChanged("app", null)
            doc.fileChanged("app", "/dir/a.txt")
            runCurrent()
            assertFalse(doc.state.value.stale)
            assertEquals(1, reader.reads.size)
            doc.fileChanged("app", "dir/a.txt"); runCurrent()
            assertEquals(2, reader.reads.size)
            reader.answer("two"); runCurrent()
            assertEquals("two", doc.state.value.content)
        }
    }

    @Test fun `OS explorer document has no project watch subscription but can refresh and reconnect`() = runTest {
        val reader = Reader()
        FileDocumentController(reader, null, "/tmp/a.txt", this).use { doc ->
            doc.show(); runCurrent(); reader.answer("one"); runCurrent()
            doc.fileChanged("app", "/tmp/a.txt"); runCurrent()
            assertEquals(1, reader.reads.size)
            doc.reconnected(); runCurrent(); reader.answer("two"); runCurrent()
            doc.refresh(); runCurrent(); reader.answer("three"); runCurrent()
            assertEquals("three", doc.state.value.content)
            assertTrue(reader.reads.all { it.project == null && it.path == "/tmp/a.txt" })
        }
    }

    @Test fun `invalidation during read rejects stale success and follows it with one fresh read`() = runTest {
        val reader = Reader()
        FileDocumentController(reader, "app", "a.txt", this).use { doc ->
            doc.show(); runCurrent()
            doc.fileChanged("app", "a.txt"); doc.refresh(); doc.reconnected()
            runCurrent()
            assertEquals(1, reader.reads.size)
            reader.answer("stale"); runCurrent()
            assertNull(doc.state.value.content)
            assertEquals(0, doc.state.value.revision)
            assertTrue(doc.state.value.loading)
            assertEquals(2, reader.reads.size)
            reader.answer("fresh"); runCurrent()
            assertEquals("fresh", doc.state.value.content)
            assertEquals(1, doc.state.value.revision)
        }
    }

    @Test fun `hide rejects in flight response without loading a hidden replacement`() = runTest {
        val reader = Reader()
        FileDocumentController(reader, "app", "a.txt", this).use { doc ->
            doc.show(); runCurrent(); doc.hide()
            reader.answer("obsolete"); runCurrent()
            assertNull(doc.state.value.content)
            assertFalse(doc.state.value.loading)
            assertTrue(doc.state.value.stale)
            assertEquals(1, reader.reads.size)
            doc.show(); runCurrent(); reader.answer("current"); runCurrent()
            assertEquals("current", doc.state.value.content)
        }
    }

    @Test fun `reopen waits for obsolete read before fetching again and ignores its error`() = runTest {
        val reader = Reader()
        FileDocumentController(reader, "app", "a.txt", this).use { doc ->
            doc.show(); runCurrent(); doc.hide(); doc.show(); runCurrent()
            assertEquals(1, reader.reads.size)
            reader.reads[0].answer.completeExceptionally(IllegalStateException("obsolete error"))
            runCurrent()
            assertNull(doc.state.value.error)
            assertEquals(2, reader.reads.size)
            reader.answer("current"); runCurrent()
            assertEquals("current", doc.state.value.content)
        }
    }

    @Test fun `dirty buffer ignores pending responses and waits until clean to reload`() = runTest {
        val reader = Reader()
        FileDocumentController(reader, "app", "a.txt", this).use { doc ->
            doc.show(); runCurrent(); reader.answer("original"); runCurrent()
            doc.refresh(); runCurrent(); doc.setDirty(true)
            reader.answer("would overwrite edits"); runCurrent()
            doc.fileChanged("app", "a.txt"); doc.refresh(); doc.reconnected(); runCurrent()
            assertEquals("original", doc.state.value.content)
            assertTrue(doc.state.value.dirty)
            assertFalse(doc.state.value.loading)
            assertEquals(2, reader.reads.size)
            doc.setDirty(false); runCurrent()
            assertEquals(3, reader.reads.size)
            reader.answer("saved"); runCurrent()
            assertEquals("saved", doc.state.value.content)
        }
    }

    @Test fun `failed initial read and refresh retain useful error and manual retry succeeds`() = runTest {
        val reader = Reader()
        FileDocumentController(reader, "app", "a.txt", this).use { doc ->
            doc.show(); runCurrent()
            reader.reads.last().answer.completeExceptionally(IllegalStateException("permission denied")); runCurrent()
            assertEquals("permission denied", doc.state.value.error)
            assertFalse(doc.state.value.loading)
            assertTrue(doc.state.value.stale)
            assertEquals(1, reader.reads.size) // No automatic retry loop.
            doc.refresh(); runCurrent(); reader.answer("cached"); runCurrent()
            doc.refresh(); runCurrent()
            reader.reads.last().answer.completeExceptionally(IllegalStateException("offline")); runCurrent()
            assertEquals("cached", doc.state.value.content)
            assertEquals("offline", doc.state.value.error)
            doc.refresh(); runCurrent()
            assertNull(doc.state.value.error)
            reader.answer("back"); runCurrent()
            assertEquals("back", doc.state.value.content)
            assertFalse(doc.state.value.stale)
        }
    }

    @Test fun `a failed watcher or reconnect read keeps the content, sets no error and stays stale until the next show`() = runTest {
        val triggers = listOf<(FileDocumentController) -> Unit>({ it.fileChanged("app", "a.txt") }, { it.reconnected() })
        for (trigger in triggers) {
            val reader = Reader()
            FileDocumentController(reader, "app", "a.txt", this).use { doc ->
                doc.show(); runCurrent(); reader.answer("old"); runCurrent()

                trigger(doc); runCurrent()
                assertTrue(doc.state.value.loading)
                reader.reads.last().answer.completeExceptionally(IllegalStateException("atomic save in progress")); runCurrent()

                assertEquals("old", doc.state.value.content)
                assertNull(doc.state.value.error)
                assertFalse(doc.state.value.loading)
                assertTrue(doc.state.value.stale)
                assertEquals(2, reader.reads.size) // nothing retries by itself
                doc.hide(); doc.show(); runCurrent()
                assertEquals(3, reader.reads.size)
                reader.answer("new"); runCurrent()
                assertEquals("new", doc.state.value.content)
                assertFalse(doc.state.value.stale)
            }
        }
    }

    @Test fun `a failed watcher read keeps a binary notice or the bytes of an image as it keeps text`() = runTest {
        val first = RawFile(byteArrayOf(1, 2), "test/type")
        val cases = listOf(
            "a.bin" to FileDocumentLoaded(cannotDisplay = true),
            "photo.png" to FileDocumentLoaded(raw = first),
        )
        for ((path, loaded) in cases) {
            val reader = Reader()
            FileDocumentController(reader, "app", path, this).use { doc ->
                doc.show(); runCurrent()
                reader.reads.last().answer.complete(loaded); runCurrent()

                doc.fileChanged("app", path); runCurrent()
                reader.reads.last().answer.completeExceptionally(IllegalStateException("gone")); runCurrent()

                assertNull(doc.state.value.error)
                assertEquals(loaded.cannotDisplay, doc.state.value.cannotDisplay)
                assertSame(loaded.raw, doc.state.value.raw)
                assertTrue(doc.state.value.stale)
            }
        }
    }

    @Test fun `a watcher read that fails with nothing on screen shows its error`() = runTest {
        val reader = Reader()
        FileDocumentController(reader, "app", "a.txt", this).use { doc ->
            doc.show(); runCurrent()
            doc.fileChanged("app", "a.txt"); runCurrent()
            reader.answer("rejected as stale"); runCurrent()
            assertEquals(2, reader.reads.size)

            reader.reads.last().answer.completeExceptionally(IllegalStateException("permission denied")); runCurrent()

            assertEquals("permission denied", doc.state.value.error)
            assertNull(doc.state.value.content)
        }
    }

    @Test fun `a refresh that was asked for shows its error even when a watcher event restarts the read`() = runTest {
        val reader = Reader()
        FileDocumentController(reader, "app", "a.txt", this).use { doc ->
            doc.show(); runCurrent(); reader.answer("old"); runCurrent()
            doc.refresh(); runCurrent()
            doc.fileChanged("app", "a.txt"); runCurrent()
            reader.answer("rejected as stale"); runCurrent()
            assertEquals(3, reader.reads.size)

            reader.reads.last().answer.completeExceptionally(IllegalStateException("offline")); runCurrent()

            assertEquals("old", doc.state.value.content)
            assertEquals("offline", doc.state.value.error)
        }
    }

    @Test fun `once a refresh has succeeded the next watcher failure is quiet`() = runTest {
        val reader = Reader()
        FileDocumentController(reader, "app", "a.txt", this).use { doc ->
            doc.show(); runCurrent(); reader.answer("old"); runCurrent()
            doc.refresh(); runCurrent(); reader.answer("fresh"); runCurrent()

            doc.fileChanged("app", "a.txt"); runCurrent()
            reader.reads.last().answer.completeExceptionally(IllegalStateException("atomic save in progress")); runCurrent()

            assertEquals("fresh", doc.state.value.content)
            assertNull(doc.state.value.error)
        }
    }

    @Test fun `once a refresh is answered the next watcher failure is quiet again, and leaves the line already shown as it was`() = runTest {
        val reader = Reader()
        FileDocumentController(reader, "app", "a.txt", this).use { doc ->
            doc.show(); runCurrent(); reader.answer("old"); runCurrent()
            doc.refresh(); runCurrent()
            reader.reads.last().answer.completeExceptionally(IllegalStateException("offline")); runCurrent()
            assertEquals("offline", doc.state.value.error)

            doc.fileChanged("app", "a.txt"); runCurrent()
            assertTrue(doc.state.value.loading)
            assertEquals("the line went when a quiet read began", "offline", doc.state.value.error)
            reader.reads.last().answer.completeExceptionally(IllegalStateException("offline again")); runCurrent()

            assertEquals("old", doc.state.value.content)
            assertEquals("a quiet failure changed the line", "offline", doc.state.value.error)
            assertTrue(doc.state.value.stale)
            doc.refresh(); runCurrent()
            assertNull("a Refresh clears the line as it starts", doc.state.value.error)
            reader.reads.last().answer.completeExceptionally(IllegalStateException("still offline")); runCurrent()
            assertEquals("still offline", doc.state.value.error)
        }
    }

    @Test fun `a quiet read that succeeds clears the line a failed Refresh left and shows the new content`() = runTest {
        for (trigger in listOf<(FileDocumentController) -> Unit>({ it.fileChanged("app", "a.txt") }, { it.reconnected() })) {
            val reader = Reader()
            FileDocumentController(reader, "app", "a.txt", this).use { doc ->
                doc.show(); runCurrent(); reader.answer("old"); runCurrent()
                doc.refresh(); runCurrent()
                reader.reads.last().answer.completeExceptionally(IllegalStateException("offline")); runCurrent()
                assertEquals("offline", doc.state.value.error)

                trigger(doc); runCurrent()
                assertEquals("offline", doc.state.value.error)
                reader.answer("the file is back"); runCurrent()

                assertNull(doc.state.value.error)
                assertEquals("the file is back", doc.state.value.content)
                assertFalse(doc.state.value.stale)
            }
        }
    }

    @Test fun `binary refresh clears text and later text clears cannot display`() = runTest {
        val reader = Reader()
        FileDocumentController(reader, "app", "a.txt", this).use { doc ->
            doc.show(); runCurrent(); reader.answer("text"); runCurrent()
            doc.refresh(); runCurrent()
            reader.reads.last().answer.complete(FileDocumentLoaded(cannotDisplay = true)); runCurrent()
            assertTrue(doc.state.value.cannotDisplay)
            assertNull(doc.state.value.content)
            doc.refresh(); runCurrent(); reader.answer(""); runCurrent()
            assertFalse(doc.state.value.cannotDisplay)
            assertEquals("", doc.state.value.content)
        }
    }

    @Test fun `image and pdf reload raw bytes on matching changes`() = runTest {
        for (path in listOf("photo.png", "doc.pdf")) {
            val reader = Reader()
            FileDocumentController(reader, "app", path, this).use { doc ->
                doc.show(); runCurrent()
                val first = RawFile(byteArrayOf(1, 2), "test/type")
                reader.reads.last().answer.complete(FileDocumentLoaded(raw = first)); runCurrent()
                assertSame(first, doc.state.value.raw)
                doc.fileChanged("app", path); runCurrent()
                val second = RawFile(byteArrayOf(3, 4), "test/type")
                reader.reads.last().answer.complete(FileDocumentLoaded(raw = second)); runCurrent()
                assertSame(second, doc.state.value.raw)
                assertEquals(2, doc.state.value.revision)
                assertNull(doc.state.value.content)
            }
        }
    }

    @Test fun `delegated viewers expose lazy refresh revisions without reading text or raw bytes`() = runTest {
        for (path in listOf("page.HTML", "video.mkv", "audio.mp3", "data.sqlite")) {
            val reader = Reader()
            FileDocumentController(reader, "app", path, this).use { doc ->
                doc.refresh(); runCurrent()
                assertEquals(0, doc.state.value.revision)
                doc.show(); runCurrent()
                assertEquals(1, doc.state.value.revision)
                doc.fileChanged("app", path)
                assertEquals(2, doc.state.value.revision)
                doc.hide(); doc.reconnected()
                assertEquals(2, doc.state.value.revision)
                doc.show()
                assertEquals(3, doc.state.value.revision)
                assertTrue(reader.reads.isEmpty())
                assertFalse(doc.state.value.loading)
            }
        }
    }

    @Test fun `close cancels waiter is idempotent and cannot be restarted`() = runTest {
        val reader = Reader()
        val doc = FileDocumentController(reader, "app", "a.txt", this)
        doc.show(); runCurrent(); doc.close(); doc.close(); runCurrent()
        assertEquals(1, reader.cancellations)
        val closed = doc.state.value
        doc.show(); doc.hide(); doc.refresh(); doc.reconnected(); doc.fileChanged("app", "a.txt"); doc.setDirty(true)
        runCurrent()
        assertEquals(closed, doc.state.value)
        assertEquals(1, reader.reads.size)
    }

    @Test fun `HTML code mode opts into text and preserves source across hide and show`() = runTest {
        val reader = Reader()
        FileDocumentController(reader, "app", "index.html", this).use { doc ->
            doc.show(); runCurrent()
            assertTrue(reader.reads.isEmpty())
            doc.show(readAsText = true); runCurrent()
            assertEquals(FileDocumentViewer.TEXT, reader.reads.single().viewer)
            reader.answer("<h1>Hello</h1>"); runCurrent()
            assertEquals(FileDocumentViewer.HTML, doc.state.value.viewer)
            assertEquals("<h1>Hello</h1>", doc.state.value.content)
            doc.hide(); doc.show(readAsText = true); runCurrent()
            assertEquals(1, reader.reads.size)
            doc.fileChanged("app", "index.html"); runCurrent()
            assertEquals(2, reader.reads.size)
            reader.answer("new source"); runCurrent()
            assertEquals("new source", doc.state.value.content)
        }
    }

    @Test fun `switching HTML back to preview discards pending source without another read`() = runTest {
        val reader = Reader()
        FileDocumentController(reader, "app", "index.html", this).use { doc ->
            doc.show(readAsText = true); runCurrent()
            doc.hide(); doc.show()
            reader.answer("obsolete source"); runCurrent()
            assertNull(doc.state.value.content)
            assertFalse(doc.state.value.loading)
            assertFalse(doc.state.value.stale)
            assertEquals(1, reader.reads.size)
        }
    }

    @Test fun `late response after close or parent cancellation cannot republish`() = runTest {
        for (cancelParent in listOf(false, true)) {
            val parentJob = Job()
            val reader = Reader(ignoreCancellation = true)
            val doc = FileDocumentController(reader, "app", "a.txt", CoroutineScope(coroutineContext + parentJob))
            try {
                doc.show(); runCurrent()
                if (cancelParent) parentJob.cancel() else doc.close()
                reader.answer("too late"); runCurrent()
                assertNull(doc.state.value.content)
                assertFalse(doc.state.value.loading)
                assertEquals(0, doc.state.value.revision)
                doc.show(); doc.refresh(); runCurrent()
                assertEquals(1, reader.reads.size)
            } finally { doc.close(); parentJob.cancel() }
        }
    }
}
