package com.hienlh.ppm.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.protocol.LimitBucket
import com.hienlh.ppm.protocol.UsageInfo
import com.hienlh.ppm.state.MANY_SWITCH_ACCOUNTS
import com.hienlh.ppm.state.SwitchAccount
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xxhdpi")
class ChatFooterToolsTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val fixedZone: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")
    private val fixedNow: Long = ZonedDateTime.of(2026, 11, 10, 0, 40, 0, 0, fixedZone).toInstant().toEpochMilli()
    private val isoUtc: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).withZone(ZoneId.of("UTC"))

    private fun inMinsFrom(baseMs: Long, mins: Long): String =
        isoUtc.format(Instant.ofEpochMilli(baseMs + mins * 60_000L))

    private fun acc(
        id: String,
        label: String,
        h5: Int,
        wk: Int,
        refusal: String? = null,
        signedOut: Boolean = false,
        fiveHourResetsAt: String? = inMinsFrom(fixedNow, 192),
        weekResetsAt: String? = inMinsFrom(fixedNow, 2 * 1440L + 8 * 60L + 20L),
    ) = SwitchAccount(
        id = id,
        label = label,
        plan = "Max",
        fiveHourPct = h5,
        weekPct = wk,
        fiveHourResetsAt = fiveHourResetsAt,
        weekResetsAt = weekResetsAt,
        refusal = refusal,
        signedOut = signedOut,
    )

    private val threeAccounts = listOf(
        acc("v2", "Victor 2 (Nxsys)", 47, 52),
        acc("v", "Victor", 12, 30),
        acc("s", "Nxsys shared", 100, 88, "5-hour limit reached"),
    )

    private val nineAccounts = threeAccounts + listOf(
        acc("a", "Nxsys team A", 5, 18),
        acc("b", "Nxsys team B", 33, 41),
        acc("h", "Hien personal", 64, 71),
        acc("q", "QA bot", 81, 60),
        acc("d", "Demo account", 96, 100, "Weekly limit reached"),
        acc("o", "Old work", 20, 30, "Signed out · sign in again", signedOut = true),
    )

    private fun hasStateDescription(expected: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected)

    @Test
    fun usageCluster_guardsCrossAccountFiguresColoursWindowsAndHidesWithDividerOnNarrowTrouble() {
        var providerId by mutableStateOf("claude")
        var pickedId by mutableStateOf<String?>("developers")
        var pickedLabel by mutableStateOf<String?>("Developers")
        var usage by mutableStateOf(UsageInfo(activeAccountId = "vincent", activeAccountLabel = "Vincent", fiveHour = 0.0, sevenDay = 1.0))
        var narrow by mutableStateOf(false)
        var trouble by mutableStateOf(false)

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Column {
                        ChatFooterTools(
                            usageInfo = usage,
                            providerId = providerId,
                            narrow = narrow,
                            pickedAccountId = pickedId,
                            pickedAccountLabel = pickedLabel,
                            connectionTrouble = trouble,
                            antigravityUsageContent = { BasicText("Antigravity quota body") },
                        )
                    }
                }
            }
        }

        // Different account snapshot -> no ring, no 100%, no divider, no "--%".
        composeRule.onNodeWithTag("usage-ring").assertDoesNotExist()
        composeRule.onNodeWithTag("usage-divider").assertDoesNotExist()
        composeRule.onNodeWithText("100%").assertDoesNotExist()
        composeRule.onNodeWithText("--%").assertDoesNotExist()

        // Selected account snapshot lands with one window missing (fiveHour = null, sevenDay = 0.82).
        usage = UsageInfo(activeAccountId = "developers", sevenDay = 0.82)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("usage-ring")
            .assertIsDisplayed()
            .assertContentDescriptionEquals("Usage: Developers · Week 82%")
        composeRule.onNodeWithTag("usage-window:5h", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("usage-window:Wk", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("usage-pct:Wk:warn", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("usage-divider").assertIsDisplayed()

        // Independent tone per window: 47% -> ok, 93% -> error.
        pickedId = null
        pickedLabel = null
        usage = UsageInfo(activeAccountId = "a", fiveHour = 0.47, sevenDay = 0.93)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("usage-pct:5h:ok", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("usage-pct:Wk:error", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("usage-ring-icon:5h", useUnmergedTree = true).assertIsDisplayed()

        // Narrow chat drops ring icons while keeping "5h" and "Wk"; connection trouble hides ring + divider together.
        narrow = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("usage-ring-icon:5h", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("usage-window:5h", useUnmergedTree = true).assertIsDisplayed()

        trouble = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("usage-ring").assertDoesNotExist()
        composeRule.onNodeWithTag("usage-divider").assertDoesNotExist()

        // Antigravity ring opens Antigravity usage popover instead of ChatMoreMenu.
        trouble = false
        narrow = false
        providerId = "antigravity"
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("usage-ring").performClick()
        composeRule.onNodeWithText("Antigravity quota body").assertIsDisplayed()
        composeRule.onNodeWithTag("chat-more-menu").assertDoesNotExist()
    }

    @Test
    fun moreMenu_switchesActiveTriggerUnfoldsSessionActionsAndDrillsIntoAccounts() {
        var unread by mutableStateOf(false)
        var reloads = 0
        var debugOpens = 0
        var logOpens = 0

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Column {
                        ChatFooterTools(
                            usageInfo = UsageInfo(
                                activeAccountId = "v2",
                                activeAccountLabel = "Victor 2 (Nxsys)",
                                fiveHour = 0.47,
                                sevenDay = 0.52,
                                totalCostUsd = 0.0123,
                            ),
                            providerId = "claude",
                            narrow = false,
                            switcher = SwitchAccountsState(accounts = threeAccounts, loading = false),
                            isConnected = true,
                            onReload = { reloads++ },
                            hasTeams = true,
                            teamUnreadCount = 2,
                            sessionActions = MoreMenuSessionActions(
                                hasUnread = unread,
                                onToggleUnread = { unread = !unread },
                                onOpenDebug = { debugOpens++ },
                                onShowLogs = { logOpens++ },
                            ),
                        )
                    }
                }
            }
        }

        // Team unread dot shows on ··· button.
        composeRule.onNodeWithTag("footer-more-unread").assertIsDisplayed()

        // Clicking usage-ring opens ChatMoreMenu with usage-ring expanded, footer-more collapsed.
        composeRule.onNodeWithTag("usage-ring").performClick()
        composeRule.onNodeWithTag("chat-more-menu").assertIsDisplayed()
        composeRule.onNodeWithTag("usage-ring").assert(hasStateDescription("expanded"))
        composeRule.onNodeWithTag("footer-more").assert(hasStateDescription("collapsed"))
        composeRule.onNodeWithText("Victor 2 (Nxsys) · Claude Max").assertIsDisplayed()
        composeRule.onNodeWithText("This session $0.0123").assertIsDisplayed()
        composeRule.onNodeWithText("3 accounts").assertIsDisplayed()

        // Clicking ··· while menu is open from ring keeps menu open and transfers expanded state.
        composeRule.onNodeWithTag("footer-more").performClick()
        composeRule.onNodeWithTag("chat-more-menu").assertIsDisplayed()
        composeRule.onNodeWithTag("usage-ring").assert(hasStateDescription("collapsed"))
        composeRule.onNodeWithTag("footer-more").assert(hasStateDescription("expanded"))

        // Folded session actions expand on "More".
        composeRule.onNodeWithTag("more-session-fold").assert(hasStateDescription("collapsed"))
        composeRule.onNodeWithTag("more-session-fold").performClick()
        composeRule.onNodeWithTag("more-session-fold").assert(hasStateDescription("expanded"))
        composeRule.onNodeWithText("Mark as unread").performClick()
        assertEquals(true, unread)
        // Selecting a session action closes the popover.
        composeRule.onNodeWithTag("chat-more-menu").assertDoesNotExist()

        // Re-open, check "Mark as read", Debug, Logs, Reload, and drill into Switch account.
        composeRule.onNodeWithTag("footer-more").performClick()
        composeRule.onNodeWithTag("more-session-fold").performClick()
        composeRule.onNodeWithText("Mark as read").assertIsDisplayed()
        composeRule.onNodeWithTag("more-debug").performClick()
        assertEquals(1, debugOpens)

        composeRule.onNodeWithTag("footer-more").performClick()
        composeRule.onNodeWithTag("more-session-fold").performClick()
        composeRule.onNodeWithTag("more-logs").performClick()
        assertEquals(1, logOpens)

        composeRule.onNodeWithTag("footer-more").performClick()
        composeRule.onNodeWithTag("more-reload").performClick()
        assertEquals(1, reloads)

        composeRule.onNodeWithTag("footer-more").performClick()
        composeRule.onNodeWithTag("more-switch-account").performClick()
        composeRule.onNodeWithTag("switch-account-view").assertIsDisplayed()
        composeRule.onNodeWithTag("switch-account-back").performClick()
        composeRule.onNodeWithTag("chat-more-menu").assertIsDisplayed()
    }

    @Test
    fun switchAccountView_threeAccountsAndNineAccountsOrderingSearchFoldAndRefusals() {
        assertEquals(5, MANY_SWITCH_ACCOUNTS)

        var accounts by mutableStateOf(threeAccounts)
        val picked = mutableListOf<String>()
        val reauthed = mutableListOf<String>()
        var doneCount = 0
        var pickHandler by mutableStateOf<(suspend (String, String) -> String?)?>(
            { id, _ ->
                picked.add(id)
                null
            },
        )

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    SwitchAccountView(
                        accounts = accounts,
                        loading = false,
                        currentId = "v2",
                        providerName = "Claude",
                        onBack = {},
                        onPick = pickHandler,
                        onPicked = { doneCount++ },
                        onReauth = { reauthed.add(it.id) },
                        onAddAccount = {},
                        onManage = {},
                    )
                }
            }
        }

        fun renderedRowIds(): List<String> = listOf("v2", "a", "v", "b", "h", "q", "s", "d", "o")
            .mapNotNull { id ->
                val nodes = composeRule.onAllNodesWithTag("account-row:$id").fetchSemanticsNodes()
                nodes.firstOrNull()?.let { id to it.positionInRoot.y }
            }
            .sortedBy { it.second }
            .map { it.first }

        // 3 accounts: no search, "for this chat", current first & ticked, unusable inline with reason.
        composeRule.onNodeWithTag("switch-account-search").assertDoesNotExist()
        composeRule.onNodeWithText("for this chat").assertIsDisplayed()
        assertEquals(listOf("v2", "v", "s"), renderedRowIds())
        composeRule.onNodeWithTag("account-active:v2", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("account-row:s").assert(hasStateDescription("disabled"))
        composeRule.onNodeWithText("5-hour limit reached").assertIsDisplayed()
        composeRule.onNodeWithText("Add account").assertIsDisplayed()
        composeRule.onNodeWithText("Manage in Settings").assertIsDisplayed()

        // Picking usable account routes and calls onPicked.
        composeRule.onNodeWithTag("account-row:v").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("v"), picked)
        assertEquals(1, doneCount)

        // When chat is not ready (onPick = null), tapping explains why.
        pickHandler = null
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("account-row:v").performClick()
        composeRule.onNodeWithTag("switch-account-alert").assertIsDisplayed()
        composeRule.onNodeWithText("The account can be changed once this chat has finished starting.").assertIsDisplayed()

        // Server refusal stays on list and shows refusal text.
        pickHandler = { _, _ -> "Account is busy" }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("account-row:v").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Account is busy").assertIsDisplayed()
        assertEquals(1, doneCount)

        // 9 accounts: search box, "9 accounts · Claude", headroom order, folded "Out of usage" (3).
        picked.clear()
        pickHandler = { id, _ ->
            picked.add(id)
            null
        }
        accounts = nineAccounts
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("switch-account-search").assertIsDisplayed()
        composeRule.onNodeWithText("9 accounts · Claude").assertIsDisplayed()
        assertEquals(listOf("v2", "a", "v", "b", "h", "q"), renderedRowIds())
        composeRule.onNodeWithTag("out-of-usage-toggle").assert(hasStateDescription("collapsed"))

        // Expand Out of usage -> refused & signed-out accounts appear; tapping refused shows alert, signed-out calls onReauth.
        composeRule.onNodeWithTag("out-of-usage-toggle").performScrollTo().performClick()
        composeRule.onNodeWithTag("out-of-usage-toggle").assert(hasStateDescription("expanded"))
        composeRule.onNodeWithTag("account-row:s").performScrollTo().performClick()
        composeRule.onNodeWithText("Cannot switch to Nxsys shared — 5-hour limit reached.").assertIsDisplayed()
        composeRule.onNodeWithTag("account-row:o").performScrollTo().performClick()
        assertEquals(listOf("o"), reauthed)
        assertEquals(emptyList<String>(), picked)

        // Collapse fold, then search "demo" -> auto-opens fold and shows only "d"; "zzz" -> "No account matches."
        composeRule.onNodeWithTag("out-of-usage-toggle").performScrollTo().performClick()
        composeRule.onNodeWithTag("switch-account-search").performTextReplacement("demo")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("out-of-usage-toggle").assert(hasStateDescription("expanded"))
        assertEquals(listOf("d"), renderedRowIds())

        composeRule.onNodeWithTag("switch-account-search").performTextReplacement("zzz")
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), renderedRowIds())
        composeRule.onNodeWithText("No account matches.").assertIsDisplayed()
    }

    @Test
    fun usageResetDisplay_showsRingCountdownMenuMetersAccountCardResetLinesRedOnFullAndTicksLive() {
        // Plain non-Compose variable so advancing `fakeNow` alone CANNOT recompose unless `rememberResetClockMillis` ticks!
        var fakeNow = fixedNow
        val reset5h = inMinsFrom(fixedNow, 192)
        val resetWeek = inMinsFrom(fixedNow, 2 * 1440L + 8 * 60L + 20L)

        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = false) {
                CompositionLocalProvider(
                    LocalReducedMotion provides true,
                    LocalResetClock provides { fakeNow },
                    LocalResetZone provides fixedZone,
                ) {
                    Column {
                        ChatFooterTools(
                            usageInfo = UsageInfo(
                                activeAccountId = "v2",
                                activeAccountLabel = "Victor 2 (Nxsys)",
                                fiveHour = 0.47,
                                sevenDay = 0.52,
                                session = LimitBucket(0.47, reset5h, null, null, 5.0),
                                weekly = LimitBucket(0.52, resetWeek, null, null, 168.0),
                            ),
                            providerId = "claude",
                            narrow = false,
                            switcher = SwitchAccountsState(accounts = threeAccounts, loading = false),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 1. Usage ring tooltip / contentDescription carries each window's countdown alone.
        composeRule.onNodeWithTag("usage-ring")
            .assertContentDescriptionEquals("Usage: Victor 2 (Nxsys) · 5h 47% (resets in 3h 12m) · Week 52% (resets in 2d 8h)")

        // 2. Open ··· menu: meters show countdown alone on the right.
        composeRule.onNodeWithTag("footer-more").performClick()
        composeRule.waitForIdle()
        assertEquals(
            "in 3h 12m",
            composeRule.onNodeWithTag("meter-reset:5 hours", useUnmergedTree = true)
                .fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.single()?.text,
        )
        assertEquals(
            "in 2d 8h",
            composeRule.onNodeWithTag("meter-reset:Week", useUnmergedTree = true)
                .fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.single()?.text,
        )

        // 3. While open on screen, advancing 1 minute + ticking the 30s clock updates the countdown live!
        fakeNow += 60_000L
        composeRule.mainClock.advanceTimeBy(31_000L)
        composeRule.waitForIdle()
        assertEquals(
            "in 3h 11m",
            composeRule.onNodeWithTag("meter-reset:5 hours", useUnmergedTree = true)
                .fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.single()?.text,
        )
        composeRule.onNodeWithTag("usage-ring")
            .assertContentDescriptionEquals("Usage: Victor 2 (Nxsys) · 5h 47% (resets in 3h 11m) · Week 52% (resets in 2d 8h)")

        // 4. Drill into Switch account: every bar shows "Resets in … · clock", red when >= 100%, equal bar widths, no "resets in" on refusal.
        composeRule.onNodeWithTag("more-switch-account").performClick()
        composeRule.waitForIdle()

        val v5hNode = composeRule.onNodeWithTag("account-reset:v:5h", useUnmergedTree = true).fetchSemanticsNode()
        val vWeekNode = composeRule.onNodeWithTag("account-reset:v:Week", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("Resets in 3h 11m · 03:52", v5hNode.config.getOrNull(SemanticsProperties.Text)?.single()?.text)
        assertEquals("Resets in 2d 8h · Thu 09:00", vWeekNode.config.getOrNull(SemanticsProperties.Text)?.single()?.text)
        assertEquals("muted", v5hNode.config.getOrNull(AccountResetToneKey))
        assertEquals("muted", vWeekNode.config.getOrNull(AccountResetToneKey))

        val s5hNode = composeRule.onNodeWithTag("account-reset:s:5h", useUnmergedTree = true).fetchSemanticsNode()
        val sWeekNode = composeRule.onNodeWithTag("account-reset:s:Week", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("error", s5hNode.config.getOrNull(AccountResetToneKey))
        assertEquals("muted", sWeekNode.config.getOrNull(AccountResetToneKey))

        val sTexts = composeRule.onNodeWithTag("account-row:s").fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" | ") { it.text }
        assertTrue("Expected window-only refusal on s, got: $sTexts", sTexts.contains("5-hour limit reached"))
        assertFalse("Expected refusal not to repeat 'resets in', got: $sTexts", sTexts.contains("· resets in"))

        val barWidths = listOf("v2" to "5h", "v2" to "Week", "v" to "5h", "v" to "Week", "s" to "5h", "s" to "Week").map { (id, w) ->
            composeRule.onNodeWithTag("account-bar:$id:$w", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.width.roundToInt()
        }
        assertEquals("Expected all 6 account usage bars to share one width, got $barWidths", 1, barWidths.toSet().size)
    }
}
