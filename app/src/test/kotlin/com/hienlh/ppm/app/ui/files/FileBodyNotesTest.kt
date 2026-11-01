package com.hienlh.ppm.app.ui.files

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A file tab that cannot show its file shows one centred line, as the web's editor does: no path
 * row, no wrap or refresh icon, no Retry. Drawn for real, against a server that answers as asked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileBodyNotesTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val server = MockWebServer()
    private lateinit var model: AppModel

    @Before
    fun setUp() {
        server.start()
        model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        model.api = ApiClient(server.url("/"), "secret")
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun json(code: Int, body: String) = MockResponse.Builder().code(code)
        .addHeader("Content-Type", "application/json").body(body).build()

    private fun show(path: String): WorkspaceTab {
        val tab = WorkspaceTab("editor:$path", "editor", path.substringAfterLast('/'), "app", buildJsonObject {
            put("filePath", path)
            put("projectName", "app")
        })
        compose.setContent { FileBody(model, tab) }
        return tab
    }

    /** The first read answers at once, the second waits for [release], and [secondArrived] says it came. */
    private class SecondReadHeld(private val content: String) : Dispatcher() {
        val secondArrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val reads = AtomicInteger()

        override fun dispatch(request: RecordedRequest): MockResponse {
            if (reads.incrementAndGet() > 1) {
                secondArrived.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "the second read was never released" }
            }
            return MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                .body("""{"ok":true,"data":{"content":"$content","encoding":"utf-8"}}""").build()
        }
    }

    private fun waitForRefreshButton() = compose.waitUntil(10_000) {
        compose.onAllNodes(hasContentDescription("Refresh file")).fetchSemanticsNodes().isNotEmpty()
    }

    private fun refreshIsEnabled() = runCatching { compose.onNodeWithContentDescription("Refresh file").assertIsEnabled() }.isSuccess

    private fun assertNoRefreshingLabel() {
        for (label in listOf("Refreshing…", "Refreshing...")) {
            assertEquals("$label is on screen", 0, compose.onAllNodesWithText(label).fetchSemanticsNodes().size)
        }
    }

    private fun waitForText(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    /** What the tab's toolbar and the old error row carried: none of it is on screen. */
    private fun assertNothingAround(path: String) {
        for (label in listOf("Wrap lines", "Refresh file", "Retry")) {
            assertEquals("$label is on screen", 0, compose.onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().size)
            assertEquals("$label is on screen as text", 0, compose.onAllNodesWithText(label).fetchSemanticsNodes().size)
        }
        assertEquals("the path row is on screen", 0, compose.onAllNodesWithText(path).fetchSemanticsNodes().size)
    }

    @Test
    fun `a read the server refused is its own words alone`() {
        server.enqueue(json(403, """{"ok":false,"error":"Access denied","code":"EACCES"}"""))
        show("/tmp/palette-lines.txt")

        waitForText("Access denied")

        compose.onNodeWithText("Access denied").assertIsDisplayed()
        assertNothingAround("/tmp/palette-lines.txt")
    }

    @Test
    fun `a file on its way says Loading file with nothing around it, then the answer replaces it`() {
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                check(release.await(10, TimeUnit.SECONDS))
                return json(404, """{"ok":false,"error":"File not found: preview/check-page.html"}""")
            }
        }
        show("/tmp/slow.txt")

        waitForText("Loading file...")
        assertNothingAround("/tmp/slow.txt")
        release.countDown()

        waitForText("File not found: preview/check-page.html")
        assertEquals(0, compose.onAllNodesWithText("Loading file...").fetchSemanticsNodes().size)
        assertNothingAround("/tmp/slow.txt")
    }

    @Test
    fun `a binary file says so and names itself`() {
        server.enqueue(json(200, """{"ok":true,"data":{"content":"AAEC","encoding":"base64"}}"""))
        show("/tmp/a.bin")

        waitForText("This file is a binary format and cannot be displayed.")

        compose.onNodeWithText("/tmp/a.bin").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Wrap lines").fetchSemanticsNodes().size)
    }

    @Test
    fun `an image that cannot be read says Failed to load image and no cause`() {
        server.enqueue(json(500, """{"ok":false,"error":"Internal failure"}"""))
        show("/tmp/a.png")

        waitForText("Failed to load image.")

        assertEquals(0, compose.onAllNodesWithText("Internal failure").fetchSemanticsNodes().size)
        assertNothingAround("/tmp/a.png")
    }

    @Test
    fun `a PDF that cannot be read says Failed to load PDF and no cause`() {
        server.enqueue(json(500, """{"ok":false,"error":"Internal failure"}"""))
        show("/tmp/a.pdf")

        waitForText("Failed to load PDF.")

        assertEquals(0, compose.onAllNodesWithText("Internal failure").fetchSemanticsNodes().size)
        assertNothingAround("/tmp/a.pdf")
    }

    @Test
    fun `a document that cannot be converted says so and names the cause under it`() {
        server.enqueue(json(500, """{"ok":false,"error":"Failed to convert docx"}"""))
        show("/tmp/a.docx")

        waitForText("Failed to load document.")

        compose.onNodeWithText("Failed to convert docx").assertIsDisplayed()
        assertNothingAround("/tmp/a.docx")
    }

    @Test
    fun `a tab with no file says No file selected and nothing else`() {
        val tab = WorkspaceTab("editor:none", "editor", "none", "app", buildJsonObject { put("projectName", "app") })
        compose.setContent { FileBody(model, tab) }

        compose.onNodeWithText("No file selected.").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("File unavailable").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Connect to the server to read this file.").fetchSemanticsNodes().size)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a reload the Refresh button asked for disables it while it runs, says nothing else, and gives it back`() {
        val reads = SecondReadHeld("hello")
        server.dispatcher = reads
        show("/tmp/x.txt")
        waitForRefreshButton()
        compose.onNodeWithContentDescription("Refresh file").assertIsEnabled()

        compose.onNodeWithContentDescription("Refresh file").performClick()
        assertTrue("the reload never reached the server", reads.secondArrived.await(10, TimeUnit.SECONDS))
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Refresh file").assertIsNotEnabled()
        assertNoRefreshingLabel()
        reads.release.countDown()
        compose.waitUntil(10_000) { refreshIsEnabled() }
    }

    @Test
    fun `a reload the watcher asked for shows nothing and leaves the Refresh button as it was`() {
        val reads = SecondReadHeld("hello")
        server.dispatcher = reads
        val tab = show("/tmp/x.txt")
        waitForRefreshButton()
        val controller = model.fileDocument(tab)!!

        compose.runOnUiThread { controller.fileChanged("app", "/tmp/x.txt") }
        assertTrue("the reload never reached the server", reads.secondArrived.await(10, TimeUnit.SECONDS))
        compose.waitForIdle()

        assertTrue("the reload is not running", controller.state.value.loading)
        compose.onNodeWithContentDescription("Refresh file").assertIsEnabled()
        assertNoRefreshingLabel()
        reads.release.countDown()
        // Asking the screen is what lets the main looper run the controller's answer.
        compose.waitUntil(10_000) { compose.waitForIdle(); !controller.state.value.loading }
        compose.onNodeWithContentDescription("Refresh file").assertIsEnabled()
    }
}
