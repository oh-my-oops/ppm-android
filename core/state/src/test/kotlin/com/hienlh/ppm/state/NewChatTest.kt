package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.AccountPick
import com.hienlh.ppm.protocol.ChatPreparationSettings
import com.hienlh.ppm.protocol.PickedAccount
import com.hienlh.ppm.protocol.PreparedChat
import com.hienlh.ppm.protocol.ProviderInfo
import com.hienlh.ppm.state.workspace.MemoryValues
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a new chat tab is resolved from (chat-preference-local-cache.ts), and its one preparation (new-chat-prepare-client.ts). */
class NewChatTest {
    private fun prepared(provider: String, pick: AccountPick) = PreparedChat(
        resolvedProviderId = provider, providerId = provider, settings = ChatPreparationSettings(provider, null, emptyMap()),
        providers = emptyList(), pickedAccount = pick, usage = null, draft = null, tags = null, slash = null,
    )

    @Test
    fun `the settings and providers come back as they went in, per server and project`() {
        val values = MemoryValues()
        val cache = NewChatCache(values, "server-a")
        assertNull(cache.settings())
        val settings = ChatPreparationSettings("codex", "follow-focus", mapOf("claude" to "default"))
        cache.writeSettings(settings)
        cache.writeProviders("my app", listOf(ProviderInfo("claude", "Claude"), ProviderInfo("codex", "Codex")))
        assertEquals(settings, cache.settings())
        assertEquals(listOf("claude", "codex"), cache.providers("my app")?.map { it.id })
        assertNull(cache.providers("other"))
        assertNull(NewChatCache(values, "server-b").settings())
    }

    @Test
    fun `a tab is prepared once, joined while it is fresh, and forgotten once it has a session`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[kotlinx.coroutines.Job]))
        val preparations = ChatPreparations(scope, joinMillis = 50)
        val answer = CompletableDeferred<PreparedChat>()
        var asked = 0
        val applied = mutableListOf<Boolean>()
        val first = preparations.start("t1", { asked++; answer.await() }) { _, current -> applied += current }
        val joined = preparations.start("t1", { asked++; answer.await() }) { _, current -> applied += current }
        assertSame(first, joined)
        answer.complete(prepared("mock", AccountPick.None))
        first.await()
        assertEquals(1, asked)
        assertEquals(listOf(true), applied)
        assertTrue(preparations.isPrepared("t1"))
        assertTrue(preparations.foundNoAccount("t1", "mock"))
        assertFalse(preparations.foundNoAccount("t1", "claude"))
        // Past the join window the answer is let go, but what it taught stays.
        delay(150)
        assertNull(preparations["t1"])
        assertTrue(preparations.isPrepared("t1"))
        preparations.forget("t1")
        assertFalse(preparations.isPrepared("t1"))
        assertFalse(preparations.foundNoAccount("t1", "mock"))
        scope.cancel()
    }

    @Test
    fun `an answer that lands after the tab moved on is not the tab's, and a failure is only its asker's`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[kotlinx.coroutines.Job]))
        val preparations = ChatPreparations(scope, joinMillis = 50)
        val answer = CompletableDeferred<PreparedChat>()
        val applied = mutableListOf<Boolean>()
        val late = preparations.start("t1", { answer.await() }) { _, current -> applied += current }
        preparations.forget("t1")
        answer.complete(prepared("claude", AccountPick.Picked(PickedAccount("a1", null))))
        late.await()
        assertEquals(listOf(false), applied)
        assertFalse(preparations.isPrepared("t1"))
        val failed = preparations.start("t2", { error("server down") }) { _, _ -> }
        val error = runCatching { failed.await() }.exceptionOrNull()
        assertEquals("server down", error?.message)
        assertFalse(preparations.isPrepared("t2"))
        scope.cancel()
    }
}
