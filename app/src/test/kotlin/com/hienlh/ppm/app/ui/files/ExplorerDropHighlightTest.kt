package com.hienlh.ppm.app.ui.files

import android.view.DragEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.hienlh.ppm.design.BuiltInThemes
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What a dragged row lights, drawn for real (native graphics, 3 pixels to the dp) on a black ground: a row is `ring-1 ring-primary
 * bg-primary/10` while a drag of the tree's own is over it, the whole tree, its header too, `bg-primary/5` while one is over its
 * blank area. The web lights every row for such a drag, even one whose drop it will refuse, but the tree only once the drag has rested
 * 120 ms on the blank area and only when the drop would be taken (a move of rows that are in the root already is not, a copy is).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
internal class ExplorerDropHighlightTest : ExplorerTreeDragTestBase() {
    private val accent = BuiltInThemes.auroraDark.tokens.accent.color
    private val tinted10 = accent.copy(alpha = 0.1f).compositeOver(Color.Black)
    private val tinted5 = accent.copy(alpha = 0.05f).compositeOver(Color.Black)

    private fun showOnBlack() = show { content -> Box(Modifier.fillMaxSize().background(Color.Black)) { content() } }

    private fun assertColor(message: String, expected: Color, actual: Color) {
        for ((name, want, got) in listOf(Triple("red", expected.red, actual.red), Triple("green", expected.green, actual.green), Triple("blue", expected.blue, actual.blue))) {
            assertEquals("$message, $name", want, got, 0.02f)
        }
    }

    /** The fill of a row left of its content and clear of its ring, and the ring itself. */
    private fun fill(text: String): Color = compose.onNodeWithText(text).captureToImage().toPixelMap().let { it[18, it.height / 2] }

    private fun ring(text: String): Color = compose.onNodeWithText(text).captureToImage().toPixelMap().let { it[1, it.height / 2] }

    /** The list's blank area, a pixel inside the bottom of the window. */
    private fun blankFill(): Color = compose.onRoot().captureToImage().toPixelMap().let { it[it.width / 2, it.height - 6] }

    /** The header's own corner, clear of its icon and its words: the toolbar has 8 dp of padding. */
    private fun headerFill(): Color = compose.onRoot().captureToImage().toPixelMap()[3, 3]

    /** Drawn after a frame, because the clock of these is held by hand. */
    private fun settle() = compose.mainClock.advanceTimeByFrame()

    @Test fun `a row is plain until a drag is over it`() {
        showOnBlack()
        assertColor("fill", Color.Black, fill("docs"))
        assertColor("ring", Color.Black, ring("docs"))
    }

    @Test fun `the row a drag is over has the primary fill and ring, and only that row`() {
        showOnBlack()
        val drag = row("README.md")
        assertEquals(true, begin(drag))
        over(drag, center("docs"))
        assertColor("fill", tinted10, fill("docs"))
        assertColor("ring", accent.compositeOver(Color.Black), ring("docs"))
        assertColor("another row's fill", Color.Black, fill("app.kt"))
        assertColor("another row's ring", Color.Black, ring("app.kt"))

        over(drag, center("app.kt"))
        assertColor("the first row once the drag has left it", Color.Black, fill("docs"))
        assertColor("the first row's ring once it has left", Color.Black, ring("docs"))
        assertColor("the row it is over now", tinted10, fill("app.kt"))
    }

    @Test fun `a row lights for a drag whose drop it will refuse`() {
        showOnBlack()
        val drag = row("src", isDirectory = true)
        begin(drag)
        over(drag, center("src"))
        assertColor("onto itself", tinted10, fill("src"))
        over(drag, center("util"))
        assertColor("into a folder of its own", tinted10, fill("util"))
    }

    @Test fun `a row is plain again once the drag is dropped, or ends elsewhere`() {
        showOnBlack()
        val drag = row("README.md")
        begin(drag)
        over(drag, center("docs"))
        drop(drag, center("docs"))
        assertColor("after the drop", Color.Black, fill("docs"))

        begin(drag)
        over(drag, center("src"))
        dispatch(DragEvent.ACTION_DRAG_ENDED, drag)
        compose.waitForIdle()
        assertColor("after a drag that ended elsewhere", Color.Black, fill("src"))
    }

