package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.AntigravityAccount
import com.hienlh.ppm.protocol.ChatFrame
import com.hienlh.ppm.protocol.LimitBucket
import com.hienlh.ppm.protocol.SessionPhase
import com.hienlh.ppm.protocol.SessionStateFrame
import com.hienlh.ppm.protocol.UsageInfo
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.state.settings.parseAntigravityLoginStart
import com.hienlh.ppm.state.settings.parseAntigravityLoginStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Antigravity usage normalization, stale detection, account pool state, login status parsing, and session title filtering. */
@OptIn(ExperimentalCoroutinesApi::class)
class AntigravityAccountsDataTest {
    private val now = Instant.parse("2026-11-09T12:00:00Z").toEpochMilli()

    private fun account(id: String, status: String = "active", planType: String? = "Pro") =
        AntigravityAccount(id = id, label = id, planType = planType, status = status)

    private fun bucket(utilization: Double, resetsAt: String, label: String? = null) =
        LimitBucket(utilization = utilization, resetsAt = resetsAt, resetsInMinutes = 30, resetsInHours = 0.5, windowHours = 5.0, label = label)

    private class FakeSource : AntigravityAccountsData.Source {
        var accounts = listOf<AntigravityAccount>()
        var usages = mapOf<String, UsageInfo>()
        var accountsGate: CompletableDeferred<Unit>? = null
        var fail: String? = null
        val patched = mutableListOf<String>()

        override suspend fun accounts(): List<AntigravityAccount> {
            val snapshot = accounts
            accountsGate?.await()
            fail?.let { throw IllegalStateException(it) }
            return snapshot
        }

        override suspend fun usages(): Map<String, UsageInfo> {
            fail?.let { throw IllegalStateException(it) }
            return usages
        }

        override suspend fun setStatus(id: String, status: String) {
            patched += "$id:$status"
            fail?.let { throw IllegalStateException(it) }
        }
    }

    @Test
    fun `stale threshold is 11 minutes and currentAntigravityUsage zeroes restarted windows and clears unused reset times`() {
        assertFalse(isAntigravityUsageStale(null, now))
        assertFalse(isAntigravityUsageStale("not-a-date", now))
        assertFalse(isAntigravityUsageStale("2026-11-09T11:49:00Z", now)) // exactly 11m
        assertTrue(isAntigravityUsageStale("2026-11-09T11:48:59Z", now)) // 11m 1s

        val raw = UsageInfo(
            fiveHour = 1.0,
            sevenDay = 0.0,
            session = bucket(1.0, "2026-11-09T11:59:00Z"), // reset in the past -> restarted -> 0%
            weekly = bucket(0.0, "2026-11-16T12:00:00Z"), // unused (0%) -> reset time stripped
            weeklyScoped = listOf(
                bucket(0.65, "2026-11-09T15:00:00Z", label = "Claude · 5-Hour"), // active future -> kept
                bucket(0.8, "2026-11-09T10:00:00Z", label = "Other Models · Weekly"), // past -> restarted -> 0%
            ),
        )
        val current = currentAntigravityUsage(raw, now)
        assertEquals(0.0, current.fiveHour!!, 0.0)
        assertEquals(0.0, current.session!!.utilization, 0.0)
        assertEquals("", current.session!!.resetsAt)
        assertNull(current.session!!.resetsInMinutes)

        assertEquals(0.0, current.sevenDay!!, 0.0)
        assertEquals("", current.weekly!!.resetsAt)
        assertNull(current.weekly!!.resetsInHours)

        assertEquals(0.65, current.weeklyScoped[0].utilization, 0.0)
        assertEquals("2026-11-09T15:00:00Z", current.weeklyScoped[0].resetsAt)
        assertEquals(0.0, current.weeklyScoped[1].utilization, 0.0)
        assertEquals("", current.weeklyScoped[1].resetsAt)
    }

    @Test
    fun `unselectable reason respects disabled status, active caps, and restarted windows, and scoped labels avoid double Weekly`() {
        val acc = account("ag-1")
        assertEquals("Disabled", antigravityUnselectableReason(acc.copy(status = "disabled"), null, now))
        assertEquals(
            "5-hour limit reached — resets 2h",
            antigravityUnselectableReason(
                acc,
                UsageInfo(fiveHour = 1.0, session = LimitBucket(1.0, "2026-11-09T14:00:00Z", null, null, 5.0)),
                now,
            ),
        )
        assertEquals(
            "Weekly limit reached",
            antigravityUnselectableReason(acc, UsageInfo(sevenDay = 1.0), now),
        )
        // A 100% bucket whose reset time already passed is restarted -> selectable!
        assertNull(
            antigravityUnselectableReason(
                acc,
                UsageInfo(fiveHour = 1.0, session = LimitBucket(1.0, "2026-11-09T11:00:00Z", null, null, 5.0)),
                now,
            ),
        )

        assertEquals("Claude · 5-Hour", scopedBucketLabel("Claude · 5-Hour"))
        assertEquals("Other Models · Weekly", scopedBucketLabel("Other Models · Weekly"))
        assertEquals("5-Hour Session", scopedBucketLabel("5-Hour Session"))
        assertEquals("Weekly (Fable)", scopedBucketLabel("Fable"))
    }

