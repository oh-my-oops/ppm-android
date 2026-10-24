package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.AccountInfo
import com.hienlh.ppm.protocol.AccountUsage
import com.hienlh.ppm.protocol.AccountUsageEntry
import com.hienlh.ppm.protocol.LimitBucket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The accounts panel's data (use-accounts-data.ts): loads that stand alone, the flash, the switch. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountsDataTest {
    private fun entry(id: String, session: Double) =
        AccountUsageEntry(id, id.uppercase(), "active", true, AccountUsage(session = LimitBucket(session, "", 30, null, 5.0)))

    private fun info(id: String, status: String = "active") = AccountInfo(id, id, null, status, null, true, false, null)

    private class FakeSource : AccountsData.Source {
        var usages = listOf<AccountUsageEntry>()
        var accounts = listOf<AccountInfo>()
        var active: AccountInfo? = null
        var failUsages = false
        var failActive = false
        var refuse: String? = null
        var gate: CompletableDeferred<Unit>? = null
        val patched = mutableListOf<Pair<String, String>>()

        override suspend fun usages(): List<AccountUsageEntry> {
            if (failUsages) error("upstream down")
            return usages
        }

        override suspend fun accounts() = accounts

        override suspend fun active(): AccountInfo? {
            if (failActive) error("no")
            return active
        }

        override suspend fun setStatus(id: String, status: String) {
            gate?.await()
            patched += id to status
            refuse?.let { throw IllegalStateException(it) }
        }
    }

    private fun TestScope.data(source: FakeSource) = AccountsData(backgroundScope, source)

    @Test
    fun `one endpoint failing does not blank the others, and an empty answer for the active account is still one`() = runTest {
        val source = FakeSource().apply {
            usages = listOf(entry("a", 0.4))
            accounts = listOf(info("a"))
            active = info("a")
        }
        val data = data(source)
        assertTrue(data.state.value.initialLoading)
        data.reload()
        assertEquals(listOf("a"), data.state.value.usages.map { it.accountId })
        assertEquals("a", data.state.value.activeAccountId)
        assertFalse(data.state.value.initialLoading)

        source.failUsages = true
        source.accounts = listOf(info("a"), info("b"))
        source.active = null
        data.reload()
        // The usage kept its last answer; the rest took theirs.
        assertEquals(listOf("a"), data.state.value.usages.map { it.accountId })
        assertEquals(2, data.state.value.accounts.size)
        assertNull(data.state.value.activeAccountId)

        source.failActive = true
        source.active = info("b")
        data.reload()
        assertNull(data.state.value.activeAccountId)
    }

    @Test
    fun `a refresh lights the accounts whose figures moved, for a second and a half`() = runTest {
        val source = FakeSource().apply { usages = listOf(entry("a", 0.4), entry("b", 0.1)) }
        val data = data(source)
        data.reload()
        // The first load lights nothing.
        assertTrue(data.state.value.flashIds.isEmpty())

        source.usages = listOf(entry("a", 0.5), entry("b", 0.1), entry("c", 0.0))
        data.reload()
        assertEquals(setOf("a", "c"), data.state.value.flashIds)
        advanceTimeBy(AccountsData.FLASH_MILLIS - 1)
        runCurrent()
        assertEquals(setOf("a", "c"), data.state.value.flashIds)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(data.state.value.flashIds.isEmpty())

        // Nothing moved: nothing lit.
        data.reload()
        assertTrue(data.state.value.flashIds.isEmpty())
    }

    @Test
    fun `a switch in flight marks its account, and the reload after it is a refresh`() = runTest {
        val source = FakeSource().apply { usages = listOf(entry("a", 0.4)) }
        val data = data(source)
        data.reload()
        source.gate = CompletableDeferred()
        val gate = source.gate!!
        val toggling = async { data.toggle("a", "active") }
        runCurrent()
        assertEquals("a", data.state.value.togglingId)
        gate.complete(Unit)
        assertNull(toggling.await())
        assertNull(data.state.value.togglingId)
        assertFalse(data.state.value.initialLoading)
        assertFalse(data.state.value.refreshing)
    }

    @Test
    fun `the switch flips the status, reloads either way, and answers the server's refusal`() = runTest {
        val source = FakeSource().apply {
            usages = listOf(entry("a", 0.4))
            accounts = listOf(info("a", "disabled"))
        }
        val data = data(source)
        data.reload()
        assertNull(data.toggle("a", "disabled"))
        assertEquals("a" to "active", source.patched.last())

        source.refuse = "Could not reach Anthropic to verify this account."
        source.accounts = listOf(info("a", "active"))
        assertEquals("Could not reach Anthropic to verify this account.", data.toggle("a", "active"))
        assertEquals("a" to "disabled", source.patched.last())
        // Reloaded after the refusal too.
        assertEquals("active", data.state.value.accounts.single().status)
        assertNull(data.state.value.togglingId)
    }
}
