package com.hienlh.ppm.app.ui.files

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
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
import mockwebserver3.SocketEffect
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
 * An audio file the server fails to give, as `<audio onError>` reports it: at once for any HTTP error status (a 4xx, a 5xx, a redirect
 * the transport does not follow), where Media3 on its own asks again three times, 1, 2 and 3 seconds apart, and says nothing for 6.6
 * seconds (measured on a device; Chrome asked once or twice and reported the error within a millisecond of the last answer). A
 * failure with no answer at all, a connection cut, still gets the default's retries: Chrome was not measured there. Over a real
 * player and a server that counts what it is asked for.
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

    /** The server answers every request with [status] (and [location], where it redirects), and remembers each one for the file. */
    private fun answer(status: Int, location: String? = null) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath.endsWith("/raw")) asked += request.url.encodedPath
                return MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body("""{"ok":false,"error":"no"}""")
                    .apply { location?.let { addHeader("Location", it) } }.build()
            }
        }
    }

    /** The server reads each request for the file and closes the connection without a word, as a reset does. */
    private fun cutEveryConnection() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath.endsWith("/raw")) asked += request.url.encodedPath
                return MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
            }
        }
    }

    private fun failsWith(status: Int, location: String? = null) {
        answer(status, location)
        showAndWait()
    }

    private fun showAndWait() {
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
    fun `a server that fails with a 500 is asked once too, and says Failed to load audio at once`() {
        failsWith(500)
        assertEquals(1, asked.size)
    }

    private val busyWords = "The PPM server is busy transcoding other videos. Try again shortly."
    private val encoderWords = "ffmpeg is not installed or has no working H.264 encoder. Install ffmpeg on the PPM server and restart PPM."

    @Test
    fun `a 503 is no different for audio, as the busy words are for a transcode that stopped`() {
        failsWith(503)
        assertEquals(1, asked.size)
        compose.onNodeWithText(busyWords).assertDoesNotExist()
    }

    @Test
    fun `nor is a 501, as the words about the encoder are for a transcode that stopped`() {
        failsWith(501)
        assertEquals(1, asked.size)
        compose.onNodeWithText(encoderWords).assertDoesNotExist()
    }

    @Test
    fun `and a redirect, which the transport does not follow, is an answer as well`() {
        failsWith(302, location = "/elsewhere")
        assertEquals(1, asked.size)
    }

    @Test
    fun `a connection that is cut is no answer, and is asked for again as Media3 does by default`() {
        cutEveryConnection()
        showAndWait()
        assertEquals(4, asked.size)
    }
}
