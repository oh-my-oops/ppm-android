package com.hienlh.ppm.app.ui.osexplorer

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.files.installBlankFileIcons
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.state.osexplorer.HostPickerMode
import java.util.concurrent.TimeUnit
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

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h600dp")
class HostPathPickerTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val server = MockWebServer()
    private lateinit var model: AppModel
    private val recorded = mutableListOf<RecordedRequest>()

    private val browseBody = """
        {
          "ok": true,
          "data": {
            "entries": [
              {"name": "sub", "path": "/home/me/sub", "type": "directory", "kind": "directory", "modified": "2026-10-01T10:00:00Z"},
              {"name": "main.kt", "path": "/home/me/main.kt", "type": "file", "kind": "text", "size": 2048, "modified": "2026-10-01T10:00:00Z"},
              {"name": "notes.txt", "path": "/home/me/notes.txt", "type": "file", "kind": "text", "size": 512, "modified": "2026-10-01T10:00:00Z"}
            ],
            "current": "/home/me",
            "parent": "/home",
            "breadcrumbs": [{"name": "me", "path": "/home/me"}],
            "sep": "/",
            "truncated": false
          }
        }
    """.trimIndent()

    @Before
    fun setUp() {
        installBlankFileIcons()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(recorded) { recorded += request }
                return when {
                    request.target.startsWith("/api/fs/browse") ->
                        MockResponse.Builder().addHeader("Content-Type", "application/json").body(browseBody).build()
                    else ->
                        MockResponse.Builder().addHeader("Content-Type", "application/json").body("""{"ok":true,"data":{}}""").build()
                }
            }
        }
        server.start()
        model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        model.api = ApiClient(server.url("/"), "secret")
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun waitFor(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun isShown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `file mode filters by accept extensions, shows accept chip and quick access places`() {
        var picked: String? = null
        compose.setContent {
            HostPathPicker(
                model = model,
                mode = HostPickerMode.FILE,
                accept = listOf(".kt"),
                onSelect = { picked = it },
                onCancel = {},
            )
        }
        waitFor("main.kt")
        assertTrue(isShown("Home"))
        assertTrue(isShown("Documents"))
        assertTrue(isShown(".kt"))
        assertFalse("non-matching file is filtered out", isShown("notes.txt"))
        compose.onNodeWithText("main.kt").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Select").performClick()
        assertEquals("/home/me/main.kt", picked)
    }

    @Test
    fun `new folder submits path only on Enter and delete folder sends rmdir after confirmation`() {
        compose.setContent {
            HostPathPicker(
                model = model,
                mode = HostPickerMode.FOLDER,
                onSelect = {},
                onCancel = {},
            )
        }
        waitFor("sub")
        compose.onNodeWithContentDescription("New Folder").performClick()
        waitFor("Folder name")
        compose.onNodeWithText("Folder name").performTextInput("new-dir")
        compose.onNodeWithText("new-dir").performKeyInput { pressKey(Key.Enter) }
        compose.waitUntil(10_000) {
            synchronized(recorded) {
                recorded.any { it.target == "/api/fs/mkdir" } &&
                    recorded.count { it.target.startsWith("/api/fs/browse") } >= 2
            } && !isShown("Folder name")
        }
        compose.waitForIdle()
        val mkdir = synchronized(recorded) { recorded.first { it.target == "/api/fs/mkdir" } }
        assertEquals("POST", mkdir.method)
        assertEquals(
            WireJson.parse("""{"path":"/home/me/new-dir"}"""),
            WireJson.parse(mkdir.body!!.utf8()),
        )

        compose.onNodeWithText("sub").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithContentDescription("Delete selected folder").performClick()
        waitFor("Delete folder?")
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(10_000) {
            synchronized(recorded) { recorded.any { it.target == "/api/fs/rmdir" } }
        }
        val rmdir = synchronized(recorded) { recorded.first { it.target == "/api/fs/rmdir" } }
        assertEquals("DELETE", rmdir.method)
        assertEquals(
            WireJson.parse("""{"path":"/home/me/sub"}"""),
            WireJson.parse(rmdir.body!!.utf8()),
        )
    }
}