    @Test fun `the whole tree is tinted once a drag has rested 120 ms on the blank area, the header with it`() {
        showOnBlack()
        compose.mainClock.autoAdvance = false
        val drag = row("src/app.kt")
        begin(drag)
        hover(drag, blank())
        // Each of `hover` and `settle` runs a frame (16 ms), so this is 96 ms on the blank area, then 152.
        advance(64)
        settle()
        assertColor("blank area before 120 ms", Color.Black, blankFill())
        assertColor("header before 120 ms", Color.Black, headerFill())

        advance(40)
        settle()
        assertColor("blank area after 120 ms", tinted5, blankFill())
        assertColor("header", tinted5, headerFill())
        assertColor("the rows are drawn over the same tint, as the web's rows are over the container's", tinted5, fill("docs"))
    }

    @Test fun `a drag resting on the header tints the whole tree after 120 ms as one on the blank area does, the header being the same target`() {
        showOnBlack()
        compose.mainClock.autoAdvance = false
        val drag = row("src/app.kt")
        begin(drag)
        hover(drag, header())
        advance(64)
        settle()
        assertColor("blank area before 120 ms", Color.Black, blankFill())
        assertColor("header before 120 ms", Color.Black, headerFill())

        advance(40)
        settle()
        assertColor("blank area after 120 ms", tinted5, blankFill())
        assertColor("header after 120 ms", tinted5, headerFill())
    }

    @Test fun `a drag that sweeps across the header flashes nothing, and moving from the header to a row takes the tint away`() {
        showOnBlack()
        compose.mainClock.autoAdvance = false
        val drag = row("src/app.kt")
        begin(drag)
        hover(drag, header())
        advance(64)
        hover(drag, center("docs"))
        advance(500)
        settle()
        assertColor("header once the drag is over a row", Color.Black, headerFill())

        hover(drag, header())
        advance(200)
        settle()
        assertColor("lit on the header", tinted5, headerFill())
        hover(drag, center("docs"))
        settle()
        assertColor("over a row again", Color.Black, headerFill())
    }

    @Test fun `a move of a row of the root is no more tinted on the header than on the blank area`() {
        showOnBlack()
        compose.mainClock.autoAdvance = false
        val drag = row("README.md")
        begin(drag)
        hover(drag, header())
        advance(1000)
        settle()
        assertColor("header", Color.Black, headerFill())
        assertColor("blank area", Color.Black, blankFill())
    }

    @Test fun `the project folder, or a folder above it, dragged over the tree is tinted nowhere, however long it rests and whatever key is held`() {
        showOnBlack()
        compose.mainClock.autoAdvance = false
        for (copy in listOf(false, true)) {
            copyHeld = copy
            for (dragged in listOf(ROOT, "/home/me")) {
                val drag = entries(dragged)
                begin(drag)
                hover(drag, blank())
                advance(1000)
                settle()
                assertColor("$dragged on the blank area, copy $copy", Color.Black, blankFill())
                hover(drag, header())
                advance(1000)
                settle()
                assertColor("$dragged on the header, copy $copy", Color.Black, headerFill())
                dispatch(DragEvent.ACTION_DRAG_ENDED, drag)
                settle()
            }
        }
    }

    @Test fun `a drag that sweeps across the blank area flashes nothing`() {
        showOnBlack()
        compose.mainClock.autoAdvance = false
        val drag = row("src/app.kt")
        begin(drag)
        hover(drag, blank())
        advance(64)
        hover(drag, center("docs"))
        advance(500)
        settle()
        assertColor("blank area once the drag is over a row", Color.Black, blankFill())
        assertColor("header", Color.Black, headerFill())
    }

    @Test fun `the tint goes when the drag leaves the blank area, and coming back waits again`() {
        showOnBlack()
        compose.mainClock.autoAdvance = false
        val drag = row("src/app.kt")
        begin(drag)
        hover(drag, blank())
        advance(200)
        settle()
        assertColor("lit", tinted5, blankFill())

        hover(drag, center("docs"))
        settle()
        assertColor("over a row", Color.Black, blankFill())
        assertColor("over a row, the header", Color.Black, headerFill())

        hover(drag, blank())
        advance(64)
        settle()
        assertColor("back for less than 120 ms", Color.Black, blankFill())
        advance(40)
        settle()
        assertColor("back for more than 120 ms", tinted5, blankFill())
    }

