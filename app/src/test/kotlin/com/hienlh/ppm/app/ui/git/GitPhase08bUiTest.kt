package com.hienlh.ppm.app.ui.git

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.files.installBlankFileIcons
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.BranchDiffFile
import com.hienlh.ppm.client.ChangeBlock
import com.hienlh.ppm.client.ChangeHunk
import com.hienlh.ppm.client.ChangeLine
import com.hienlh.ppm.client.ChangeSide
import com.hienlh.ppm.client.ChangeSideDetail
import com.hienlh.ppm.client.ChangedFile
import com.hienlh.ppm.client.FileChangeDetail
import com.hienlh.ppm.client.GitBranch
import com.hienlh.ppm.client.GitCommit
import com.hienlh.ppm.state.git.BranchReviewState
import com.hienlh.ppm.state.git.DiscardedEntry
import com.hienlh.ppm.state.git.GitFileTree
import com.hienlh.ppm.state.git.GitReviewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xxhdpi")
class GitPhase08bUiTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    @Test
    fun `git log commit row renders ref badges, subject, hash, and author`() {
        val branches = listOf(
            GitBranch(
                name = "main",
                current = true,
                remote = false,
                tracking = "origin/main",
                ahead = 0,
                behind = 0,
                commit = "abcdef1234567890",
            ),
            GitBranch(
                name = "origin/main",
                current = false,
                remote = true,
                tracking = null,
                ahead = 0,
                behind = 0,
                commit = "abcdef1234567890",
            ),
        )
        val commit = GitCommit(
            hash = "abcdef1234567890",
            abbreviatedHash = "abcdef1",
            subject = "feat: add phase 08b review changes",
            authorName = "Hoang Thang",
            authorDate = "2026-11-07T10:00:00Z",
            refs = listOf("HEAD -> main", "origin/main", "tag: v1.2.0"),
        )

        compose.setContent {
            Column {
                CommitRow(commit = commit, branches = branches, head = "abcdef1234567890")
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("git-log-row-abcdef1", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("git-ref-badge-main", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("git-ref-badge-v1.2.0", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("feat: add phase 08b review changes").assertIsDisplayed()
        compose.onNodeWithText("abcdef1").assertIsDisplayed()
        compose.onNodeWithText("Hoang Thang").assertIsDisplayed()
    }

    @Test
    fun `git diff viewer renders inline unified diff and binary switcher and placeholder`() {
        val model = AppModel(ApplicationProvider.getApplicationContext())
        var switchedMode = "preview"
        var openedAnyway = false

        compose.setContent {
            Column {
                Box(Modifier.size(600.dp, 300.dp)) {
                    GitDiffViewer(
                        model = model,
                        projectName = "ppm",
                        filePath = "src/App.kt",
                        inlineOriginal = "fun greet() {\n    println(\"old\")\n}\n",
                        inlineModified = "fun greet() {\n    println(\"new\")\n}\n",
                    )
                }
                BinaryViewSwitcher(mode = switchedMode, onChange = { switchedMode = it })
                BinaryPlaceholder(onOpenAnyway = { openedAnyway = true })
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("git-diff-viewer", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("git-diff-rows", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("binary-mode-text", useUnmergedTree = true).performClick()
        assertEquals("text", switchedMode)

        compose.onNodeWithTag("binary-open-anyway", useUnmergedTree = true).performClick()
        assertTrue(openedAnyway)
    }

    @Test
    fun `branch review tree row renders file status, counts, and toggles reviewed`() {
        installBlankFileIcons()
        val files = listOf(
            BranchDiffFile(
                path = "src/ui/Review.kt",
                status = "M",
                additions = 24,
                deletions = 5,
                binary = false,
                blob = "blob-111",
            ),
        )
        val byPath = files.associateBy { it.path }
        val tree = GitFileTree.buildTree(
            files.map { f ->
                ChangedFile(
                    path = f.path,
                    unstaged = ChangeSide(status = f.status, added = f.additions, removed = f.deletions, blocks = emptyList()),
                )
            },
        )
        var reviewed = emptyMap<String, String>()
        var selectedPath: String? = null

        compose.setContent {
            Column {
                for (node in tree) {
                    BranchReviewTreeRow(
                        node = node,
                        depth = 0,
                        byPath = byPath,
                        reviewed = reviewed,
                        selectedPath = selectedPath,
                        collapsed = emptySet(),
                        onToggleCollapse = {},
                        onSelect = { selectedPath = it },
                        onToggleReviewed = { file ->
                            reviewed = BranchReviewState.toggleReviewed(reviewed, file)
                        },
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("branch-review-file-src/ui/Review.kt", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("branch-review-check-src/ui/Review.kt", useUnmergedTree = true).performClick()
        assertEquals("blob-111", reviewed["src/ui/Review.kt"])
    }

    @Test
    fun `git review block card, file list, toast, and empty view trigger stage, discard, undo, and line picking`() {
        installBlankFileIcons()
        val hunk = ChangeHunk(
            "hunk-0",
            0,
            10,
            2,
            10,
            3,
            2,
            1,
            "@@ -10,2 +10,3 @@",
            listOf(
                ChangeLine(kind = " ", text = "val x = 1"),
                ChangeLine(kind = "-", text = "val y = 2"),
                ChangeLine(kind = "+", text = "val y = 20"),
                ChangeLine(kind = "+", text = "val z = 30"),
            ),
        )
        val block0 = ChangeBlock("hunk-0", 0, 10, 2, 10, 3, added = 2, removed = 1)
        val file = ChangedFile(
            path = "src/App.kt",
            unstaged = ChangeSide(status = "M", added = 2, removed = 1, blocks = listOf("hunk-0"), changeBlocks = listOf(block0)),
        )
        val detail = FileChangeDetail(
            path = "src/App.kt",
            unstaged = ChangeSideDetail(whole = null, hunks = listOf(hunk)),
        )
        val reviews = GitReviewModel.fileReviews(
            files = listOf(file),
            details = mapOf("src/App.kt" to detail),
            discards = listOf(
                DiscardedEntry(
                    path = "src/App.kt",
                    recordId = "disc-1",
                    hunks = listOf(hunk.copy(index = 1, id = "hunk-1", oldStart = 30, newStart = 30)),
                    whole = null,
                    added = 2,
                    removed = 1,
                ),
            ),
            pending = emptyMap(),
        )
        val rev = reviews.single()
        val openBlock = rev.blocks.first { it.state == "open" }
        val discardedBlock = rev.blocks.first { it.state == "discarded" }

        var staged = false
        var discarded = false
        var undoRecord: String? = null
        var pickedFile: String? = null
        var toastUndone = false

        compose.setContent {
            Column {
                ReviewFileListView(
                    reviews = reviews,
                    conflicts = emptyList(),
                    currentPath = "src/App.kt",
                    mobile = false,
                    onPick = { pickedFile = it },
                    onResolve = {},
                )
                ReviewBlockCardView(
                    name = "App.kt",
                    oldPath = null,
                    block = openBlock,
                    index = 0,
                    total = rev.blocks.size,
                    focused = true,
                    pick = null,
                    compact = false,
                    lang = "kotlin",
                    busy = false,
                    onFocus = {},
                    onStage = { staged = true },
                    onDiscard = { discarded = true },
                    onUnstage = {},
                    onUndoDiscard = {},
                    onStartPick = {},
                    onTogglePickLine = {},
                    onSetPick = {},
                    onStagePicked = {},
                )
                ReviewBlockCardView(
                    name = "App.kt",
                    oldPath = null,
                    block = discardedBlock,
                    index = 1,
                    total = rev.blocks.size,
                    focused = false,
                    pick = null,
                    compact = false,
                    lang = "kotlin",
                    busy = false,
                    onFocus = {},
                    onStage = {},
                    onDiscard = {},
                    onUnstage = {},
                    onUndoDiscard = { undoRecord = it },
                    onStartPick = {},
                    onTogglePickLine = {},
                    onSetPick = {},
                    onStagePicked = {},
                )
                ReviewToastOverlay(
                    toast = ReviewToastData(id = 1L, text = "Discarded block 1 of 2 in App.kt", undo = { toastUndone = true }),
                    mobile = false,
                    onClose = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("git-review-file-src/App.kt", useUnmergedTree = true).performClick()
        assertEquals("src/App.kt", pickedFile)

        compose.onNodeWithTag("git-review-block-stage-${openBlock.key}", useUnmergedTree = true).performClick()
        assertTrue(staged)

        compose.onNodeWithTag("git-review-block-discard-${openBlock.key}", useUnmergedTree = true).performClick()
        assertTrue(discarded)

        compose.onNodeWithTag("git-review-block-undo-${discardedBlock.key}", useUnmergedTree = true).performClick()
        assertEquals("disc-1", undoRecord)

        compose.onNodeWithTag("git-review-toast-undo", useUnmergedTree = true).performClick()
        assertTrue(toastUndone)
    }
}
