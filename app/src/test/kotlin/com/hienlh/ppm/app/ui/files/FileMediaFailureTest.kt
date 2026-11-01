package com.hienlh.ppm.app.ui.files

import android.app.Application
import android.net.Uri
import android.view.PointerIcon
import android.view.View
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.state.media.MediaFailure
import com.hienlh.ppm.state.media.TRANSCODE_FAILURE_LINE
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * A media file that cannot play shows what the web's audio-preview.tsx, video-preview.tsx and
 * video-player.tsx show. For audio the warning icon over "Failed to load audio.", and for a video that
 * no server tool can transcode, the codecs, how to get ffmpeg and a "Download file" button: no player
 * controls, no Retry. A transcode that stopped is a black overlay with the message and a Retry over the
 * video's area, the controls still under it, and a video being probed is a spinner alone. The screens
 * are drawn on their own in boxes of a size the test sets, then through the file tab against a server
 * that answers as asked. Robolectric measures a character as one pixel, so a wrap is seen at a density
 * that makes the web's 384, 24 and 16 smaller than the text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileMediaFailureTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val server = MockWebServer()
    private lateinit var model: AppModel

    private val line = "This video cannot be played in the browser (hevc + aac)."
    private val hint = "Install ffmpeg on the PPM server (e.g. winget install ffmpeg, brew install ffmpeg, apt install ffmpeg) " +
        "and restart PPM to transcode it on the fly."

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

    private fun waitForText(text: String) = compose.waitUntil(10_000) { count(text) > 0 }

    private fun assertNoPlayerChrome() {
        for (label in listOf("Play", "Pause", "Playback settings", "Retry")) {
            assertEquals("$label is on screen", 0, compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().size)
            assertEquals("$label is on screen as text", 0, count(label))
        }
        assertEquals("the old error panel is on screen", 0, count("This video cannot be played."))
    }

    // The screens on their own.

    @Test
    fun `an audio file that failed is the warning over Failed to load audio and nothing else`() {
        compose.setContent { MediaFailureView(MediaFailure.Audio, onDownload = {}, onRetry = {}) }

        compose.onNodeWithText("Failed to load audio.").assertIsDisplayed()
        assertEquals("the web's audio screen has nothing to press", 0, compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
        assertEquals(0, count("Download file"))
        assertNoPlayerChrome()
    }

    @Test
    fun `an unsupported video names its codecs, says how to get ffmpeg and offers the download, once`() {
        var downloads = 0
        compose.setContent { MediaFailureView(MediaFailure.UnsupportedVideo("hevc + aac"), { downloads++ }, onRetry = {}) }

        compose.onNodeWithText(line).assertIsDisplayed()
        compose.onNodeWithText(hint).assertIsDisplayed()
        compose.onNodeWithText("Download file").assertIsDisplayed().assertIsEnabled().assertHasClickAction()
        assertEquals("the download button is the only thing to press", 1, compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
        assertNoPlayerChrome()

        compose.onNodeWithText("Download file").performClick()
        assertEquals(1, downloads)
    }

    @Test
    fun `a video with no codecs to name has no brackets`() {
        compose.setContent { MediaFailureView(MediaFailure.UnsupportedVideo(""), onDownload = {}, onRetry = {}) }

        compose.onNodeWithText("This video cannot be played in the browser.").assertIsDisplayed()
    }

    @Test
    fun `the install commands are monospace and the rest of the hint is not`() {
        compose.setContent { MediaFailureView(MediaFailure.UnsupportedVideo("hevc + aac"), onDownload = {}, onRetry = {}) }

        val text = compose.onNodeWithText(hint).fetchSemanticsNode().config[SemanticsProperties.Text].single()
        val mono = text.spanStyles.filter { it.item.fontFamily == PpmFonts.mono }
        assertEquals(
            listOf("ffmpeg", "winget install ffmpeg", "brew install ffmpeg", "apt install ffmpeg"),
            mono.map { text.text.substring(it.start, it.end) },
        )
        assertTrue("ligatures are off in code", mono.all { it.item.fontFeatureSettings == "\"liga\" 0, \"calt\" 0" })
        assertEquals("only the code is styled", mono.size, text.spanStyles.size)
    }

    @Test
    fun `the download button is dimmed and inert while a download runs, and back once it is done`() {
        var downloads = 0
        var running by mutableStateOf(false)
        compose.setContent { MediaFailureView(MediaFailure.UnsupportedVideo("hevc + aac"), if (running) null else ({ downloads++ }), onRetry = {}) }

        compose.onNodeWithText("Download file").assertIsEnabled().performClick()
        assertEquals(1, downloads)

        running = true
        compose.waitForIdle()
        compose.onNodeWithText("Download file").assertIsNotEnabled().performClick()
        assertEquals("a download that is running is not started again", 1, downloads)

        running = false
        compose.waitForIdle()
        compose.onNodeWithText("Download file").assertIsEnabled().performClick()
        assertEquals(2, downloads)
    }

    /** The style [text] was laid out in, as the text node reports its layout. */
    private fun styleOf(text: String): TextStyle {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
        return layouts.single().layoutInput.style
    }

    @Test
    fun `the line is 14 on 20 in the secondary colour and the hint 12 on 16 in the subtle one, both centred`() {
        var secondary = Color.Unspecified
        var subtle = Color.Unspecified
        compose.setContent {
            secondary = LocalPpmTokens.current.text2.color
            subtle = LocalPpmTokens.current.text3.color
            MediaFailureView(MediaFailure.UnsupportedVideo("hevc + aac"), onDownload = {}, onRetry = {})
        }

        val first = styleOf(line)
        assertEquals(14f, first.fontSize.value, 0f)
        assertEquals(20f, first.lineHeight.value, 0f)
        assertEquals(secondary, first.color)
        assertEquals(TextAlign.Center, first.textAlign)
        val second = styleOf(hint)
        assertEquals(12f, second.fontSize.value, 0f)
        assertEquals(16f, second.lineHeight.value, 0f)
        assertEquals(subtle, second.color)
        assertEquals(TextAlign.Center, second.textAlign)
    }

    /** The unsupported screen alone in a [width] x [height] box (dp at [density]), whose bounds [box] answers. */
    private fun showSized(width: Int, height: Int, density: Float = 1f) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density)) {
                Box(Modifier.testTag("box").requiredSize(width.dp, height.dp)) {
                    MediaFailureView(MediaFailure.UnsupportedVideo("hevc + aac"), onDownload = {}, onRetry = {})
                }
            }
        }
    }

    private fun box() = compose.onNodeWithTag("box").getBoundsInRoot()

    @Test
    fun `the button is 44 high with 10 of padding in a 1 border, a 16 icon and 14 before the label`() {
        showSized(800, 600)

        val button = compose.onNodeWithText("Download file").getBoundsInRoot()
        val label = compose.onNodeWithText("Download file", useUnmergedTree = true).getBoundsInRoot()
        assertEquals(44f, (button.bottom - button.top).value, 0f)
        // border 1 + padding 10 + icon 16 + gap 6 + the icon's mr-2 8, and padding 10 + border 1 after it.
        assertEquals(41f, (label.left - button.left).value, 0.6f)
        assertEquals(11f, (button.right - label.right).value, 0.6f)
    }

    @Test
    fun `the column is centred in both directions and 12 apart`() {
        showSized(800, 600)

        val box = box()
        val first = compose.onNodeWithText(line).getBoundsInRoot()
        val second = compose.onNodeWithText(hint).getBoundsInRoot()
        val button = compose.onNodeWithText("Download file").getBoundsInRoot()
        assertEquals(12f, (second.top - first.bottom).value, 1f)
        assertEquals(12f, (button.top - second.bottom).value, 1f)
        // The icon is 40 high and 12 above the line: what is above it and below the button is the same room.
        assertEquals((box.bottom - button.bottom).value, (first.top - 52.dp - box.top).value, 1f)
        assertEquals((box.left + box.right).value / 2f, (button.left + button.right).value / 2f, 1f)
        assertEquals((box.left + box.right).value / 2f, (first.left + first.right).value / 2f, 1f)
    }

    @Test
    fun `the hint wraps at 384 wide, however wide the screen is`() {
        // At a quarter of the density 384 dp is 96 pixels, narrower than the hint's 146 characters.
        showSized(3200, 2400, density = 0.25f)

        val bounds = compose.onNodeWithText(hint).getBoundsInRoot()
        assertEquals(384f, (bounds.right - bounds.left).value, 0.6f)
    }

    @Test
    fun `the hint wraps 24 in from a screen narrower than that`() {
        // 320 dp at a quarter is 80 pixels, 24 dp of padding is 6 on each side, and the 68 left is still short of the hint.
        showSized(320, 2400, density = 0.25f)

        val bounds = compose.onNodeWithText(hint).getBoundsInRoot()
        assertEquals(272f, (bounds.right - bounds.left).value, 0.6f)
    }

    // A transcode that stopped, on its own.

    private val stoppedLine = "Transcoding failed. ffmpeg may have stopped or the file is corrupt."

    @Test
    fun `a stopped transcode is the web's sentence over a Retry button, and Retry is the only thing to press`() {
        var retries = 0
        compose.setContent { MediaFailureView(MediaFailure.TranscodeStopped(TRANSCODE_FAILURE_LINE), onDownload = {}, onRetry = { retries++ }) }

        compose.onNodeWithText(stoppedLine).assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed().assertIsEnabled().assertHasClickAction()
        assertEquals("the overlay swallows taps without being something to press", 1, compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
        assertEquals("the web's overlay has no download", 0, count("Download file"))
        assertEquals("and no unsupported screen's hint", 0, count(hint))

        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }

    /** The stopped overlay alone in a [width] x [height] box (dp at [density]), whose bounds [box] answers. */
    private fun showStoppedSized(width: Int, height: Int, density: Float = 1f) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density)) {
                Box(Modifier.testTag("box").requiredSize(width.dp, height.dp)) {
                    MediaFailureView(MediaFailure.TranscodeStopped(stoppedLine), onDownload = {}, onRetry = {})
                }
            }
        }
    }

    @Test
    fun `the Retry button is 44 high with 16 of padding in a 1 border and no icon`() {
        showStoppedSized(800, 600)

        val button = compose.onNodeWithText("Retry").getBoundsInRoot()
        val label = compose.onNodeWithText("Retry", useUnmergedTree = true).getBoundsInRoot()
        assertEquals(44f, (button.bottom - button.top).value, 0f)
        // px-4 beside a 1 border: with no icon, has-[>svg]:px-2.5 does not apply.
        assertEquals(17f, (label.left - button.left).value, 0.6f)
        assertEquals(17f, (button.right - label.right).value, 0.6f)
    }

    @Test
    fun `the message and the button are centred in both directions and 12 apart`() {
        showStoppedSized(800, 600)

        val box = box()
        val message = compose.onNodeWithText(stoppedLine).getBoundsInRoot()
        val button = compose.onNodeWithText("Retry").getBoundsInRoot()
        assertEquals(12f, (button.top - message.bottom).value, 1f)
        assertEquals((box.bottom - button.bottom).value, (message.top - box.top).value, 1f)
        assertEquals((box.left + box.right).value / 2f, (button.left + button.right).value / 2f, 1f)
        assertEquals((box.left + box.right).value / 2f, (message.left + message.right).value / 2f, 1f)
    }

    @Test
    fun `the message wraps 16 in from the edge of the video's area`() {
        // 200 dp at a quarter of the density is 50 pixels, 16 dp of padding 4 on each side, and the 42 left are short of the 67 characters.
        showStoppedSized(200, 2400, density = 0.25f)

        val bounds = compose.onNodeWithText(stoppedLine).getBoundsInRoot()
        assertEquals(168f, (bounds.right - bounds.left).value, 0.6f)
    }

    @Test
    fun `the message is 14 on 20 centred in the secondary colour and the label 14 medium in the same`() {
        var secondary = Color.Unspecified
        compose.setContent {
            secondary = LocalPpmTokens.current.text2.color
            MediaFailureView(MediaFailure.TranscodeStopped(stoppedLine), onDownload = {}, onRetry = {})
        }

        val message = styleOf(stoppedLine)
        assertEquals(14f, message.fontSize.value, 0f)
        assertEquals(20f, message.lineHeight.value, 0f)
        assertEquals(secondary, message.color)
        assertEquals(TextAlign.Center, message.textAlign)
        val label = styleOf("Retry")
        assertEquals(14f, label.fontSize.value, 0f)
        assertEquals(20f, label.lineHeight.value, 0f)
        assertEquals(FontWeight.Medium, label.fontWeight)
        assertEquals("at rest the label is the colour of the box around it", secondary, label.color)
    }

    private lateinit var view: View

    private fun icon(type: Int): PointerIcon = PointerIcon.getSystemIcon(ApplicationProvider.getApplicationContext(), type)

    private fun cursorOver(text: String): PointerIcon? {
        compose.onNodeWithText(text).performMouseInput { moveTo(center) }
        compose.waitForIdle()
        return view.pointerIcon
    }

    @Test
    fun `the Download file link shows the hand, as a link does, also while a download runs`() {
        var running by mutableStateOf(false)
        compose.setContent {
            view = LocalView.current
            MediaFailureView(MediaFailure.UnsupportedVideo("hevc"), if (running) null else ({}), onRetry = {})
        }

        assertEquals(icon(PointerIcon.TYPE_HAND), cursorOver("Download file"))
        running = true
        compose.waitForIdle()
        assertEquals("a link does not change its cursor while it is dimmed", icon(PointerIcon.TYPE_HAND), cursorOver("Download file"))
    }

    @Test
    fun `the Retry button shows the arrow, as a button does`() {
        compose.setContent {
            view = LocalView.current
            MediaFailureView(MediaFailure.TranscodeStopped(stoppedLine), onDownload = {}, onRetry = {})
        }

        // The arrow is the window's own, which it answers with no icon at all.
        assertNull(cursorOver("Retry"))
    }

    // Through the file tab.

    private fun mediaTab(path: String) = WorkspaceTab("editor:$path", "editor", path.substringAfterLast('/'), "app", buildJsonObject {
        put("filePath", path)
        put("projectName", "app")
    })

    private fun json(code: Int, body: String) = MockResponse.Builder().code(code)
        .addHeader("Content-Type", "application/json").body(body).build()

    /** The probe waits for [release]; anything else (the transcode stop) is answered at once. */
    private class ProbeServer(private val answer: MockResponse) : Dispatcher() {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val paths = CopyOnWriteArrayList<String>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            paths += request.url.encodedPath
            if (!request.url.encodedPath.endsWith("/probe")) {
                return MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                    .body("""{"ok":true,"data":{"stopped":false}}""").build()
            }
            arrived.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "the probe was never released" }
            return answer
        }
    }

    /** The file picker, answered by the test: what it was asked for, and an answer on request. */
    private class PickerRegistry : ActivityResultRegistry() {
        val asked = CopyOnWriteArrayList<Pair<Int, String>>()

        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            asked += requestCode to input.toString()
        }
    }

    private fun showMedia(tab: WorkspaceTab, picker: PickerRegistry = PickerRegistry()) {
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = picker }
        compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { FileMediaView(model, tab) } }
    }

    @Test
    fun `a video the server cannot transcode goes from the spinner to the unsupported screen, codecs and all`() {
        val probe = ProbeServer(json(200, """{"ok":true,"data":{"transcodable":false,"video":{"codec":"hevc"},"audio":{"codec":"aac"}}}"""))
        server.dispatcher = probe
        showMedia(mediaTab("/tmp/clip.mkv"))

        compose.waitUntil(10_000) { compose.waitForIdle(); probe.arrived.count == 0L }
        assertEquals("a video still being probed is no unsupported screen", 0, count("Download file"))
        assertEquals("nor a player", 0, compose.onAllNodesWithContentDescription("Playback settings").fetchSemanticsNodes().size)
        probe.release.countDown()

        waitForText("Download file")
        compose.onNodeWithText(line).assertIsDisplayed()
        compose.onNodeWithText(hint).assertIsDisplayed()
        assertNoPlayerChrome()
    }

    @Test
    fun `an audio file the server refuses is Failed to load audio, with the player gone`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = json(404, """{"ok":false,"error":"File not found"}""")
        }
        showMedia(mediaTab("/tmp/song.mp3"))

        waitForText("Failed to load audio.")

        assertEquals("the web's audio screen has nothing to press", 0, compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
        assertNoPlayerChrome()
    }

    @Test
    fun `a probe the server failed is the same screen with no codecs`() {
        val probe = ProbeServer(json(500, """{"ok":false,"error":"ffprobe exploded"}"""))
        server.dispatcher = probe
        showMedia(mediaTab("/tmp/clip.avi"))
        probe.release.countDown()

        waitForText("Download file")
        compose.onNodeWithText("This video cannot be played in the browser.").assertIsDisplayed()
        compose.onNodeWithText(hint).assertIsDisplayed()
        assertEquals("the server's own words are not shown", 0, count("ffprobe exploded"))
        assertNoPlayerChrome()
    }

    @Test
    fun `the unsupported screen's button asks the system picker for the file's name, and is inert until the picker answers`() {
        val probe = ProbeServer(json(200, """{"ok":true,"data":{"transcodable":false,"video":{"codec":"hevc"}}}"""))
        server.dispatcher = probe
        val picker = PickerRegistry()
        showMedia(mediaTab("/tmp/clips/holiday clip.mkv"), picker)
        probe.release.countDown()
        waitForText("Download file")
        compose.onNodeWithText("This video cannot be played in the browser (hevc).").assertIsDisplayed()

        compose.onNodeWithText("Download file").assertIsEnabled().performClick()

        assertEquals(listOf("holiday clip.mkv"), picker.asked.map { it.second })
        compose.waitForIdle()
        compose.onNodeWithText("Download file").assertIsNotEnabled()
        compose.onNodeWithText("Download file").performClick()
        assertEquals("the picker is not opened twice", 1, picker.asked.size)

        picker.dispatchResult<Uri?>(picker.asked.single().first, null)
        compose.waitForIdle()
        compose.onNodeWithText("Download file").assertIsEnabled()
    }
}
