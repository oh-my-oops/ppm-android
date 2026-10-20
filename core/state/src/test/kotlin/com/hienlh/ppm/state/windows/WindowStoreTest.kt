package com.hienlh.ppm.state.windows

import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.state.workspace.MemoryValues
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** window-geometry.ts, window-store.ts and window-persistence.ts, with the web's own test cases. */
class WindowStoreTest {
    private val bounds = Bounds(1600.0, 900.0)

    private fun r(x: Number, y: Number, w: Number, h: Number) = Rect(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble())

    private val values = MemoryValues()
    private var clock = 1_000L
    private var suffix = 0

    private fun store() = WindowStore(values, "windows", now = { clock++ }, randomSuffix = { "s${++suffix}" })

    private val WindowStore.windows get() = state.value.windows

    // ---------------------------------------------------------------------------------------
    // Geometry
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a rect is clamped to the minimum size, the layer and a reachable titlebar`() {
        assertEquals(r(100, 80, 800, 500), clampRect(r(100, 80, 800, 500), bounds))
        assertEquals(r(10, 10, 360, 240), clampRect(r(10, 10, 40, 20), bounds))
        assertEquals(r(0, 0, 1600, 900), clampRect(r(0, 0, 9999, 9999), bounds))
        // The minimum stands even in a layer smaller than it.
        assertEquals(r(0, 0, 360, 240), clampRect(r(0, 0, 100, 100), Bounds(200.0, 150.0)))
        assertEquals(1600.0 - KEEP_VISIBLE, clampRect(r(5000, 100, 800, 500), bounds).x, 0.0)
        assertEquals(KEEP_VISIBLE - 800, clampRect(r(-5000, 100, 800, 500), bounds).x, 0.0)
        assertEquals(0.0, clampRect(r(100, -400, 800, 500), bounds).y, 0.0)
        assertEquals(900.0 - KEEP_VISIBLE, clampRect(r(100, 5000, 800, 500), bounds).y, 0.0)
        assertEquals(r(10, 21, 800, 501), clampRect(r(10.4, 20.6, 800.3, 500.9), bounds))
        // Halves round up, as Math.round does: -2.5 to -2.
        assertEquals(-2.0, jsRound(-2.5), 0.0)
        assertEquals(3.0, jsRound(2.5), 0.0)
    }

    @Test
    fun `a resize moves the edges its handle names and stops at the minimum`() {
        val base = r(200, 150, 800, 500)
        assertEquals(r(200, 150, 900, 500), applyResize(base, ResizeHandle.E, 100.0, 0.0))
        assertEquals(r(200, 150, 800, 560), applyResize(base, ResizeHandle.S, 0.0, 60.0))
        assertEquals(r(300, 150, 700, 500), applyResize(base, ResizeHandle.W, 100.0, 0.0))
        assertEquals(r(200, 200, 800, 450), applyResize(base, ResizeHandle.N, 0.0, 50.0))
        assertEquals(r(200, 150, 840, 530), applyResize(base, ResizeHandle.SE, 40.0, 30.0))
        assertEquals(r(240, 180, 760, 470), applyResize(base, ResizeHandle.NW, 40.0, 30.0))
        assertEquals(r(200, 180, 840, 470), applyResize(base, ResizeHandle.NE, 40.0, 30.0))
        assertEquals(r(240, 150, 760, 530), applyResize(base, ResizeHandle.SW, 40.0, 30.0))
        for (handle in ResizeHandle.entries) {
            for (d in listOf(-5000.0, 5000.0)) {
                val out = applyResize(base, handle, d, d)
                assertTrue(out.w >= MIN_WINDOW_W && out.h >= MIN_WINDOW_H)
            }
        }
        // The anchored edge stays where it was when the moving one hits the minimum.
        val w = applyResize(base, ResizeHandle.W, 5000.0, 0.0)
        assertEquals(MIN_WINDOW_W, w.w, 0.0)
        assertEquals(base.x + base.w, w.x + w.w, 0.0)
        assertEquals(base.h, applyResize(base, ResizeHandle.E, 100.0, 999.0).h, 0.0)
    }

    @Test
    fun `new windows cascade and wrap at the cap, an agent session opens portrait at the top right`() {
        val rects = ArrayList<Rect>()
        repeat(MAX_WINDOWS) {
            val next = cascadeSpawnRect(rects, bounds)
            assertEquals(next, clampRect(next, bounds))
            rects += next
        }
        assertEquals(r(48, 48, 880, 560), rects[0])
        assertEquals(24.0, rects[1].x - rects[0].x, 0.0)
        assertEquals(MAX_WINDOWS, rects.map { it.x to it.y }.toSet().size)
        assertEquals(cascadeSpawnRect(emptyList(), bounds), cascadeSpawnRect(List(MAX_WINDOWS) { r(0, 0, 400, 300) }, bounds))
        val small = cascadeSpawnRect(emptyList(), Bounds(500.0, 400.0))
        assertTrue(small.w in MIN_WINDOW_W..500.0 && small.h >= MIN_WINDOW_H)

        assertEquals(r(836, 48, 396, 704), portraitSpawnRect(emptyList(), Bounds(1280.0, 800.0)))
        assertEquals(MIN_WINDOW_W, portraitSpawnRect(emptyList(), Bounds(1280.0, 300.0)).w, 0.0)
    }

    @Test
    fun `dragging the right edge past the layer arms a snap to its right part`() {
        val layer = Bounds(1280.0, 800.0)
        assertEquals(r(576, 0, 704, 800), snapRect(layer))
        val start = r(100, 100, 800, 500)
        assertFalse(wantsSnap(start.copy(x = 500.0), start, layer))
        assertTrue(wantsSnap(start.copy(x = 521.0), start, layer))
        // A window resting off the right side is measured from where its edge already was.
        val off = r(1000, 100, 800, 500)
        assertFalse(wantsSnap(off.copy(x = 1030.0), off, layer))
        assertEquals(r(0, 0, 1280, 800), displayedRect(WindowVisualState.Maximized, start, layer))
        assertEquals(snapRect(layer), displayedRect(WindowVisualState.Snapped, start, layer))
        assertEquals(start, displayedRect(WindowVisualState.Minimized, start, layer))
        assertEquals(r(90, 110, 800, 500), nudgeRect(r(100, 100, 800, 500), -10.0, 10.0, bounds))
    }

    // ---------------------------------------------------------------------------------------
    // Store
    // ---------------------------------------------------------------------------------------

    @Test
    fun `windows open cascaded on top, come forward when focused, and keep dense ranks`() {
        val store = store()
        val a = store.open(WindowKind.Settings)
        val b = store.open(WindowKind.Logs)
        assertTrue(a.startsWith("win-") && a.endsWith("-s1"))
        assertEquals(listOf(a, b), windowsInRankOrder(store.windows).map { it.id })
        assertEquals(r(72, 72, 880, 560), store.windows.getValue(b).rect)
        store.focus(a)
        assertEquals(listOf(b, a), windowsInRankOrder(store.windows).map { it.id })
        // The dock keeps the order they were opened in.
        assertEquals(listOf(a, b), windowsInOpenOrder(store.windows).map { it.id })
        store.close(b)
        assertEquals(0, store.windows.getValue(a).rank)
        // One past the newest still open.
        val c = store.open(WindowKind.Logs)
        assertEquals(1.0, store.windows.getValue(c).opened, 0.0)
    }

    @Test
    fun `a minimized window remembers what it was and is never the one in front`() {
        val store = store()
        val a = store.open(WindowKind.Settings)
        val b = store.open(WindowKind.Logs)
        store.setState(b, WindowVisualState.Maximized)
        store.setState(b, WindowVisualState.Minimized)
        assertEquals(WindowVisualState.Maximized, store.windows.getValue(b).restoreTo)
        assertEquals(a, frontWindowId(store.windows))
        // A dock chip: a window behind comes forward, the one in front minimizes.
        store.activateFromDock(b)
        assertEquals(WindowVisualState.Maximized, store.windows.getValue(b).state)
        assertNull(store.windows.getValue(b).restoreTo)
        assertEquals(b, frontWindowId(store.windows))
        store.activateFromDock(b)
        assertEquals(WindowVisualState.Minimized, store.windows.getValue(b).state)
        store.minimizeAll()
        assertNull(frontWindowId(store.windows))
        store.focus(a)
        assertEquals(WindowVisualState.Normal, store.windows.getValue(a).state)
    }

    @Test
    fun `at the cap the oldest window comes forward instead of a new one`() {
        val store = store()
        val ids = List(MAX_WINDOWS) { store.open(WindowKind.Logs) }
        store.setState(ids[0], WindowVisualState.Minimized)
        assertEquals(ids[0], store.open(WindowKind.Settings))
        assertEquals(MAX_WINDOWS, store.windows.size)
        assertEquals(ids[0], frontWindowId(store.windows))
        assertEquals(WindowVisualState.Normal, store.windows.getValue(ids[0]).state)
    }

    @Test
    fun `payloads merge, and an unchanged one writes nothing`() {
        val store = store()
        val id = store.open(WindowKind.Settings, JsonObject(mapOf("category" to JsonPrimitive("appearance"))))
        values.map.remove("windows")
        store.setPayload(id, JsonObject(mapOf("category" to JsonPrimitive("appearance"))))
        assertNull(values.map["windows"])
        store.setPayload(id, JsonObject(mapOf("category" to JsonPrimitive("servers"))))
        assertEquals(JsonPrimitive("servers"), store.windows.getValue(id).payload!!["category"])
        assertTrue(values.map.getValue("windows").contains("servers"))
    }

    @Test
    fun `a smaller layer pulls windows back in without saving them there`() {
        val store = store()
        val id = store.open(WindowKind.Settings, rect = r(1000, 600, 500, 300))
        val saved = values.map.getValue("windows")
        store.setBounds(Bounds(800.0, 500.0))
        assertEquals(r(752, 452, 500, 300), store.windows.getValue(id).rect)
        assertEquals(saved, values.map["windows"])
    }

    // ---------------------------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------------------------

    @Test
    fun `saved windows come back once, in their stacking order, re-clamped`() {
        val first = store()
        val a = first.open(WindowKind.Settings, JsonObject(mapOf("category" to JsonPrimitive("servers"))))
        val b = first.open(WindowKind.TabHost)
        first.setState(b, WindowVisualState.Snapped)
        first.setState(b, WindowVisualState.Minimized)
        first.focus(a)
        first.open(WindowKind.AgentSession)

        val saved = WireJson.parse(values.map.getValue("windows")) as JsonArray
        // Back to front, whole numbers written as JavaScript writes them.
        assertEquals(listOf(b, a), saved.take(2).map { (it as JsonObject)["id"]!!.toString().trim('"') })
        assertTrue(values.map.getValue("windows").contains("\"rect\":{\"x\":72,\"y\":72,\"w\":880,\"h\":560}"))

        val second = store()
        second.restoreAll(Bounds(1280.0, 800.0))
        val restored = windowsInRankOrder(second.windows)
        // An agent session's transcript is gone after a restart: it does not come back.
        assertEquals(listOf(b, a), restored.map { it.id })
        assertEquals(WindowVisualState.Snapped, second.windows.getValue(b).restoreTo)
        assertEquals(JsonPrimitive("servers"), second.windows.getValue(a).payload!!["category"])
        assertTrue(second.state.value.restored)
        second.close(a)
        second.restoreAll(Bounds(1280.0, 800.0))
        assertEquals(listOf(b), second.windows.keys.toList())
    }

    @Test
    fun `a malformed saved window is dropped, a doubtful field falls back`() {
        val text = """[
            {"id":"w1","kind":"logs","rect":{"x":5000,"y":10,"w":400,"h":300},"state":"floating","restoreTo":"maximized"},
            {"id":"w2","kind":"team-member","rect":{"x":0,"y":0,"w":400,"h":300},"state":"normal"},
            {"id":"w3","kind":"logs","rect":{"x":0,"y":0,"w":"400","h":300},"state":"normal"},
            {"id":"w4","kind":"logs","rect":{"x":0,"y":0,"w":400,"h":300},"state":"normal","payload":null},
            {"id":"w5","kind":"logs","rect":{"x":0,"y":0,"w":400,"h":300},"state":"minimized","restoreTo":"minimized","opened":7}
        ]"""
        val loaded = loadWindowRects(text, Bounds(1280.0, 800.0))
        assertEquals(listOf("w1", "w5"), loaded.map { it.id })
        assertEquals(WindowVisualState.Normal, loaded[0].state)
        assertNull(loaded[0].restoreTo)
        assertEquals(1280.0 - KEEP_VISIBLE, loaded[0].rect.x, 0.0)
        assertNull(loaded[0].opened)
        assertNull(loaded[1].restoreTo)
        assertEquals(7.0, loaded[1].opened!!, 0.0)
        assertEquals(emptyList<PersistedWindow>(), loadWindowRects("{}", bounds))
        assertEquals(emptyList<PersistedWindow>(), loadWindowRects("not json", bounds))
        assertNull(saveWindowRects(emptyList()))
    }
}
