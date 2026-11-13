package com.hienlh.ppm.app.ui.git

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.files.installBlankFileIcons
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ChangeBlock
import com.hienlh.ppm.client.ChangeHunk
import com.hienlh.ppm.client.ChangeLine
import com.hienlh.ppm.client.ChangeSide
import com.hienlh.ppm.client.ChangeSideDetail
import com.hienlh.ppm.client.ChangedFile
import com.hienlh.ppm.client.FileChangeDetail
import com.hienlh.ppm.client.FileFullDiff
import com.hienlh.ppm.client.GitBranchState
import com.hienlh.ppm.client.GitChanges
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.git.GitChangesView
import com.hienlh.ppm.state.git.GitReviewFocus
import com.hienlh.ppm.state.git.GitReviewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Side by side or inline (`diffSideBySide`): one choice for the diff tab and Review changes, a
 * toolbar button in each, and a phone always inline (`diff-viewer.tsx`, `git-review-desktop.tsx`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-xhdpi")
class DiffSideBySideChoiceTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val original = "fun greet() {\n    println(\"old\")\n}\n"
    private val modified = "fun greet() {\n    println(\"new\")\n}\n"

    @Test
    fun `the diff tab's button switches a tablet between side by side and inline, and the model keeps the choice`() {
        val model = AppModel(ApplicationProvider.getApplicationContext())
        assertTrue("side by side is the default, as on the web", model.diffSideBySide)
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                Box(Modifier.size(900.dp, 400.dp)) {
                    GitDiffViewer(model = model, projectName = "ppm", filePath = "src/App.kt", inlineOriginal = original, inlineModified = modified)
                }
            }
        }
        composeRule.onNodeWithTag("git-diff-side-by-side-block", useUnmergedTree = true).assertExists()

        composeRule.onNodeWithTag("git-diff-side-by-side-toggle").performClick()
        assertFalse(model.diffSideBySide)
        composeRule.onAllNodesWithTag("git-diff-side-by-side-block", useUnmergedTree = true).assertCountEquals(0)
        composeRule.onNodeWithTag("git-diff-rows").assertExists()

        composeRule.onNodeWithTag("git-diff-side-by-side-toggle").performClick()
        assertTrue(model.diffSideBySide)
        composeRule.onNodeWithTag("git-diff-side-by-side-block", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a phone's diff stays inline and offers no switch`() {
        val model = AppModel(ApplicationProvider.getApplicationContext())
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                Box(Modifier.size(400.dp, 600.dp)) {
                    GitDiffViewer(model = model, projectName = "ppm", filePath = "src/App.kt", inlineOriginal = original, inlineModified = modified)
                }
            }
        }
        composeRule.onNodeWithTag("git-diff-rows").assertExists()
        composeRule.onAllNodesWithTag("git-diff-side-by-side-toggle").assertCountEquals(0)
        composeRule.onAllNodesWithTag("git-diff-side-by-side-block", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `Review changes side by side shows the whole file with each block's bar, and the button goes back to the block list`() {
        installBlankFileIcons()
        val hunk = ChangeHunk(
            "hunk-0", 0, 10, 2, 10, 2, 1, 1, "@@ -10,2 +10,2 @@",
            listOf(ChangeLine(" ", "val x = 1"), ChangeLine("-", "val y = 2"), ChangeLine("+", "val y = 20")),
        )
        val file = ChangedFile(
            path = "src/App.kt",
            conflict = false,
            x = ".",
            y = "M",
            untracked = false,
            unstaged = ChangeSide(added = 1, removed = 1, blocks = listOf("hunk-0"), changeBlocks = listOf(ChangeBlock("hunk-0", 0, 10, 2, 10, 2, 1, 1))),
        )
        val reviews = GitReviewModel.fileReviews(
            files = listOf(file),
            details = mapOf("src/App.kt" to FileChangeDetail(path = "src/App.kt", unstaged = ChangeSideDetail(whole = null, hunks = listOf(hunk)))),
            discards = emptyList(),
            pending = emptyMap(),
        )
        val review = reviews.single()
        val block = review.blocks.single()
        val head = (1..20).joinToString("") { n ->
            when (n) {
                10 -> "val x = 1\n"
                11 -> "val y = 2\n"
                else -> "line $n\n"
            }
        }
        val worktree = head.replace("val y = 2\n", "val y = 20\n")
        val asked = mutableListOf<String>()
        val staged = mutableListOf<String>()
        var sideBySide by mutableStateOf(true)

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                Box(Modifier.size(1100.dp, 700.dp)) {
                    GitReviewDesktopLayout(
                        branch = "main",
                        changes = GitChanges(
                            branch = GitBranchState("main", "abcdef1234567890", "origin/main", false, 0, 0, hasRemote = true),
                            operation = null,
                            files = listOf(file),
                            stashes = 0,
                            lastCommit = null,
                            truncated = false,
                        ),
                        error = null,
                        reviews = reviews,
                        conflicts = emptyList(),
                        totals = GitReviewModel.reviewTotals(reviews),
                        commitTotals = GitChangesView.changeTotals(listOf(file)),
                        focus = GitReviewFocus(review.path, block.key),
                        focusedTriple = Triple(review, block, 0),
                        items = GitReviewModel.paneItems(review.blocks),
                        pickSet = null,
                        busy = null,
                        toast = null,
                        commitMessage = "",
                        onCommitMessageChange = {},
                        onDismissToast = {},
                        onFocusBlock = { _, _ -> },
                        onFocusFile = {},
                        onStep = {},
                        onNextFile = {},
                        onStage = { p, k -> staged += "$p:$k" },
                        onDiscard = { _, _ -> },
                        onUnstage = { _, _ -> },
                        onUndoDiscard = {},
                        onStageFile = {},
                        onDiscardFile = {},
                        onStageAll = {},
                        onStartPick = {},
                        onTogglePickLine = {},
                        onSetPick = {},
                        onStagePicked = {},
                        onCommit = { _, _, _ -> },
                        onUndoCommit = {},
                        onOpenFile = {},
                        onResolveConflict = {},
                        onOpenGraph = {},
                        sideBySide = sideBySide,
                        onToggleSideBySide = { sideBySide = !sideBySide },
                        readWholeFile = { path ->
                            asked += path
                            FileFullDiff(original = head, modified = worktree, binary = false, originalSize = null, modifiedSize = null)
                        },
                    )
                }
            }
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("git-review-side-bar-${block.key}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(listOf("src/App.kt"), asked)
        composeRule.onAllNodesWithTag("git-review-code").assertCountEquals(0)
        composeRule.onNodeWithTag("git-review-block-stage-${block.key}", useUnmergedTree = true).performClick()
        assertEquals(listOf("src/App.kt:${block.key}"), staged)

        composeRule.onNodeWithTag("git-review-side-by-side-toggle").performClick()
        assertFalse(sideBySide)
        composeRule.onNodeWithTag("git-review-code").assertExists()
        composeRule.onAllNodesWithTag("git-review-side-by-side").assertCountEquals(0)
    }
}
