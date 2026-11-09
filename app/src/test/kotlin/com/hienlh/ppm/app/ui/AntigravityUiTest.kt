package com.hienlh.ppm.app.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.AntigravityAccount
import com.hienlh.ppm.protocol.LimitBucket
import com.hienlh.ppm.protocol.UsageInfo
import com.hienlh.ppm.state.AntigravityAccountsData
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
    fun hasUsagePanel_includesClaudeCodexAndAntigravityOnly() {
        assertTrue(hasUsagePanel(""))
        assertTrue(hasUsagePanel("claude"))
        assertTrue(hasUsagePanel("codex"))
        assertTrue(hasUsagePanel("antigravity"))
        assertFalse(hasUsagePanel("cursor"))
        assertFalse(hasUsagePanel("gemini"))
        assertFalse(hasUsagePanel("mock"))
    }

    @Test
    fun usageChip_showsAntigravityAccountLabelAndPercentages() {
        var clicked = 0
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                UsageChip(
                    usage = UsageInfo(
                        fiveHour = 0.42,
                        sevenDay = 0.15,
                        activeAccountId = "acc-1",
                        activeAccountLabel = "work@example.com · Pro",
                    ),
                    pickedLabel = null,
                    pickedAccountId = null,
                    active = false,
                    narrow = false,
                    onClick = { clicked++ },
                )
            }
        }

        composeRule.onNodeWithText("[work@example.com · Pro]").assertIsDisplayed()
        composeRule.onNodeWithText("5h:42%").assertIsDisplayed()
        composeRule.onNodeWithText("Wk:15%").assertIsDisplayed()
        composeRule.onNodeWithText("5h:42%").performClick()
        assertEquals(1, clicked)
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
}
