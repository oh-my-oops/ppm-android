package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.UpgradeResult
import com.hienlh.ppm.protocol.UpgradeStatus
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** The version chip's routes, and the CHANGELOG it lists, asked for as the web asks. */
class UpgradeApiTest {
    @Test
    fun `the poll asks the cached status, an opened popover a fresh one, and apply posts`() = runBlocking {
        val server = MockWebServer()
        val status = """{"ok":true,"data":{"currentVersion":"0.23.21","availableVersion":"0.23.24","installMethod":"bun"}}"""
        server.enqueue(MockResponse.Builder().body(status).build())
        server.enqueue(MockResponse.Builder().body(status).build())
        server.enqueue(MockResponse.Builder().body("""{"ok":true,"data":{"success":true,"newVersion":"0.23.24","restart":true}}""").build())
        server.start()
        try {
            val api = ApiClient(server.url("/"), "secret")
            assertEquals(UpgradeStatus("0.23.21", "0.23.24", "bun"), api.upgradeStatus())
            assertEquals("/api/upgrade", server.takeRequest().target)
            api.upgradeStatus(refresh = true)
            assertEquals("/api/upgrade?refresh=1", server.takeRequest().target)
            assertEquals(UpgradeResult(true, "0.23.24", true, null), api.applyUpgrade())
            val apply = server.takeRequest()
            assertEquals("POST", apply.method)
            assertEquals("/api/upgrade/apply", apply.target)
        } finally {
            server.close()
        }
    }

    @Test
    fun `release notes bust the CDN's cache and keep the newest ten`() = runBlocking {
        val server = MockWebServer()
        val markdown = (30 downTo 1).joinToString("\n") { "## [0.23.$it] - 2026-10-$it\n- change $it" }
        server.enqueue(MockResponse.Builder().body(markdown).build())
        server.enqueue(MockResponse.Builder().code(404).body("404: Not Found").build())
        server.start()
        try {
            val url = server.url("/hienlh/ppm/main/CHANGELOG.md").toString()
            val notes = ReleaseNotes.recent(url = url, now = 1_762_100_000_000)
            assertEquals((30 downTo 21).map { "0.23.$it" }, notes.map { it.version })
            assertEquals("- change 30", notes.first().body)
            assertEquals("/hienlh/ppm/main/CHANGELOG.md?t=1762100000000", server.takeRequest().target)
            try {
                ReleaseNotes.recent(url = url)
                fail("a failed fetch must throw")
            } catch (error: IOException) {
                assertEquals("CHANGELOG fetch failed (404)", error.message)
            }
        } finally {
            server.close()
        }
    }
}
