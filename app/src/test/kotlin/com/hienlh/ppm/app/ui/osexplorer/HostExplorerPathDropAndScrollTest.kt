package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.DragModifierKeys
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.protocol.HostDirectory
import com.hienlh.ppm.protocol.HostFilesystem
import com.hienlh.ppm.protocol.HostPlace
import com.hienlh.ppm.state.osexplorer.ExplorerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sidebar's places (`explorer-sidebar.tsx`), the toolbar's breadcrumb crumbs (`explorer-toolbar.tsx`), and the views' auto-scroll
 * (`useDragAutoScroll`) when entries are dragged over them: places and crumbs take a drop into their path without a success toast and
 * light after 120 ms when they would take it; List, Icons and Columns scroll at 48 dp from an edge and re-hit what slides under the pointer.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w800dp-h600dp-xxhdpi")
internal class HostExplorerPathDropAndScrollTest : HostEntryDndTestBase() {
    private val accent = BuiltInThemes.auroraDark.tokens.accent.color
    private val ring = accent.compositeOver(Color.Black)

    private val filesystem = HostFilesystem(
        platform = "linux",
        separator = "/",
        home = "/home/me",
        hostname = "box",
        knownFolders = listOf(HostPlace("Desktop", "/home/me/Desktop"), HostPlace("Documents", "/home/me/Documents")),
        drives = listOf(HostPlace("/", "/")),
        pinned = listOf(HostPlace("Work", "/work")),
        warnings = emptyList(),
    )

