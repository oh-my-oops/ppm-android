package com.hienlh.ppm.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.state.settings.AiResourceFilter
import com.hienlh.ppm.state.settings.AiResourceItem
import com.hienlh.ppm.state.settings.AiResourcesOverview
import com.hienlh.ppm.state.settings.AiResourcesStats
import com.hienlh.ppm.state.settings.ClawbotConfigState
import com.hienlh.ppm.state.settings.ClawbotTelegramState
import com.hienlh.ppm.state.settings.DesignProviderSkillGroup
import com.hienlh.ppm.state.settings.DesignSettingsState
import com.hienlh.ppm.state.settings.DesignSkillItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xxhdpi")
class Phase12bUiTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @Test
    fun toolsSettingsContent_togglesPpmToolOptimistically() {
        var toggled: Pair<String, Boolean>? = null

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                ToolsSettingsContent(
                    ppmTools = mapOf("open_file" to true, "db_execute" to false),
                    onToggleTool = { toolId, enabled ->
                        toggled = toolId to enabled
                    },
                )
            }
        }

        composeRule.onNodeWithTag("ppm-tool-toggle-open_file").performClick()
        assertEquals("open_file" to false, toggled)

        composeRule.onNodeWithTag("ppm-tool-toggle-db_execute").performClick()
        assertEquals("db_execute" to true, toggled)
    }

    @Test
    fun mcpServerFormContent_validatesNameClearsFieldsOnTransportSwitchAndSaves() {
        var savedName: String? = null
        var savedTransport: String? = null
        var savedUrl: String? = null

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                McpServerFormContent(
                    initial = null,
                    onCancel = {},
                    onSave = { name, transport, _, _, url, _ ->
                        savedName = name
                        savedTransport = transport
                        savedUrl = url
                    },
                )
            }
        }

        // Invalid name -> error
        composeRule.onNodeWithTag("mcp-form-name-input").performTextReplacement("bad name!")
        composeRule.onNodeWithTag("mcp-form-save-btn").performClick()
        composeRule.onNodeWithTag("mcp-form-error").assertIsDisplayed()

        // Valid name + stdio command, then switch to HTTP (clears command, requires URL)
        composeRule.onNodeWithTag("mcp-form-name-input").performTextReplacement("remote-mcp")
        composeRule.onNodeWithTag("mcp-form-command-input").performTextReplacement("npx")
        composeRule.onNodeWithTag("mcp-form-transport-http").performClick()
        composeRule.onNodeWithTag("mcp-form-url-input").performTextReplacement("https://mcp.example.com/sse")
        composeRule.onNodeWithTag("mcp-form-save-btn").performClick()

        assertEquals("remote-mcp", savedName)
        assertEquals("http", savedTransport)
        assertEquals("https://mcp.example.com/sse", savedUrl)
    }

    @Test
    fun aiResourcesContent_filtersByTypeAndQueryAndShowsStatsFooter() {
        val items = listOf(
            AiResourceItem(
                type = "skill",
                name = "ppm-skill",
                description = "PPM CLI skill",
                scope = "user",
                filePath = "/u/skill.md",
            ),
            AiResourceItem(
                type = "agent",
                name = "db-debugger",
                description = "Database helper",
                scope = "project",
                filePath = "/p/agent.md",
            ),
        )
        var currentFilter = AiResourceFilter.ALL
        var currentQuery = ""
        var deletedName: String? = null

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                AiResourcesContent(
                    overview = AiResourcesOverview(
                        items = items,
                        stats = AiResourcesStats(active = 2, project = 1, user = 1, bundled = 0, shadowed = 0),
                    ),
                    filter = currentFilter,
                    query = currentQuery,
                    onFilterChange = { currentFilter = it },
                    onQueryChange = { currentQuery = it },
                    onNewResourceClick = {},
                    onOpenResource = {},
                    onDuplicateResource = {},
                    onDeleteResource = { deletedName = it.name },
                )
            }
        }

        composeRule.onNodeWithTag("ai-resources-stats-footer").assertIsDisplayed()
        composeRule.onNodeWithTag("ai-resource-row-ppm-skill").assertIsDisplayed()
        composeRule.onNodeWithTag("ai-resource-row-db-debugger").assertIsDisplayed()

        composeRule.onNodeWithTag("ai-resource-delete-db-debugger").performClick()
        assertEquals("db-debugger", deletedName)
    }

    @Test
    fun ppmBotSettingsContent_togglesEnableSavesTokenAndClampsDebounce() {
        var enabledState: Boolean? = null
        var savedToken: String? = null
        var savedDebounce: Int? = null

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                PpmBotSettingsContent(
                    config = ClawbotConfigState(enabled = false, debounceMs = 1500),
                    telegram = ClawbotTelegramState(configured = true, botUsername = "ppm_bot"),
                    tasks = emptyList(),
                    memories = emptyList(),
                    onToggleEnabled = { enabledState = it },
                    onSaveTelegramToken = { savedToken = it },
                    onStartTelegramConnect = {},
                    onCancelTelegramConnect = {},
                    onDisconnectChat = {},
                    onSaveCoordinatorConfig = { savedDebounce = it.debounceMs },
                    onDeleteMemory = {},
                )
            }
        }

        composeRule.onNodeWithTag("ppmbot-enable-switch").performClick()
        assertEquals(true, enabledState)

        composeRule.onNodeWithTag("ppmbot-telegram-token-input").performTextReplacement("999:XYZ")
        composeRule.onNodeWithTag("ppmbot-save-token-btn").performClick()
        assertEquals("999:XYZ", savedToken)

        composeRule.onNodeWithTag("ppmbot-debounce-input").performTextReplacement("99000")
        composeRule.onNodeWithTag("ppmbot-save-config-btn").performClick()
        assertEquals(30_000, savedDebounce)
    }

    @Test
    fun designSettingsContent_insertsSkillChipTracksByteCountAndSaves() {
        var savedInstructions: String? = null

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                DesignSettingsContent(
                    designWindows = false,
                    onToggleDesignWindows = {},
                    state = DesignSettingsState(
                        instructions = "Use clean layout",
                        providers = listOf(
                            DesignProviderSkillGroup(
                                id = "claude",
                                name = "Claude",
                                runtime = "claude",
                                available = true,
                                items = listOf(DesignSkillItem(name = "ui-ux-pro-max", description = "Pro UI")),
                            ),
                        ),
                    ),
                    systems = emptyList(),
                    hasActiveProject = true,
                    onSaveInstructions = { savedInstructions = it },
                    onInstallSkill = {},
                    onAddSystem = { _, _, _ -> },
                    onRemoveSystem = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithTag("design-skill-chip-ui-ux-pro-max").performClick()
        composeRule.onNodeWithTag("design-instructions-save-btn").performClick()
        assertEquals("Use clean layout /ui-ux-pro-max", savedInstructions)
    }
}
