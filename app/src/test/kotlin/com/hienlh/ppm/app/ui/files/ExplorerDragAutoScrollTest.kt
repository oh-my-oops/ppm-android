package com.hienlh.ppm.app.ui.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.withComposeActivity
import kotlinx.coroutines.runBlocking
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
 * The list under a dragged row scrolls the way the web's does: within 48 dp of an edge, `ceil(intensity x 16)` dp a frame,
 * for as long as the pointer stays and the list can move that way. The frames are counted one by one on a clock held by hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExplorerDragAutoScrollTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val over = ExplorerDragOver()
    private val rows = mutableIntStateOf(100)
    private val rehits = mutableListOf<Offset>()
    private lateinit var list: LazyListState
    private var density = 1f
    private var area = Rect.Zero

    /** A list 400 dp high of rows 26 dp high, which is also the area a drag is over. */
    private fun show() {
        compose.setContent {
            list = rememberLazyListState()
            density = LocalDensity.current.density
            val bounds = remember { mutableStateOf<Rect?>(null) }
            Box(Modifier.size(300.dp, 400.dp).onGloballyPositioned { bounds.value = it.boundsInRoot().also { rect -> area = rect } }) {
                LazyColumn(Modifier.fillMaxSize(), state = list) {
                    items(rows.intValue) { Box(Modifier.fillMaxWidth().height(26.dp)) }
                }
            }
            ExplorerDragAutoScroll(over, list, { bounds.value }) { rehits += over.pointer }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    /** How far the list is scrolled, in px. */
    private fun scrolled(): Int = list.firstVisibleItemIndex * Math.round(26 * density) + list.firstVisibleItemScrollOffset

    private fun pointerAt(x: Float, y: Float) {
        over.pointer = Offset(x, y)
        // The write reaches the `snapshotFlow` watching it on the main looper, which a clock held by hand does not run.
        flushStateWrites()
    }

    private fun frames(count: Int) = repeat(count) { compose.mainClock.advanceTimeByFrame() }

    /** The distance the list moves in the next [count] frames, once the first frames have started it. */
    private fun movesIn(count: Int): Int {
        frames(3)
        val before = scrolled()
        frames(count)
        return scrolled() - before
    }

    @Test fun `at the bottom edge the list moves down by the step on every frame`() {
        show()
        pointerAt(10f, area.bottom - 12f)
        assertEquals(12, movesIn(1))
        assertEquals(12 * 5, movesIn(5))
    }

    @Test @Config(qualifiers = "xhdpi") fun `the step is in dp, so a 2x screen moves twice the pixels`() {
        show()
        assertEquals(2f, density)
        // 24 px from the edge is 12 dp: the step of 12 dp, 24 px.
        pointerAt(10f, area.bottom - 24f)
        assertEquals(24, movesIn(1))
    }

    @Test fun `at the top edge it moves back up`() {
        show()
        compose.runOnUiThread { runBlocking { list.scrollToItem(20) } }
        pointerAt(10f, area.top + 12f)
        assertEquals(-12, movesIn(1))
        assertEquals(-12 * 5, movesIn(5))
    }

    @Test fun `the closer to the edge, the faster`() {
        show()
        pointerAt(10f, area.bottom - 40f)
        assertEquals(3, movesIn(1))
        pointerAt(10f, area.bottom - 2f)
        assertEquals(16, movesIn(1))
    }

    @Test fun `a pointer outside the list moves nothing, above it or below, as the web's list hears only a drag over itself`() {
        show()
        // In the middle of a long list, so that it can move both ways.
        compose.runOnUiThread { runBlocking { list.scrollToItem(20) } }
        pointerAt(10f, area.bottom + 30f)
        assertEquals(0, movesIn(3))
        pointerAt(10f, area.top - 30f)
        assertEquals(0, movesIn(3))
        assertEquals(emptyList<Offset>(), rehits)
        pointerAt(10f, area.bottom - 2f)
        assertEquals("back on the list's edge it moves again", 16, movesIn(1))
    }

    @Test fun `in the middle of the list nothing moves`() {
        show()
        pointerAt(10f, area.center.y)
        assertEquals(0, movesIn(10))
        assertEquals(emptyList<Offset>(), rehits)
    }

    @Test fun `with no pointer nothing moves`() {
        show()
        assertEquals(0, movesIn(10))
    }

    @Test fun `a pointer that goes stops the list`() {
        show()
        pointerAt(10f, area.bottom - 12f)
        assertEquals(12, movesIn(1))
        over.pointer = Offset.Unspecified
        flushStateWrites()
        assertEquals(0, movesIn(5))
    }

    @Test fun `each step tells the tree to ask again what is under the pointer`() {
        show()
        pointerAt(10f, area.bottom - 12f)
        frames(3)
        val before = rehits.size
        frames(4)
        assertEquals(4, rehits.size - before)
        assertTrue(rehits.all { it == Offset(10f, area.bottom - 12f) })
    }

    @Test fun `a list that has reached its end stops asking for frames, and a list that grows starts again`() {
        rows.intValue = 20
        show()
        pointerAt(10f, area.bottom - 12f)
        frames(60)
        assertFalse(list.canScrollForward)
        val atEnd = scrolled()
        val steps = rehits.size
        frames(10)
        assertEquals(atEnd, scrolled())
        assertEquals("no frame is asked for at the end", steps, rehits.size)

        rows.intValue = 40
        flushStateWrites()
        frames(3)
        assertTrue("the list moves again once it has more to show", movesIn(3) > 0)
    }
}
