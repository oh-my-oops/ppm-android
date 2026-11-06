package com.hienlh.ppm.app.ui.files

import android.content.ClipData
import android.content.ClipDescription
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.rememberTextMeasurer
import com.hienlh.ppm.app.DragModifierKeys
import com.hienlh.ppm.app.ui.dropZone
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.state.osexplorer.EntryDragOrigin
import com.hienlh.ppm.state.osexplorer.EntryDragPayload
import com.hienlh.ppm.state.osexplorer.EntryDropDecision
import com.hienlh.ppm.state.osexplorer.decideEntryDrop
import com.hienlh.ppm.state.workspace.absoluteProjectPath
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.ceil

/** The web's `TREE_LEGACY_DRAG_MIME`: what a drag that began on a project-tree row says it carries. */
internal const val TREE_PATH_MIME = "application/x-ppm-path"

/** The web's `ENTRY_DRAG_MIME`: the payload every PPM entry drag carries, the absolute paths of everything dragged. */
internal const val ENTRY_DRAG_MIME = "application/x-ppm-paths"

/** [ExplorerDropSite.key] of the blank area under the rows. A row's key is its path, which is never empty. */
internal const val ROOT_DROP_KEY = ""

/** `SPRING_LOAD_MS`: how long a drag rests on a collapsed folder before it opens. */
internal const val SPRING_LOAD_MS = 800L

/** `HIGHLIGHT_DELAY_MS`: how long a drag rests on the blank area before the tree is tinted for it, so a sweep across it does not flash. */
internal const val ROOT_TINT_DELAY_MS = 120L

/**
 * What a project-tree row carries while it is dragged, as the drag's local state: the project it came from, the row's own
 * project-relative [path], and [paths], everything the drag carries: the whole selection when the row is one of several selected, the
 * row alone otherwise (the web's `dragAbsolutePaths`). [root] is the project folder, absolute, which the web's payload paths are under;
 * a tree that does not know it carries them as they are. The web keeps the same in a module-level "in-flight" value because a drop
 * target cannot read a drag's data while it hovers; local state can be read at every step.
 */
internal data class ExplorerRowDrag(
    val project: String, val path: String, val isDirectory: Boolean,
    val paths: List<String> = listOf(path), val root: String? = null,
) : EntryDrag {
    /** [paths] as the payload carries them: absolute under [root]. */
    val absolutePaths: List<String> get() = root?.let { base -> paths.map { absoluteProjectPath(base, it) } } ?: paths

    override val payload get() = EntryDragPayload(absolutePaths, EntryDragOrigin.TREE, project)
}

/** The web's `encodeEntryDrag`: `{ paths, origin, projectName }`, the shared payload every drop target of PPM reads. */
internal fun explorerEntryPayload(drag: EntryDrag): String = drag.payload.toJson()

/**
 * The clip of a drag of entries. A project tree's row has the web's legacy single-path value as item 0, with a "/" after a folder's path:
 * the chat's drop reads that one, so it stays first; item 1 is the shared payload ([explorerEntryPayload]). A window's entries have the
 * shared payload alone: they are no rows of a project, and the chat's drop has no path to take from them. No `text/plain` or
 * `text/uri-list` mirror, which the web writes for apps that understand dropped files: here they would light the chat's "Drop files to
 * attach" for what is not a file.
 */
internal fun explorerDragClip(drag: EntryDrag): ClipData = when (drag) {
    is ExplorerRowDrag -> ClipData(
        ClipDescription("PPM path", arrayOf(TREE_PATH_MIME, ENTRY_DRAG_MIME)),
        ClipData.Item(if (drag.isDirectory) drag.path + "/" else drag.path),
    ).apply { addItem(ClipData.Item(explorerEntryPayload(drag))) }
    is HostEntryDrag -> ClipData(ClipDescription("PPM entries", arrayOf(ENTRY_DRAG_MIME)), ClipData.Item(explorerEntryPayload(drag)))
}

/** The chip's words (`drag-ghost.ts`): the entry's own name for one, "N items" for a selection. */
internal fun explorerDragLabel(drag: EntryDrag): String {
    val names = drag.payload.paths.map { path -> path.split('/', '\\').lastOrNull { it.isNotEmpty() } ?: path }
    return names.singleOrNull() ?: "${names.size} items"
}

