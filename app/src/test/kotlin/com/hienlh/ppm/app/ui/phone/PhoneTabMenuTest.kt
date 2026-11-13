package com.hienlh.ppm.app.ui.phone

import com.hienlh.ppm.state.workspace.WorkspaceTab
import org.junit.Assert.assertEquals
import org.junit.Test

/** The phone tab menu's pin and close entries, offered as `mobile-nav.tsx` offers them. */
class PhoneTabMenuTest {
    private fun tab(id: String, pinned: Boolean = false, closable: Boolean = true) =
        WorkspaceTab(id, "editor", id, closable = closable, pinned = pinned)

    private fun ids(anchor: WorkspaceTab, tabs: List<WorkspaceTab>) = phoneTabMenuItems(anchor, tabs).map { it.id }

    @Test
    fun `an unpinned tab offers Pin, Close and every bulk close that would close something`() {
        val a = tab("a")
        val tabs = listOf(tab("p", pinned = true), a, tab("b"))
        assertEquals(listOf("pin", "close", "close-others", "close-right", "close-saved", "close-all"), ids(a, tabs))
    }

    @Test
    fun `a pinned tab offers Unpin, and bulk closes count only the unpinned tabs`() {
        val p = tab("p", pinned = true)
        val q = tab("q", pinned = true)
        // Nothing but pins beside it: no bulk close would close anything.
        assertEquals(listOf("unpin", "close"), ids(p, listOf(p, q)))
        // The last tab has nothing to its right.
        val last = tab("z")
        assertEquals(listOf("pin", "close", "close-others", "close-saved", "close-all"), ids(last, listOf(tab("y"), last)))
        // Only a pin beside it: Close Others has nothing to take, Close All still takes the tab itself.
        assertEquals(listOf("pin", "close", "close-saved", "close-all"), ids(last, listOf(p, last)))
    }

    @Test
    fun `a tab that cannot close offers no Close`() {
        val fixed = tab("f", closable = false)
        assertEquals(listOf("pin"), ids(fixed, listOf(fixed)))
    }
}
