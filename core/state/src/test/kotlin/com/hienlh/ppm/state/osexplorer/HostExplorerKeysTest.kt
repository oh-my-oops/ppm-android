package com.hienlh.ppm.state.osexplorer

import com.hienlh.ppm.protocol.HostFileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `use-entry-selection.ts` (the selection reducer, the cursor, type-ahead) and `use-explorer-keyboard.ts` (which key does what),
 * which `tests/unit/web/use-entry-selection.test.ts` pins on the web: the same cases, then the keys.
 */
class HostExplorerKeysTest {
    private val order = listOf("/a", "/b", "/c", "/d", "/e")
    private fun state(paths: List<String>, anchor: String?) = HostSelection(paths.toSet(), anchor)
    private fun HostSelection.paths() = selection.sorted()
    private fun apply(state: HostSelection, event: HostSelectionEvent) = applyHostSelection(state, order, event)

    @Test
    fun `a plain click selects the row alone and moves the anchor to it`() {
        val next = apply(state(listOf("/a", "/b"), "/a"), HostSelectionEvent.Click("/d"))
        assertEquals(listOf("/d"), next.paths())
        assertEquals("/d", next.anchor)
    }

    @Test
    fun `a control click toggles a row, and moves the anchor so a shift click extends from it`() {
        val added = apply(state(listOf("/a"), "/a"), HostSelectionEvent.Click("/c", ctrl = true))
        assertEquals(listOf("/a", "/c"), added.paths())
        assertEquals("/c", added.anchor)
        assertEquals(listOf("/a"), apply(added, HostSelectionEvent.Click("/c", ctrl = true)).paths())
        assertEquals(listOf("/c", "/d", "/e"), apply(added, HostSelectionEvent.Click("/e", shift = true)).paths())
    }

    @Test
    fun `control with shift toggles the row, as control alone does, and moves the anchor to it`() {
        val next = apply(state(listOf("/a"), "/a"), HostSelectionEvent.Click("/d", shift = true, ctrl = true))
        assertEquals(listOf("/a", "/d"), next.paths())
        assertEquals("/d", next.anchor)
    }

    @Test
    fun `a shift click selects the range from the anchor, either way round, and leaves the anchor`() {
        assertEquals(listOf("/b", "/c", "/d"), apply(state(listOf("/b"), "/b"), HostSelectionEvent.Click("/d", shift = true)).paths())
        assertEquals(listOf("/b", "/c", "/d"), apply(state(listOf("/d"), "/d"), HostSelectionEvent.Click("/b", shift = true)).paths())
        val first = apply(state(listOf("/b"), "/b"), HostSelectionEvent.Click("/e", shift = true))
        assertEquals("/b", first.anchor)
        assertEquals(listOf("/b", "/c"), apply(first, HostSelectionEvent.Click("/c", shift = true)).paths())
    }

    @Test
    fun `a shift click with no anchor selects the row alone and anchors there`() {
        val next = apply(state(emptyList(), null), HostSelectionEvent.Click("/c", shift = true))
        assertEquals(listOf("/c"), next.paths())
        assertEquals("/c", next.anchor)
    }

    @Test
    fun `a shift click on a row that is no longer listed selects nothing`() {
        assertEquals(emptyList<String>(), apply(state(listOf("/b"), "/b"), HostSelectionEvent.Click("/gone", shift = true)).paths())
    }

    @Test
    fun `select all takes every row and keeps the anchor, or else anchors on the first row`() {
        val kept = apply(state(listOf("/b"), "/b"), HostSelectionEvent.SelectAll)
        assertEquals(order, kept.paths())
        assertEquals("/b", kept.anchor)
        assertEquals("/a", apply(state(emptyList(), null), HostSelectionEvent.SelectAll).anchor)
        assertNull(applyHostSelection(HostSelection(), emptyList(), HostSelectionEvent.SelectAll).anchor)
    }

    @Test
    fun `clear empties the selection and the anchor`() {
        val next = apply(state(listOf("/b", "/c"), "/b"), HostSelectionEvent.Clear)
        assertEquals(emptyList<String>(), next.paths())
        assertNull(next.anchor)
    }

    @Test
    fun `the cursor steps a row either way and stops at the ends instead of wrapping`() {
        assertEquals("/c", hostMoveCursor(order, "/b", 1))
        assertEquals("/a", hostMoveCursor(order, "/b", -1))
        assertEquals("/a", hostMoveCursor(order, "/a", -1))
        assertEquals("/e", hostMoveCursor(order, "/e", 1))
        assertEquals("/e", hostMoveCursor(order, "/d", 3))
    }

