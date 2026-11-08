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

        // Open Reset dialog on c2 with dirty working tree -> hard mode refuses reset
        sentMessages.clear()
        composeRule.onNodeWithTag("git-graph-row-menu-btn-c222222222222222").performClick()
        composeRule.onNodeWithTag("git-graph-menu-reset").performClick()
        composeRule.onNodeWithTag("git-graph-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("git-graph-dialog-opt-hard").performClick()
        composeRule.onNodeWithTag("git-graph-dialog-confirm-btn").performClick()
        assertTrue(sentMessages.none { it["action"]?.jsonPrimitive?.content == "reset" })
        assertEquals("Hard reset refused while working tree has uncommitted changes", lastToast)

        // Re-open Reset dialog and confirm mixed mode -> sends reset action
        composeRule.onNodeWithTag("git-graph-row-menu-btn-c222222222222222").performClick()
        composeRule.onNodeWithTag("git-graph-menu-reset").performClick()
        composeRule.onNodeWithTag("git-graph-dialog-opt-mixed").performClick()
        composeRule.onNodeWithTag("git-graph-dialog-confirm-btn").performClick()
        assertTrue(sentMessages.any { it["action"]?.jsonPrimitive?.content == "reset" })
    }
}
