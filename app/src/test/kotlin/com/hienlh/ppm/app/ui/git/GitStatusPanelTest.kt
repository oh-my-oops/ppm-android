package com.hienlh.ppm.app.ui.git

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.files.ExplorerTreeRow
import com.hienlh.ppm.app.ui.files.installBlankFileIcons
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
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
                    onOpen = {},
                    onOpenDiff = {},
                    onOpenFile = {},
                    onDiscard = { _, _ -> },
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
}
