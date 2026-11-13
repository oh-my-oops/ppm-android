package com.hienlh.ppm.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.client.spendClaudeLimitReset
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.ClaudeLimitResetGrant
import com.hienlh.ppm.protocol.ClaudeLimitResets
import com.hienlh.ppm.protocol.LimitBucket
import com.hienlh.ppm.protocol.ResetCredit
import com.hienlh.ppm.protocol.ResetCredits
import com.hienlh.ppm.protocol.UsageInfo
import com.hienlh.ppm.state.CODEX_RESET_NOT_AT_LIMIT
import com.hienlh.ppm.state.CodexCredit
import com.hienlh.ppm.state.ResetsSummary
import com.hienlh.ppm.state.SwitchAccount
import com.hienlh.ppm.state.claudeResetResultMessage
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The account card's free resets (`account-resets.tsx`, `claude-limit-resets-button.tsx`,
 * `codex-reset-credit-button.tsx`) and the chat's Switch account line that leads to them
 * (`switch-account-view.tsx`). Spending goes to a fake server or a recording lambda, never a real one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xxhdpi")
class AccountResetsUiTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val fullGrant = ClaudeLimitResetGrant(
        id = "g-full",
        label = "Full reset",
        kind = "full",
        resetsLeft = 2,
        resetsTotal = 3,
        endsAt = "2027-02-01T00:00:00Z",
        clears = listOf("five_hour", "seven_day"),
        usableNow = true,
    )

    @Test
    fun claudeReset_isPickedConfirmedAndClaimedByGrantIdOnATablet() {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                .body("""{"ok":true,"data":{"result":"reset","kind":"full","resetsLeft":1}}""").build(),
        )
        val api = ApiClient(server.url("/"), "t")
        var done: Pair<String, Boolean>? = null
        try {
            composeRule.setContent {
                PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                    ClaudeLimitResetsButton(
                        accountLabel = "Work",
                        resets = ClaudeLimitResets(grants = listOf(fullGrant), atLimit = true, exhausted = listOf("five_hour")),
                        spend = { grant -> claudeResetResultMessage("Work", api.spendClaudeLimitReset("acc-1", grant.id)) },
                        onDone = { message, failed -> done = message to failed },
                    )
                }
            }

            composeRule.onNodeWithTag("resets-section").assertExists()
            composeRule.onNodeWithTag("reset-line").assertExists()
            composeRule.onNodeWithTag("resets-use").performClick()
            composeRule.onNodeWithText("Limit resets · 2 available").assertExists()
            composeRule.onNode(hasText("Reset for free") and hasAnyAncestor(hasTestTag("claude-reset-g-full")), useUnmergedTree = true).performClick()

            composeRule.onNodeWithTag("claude-limit-reset-confirm", useUnmergedTree = true).assertExists()
            composeRule.onNodeWithText("Use a full reset?").assertExists()
            composeRule.onNodeWithTag("reset-confirm-spend").performClick()

            composeRule.waitUntil(5_000) { done != null }
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/api/accounts/acc-1/limit-reset", request.url.encodedPath)
            assertEquals("""{"grantId":"g-full"}""", request.body?.utf8())
            assertEquals(false, done!!.second)
            assertTrue(done!!.first, done!!.first.contains("Limits reset"))
            composeRule.onNodeWithTag("claude-limit-reset-confirm", useUnmergedTree = true).assertDoesNotExist()
        } finally {
            server.close()
        }
    }

    @Test
    fun claudeReset_keepItGoesBackToTheListWithoutSpending() {
        var spent = 0
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                ClaudeLimitResetsButton(
                    accountLabel = "Work",
                    resets = ClaudeLimitResets(grants = listOf(fullGrant), atLimit = true, exhausted = listOf("five_hour")),
                    spend = { spent++; "" },
                    onDone = { _, _ -> },
                )
            }
        }
        composeRule.onNodeWithTag("resets-use").performClick()
        composeRule.onNode(hasText("Reset for free") and hasAnyAncestor(hasTestTag("claude-reset-g-full")), useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("Keep it").performClick()
        composeRule.onNodeWithTag("claude-limit-resets", useUnmergedTree = true).assertExists()
        assertEquals(0, spent)
    }

    @Test
    fun claudeReset_noGrantsLeftShowsNoneRightNow() {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                ClaudeLimitResetsButton("Work", null, spend = { "" }, onDone = { _, _ -> })
            }
        }
        composeRule.onNodeWithText("None right now").assertExists()
    }

    private fun codexUsage(atLimit: Boolean) = UsageInfo(
        session = LimitBucket(if (atLimit) 1.0 else 0.4, "2026-11-20T00:00:00Z", null, null, 5.0),
        resetCredits = ResetCredits(
            available = 2,
            nextExpiresAt = "2027-01-10T00:00:00Z",
            title = "Full reset",
            nextCreditId = "cr-1",
            credits = listOf(
                ResetCredit("cr-1", "Full reset", "2027-01-10T00:00:00Z"),
                ResetCredit("cr-2", "Full reset", "2027-03-10T00:00:00Z"),
            ),
        ),
    )

    @Test
    fun codexReset_isSpentByCreditIdFromThePhoneSheet() {
        var spentWith: CodexCredit? = null
        var done: Pair<String, Boolean>? = null
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                CodexResetCreditButton(
                    accountLabel = "Codex A",
                    usage = codexUsage(atLimit = true),
                    spend = { credit -> spentWith = credit; "Codex A: limits reset to 0%." },
                    onDone = { message, failed -> done = message to failed },
                )
            }
        }
        composeRule.onNodeWithTag("resets-use").performClick()
        composeRule.onNodeWithText("Free resets · 2 available").assertExists()
        composeRule.onNode(hasText("Use reset") and hasAnyAncestor(hasTestTag("codex-reset-cr-2")), useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag("codex-reset-credit-confirm", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("reset-confirm-spend").performClick()
        composeRule.waitUntil(5_000) { done != null }
        assertEquals("cr-2", spentWith?.id)
        assertEquals("Codex A: limits reset to 0%." to false, done)
    }

    @Test
    fun codexReset_refusalBringsTheServersReasonAsAFailure() {
        var done: Pair<String, Boolean>? = null
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                CodexResetCreditButton(
                    accountLabel = "Codex A",
                    usage = codexUsage(atLimit = true),
                    spend = { throw PpmError.Server(409, "No limit is reached.") },
                    onDone = { message, failed -> done = message to failed },
                )
            }
        }
        composeRule.onNodeWithTag("resets-use").performClick()
        composeRule.onNode(hasText("Use reset") and hasAnyAncestor(hasTestTag("codex-reset-cr-1")), useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag("reset-confirm-spend").performClick()
        composeRule.waitUntil(5_000) { done != null }
        assertEquals("No limit is reached." to true, done)
    }

    @Test
    fun codexReset_belowTheLimitSaysSoAndOffersNoConfirmation() {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                CodexResetCreditButton("Codex A", codexUsage(atLimit = false), spend = { "" }, onDone = { _, _ -> })
            }
        }
        composeRule.onNodeWithTag("resets-use").performClick()
        composeRule.onNodeWithText(CODEX_RESET_NOT_AT_LIMIT).assertExists()
        composeRule.onNode(hasText("Use reset") and hasAnyAncestor(hasTestTag("codex-reset-cr-1")), useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag("codex-reset-credit-confirm", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun switchAccount_resetsLineOpensSettingsWithoutPickingTheAccount() {
        val opened = mutableListOf<String>()
        val picked = mutableListOf<String>()
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    SwitchAccountView(
                        accounts = listOf(
                            SwitchAccount(id = "a1", label = "Work", fiveHourPct = 20, weekPct = 40, resets = ResetsSummary(3, "2027-01-11T12:00:00Z")),
                            SwitchAccount(id = "a2", label = "Home", fiveHourPct = 10, weekPct = 10, resets = ResetsSummary(0, null)),
                        ),
                        loading = false,
                        currentId = "a2",
                        providerName = "Claude",
                        onBack = {},
                        onPick = { id, _ -> picked.add(id); null },
                        onPicked = {},
                        onReauth = {},
                        onAddAccount = {},
                        onManage = {},
                        onOpenResets = { opened.add(it.id) },
                    )
                }
            }
        }
        composeRule.onNodeWithText("3 resets").assertExists()
        composeRule.onNodeWithText("No free resets").assertExists()
        composeRule.onNodeWithTag("account-resets:a1").performClick()
        assertEquals(listOf("a1"), opened)
        assertEquals(emptyList<String>(), picked)

        // An account with none has nothing to open.
        composeRule.onNodeWithTag("account-resets:a2").performClick()
        assertEquals(listOf("a1"), opened)
    }
}
