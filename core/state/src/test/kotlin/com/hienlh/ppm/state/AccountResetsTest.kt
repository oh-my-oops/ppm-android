package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.AccountUsage
import com.hienlh.ppm.protocol.AccountUsageEntry
import com.hienlh.ppm.protocol.ClaudeLimitResetAnswer
import com.hienlh.ppm.protocol.ClaudeLimitResetGrant
import com.hienlh.ppm.protocol.ClaudeLimitResets
import com.hienlh.ppm.protocol.CodexAccount
import com.hienlh.ppm.protocol.ResetCredit
import com.hienlh.ppm.protocol.ResetCredits
import com.hienlh.ppm.protocol.UsageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** Free limit resets as the account card, its sheet and the chat's Switch account word them. */
class AccountResetsTest {
    private val utc = ZoneOffset.UTC
    private val now = Instant.parse("2026-11-13T09:00:00Z").toEpochMilli()

    private fun grant(
        id: String = "g1",
        kind: String = "full",
        left: Int = 1,
        endsAt: String? = "2026-12-11T05:00:00.000Z",
        clears: List<String> = listOf("five_hour", "seven_day"),
        usableNow: Boolean = true,
        givesBack: Boolean = false,
        blocking: List<String> = emptyList(),
        percentUsed: Map<String, Int> = emptyMap(),
        useRequiresLimit: Boolean = true,
    ) = ClaudeLimitResetGrant(
        id = id, label = "x", kind = kind, resetsLeft = left, endsAt = endsAt, clears = clears,
        givesBackWeekly = givesBack, usableNow = usableNow, blocking = blocking, percentUsed = percentUsed,
        useRequiresLimit = useRequiresLimit,
    )

    @Test fun `an expiry reads its day and the days left, amber within a week`() {
        assertEquals(ResetLineExpiry("Dec 11 · 28 days", false), resetLineExpiry("2026-12-11T05:00:00.000Z", now, utc))
        assertEquals(ResetLineExpiry("Nov 14 · 1 day", true), resetLineExpiry("2026-11-14T05:00:00.000Z", now, utc))
        assertEquals(ResetLineExpiry("Nov 20 · 7 days", true), resetLineExpiry("2026-11-20T09:00:00.000Z", now, utc))
        // A day still ahead within the hour is one day, never zero.
        assertEquals(1, resetDaysLeft("2026-11-13T09:30:00.000Z", now))
        assertEquals(ResetLineExpiry("Jan 2, 2027 · 50 days", false), resetLineExpiry("2027-01-02T00:00:00.000Z", now, utc))
        assertEquals(ResetLineExpiry("No expiry", false), resetLineExpiry(null, now, utc))
    }

    @Test fun `a sheet row says when it expires to the minute`() {
        assertEquals("Expires Dec 11, 5:00 AM", resetRowExpiry("2026-12-11T05:00:00.000Z", now, utc))
        assertEquals("Expires Jan 2, 2027, 12:30 PM", resetRowExpiry("2027-01-02T12:30:00.000Z", now, utc))
        assertEquals("No expiry", resetRowExpiry(null, now, utc))
    }

    @Test fun `card lines group a kind ending the same day`() {
        val lines = claudeResetCardLines(
            listOf(
                grant("a", "fable", 1, "2026-12-01T00:00:00.000Z"),
                grant("b", "fable", 1, "2026-12-01T00:00:00.000Z"),
                grant("c", "full", 1, "2026-12-11T05:00:00.000Z"),
            ),
        )
        assertEquals(
            listOf(
                ResetLine("fable|2026-12-01T00:00:00.000Z", "Fable reset", 2, "2026-12-01T00:00:00.000Z"),
                ResetLine("full|2026-12-11T05:00:00.000Z", "Full reset", 1, "2026-12-11T05:00:00.000Z"),
            ),
            lines,
        )
    }

    @Test fun `a grant Anthropic would not take now says why in Claude Code's words`() {
        val r = ClaudeLimitResets(grants = emptyList())
        assertNull(claudeResetBlockedReason(grant(), r))
        assertEquals("usable once you reach a limit it refills", claudeResetBlockedReason(grant(usableNow = false), r))
        assertEquals(
            "can't be used while you're at your weekly limit",
            claudeResetBlockedReason(grant(usableNow = false, blocking = listOf("seven_day")), r),
        )
        assertEquals("a reset was just used, try again in a minute", claudeResetBlockedReason(grant(), r.copy(cooldownUntil = "2026-11-13T09:01:00Z")))
        assertEquals(
            "doesn't refill the limit you've reached",
            claudeResetBlockedReason(grant(usableNow = false, clears = listOf("five_hour")), r.copy(atLimit = true, exhausted = listOf("seven_day_opus"))),
        )
        assertEquals("none left", claudeResetBlockedReason(grant(left = 0, usableNow = false), r))
    }

