package com.hienlh.ppm.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which window takes the keys while a menu or a picker is open. A popup that is not focusable leaves the keyboard with the
 * window behind it: a Tab or an arrow key moves the focus there and not over the rows it opened, and a typed letter lands in
 * the text box under it. The rows of a popover cannot be pressed through a real window in a unit test (Compose reads a
 * string resource for its title), so this pins what Compose makes the window's flags from: every popup the app opens is one
 * of these two, and both are focusable and close on Back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PopupWindowFocusTest {
    @Test
    fun `a menu's window takes the keyboard and closes on Back`() {
        assertTrue(MenuProperties.focusable)
        assertTrue(MenuProperties.dismissOnBackPress)
    }

    @Test
    fun `a pass-through window, the composer's three pickers' window, takes the keyboard and closes on Back as a menu's does`() {
        assertTrue(PassThroughProperties.focusable)
        assertTrue(PassThroughProperties.dismissOnBackPress)
    }
}
