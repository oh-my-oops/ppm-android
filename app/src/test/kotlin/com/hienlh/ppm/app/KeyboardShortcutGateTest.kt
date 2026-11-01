package com.hienlh.ppm.app

import android.app.Application
import android.view.KeyEvent
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which keys the shortcuts take while a text field has the focus, as the web's handler does
 * (`use-global-keybindings.ts`): with the focus in an input, a textarea or an editable element it
 * runs only the terminal panel toggle and the chat jumps, however the element got the focus. Double
 * Shift is handled before that gate and opens the palette from a field too.
 *
 * So F1 does nothing after the app starts when the sidebar's Search (focused on show) or a new
 * chat's box (focused 100 ms after it appears) holds the focus, as on the web: Double Shift is the
 * way in, or a tap on something that takes the focus away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardShortcutGateTest {
    private lateinit var model: AppModel
    private lateinit var shortcuts: KeyboardShortcuts
    private val field = TextFieldState()
    private val terminal = Any()

    @Before
    fun setUp() {
        model = AppModel(ApplicationProvider.getApplicationContext<Application>())
        shortcuts = KeyboardShortcuts(model)
    }

    @After
    fun releaseAll() {
        TextInputFocus.release(field)
        TextInputFocus.releaseNative(terminal)
    }

    private fun down(code: Int, meta: Int = 0) = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta)

    private fun f1(nativeEditor: Boolean = false) = shortcuts.dispatch(down(KeyEvent.KEYCODE_F1), nativeEditor)

    private fun ctrlB() = shortcuts.dispatch(down(KeyEvent.KEYCODE_B, KeyEvent.META_CTRL_ON))

    /** A Shift going down or up at [at] ms: [KeyEvent]'s times are the detector's clock. */
    private fun shift(action: Int, at: Long) =
        KeyEvent(at, at, action, KeyEvent.KEYCODE_SHIFT_LEFT, 0, if (action == KeyEvent.ACTION_DOWN) KeyEvent.META_SHIFT_ON else 0)

    private fun tapShift(at: Long) {
        shortcuts.dispatch(shift(KeyEvent.ACTION_DOWN, at))
        shortcuts.dispatch(shift(KeyEvent.ACTION_UP, at + 50))
    }

    @Test
    fun `F1 opens the palette with no field focused`() {
        assertTrue(f1())
        assertEquals("", model.paletteQuery)
    }

    @Test
    fun `F1 does nothing over a field the app focused on its own, as the sidebar's Search and a new chat's box are`() {
        TextInputFocus.take(field)
        assertFalse(f1())
        assertNull(model.paletteQuery)
    }

    @Test
    fun `F1 does nothing over a field the user pressed in`() {
        TextInputFocus.tapStarts()
        TextInputFocus.pressed(field)
        TextInputFocus.take(field)
        assertFalse(f1())
        assertNull(model.paletteQuery)
    }

    @Test
    fun `F1 does nothing with the keyboard up for the field either`() {
        TextInputFocus.take(field)
        assertFalse(f1(nativeEditor = true))
        assertNull(model.paletteQuery)
    }

    @Test
    fun `F1 does nothing in an editor the focus tracking does not know`() {
        assertFalse(f1(nativeEditor = true))
        assertNull(model.paletteQuery)
    }

    @Test
    fun `the terminal gets F1`() {
        TextInputFocus.takeNative(terminal, FocusWindow()) { null }
        assertFalse(f1())
        assertNull(model.paletteQuery)
    }

    @Test
    fun `F1 opens the palette again once the field has let go of the focus`() {
        TextInputFocus.take(field)
        assertFalse(f1())
        TextInputFocus.release(field)
        assertTrue(f1())
        assertEquals("", model.paletteQuery)
    }

    @Test
    fun `a chord that is not for a field runs with none focused and not in one`() {
        assertFalse(model.sidebarCollapsed)
        TextInputFocus.take(field)
        assertFalse(ctrlB())
        assertFalse(model.sidebarCollapsed)

        TextInputFocus.release(field)
        assertTrue(ctrlB())
        assertTrue(model.sidebarCollapsed)
    }

    @Test
    fun `the terminal panel toggle runs in a field`() {
        TextInputFocus.take(field)
        assertTrue(shortcuts.dispatch(down(KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.META_CTRL_ON)))
    }

    @Test
    fun `the chat jumps run in a field`() {
        TextInputFocus.take(field)
        assertTrue(shortcuts.dispatch(down(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.META_ALT_ON)))
        assertTrue(shortcuts.dispatch(down(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.META_ALT_ON)))
    }

    @Test
    fun `Double Shift opens the palette from a field`() {
        TextInputFocus.take(field)
        tapShift(10_000)
        assertNull(model.paletteQuery)
        tapShift(10_150)
        assertEquals("", model.paletteQuery)
    }

    @Test
    fun `two Shift taps further apart than 400 ms are not a double tap`() {
        TextInputFocus.take(field)
        tapShift(10_000)
        tapShift(10_500)
        assertNull(model.paletteQuery)
    }

    @Test
    fun `Double Shift opens the palette from the terminal's editor too`() {
        TextInputFocus.takeNative(terminal, FocusWindow()) { null }
        tapShift(10_000)
        tapShift(10_150)
        assertEquals("", model.paletteQuery)
    }

    private class FocusWindow : FocusManager {
        override fun clearFocus(force: Boolean) = Unit

        override fun moveFocus(focusDirection: FocusDirection) = false
    }
}
