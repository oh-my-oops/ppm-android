package com.hienlh.ppm.app.ui.git

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.files.installBlankFileIcons
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
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
import com.hienlh.ppm.client.GitRepo
import com.hienlh.ppm.client.GitRepos
import com.hienlh.ppm.state.git.BranchReviewState
import com.hienlh.ppm.state.git.DiscardedEntry
import com.hienlh.ppm.state.git.GitFileTree
import com.hienlh.ppm.state.git.GitReviewModel
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `typing n j k y l in commit message box does not discard or stage or step the focused block`() {
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
        val changes = com.hienlh.ppm.client.GitChanges(
            branch = com.hienlh.ppm.client.GitBranchState(
                head = "main",
                oid = "abcdef1234567890",
                upstream = "origin/main",
                upstreamGone = false,
                ahead = 0,
                behind = 0,
                hasRemote = true,
            ),
            operation = null,
            files = listOf(file),
            stashes = 0,
            lastCommit = null,
            truncated = false,
        )
        val reviews = GitReviewModel.fileReviews(
            files = listOf(file),
            details = mapOf("src/App.kt" to detail),
            discards = emptyList(),
            pending = emptyMap(),
        )
        val rev = reviews.single()
        val openBlock = rev.blocks.first()
        val focus = com.hienlh.ppm.state.git.GitReviewFocus(rev.path, openBlock.key)
        val focusedTriple = Triple(rev, openBlock, 0)
        val items = GitReviewModel.paneItems(rev.blocks)
        val totals = GitReviewModel.reviewTotals(reviews)
        val commitTotals = com.hienlh.ppm.state.git.GitChangesView.changeTotals(listOf(file))

        var message by mutableStateOf("")
        val discarded = mutableListOf<String>()
        val staged = mutableListOf<String>()
        val stepped = mutableListOf<Int>()
        var pickStarted = false

        compose.setContent {
            Box(Modifier.size(900.dp, 700.dp)) {
                GitReviewDesktopLayout(
                    branch = "main",
                    changes = changes,
                    error = null,
                    reviews = reviews,
                    conflicts = emptyList(),
                    totals = totals,
                    commitTotals = commitTotals,
                    focus = focus,
                    focusedTriple = focusedTriple,
                    items = items,
                    pickSet = null,
                    busy = null,
                    toast = null,
                    commitMessage = message,
                    onCommitMessageChange = { message = it },
                    onDismissToast = {},
                    onFocusBlock = { _, _ -> },
                    onFocusFile = {},
                    onStep = { stepped += it },
                    onNextFile = {},
                    onStage = { p, k -> staged += "$p:$k" },
                    onDiscard = { p, k -> discarded += "$p:$k" },
                    onUnstage = { _, _ -> },
                    onUndoDiscard = {},
                    onStageFile = {},
                    onDiscardFile = {},
                    onStageAll = {},
                    onStartPick = { pickStarted = true },
                    onTogglePickLine = {},
                    onSetPick = {},
                    onStagePicked = {},
                    onCommit = { _, _, _ -> },
                    onUndoCommit = {},
                    onOpenFile = {},
                    onResolveConflict = {},
                    onOpenGraph = {},
                )
            }
        }
        compose.waitForIdle()

        // Focus the commit message input and press n, j, k, y, l via hardware keys and text input.
        compose.onNodeWithTag("git-commit-input", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("git-commit-input", useUnmergedTree = true).performKeyInput {
            pressKey(Key.N)
            pressKey(Key.J)
            pressKey(Key.K)
            pressKey(Key.Y)
            pressKey(Key.L)
        }
        compose.onNodeWithTag("git-commit-input", useUnmergedTree = true).performTextInput("no block discarded")
        compose.waitForIdle()

        assertTrue("typing 'n' in commit box must NOT discard focused block", discarded.isEmpty())
        assertTrue("typing 'y' in commit box must NOT stage focused block", staged.isEmpty())
        assertTrue("typing 'j'/'k' in commit box must NOT step blocks", stepped.isEmpty())
        assertTrue("typing 'l' in commit box must NOT start line pick", !pickStarted)
        assertTrue(message.contains("no block discarded"))
    }

    @Test
    fun `resolveFileRepoScope picks deepest nested repo and GitDiffViewer skips gitFileFullDiff for files outside all repos`() {
        val outer = GitRepo("/work/mono/apps", "apps", "apps")
        val inner = GitRepo("/work/mono/apps/web", "web", "apps/web")
        val discovery = GitRepos(rootIsRepo = false, repos = listOf(outer, inner), root = "/work/mono")

        // Deepest prefix match wins regardless of discovery order
        val scoped = resolveFileRepoScope("apps/web/src/App.tsx", discovery, outer)
        assertEquals("src/App.tsx" to inner, scoped)

        // File outside all repos when rootIsRepo == false returns null
        assertNull(resolveFileRepoScope("README.md", discovery, inner))

        val server = MockWebServer()
        val recordedTargets = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target
                synchronized(recordedTargets) { recordedTargets += target }
                return when {
                    target.endsWith("/git/repos") -> MockResponse.Builder()
                        .addHeader("Content-Type", "application/json")
                        .body("""{"ok":true,"data":{"projectPath":"/work/mono","rootIsRepo":false,"repos":[{"path":"/work/mono/apps","name":"apps","relative":"apps"}]}}""")
                        .build()
                    else -> MockResponse.Builder()
                        .addHeader("Content-Type", "application/json")
                        .body("""{"ok":true,"data":{}}""")
                        .build()
                }
            }
        }
        server.start()
        try {
            val model = AppModel(ApplicationProvider.getApplicationContext())
            model.api = ApiClient(server.url("/"), "tok")
            model.selectedProject = "mono"

            compose.setContent {
                Box(Modifier.size(600.dp, 300.dp)) {
                    GitDiffViewer(
                        model = model,
                        projectName = "mono",
                        filePath = "outside/README.md",
                    )
                }
            }
            compose.waitUntil(5_000) {
                synchronized(recordedTargets) { recordedTargets.any { it.endsWith("/git/repos") } }
            }
            compose.waitForIdle()
            val allTargets = synchronized(recordedTargets) { recordedTargets.toList() }
            assertFalse("Expected no /git/file-full-diff for file outside all repos, got $allTargets", allTargets.any { it.contains("/git/file-full-diff") })
        } finally {
            server.close()
        }
    }

    @Test
    fun `GitReviewBody and BranchReviewBody end-to-end with MockWebServer stage hunk and load branch diff`() {
        installBlankFileIcons()
        val server = MockWebServer()
        val stageBodies = mutableListOf<String>()
        val recordedTargets = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target
                synchronized(recordedTargets) { recordedTargets += target }
                return when {
                    target.endsWith("/git/repos") -> MockResponse.Builder()
                        .addHeader("Content-Type", "application/json")
                        .body("""{"ok":true,"data":{"projectPath":"/work/ppm","rootIsRepo":true,"repos":[{"path":"/work/ppm","name":"ppm","relative":"."}]}}""")
                        .build()
                    target.contains("/git/commit-draft") -> MockResponse.Builder()
                        .addHeader("Content-Type", "application/json")
                        .body("""{"ok":true,"data":{"message":"","updatedAt":0}}""")
                        .build()
                    target.contains("/git/changes/file") -> MockResponse.Builder()
                        .addHeader("Content-Type", "application/json")
                        .body(
                            """{"ok":true,"data":{"path":"src/App.kt","staged":null,"unstaged":{"whole":null,"hunks":[{"id":"hunk-0","index":0,"oldStart":10,"oldLines":2,"newStart":10,"newLines":3,"added":2,"removed":1,"header":"@@ -10,2 +10,3 @@","lines":[{"kind":" ","text":"val x = 1"},{"kind":"-","text":"val y = 2"},{"kind":"+","text":"val y = 20"},{"kind":"+","text":"val z = 30"}]}]}}}""",
                        )
                        .build()
                    target.contains("/git/changes") -> MockResponse.Builder()
                        .addHeader("Content-Type", "application/json")
                        .body(
                            """{"ok":true,"data":{"branch":{"head":"feat/review","oid":"abcdef1","upstream":"origin/feat/review","upstreamGone":false,"ahead":1,"behind":0,"hasRemote":true},"operation":null,"files":[{"path":"src/App.kt","oldPath":null,"x":" ","y":"M","untracked":false,"conflict":null,"staged":null,"unstaged":{"status":"M","added":2,"removed":1,"blocks":[{"id":"hunk-0","index":0,"oldStart":10,"oldLines":2,"newStart":10,"newLines":3,"added":2,"removed":1}]}}],"stashes":0,"lastCommit":null,"truncated":false}}""",
                        )
                        .build()
                    target.endsWith("/git/stage-hunks") -> {
                        val b = request.body?.utf8().orEmpty()
                        synchronized(stageBodies) { stageBodies += b }
                        MockResponse.Builder()
                            .addHeader("Content-Type", "application/json")
                            .body("""{"ok":true,"data":{"staged":true}}""")
                            .build()
                    }
                    target.contains("/git/branches") -> MockResponse.Builder()
                        .addHeader("Content-Type", "application/json")
                        .body("""{"ok":true,"data":[{"name":"feat/review","current":true,"remote":false,"tracking":"origin/feat/review","ahead":1,"behind":0,"commit":"abcdef1"},{"name":"main","current":false,"remote":false,"tracking":"origin/main","ahead":0,"behind":0,"commit":"1111111"}]}""")
                        .build()
                    target.contains("/git/branch-diff") -> MockResponse.Builder()
                        .addHeader("Content-Type", "application/json")
                        .body("""{"ok":true,"data":{"base":"main","head":"feat/review","mode":"three-dot","mergeBase":"1111111","headCommit":"abcdef1","omitted":0,"files":[{"path":"src/Feature.kt","status":"M","additions":12,"deletions":3,"binary":false,"blob":"blob-feat"}]}}""")
                        .build()
                    else -> MockResponse.Builder()
                        .addHeader("Content-Type", "application/json")
                        .body("""{"ok":true,"data":{}}""")
                        .build()
                }
            }
        }
        server.start()
        try {
            val model = AppModel(ApplicationProvider.getApplicationContext())
            model.api = ApiClient(server.url("/"), "tok")
            model.selectedProject = "ppm"
            val reviewTab = WorkspaceTab(
                id = "tab-review",
                type = "git-review",
                title = "Review changes",
                projectId = "ppm",
                metadata = buildJsonObject { put("projectName", JsonPrimitive("ppm")) },
            )
            val branchTab = WorkspaceTab(
                id = "tab-branch",
                type = "branch-review",
                title = "Branch Review",
                projectId = "ppm",
                metadata = buildJsonObject {
                    put("projectName", JsonPrimitive("ppm"))
                    put("base", JsonPrimitive("main"))
                },
            )

            compose.setContent {
                Column {
                    Box(Modifier.size(900.dp, 500.dp)) {
                        GitReviewBody(model = model, tab = reviewTab)
                    }
                    Box(Modifier.size(900.dp, 400.dp)) {
                        BranchReviewBody(model = model, tab = branchTab)
                    }
                }
            }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("git-review-block-stage-u:hunk-0", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithTag("branch-review-file-src/Feature.kt", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()

            // Stage the open block in GitReviewBody -> sends POST /git/stage-hunks with {"path":"src/App.kt","hunks":[{"hunk":0,"id":"hunk-0"}]}
            compose.onNodeWithTag("git-review-block-stage-u:hunk-0", useUnmergedTree = true).performClick()
            compose.waitUntil(5_000) {
                synchronized(stageBodies) { stageBodies.isNotEmpty() }
            }
            val stageBody = synchronized(stageBodies) { stageBodies.first() }
            assertTrue("Expected stage-hunks body to include path and hunk-0, got $stageBody", stageBody.contains("\"path\":\"src/App.kt\"") && stageBody.contains("\"id\":\"hunk-0\""))

            // BranchReviewBody renders the diffed file row
            compose.onNodeWithTag("branch-review-file-src/Feature.kt", useUnmergedTree = true).assertIsDisplayed()
        } finally {
            server.close()
        }
    }
}
