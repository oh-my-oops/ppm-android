package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.CodexAccount
import com.hienlh.ppm.protocol.UsageInfo
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

/** The Codex panel's data (codex-usage-panel.tsx): newest read wins, polls wait out a refresh, switches. */
@OptIn(ExperimentalCoroutinesApi::class)
class CodexAccountsDataTest {
    private fun account(id: String, status: String = "active") = CodexAccount(id, id, "chatgpt", null, status, false, false)

    private class FakeSource : CodexAccountsData.Source {
        var accounts = listOf<CodexAccount>()
        var usages = mapOf<String, UsageInfo>()
        var accountsGate: CompletableDeferred<Unit>? = null
        var fail: String? = null
        val patched = mutableListOf<String>()

        override suspend fun accounts(): List<CodexAccount> {
            val snapshot = accounts
            accountsGate?.await()
            return snapshot
        }

        override suspend fun usages() = usages

        override suspend fun setStatus(id: String, status: String) {
            patched += "$id:$status"
            fail?.let { throw IllegalStateException(it) }
        }

        override suspend fun setDailyGuard(id: String, enabled: Boolean) {
            patched += "$id:guard=$enabled"
            fail?.let { throw IllegalStateException(it) }
        }
    }

    @Test
    fun `a read overtaken by a newer one does not land`() = runTest {
        val source = FakeSource().apply { accounts = listOf(account("old")) }
        val data = CodexAccountsData(source)
        val gate = CompletableDeferred<Unit>()
        source.accountsGate = gate
        val slow = launch { data.load() }
        runCurrent()
        source.accountsGate = null
        source.accounts = listOf(account("new"))
        data.load()
        assertEquals(listOf("new"), data.state.value.accounts.map { it.id })
        assertFalse(data.state.value.loading)
        // The first read comes back late, with what it was given before.
        gate.complete(Unit)
        slow.join()
        assertEquals(listOf("new"), data.state.value.accounts.map { it.id })
        assertFalse(data.state.value.loading)
    }

    @Test
    fun `a poll during a refresh is skipped, and the refresh reads the quota after the chat`() = runTest {
        val source = FakeSource().apply {
            accounts = listOf(account("a"))
            usages = mapOf("a" to UsageInfo(fiveHour = 0.1))
        }
        val data = CodexAccountsData(source)
        data.load()
        val chat = CompletableDeferred<Unit>()
        val refresh = async { data.reload { chat.await() } }
        runCurrent()
        assertTrue(data.state.value.loading)
        source.usages = mapOf("a" to UsageInfo(fiveHour = 0.9))
        source.accounts = emptyList()
        data.load()
        // Skipped: the accounts are what they were.
        assertEquals(listOf("a"), data.state.value.accounts.map { it.id })
        chat.complete(Unit)
        refresh.await()
        assertEquals(0.9, data.state.value.usages["a"]?.fiveHour)
        assertFalse(data.state.value.loading)
    }

    @Test
    fun `switches flip the status or the guard, read the accounts again, and answer refusals`() = runTest {
        val source = FakeSource().apply { accounts = listOf(account("a", "disabled")) }
        val data = CodexAccountsData(source)
        assertNull(data.toggle("a", "disabled"))
        assertEquals("a:active", source.patched.last())
        assertEquals("disabled", data.state.value.accounts.single().status)
        assertNull(data.state.value.togglingId)

        assertNull(data.toggleDailyGuard("a", enabled = true))
        assertEquals("a:guard=false", source.patched.last())

        source.fail = "Account not found"
        assertEquals("Account not found", data.toggle("a", "active"))
        source.fail = ""
        assertEquals("Could not change Daily guard", data.toggleDailyGuard("a", enabled = false))
        assertNull(data.state.value.togglingId)
    }
}
