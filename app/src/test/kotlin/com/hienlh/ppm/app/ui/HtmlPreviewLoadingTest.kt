package com.hienlh.ppm.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ThemeTokens
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.state.preview.PreviewLoads
import com.hienlh.ppm.web.HtmlPreview
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
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * An HTML page's preview while the server is still being asked for the page (`POST /api/html-preview` in flight): the web's
 * `role=status flex flex-1 items-center justify-center gap-2 text-sm text-muted-foreground` with a `size-5 animate-spin`
 * Loader2 in front of "Loading preview…" (the real ellipsis). Drawn for real over a server that holds the request until the
 * test lets go of it, at 3 pixels to the dp (native graphics).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class HtmlPreviewLoadingTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val server = MockWebServer()
    private val release = CountDownLatch(1)
    private var tokens: ThemeTokens? = null

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                check(release.await(10, TimeUnit.SECONDS)) { "the preview request was never released" }
                return MockResponse.Builder().code(500).addHeader("Content-Type", "application/json")
                    .body("""{"ok":false,"error":"Preview unavailable"}""").build()
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        release.countDown()
        server.close()
    }

    /**
     * [paused]: the test's clock is stopped before anything is drawn. Compose's tests cancel an infinite animation that
     * starts while the clock runs by itself ("Infinite animations are disabled on tests"), so a spinner can only be seen
     * turning from a clock that was paused when it began.
     */
    private fun show(paused: Boolean = false) {
        if (paused) compose.mainClock.autoAdvance = false
        compose.setContent {
            tokens = LocalPpmTokens.current
            HtmlPreview("page.html", "app", 0, ApiClient(server.url("/"), "secret"), PreviewLoads(), Modifier.fillMaxSize())
        }
        if (paused) repeat(5) { compose.mainClock.advanceTimeByFrame() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(LOADING).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun layout(text: String): TextLayoutResult {
        val out = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(out)
        return out.single()
    }

    private fun near(message: String, expected: Color, actual: Color, tolerance: Float = 0.02f) {
        assertEquals("$message, red", expected.red, actual.red, tolerance)
        assertEquals("$message, green", expected.green, actual.green, tolerance)
        assertEquals("$message, blue", expected.blue, actual.blue, tolerance)
    }

    @Test
    fun `the request is asked once, and what shows while it is held is Loading preview with the real ellipsis`() {
        show()

        compose.onNodeWithText(LOADING).assertExists()
        assertEquals("three dots", 0, compose.onAllNodesWithText("Loading preview...").fetchSemanticsNodes().size)
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/api/html-preview", request.target)
        assertEquals(WireJson.parse("""{"filePath":"page.html","projectName":"app"}"""), WireJson.parse(request.body!!.utf8()))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the line is 14 px secondary text`() {
        show()

        val style = layout(LOADING).layoutInput.style
        assertEquals(14.sp, style.fontSize)
        assertEquals(20.sp, style.lineHeight)
        near("the line's colour", tokens!!.text2.color, style.color)
    }

    @Test
    fun `a 20 px spinner and the line 8 px after it are centred in the preview, both ways`() {
        show()

        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val text = compose.onNodeWithText(LOADING).fetchSemanticsNode().boundsInRoot
        // The spinner is 60 px (20 dp) wide and the gap 24 px (8 dp), in front of the text.
        val left = text.left - 84f
        assertEquals("left and right margins of the pair", left - root.left, root.right - text.right, 1f)
        assertEquals("above and below the line", text.top - root.top, root.bottom - text.bottom, 1f)
        assertEquals("the line is 20 dp high", 60f, text.height, 0.5f)
    }

    /** The pixels in the box the spinner is drawn in: 60 px square, 84 px before the line, on the line's middle. */
    private fun spinner(): List<Color> {
        val text = compose.onNodeWithText(LOADING).fetchSemanticsNode().boundsInRoot
        val image = compose.onRoot().captureToImage().toPixelMap()
        val x = (text.left - 84f).toInt()
        val y = ((text.top + text.bottom) / 2 - 30f).toInt()
        return buildList { for (j in 0 until 60) for (i in 0 until 60) add(image[x + i, y + j]) }
    }

    private fun differing(a: List<Color>, b: List<Color>) = a.indices.count { index ->
        Math.abs(a[index].red - b[index].red) > 0.1f || Math.abs(a[index].green - b[index].green) > 0.1f || Math.abs(a[index].blue - b[index].blue) > 0.1f
    }

    @Test
    fun `the spinner is drawn in the secondary colour, and turns once in a second`() {
        show(paused = true)

        val start = spinner()
        val ink = tokens!!.text2.color
        assertTrue("no pixel of the spinner is the secondary colour", start.any { Math.abs(it.red - ink.red) < 0.03f && Math.abs(it.green - ink.green) < 0.03f && Math.abs(it.blue - ink.blue) < 0.03f })

        compose.mainClock.advanceTimeBy(250, ignoreFrameDuration = true)
        val quarter = spinner()
        compose.mainClock.advanceTimeBy(250, ignoreFrameDuration = true)
        val half = spinner()
        compose.mainClock.advanceTimeBy(500, ignoreFrameDuration = true)
        val whole = spinner()
        // A turn of the 5 px stroke moves about 280 of its pixels; a frame's worth (the clock ticks every 16 ms) about 25.
        assertTrue("a quarter of a second on, the spinner has not moved (" + differing(start, quarter) + " pixels differ)", differing(start, quarter) > 150)
        assertTrue("half a second on, the spinner is where it began (" + differing(start, half) + " pixels differ)", differing(start, half) > 150)
        assertTrue("a second on, the spinner is not back where it began (" + differing(start, whole) + " pixels differ)", differing(start, whole) < 60)
    }

    @Test
    fun `once the server answers, the line is gone and the preview says what the server said`() {
        show()

        release.countDown()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Preview unavailable. Use Refresh to retry.").fetchSemanticsNodes().isNotEmpty() }

        assertEquals(0, compose.onAllNodesWithText(LOADING).fetchSemanticsNodes().size)
    }

    private companion object {
        const val LOADING = "Loading preview…"
    }
}
