package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.AccountInfo
import com.hienlh.ppm.protocol.AccountUsage
import com.hienlh.ppm.protocol.AccountUsageEntry
import com.hienlh.ppm.protocol.ChatEvent
import com.hienlh.ppm.protocol.CodexAccount
import com.hienlh.ppm.protocol.LimitBucket
import com.hienlh.ppm.protocol.ResetCredits
import com.hienlh.ppm.protocol.UsageInfo
import com.hienlh.ppm.protocol.WireJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The usage chip's figures and the account cards' rules (account-usage-format.ts, use-usage.ts, usage-badge.tsx). */
class UsageTest {
    private val now = Instant.parse("2026-10-24T10:00:00Z").toEpochMilli()

    private fun bucket(utilization: Double, resetsAt: String = "", minutes: Long? = null, hours: Double? = null, window: Double = 5.0) =
        LimitBucket(utilization, resetsAt, minutes, hours, window)

    @Test
    fun `a figure is coloured by the thresholds every surface shares, rounded as JavaScript rounds`() {
        assertEquals(UsageTone.Success, UsageTone.of(69))
        assertEquals(UsageTone.Warning, UsageTone.of(70))
        assertEquals(UsageTone.Warning, UsageTone.of(89))
        assertEquals(UsageTone.Error, UsageTone.of(90))
        assertEquals(43, usagePercent(0.425))
        assertEquals(100, usagePercent(0.995))
        assertTrue(atCap(0.995))
        assertFalse(atCap(0.994))
        assertFalse(atCap(null))
    }

    @Test
    fun `resets in reads minutes, then hours, then the moment, and shows the largest units`() {
        assertEquals("2h 10m", formatResetTime(bucket(0.1, minutes = 130), now))
        assertEquals("1d 2h", formatResetTime(bucket(0.1, minutes = 1560), now))
        assertEquals("3d", formatResetTime(bucket(0.1, minutes = 4320), now))
        assertEquals("1d 0h 1m", formatResetTime(bucket(0.1, minutes = 1441), now))
        assertEquals("1h 30m", formatResetTime(bucket(0.1, hours = 1.5), now))
        // A moment rounds up to the minute; one past, or unreadable, is now.
        assertEquals("1m", formatResetTime(bucket(0.1, resetsAt = "2026-10-24T10:00:01Z"), now))
        assertEquals("now", formatResetTime(bucket(0.1, resetsAt = "2026-10-24T09:00:00Z"), now))
        assertEquals("now", formatResetTime(bucket(0.1, resetsAt = "soon"), now))
        assertEquals("45m", formatResetTime(bucket(0.1, resetsAt = "2026-10-24T10:45:00.000+00:00"), now))
        assertNull(formatResetTime(bucket(0.1), now))
        assertNull(formatResetTime(null, now))
    }