    @Test fun `what a grant refills and the warning for spending it early`() {
        assertEquals("5-hour and weekly limits", claudeResetRefills(grant()))
        assertEquals("Fable limit", claudeResetRefills(grant(kind = "fable", clears = listOf("seven_day_overage_included"), givesBack = true)))
        assertEquals(
            "Refills your 5-hour and weekly limits. Your weekly reset day stays the same.",
            claudeGrantDescription(grant()),
        )
        val r = ClaudeLimitResets(grants = emptyList())
        assertEquals(
            "You still have 60% of your 5-hour limit left. Use your full reset anyway?",
            claudeResetEarlyWarning(grant(percentUsed = mapOf("five_hour" to 40, "seven_day" to 12)), r),
        )
        assertEquals("You haven't reached a limit yet. Use your full reset anyway?", claudeResetEarlyWarning(grant(), r))
        assertNull(claudeResetEarlyWarning(grant(), r.copy(atLimit = true, exhausted = listOf("five_hour"))))
        assertEquals("2 of this kind left, use by Dec 11, 5:00 AM.", claudeResetConfirmLeft(grant(left = 2), now, utc))
        assertEquals("The only one of this kind.", claudeResetConfirmLeft(grant(endsAt = null), now, utc))
    }

    @Test fun `the line after a claim says what happened`() {
        assertEquals(
            "Work: Limits reset. Your weekly reset day stays Mon, Nov 16, 5:00 AM. 2 full resets left.",
            claudeResetResultMessage("Work", ClaudeLimitResetAnswer("reset", "full", null, 2, "2026-11-16T05:00:00.000Z"), utc),
        )
        assertEquals(
            "Work: Fable reset used. What Fable used is back in your weekly limit. No Fable resets left.",
            claudeResetResultMessage("Work", ClaudeLimitResetAnswer("reset", "fable", null, 0, null), utc),
        )
        assertEquals(
            "Work: couldn't reset the limits (grant lapsed). Nothing was used; try again in a moment.",
            claudeResetResultMessage("Work", ClaudeLimitResetAnswer("unavailable", "full", "grant_lapsed"), utc),
        )
        assertEquals(
            "Work: nothing needed resetting, so the 5-hour reset was kept.",
            claudeResetResultMessage("Work", ClaudeLimitResetAnswer("not_limited", "session"), utc),
        )
    }

    @Test fun `a Codex reading without the list still shows each credit`() {
        assertEquals(
            listOf(CodexCredit("c1", "Full reset (Weekly + 5 hr)", "2026-12-01T00:00:00.000Z"), CodexCredit(null, "Full reset (Weekly + 5 hr)", null)),
            codexCreditList(ResetCredits(2, "2026-12-01T00:00:00.000Z", "Full reset (Weekly + 5 hr)", "c1")),
        )
        val listed = ResetCredits(1, null, null, null, listOf(ResetCredit("c9", null, "2026-12-02T00:00:00.000Z")))
        assertEquals(listOf(ResetLine("c9", "Full reset", 1, "2026-12-02T00:00:00.000Z")), codexResetCardLines(codexCreditList(listed)))
    }

    @Test fun `Switch account carries each account's count and nearest expiry`() {
        val resets = ClaudeLimitResets(
            grants = listOf(grant("a", "fable", 2, "2026-12-20T00:00:00.000Z"), grant("b", "full", 1, "2026-12-01T00:00:00.000Z"), grant("c", left = 0, endsAt = "2026-11-20T00:00:00.000Z")),
        )
        val claude = claudeSwitchAccount(
            AccountUsageEntry("a1", "Work", "active", true, AccountUsage(limitResets = resets)),
            now = now,
        )
        assertEquals(ResetsSummary(3, "2026-12-01T00:00:00.000Z"), claude.resets)
        assertEquals(ResetsSummary(0, null), claudeSwitchAccount(AccountUsageEntry("a2", null, "active", true, AccountUsage()), now = now).resets)

        val codex = codexSwitchAccount(
            CodexAccount("c", "Team", "chatgpt", "plus", "active", false, false),
            UsageInfo(resetCredits = ResetCredits(2, "2026-12-05T00:00:00.000Z", null, null, listOf(ResetCredit("x", null, "2026-12-03T00:00:00.000Z")))),
            now = now,
        )
        assertEquals(ResetsSummary(2, "2026-12-03T00:00:00.000Z"), codex.resets)
        assertEquals(ResetsSummary(0, null), codexResetsSummary(ResetCredits(0, null, null, null)))
    }

    @Test fun `the total counts every reset held`() {
        assertEquals(3, claudeResetsLeft(ClaudeLimitResets(listOf(grant(left = 2), grant("b", left = 1)))))
        assertEquals(0, claudeResetsLeft(null))
        assertTrue(claudeResetGrantsLeft(ClaudeLimitResets(listOf(grant(left = 0)))).isEmpty())
        assertEquals("5-hour reset", claudeResetKindName("session", capital = true))
    }
}
