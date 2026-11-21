package com.hienlh.ppm.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ui.files.installBlankFileIcons
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.chat.ReviewDesktop
import com.hienlh.ppm.chat.ReviewPhone
import com.hienlh.ppm.chat.ReviewSheet
import com.hienlh.ppm.chat.ReviewTabComments
import com.hienlh.ppm.chat.ReviewTabContext
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.SessionAnswer
import com.hienlh.ppm.protocol.SessionAnswerFile
import com.hienlh.ppm.protocol.SessionAnswerResult
import com.hienlh.ppm.protocol.SessionBlockSummary
import com.hienlh.ppm.protocol.SessionFileChange
import com.hienlh.ppm.protocol.SessionFileDiff
import com.hienlh.ppm.state.ReviewBlocks
import com.hienlh.ppm.state.ReviewComments
import com.hienlh.ppm.state.ReviewLineComment
import com.hienlh.ppm.state.SessionReview
import com.hienlh.ppm.state.SessionReviewBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Review tab's line comments (line-comments.test.tsx): one opens from an open block's line
 * number, is saved under the line and kept for the chat, can be deleted, and the header's "Send N
 * comments to chat" (a badge on a phone) hands them over. On a tablet and on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ReviewLineCommentsUiTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Before
    fun setUp() = installBlankFileIcons()

    @After
    fun tearDown() = scope.cancel()

    private val path = "/p/src/a.ts"
    private val original = (1..10).joinToString("") { "line $it\n" }
    private val modified = original.replace("line 3\n", "line three\n")

    /** One file with one changed line, as the server lists and diffs it. */
    private inner class OneFile : SessionReviewBackend {
        private fun row(): SessionFileChange {
            val diff = ReviewBlocks.computeBlocks(original, modified)!!
            return SessionFileChange(
                path, SessionFileChange.Status.MODIFIED, diff.additions, diff.deletions, version = "v1", base = "b",
                blocks = diff.blocks.map { SessionBlockSummary(it.key, it.added, it.removed) },
            )
        }

        override suspend fun list(paths: List<String>) = listOf(row())
        override suspend fun diff(path: String) = SessionFileDiff(row(), original, modified)
        override suspend fun answer(answer: SessionAnswer, files: List<SessionAnswerFile>): SessionAnswerResult = error("not asked")
        override suspend fun undo(undoId: String): SessionAnswerResult = error("not asked")
    }

    private val store = ReviewComments()
    private var sent = 0

    private fun show(mobile: Boolean) {
        val review = SessionReview(OneFile(), scope, emptyList(), compute = Dispatchers.Main)
        review.start()
        val sheet: ReviewSheet = { _, content -> Column { content(0.dp) } }
        val ctx = ReviewTabContext(
            projectPath = "/p",
            providerId = "claude",
            chatTitle = "Chat",
            turns = emptyMap(),
            openChat = {},
            openFile = {},
            showInChat = { _, _ -> },
            typing = { false },
            ownsKeys = { true },
            sheet = sheet,
            listSheet = sheet,
            comments = ReviewTabComments("s1", store) { sent++ },
        )
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = mobile) {
                if (mobile) ReviewPhone(review, ctx) else ReviewDesktop(review, ctx)
            }
        }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithContentDescription("Comment on line 3").fetchSemanticsNodes().size == 2 }
    }

    private fun commentOnTheAddedLine(mobile: Boolean, send: String) {
        show(mobile)
        // The block's removed line 3 comes first, then the added one.
        composeRule.onAllNodesWithContentDescription("Comment on line 3")[1].performClick()
        composeRule.onNodeWithContentDescription("Comment on this line").performTextInput("Call it 3, not three")
        composeRule.onNodeWithText("Comment").performClick()
        assertEquals(listOf(ReviewLineComment("$path\nnew\n3", path, 3, ReviewLineComment.Side.NEW, "line three", "Call it 3, not three")), store.of("s1"))
        composeRule.onNodeWithText("Call it 3, not three").assertExists()

        if (mobile) composeRule.onNodeWithContentDescription(send).performClick() else composeRule.onNodeWithText(send).performClick()
        assertEquals(1, sent)

        composeRule.onNodeWithContentDescription("Delete comment").performClick()
        assertEquals(emptyList<ReviewLineComment>(), store.of("s1"))
        composeRule.onAllNodesWithText("Call it 3, not three").assertCountEquals(0)
        composeRule.onAllNodesWithText(send).assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `a tablet comments on a changed line from its number and sends it from the header`() =
        commentOnTheAddedLine(mobile = false, send = "Send 1 comment to chat")

    @Test
    fun `a phone comments on a changed line from its number and sends it from the badge`() =
        commentOnTheAddedLine(mobile = true, send = "Send 1 comments to chat")

    @Test
    fun `a removed line's comment names the base's line`() {
        show(mobile = true)
        composeRule.onAllNodesWithContentDescription("Comment on line 3")[0].performClick()
        composeRule.onNodeWithContentDescription("Comment on this line").performTextInput("Why?")
        composeRule.onNodeWithText("Comment").performClick()
        assertEquals(ReviewLineComment.Side.OLD, store.of("s1").single().side)
        assertEquals("line 3", store.of("s1").single().quote)
    }

    @Test
    fun `Cancel closes the box and saves nothing`() {
        show(mobile = false)
        composeRule.onAllNodesWithContentDescription("Comment on line 3")[1].performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onAllNodesWithContentDescription("Comment on this line").assertCountEquals(0)
        assertEquals(emptyList<ReviewLineComment>(), store.of("s1"))
    }
}
