package com.hienlh.ppm.state.osexplorer

import com.hienlh.ppm.client.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HostUploadsTest {
    private fun source(name: String) = HostUploadSource(name, 4, { "data".byteInputStream() })

    @Test fun batchesShareThreeSlotsAndCancellationRefreshesEveryAttemptedDirectory() = runTest {
        var active = 0
        var peak = 0
        val changed = mutableListOf<Set<String>>()
        var finished = 0
        val release = CompletableDeferred<Unit>()
        val backend = object : HostUploadBackend {
            override suspend fun upload(path: String, source: HostUploadSource, overwrite: Boolean, progress: (HostTransferProgress) -> Unit): HostUploadedFile {
                active++
                peak = maxOf(peak, active)
                try { release.await(); progress(HostTransferProgress(4, 4)); return HostUploadedFile(path, 4) }
                finally { active-- }
            }
            override suspend fun stat(path: String) = error("Unexpected stat")
            override suspend fun delete(path: String, permanent: Boolean) = error("Unexpected delete")
        }
        val queue = HostUploads(this, "/", backend, changed::add)
        try {
            val first = queue.enqueue("/one", (1..5).map { source("$it.txt") }, finished = { finished++ })
            queue.enqueue("/two", listOf(source("nested/a.txt")), finished = { finished++ })
            runCurrent()
            assertEquals(3, active)
            queue.cancelBatch(first)
            runCurrent()
            assertTrue(queue.state.value.first().items.all { it.phase == HostUploadPhase.CANCELLED })
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(3, peak)
            assertEquals(2, finished)
            assertTrue(changed.contains(setOf("/one")))
            assertTrue(changed.contains(setOf("/two", "/two/nested")))
            assertEquals(HostUploadPhase.DONE, queue.state.value.last().items.single().phase)
            assertEquals(4L, queue.state.value.last().items.single().bytes)
        } finally { queue.close() }
    }

    @Test fun unsafeBatchIsRejectedBeforeAnySourceIsOpened() = runTest {
        var uploads = 0
        val backend = object : HostUploadBackend {
            override suspend fun upload(path: String, source: HostUploadSource, overwrite: Boolean, progress: (HostTransferProgress) -> Unit): HostUploadedFile {
                uploads++; return HostUploadedFile(path, 4)
            }
            override suspend fun stat(path: String) = Unit
            override suspend fun delete(path: String, permanent: Boolean) = Unit
        }
        val queue = HostUploads(this, "/", backend) {}
        try {
            try { queue.enqueue("/target", listOf(source("ok.txt"), source("../secret"))); fail("Unsafe path accepted") }
            catch (_: IllegalArgumentException) { }
            advanceUntilIdle()
            assertEquals(0, uploads)
            assertTrue(queue.state.value.isEmpty())
        } finally { queue.close() }
    }
}
