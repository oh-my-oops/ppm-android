package com.hienlh.ppm.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.ContextUsageView
import com.hienlh.ppm.protocol.UsageInfo
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Ctx 42%" in the chat footer and its popover (`context-meter.tsx`, web b49accc87). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xxhdpi")
class ContextMeterTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val usage = ContextUsageView(
        model = "claude-opus-4-5",
        percentage = 47,
        totalTokens = 94_000,
        maxTokens = 200_000,
        rows = listOf(
            ContextUsageView.Row("messages", "Messages", 60_000, "used"),
            ContextUsageView.Row("system_tools", "System tools", 12_300, "used"),
            ContextUsageView.Row("free_space", "Free space", 106_000, "free"),
        ),
    )

    @Test
    fun tablet_meterSitsFirstInTheCluster_popoverListsWhatFillsTheContext_andCompacts() {
        val answer = CompletableDeferred<ContextUsageView?>()
        val asked = mutableListOf<String>()
        var compacted = 0
        var pct by mutableStateOf<Int?>(42)
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Column {
                        ChatFooterTools(
                            usageInfo = UsageInfo(),
                            providerId = "claude",
                            narrow = false,
                            sessionId = "s1",
                            contextPct = pct,
                            contextUsage = { id -> asked += id; answer.await() },
                            onCompact = { compacted++ },
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag("context-meter").assertIsDisplayed().assertContentDescriptionEquals("Context 42% used")
        composeRule.onNodeWithTag("context-divider").assertExists()
        composeRule.onNodeWithTag("context-meter-pct:normal", useUnmergedTree = true).assertExists()
        // The ring shows beside the figure on a wide chat.
        composeRule.onNodeWithText("Ctx").assertIsDisplayed()

        composeRule.onNodeWithTag("context-meter").performClick()
        composeRule.onNodeWithText("Reading what fills the context…").assertIsDisplayed()
        answer.complete(usage)
        composeRule.waitForIdle()
        assertEquals(listOf("s1"), asked)
        composeRule.onNodeWithText("94.0k of 200k tokens · claude-opus-4-5").assertIsDisplayed()
        composeRule.onNodeWithTag("context-row:messages").assertIsDisplayed()
        composeRule.onNodeWithText("12.3k").assertIsDisplayed()
        composeRule.onNodeWithText("53%").assertIsDisplayed()
        composeRule.onNodeWithText("47% used").assertIsDisplayed()
        // The fresher figure replaces the last turn's on the chip.
        composeRule.onNodeWithTag("context-meter").assertContentDescriptionEquals("Context 47% used")

        composeRule.onNodeWithTag("context-compact").performClick()
        assertEquals(1, compacted)
        composeRule.onNodeWithText("47% used").assertDoesNotExist()

        // A new turn's figure takes over again.
        pct = 91
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("context-meter").assertContentDescriptionEquals("Context 91% used")
        composeRule.onNodeWithTag("context-meter-pct:error", useUnmergedTree = true).assertExists()
    }

    @Test
    fun tablet_noLiveClaude_saysSo_andAnErrorShowsInline() {
        var answer by mutableStateOf<suspend (String) -> ContextUsageView?>({ null })
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    ChatFooterTools(
                        usageInfo = UsageInfo(),
                        providerId = "claude",
                        narrow = false,
                        sessionId = "s1",
                        contextPct = 80,
                        contextUsage = { answer(it) },
                        onCompact = null,
                    )
                }
            }
        }
        composeRule.onNodeWithTag("context-meter-pct:warning", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("context-meter").performClick()
        composeRule.onNodeWithText(
            "The breakdown appears while this chat's Claude process is running. The figure is from the last turn.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("80% used").assertIsDisplayed()
        // While a turn runs Compact now waits.
        composeRule.onNodeWithTag("context-compact").performClick()
        composeRule.onNodeWithText("80% used").assertIsDisplayed()

        composeRule.onNodeWithTag("context-meter").performClick()
        answer = { throw PpmError.OutdatedServer() }
        composeRule.onNodeWithTag("context-meter").performClick()
        composeRule.onNodeWithText("Could not read the context: This needs a newer PPM. Update PPM on that machine.").assertIsDisplayed()
    }

    @Test
    fun tablet_noMeterBeforeAFigure_inAnotherProvider_orWithoutASession() {
        var provider by mutableStateOf("claude")
        var pct by mutableStateOf<Int?>(null)
        var session by mutableStateOf<String?>("s1")
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                ChatFooterTools(
                    usageInfo = UsageInfo(),
                    providerId = provider,
                    narrow = false,
                    sessionId = session,
                    contextPct = pct,
                    contextUsage = { usage },
                )
            }
        }
        composeRule.onNodeWithTag("context-meter").assertDoesNotExist()
        pct = 30
        composeRule.onNodeWithTag("context-meter").assertExists()
        provider = "codex"
        composeRule.onNodeWithTag("context-meter").assertDoesNotExist()
        provider = "claude"
        session = null
        composeRule.onNodeWithTag("context-meter").assertDoesNotExist()
        assertTrue(composeRule.onAllNodesWithTag("context-divider").fetchSemanticsNodes().isEmpty())
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xxhdpi")
    fun phone_chipOpensTheBreakdownInASheet_withoutTheRing() {
        var compacted = 0
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    ContextMeterChip(
                        sessionId = "s1",
                        providerId = "claude",
                        contextPct = 42,
                        load = { usage },
                        onCompact = { compacted++ },
                    )
                }
            }
        }
        composeRule.onNodeWithTag("context-meter").assertIsDisplayed().assertContentDescriptionEquals("Context 42% used")
        composeRule.onNodeWithTag("context-meter").performClick()
        composeRule.onNodeWithTag("footer-popover:Context").assertIsDisplayed()
        composeRule.onNodeWithTag("context-row:free_space").assertExists()
        composeRule.onNodeWithTag("context-compact").performClick()
        assertEquals(1, compacted)
        composeRule.onNodeWithTag("footer-popover:Context").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xxhdpi")
    fun phone_noChipForCodexOrBeforeAFigure() {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                Column {
                    ContextMeterChip(sessionId = "s1", providerId = "codex", contextPct = 42, load = { usage }, onCompact = null)
                    ContextMeterChip(sessionId = "s1", providerId = "claude", contextPct = null, load = { usage }, onCompact = null)
                }
            }
        }
        composeRule.onNodeWithTag("context-meter").assertDoesNotExist()
    }
}
