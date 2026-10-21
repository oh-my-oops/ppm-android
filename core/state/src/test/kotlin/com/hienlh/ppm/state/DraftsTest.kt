package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.protocol.ChatDraft
import com.hienlh.ppm.protocol.DraftAttachment
import com.hienlh.ppm.protocol.ReplyReference
import com.hienlh.ppm.state.workspace.MemoryValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** `use-draft.ts`: this device's copy, the server's, and the order the two are written in. */
class DraftsTest {
    private val values = MemoryValues()
    private val store = DraftLocalStore(values, "server-a")
    private val key = store.key("p", "tab-1", "s-1")

    private val reply = ReplyReference(
        sessionId = "s-1", providerId = "claude", messageId = "m-9", role = ReplyReference.Role.ASSISTANT,
        timestamp = "2026-10-20T01:02:03.000Z", quote = "quoted", truncated = false,
    )

    private val saved = MockResponse.Builder().body("""{"ok":true,"data":{"saved":true}}""").build()

    private fun row(content: String) = MockResponse.Builder().body("""{"ok":true,"data":{"content":"$content","attachments":"[]","updatedAt":"x"}}""").build()

    private val ok = MockResponse.Builder().body("""{"ok":true,"data":{"deleted":true}}""").build()

    private fun withServer(vararg responses: MockResponse, block: suspend CoroutineScope.(MockWebServer, (Long) -> DraftSync) -> Unit) = runBlocking {
        val server = MockWebServer()
        responses.forEach(server::enqueue)
        server.start()
        try {
            val api = ApiClient(server.url("/"), "t")
            block(server) { debounce -> DraftSync(api, "p", "tab-1", "s-1", store, this, debounce) }
        } finally {
            server.close()
        }
    }

    /** Waits off the test's thread, which is the one the drafts' saves run on. */
    private suspend fun MockWebServer.next(): RecordedRequest = withContext(Dispatchers.IO) { takeRequest(2, TimeUnit.SECONDS)!! }

    @Test
    fun `keyed like the web's, per server, and read back whole`() {
        assertEquals("""draft.server-a.["p","tab-1","s-1"]""", key)
        assertFalse(key == DraftLocalStore(values, "server-b").key("p", "tab-1", "s-1"))
        val draft = ChatDraft("x", listOf(DraftAttachment("n", "/p")), reply)
        store.write(key, draft)
        assertEquals(draft, store.read(key)?.draft)
        assertEquals(false, store.read(key)?.synced)
        // Only the copy the server took is marked; one typed since is not.
        store.markSynced(key, ChatDraft("older"))
        assertEquals(false, store.read(key)?.synced)
        store.markSynced(key, draft)
        assertEquals(true, store.read(key)?.synced)
        store.remove(key)
        assertNull(store.read(key))
    }

    @Test
    fun `this device's copy wins while the server does not have it, without asking`() = withServer { server, drafts ->
        val sync = drafts(60_000)
        sync.save(ChatDraft("typed here"))
        sync.cancelPendingSave()
        assertEquals(ChatDraft("typed here"), sync.load())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the server gets the last edit a second after typing stops, and then holds this device's copy`() = withServer(saved) { server, drafts ->
        val sync = drafts(50)
        sync.save(ChatDraft("a"))
        sync.save(ChatDraft("b"))
        val put = server.next()
        assertEquals("PUT", put.method)
        assertEquals("""{"content":"b","attachments":"[]"}""", put.body?.utf8())
        delay(100)
        assertEquals(1, server.requestCount)
        assertEquals(true, store.read(key)?.synced)
    }

    @Test
    fun `once the server has it, the server's answer wins, unless it cannot be asked`() = withServer(
        row("carried on elsewhere"),
        MockResponse.Builder().code(500).body("""{"ok":false,"error":"down"}""").build(),
        MockResponse.Builder().body("""{"ok":true,"data":null}""").build(),
    ) { _, drafts ->
        val sync = drafts(60_000)
        store.write(key, ChatDraft("saved from here"), synced = true)
        assertEquals(ChatDraft("carried on elsewhere"), sync.load())
        // The server cannot be asked: what it last took from here.
        assertEquals(ChatDraft("saved from here"), sync.load())
        // Sent or cleared somewhere else: nothing, and this copy goes.
        assertNull(sync.load())
        assertNull(store.read(key))
    }

    @Test
    fun `an edit while the server's answer is on its way wins over it`() = withServer(
        MockResponse.Builder().headersDelay(300, TimeUnit.MILLISECONDS).body("""{"ok":true,"data":{"content":"late","attachments":"[]","updatedAt":"x"}}""").build(),
    ) { _, drafts ->
        val sync = drafts(60_000)
        val loading = async { sync.load() }
        delay(50)
        sync.save(ChatDraft("first keystroke"))
        sync.cancelPendingSave()
        assertNull(loading.await())
    }

    @Test
    fun `a sent message drops the save still waiting and deletes the draft`() = withServer(ok) { server, drafts ->
        val sync = drafts(60_000)
        sync.save(ChatDraft("the message"))
        sync.clear()
        val delete = server.next()
        assertEquals("DELETE", delete.method)
        assertEquals("/api/project/p/chat/drafts/s-1", delete.target)
        delay(100)
        assertEquals(1, server.requestCount)
        assertNull(store.read(key))
    }

    @Test
    fun `a delete waits for the save already on its way, so the message cannot come back as a draft`() = withServer(
        MockResponse.Builder().headersDelay(400, TimeUnit.MILLISECONDS).body("""{"ok":true,"data":{"saved":true}}""").build(),
        ok,
    ) { server, drafts ->
        val sync = drafts(10)
        sync.save(ChatDraft("the message"))
        assertEquals("PUT", server.next().method)
        sync.clear()
        delay(150)
        assertEquals(1, server.requestCount)
        assertEquals("DELETE", server.next().method)
    }

    @Test
    fun `leaving the screen sends a waiting save at once`() = withServer(saved) { server, drafts ->
        val sync = drafts(60_000)
        sync.save(ChatDraft("half"))
        sync.flush()
        val put = server.next()
        assertEquals("""{"content":"half","attachments":"[]"}""", put.body?.utf8())
        // Nothing waits any more: a second flush sends nothing.
        sync.flush()
        delay(100)
        assertEquals(1, server.requestCount)
        assertTrue(store.read(key)?.synced == true)
    }
}
