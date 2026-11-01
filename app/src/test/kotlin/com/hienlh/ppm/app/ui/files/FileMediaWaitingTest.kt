package com.hienlh.ppm.app.ui.files

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A video waiting for its stream, opened through the file tab with the real ExoPlayer against a
 * server that holds the stream back: the web's spinner is in the middle of the stage, above the
 * controls, and takes no touch (`pointer-events-none`), so a tap on it still plays or pauses.
 * Drawn for real (native graphics, 3 pixels to the dp); the spinner's own timing and look are in
 * [VideoWaitingSpinnerTest].
 *
 * The compose rule runs its effects on a standard test dispatcher, as in [FileMediaTranscodeTest]:
 * the player belongs to the main thread.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h400dp-xxhdpi")
class FileMediaWaitingTest {
    private val compose = createComposeRule(StandardTestDispatcher())

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val server = MockWebServer()
    private lateinit var model: AppModel
    private val streams = CopyOnWriteArrayList<HttpUrl>()
    private val release = CountDownLatch(1)

    private fun json(code: Int, body: String) = MockResponse.Builder().code(code)
        .addHeader("Content-Type", "application/json").body(body).build()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path.endsWith("/probe") ->
                        json(200, """{"ok":true,"data":{"transcodable":true,"duration":60,"video":{"codec":"hevc"},"audio":{"codec":"aac"}}}""")
                    path.endsWith("/transcode") && request.method == "GET" -> {
                        streams += request.url
                        check(release.await(30, TimeUnit.SECONDS)) { "the stream was never released" }
                        MockResponse.Builder().code(200).addHeader("Content-Type", "video/mp4").body("this is not a video").build()
                    }
                    else -> json(200, """{"ok":true,"data":{"stopped":false}}""")
                }
            }
        }
        server.start()
        model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        model.api = ApiClient(server.url("/"), "secret")
    }

    @After
    fun tearDown() {
        release.countDown()
        server.close()
    }

    private fun describedAs(label: String) = compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().size

    private fun picture(): PixelMap {
        compose.waitForIdle()
        return compose.onRoot().captureToImage().toPixelMap()
    }

    /** The stage is what is above the controls, which are the last run of ink down the picture. */
    private fun stage(image: PixelMap): IntRange = 0 until (image.inkBands().lastOrNull()?.first ?: image.height)

    private fun showWaiting() {
        val path = "/tmp/clip.mkv"
        val tab = WorkspaceTab("editor:" + path, "editor", "clip.mkv", "app", buildJsonObject {
            put("filePath", path)
            put("projectName", "app")
        })
        compose.setContent { FileMediaView(model, tab) }
        compose.waitUntil(10_000) { compose.waitForIdle(); streams.isNotEmpty() }
        // The 300 ms and the fade pass in frames of the test clock while it waits for the picture.
        compose.waitUntil(10_000) { picture().let { it.peak(stage(it)) >= 0.79f } }
    }

    @Test
    fun `a video waiting for its stream shows the web's spinner in the middle of the stage, above the controls`() {
        showWaiting()

        val image = picture()
        val rows = stage(image)
        assertEquals("one thing is drawn above the controls", 1, image.inkBands().size - 1)
        // 833 pixels measured at 32 dp, at any angle; the old 28 dp white one was 638.
        assertEquals("its area", 833.0, image.coverage(0.8f, rows), 833 * 0.04)
        val ink = image.brightest(rows)
        for (channel in listOf(ink.red, ink.green, ink.blue)) assertEquals("white at 80 percent", 0.8f, channel, 0.02f)
        val box = image.inkBox(rows)
        assertNotNull(box)
        assertEquals("centred across", image.width / 2.0, (box!![0] + box[2]) / 2.0, 8.0)
        assertEquals("centred down the stage", (rows.last + 1) / 2.0, (box[1] + box[3]) / 2.0, 8.0)
    }

    @Test
    fun `a tap on the spinner goes through to the video and pauses it`() {
        showWaiting()
        assertEquals("the video is wanted playing", 1, describedAs("Pause"))
        val image = picture()
        val box = image.inkBox(stage(image))!!

        compose.onRoot().performTouchInput { click(Offset((box[0] + box[2]) / 2f, (box[1] + box[3]) / 2f)) }

        compose.waitUntil(10_000) { compose.waitForIdle(); describedAs("Play") == 1 }
        assertEquals("the tap was taken by the spinner", 0, describedAs("Pause"))
    }
}
