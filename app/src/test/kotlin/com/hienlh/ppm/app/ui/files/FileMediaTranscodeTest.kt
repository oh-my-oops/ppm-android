package com.hienlh.ppm.app.ui.files

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A video the server can transcode, opened through the file tab with the real ExoPlayer. While it is
 * probed it is a spinner alone (video-preview.tsx), and once the stream stops (video-player.tsx) the
 * web draws a black overlay with the message and a Retry over the video's area, the controls still
 * under it, whose clicks go no further. The server answers as asked: a stream that is no video, or a
 * status, or a stream held until the test says so.
 *
 * The compose rule runs its effects on a standard test dispatcher: with the default, a coroutine that
 * the server's answer wakes on an OkHttp thread carries on there, and the player, which belongs to the
 * main thread, is built and used from that thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileMediaTranscodeTest {
    private val compose = createComposeRule(StandardTestDispatcher())

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val server = MockWebServer()
    private lateinit var model: AppModel

    private val stoppedLine = "Transcoding failed. ffmpeg may have stopped or the file is corrupt."

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

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun waitForText(text: String) = compose.waitUntil(10_000) { compose.waitForIdle(); count(text) > 0 }

    private fun controls() = compose.onAllNodesWithContentDescription("Playback settings").fetchSemanticsNodes().size

    private fun describedAs(label: String) = compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().size

    private fun json(code: Int, body: String) = MockResponse.Builder().code(code)
        .addHeader("Content-Type", "application/json").body(body).build()

    /** A transcodable .mkv: the probe waits for [probeRelease], and every stream request is answered by [stream], after [streamHold] if set. */
    private inner class TranscodeServer : Dispatcher() {
        val probeArrived = CountDownLatch(1)
        val probeRelease = CountDownLatch(1)
        val streams = CopyOnWriteArrayList<HttpUrl>()
        @Volatile var streamHold: CountDownLatch? = null
        @Volatile var stream: () -> MockResponse = { MockResponse.Builder().code(200).addHeader("Content-Type", "video/mp4").body("this is not a video").build() }

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            return when {
                path.endsWith("/probe") -> {
                    probeArrived.countDown()
                    check(probeRelease.await(10, TimeUnit.SECONDS)) { "the probe was never released" }
                    json(200, """{"ok":true,"data":{"transcodable":true,"duration":60,"video":{"codec":"hevc"},"audio":{"codec":"aac"}}}""")
                }
                path.endsWith("/transcode") && request.method == "GET" -> {
                    streams += request.url
                    streamHold?.let { check(it.await(10, TimeUnit.SECONDS)) { "the stream was never released" } }
                    stream()
                }
                else -> json(200, """{"ok":true,"data":{"stopped":false}}""")
            }
        }
    }

    private fun show(transcode: TranscodeServer) {
        server.dispatcher = transcode
        val path = "/tmp/clip.mkv"
        val tab = WorkspaceTab("editor:$path", "editor", "clip.mkv", "app", buildJsonObject {
            put("filePath", path)
            put("projectName", "app")
        })
        compose.setContent { FileMediaView(model, tab) }
    }

    private fun showStopped(): TranscodeServer {
        val transcode = TranscodeServer()
        transcode.probeRelease.countDown()
        show(transcode)
        waitForText(stoppedLine)
        return transcode
    }

    @Test
    fun `a transcode that stopped is the overlay over the picture, with a Retry on it and the controls still under it`() {
        val transcode = showStopped()

        assertTrue("the player never asked for the stream", transcode.streams.isNotEmpty())
        compose.onNodeWithText("Retry").assertIsDisplayed()
        assertEquals("the controls stay under the overlay", 1, controls())
        compose.onNodeWithContentDescription("Fullscreen").assertIsDisplayed()
        assertEquals("the old panel's Retry was a button of the controls' kind, with no text", 0, describedAs("Retry"))
        assertEquals("the old panel's Download is gone", 0, describedAs("Download"))
        assertEquals("and the unsupported screen's is not drawn", 0, count("Download file"))
        // The overlay is over the video's area, which stops where the controls start.
        val controlsTop = compose.onNodeWithContentDescription("Playback settings").getBoundsInRoot().top
        assertTrue("the Retry button runs into the controls", compose.onNodeWithText("Retry").getBoundsInRoot().bottom <= controlsTop)
    }

    @Test
    fun `Retry asks for the stream again from a thousandth of a second on, and the overlay is gone while it loads`() {
        val transcode = showStopped()
        val before = transcode.streams.size
        assertTrue("the first requests are from the start", transcode.streams.all { it.queryParameter("start") == null })
        val hold = CountDownLatch(1)
        transcode.streamHold = hold

        compose.onNodeWithText("Retry").performClick()

        compose.waitUntil(10_000) { compose.waitForIdle(); transcode.streams.size > before }
        compose.waitForIdle()
        assertEquals("the overlay goes when the stream is asked for again", 0, count(stoppedLine))
        assertEquals("nor does it leave its button", 0, count("Retry"))
        assertEquals(listOf("0.001"), transcode.streams.drop(before).map { it.queryParameter("start") }.distinct())
        hold.countDown()
        waitForText(stoppedLine)
    }

    @Test
    fun `a tap or a double tap on the overlay does not play, pause or enlarge the video`() {
        showStopped()
        assertEquals("the video is wanted playing", 1, describedAs("Pause"))

        compose.onNodeWithText(stoppedLine).performTouchInput { click() }
        compose.waitForIdle()
        compose.onNodeWithText(stoppedLine).performTouchInput { doubleClick() }
        compose.waitForIdle()

        assertEquals("a tap paused it", 1, describedAs("Pause"))
        assertEquals("a tap paused it", 0, describedAs("Play"))
        assertEquals("a double tap took it fullscreen", 0, describedAs("Exit fullscreen"))
        compose.onNodeWithText(stoppedLine).assertIsDisplayed()
    }

    @Test
    fun `a busy server's own words are the overlay's message`() {
        val transcode = TranscodeServer()
        transcode.probeRelease.countDown()
        transcode.stream = { json(503, """{"ok":false,"error":"Too many transcodes"}""") }
        show(transcode)

        waitForText("The PPM server is busy transcoding other videos. Try again shortly.")

        compose.onNodeWithText("Retry").assertIsDisplayed()
        assertEquals("the web's sentence is for what the web cannot tell apart", 0, count(stoppedLine))
    }

    @Test
    fun `a video being probed is a spinner alone, with no player, controls or button`() {
        val transcode = TranscodeServer()
        show(transcode)

        compose.waitUntil(10_000) { compose.waitForIdle(); transcode.probeArrived.count == 0L }

        assertEquals("a player", 0, controls())
        for (label in listOf("Play", "Pause", "Fullscreen", "Mute", "Unmute")) {
            assertEquals("$label is on screen", 0, describedAs(label))
        }
        assertEquals("something to press", 0, compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
        assertEquals("a screen of words", 0, count("Download file") + count(stoppedLine))
        assertTrue("the stream is not asked for while probing", transcode.streams.isEmpty())
        transcode.probeRelease.countDown()
        waitForText(stoppedLine)
        assertEquals("the player is back around the overlay", 1, controls())
    }
}
