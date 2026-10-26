package com.hienlh.ppm.state.preview

import com.hienlh.ppm.protocol.CanvasCheckReport
import com.hienlh.ppm.protocol.CheckSize
import com.hienlh.ppm.protocol.LayoutCheckReport
import com.hienlh.ppm.protocol.TabOpenAnswer
import com.hienlh.ppm.protocol.TabOpenCheck
import com.hienlh.ppm.protocol.TabOpenRequest
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.workspace.AiTabTarget
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** html-preview-loads.test.ts, the bridge's accept rule and check (use-design-bridge, use-design-canvas-check), and answerTabOpen. */
class PreviewTest {
    private val noCheck = PreviewLoads.Check { _, _ -> error("not checked") }

    // PreviewLoads

    @Test
    fun `a finished load past the one seen is returned at once`() = runTest {
        val loads = PreviewLoads()
        val key = PreviewLoads.key("app", "r.html")
        val first = loads.begin(key, noCheck)
        loads.finish(key, first)
        assertNull(loads.waitFor(key, first, 1_000))
        val second = loads.begin(key, noCheck)
        loads.finish(key, second)
        assertEquals(second, loads.waitFor(key, first, 1_000)?.seq)
        assertEquals(second, loads.latest(key))
    }

    @Test
    fun `a wait ends at the next finished load, and a replaced load's finish is ignored`() = runTest {
        val loads = PreviewLoads()
        val key = PreviewLoads.key(null, "/tmp/r.html")
        val waiting = async { loads.waitFor(key, 0, 12_000) }
        runCurrent()
        val replaced = loads.begin(key, noCheck)
        val current = loads.begin(key, noCheck)
        loads.finish(key, replaced)
        runCurrent()
        assertFalse(waiting.isCompleted)
        loads.finish(key, current)
        assertEquals(current, waiting.await()?.seq)
    }

    @Test
    fun `when time runs out a load that only started is still checked, and none is null`() = runTest {
        val loads = PreviewLoads()
        val key = PreviewLoads.key("app", "r.html")
        assertNull(loads.waitFor(key, 0, 12_000))
        val started = loads.begin(key, noCheck)
        val load = loads.waitFor(key, 0, 12_000)
        assertEquals(started, load?.seq)
        assertFalse(load!!.loaded)
    }

    @Test
    fun `a preview that went away has nothing to check, but a newer one keeps its load`() {
        val loads = PreviewLoads()
        val key = PreviewLoads.key("app", "r.html")
        val old = loads.begin(key, noCheck)
        val new = loads.begin(key, noCheck)
        loads.end(key, old)
        assertEquals(new, loads.latest(key))
        loads.end(key, new)
        assertEquals(0, loads.latest(key))
        assertTrue(PreviewLoads.key("a", "x") != PreviewLoads.key(null, "x"))
    }

    // PreviewBridge

    private val nonce = "abcdefghijklmnop"
    private val gen = "0123456789abcdef"
    private fun env(type: String, extra: String, nonce: String = this.nonce) =
        """{"ppm":"design-bridge","v":1,"nonce":"$nonce","type":"$type",$extra}"""
    private val ready = env("ready", """"gen":"$gen","cssGens":{},"file":"out/r.html","instrumented":false,"title":"R","docHeight":900""")
    private fun issue(n: Int) = env("issue", """"kind":"error","message":"boom $n","source":"r.js","line":$n""")
    private val report = """{"viewport":{"width":390,"height":700},"page":{"width":390,"height":1200},"findings":[{"kind":"overlap","message":"o"}],"counts":{"overlap":1}}"""

    private class Frame {
        val sent = ArrayList<JsonObject>()
        var open = true
    }

    private fun bridge(frame: Frame = Frame()) = PreviewBridge { envelope -> frame.open.also { if (it) frame.sent += envelope } }

    @Test
    fun `only the current load's nonce counts`() {
        val bridge = bridge()
        bridge.receive(ready)
        assertNull("before any load", bridge.ready)
        bridge.startLoad(nonce)
        bridge.receive(env("ready", """"gen":"$gen","cssGens":{},"file":"x","instrumented":false""", nonce = "zyxwvutsrqponmlk"))
        bridge.receive(ready.replace("\"nonce\":\"$nonce\"", "\"nonce\":null"))
        bridge.receive("not json")
        assertNull(bridge.ready)
        bridge.receive(ready)
        assertEquals("out/r.html", bridge.ready?.file)
        // A new load forgets the old document.
        bridge.startLoad("ponmlkjihgfedcba")
        assertNull(bridge.ready)
    }

