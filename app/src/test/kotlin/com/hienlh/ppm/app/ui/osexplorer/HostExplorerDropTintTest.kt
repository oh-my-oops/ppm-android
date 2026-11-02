package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.DragModifierKeys
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.state.osexplorer.ExplorerView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What a window's views light for a drag of entries, drawn for real (native graphics, 3 pixels to the dp) on a black ground: `ring-1
 * ring-primary bg-primary/10` (`DROP_TARGET_CLASS`) on the folder's row or tile the drag has rested on, and the same on the view's own
 * scrolling area while it rests there or on a file's row (its folder). Only after 120 ms (`HIGHLIGHT_DELAY_MS`), so a sweep across
 * rows flashes none, and only where the drop would be taken, so a refusal never looks like a promise.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w600dp-h500dp-xxhdpi")
internal class HostExplorerDropTintTest : HostEntryDndTestBase() {
    override val ground: Color = Color.Black
    override val boxWidth: Dp = 460.dp
    override val boxHeight: Dp = 360.dp
    override val windowsSkin = true

    private val accent = BuiltInThemes.auroraDark.tokens.accent.color
    private val tinted = accent.copy(alpha = 0.1f).compositeOver(Color.Black)
    private val ring = accent.compositeOver(Color.Black)

    private fun assertColor(message: String, expected: Color, actual: Color) {
        for ((name, want, got) in listOf(Triple("red", expected.red, actual.red), Triple("green", expected.green, actual.green), Triple("blue", expected.blue, actual.blue))) {
            assertEquals("$message, $name", want, got, 0.02f)
        }
    }

    /** The fill of a row, left of its icon and clear of the ring. */
    private fun fill(name: String): Color = compose.onNodeWithContentDescription(name).captureToImage().toPixelMap().let { it[18, it.height / 2] }

    /** The ring's own pixel on the left edge of a row. */
    private fun ringOf(name: String): Color = compose.onNodeWithContentDescription(name).captureToImage().toPixelMap().let { it[1, it.height / 2] }

    /** A pixel of a row just inside its ring, which is 1 dp, 3 px, wide: a wider one would reach it. */
    private fun insideRingOf(name: String): Color = compose.onNodeWithContentDescription(name).captureToImage().toPixelMap().let { it[4, it.height / 2] }

    /** One pixel of a tile, counted from its top left corner. */
    private fun tilePixel(name: String, x: Int, y: Int): Color = compose.onNodeWithContentDescription(name).captureToImage().toPixelMap()[x, y]

    /** The ring's pixel on the top edge of a row, which the area's own ring, on the list's left and right, does not reach. */
    private fun topRingOf(name: String): Color = compose.onNodeWithContentDescription(name).captureToImage().toPixelMap().let { it[it.width / 2, 1] }

    /** A tile's fill, in the corner of its padding, which neither the ring nor the icon reaches. */
    private fun tileFill(name: String): Color = compose.onNodeWithContentDescription(name).captureToImage().toPixelMap()[24, 24]

    /** The view's own area, below its last row, where nothing else is drawn: its fill, and its ring on the left edge. */
    private fun areaFill(): Color = compose.onNodeWithTag("explorer").captureToImage().toPixelMap().let { it[300, it.height - 40] }

    private fun areaRing(): Color = compose.onNodeWithTag("explorer").captureToImage().toPixelMap().let { it[1, it.height - 40] }

    /** The column of [folder], below its last row. */
    private fun columnFill(folder: String): Color = compose.onNodeWithContentDescription(folder).captureToImage().toPixelMap().let { it[150, it.height - 40] }

    private fun columnRing(folder: String): Color = compose.onNodeWithContentDescription(folder).captureToImage().toPixelMap().let { it[1, it.height - 40] }

    /** Exactly [ms] of virtual time, then a frame to draw what it changed. */
    private fun advance(ms: Long) {
        compose.mainClock.advanceTimeBy(ms, ignoreFrameDuration = true)
        compose.mainClock.advanceTimeByFrame()
    }

    private fun nothingIsLit(rows: List<String>) {
        for (row in rows) {
            assertColor("$row's fill", Color.Black, fill(row))
            assertColor("$row's ring", Color.Black, ringOf(row))
        }
        assertColor("the area's fill", Color.Black, areaFill())
        assertColor("the area's ring", Color.Black, areaRing())
    }

    private val rows = listOf("docs", "src", "a.txt", "b.txt")

    @Test
    fun `nothing is lit until a drag is over something`() {
        show(ExplorerView.LIST, clockHeld = true)
        nothingIsLit(rows)
    }

    @Test
    fun `the folder a drag has rested 120 ms on has the primary fill and ring, and only that row`() {
        show(ExplorerView.LIST, clockHeld = true)
        begin(entries("/elsewhere/x.txt"))
        hover(center("docs"))
        advance(100)
        assertColor("docs at 100 ms", Color.Black, fill("docs"))
        advance(40)
        assertColor("docs at 140 ms: fill", tinted, fill("docs"))
        assertColor("docs at 140 ms: ring", ring, ringOf("docs"))
        assertColor("docs at 140 ms: the ring is one dp wide", tinted, insideRingOf("docs"))
        assertColor("another folder", Color.Black, fill("src"))
        assertColor("a file", Color.Black, fill("a.txt"))
        assertColor("the area is not lit by a row in it", Color.Black, areaFill())
        assertColor("nor its ring", Color.Black, areaRing())
    }

    @Test
    fun `a drag that sweeps across the folders lights none of them`() {
        show(ExplorerView.LIST, clockHeld = true)
        begin(entries("/elsewhere/x.txt"))
        for (row in listOf("docs", "src", "docs", "src")) {
            hover(center(row))
            advance(60)
        }
        nothingIsLit(rows)
    }

    @Test
    fun `a drag that goes from one file's row to another has rested on the area all the while, and lights it once that is 120 ms`() {
        show(ExplorerView.LIST, clockHeld = true)
        begin(entries("/elsewhere/x.txt"))
        hover(center("a.txt"))
        advance(60)
        hover(center("b.txt"))
        advance(30)
        assertColor("after 106 ms", Color.Black, areaFill())
        advance(30)
        assertColor("after 136 ms", tinted, areaFill())
    }

    @Test
    fun `moving to another row takes the tint away, and the new one waits its own 120 ms`() {
        show(ExplorerView.LIST, clockHeld = true)
        begin(entries("/elsewhere/x.txt"))
        hover(center("docs"))
        advance(140)
        assertColor("docs", tinted, fill("docs"))
        hover(center("src"))
        advance(16)
        assertColor("docs once the drag has left it", Color.Black, fill("docs"))
        assertColor("src just after", Color.Black, fill("src"))
        advance(140)
        assertColor("src after 120 ms", tinted, fill("src"))
        assertColor("docs is still plain", Color.Black, fill("docs"))
    }

    @Test
    fun `a file's row lights the area, its folder, and not itself`() {
        show(ExplorerView.LIST, clockHeld = true)
        begin(entries("/elsewhere/x.txt"))
        hover(center("a.txt"))
        advance(100)
        assertColor("the area at 100 ms", Color.Black, areaFill())
        advance(40)
        assertColor("the area's fill", tinted, areaFill())
        assertColor("the area's ring", ring, areaRing())
        assertColor("the file's row has no tint of its own, only the area's under it", tinted, fill("a.txt"))
        assertColor("nor a ring", tinted, topRingOf("a.txt"))
    }

    @Test
    fun `the space under the rows lights the area`() {
        show(ExplorerView.LIST, clockHeld = true)
        begin(entries("/elsewhere/x.txt"))
        hover(below())
        advance(140)
        assertColor("fill", tinted, areaFill())
        assertColor("ring", ring, areaRing())
    }

    @Test
    fun `a drag that leaves, or is dropped, or ends elsewhere, takes the tint away`() {
        show(ExplorerView.LIST, clockHeld = true)
        begin(entries("/elsewhere/x.txt"))
        hover(center("docs"))
        advance(140)
        assertColor("lit", tinted, fill("docs"))
        hover(top())
        advance(16)
        assertColor("over the header, which is no target", Color.Black, fill("docs"))
        driver.end()
        advance(16)
        nothingIsLit(rows)

        begin(entries("/elsewhere/x.txt"))
        hover(center("src"))
        advance(140)
        assertColor("lit again", tinted, fill("src"))
        driver.drop(center("src").x.toInt(), center("src").y.toInt())
        driver.end()
        advance(16)
        assertColor("after the drop", Color.Black, fill("src"))
        awaitTransfer()
    }

    @Test
    fun `a folder that would refuse the drop is not lit, and nor is the area behind it`() {
        show(ExplorerView.LIST, clockHeld = true)
        begin(entries("/home/me/docs"))
        hover(center("docs"))
        advance(300)
        assertColor("a folder onto itself", Color.Black, fill("docs"))
        assertColor("the area behind it", Color.Black, areaFill())
        driver.end()

        begin(entries("/home/me/docs/guide.md"))
        hover(center("docs"))
        advance(300)
        assertColor("a move out of the folder it is on", Color.Black, fill("docs"))
        assertColor("the area is not the folder's stand-in", Color.Black, areaFill())
    }

    @Test
    fun `what is in the folder already lights nothing as a move, and the area as a copy, by the keys at the moment it arrives`() {
        show(ExplorerView.LIST, clockHeld = true)
        begin(entries("/home/me/a.txt"))
        hover(below())
        advance(300)
        assertColor("a move", Color.Black, areaFill())
        driver.end()

        DragModifierKeys.update(ctrl = true, alt = false)
        begin(entries("/home/me/a.txt"))
        hover(below())
        advance(140)
        assertColor("a copy", tinted, areaFill())
        assertColor("its ring", ring, areaRing())
    }

    @Test
    fun `a tile is lit as a row is, with the area behind it while the drag is over a file's tile`() {
        show(ExplorerView.ICONS, clockHeld = true)
        begin(entries("/elsewhere/x.txt"))
        hover(center("docs"))
        advance(100)
        assertColor("docs at 100 ms", Color.Black, tileFill("docs"))
        advance(40)
        assertColor("docs at 140 ms: fill", tinted, tileFill("docs"))
        assertColor("docs at 140 ms: ring", ring, ringOf("docs"))
        assertColor("the ring turns with the tile's corner, 6 dp round", ring, tilePixel("docs", 4, 8))
        assertColor("and nothing is drawn outside the corner", Color.Black, tilePixel("docs", 0, 0))
        assertColor("src", Color.Black, tileFill("src"))
        assertColor("the area", Color.Black, areaFill())

        hover(center("a.txt"))
        advance(16)
        assertColor("docs once the drag has left it", Color.Black, tileFill("docs"))
        advance(140)
        assertColor("the area while the drag is over a file's tile", tinted, areaFill())
        assertColor("its ring", ring, areaRing())
        assertColor("the file's tile has no tint of its own, only the area's under it", tinted, tileFill("a.txt"))
        assertColor("nor a ring", tinted, ringOf("a.txt"))
    }

    @Test
    fun `a column's folder row and a column's own area are lit as the list's are`() {
        show(ExplorerView.COLUMNS, clockHeld = true)
        // The column that has the focus is shaded, and what is lit is drawn over that.
        val shaded = fill("docs")
        val lit = accent.copy(alpha = 0.1f).compositeOver(shaded)
        val focusedArea = columnFill("/home/me")
        val litArea = accent.copy(alpha = 0.1f).compositeOver(focusedArea)
        begin(entries("/elsewhere/x.txt"))
        hover(center("docs"))
        advance(140)
        assertColor("docs: fill", lit, fill("docs"))
        assertColor("docs: ring", ring, ringOf("docs"))
        assertColor("the column's own area", focusedArea, columnFill("/home/me"))

        hover(center("a.txt"))
        advance(16)
        advance(140)
        assertColor("the column while the drag is over its file", litArea, columnFill("/home/me"))
        assertColor("its ring", ring, columnRing("/home/me"))
        assertColor("the column to its left", Color.Black, columnFill("/home"))

        hover(belowColumn("/home"))
        advance(16)
        advance(140)
        assertColor("the column to the left, when the drag is over it", tinted, columnFill("/home"))
        assertColor("the one it left", focusedArea, columnFill("/home/me"))
        assertColor("docs", shaded, fill("docs"))
    }

    @Test
    fun `a phone's window lights nothing`() {
        show(ExplorerView.LIST, mobile = true, clockHeld = true)
        begin(entries("/elsewhere/x.txt"))
        hover(below())
        advance(300)
        assertColor("the area", Color.Black, areaFill())
    }
}