    @Test
    fun `footer reset formatters match account-reset-format cases with fixed now and zone`() {
        val zone = ZoneId.of("Asia/Ho_Chi_Minh")
        // Fixed local moment matching `account-reset-format.test.ts`: Tuesday 10 Nov 2026, 00:40.
        val localNow = ZonedDateTime.of(2026, 11, 10, 0, 40, 0, 0, zone).toInstant().toEpochMilli()
        val isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .withZone(ZoneId.of("UTC"))
        fun at(mins: Long): String = isoFormatter.format(Instant.ofEpochMilli(localNow + mins * 60_000L))

        // formatResetCountdown: keeps two units at most, rounds minutes up (ceil), "now" once past, null otherwise.
        assertEquals("in 3h 12m", formatResetCountdown(at(192), localNow))
        assertEquals("in 2d 8h", formatResetCountdown(at(2 * 1440 + 8 * 60 + 15), localNow))
        assertEquals("in 40m", formatResetCountdown(at(40), localNow))
        assertEquals("in 2h", formatResetCountdown(at(120), localNow))
        assertEquals("in 1d", formatResetCountdown(at(1440), localNow))
        assertEquals("in 1m", formatResetCountdown(isoFormatter.format(Instant.ofEpochMilli(localNow + 1_000L)), localNow))
        assertEquals("now", formatResetCountdown(at(-5), localNow))
        assertNull(formatResetCountdown(null, localNow))
        assertNull(formatResetCountdown("not a date", localNow))

        // formatResetClock: bare 24h time within 12h (even past midnight), "today HH:mm", weekday within 6.5d, "MMM d HH:mm" beyond.
        assertEquals("03:52", formatResetClock(at(192), localNow, zone))
        val lateNight = ZonedDateTime.of(2026, 11, 9, 23, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("00:40", formatResetClock(isoFormatter.format(Instant.ofEpochMilli(localNow)), lateNight, zone))
        assertEquals("today 18:00", formatResetClock(at(17 * 60 + 20), localNow, zone))
        assertEquals("Thu 09:00", formatResetClock(at(2 * 1440 + 8 * 60 + 20), localNow, zone))
        assertEquals("Nov 18 00:40", formatResetClock(at(8 * 1440), localNow, zone))
        // Different viewer zone formats the same instant in that zone's 24h clock.
        assertEquals("20:52", formatResetClock(at(192), localNow, ZoneId.of("UTC")))

        // formatResetLine: joins countdown and clock, or "Resets now".
        assertEquals("Resets in 3h 12m · 03:52", formatResetLine(at(192), localNow, zone))
        assertEquals("Resets in 2d 8h · Thu 09:00", formatResetLine(at(2 * 1440 + 8 * 60 + 20), localNow, zone))
        assertEquals("Resets in 17h 20m · today 18:00", formatResetLine(at(17 * 60 + 20), localNow, zone))
        assertEquals("Resets now", formatResetLine(at(-1), localNow, zone))
        assertNull(formatResetLine(null, localNow, zone))

        // bucketResetsAt: prefers server ISO moment, else converts countdown minutes/hours into ISO, else null.
        assertEquals(at(30), bucketResetsAt(bucket(0.4, resetsAt = at(30), minutes = 99, hours = null), localNow))
        assertEquals(at(90), bucketResetAtOrNull(bucket(0.4, resetsAt = "", minutes = 90, hours = null), localNow))
        assertEquals(at(120), bucketResetsAt(bucket(0.4, resetsAt = "", minutes = null, hours = 2.0), localNow))
        assertNull(bucketResetsAt(bucket(0.4, resetsAt = "", minutes = null, hours = null), localNow))
        assertNull(bucketResetsAt(null, localNow))
    }

    private fun bucketResetAtOrNull(bucket: LimitBucket?, now: Long): String? = bucketResetsAt(bucket, now)

    @Test
    fun `ages and expiries read as the cards print them`() {
        assertEquals("just now", formatLastUpdated(now - 4_400, now))
        assertEquals("42s ago", formatLastUpdated(now - 42_000, now))
        assertEquals("1h 5m ago", formatLastUpdated(now - 65 * 60_000, now))
        assertEquals("2h ago", formatLastUpdated(now - 120 * 60_000, now))
        assertEquals("3d ago", formatLastUpdated(now - 3 * 86_400_000L, now))
        assertNull(formatLastUpdated(null, now))
        assertEquals("expired", formatExpiry(now, now))
        assertEquals("4d 3h", formatExpiry(now + (4 * 24 + 3) * 3_600_000L, now))
        assertEquals("2h 1m", formatExpiry(now + 121 * 60_000L, now))
    }

    private fun account(status: String = "active", expiresAt: Long? = null, refresh: Boolean = true, reauth: Boolean = false) =
        AccountInfo("a1", "Work", null, status, expiresAt, refresh, reauth, null)

    @Test
    fun `a token's status puts a refused renewal ahead of its expiry`() {
        val past = now / 1000 - 60
        val future = now / 1000 + 3_600
        assertEquals("unknown", tokenStatus(null, now).label)
        assertEquals("key", tokenStatus(account(expiresAt = null), now).label)
        assertEquals("key", tokenStatus(account(expiresAt = 0), now).label)
        assertEquals(TokenStatus("sign in again", "Anthropic rejected this account's refresh token. Sign in again to restore it.", UsageTone.Error), tokenStatus(account(expiresAt = past, reauth = true), now))
        assertEquals(UsageTone.Warning, tokenStatus(account(expiresAt = past), now).tone)
        assertEquals(UsageTone.Error, tokenStatus(account(expiresAt = past, refresh = false), now).tone)
        assertEquals("long-lived", tokenStatus(account(expiresAt = future), now).label)
        assertEquals("temp", tokenStatus(account(expiresAt = future, refresh = false), now).label)
        assertTrue(account(expiresAt = past, refresh = false).isExpired(now))
        assertFalse(account(expiresAt = past).isExpired(now))
        assertFalse(account(expiresAt = 0, refresh = false).isExpired(now))
    }

    @Test
    fun `an account is refused only for what the server refuses, a reached window included`() {
        fun entry(session: Double?, weekly: Double? = null) = AccountUsageEntry(
            "a1", "Work", "active", true,
            AccountUsage(session = session?.let { bucket(it, "2026-10-24T12:00:00Z", minutes = 120) }, weekly = weekly?.let { bucket(it) }),
        )
        assertEquals("Disabled", claudeUnselectableReason(account(status = "disabled"), entry(0.1), now))
        assertEquals("Sign in again to use this account", claudeUnselectableReason(account(reauth = true), null, now))
        assertEquals("Token expired — sign in again", claudeUnselectableReason(account(expiresAt = 1, refresh = false), null, now))
        assertNull(claudeUnselectableReason(account(), entry(0.96), now))
        assertEquals("5-hour limit reached — resets 2h", claudeUnselectableReason(account(), entry(1.0), now))
        assertEquals("Weekly limit reached", claudeUnselectableReason(null, entry(0.2, weekly = 1.0), now))
        assertEquals("Cannot switch to this account — 5-hour limit reached — resets 2h.", switchRefusal("5-hour limit reached — resets 2h"))

        val codex = CodexAccount("c1", "Team", "chatgpt", "team", "active", false, signedOut = false)
        assertEquals("Signed out — sign in again first", codexUnselectableReason(codex.copy(signedOut = true), null, now))
        assertEquals("Disabled", codexUnselectableReason(codex.copy(status = "disabled", signedOut = true), null, now))
        assertEquals("Weekly limit reached", codexUnselectableReason(codex, UsageInfo(sevenDay = 1.0), now))
        assertNull(codexUnselectableReason(codex, UsageInfo(fiveHour = 0.99), now))
    }

    @Test
    fun `codex plans, the daily guard and a free reset follow the shared rules`() {
        assertEquals("ChatGPT Team", codexPlanLabel(" Team "))
        assertEquals("ChatGPT Business", codexPlanLabel("self_serve_business_prolite"))
        assertEquals("mystery", codexPlanLabel("mystery"))
        assertNull(codexPlanLabel(" "))

        // A week that resets Saturday 2026-10-31 10:00 UTC started Saturday the 24th: slot 0 is a
        // Saturday, so on Monday the 26th at noon (slot 2) one weekday has begun.
        val weekly = bucket(0.3, "2026-10-31T10:00:00Z", window = 168.0)
        val monday = Instant.parse("2026-10-26T12:00:00Z").toEpochMilli()
        assertEquals(DailyGuardState(day = 1, cap = 0.2, used = 0.3, blocked = true), dailyGuardState(weekly, monday))
        assertNull(dailyGuardState(bucket(0.3, "2026-10-31T10:00:00Z", window = 5.0), monday))
        assertNull(dailyGuardState(weekly, Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()))

        val credits = ResetCredits(2, null, null, null)
        assertTrue(canUseResetCredit(UsageInfo(session = bucket(1.0), resetCredits = credits)))
        assertFalse(canUseResetCredit(UsageInfo(session = bucket(0.9), resetCredits = credits)))
        assertFalse(canUseResetCredit(UsageInfo(weekly = bucket(1.0), resetCredits = ResetCredits(0, null, null, null))))
        assertEquals("Team: nothing needed resetting, so the reset was kept.", resetCreditOutcome("nothingToReset", "Team"))
    }

    @Test
    fun `account events name the account, and the parts of a card come off the wire`() {
        val info = ChatEvent.from(WireJson.parse("""{"type":"account_info","accountId":"a2","accountLabel":"Home"}"""))
        assertEquals(ChatEvent.AccountInfo("a2", "Home"), info)
        val retry = ChatEvent.from(WireJson.parse("""{"type":"account_retry","reason":"rate limit","accountId":"a3","accountLabel":"Spare"}"""))
        assertEquals(ChatEvent.AccountRetry("rate limit", "Spare", "a3"), retry)

        val entry = AccountUsageEntry.from(
            WireJson.parse(
                """{"accountId":"a1","accountLabel":null,"accountStatus":"cooldown","isOAuth":true,
                   "usage":{"lastFetchedAt":"2026-10-24T09:59:00Z","session":{"utilization":0.5,"resetsAt":"","resetsInMinutes":30,"resetsInHours":null,"windowHours":5},
                   "weeklyScoped":[{"label":"Fable","utilization":0.2,"resetsAt":"2026-10-30T00:00:00Z","resetsInMinutes":null,"resetsInHours":12.5,"windowHours":168}]}}""",
            ),
        )!!
        assertNull(entry.accountLabel)
        assertEquals("cooldown", entry.accountStatus)
        assertTrue(entry.usage.hasBuckets)
        assertEquals("Fable", entry.usage.weeklyScoped.single().label)
        val record = AccountInfo.from(
            WireJson.parse("""{"id":"a1","label":"Work","email":"w@x","status":"disabled","expiresAt":1790000000,"hasRefreshToken":false,"reauthRequired":true,"grantExpiresAt":null,"priority":0}"""),
        )!!
        assertEquals(AccountInfo("a1", "Work", "w@x", "disabled", 1_790_000_000, false, true, null), record)
        val codex = CodexAccount.from(WireJson.parse("""{"id":"c1","label":"Team","type":"chatgpt","planType":"team","dailyGuardEnabled":true,"signedOut":true}"""))
        assertEquals(CodexAccount("c1", "Team", "chatgpt", "team", "active", true, true), codex)
    }

    private val claimed = UsageScope("demo", "claude", null, "a1")

    @Test
    fun `a reading shows only for the account its scope claimed`() {
        val other = UsageInfo(fiveHour = 0.5, activeAccountId = "a2", activeAccountLabel = "Home", lastFetchedAt = "t")
        assertEquals(UsageSnapshot(claimed, UsageInfo(), null), UsageSnapshot.of(claimed, other, "t"))
        val mine = other.copy(activeAccountId = "a1")
        assertEquals(UsageSnapshot(claimed, mine, "t"), UsageSnapshot.of(claimed, mine, "t"))
        val unclaimed = claimed.copy(pickedAccountId = null)
        assertEquals(UsageSnapshot(unclaimed, UsageInfo(), null), UsageSnapshot.of(unclaimed, null, null))
        assertEquals("a1", claimed.previewAccountId)
        assertNull(claimed.copy(sessionId = "s1").previewAccountId)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the first read waits half a second, then the reading is read again every two minutes`() = runTest {
        val cache = UsageCache { testScheduler.currentTime }
        val asked = mutableListOf<Pair<UsageScope, Boolean>>()
        val reader = UsageReader(cache) { scope, refresh ->
            asked += scope to refresh
            UsageInfo(fiveHour = asked.size / 10.0, activeAccountId = "a1", lastFetchedAt = "r${asked.size}")
        }
        val job = launch { reader.run(claimed, prepare = null) }
        advanceTimeBy(499)
        assertTrue(asked.isEmpty())
        advanceTimeBy(2)
        assertEquals(listOf(claimed to false), asked)
        assertEquals("r1", reader.snapshot.value?.fetchedAt)
        assertEquals("r1", cache[claimed]?.fetchedAt)
        advanceTimeBy(UsageReader.POLL_MILLIS)
        assertEquals(2, asked.size)
        reader.read(refresh = true)
        assertEquals(claimed to true, asked.last())
        assertFalse(reader.loading.value)
        job.cancel()
        runCurrent()
        // Stopped, it reads nothing more, and asks for no scope at all.
        reader.read(refresh = true)
        assertEquals(3, asked.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a fresh seed from the tab's preparation stands in for the first read`() = runTest {
        val cache = UsageCache { testScheduler.currentTime }
        var asked = 0
        val reader = UsageReader(cache) { _, _ -> asked++; UsageInfo(activeAccountId = "a1") }
        val prepare = CompletableDeferred<Unit>()
        val job = launch { reader.run(claimed, prepare) }
        advanceTimeBy(5_000)
        assertEquals(0, asked)
        cache.seed(claimed, UsageInfo(fiveHour = 0.42, activeAccountId = "a1", lastFetchedAt = "seed"), "seed")
        prepare.complete(Unit)
        runCurrent()
        advanceTimeBy(1_000)
        assertEquals(0, asked)
        assertEquals(0.42, reader.snapshot.value?.usage?.fiveHour!!, 0.0)
        job.cancel()

        // A seed older than 30 s is not trusted: the tab reads for itself.
        val stale = UsageReader(cache) { _, _ -> asked++; UsageInfo(activeAccountId = "a1") }
        advanceTimeBy(UsageCache.SEED_FRESH_MILLIS)
        val failed = CompletableDeferred<Unit>().apply { completeExceptionally(IllegalStateException("prepare failed")) }
        val second = launch { stale.run(claimed, failed) }
        advanceTimeBy(501)
        assertEquals(1, asked)
        second.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `only the newest request's answer lands, and another scope's reading is never shown`() = runTest {
        val cache = UsageCache { testScheduler.currentTime }
        val answers = mutableListOf<CompletableDeferred<UsageInfo?>>()
        val reader = UsageReader(cache) { _, _ -> CompletableDeferred<UsageInfo?>().also { answers += it }.await() }
        val job = launch { reader.run(claimed, prepare = null) }
        advanceTimeBy(501)
        val slow = launch { reader.read(refresh = true) }
        runCurrent()
        assertEquals(2, answers.size)
        answers[1].complete(UsageInfo(fiveHour = 0.2, activeAccountId = "a1"))
        answers[0].complete(UsageInfo(fiveHour = 0.9, activeAccountId = "a1"))
        runCurrent()
        assertEquals(0.2, reader.snapshot.value?.usage?.fiveHour!!, 0.0)
        assertFalse(reader.loading.value)
        slow.cancel()
        job.cancel()

        val session = claimed.copy(sessionId = "s1")
        assertNull(reader.shown(session, reader.snapshot.value))
        cache.put(UsageSnapshot(session, UsageInfo(sevenDay = 0.3), null))
        assertEquals(0.3, reader.shown(session, reader.snapshot.value)?.usage?.sevenDay!!, 0.0)
        assertEquals(0.2, reader.shown(claimed, reader.snapshot.value)?.usage?.fiveHour!!, 0.0)
        assertNull(reader.shown(null, reader.snapshot.value))
    }
}
