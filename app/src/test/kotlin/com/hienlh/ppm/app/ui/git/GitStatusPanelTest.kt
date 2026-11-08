package com.hienlh.ppm.app.ui.git

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.files.ExplorerTreeRow
import com.hienlh.ppm.app.ui.files.installBlankFileIcons
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.ChangeBlock
import com.hienlh.ppm.client.ChangeSide
import com.hienlh.ppm.client.ChangedFile
import com.hienlh.ppm.client.GitBranchState
import com.hienlh.ppm.client.GitOperation
import com.hienlh.ppm.client.LastCommit
import com.hienlh.ppm.state.files.FileTreeNode
import com.hienlh.ppm.state.files.FileTreeRow
import com.hienlh.ppm.state.git.GitChangesView
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
@Config(sdk = [34], qualifiers = "w800dp-h1200dp-xxhdpi")
class GitStatusPanelTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(compose)

    @Test
    fun `explorer tree row renders git status letter for files and dot for folders`() {
        installBlankFileIcons()
        val fileNode = FileTreeNode("Main.kt", "src/Main.kt", isDirectory = false, isIgnored = false)
        val dirNode = FileTreeNode("src", "src", isDirectory = true, isIgnored = false)

        compose.setContent {
            Column {
                ExplorerTreeRow(
                    row = FileTreeRow(fileNode, depth = 1),
                    expanded = false,
                    loading = false,
                    selected = false,
                    focused = false,
                    onClick = { _, _ -> },
                    onMenu = {},
                    gitStatus = "M",
                )
                ExplorerTreeRow(
                    row = FileTreeRow(dirNode, depth = 0),
                    expanded = true,
                    loading = false,
                    selected = false,
                    focused = false,
                    onClick = { _, _ -> },
                    onMenu = {},
                    gitStatus = "A",
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("explorer-git-status-src/Main.kt", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("explorer-git-dot-src", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `branch row, operation banner, commit composer, and clean state trigger actions`() {
        var pickerOpened = false
        var syncClicked = false
        var abortClicked = false
        var continueClicked = false
        var draftText = ""
        var committed = false
        var cleanUndone = false

        val branch = GitBranchState(
            head = "feat/source-control",
            oid = "1234567890abcdef",
            upstream = "origin/feat/source-control",
            upstreamGone = false,
            ahead = 2,
            behind = 0,
        )
        val operation = GitOperation(kind = "rebase", step = 1, total = 3)
        val lastCommit = LastCommit(
            hash = "abcdef123456",
            shortHash = "abcdef1",
            subject = "feat: add git status panel",
            date = "2026-11-07T10:00:00Z",
            pushed = false,
            hasParent = true,
        )
        val files = listOf(
            ChangedFile(
                path = "src/App.kt",
                staged = ChangeSide(status = "M", added = 12, removed = 3, blocks = listOf("a")),
            ),
        )
        val totals = GitChangesView.changeTotals(files)

        compose.setContent {
            Column {
                GitBranchRow(
                    branch = branch,
                    operation = null,
                    fileCount = files.size,
                    busyAction = null,
                    onOpenPicker = { pickerOpened = true },
                    onSync = { syncClicked = true },
                )
                GitOperationBanner(
                    operation = operation,
                    branch = branch,
                    conflicts = 0,
                    busyAction = null,
                    onContinue = { continueClicked = true },
                    onAbort = { abortClicked = true },
                )
                GitCommitComposer(
                    message = "feat: initial draft",
                    onMessageChange = { draftText = it },
                    totals = totals,
                    operation = null,
                    lastCommit = lastCommit,
                    syncMode = GitChangesView.syncMode(branch),
                    busyAction = null,
                    onCommit = { _, _, _ -> committed = true },
                    onUndoLast = {},
                )
                CleanState(
                    last = lastCommit,
                    operation = null,
                    busy = false,
                    onUndo = { cleanUndone = true },
                    onOpenHistory = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("git-branch-row-button").performClick()
        assertTrue(pickerOpened)

        compose.onNodeWithTag("git-sync-button").performClick()
        assertTrue(syncClicked)

        compose.onNodeWithText("Abort rebase").performClick()
        assertTrue(abortClicked)

        compose.onNodeWithText("Continue").performClick()
        assertTrue(continueClicked)

        compose.onNodeWithTag("git-commit-input").performTextInput(" updated")
        assertTrue(draftText.contains("updated"))

        compose.onNodeWithTag("git-commit-button").performClick()
        assertTrue(committed)

        compose.onNodeWithTag("git-undo-last-commit").performClick()
        assertTrue(cleanUndone)
    }

    @Test
    fun `change group section renders flat rows and toggles file and group check state`() {
        installBlankFileIcons()
        val model = AppModel(ApplicationProvider.getApplicationContext())
        val files = listOf(
            ChangedFile(
                path = "src/ui/GitStatusPanel.kt",
                staged = ChangeSide(status = "A", added = 50, removed = 0, blocks = listOf("a")),
            ),
            ChangedFile(
                path = "src/ui/GitChangeParts.kt",
                unstaged = ChangeSide(status = "M", added = 10, removed = 2, blocks = listOf("m")),
            ),
        )
        var toggledFile: String? = null
        var toggledBatchCount = 0
        var reviewedAll = false

        compose.setContent {
            Column {
                ChangeGroupSection(
                    model = model,
                    label = "Changes",
                    files = files,
                    errorTone = false,
                    showCheckAll = true,
                    viewMode = "flat",
                    onToggleFile = { toggledFile = it.path },
                    onToggleBatch = { toggledBatchCount = it.size },
                    onStageFile = {},
                    onUnstageFile = {},
                    onOpen = {},
                    onOpenDiff = {},
                    onOpenFile = {},
                    onDiscard = {},
                    onStageLines = { _, _ -> },
                    onMarkResolved = {},
                    onReviewAll = { reviewedAll = true },
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("GitStatusPanel.kt").assertIsDisplayed()
        compose.onNodeWithText("GitChangeParts.kt").assertIsDisplayed()

        compose.onNodeWithTag("git-change-check-src/ui/GitChangeParts.kt").performClick()
        assertEquals("src/ui/GitChangeParts.kt", toggledFile)

        compose.onNodeWithTag("git-group-check-all-Changes").performClick()
        assertEquals(2, toggledBatchCount)

        compose.onNodeWithText("Review").performClick()
        assertTrue(reviewedAll)
    }

    @Test
    fun `git status panel and branch picker never call changes commit-draft or refs while repo is null and Try again retries loadGitRepos`() {
        val server = MockWebServer()
        val recorded = mutableListOf<String>()
        var reposShouldSucceed = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target
                synchronized(recorded) { recorded += target }
                return when {
                    target.endsWith("/git/repos") -> {
                        if (!reposShouldSucceed) {
                            MockResponse.Builder()
                                .code(500)
                                .addHeader("Content-Type", "application/json")
                                .body("""{"ok":false,"error":"repo scan failed"}""")
                                .build()
                        } else {
                            MockResponse.Builder()
                                .addHeader("Content-Type", "application/json")
                                .body(
                                    """{"ok":true,"data":{"projectPath":"/work/app","rootIsRepo":true,"repos":[{"path":"/work/app","name":"app","relative":"."}]}}""",
                                )
                                .build()
                        }
                    }
                    target.contains("/git/changes") -> {
                        MockResponse.Builder()
                            .addHeader("Content-Type", "application/json")
                            .body(
                                """{"ok":true,"data":{"branch":{"head":"main","oid":"1111111","upstream":"origin/main","upstreamGone":false,"ahead":0,"behind":0,"hasRemote":true},"operation":null,"files":[],"stashes":0,"lastCommit":null,"truncated":false}}""",
                            )
                            .build()
                    }
                    target.contains("/git/commit-draft") -> {
                        MockResponse.Builder()
                            .addHeader("Content-Type", "application/json")
                            .body("""{"ok":true,"data":{"message":"","updatedAt":0}}""")
                            .build()
                    }
                    else -> {
                        MockResponse.Builder()
                            .addHeader("Content-Type", "application/json")
                            .body("""{"ok":true,"data":{}}""")
                            .build()
                    }
                }
            }
        }
        server.start()
        try {
            val model = AppModel(ApplicationProvider.getApplicationContext())
            model.api = ApiClient(server.url("/"), "tok")
            model.selectedProject = "demo"

            compose.setContent {
                Column {
                    GitStatusPanel(model = model)
                    GitBranchPickerModal(
                        model = model,
                        project = "demo",
                        repo = null,
                        onClose = {},
                        onChanged = {},
                    )
                }
            }
            compose.waitUntil(5_000) {
                synchronized(recorded) { recorded.any { it.endsWith("/git/repos") } }
            }
            compose.waitForIdle()

            // While repo == null, "Looking for a repository..." is shown and no /git/changes, /git/commit-draft, or /git/refs is called.
            compose.onNodeWithText("Looking for a repository...").assertIsDisplayed()
            val targetsBefore = synchronized(recorded) { recorded.toList() }
            assertFalse("Expected no /git/changes while repo == null, got $targetsBefore", targetsBefore.any { it.contains("/git/changes") })
            assertFalse("Expected no /git/commit-draft while repo == null, got $targetsBefore", targetsBefore.any { it.contains("/git/commit-draft") })
            assertFalse("Expected no /git/refs while repo == null, got $targetsBefore", targetsBefore.any { it.contains("/git/refs") })

            // Clicking "Try again" re-runs loadGitRepos(force = true), resolves repo, and then loads /git/changes.
            reposShouldSucceed = true
            compose.onNodeWithTag("git-try-again").performClick()
            compose.waitUntil(5_000) {
                synchronized(recorded) { recorded.any { it.contains("/git/changes") } }
            }
            val targetsAfter = synchronized(recorded) { recorded.toList() }
            assertEquals(2, targetsAfter.count { it.endsWith("/git/repos") })
            assertTrue(targetsAfter.any { it.contains("/git/changes") })
        } finally {
            server.close()
        }
    }

    @Test
    fun `git status panel unstages partly-staged file via Unstage file menu and handles null stash and mixed discard undo+skipped`() {
        installBlankFileIcons()
        val server = MockWebServer()
        val recordedMethodsAndTargets = mutableListOf<Pair<String, String>>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target
                synchronized(recordedMethodsAndTargets) {
                    recordedMethodsAndTargets += (request.method ?: "GET") to target
                }
                return when {
                    target.endsWith("/git/repos") -> MockResponse.Builder()
                        .addHeader("Content-Type", "application/json")
                        .body("""{"ok":true,"data":{"projectPath":"/work/app","rootIsRepo":true,"repos":[{"path":"/work/app","name":"app","relative":"."}]}}""")
                        .build()
                    target.contains("/git/changes") -> MockResponse.Builder()
                        .addHeader("Content-Type", "application/json")
                        .body(
                            """{"ok":true,"data":{"branch":{"head":"main","oid":"1111111","upstream":"origin/main","upstreamGone":false,"ahead":0,"behind":0,"hasRemote":true},"operation":null,"files":[{"path":"src/Partial.kt","oldPath":null,"x":"M","y":"M","untracked":false,"conflict":null,"staged":{"status":"M","added":2,"removed":0,"blocks":[{"id":"b1","index":0,"oldStart":1,"oldLines":0,"newStart":1,"newLines":2,"added":2,"removed":0}]},"unstaged":{"status":"M","added":3,"removed":1,"blocks":[{"id":"b2","index":0,"oldStart":10,"oldLines":1,"newStart":12,"newLines":3,"added":3,"removed":1}]}}],"stashes":0,"lastCommit":null,"truncated":false}}""",
                        )
                        .build()
                    target.contains("/git/commit-draft") -> MockResponse.Builder()
                        .addHeader("Content-Type", "application/json")
                        .body("""{"ok":true,"data":{"message":"","updatedAt":0}}""")
                        .build()
                    target.endsWith("/git/unstage") -> MockResponse.Builder()
                        .addHeader("Content-Type", "application/json")
                        .body("""{"ok":true,"data":{"unstaged":true}}""")
                        .build()
                    target.endsWith("/git/stash") -> MockResponse.Builder()
                        .addHeader("Content-Type", "application/json")
                        .body("""{"ok":true,"data":{"stashed":false,"stash":null}}""")
                        .build()
                    target.endsWith("/git/discard") -> MockResponse.Builder()
                        .addHeader("Content-Type", "application/json")
                        .body("""{"ok":true,"data":{"undo":{"id":"undo-99","createdAt":1700000000,"paths":["src/Partial.kt"],"skipped":["assets/huge.bin"]}}}""")
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
            model.selectedProject = "demo"

            var isMobile by androidx.compose.runtime.mutableStateOf(true)
            compose.setContent {
                androidx.compose.runtime.CompositionLocalProvider(com.hienlh.ppm.design.LocalPpmMobile provides isMobile) {
                    GitStatusPanel(model = model)
                }
            }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("git-change-row-src/Partial.kt").fetchSemanticsNodes().isNotEmpty()
            }

            // Open row bottom-sheet menu on partly-staged file via long-click and click "Unstage file" -> must call /git/unstage, NOT /git/stage
            compose.onNodeWithTag("git-change-row-src/Partial.kt").performTouchInput {
                down(center)
                advanceEventTime(1_000L)
                up()
            }
            compose.waitForIdle()
            compose.onNodeWithText("Unstage file").performClick()
            compose.waitUntil(5_000) {
                synchronized(recordedMethodsAndTargets) {
                    recordedMethodsAndTargets.any { it.second.endsWith("/git/unstage") } &&
                        recordedMethodsAndTargets.count { it.second.contains("/git/changes") } >= 2
                }
            }
            compose.waitForIdle()
            val afterUnstage = synchronized(recordedMethodsAndTargets) { recordedMethodsAndTargets.toList() }
            assertFalse("Unstage file on partly-staged file must not call /git/stage", afterUnstage.any { it.second.endsWith("/git/stage") })

            // Click More -> wait until busyAction clears so "Stash all changes" is enabled -> click -> shows "Nothing was stashed" toast
            compose.onNodeWithTag("git-more-button").performClick()
            compose.waitUntil(5_000) {
                runCatching {
                    compose.onNodeWithText("Stash all changes").assertIsEnabled()
                    true
                }.getOrDefault(false)
            }
            compose.onNodeWithText("Stash all changes").performClick()
            compose.waitUntil(5_000) {
                synchronized(recordedMethodsAndTargets) { recordedMethodsAndTargets.any { it.second.endsWith("/git/stash") } }
            }
            compose.waitUntil(5_000) {
                model.toasts.any { it.message.title == "Nothing was stashed" }
            }
            val stashToast = model.toasts.firstOrNull { it.message.title == "Nothing was stashed" }
            assertTrue("Expected 'Nothing was stashed' toast, got ${model.toasts.map { it.message }}", stashToast != null)

            // Switch to tablet/desktop mode (`isMobile = false`) and click Discard row tool -> confirm dialog uses DiscardSummary
            isMobile = false
            compose.waitForIdle()
            compose.onNodeWithTag("git-row-discard-src/Partial.kt").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Discard changes to Partial.kt?").assertIsDisplayed()
            compose.onNodeWithText("This puts 1 unstaged block back to the staged version. The 1 staged block stays. You can undo it right after.").assertIsDisplayed()
            compose.onNodeWithText("Discard 1 block").assertIsDisplayed()
            compose.onNodeWithTag("git-confirm-ok").performClick()
            compose.waitUntil(5_000) {
                synchronized(recordedMethodsAndTargets) { recordedMethodsAndTargets.any { it.second.endsWith("/git/discard") } }
            }
            compose.waitUntil(5_000) {
                model.toasts.any { it.message.title == "Discarded changes to Partial.kt" }
            }
            val toasts = model.toasts.map { it.message }
            assertTrue("Expected Undo toast 'Discarded changes to Partial.kt', got $toasts", toasts.any { it.title == "Discarded changes to Partial.kt" && it.action?.label == "Undo" })
            assertTrue("Expected warning toast for skipped file, got $toasts", toasts.any { it.title.contains("could not be kept") })
        } finally {
            server.close()
        }
    }

    @Test
    fun `BlockDots draws first 6 dots and +N-6 overflow like git-change-parts tsx`() {
        val fileWith8Blocks = ChangedFile(
            path = "src/EightBlocks.kt",
            staged = ChangeSide(
                status = "M",
                added = 2,
                removed = 0,
                blocks = listOf("s0", "s1"),
                changeBlocks = listOf(
                    ChangeBlock("s0", 0, 5, 1, 5, 2, 1, 0),
                    ChangeBlock("s1", 1, 25, 1, 25, 2, 1, 0),
                ),
            ),
            unstaged = ChangeSide(
                status = "M",
                added = 6,
                removed = 0,
                blocks = emptyList(),
                changeBlocks = (0 until 6).map { idx ->
                    ChangeBlock("u$idx", idx, idx * 10, 1, idx * 10, 2, 1, 0)
                },
            ),
        )
        compose.setContent {
            BlockDots(fileWith8Blocks)
        }
        compose.waitForIdle()
        // First 6 dots (0..5) are drawn; dot 6 is not; overflow text is +2
        for (i in 0 until 6) {
            val state = if (i == 1 || i == 4) "on" else "off"
            compose.onNodeWithTag("git-block-dot-src/EightBlocks.kt-$i-$state", useUnmergedTree = true).assertIsDisplayed()
        }
        assertTrue(compose.onAllNodesWithTag("git-block-dot-src/EightBlocks.kt-6-off", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("+2", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `gitChangesCache is keyed per repo and GitStatusPanel refreshes on failed write visibility and Fetch and shows poll-error banner`() {
        installBlankFileIcons()
        val server = MockWebServer()
        val calls = mutableListOf<Pair<String, String>>()
        var changesBehind = 0
        var failChanges = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val method = request.method ?: "GET"
                val target = request.target
                synchronized(calls) { calls += method to target }
                return when {
                    target.endsWith("/git/repos") -> MockResponse.Builder()
                        .addHeader("Content-Type", "application/json")
                        .body("""{"ok":true,"data":{"projectPath":"/work/app","rootIsRepo":false,"repos":[{"path":"/work/app/a","name":"a","relative":"a"},{"path":"/work/app/b","name":"b","relative":"b"}]}}""")
                        .build()
                    target.contains("/git/changes") -> {
                        if (failChanges) {
                            MockResponse.Builder()
                                .code(500)
                                .addHeader("Content-Type", "application/json")
                                .body("""{"ok":false,"error":"git status poll failed"}""")
                                .build()
                        } else {
                            val fileName = if (target.contains("repo=%2Fwork%2Fapp%2Fb")) "src/B.kt" else "src/A.kt"
                            MockResponse.Builder()
                                .addHeader("Content-Type", "application/json")
                                .body(
                                    """{"ok":true,"data":{"branch":{"head":"main","oid":"1111111","upstream":"origin/main","upstreamGone":false,"ahead":0,"behind":$changesBehind,"hasRemote":true},"operation":null,"files":[{"path":"$fileName","oldPath":null,"x":" ","y":"M","untracked":false,"conflict":null,"staged":null,"unstaged":{"status":"M","added":1,"removed":0,"blocks":[{"id":"b1","index":0,"oldStart":1,"oldLines":1,"newStart":1,"newLines":2,"added":1,"removed":0}]}}],"stashes":0,"lastCommit":null,"truncated":false}}""",
                                )
                                .build()
                        }
                    }
                    target.contains("/git/commit-draft") -> MockResponse.Builder()
                        .addHeader("Content-Type", "application/json")
                        .body("""{"ok":true,"data":{"message":"","updatedAt":0}}""")
                        .build()
                    target.contains("/git/stage") -> MockResponse.Builder()
                        .code(500)
                        .addHeader("Content-Type", "application/json")
                        .body("""{"ok":false,"error":"index.lock exists"}""")
                        .build()
                    target.contains("/git/fetch") -> {
                        changesBehind = 2
                        MockResponse.Builder()
                            .addHeader("Content-Type", "application/json")
                            .body("""{"ok":true,"data":{}}""")
                            .build()
                    }
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
            model.selectedProject = "demo"
            model.chooseGitRepo("demo", "/work/app/a")

            compose.setContent {
                androidx.compose.runtime.CompositionLocalProvider(com.hienlh.ppm.design.LocalPpmMobile provides true) {
                    GitStatusPanel(model = model)
                }
            }
            compose.waitUntil(5_000) {
                model.cachedGitChanges("demo", "/work/app/a") != null
            }
            compose.waitForIdle()
            assertEquals("src/A.kt", model.cachedGitChanges("demo", "/work/app/a")?.files?.single()?.path)
            assertNull(model.cachedGitChanges("demo", "/work/app/b"))

            // Switch to repo B -> separate cache entry for repo B, repo A stays intact
            model.chooseGitRepo("demo", "/work/app/b")
            compose.waitUntil(5_000) {
                model.cachedGitChanges("demo", "/work/app/b") != null
            }
            compose.waitForIdle()
            assertEquals("src/A.kt", model.cachedGitChanges("demo", "/work/app/a")?.files?.single()?.path)
            assertEquals("src/B.kt", model.cachedGitChanges("demo", "/work/app/b")?.files?.single()?.path)

            // Failed write (stage returns 500) still refreshes in finally; make that refresh fail so poll-error banner shows
            val changesReadsBeforeFail = synchronized(calls) { calls.count { it.second.contains("/git/changes") } }
            failChanges = true
            compose.onNodeWithTag("git-change-check-src/B.kt").performClick()
            compose.waitUntil(5_000) {
                synchronized(calls) { calls.any { it.second.contains("/git/stage") } } &&
                    synchronized(calls) { calls.count { it.second.contains("/git/changes") } > changesReadsBeforeFail } &&
                    compose.onAllNodesWithTag("git-poll-error-banner").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("git-poll-error-banner").assertIsDisplayed()

            // Visibility transition false -> true triggers refresh and clears poll-error banner
            failChanges = false
            val readsBeforeVisible = synchronized(calls) { calls.count { it.second.contains("/git/changes") } }
            model.windowVisible = false
            compose.waitForIdle()
            model.windowVisible = true
            compose.waitUntil(5_000) {
                synchronized(calls) { calls.count { it.second.contains("/git/changes") } > readsBeforeVisible } &&
                    compose.onAllNodesWithTag("git-poll-error-banner").fetchSemanticsNodes().isEmpty()
            }
            assertTrue(compose.onAllNodesWithTag("git-poll-error-banner").fetchSemanticsNodes().isEmpty())

            // More -> Fetch runs loadChanges() and shows Fetched — 2 new commits to pull
            compose.onNodeWithTag("git-more-button").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Fetch").performClick()
            compose.waitUntil(5_000) {
                model.toasts.any { it.message.title == "Fetched — 2 new commits to pull" }
            }
            assertEquals(2, model.cachedGitChanges("demo", "/work/app/b")?.branch?.behind)
        } finally {
            server.close()
        }
    }
}
