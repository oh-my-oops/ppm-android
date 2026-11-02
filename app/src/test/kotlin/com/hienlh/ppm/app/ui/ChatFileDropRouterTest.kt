package com.hienlh.ppm.app.ui

import android.content.ClipData
import android.content.ClipDescription
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.app.ui.tablet.WorkingAreaLayers
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.protocol.SessionInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The chat's "Drop files to attach" overlay and its count of entries and exits ([ChatFileDrop]) while the chat comes and goes in the middle of a
 * drag in a working area's drop router: a tab switched away from, or closed, with the pointer over it. The count is the web's `dragCounterRef`: a
 * chat that left without being told it was exited would keep a count that outlasts the drag, and its next drag would light the overlay one exit late.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w700dp-h600dp")
class ChatFileDropRouterTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val first = ChatTab("a", SessionInfo("s1", "mock", "First"), "p", "mock")
    private val second = ChatTab("b", SessionInfo("s2", "mock", "Second"), "p", "mock")
    private val firstDrop = ChatFileDrop(first) { _, _ -> }
    private val secondDrop = ChatFileDrop(second) { _, _ -> }

    private val anchor = object : DragAndDropTarget {
        override fun onDrop(event: DragAndDropEvent) = true
    }

    /** Which chat is shown: 0 the first, 1 the second, anything else none. */
    private var shown by mutableStateOf(0)
    private var view: android.view.View? = null
    private val drag = DragDriver(
        compose,
        clip = ClipData(ClipDescription("picture", arrayOf("image/png")), ClipData.Item("x")),
    ) { view!! }

    @org.junit.Before
    fun setUp() {
        compose.setContent {
            view = LocalView.current
            WorkingAreaLayers(
                Modifier.size(300.dp),
                panels = {
                    // Something else in the area that takes drags, so that the router is in a drag that begins with no chat shown.
                    Box(Modifier.size(10.dp).dropZone({ true }, anchor))
                    when (shown) {
                        0 -> Box(Modifier.fillMaxSize().dropZone({ true }, firstDrop))
                        1 -> Box(Modifier.fillMaxSize().dropZone({ true }, secondDrop))
                        else -> Unit
                    }
                },
                windows = {},
            )
        }
        compose.waitForIdle()
    }

    private fun show(which: Int) {
        compose.runOnIdle { shown = which }
        compose.waitForIdle()
    }

    @Test
    fun `a chat closed with a drag over it leaves no overlay, and its count is back to nothing for the next drag`() {
        drag.begin()
        drag.move(100, 100)
        assertTrue("files are over the chat", first.filesOver)

        show(2)
        assertFalse("no chat, no overlay, at once", first.filesOver)
        drag.end()

        // The same chat comes back (its tab is shown again) and a drag goes in and out of it: one entry, one exit.
        show(0)
        drag.begin()
        drag.move(100, 100)
        assertTrue(first.filesOver)
        drag.move(450, 450)
        assertFalse("out of the chat means out: the count did not outlast the first drag", first.filesOver)
        drag.end()
    }

    @Test
    fun `a chat shown in the middle of a drag takes it from the next move, with the overlay`() {
        show(2)
        drag.begin()
        drag.move(100, 100)
        assertFalse(first.filesOver)

        show(0)
        assertFalse("asked at the next move, not before", first.filesOver)
        drag.move(100, 100)
        assertTrue("it knows the drag carries files, from the event it was asked with", first.filesOver)
        drag.end()
        assertFalse(first.filesOver)
    }

    @Test
    fun `the tab switched to in the middle of a drag takes over the overlay from the one switched away from`() {
        drag.begin()
        drag.move(100, 100)
        assertTrue(first.filesOver)

        show(1)
        assertFalse(first.filesOver)
        assertFalse(second.filesOver)
        drag.move(110, 110)
        assertTrue(second.filesOver)
        assertFalse(first.filesOver)
        drag.end()
        assertFalse(second.filesOver)
    }
}
