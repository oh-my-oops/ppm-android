package com.hienlh.ppm.state.windows

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * window-geometry.ts: the floating window layer's geometry, pure so every rule (minimum size,
 * keeping a titlebar reachable, per-handle resize, the spawn cascade) is tested without a screen.
 *
 * Coordinates are dp from the layer's top-left (the content area, not the screen), where the
 * web has CSS pixels from its layer container.
 */
data class Rect(val x: Double, val y: Double, val w: Double, val h: Double)

/** Size of the layer the rects live in. */
data class Bounds(val w: Double, val h: Double)

/** The eight grab strips, in the web's `RESIZE_HANDLES` order. */
enum class ResizeHandle(val id: String) {
    N("n"), S("s"), E("e"), W("w"), NE("ne"), NW("nw"), SE("se"), SW("sw");

    val north: Boolean get() = 'n' in id
    val south: Boolean get() = 's' in id
    val east: Boolean get() = 'e' in id
    val west: Boolean get() = 'w' in id
    val corner: Boolean get() = id.length == 2
}

const val MIN_WINDOW_W = 360.0
const val MIN_WINDOW_H = 240.0

/** How much of a window stays inside the layer after a clamp, so it can always be grabbed back. */
const val KEEP_VISIBLE = 48.0

/** Offset between consecutive spawns. */
const val CASCADE_STEP = 24.0

/** Windows open at once, minimized ones included: they come back without asking for a slot. */
const val MAX_WINDOWS = 8

private const val PREFERRED_W = 880.0
private const val PREFERRED_H = 560.0
private const val SPAWN_MARGIN = 48.0

/** Cap on a portrait window's height: a 9:16 window this tall already shows a whole step list. */
private const val PORTRAIT_MAX_H = 780.0

/** Share of the layer a snapped window takes, measured from the right edge. */
const val SNAP_FRACTION = 0.55

/** How far past the layer's right edge a window's right edge must be dragged for a release to snap it. */
const val SNAP_TRIGGER = 40.0

/** `Math.round`: halves go up, `-2.5` to `-2`. */
internal fun jsRound(value: Double): Double = floor(value + 0.5)

private fun clamp(v: Double, lo: Double, hi: Double) = min(hi, max(lo, v))

/**
 * Fits [rect] in the layer: at least the minimum size, never larger than the layer, and placed so
 * [KEEP_VISIBLE] of its titlebar row stays inside. Whole units, as the web rounds to whole pixels.
 */
fun clampRect(rect: Rect, bounds: Bounds): Rect {
    val w = max(MIN_WINDOW_W, min(rect.w, max(bounds.w, MIN_WINDOW_W)))
    val h = max(MIN_WINDOW_H, min(rect.h, max(bounds.h, MIN_WINDOW_H)))
    // The left edge may hang off the layer while the right one still shows KEEP_VISIBLE; the top
    // edge may never go above it, or the titlebar is out of reach.
    val x = clamp(rect.x, KEEP_VISIBLE - w, max(KEEP_VISIBLE - w, bounds.w - KEEP_VISIBLE))
    val y = clamp(rect.y, 0.0, max(0.0, bounds.h - KEEP_VISIBLE))
    return Rect(jsRound(x), jsRound(y), jsRound(w), jsRound(h))
}

/**
 * [rect] dragged by ([dx], [dy]) on [handle]: the edges the handle anchors stay put. Shrinking past
 * the minimum stops the moving edge rather than pushing the anchored one.
 */
fun applyResize(rect: Rect, handle: ResizeHandle, dx: Double, dy: Double): Rect {
    var x = rect.x
    var y = rect.y
    var w = rect.w
    var h = rect.h
    if (handle.east) {
        w = max(MIN_WINDOW_W, rect.w + dx)
    } else if (handle.west) {
        val right = rect.x + rect.w
        w = max(MIN_WINDOW_W, rect.w - dx)
        x = right - w
    }
    if (handle.south) {
        h = max(MIN_WINDOW_H, rect.h + dy)
    } else if (handle.north) {
        val bottom = rect.y + rect.h
        h = max(MIN_WINDOW_H, rect.h - dy)
        y = bottom - h
    }
    return Rect(x, y, w, h)
}

/** Where a new window opens: cascaded from the top-left by one step per window open, wrapping at the cap. */
fun cascadeSpawnRect(existing: List<Rect>, bounds: Bounds, preferredW: Double = PREFERRED_W, preferredH: Double = PREFERRED_H): Rect {
    val w = min(preferredW, max(MIN_WINDOW_W, bounds.w - SPAWN_MARGIN * 2))
    val h = min(preferredH, max(MIN_WINDOW_H, bounds.h - SPAWN_MARGIN * 2))
    val step = existing.size % MAX_WINDOWS
    return clampRect(Rect(SPAWN_MARGIN + step * CASCADE_STEP, SPAWN_MARGIN + step * CASCADE_STEP, w, h), bounds)
}

/** Where an agent session window opens: 9:16, pinned to the top-right, cascading like any other. */
fun portraitSpawnRect(existing: List<Rect>, bounds: Bounds): Rect {
    val h = min(bounds.h - SPAWN_MARGIN * 2, PORTRAIT_MAX_H)
    val w = max(MIN_WINDOW_W, jsRound(h * 9 / 16))
    val step = existing.size % MAX_WINDOWS
    return clampRect(Rect(bounds.w - SPAWN_MARGIN - w - step * CASCADE_STEP, SPAWN_MARGIN + step * CASCADE_STEP, w, h), bounds)
}

/** A window snapped to the right: full height, [SNAP_FRACTION] of the width. */
fun snapRect(bounds: Bounds): Rect {
    val w = min(bounds.w, max(MIN_WINDOW_W, jsRound(bounds.w * SNAP_FRACTION)))
    return Rect(bounds.w - w, 0.0, w, bounds.h)
}

/**
 * A drag that started at [start] has carried the window ([rect], before clamping) far enough
 * right to snap on release. Measured from wherever the right edge already was when that lies
 * past the layer's, so pulling back a window that rests off the right side moves it.
 */
fun wantsSnap(rect: Rect, start: Rect, bounds: Bounds): Boolean =
    rect.x + rect.w > max(start.x + start.w, bounds.w) + SNAP_TRIGGER

/** Where a window in [state] is drawn; [rect] is the one it comes back to meanwhile. */
fun displayedRect(state: WindowVisualState, rect: Rect, bounds: Bounds): Rect = when (state) {
    WindowVisualState.Maximized -> Rect(0.0, 0.0, bounds.w, bounds.h)
    WindowVisualState.Snapped -> snapRect(bounds)
    else -> rect
}

/** An arrow key on a focused titlebar: moved, then clamped. */
fun nudgeRect(rect: Rect, dx: Double, dy: Double, bounds: Bounds): Rect = clampRect(rect.copy(x = rect.x + dx, y = rect.y + dy), bounds)
