package com.hienlh.ppm.app.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ui.BottomSheet
import com.hienlh.ppm.app.ui.LocalSheetContentTop
import com.hienlh.ppm.app.ui.WebDialog
import com.hienlh.ppm.app.ui.files.installBlankFileIcons
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.chat.ChatTranscriptView
import com.hienlh.ppm.chat.LocalChatViewMode
import com.hienlh.ppm.state.ChatViewMode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hienlh.ppm.chat.ChatTurnTools
import com.hienlh.ppm.chat.LocalTurnChanges
import com.hienlh.ppm.chat.TurnChangesHost
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.SessionAnswer
import com.hienlh.ppm.protocol.SessionAnswerFile
import com.hienlh.ppm.protocol.SessionAnswerResult
import com.hienlh.ppm.protocol.SessionBookmark
import com.hienlh.ppm.protocol.SessionFileChange
import com.hienlh.ppm.protocol.TurnRevertFile
import com.hienlh.ppm.protocol.TurnRevertResult
import com.hienlh.ppm.state.ChatBlock
import com.hienlh.ppm.state.ChatItem
import com.hienlh.ppm.state.ChatState
import com.hienlh.ppm.state.HistoryWindowController
import com.hienlh.ppm.state.SessionBookmarks
import com.hienlh.ppm.state.SessionBookmarksBackend
import com.hienlh.ppm.state.SessionChanges
import com.hienlh.ppm.state.SessionChangesBackend
import com.hienlh.ppm.state.ToolBlock
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Bookmark and Rewind code under a message the person sent, on a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h840dp-xhdpi")
class PhonePromptActionsUiTest : PromptActionsUiTest(mobile = true)

