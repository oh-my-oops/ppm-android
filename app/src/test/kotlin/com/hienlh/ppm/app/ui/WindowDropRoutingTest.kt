package com.hienlh.ppm.app.ui

import android.app.Application
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.tablet.WorkingAreaLayers
import com.hienlh.ppm.app.ui.windows.WindowLayer
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.KeyValueStore
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
 * The floating windows over the chat, drawn for real by `WindowLayer` inside the working area as the tablet shell builds it: where a window
 * is, it takes a drop and refuses it (what is on the other side of it, here the chat, is not reached through it), and where it is not, the
 * chat does. Nothing in these windows takes a drag of its own unless [zones] is set, so every drop on one is refused and the zone that is
 * asked for is the frame's; with it set, each window's body is one zone of its own, on the window's layer, which says "<window> content drop".
 *
 * The area is 800 by 600 dp, 100 dp from the left, which is also where the layer's own origin is. Window A is (50, 50) to (450, 350) in the
 * layer and window B (250, 150) to (650, 450), so the two overlap in (250, 150) to (450, 350). Add 100 to x for the screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h700dp")
class WindowDropRoutingTest {
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

    private val store = WindowStore(MemoryValues(), "windows")
    private lateinit var model: AppModel
    private val log = mutableListOf<String>()
    private var view: View? = null
    private var router: DropRouter? = null
    private val drag = DragDriver(compose) { view!! }
    private var zones = false
    private val names = mutableMapOf<String, String>()

    private fun contentTarget(id: String) = object : DragAndDropTarget {
        override fun onDrop(event: DragAndDropEvent): Boolean {
            log += (names[id] ?: id) + " content drop"
            return true
        }
    }

    private val chat = object : DragAndDropTarget {
        override fun onEntered(event: DragAndDropEvent) { log += "CHAT entered" }

        override fun onExited(event: DragAndDropEvent) { log += "CHAT exited" }

        override fun onDrop(event: DragAndDropEvent): Boolean {
            log += "CHAT drop"
            return true
        }
    }

