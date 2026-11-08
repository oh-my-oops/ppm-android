package com.hienlh.ppm.app.ui.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.ReviewBlocks
import com.hienlh.ppm.state.editor.BlameCommitInfo
import com.hienlh.ppm.state.editor.BlameLineDetailState
import com.hienlh.ppm.state.editor.CompareCandidate
import com.hienlh.ppm.state.editor.CompareSelection
import com.hienlh.ppm.state.editor.SideBySideDiffRow
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
class Phase07dUiTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @Test
    fun blameHoverCard_displaysVerbatimMessageActionsAndLineDiff() {
        var copiedSha: String? = null
        val executedCommands = mutableListOf<Pair<String, List<String>>>()

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                BlameHoverCard(
                    commit = BlameCommitInfo(
                        hash = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678",
                        author = "Thang Ho",
                        authorMail = "<thang@example.com>",
                        authorTime = 1_700_000_000L,
                        summary = "feat: support <T> generics",
                    ),
                    detail = BlameLineDetailState(
                        hash = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678",
                        author = "Thang Ho",
                        authorMail = "<thang@example.com>",
                        authorTime = 1_700_000_000L,
                        message = "feat: support <T> generics & markdown",
                        removed = listOf("val old = 1"),
                        added = listOf("val new = 2"),
                    ),
                    filePath = "src/Main.kt",
                    projectPath = "ppm",
                    nowMs = 1_700_003_600_000L,
                    onCopySha = { copiedSha = it },
                    onActionCommand = { cmd, args -> executedCommands += cmd to args },
                )
            }
        }

        composeRule.onNodeWithTag("blame-hover-card").assertIsDisplayed()
        composeRule.onNodeWithText("feat: support <T> generics & markdown").assertIsDisplayed()
        composeRule.onNodeWithText("-val old = 1").assertIsDisplayed()
        composeRule.onNodeWithText("+val new = 2").assertIsDisplayed()

        composeRule.onNodeWithTag("blame-action-copy-sha").performClick()
        assertEquals("a1b2c3d4e5f60718293a4b5c6d7e8f9012345678", copiedSha)

        composeRule.onNodeWithTag("blame-action-file-history").performClick()
        composeRule.onNodeWithTag("blame-action-blame-file").performClick()
        composeRule.onNodeWithTag("blame-action-show-graph").performClick()

        assertEquals(3, executedCommands.size)
        assertEquals("ppm.blame.fileHistory", executedCommands[0].first)
        assertEquals("ppm.blame.blameFile", executedCommands[1].first)
        assertEquals("ppm.blame.showInGraph", executedCommands[2].first)
    }

    @Test
    fun gitSideBySideDiffRows_rendersTwoColumnsAligned() {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                GitSideBySideDiffRows(
                    rows = listOf(
                        SideBySideDiffRow(
                            key = "hunk:0",
                            leftLineNumber = 1,
                            leftText = "val old = 10",
                            leftKind = ReviewBlocks.Kind.REMOVED,
                            rightLineNumber = 1,
                            rightText = "val new = 20",
                            rightKind = ReviewBlocks.Kind.ADDED,
                        ),
                    ),
                    wordWrap = true,
                )
            }
        }

        composeRule.onNodeWithTag("git-diff-side-by-side-block").assertIsDisplayed()
        composeRule.onNodeWithText("val old = 10").assertIsDisplayed()
        composeRule.onNodeWithText("val new = 20").assertIsDisplayed()
    }

    @Test
    fun conflictEditorContent_resolvesRegionsAndSavesCleanFile() {
        val raw = listOf(
            "<<<<<<< HEAD",
            "val a = 1",
            "=======",
            "val a = 2",
            ">>>>>>> feat/branch",
            "<<<<<<< HEAD",
            "val b = 10",
            "||||||| base",
            "val b = 0",
            "=======",
            "val b = 20",
            ">>>>>>> feat/branch",
        ).joinToString("\n")

        var savedContent = ""
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                ConflictEditorContent(
                    fileName = "App.kt",
                    initialContent = raw,
                    onSaveContent = { savedContent = it },
                )
            }
        }

        composeRule.onNodeWithText("2 conflicts remaining").assertIsDisplayed()

        // Region 0 -> Accept Current ("val a = 1")
        composeRule.onNodeWithTag("conflict-accept-current-0").performClick()
        composeRule.onNodeWithText("1 conflict remaining").assertIsDisplayed()

        // Remaining region (re-indexed to 0) -> Accept Incoming ("val b = 20", ignoring diff3 base)
        composeRule.onNodeWithTag("conflict-accept-incoming-0").performClick()
        composeRule.onNodeWithText("All conflicts resolved").assertIsDisplayed()

        assertEquals("val a = 1\nval b = 20", savedContent)
    }

    @Test
    fun compareFilesDialogContent_selectsTwoFilesAndInvokesCallback() {
        var pickedPair: Pair<CompareSelection, CompareCandidate>? = null
        val candidates = listOf(
            CompareCandidate(id = "tab:src/A.kt", path = "src/A.kt", label = "A.kt", source = "tab", dirtyContent = "fun a() = 1"),
            CompareCandidate(id = "tab:src/B.kt", path = "src/B.kt", label = "B.kt", source = "tab", dirtyContent = "fun b() = 2"),
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                CompareFilesDialogContent(
                    initialSelectionA = null,
                    candidates = candidates,
                    onPickPair = { a, b -> pickedPair = a to b },
                )
            }
        }

        composeRule.onNodeWithText("Pick first file, then second.").assertIsDisplayed()
        composeRule.onNodeWithTag("compare-candidate-0").performClick()

        // Side A chip is now shown; click B.kt (index 1) as second file
        composeRule.onNodeWithTag("compare-side-a-chip").assertIsDisplayed()
        composeRule.onNodeWithTag("compare-candidate-1").performClick()

        assertTrue(pickedPair != null)
        assertEquals("src/A.kt", pickedPair!!.first.filePath)
        assertEquals("src/B.kt", pickedPair!!.second.path)
    }

    @Test
    fun editableFileCodeView_blameCommitLineUsesCommitFilenameBeforeCurrentPath() {
        val server = mockwebserver3.MockWebServer()
        server.start()
        try {
            DeviceLspPreference.setEnabled(false)
            SharedBlamePreference.setEnabled(true)
            // 1. /git/blame response where commit renamed file from src/OldName.ts -> src/NewName.ts
            server.enqueue(
                mockwebserver3.MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "application/json")
                    .body(
                        """
                        {
                          "ok": true,
                          "data": {
                            "commits": {
                              "1111111122222222333333334444444455555555": {
                                "hash": "1111111122222222333333334444444455555555",
                                "author": "Thang Ho",
                                "authorMail": "<thang@example.com>",
                                "authorTime": 1700000000,
                                "summary": "refactor: rename file",
                                "filename": "src/OldName.ts"
                              }
                            },
                            "lines": [
                              {
                                "hash": "1111111122222222333333334444444455555555",
                                "origLine": 7,
                                "finalLine": 1
                              }
                            ]
                          }
                        }
                        """.trimIndent(),
                    )
                    .build(),
            )
            // 2. /git/commit-line response
            server.enqueue(
                mockwebserver3.MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "application/json")
                    .body(
                        """
                        {
                          "ok": true,
                          "data": {
                            "hash": "1111111122222222333333334444444455555555",
                            "author": "Thang Ho",
                            "authorMail": "<thang@example.com>",
                            "authorTime": 1700000000,
                            "message": "refactor: rename file",
                            "removed": ["const oldVal = 1;"],
                            "added": ["const newVal = 2;"]
                          }
                        }
                        """.trimIndent(),
                    )
                    .build(),
            )

            val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
            val model = com.hienlh.ppm.app.AppModel(app).apply {
                api = com.hienlh.ppm.client.ApiClient(server.url("/"), "secret")
            }

            composeRule.setContent {
                PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                    EditableFileCodeView(
                        model = model,
                        project = "ppm",
                        path = "src/NewName.ts",
                        initialContent = "const newVal = 2;",
                        initialLanguage = "typescript",
                        wrap = true,
                        onToggleWrap = {},
                    )
                }
            }

            composeRule.mainClock.advanceTimeBy(400)
            composeRule.waitForIdle()
            composeRule.waitUntil(5_000) {
                composeRule.mainClock.advanceTimeBy(50)
                server.requestCount >= 1
            }

            val blameReq = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
            org.junit.Assert.assertNotNull(blameReq)
            assertTrue(blameReq!!.url.encodedPath.endsWith("/git/blame"))

            composeRule.waitUntil(5_000) {
                composeRule.mainClock.advanceTimeBy(50)
                composeRule.waitForIdle()
                server.requestCount >= 2
            }

            val commitLineReq = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
            org.junit.Assert.assertNotNull(commitLineReq)
            assertTrue(commitLineReq!!.url.encodedPath.endsWith("/git/commit-line"))
            assertEquals("src/OldName.ts", commitLineReq.url.queryParameter("path"))
            assertEquals("7", commitLineReq.url.queryParameter("line"))
        } finally {
            SharedBlamePreference.setEnabled(false)
            DeviceLspPreference.setEnabled(true)
            server.close()
        }
    }

    @Test
    fun conflictEditorContent_flushesUnsavedManualEditsOnDisposePreservingCrlf() {
        var savedContent = ""
        val mounted = androidx.compose.runtime.mutableStateOf(true)

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                if (mounted.value) {
                    ConflictEditorContent(
                        fileName = "Conflict.ts",
                        initialContent = "a\r\nb",
                        onSaveContent = { savedContent = it },
                    )
                }
            }
        }

        // Type '#' via the mobile symbol bar and immediately unmount before the 1000ms debounce fires
        composeRule.onNodeWithTag("mobile-key-sym-#").performClick()
        mounted.value = false
        composeRule.waitForIdle()

        assertEquals("#a\r\nb", savedContent)
    }

    @Test
    fun conflictEditorTabBody_showsSaveErrorAndHaltsWhenWriteAnswers403() {
        val server = mockwebserver3.MockWebServer()
        server.start()
        try {
            val conflicted = listOf(
                "<<<<<<< HEAD",
                "const v = 1;",
                "=======",
                "const v = 2;",
                ">>>>>>> branch",
            ).joinToString("\n")
            val escaped = conflicted.replace("\n", "\\n")
            // 1. GET /files/read succeeds
            server.enqueue(
                mockwebserver3.MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "application/json")
                    .body("""{"ok":true,"data":{"content":"$escaped","path":"src/Conflict.ts"}}""")
                    .build(),
            )
            // 2. PUT /files/write answers 403 Forbidden
            server.enqueue(
                mockwebserver3.MockResponse.Builder()
                    .code(403)
                    .addHeader("Content-Type", "application/json")
                    .body("""{"ok":false,"error":"Read-only conflict file"}""")
                    .build(),
            )

            val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
            val model = com.hienlh.ppm.app.AppModel(app).apply {
                api = com.hienlh.ppm.client.ApiClient(server.url("/"), "secret")
            }
            val tab = com.hienlh.ppm.state.workspace.WorkspaceTab(
                id = "tab-conflict-1",
                type = "conflict-editor",
                title = "Conflict.ts",
                projectId = "ppm",
                closable = true,
                metadata = kotlinx.serialization.json.buildJsonObject {
                    put("projectName", kotlinx.serialization.json.JsonPrimitive("ppm"))
                    put("filePath", kotlinx.serialization.json.JsonPrimitive("src/Conflict.ts"))
                },
            )

            composeRule.setContent {
                PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                    ConflictEditorTabBody(model = model, tab = tab)
                }
            }

            composeRule.waitUntil(5_000) {
                composeRule.mainClock.advanceTimeBy(50)
                composeRule.waitForIdle()
                server.requestCount >= 1
            }
            composeRule.onNodeWithTag("conflict-accept-current-0").assertIsDisplayed()
            composeRule.onNodeWithTag("conflict-accept-current-0").performClick()

            composeRule.waitUntil(5_000) {
                composeRule.mainClock.advanceTimeBy(50)
                composeRule.waitForIdle()
                server.requestCount >= 2
            }
            composeRule.waitUntil(5_000) {
                runCatching {
                    composeRule.onNodeWithTag("editor-save-error-banner").assertIsDisplayed()
                    true
                }.getOrDefault(false)
            }

            // Save error banner and toast must be shown, and 403 must not loop retries
            assertTrue(model.toasts.isNotEmpty())
            composeRule.mainClock.advanceTimeBy(2_500)
            composeRule.waitForIdle()
            assertEquals(2, server.requestCount)
        } finally {
            server.close()
        }
    }
}
