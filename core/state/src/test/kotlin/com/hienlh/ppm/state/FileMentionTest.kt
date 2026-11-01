package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.protocol.FileEntry
import com.hienlh.ppm.protocol.FileIndexAnswer
import com.hienlh.ppm.protocol.FileMention
import com.hienlh.ppm.protocol.SlashTrigger
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The web's own answers (the `@` trigger and pick in message-input.tsx, the filter in
 * file-picker.tsx), printed by the Apple port's `apple/scripts/vectors/file-mention.ts` into
 * `vectors/file-mention.json`.
 */
class FileMentionTest {
    private val vectors = WireJson.parse(javaClass.getResource("/vectors/file-mention.json")!!.readText())

    @Test
    fun `the picker opens on the web's @, after the slash picker's slash`() {
        for (vector in vectors["triggers"].array!!) {
            val text = vector["text"].string!!
            val slash = SlashTrigger.queryBefore(text)
            val file = if (slash == null) FileMention.queryBefore(text) else null
            val picker = if (slash != null) "slash" else if (file != null) "file" else null
            assertEquals(text, vector["picker"].string, picker)
            assertEquals(text, vector["query"].string, slash ?: file)
        }
    }

    @Test
    fun `a pick replaces the @query left of the cursor, or goes at the end`() {
        for (vector in vectors["picks"].array!!) {
            val picked = FileMention.pick(vector["path"].string!!, vector["text"].string!!, vector["caret"].int!!)
            assertEquals(vector.toString(), FileMention.Picked(vector["picked"]["text"].string!!, vector["picked"]["caret"].int!!), picked)
        }
    }

    /** Not from the vectors: the web's drop handler is three lines, `cur + sep + "@" + path + " "` with `sep` a space unless `cur` is empty or ends in one. */
    @Test
    fun `a dropped path goes at the end, after a space unless the box is empty or ends in one`() {
        assertEquals(FileMention.Picked("@src/app.kt ", 12), FileMention.append("src/app.kt", ""))
        assertEquals(FileMention.Picked("see @a.kt ", 10), FileMention.append("a.kt", "see"))
        assertEquals(FileMention.Picked("see @a.kt ", 10), FileMention.append("a.kt", "see "))
        // Only a space ends it: after a new line there is a space too.
        assertEquals(FileMention.Picked("see\n @a.kt ", 11), FileMention.append("a.kt", "see\n"))
        assertEquals(FileMention.Picked("@src/util/ ", 11), FileMention.append("src/util/", ""))
        // An `@query` that a pick would have replaced stays, as the cursor is not where the path goes.
        assertEquals(FileMention.Picked("@sr @a.kt ", 10), FileMention.append("a.kt", "@sr"))
    }

    @Test
    fun `the list holds what the web's filter keeps, fifty at most`() {
        val entries = vectors["entries"].array!!.mapNotNull(FileEntry::from)
        assertEquals(69, entries.size)
        assertTrue(entries.first().isDirectory)
        for (vector in vectors["filters"].array!!) {
            val list = FilePickerList(entries, vector["query"].string!!)
            assertEquals(vector["query"].string, vector["paths"].array!!.map { it.string!! }, list.items.map { it.path })
        }
        val list = FilePickerList(entries, "main")
        assertEquals(1, list.step(0, -1))
        assertEquals(0, list.step(1, 1))
        assertEquals(0, FilePickerList(emptyList(), "").step(0, 1))
    }

    @Test
    fun `the server's answer is the list, or only how long it is`() {
        assertEquals(
            FileIndexAnswer.Entries(listOf(FileEntry("a/b.ts", "b.ts"), FileEntry("a", "a", isDirectory = true))),
            FileIndexAnswer.from(WireJson.parse("""[{"path":"a/b.ts","name":"b.ts","type":"file"},{"path":"a","name":"a","type":"directory"},{"name":"no path"}]""")),
        )
        assertEquals(FileIndexAnswer.TooLarge(175_000), FileIndexAnswer.from(WireJson.parse("""{"tooLarge":true,"count":175000}""")))
        assertNull(FileIndexAnswer.from(WireJson.parse("""{"count":3}""")))
    }

    @Test
    fun `an entry the server flags as ignored keeps the flag, and no other does`() {
        val answer = FileIndexAnswer.from(
            WireJson.parse("""[{"path":".env","name":".env","type":"file","isIgnored":true},{"path":"a.ts","name":"a.ts","type":"file"},{"path":"b.ts","name":"b.ts","type":"file","isIgnored":"yes"}]"""),
        )
        assertEquals(
            FileIndexAnswer.Entries(listOf(FileEntry(".env", ".env", isIgnored = true), FileEntry("a.ts", "a.ts"), FileEntry("b.ts", "b.ts"))),
            answer,
        )
    }

