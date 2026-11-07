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
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.git.GitBlameCommitInfo
import com.hienlh.ppm.state.git.GitBlameData
import com.hienlh.ppm.state.git.GitBlameLine
import com.hienlh.ppm.state.git.GitCompareCounts
import com.hienlh.ppm.state.git.GitCompareFileChange
import com.hienlh.ppm.state.git.GitCompareRefEntry
import com.hienlh.ppm.state.git.GitFileHistoryEntry
import com.hienlh.ppm.state.git.GitRebasePlanEntry
import com.hienlh.ppm.state.git.GitReflogEntry
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
class Phase08dGitInsightsNativeUiTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @Test
    fun `renders blame lines, heatmap, commit detail actions, and file history range mode`() {
        val sentMessages = mutableListOf<JsonObject>()
        var activeTab by mutableStateOf("blame")

        val blameData = GitBlameData(
            filePath = "src/App.tsx",
            rev = "c444444444444444",
            lines = listOf(
                GitBlameLine(hash = "c444444444444444", finalLine = 1, origLine = 1, content = "import React from 'react';"),
                GitBlameLine(hash = "c444444444444444", finalLine = 2, origLine = 2, content = "export function App() {"),
                GitBlameLine(hash = "0000000000000000000000000000000000000000", finalLine = 3, origLine = 3, content = "  return <div />;"),
            ),
            commits = mapOf(
                "c444444444444444" to GitBlameCommitInfo(
                    hash = "c444444444444444",
                    author = "Alice Nguyen",
                    authorMail = "alice@ppm.dev",
                    authorTime = 1_700_000_200L,
                    summary = "feat: initial App component",
                    filename = "src/OldApp.tsx",
                ),
            ),
            weights = mapOf("c444444444444444" to 0.8),
            focusLine = 2,
        )

        val historyEntries = listOf(
            GitFileHistoryEntry(
                hash = "c444444444444444",
                parents = listOf("c111111111111111"),
                author = "Alice Nguyen",
                authorEmail = "alice@ppm.dev",
                authorDate = 1_700_000_200L,
                subject = "feat: rename OldApp to App",
                status = "R",
                path = "src/App.tsx",
                oldPath = "src/OldApp.tsx",
            ),
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                if (activeTab == "blame") {
                    GitBlameNativeContent(
                        blameData = blameData,
                        onSendMessage = { sentMessages += it },
                    )
                } else {
                    GitFileHistoryNativeContent(
                        filePath = "src/App.tsx",
                        entries = historyEntries,
                        hasMore = true,
                        onSendMessage = { sentMessages += it },
                    )
                }
            }
        }

        // 1. Blame panel assertions
        composeRule.onNodeWithTag("git-blame-toolbar").assertIsDisplayed()
        composeRule.onNodeWithTag("git-blame-list").assertIsDisplayed()
        composeRule.onNodeWithTag("git-blame-gutter-1").performClick()
        composeRule.onNodeWithTag("git-blame-detail").assertIsDisplayed()
        composeRule.onNodeWithTag("git-blame-act-diff").performClick()
        assertTrue(sentMessages.any { it["command"]?.jsonPrimitive?.content == "openDiff" })
        composeRule.onNodeWithTag("git-blame-act-prev").performClick()
        assertTrue(sentMessages.any { it["command"]?.jsonPrimitive?.content == "blamePrevious" })
        composeRule.onNodeWithTag("git-blame-act-line-history").performClick()
        assertTrue(sentMessages.any { it["command"]?.jsonPrimitive?.content == "openLineHistory" })

        // 2. Switch to File History panel
        sentMessages.clear()
        activeTab = "history"
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("git-fh-toolbar").assertIsDisplayed()
        composeRule.onNodeWithTag("git-fh-row-c444444444444444").assertIsDisplayed()
        composeRule.onNodeWithTag("git-fh-row-blame-c444444444444444").performClick()
        assertTrue(sentMessages.any { it["command"]?.jsonPrimitive?.content == "openBlame" })

        // Toggle Lines range mode and apply
        composeRule.onNodeWithTag("git-fh-lines-toggle").performClick()
        composeRule.onNodeWithTag("git-fh-line-start").performTextReplacement("5")
        composeRule.onNodeWithTag("git-fh-line-end").performTextReplacement("20")
        composeRule.onNodeWithTag("git-fh-apply-lines-btn").performClick()
        assertTrue(sentMessages.any { it["command"]?.jsonPrimitive?.content == "requestHistory" })
    }

    @Test
    fun `renders compare split view, interactive rebase plan reordering and reword, and reflog reset hard guard`() {
        val sentMessages = mutableListOf<JsonObject>()
        var activePanel by mutableStateOf("compare")
        var rebasePlan by mutableStateOf(
            listOf(
                GitRebasePlanEntry(
                    hash = "c333333333333333",
                    author = "Alice",
                    authorEmail = "alice@ppm.dev",
                    authorDate = 1_700_000_300L,
                    subject = "feat: third commit",
                    action = "pick",
                ),
                GitRebasePlanEntry(
                    hash = "c222222222222222",
                    author = "Bob",
                    authorEmail = "bob@ppm.dev",
                    authorDate = 1_700_000_200L,
                    subject = "feat: second commit",
                    action = "pick",
                ),
            ),
        )
        var dirtyTree by mutableStateOf(true)

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                when (activePanel) {
                    "compare" -> GitCompareNativeContent(
                        refs = listOf(
                            GitCompareRefEntry(name = "main", hash = "c1111111", kind = "head"),
                            GitCompareRefEntry(name = "feature/login", hash = "c3333333", kind = "head"),
                        ),
                        ref1 = "main",
                        ref2 = "feature/login",
                        mode = "three-dot",
                        commits = listOf(
                            GitFileHistoryEntry(
                                hash = "c333333333333333",
                                author = "Alice",
                                authorEmail = "alice@ppm.dev",
                                authorDate = 1_700_000_300L,
                                subject = "feat: third commit",
                            ),
                        ),
                        files = listOf(
                            GitCompareFileChange(path = "src/Login.tsx", additions = 20, deletions = 2),
                        ),
                        counts = GitCompareCounts(behind = 1, ahead = 2),
                        onSendMessage = { sentMessages += it },
                    )
                    "rebase" -> GitInteractiveRebaseNativeContent(
                        branch = "feature/login",
                        upstream = "origin/main",
                        base = "c111111111111111",
                        plan = rebasePlan,
                        onUpdatePlan = { rebasePlan = it },
                        onSendMessage = { sentMessages += it },
                    )
                    else -> GitReflogNativeContent(
                        entries = listOf(
                            GitReflogEntry(
                                hash = "a111111122222222",
                                selector = "HEAD@{0}",
                                action = "commit: feat login",
                                kind = "commit",
                                author = "Alice",
                                authorEmail = "alice@ppm.dev",
                                authorDate = 1_700_000_300L,
                                subject = "feat login",
                            ),
                        ),
                        branch = "main",
                        hasDirtyWorkingTree = dirtyTree,
                        onSendMessage = { sentMessages += it },
                    )
                }
            }
        }

        // 1. Compare panel assertions
        composeRule.onNodeWithTag("git-compare-toolbar").assertIsDisplayed()
        composeRule.onNodeWithTag("git-compare-counts").assertIsDisplayed()
        composeRule.onNodeWithTag("git-compare-file-src/Login.tsx").performClick()
        assertTrue(sentMessages.any { it["command"]?.jsonPrimitive?.content == "openDiff" })

        // 2. Interactive Rebase panel assertions
        sentMessages.clear()
        activePanel = "rebase"
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("git-rebase-toolbar").assertIsDisplayed()
        // Move second item up to index 0
        composeRule.onNodeWithTag("git-rebase-up-1").performClick()
        assertEquals("c222222222222222", rebasePlan[0].hash)
        // Change index 0 to reword & edit message
        composeRule.onNodeWithTag("git-rebase-action-0-reword").performClick()
        composeRule.onNodeWithTag("git-rebase-reword-input-0").performTextReplacement("feat: second commit (reworded)")
        // Change index 1 (oldest kept) to squash -> should fail validation
        composeRule.onNodeWithTag("git-rebase-action-1-squash").performClick()
        composeRule.onNodeWithTag("git-rebase-run-btn").performClick()
        composeRule.onNodeWithTag("git-rebase-error-banner").assertIsDisplayed()
        assertTrue(sentMessages.none { it["command"]?.jsonPrimitive?.content == "runRebase" })
        // Fix index 1 back to pick and index 0 to squash -> valid!
        composeRule.onNodeWithTag("git-rebase-action-1-pick").performClick()
        composeRule.onNodeWithTag("git-rebase-run-btn").performClick()
        assertTrue(sentMessages.any { it["command"]?.jsonPrimitive?.content == "runRebase" })

        // 3. Reflog panel assertions (Reset --hard requires matching short SHA and clean working tree)
        sentMessages.clear()
        activePanel = "reflog"
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("git-reflog-toolbar").assertIsDisplayed()
        composeRule.onNodeWithTag("git-reflog-reset-btn-HEAD@{0}").performClick()
        composeRule.onNodeWithTag("git-reflog-confirm").assertIsDisplayed()
        composeRule.onNodeWithTag("git-reflog-confirm-input").performTextReplacement("a111111")
        composeRule.onNodeWithTag("git-reflog-do-reset-btn").performClick()
        // Refused because dirtyTree == true
        composeRule.onNodeWithTag("git-reflog-error-banner").assertIsDisplayed()
        assertTrue(sentMessages.none { it["command"]?.jsonPrimitive?.content == "resetHard" })

        // Clean working tree -> Reset --hard succeeds
        dirtyTree = false
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("git-reflog-do-reset-btn").performClick()
        assertTrue(sentMessages.any { it["command"]?.jsonPrimitive?.content == "resetHard" })
    }
}
