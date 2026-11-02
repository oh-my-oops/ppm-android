package com.hienlh.ppm.app.ui.osexplorer

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.dropZone
import com.hienlh.ppm.app.ui.files.installBlankFileIcons
import com.hienlh.ppm.app.ui.tablet.WorkingAreaLayers
import com.hienlh.ppm.app.ui.windows.WindowLayer
import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.state.windows.Rect
import com.hienlh.ppm.state.windows.WindowKind
import com.hienlh.ppm.state.windows.WindowStore
import com.hienlh.ppm.state.windows.WindowVisualState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An explorer window's List view as the tablet builds it: inside a floating window, over a chat that takes any drag. What is let go on the
 * window's folders and rows is the window's, as it is on the page of the web, and the chat, which Compose would have kept as the target
 * while the pointer crossed onto the window, is not asked; where the window is not, and on the window's own titlebar, nothing of the
 * window's is, so the chat has the first and the window refuses the second.
 *
 * The area is 900 by 600 dp, 100 dp from the screen's left edge, with the window at (100, 100) in it, 500 by 400: from (200, 100) to
 * (700, 500) on the screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h700dp")
internal class HostExplorerWindowDropTest : HostEntryDndTestBase() {
    private class MemoryValues : KeyValueStore {
        private val values = mutableMapOf<String, String>()

        override fun get(key: String) = values[key]

        override fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private val store = WindowStore(MemoryValues(), "windows")
    private val chatLog = mutableListOf<String>()
    private lateinit var windowId: String

    private val chat = object : DragAndDropTarget {
        override fun onEntered(event: DragAndDropEvent) { chatLog += "entered" }

        override fun onDrop(event: DragAndDropEvent): Boolean {
            chatLog += "drop"
            return true
        }
    }

    @Before
    fun showWindow() {
        installBlankFileIcons()
        val model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        compose.setContent {
            host = LocalView.current
            val windows by store.state.collectAsState()
            val state by session.state.collectAsState()
            val preferences by session.preferences.state.collectAsState()
            val entries = remember(state.directory, state.filter, preferences) { session.visibleEntries() }
            Row(Modifier.size(1000.dp, 600.dp)) {
                Box(Modifier.size(100.dp, 600.dp))
                WorkingAreaLayers(
                    Modifier.size(900.dp, 600.dp),
                    panels = { Box(Modifier.fillMaxSize().dropZone({ true }, chat)) },
                    windows = {
                        WindowLayer(model, store, windows) {
                            HostExplorerEntries(
                                null, session, entries, state, preferences, mobile = false, selectMode = false, windows = false, commands = commands,
                                cut = emptySet(), onFocus = {}, onOpen = {}, onMenu = { _, _ -> },
                            )
                        }
                    },
                )
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { windowId = store.open(WindowKind.Logs, rect = Rect(100.0, 100.0, 500.0, 400.0)) }
        compose.waitForIdle()
    }

    /** The window's titlebar, 6 dp inside the frame's top, which its content never reaches. */
    private val titlebar = Offset(450f, 106f)

    /** Inside the bottom of the window's body, under the last row. */
    private val blank = Offset(450f, 470f)

    @Test
    fun `entries let go on a folder in the window are the window's, and the chat under it is not asked`() {
        assertTrue(begin(entries("/elsewhere/x.txt")))
        hover(center("docs"))
        assertTrue(letGo(center("docs")))
        assertEquals(listOf("move /elsewhere/x.txt -> /home/me/docs/x.txt"), transfers.toList())
        assertEquals(emptyList<String>(), chatLog)
    }

    @Test
    fun `entries let go on the space the window's rows leave are its folder's, and still not the chat's`() {
        begin(entries("/elsewhere/x.txt"))
        hover(blank)
        assertTrue(letGo(blank))
        assertEquals(listOf("move /elsewhere/x.txt -> /home/me/x.txt"), transfers.toList())
        assertEquals(emptyList<String>(), chatLog)
    }

    @Test
    fun `on the window's titlebar nothing is taken, and the chat behind it is not asked either`() {
        begin(entries("/elsewhere/x.txt"))
        hover(titlebar)
        assertFalse(letGo(titlebar))
        assertEquals(emptyList<String>(), transfers.toList())
        assertEquals(emptyList<String>(), chatLog)
    }

    @Test
    fun `beside the window the chat has the drop, as it has what the window does not cover`() {
        begin(entries("/elsewhere/x.txt"))
        val beside = Offset(850f, 500f)
        hover(beside)
        assertTrue(letGo(beside))
        assertEquals(listOf("entered", "drop"), chatLog)
        assertEquals(emptyList<String>(), transfers.toList())
    }

    @Test
    fun `a minimized window takes nothing, and takes drops again when it is restored`() {
        compose.runOnIdle { store.setState(windowId, WindowVisualState.Minimized) }
        compose.waitForIdle()
        begin(entries("/elsewhere/x.txt"))
        val where = Offset(300f, 300f)
        hover(where)
        assertTrue("the chat's", letGo(where))
        assertEquals(listOf("entered", "drop"), chatLog)
        assertEquals(emptyList<String>(), transfers.toList())

        compose.runOnIdle { store.focus(windowId) }
        compose.waitForIdle()
        chatLog.clear()
        begin(entries("/elsewhere/x.txt"))
        hover(center("docs"))
        assertTrue(letGo(center("docs")))
        assertEquals(listOf("move /elsewhere/x.txt -> /home/me/docs/x.txt"), transfers.toList())
        assertEquals(emptyList<String>(), chatLog)
    }

    @Test
    fun `a window closed in the middle of the drag leaves the chat to take the drop`() {
        begin(entries("/elsewhere/x.txt"))
        hover(center("docs"))
        compose.runOnIdle { store.close(windowId) }
        compose.waitForIdle()
        val where = Offset(300f, 300f)
        hover(where)
        assertTrue(letGo(where))
        assertEquals(emptyList<String>(), transfers.toList())
        assertTrue("drop" in chatLog)
    }
}