    @Test
    fun `with no cursor the arrow enters from the end it points away from`() {
        assertEquals("/a", hostMoveCursor(order, null, 1))
        assertEquals("/e", hostMoveCursor(order, null, -1))
        assertEquals("/a", hostMoveCursor(order, "/gone", 1))
        assertEquals("/e", hostMoveCursor(order, "/gone", -1))
        assertNull(hostMoveCursor(emptyList(), "/a", 1))
    }

    private val names = listOf("alpha", "Beta", "beacon", "gamma")

    @Test
    fun `type-ahead matches a prefix whatever its case`() {
        assertEquals(1, hostTypeAheadIndex(names, "be", -1))
        assertEquals(1, hostTypeAheadIndex(names, "BE", -1))
    }

    @Test
    fun `type-ahead searches after the cursor row, wraps to it, and cycles through the matches`() {
        assertEquals(2, hostTypeAheadIndex(names, "b", 1))
        assertEquals(1, hostTypeAheadIndex(names, "b", 2))
        assertEquals(0, hostTypeAheadIndex(names, "a", 3))
        assertEquals(0, hostTypeAheadIndex(names, "a", 0))
    }

    @Test
    fun `type-ahead answers -1 for no match and for nothing typed`() {
        assertEquals(-1, hostTypeAheadIndex(names, "zz", -1))
        assertEquals(-1, hostTypeAheadIndex(names, "", -1))
        assertEquals(-1, hostTypeAheadIndex(emptyList(), "a", -1))
    }

    @Test
    fun `typed letters join while each comes within 800 ms of the one before and start again after`() {
        val typed = HostTypeAhead()
        assertEquals(1, typed.feed('b', 10_000, names, -1))
        assertEquals("be: Beta", 1, typed.feed('e', 10_800, names, 1))
        assertEquals("bea: beacon, each letter within 800 ms of the one before", 2, typed.feed('a', 11_600, names, 1))
        // 800 ms is still inside the window, 801 is not: "b" then "e" is "be", but "b" then (801 ms) "e" is "e" alone.
        val late = HostTypeAhead()
        late.feed('b', 20_000, names, -1)
        assertEquals(-1, late.feed('e', 20_801, names, -1))
        val inside = HostTypeAhead()
        inside.feed('b', 20_000, names, -1)
        assertEquals(1, inside.feed('e', 20_800, names, -1))
    }

    @Test
    fun `a repeated single letter starts after the cursor, a longer buffer starts from the top`() {
        val repeated = HostTypeAhead()
        assertEquals(2, repeated.feed('b', 1_000, names, 1))
        // The cursor is now on beacon; typing b again after the window walks on, round to Beta.
        assertEquals(1, repeated.feed('b', 5_000, names, 2))
        val longer = HostTypeAhead()
        assertEquals(2, longer.feed('b', 1_000, names, 1))
        assertEquals("be starts at the top (Beta), not after the cursor on Beta (beacon)", 1, longer.feed('e', 1_100, names, 1))
    }

    private fun dir(name: String) = HostFileEntry(name, "/r/$name", "directory", "directory")
    private fun file(name: String) = HostFileEntry(name, "/r/$name", "file", "file", size = 1)
    private val rows = listOf(dir("alpha"), file("Beta"), file("beacon"), file("gamma"), file("omega"))
    private fun press(key: HostKey, char: Char? = null, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false) =
        HostKeyPress(key, char, ctrl, shift, alt)

    private fun command(
        press: HostKeyPress, selection: Set<String> = emptySet(), anchor: String? = null, columns: Int? = null,
        typeAhead: HostTypeAhead = HostTypeAhead(), now: Long = 100_000,
    ) = hostKeyCommand(press, rows, selection, anchor, columns, typeAhead, now)

    private fun p(name: String) = "/r/$name"

    @Test
    fun `Backspace goes back, not up`() {
        assertEquals(HostKeyCommand.Back, command(press(HostKey.BACKSPACE)))
    }

    @Test
    fun `Alt with an arrow goes back, forward or up, as the web's Alt and the arrow do and this app's Alt Up`() {
        assertEquals(HostKeyCommand.Back, command(press(HostKey.LEFT, alt = true)))
        assertEquals(HostKeyCommand.Forward, command(press(HostKey.RIGHT, alt = true)))
        assertEquals(HostKeyCommand.Up, command(press(HostKey.UP, alt = true)))
        assertEquals(HostKeyCommand.Back, command(press(HostKey.LEFT, alt = true), columns = 3))
    }

