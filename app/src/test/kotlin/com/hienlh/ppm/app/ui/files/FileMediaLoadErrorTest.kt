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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An audio file the server refuses, as `<audio onError>` reports it: at once for what the server will answer the same every
 * time (a 4xx), where Media3 on its own asks three more times, 0, 1 and 2 seconds apart, and says nothing for three seconds. Any
 * other failure still gets the default's retries. Over a real player and a server that counts what it is asked for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileMediaLoadErrorTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val server = MockWebServer()
    private lateinit var model: AppModel
    private val asked = CopyOnWriteArrayList<String>()

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

    /** The server answers every request with [status], and remembers each one for the file. */
    private fun answer(status: Int) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath.endsWith("/raw")) asked += request.url.encodedPath
                return MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body("""{"ok":false,"error":"no"}""").build()
            }
        }
    }

    private fun failsWith(status: Int) {
        answer(status)
        val tab = WorkspaceTab("editor:/tmp/song.mp3", "editor", "song.mp3", "app", buildJsonObject {
            put("filePath", "/tmp/song.mp3")
            put("projectName", "app")
        })
        compose.setContent { FileMediaView(model, tab) }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Failed to load audio.").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `a file the server says is not there is asked for once and is Failed to load audio at once`() {
        failsWith(404)
        assertEquals(1, asked.size)
    }

    @Test
    fun `so is a file it refuses, whatever the 4xx`() {
        failsWith(403)
        assertEquals(1, asked.size)
    }

    @Test
    fun `and one it says to come back for, which is no more a reason to ask again here`() {
        failsWith(429)
        assertEquals(1, asked.size)
    }

    @Test
    fun `a server that fails is asked for again three times, as Media3 does by default`() {
        failsWith(500)
        assertEquals(4, asked.size)
    }
}