/**
 * The web's `decideDrop` for a drop on the project folder [root], which [ExplorerEntryDrops] takes on the tree's blank area and on its header:
 * whether [drag] let go there now, with [copy] as Ctrl or Alt hold it, would be taken. The drag's paths are compared as the web
 * compares them, absolute and normalised. A move of rows that are all in the folder already changes nothing, so it is refused, and a
 * copy of them is not (it makes `name (2)`, as every OS does). So is a drag that holds the folder itself or one above it, which would
 * go into itself: from the tree's own rows (all under the folder) it never happens, from a window's entries it can.
 */
internal fun explorerRootDropTaken(drag: EntryDrag, copy: Boolean, root: String): Boolean =
    decideEntryDrop(drag.payload.paths, root, copy) is EntryDropDecision.Accept

/**
 * Only a mouse's primary button drags a row. The web gives a coarse pointer no drag at all, a long press is the menu,
 * and a finger's or a stylus's swipe is what scrolls the list, so none of them can start one here.
 */
internal fun explorerDragStarts(type: PointerType, primaryPressed: Boolean) = type == PointerType.Mouse && primaryPressed

private const val EDGE_ZONE = 48.0
private const val MAX_STEP = 16.0

/** The web's `stepFor`: dp to scroll in a frame with the pointer [distance] dp from an edge; nothing beyond the 48 dp zone. */
internal fun explorerEdgeStep(distance: Double): Int {
    if (distance >= EDGE_ZONE) return 0
    return ceil(((EDGE_ZONE - distance) / EDGE_ZONE).coerceIn(0.0, 1.0) * MAX_STEP).toInt()
}

/** dp to scroll in a frame, down positive: the bottom edge's step less the top edge's, as the web's `velocity.y`. */
internal fun explorerAutoScrollStep(fromTop: Double, fromBottom: Double) = explorerEdgeStep(fromBottom) - explorerEdgeStep(fromTop)

/**
 * What a dragged row is over: [key] is the row's path ([ROOT_DROP_KEY] for the blank area), [directory] where a drop would
 * put the dragged row (a folder's own path for a folder, its parent for a file, "" for the blank area) and [collapsedFolder]
 * whether it is a folder that is shut, which a drag resting on it opens.
 */
internal data class ExplorerDropSite(val drag: EntryDrag, val key: String, val directory: String, val collapsedFolder: Boolean)

/** One drag of a row over the tree: what the pointer is over, and where it is, in the tree's own pixels. Both clear when it leaves. */
@Stable
internal class ExplorerDragOver {
    var site by mutableStateOf<ExplorerDropSite?>(null)
    var pointer by mutableStateOf(Offset.Unspecified)
}

/**
 * A row, a tile or an entry is the source of a drag by a mouse's primary button, once it has moved past the touch slop, and by nothing else.
 * Compose's own start detector cannot be told to ignore a finger: it waits out a long press and consumes the lift of a
 * tap that is not one, so a tap on a row that was a drag source opened nothing. This one watches in the initial pass and
 * consumes nothing, so a tap, the long-press menu and a right click reach the row as if it were not a source.
 *
 * It starts the platform drag itself, with [View.startDragAndDrop], because Compose's `dragAndDropSource` holds the touch point
 * at the middle of the image it draws and the browser holds its image 12 dp from the corner. The image is the chip of
 * `drag-ghost.ts` ([ExplorerDragChip]), not the row: the web's `setDragImage` is given a small box with the entry's name or "N
 * items" in it. Targets inside the window still hear the drag, whoever started it, with the [EntryDrag] as its local state. That is
 * asked for ([drag]) when the drag starts, not when the gesture was set up, so what a window's row carries is the selection it is part of
 * at that moment. The modifier keys at the press are noted for the drop, which Android's `DragEvent` carries none for ([DragModifierKeys]).
 */