/** The same on a tablet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xxhdpi")
class TabletPromptActionsUiTest : PromptActionsUiTest(mobile = false)

abstract class PromptActionsUiTest(private val mobile: Boolean) {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @Before
    fun setUp() {
        installBlankFileIcons()
    }

    private val sid = "0f8e2a4c-1111-4000-8000-000000000001"
    private val tid = "7a1b9c3d-2222-4000-8000-000000000002"
    private val at = Instant.parse("2026-11-21T08:00:00Z")

    private val items = listOf(
        ChatItem("m1", ChatItem.Role.USER, listOf(ChatBlock.Text("Fix the login\nmore detail")), at, turnId = tid, turnN = 1),
        ChatItem("a1", ChatItem.Role.ASSISTANT, listOf(ChatBlock.Tool(ToolBlock(toolUseId = "c1", name = "Bash", input = kotlinx.serialization.json.buildJsonObject { put("command", kotlinx.serialization.json.JsonPrimitive("ls")) }))), at),
    )

    private class FakeBookmarks(val fail: Boolean = false) : SessionBookmarksBackend {
        val saved = mutableListOf<SessionBookmark>()
        override suspend fun list(project: String, sessionId: String): List<SessionBookmark> {
            if (fail) throw PpmError.Server(404, "Not found")
            return saved.toList()
        }
        override suspend fun put(project: String, sessionId: String, turnId: String, turnN: Int?, label: String): List<SessionBookmark> {
            saved += SessionBookmark(turnId, turnN, label, "now")
            return saved.toList()
        }
        override suspend fun delete(project: String, sessionId: String, turnId: String): List<SessionBookmark> {
            saved.removeAll { it.turnId == turnId }
            return saved.toList()
        }
    }

    private class FakeRewind : SessionChangesBackend {
        val reverts = mutableListOf<List<Pair<String, String>>?>()
        val undone = mutableListOf<String>()
        override suspend fun list(paths: List<String>): List<SessionFileChange> = emptyList()
        override suspend fun count() = 0
        override suspend fun setReviewed(files: List<SessionFileChange>, reviewed: Boolean) = Unit
        override suspend fun answer(answer: SessionAnswer, files: List<SessionAnswerFile>) = SessionAnswerResult(emptyList())
        override suspend fun revertTurn(calls: List<String>, apply: List<Pair<String, String>>?): TurnRevertResult {
            reverts += apply
            val file = TurnRevertFile("src/login.ts", "v1", TurnRevertFile.Action.EDIT, changes = 1, added = 2, removed = 1)
            return if (apply == null) TurnRevertResult(listOf(file)) else TurnRevertResult(listOf(file), undoId = "u1")
        }
        override suspend fun undo(undoId: String): SessionAnswerResult {
            undone += undoId
            return SessionAnswerResult(emptyList())
        }
    }

    private fun tools(bookmarks: SessionBookmarks? = null) = ChatTurnTools(
        projectName = "ppm",
        origin = "http://localhost:8080",
        resolveTurnId = { _, _, _ -> null },
        history = HistoryWindowController(CoroutineScope(Dispatchers.Main)) { error("no window") },
        toast = {},
        bookmarks = bookmarks,
    )

    private fun button(label: String) =
        if (mobile) composeRule.onNodeWithContentDescription(label) else composeRule.onNodeWithText(label)

    @Test
    fun bookmark_marksTheTurnWithItsFirstLine() {
        val fake = FakeBookmarks()
        val store = SessionBookmarks({ fake }, CoroutineScope(Dispatchers.Main))
        val state = ChatState(sessionId = sid, providerId = "claude", items = items)
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                ChatTranscriptView(state, onRespond = { _, _, _ -> }, onLoadEarlier = {}, onReload = {}, turnTools = tools(store))
            }
        }
        composeRule.waitForIdle()
        button("Bookmark").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(SessionBookmark(tid, 1, "Fix the login", "now")), fake.saved)
        button("Bookmarked").assertExists()
        button("Bookmarked").performClick()
        composeRule.waitForIdle()
        assertEquals(emptyList<SessionBookmark>(), fake.saved)
        button("Bookmark").assertExists()
    }

    @Test
    fun bookmark_staysOutWhenTheServerHasNoBookmarks() {
        val store = SessionBookmarks({ FakeBookmarks(fail = true) }, CoroutineScope(Dispatchers.Main))
        val state = ChatState(sessionId = sid, providerId = "claude", items = items)
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                ChatTranscriptView(state, onRespond = { _, _, _ -> }, onLoadEarlier = {}, onReload = {}, turnTools = tools(store))
            }
        }
        composeRule.waitForIdle()
        button("Copy turn link").assertExists()
        button("Bookmark").assertDoesNotExist()
    }

    @Test
    fun rewindCode_previewsWritesAndUndoes() {
        val fake = FakeRewind()
        val changes = SessionChanges(fake, CoroutineScope(Dispatchers.Main))
        val host = TurnChangesHost(
            changes,
            openReview = {},
            sheet = { dismiss, content -> BottomSheet(dismiss, Modifier, radius = 14.dp) { content(LocalSheetContentTop.current) } },
            dialog = { title, description, dismiss, content -> WebDialog(title, dismiss, widest = 460.dp, description = description, content = content) },
        )
        val state = ChatState(sessionId = sid, providerId = "claude", items = items)
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                CompositionLocalProvider(LocalTurnChanges provides host) {
                    ChatTranscriptView(state, onRespond = { _, _, _ -> }, onLoadEarlier = {}, onReload = {}, turnTools = tools())
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Rewind code").performClick()
        composeRule.waitForIdle()
        val inDialog = hasAnyAncestor(hasTestTag("rewind-code-dialog"))
        composeRule.onNodeWithText("Rewind code to here?").assertExists()
        composeRule.onNodeWithText(
            "Puts the files back as they were before this message, undoing what 1 turn wrote. The chat itself stays as it is.",
        ).assertExists()
        composeRule.onNodeWithText("1 file, +2 −1 undone.", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Rewind and fork").assertDoesNotExist()
        composeRule.onNode(hasText("Rewind code") and inDialog, useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(null, listOf("src/login.ts" to "v1")), fake.reverts)
        composeRule.onNodeWithTag("rewind-code-status", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Rewound 1 file to before this message.", useUnmergedTree = true).assertExists()
        composeRule.onNode(hasText("Undo") and inDialog, useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("u1"), fake.undone)
        composeRule.onNodeWithText("Undone: the files are as they were before the rewind.", useUnmergedTree = true).assertExists()
        composeRule.onNode(hasText("Close") and inDialog, useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rewind-code-dialog").assertDoesNotExist()
    }

    /** The card's output, drawn with a break allowed between any two letters (zero-width spaces). */
    private val showsOutput = androidx.compose.ui.test.SemanticsMatcher("shows README.md") { node ->
        node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }
            ?.any { it.text.replace("\u200B", "") == "README.md" } == true
    }

    @Test
    fun viewModes_openThinkingThenEveryCard() {
        var mode by mutableStateOf(ChatViewMode.NORMAL)
        val answer = ChatItem(
            "a1",
            ChatItem.Role.ASSISTANT,
            listOf(
                ChatBlock.Thinking("Weighing the login flow"),
                ChatBlock.Tool(
                    ToolBlock(
                        toolUseId = "c1",
                        name = "Bash",
                        input = kotlinx.serialization.json.buildJsonObject { put("command", kotlinx.serialization.json.JsonPrimitive("ls")) },
                        result = com.hienlh.ppm.protocol.ToolOutcome("README.md", false),
                    ),
                ),
                ChatBlock.Text("Done."),
            ),
            at,
        )
        val state = ChatState(sessionId = sid, providerId = "claude", items = listOf(items[0], answer))
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                CompositionLocalProvider(LocalChatViewMode provides mode) {
                    ChatTranscriptView(state, onRespond = { _, _, _ -> }, onLoadEarlier = {}, onReload = {}, turnTools = tools())
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Weighing the login flow", substring = true, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNode(showsOutput, useUnmergedTree = true).assertDoesNotExist()
        mode = ChatViewMode.THINKING
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Weighing the login flow", substring = true, useUnmergedTree = true).assertExists()
        composeRule.onNode(showsOutput, useUnmergedTree = true).assertDoesNotExist()
        mode = ChatViewMode.VERBOSE
        composeRule.waitForIdle()
        composeRule.onNode(showsOutput, useUnmergedTree = true).assertExists()
        mode = ChatViewMode.NORMAL
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Weighing the login flow", substring = true, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNode(showsOutput, useUnmergedTree = true).assertDoesNotExist()
    }
}
