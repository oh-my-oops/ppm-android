package com.hienlh.ppm.app.ui.files

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Opening an audio file builds the real ExoPlayer, which asks the server for the file. The load
 * control it was given was `LoadControl by DefaultLoadControl()`: Kotlin forwards only the abstract
 * methods of a Java interface, the rest of media3's default to a throw ("getBackBufferDurationUs not
 * implemented") and the ExoPlayer constructor calls the first, so no audio file or native video
 * could be opened at all. Drawn for real, against a server that refuses the file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileMediaPlayerTest {
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

    @Test
    fun `an audio file builds its player, which asks the server for the file and says Failed to load audio when refused`() {
        val paths = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.url.encodedPath
                return MockResponse.Builder().code(404).addHeader("Content-Type", "application/json")
                    .body("""{"ok":false,"error":"File not found"}""").build()
            }
        }
        val tab = WorkspaceTab("editor:/tmp/song.mp3", "editor", "song.mp3", "app", buildJsonObject {
            put("filePath", "/tmp/song.mp3")
            put("projectName", "app")
        })
        compose.setContent { FileMediaView(model, tab) }

        compose.waitUntil(10_000) { compose.onAllNodesWithText("Failed to load audio.").fetchSemanticsNodes().isNotEmpty() }

        assertTrue("the player never asked for the file: $paths", paths.any { it.endsWith("/raw") })
    }
}