@Composable
internal fun Modifier.explorerEntryDragSource(enabled: Boolean, drag: () -> EntryDrag): Modifier {
    if (!enabled) return this
    val current by rememberUpdatedState(drag)
    val view = LocalView.current
    val tokens by rememberUpdatedState(LocalPpmTokens.current)
    val density by rememberUpdatedState(LocalDensity.current)
    val measurer = rememberTextMeasurer()
    return pointerInput(view, measurer) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (explorerDragStarts(down.type, currentEvent.buttons.isPrimaryPressed) && awaitPastSlop(down)) {
                currentEvent.keyboardModifiers.let { DragModifierKeys.update(ctrl = it.isCtrlPressed, alt = it.isAltPressed) }
                // One drag for the chip, the clip and the local state: what the row carries now, not when this gesture was set up.
                val started = current()
                val chip = ExplorerDragChip.of(explorerDragLabel(started), measurer, density, tokens)
                view.startDragAndDrop(explorerDragClip(started), ExplorerDragShadow(chip), started, 0)
            }
        }
    }
}

/** A project tree's row as the source of its drag ([explorerEntryDragSource]). */
@Composable
internal fun Modifier.explorerRowDragSource(drag: ExplorerRowDrag, enabled: Boolean): Modifier = explorerEntryDragSource(enabled) { drag }

/** True once [down]'s pointer has moved further than the touch slop with its button held; false if it lifts or is cancelled first. */
private suspend fun AwaitPointerEventScope.awaitPastSlop(down: PointerInputChange): Boolean {
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: return false
        if (!change.pressed) return false
        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) return true
    }
}

private class DropHere(
    val accepts: (EntryDrag) -> Boolean,
    val siteAt: (EntryDrag, Offset) -> ExplorerDropSite, val onDrop: (EntryDrag, ExplorerDropSite) -> Boolean,
)

/**
 * The whole list is one target and asks the list what is under the pointer each time, rather than every row being a target
 * of its own: Android sends a location only when the pointer moves, so rows that scroll under a pointer held still would
 * keep their last answer, both for the highlight and for where the drop goes.
 */
private class ExplorerMoveTarget(private val over: ExplorerDragOver, private val here: () -> DropHere) : DragAndDropTarget {
    fun accepts(event: DragAndDropEvent): Boolean {
        val drag = event.drag() ?: return false
        return here().accepts(drag)
    }

    override fun onEntered(event: DragAndDropEvent) = moved(event)

    override fun onMoved(event: DragAndDropEvent) = moved(event)

    override fun onExited(event: DragAndDropEvent) = leave()

    override fun onEnded(event: DragAndDropEvent) = leave()

    /** False for a drop that is refused, which makes Android take the dragged row back. */
    override fun onDrop(event: DragAndDropEvent): Boolean {
        val drag = event.drag()
        val at = event.at()
        leave()
        return drag != null && here().let { it.onDrop(drag, it.siteAt(drag, at)) }
    }

    private fun moved(event: DragAndDropEvent) {
        val drag = event.drag() ?: return
        val at = event.at()
        over.pointer = at
        over.site = here().siteAt(drag, at)
    }

    private fun leave() {
        over.pointer = Offset.Unspecified
        over.site = null
    }

    private fun DragAndDropEvent.drag() = toAndroidDragEvent().localState as? EntryDrag

    private fun DragAndDropEvent.at() = toAndroidDragEvent().let { Offset(it.x, it.y) }
}

/**
 * The drop target of a drag of entries [accepts] says it takes, over the whole list. [siteAt] answers what is at a point of it,
 * [onDrop] gets the site the entries are let go over and says whether the drop was taken. A zone of the working area's router
 * ([dropZone]) where there is one, so that a window floating over the chat has what is let go on it.
 */
@Composable
internal fun Modifier.explorerMoveTarget(
    over: ExplorerDragOver, accepts: (EntryDrag) -> Boolean,
    siteAt: (EntryDrag, Offset) -> ExplorerDropSite, onDrop: (EntryDrag, ExplorerDropSite) -> Boolean,
): Modifier {
    val here by rememberUpdatedState(DropHere(accepts, siteAt, onDrop))
    val target = remember(over) { ExplorerMoveTarget(over) { here } }
    return dropZone(shouldStartDragAndDrop = target::accepts, target = target)
}

