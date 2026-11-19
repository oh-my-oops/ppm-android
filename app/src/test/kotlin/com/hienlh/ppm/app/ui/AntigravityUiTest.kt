package com.hienlh.ppm.app.ui

import android.app.Application
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.AntigravityAccount
import com.hienlh.ppm.protocol.LimitBucket
import com.hienlh.ppm.protocol.UsageInfo
import com.hienlh.ppm.state.AntigravityAccountsData
import com.hienlh.ppm.state.settings.AntigravityLoginFlow
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xxhdpi")
class AntigravityUiTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private fun bucket(utilization: Double, resetsAt: String, label: String? = null) =
        LimitBucket(utilization, resetsAt, resetsInMinutes = null, resetsInHours = null, windowHours = 5.0, label = label)

    @Test
    fun footerUsageCluster_includesClaudeCodexAndAntigravityOnly() {
        var provider by mutableStateOf("antigravity")
        val sample = UsageInfo(
            fiveHour = 0.42,
            sevenDay = 0.15,
            activeAccountId = "acc-1",
            activeAccountLabel = "work@example.com · Pro",
        )
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                ChatFooterTools(
                    usageInfo = sample,
                    providerId = provider,
                    narrow = false,
                )
            }
        }

        for (supported in listOf("", "claude", "codex", "antigravity")) {
            provider = supported
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("usage-ring").assertIsDisplayed()
        }
        for (unsupported in listOf("cursor", "gemini", "mock")) {
            provider = unsupported
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("usage-ring").assertDoesNotExist()
        }
    }

    @Test
    fun antigravityUsageRing_showsLabelledWindowsAndOpensAntigravityUsagePopover() {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                val host = androidx.compose.runtime.remember { FooterPopoverHostState() }
                androidx.compose.runtime.CompositionLocalProvider(LocalFooterPopoverHost provides host) {
                    androidx.compose.foundation.layout.Column {
                        FooterPopoverHostSlot(host)
                        ChatFooterTools(
                            usageInfo = UsageInfo(
                                fiveHour = 0.42,
                                sevenDay = 0.15,
                                activeAccountId = "acc-1",
                                activeAccountLabel = "work@example.com · Pro",
                            ),
                            providerId = "antigravity",
                            narrow = false,
                            antigravityUsageContent = {
                                androidx.compose.foundation.text.BasicText("Antigravity Usage Panel Body")
                            },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithContentDescription("Usage: work@example.com · Pro · 5h 42% · Week 15%").assertIsDisplayed()
        composeRule.onNodeWithText("5h", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("42%", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Wk", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("15%", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("usage-ring").performClick()
        composeRule.onNodeWithText("Antigravity Usage Panel Body").assertIsDisplayed()
        composeRule.onNodeWithTag("chat-more-menu").assertDoesNotExist()
    }

    @Test
    fun antigravityUsagePanel_ambientFallbackShowsDesktopCardAndBuckets() {
        val data = AntigravityAccountsData(
            object : AntigravityAccountsData.Source {
                override suspend fun accounts() = emptyList<AntigravityAccount>()
                override suspend fun usages() = emptyMap<String, UsageInfo>()
                override suspend fun setStatus(id: String, status: String) = Unit
            },
        )
        var openedAccounts = 0

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                AntigravityUsagePanelContent(
                    data = data,
                    usage = UsageInfo(
                        activeAccountId = "ambient@example.com",
                        session = bucket(0.25, "2026-11-10T03:00:00Z"),
                        weekly = bucket(0.60, "2026-11-14T00:00:00Z"),
                        weeklyScoped = listOf(bucket(0.10, "2026-11-10T02:00:00Z", "Claude · 5-Hour")),
                    ),
                    loading = false,
                    lastFetchedAt = "2026-11-10T00:00:00Z",
                    selectedAccountId = null,
                    now = { 1_794_268_860_000L }, // 2026-11-10T00:01:00Z
                    onManageAccounts = { openedAccounts++ },
                    onSelectAccount = null,
                    onReload = null,
                    onClose = {},
                )
            }
        }

        composeRule.onNodeWithText("Antigravity Usage").assertIsDisplayed()
        composeRule.onNodeWithText("Antigravity Desktop").assertIsDisplayed()
        composeRule.onNodeWithText("ambient@example.com").assertIsDisplayed()
        composeRule.onNodeWithText("Gemini · 5-Hour").assertIsDisplayed()
        composeRule.onNodeWithText("Gemini · Weekly").assertIsDisplayed()
        composeRule.onNodeWithText("Claude · 5-Hour").assertIsDisplayed()

        composeRule.onNodeWithText("Manage accounts").performClick()
        assertEquals(1, openedAccounts)
    }

    @Test
    fun antigravityUsagePanel_managedAccountsMatchActiveByEmailFormatScopedLabelsFlagStaleAndSelectWithDisplayLabel() {
        val nowMs = 1_794_270_000_000L // 2026-11-10T00:20:00Z
        val freshIso = "2026-11-10T00:18:00Z" // 2m old -> fresh
        val staleIso = "2026-11-10T00:00:00Z" // 20m old -> stale (> 11m)
        val accounts = listOf(
            AntigravityAccount(id = "default", label = "Default", email = "ambient@example.com", planType = "Free"),
            AntigravityAccount(id = "acc-pro", label = "Work", email = "work@example.com", planType = "Pro"),
            AntigravityAccount(id = "acc-off", label = "Disabled Acc", status = "disabled"),
        )
        val usages = mapOf(
            "default" to UsageInfo(
                fiveHour = 0.2,
                session = bucket(0.2, "2026-11-10T04:00:00Z"),
                weeklyScoped = listOf(
                    bucket(0.35, "2026-11-10T04:00:00Z", "Claude · 5-Hour"),
                    bucket(0.15, "2026-11-14T00:00:00Z", "Opus"),
                ),
                lastFetchedAt = freshIso,
            ),
            "acc-pro" to UsageInfo(
                fiveHour = 0.1,
                session = bucket(0.1, "2026-11-10T04:00:00Z"),
                lastFetchedAt = staleIso,
            ),
        )
        val data = AntigravityAccountsData(
            object : AntigravityAccountsData.Source {
                override suspend fun accounts() = accounts
                override suspend fun usages() = usages
                override suspend fun setStatus(id: String, status: String) = Unit
            },
        )
        var picked: Pair<String, String?>? = null

        composeRule.setContent {
            LaunchedEffect(data) { data.load() }
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                AntigravityUsagePanelContent(
                    data = data,
                    usage = UsageInfo(activeAccountId = "ambient@example.com"),
                    loading = false,
                    lastFetchedAt = freshIso,
                    selectedAccountId = null,
                    now = { nowMs },
                    onManageAccounts = {},
                    onSelectAccount = { id, label ->
                        picked = id to label
                        null
                    },
                    onReload = {},
                    onClose = {},
                )
            }
        }
        composeRule.waitForIdle()

        // Active matched by email on "default"
        composeRule.onNodeWithText("Active").assertIsDisplayed()
        // Scoped bucket labels: "Claude · 5-Hour" stays as-is, "Opus" becomes "Weekly (Opus)"
        composeRule.onNodeWithText("Claude · 5-Hour").assertIsDisplayed()
        composeRule.onNodeWithText("Weekly (Opus)").assertIsDisplayed()
        // Fresh vs stale indicator tags
        composeRule.onNodeWithTag("account-card-last-read-default-fresh").assertIsDisplayed()
        composeRule.onNodeWithTag("account-card-last-read-acc-pro-stale").assertIsDisplayed()

        // Selecting disabled account shows refusal error and does not invoke onSelectAccount
        val useButtons = composeRule.onAllNodes(androidx.compose.ui.test.hasText("Use"))
        assertEquals(2, useButtons.fetchSemanticsNodes().size)
        useButtons[1].performClick()
        composeRule.waitForIdle()
        assertNull(picked)
        composeRule.onNodeWithText("Cannot switch to this account — disabled.").assertIsDisplayed()

        // Selecting active/selectable Pro account passes displayLabel "Work · Pro"
        useButtons[0].performClick()
        composeRule.waitForIdle()
        assertEquals("acc-pro" to "Work · Pro", picked)
    }

    @Test
    fun accountsSettingsSection_switchesToAntigravityTabAndShowsEmptyState() {
        assertEquals("Claude, Codex and Antigravity sign-ins, rotation", SettingsCategory.Accounts.subtitle)
        val model = AppModel(ApplicationProvider.getApplicationContext<Application>())

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                AccountsSettingsSection(model)
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("accounts-tab-antigravity").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("antigravity-empty-state").assertIsDisplayed()
        composeRule.onNodeWithText("No Antigravity accounts yet.").assertIsDisplayed()
    }

    @Test
    fun antigravityAccountsContent_rendersDefaultVsIsolatedCardsActionsStrategyAndStaleWarning() {
        val nowMs = 1_794_270_000_000L // 2026-11-10T00:20:00Z
        val freshIso = "2026-11-10T00:18:00Z"
        val staleIso = "2026-11-10T00:00:00Z"
        val defaultAcc = AntigravityAccount(id = "default", label = "Default", email = "local@example.com", planType = "Free")
        val isolatedAcc = AntigravityAccount(id = "acc-google", label = "Work", email = "work@example.com", planType = "Pro")

        var selectedStrategy by mutableStateOf("round-robin")
        var toggledId: String? = null
        var reloginId: String? = null
        var deletedId: String? = null

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                AntigravityAccountsContent(
                    accounts = listOf(defaultAcc, isolatedAcc),
                    usages = mapOf(
                        "default" to UsageInfo(
                            fiveHour = 0.25,
                            session = bucket(0.25, "2026-11-10T04:00:00Z"),
                            lastFetchedAt = freshIso,
                        ),
                        "acc-google" to UsageInfo(
                            fiveHour = 0.40,
                            session = bucket(0.40, "2026-11-10T04:00:00Z"),
                            weeklyScoped = listOf(bucket(0.55, "2026-11-12T00:00:00Z", "Claude · 5-Hour")),
                            lastFetchedAt = staleIso,
                        ),
                    ),
                    strategy = selectedStrategy,
                    onSelectStrategy = { selectedStrategy = it },
                    onToggleAccount = { toggledId = it.id },
                    onOpenRelogin = { reloginId = it.id },
                    onDeleteAccount = { deletedId = it.id },
                    now = nowMs,
                )
            }
        }

        // Default account shows ~/.gemini badge and hides Sign in again / Delete
        composeRule.onNodeWithText("~/.gemini").assertIsDisplayed()
        composeRule.onNodeWithText("local@example.com").assertIsDisplayed()
        composeRule.onNodeWithTag("antigravity-relogin-default").assertDoesNotExist()
        composeRule.onNodeWithTag("antigravity-delete-default").assertDoesNotExist()
        composeRule.onNodeWithTag("antigravity-stale-default").assertDoesNotExist()

        // Isolated account shows Google badge, scoped bar, stale warning, and action buttons
        composeRule.onNodeWithText("Google").assertIsDisplayed()
        composeRule.onNodeWithText("work@example.com").assertIsDisplayed()
        composeRule.onNodeWithText("Claude · 5-Hour").assertIsDisplayed()
        composeRule.onNodeWithTag("antigravity-stale-acc-google").assertIsDisplayed()

        // Strategy switching updates hint
        composeRule.onNodeWithText("Take the next account each time.").assertIsDisplayed()
        composeRule.onNodeWithTag("antigravity-strategy-lowest-usage").performClick()
        composeRule.waitForIdle()
        assertEquals("lowest-usage", selectedStrategy)
        composeRule.onNodeWithText("Pick whichever has the most left.").assertIsDisplayed()

        // Toggle, re-login, and delete callbacks
        composeRule.onNodeWithTag("antigravity-toggle-acc-google").performClick()
        assertEquals("acc-google", toggledId)

        composeRule.onNodeWithTag("antigravity-relogin-acc-google").performClick()
        assertEquals("acc-google", reloginId)

        composeRule.onNodeWithTag("antigravity-delete-acc-google").performClick()
        assertEquals("acc-google", deletedId)
    }

    @Test
    fun antigravityLoginDialogContent_runsPkceStepOneStepTwoAndReloginTitle() {
        var relogin by mutableStateOf<AntigravityAccount?>(null)
        var label by mutableStateOf("")
        var flow by mutableStateOf<AntigravityLoginFlow?>(null)
        var code by mutableStateOf("")
        var startedLabel: String? = null
        var submittedCode: String? = null

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                AntigravityLoginDialogContent(
                    reloginAccount = relogin,
                    loginLabel = label,
                    onLoginLabelChange = { label = it },
                    loginFlow = flow,
                    authCode = code,
                    onAuthCodeChange = { code = it },
                    onStartLogin = {
                        startedLabel = label
                        flow = AntigravityLoginFlow(id = "login-1", authUrl = "https://accounts.google.com/o/oauth2/v2/auth")
                    },
                    onSubmitAuthCode = {
                        submittedCode = code
                    },
                )
            }
        }

        composeRule.onNodeWithText("Add Antigravity Account").assertIsDisplayed()
        composeRule.onNodeWithTag("antigravity-login-label-input").performTextReplacement("Shopee 2")
        composeRule.onNodeWithTag("antigravity-start-login-btn").performClick()
        composeRule.waitForIdle()

        assertEquals("Shopee 2", startedLabel)
        composeRule.onNodeWithTag("antigravity-open-auth-url-btn").assertIsDisplayed()
        composeRule.onNodeWithTag("antigravity-auth-code-input").assertIsDisplayed()
        composeRule.onNodeWithTag("antigravity-submit-code-btn").performClick()
        assertNull(submittedCode)
        composeRule.onNodeWithTag("antigravity-auth-code-input")
            .performTextReplacement("https://antigravity.google/oauth-callback?code=4/0Axyz")
        composeRule.onNodeWithTag("antigravity-submit-code-btn").performClick()
        assertEquals("https://antigravity.google/oauth-callback?code=4/0Axyz", submittedCode)

        // Re-login mode hides optional label input and shows Sign in again header
        flow = null
        relogin = AntigravityAccount(id = "acc-google", label = "Work")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Sign in again — Work").assertIsDisplayed()
        composeRule.onNodeWithTag("antigravity-login-label-input").assertDoesNotExist()
    }

    @Test
    fun aiSettingsSection_antigravityTabShowsAccountsLinkThreeEffortsAndSystemPromptNote() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when (request.url.encodedPath) {
                    "/api/settings/ai" -> """
                        {
                          "ok": true,
                          "data": {
                            "default_provider": "antigravity",
                            "providers": {
                              "claude": { "type": "agent-sdk" },
                              "antigravity": { "type": "cli", "effort": "medium" }
                            }
                          }
                        }
                    """.trimIndent()
                    "/api/settings/ai/providers/status" -> """{"ok":true,"data":[]}"""
                    "/api/settings/ai/providers/antigravity/models" -> """
                        {
                          "ok": true,
                          "data": [
                            { "value": "gemini-4-argon", "label": "Gemini 4 Argon" },
                            { "value": "gemini-3.8-flash", "label": "Gemini 3.8 Flash" }
                          ]
                        }
                    """.trimIndent()
                    else -> """{"ok":true,"data":[]}"""
                }
                return MockResponse.Builder()
                    .addHeader("Content-Type", "application/json")
                    .body(body)
                    .build()
            }
        }
        server.start()
        try {
            val model = AppModel(ApplicationProvider.getApplicationContext<Application>())
            model.api = ApiClient(server.url("/"), "secret")

            composeRule.setContent {
                PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                    AiSettingsSection(model)
                }
            }
            composeRule.waitUntil(5_000L) {
                composeRule.onAllNodes(androidx.compose.ui.test.hasText("Antigravity account & quota"))
                    .fetchSemanticsNodes().isNotEmpty()
            }

            composeRule.onNodeWithText("Antigravity account & quota").assertIsDisplayed()
            composeRule.onNodeWithText("Codex accounts & login").assertDoesNotExist()
            composeRule.onNodeWithText("Effort").assertIsDisplayed()
            composeRule.onNodeWithText("Medium").assertIsDisplayed()
            composeRule.onNodeWithText(
                "Antigravity reads these only when PPM starts a new chat, and a chat keeps the ones it started with.",
            ).assertIsDisplayed()
            val neon = com.hienlh.ppm.protocol.ModelOption("gemini-4-neon", "Gemini 4 Neon", supportedEfforts = listOf("low", "medium", "high", "xhigh"))
            val carbon = com.hienlh.ppm.protocol.ModelOption("gemini-4-carbon", "Gemini 4 Carbon", supportedEfforts = listOf("low", "medium", "high"))
            assertEquals(
                listOf("low" to "Low", "medium" to "Medium", "high" to "High", "xhigh" to "Extra"),
                effortSelectOptions("antigravity", neon).map { it.value to it.label },
            )
            assertEquals(
                listOf("low" to "Low", "medium" to "Medium", "high" to "High"),
                effortSelectOptions("antigravity", carbon).map { it.value to it.label },
            )

            // Switching to the Claude tab hides Antigravity-only link and system-prompt helper note
            composeRule.onNodeWithText("Claude").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Antigravity account & quota").assertDoesNotExist()
            composeRule.onNodeWithText(
                "Antigravity reads these only when PPM starts a new chat, and a chat keeps the ones it started with.",
            ).assertDoesNotExist()
        } finally {
            server.close()
        }
    }

    @Test
    fun composerModelChip_antigravityReadsSuffixEffortAndHidesThinkingInLabel() {
        var clicked = 0
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                ComposerModelChip(
                    name = "Gemini 4 Argon",
                    state = com.hienlh.ppm.state.ChatState(
                        sessionId = null,
                        model = "gemini-4-argon-medium",
                        effort = null,
                        thinking = true,
                    ),
                    providerId = "antigravity",
                    onClick = { clicked++ },
                )
            }
        }

        // Chip text uses suffix effort ("Medium") and aria-label omits ", thinking on" even when state.thinking is true
        composeRule.onNodeWithContentDescription("Model Gemini 4 Argon, effort medium")
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, clicked)
    }
}