    @Test
    fun `Enter opens exactly one active entry, the selected one or else the cursor row`() {
        assertEquals(HostKeyCommand.Open(rows[1]), command(press(HostKey.ENTER), selection = setOf(p("Beta")), anchor = p("Beta")))
        assertEquals(HostKeyCommand.Open(rows[2]), command(press(HostKey.ENTER), anchor = p("beacon")))
    }

    @Test
    fun `Enter with two selected, or none, opens nothing but is still the explorer's key`() {
        assertEquals(HostKeyCommand.Handled, command(press(HostKey.ENTER), selection = setOf(p("Beta"), p("beacon")), anchor = p("Beta")))
        assertEquals(HostKeyCommand.Handled, command(press(HostKey.ENTER)))
    }

    @Test
    fun `F2 renames exactly one active entry`() {
        assertEquals(HostKeyCommand.Rename(rows[3]), command(press(HostKey.F2), selection = setOf(p("gamma"))))
        assertEquals(HostKeyCommand.Rename(rows[4]), command(press(HostKey.F2), anchor = p("omega")))
        assertEquals(HostKeyCommand.Handled, command(press(HostKey.F2), selection = setOf(p("gamma"), p("omega"))))
        assertEquals(HostKeyCommand.Handled, command(press(HostKey.F2)))
    }

    @Test
    fun `Delete trashes every active entry in the listing's order and Shift Delete deletes them for good`() {
        val both = setOf(p("omega"), p("Beta"))
        assertEquals(HostKeyCommand.Trash(listOf(rows[1], rows[4])), command(press(HostKey.DELETE), selection = both))
        assertEquals(HostKeyCommand.DeletePermanently(listOf(rows[1], rows[4])), command(press(HostKey.DELETE, shift = true), selection = both))
    }

    @Test
    fun `Delete acts on the cursor row when nothing is selected, and on nothing when there is no cursor`() {
        assertEquals(HostKeyCommand.Trash(listOf(rows[2])), command(press(HostKey.DELETE), anchor = p("beacon")))
        assertEquals(HostKeyCommand.DeletePermanently(listOf(rows[2])), command(press(HostKey.DELETE, shift = true), anchor = p("beacon")))
        assertEquals(HostKeyCommand.Handled, command(press(HostKey.DELETE)))
        assertEquals(HostKeyCommand.Handled, command(press(HostKey.DELETE, shift = true)))
    }

    @Test
    fun `Control C and Control X take every active entry, or the cursor row`() {
        assertEquals(HostKeyCommand.Copy(listOf(rows[0], rows[3])), command(press(HostKey.C, ctrl = true), selection = setOf(p("gamma"), p("alpha"))))
        assertEquals(HostKeyCommand.Cut(listOf(rows[0], rows[3])), command(press(HostKey.X, ctrl = true), selection = setOf(p("gamma"), p("alpha"))))
        assertEquals(HostKeyCommand.Copy(listOf(rows[4])), command(press(HostKey.C, ctrl = true), anchor = p("omega")))
        assertEquals(HostKeyCommand.Cut(listOf(rows[4])), command(press(HostKey.X, ctrl = true), anchor = p("omega")))
        assertEquals(HostKeyCommand.Cut(emptyList()), command(press(HostKey.X, ctrl = true)))
    }

    @Test
    fun `Control A and Control V select every row and paste, and every other Control key is left to the app`() {
        assertEquals(HostKeyCommand.SelectAll, command(press(HostKey.A, 'a', ctrl = true)))
        assertEquals(HostKeyCommand.Paste, command(press(HostKey.V, 'v', ctrl = true)))
        assertNull(command(press(HostKey.OTHER, 'z', ctrl = true)))
        assertNull(command(press(HostKey.DELETE, ctrl = true)))
        assertNull(command(press(HostKey.UP, ctrl = true)))
    }

    @Test
    fun `Escape clears the selection`() {
        assertEquals(HostKeyCommand.ClearSelection, command(press(HostKey.ESCAPE), selection = setOf(p("Beta"))))
    }

    @Test
    fun `an arrow with no cursor enters at the first row going down and at the last going up`() {
        assertEquals(HostKeyCommand.Select(p("alpha")), command(press(HostKey.DOWN)))
        assertEquals(HostKeyCommand.Select(p("omega")), command(press(HostKey.UP)))
    }

