package com.hienlh.ppm.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.settings.DbDriverRow
import com.hienlh.ppm.state.settings.FileFiltersState
import com.hienlh.ppm.state.settings.LspServerRow
import com.hienlh.ppm.state.settings.LspServersOverview
import com.hienlh.ppm.state.settings.QueryAuditState
import com.hienlh.ppm.state.settings.RelayStatusState
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
class Phase12aUiTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @Test
    fun changePasswordBlock_validatesMismatchAndSubmitsWhenMatching() {
        var submittedPair: Pair<String, String>? = null

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                ChangePasswordBlock(
                    onSubmit = { pwd, confirm ->
                        submittedPair = pwd to confirm
                    },
                )
            }
        }

        composeRule.onNodeWithTag("settings-password-input").performTextReplacement("secret1")
        composeRule.onNodeWithTag("settings-password-confirm").performTextReplacement("secret2")
        composeRule.onNodeWithTag("settings-password-submit").performClick()

        composeRule.onNodeWithText("Passwords do not match").assertIsDisplayed()
        assertEquals(null, submittedPair)

        composeRule.onNodeWithTag("settings-password-confirm").performTextReplacement("secret1")
        composeRule.onNodeWithTag("settings-password-submit").performClick()

        assertEquals("secret1" to "secret1", submittedPair)
        composeRule.onNodeWithText("Password updated").assertIsDisplayed()
    }

    @Test
    fun fileFiltersContent_switchesScopeAndAddsRemovesPatterns() {
        var scope = "global"
        var latestExclude = listOf(".git", "node_modules")
        var ignoreToggled: Boolean? = null

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                FileFiltersContent(
                    activeProject = "ppm-android",
                    scopeSelection = scope,
                    onScopeChange = { scope = it },
                    filters = FileFiltersState(
                        filesExclude = latestExclude,
                        searchExclude = listOf("dist"),
                        useIgnoreFiles = true,
                    ),
                    loading = false,
                    onToggleIgnoreFiles = { ignoreToggled = it },
                    onUpdateFilesExclude = { latestExclude = it },
                    onUpdateSearchExclude = {},
                    onResetProjectToGlobal = {},
                )
            }
        }

        composeRule.onNodeWithTag("files-scope-project").performClick()
        assertEquals("ppm-android", scope)

        composeRule.onNodeWithTag("files-use-ignore-toggle").performClick()
        assertEquals(false, ignoreToggled)

        composeRule.onNodeWithTag("files-exclude-input").performTextReplacement("*.log")
        composeRule.onNodeWithTag("files-exclude-add").performClick()
        assertEquals(listOf(".git", "node_modules", "*.log"), latestExclude)
    }

    @Test
    fun queryAuditContent_enablesSaveWhenDirtyAndPerformsTwoStepClear() {
        var savedCount = 0
        var clearedCount = 0

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                QueryAuditContent(
                    state = QueryAuditState(
                        retentionDays = 60,
                        maxSizeMb = 500,
                        entryCount = 42L,
                        sizeBytes = 1024L * 1024L,
                    ),
                    retentionInput = "30",
                    maxSizeInput = "500",
                    saving = false,
                    clearing = false,
                    statusMsg = null,
                    isError = false,
                    onRetentionChange = {},
                    onMaxSizeChange = {},
                    onSave = { savedCount++ },
                    onClearLogs = { clearedCount++ },
                )
            }
        }

        composeRule.onNodeWithTag("query-audit-save").performClick()
        assertEquals(1, savedCount)

        composeRule.onNodeWithTag("query-audit-clear").performClick()
        composeRule.onNodeWithTag("query-audit-clear-confirm").performClick()
        assertEquals(1, clearedCount)
    }

    @Test
    fun languageServersDriversAndRelay_triggerInstallAndRemoveCallbacks() {
        var installedLspId: String? = null
        var installedDriverId: String? = null
        var relayInstallClicked = false

        val lspOverview = LspServersOverview(
            installDir = "/home/user/.ppm/lsp/node_modules/.bin",
            servers = listOf(
                LspServerRow(
                    id = "typescript",
                    displayName = "TypeScript",
                    languages = listOf("ts", "tsx"),
                    installed = false,
                    origin = null,
                    installable = true,
                    removable = false,
                    alsoRemoves = emptyList(),
                    installHint = "npm i -g typescript-language-server",
                ),
            ),
            running = emptyList(),
        )
        val drivers = listOf(
            DbDriverRow(
                id = "mssql",
                displayName = "SQL Server",
                engines = listOf("mssql"),
                usedFor = "SQL Server connections",
                pkg = "mssql",
                version = "11.0.1",
                license = "MIT",
                state = "missing",
            ),
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                androidx.compose.foundation.layout.Column {
                    LanguageServersContent(
                        lspEnabled = true,
                        overview = lspOverview,
                        loading = false,
                        busyServerId = null,
                        busyAction = null,
                        onToggleLspEnabled = {},
                        onRefresh = {},
                        onInstall = { installedLspId = it.id },
                        onRequestUninstall = {},
                        onCopyHint = {},
                    )
                    DatabaseDriversContent(
                        drivers = drivers,
                        busyIds = emptySet(),
                        onInstall = { installedDriverId = it.id },
                        onRequestUninstall = {},
                    )
                    RemoteDesktopRelayContent(
                        relay = RelayStatusState(installed = false, available = true),
                        busy = false,
                        onInstall = { relayInstallClicked = true },
                        onUninstall = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("lsp-install-typescript").performClick()
        assertEquals("typescript", installedLspId)

        composeRule.onNodeWithTag("db-driver-install-mssql").performClick()
        assertEquals("mssql", installedDriverId)

        composeRule.onNodeWithTag("rd-relay-download").performClick()
        assertTrue(relayInstallClicked)
    }

    @Test
    fun copySensitiveClip_marksClipWithExtraIsSensitiveAndCustomThemeResolvesTokens() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val clip = copySensitiveClip(ctx, "Secret Key", "sk-secret-123")
        val extras = clip.description.extras
        assertTrue(extras != null && extras.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE))

        val customTheme = com.hienlh.ppm.design.PpmTheme.fromCustom(
            id = "catppuccin-mocha",
            name = "Catppuccin Mocha",
            mode = "dark",
            tokenMap = mapOf(
                "bgSolid" to "#1e1e2e",
                "panel" to "#181825",
                "text" to "#cdd6f4",
                "accent" to "#cba6f7",
                "rad" to "14px",
            ),
            swatchStrings = listOf("#1e1e2e", "#cba6f7", "#89b4fa"),
        )
        val resolved = com.hienlh.ppm.design.PpmTheme.resolve(
            style = "custom",
            mode = "dark",
            systemIsDark = true,
            customThemes = listOf(customTheme),
            customThemeId = "catppuccin-mocha",
        )
        assertEquals("catppuccin-mocha", resolved.id)
        assertEquals(com.hienlh.ppm.design.Rgba.hex("#cba6f7"), resolved.tokens.accent)
        assertEquals(com.hienlh.ppm.design.Rgba.hex("#1e1e2e"), resolved.tokens.bgSolid)
        assertEquals(14.0, resolved.tokens.rad, 0.001)
    }
}
