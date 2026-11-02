package com.hienlh.ppm.app.ui

import android.content.ClipData
import android.content.ClipDescription
import android.view.DragEvent
import android.view.View
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ui.tablet.WorkingAreaLayers
import com.hienlh.ppm.app.withComposeActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a drag is over in the tablet's working area, with the drags Android delivers: the chat over the whole area with a window or two
 * floating on it, each a frame that refuses a drop and a zone inside it ([Window]), and a plain target beside it for the sidebar. Where a
 * browser lets what is on top decide, Compose's own targets are chosen by composition order and kept while the pointer stays inside the
 * one it is in, so each of these has a case the same layout fails without the router (measured on this layout: a drag that crossed the chat
 * and came onto a window was still the chat's, the back window took what the front one covered, and a window that was not placed took the
 * area's corner).
 *
 * The area is 500 dp square, 100 dp from the left; the front window's frame is (110, 10) to (310, 210) and its zone 20 dp inside, the back
 * window's (100, 0) to (400, 300) and its zone 20 dp inside.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w700dp-h600dp")
class DropRouterTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val log = mutableListOf<String>()
    private var view: View? = null
    private val drag = DragDriver(compose) { view!! }

    /** Writes "name event" to [log] for everything it hears. */
    private inner class Probe(private val name: String, private val takes: Boolean = true) : DragAndDropTarget {
        override fun onStarted(event: DragAndDropEvent) { log += "$name started" }

        override fun onEntered(event: DragAndDropEvent) { log += "$name entered" }

        override fun onMoved(event: DragAndDropEvent) { log += "$name moved" }

        override fun onExited(event: DragAndDropEvent) { log += "$name exited" }

        override fun onEnded(event: DragAndDropEvent) { log += "$name ended" }

        override fun onDrop(event: DragAndDropEvent): Boolean {
            log += "$name drop"
            return takes
        }
    }

    private fun heard(name: String) = log.filter { it.startsWith("$name ") }.map { it.removePrefix("$name ") }

    private fun assertOrder(first: String, then: String) {
        val a = log.indexOf(first)
        val b = log.indexOf(then)
        assertTrue("$first is in $log", a >= 0)
        assertTrue("$then is in $log", b >= 0)
        assertTrue("$first before $then in $log", a < b)
    }

    /**
     * A window as the layer places one: a frame of [size] at ([x], [y]) in the area, a refusing zone on [layer] (what `FloatingWindow` puts
     * on its frame), and, unless [zone] is false, a zone of the window's own inset 20 dp in it, with [content] over that.
     */
    @Composable
    private fun Window(
        name: String, x: Int, y: Int, size: Int, layer: Int, showing: Boolean = true, zone: Boolean = true,
        content: @Composable BoxScope.() -> Unit = {},
    ) {
        CompositionLocalProvider(LocalDropLayer provides layer, LocalWindowContentVisible provides showing) {
            Box(
                Modifier
                    .layout { measurable, constraints ->
                        val frame = measurable.measure(Constraints.fixed(size.dp.roundToPx(), size.dp.roundToPx()))
                        layout(constraints.maxWidth, constraints.maxHeight) { frame.place(x.dp.roundToPx(), y.dp.roundToPx()) }
                    }
                    .dropZone({ true }, remember { Probe("$name-frame", takes = false) }, visible = showing, layer = layer),
            ) {
                if (zone) Box(Modifier.offset(20.dp, 20.dp).size(size.dp - 40.dp).dropZone({ true }, remember { Probe("$name-zone") }))
                content()
            }
        }
    }

    /** The sidebar's tree beside the area, an ordinary target, and the area: the chat under [windows]. */
    private fun shell(chat: @Composable BoxScope.() -> Unit = {}, windows: @Composable () -> Unit = {}) {
        compose.setContent {
            view = LocalView.current
            Row(Modifier.size(600.dp, 500.dp)) {
                Box(Modifier.size(100.dp, 500.dp).dragAndDropTarget({ true }, remember { Probe("TREE") }))
                WorkingAreaLayers(
                    Modifier.size(500.dp),
                    panels = { Box(Modifier.fillMaxSize().dropZone({ true }, remember { Probe("CHAT") })) { chat() } },
                    windows = windows,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun twoWindows() = shell(windows = {
        // The front one first in composition order, as a layer that goes by order would get it wrong.
        Window("FRONT", 10, 10, 200, layer = 2)
        Window("BACK", 0, 0, 300, layer = 1)
    })

    @Test
    fun `the front window takes a drop where two windows overlap, with the chat and the back window beneath it hearing nothing`() {
        twoWindows()

        drag.begin()
        drag.move(150, 50)
        assertTrue(drag.drop(150, 50))
        drag.end()

        assertEquals(listOf("started", "entered", "moved", "drop", "ended"), heard("FRONT-zone"))
        assertEquals(listOf("started", "ended"), heard("BACK-zone"))
        assertEquals(listOf("started", "ended"), heard("CHAT"))
        assertEquals(listOf("started", "ended"), heard("FRONT-frame"))
    }

    @Test
    fun `the back window takes a drop where only it is`() {
        twoWindows()

        drag.begin()
        drag.move(350, 250)
        assertTrue(drag.drop(350, 250))
        drag.end()

        assertEquals(listOf("started", "entered", "moved", "drop", "ended"), heard("BACK-zone"))
        assertEquals(listOf("started", "ended"), heard("FRONT-zone"))
        assertEquals(listOf("started", "ended"), heard("CHAT"))
    }

    @Test
    fun `a window's frame refuses a drop on it, and nothing under it is reached through the refusal`() {
        twoWindows()

        // On the front window's frame outside its zone, where the back window's zone does not reach: the front frame is the top.
        drag.begin()
        drag.move(115, 15)
        assertFalse("refused", drag.drop(115, 15))
        drag.end()

        assertEquals(listOf("started", "entered", "moved", "drop", "ended"), heard("FRONT-frame"))
        assertEquals(listOf("started", "ended"), heard("BACK-frame"))
        assertEquals(listOf("started", "ended"), heard("BACK-zone"))
        assertEquals(listOf("started", "ended"), heard("CHAT"))
    }

    @Test
    fun `a window's frame beats a smaller zone of the panels under it, as a layer is above whatever is on a lower one`() {
        shell(
            chat = { Box(Modifier.offset(150.dp, 50.dp).size(60.dp).dropZone({ true }, remember { Probe("BOX") })) },
            windows = { Window("W", 10, 10, 300, layer = 1, zone = false) },
        )

        drag.begin()
        // Inside the box under the window, though the box is the smaller of the two.
        drag.move(280, 80)
        assertFalse(drag.drop(280, 80))
        drag.end()

        assertEquals(listOf("started", "entered", "moved", "drop", "ended"), heard("W-frame"))
        assertEquals(listOf("started", "ended"), heard("BOX"))
        assertEquals(listOf("started", "ended"), heard("CHAT"))
    }

    @Test
    fun `the chat takes a drop where no window is`() {
        twoWindows()

        drag.begin()
        drag.move(500, 400)
        assertTrue(drag.drop(500, 400))
        drag.end()

        assertEquals(listOf("started", "entered", "moved", "drop", "ended"), heard("CHAT"))
    }

    @Test
    fun `a drop goes to what is where it is let go, not to the zone the last move was in`() {
        twoWindows()

        drag.begin()
        drag.move(150, 50)
        assertTrue(drag.drop(500, 400))
        drag.end()

        assertEquals("the pointer was never in the chat before the drop, which is Android's to say and not a move", listOf("started", "drop", "ended"), heard("CHAT"))
        assertEquals("it was not let go there", listOf("started", "entered", "moved", "ended"), heard("FRONT-zone"))
    }

    @Test
    fun `a drag over a window and across the chat is the chat's, then the window's again, wherever it began and wherever it is let go`() {
        twoWindows()

        // (a) from the sidebar, across the chat, onto the front window's zone.
        drag.begin()
        drag.move(50, 50)
        drag.move(500, 400)
        drag.move(450, 300)
        drag.move(150, 50)
        assertTrue(drag.drop(150, 50))
        drag.end()
        assertEquals("the window's zone, not the chat the drag crossed", listOf("started", "entered", "moved", "drop", "ended"), heard("FRONT-zone"))
        assertEquals("the chat was crossed and let go of", listOf("started", "entered", "moved", "moved", "exited", "ended"), heard("CHAT"))
        assertEquals("the sidebar's own target left when the drag came into the area", listOf("started", "entered", "moved", "exited", "ended"), heard("TREE"))

        // (d) the same drag, out to the chat and back onto the zone: the zone again.
        log.clear()
        drag.begin()
        drag.move(150, 50)
        drag.move(500, 450)
        drag.move(150, 50)
        assertTrue(drag.drop(150, 50))
        drag.end()
        assertEquals(listOf("started", "entered", "moved", "exited", "entered", "moved", "drop", "ended"), heard("FRONT-zone"))
    }

    @Test
    fun `a drag from a window's zone onto the chat beside it is the chat's`() {
        twoWindows()

        drag.begin()
        drag.move(150, 50)
        drag.move(500, 450)
        assertTrue(drag.drop(500, 450))
        drag.end()

        assertEquals(listOf("started", "entered", "moved", "exited", "ended"), heard("FRONT-zone"))
        assertEquals(listOf("started", "entered", "moved", "drop", "ended"), heard("CHAT"))
    }

    @Test
    fun `a drag that is in the back window and moves to where the front one covers it is the front one's`() {
        twoWindows()

        drag.begin()
        drag.move(350, 250)
        drag.move(300, 200)
        drag.move(150, 50)
        assertTrue(drag.drop(150, 50))
        drag.end()

        assertEquals(listOf("started", "entered", "moved", "exited", "ended"), heard("BACK-zone"))
        assertEquals("on the front window's frame on the way, then its zone", listOf("started", "entered", "moved", "exited", "ended"), heard("FRONT-frame"))
        assertEquals(listOf("started", "entered", "moved", "drop", "ended"), heard("FRONT-zone"))
    }

    @Test
    fun `going from one zone to another, the new one is entered and moved before the old one is exited, as Compose does it`() {
        twoWindows()

        drag.begin()
        drag.move(500, 450)
        log.clear()
        drag.move(150, 50)

        assertEquals(listOf("FRONT-zone entered", "FRONT-zone moved", "CHAT exited"), log)
    }

    @Test
    fun `a zone inside another is the one that takes what is over it, and the outer one has the rest`() {
        shell(chat = { Box(Modifier.offset(100.dp, 400.dp).size(200.dp, 60.dp).dropZone({ true }, remember { Probe("BOX") })) })

        drag.begin()
        drag.move(300, 300)
        var mark = log.size
        drag.move(300, 450)
        assertEquals("into the box: the box first, the chat last", listOf("BOX entered", "BOX moved", "CHAT exited"), log.drop(mark))
        mark = log.size
        drag.move(500, 450)
        assertEquals("and out of it", listOf("CHAT entered", "CHAT moved", "BOX exited"), log.drop(mark))
        drag.move(300, 450)
        assertTrue(drag.drop(300, 450))
        drag.end()

        assertEquals(listOf("started", "entered", "moved", "exited", "entered", "moved", "drop", "ended"), heard("BOX"))
    }

    @Test
    fun `of two zones that are the same size on one layer, the one composed last takes it`() {
        shell(chat = {
            Box(Modifier.size(100.dp).dropZone({ true }, remember { Probe("FIRST") }))
            Box(Modifier.size(100.dp).dropZone({ true }, remember { Probe("SECOND") }))
        })

        drag.begin()
        drag.move(150, 50)
        assertTrue(drag.drop(150, 50))
        drag.end()

        assertEquals(listOf("started", "entered", "moved", "drop", "ended"), heard("SECOND"))
        assertEquals(listOf("started", "ended"), heard("FIRST"))
    }

    @Test
    fun `a minimized window takes nothing and its area is the chat's again`() {
        shell(windows = { Window("GONE", 10, 10, 200, layer = 1, showing = false) })

        drag.begin()
        drag.move(150, 50)
        assertTrue(drag.drop(150, 50))
        drag.end()

        assertEquals("the chat took it", listOf("started", "entered", "moved", "drop", "ended"), heard("CHAT"))
        assertEquals("not asked, not started, never entered", emptyList<String>(), heard("GONE-zone") + heard("GONE-frame"))
    }

    @Test
    fun `a zone in content that is not showing takes nothing, whoever composed it`() {
        shell(chat = {
            CompositionLocalProvider(LocalWindowContentVisible provides false) {
                Box(Modifier.size(100.dp).dropZone({ true }, remember { Probe("HIDDEN") }))
            }
        })

        drag.begin()
        drag.move(150, 50)
        assertTrue(drag.drop(150, 50))
        drag.end()

        assertEquals(emptyList<String>(), heard("HIDDEN"))
        assertTrue("drop" in heard("CHAT"))
    }

    @Test
    fun `a zone's bounds are what shows of it, so a row half out of its window takes no drop where the window ends`() {
        shell(windows = {
            Window("W", 10, 10, 200, layer = 1, zone = false) {
                // The bottom half of the frame holds a clip with a row 100 dp tall whose lower half is outside it, and so outside the window.
                Box(Modifier.align(Alignment.BottomStart).size(200.dp, 100.dp).clipToBounds()) {
                    Box(Modifier.offset(y = 50.dp).size(200.dp, 100.dp).dropZone({ true }, remember { Probe("ROW") }))
                }
            }
        })

        drag.begin()
        drag.move(200, 190)
        assertTrue("the half that shows is the row's", drag.drop(200, 190))
        assertTrue("drop" in heard("ROW"))

        log.clear()
        drag.move(200, 240)
        assertTrue("past the window's edge is the chat's", drag.drop(200, 240))
        assertTrue("drop" in heard("CHAT"))
        assertFalse("drop" in heard("ROW"))
        drag.end()
    }

    @Test
    fun `a zone that does not take this drag is not under the pointer, and the frame refuses in its place`() {
        val text = ClipData(ClipDescription("words", arrayOf("text/plain")), ClipData.Item("words"))
        shell(windows = {
            Window("W", 10, 10, 200, layer = 1, zone = false) {
                Box(
                    Modifier.offset(20.dp, 20.dp).size(100.dp).dropZone(
                        { it.toAndroidDragEvent().clipDescription?.hasMimeType("application/x-wanted") == true },
                        remember { Probe("WANTS") },
                    ),
                )
            }
        })

        drag.clip = text
        drag.begin()
        drag.move(150, 60)
        assertFalse("the frame refuses what the zone did not want", drag.drop(150, 60))
        drag.end()
        assertEquals(emptyList<String>(), heard("WANTS"))
        assertTrue("drop" in heard("W-frame"))

        log.clear()
        drag.clip = ClipData(ClipDescription("wanted", arrayOf("application/x-wanted")), ClipData.Item("x"))
        drag.begin()
        drag.move(150, 60)
        assertTrue(drag.drop(150, 60))
        drag.end()
        assertTrue("drop" in heard("WANTS"))
    }

    @Test
    fun `leaving the area for the sidebar takes the pointer out of the zone it was in`() {
        twoWindows()

        drag.begin()
        drag.move(500, 450)
        drag.move(50, 50)

        assertEquals(listOf("started", "entered", "moved", "exited"), heard("CHAT"))
        assertEquals(listOf("started", "entered", "moved"), heard("TREE"))
        drag.end()
    }

    @Test
    fun `every zone that took the drag is ended once wherever it was let go, and one that did not take it is not`() {
        shell(chat = {
            Box(Modifier.offset(100.dp, 100.dp).size(60.dp).dropZone({ false }, remember { Probe("DECLINES") }))
        }, windows = { Window("W", 10, 10, 200, layer = 1) })

        drag.begin()
        drag.move(500, 450)
        assertTrue(drag.drop(500, 450))
        drag.end()

        assertEquals(listOf("started", "ended"), heard("W-zone"))
        assertEquals(listOf("started", "ended"), heard("W-frame"))
        assertEquals(listOf("started", "entered", "moved", "drop", "ended"), heard("CHAT"))
        assertEquals(emptyList<String>(), heard("DECLINES"))
    }

    @Test
    fun `a drop in the area where no zone is, is refused`() {
        compose.setContent {
            view = LocalView.current
            WorkingAreaLayers(
                Modifier.size(300.dp),
                panels = { Box(Modifier.size(50.dp).dropZone({ true }, remember { Probe("SMALL") })) },
                windows = {},
            )
        }
        compose.waitForIdle()

        drag.begin()
        drag.move(200, 200)
        assertFalse(drag.drop(200, 200))
        drag.end()

        assertEquals(listOf("started", "ended"), heard("SMALL"))
    }

    @Test
    fun `nothing in the area takes a drag nobody wants, so the window is told it is not interested`() {
        compose.setContent {
            view = LocalView.current
            WorkingAreaLayers(
                Modifier.size(200.dp),
                panels = { Box(Modifier.fillMaxSize().dropZone({ false }, remember { Probe("CHAT") })) },
                windows = {},
            )
        }
        compose.waitForIdle()

        assertFalse(drag.begin())
        drag.move(50, 50)
        assertFalse(drag.drop(50, 50))
        drag.end()

        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun `a zone that arrives in the middle of a drag is asked at the next move, started, and entered`() {
        var late by mutableStateOf(false)
        shell(windows = { if (late) Window("LATE", 10, 10, 200, layer = 1) })

        drag.begin()
        drag.move(150, 50)
        assertEquals("only the chat is here", listOf("started", "entered", "moved"), heard("CHAT"))
        compose.runOnIdle { late = true }
        compose.waitForIdle()
        assertEquals("nothing is said until the pointer moves", emptyList<String>(), heard("LATE-zone"))

        drag.move(150, 50)
        assertEquals(listOf("started", "entered", "moved"), heard("LATE-zone"))
        assertOrder("LATE-zone moved", "CHAT exited")
        assertTrue(drag.drop(150, 50))
        drag.end()

        assertEquals(listOf("started", "entered", "moved", "drop", "ended"), heard("LATE-zone"))
        assertEquals(listOf("started", "entered", "moved", "exited", "ended"), heard("CHAT"))
    }

    @Test
    fun `a zone that goes in the middle of a drag, with the pointer in it, is exited and then ended, once`() {
        var open by mutableStateOf(true)
        shell(windows = { if (open) Window("W", 10, 10, 200, layer = 1) })

        drag.begin()
        drag.move(150, 50)
        log.clear()
        compose.runOnIdle { open = false }
        compose.waitForIdle()

        assertEquals(listOf("W-zone exited", "W-zone ended", "W-frame ended").sorted(), log.sorted())
        assertOrder("W-zone exited", "W-zone ended")

        log.clear()
        drag.move(150, 50)
        drag.end()
        assertEquals("the chat is under the pointer now, and the zone that went hears no more", listOf("CHAT entered", "CHAT moved", "CHAT ended"), log.filter { it.startsWith("CHAT") })
        assertEquals(emptyList<String>(), log.filter { it.startsWith("W-") })
    }

    @Test
    fun `a zone that goes while the pointer is elsewhere is ended, not exited`() {
        var open by mutableStateOf(true)
        shell(windows = { if (open) Window("W", 10, 10, 200, layer = 1) })

        drag.begin()
        drag.move(500, 450)
        log.clear()
        compose.runOnIdle { open = false }
        compose.waitForIdle()

        assertEquals(listOf("W-frame ended", "W-zone ended"), log.sorted())
        drag.end()
    }

    @Test
    fun `a window minimized in the middle of a drag lets go of its zones the same way`() {
        var showing by mutableStateOf(true)
        shell(windows = { Window("W", 10, 10, 200, layer = 1, showing = showing) })

        drag.begin()
        drag.move(150, 50)
        log.clear()
        compose.runOnIdle { showing = false }
        compose.waitForIdle()

        assertOrder("W-zone exited", "W-zone ended")
        assertEquals(1, log.count { it == "W-zone ended" })

        log.clear()
        drag.move(150, 50)
        assertTrue(drag.drop(150, 50))
        drag.end()
        assertTrue("the chat has it, it is let go there", "CHAT drop" in log)
        assertEquals("not ended a second time", emptyList<String>(), log.filter { it == "W-zone ended" })
    }

    @Test
    fun `a zone that took the drag hears it started as it begins, and ended when it ends, though the pointer never moved`() {
        shell()

        drag.begin()
        assertEquals(listOf("started"), heard("CHAT"))
        drag.end()

        assertEquals(listOf("started", "ended"), heard("CHAT"))
    }

    @Test
    fun `a zone that goes before the pointer has moved is still ended`() {
        var open by mutableStateOf(true)
        shell(windows = { if (open) Window("W", 10, 10, 200, layer = 1) })

        drag.send(DragEvent.ACTION_DRAG_STARTED)
        log.clear()
        compose.runOnIdle { open = false }
        compose.waitForIdle()

        assertEquals(listOf("W-frame ended", "W-zone ended"), log.sorted())
        drag.end()
        assertEquals("not a second time", 1, log.count { it == "W-zone ended" })
    }

    @Test
    fun `a zone that goes in the middle of a drag is told where the pointer is, by the last event of the drag`() {
        var open by mutableStateOf(true)
        var exitedAt: Pair<Float, Float>? = null
        var endedAt: Pair<Float, Float>? = null
        val zone = object : DragAndDropTarget {
            override fun onExited(event: DragAndDropEvent) { exitedAt = event.toAndroidDragEvent().let { it.x to it.y } }

            override fun onEnded(event: DragAndDropEvent) { endedAt = event.toAndroidDragEvent().let { it.x to it.y } }

            override fun onDrop(event: DragAndDropEvent) = true
        }
        shell(chat = { if (open) Box(Modifier.size(100.dp).dropZone({ true }, zone)) })

        drag.begin()
        drag.move(150, 50)
        compose.runOnIdle { open = false }
        compose.waitForIdle()

        assertEquals("exited at the last move, not where the drag began", 150f to 50f, exitedAt)
        assertEquals(150f to 50f, endedAt)
        drag.end()
    }

    @Test
    fun `a zone is heard by the target it has now`() {
        var second by mutableStateOf(false)
        shell(chat = {
            val one = remember { Probe("ONE") }
            val two = remember { Probe("TWO") }
            Box(Modifier.size(100.dp).dropZone({ true }, if (second) two else one))
        })

        drag.begin()
        drag.move(150, 50)
        assertTrue(drag.drop(150, 50))
        drag.end()
        assertEquals(listOf("started", "entered", "moved", "drop", "ended"), heard("ONE"))

        compose.runOnIdle { second = true }
        compose.waitForIdle()
        log.clear()
        drag.begin()
        drag.move(150, 50)
        assertTrue(drag.drop(150, 50))
        drag.end()
        assertEquals(listOf("started", "entered", "moved", "drop", "ended"), heard("TWO"))
        assertEquals("the one it had before hears nothing of this drag", emptyList<String>(), heard("ONE"))
    }

    @Test
    fun `a zone is asked by the question it has now`() {
        var takes by mutableStateOf(true)
        shell(chat = {
            val answer = takes
            Box(Modifier.size(100.dp).dropZone({ answer }, remember { Probe("ZONE") }))
        })

        drag.begin()
        drag.move(150, 50)
        drag.end()
        assertEquals(listOf("started", "entered", "moved", "ended"), heard("ZONE"))

        compose.runOnIdle { takes = false }
        compose.waitForIdle()
        log.clear()
        drag.begin()
        drag.move(150, 50)
        drag.end()
        assertEquals(emptyList<String>(), heard("ZONE"))
    }

    @Test
    fun `a zone is asked once for a drag, as Compose asks a target, and its first answer stands`() {
        var asked = 0
        shell(chat = {
            Box(Modifier.size(100.dp).dropZone({ asked++ == 0 }, remember { Probe("ZONE") }))
        })

        drag.begin()
        drag.move(150, 50)
        drag.move(160, 60)
        drag.move(170, 70)
        assertEquals(1, asked)
        assertTrue(drag.drop(170, 70))
        drag.end()

        assertEquals(listOf("started", "entered", "moved", "moved", "moved", "drop", "ended"), heard("ZONE"))
    }

    /**
     * A message box in the chat, as the chat builds it: a text field's own target (here, a plain one that takes what is text, which is what the
     * field has) around the zone of the box, with a relay inside.
     */
    private fun textField(relay: Boolean = true): @Composable BoxScope.() -> Unit = {
        Box(
            Modifier.offset(100.dp, 400.dp).size(200.dp, 60.dp)
                .dragAndDropTarget({ it.toAndroidDragEvent().clipDescription?.hasMimeType("text/*") == true }, remember { Probe("FIELD") }),
        ) {
            val inside = Modifier.fillMaxSize().dropZone({ true }, remember { Probe("BOX") })
            Box(if (relay) inside.dropRelay() else inside)
        }
    }

    private val text = ClipData(ClipDescription("words", arrayOf("text/plain")), ClipData.Item("words"))

    @Test
    fun `text dropped on a text field in the area goes to the zone around it, as it does for anything else, with the relay inside the field`() {
        shell(chat = textField())
        drag.clip = text

        drag.begin()
        drag.move(500, 300)
        val mark = log.size
        drag.move(300, 450)
        assertEquals("the box is entered through the relay, and left alone by the router's own exit", listOf("BOX entered", "BOX moved", "CHAT exited"), log.drop(mark).filter { it.startsWith("BOX") || it.startsWith("CHAT") })
        assertTrue(drag.drop(300, 450))
        drag.end()

        assertEquals(listOf("started", "entered", "moved", "drop", "ended"), heard("BOX"))
        assertFalse("the field's own target was not given it", "drop" in heard("FIELD"))
    }

    @Test
    fun `whatever kind of text it is, the relay takes it`() {
        shell(chat = textField())
        drag.clip = ClipData(ClipDescription("a link", arrayOf("text/uri-list")), ClipData.Item("https://example.com"))

        drag.begin()
        drag.move(300, 450)
        assertTrue(drag.drop(300, 450))
        drag.end()

        assertEquals(listOf("started", "entered", "moved", "drop", "ended"), heard("BOX"))
        assertFalse("drop" in heard("FIELD"))
    }

    @Test
    fun `a window over a text field takes what is dropped there, not the zone around the field`() {
        shell(chat = textField(), windows = { Window("W", 100, 380, 200, layer = 1, zone = false) })
        drag.clip = text

        drag.begin()
        drag.move(500, 300)
        drag.move(300, 450)
        assertFalse("the window refuses it", drag.drop(300, 450))
        drag.end()

        assertEquals(listOf("started", "ended"), heard("BOX"))
        assertEquals(listOf("started", "entered", "moved", "drop", "ended"), heard("W-frame"))
        assertFalse("nor does the field's own target, once the window refused it", "drop" in heard("FIELD"))
    }

    @Test
    fun `the pointer going from a text field to the sidebar takes it out of the zone around the field`() {
        shell(chat = textField())
        drag.clip = text

        drag.begin()
        drag.move(300, 450)
        drag.move(50, 50)
        drag.end()

        assertEquals(listOf("started", "entered", "moved", "exited", "ended"), heard("BOX"))
    }

    @Test
    fun `without a router a zone is an ordinary target`() {
        compose.setContent {
            view = LocalView.current
            Box(Modifier.size(200.dp).dropZone({ true }, remember { Probe("PLAIN") }))
        }
        compose.waitForIdle()

        assertTrue(drag.begin())
        drag.move(50, 50)
        assertTrue(drag.drop(50, 50))
        drag.end()

        assertEquals(listOf("started", "entered", "moved", "drop", "ended"), heard("PLAIN"))
    }

    @Test
    fun `the drag that ends is told to the router once, and a new one starts clean`() {
        twoWindows()
        drag.begin()
        drag.move(150, 50)
        drag.end()
        log.clear()

        drag.begin()
        drag.move(500, 450)
        assertTrue(drag.drop(500, 450))
        drag.end()

        assertEquals("the zone that was current in the first drag is not current in the second", listOf("started", "ended"), heard("FRONT-zone"))
        assertEquals(listOf("started", "entered", "moved", "drop", "ended"), heard("CHAT"))
    }
}
