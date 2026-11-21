package com.hienlh.ppm.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.app.PrefsStore
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.SessionWorktree
import com.hienlh.ppm.state.DraftLocalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The footer's worktree chip (`worktree-chip.tsx`): a switch before a new Claude chat's first message, then its branch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h900dp")
class WorktreeChipTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val worktree = SessionWorktree("chat-261121-0930-00ff", "/repo/.claude/worktrees/chat-261121-0930-00ff", "worktree-chat-261121-0930-00ff")
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun count(tag: String) = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    @Test
    fun `on a tablet the switch says Worktree, then New worktree once on`() {
        var wanted by mutableStateOf(false)
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                WorktreeChip(worktree = null, canChoose = true, wanted = wanted, narrow = false, onWantedChange = { wanted = it }, onCopyPath = {})
            }
        }
        composeRule.onNodeWithText("Worktree", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("worktree-chip-off").assertIsOff().performClick()
        assertTrue(wanted)
        composeRule.onNodeWithText("New worktree", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("worktree-chip-wanted").assertIsOn().performClick()
        assertFalse(wanted)
    }

    @Test
    @Config(qualifiers = "w400dp-h840dp-xhdpi")
    fun `on a phone the switch is the icon alone, named for a reader`() {
        var wanted by mutableStateOf(false)
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                WorktreeChip(worktree = null, canChoose = true, wanted = wanted, narrow = true, onWantedChange = { wanted = it }, onCopyPath = {})
            }
        }
        assertEquals(0, composeRule.onAllNodesWithText("Worktree", useUnmergedTree = true).fetchSemanticsNodes().size)
        composeRule.onNodeWithContentDescription("Worktree").performClick()
        assertTrue(wanted)
        composeRule.onNodeWithContentDescription("New worktree").assertExists()
    }

    @Test
    fun `a chat in its worktree names the branch, and a tap copies the path`() {
        val copied = mutableListOf<String>()
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                WorktreeChip(worktree = worktree, canChoose = false, wanted = false, narrow = false, onWantedChange = {}, onCopyPath = { copied += it })
            }
        }
        composeRule.onNodeWithText(worktree.branch, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("Worktree ${worktree.branch}, copy its path").performClick()
        assertEquals(listOf(worktree.path), copied)
    }

    @Test
    @Config(qualifiers = "w400dp-h840dp-xhdpi")
    fun `on a phone a chat in its worktree still names the branch`() {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                WorktreeChip(worktree = worktree, canChoose = false, wanted = false, narrow = true, onWantedChange = {}, onCopyPath = {})
            }
        }
        composeRule.onNodeWithText(worktree.branch, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("worktree-chip-on").assertExists()
    }

    @Test
    fun `a chat that may not choose and has no worktree shows nothing`() {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                WorktreeChip(worktree = null, canChoose = false, wanted = true, narrow = false, onWantedChange = {}, onCopyPath = {})
            }
        }
        assertEquals(0, count("worktree-chip-off") + count("worktree-chip-wanted") + count("worktree-chip-on"))
    }

    private fun newChat(providerId: String): Pair<AppModel, ChatTab> {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().addHeader("Content-Type", "application/json").body("""{"ok":true,"data":null}""").build()
        }
        server.start()
        val context = ApplicationProvider.getApplicationContext<Application>()
        val api = ApiClient(server.url("/"), "secret")
        val model = AppModel(context).also { it.api = api }
        val tab = ChatTab("tab-new", null, "p", providerId).also {
            it.start(api, scope, DraftLocalStore(PrefsStore(context, "worktree-chip-test"), "server"))
        }
        return model to tab
    }

    @Test
    fun `a new Claude chat's switch is the choice its first message carries`() {
        val (model, tab) = newChat("claude")
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                ChatWorktreeChip(model, tab, narrow = false)
            }
        }
        composeRule.onNodeWithTag("worktree-chip-off").performClick()
        composeRule.waitUntil(5_000L) { composeRule.onAllNodes(hasTestTag("worktree-chip-wanted")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(tab.controller!!.state.value.worktreeWanted)
    }

    @Test
    fun `a new chat on another provider is offered no worktree`() {
        val (model, tab) = newChat("codex")
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                ChatWorktreeChip(model, tab, narrow = false)
            }
        }
        composeRule.waitForIdle()
        assertEquals(0, count("worktree-chip-off"))
    }
}
