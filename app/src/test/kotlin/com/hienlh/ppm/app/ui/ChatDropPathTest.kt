package com.hienlh.ppm.app.ui

import android.content.ClipData
import android.content.ClipDescription
import android.net.Uri
import android.view.DragEvent
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.app.ui.files.ENTRY_DRAG_MIME
import com.hienlh.ppm.app.ui.files.ExplorerRowDrag
import com.hienlh.ppm.app.ui.files.HostEntryDrag
import com.hienlh.ppm.app.ui.files.explorerDragClip
import com.hienlh.ppm.app.ui.tablet.WorkingAreaLayers
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.DragEventBuilder

/**
 * What a project-tree row dragged onto the chat does, as `chat-tab.tsx`'s drop (`setExternalPaths`) and the message box's own do:
 * `@path ` goes at the end of the box, after a space unless the box is empty or ends in one, and the box takes the focus. The
 * web puts it in twice when the row is let go on the box itself, because the box's handler and the chat's both run; here it is
 * once wherever in the chat the row is let go. The chat as `ChatPane` builds it (its whole area one target, the field's own
 * inside it, both the same [ChatFileDrop]), over the real [ComposerField], with the drags Android delivers (started, entered,
 * a location, a drop, ended).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
open class ChatDropPathTest {
    private val compose = createComposeRule()

    /** Whether the chat is in a working area's [DropRouter], as on a tablet, or alone, as on a phone: [ChatDropPathRoutedTest] runs every case again with it. */
    protected open val routed = false

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val tab = ChatTab("t", SessionInfo("s", "mock", "Chat"), "p", "mock")
    private val attached = mutableListOf<Uri>()
    private val fileDrop = ChatFileDrop(tab) { uris, _ -> attached += uris }
    private var view: View? = null

    /** What the box held each time the draft was told to save itself: what it holds is kept when a path goes into it. */
    private val saved = mutableListOf<String>()

    private fun show(draft: String = "", caret: Int = draft.length) {
        tab.draft.setTextAndPlaceCursorAtEnd(draft)
        tab.draft.edit { selection = TextRange(caret) }
        tab.prewarmTouch = { saved += tab.draft.text.toString() }
        compose.setContent {
            view = LocalView.current
            val focus = remember { FocusRequester() }
            val chat = @Composable {
                Box(Modifier.fillMaxSize().dropZone(shouldStartDragAndDrop = { true }, target = fileDrop)) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().weight(1f).testTag(TRANSCRIPT))
                        ComposerField(
                            tab, focus, remember { mutableStateOf(false) }, remember { mutableStateOf(false) },
                            onKey = { false }, send = {}, pasteImage = {}, fileDrop = fileDrop,
                            style = ppmText(14f, lineHeight = 14f * 1.625f), padding = 8.dp, cssStart = 0.dp, maxHeight = 90.dp,
                            placeholder = "Ask anything...", enabled = true,
                        )
                    }
                }
            }
            if (routed) WorkingAreaLayers(Modifier.fillMaxSize(), panels = chat, windows = {}) else chat()
        }
        compose.waitForIdle()
    }

    private val transcript get() = compose.onNodeWithTag(TRANSCRIPT).fetchSemanticsNode().boundsInRoot.center
    private val box get() = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot.center

    /** A point past the window's edge: where a drag is when it has left the chat for whatever is beside it. */
    private val outside = Offset(5_000f, 5_000f)

    private fun dispatch(action: Int, clip: ClipData, local: Any?, at: Offset = Offset.Zero): Boolean {
        var result = false
        compose.runOnUiThread {
            result = view!!.dispatchDragEvent(
                DragEventBuilder.newBuilder().setAction(action).setX(at.x).setY(at.y).setLocalState(local)
                    .setClipDescription(clip.description).setClipData(clip).build(),
            )
        }
        compose.waitForIdle()
        return result
    }

    private fun begin(clip: ClipData, local: Any?) {
        dispatch(DragEvent.ACTION_DRAG_STARTED, clip, local)
        dispatch(DragEvent.ACTION_DRAG_ENTERED, clip, local)
    }

    /** Lets go at [at], having moved there; whether the chat took the drop. */
    private fun letGo(clip: ClipData, local: Any?, at: Offset): Boolean {
        dispatch(DragEvent.ACTION_DRAG_LOCATION, clip, local, at)
        val taken = dispatch(DragEvent.ACTION_DROP, clip, local, at)
        dispatch(DragEvent.ACTION_DRAG_ENDED, clip, local)
        return taken
    }

    private fun row(path: String, isDirectory: Boolean = false) = ExplorerRowDrag("p", path, isDirectory).let { it to explorerDragClip(it) }

    /** A row of the tree dragged here and let go at [at]. */
    private fun dropRow(path: String, at: Offset, isDirectory: Boolean = false) {
        val (drag, clip) = row(path, isDirectory)
        begin(clip, drag)
        letGo(clip, drag, at)
    }

    private fun text() = tab.draft.text.toString()

    @Test
    fun `a row let go over the transcript puts its path in an empty box and asks the box for the focus once`() {
        show()

        dropRow("src/app.kt", transcript)

        assertEquals("@src/app.kt ", text())
        assertEquals("the caret is after the path", TextRange(12), tab.draft.selection)
        assertEquals(1, tab.focusRequests)
        assertTrue("the keyboard comes with the focus, as it does for a file that was dropped", tab.raisesKeyboard)
        assertEquals("the draft was saved once, with the path in it", listOf("@src/app.kt "), saved)
        assertEquals("nothing was attached", emptyList<Uri>(), attached)
    }

    @Test
    fun `a row let go on the message box itself goes in once, not twice`() {
        show()

        dropRow("src/app.kt", box)

        assertEquals("@src/app.kt ", text())
        assertEquals(1, tab.focusRequests)
        assertEquals(listOf("@src/app.kt "), saved)
    }

    @Test
    fun `the path goes after what is typed, a space between them unless the box ends in one`() {
        show("see")
        dropRow("a.kt", transcript)
        assertEquals("see @a.kt ", text())

        dropRow("b.kt", box)
        assertEquals("what the first left ends in a space, so no second one", "see @a.kt @b.kt ", text())
    }

    @Test
    fun `a box that already ends in a space gets no second one`() {
        show("see ")

        dropRow("a.kt", transcript)

        assertEquals("see @a.kt ", text())
    }

    @Test
    fun `only a space counts, so a box that ends in a new line gets one before the path`() {
        show("see\n")

        dropRow("a.kt", transcript)

        assertEquals("see\n @a.kt ", text())
    }

    @Test
    fun `the path goes at the end, whatever the caret is`() {
        show("hello world", caret = 5)

        dropRow("a.kt", transcript)

        assertEquals("hello world @a.kt ", text())
        assertEquals(TextRange(text().length), tab.draft.selection)
    }

    @Test
    fun `the cursor is after the path even when it was at the start of the box`() {
        show("hello", caret = 0)

        dropRow("a.kt", transcript)

        assertEquals("hello @a.kt ", text())
        assertEquals(TextRange(text().length), tab.draft.selection)
    }

    @Test
    fun `a folder's path keeps its slash`() {
        show()

        dropRow("src/util", transcript, isDirectory = true)

        assertEquals("@src/util/ ", text())
    }

    @Test
    fun `a row of another project is let go the same, as the web does`() {
        show()
        val clip = explorerDragClip(ExplorerRowDrag("other", "x/y.kt", false))

        begin(clip, null)
        letGo(clip, null, transcript)

        assertEquals("@x/y.kt ", text())
    }

    @Test
    fun `the overlay is up while a row is over the chat and gone when it is let go, or the drag ends elsewhere`() {
        show()
        val (drag, clip) = row("src/app.kt")
        assertFalse(tab.filesOver)

        begin(clip, drag)
        dispatch(DragEvent.ACTION_DRAG_LOCATION, clip, drag, transcript)
        assertTrue("a row is over the chat", tab.filesOver)
        dispatch(DragEvent.ACTION_DRAG_LOCATION, clip, drag, box)
        assertTrue("and over the box", tab.filesOver)
        letGo(clip, drag, transcript)
        assertFalse("it is let go", tab.filesOver)

        begin(clip, drag)
        dispatch(DragEvent.ACTION_DRAG_LOCATION, clip, drag, transcript)
        assertTrue("it is back over the chat", tab.filesOver)
        dispatch(DragEvent.ACTION_DRAG_LOCATION, clip, drag, outside)
        assertFalse("it leaves for what is beside the chat", tab.filesOver)
        dispatch(DragEvent.ACTION_DRAG_ENDED, clip, drag)
        assertEquals("a drag that left puts nothing more in the box", "@src/app.kt ", text())
        assertEquals(1, tab.focusRequests)
    }

    @Test
    fun `a row that carries no path puts nothing in the box`() {
        show("hi")
        val clip = ClipData(ClipDescription("PPM path", arrayOf("application/x-ppm-path")), ClipData.Item(""))

        begin(clip, null)
        letGo(clip, null, transcript)

        assertEquals("hi", text())
        assertEquals(0, tab.focusRequests)
        assertEquals(emptyList<String>(), saved)
    }

    @Test
    fun `a drag that carries a path and a file is the path, as the web looks for the path first`() {
        show()
        val uri = Uri.parse("content://media/external/images/1")
        val clip = ClipData(ClipDescription("both", arrayOf("application/x-ppm-path", "image/png")), ClipData.Item("a.kt"))
        clip.addItem(ClipData.Item(uri))

        begin(clip, null)
        letGo(clip, null, transcript)

        assertEquals("@a.kt ", text())
        assertEquals("the file is not attached too", emptyList<Uri>(), attached)
    }

    @Test
    fun `files from another app are attached, and put no path in the box`() {
        show("hi")
        val uri = Uri.parse("content://media/external/images/1")
        val clip = ClipData(ClipDescription("picture", arrayOf("image/png")), ClipData.Item(uri))

        begin(clip, null)
        dispatch(DragEvent.ACTION_DRAG_LOCATION, clip, null, transcript)
        assertTrue("the overlay is up for files", tab.filesOver)
        letGo(clip, null, transcript)

        assertEquals(listOf(uri), attached)
        assertEquals("hi", text())
    }

    @Test
    fun `entries of an explorer window light no overlay and put nothing in the box, as the web's overlay looks only for a file or a tree row`() {
        show("hi")
        val drag = HostEntryDrag(listOf("/home/me/a.txt"))
        val clip = explorerDragClip(drag)

        begin(clip, drag)
        dispatch(DragEvent.ACTION_DRAG_LOCATION, clip, drag, transcript)
        assertFalse("no overlay over the chat", tab.filesOver)
        dispatch(DragEvent.ACTION_DRAG_LOCATION, clip, drag, box)
        assertFalse("none over the box", tab.filesOver)
        letGo(clip, drag, transcript)

        assertEquals("hi", text())
        assertEquals(0, tab.focusRequests)
        assertEquals(emptyList<Uri>(), attached)
    }

    @Test
    fun `a file that comes with entries is still a file, and lights the overlay`() {
        show("hi")
        val clip = ClipData(ClipDescription("both", arrayOf(ENTRY_DRAG_MIME, "image/png")), ClipData.Item("{}"))

        begin(clip, null)
        dispatch(DragEvent.ACTION_DRAG_LOCATION, clip, null, transcript)

        assertTrue(tab.filesOver)
        dispatch(DragEvent.ACTION_DRAG_ENDED, clip, null)
    }

    @Test
    fun `text dragged in is swallowed, with no overlay`() {
        show("hi")
        val clip = ClipData.newPlainText("note", "some words")

        begin(clip, null)
        dispatch(DragEvent.ACTION_DRAG_LOCATION, clip, null, box)
        assertFalse("no overlay for text", tab.filesOver)
        letGo(clip, null, box)

        assertEquals("hi", text())
        assertEquals(emptyList<Uri>(), attached)
        assertEquals(0, tab.focusRequests)
    }

    private companion object {
        const val TRANSCRIPT = "transcript"
    }
}
