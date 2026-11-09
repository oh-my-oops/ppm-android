package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.AccountInfo
import com.hienlh.ppm.protocol.AccountUsage
import com.hienlh.ppm.protocol.AccountUsageEntry
import com.hienlh.ppm.protocol.CodexAccount
import com.hienlh.ppm.protocol.LimitBucket
import com.hienlh.ppm.protocol.UsageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ports `chat-account-switch.test.ts`, `switch-account-view.test.tsx` ordering, and
 * `chat-footer-tools-usage.test.tsx` usage resolution.
 */
class ChatAccountSwitchTest {
    private val accounts = listOf(
        SwitchAccount(id = "a", label = "Alice", plan = null, fiveHourPct = 50, weekPct = 60, refusal = null, signedOut = false),
        SwitchAccount(id = "b", label = "Bob", plan = null, fiveHourPct = 80, weekPct = 70, refusal = null, signedOut = false),
        SwitchAccount(id = "c", label = "Charlie", plan = null, fiveHourPct = 30, weekPct = 40, refusal = "5-hour limit reached", signedOut = false),
        SwitchAccount(id = "d", label = "Diana", plan = null, fiveHourPct = 100, weekPct = 95, refusal = "5-hour limit reached", signedOut = false),
    )

    @Test
    fun `orderSwitchAccounts puts current first even if out of usage, then most headroom, then refused`() {
        val whenCurrentOut = orderSwitchAccounts(accounts, "c")
        assertEquals("c", whenCurrentOut.usable.first().id)
        assertEquals(listOf("d"), whenCurrentOut.outOfUsage.map { it.id })

        val noCurrent = orderSwitchAccounts(accounts, null)
        assertEquals(listOf("a", "b"), noCurrent.usable.map { it.id })
        assertEquals(listOf("c", "d"), noCurrent.outOfUsage.map { it.id })

        val bobCurrent = orderSwitchAccounts(accounts, "b")
        assertEquals(listOf("b", "a"), bobCurrent.usable.map { it.id })

        fun acc(id: String, label: String, h5: Int, wk: Int, refusal: String? = null, signedOut: Boolean = false) =
            SwitchAccount(id, label, "Max", h5, wk, refusal, signedOut)
        val nine = listOf(
            acc("v2", "Victor 2 (Nxsys)", 47, 52),
            acc("v", "Victor", 12, 30),
            acc("s", "Nxsys shared", 100, 88, "5-hour limit reached · resets in 2h 10m"),
            acc("a", "Nxsys team A", 5, 18),
            acc("b", "Nxsys team B", 33, 41),
            acc("h", "Hien personal", 64, 71),
            acc("q", "QA bot", 81, 60),
            acc("d", "Demo account", 96, 100, "Weekly limit reached"),
            acc("o", "Old work", 20, 30, "Signed out · sign in again", signedOut = true),
        )
        val orderedNine = orderSwitchAccounts(nine, "v2")
        assertEquals(listOf("v2", "a", "v", "b", "h", "q"), orderedNine.usable.map { it.id })
        assertEquals(listOf("s", "d", "o"), orderedNine.outOfUsage.map { it.id })
    }

    @Test
    fun `claudeSwitchAccount and codexSwitchAccount compute refusals, plans, and signedOut flags`() {
        val reauth = claudeSwitchAccount(
            entry = AccountUsageEntry("a", "Alice", "active", isOAuth = true, usage = AccountUsage(session = LimitBucket(0.5, "", null, null, 5.0))),
            info = AccountInfo("a", "Alice", "alice@example.com", "active", expiresAt = null, hasRefreshToken = true, reauthRequired = true, grantExpiresAt = null, hasClaudeMax = true),
        )
        assertTrue(reauth.refusal.orEmpty().contains("Signed out"))
        assertTrue(reauth.signedOut)
        assertEquals("Max", reauth.plan)

        val capped = claudeSwitchAccount(
            entry = AccountUsageEntry("a", "Alice", "active", isOAuth = true, usage = AccountUsage(session = LimitBucket(1.0, "", null, null, 5.0))),
        )
        assertTrue(capped.refusal.orEmpty().contains("5-hour limit reached"))

        val at99 = claudeSwitchAccount(
            entry = AccountUsageEntry("a", "Alice", "active", isOAuth = true, usage = AccountUsage(session = LimitBucket(0.99, "", null, null, 5.0))),
        )
        assertNull(at99.refusal)

        val codexChatgpt = codexSwitchAccount(
            account = CodexAccount("c1", "ChatGPT Account", "chatgpt", "plus", "active", dailyGuardEnabled = false, signedOut = true),
            usage = UsageInfo(sevenDay = 0.5),
        )
        assertTrue(codexChatgpt.refusal.orEmpty().contains("Signed out"))
        assertTrue(codexChatgpt.signedOut)
        assertEquals("Plus", codexChatgpt.plan)

        val codexOther = codexSwitchAccount(
            account = CodexAccount("c2", "Non-ChatGPT", "other", null, "active", dailyGuardEnabled = false, signedOut = true),
            usage = UsageInfo(sevenDay = 0.5),
            plan = null,
        )
        assertTrue(codexOther.refusal.orEmpty().contains("Signed out"))
        assertFalse(codexOther.signedOut)
    }

