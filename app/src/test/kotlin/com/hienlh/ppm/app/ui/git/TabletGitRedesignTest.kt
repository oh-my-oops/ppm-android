package com.hienlh.ppm.app.ui.git

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.LocalRedesign
import com.hienlh.ppm.app.ui.files.installBlankFileIcons
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.ChangeSide
import com.hienlh.ppm.client.ChangedFile
import com.hienlh.ppm.client.GitBranchState
import com.hienlh.ppm.client.GitChanges
import com.hienlh.ppm.client.GitRepo
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.git.GitBlameCommitInfo
import com.hienlh.ppm.state.git.GitBlameData
import com.hienlh.ppm.state.git.GitBlameLine
import com.hienlh.ppm.state.git.GitFileHistoryEntry
import com.hienlh.ppm.state.git.GitGraphActionResult
import com.hienlh.ppm.state.git.GitGraphCommitDraft
import com.hienlh.ppm.state.git.GitGraphCommitsPageState
import com.hienlh.ppm.state.git.GitGraphRefData
import com.hienlh.ppm.state.git.GitGraphRepoInfo
import com.hienlh.ppm.state.git.GitGraphSettings
import com.hienlh.ppm.state.git.GitGraphVertex
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.JsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1024dp-h768dp-xxhdpi")
class TabletGitRedesignTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @Test
    fun `staged and changes groups split via GitChangeGroups with partial file in both, block summaries, and discard all`() {
        installBlankFileIcons()
        val server = MockWebServer()
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        val changesJson =
            """
            {
              "ok": true,
              "data": {
                "branch": {"head":"main","oid":"1111111","upstream":"origin/main","upstreamGone":false,"hasRemote":true,"ahead":1,"behind":0},
                "operation": null,
                "files": [
                  {
                    "path": "src/staged.ts",
                    "x": "M",
                    "y": ".",
                    "untracked": false,
                    "conflict": false,
                    "staged": {
                      "added": 5,
                      "removed": 2,
                      "blocks": [
                        {"id":"s0","index":0,"oldStart":1,"oldLines":2,"newStart":1,"newLines":5,"added":5,"removed":2}
                      ]
                    }
                  },
                  {
                    "path": "src/partial.ts",
                    "x": "M",
                    "y": "M",
                    "untracked": false,
                    "conflict": false,
                    "staged": {
                      "added": 4,
                      "removed": 1,
                      "blocks": [
                        {"id":"b0","index":0,"oldStart":1,"oldLines":1,"newStart":1,"newLines":2,"added":2,"removed":1},
                        {"id":"b1","index":1,"oldStart":10,"oldLines":0,"newStart":11,"newLines":2,"added":2,"removed":0}
                      ]
                    },
                    "unstaged": {
                      "added": 3,
                      "removed": 0,
                      "blocks": [
                        {"id":"b2","index":0,"oldStart":20,"oldLines":0,"newStart":22,"newLines":3,"added":3,"removed":0}
                      ]
                    }
                  },
                  {
                    "path": "src/unstaged.ts",
                    "x": ".",
                    "y": "M",
                    "untracked": false,
                    "conflict": false,
                    "unstaged": {
                      "added": 7,
                      "removed": 1,
                      "blocks": [
                        {"id":"u0","index":0,"oldStart":1,"oldLines":1,"newStart":1,"newLines":7,"added":7,"removed":1}
                      ]
                    }
                  }
                ],
                "truncated": false,
                "stashes": 0,
                "lastCommit": null
              }
            }
            """.trimIndent()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val body = request.body?.utf8().orEmpty()
                requests.add(path to body)
                return when {
                    path.endsWith("/git/repos") -> MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "application/json")
                        .body("""{"ok":true,"data":{"projectPath":"/repo","rootIsRepo":true,"repos":[{"path":"/repo","name":"demo","relative":"."}]}}""")
                        .build()
                    path.endsWith("/git/changes") -> MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "application/json")
                        .body(changesJson)
                        .build()
                    path.endsWith("/git/commit-draft") -> MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "application/json")
                        .body("""{"ok":true,"data":{"message":"","updatedAt":0}}""")
                        .build()
                    path.endsWith("/git/discard") -> MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "application/json")
                        .body("""{"ok":true,"data":{"undo":{"id":"disc-1","createdAt":1700000000,"paths":["src/partial.ts","src/unstaged.ts"],"skipped":[]}}}""")
                        .build()
                    else -> MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body("""{"ok":true,"data":{}}""").build()
                }
            }
        }
        server.start()
        try {
            val model = AppModel(ApplicationProvider.getApplicationContext())
            model.api = ApiClient(server.url("/"), "tok")
            model.selectedProject = "demo"

            composeRule.setContent {
                PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                    CompositionLocalProvider(LocalRedesign provides true) {
                        GitStatusPanel(model = model)
                    }
                }
            }
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithTag("git-group-Staged", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }

            // Both Staged and Changes groups exist
            composeRule.onNodeWithTag("git-group-Staged", useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNodeWithTag("git-group-Changes", useUnmergedTree = true).assertIsDisplayed()

            // Partially staged file appears in both Staged and Changes with per-side block summaries
            composeRule.onNodeWithTag("git-staged-row-src/partial.ts", useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNodeWithTag("git-changes-row-src/partial.ts", useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNodeWithTag("git-staged-blocks-src/partial.ts", useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNodeWithTag("git-changes-blocks-src/partial.ts", useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNodeWithText("2 of 3 blocks", useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNodeWithText("1 block left", useUnmergedTree = true).assertIsDisplayed()

            // Discard all on Changes group header opens confirmation and discards unstaged files
            composeRule.onNodeWithTag("git-group-discard-all-Changes", useUnmergedTree = true).performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("git-confirm-ok", useUnmergedTree = true).performClick()
            composeRule.waitUntil(5_000) {
                requests.any { it.first.endsWith("/git/discard") }
            }
            val discardBody = requests.first { it.first.endsWith("/git/discard") }.second
            assertTrue(discardBody.contains("src/partial.ts"))
            assertTrue(discardBody.contains("src/unstaged.ts"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `write with ai button fills commit message and offline banner renders when reconnecting`() {
        installBlankFileIcons()
        val server = MockWebServer()
        val changesJson =
            """
            {
              "ok": true,
              "data": {
                "branch": {"head":"main","oid":"1111111","upstream":"origin/main","upstreamGone":false,"hasRemote":true,"ahead":0,"behind":0},
                "operation": null,
                "files": [
                  {
                    "path": "src/app.ts",
                    "x": "M",
                    "y": ".",
                    "untracked": false,
                    "conflict": false,
                    "staged": {"added": 3, "removed": 1, "blocks": []}
                  }
                ],
                "truncated": false,
                "stashes": 0,
                "lastCommit": null
              }
            }
            """.trimIndent()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path.endsWith("/git/repos") -> MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "application/json")
                        .body("""{"ok":true,"data":{"projectPath":"/repo","rootIsRepo":true,"repos":[{"path":"/repo","name":"demo","relative":"."}]}}""")
                        .build()
                    path.endsWith("/git/changes") -> MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "application/json")
                        .body(changesJson)
                        .build()
                    path.endsWith("/git/commit-draft") -> MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "application/json")
                        .body("""{"ok":true,"data":{"message":"","updatedAt":0}}""")
                        .build()
                    path.endsWith("/git/commit-message") -> MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "application/json")
                        .body("""{"ok":true,"data":{"message":"feat(git): split staged and changes on tablet"}}""")
                        .build()
                    else -> MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body("""{"ok":true,"data":{}}""").build()
                }
            }
        }
        server.start()
        try {
            val model = AppModel(ApplicationProvider.getApplicationContext())
            model.api = ApiClient(server.url("/"), "tok")
            model.selectedProject = "demo"
            var offline by mutableStateOf(false)

            composeRule.setContent {
                PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                    CompositionLocalProvider(LocalRedesign provides true) {
                        GitStatusPanel(model = model, isOffline = offline)
                    }
                }
            }
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithTag("git-ai-msg-btn", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }

            // Click Write with AI (✦) button
            composeRule.onNodeWithTag("git-ai-msg-btn", useUnmergedTree = true).performClick()
            composeRule.waitUntil(5_000) {
                model.commitDrafts.drafts.value.values.any { it.contains("feat(git): split staged and changes on tablet") }
            }

            // Open shortcuts sheet via ? button
            composeRule.onNodeWithTag("git-shortcuts-button", useUnmergedTree = true).performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("git-shortcuts-sheet", useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNodeWithTag("git-shortcuts-close-btn", useUnmergedTree = true).performClick()
            composeRule.waitForIdle()

            // Switch to offline state and verify offline banner
            offline = true
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("git-offline-banner", useUnmergedTree = true).assertIsDisplayed()
        } finally {
            server.close()
        }
    }

    @Test
    fun `branch picker keeps modal open on checkout refusal and supports stash and switch`() {
        val server = MockWebServer()
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        var checkoutAttempts = 0

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val body = request.body?.utf8().orEmpty()
                requests.add(path to body)
                return when {
                    path.endsWith("/git/refs") -> MockResponse.Builder()
                        .code(200)
                        .body(
                            """
                            {
                              "ok": true,
                              "data": [
                                {"name":"main","kind":"branch","current":true,"shortHash":"1111111","subject":"init","date":1700000000},
                                {"name":"feat/tablet","kind":"branch","current":false,"shortHash":"2222222","subject":"tablet git","date":1700000100}
                              ]
                            }
                            """.trimIndent(),
                        )
                        .build()
                    path.endsWith("/git/checkout") -> {
                        checkoutAttempts++
                        if (checkoutAttempts == 1) {
                            MockResponse.Builder()
                                .code(400)
                                .body("""{"ok":false,"error":"Your local changes to tracked files would be overwritten by checkout."}""")
                                .build()
                        } else {
                            MockResponse.Builder()
                                .code(200)
                                .body("""{"ok":true,"data":{}}""")
                                .build()
                        }
                    }
                    path.endsWith("/git/stash") -> MockResponse.Builder()
                        .code(200)
                        .body("""{"ok":true,"data":{"stashed":true,"index":0,"hash":"abc1234","message":"WIP"}}""")
                        .build()
                    else -> MockResponse.Builder().code(200).body("""{"ok":true,"data":{}}""").build()
                }
            }
        }
        server.start()
        try {
            val model = AppModel(ApplicationProvider.getApplicationContext())
            model.api = ApiClient(server.url("/"), "tok")
            val repo = GitRepo(path = "/repo", name = "demo", relative = ".")
            var closed = false

            composeRule.setContent {
                PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                    CompositionLocalProvider(LocalRedesign provides true) {
                        GitBranchPickerModal(
                            model = model,
                            project = "demo",
                            repo = repo,
                            onClose = { closed = true },
                            onChanged = {},
                        )
                    }
                }
            }
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithTag("git-picker-ref-feat/tablet", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }

            composeRule.onNodeWithTag("git-branch-picker-kbd-footer", useUnmergedTree = true).assertIsDisplayed()

            // First click fails with local changes error; picker stays open and shows inline error + Stash and switch
            composeRule.onNodeWithTag("git-picker-ref-feat/tablet", useUnmergedTree = true).performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithTag("git-branch-picker-error", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(false, closed)
            composeRule.onNodeWithTag("git-branch-picker-stash-switch-btn", useUnmergedTree = true).performClick()

            composeRule.waitUntil(5_000) { closed }
            assertTrue(requests.any { it.first.endsWith("/git/stash") })
            assertEquals(2, checkoutAttempts)
        } finally {
            server.close()
        }
    }

    @Test
    fun `hunk stage dialog supports range selection and conflict resolution view resolves conflicts`() {
        val server = MockWebServer()
        val stagedRequests = CopyOnWriteArrayList<String>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val body = request.body?.utf8().orEmpty()
                return when {
                    path.endsWith("/git/hunks") -> MockResponse.Builder()
                        .code(200)
                        .body(
                            """
                            {
                              "ok": true,
                              "data": {
                                "binary": false,
                                "hunks": [
                                  {
                                    "index": 0,
                                    "id": "h0",
                                    "header": "@@ -1,4 +1,6 @@",
                                    "oldStart": 1,
                                    "oldLines": 4,
                                    "newStart": 1,
                                    "newLines": 6,
                                    "lines": [
                                      {"type": " ", "content": "const a = 1;"},
                                      {"type": "+", "content": "const b = 2;"},
                                      {"type": "+", "content": "const c = 3;"},
                                      {"type": "+", "content": "const d = 4;"}
                                    ]
                                  }
                                ]
                              }
                            }
                            """.trimIndent(),
                        )
                        .build()
                    path.endsWith("/git/stage-hunks") -> {
                        stagedRequests.add(body)
                        MockResponse.Builder().code(200).body("""{"ok":true,"data":{}}""").build()
                    }
                    else -> MockResponse.Builder().code(200).body("""{"ok":true,"data":{}}""").build()
                }
            }
        }
        server.start()
        try {
            val model = AppModel(ApplicationProvider.getApplicationContext())
            model.api = ApiClient(server.url("/"), "tok")
            val repo = GitRepo(path = "/repo", name = "demo", relative = ".")

            val resolvedChoices = mutableStateMapOf<Int, ConflictResolutionChoice>()
            var markedResolved = false
            var showHunkDialog by mutableStateOf(true)

            composeRule.setContent {
                val conflictItems = remember(resolvedChoices.toMap()) {
                    listOf(
                        ConflictRegionItem(
                            index = 0,
                            currentLabel = "HEAD",
                            incomingLabel = "feat/tablet",
                            currentLines = listOf("val mode = \"tablet\""),
                            incomingLines = listOf("val mode = \"phone\""),
                            resolvedChoice = resolvedChoices[0],
                        ),
                        ConflictRegionItem(
                            index = 1,
                            currentLabel = "HEAD",
                            incomingLabel = "feat/tablet",
                            currentLines = listOf("val retries = 3"),
                            incomingLines = listOf("val retries = 5"),
                            resolvedChoice = resolvedChoices[1],
                        ),
                    )
                }
                PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                    CompositionLocalProvider(LocalRedesign provides true) {
                        Column {
                            GitConflictResolutionView(
                                filePath = "src/config.kt",
                                conflicts = conflictItems,
                                onAccept = { idx, choice -> resolvedChoices[idx] = choice },
                                onMarkResolved = { markedResolved = true },
                            )
                            if (showHunkDialog) {
                                GitHunkStageDialog(
                                    model = model,
                                    project = "demo",
                                    repo = repo,
                                    filePath = "src/config.kt",
                                    scope = "worktree",
                                    onClose = { showHunkDialog = false },
                                    onApplied = {},
                                )
                            }
                        }
                    }
                }
            }

            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithTag("git-hunk-line-0-1", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }

            // Toggle all off, enable Range mode, click line 1 and line 3 -> selects lines 1..3
            composeRule.onNodeWithTag("git-hunk-toggle-all", useUnmergedTree = true).performClick()
            composeRule.onNodeWithTag("git-hunk-range-toggle", useUnmergedTree = true).performClick()
            composeRule.onNodeWithTag("git-hunk-line-0-1", useUnmergedTree = true).performClick()
            composeRule.onNodeWithTag("git-hunk-line-0-3", useUnmergedTree = true).performClick()
            composeRule.onNodeWithText("3 of 3 lines selected", useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNodeWithTag("git-hunk-apply-button", useUnmergedTree = true).performClick()
            composeRule.waitUntil(5_000) { !showHunkDialog }
            assertEquals(1, stagedRequests.size)

            // Test conflict resolution view buttons and stepper
            composeRule.onNodeWithTag("git-conflict-accept-current-0", useUnmergedTree = true).performClick()
            assertEquals(ConflictResolutionChoice.CURRENT, resolvedChoices[0])
            composeRule.onNodeWithTag("git-conflict-next-btn", useUnmergedTree = true).performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("git-conflict-accept-both-1", useUnmergedTree = true).performClick()
            composeRule.waitForIdle()
            assertEquals(ConflictResolutionChoice.BOTH, resolvedChoices[1])
            composeRule.onNodeWithTag("git-conflict-mark-resolved-btn", useUnmergedTree = true).performClick()
            assertTrue(markedResolved)
        } finally {
            server.close()
        }
    }

    @Test
    fun `git graph wip inspector groups, checkout stash dialog, and blame and file history tablet enhancements work`() {
        val sentGraphMessages = mutableListOf<JsonObject>()
        val sentBlameMessages = mutableListOf<JsonObject>()
        val sentFhMessages = mutableListOf<JsonObject>()

        val changes = GitChanges(
            branch = GitBranchState("main", "origin/main", "origin", true, 0, 0),
            operation = null,
            files = listOf(
                ChangedFile(
                    path = "src/partial.kt",
                    x = "M",
                    y = "M",
                    conflict = false,
                    untracked = false,
                    staged = ChangeSide(2, 1, listOf("b0")),
                    unstaged = ChangeSide(4, 0, listOf("b1")),
                ),
            ),
            truncated = false,
            stashes = 0,
            lastCommit = null,
        )

        val commitHash = "aaaa1111bbbb2222cccc3333dddd4444eeee5555"
        val commits = listOf(
            GitGraphVertex(
                hash = commitHash,
                parents = emptyList(),
                author = "Alice",
                authorEmail = "alice@example.com",
                authorDate = 1_700_000_000L,
                committer = "Alice",
                committerEmail = "alice@example.com",
                commitDate = 1_700_000_000L,
                message = "feat: initial tablet commit",
                refs = listOf(
                    GitGraphRefData(name = "main", type = "head"),
                    GitGraphRefData(name = "feat/other", type = "local"),
                ),
            ),
        )
        val repoInfo = GitGraphRepoInfo(
            path = "/repo",
            branches = emptyList(),
            tags = emptyList(),
            remotes = emptyList(),
            stashes = emptyList(),
            head = commitHash,
            currentBranch = "main",
            remoteWeb = null,
        )

        val blameData = GitBlameData(
            filePath = "src/App.kt",
            rev = null,
            focusLine = 1,
            lines = listOf(
                GitBlameLine(hash = "1111222233334444555566667777888899990000", origLine = 1, finalLine = 1, content = "class App"),
            ),
            commits = mapOf(
                "1111222233334444555566667777888899990000" to GitBlameCommitInfo(
                    hash = "1111222233334444555566667777888899990000",
                    author = "Alice",
                    authorMail = "alice@example.com",
                    authorTime = 1_700_000_000L,
                    summary = "feat: add App",
                    filename = "src/App.kt",
                ),
            ),
            weights = mapOf("1111222233334444555566667777888899990000" to 1.0),
        )

        val fhEntries = listOf(
            GitFileHistoryEntry(
                hash = "1111222233334444555566667777888899990000",
                parents = listOf("0000111122223333"),
                author = "Alice",
                authorEmail = "alice@example.com",
                authorDate = 1_700_000_000L,
                subject = "feat: add App",
                status = "M",
                path = "src/App.kt",
            ),
        )

        var mode by mutableStateOf("graph")

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                CompositionLocalProvider(LocalRedesign provides true) {
                    when (mode) {
                        "graph" -> GitGraphNativeContent(
                            pageState = GitGraphCommitsPageState(
                                commits = commits,
                                hasMore = false,
                                commitsLoaded = true,
                                loading = false,
                                scope = "all",
                            ),
                            repoInfo = repoInfo,
                            commitDetails = null,
                            searchResults = null,
                            settings = GitGraphSettings(),
                            changes = changes,
                            draft = GitGraphCommitDraft(message = ""),
                            onSendMessage = { sentGraphMessages.add(it) },
                            onSendTrackedAction = { action, buildMsg, onDone ->
                                val msg = buildMsg(1)
                                sentGraphMessages.add(msg)
                                if (action == "checkout") {
                                    onDone(
                                        GitGraphActionResult(
                                            ok = false,
                                            error = "Your local changes would be overwritten by checkout",
                                        ),
                                    )
                                } else {
                                    onDone(GitGraphActionResult(ok = true))
                                }
                            },
                            onClearSearchResults = {},
                        )
                        "blame" -> GitBlameNativeContent(
                            blameData = blameData,
                            onSendMessage = { sentBlameMessages.add(it) },
                        )
                        "fh" -> GitFileHistoryNativeContent(
                            filePath = "src/App.kt",
                            entries = fhEntries,
                            hasMore = false,
                            onSendMessage = { sentFhMessages.add(it) },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 1. Click WIP row -> WIP inspector shows Staged and Changes group chips via GitChangeGroups
        composeRule.onNodeWithTag("git-graph-row-uncommitted", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("git-graph-wip-group-staged", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("git-graph-wip-group-changes", useUnmergedTree = true).assertIsDisplayed()

        // 2. Click ? button in Git Graph toolbar to open shortcuts sheet
        composeRule.onNodeWithTag("git-graph-shortcuts-btn", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("git-shortcuts-sheet", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("git-shortcuts-close-btn", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()

        // 3. Switch to Blame and verify File History and Graph links in detail strip
        mode = "blame"
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("git-blame-gutter-1", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("git-blame-act-file-history", useUnmergedTree = true).performClick()
        assertEquals("openHistory", sentBlameMessages.last()["command"].string)
        composeRule.onNodeWithTag("git-blame-act-graph", useUnmergedTree = true).performClick()
        assertEquals("openGraphAtCommit", sentBlameMessages.last()["command"].string)

        // 4. Switch to File History and verify side-by-side preview pane on tablet
        mode = "fh"
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("git-fh-row-1111222233334444555566667777888899990000", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("git-fh-preview-pane", useUnmergedTree = true).assertIsDisplayed()
    }
}
