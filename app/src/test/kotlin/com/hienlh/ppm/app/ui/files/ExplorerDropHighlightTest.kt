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
 * bg-primary/10` while a drag of the tree's own is over it, the list `bg-primary/5` while one is over its blank area. The
 * web lights every row for such a drag, even one whose drop it will refuse, but the blank area only when the drop would move
 * the row.
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

    @Test fun `the blank area is tinted for a row that would change folders`() {
        showOnBlack()
        val drag = row("src/app.kt")
        begin(drag)
        over(drag, blank())
        assertColor("blank area", tinted5, blankFill())
        assertColor("the rows are drawn over the same tint, as the web's rows are over the container's", tinted5, fill("docs"))
        over(drag, center("docs"))
        assertColor("blank area once the drag is over a row", Color.Black, blankFill())
    }

    @Test fun `the blank area is not tinted for a row of the root, whose drop it would refuse`() {
        showOnBlack()
        val drag = row("README.md")
        begin(drag)
        over(drag, blank())
        assertColor("blank area", Color.Black, blankFill())
    }

    @Test fun `a drag from another project lights nothing`() {
        showOnBlack()
        val other = ExplorerRowDrag("elsewhere", "README.md", false)
        begin(other)
        over(other, center("docs"))
        assertColor("fill", Color.Black, fill("docs"))
        assertColor("ring", Color.Black, ring("docs"))
    }
}
