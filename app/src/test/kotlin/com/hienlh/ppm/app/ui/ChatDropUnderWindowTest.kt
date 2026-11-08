package com.hienlh.ppm.app.ui

import android.app.Application
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.app.ui.files.ExplorerRowDrag
import com.hienlh.ppm.app.ui.files.explorerDragClip
import com.hienlh.ppm.app.ui.tablet.WorkingAreaLayers
import com.hienlh.ppm.app.ui.windows.WindowLayer
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.state.windows.Rect
import com.hienlh.ppm.state.windows.WindowKind
import com.hienlh.ppm.state.windows.WindowStore
import com.hienlh.ppm.state.windows.WindowVisualState
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

/**
 * A chat's message box with a window floating over it, in the tablet's working area as `TabletShell` builds it (the real [ComposerField] and
 * the real `WindowLayer`): what is dropped on the window is the window's, so no `@path` goes into a box that is behind it, and once the window is
 * minimized the box has its drops back. The area is 800 by 600 dp, 100 dp from the left; the box is at its bottom left, a 10 dp column as the field
 * is laid out here, and the window is (100, 360) to (500, 600) in the screen, over it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h700dp")
class ChatDropUnderWindowTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private class MemoryValues : KeyValueStore {
        private val values = mutableMapOf<String, String>()

        override fun get(key: String) = values[key]

        override fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private val tab = ChatTab("t", SessionInfo("s", "mock", "Chat"), "p", "mock")
    private val fileDrop = ChatFileDrop(tab) { _, _ -> }
    private val store = WindowStore(MemoryValues(), "windows")
    private var view: View? = null
    private val drag = DragDriver(compose) { view!! }
    private lateinit var windowId: String

    @Before
    fun setUp() {
        tab.draft.setTextAndPlaceCursorAtEnd("")
        val model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        compose.setContent {
            view = LocalView.current
            val state by store.state.collectAsState()
            val focus = remember { FocusRequester() }
            Row(Modifier.size(900.dp, 600.dp)) {
                Box(Modifier.size(100.dp, 600.dp))
                WorkingAreaLayers(
                    Modifier.size(800.dp, 600.dp),
                    panels = {
                        Box(Modifier.fillMaxSize().dropZone(shouldStartDragAndDrop = { true }, target = fileDrop)) {
                            Column(Modifier.fillMaxSize()) {
                                Box(Modifier.fillMaxWidth().weight(1f))
                                ComposerField(
                                    tab, focus, remember { mutableStateOf(false) }, remember { mutableStateOf(false) },
                                    onKey = { false }, send = {}, pasteImage = {}, fileDrop = fileDrop,
                                    style = ppmText(14f, lineHeight = 14f * 1.625f), padding = 8.dp, cssStart = 0.dp, maxHeight = 90.dp,
                                    placeholder = "Ask anything...", enabled = true,
                                )
                            }
                        }
                    },
                    windows = { WindowLayer(model, store, state) },
                )
            }
        }
        compose.waitForIdle()
        // Over the box, which is at the bottom left of the area: the layer's (0, 360) to (400, 600), the window being 240 dp tall at least.
        compose.runOnIdle { windowId = store.open(WindowKind.Logs, rect = Rect(0.0, 360.0, 400.0, 240.0)) }
        compose.waitForIdle()
    }

    private val box get() = compose.onNode(hasSetTextAction().and(hasImeAction(ImeAction.Send))).fetchSemanticsNode().boundsInRoot.center

    private fun dropRow(): Boolean {
        val row = ExplorerRowDrag("p", "src/app.kt", false)
        drag.clip = explorerDragClip(row)
        drag.local = row
        drag.begin()
        drag.move(box.x.toInt(), box.y.toInt())
        val taken = drag.drop(box.x.toInt(), box.y.toInt())
        drag.end()
        return taken
    }

    @Test
    fun `a row of the tree let go on a window that is over the message box puts no path in the box`() {
        assertFalse("the window refuses it", dropRow())

        assertEquals("", tab.draft.text.toString())
        assertEquals(0, tab.focusRequests)
        assertFalse("and the chat is not lit", tab.filesOver)
    }

    @Test
    fun `text let go on a window that is over the message box is not put in it either`() {
        drag.clip = android.content.ClipData.newPlainText("note", "some words")
        drag.begin()
        drag.move(box.x.toInt(), box.y.toInt())
        assertFalse(drag.drop(box.x.toInt(), box.y.toInt()))
        drag.end()

        assertEquals("", tab.draft.text.toString())
    }

    @Test
    fun `the box has its drops back when the window is minimized`() {
        compose.runOnIdle { store.setState(windowId, WindowVisualState.Minimized) }
        compose.waitForIdle()

        assertTrue(dropRow())

        assertEquals("@src/app.kt ", tab.draft.text.toString())
        assertEquals(1, tab.focusRequests)
    }
}