    private fun showChrome() {
        com.hienlh.ppm.app.ui.files.installBlankFileIcons()
        compose.setContent {
            host = LocalView.current
            val state by session.state.collectAsState()
            val preferences by session.preferences.state.collectAsState()
            Row(Modifier.size(800.dp, 400.dp).background(Color.Black)) {
                HostExplorerPlaces(session, filesystem, windows = false, commands = commands)
                Box(Modifier.width(600.dp)) {
                    HostExplorerToolbar(session, state, preferences, canUpload = true, commands = commands) {}
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun placeCenter(label: String): Offset = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.center

    private fun advance(ms: Long) {
        compose.mainClock.advanceTimeBy(ms, ignoreFrameDuration = true)
        compose.mainClock.advanceTimeByFrame()
    }

    private fun assertColor(message: String, expected: Color, actual: Color) {
        for ((name, want, got) in listOf(Triple("red", expected.red, actual.red), Triple("green", expected.green, actual.green), Triple("blue", expected.blue, actual.blue))) {
            assertEquals("$message, $name", want, got, 0.03f)
        }
    }

    @Test
    fun `a place in the sidebar takes a drop into its path without announcing a toast, and refuses one that is in it already as a move`() {
        showChrome()
        assertTrue(begin(entries("/elsewhere/x.txt")))
        hover(placeCenter("Desktop"))
        assertTrue(letGo(placeCenter("Desktop")))
        assertEquals(listOf("move /elsewhere/x.txt -> /home/me/Desktop/x.txt"), transfers.toList())
        assertEquals("sidebar places do not announce a success toast", emptyList<String>(), titles())

        begin(entries("/home/me/Desktop/x.txt"))
        hover(placeCenter("Desktop"))
        assertFalse("already in Desktop", letGo(placeCenter("Desktop")))
        assertEquals(1, transfers.size)

        DragModifierKeys.update(ctrl = true, alt = false)
        begin(entries("/home/me/Desktop/x.txt"))
        hover(placeCenter("Desktop"))
        assertTrue("a copy in Desktop is taken", letGo(placeCenter("Desktop")))
        assertEquals("copy /home/me/Desktop/x.txt -> /home/me/Desktop/x (2).txt", transfers.last())
        assertEquals(emptyList<String>(), titles())
    }

    @Test
    fun `a breadcrumb crumb takes a drop into its segment without announcing a toast, and refuses one already in that folder`() {
        showChrome()
        assertTrue(begin(entries("/home/me/a.txt")))
        hover(placeCenter("me"))
        assertFalse("already in /home/me", letGo(placeCenter("me")))
        assertEquals(emptyList<String>(), transfers.toList())

        begin(entries("/home/me/a.txt"))
        hover(placeCenter("home"))
        assertTrue("moves up to /home", letGo(placeCenter("home")))
        assertEquals(listOf("move /home/me/a.txt -> /home/a.txt"), transfers.toList())
        assertEquals("breadcrumb crumbs do not announce a success toast", emptyList<String>(), titles())
    }

    @Test
    fun `a breadcrumb crumb lights after 120 ms when it would take the drop, and stays plain when it would refuse`() {
        showChrome()
        fun crumbTopPixel(name: String): Color = compose.onNodeWithText(name).captureToImage().toPixelMap().let { it[it.width / 2, 1] }
        val plain = crumbTopPixel("home")

        begin(entries("/home/me/a.txt"))
        hover(placeCenter("me"))
        advance(300)
        assertColor("refused crumb", plain, crumbTopPixel("me"))

        hover(placeCenter("home"))
        advance(80)
        assertColor("before 120 ms", plain, crumbTopPixel("home"))
        advance(60)
        assertColor("lit after 120 ms", ring, crumbTopPixel("home"))
        driver.end()
    }

    private fun assertVerticalAutoScroll(view: ExplorerView) {
        val many = (0 until 40).map { dir("/home/me/d%02d".format(it)) }
        val home = HostDirectory(many, "/home/me", "/home", listOf(HostPlace("home", "/home"), HostPlace("me", "/home/me")), "/", truncated = false)
        session.close()
        session = explorerSessionOn(home, { home }, "/home/me")
        commands = HostExplorerCommands(toasts::add, api, session, com.hienlh.ppm.state.osexplorer.HostFileActions(api, com.hienlh.ppm.state.files.FileMutations(api), "/", null), null, scope)
        show(view, clockHeld = true)
        assertTrue(begin(entries("/elsewhere/x.txt")))
        val nearBottom = compose.onNodeWithTag("explorer").fetchSemanticsNode().boundsInRoot.let { Offset(it.center.x, it.bottom - 12f) }
        hover(nearBottom)
        repeat(30) { compose.mainClock.advanceTimeByFrame() }
        assertTrue("$view scrolled down to reveal later folders", compose.onAllNodesWithContentDescription("d15").fetchSemanticsNodes().isNotEmpty())
        driver.end()
    }

    @Test
    fun `the List view scrolls vertically when a drag hovers within 48 dp of its bottom edge`() = assertVerticalAutoScroll(ExplorerView.LIST)

    @Test
    fun `the Icons view scrolls vertically when a drag hovers within 48 dp of its bottom edge`() = assertVerticalAutoScroll(ExplorerView.ICONS)

    @Test
    fun `the Columns view scrolls horizontally near the strip's edges and vertically near a column's edges`() {
        val manyInLeaf = (0 until 40).map { dir("/a/b/c/d/d%02d".format(it)) }
        val leaf = HostDirectory(
            manyInLeaf, "/a/b/c/d", "/a/b/c",
            listOf(HostPlace("a", "/a"), HostPlace("b", "/a/b"), HostPlace("c", "/a/b/c"), HostPlace("d", "/a/b/c/d")),
            "/", truncated = false,
        )
        session.close()
        session = explorerSessionOn(leaf, { leaf }, "/a/b/c/d")
        commands = HostExplorerCommands(toasts::add, api, session, com.hienlh.ppm.state.osexplorer.HostFileActions(api, com.hienlh.ppm.state.files.FileMutations(api), "/", null), null, scope)
        com.hienlh.ppm.app.ui.files.installBlankFileIcons()
        session.preferences.update(session.preferences.state.value.copy(view = ExplorerView.COLUMNS))
        compose.setContent {
            host = LocalView.current
            val state by session.state.collectAsState()
            val preferences by session.preferences.state.collectAsState()
            val entries = session.visibleEntries()
            Box(Modifier.size(420.dp, 300.dp).testTag("explorer")) {
                HostExplorerColumns(api, session, entries, state, preferences, mobile = false, onSelect = {}, onOpen = {}, onMenu = { _, _, _ -> }, commands = commands)
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        val box = compose.onNodeWithTag("explorer").fetchSemanticsNode().boundsInRoot
        assertTrue(begin(entries("/elsewhere/x.txt")))

        // Hover near the bottom of the rightmost visible column (/a/b/c/d) -> vertical auto-scroll reveals d20.
        hover(Offset(box.right - 60f, box.bottom - 12f))
        repeat(35) { compose.mainClock.advanceTimeByFrame() }
        assertTrue("column scrolled vertically", compose.onAllNodesWithContentDescription("d20").fetchSemanticsNodes().isNotEmpty())

        // Hover near the left edge of the strip -> horizontal auto-scroll brings the leftmost column (/a) into view.
        hover(Offset(box.left + 12f, box.center.y))
        repeat(40) { compose.mainClock.advanceTimeByFrame() }
        assertTrue("strip scrolled horizontally to /a", compose.onAllNodesWithContentDescription("/a").fetchSemanticsNodes().isNotEmpty())
        driver.end()
    }
}
