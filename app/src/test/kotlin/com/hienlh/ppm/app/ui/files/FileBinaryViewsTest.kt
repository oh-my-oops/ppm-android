package com.hienlh.ppm.app.ui.files

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ThemeTokens
import com.hienlh.ppm.state.files.FileDocumentViewer
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
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
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * An image, a PDF and a document as the web's editor shows them (code-editor.tsx returns each preview alone): no row of the
 * file's path and Refresh above them, not when the file cannot be shown (the centred warning and its line, and nothing
 * around them) and not when it can; the PDF has a row of its own, with the path and Open in new tab. Drawn for real over a
 * server that answers what it is asked, with the decoders the device has (native graphics).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class FileBinaryViewsTest {
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

    private fun file(contentType: String, bytes: ByteArray) = MockResponse.Builder().code(200)
        .addHeader("Content-Type", contentType).body(Buffer().write(bytes)).build()

    private fun show(path: String) {
        val tab = WorkspaceTab("editor:$path", "editor", path.substringAfterLast('/'), "app", buildJsonObject {
            put("filePath", path)
            put("projectName", "app")
        })
        compose.setContent { FileBody(model, tab) }
    }

    private fun waitForText(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForDescription(description: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()
    }

    /** What the tab's own toolbar carries: its path and its Refresh, Wrap and Retry. None of it is on screen. */
    private fun assertNothingAround(path: String) {
        for (label in listOf("Wrap lines", "Refresh file", "Retry")) {
            assertEquals("$label is on screen", 0, compose.onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().size)
            assertEquals("$label is on screen as text", 0, compose.onAllNodesWithText(label).fetchSemanticsNodes().size)
        }
        assertEquals("the path row is on screen", 0, compose.onAllNodesWithText(path).fetchSemanticsNodes().size)
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366CC.toInt()) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Test
    fun `an image that cannot be decoded says Failed to load image and has nothing around it`() {
        server.enqueue(file("image/png", "this is not a picture".toByteArray()))
        show("/tmp/bad.png")

        waitForText("Failed to load image.")

        assertNothingAround("/tmp/bad.png")
    }

    @Test
    fun `an image that decodes is the image, with no row of its path and Refresh above it`() {
        server.enqueue(file("image/png", png()))
        show("/tmp/pic.png")

        waitForDescription("Fit image")

        compose.onNodeWithContentDescription("Zoom in").assertExists()
        assertNothingAround("/tmp/pic.png")
    }

    @Test
    fun `a PDF that cannot be opened says Failed to load PDF and has nothing around it`() {
        server.enqueue(file("application/pdf", "this is not a document".toByteArray()))
        show("/tmp/bad.pdf")

        waitForText("Failed to load PDF.")

        assertNothingAround("/tmp/bad.pdf")
        assertEquals("a row of its own is on screen", 0, compose.onAllNodesWithText("Open in new tab").fetchSemanticsNodes().size)
    }

    @Test
    fun `a document the server converted but the app cannot prepare says Failed to load document and its cause, with nothing around it`() {
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                .body("""{"ok":true,"data":{"html":"<p>hello</p>"}}""").build(),
        )
        show("/tmp/a.docx")

        // This unit-test environment has no JavaScript engine for the sanitiser, which is the step after the server's.
        waitForText("Failed to load document.")

        compose.onNodeWithText("JavaScript is unavailable", substring = true).assertExists()
        assertNothingAround("/tmp/a.docx")
    }

    @Test
    fun `only the viewers the web gives the editor's toolbar have it, and an image, a PDF and a document do not`() {
        for (viewer in listOf(FileDocumentViewer.TEXT, FileDocumentViewer.MARKDOWN, FileDocumentViewer.CSV)) {
            assertTrue("$viewer has no toolbar", fileHasToolbar(viewer))
        }
        for (viewer in listOf(FileDocumentViewer.IMAGE, FileDocumentViewer.PDF, FileDocumentViewer.DOCX)) {
            assertFalse("$viewer has a toolbar", fileHasToolbar(viewer))
        }
    }

    private class Pages(count: Int) : PdfPageSource {
        override val sizes = List(count) { PdfPages.Size(200, 300) }
        override fun render(index: Int, requestedWidth: Int): Bitmap? = null
    }

    private var opened = 0
    private var tokens: ThemeTokens? = null

    private fun showPages(path: String = "docs/spec.pdf", opening: Boolean = false) {
        compose.setContent {
            tokens = LocalPpmTokens.current
            var zoom by remember { mutableStateOf(1f) }
            PdfPagesView(Pages(3), path, zoom, { zoom = it }, rememberLazyListState(), opening) { opened++ }
        }
        compose.waitForIdle()
    }

    /** How a text on screen was laid out: its size, its colour and whether it was cut. */
    private fun layout(text: String): TextLayoutResult {
        val out = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(out)
        return out.single()
    }

    private fun near(message: String, expected: Color, actual: Color) {
        assertEquals("$message, red", expected.red, actual.red, 0.02f)
        assertEquals("$message, green", expected.green, actual.green, 0.02f)
        assertEquals("$message, blue", expected.blue, actual.blue, 0.02f)
    }

    @Test
    fun `a PDF that opened has the web's row, its path and Open in new tab, over the page controls`() {
        showPages()

        compose.onNodeWithText("docs/spec.pdf").assertExists()
        compose.onNodeWithText("Open in new tab").assertExists()
        val row = compose.onNodeWithText("docs/spec.pdf").fetchSemanticsNode().boundsInRoot
        val controls = compose.onNodeWithContentDescription("Next page").fetchSemanticsNode().boundsInRoot
        assertTrue("the row is not above the page controls", row.bottom <= controls.top)
        assertEquals("the old Open with action is still there", 0, compose.onAllNodes(hasContentDescription("Open with…")).fetchSemanticsNodes().size)
    }

    @Test
    fun `Open in new tab hands the file on`() {
        showPages()
        compose.onNodeWithText("Open in new tab").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `the path is 12 px secondary text on one line, 12 dp in from the start and 6 dp down`() {
        showPages()

        val bounds = compose.onNodeWithText("docs/spec.pdf").fetchSemanticsNode().boundsInRoot
        assertEquals("12 dp in", 36f, bounds.left, 0.5f)
        assertEquals("6 dp down", 18f, bounds.top, 0.5f)
        val style = layout("docs/spec.pdf").layoutInput.style
        assertEquals(12.sp, style.fontSize)
        assertEquals(16.sp, style.lineHeight)
        near("the path's colour", tokens!!.text2.color, style.color)
    }

    @Test
    fun `Open in new tab ends 12 dp from the end, with its 12 px icon and a 4 px gap in front of the text`() {
        showPages()

        val button = compose.onNodeWithText("Open in new tab").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals("12 dp from the end", root.right - 36f, button.right, 0.5f)
        assertEquals("the icon, the gap and the text", 48f + layout("Open in new tab").size.width, button.width, 1f)
        assertEquals("one line, 16 dp high", 48f, button.height, 0.5f)
    }

    @Test
    fun `the row is 29 dp high, the page's background with a 1 dp line of the border colour under it`() {
        showPages()
        val image = compose.onRoot().captureToImage().toPixelMap()
        val t = tokens!!

        // Between the path and the button, where there is no text.
        near("the row's background", t.bgSolid.color, image[600, 40])
        near("the line under it", t.border.over(t.bgSolid).color, image[600, 85])
        near("the row above the line", t.bgSolid.color, image[600, 80])
    }

    @Test
    fun `Open in new tab is secondary text, and primary text under a mouse`() {
        showPages()
        val t = tokens!!
        near("at rest", t.text2.color, layout("Open in new tab").layoutInput.style.color)

        compose.onNodeWithText("Open in new tab").performMouseInput { moveTo(center) }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()

        near("under a mouse", t.text.color, layout("Open in new tab").layoutInput.style.color)
    }

    @Test
    fun `a path too long for the row ends in an ellipsis and leaves the button whole`() {
        showPages(path = "a/very/long/directory/".repeat(12) + "spec.pdf")

        val path = layout("a/very/long/directory/".repeat(12) + "spec.pdf")
        assertTrue("the path was not cut", path.isLineEllipsized(0))
        assertEquals(1, path.lineCount)
        val button = layout("Open in new tab")
        assertEquals("the button wrapped", 1, button.lineCount)
        assertFalse("the button was cut", button.isLineEllipsized(0))
        val bounds = compose.onNodeWithText("Open in new tab").fetchSemanticsNode().boundsInRoot
        assertEquals("the button is as wide as it is", 48f + button.size.width, bounds.width, 1f)
        assertTrue("the path runs under the button", compose.onNodeWithText("a/very/long/directory/".repeat(12) + "spec.pdf").fetchSemanticsNode().boundsInRoot.right <= bounds.left)
    }

    @Test
    fun `a PDF being handed on does not hand itself on again`() {
        showPages(opening = true)
        compose.onNodeWithText("Open in new tab").performClick()
        assertEquals(0, opened)
    }
}
