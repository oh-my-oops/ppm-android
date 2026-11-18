package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** The app's trace client against `POST /api/trace` (`trace-client.ts`'s rules). */
class TraceClientTest {
    /** Timers on a clock the test moves; a due task runs on the test's thread. */
    private class FakeClock {
        var now = 1_000_000L
        private class Task(val due: Long, val run: () -> Unit) { var cancelled = false }
        private val tasks = mutableListOf<Task>()

        val timers = TraceTimers { delayMs, task ->
            val t = Task(now + delayMs, task)
            tasks += t
            return@TraceTimers { t.cancelled = true }
        }

        fun advance(ms: Long) {
            val until = now + ms
            while (true) {
                val next = tasks.filter { !it.cancelled && it.due <= until }.minByOrNull { it.due } ?: break
                tasks -= next
                now = maxOf(now, next.due)
                next.run()
            }
            now = until
        }
    }

    private class MemoryStore : TraceQueueStore {
        var value: String? = null
        override fun read(): String? = value
        override fun write(json: String?) { value = json }
    }

    private lateinit var server: MockWebServer
    private val clock = FakeClock()
    private var api: ApiClient? = null
    private val deviceId = "3f2b8c1e-1111-4222-8333-944455556666"

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        api = ApiClient(server.url("/"), "the-token")
    }

    @After
    fun stop() {
        server.close()
        PpmHttp.userAgent = null
    }

    private fun client(
        storage: TraceQueueStore? = null,
        refId: () -> String? = { null },
        context: () -> Map<String, String?> = { emptyMap() },
        redactor: TraceRedactor = TraceRedactor(),
    ) = TraceClient(
        transport = HttpTraceTransport { api },
        deviceId = { deviceId },
        storage = storage,
        now = { clock.now },
        timers = clock.timers,
        refId = refId,
        context = context,
        redactor = redactor,
        launch = { block -> runBlocking { block() } },
    )

    private fun ok() = MockResponse.Builder().body("""{"ok":true,"data":{"accepted":1,"dropped":0}}""").build()

    private fun status(code: Int, retryAfter: String? = null) = MockResponse.Builder().code(code).apply {
        if (retryAfter != null) addHeader("Retry-After", retryAfter)
    }.body("""{"ok":false,"error":"x"}""").build()

    private fun RecordedRequest.json(): JsonElement = WireJson.parse(body!!.utf8())

    private fun RecordedRequest.entries() = json()["entries"].array.orEmpty()

    private fun next(): RecordedRequest? = server.takeRequest(200, TimeUnit.MILLISECONDS)

    @Test
    fun `lines go out in batches of at most 50, signed, with the device and the app's user agent`() {
        PpmHttp.userAgent = PpmHttp.androidUserAgent("0.9.3", "17", "Lenovo TB-J716F", tablet = true)
        repeat(3) { server.enqueue(ok()) }
        val trace = client()
        repeat(120) { trace.console(TraceClient.Level.INFO, listOf("line $it")) }
        clock.advance(TraceClient.LOG_FLUSH_MS)
        val sizes = List(3) { next()!! }.map { request ->
            assertEquals("POST", request.method)
            assertEquals("/api/trace", request.target)
            assertEquals("Bearer the-token", request.headers["Authorization"])
            assertEquals("PPM-Android/0.9.3 (Android 17; Lenovo TB-J716F; tablet)", request.headers["User-Agent"])
            assertEquals(deviceId, request.json()["deviceId"].string)
            assertTrue(TraceContract.DEVICE_ID.matches(request.json()["deviceId"].string!!))
            request.entries().size
        }
        assertEquals(listOf(50, 50, 20), sizes)
        assertNull(next())
        assertEquals(0, trace.pending())
    }

    @Test
    fun `a line waits five seconds, an error one`() {
        repeat(2) { server.enqueue(ok()) }
        val trace = client()
        trace.console(TraceClient.Level.WARN, listOf("slow"))
        clock.advance(TraceClient.LOG_FLUSH_MS - 1)
        assertNull(next())
        clock.advance(1)
        assertEquals("console_warn", next()!!.entries().single()["type"].string)

        trace.error("render_error", IllegalStateException("boom"))
        clock.advance(TraceClient.ERROR_FLUSH_MS - 1)
        assertNull(next())
        clock.advance(1)
        val entry = next()!!.entries().single()
        assertEquals("render_error", entry["type"].string)
        assertEquals("java.lang.IllegalStateException", entry["payload"]["name"].string)
        assertEquals("boom", entry["payload"]["message"].string)
        assertTrue(entry["payload"]["stack"].string!!.contains("TraceClientTest"))
    }

    @Test
    fun `an error carries the 50 lines before it, not itself`() {
        repeat(2) { server.enqueue(ok()) }
        val trace = client()
        repeat(60) { trace.console(TraceClient.Level.DEBUG, listOf("step", "$it")) }
        trace.console(TraceClient.Level.ERROR, listOf("it broke"))
        clock.advance(TraceClient.ERROR_FLUSH_MS)
        // 61 entries: a full batch, then the rest at once.
        assertEquals(50, next()!!.entries().size)
        val error = next()!!.entries().last()
        assertEquals("console_error", error["type"].string)
        val crumbs = error["payload"]["breadcrumbs"].array!!
        assertEquals(50, crumbs.size)
        assertEquals("step 10", crumbs.first()["text"].string)
        assertEquals("step 59", crumbs.last()["text"].string)
        assertEquals("debug", crumbs.last()["level"].string)
    }

    @Test
    fun `past 300 a minute only a count is kept, sent with the next minute`() {
        api = null
        val trace = client()
        repeat(305) { trace.console(TraceClient.Level.LOG, listOf("flood $it")) }
        assertEquals(300, trace.pending())
        clock.now += 60_000
        trace.console(TraceClient.Level.LOG, listOf("after"))
        // The queue holds 300 at most: the oldest went for the count and the new line.
        assertEquals(TraceClient.MAX_QUEUE, trace.pending())
        api = ApiClient(server.url("/"), "the-token")
        repeat(7) { server.enqueue(ok()) }
        clock.advance(TraceClient.AUTH_RECHECK_MS)
        val all = generateSequence { next() }.flatMap { it.entries() }.toList()
        val dropped = all.single { it["type"].string == "console_dropped" }
        assertEquals(5, dropped["payload"]["count"].int)
        assertEquals("after", all.last()["payload"]["args"].array!!.single().string)
    }

    @Test
    fun `a payload is shrunk under the cap, in bytes, before it is queued`() {
        server.enqueue(ok())
        val trace = client()
        trace.console(TraceClient.Level.WARN, List(10) { "x".repeat(5_000) })
        trace.console(TraceClient.Level.WARN, List(10) { "é漢".repeat(1_000) })
        clock.advance(TraceClient.LOG_FLUSH_MS)
        val entries = next()!!.entries()
        assertEquals(2, entries.size)
        for (entry in entries) {
            val bytes = entry["payload"].toString().toByteArray().size
            assertTrue("payload of $bytes bytes", bytes <= TraceClient.MAX_PAYLOAD_BYTES)
            assertTrue(bytes < TraceContract.MAX_PAYLOAD_BYTES)
        }
        assertTrue(entries[0]["payload"]["args"].array!![0].string!!.contains("more]"))
    }

    @Test
    fun `a 429 keeps the batch and waits as long as Retry-After says`() {
        server.enqueue(status(429, "30"))
        server.enqueue(ok())
        val trace = client()
        trace.console(TraceClient.Level.ERROR, listOf("x"))
        clock.advance(TraceClient.ERROR_FLUSH_MS)
        assertNotNull(next())
        assertEquals(1, trace.pending())
        clock.advance(29_999)
        assertNull(next())
        clock.advance(1)
        assertEquals(1, next()!!.entries().size)
        assertEquals(0, trace.pending())
    }

    @Test
    fun `a 503 keeps the batch and tries again after a backoff, a 400 drops it`() {
        server.enqueue(status(503))
        server.enqueue(ok())
        server.enqueue(status(400))
        val trace = client()
        trace.console(TraceClient.Level.ERROR, listOf("stored later"))
        clock.advance(TraceClient.ERROR_FLUSH_MS)
        assertNotNull(next())
        assertEquals(1, trace.pending())
        clock.advance(4_999)
        assertNull(next())
        clock.advance(1)
        assertEquals("stored later", next()!!.entries().single()["payload"]["args"].array!!.single().string)
        assertEquals(0, trace.pending())

        trace.console(TraceClient.Level.ERROR, listOf("refused"))
        clock.advance(TraceClient.ERROR_FLUSH_MS)
        assertNotNull(next())
        assertEquals(0, trace.pending())
    }

    @Test
    fun `refused or signed out, nothing is sent until another token`() {
        server.enqueue(status(401))
        server.enqueue(ok())
        val trace = client()
        trace.console(TraceClient.Level.ERROR, listOf("kept"))
        clock.advance(TraceClient.ERROR_FLUSH_MS)
        assertEquals("Bearer the-token", next()!!.headers["Authorization"])
        clock.advance(60_000)
        assertNull("the same token is not tried again", next())

        api = null
        clock.advance(60_000)
        assertNull("signed out", next())
        assertEquals(1, trace.pending())

        api = ApiClient(server.url("/"), "new-token")
        clock.advance(TraceClient.AUTH_RECHECK_MS)
        assertEquals("Bearer new-token", next()!!.headers["Authorization"])
        assertEquals(0, trace.pending())
    }

    @Test
    fun `a crash is on disk before recordFatal returns, and goes out at the next start`() {
        val file = File.createTempFile("trace-queue", ".json").also { it.delete() }
        try {
            api = null
            val before = client(storage = FileTraceQueueStore(file))
            before.console(TraceClient.Level.INFO, listOf("opened chat"))
            clock.advance(TraceClient.PERSIST_DEBOUNCE_MS)
            assertTrue("debounced persist", file.readText().contains("opened chat"))
            before.console(TraceClient.Level.WARN, listOf("about to fail"))
            before.recordFatal("browser_error", IllegalStateException("fatal"), mapOf("thread" to "main"))
            assertTrue("written synchronously", file.readText().contains("fatal"))

            // The next launch.
            api = ApiClient(server.url("/"), "the-token")
            server.enqueue(ok())
            val after = client(storage = FileTraceQueueStore(file))
            assertEquals(3, after.pending())
            clock.advance(TraceClient.START_FLUSH_MS)
            val entries = next()!!.entries()
            assertEquals(listOf("console_info", "console_warn", "browser_error"), entries.map { it["type"].string })
            val fatal = entries.last()["payload"]
            assertEquals("main", fatal["thread"].string)
            assertEquals("about to fail", fatal["breadcrumbs"].array!!.last()["text"].string)
            assertEquals(0, after.pending())
            clock.advance(TraceClient.PERSIST_DEBOUNCE_MS)
            assertFalse("the sent queue is cleared from disk", file.exists())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `the session in front and the context are stamped, an id the server would refuse is left out`() {
        server.enqueue(ok())
        var ref: String? = "8d1f0a52-6b1c-4c1f-9b52-0e2a3c4d5e6f"
        val trace = client(refId = { ref }, context = { mapOf("page" to "launch-1", "project" to "ppm", "missing" to null) })
        trace.console(TraceClient.Level.WARN, listOf("in chat"))
        ref = "has a space"
        trace.console(TraceClient.Level.WARN, listOf("odd id"))
        clock.advance(TraceClient.LOG_FLUSH_MS)
        val (first, second) = next()!!.entries()
        assertEquals("8d1f0a52-6b1c-4c1f-9b52-0e2a3c4d5e6f", first["refId"].string)
        assertEquals("launch-1", first["payload"]["page"].string)
        assertEquals("ppm", first["payload"]["project"].string)
        assertNull(first["payload"]["missing"])
        assertEquals(kotlinx.serialization.json.JsonNull, second["refId"])
    }

    @Test
    fun `no token, token URL, bearer header or remembered secret reaches the server or the queue file`() {
        val redactor = TraceRedactor()
        val password = "p+w&d/=hunter2"
        val vault = "vault-value-8842"
        redactor.remember(password)
        redactor.remember(vault)
        val socket = ApiClient("http://ppm.test:8080".toHttpUrl(), password).globalSocketUrl()
        assertTrue(socket.contains(ApiClient.escape(password)))
        val store = MemoryStore()
        server.enqueue(ok())
        val trace = client(storage = store, redactor = redactor)
        trace.console(TraceClient.Level.ERROR, listOf("socket $socket failed", "Authorization: Bearer abc.def.ghi", "typed $password"))
        trace.error("unhandled_rejection", IllegalStateException("could not read $vault"), extra = mapOf("url" to "https://h/x?token=$password"))
        trace.record("console_log", buildJsonObject { put("nested", buildJsonObject { put("value", JsonPrimitive("api_key: sk-123456")) }) })
        trace.onHidden()
        assertNotNull("persisted at once", store.value)
        clock.advance(0)
        val sent = next()!!.body!!.utf8()
        for (text in listOf(sent, store.value!!)) {
            for (secret in listOf(password, ApiClient.escape(password), vault, "abc.def.ghi", "sk-123456", "hunter2")) {
                assertFalse("$secret leaked", text.contains(secret))
            }
        }
        assertTrue(sent.contains(TraceRedactor.REDACTED))
    }

    @Test
    fun `every token the app sends with is remembered`() {
        ApiClient("http://ppm.test:8080".toHttpUrl(), "global-token-5531")
        assertEquals("x [REDACTED] y", TraceRedactor.Global.redact("x global-token-5531 y"))
        assertEquals("ok abc", TraceRedactor().apply { remember("abc") }.redact("ok abc"))
    }

    @Test
    fun `the device id is a UUID minted once`() {
        val values = mutableMapOf<String, String?>()
        val store = object : KeyValueStore {
            override fun get(key: String) = values[key]
            override fun put(key: String, value: String?) { values[key] = value }
        }
        val id = TraceDeviceId.get(store)
        assertTrue(TraceContract.DEVICE_ID.matches(id))
        assertEquals(id, TraceDeviceId.get(store))
        values[TraceDeviceId.KEY] = "bad id!"
        val replaced = TraceDeviceId.get(store)
        assertTrue(TraceContract.DEVICE_ID.matches(replaced))
    }

    @Test
    fun `the user agent keeps its fields readable`() {
        assertEquals("PPM-Android/1.2.3 (Android 16; Pixel 7; phone)", PpmHttp.androidUserAgent("1.2.3", "16", "Pixel 7", tablet = false))
        assertEquals("PPM-Android/1.2.3 (Android 16; Galaxy Tab S9 x; tablet)", PpmHttp.androidUserAgent("1.2.3", "16", "Galaxy (Tab) S9; x é", tablet = true))
        assertEquals(30_000L, HttpTraceTransport.retryAfterMs("30"))
        assertNull(HttpTraceTransport.retryAfterMs("soon"))
    }

    @Test
    fun `every API request carries the user agent once it is set`() = runBlocking {
        PpmHttp.userAgent = "PPM-Android/1.0.0 (Android 16; Pixel 7; phone)"
        server.enqueue(MockResponse.Builder().body("""{"ok":true,"data":null}""").build())
        api!!.health()
        assertEquals("PPM-Android/1.0.0 (Android 16; Pixel 7; phone)", server.takeRequest().headers["User-Agent"])
    }
}
