package com.hienlh.ppm.app

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A tap on blank space takes the focus from the terminal's native editor, as a press on nothing takes it
 * from xterm's textarea, and its keyboard goes with it. Compose hides a text field's own keyboard as the
 * field loses the focus; the native editor can only hide its own while it still holds the focus, so
 * [leavesTextOnTap] tells it first ([TextInputFocus.blurNative]). The tester measured the keyboard staying
 * up, and typing dead, after such a tap on the sidebar.
 *
 * The terminal is stood for by a focusable box that reports itself the way `TerminalBody` does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeEditorTapTest {
    private val compose = createComposeRule()

    // The compose rule starts a `ComponentActivity`, which this module's manifest does not declare.
    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(
            TestRule { base, _ ->
                object : Statement() {
                    override fun evaluate() {
                        val app = ApplicationProvider.getApplicationContext<Application>()
                        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                        base.evaluate()
                    }
                }
            },
        )
        .around(compose)

    private val editor = Any()
    private val requester = FocusRequester()

    /** What the editor was told and what happened to its focus, in order. */
    private val log = mutableListOf<String>()

    @After
    fun releaseEditor() {
        TextInputFocus.releaseNative(editor)
    }

    private fun showPage() {
        compose.setContent {
            val focusManager = LocalFocusManager.current
            Column(Modifier.fillMaxSize().leavesTextOnTap(focusManager)) {
                Box(
                    Modifier.size(80.dp).testTag("editor").focusRequester(requester)
                        .onFocusChanged { focus ->
                            if (focus.hasFocus) {
                                TextInputFocus.takeNative(editor, focusManager, onBlur = { log += "hide" }) { null }
                            } else {
                                log += "lost"
                                TextInputFocus.releaseNative(editor)
                            }
                        }
                        .focusable(),
                )
                Box(Modifier.size(80.dp).testTag("blank"))
                // A key of the toolbar's row: its handler asks for the focus back, as `focusTerminal()` does.
                Box(Modifier.size(80.dp).testTag("key").clickable { TextInputFocus.asked() })
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { requester.requestFocus() }
        compose.waitForIdle()
        assertTrue("the editor holds the focus", TextInputFocus.focused)
        // The first report of a focus modifier is its initial, unfocused state.
        log.clear()
    }

    @Test
    fun `a tap on blank space hides the editor's keyboard while it still holds the focus, then takes the focus`() {
        showPage()

        compose.onNodeWithTag("blank").performClick()
        compose.waitForIdle()

        // Only the order is asserted, not who holds the focus afterwards: this window is not in touch mode, where
        // `View.clearFocus` hands the focus straight back to the first focusable, as a finger's tap does not.
        assertEquals(listOf("hide", "lost"), log)
    }

    @Test
    fun `a tap on a control that asks for the focus back keeps the editor and its keyboard`() {
        showPage()

        compose.onNodeWithTag("key").performClick()
        compose.waitForIdle()

        assertEquals(emptyList<String>(), log)
        assertTrue(TextInputFocus.focused)
    }

    @Test
    fun `a long press on blank space leaves the editor and its keyboard alone`() {
        showPage()

        compose.onNodeWithTag("blank").performTouchInput { longClick() }
        compose.waitForIdle()

        assertEquals(emptyList<String>(), log)
        assertTrue(TextInputFocus.focused)
    }
}
