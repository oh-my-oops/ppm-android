package com.hienlh.ppm.app.ui.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hienlh.ppm.app.ui.files.installBlankFileIcons
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.editor.FileProblems
import com.hienlh.ppm.state.editor.LspCompletionEntry
import com.hienlh.ppm.state.editor.LspDiagnostic
import com.hienlh.ppm.state.editor.LspDocumentStatus
import com.hienlh.ppm.state.editor.LspMissingServer
import com.hienlh.ppm.state.editor.LspPosition
import com.hienlh.ppm.state.editor.LspRange
import com.hienlh.ppm.state.editor.ProblemTotals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xxhdpi")
class Phase07cLspUiTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @Test
    fun lspStatusChip_offStateTogglesOn() {
        var toggledTo: Boolean? = null
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                LspStatusChip(
                    enabled = false,
                    onToggle = { toggledTo = it },
                    status = null,
                    diagnostics = emptyList(),
                )
            }
        }
        composeRule.onNodeWithTag("editor-lsp-chip-off").assertIsDisplayed().performClick()
        assertEquals(true, toggledTo)
    }

    @Test
    fun lspStatusDetailsContent_unavailableServerTriggersInstallAndDeviceOffToggle() {
        var installedId: String? = null
        var deviceEnabled = true
        val missing = LspMissingServer(
            id = "ts",
            displayName = "TypeScript",
            installHint = "bun add -g typescript-language-server",
            installable = true,
            installWith = "npm",
        )
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                LspStatusDetailsContent(
                    status = LspDocumentStatus.Unavailable(
                        reason = "not-installed",
                        server = missing,
                        message = "TypeScript language server is not installed.",
                    ),
                    errors = 0,
                    warnings = 0,
                    onToggle = { deviceEnabled = it },
                    onInstall = { id -> installedId = id },
                )
            }
        }

        composeRule.onNodeWithText("Install TypeScript").assertIsDisplayed()
        composeRule.onNodeWithTag("lsp-install-btn").performClick()
        composeRule.waitForIdle()
        assertEquals("ts", installedId)

        composeRule.onNodeWithTag("lsp-copy-cmd-btn").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("lsp-device-toggle").performClick()
        assertEquals(false, deviceEnabled)
    }

    @Test
    fun problemsPanelContent_displaysCountsAndJumpsToDiagnosticLine() {
        installBlankFileIcons()
        var jumpedPath: String? = null
        var jumpedLine: Int? = null
        val diag1 = LspDiagnostic(
            message = "Type 'string' is not assignable to type 'number'.",
            severity = 1,
            source = "ts",
            code = "2322",
            range = LspRange(LspPosition(14, 4), LspPosition(14, 10)),
        )
        val diag2 = LspDiagnostic(
            message = "Unused variable 'tmp'",
            severity = null, // Missing severity -> Warning (2)
            source = "eslint",
            code = "no-unused-vars",
            range = LspRange(LspPosition(2, 0), LspPosition(2, 3)),
        )
        val group = FileProblems(
            projectName = "ppm",
            filePath = "src/web/use-lsp.ts",
            diagnostics = listOf(diag2, diag1),
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                ProblemsPanelContent(
                    hasAnyOpenFileProblems = true,
                    totals = ProblemTotals(errors = 1, warnings = 1),
                    groups = listOf(group),
                    query = "",
                    onQueryChange = {},
                    onJumpToDiagnostic = { f, d ->
                        jumpedPath = f.filePath
                        jumpedLine = d.range.start.line + 1
                    },
                )
            }
        }

        composeRule.onNodeWithTag("problems-panel").assertIsDisplayed()
        composeRule.onNodeWithText("use-lsp.ts").assertIsDisplayed()
        composeRule.onNodeWithTag("problems-row-src/web/use-lsp.ts-1").performClick()
        assertEquals("src/web/use-lsp.ts", jumpedPath)
        assertEquals(15, jumpedLine)
    }

    @Test
    fun lspCompletionPopup_rendersSuggestionsAndSelectsItem() {
        var selectedLabel: String? = null
        val items = listOf(
            LspCompletionEntry(
                label = "parseLspCompletions",
                detail = "(JsonElement?) -> List<LspCompletionEntry>",
                kindName = "Function",
                insertText = "parseLspCompletions(\$1)",
                isSnippet = true,
            ),
        )
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                LspCompletionPopup(
                    items = items,
                    onSelect = { selectedLabel = it.label },
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithTag("lsp-completion-popup").assertIsDisplayed()
        composeRule.onNodeWithTag("lsp-completion-item-0").performClick()
        assertEquals("parseLspCompletions", selectedLabel)
    }

    @Test
    fun sharedProjectLspRegistry_reusesSingleBridgePerProjectAndStopsConnectLoopWhenDisabled() {
        val server = mockwebserver3.MockWebServer()
        server.start()
        try {
            SharedProjectLspRegistry.disconnectAll()
            DeviceLspPreference.setEnabled(true)
            val api = com.hienlh.ppm.client.ApiClient(server.url("/"), "secret")

            val bridgeTab1 = SharedProjectLspRegistry.getOrCreate(api, "ppm")
            val bridgeTab2 = SharedProjectLspRegistry.getOrCreate(api, "ppm")
            org.junit.Assert.assertSame(bridgeTab1, bridgeTab2)
            assertEquals(1, SharedProjectLspRegistry.activeBridgeCount())

            bridgeTab1.openDocument("src/a.ts", "inmemory://model/1", "const a = 1;")
            org.junit.Assert.assertTrue(bridgeTab1.isConnectedOrConnecting)

            // Turning off DeviceLspPreference disconnects all active bridges and stops reconnect loops
            DeviceLspPreference.setEnabled(false)
            org.junit.Assert.assertFalse(bridgeTab1.isConnectedOrConnecting)
            assertEquals(0, SharedProjectLspRegistry.activeBridgeCount())
        } finally {
            DeviceLspPreference.setEnabled(false)
            SharedProjectLspRegistry.disconnectAll()
            server.close()
        }
    }

    @Test
    fun lspStatusDetailsContent_readyStateOpensProblemsTabOnClick() {
        var problemsOpened = false
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                LspStatusDetailsContent(
                    status = LspDocumentStatus.Ready(
                        languageId = "typescript",
                        server = com.hienlh.ppm.state.editor.LspServerInfo(
                            id = "ts",
                            displayName = "typescript-language-server",
                            rootPath = "/repo",
                        ),
                        projectPath = "/repo",
                    ),
                    errors = 2,
                    warnings = 1,
                    onToggle = {},
                    onOpenProblems = { problemsOpened = true },
                )
            }
        }

        composeRule.onNodeWithTag("lsp-open-problems-btn").assertIsDisplayed().performClick()
        org.junit.Assert.assertTrue(problemsOpened)
    }
}