    @Test fun `the tint goes with the drop and with a drag that ends elsewhere`() {
        showOnBlack()
        compose.mainClock.autoAdvance = false
        val drag = row("src/app.kt")
        begin(drag)
        hover(drag, blank())
        advance(200)
        settle()
        assertColor("lit", tinted5, blankFill())
        dispatch(DragEvent.ACTION_DROP, drag, blank())
        dispatch(DragEvent.ACTION_DRAG_ENDED, drag)
        settle()
        assertColor("after the drop", Color.Black, blankFill())
        assertColor("after the drop, the header", Color.Black, headerFill())

        begin(drag)
        hover(drag, blank())
        advance(200)
        settle()
        assertColor("lit again", tinted5, blankFill())
        dispatch(DragEvent.ACTION_DRAG_ENDED, drag)
        settle()
        assertColor("after a drag that ended elsewhere", Color.Black, blankFill())
    }

    @Test fun `the tree is not tinted for a move of a row of the root, whose drop it would refuse, however long the drag rests`() {
        showOnBlack()
        compose.mainClock.autoAdvance = false
        val drag = row("README.md")
        begin(drag)
        hover(drag, blank())
        advance(1000)
        settle()
        assertColor("blank area", Color.Black, blankFill())
        assertColor("header", Color.Black, headerFill())
    }

    @Test fun `it is tinted for a copy of a row of the root, which the drop would take`() {
        showOnBlack()
        compose.mainClock.autoAdvance = false
        copyHeld = true
        val drag = row("README.md")
        begin(drag)
        hover(drag, blank())
        advance(200)
        settle()
        assertColor("blank area", tinted5, blankFill())
    }

    @Test fun `a tree with no shared transfer is never tinted, for its blank area takes no drop`() {
        show(content = { tree, commands -> ExplorerTree(tree, onOpenFile = {}, mutations = commands) }) { content ->
            Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
        }
        compose.mainClock.autoAdvance = false
        val drag = row("src/app.kt")
        begin(drag)
        hover(drag, blank())
        advance(500)
        settle()
        assertColor("blank area", Color.Black, blankFill())
    }

    @Test fun `a drag from another project whose root is unknown lights nothing`() {
        showOnBlack()
        val other = ExplorerRowDrag("elsewhere", "README.md", false)
        begin(other)
        over(other, center("docs"))
        assertColor("fill", Color.Black, fill("docs"))
        assertColor("ring", Color.Black, ring("docs"))
    }

    @Test fun `a window's drag, or another project's with a known root, lights a folder row after 120 ms when it would take the drop, and not when it would refuse`() {
        showOnBlack()
        compose.mainClock.autoAdvance = false
        for (drag in listOf(HostEntryDrag(listOf("/elsewhere/x.txt")), ExplorerRowDrag("elsewhere", "src/app.kt", false, listOf("src/app.kt"), "/home/me/p2"))) {
            begin(drag)
            hover(drag, center("docs"))
            advance(64)
            settle()
            assertColor("$drag before 120 ms", Color.Black, fill("docs"))
            advance(40)
            settle()
            assertColor("$drag fill after 120 ms", tinted10, fill("docs"))
            assertColor("$drag ring after 120 ms", accent.compositeOver(Color.Black), ring("docs"))
            assertColor("$drag blank area stays plain", Color.Black, blankFill())
            dispatch(DragEvent.ACTION_DRAG_ENDED, drag)
            settle()
        }

        val refused = HostEntryDrag(listOf("$ROOT/docs/guide.md"))
        begin(refused)
        hover(refused, center("docs"))
        advance(1000)
        settle()
        assertColor("refused folder fill", Color.Black, fill("docs"))
        assertColor("refused folder ring", Color.Black, ring("docs"))
    }

    @Test fun `a window's drag over a file's row tints the whole tree after 120 ms, the file's row having no ring of its own`() {
        showOnBlack()
        compose.mainClock.autoAdvance = false
        val drag = HostEntryDrag(listOf("/elsewhere/x.txt"))
        begin(drag)
        hover(drag, center("README.md"))
        advance(64)
        settle()
        assertColor("blank area before 120 ms", Color.Black, blankFill())
        advance(40)
        settle()
        assertColor("blank area after 120 ms", tinted5, blankFill())
        assertColor("header after 120 ms", tinted5, headerFill())
        assertColor("file row fill is the tree's tint", tinted5, fill("README.md"))
        assertColor("file row has no ring of its own", tinted5, ring("README.md"))
    }
}
