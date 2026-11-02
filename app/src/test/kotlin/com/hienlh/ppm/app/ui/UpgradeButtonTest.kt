package com.hienlh.ppm.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.ChangelogSection
import com.hienlh.ppm.state.ServerUpgrade
import com.hienlh.ppm.state.UpgradeOffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * The version chip (upgrade-button.tsx) against a server that runs v0.23.21 and reports v0.23.24,
 * and its popover's content. The popup window itself does not run under Robolectric (as for every
 * popover here), so its opening is checked on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp-xhdpi")
class UpgradeButtonTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private lateinit var model: AppModel
    private lateinit var upgrade: ServerUpgrade

    private val notes = listOf(
        ChangelogSection("0.23.24", "2026-11-02", "### Fixed\n- **Chat**: no crash when the `cache` turns cold"),
        ChangelogSection("0.23.21", "2026-10-30", "- An older change"),
    )

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += "${request.method} ${request.target}"
                val body = when (request.url.encodedPath) {
                    "/api/upgrade" -> """{"ok":true,"data":{"currentVersion":"0.23.21","availableVersion":"0.23.24","installMethod":"bun"}}"""
                    "/api/upgrade/apply" -> """{"ok":true,"data":{"success":true,"newVersion":"0.23.24","restart":true}}"""
                    // The stopped page, while the server restarts.
                    else -> return MockResponse.Builder().code(503).body("""{"status":"stopped"}""").build()
                }
                return MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()
            }
        }
        server.start()
        model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        val api = ApiClient(server.url("/"), "secret")
        model.api = api
        upgrade = ServerUpgrade(api, scope, mutableSetOf(), notify = {}, reopen = {}, releaseNotes = { notes })
        model.upgrade = upgrade
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun showChip(prefix: Boolean = true) {
        compose.setContent {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                UpgradeButton(model, ppmText(11f, lineHeight = 16.5f), prefix = prefix)
            }
        }
        // Nothing before the first answer; the app asks once a minute, here once by hand.
        assertFalse(shown("v0.23.21"))
        compose.runOnIdle { scope.launch { upgrade.check() } }
    }

    @Test
    fun `a newer version turns the chip into the update until Later`() {
        showChip()
        compose.waitUntil(5_000) { shown("New version · v0.23.24") }
        assertEquals(listOf("GET /api/upgrade"), requests.toList())
        compose.runOnIdle { upgrade.dismiss("0.23.24") }
        compose.waitUntil(5_000) { shown("v0.23.21") }
        assertFalse(shown("New version · v0.23.24"))
    }

    @Test
    fun `a narrow status bar keeps the version, and Update now waits for the restart`() {
        showChip(prefix = false)
        compose.waitUntil(5_000) { shown("v0.23.24") }
        assertFalse(shown("New version · v0.23.24"))
        compose.runOnIdle { upgrade.update("0.23.24") }
        compose.waitUntil(5_000) { shown("Updating, reconnecting…") }
        compose.waitUntil(5_000) { requests.contains("GET /api/health") }
        assertEquals(listOf("GET /api/upgrade", "POST /api/upgrade/apply", "GET /api/health"), requests.toList())
        assertEquals(ServerUpgrade.Phase.RESTARTING, upgrade.state.value.phase)
    }

    @Test
    fun `the popover lists what the update brings and offers it`() {
        var updated = 0
        var later = 0
        val offer = ServerUpgrade.State(currentVersion = "0.23.21", availableVersion = "0.23.24", notes = notes).offer(null)
        compose.setContent { UpgradePopover(offer, notesLoading = false, update = { updated++ }, later = { later++ }) }
        assertTrue(shown("Update available"))
        assertTrue(shown("v0.23.21 → v0.23.24"))
        assertTrue(shown("v0.23.24"))
        // The heading upper-cased, the Markdown plain, and nothing already installed.
        assertTrue(shown("FIXED"))
        assertTrue(shown("Chat: no crash when the cache turns cold"))
        assertFalse(shown("An older change"))
        compose.onNodeWithText("Update now").performClick()
        compose.onNodeWithText("Later").performClick()
        assertEquals(1, updated)
        assertEquals(1, later)
        assertTrue(shown("Release notes"))
    }

    @Test
    fun `without an update the popover is the release notes alone`() {
        val offer = UpgradeOffer("0.23.24", null, false, null)
        var loading by mutableStateOf(false)
        compose.setContent { UpgradePopover(offer, notesLoading = loading, update = {}, later = {}) }
        assertTrue(shown("PPM v0.23.24"))
        assertTrue(shown("No release notes available right now."))
        assertEquals(2, compose.onAllNodesWithText("Release notes").fetchSemanticsNodes().size)
        assertFalse(shown("Update now"))
        assertFalse(shown("Later"))
        loading = true
        compose.waitForIdle()
        assertTrue(shown("Loading release notes…"))
    }
}
