package com.hienlh.ppm.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The free resets an account holds, as `GET /api/accounts/usage` and the Codex usage read report them. */
class LimitResetsTest {
    @Test fun `a Claude usage entry carries its limit resets`() {
        val entry = AccountUsageEntry.from(
            WireJson.parse(
                """{"accountId":"a1","accountStatus":"active","isOAuth":true,"usage":{
                  "limitResets":{"grants":[{"id":"g1","label":"One full limit reset","kind":"full","resetsLeft":1,"resetsTotal":1,
                    "endsAt":"2026-12-11T05:00:00.000Z","clears":["five_hour","seven_day"],"givesBackWeekly":false,"paused":false,
                    "usableNow":true,"useRequiresLimit":true,"blocking":[],"percentUsed":{"five_hour":40,"seven_day":12}}],
                  "atLimit":false,"exhausted":[],"weeklyResetsAt":"2026-11-16T05:00:00.000Z"}}}""",
            ),
        )!!
        val resets = entry.usage.limitResets!!
        val grant = resets.grants.single()
        assertEquals("g1", grant.id)
        assertEquals("full", grant.kind)
        assertEquals(1, grant.resetsLeft)
        assertEquals("2026-12-11T05:00:00.000Z", grant.endsAt)
        assertEquals(listOf("five_hour", "seven_day"), grant.clears)
        assertTrue(grant.usableNow)
        assertTrue(grant.useRequiresLimit)
        assertEquals(mapOf("five_hour" to 40, "seven_day" to 12), grant.percentUsed)
        assertFalse(resets.atLimit)
        assertEquals("2026-11-16T05:00:00.000Z", resets.weeklyResetsAt)
    }

    @Test fun `limit resets with no grant are none`() {
        assertNull(ClaudeLimitResets.from(WireJson.parse("""{"grants":[],"atLimit":true,"exhausted":[]}""")))
        assertNull(AccountUsage.from(WireJson.parse("{}")).limitResets)
    }

    @Test fun `codex reset credits list each credit by id`() {
        val credits = ResetCredits.from(
            WireJson.parse(
                """{"available":2,"nextCreditId":"c1","nextExpiresAt":"2026-12-01T00:00:00.000Z","credits":[
                  {"id":"c1","title":"Full reset","expiresAt":"2026-12-01T00:00:00.000Z"},{"id":"c2"}]}""",
            ),
        )!!
        assertEquals(listOf(ResetCredit("c1", "Full reset", "2026-12-01T00:00:00.000Z"), ResetCredit("c2")), credits.credits)
        // An older stored reading has no list.
        assertEquals(emptyList<ResetCredit>(), ResetCredits.from(WireJson.parse("""{"available":1}"""))!!.credits)
    }

    @Test fun `a limit reset answer reads its result and what is left`() {
        val answer = ClaudeLimitResetAnswer.from(WireJson.parse("""{"result":"reset","kind":"fable","resetsLeft":0,"weeklyResetsAt":null}"""))
        assertEquals(ClaudeLimitResetAnswer("reset", "fable", null, 0, null), answer)
    }
}
