package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.protocol.ChangelogSection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The version chip (upgrade-button.tsx): what it offers, Later, and Update now through the restart. */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerUpgradeTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun status(available: String?) = MockResponse.Builder()
        .body("""{"ok":true,"data":{"currentVersion":"0.23.21","availableVersion":${available?.let { "\"$it\"" } ?: "null"},"installMethod":"bun"}}""")
        .build()

    private fun section(version: String) = ChangelogSection(version, "", "- $version")

    private class Harness(scope: TestScope, server: MockWebServer, notes: List<ChangelogSection> = emptyList()) {
        val notices = mutableListOf<UpgradeNotice>()
        var reopenedAt: Long? = null
        val dismissed = mutableSetOf<String>()
        val upgrade = ServerUpgrade(
            ApiClient(server.url("/"), "t"),
            scope.backgroundScope,
            dismissed,
            notify = { notices += it },
            reopen = { reopenedAt = scope.testScheduler.currentTime },
            releaseNotes = { notes },
            now = { scope.testScheduler.currentTime },
        )
    }

    @Test
    fun `only a newer version is on offer, from the server or else from the changelog`() {
        val notes = listOf(section("0.23.25"), section("0.23.21"), section("0.23.20"))
        assertEquals("0.23.24", UpgradeOffer.reported("0.23.24", "0.23.21"))
        assertNull(UpgradeOffer.reported("0.23.21", "0.23.21"))
        assertNull(UpgradeOffer.reported("0.23.24", null))
        assertEquals("0.23.25", UpgradeOffer.announced(notes, "0.23.21"))
        assertNull(UpgradeOffer.announced(notes, "0.23.25"))

        // The server's answer wins; the notes list only what the update brings.
        val offer = ServerUpgrade.State(currentVersion = "0.23.21", availableVersion = "0.23.24", notes = notes).offer(null)
        assertEquals(UpgradeOffer("0.23.21", "0.23.24", true, listOf(section("0.23.25"))), offer)
        // Before /api/upgrade answers, /api/info's version; with nothing newer, every note.
        assertEquals(UpgradeOffer("0.23.25", null, false, notes), ServerUpgrade.State(notes = notes).offer("0.23.25"))
        // Later keeps the version but takes the offer back.
        assertFalse(ServerUpgrade.State(currentVersion = "0.23.21", availableVersion = "0.23.24", dismissed = true).offer(null).hasUpdate)
        // Registry ahead of the changelog: the whole list rather than none.
        assertEquals(notes.drop(1), UpgradeOffer.visibleNotes(notes.drop(1), hasUpdate = true, current = "0.23.21"))
    }

    @Test
    fun `the poll uses the server's cache, an opened popover does not, and Later holds per version`() = runTest {
        val h = Harness(this, server, notes = listOf(section("0.23.26")))
        server.enqueue(status("0.23.24"))
        h.upgrade.check()
        assertEquals("/api/upgrade", server.takeRequest().target)
        assertEquals(UpgradeOffer("0.23.21", "0.23.24", true, null), h.upgrade.state.value.offer(null))

        h.upgrade.dismiss("0.23.24")
        server.enqueue(status("0.23.24"))
        h.upgrade.refresh().join()
        assertEquals("/api/upgrade?refresh=1", server.takeRequest().target)
        assertFalse(h.upgrade.state.value.offer(null).hasUpdate)

        // A failed check leaves the chip as it was.
        server.enqueue(MockResponse.Builder().code(500).body("""{"ok":false,"error":"down"}""").build())
        h.upgrade.check()
        assertEquals("0.23.24", h.upgrade.state.value.availableVersion)

        // No version from the server: the dismissal stands, even for what the changelog names.
        server.enqueue(status(null))
        h.upgrade.check()
        val notes = h.upgrade.loadNotes()
        assertTrue(h.upgrade.state.value.notesLoading)
        notes.join()
        assertEquals(UpgradeOffer("0.23.21", "0.23.26", false, listOf(section("0.23.26"))), h.upgrade.state.value.offer(null))

        // A newer release the server reports was never dismissed.
        server.enqueue(status("0.23.26"))
        h.upgrade.check()
        assertTrue(h.upgrade.state.value.offer(null).hasUpdate)
        assertEquals(setOf("0.23.24"), h.dismissed)
    }

    @Test
    fun `a restart is waited for, asked every 2 s, before the server is read again`() = runTest {
        val h = Harness(this, server)
        server.enqueue(MockResponse.Builder().body("""{"ok":true,"data":{"success":true,"newVersion":"0.23.24","restart":true}}""").build())
        // The tunnel's gateway error, then the stopped page's health, then the new server.
        server.enqueue(MockResponse.Builder().code(502).body("Bad gateway").build())
        server.enqueue(MockResponse.Builder().code(503).body("""{"status":"stopped"}""").build())
        server.enqueue(MockResponse.Builder().body("""{"ok":true,"data":{"status":"running"}}""").build())
        val upgrade = h.upgrade.update("0.23.24")
        assertEquals(ServerUpgrade.Phase.UPGRADING, h.upgrade.state.value.phase)
        upgrade.join()
        assertEquals(ServerUpgrade.Phase.RESTARTING, h.upgrade.state.value.phase)
        assertEquals(4_000L, h.reopenedAt)
        assertEquals(listOf("/api/upgrade/apply", "/api/health", "/api/health", "/api/health"), List(4) { server.takeRequest().target })
        assertEquals(emptyList<UpgradeNotice>(), h.notices)
    }

    @Test
    fun `a server that does not come back is read again after a minute`() = runTest {
        val h = Harness(this, server)
        // The answer lost to the restart, behind a tunnel.
        server.enqueue(MockResponse.Builder().code(502).body("Bad gateway").build())
        repeat(40) { server.enqueue(MockResponse.Builder().code(503).body("""{"status":"stopped"}""").build()) }
        h.upgrade.update("0.23.24").join()
        assertEquals(60_000L, h.reopenedAt)
        assertEquals(1 + 30, server.requestCount)
    }

    @Test
    fun `an answer from PPM is a failure whatever it names, and an unpublished release is said so`() = runTest {
        val h = Harness(this, server)
        server.enqueue(MockResponse.Builder().code(500).body("""{"ok":false,"error":"Already on latest version"}""").build())
        h.upgrade.update("0.23.25").join()
        server.enqueue(MockResponse.Builder().code(500).body("""{"ok":false,"error":"Install failed (exit 1): npm ERR! network request failed"}""").build())
        h.upgrade.update("0.23.25").join()
        server.enqueue(MockResponse.Builder().body("""{"ok":true,"data":{"success":true,"newVersion":"0.23.25","restart":false,"message":"Restart manually: ppm restart"}}""").build())
        h.upgrade.update("0.23.25").join()
        assertEquals(
            listOf(
                UpgradeNotice("v0.23.25 is announced but not published yet — try again shortly."),
                UpgradeNotice("Upgrade failed: Install failed (exit 1): npm ERR! network request failed", error = true),
                UpgradeNotice("Restart manually: ppm restart"),
            ),
            h.notices,
        )
        assertEquals(ServerUpgrade.Phase.IDLE, h.upgrade.state.value.phase)
        assertTrue(h.upgrade.state.value.dismissed)
        assertEquals(setOf("0.23.25"), h.dismissed)
        assertNull(h.reopenedAt)
        // No answer at all is the restart too.
        assertEquals(UpgradeFailure.Restarting, UpgradeOffer.failure(PpmError.Unreachable("Connection reset.")))
        assertEquals(UpgradeFailure.Failed("Wrong password, or it was changed on the server."), UpgradeOffer.failure(PpmError.Unauthorized()))
    }
}