    @Test
    fun `nothing goes to a frame that loaded more often than it said ready`() {
        val frame = Frame()
        val bridge = bridge(frame)
        val envelope = JsonObject(emptyMap())
        assertFalse("no load yet", bridge.send(envelope))
        bridge.startLoad(nonce)
        bridge.receive(ready)
        bridge.frameLoaded()
        assertTrue(bridge.send(envelope))
        // The frame went to another page: a load and no ready.
        bridge.frameLoaded()
        assertFalse(bridge.send(envelope))
        assertEquals(1, frame.sent.size)
    }

    @Test
    fun `a check asks the frame, then adds the load's runtime issues, the document and the frame`() = runTest {
        val frame = Frame()
        val bridge = bridge(frame)
        bridge.startLoad(nonce)
        bridge.receive(ready)
        bridge.frameLoaded()
        repeat(25) { bridge.receive(issue(it + 1)) }
        val result = async { bridge.check(screenshot = true, frame = "Phone") { "lib source" } }
        runCurrent()
        val run = frame.sent.single()
        assertEquals("check-run", run["type"].string)
        assertEquals(true, run["screenshot"].bool)
        assertEquals("lib source", run["lib"].string)
        val id = run["requestId"].string!!
        // An answer to another request, or under another nonce, is not this one's.
        bridge.receive(env("check-result", """"requestId":"otherid123","report":$report"""))
        bridge.receive(env("check-result", """"requestId":"$id","report":$report""", nonce = "zyxwvutsrqponmlk"))
        runCurrent()
        assertFalse(result.isCompleted)
        bridge.receive(env("check-result", """"requestId":"$id","report":$report"""))
        val checked = result.await()
        assertEquals("out/r.html" to gen, checked.file to checked.gen)
        assertEquals("Phone", checked.frame)
        // 20 issues are kept per load, after the bridge's own findings in kind order.
        assertEquals(listOf("runtime") + List(19) { "runtime" } + "overlap", checked.layout.findings.map { it.kind })
        assertEquals("error: boom 1 (r.js:1)", checked.layout.findings.first().message)
        assertEquals(mapOf("overlap" to 1, "runtime" to 20), checked.layout.counts)
        assertNull(checked.layout.screenshotNote)
    }

