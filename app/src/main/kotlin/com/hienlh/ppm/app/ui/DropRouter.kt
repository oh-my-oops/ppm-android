package com.hienlh.ppm.app.ui

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Decides which place a drag is over in the tablet's working area, as a browser does and Compose's own targets do not. Compose chooses among
 * overlapping targets by composition order, keeps the one the pointer is in for as long as it stays inside it, and ignores `zIndex`, so a
 * window floating over the chat left the chat the one that took what was dropped on the window.
 *
 * The area has one [dragAndDropTarget] ([dropRouter]) and the places that take a drag are its zones ([dropZone]). At each location, and at
 * the drop, the zones that took this drag and are showing are asked who has the pointer: the one on the highest layer ([LocalDropLayer]: the
 * panels 0, each window above the one behind it), the smallest of them if there are several (a message box in a chat), the last composed
 * after that. A zone's bounds are what shows of it, so a row half scrolled out of a window is not under a pointer beside the window.
 * Nothing is kept from one location to the next.
 *
 * A zone hears what a Compose target nested in another does: `onEntered` and `onMoved` on the new one, then `onExited` on the old, when the
 * pointer goes from one to the other; `onMoved` while it stays; `onEnded` once the drag is over. And it is told what Compose's are not.
 * A zone that arrives in the middle of a drag (a window opened, another chat's tab shown) is asked whether it takes the drag at the next
 * location, with that location's event: the event the drag began with is recycled by Android as soon as it has been dispatched, its clip
 * description and local state gone. A zone that goes in the middle of one (closed, minimized, its tab switched) gets `onExited` if the
 * pointer was in it, then `onEnded`, so nothing it counts is left up.
 */
internal class DropRouter : DragAndDropTarget {
    private val zones = ArrayList<DropZone>()
    private var dragging = false
    private var current: DropZone? = null

    /** The last event of the drag: what a zone that goes is told its end with. */
    private var last: DragAndDropEvent? = null

    /** The area the router is on, which tells the pointer leaving it from a target inside it taking the pointer over ([onExited]). */
    var area: LayoutCoordinates? = null

    /** Whether any zone takes this drag; Compose asks once, as it begins. */
    fun shouldStart(event: DragAndDropEvent): Boolean {
        last = event
        for (zone in zones) {
            zone.accepted = null
            zone.started = false
        }
        for (zone in zones) ask(zone, event)
        dragging = zones.any { it.accepted == true }
        return dragging
    }

    override fun onStarted(event: DragAndDropEvent) {
        for (zone in zones.toList()) begin(zone, event)
    }

    /** Which zone is under the pointer is decided at each [onMoved], so reaching the router starts nothing. */
    override fun onEntered(event: DragAndDropEvent) = Unit

    override fun onMoved(event: DragAndDropEvent) {
        if (!dragging) return
        last = event
        sync(event)
        val over = zoneAt(event.at())
        val before = current
        if (over === before) {
            over?.target?.onMoved(event)
            return
        }
        current = over
        // As Compose does it when the pointer goes from one target to another: the new one first, the old one last.
        over?.target?.onEntered(event)
        over?.target?.onMoved(event)
        before?.target?.onExited(event)
    }

    /**
     * Compose also calls this when a target nested in the area (a text field's own) takes the pointer over from the router, with the move that
     * did it. That is not the pointer leaving, which is a move to a point outside the area.
     */
    override fun onExited(event: DragAndDropEvent) {
        val inside = area?.takeIf { it.isAttached }?.boundsInRoot()?.contains(event.at()) == true
        val zone = current
        if (inside || zone == null) return
        current = null
        zone.target.onExited(event)
    }

    override fun onDrop(event: DragAndDropEvent): Boolean {
        if (!dragging) return false
        last = event
        sync(event)
        return zoneAt(event.at())?.target?.onDrop(event) ?: false
    }

    override fun onEnded(event: DragAndDropEvent) {
        if (dragging) finish(event)
    }

    /**
     * What a target nested in the area passes on to the router ([dropRelay]): where the pointer is and what is dropped, for it to decide as it
     * does for anything else in the area.
     */
    val relay = object : DragAndDropTarget {
        override fun onMoved(event: DragAndDropEvent) = this@DropRouter.onMoved(event)

        override fun onDrop(event: DragAndDropEvent): Boolean = this@DropRouter.onDrop(event)
    }

    fun register(zone: DropZone) {
        zones.add(zone)
    }

    fun unregister(zone: DropZone) {
        zones.remove(zone)
        retire(zone)
    }

    /** A zone that stopped showing in the middle of a drag. One that starts showing is asked at the next location, like any that arrives. */
    fun showingChanged(zone: DropZone) {
        if (!zone.showing) retire(zone)
    }

    /** Zones that arrived since the last event are asked, and started, with this one. */
    private fun sync(event: DragAndDropEvent) {
        for (zone in zones.toList()) {
            ask(zone, event)
            begin(zone, event)
        }
    }

    private fun ask(zone: DropZone, event: DragAndDropEvent) {
        if (zone.accepted == null && zone.showing) zone.accepted = zone.accepts(event)
    }

    private fun begin(zone: DropZone, event: DragAndDropEvent) {
        if (zone.accepted == true && !zone.started) {
            zone.started = true
            zone.target.onStarted(event)
        }
    }

    /** What a zone is told when it goes in the middle of a drag, from the last event of it: out if the pointer was in it, then ended. */
    private fun retire(zone: DropZone) {
        val event = last
        if (!dragging || event == null) return
        if (current === zone) {
            current = null
            zone.target.onExited(event)
        }
        if (zone.started) {
            zone.started = false
            zone.target.onEnded(event)
        }
        // Asked again if it comes back.
        zone.accepted = null
    }

    private fun finish(event: DragAndDropEvent) {
        dragging = false
        current = null
        last = null
        for (zone in zones.toList()) if (zone.started) zone.target.onEnded(event)
    }

    /** The zone that has [point], of those that took this drag: what [onMoved] and [onDrop] decide by. */
    fun zoneAt(point: Offset): DropZone? {
        var found: DropZone? = null
        var foundArea = Float.MAX_VALUE
        for (zone in zones) {
            if (zone.accepted != true) continue
            val bounds = zone.bounds() ?: continue
            if (!bounds.contains(point)) continue
            val area = bounds.width * bounds.height
            // The higher layer; on one layer the smaller area, and the later of equals.
            if (found == null || zone.layer > found.layer || (zone.layer == found.layer && area <= foundArea)) {
                found = zone
                foundArea = area
            }
        }
        return found
    }

    private fun DragAndDropEvent.at() = toAndroidDragEvent().let { Offset(it.x, it.y) }
}

/**
 * One place a drag can be let go ([dropZone]): what it [accepts], who hears it ([target]), the [layer] it is on and whether it is [showing]
 * (a minimized window's are not). Where it is comes from the layout node it is on; [bounds] is the part of it that is visible.
 */
internal class DropZone(
    var accepts: (DragAndDropEvent) -> Boolean,
    var target: DragAndDropTarget,
    var layer: Int,
    var showing: Boolean,
) {
    var coordinates: LayoutCoordinates? = null

    /** What it answered for this drag, null while it has not been asked. */
    var accepted: Boolean? = null
    var started = false

    /**
     * Where the zone is, clipped by every ancestor that clips ([boundsInRoot]), not its size from its own corner: a row half scrolled out
     * of a window ends at the window's edge, and a drop past that edge is not on the row.
     */
    fun bounds(): Rect? = coordinates?.takeIf { it.isAttached }?.boundsInRoot()
}

/** What answers drops in the area it is in: null where there is none (the phone, a sheet), and a [dropZone] is then an ordinary target. */
internal val LocalDropRouter = staticCompositionLocalOf<DropRouter?> { null }

/**
 * Which layer the zones below are on: 0 for the panels, a window's own above the windows behind it. Raising a window moves that window and
 * every one above it by one, so this is not a static local, which would recompose the whole content of each of them (a chat, a terminal)
 * for a value only the zones read.
 */
internal val LocalDropLayer = compositionLocalOf { 0 }

/** The one target of an area whose zones are routed ([DropRouter]). */
internal fun Modifier.dropRouter(router: DropRouter): Modifier =
    onGloballyPositioned { router.area = it }.dragAndDropTarget(shouldStartDragAndDrop = router::shouldStart, target = router)

/**
 * For the inside of a text field in a routed area. A text field is a target of its own for text, nested deeper than the router, so a zone
 * around it never hears the text dropped on it (what the field is given is put in the field, where a chat's message box swallows it) and
 * the router hears nothing while the pointer is over it. This is deeper than the field's own, takes what the field takes, and tells the
 * router what it is told, so that over the field it is as over the rest of the area. Without a router a [dropZone] is the nested target.
 */
@Composable
internal fun Modifier.dropRelay(): Modifier {
    val router = LocalDropRouter.current ?: return this
    return dragAndDropTarget(shouldStartDragAndDrop = { it.hasText() }, target = router.relay)
}

private fun DragAndDropEvent.hasText() = toAndroidDragEvent().clipDescription?.hasMimeType("text/*") == true

/**
 * A place that takes drags, in the signature of [dragAndDropTarget] so that it can replace one. With a [DropRouter] above it, it is a zone
 * of that router; without one it is that very target. [visible] is false for what is not being shown (a minimized window's content):
 * such a zone takes nothing. [layer] is the one it is on, which a window's own frame names, being outside what provides it.
 */
@Composable
internal fun Modifier.dropZone(
    shouldStartDragAndDrop: (DragAndDropEvent) -> Boolean,
    target: DragAndDropTarget,
    visible: Boolean = LocalWindowContentVisible.current,
    layer: Int = LocalDropLayer.current,
): Modifier {
    val router = LocalDropRouter.current ?: return dragAndDropTarget(shouldStartDragAndDrop, target)
    val zone = remember(router) { DropZone(shouldStartDragAndDrop, target, layer, visible) }
    SideEffect {
        zone.accepts = shouldStartDragAndDrop
        zone.target = target
        zone.layer = layer
        if (zone.showing != visible) {
            zone.showing = visible
            router.showingChanged(zone)
        }
    }
    DisposableEffect(router, zone) {
        router.register(zone)
        onDispose { router.unregister(zone) }
    }
    return onGloballyPositioned { zone.coordinates = it }
}

/** A zone that takes every drag and refuses every drop: where nothing under a window is to be reached through it. */
internal object RefusingDrop : DragAndDropTarget {
    override fun onDrop(event: DragAndDropEvent): Boolean = false
}
