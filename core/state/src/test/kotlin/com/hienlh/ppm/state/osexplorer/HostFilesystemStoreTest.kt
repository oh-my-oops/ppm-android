package com.hienlh.ppm.state.osexplorer

import com.hienlh.ppm.protocol.HostFilesystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HostFilesystemStoreTest {
    private fun host(name: String) = HostFilesystem("linux", "/", "/home/user", name, emptyList(), emptyList(), emptyList(), emptyList())
    @Test fun `windows share one in-flight probe and a minute of cached results`() = runTest {
        var time = 100L; var requests = 0
        var response = CompletableDeferred<HostFilesystem>()
        HostFilesystemStore({ requests++; response.await() }, this) { time }.use { store ->
            repeat(10) { store.load() }; runCurrent(); assertEquals(1, requests)
            response.complete(host("one")); runCurrent()
            time += 59_999; store.load(); runCurrent(); assertEquals(1, requests)
            time++; response = CompletableDeferred(); store.load(); runCurrent(); assertEquals(2, requests)
            assertEquals("one", store.state.value.host?.hostname)
            response.complete(host("two")); runCurrent(); assertEquals("two", store.state.value.host?.hostname)
        }
    }
    @Test fun `failure can retry and explicit refresh bypasses the warm cache`() = runTest {
        var fail = true; val requests = mutableListOf<Boolean>()
        HostFilesystemStore({ refresh -> requests += refresh; if (fail) error("Denied") else host("ok") }, this).use { store ->
            store.load(); runCurrent(); assertEquals("Denied", store.state.value.error)
            fail = false; store.load(); runCurrent(); assertNull(store.state.value.error)
            store.load(refresh = true); runCurrent(); assertEquals(listOf(false, false, true), requests)
        }
    }
}