    @Before
    fun setUp() {
        model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        compose.setContent {
            view = LocalView.current
            val state by store.state.collectAsState()
            Row(Modifier.size(900.dp, 600.dp)) {
                Box(Modifier.size(100.dp, 600.dp))
                WorkingAreaLayers(
                    Modifier.size(800.dp, 600.dp),
                    panels = {
                        router = LocalDropRouter.current
                        Box(Modifier.fillMaxSize().dropZone({ true }, chat))
                    },
                    windows = {
                        WindowLayer(model, store, state) { win ->
                            if (zones) Box(Modifier.fillMaxSize().dropZone({ true }, contentTarget(win.id)))
                        }
                    },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun open(x: Int, y: Int, w: Int, h: Int): String {
        var id = ""
        compose.runOnIdle { id = store.open(WindowKind.Logs, rect = Rect(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble())) }
        compose.waitForIdle()
        return id
    }

    private fun act(change: () -> Unit) {
        compose.runOnIdle(change)
        compose.waitForIdle()
    }

    /** Let go of a drag at ([x], [y]) in the screen, having moved there; whether the drop was taken. */
    private fun letGoAt(x: Int, y: Int): Boolean {
        log.clear()
        drag.begin()
        drag.move(x, y)
        val taken = drag.drop(x, y)
        drag.end()
        return taken
    }

    @Test
    fun `a drop on a window over the chat is refused, and the chat behind it is not asked`() {
        open(50, 50, 400, 300)

        assertFalse(letGoAt(250, 200))
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun `a drop on a window's titlebar and on the strip a resize begins on is refused too`() {
        open(50, 50, 400, 300)

        assertFalse("its titlebar, 6 dp inside the frame's top", letGoAt(250, 56))
        assertFalse("the strip outside the frame's left edge, which is part of the window", letGoAt(147, 200))
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun `a drop beside the window is the chat's`() {
        open(50, 50, 400, 300)

        assertTrue(letGoAt(700, 450))
        assertEquals(listOf("CHAT entered", "CHAT drop"), log)
    }

    @Test
    fun `where two windows overlap, the one in front is on the higher layer, and it changes as they are raised`() {
        val a = open(50, 50, 400, 300)
        open(250, 150, 400, 300)
        drag.begin()

        fun top(x: Int, y: Int): DropZone {
            var zone: DropZone? = null
            compose.runOnUiThread { zone = router!!.zoneAt(Offset(x.toFloat(), y.toFloat())) }
            return zone ?: error("no zone at ($x, $y)")
        }

        // B opened last, so it is in front: its padded frame starts 6 dp before (250, 150), which is 344 on the screen.
        assertEquals(2, top(500, 250).layer)
        assertEquals(344f, top(500, 250).bounds()!!.left, 0.5f)
        assertEquals("A alone is on the lower layer", 1, top(200, 100).layer)
        assertEquals(144f, top(200, 100).bounds()!!.left, 0.5f)

        act { store.focus(a) }
        assertEquals("A is raised over B", 144f, top(500, 250).bounds()!!.left, 0.5f)
        assertEquals(2, top(500, 250).layer)
        assertEquals("and B is the one behind", 344f, top(700, 400).bounds()!!.left, 0.5f)
        assertEquals(1, top(700, 400).layer)
        drag.end()
    }

    @Test
    fun `a minimized window's area is the chat's again, and the window takes it back when it is restored`() {
        val a = open(50, 50, 400, 300)
        assertFalse(letGoAt(250, 200))

        act { store.setState(a, WindowVisualState.Minimized) }
        assertTrue("minimized: the chat has it", letGoAt(250, 200))
        assertEquals(listOf("CHAT entered", "CHAT drop"), log)

        act { store.focus(a) }
        assertFalse("restored: the window has it", letGoAt(250, 200))
    }

    @Test
    fun `a zone in a window's body takes the drop on it, and the titlebar above it is still refused`() {
        zones = true
        names[open(50, 50, 400, 300)] = "A"

        assertTrue("the body, 200 dp below the window's top", letGoAt(250, 250))
        assertEquals(listOf("A content drop"), log)
        assertFalse("the titlebar", letGoAt(250, 56))
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun `where two windows overlap, the body of the one in front has the drop whether it is the smaller or not, and the one raised takes it`() {
        zones = true
        // A is behind and the smaller: (50, 50) to (250, 250). B is in front and bigger: (100, 100) to (500, 400). In the screen both have
        // x 200 to 350 and y 100 to 250, below B's titlebar and A's.
        val a = open(50, 50, 200, 200)
        val b = open(100, 100, 400, 300)
        names[a] = "A"
        names[b] = "B"

        assertTrue(letGoAt(300, 200))
        assertEquals("the one in front, though the zone of the one behind is smaller", listOf("B content drop"), log)

        act { store.focus(a) }
        assertTrue(letGoAt(300, 200))
        assertEquals("A raised, B behind it", listOf("A content drop"), log)

        act { store.focus(b) }
        assertTrue(letGoAt(300, 200))
        assertEquals("B raised again", listOf("B content drop"), log)
    }

    @Test
    fun `the body of a minimized window takes nothing, and takes drops again when it is restored`() {
        zones = true
        val a = open(50, 50, 400, 300)
        names[a] = "A"
        assertTrue(letGoAt(250, 250))
        assertEquals(listOf("A content drop"), log)

        act { store.setState(a, WindowVisualState.Minimized) }
        assertTrue(letGoAt(250, 250))
        assertEquals("the chat's", listOf("CHAT entered", "CHAT drop"), log)

        act { store.focus(a) }
        assertTrue(letGoAt(250, 250))
        assertEquals(listOf("A content drop"), log)
    }

    @Test
    fun `a closed window's area is the chat's`() {
        val a = open(50, 50, 400, 300)
        assertFalse(letGoAt(250, 200))

        act { store.close(a) }

        assertTrue(letGoAt(250, 200))
    }

    @Test
    fun `a window closed in the middle of a drag over it leaves the chat to take the drop`() {
        val a = open(50, 50, 400, 300)
        drag.begin()
        drag.move(250, 200)

        act { store.close(a) }
        drag.move(250, 200)

        assertTrue(drag.drop(250, 200))
        drag.end()
        assertTrue("CHAT drop" in log)
    }
}
