package com.hienlh.ppm.app.ui.editor

import android.app.Application
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.editor.EditorSessionState
import com.hienlh.ppm.state.editor.MAX_IME_SLICE_CHARS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xxhdpi")
class Phase07abEditorUiTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @Test
    fun nativeCodeEditorCanvas_findReplaceRegexAndUndoRedoWork() {
        val session = EditorSessionState(
            initialText = "const alpha = 1;\nconst beta = 2;\nconst gamma = 3;",
            language = "typescript",
        )

        composeRule.setContent {
            var tick by remember { mutableLongStateOf(0L) }
            var wrap by remember { mutableStateOf(false) }
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                NativeCodeEditorCanvas(
                    session = session,
                    editTick = tick,
                    onMutated = { tick++ },
                    isDirty = tick > 0,
                    wrap = wrap,
                    onToggleWrap = { wrap = !wrap },
                )
            }
        }

        composeRule.onNodeWithTag("editor-cursor-status").assertTextContains("Ln 1, Col 1")

        // Open Find & Replace bar, enable regex, replace "const (\w+)" -> "let $1"
        composeRule.onNodeWithTag("editor-toggle-find").performClick()
        composeRule.onNodeWithTag("editor-find-bar").assertIsDisplayed()
        composeRule.onNodeWithTag("editor-find-regex").performClick()
        composeRule.onNodeWithTag("editor-find-input").performTextReplacement("const (\\w+)")
        composeRule.onNodeWithTag("editor-find-count").assertTextContains("1/3")
        composeRule.onNodeWithTag("editor-replace-input").performTextReplacement("let $1")
        composeRule.onNodeWithTag("editor-replace-all").performClick()

        assertEquals("let alpha = 1;\nlet beta = 2;\nlet gamma = 3;", session.buffer.toString())

        // Undo restores original
        composeRule.onNodeWithTag("editor-undo-btn").performClick()
        assertEquals("const alpha = 1;\nconst beta = 2;\nconst gamma = 3;", session.buffer.toString())
    }

    @Test
    fun nativeCodeEditorCanvas_goToLineAndMobileSymbolBarInsertAutoPairs() {
        val session = EditorSessionState(
            initialText = "line1\nline2\nline3",
            language = "typescript",
        )

        composeRule.setContent {
            var tick by remember { mutableLongStateOf(0L) }
            var wrap by remember { mutableStateOf(true) }
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                NativeCodeEditorCanvas(
                    session = session,
                    editTick = tick,
                    onMutated = { tick++ },
                    isDirty = false,
                    wrap = wrap,
                    onToggleWrap = { wrap = !wrap },
                )
            }
        }

        // Go to line 3:6
        composeRule.onNodeWithTag("editor-toggle-goto").performClick()
        composeRule.onNodeWithTag("editor-goto-input").performTextReplacement("3:6")
        composeRule.onNodeWithTag("editor-goto-submit").performClick()
        composeRule.onNodeWithTag("editor-cursor-status").assertTextContains("Ln 3, Col 6")

        // Tap '{' on mobile symbol bar -> inserts "{}" at end of line 3
        composeRule.onNodeWithTag("mobile-editor-symbol-bar").assertIsDisplayed()
        composeRule.onNodeWithTag("mobile-key-sym-{").performClick()
        assertEquals("line1\nline2\nline3{}", session.buffer.toString())
    }

    @Test
    fun editorImeBridgeView_createsBoundedInputConnectionAndSupportsTelexComposition() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val hugeText = "abcdef\n".repeat(10_000)
        val session = EditorSessionState(initialText = hugeText, language = "plaintext")
        val view = EditorImeBridgeView(app).apply {
            this.session = session
        }

        val attrs = EditorInfo()
        val ic = view.onCreateInputConnection(attrs)
        assertNotNull(ic)

        // Select all (~70,000 chars) -> getSelectedText must be capped at MAX_IME_SLICE_CHARS (4096)
        session.selectAll()
        val selected = ic!!.getSelectedText(0)
        assertNotNull(selected)
        assertEquals(MAX_IME_SLICE_CHARS, selected!!.length)

        // Reset to empty and test Telex composition via InputConnection
        session.reloadExternalContent("")
        ic.setComposingText("tiê", 1)
        ic.setComposingText("tiếng", 1)
        ic.commitText("tiếng ", 1)
        ic.setComposingText("việt", 1)
        ic.commitText("việt", 1)
        assertEquals("tiếng việt", session.buffer.toString())
    }
}
