package com.hienlh.ppm.app

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.HttpTraceTransport
import com.hienlh.ppm.client.PpmHttp
import com.hienlh.ppm.client.TraceClient
import com.hienlh.ppm.client.TraceSendResult
import com.hienlh.ppm.client.TraceTimers
import com.hienlh.ppm.client.TraceTransport
import com.hienlh.ppm.design.PpmLog
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.logs.DEVICE_LOG_SOURCE
import com.hienlh.ppm.state.logs.DeviceLogBuffer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The app's own lines: kept on the device for "This device", and — the reason the Android app
 * never showed in PPM's Logs — actually sent, since a healthy app now logs its launch, the server
 * it opened, lost connections and the error toasts it showed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDeviceLogTest {
    private class MemoryDeviceStore : DeviceLogBuffer.Store {
        var value: String? = null
        override fun read(): String? = value
        override fun write(text: String) { value = text }
    }

    private object SignedOut : TraceTransport {
        override fun token(): String? = null
        override suspend fun send(body: String, token: String) = TraceSendResult(0)
    }

    /** Timers that wait to be run by the test. */
    private class ManualTimers {
        val pending = mutableListOf<() -> Unit>()
        val timers = TraceTimers { _, task ->
            pending += task
            return@TraceTimers { pending.remove(task) }
        }

        fun runAll() {
            repeat(10) {
                val due = pending.toList()
                pending.clear()
                due.forEach { it() }
            }
        }
    }

    private val deviceStore = MemoryDeviceStore()
    private val device = DeviceLogBuffer(store = deviceStore)
    private val quietTrace = TraceClient(SignedOut, deviceId = { "device-0001" }, timers = TraceTimers { _, _ -> {} }, launch = {})
    private var previousSink: PpmLog.Sink? = null

    @Before
    fun attach() {
        previousSink = PpmLog.sink
        PpmLog.sink = AppTrace.LogSink(quietTrace, device) { "session-42" }
    }

    @After
    fun detach() {
        PpmLog.sink = previousSink
        PpmHttp.userAgent = null
    }

    @Test
    fun `every logged line is kept on the device with its level, tag, stack and the chat in front`() {
        PpmLog.d("NativeTerminal", "Replayed 10 bytes")
        PpmLog.i("PPM", "opened server")
        PpmLog.w("PPM", "workspace: no project selected", IllegalStateException("no project"))
        PpmLog.e("Push", "failed")
        PpmLog.renderError("HtmlSurface", "The WebView renderer crashed")
        val lines = device.entries()
        assertEquals(listOf("debug", "info", "warn", "error", "error"), lines.map { it.lv })
        assertEquals(listOf("NativeTerminal", "PPM", "PPM", "Push", "HtmlSurface"), lines.map { it.tag })
        assertTrue(lines.all { it.src == DEVICE_LOG_SOURCE && it.sid == "session-42" })
        assertEquals("workspace: no project selected", lines[2].msg)
        assertTrue(lines[2].more.first().startsWith("java.lang.IllegalStateException: no project"))
    }

    @Test
    fun `a crash is kept on the device and written out before the process goes`() {
        var handedOn: Throwable? = null
        val recorder = AppTrace.CrashRecorder(quietTrace, device) { _, error -> handedOn = error }
        recorder.uncaughtException(Thread.currentThread(), IllegalArgumentException("bad state"))
        assertNotNull(handedOn)
        val restored = DeviceLogBuffer(store = deviceStore).entries().single()
        assertEquals("fatal", restored.lv)
        assertEquals("crash", restored.tag)
        assertTrue(restored.msg, restored.msg.contains("IllegalArgumentException: bad state"))
    }

    @Test
    fun `error and warning toasts, lost connections and the opened server are lines, other toasts are not`() {
        val api = ApiClient(okhttp3.HttpUrl.Builder().scheme("https").host("ppm.example.test").build(), "secret-token")
        AppLogLines.opened(api)
        AppLogLines.toast(ErrorToastMessage("Could not save", description = "Disk full"))
        AppLogLines.toast(ErrorToastMessage("Careful", kind = ErrorToastMessage.Kind.WARNING))
        AppLogLines.toast(ErrorToastMessage("Saved", kind = ErrorToastMessage.Kind.SUCCESS))
        AppLogLines.connection(api, was = false, now = false)
        AppLogLines.connection(api, was = false, now = true)
        AppLogLines.connection(api, was = true, now = false)
        val lines = device.entries().map { "${it.lv} ${it.tag} ${it.msg}" }
        assertEquals(
            listOf(
                "info PPM opened server https://ppm.example.test:443",
                "error toast Could not save — Disk full",
                "warn toast Careful",
                "warn PPM lost the connection to https://ppm.example.test:443",
                "info PPM the connection to https://ppm.example.test:443 is back",
            ),
            lines,
        )
        assertFalse(lines.joinToString().contains("secret-token"))
    }

    @Test
    fun `a fresh app's launch line reaches POST api trace as a batch trace_ts accepts whole`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("""{"ok":true,"data":{"accepted":2,"dropped":0}}""").build())
            PpmHttp.userAgent = PpmHttp.androidUserAgent("0.9.3", "17", "Lenovo TB-J716F", tablet = true)
            val api = ApiClient(server.url("/"), "the-token")
            val timers = ManualTimers()
            val trace = TraceClient(
                transport = HttpTraceTransport { api },
                deviceId = { "3f2b8c1e-1111-4222-8333-944455556666" },
                timers = timers.timers,
                refId = { "session-42" },
                context = { mapOf("page" to "launch-1", "project" to "ppm") },
                launch = { block -> runBlocking { block() } },
            )
            PpmLog.sink = AppTrace.LogSink(trace, device)

            PpmLog.i("PPM", AppTrace.launchLine("0.9.3", "17", "Lenovo TB-J716F"))
            AppLogLines.opened(api)
            timers.runAll()

            val request = server.takeRequest(2, TimeUnit.SECONDS)
            assertNotNull("the app sent nothing", request)
            request!!
            assertEquals("POST", request.method)
            assertEquals("/api/trace", request.target)
            assertEquals("Bearer the-token", request.headers["Authorization"])
            assertEquals("PPM-Android/0.9.3 (Android 17; Lenovo TB-J716F; tablet)", request.headers["User-Agent"])
            val body = WireJson.parse(request.body!!.utf8())
            val accepted = acceptedBy(body)
            assertEquals(2, accepted.size)
            assertEquals(
                listOf("[PPM] PPM app 0.9.3 started on Android 17 (Lenovo TB-J716F)", "[PPM] opened server " + server.url("/").toString().trimEnd('/')),
                accepted.map { it["payload"]["args"].array!![0].string },
            )
            assertEquals(0, trace.pending())
        }
    }

    /**
     * `parseBrowserBatch` (`src/services/session-trace/browser-trace-ingest.ts`) in Kotlin: the
     * envelope must pass or the whole batch is a 400, and each entry is kept or dropped. Returns
     * the entries kept, after asserting none was dropped.
     */
    private fun acceptedBy(body: JsonElement): List<JsonElement> {
        assertTrue("body must be an object", body is JsonObject)
        val deviceId = (body["deviceId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        assertTrue("invalid deviceId", deviceId != null && Regex("^[A-Za-z0-9-]{8,64}$").matches(deviceId))
        val sentAt = (body["sentAt"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        assertTrue("invalid sentAt", sentAt != null && sentAt.isFinite())
        val entries = body["entries"].array
        assertNotNull("entries must be an array", entries)
        assertTrue("at most 50 entries per batch", entries!!.size <= 50)
        val kept = entries.filter { entry ->
            val type = (entry["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val ts = (entry["ts"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            val refId = entry["refId"]
            val payload = entry["payload"]
            type in BROWSER_TRACE_TYPES &&
                ts != null && ts.isFinite() &&
                (refId == null || refId is JsonNull || (refId is JsonPrimitive && refId.isString && Regex("^[A-Za-z0-9._:-]{1,128}$").matches(refId.content))) &&
                payload is JsonObject && payload.toString().toByteArray().size <= 16 * 1024
        }
        assertEquals("entries the server would drop", entries.size, kept.size)
        return kept
    }

    private companion object {
        /** `BROWSER_TRACE_TYPES` in `src/shared/session-trace.ts`. */
        val BROWSER_TRACE_TYPES = setOf(
            "console_log", "console_info", "console_debug", "console_warn", "console_error", "browser_error",
            "unhandled_rejection", "render_error", "chunk_error", "entry_never_ran", "console_dropped",
        )
    }
}
