package com.hienlh.ppm.app.ui.files

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.state.media.MediaFailure
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
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * What the two screens that are mostly paint look like. The overlay of a stopped transcode is
 * `bg-black/85`, which only its pixels tell from a fill of another strength or none; a video being
 * probed is a 20 px spinner in the subtle colour alone on the page, not the black player with its
 * controls and a white spinner, and only the pixels tell that it is nothing else (native graphics, 3
 * pixels to the dp).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h400dp-xxhdpi")
class FileMediaPixelsTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val server = MockWebServer()
    private lateinit var model: AppModel
    private val perDp = 3

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
    fun `the overlay over a white picture is 85 percent black, corner to corner`() {
        compose.setContent {
            Box(Modifier.testTag("stage").requiredSize(200.dp, 200.dp).background(Color.White)) {
                MediaFailureView(MediaFailure.TranscodeStopped("It stopped."), onDownload = {}, onRetry = {})
            }
        }

        val image = compose.onNodeWithTag("stage").captureToImage().toPixelMap()
        for ((x, y) in listOf(0 to 0, image.width - 1 to 0, 0 to image.height - 1, image.width - 1 to image.height - 1)) {
            val at = image[x, y]
            // 15 percent of white is what 85 percent black leaves.
            for (channel in listOf(at.red, at.green, at.blue)) assertEquals("pixel $x, $y", 0.15f, channel, 0.02f)
        }
    }

    /** Every pixel of [image] that is not [page], as (x, y). */
    private fun ink(image: PixelMap, page: Color): List<Pair<Int, Int>> {
        val found = mutableListOf<Pair<Int, Int>>()
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val at = image[x, y]
            if (maxOf(abs(at.red - page.red), abs(at.green - page.green), abs(at.blue - page.blue)) > 0.02f) found += x to y
        }
        return found
    }

    @Test
    fun `a video being probed is one 20 dp spinner in the subtle colour in the middle of the page, and nothing else`() {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath.endsWith("/probe")) {
                    arrived.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "the probe was never released" }
                }
                return MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                    .body("""{"ok":true,"data":{"transcodable":false}}""").build()
            }
        }
        var page = Color.Unspecified
        var subtle = Color.Unspecified
        val tab = WorkspaceTab("editor:/tmp/clip.mkv", "editor", "clip.mkv", "app", buildJsonObject {
            put("filePath", "/tmp/clip.mkv")
            put("projectName", "app")
        })
        compose.setContent {
            page = LocalPpmTokens.current.bgSolid.color
            subtle = LocalPpmTokens.current.text3.color
            FileMediaView(model, tab)
        }
        compose.waitUntil(10_000) { compose.waitForIdle(); arrived.count == 0L }

        val image = compose.onRoot().captureToImage().toPixelMap()
        release.countDown()
        val spots = ink(image, page)

        assertTrue("nothing was drawn", spots.isNotEmpty())
        val left = spots.minOf { it.first }
        val right = spots.maxOf { it.first }
        val top = spots.minOf { it.second }
        val bottom = spots.maxOf { it.second }
        // Lucide's loader is a three-quarter ring that is drawn in a 24 unit box, scaled to 20 dp, and its ink is about 16 dp of the 20 dp (48 pixels measured, a few more or fewer as it turns).
        assertTrue("${right - left + 1} pixels wide", (right - left + 1) in 14 * perDp..18 * perDp)
        assertTrue("${bottom - top + 1} pixels tall", (bottom - top + 1) in 14 * perDp..18 * perDp)
        assertEquals("centred across", image.width / 2.0, (left + right) / 2.0, 3.0)
        assertEquals("centred down", image.height / 2.0, (top + bottom) / 2.0, 3.0)
        // The ink's strongest pixel is the subtle colour itself, not white or a text colour.
        val strongest = spots.maxBy { (x, y) -> abs(image[x, y].red - page.red) + abs(image[x, y].green - page.green) + abs(image[x, y].blue - page.blue) }
        val at = image[strongest.first, strongest.second]
        assertEquals(subtle.red, at.red, 0.08f)
        assertEquals(subtle.green, at.green, 0.08f)
        assertEquals(subtle.blue, at.blue, 0.08f)
    }
}