    @Test
    fun `matchesAccountQuery, footerUsageTone, and highestPct match web rules`() {
        val account = SwitchAccount("a", "Alice Pro", "Plus", 50, 60, null, false)
        assertTrue(matchesAccountQuery(account, "alice"))
        assertTrue(matchesAccountQuery(account, "ALICE"))
        assertTrue(matchesAccountQuery(account, "pro"))
        assertTrue(matchesAccountQuery(account, "plus"))
        assertTrue(matchesAccountQuery(account, ""))
        assertTrue(matchesAccountQuery(account, "   "))
        assertFalse(matchesAccountQuery(account, "bob"))

        assertEquals(FooterUsageTone.OK, footerUsageTone(null))
        assertEquals(FooterUsageTone.OK, footerUsageTone(0))
        assertEquals(FooterUsageTone.OK, footerUsageTone(50))
        assertEquals(FooterUsageTone.OK, footerUsageTone(69))
        assertEquals(FooterUsageTone.WARN, footerUsageTone(70))
        assertEquals(FooterUsageTone.WARN, footerUsageTone(75))
        assertEquals(FooterUsageTone.WARN, footerUsageTone(89))
        assertEquals(FooterUsageTone.ERROR, footerUsageTone(90))
        assertEquals(FooterUsageTone.ERROR, footerUsageTone(95))
        assertEquals(FooterUsageTone.ERROR, footerUsageTone(100))

        assertEquals(75, highestPct(50, 75))
        assertEquals(90, highestPct(90, 50))
        assertEquals(50, highestPct(50, null))
        assertEquals(75, highestPct(null, 75))
        assertNull(highestPct(null, null))
    }

    @Test
    fun `resolveFooterUsage never borrows another account's numbers or name`() {
        val mismatch = resolveFooterUsage(
            providerId = "claude",
            usageInfo = UsageInfo(activeAccountId = "vincent", activeAccountLabel = "Vincent", fiveHour = 0.0, sevenDay = 1.0),
            pickedAccountId = "developers",
            pickedAccountLabel = null,
        )
        assertFalse(mismatch.hasUsage)
        assertNull(mismatch.fiveHourPct)
        assertNull(mismatch.weekPct)
        assertNull(mismatch.accountLabel)
        assertFalse(mismatch.who.contains("Vincent"))

        val matched = resolveFooterUsage(
            providerId = "claude",
            usageInfo = UsageInfo(activeAccountId = "developers", fiveHour = 0.0, sevenDay = 0.82),
            pickedAccountId = "developers",
            pickedAccountLabel = "Developers",
        )
        assertTrue(matched.hasUsage)
        assertEquals(0, matched.fiveHourPct)
        assertEquals(82, matched.weekPct)
        assertEquals("Developers", matched.accountLabel)
        assertEquals("Developers · 5h 0% · Week 82%", matched.ringTitle)

        val empty = resolveFooterUsage(providerId = "claude", usageInfo = UsageInfo())
        assertFalse(empty.hasUsage)

        val oneWindow = resolveFooterUsage(
            providerId = "claude",
            usageInfo = UsageInfo(activeAccountId = "a", sevenDay = 0.52),
        )
        assertNull(oneWindow.fiveHourPct)
        assertEquals(52, oneWindow.weekPct)
        assertTrue(oneWindow.hasUsage)
    }
}