    @Test
    fun `a change marks the list stale without reading it, and a reset drops it all`() = runBlocking {
        val store = FileIndexStore(ApiClient("http://127.0.0.1:9".toHttpUrl(), ""), this)
        assertEquals(FileIndexStore.Status.IDLE, store.state.value.status)
        store.markStale("p")
        assertFalse("a change to a project not held is not this list's", store.state.value.stale)
        store.load("p").join()
        assertEquals("p", store.state.value.project)
        assertEquals(FileIndexStore.Status.FAILED, store.state.value.status)
        store.markStale("p")
        assertTrue(store.state.value.stale)
        val close = store.openReader("p")
        close()
        close()
        store.reset()
        assertEquals(FileIndexStore.State(), store.state.value)
    }

    private val twoFiles = """{"ok":true,"data":[{"path":"a.ts","name":"a.ts","type":"file"},{"path":"b.ts","name":"b.ts","type":"file"}]}"""

    private fun <T> withServer(vararg responses: MockResponse, block: suspend CoroutineScope.(MockWebServer, ApiClient) -> T): T = runBlocking {
        val server = MockWebServer()
        responses.forEach(server::enqueue)
        server.start()
        try {
            block(server, ApiClient(server.url("/"), "t"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `a list is read once, kept when a refresh fails, and a change during a read leaves it stale`() = withServer(
        MockResponse.Builder().body(twoFiles).build(),
        MockResponse.Builder().code(500).body("""{"ok":false,"error":"boom"}""").build(),
        MockResponse.Builder().body(twoFiles).build(),
    ) { server, api ->
        val store = FileIndexStore(api, this)
        store.ensure("my app")
        assertEquals(FileIndexStore.Status.LOADING, store.state.value.status)
        store.ensure("my app")
        store.load("my app").join()
        assertEquals("/api/project/my%20app/files/index?max=50000", server.takeRequest().target)
        assertEquals(1, server.requestCount)
        assertEquals(listOf("a.ts", "b.ts"), store.state.value.entries.map { it.path })
        assertEquals(FileIndexStore.Status.READY, store.state.value.status)
        assertEquals(1, store.state.value.revision)
        store.ensure("my app")
        assertEquals(1, server.requestCount)

        store.markStale("my app")
        store.load("my app").join()
        assertEquals("a failed refresh keeps the list", FileIndexStore.Status.READY, store.state.value.status)
        assertEquals(2, store.state.value.entries.size)
        assertTrue(store.state.value.stale)

        val read = store.load("my app")
        store.markStale("my app")
        read.join()
        assertEquals(2, store.state.value.revision)
        assertTrue("a change while it was read", store.state.value.stale)
    }

    @Test
    fun `a rebuild is read at once only while a picker shows the list`() = withServer(
        MockResponse.Builder().body(twoFiles).build(),
        MockResponse.Builder().body(twoFiles).build(),
        MockResponse.Builder().body("""{"ok":true,"data":[{"path":"c.ts","name":"c.ts","type":"file"}]}""").build(),
    ) { server, api ->
        val store = FileIndexStore(api, this)
        store.load("p").join()
        server.takeRequest()
        store.rebuilt("p")
        assertTrue(store.state.value.stale)
        yield()
        assertNull("no picker shows it, so nothing is read", server.takeRequest(200, TimeUnit.MILLISECONDS))
        val close = store.openReader("p")
        store.load("p").join()
        server.takeRequest()
        assertFalse(store.state.value.stale)
        store.rebuilt("p")
        withTimeout(5_000) { store.state.first { state -> state.entries.map { it.path } == listOf("c.ts") } }
        assertFalse(store.state.value.stale)
        close()
        store.rebuilt("other")
        assertFalse(store.state.value.stale)
    }

    @Test
    fun `a list too long to send is searched on the server`() = withServer(
        MockResponse.Builder().body("""{"ok":true,"data":{"tooLarge":true,"count":175000}}""").build(),
        MockResponse.Builder().body("""{"ok":true,"data":[{"path":"src","name":"src","type":"directory"}]}""").build(),
    ) { server, api ->
        val store = FileIndexStore(api, this)
        assertNull(store.searchRemote("src"))
        store.load("p").join()
        server.takeRequest()
        assertTrue(store.state.value.remote)
        assertEquals(emptyList<FileEntry>(), store.state.value.entries)
        assertEquals(listOf(FileEntry("src", "src", isDirectory = true)), store.searchRemote("s rc"))
        assertEquals("/api/project/p/files/index/search?q=s%20rc&kind=all&limit=100", server.takeRequest().target)
    }

    @Test
    fun `the palette asks the server for files only, the picker for folders as well`() = withServer(
        MockResponse.Builder().body("""{"ok":true,"data":{"tooLarge":true,"count":175000}}""").build(),
        MockResponse.Builder().body("""{"ok":true,"data":[{"path":"src/a.ts","name":"a.ts","type":"file","isIgnored":true}]}""").build(),
    ) { server, api ->
        val store = FileIndexStore(api, this)
        store.load("p").join()
        server.takeRequest()
        assertEquals(listOf(FileEntry("src/a.ts", "a.ts", isIgnored = true)), store.searchRemote("a", directories = false))
        assertEquals("/api/project/p/files/index/search?q=a&kind=file&limit=100", server.takeRequest().target)
    }
}
