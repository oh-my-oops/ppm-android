package com.hienlh.ppm.app

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.TraceClient
import com.hienlh.ppm.client.TraceQueueStore
import com.hienlh.ppm.client.TraceSendResult
import com.hienlh.ppm.client.TraceTimers
import com.hienlh.ppm.client.TraceTransport
import com.hienlh.ppm.design.PpmLog
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the app feeds its trace client: [PpmLog]'s lines, render failures and crashes, with no secret in them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppTraceTest {
    private class MemoryStore : TraceQueueStore {
        var value: String? = null
        override fun read(): String? = value
        override fun write(json: String?) { value = json }
    }

    /** Signed out: nothing is sent, so the queue is what the test reads. */
    private object SignedOut : TraceTransport {
        override fun token(): String? = null
        override suspend fun send(body: String, token: String) = TraceSendResult(0)
    }

    private val store = MemoryStore()
    private val trace = TraceClient(
        transport = SignedOut,
        deviceId = { "device-0001" },
        storage = store,
        timers = TraceTimers { _, _ -> {} },
        launch = {},
    )
    private var previousSink: PpmLog.Sink? = null

    @Before
    fun attach() {
        previousSink = PpmLog.sink
        PpmLog.sink = AppTrace.LogSink(trace)
    }

    @After
    fun detach() {
        PpmLog.sink = previousSink
    }

    private fun queued(): List<JsonElement> {
        trace.onHidden()
        return WireJson.parse(store.value!!).array!!
    }

    @Test
    fun `every PpmLog level reaches the trace as the console level it stands for`() {
        PpmLog.d("NativeTerminal", "Replayed 10 bytes")
        PpmLog.i("PPM", "workspace: tab request waits")
        PpmLog.w("PPM", "workspace: no project selected", IllegalStateException("no project"))
        PpmLog.e("Push", "failed")
        val entries = queued()
        assertEquals(listOf("console_debug", "console_info", "console_warn", "console_error"), entries.map { it["type"].string })
        val warn = entries[2]["payload"]["args"].array!!
        assertEquals("[PPM] workspace: no project selected", warn[0].string)
        assertTrue(warn[1].string!!.startsWith("java.lang.IllegalStateException: no project"))
        assertEquals(3, entries[3]["payload"]["breadcrumbs"].array!!.size)
    }

    @Test
    fun `a render failure the app survived is a render error naming where`() {
        PpmLog.renderError("HtmlSurface", "The WebView renderer crashed")
        val entry = queued().single()
        assertEquals("render_error", entry["type"].string)
        assertEquals("The WebView renderer crashed", entry["payload"]["message"].string)
        assertEquals("HtmlSurface", entry["payload"]["where"].string)
    }

    @Test
    fun `an uncaught exception is stored before the crash goes on as before`() {
        var handedOn: Throwable? = null
        val recorder = AppTrace.CrashRecorder(trace) { _, error -> handedOn = error }
        val error = IllegalArgumentException("bad state")
        recorder.uncaughtException(Thread.currentThread(), error)
        assertSame(error, handedOn)
        val entry = WireJson.parse(store.value!!).array!!.single()
        assertEquals("browser_error", entry["type"].string)
        assertEquals("bad state", entry["payload"]["message"].string)
        assertEquals("true", entry["payload"]["fatal"].string)
        assertEquals(Thread.currentThread().name, entry["payload"]["thread"].string)
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `a coroutine's uncaught exception is reported as an unhandled rejection with its context`() {
        var handedOn: Throwable? = null
        val worker = Thread {
            GlobalScope.launch(Dispatchers.Unconfined + CoroutineName("loader")) { throw IllegalStateException("from a coroutine") }
        }
        worker.uncaughtExceptionHandler = AppTrace.CrashRecorder(trace) { _, error -> handedOn = error }
        worker.start()
        worker.join(5_000)
        assertNotNull(handedOn)
        val entry = WireJson.parse(store.value!!).array!!.single()
        assertEquals("unhandled_rejection", entry["type"].string)
        assertEquals("from a coroutine", entry["payload"]["message"].string)
        assertTrue(entry["payload"]["coroutine"].string!!.contains("loader"))
    }

    @Test
    fun `the token, a socket URL carrying it and a bearer header never reach the queue`() {
        val password = "correct horse+battery/staple"
        val api = ApiClient("https://ppm.example.test".toHttpUrl(), password)
        PpmLog.w("Socket", "closed ${api.globalSocketUrl()}")
        PpmLog.e("Http", "refused Authorization: Bearer $password")
        PpmLog.renderError("Docx", "could not open with $password", IllegalStateException(password))
        val text = store.let { queued(); it.value!! }
        for (secret in listOf(password, ApiClient.escape(password), "battery", "staple")) {
            assertFalse("$secret leaked: $text", text.contains(secret))
        }
    }

    @Test
    fun `a phone names itself the way Logs reads it`() {
        val agent = AppTrace.userAgent(ApplicationProvider.getApplicationContext())
        assertTrue(agent, AGENT.matches(agent))
        assertTrue(agent, agent.startsWith("PPM-Android/" + BuildConfig.VERSION_NAME + " (Android "))
        assertTrue(agent, agent.endsWith("; phone)"))
    }

    @Test
    @Config(qualifiers = "sw800dp-w1280dp-h800dp")
    fun `a tablet says so`() {
        val agent = AppTrace.userAgent(ApplicationProvider.getApplicationContext())
        assertTrue(agent, AGENT.matches(agent))
        assertTrue(agent, agent.endsWith("; tablet)"))
    }

    private companion object {
        /** `src/shared/user-agent-label.ts` reads `PPM-Android/<n>` as "PPM app" and `Android` as the system. */
        val AGENT = Regex("^PPM-Android/\\d+(\\.\\d+)* \\(Android [^;()]+; [^;()]+; (tablet|phone)\\)$")
    }
}
