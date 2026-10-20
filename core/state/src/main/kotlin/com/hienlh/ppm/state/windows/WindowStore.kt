package com.hienlh.ppm.state.windows

import com.hienlh.ppm.client.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlin.math.max
import kotlin.random.Random

/** What a window hosts (`WINDOW_KINDS`); the content registry decides what renders inside. */
enum class WindowKind(val wire: String) {
    Explorer("explorer"),
    AgentSession("agent-session"),
    SystemMonitor("system-monitor"),
    TabHost("tab-host"),
    RemoteDesktop("remote-desktop"),
    Settings("settings"),
    Logs("logs");

    companion object {
        fun fromWire(value: String?): WindowKind? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * How a window is shown. `Snapped` fills the right part of the layer ([snapRect]); `Minimized`
 * takes it off screen, leaving its chip in the status bar's window dock.
 */
enum class WindowVisualState(val wire: String) {
    Normal("normal"),
    Maximized("maximized"),
    Snapped("snapped"),
    Minimized("minimized");

    companion object {
        fun fromWire(value: String?): WindowVisualState? = entries.firstOrNull { it.wire == value }
    }
}

data class WindowRuntimeState(
    val id: String,
    val kind: WindowKind,
    /** Layer coordinates; while maximized or snapped, the rect it comes back to. */
    val rect: Rect,
    /** Dense stacking order, 0 = backmost. */
    val rank: Int,
    /** When it was opened relative to the others: the dock lists chips in this order. */
    val opened: Double,
    val state: WindowVisualState,
    /** What a minimized window comes back as; null means normal. */
    val restoreTo: WindowVisualState? = null,
    /** Kind-specific, kept with the window across a restart. */
    val payload: JsonObject? = null,
)

data class WindowsState(
    val windows: Map<String, WindowRuntimeState> = emptyMap(),
    /** The layer's measured size; every rect is clamped against it. */
    val bounds: Bounds = DEFAULT_BOUNDS,
    /** The saved windows were read: restoring runs once. */
    val restored: Boolean = false,
    /** The window being dragged towards a snap, for the layer's preview. */
    val snapPreviewId: String? = null,
)

/** The layer's size before it has been measured. */
val DEFAULT_BOUNDS = Bounds(1280.0, 800.0)

/**
 * The floating windows (window-store.ts): geometry, stacking and lifecycle, whatever they host.
 * Ranks stay dense, 0 to n-1. Saved under [key] after each change, as the web keeps `ppm-windows`
 * in its origin's storage — one set of windows per server here, as there.
 *
 * Driven from one thread, like the other stores; [state] is what others read.
 */
class WindowStore(
    private val values: KeyValueStore,
    private val key: String,
    private val now: () -> Long = System::currentTimeMillis,
    /** `Math.random().toString(36).slice(2, 8)`. */
    private val randomSuffix: () -> String = { List(6) { BASE36[Random.nextInt(36)] }.joinToString("") },
) {
    private val _state = MutableStateFlow(WindowsState())
    val state: StateFlow<WindowsState> = _state

    private val s: WindowsState get() = _state.value

    /**
     * Opens a window and returns its id. At [MAX_WINDOWS] the oldest one comes forward instead and
     * its id is returned — callers that must not land in an unrelated window check the count first.
     */
    fun open(kind: WindowKind, payload: JsonObject? = null, rect: Rect? = null): String {
        val ordered = windowsInRankOrder(s.windows)
        if (ordered.size >= MAX_WINDOWS) {
            val oldest = ordered.first()
            restore(oldest.id)
            return oldest.id
        }
        val id = "win-${now().toString(36)}-${randomSuffix()}"
        val spawn = rect?.let { clampRect(it, s.bounds) } ?: cascadeSpawnRect(ordered.map { it.rect }, s.bounds)
        val next = densify(ordered + WindowRuntimeState(id, kind, spawn, ordered.size, nextOpened(ordered), WindowVisualState.Normal, payload = payload))
        commit(s.copy(windows = next))
        return id
    }

    fun close(id: String) {
        if (id !in s.windows) return
        commit(s.copy(windows = densify(windowsInRankOrder(s.windows).filter { it.id != id })))
    }

    /** Raises a window, bringing it back from the dock when minimized. */
    fun focus(id: String) {
        val found = s.windows[id] ?: return
        val target = if (found.state == WindowVisualState.Minimized) withState(found, found.restoreTo ?: WindowVisualState.Normal) else found
        val ordered = windowsInRankOrder(s.windows)
        if (target === found && ordered.lastOrNull()?.id == id) return
        // Stacking order is part of what comes back after a restart.
        commit(s.copy(windows = densify(ordered.filter { it.id != id } + target)))
    }

    fun move(id: String, x: Double, y: Double) {
        val win = s.windows[id] ?: return
        commit(s.copy(windows = s.windows + (id to win.copy(rect = clampRect(win.rect.copy(x = x, y = y), s.bounds)))))
    }

    fun resize(id: String, rect: Rect) {
        val win = s.windows[id] ?: return
        commit(s.copy(windows = s.windows + (id to win.copy(rect = clampRect(rect, s.bounds)))))
    }

    /** Changes how a window is shown; minimizing remembers what it comes back as. */
    fun setState(id: String, state: WindowVisualState) {
        val win = s.windows[id] ?: return
        if (win.state == state) return
        commit(s.copy(windows = s.windows + (id to withState(win, state))))
    }

    fun restore(id: String) = focus(id)

    /** A dock chip: the window in front minimizes, any other comes forward. */
    fun activateFromDock(id: String) {
        if (id !in s.windows) return
        if (frontWindowId(s.windows) == id) setState(id, WindowVisualState.Minimized) else restore(id)
    }

    fun minimizeAll() {
        commit(s.copy(windows = s.windows.mapValues { (_, win) -> withState(win, WindowVisualState.Minimized) }))
    }

    fun setSnapPreview(id: String?) {
        if (s.snapPreviewId != id) _state.value = s.copy(snapPreviewId = id)
    }

    /** Merges [payload] into a window's, as `{ ...payload, ...changes }`; nothing is written when nothing changed. */
    fun setPayload(id: String, payload: JsonObject) {
        val win = s.windows[id] ?: return
        val old = win.payload ?: JsonObject(emptyMap())
        val merged = JsonObject(LinkedHashMap(old).apply { putAll(payload) })
        if (merged.size == old.size && merged.all { (k, v) -> old[k] == v }) return
        commit(s.copy(windows = s.windows + (id to win.copy(payload = merged))))
    }

    /** The layer's new size; a smaller one would otherwise strand windows where they cannot be dragged back. */
    fun setBounds(bounds: Bounds) {
        if (s.bounds == bounds) return
        val windows = s.windows.mapValues { (_, win) ->
            val rect = clampRect(win.rect, bounds)
            if (rect == win.rect) win else win.copy(rect = rect)
        }
        _state.value = s.copy(bounds = bounds, windows = windows)
    }

    /** Brings back the saved windows, re-clamped to [bounds]; only the first call does anything. */
    fun restoreAll(bounds: Bounds) {
        if (s.restored) return
        val saved = loadWindowRects(values.get(key), bounds).take(MAX_WINDOWS)
        val windows = densify(saved.mapIndexed { rank, w ->
            // A blob from before open order was kept has none; stacking order stands in for it.
            WindowRuntimeState(w.id, w.kind, w.rect, rank, w.opened ?: rank.toDouble(), w.state, w.restoreTo, w.payload)
        })
        _state.value = s.copy(bounds = bounds, windows = windows, restored = true)
    }

    private fun commit(next: WindowsState) {
        _state.value = next
        values.put(key, saveWindowRects(next.windows.values))
    }

    private companion object {
        const val BASE36 = "0123456789abcdefghijklmnopqrstuvwxyz"
    }
}

/** Windows in paint order, backmost first. */
fun windowsInRankOrder(windows: Map<String, WindowRuntimeState>): List<WindowRuntimeState> = windows.values.sortedBy { it.rank }

/**
 * The window in front: the highest-ranked one on screen. A minimized window keeps its rank, to come
 * back where it was in the stack, but it is never the one in front.
 */
fun frontWindowId(windows: Map<String, WindowRuntimeState>): String? =
    windowsInRankOrder(windows).lastOrNull { it.state != WindowVisualState.Minimized }?.id

/** Windows in the order they were opened: the dock's order. */
fun windowsInOpenOrder(windows: Map<String, WindowRuntimeState>): List<WindowRuntimeState> = windows.values.sortedBy { it.opened }

/** Ranks rewritten to 0..n-1 in [ordered]'s order (last = frontmost), keyed in that order. */
private fun densify(ordered: List<WindowRuntimeState>): Map<String, WindowRuntimeState> {
    val out = LinkedHashMap<String, WindowRuntimeState>()
    ordered.forEachIndexed { rank, win -> out[win.id] = if (win.rank == rank) win else win.copy(rank = rank) }
    return out
}

/** One past the newest window, so the dock appends it. */
private fun nextOpened(windows: List<WindowRuntimeState>): Double = windows.fold(0.0) { acc, w -> max(acc, w.opened + 1) }

/** [win] in [state], remembering what a minimized window was showing as. */
private fun withState(win: WindowRuntimeState, state: WindowVisualState): WindowRuntimeState = when {
    win.state == state -> win
    state == WindowVisualState.Minimized -> win.copy(state = state, restoreTo = win.state)
    else -> win.copy(state = state, restoreTo = null)
}
