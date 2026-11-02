package com.hienlh.ppm.app.ui.osexplorer

import com.hienlh.ppm.state.osexplorer.ExplorerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A window opened on `~` shows the folder the host answered with, `/home/me`, and that is where what is let go on the space under its
 * rows goes: `~` is no path a move can be made into.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h700dp")
internal class HostExplorerTildeDropTest : HostEntryDndTestBase() {
    override val startPath = "~"

    @Test
    fun `the List view's blank is the folder the host answered with`() {
        show(ExplorerView.LIST)
        assertEquals("~", session.state.value.path)
        begin(entries("/elsewhere/x.txt"))
        hover(below())
        assertTrue(letGo(below()))
        assertEquals(listOf("move /elsewhere/x.txt -> /home/me/x.txt"), transfers.toList())
    }

    @Test
    fun `so is the Icons view's`() {
        show(ExplorerView.ICONS)
        assertEquals("~", session.state.value.path)
        begin(entries("/elsewhere/x.txt"))
        hover(below())
        assertTrue(letGo(below()))
        assertEquals(listOf("move /elsewhere/x.txt -> /home/me/x.txt"), transfers.toList())
    }
}
