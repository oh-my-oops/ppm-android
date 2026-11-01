package com.hienlh.ppm.app.ui.files

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ThemeTokens
import com.hienlh.ppm.design.vector
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
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
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * An audio file as the web's audio-preview.tsx shows it: the note icon (`size-16 text-text-subtle`, 64 dp) over the file's name
 * (`text-sm text-text-secondary truncate max-w-xs`) in `gap-4 p-4`; and a file's length as Chrome's own controls say it. Drawn
 * for real over a server that either holds the request, so the player has not decided anything, or sends the file, at 3 pixels
 * to the dp (native graphics). The tags inside the file (an ID3 title and artist, which the page used to show in place of the
 * name) cannot be tested here: Robolectric has no decoder, so the player never selects a track and never reports them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class FileMediaAudioTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val server = MockWebServer()
    private val hold = CountDownLatch(1)
    private lateinit var model: AppModel
    private var tokens: ThemeTokens? = null

    @Before
    fun setUp() {
        server.start()
        model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        model.api = ApiClient(server.url("/"), "secret")
    }

    @After
    fun tearDown() {
        hold.countDown()
        server.close()
    }

    /** ffmpeg's 4.000 s tone with its gapless tag, byte for byte the one the device run showed 0:03 for. */
    private fun tone(): ByteArray = javaClass.getResourceAsStream("/media/tone-4s.mp3")!!.use { it.readBytes() }

    private fun serve(bytes: ByteArray) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse.Builder().code(200).addHeader("Content-Type", "audio/mpeg").body(Buffer().write(bytes)).build()
        }
    }

    /** The server never answers (until the test ends): the player has nothing to say yet, and the page is what the tab starts as. */
    private fun holdTheFile() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                hold.await(10, TimeUnit.SECONDS)
                return MockResponse.Builder().code(404).build()
            }
        }
    }

    /** [paused]: the test's clock is stopped, so the player's waiting spinner (300 ms in) never covers the page. */
    private fun show(path: String, paused: Boolean = false) {
        if (paused) compose.mainClock.autoAdvance = false
        val tab = WorkspaceTab("editor:$path", "editor", path.substringAfterLast('/'), "app", buildJsonObject {
            put("filePath", path)
            put("projectName", "app")
        })
        compose.setContent {
            tokens = LocalPpmTokens.current
            FileMediaView(model, tab)
        }
        if (paused) repeat(5) { compose.mainClock.advanceTimeByFrame() }
    }

    private fun texts(): List<String> = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
        .fetchSemanticsNodes().flatMap { node -> node.config[SemanticsProperties.Text].map { it.text } }

    private val clock = Regex("\\d+:\\d\\d / \\d+:\\d\\d")

    private fun waitForClock(): String {
        compose.waitUntil(15_000) { texts().any { clock.matches(it) } }
        return texts().first { clock.matches(it) }
    }

    private fun layout(text: String): TextLayoutResult {
        val out = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(out)
        return out.single()
    }

    private fun near(message: String, expected: Color, actual: Color, tolerance: Float = 0.03f) {
        assertEquals("$message, red", expected.red, actual.red, tolerance)
        assertEquals("$message, green", expected.green, actual.green, tolerance)
        assertEquals("$message, blue", expected.blue, actual.blue, tolerance)
    }

    // The length of a file.

    @Test
    fun `a 4 second mp3 with a gapless tag has a clock of 0 00 over 0 04 and a seek bar 4 seconds long, as Chrome's controls have`() {
        serve(tone())
        show("/music/tone.mp3")

        // Media3 reads this file as 3,999,977 microseconds, a sample short, and ExoPlayer.duration truncates that to 3999 ms.
        assertEquals("0:00 / 0:04", waitForClock())
        val seek = compose.onAllNodes(hasContentDescription("Seek")).fetchSemanticsNodes().single()
        assertEquals(4.0f, seek.config[SemanticsProperties.ProgressBarRangeInfo].range.endInclusive, 0.00001f)
    }

    // What the page is.

    /** The box of the icon in its 20 unit viewport that the glyph's ink fills: where the pixels are, relative to the icon's box. */
    private val musicInk: Rect = ProductIcon.Music.vector.root.filterIsInstance<VectorPath>()
        .map { it.pathData.toPath().getBounds() }
        .reduce { a, b -> Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }

    private fun differs(a: Color, b: Color) = abs(a.red - b.red) > 0.05f || abs(a.green - b.green) > 0.05f || abs(a.blue - b.blue) > 0.05f

    /** The pixels above [limit] that are not the page, as (x, y). */
    private fun ink(image: PixelMap, page: Color, limit: Int): List<Pair<Int, Int>> = buildList {
        for (y in 0 until limit) for (x in 0 until image.width) if (differs(image[x, y], page)) add(x to y)
    }

    @Test
    fun `the page is the file's name, not its path, in 14 px secondary text`() {
        holdTheFile()
        show("/music/an album/song one.mp3", paused = true)

        compose.onNodeWithText("song one.mp3").assertExists()
        assertEquals("the path is on screen", 0, compose.onAllNodesWithText("/music/an album/song one.mp3").fetchSemanticsNodes().size)
        val style = layout("song one.mp3").layoutInput.style
        assertEquals(14.sp, style.fontSize)
        assertEquals(20.sp, style.lineHeight)
        near("the name's colour", tokens!!.text2.color, style.color)
    }

    @Test
    fun `a name too long for 320 dp ends in an ellipsis on one line`() {
        holdTheFile()
        val name = "a very long song title that goes on and on ".repeat(6) + "end.mp3"
        show("/music/$name", paused = true)

        val line = layout(name)
        assertTrue("the name was not cut", line.isLineEllipsized(0))
        assertEquals(1, line.lineCount)
        assertEquals("320 dp wide", 960f, compose.onNodeWithText(name).fetchSemanticsNode().boundsInRoot.width, 1f)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w300dp-h800dp-xxhdpi")
    fun `on a screen narrower than the name's 320 dp the name is cut 16 dp in from each side`() {
        holdTheFile()
        val name = "a very long song title that goes on and on ".repeat(6) + "end.mp3"
        show("/music/$name", paused = true)

        assertTrue("the name was not cut", layout(name).isLineEllipsized(0))
        // 300 dp is 900 px, and p-4 leaves 268 dp of it.
        assertEquals(804f, compose.onNodeWithText(name).fetchSemanticsNode().boundsInRoot.width, 1f)
    }

    @Test
    fun `the note is 64 dp in the subtle colour, centred, with the name 16 dp under it`() {
        holdTheFile()
        show("/music/song.mp3", paused = true)

        val t = tokens!!
        val image = compose.onRoot().captureToImage().toPixelMap()
        val name = compose.onNodeWithText("song.mp3").fetchSemanticsNode().boundsInRoot
        val spots = ink(image, t.panel.color, name.top.toInt() - 1)
        assertTrue("no icon was drawn", spots.isNotEmpty())
        val left = spots.minOf { it.first }
        val right = spots.maxOf { it.first } + 1
        val top = spots.minOf { it.second }
        val bottom = spots.maxOf { it.second } + 1
        // 64 dp is 192 px for the glyph's 20 units: 9.6 px a unit, and the ink is the glyph's own bounds.
        val unit = 9.6f
        assertEquals("the ink's width", musicInk.width * unit, (right - left).toFloat(), 3f)
        assertEquals("the ink's height", musicInk.height * unit, (bottom - top).toFloat(), 3f)
        // The glyph's body is the subtle colour; only its edge is anything else, and an edge of the secondary colour passes through it.
        val subtle = spots.count { (x, y) ->
            abs(image[x, y].red - t.text3.color.red) < 0.02f && abs(image[x, y].green - t.text3.color.green) < 0.02f && abs(image[x, y].blue - t.text3.color.blue) < 0.02f
        }
        assertTrue("only " + subtle + " of the note's " + spots.size + " pixels are the subtle colour", subtle * 2 > spots.size)
        val boxLeft = left - musicInk.left * unit
        val boxTop = top - musicInk.top * unit
        assertEquals("the note is centred", 600f, boxLeft + 96f, 2f)
        assertEquals("the name is centred", 600f, (name.left + name.right) / 2, 1f)
        assertEquals("16 dp between the note and the name", 48f, name.top - (boxTop + 192f), 2f)
    }
}
