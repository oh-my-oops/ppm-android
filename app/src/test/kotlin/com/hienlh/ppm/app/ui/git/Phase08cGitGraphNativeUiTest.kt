package com.hienlh.ppm.app.ui.git

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.GitChanges
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.git.GitGraphActionResult
import com.hienlh.ppm.state.git.GitGraphBranch
import com.hienlh.ppm.state.git.GitGraphCommitDetail
import com.hienlh.ppm.state.git.GitGraphCommitDraft
import com.hienlh.ppm.state.git.GitGraphCommitsPageState
import com.hienlh.ppm.state.git.GitGraphExtMessage
import com.hienlh.ppm.state.git.GitGraphFileChange
import com.hienlh.ppm.state.git.GitGraphRefData
import com.hienlh.ppm.state.git.GitGraphRemote
import com.hienlh.ppm.state.git.GitGraphRemoteWeb
import com.hienlh.ppm.state.git.GitGraphRepoInfo
import com.hienlh.ppm.state.git.GitGraphSearchHit
import com.hienlh.ppm.state.git.GitGraphSettings
import com.hienlh.ppm.state.git.GitGraphStash
import com.hienlh.ppm.state.git.GitGraphTag
import com.hienlh.ppm.state.git.GitGraphVertex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1024dp-h768dp")
class Phase08cGitGraphNativeUiTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val sampleRepoInfo = GitGraphRepoInfo(
        path = "/work/ppm",
        branches = listOf(
            GitGraphBranch(name = "main", remote = "origin", current = true, hash = "c444444444444444"),
            GitGraphBranch(name = "feature/login", remote = null, current = false, hash = "c333333333333333"),
        ),
        tags = listOf(
            GitGraphTag(name = "v1.0.0", hash = "c333333333333333"),
        ),
        remotes = listOf(
            GitGraphRemote(
                name = "origin",
                fetchUrl = "https://github.com/hienlh/ppm.git",
                pushUrl = "https://github.com/hienlh/ppm.git",
            ),
        ),
        stashes = listOf(
            GitGraphStash(
                index = 0,
                hash = "s999999999999999",
                parentHash = "c444444444444444",
                author = "Alice Nguyen",
                authorEmail = "alice@ppm.dev",
                date = 1_700_000_300L,
                message = "WIP on main: c444444 WIP work",
            ),
        ),
        head = "c444444444444444",
        currentBranch = "main",
        remoteWeb = GitGraphRemoteWeb(
            base = "https://github.com/hienlh/ppm",
            commitPath = "/commit/",
            label = "GitHub",
        ),
    )

    private val sampleCommits = listOf(
        GitGraphVertex(
            hash = "c444444444444444",
            parents = listOf("c222222222222222", "c333333333333333"),
            author = "Alice Nguyen",
            authorEmail = "alice@ppm.dev",
            authorDate = 1_700_000_200L,
            committer = "Alice Nguyen",
            committerEmail = "alice@ppm.dev",
            commitDate = 1_700_000_200L,
            message = "Merge branch 'feature/login' (#42)",
            refs = listOf(
                GitGraphRefData(name = "main", type = "head"),
                GitGraphRefData(name = "origin/main", type = "remote"),
            ),
        ),
        GitGraphVertex(
            hash = "c333333333333333",
            parents = listOf("c111111111111111"),
            author = "Bob Tran",
            authorEmail = "bob@ppm.dev",
            authorDate = 1_700_000_150L,
            committer = "Bob Tran",
            committerEmail = "bob@ppm.dev",
            commitDate = 1_700_000_150L,
            message = "feat: add OAuth callback",
            refs = listOf(
                GitGraphRefData(name = "feature/login", type = "local"),
                GitGraphRefData(name = "v1.0.0", type = "tag"),
            ),
        ),
        GitGraphVertex(
            hash = "c222222222222222",
            parents = listOf("c111111111111111"),
            author = "Alice Nguyen",
            authorEmail = "alice@ppm.dev",
            authorDate = 1_700_000_100L,
            committer = "Alice Nguyen",
            committerEmail = "alice@ppm.dev",
            commitDate = 1_700_000_100L,
            message = "fix: header alignment",
            refs = emptyList(),
        ),
        GitGraphVertex(
            hash = "c111111111111111",
            parents = emptyList(),
            author = "Alice Nguyen",
            authorEmail = "alice@ppm.dev",
            authorDate = 1_700_000_000L,
            committer = "Alice Nguyen",
            committerEmail = "alice@ppm.dev",
            commitDate = 1_700_000_000L,
            message = "chore: initial commit",
            refs = emptyList(),
        ),
    )

    private val sampleCommitDetail = GitGraphCommitDetail(
        hash = "c444444444444444",
        parents = listOf("c222222222222222", "c333333333333333"),
        author = "Alice Nguyen",
        authorEmail = "alice@ppm.dev",
        authorDate = 1_700_000_200L,
        committer = "Alice Nguyen",
        committerEmail = "alice@ppm.dev",
        commitDate = 1_700_000_200L,
        message = "Merge branch 'feature/login' (#42)\n\nDetailed merge summary.",
        fileChanges = listOf(
            GitGraphFileChange(path = "src/App.tsx", status = "M", additions = 14, deletions = 3),
        ),
    )

    private val sampleChanges = GitChanges.from(
        Json.parseToJsonElement(
            """
            {
              "branch": {
                "name": "main",
                "upstream": "origin/main",
                "ahead": 1,
                "behind": 0,
                "detached": false
              },
              "operation": {
                "kind": "rebase",
                "step": 1,
                "total": 3,
                "subject": "feat: add OAuth callback"
              },
              "files": [
                {
                  "path": "src/App.tsx",
                  "x": "M",
                  "y": ".",
                  "staged": {
                    "blocks": [
                      {"id": "h0", "index": 0, "oldStart": 1, "oldLines": 1, "newStart": 1, "newLines": 2, "added": 1, "removed": 0}
                    ],
                    "added": 1,
                    "removed": 0
                  },
                  "unstaged": null,
                  "untracked": false,
                  "conflict": false
                },
                {
                  "path": "src/Login.tsx",
                  "x": ".",
                  "y": "M",
                  "staged": null,
                  "unstaged": {
                    "blocks": [
                      {"id": "h1", "index": 0, "oldStart": 5, "oldLines": 2, "newStart": 5, "newLines": 3, "added": 2, "removed": 1}
                    ],
                    "added": 2,
                    "removed": 1
                  },
                  "untracked": false,
                  "conflict": false
                }
              ],
              "truncated": false,
              "stashes": 1
            }
            """.trimIndent(),
        ),
    )

    @Test
    fun `renders graph rows, inspector commit details, WIP commit, find bar, and settings sheet`() {
        val sentMessages = mutableListOf<JsonObject>()
        val currentDetail by mutableStateOf<GitGraphCommitDetail?>(sampleCommitDetail)
        var currentSearchResults by mutableStateOf<GitGraphExtMessage.LoadSearchResults?>(null)
        val currentSettings by mutableStateOf(GitGraphSettings(showStashes = true))

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                GitGraphNativeContent(
                    repoInfo = sampleRepoInfo,
                    pageState = GitGraphCommitsPageState(
                        commits = sampleCommits,
                        hasMore = false,
                        commitsLoaded = true,
                        loading = false,
                        scope = "all",
                    ),
                    commitDetails = currentDetail,
                    searchResults = currentSearchResults,
                    settings = currentSettings,
                    changes = sampleChanges,
                    draft = GitGraphCommitDraft(message = "feat: finish login flow"),
                    onSendMessage = { msg -> sentMessages += msg },
                    onSendTrackedAction = { _, buildMsg, onDone ->
                        sentMessages += buildMsg(1)
                        onDone(GitGraphActionResult(ok = true))
                    },
                    onClearSearchResults = { currentSearchResults = null },
                )
            }
        }

        // Verify operation banner, toolbar, commit list, WIP row, and commit row
        composeRule.onNodeWithTag("git-graph-op-banner").assertIsDisplayed()
        composeRule.onNodeWithTag("git-graph-toolbar").assertIsDisplayed()
        composeRule.onNodeWithTag("git-graph-commit-list").assertIsDisplayed()
        composeRule.onNodeWithTag("git-graph-row-uncommitted").assertIsDisplayed()
        composeRule.onNodeWithTag("git-graph-row-c444444444444444").assertIsDisplayed()
        composeRule.onNodeWithTag("git-graph-ref-pill-main").assertIsDisplayed()

        // Click commit row c4 -> inspector shows commit details & file menu works
        composeRule.onNodeWithTag("git-graph-row-c444444444444444").performClick()
        composeRule.onNodeWithTag("git-graph-inspector").assertIsDisplayed()
        composeRule.onNodeWithTag("git-graph-inspector-commit").assertIsDisplayed()
        composeRule.onNodeWithTag("git-graph-insp-file-menu-btn-src/App.tsx").performClick()
        composeRule.onNodeWithTag("git-graph-file-menu").assertIsDisplayed()
        composeRule.onNodeWithTag("git-graph-file-menu-blame").performClick()
        assertTrue(sentMessages.any { it["command"]?.jsonPrimitive?.content == "openBlame" })

        // Select WIP row -> WIP inspector opens -> commit staged changes
        composeRule.onNodeWithTag("git-graph-row-uncommitted").performClick()
        composeRule.onNodeWithTag("git-graph-inspector-wip").assertIsDisplayed()
        composeRule.onNodeWithTag("git-graph-wip-commit-btn").performClick()
        assertTrue(sentMessages.any { it["command"]?.jsonPrimitive?.content == "commitStaged" })

        // Toggle Find bar & switch to History mode
        composeRule.onNodeWithTag("git-graph-find-btn").performClick()
        composeRule.onNodeWithTag("git-graph-find-bar").assertIsDisplayed()
        composeRule.onNodeWithTag("git-graph-find-input").performTextReplacement("OAuth")
        composeRule.onNodeWithTag("git-graph-find-mode-message").performClick()
        currentSearchResults = GitGraphExtMessage.LoadSearchResults(
            mode = "message",
            text = "OAuth",
            hits = listOf(
                GitGraphSearchHit(
                    hash = "c333333333333333",
                    subject = "feat: add OAuth callback",
                    author = "Bob Tran",
                    authorEmail = "bob@ppm.dev",
                    authorDate = 1_700_000_150L,
                ),
            ),
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("git-graph-search-results").assertIsDisplayed()

        // Open View menu -> Settings sheet and toggle graph style
        composeRule.onNodeWithTag("git-graph-view-btn").performClick()
        composeRule.onNodeWithTag("git-graph-view-menu").assertIsDisplayed()
        composeRule.onNodeWithTag("git-graph-view-settings-btn").performClick()
        composeRule.onNodeWithTag("git-graph-settings-sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("git-graph-setting-style-angular").performClick()
        assertTrue(sentMessages.any { it["command"]?.jsonPrimitive?.content == "updateSetting" })
    }

    @Test
    fun `supports commit context menu, create branch dialog, and reset dirty working tree guard`() {
        val sentMessages = mutableListOf<JsonObject>()
        var lastToast by mutableStateOf<String?>(null)

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                GitGraphNativeContent(
                    repoInfo = sampleRepoInfo,
                    pageState = GitGraphCommitsPageState(
                        commits = sampleCommits,
                        hasMore = false,
                        commitsLoaded = true,
                        loading = false,
                        scope = "all",
                    ),
                    changes = sampleChanges,
                    toastMessage = lastToast,
                    onShowToast = { msg, _ -> lastToast = msg },
                    onSendMessage = { msg -> sentMessages += msg },
                    onSendTrackedAction = { _, buildMsg, onDone ->
                        sentMessages += buildMsg(1)
                        onDone(GitGraphActionResult(ok = true))
                    },
                )
            }
        }

        // Open commit context menu on c3 -> Create branch here
        composeRule.onNodeWithTag("git-graph-row-menu-btn-c333333333333333").performClick()
        composeRule.onNodeWithTag("git-graph-commit-menu").assertIsDisplayed()
        composeRule.onNodeWithTag("git-graph-menu-create-branch").performClick()
        composeRule.onNodeWithTag("git-graph-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("git-graph-dialog-input").performTextReplacement("feature/oauth-v2")
        composeRule.onNodeWithTag("git-graph-dialog-confirm-btn").performClick()
        assertTrue(sentMessages.any { it["action"]?.jsonPrimitive?.content == "createBranch" })

        // Open Reset dialog on c2 -> selecting hard mode opens second "Throw away every change" confirmation step
        sentMessages.clear()
        composeRule.onNodeWithTag("git-graph-row-menu-btn-c222222222222222").performClick()
        composeRule.onNodeWithTag("git-graph-menu-reset").performClick()
        composeRule.onNodeWithTag("git-graph-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("git-graph-dialog-opt-hard").performClick()
        composeRule.onNodeWithTag("git-graph-dialog-confirm-btn").performClick()
        // First confirm opens the second confirmation step instead of sending reset immediately
        assertTrue(sentMessages.none { it["action"]?.jsonPrimitive?.content == "reset" })
        composeRule.onNodeWithTag("git-graph-dialog").assertIsDisplayed()
        // Confirming the second step sends hard reset
        composeRule.onNodeWithTag("git-graph-dialog-confirm-btn").performClick()
        assertTrue(
            sentMessages.any {
                it["action"]?.jsonPrimitive?.content == "reset" &&
                    it["args"]?.let { p -> (p as? JsonObject)?.get("mode")?.jsonPrimitive?.content } == "hard"
            },
        )

        // Re-open Reset dialog and confirm mixed mode -> sends reset action in one step
        sentMessages.clear()
        composeRule.onNodeWithTag("git-graph-row-menu-btn-c222222222222222").performClick()
        composeRule.onNodeWithTag("git-graph-menu-reset").performClick()
        composeRule.onNodeWithTag("git-graph-dialog-opt-mixed").performClick()
        composeRule.onNodeWithTag("git-graph-dialog-confirm-btn").performClick()
        assertTrue(sentMessages.any { it["action"]?.jsonPrimitive?.content == "reset" })
    }

    @Test
    fun `c6_c7_and_08c_major_confirmDestructiveActionsSupportDiscardUndoAndCompleteMenus`() {
        val sentMessages = mutableListOf<JsonObject>()
        var lastToast by mutableStateOf<String?>(null)
        var lastToastUndo by mutableStateOf<Pair<String, () -> Unit>?>(null)
        var nextActionResult by mutableStateOf(GitGraphActionResult(ok = true))

        val wipChanges = GitChanges.from(
            Json.parseToJsonElement(
                """
                {
                  "branch": {
                    "name": "main",
                    "upstream": "origin/main",
                    "ahead": 1,
                    "behind": 0,
                    "detached": false
                  },
                  "files": [
                    {
                      "path": "src/StagedOnly.tsx",
                      "x": "M",
                      "y": ".",
                      "untracked": false,
                      "conflict": false,
                      "staged": {
                        "added": 2,
                        "removed": 1,
                        "blocks": [
                          {"id": "h1", "index": 0, "oldStart": 1, "oldLines": 1, "newStart": 1, "newLines": 2, "added": 2, "removed": 1}
                        ]
                      },
                      "unstaged": null
                    },
                    {
                      "path": "src/TrackedEdit.tsx",
                      "x": ".",
                      "y": "M",
                      "untracked": false,
                      "conflict": false,
                      "staged": null,
                      "unstaged": {
                        "added": 4,
                        "removed": 1,
                        "blocks": [
                          {"id": "h2", "index": 0, "oldStart": 5, "oldLines": 1, "newStart": 5, "newLines": 4, "added": 4, "removed": 1}
                        ]
                      }
                    },
                    {
                      "path": "src/NewFile.tsx",
                      "x": "?",
                      "y": "?",
                      "untracked": true,
                      "conflict": false,
                      "staged": null,
                      "unstaged": {
                        "added": 5,
                        "removed": 0,
                        "blocks": [],
                        "whole": "new"
                      }
                    }
                  ],
                  "truncated": false,
                  "stashes": 1
                }
                """.trimIndent(),
            ),
        )

        val commitsWithNonHeadMerge = sampleCommits.map { c ->
            if (c.hash == "c333333333333333") c.copy(parents = listOf("c111111111111111", "c222222222222222")) else c
        }

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                GitGraphNativeContent(
                    repoInfo = sampleRepoInfo,
                    pageState = GitGraphCommitsPageState(
                        commits = commitsWithNonHeadMerge,
                        hasMore = false,
                        commitsLoaded = true,
                        loading = false,
                        scope = "all",
                    ),
                    changes = wipChanges,
                    toastMessage = lastToast,
                    toastUndo = lastToastUndo,
                    onShowToast = { msg, undo ->
                        lastToast = msg
                        lastToastUndo = undo
                    },
                    onDismissToast = {
                        lastToast = null
                        lastToastUndo = null
                    },
                    onSendMessage = { msg -> sentMessages += msg },
                    onSendTrackedAction = { _, buildMsg, onDone ->
                        sentMessages += buildMsg(1)
                        onDone(nextActionResult)
                    },
                )
            }
        }

        // C6: WIP "Discard all changes…" filters to hasUnstaged (TrackedEdit.tsx + NewFile.tsx, NOT StagedOnly.tsx),
        // opens confirmation dialog first, and offers Undo toast that sends undoDiscard
        nextActionResult = GitGraphActionResult(
            ok = true,
            data = Json.parseToJsonElement(
                """{"undo":{"id":"undo-snap-1","paths":["src/TrackedEdit.tsx","src/NewFile.tsx"],"skipped":[]}}""",
            ) as JsonObject,
        )
        composeRule.onNodeWithTag("git-graph-row-menu-btn-uncommitted").performClick()
        composeRule.onNodeWithTag("git-graph-wip-menu-discard-all").performClick()
        // Must not discard before confirmation
        assertTrue(sentMessages.none { it["command"]?.jsonPrimitive?.content == "discardFiles" })
        composeRule.onNodeWithTag("git-graph-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("git-graph-dialog-confirm-btn").performClick()
        val discardMsg = sentMessages.last { it["command"]?.jsonPrimitive?.content == "discardFiles" }
        assertEquals("""["src/TrackedEdit.tsx","src/NewFile.tsx"]""", discardMsg["paths"].toString())
        assertEquals("Discarded changes to 2 files", lastToast)
        // Clicking Undo sends undoDiscard with the snapshot id
        composeRule.onNodeWithTag("git-graph-toast-undo-btn").performClick()
        val undoMsg = sentMessages.last { it["command"]?.jsonPrimitive?.content == "undoDiscard" }
        assertEquals("undo-snap-1", undoMsg["id"]?.jsonPrimitive?.content)

        // 08c Major: WIP "Delete untracked files…" filters to untracked (NewFile.tsx) and confirms
        sentMessages.clear()
        composeRule.onNodeWithTag("git-graph-row-menu-btn-uncommitted").performClick()
        composeRule.onNodeWithTag("git-graph-wip-menu-delete-untracked").performClick()
        composeRule.onNodeWithTag("git-graph-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("git-graph-dialog-confirm-btn").performClick()
        val deleteUntrackedMsg = sentMessages.last { it["command"]?.jsonPrimitive?.content == "discardFiles" }
        assertEquals("""["src/NewFile.tsx"]""", deleteUntrackedMsg["paths"].toString())

        // 08c Major: WIP "Reset…" opens reset mode dialog against HEAD
        sentMessages.clear()
        composeRule.onNodeWithTag("git-graph-row-menu-btn-uncommitted").performClick()
        composeRule.onNodeWithTag("git-graph-wip-menu-reset").performClick()
        composeRule.onNodeWithTag("git-graph-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("git-graph-dialog-opt-mixed").performClick()
        composeRule.onNodeWithTag("git-graph-dialog-confirm-btn").performClick()
        assertTrue(sentMessages.any { it["action"]?.jsonPrimitive?.content == "reset" })

        // C6: Single-file Discard in WIP inspector requires confirmation too
        sentMessages.clear()
        composeRule.onNodeWithTag("git-graph-row-uncommitted").performClick()
        composeRule.onNodeWithTag("git-graph-wip-discard-src/TrackedEdit.tsx").performClick()
        assertTrue(sentMessages.none { it["command"]?.jsonPrimitive?.content == "discardFiles" })
        composeRule.onNodeWithTag("git-graph-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("git-graph-dialog-confirm-btn").performClick()
        val singleDiscardMsg = sentMessages.last { it["command"]?.jsonPrimitive?.content == "discardFiles" }
        assertEquals("""["src/TrackedEdit.tsx"]""", singleDiscardMsg["paths"].toString())

        // C7: Cherry-pick on merge commit (c3 has 2 parents and is not HEAD) is blocked with web toast
        sentMessages.clear()
        composeRule.onNodeWithTag("git-graph-row-menu-btn-c333333333333333").performClick()
        composeRule.onNodeWithTag("git-graph-menu-cherry-pick").performClick()
        assertTrue(sentMessages.none { it["action"]?.jsonPrimitive?.content == "cherryPick" })
        assertEquals("A merge cannot be cherry-picked from here", lastToast)

        // C7: Delete branch confirms first, and when unmerged ("not fully merged") prompts "Delete anyway" with force=true
        sentMessages.clear()
        nextActionResult = GitGraphActionResult(
            ok = false,
            error = "error: The branch 'feature/login' is not fully merged.",
        )
        composeRule.onNodeWithTag("git-graph-ref-pill-feature/login").performClick()
        composeRule.onNodeWithTag("git-graph-ref-delete").performClick()
        assertTrue(sentMessages.none { it["action"]?.jsonPrimitive?.content == "deleteBranch" })
        composeRule.onNodeWithTag("git-graph-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("git-graph-dialog-confirm-btn").performClick()
        // First attempt used force=false and failed with "not fully merged" -> second dialog "Delete anyway" opens
        val firstDelete = sentMessages.last { it["action"]?.jsonPrimitive?.content == "deleteBranch" }
        assertEquals("false", (firstDelete["args"] as JsonObject)["force"]?.jsonPrimitive?.content)
        composeRule.onNodeWithTag("git-graph-dialog").assertIsDisplayed()
        nextActionResult = GitGraphActionResult(ok = true)
        composeRule.onNodeWithTag("git-graph-dialog-confirm-btn").performClick()
        val forcedDelete = sentMessages.last { it["action"]?.jsonPrimitive?.content == "deleteBranch" }
        assertEquals("true", (forcedDelete["args"] as JsonObject)["force"]?.jsonPrimitive?.content)

        // 08c Major: Stash menu & commit menu include "Create branch from stash…"
        sentMessages.clear()
        composeRule.onNodeWithTag("git-graph-stashes-btn").performClick()
        composeRule.onNodeWithTag("git-graph-stash-branch-0").performClick()
        composeRule.onNodeWithTag("git-graph-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("git-graph-dialog-input").performTextReplacement("from-stash-0")
        composeRule.onNodeWithTag("git-graph-dialog-confirm-btn").performClick()
        val stashBranchMsg = sentMessages.last { it["action"]?.jsonPrimitive?.content == "stashBranch" }
        assertEquals("from-stash-0", (stashBranchMsg["args"] as JsonObject)["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `toasts auto-dismiss after 8s for discard with Undo and 7s for standard toasts`() {
        var lastToast by mutableStateOf<String?>(null)
        var lastToastUndo by mutableStateOf<Pair<String, () -> Unit>?>(null)

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                GitGraphNativeContent(
                    repoInfo = sampleRepoInfo,
                    pageState = GitGraphCommitsPageState(
                        commits = sampleCommits,
                        hasMore = false,
                        commitsLoaded = true,
                        loading = false,
                        scope = "all",
                    ),
                    changes = sampleChanges,
                    toastMessage = lastToast,
                    toastUndo = lastToastUndo,
                    onShowToast = { msg, undo ->
                        lastToast = msg
                        lastToastUndo = undo
                    },
                    onDismissToast = {
                        lastToast = null
                        lastToastUndo = null
                    },
                )
            }
        }

        // 1. Discard toast with Undo stays for 8,000 ms (still visible at 7,500 ms, dismissed at 8,000 ms)
        composeRule.runOnIdle {
            lastToast = "Discarded changes to 2 files"
            lastToastUndo = "Undo" to {}
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("git-graph-toast").assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(7_500L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("git-graph-toast").assertIsDisplayed()
        assertEquals("Discarded changes to 2 files", lastToast)

        composeRule.mainClock.advanceTimeBy(600L)
        composeRule.waitForIdle()
        assertEquals(null, lastToast)

        // 2. Standard toast stays for 7,000 ms (still visible at 6,500 ms, dismissed at 7,000 ms)
        composeRule.runOnIdle {
            lastToast = "Copied the hash"
            lastToastUndo = null
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("git-graph-toast").assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(6_500L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("git-graph-toast").assertIsDisplayed()
        assertEquals("Copied the hash", lastToast)

        composeRule.mainClock.advanceTimeBy(600L)
        composeRule.waitForIdle()
        assertEquals(null, lastToast)
    }
}
