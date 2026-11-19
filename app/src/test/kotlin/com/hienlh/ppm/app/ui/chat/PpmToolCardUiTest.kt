package com.hienlh.ppm.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.chat.AgentSessionOpener
import com.hienlh.ppm.chat.ChatSessionOpener
import com.hienlh.ppm.chat.ChatSessionTarget
import com.hienlh.ppm.chat.LocalAgentSessionOpener
import com.hienlh.ppm.chat.LocalChatSessionOpener
import com.hienlh.ppm.chat.LocalTaskCodeOpener
import com.hienlh.ppm.chat.TaskCodeOpener
import com.hienlh.ppm.chat.ToolCardView
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.ASSISTANT_PROJECT_NAME
import com.hienlh.ppm.protocol.ToolOutcome
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.state.AgentSessionIdentity
import com.hienlh.ppm.state.ToolBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cards for PPM's own tools (`ppm-tool-header.tsx`, `ToolCard` in `tool-cards.tsx`): a readable name
 * and one line in place of `mcp__ppm-…`, the running, done, failed and stopped states, and the
 * buttons that open the task or chat a call named. Phone and tablet draw the same card.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class PpmToolCardUiTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val opened = mutableListOf<ChatSessionTarget>()
    private val tasks = mutableListOf<Pair<String, String?>>()

    private fun content(mobile: Boolean, project: String = "ppm", body: @Composable () -> Unit) {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                CompositionLocalProvider(
                    LocalChatSessionOpener provides ChatSessionOpener { opened += it },
                    LocalTaskCodeOpener provides TaskCodeOpener { code, chatProject -> tasks += code to chatProject },
                    LocalAgentSessionOpener provides AgentSessionOpener(AgentSessionIdentity(project, "claude", "this-session")) { _, _ -> },
                ) { body() }
            }
        }
    }

    private fun tool(name: String, input: String, output: String? = null, isError: Boolean = false) =
        ToolBlock(toolUseId = "t1", name = name, input = WireJson.parse(input), result = output?.let { ToolOutcome(it, isError) })

    private fun noRawName() {
        assertEquals(0, composeRule.onAllNodesWithText("mcp__", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("ppm_sessions:", substring = true).fetchSemanticsNodes().size)
    }

    private fun aTaskCardRunsThenNamesItsTask(mobile: Boolean) {
        var block by mutableStateOf(tool("mcp__ppm-sessions__ppm_task_create", """{"title":"Fix login"}"""))
        content(mobile) { ToolCardView(block, completed = false) }
        composeRule.onNodeWithText("Create task Fix login").assertExists()
        noRawName()
        assertEquals(0, composeRule.onAllNodesWithTag("ppm-tool-task").fetchSemanticsNodes().size)

        block = tool("mcp__ppm-sessions__ppm_task_create", """{"title":"Fix login"}""", "Created: ppm/151 \"Fix login\" — now revision 1.")
        composeRule.onNodeWithText("Created task ppm/151 · Fix login").assertExists()
        composeRule.onNodeWithTag("ppm-tool-task").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("ppm/151" to "ppm"), tasks)
        // Expanding still shows the full input and output.
        composeRule.onNodeWithText("Created task ppm/151 · Fix login").performClick()
        // The details break anywhere (U+200B between characters), so the text is read without the breaks.
        val showing = SemanticsMatcher("shows Fix login") { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { "Fix login" in it.text.replace("\u200B", "") }
        }
        assertTrue(composeRule.onAllNodes(showing, useUnmergedTree = true).fetchSemanticsNodes().size >= 2)
    }

    @Test
    fun aTaskCard_onThePhone() = aTaskCardRunsThenNamesItsTask(mobile = true)

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun aTaskCard_onTheTablet() = aTaskCardRunsThenNamesItsTask(mobile = false)

    private fun aReadTurnOpensItsChat(mobile: Boolean) {
        val answer = "Session s9 · codex · project web, not this chat's project · Turn 3 · 2026-11-18T14:00:00Z · done"
        content(mobile) { ToolCardView(tool("ppm_tabs:read_session_turns", """{"server":"ppm_tabs","tool":"read_session_turns","arguments":{"session_id":"s9","turn_ids":["t3"]}}""", answer), completed = true) }
        composeRule.onNodeWithText("Read turn 3 · in web").assertExists()
        noRawName()
        composeRule.onNodeWithText("Open chat").performClick()
        assertEquals(listOf(ChatSessionTarget("web", "codex", "s9", "", "t3")), opened)
    }

    @Test
    fun aReadTurnOpensItsChat_onThePhone() = aReadTurnOpensItsChat(mobile = true)

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun aReadTurnOpensItsChat_onTheTablet() = aReadTurnOpensItsChat(mobile = false)

    @Test
    fun aFailedCallShowsItsRefusal_andAStoppedOneSaysSo() {
        var block by mutableStateOf(
            tool(
                "mcp__ppm-sessions__session_schedule_propose_script",
                """{"name":"Backup"}""",
                """{"done":false,"outcome":"declined","note":"The user declined. Nothing was created."}""",
                isError = true,
            ),
        )
        var completed by mutableStateOf(true)
        content(mobile = true) { ToolCardView(block, completed) }
        composeRule.onNodeWithText("Propose script schedule “Backup”").assertExists()
        composeRule.onNodeWithTag("ppm-tool-error").assertExists()
        composeRule.onNodeWithText("The user declined. Nothing was created.").assertExists()
        composeRule.onNodeWithTag("ppm-tool-badge", useUnmergedTree = true).assertExists()

        // The turn ended before the tool answered: stopped, never a success.
        block = tool("mcp__ppm-sessions__ppm_task_list", "{}")
        composeRule.onNodeWithText("List tasks").assertExists()
        composeRule.onNodeWithTag("ppm-tool-stopped", useUnmergedTree = true).assertExists()
        completed = false
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithTag("ppm-tool-stopped", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun theAssistantOpensNoChatInItsOwnProject_andAnAssistantToolIsNamedOnly() {
        content(mobile = false, project = ASSISTANT_PROJECT_NAME) {
            androidx.compose.foundation.layout.Column {
                ToolCardView(tool("mcp__ppm-tabs__list_session_turns", """{"session_id":"s1"}"""), completed = false)
                ToolCardView(tool("mcp__ppm-assistant__chats_attention", "{}", "[]"), completed = false)
                ToolCardView(tool("mcp__ppm-db__db_query", """{"connection":"main","sql":"SELECT 1"}""", "Statement 1 returned 3 rows:"), completed = false)
            }
        }
        composeRule.onNodeWithText("List turns").assertExists()
        assertEquals(0, composeRule.onAllNodesWithText("Open chat").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("Chats attention").assertExists()
        composeRule.onNodeWithText("3 rows").assertExists()
        noRawName()
    }
}
