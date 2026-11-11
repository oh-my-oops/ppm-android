package com.hienlh.ppm.app.ui.editor

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.hienlh.ppm.app.TextInputFocus
import com.hienlh.ppm.app.leavesTextOnTap
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.editor.EditorSessionState
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A finger tap on the code, or on a key of the editor's key row, keeps the keyboard's focus on the editor's IME
 * bridge. Every shell sits under `Root`'s [leavesTextOnTap], which clears the focus after a tap unless the tap's
 * handler asked for it ([TextInputFocus.asked]) or landed in the focused field ([TextInputFocus.pressed]); the
 * editor's tap focused the bridge without asking, so the focus was cleared 2 ms later and Gboard, still up, typed
 * into nothing (measured on the phone layout).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class EditorTapKeepsImeTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val session = EditorSessionState(initialText = "hi", language = "plaintext")
    private lateinit var host: View

    @After
    fun releaseEditor() {
        TextInputFocus.releaseNative(session)
    }

    private fun showEditor() {
        composeRule.setContent {
            host = LocalView.current
            val focusManager = LocalFocusManager.current
            var tick by remember { mutableLongStateOf(0L) }
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                Box(Modifier.fillMaxSize().leavesTextOnTap(focusManager)) {
                    NativeCodeEditorCanvas(
                        session = session,
                        editTick = tick,
                        onMutated = { tick++ },
                        wrap = true,
                        onToggleWrap = {},
                    )
                }
            }
        }
    }

    private fun bridge(): EditorImeBridgeView {
        val found = composeRule.runOnIdle { findBridge(host.rootView) }
        assertNotNull("the editor hosts its IME bridge", found)
        return found!!
    }

    /** Types [text] the way the soft keyboard does, through the bridge's InputConnection. */
    private fun typeThroughIme(bridge: EditorImeBridgeView, text: String) = composeRule.runOnIdle {
        val ic = bridge.onCreateInputConnection(EditorInfo())
        assertNotNull("an editable file gives the IME a connection", ic)
        ic!!.commitText(text, 1)
    }

    @Test
    fun `a tap on the code under the app's tap-to-blur root leaves the bridge focused and typing reaches the document`() {
        showEditor()

        composeRule.onNodeWithTag("native-code-editor-canvas").performTouchInput { click() }
        composeRule.waitForIdle()

        val bridge = bridge()
        composeRule.runOnIdle {
            assertTrue("the bridge still holds the focus after the tap", bridge.isFocused)
            assertSame("the editor is the focused field", session, TextInputFocus.field)
        }
        typeThroughIme(bridge, "X")
        assertTrue("typed text reaches the document", session.buffer.toString().contains("X"))
    }

    @Test
    fun `a tap on a key of the key row keeps the focus and inserts the key, and typing goes on after it`() {
        showEditor()
        composeRule.onNodeWithTag("native-code-editor-canvas").performTouchInput { click() }
        composeRule.waitForIdle()
        val bridge = bridge()

        composeRule.onNodeWithTag("mobile-key-sym-(").performTouchInput { click() }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertTrue("the bridge still holds the focus after the key", bridge.isFocused)
            assertSame("the editor is still the focused field", session, TextInputFocus.field)
        }
        assertTrue("the key's bracket is in the document", session.buffer.toString().contains("("))
        typeThroughIme(bridge, "a")
        assertTrue("typing after the key reaches the document", session.buffer.toString().contains("(a"))
    }

    private fun findBridge(view: View): EditorImeBridgeView? {
        if (view is EditorImeBridgeView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findBridge(view.getChildAt(i))?.let { return it }
        }
        return null
    }
}
