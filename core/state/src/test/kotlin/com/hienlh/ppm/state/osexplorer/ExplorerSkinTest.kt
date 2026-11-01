package com.hienlh.ppm.state.osexplorer

import org.junit.Assert.assertEquals
import org.junit.Test

/** use-explorer-skin.ts: the pick wins, and `auto` follows the server's OS the way "no pick" does. */
class ExplorerSkinTest {
    @Test
    fun `auto follows the server, Windows on win32 and macOS on any other`() {
        assertEquals(true, explorerSkinIsWindows("auto", "win32"))
        assertEquals(false, explorerSkinIsWindows("auto", "darwin"))
        assertEquals(false, explorerSkinIsWindows("auto", "linux"))
    }

    @Test
    fun `no pick follows the server as auto does`() {
        assertEquals(true, explorerSkinIsWindows(null, "win32"))
        assertEquals(false, explorerSkinIsWindows(null, "linux"))
    }

    @Test
    fun `a server whose OS is not known yet is not Windows`() {
        assertEquals(false, explorerSkinIsWindows("auto", null))
        assertEquals(false, explorerSkinIsWindows(null, null))
    }

    @Test
    fun `a skin picked in Settings wins over the server`() {
        assertEquals(true, explorerSkinIsWindows("windows", "linux"))
        assertEquals(true, explorerSkinIsWindows("windows", null))
        assertEquals(false, explorerSkinIsWindows("macos", "win32"))
    }
}