/**
 * The site a drag is tinted for, as `useEntryDropTarget` tints: only once the pointer has rested [ROOT_TINT_DELAY_MS] on it, and only if
 * the drop would be taken ([takes], asked as the pointer arrives, with the modifier keys as they are then), so a sweep across rows
 * flashes none and a refused drop never looks like a promise. Moving to another site, or leaving by any way a drag leaves, takes the
 * tint away, and coming back waits again. Null while there is none.
 */
@Composable
internal fun explorerDropTint(over: ExplorerDragOver, takes: (ExplorerDropSite) -> Boolean): ExplorerDropSite? {
    var tint by remember(over) { mutableStateOf<ExplorerDropSite?>(null) }
    val takesNow by rememberUpdatedState(takes)
    LaunchedEffect(over) {
        snapshotFlow { over.site }.collectLatest { site ->
            tint = null
            if (site != null && takesNow(site)) {
                delay(ROOT_TINT_DELAY_MS)
                tint = site
            }
        }
    }
    return tint
}

/** [explorerDropTint] for the tree's blank area: whether the drag has rested there and would be taken ([takes]). */
@Composable
internal fun explorerRootTint(over: ExplorerDragOver, takes: (EntryDrag) -> Boolean): Boolean =
    explorerDropTint(over) { site -> site.key == ROOT_DROP_KEY && takes(site.drag) } != null

/**
 * Dp to scroll in a frame for a pointer at [pointer] over [area]: 0 with no pointer or no area, and 0 for a pointer outside it, over
 * the header above the list, as the web's list hears only a drag over itself. [area] is read inside a `snapshotFlow`, so it has to be state.
 */
internal fun explorerPointerScrollStep(pointer: Offset, area: Rect?, density: Float, horizontal: Boolean = false): Int {
    if (pointer == Offset.Unspecified || area == null ||
        pointer.x < area.left || pointer.x > area.right ||
        pointer.y < area.top || pointer.y > area.bottom
    ) return 0
    return if (horizontal) {
        explorerAutoScrollStep((pointer.x - area.left) / density.toDouble(), (area.right - pointer.x) / density.toDouble())
    } else {
        explorerAutoScrollStep((pointer.y - area.top) / density.toDouble(), (area.bottom - pointer.y) / density.toDouble())
    }
}

/**
 * The web's `useDragAutoScroll`: while the pointer of a dragged row is within 48 dp of an edge of [scroll] (top and bottom, or left and
 * right when [horizontal]), scrolls `ceil(intensity x 16)` dp a frame, the more the closer it is, and says so ([rehit]) so what is under
 * the pointer is asked again. The last position stands in for the web's `dragover`, which a pointer held still keeps sending.
 * Frames are only asked for while [scroll] can move that way, so a drag in the middle of it, or at an end it has reached,
 * costs none, and a list that grows under a held pointer (a folder opening) starts again by itself.
 */
@Composable
internal fun ExplorerDragAutoScroll(
    over: ExplorerDragOver, scroll: ScrollableState, area: () -> Rect?,
    horizontal: Boolean = false, rehit: () -> Unit,
) {
    val density by rememberUpdatedState(LocalDensity.current.density)
    val areaNow by rememberUpdatedState(area)
    val rehitNow by rememberUpdatedState(rehit)
    LaunchedEffect(over, scroll, horizontal) {
        snapshotFlow {
            val step = explorerPointerScrollStep(over.pointer, areaNow(), density, horizontal)
            if ((step > 0 && !scroll.canScrollForward) || (step < 0 && !scroll.canScrollBackward)) 0 else step
        }.collectLatest { step ->
            while (step != 0) {
                withFrameNanos { }
                scroll.scrollBy(step * density)
                rehitNow()
            }
        }
    }
}

/** The web's `springLoad`: a drag that rests [SPRING_LOAD_MS] on a collapsed folder opens it ([open] gets its path). */
@Composable
internal fun ExplorerDragSpringLoad(over: ExplorerDragOver, open: (String) -> Unit) {
    val openNow by rememberUpdatedState(open)
    LaunchedEffect(over) {
        snapshotFlow { over.site?.takeIf { it.collapsedFolder }?.key }.collectLatest { key ->
            if (key != null) {
                delay(SPRING_LOAD_MS)
                openNow(key)
            }
        }
    }
}
