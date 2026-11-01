package com.hienlh.ppm.app.ui.files

import com.hienlh.ppm.client.ProjectTransferProgress
import com.hienlh.ppm.state.files.FileTreeNode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class ExplorerTransfersTest {
    private val file = FileTreeNode("report.txt", "docs/report.txt", false, false)

    @Test fun pickerMustReturnDestinationBeforeAnyDownloadWork() = runBlocking {
        val session = ExplorerTransferSession(this)
        var requests = 0
        try {
            assertNull(session.chosen { _, _ -> requests++; "saved" })
            assertTrue(session.choose(file))
            assertEquals(0, requests)
            assertEquals(ExplorerTransferPhase.CHOOSING, session.state.value.phase)
            session.cancel()
            assertNull(session.chosen { _, _ -> requests++; "saved" })
            assertEquals(0, requests)
            assertTrue(session.choose(file))
            session.chosen { target, progress ->
                assertEquals(file, target)
                requests++
                progress(ProjectTransferProgress(12, 12))
                "Saved report.txt."
            }!!.join()
            assertEquals(1, requests)
            assertEquals(ExplorerTransferPhase.DONE, session.state.value.phase)
        } finally { session.close() }
    }

    @Test fun activePickerOrTransferCannotBeReplacedByAnotherFileDrop() = runBlocking {
        val session = ExplorerTransferSession(this)
        val finish = CompletableDeferred<Unit>()
        var uploaded = 0
        try {
            session.choose(file)
            assertFalse(session.choose(file.copy(path = "another")))
            assertNull(session.upload("other", { uploaded++; "ok" }, {}))
            val job = session.chosen { _, progress -> progress(ProjectTransferProgress(4, null)); finish.await(); "saved" }!!
            assertEquals(ProjectTransferProgress(4, null), session.state.value.progress)
            assertNull(session.upload("other", { uploaded++; "ok" }, {}))
            finish.complete(Unit)
            job.join()
            assertEquals(0, uploaded)
        } finally { session.close() }
    }

    @Test fun failedUploadAlwaysRefreshesItsDirectoryAndKeepsFailure() = runBlocking {
        val session = ExplorerTransferSession(this)
        var refreshed = 0
        try {
            session.upload("2 files", { error("Second file failed; earlier files may already have been saved") }, { refreshed++ })!!.join()
            assertEquals(1, refreshed)
            assertEquals(ExplorerTransferPhase.FAILED, session.state.value.phase)
            assertTrue(session.state.value.message!!.contains("earlier files"))
            assertFalse(session.state.value.busy)
        } finally { session.close() }
    }

    @Test fun cancellingUploadRunsCleanupAndRefreshExactlyOnce() = runBlocking {
        val session = ExplorerTransferSession(this)
        val finish = CompletableDeferred<Unit>()
        var streamsClosed = 0
        var refreshed = 0
        try {
            val job = session.upload("cloud file", { try { finish.await(); "done" } finally { streamsClosed++ } }, { refreshed++ })!!
            session.cancel()
            job.join()
            assertEquals(1, streamsClosed)
            assertEquals(1, refreshed)
            assertEquals(ExplorerTransferPhase.CANCELLED, session.state.value.phase)
            assertFalse(session.state.value.busy)
        } finally { session.close() }
    }

    @Test fun lateCompletionAfterProjectClosesCannotRestoreTransferUi() = runBlocking {
        val session = ExplorerTransferSession(this)
        val finish = CompletableDeferred<Unit>()
        var refreshed = 0
        val job = session.upload("file", { progress ->
            withContext(NonCancellable) { finish.await(); progress(ProjectTransferProgress(99, 99)) }
            "done"
        }, { refreshed++ })!!
        session.close()
        finish.complete(Unit)
        job.join()
        assertEquals(ExplorerTransferState(), session.state.value)
        assertEquals(1, refreshed)
        assertFalse(session.choose(file))
    }

    @Test fun pickerMetadataSurvivesSerializationWithoutTokenOrBytes() {
        val node = FileTreeNode("日本語 & report.txt", "nested/日本語 & report.txt", false, true)
        val serialized = encodeDownloadTarget(node)
        assertEquals(node.copy(isIgnored = false), decodeDownloadTarget(serialized))
        assertFalse(serialized.contains("token"))
        assertNull(decodeDownloadTarget("not json"))
        assertNull(decodeDownloadTarget("{}"))
    }

    @Test fun saveNamesRemovePathSeparatorsAndControlCharactersAndZipFolders() {
        assertEquals("report.txt", explorerDownloadName(file))
        assertEquals("dir_with_slash_new_line.zip", explorerDownloadName(FileTreeNode("dir/with\\slash\nnew\u0000line", "", true, false)))
        assertEquals("folder.zip", explorerDownloadName(FileTreeNode("..", "", true, false)))
        assertEquals("file", explorerDownloadName(FileTreeNode("", "", false, false)))
    }
}
