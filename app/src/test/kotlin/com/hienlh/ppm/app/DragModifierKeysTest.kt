package com.hienlh.ppm.app

import android.app.Application
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Whether Ctrl or Alt is down, as a drop reads it: the web copies instead of moving when either is, off the drop event, and an
 * Android `DragEvent` carries no modifier state. So it is kept from the key events the window's shortcuts see, each one, whether a
 * shortcut takes it or not, and every one carries the meta state as it stands.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DragModifierKeysTest {
    private lateinit var shortcuts: KeyboardShortcuts

    @Before
    fun setUp() {
        DragModifierKeys.update(ctrl = false, alt = false)
        shortcuts = KeyboardShortcuts(AppModel(ApplicationProvider.getApplicationContext<Application>()))
    }

    @After
    fun reset() = DragModifierKeys.update(ctrl = false, alt = false)

    private fun down(code: Int, meta: Int = 0) = shortcuts.dispatch(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta))

    private fun up(code: Int, meta: Int = 0) = shortcuts.dispatch(KeyEvent(0, 0, KeyEvent.ACTION_UP, code, 0, meta))

    @Test
    fun `with no key held it is not a copy`() {
        assertFalse(DragModifierKeys.copy)
    }

    @Test
    fun `Ctrl going down makes it a copy, and going up ends it`() {
        down(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_ON)
        assertTrue(DragModifierKeys.copy)
        up(KeyEvent.KEYCODE_CTRL_LEFT)
        assertFalse(DragModifierKeys.copy)
    }

    @Test
    fun `Alt going down makes it a copy, and going up ends it`() {
        down(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.META_ALT_ON)
        assertTrue(DragModifierKeys.copy)
        up(KeyEvent.KEYCODE_ALT_LEFT)
        assertFalse(DragModifierKeys.copy)
    }

    @Test
    fun `either key is enough, and it ends only when both are let go`() {
        down(KeyEvent.KEYCODE_CTRL_RIGHT, KeyEvent.META_CTRL_ON)
        down(KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        assertTrue(DragModifierKeys.copy)
        up(KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.META_CTRL_ON)
        assertTrue("Ctrl is still down", DragModifierKeys.copy)
        up(KeyEvent.KEYCODE_CTRL_RIGHT)
        assertFalse(DragModifierKeys.copy)
    }

    @Test
    fun `Shift and Meta are not a copy`() {
        down(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_ON)
        assertFalse(DragModifierKeys.copy)
        down(KeyEvent.KEYCODE_META_LEFT, KeyEvent.META_META_ON or KeyEvent.META_SHIFT_ON)
        assertFalse(DragModifierKeys.copy)
    }

    @Test
    fun `a key no shortcut takes is read as well, and so is one with a modifier on it`() {
        down(KeyEvent.KEYCODE_Z, KeyEvent.META_ALT_ON)
        assertTrue(DragModifierKeys.copy)
        down(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)
        assertTrue(DragModifierKeys.copy)
    }

    @Test
    fun `a release that went to another window is corrected by the next key`() {
        down(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_ON)
        assertTrue(DragModifierKeys.copy)
        down(KeyEvent.KEYCODE_A)
        assertFalse(DragModifierKeys.copy)
    }

    @Test
    fun `the press that starts a drag sets it too`() {
        DragModifierKeys.update(ctrl = true, alt = false)
        assertTrue(DragModifierKeys.copy)
        DragModifierKeys.update(ctrl = false, alt = true)
        assertTrue(DragModifierKeys.copy)
        DragModifierKeys.update(ctrl = false, alt = false)
        assertFalse(DragModifierKeys.copy)
    }
}