    @Test
    fun `an arrow steps one row from the cursor and stops at the ends`() {
        assertEquals(HostKeyCommand.Select(p("beacon")), command(press(HostKey.DOWN), anchor = p("Beta")))
        assertEquals(HostKeyCommand.Select(p("alpha")), command(press(HostKey.UP), anchor = p("Beta")))
        assertEquals(HostKeyCommand.Select(p("omega")), command(press(HostKey.DOWN), anchor = p("omega")))
        assertEquals(HostKeyCommand.Select(p("alpha")), command(press(HostKey.UP), anchor = p("alpha")))
    }

    @Test
    fun `Shift with an arrow asks for the range`() {
        assertEquals(HostKeyCommand.Select(p("beacon"), range = true), command(press(HostKey.DOWN, shift = true), anchor = p("Beta")))
    }

    @Test
    fun `in the list Left and Right are not the explorer's, which leaves them to whoever else wants them`() {
        assertNull(command(press(HostKey.LEFT), anchor = p("Beta")))
        assertNull(command(press(HostKey.RIGHT), anchor = p("Beta")))
    }

    @Test
    fun `in Icons view Up and Down step a row of columns and Left and Right one entry`() {
        assertEquals(HostKeyCommand.Select(p("omega")), command(press(HostKey.DOWN), anchor = p("Beta"), columns = 3))
        assertEquals(HostKeyCommand.Select(p("gamma")), command(press(HostKey.DOWN), anchor = p("alpha"), columns = 3))
        assertEquals(HostKeyCommand.Select(p("alpha")), command(press(HostKey.UP), anchor = p("gamma"), columns = 3))
        assertEquals(HostKeyCommand.Select(p("alpha")), command(press(HostKey.UP), anchor = p("Beta"), columns = 3))
        assertEquals(HostKeyCommand.Select(p("beacon")), command(press(HostKey.RIGHT), anchor = p("Beta"), columns = 3))
        assertEquals(HostKeyCommand.Select(p("alpha")), command(press(HostKey.LEFT), anchor = p("Beta"), columns = 3))
        assertEquals(HostKeyCommand.Select(p("gamma"), range = true), command(press(HostKey.DOWN, shift = true), anchor = p("alpha"), columns = 3))
    }

    @Test
    fun `in Icons view with a single column Up and Down are a row too`() {
        assertEquals(HostKeyCommand.Select(p("beacon")), command(press(HostKey.DOWN), anchor = p("Beta"), columns = 1))
    }

    @Test
    fun `Home and End go to the first and the last row, and with Shift extend to them`() {
        assertEquals(HostKeyCommand.Select(p("alpha")), command(press(HostKey.HOME), anchor = p("gamma")))
        assertEquals(HostKeyCommand.Select(p("omega")), command(press(HostKey.END), anchor = p("gamma")))
        assertEquals(HostKeyCommand.Select(p("omega"), range = true), command(press(HostKey.END, shift = true), anchor = p("gamma")))
    }

    @Test
    fun `a typed letter jumps to the next name that starts with it, after the cursor row and round`() {
        val typed = HostTypeAhead()
        assertEquals(HostKeyCommand.Select(p("Beta")), command(press(HostKey.OTHER, 'b'), typeAhead = typed, now = 1_000))
        assertEquals(HostKeyCommand.Select(p("beacon")), command(press(HostKey.OTHER, 'b'), anchor = p("Beta"), typeAhead = typed, now = 9_000))
        assertEquals(HostKeyCommand.Select(p("Beta")), command(press(HostKey.OTHER, 'b'), anchor = p("beacon"), typeAhead = typed, now = 20_000))
    }

    @Test
    fun `letters typed together look for the whole word from the top, even with the cursor past it`() {
        val typed = HostTypeAhead()
        command(press(HostKey.OTHER, 'b'), typeAhead = typed, now = 1_000)
        // "be" with the cursor on Beta: from the top that is Beta, after the cursor it would be beacon.
        assertEquals(HostKeyCommand.Select(p("Beta")), command(press(HostKey.OTHER, 'e'), anchor = p("Beta"), typeAhead = typed, now = 1_300))
        assertEquals(HostKeyCommand.Select(p("beacon")), command(press(HostKey.OTHER, 'a'), anchor = p("Beta"), typeAhead = typed, now = 1_600))
    }

    @Test
    fun `a letter that matches nothing is still taken, and a letter key with Alt or without a character is not`() {
        assertEquals(HostKeyCommand.Handled, command(press(HostKey.OTHER, 'q')))
        assertNull(command(press(HostKey.OTHER, 'b', alt = true)))
        assertNull(command(press(HostKey.OTHER)))
    }

    @Test
    fun `the letters that are also shortcuts type ahead when Control is not held`() {
        assertEquals(HostKeyCommand.Select(p("alpha")), command(press(HostKey.A, 'a')))
    }
}
