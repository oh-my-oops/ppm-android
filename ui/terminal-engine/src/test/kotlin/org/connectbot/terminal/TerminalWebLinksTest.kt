package org.connectbot.terminal

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The links of a row apart from the plain URLs `WebLinksOracleTest` holds against xterm itself: the ones an escape sequence made
 * (`OscLinkProvider`), which of the two kinds a cell belongs to when both claim it (`Linkifier._removeIntersectingLinks`), and the
 * viewport's row and column mapped onto the buffer the links are found in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalWebLinksTest {
    private val cols = 40

    private fun row(index: Int, text: String, softWrapped: Boolean = false, segments: List<SemanticSegment> = emptyList()) = TerminalLine(
        index,
        List(cols) { col -> TerminalLine.Cell(text.getOrElse(col) { ' ' }, emptyList(), fgColor = Color.White, bgColor = Color.Black, width = 1) },
        semanticSegments = segments,
        softWrapped = softWrapped,
    )

    private fun osc(start: Int, end: Int, uri: String?, type: SemanticType = SemanticType.HYPERLINK) = SemanticSegment(start, end, type, uri)

    private fun links(vararg lines: TerminalLine) = TerminalWebLinks(cols) { lines.getOrNull(it) }

    @Test
    fun `an OSC 8 link is the cells it covers, counted from 1, and says it came from an escape sequence`() {
        val web = links(row(0, "go to the docs now", segments = listOf(osc(6, 14, "https://example.com/docs"))))
        val link = web.linkAt(0, 6)!!
        assertEquals(TerminalLink("https://example.com/docs", 7, 1, 14, 1, osc8 = true), link)
        assertEquals(link, web.linkAt(0, 13))
        assertNull(web.linkAt(0, 5))
        assertNull(web.linkAt(0, 14))
    }

    @Test
    fun `the address of an OSC 8 link is the escape sequence's, not the text under it`() {
        val web = links(row(0, "https://shown.test/", segments = listOf(osc(0, 19, "https://hidden.test/target"))))
        assertEquals("https://hidden.test/target", web.linkAt(0, 3)!!.url)
    }

    @Test
    fun `only http and https addresses are links, as xterm shows them`() {
        for (uri in listOf("mailto:a@b.test", "file:///etc/passwd", "javascript:alert(1)", "ftp://example.com/", "example.com", "")) {
            val web = links(row(0, "click here", segments = listOf(osc(0, 5, uri))))
            assertNull(uri, web.linkAt(0, 2))
        }
        for (uri in listOf("http://example.com/", "https://example.com/", "HTTPS://EXAMPLE.COM/")) {
            val web = links(row(0, "click here", segments = listOf(osc(0, 5, uri))))
            assertEquals(uri, web.linkAt(0, 2)!!.url)
        }
    }

    @Test
    fun `a segment of another kind, without an address, or without cells is not a link`() {
        val web = links(
            row(
                0,
                "abcdefghijklmnop",
                segments = listOf(
                    osc(0, 4, "https://a.test/", SemanticType.PROMPT),
                    osc(4, 8, null),
                    osc(8, 8, "https://b.test/"),
                    osc(12, 16, "https://c.test/"),
                ),
            ),
        )
        assertNull(web.linkAt(0, 1))
        assertNull(web.linkAt(0, 5))
        assertNull(web.linkAt(0, 8))
        assertEquals("https://c.test/", web.linkAt(0, 13)!!.url)
        // The list of the row's OSC 8 links, which a cell lookup cannot show: a segment without cells is not one of them.
        assertEquals(listOf("https://c.test/"), web.oscLinks(1).map { it.url })
    }

    @Test
    fun `a segment that runs past the row ends at its last cell`() {
        val web = links(row(0, "tail of the row", segments = listOf(osc(30, 60, "https://example.com/"))))
        assertEquals(TerminalLink("https://example.com/", 31, 1, cols, 1, osc8 = true), web.linkAt(0, 35))
    }

    @Test
    fun `two OSC 8 links in a row are two links`() {
        val web = links(row(0, "one two", segments = listOf(osc(0, 3, "https://one.test/"), osc(4, 7, "https://two.test/"))))
        assertEquals("https://one.test/", web.linkAt(0, 1)!!.url)
        assertEquals("https://two.test/", web.linkAt(0, 5)!!.url)
        assertNull(web.linkAt(0, 3))
    }

    @Test
    fun `a plain URL is one link over the cells it is written in`() {
        val web = links(row(0, "see https://example.com/x for more"))
        val link = web.linkAt(0, 8)!!
        assertEquals(TerminalLink("https://example.com/x", 5, 1, 25, 1), link)
        assertTrue(!link.osc8)
        assertNull(web.linkAt(0, 3))
        assertNull(web.linkAt(0, 25))
    }

    @Test
    fun `an OSC 8 link keeps the cells it shares with a plain URL, and the plain one is dropped whole`() {
        // The OSC 8 link covers the URL's first eight cells: xterm takes the later provider's link away once it touches one.
        val web = links(row(0, "https://example.com/aaaa", segments = listOf(osc(0, 8, "https://other.test/"))))
        assertEquals("https://other.test/", web.linkAt(0, 2)!!.url)
        assertNull(web.linkAt(0, 12))
        assertNull(web.linkAt(0, 20))
    }

    @Test
    fun `an OSC 8 link and a plain URL that share no cell are both links`() {
        val web = links(row(0, "docs https://example.com/aaaa", segments = listOf(osc(0, 4, "https://other.test/"))))
        assertEquals("https://other.test/", web.linkAt(0, 2)!!.url)
        assertEquals("https://example.com/aaaa", web.linkAt(0, 12)!!.url)
        assertNull(web.linkAt(0, 4))
    }

    @Test
    fun `a plain URL that wraps onto the next row is one link over both`() {
        // The first row is the 40 columns' worth of it, so nothing in the row is a space.
        val web = links(row(0, "https://example.com/a-very-long-path/par", softWrapped = true), row(1, "t/of/it and more"))
        val link = web.linkAt(1, 3)!!
        assertEquals(TerminalLink("https://example.com/a-very-long-path/part/of/it", 1, 1, 7, 2), link)
        assertEquals(link, web.linkAt(0, 5))
        assertNull(web.linkAt(1, 7))
    }

    private fun screen(): TerminalScreenState {
        val rows = 4
        val snapshot = TerminalSnapshot.empty(rows = rows, cols = cols).copy(
            lines = listOf(row(0, "prompt"), row(1, "b https://screen.test/y"), row(2, "cursor line"), row(3, "")),
            scrollback = listOf(row(0, "old"), row(1, "see https://history.test/x here"), row(2, "older")),
        )
        return TerminalScreenState(snapshot)
    }

    @Test
    fun `a viewport row is a row of the screen at the bottom, and the buffer row once the view is scrolled back`() {
        val state = screen()
        assertEquals("https://screen.test/y", state.linkAt(1, 6)!!.url)
        assertNull(state.linkAt(0, 6))
        state.scrollBy(2)
        // Two rows of history on top now: the viewport's first row is the history's second.
        val history = state.linkAt(0, 8)!!
        assertEquals("https://history.test/x", history.url)
        // Its range is of the whole buffer, history first: the history's second row is row 2 there.
        assertEquals(2, history.startY)
        assertEquals(2, history.endY)
        assertNull(state.linkAt(2, 6))
    }

    @Test
    fun `outside the viewport nothing is a link`() {
        val state = screen()
        assertNull(state.linkAt(-1, 6))
        assertNull(state.linkAt(4, 6))
        assertNull(state.linkAt(1, -1))
        assertNull(state.linkAt(1, cols))
    }

    @Test
    fun `a pointer one cell past a margin is not on the cell of the wrapped line that goes on there`() {
        // Counted in the buffer's cells the one after row 0's last is row 1's first, and the one before row 1's first is row 0's last,
        // so a link that wraps over the margin covers both.
        val url = "https://example.com/a-very-long-path/part/of/it"
        val state = TerminalScreenState(
            TerminalSnapshot.empty(rows = 4, cols = cols).copy(
                lines = listOf(row(0, "https://example.com/a-very-long-path/par", softWrapped = true), row(1, "t/of/it and more"), row(2, ""), row(3, "")),
            ),
        )
        assertEquals(url, state.linkAt(0, cols - 1)!!.url)
        assertEquals(url, state.linkAt(1, 0)!!.url)
        assertNull(state.linkAt(0, cols))
        assertNull(state.linkAt(1, -1))
    }
}
