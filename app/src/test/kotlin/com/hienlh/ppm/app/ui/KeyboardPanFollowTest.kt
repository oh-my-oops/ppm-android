package com.hienlh.ppm.app.ui

import android.app.Activity
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.TextInputFocus
import com.hienlh.ppm.app.withComposeActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the keyboard does to a page whose focus is a terminal: its cursor's row has to be in view, wherever the cursor goes, and the key row under
 * it is in view as well when both fit. Chrome does the first for xterm's textarea, which stays on the cursor cell; the second is for the app's
 * key row, which the web leaves behind the keyboard.
 *
 * The page is [PannedPage] with the insets handed in, [PAGE] px high at one pixel to the dp (Robolectric's own root is 414, which is not a number
 * to count with), a terminal that fills it and a row of keys at its bottom, and [TerminalCaret] and [TextInputFocus] wired as `TerminalBody`
 * wires them. The cursor's cell is put by hand, as the engine reports it; the page's own offset is read from a marker at its top-left corner.
 *
 * With the keyboard [KEYBOARD] px high the view over the page is 0..250 and the page goes up by 150 at most, which puts its bottom on the keyboard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardPanFollowTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    private val owner = Any()
    private val caret = TerminalCaret()
    private var keyboard by mutableIntStateOf(0)
    private var terminalTop by mutableIntStateOf(0)
    private var keysShown by mutableStateOf(false)
    private var keysLift by mutableIntStateOf(0)
    private val sheet = Any()
    private var placedInOtherRoot = false
    private lateinit var focusManager: FocusManager
    private lateinit var activity: Activity

    @After
    fun release() {
        TextInputFocus.releaseNative(owner)
        TextInputFocus.releaseNative(sheet)
    }

    /**
     * The keys sit [KEYS] px tall at the page's bottom, 360..400 less [keysLift], from the start or from when [keysShown] is set; the terminal is all
     * of the page unless it is moved down, so a cursor row is at the y it is given, or it is in a composition of its own ([inOtherRoot]), as a sheet's is.
     */
    private fun show(withKeys: Boolean = true, inOtherRoot: Boolean = false, navigationBar: Int = 0, statusBar: Int = 0) {
        keysShown = withKeys
        compose.setContent {
            focusManager = LocalFocusManager.current
            activity = LocalContext.current as Activity
            Box(Modifier.size(320.dp, PAGE.dp)) {
                PannedPage(keyboard = keyboard, opening = keyboard, navigationBar = navigationBar, statusBar = statusBar) {
                    Box(Modifier.size(1.dp).testTag("marker"))
                    if (!inOtherRoot) {
                        Box(Modifier.offset { IntOffset(0, terminalTop) }.fillMaxSize().onGloballyPositioned(caret::terminalPlaced))
                    }
                    if (keysShown) {
                        Box(
                            Modifier.align(Alignment.BottomStart).offset { IntOffset(0, -keysLift) }.size(100.dp, KEYS.dp)
                                .onGloballyPositioned(caret::keysPlaced),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        if (inOtherRoot) {
            compose.runOnUiThread {
                val view = ComposeView(activity)
                view.setContent {
                    Box(Modifier.size(100.dp, 300.dp).onGloballyPositioned { caret.terminalPlaced(it); placedInOtherRoot = true })
                }
                activity.addContentView(view, ViewGroup.LayoutParams(100, 300))
            }
            compose.waitForIdle()
        }
    }

    private fun takeFocus(withKeys: Boolean = true) {
        compose.runOnIdle {
            TextInputFocus.takeNative(owner, focusManager, keep = if (withKeys) caret::keysIn else null, bounds = caret::cursorIn)
        }
        compose.waitForIdle()
    }

    private fun cursorAt(top: Int) {
        compose.runOnIdle { caret.cursorMoved(Rect(0f, top.toFloat(), 8f, top + ROW)) }
        compose.waitForIdle()
    }

    private fun openKeyboard(height: Int = KEYBOARD) {
        compose.runOnIdle { keyboard = height }
        compose.waitForIdle()
    }

    /** How far the page is panned up. */
    private fun pan(): Int = -compose.onNodeWithTag("marker").fetchSemanticsNode().positionInRoot.y.toInt()

    @Test
    fun `a cursor above the keyboard leaves the page where it is`() {
        show(withKeys = false)
        cursorAt(100)
        takeFocus(withKeys = false)
        openKeyboard()
        assertEquals(0, pan())
    }

    @Test
    fun `a cursor under the keyboard is centred in what the keyboard leaves`() {
        show(withKeys = false)
        cursorAt(260)
        takeFocus(withKeys = false)
        openKeyboard()
        // The row 260..280 has its middle, 270, put at the view's, 125: a pan of 145.
        assertEquals(145, pan())
    }

    @Test
    fun `a cursor at the bottom of the page pans it no further than puts its bottom on the keyboard`() {
        show(withKeys = false)
        cursorAt(380)
        takeFocus(withKeys = false)
        openKeyboard()
        // Centred it would be 265.
        assertEquals(150, pan())
    }

    @Test
    fun `the navigation bar is under the keyboard too, so the page stops short of it`() {
        show(withKeys = false, navigationBar = 30)
        cursorAt(380)
        takeFocus(withKeys = false)
        openKeyboard()
        assertEquals(120, pan())
    }

    @Test
    fun `what the status bar covers is not part of the view`() {
        show(withKeys = false, statusBar = 40)
        cursorAt(260)
        takeFocus(withKeys = false)
        openKeyboard()
        // The view is 40..250 now, and 260..280 has its middle put at 145: a pan of 125.
        assertEquals(125, pan())
    }

    @Test
    fun `the keys are not worth a pan that leaves them under the keyboard`() {
        show(navigationBar = 30)
        cursorAt(200)
        takeFocus()
        openKeyboard()
        // The page goes 120 at most, the navigation bar's 30 short of the keyboard: 200..400 would be centred at 175 and the keys, at 360..400, would
        // still be over the keyboard's edge at 250..280, so the cursor, in view where the page is, decides.
        assertEquals(0, pan())
    }

    @Test
    fun `the key row is in view with a cursor that is under the keyboard when both fit`() {
        show()
        cursorAt(240)
        takeFocus()
        openKeyboard()
        // The cursor row alone would be centred at 125. With the keys, 240..400 is 160 px of the 250 the view has, and the page goes as far as it
        // can, 150: the keys at 360..400 are over the keyboard at 210..250.
        assertEquals(150, pan())
    }

    @Test
    fun `the cursor row wins when it and the keys do not fit above the keyboard together`() {
        show()
        cursorAt(100)
        takeFocus()
        openKeyboard()
        // 100..400 is 300 px of 250: the cursor is in view where the page is and the keys are left under the keyboard.
        assertEquals(0, pan())
        cursorAt(140)
        assertEquals(0, pan())
        // 160..400 is 240 px: both fit, and the page goes up for the keys.
        cursorAt(160)
        assertEquals(150, pan())
    }

    @Test
    fun `the page follows a cursor that goes down under the keyboard as lines are typed`() {
        show()
        cursorAt(100)
        takeFocus()
        openKeyboard()
        assertEquals(0, pan())
        cursorAt(300)
        assertEquals(150, pan())
    }

    @Test
    fun `the page follows a cursor that goes under the keyboard when there are no keys to show`() {
        show(withKeys = false)
        cursorAt(100)
        takeFocus(withKeys = false)
        openKeyboard()
        assertEquals(0, pan())
        cursorAt(260)
        assertEquals(145, pan())
    }

    @Test
    fun `a cursor the page has panned out of sight brings the page back as it goes up`() {
        show(withKeys = false)
        cursorAt(380)
        takeFocus(withKeys = false)
        openKeyboard()
        assertEquals(150, pan())
        // At the pan of 150 the view shows 150..400 of the page, and the cursor is going to 50.
        cursorAt(50)
        assertEquals(0, pan())
    }

    @Test
    fun `the page follows the terminal when it moves under a cursor that has not moved in it`() {
        show(withKeys = false)
        cursorAt(50)
        takeFocus(withKeys = false)
        openKeyboard()
        assertEquals(0, pan())
        // The dock's splitter dragged: the cell is the same in the terminal's own pixels and 200 px lower in the page, 250..270.
        compose.runOnIdle { terminalTop = 200 }
        compose.waitForIdle()
        assertEquals(135, pan())
    }

    @Test
    fun `a cursor that is in view brings the keys up with it when both fit`() {
        show()
        cursorAt(210)
        takeFocus()
        openKeyboard()
        // 210..400 is 190 px of 250: the cursor is in view and the keys are not, and the page goes up for them.
        assertEquals(150, pan())
    }

    @Test
    fun `the page follows the keys when they are laid out after the keyboard is up`() {
        show(withKeys = false)
        cursorAt(240)
        takeFocus()
        openKeyboard()
        // Nothing to show with the cursor but the cursor: 240..260 centred in 0..250.
        assertEquals(125, pan())
        compose.runOnIdle { keysShown = true }
        compose.waitForIdle()
        assertEquals(150, pan())
    }

    @Test
    fun `the page follows the keys when they move and the cursor does not`() {
        show()
        cursorAt(100)
        takeFocus()
        openKeyboard()
        // 100..400 is 300 px of the 250 the view has: the cursor, in view, is what decides.
        assertEquals(0, pan())
        compose.runOnIdle { keysLift = 100 }
        compose.waitForIdle()
        // The keys are at 260..300 now, half under the keyboard's edge at 250, and 100..300 fits the view.
        assertEquals(75, pan())
    }

    @Test
    fun `the page comes back when the focus goes to a terminal in another window`() {
        show()
        cursorAt(300)
        takeFocus()
        openKeyboard()
        assertEquals(150, pan())
        compose.runOnIdle { TextInputFocus.takeNative(sheet, focusManager) { null } }
        compose.waitForIdle()
        assertEquals(0, pan())
    }

    @Test
    fun `a keyboard that is not up pans nothing, and the pan goes with it`() {
        show()
        cursorAt(300)
        takeFocus()
        assertEquals(0, pan())
        openKeyboard()
        assertEquals(150, pan())
        openKeyboard(0)
        assertEquals(0, pan())
    }

    @Test
    fun `a terminal in another window leaves the page where it was`() {
        // A sheet's window has a root of its own: a place in it is not a place in this page, and asking for one is an error.
        show(inOtherRoot = true)
        assertTrue("the terminal was laid out in the other composition", placedInOtherRoot)
        cursorAt(300)
        takeFocus()
        openKeyboard()
        assertEquals(0, pan())
    }

    @Test
    fun `a cursor that is reported again after the page has panned is not a reason to pan again`() {
        show(withKeys = false)
        cursorAt(260)
        takeFocus(withKeys = false)
        openKeyboard()
        assertEquals(145, pan())
        // At the pan of 145 the view shows 145..395: the cursor, a row lower, is in it.
        cursorAt(261)
        assertEquals(145, pan())
    }

    private companion object {
        const val PAGE = 400
        const val ROW = 20f
        const val KEYS = 40
        const val KEYBOARD = 150
    }
}
