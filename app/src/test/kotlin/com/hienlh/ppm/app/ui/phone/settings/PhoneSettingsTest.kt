package com.hienlh.ppm.app.ui.phone.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ui.SettingsCategory
import com.hienlh.ppm.app.ui.TABLET_DETAIL_MAX_WIDTH
import com.hienlh.ppm.app.ui.TABLET_RAIL_LANDSCAPE_WIDTH
import com.hienlh.ppm.app.ui.TABLET_RAIL_PORTRAIT_WIDTH
import com.hienlh.ppm.app.ui.TabletSettingsLayout
import com.hienlh.ppm.app.ui.tabletSettingsRailWidth
import com.hienlh.ppm.app.ui.phone.chat.PhoneModelEffortSheet
import com.hienlh.ppm.app.ui.phone.chat.PhoneModelOption
import com.hienlh.ppm.app.ui.phone.chat.PhoneProviderOption
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ServerProfile
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.ModelOption
import com.hienlh.ppm.state.settings.NotificationSettingsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h844dp-xxhdpi")
class PhoneSettingsTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    @Test
    fun `phone settings route model gives every settings category a full page`() {
        assertEquals(
            listOf(
                "Accounts", "AI Provider", "Memory", "Tools", "MCP Servers", "AI Resources", "PPM Assistant", "Vault", "Design",
                "Notifications", "Theme", "Voice Input", "Keyboard Shortcuts", "Servers", "Schedules", "Storage", "About",
                "Remote Access", "Cloud & Share", "API Proxy", "Extensions", "Jira", "File Filters", "Language Servers",
                "Database Drivers", "Remote Desktop",
            ),
            PhoneSettingsSection.entries.map { it.title },
        )
        assertEquals(
            PhoneSettingsRoute.FullPage(PhoneSettingsSection.Accounts),
            resolvePhoneSettingsRoute("accounts"),
        )
        assertEquals(
            PhoneSettingsRoute.FullPage(PhoneSettingsSection.AiProvider),
            resolvePhoneSettingsRoute("ai"),
        )
        assertEquals(
            PhoneSettingsRoute.FullPage(PhoneSettingsSection.Memory),
            resolvePhoneSettingsRoute("memory"),
        )
        assertEquals(
            PhoneSettingsRoute.FullPage(PhoneSettingsSection.Notifications),
            resolvePhoneSettingsRoute("notifications"),
        )
        assertEquals(
            PhoneSettingsRoute.FullPage(PhoneSettingsSection.Theme),
            resolvePhoneSettingsRoute("appearance"),
        )
        assertEquals(
            PhoneSettingsRoute.FullPage(PhoneSettingsSection.Theme),
            resolvePhoneSettingsRoute("theme"),
        )
        assertEquals(
            PhoneSettingsRoute.FullPage(PhoneSettingsSection.Servers),
            resolvePhoneSettingsRoute("servers"),
        )
        assertEquals(
            PhoneSettingsRoute.FullPage(PhoneSettingsSection.About),
            resolvePhoneSettingsRoute("about"),
        )
        assertEquals(
            PhoneSettingsRoute.FullPage(PhoneSettingsSection.Schedules),
            resolvePhoneSettingsRoute("schedules"),
        )
        // Storage, which the web's Query Audit Log section folded into.
        assertEquals(PhoneSettingsRoute.FullPage(PhoneSettingsSection.Storage), resolvePhoneSettingsRoute("storage"))
        assertEquals(PhoneSettingsRoute.FullPage(PhoneSettingsSection.Storage), resolvePhoneSettingsRoute("query-audit"))

        // Every category opens a page of its own on a phone; General's is About.
        for (category in SettingsCategory.entries) {
            val route = resolvePhoneSettingsCategory(category)
            assertTrue("Expected ${category.id} to open a page", route is PhoneSettingsRoute.FullPage)
            assertEquals(category, (route as PhoneSettingsRoute.FullPage).section.category)
        }
        assertEquals(PhoneSettingsRoute.FullPage(PhoneSettingsSection.About), resolvePhoneSettingsRoute("general"))
        assertEquals(PhoneSettingsRoute.FullPage(PhoneSettingsSection.RemoteAccess), resolvePhoneSettingsRoute("remote-access"))
        assertEquals(PhoneSettingsRoute.FullPage(PhoneSettingsSection.Shortcuts), resolvePhoneSettingsRoute("keybindings"))
        assertEquals(PhoneSettingsRoute.FullPage(PhoneSettingsSection.Assistant), resolvePhoneSettingsRoute("ppmbot"))

        // A server started with --minimal leaves out the sections it does not run, as the web's list does.
        val minimal = PhoneSettingsGroup.entries.flatMap { PhoneSettingsSection.forGroup(it, minimalMode = true) }
        assertTrue(PhoneSettingsSection.RemoteAccess in minimal && PhoneSettingsSection.Shortcuts in minimal)
        assertFalse(PhoneSettingsSection.Jira in minimal || PhoneSettingsSection.Storage in minimal)

        // Tablet rail widths (Requirement 15.3)
        assertEquals(TABLET_RAIL_LANDSCAPE_WIDTH, tabletSettingsRailWidth(1280.dp))
        assertEquals(336.dp, TABLET_RAIL_LANDSCAPE_WIDTH)
        assertEquals(TABLET_RAIL_PORTRAIT_WIDTH, tabletSettingsRailWidth(800.dp))
        assertEquals(300.dp, TABLET_RAIL_PORTRAIT_WIDTH)
        assertEquals(760.dp, TABLET_DETAIL_MAX_WIDTH)
    }

    @Test
    fun `phone settings home list lists every section and a tap picks it`() {
        var selectedSection by mutableStateOf<PhoneSettingsSection?>(null)

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneSettingsHomeList(onSelectSection = { selectedSection = it })
            }
        }

        for (section in PhoneSettingsSection.entries) {
            composeRule.onNodeWithTag("phone-settings-row-${section.id}").assertExists()
        }
        composeRule.onNodeWithTag("phone-settings-row-accounts").performClick()
        assertEquals(PhoneSettingsSection.Accounts, selectedSection)
        composeRule.onNodeWithTag("phone-settings-row-jira").performScrollTo().performClick()
        assertEquals(PhoneSettingsSection.Jira, selectedSection)
        assertEquals(0, composeRule.onAllNodesWithText("on a tablet", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `phone AI Provider page exposes model sheet, effort segments, permission mode sheet and the row into all provider settings`() {
        var selectedProvider by mutableStateOf("claude")
        var selectedModel by mutableStateOf("claude-sonnet-4-6")
        var selectedEffort by mutableStateOf("high")
        var selectedPermission by mutableStateOf("bypassPermissions")
        var openedAll = false

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneAiProviderContent(
                    providerTabs = listOf("claude", "codex", "antigravity"),
                    activeProvider = selectedProvider,
                    onSelectProvider = { selectedProvider = it },
                    modelValue = selectedModel,
                    models = listOf(
                        ModelOption("claude-sonnet-4-6", "Sonnet 4.6"),
                        ModelOption("claude-opus-4-7", "Opus 4.7"),
                    ),
                    effortValue = selectedEffort,
                    permissionModeValue = selectedPermission,
                    onSelectModel = { selectedModel = it },
                    onSelectEffort = { selectedEffort = it },
                    onSelectPermissionMode = { selectedPermission = it },
                    onOpenAllSettings = { openedAll = true },
                )
            }
        }

        // Effort segmented control
        composeRule.onNodeWithTag("phone-ai-effort-max").performClick()
        assertEquals("max", selectedEffort)

        // Model sheet
        composeRule.onNodeWithTag("phone-ai-model-row").performClick()
        composeRule.onNodeWithTag("phone-ai-model-option-claude-opus-4-7", useUnmergedTree = true).performClick()
        assertEquals("claude-opus-4-7", selectedModel)

        // Default Permission Mode sheet
        composeRule.onNodeWithTag("phone-ai-permission-row").performClick()
        composeRule.onNodeWithTag("phone-ai-permission-option-plan", useUnmergedTree = true).performClick()
        assertEquals("plan", selectedPermission)

        // Keys, budgets, MCP, teams and instructions: one level down.
        composeRule.onNodeWithTag("phone-ai-all-settings").performScrollTo().performClick()
        assertTrue(openedAll)
    }

    @Test
    fun `phone Accounts, Notifications, Theme, Servers and About pages handle core actions`() {
        var pageIndex by mutableStateOf(0)
        var toggledAccount by mutableStateOf<Pair<String, Boolean>?>(null)
        var notifySettings by mutableStateOf(NotificationSettingsState())
        var systemNotifyClicked by mutableStateOf(false)
        var pickedThemeMode by mutableStateOf("dark")
        var pickedServerId by mutableStateOf("srv-1")

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                when (pageIndex) {
                    0 -> PhoneAccountsContent(
                        activeProvider = "claude",
                        onSelectProvider = {},
                        cards = listOf(
                            PhoneAccountCardData(
                                id = "acc-1",
                                provider = "claude",
                                label = "Team Max",
                                subtitle = "dev@example.com",
                                status = "active",
                                isActive = true,
                                meters = listOf(PhoneUsageMeterItem("5-hour session", 42, "2h 10m")),
                            ),
                        ),
                        onToggleAccount = { card, enabled -> toggledAccount = card.id to enabled },
                        onDeleteAccount = {},
                    )
                    1 -> PhoneNotificationsContent(
                        settings = notifySettings,
                        notificationsEnabledOnDevice = false,
                        onOpenSystemNotificationSettings = { systemNotifyClicked = true },
                        onUpdateSettings = { notifySettings = it },
                    )
                    2 -> PhoneThemeContent(
                        activeThemeId = BuiltInThemes.auroraDark.id,
                        activeMode = pickedThemeMode,
                        onSelectMode = { pickedThemeMode = it },
                        onSelectTheme = {},
                    )
                    3 -> PhoneServersContent(
                        servers = listOf(
                            ServerProfile(id = "srv-1", name = "Workstation", url = "https://ppm.example.com"),
                            ServerProfile(id = "srv-2", name = "Mini PC", url = "http://100.64.0.2:8080"),
                        ),
                        selectedServerId = pickedServerId,
                        onSelectServer = { pickedServerId = it.id },
                        onRemoveServer = {},
                        onLogOut = {},
                        onAddServer = {},
                    )
                    else -> PhoneAboutContent(serverVersion = "v0.10.22")
                }
            }
        }

        // Page 0: Accounts
        composeRule.onNodeWithTag("phone-account-card-acc-1").assertIsDisplayed()
        composeRule.onNodeWithTag("phone-account-toggle-acc-1").performClick()
        assertEquals("acc-1" to false, toggledAccount)

        // Page 1: Notifications (including "Notifications are off" banner)
        pageIndex = 1
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Notifications are off").assertIsDisplayed()
        composeRule.onNodeWithTag("phone-notifications-off-banner").performClick()
        assertTrue(systemNotifyClicked)

        // Page 2: Theme
        pageIndex = 2
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("phone-theme-mode-light").performClick()
        assertEquals("light", pickedThemeMode)

        // Page 3: Servers
        pageIndex = 3
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("phone-server-card-srv-2").performClick()
        assertEquals("srv-2", pickedServerId)

        // Page 4: About
        pageIndex = 4
        composeRule.waitForIdle()
        composeRule.onNodeWithText("PPM — Personal Project Manager").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w1280dp-h800dp-xxhdpi")
    fun `tablet settings shows every section as a list beside the selected section detail`() {
        var activeCategory by mutableStateOf(SettingsCategory.General)

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                TabletSettingsLayout(
                    activeCategory = activeCategory,
                    onSelectCategory = { activeCategory = it },
                ) { category ->
                    androidx.compose.foundation.text.BasicText("Detail pane for ${category.label}")
                }
            }
        }

        composeRule.onNodeWithTag("tablet-settings-rail").assertIsDisplayed()
        composeRule.onNodeWithTag("tablet-settings-detail-general").assertIsDisplayed()
        composeRule.onNodeWithText("Detail pane for General").assertIsDisplayed()

        // Selecting another category in the left list updates the detail pane beside it
        composeRule.onNodeWithTag("tablet-settings-category-accounts").performClick()
        assertEquals(SettingsCategory.Accounts, activeCategory)
        composeRule.onNodeWithTag("tablet-settings-detail-accounts").assertIsDisplayed()
        composeRule.onNodeWithText("Detail pane for Accounts").assertIsDisplayed()
    }

    @Test
    fun `phone AI Provider page shows Extra only when the chosen model supports xhigh`() {
        var activeProvider by mutableStateOf("antigravity")
        var selectedModel by mutableStateOf("gemini-4-neon")
        var selectedEffort by mutableStateOf("xhigh")
        val agyModels = listOf(
            ModelOption("gemini-4-neon", "Gemini 4 Neon", isDefault = true, supportedEfforts = listOf("low", "medium", "high", "xhigh")),
            ModelOption("gemini-4-carbon", "Gemini 4 Carbon", supportedEfforts = listOf("low", "medium", "high")),
        )
        val claudeModels = listOf(
            ModelOption("claude-sonnet-5-5", "Claude Sonnet 5.5", supportedEfforts = listOf("low", "medium", "high", "xhigh", "max")),
            ModelOption("claude-sonnet-4-6", "Claude Sonnet 4.6", supportedEfforts = listOf("low", "medium", "high", "max")),
            ModelOption("claude-haiku-4-5", "Claude Haiku 4.5", supportedEfforts = emptyList()),
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneAiProviderContent(
                    providerTabs = listOf("claude", "antigravity"),
                    activeProvider = activeProvider,
                    onSelectProvider = { activeProvider = it },
                    modelValue = selectedModel,
                    models = if (activeProvider == "antigravity") agyModels else claudeModels,
                    includeAutoDefaultModel = activeProvider != "claude",
                    effortValue = selectedEffort,
                    permissionModeValue = "bypassPermissions",
                    onSelectModel = { selectedModel = it },
                    onSelectEffort = { selectedEffort = it },
                    onSelectPermissionMode = {},
                )
            }
        }

        // Antigravity Neon has Extra, no Max
        composeRule.onNodeWithTag("phone-ai-effort-xhigh").assertIsDisplayed()
        composeRule.onNodeWithTag("phone-ai-effort-max").assertDoesNotExist()

        // Switching to Carbon (no xhigh) hides Extra
        selectedModel = "gemini-4-carbon"
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("phone-ai-effort-xhigh").assertDoesNotExist()
        composeRule.onNodeWithTag("phone-ai-effort-high").assertIsDisplayed()

        // Claude Sonnet 5.5 has Extra and Max
        activeProvider = "claude"
        selectedModel = "claude-sonnet-5-5"
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("phone-ai-effort-xhigh").assertIsDisplayed()
        composeRule.onNodeWithTag("phone-ai-effort-max").assertIsDisplayed()

        // Claude Sonnet 4.6 hides Extra, keeps Max
        selectedModel = "claude-sonnet-4-6"
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("phone-ai-effort-xhigh").assertDoesNotExist()
        composeRule.onNodeWithTag("phone-ai-effort-max").assertIsDisplayed()

        // Claude Haiku 4.5 (supportedEfforts: []) hides Extra and Max
        selectedModel = "claude-haiku-4-5"
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("phone-ai-effort-xhigh").assertDoesNotExist()
        composeRule.onNodeWithTag("phone-ai-effort-max").assertDoesNotExist()
        composeRule.onNodeWithTag("phone-ai-effort-high").assertIsDisplayed()
    }

    @Test
    fun `phone Model & Effort sheet shows per-model effort levels`() {
        var selectedModel by mutableStateOf("gemini-4-neon")
        val models = listOf(
            PhoneModelOption(
                id = "gemini-4-neon",
                name = "Gemini 4 Neon",
                description = "Default",
                effortLevels = listOf("Low", "Medium", "High", "Extra"),
            ),
            PhoneModelOption(
                id = "gemini-4-carbon",
                name = "Gemini 4 Carbon",
                description = "",
                effortLevels = listOf("Low", "Medium", "High"),
            ),
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                PhoneModelEffortSheet(
                    providers = listOf(PhoneProviderOption(id = "antigravity", name = "Antigravity", subtitle = "Active provider")),
                    selectedProviderId = "antigravity",
                    models = models,
                    selectedModelId = selectedModel,
                    selectedEffort = "Extra",
                    thinkingEnabled = false,
                    providerLocked = true,
                    onSelectProvider = {},
                    onSelectModel = { selectedModel = it },
                    onSelectEffort = {},
                    onToggleThinking = {},
                    onResetEffortToDefault = {},
                    onDismiss = {},
                )
            }
        }

        // Neon offers Extra
        composeRule.onNodeWithText("Extra").assertIsDisplayed()

        // Switching to Carbon hides Extra and clamps the In effect line to High
        selectedModel = "gemini-4-carbon"
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Extra").assertDoesNotExist()
        composeRule.onNodeWithText("In effect: High", substring = true).assertIsDisplayed()
    }
}
