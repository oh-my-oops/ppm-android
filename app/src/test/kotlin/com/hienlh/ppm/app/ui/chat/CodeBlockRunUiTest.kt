package com.hienlh.ppm.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ui.shellCodeActions
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.chat.LocalShellCodeActions
import com.hienlh.ppm.chat.MarkdownView
import com.hienlh.ppm.chat.ShellCodeActions
import com.hienlh.ppm.client.ShellRunResult
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val FENCE = "```"

/**
 * A chat's shell code block offers Copy, Run (its output under the block) and Run in terminal
 * (typed and Enter pressed), as `code-block-run-buttons.test.tsx` checks on the web; a block that
 * is not a command, or one still streaming, offers Copy only.
 */
abstract class CodeBlockRunUiTestBase(private val mobile: Boolean) {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val typed = mutableListOf<String>()
    private val ran = mutableListOf<String>()
    private var answer = CompletableDeferred<ShellRunResult>()
    private var cancelled = false

    private val runner: suspend (String) -> ShellRunResult = { command ->
        ran += command
        try {
            answer.await()
        } catch (error: kotlinx.coroutines.CancellationException) {
            cancelled = true
            throw error
        }
    }

    private fun actions(project: String = "ppm", minimal: Boolean = false): ShellCodeActions? =
        shellCodeActions(project, minimal, { typed += it }, runner)

    private var shown by mutableStateOf(true)

    private fun show(markdown: String, streaming: Boolean = false, actions: ShellCodeActions? = actions()) {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                CompositionLocalProvider(LocalShellCodeActions provides actions) {
                    Box(Modifier.width(360.dp)) {
                        if (shown) MarkdownView(markdown, streaming = streaming)
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun button(title: String): SemanticsNodeInteraction = composeRule.onNodeWithContentDescription(title, useUnmergedTree = true)

    private fun count(title: String) = composeRule.onAllNodesWithContentDescription(title, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun waitText(text: String) = composeRule.waitUntil(5_000) {
        composeRule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun status(): String {
        val node = composeRule.onNodeWithTag("code-block-run-status", useUnmergedTree = true).fetchSemanticsNode()
        return node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
    }

    private fun statusColor(): Color {
        val node = composeRule.onNodeWithTag("code-block-run-status", useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.single().layoutInput.style.color
    }

    private val shellBlock = "$FENCE\ngrep '^MASTER_PASSWORD=' .env\n$FENCE"

    @Test
    fun aShellBlockOffersCopyRunAndRunInTerminal_andNoSendToTerminal() {
        show(shellBlock)
        assertEquals(1, count("Copy"))
        assertEquals(1, count("Run (output below)"))
        assertEquals(1, count("Run in terminal"))
        assertEquals(0, count("Send to terminal"))
    }

    @Test
    fun codeThatIsNotACommandOffersCopyOnly() {
        show("${FENCE}ts\nconst x = 1;\n$FENCE")
        assertEquals(1, count("Copy"))
        assertEquals(0, count("Run (output below)"))
        assertEquals(0, count("Run in terminal"))
    }

    @Test
    fun aBlockStillStreamingOffersCopyOnly() {
        show("${FENCE}bash\ngit status\n$FENCE", streaming = true)
        assertEquals(1, count("Copy"))
        assertEquals(0, count("Run (output below)"))
        assertEquals(0, count("Run in terminal"))
    }

    @Test
    fun theAssistantsChatMinimalModeAndNoProjectOfferNoRun() {
        assertNull(shellCodeActions("__assistant__", false, {}, runner))
        assertNull(shellCodeActions("ppm", true, {}, runner))
        assertNull(shellCodeActions("", false, {}, runner))
        assertNull(shellCodeActions(null, false, {}, runner))
        assertNotNull(shellCodeActions("ppm", false, {}, runner))
        show(shellBlock, actions = actions(project = "__assistant__"))
        assertEquals(1, count("Copy"))
        assertEquals(0, count("Run (output below)"))
    }

    @Test
    fun runShowsRunningThenWhatItPrintedAndItsExit() {
        show("${FENCE}bash\n$ grep '^MASTER_PASSWORD=' .env\n$FENCE")
        button("Run (output below)").performClick()
        waitText("Running ·")
        assertEquals(listOf("grep '^MASTER_PASSWORD=' .env"), ran)
        assertEquals(1, count("Stop"))
        answer.complete(ShellRunResult("MASTER_PASSWORD=x\n", 0, timedOut = false, stopped = false, durationMs = 1_200))
        waitText("Exit 0 · 1.2s")
        assertEquals("Exit 0 · 1.2s", status())
        waitText("MASTER_PASSWORD=x")
        assertEquals(0, count("Stop"))
        assertEquals(1, count("Close output"))
        assertTrue(statusColor() != errorColor())

        // Close takes the output away.
        button("Close output").performClick()
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("MASTER_PASSWORD=x", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun aFailedRunIsShownRedWithItsText() {
        show(shellBlock)
        button("Run (output below)").performClick()
        waitText("Running ·")
        answer.completeExceptionally(IllegalStateException("Can't reach the server."))
        waitText("Could not run: Can't reach the server.")
        assertEquals(errorColor(), statusColor())

        // An exit other than 0 is a failure too, its output still shown.
        answer = CompletableDeferred()
        button("Run (output below)").performClick()
        waitText("Running ·")
        answer.complete(ShellRunResult("", 1, timedOut = false, stopped = false, durationMs = 300))
        waitText("Exit 1 · 0.3s")
        assertEquals(errorColor(), statusColor())
        waitText("(no output)")
    }

    @Test
    fun stopCancelsTheRunAndSaysStopped() {
        show(shellBlock)
        button("Run (output below)").performClick()
        waitText("Running ·")
        button("Stop").performClick()
        waitText("Stopped")
        assertEquals("Stopped", status())
        composeRule.waitUntil(5_000) { cancelled }
    }

    @Test
    fun leavingCompositionStopsTheRun() {
        show(shellBlock)
        button("Run (output below)").performClick()
        waitText("Running ·")
        shown = false
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000) { cancelled }
    }

    @Test
    fun runInTerminalHandsTheCommandWithEnter() {
        show("$FENCE\n$ git status\n$FENCE")
        button("Run in terminal").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("git status\r"), typed)
        assertTrue(ran.isEmpty())
    }

    private fun errorColor(): Color = BuiltInThemes.auroraDark.tokens.error.color
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xhdpi")
class CodeBlockRunTabletUiTest : CodeBlockRunUiTestBase(mobile = false)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h840dp-xhdpi")
class CodeBlockRunPhoneUiTest : CodeBlockRunUiTestBase(mobile = true)