    @Test
    fun `reload refreshes both accounts and usages while blocking background polls, and toggle updates status`() = runTest {
        val source = FakeSource().apply {
            accounts = listOf(account("default"))
            usages = mapOf("default" to UsageInfo(fiveHour = 0.2))
        }
        val data = AntigravityAccountsData(source)
        data.load()
        assertEquals(listOf("default"), data.state.value.accounts.map { it.id })
        assertEquals(0.2, data.state.value.usages["default"]?.fiveHour)

        val chatRefresh = CompletableDeferred<Unit>()
        val reloadJob = async { data.reload { chatRefresh.await() } }
        runCurrent()
        assertTrue(data.state.value.loading)

        // Poll during refresh is ignored
        source.accounts = emptyList()
        data.load()
        assertEquals(listOf("default"), data.state.value.accounts.map { it.id })

        // Reload updates both accounts and usages
        source.accounts = listOf(account("default"), account("ag-2"))
        source.usages = mapOf("default" to UsageInfo(fiveHour = 0.5), "ag-2" to UsageInfo(fiveHour = 0.1))
        chatRefresh.complete(Unit)
        reloadJob.await()
        assertEquals(listOf("default", "ag-2"), data.state.value.accounts.map { it.id })
        assertEquals(0.5, data.state.value.usages["default"]?.fiveHour)
        assertFalse(data.state.value.loading)

        // Toggle flips active -> disabled and disabled -> active
        assertNull(data.toggle("ag-2", "active"))
        assertEquals("ag-2:disabled", source.patched.last())
        source.fail = "Account not found"
        assertEquals("Account not found", data.toggle("ag-2", "disabled"))
        assertEquals("Account not found", data.state.value.error)
        data.setError(null)
        assertNull(data.state.value.error)
    }

    @Test
    fun `parses Antigravity login start and status payloads`() {
        val started = parseAntigravityLoginStart(WireJson.parse("""{"id":"f1","authUrl":"https://accounts.google.com/o/oauth2/auth?x=1"}"""))!!
        assertEquals("f1", started.id)
        assertEquals("https://accounts.google.com/o/oauth2/auth?x=1", started.authUrl)
        assertEquals("pending", started.status)
        assertNull(parseAntigravityLoginStart(WireJson.parse("""{"id":"f1"}""")))

        val pending = parseAntigravityLoginStatus(WireJson.parse("""{"state":"pending"}"""))!!
        assertTrue(pending.isPending)
        val done = parseAntigravityLoginStatus(
            WireJson.parse("""{"state":"done","account":{"id":"ag-1","label":"Work","planType":"Pro","home":"/h","status":"active","addedAt":"t"}}"""),
        )!!
        assertTrue(done.isDone)
        assertEquals("Work · Pro", done.account?.displayLabel)
        val err = parseAntigravityLoginStatus(WireJson.parse("""{"state":"error","error":"Timed out"}"""))!!
        assertTrue(err.isError)
        assertEquals("Timed out", err.error)
    }

    @Test
    fun `session_state ignores New Chat placeholder title only for Antigravity and filters Resumed Chat and Chat`() {
        val agy = ChatTranscript(providerId = "antigravity")
        agy.handle(ChatFrame.SessionState(SessionStateFrame(phase = SessionPhase.IDLE, title = "New Chat")), now)
        assertNull(agy.title)
        agy.handle(ChatFrame.SessionState(SessionStateFrame(phase = SessionPhase.IDLE, title = "Resumed Chat")), now)
        assertNull(agy.title)
        agy.handle(ChatFrame.SessionState(SessionStateFrame(phase = SessionPhase.IDLE, title = "Chat")), now)
        assertNull(agy.title)
        agy.handle(ChatFrame.SessionState(SessionStateFrame(phase = SessionPhase.IDLE, title = "Fix Antigravity quota")), now)
        assertEquals("Fix Antigravity quota", agy.title)

        val claude = ChatTranscript(providerId = "claude")
        claude.handle(ChatFrame.SessionState(SessionStateFrame(phase = SessionPhase.IDLE, title = "New Chat")), now)
        assertEquals("New Chat", claude.title)
    }
}