    @Test
    fun `a check says why it could not run`() = runTest {
        val frame = Frame()
        val bridge = bridge(frame)
        bridge.startLoad(nonce)
        suspend fun reason(block: suspend () -> Unit): String? = try {
            block()
            null
        } catch (e: IllegalStateException) {
            e.message
        }
        // No ready within 8 s.
        assertEquals("The canvas is still loading", reason { bridge.check(false, "Desktop") { null } })
        bridge.receive(ready)
        bridge.frameLoaded()
        // The frame does not answer within 15 s.
        assertEquals("The canvas did not answer in time", reason { bridge.check(false, "Desktop") { null } })
        // The frame answers with an error.
        val failing = async { reason { bridge.check(false, "Desktop") { null } } }
        runCurrent()
        bridge.receive(env("check-error", """"requestId":"${frame.sent.last()["requestId"].string}","message":"measure failed""""))
        assertEquals("measure failed", failing.await())
        // The frame cannot be posted to.
        frame.open = false
        assertEquals("The canvas is not accepting messages", reason { bridge.check(false, "Desktop") { null } })
    }

    @Test
    fun `a screenshot without its library is noted, and a check waits for a page still loading`() = runTest {
        val frame = Frame()
        val bridge = bridge(frame)
        bridge.startLoad(nonce)
        val result = async { bridge.check(screenshot = true, frame = "Desktop") { throw IllegalStateException("no asset") } }
        advanceTimeBy(2_000)
        assertTrue(frame.sent.isEmpty())
        bridge.receive(ready)
        runCurrent()
        val run = frame.sent.single()
        assertNull(run["lib"])
        bridge.receive(env("check-result", """"requestId":"${run["requestId"].string}","report":$report"""))
        assertEquals("The screenshot library could not be loaded.", result.await().layout.screenshotNote)
    }

    // answerTabOpen

    private val layout = LayoutCheckReport(CheckSize(390, 700), CheckSize(390, 700), emptyList(), emptyMap())

    private fun file(line: Int? = null, check: Boolean = true, path: String = "out/r.html", tool: String = "open_preview") =
        TabOpenRequest.File("q1", tool, path, "app", line, if (check) TabOpenCheck(screenshot = true) else null)

    @Test
    fun `what the app cannot open yet is refused at once, saying why`() = runTest {
        suspend fun answer(request: TabOpenRequest) = answerTabOpen(request, PreviewLoads(), { error("not opened") }, { fail() }, { "Phone" })
        assertEquals(TabOpenAnswer(false, "the PPM Android app cannot show code yet"), answer(file(line = 4, tool = "open_file")))
        assertEquals(TabOpenAnswer(false, "the PPM Android app can show only HTML pages so far"), answer(file(path = "notes.md", check = false)))
        assertEquals(
            TabOpenAnswer(false, "the PPM Android app cannot open database queries yet"),
            answer(TabOpenRequest.Query("q1", WireJson.parse("""{"sql":"select 1"}""") as JsonObject)),
        )
        assertEquals(TabOpenAnswer(false, "the PPM Android app has no terminal yet"), answer(TabOpenRequest.Terminal("q1", "t", null, "/")))
        assertEquals(TabOpenAnswer(false, "the PPM Android app does not know the open_window tool"), answer(TabOpenRequest.Other("q1", "open_window")))
        assertTrue(isHtmlFile("a/B.HTM") && isHtmlFile("x.html") && !isHtmlFile("x.html/y"))
        // `getFileExt` takes a name without a dot whole, so the editor shows a file named "html" as a page too.
        assertTrue(isHtmlFile("html"))
    }

    @Test
    fun `a web server opens, or says why not`() = runTest {
        val url = TabOpenRequest.Url("q1", "http://localhost:5173/", 5173, "local")
        assertEquals(TabOpenAnswer(true), answerTabOpen(url, PreviewLoads(), { error("not opened") }, {}, { "Phone" }))
        assertEquals(
            TabOpenAnswer(false, "this device is not the machine PPM runs on, so it cannot reach localhost:5173"),
            answerTabOpen(url, PreviewLoads(), { error("not opened") }, { throw IllegalStateException("this device is not the machine PPM runs on, so it cannot reach localhost:5173") }, { "Phone" }),
        )
    }

    @Test
    fun `a page opened without a check is answered once its tab is open`() = runTest {
        var opened: AiTabTarget? = null
        val answer = answerTabOpen(file(check = false, tool = "open_file"), PreviewLoads(), { opened = it; PreviewTicket("k", 0) }, {}, { "Phone" })
        assertEquals(TabOpenAnswer(true), answer)
        assertEquals(AiTabTarget("open_file", "out/r.html", "app"), opened)
    }

    @Test
    fun `a page is checked once it loaded and settled, in the frame it is seen in`() = runTest {
        val loads = PreviewLoads()
        val key = PreviewLoads.key("app", "out/r.html")
        val old = loads.begin(key, noCheck)
        loads.finish(key, old)
        var asked: Pair<Boolean, String>? = null
        val settled = ArrayList<Long>()
        val answer = async {
            answerTabOpen(file(), loads, { PreviewTicket(key, loads.latest(key)) }, {}, { "Phone" }, settle = { settled += it })
        }
        runCurrent()
        // The page on screen before the call is not the one checked.
        assertFalse(answer.isCompleted)
        val seq = loads.begin(key) { screenshot, frame ->
            asked = screenshot to frame
            CanvasCheckReport(layout, "out/r.html", null, frame)
        }
        loads.finish(key, seq)
        val result = answer.await()
        assertEquals(true to "Phone", asked)
        assertEquals(listOf(PREVIEW_SETTLE_MS), settled)
        assertEquals(TabOpenAnswer(true, report = CanvasCheckReport(layout, "out/r.html", null, "Phone")), result)
    }

    @Test
    fun `a page that never starts loading, or whose check fails, is still opened`() = runTest {
        val loads = PreviewLoads()
        val key = PreviewLoads.key("app", "out/r.html")
        assertEquals(
            TabOpenAnswer(true, "the page did not start loading"),
            answerTabOpen(file(), loads, { PreviewTicket(key, 0) }, {}, { "Desktop" }, settle = {}),
        )
        val seq = loads.begin(key) { _, _ -> throw IllegalStateException("The canvas did not answer in time") }
        loads.finish(key, seq)
        assertEquals(
            TabOpenAnswer(true, "The canvas did not answer in time"),
            answerTabOpen(file(), loads, { PreviewTicket(key, 0) }, {}, { "Desktop" }, settle = {}),
        )
        assertEquals(
            TabOpenAnswer(false, "PPM has no panel to open the tab in"),
            answerTabOpen(file(), loads, { throw IllegalStateException("PPM has no panel to open the tab in") }, {}, { "Desktop" }),
        )
    }

    @Test
    fun `a load carries the check it was begun with`() = runTest {
        val loads = PreviewLoads()
        val key = PreviewLoads.key("app", "out/r.html")
        val check = PreviewLoads.Check { _, frame -> CanvasCheckReport(layout, "", null, frame) }
        val seq = loads.begin(key, check)
        loads.finish(key, seq)
        val load = loads.waitFor(key, 0, 1)
        assertSame(check, load?.check)
    }
}
