package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.DragModifierKeys
import com.hienlh.ppm.app.ui.files.EntryDrag
import com.hienlh.ppm.app.ui.files.ExplorerDragOver
import com.hienlh.ppm.app.ui.files.ExplorerDragSpringLoad
import com.hienlh.ppm.app.ui.files.ExplorerDropSite
import com.hienlh.ppm.app.ui.files.ExplorerRowDrag
import com.hienlh.ppm.app.ui.files.HostEntryDrag
import com.hienlh.ppm.app.ui.files.ROOT_DROP_KEY
import com.hienlh.ppm.app.ui.files.explorerDropTint
import com.hienlh.ppm.app.ui.files.explorerMoveTarget
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.protocol.HostFileEntry
import com.hienlh.ppm.state.osexplorer.EntryDropDecision
import com.hienlh.ppm.state.osexplorer.decideEntryDrop

/**
 * What an explorer window does with a drag of entries, the web's `useEntryDropTarget` over `decideDrop`: whether a drag is one the window
 * takes at all, whether it would be taken on a folder now (with Ctrl or Alt held, [copyHeld], it is a copy), and the drop, which is the
 * transfer a window's Paste runs ([transfer]: the collision prompt, Replace to Trash first, a refresh of every folder it touched, and
 * "Moved N items" or "Copied N items" when it is done). The window is busy while one runs ([busy]) and takes no other.
 *
 * A drag of a window's own entries or of a project tree's rows with their project folder known (the paths are then absolute) is taken; a
 * tree row without it has only a project-relative path, which names nothing a window can write to.
 */
internal class HostEntryDrops(
    private val busy: () -> Boolean,
    private val transfer: (paths: List<String>, directory: String, copy: Boolean) -> Unit,
    private val copyHeld: () -> Boolean = { DragModifierKeys.copy },
) {
    fun accepts(drag: EntryDrag): Boolean = !busy() && when (drag) {
        is HostEntryDrag -> true
        is ExplorerRowDrag -> drag.root != null
    }

    /** Whether [site]'s drag let go there now would be taken: the web's rules ([decideEntryDrop]), with the modifier keys as they are. */
    fun takes(site: ExplorerDropSite): Boolean = accepts(site.drag) && decide(site.drag, site.directory, copyHeld()) is EntryDropDecision.Accept

    /** The drop, with the modifier keys as they are at the moment it is let go. False when it is refused, which hands the entries back. */
    fun drop(drag: EntryDrag, site: ExplorerDropSite): Boolean {
        val copy = copyHeld()
        if (!accepts(drag) || decide(drag, site.directory, copy) !is EntryDropDecision.Accept) return false
        transfer(drag.payload.paths, site.directory, copy)
        return true
    }

    private fun decide(drag: EntryDrag, directory: String, copy: Boolean) = decideEntryDrop(drag.payload.paths, directory, copy)
}

/** The drops of a window with commands, none on a phone (no drag can begin there, as on the web) and none without commands. */
@Composable
internal fun rememberHostEntryDrops(commands: HostExplorerCommands?, mobile: Boolean): HostEntryDrops? =
    remember(commands, mobile) {
        if (mobile || commands == null) null else HostEntryDrops(busy = { commands.busy }, transfer = commands::transfer)
    }

/**
 * One list of a window as the target of drags of entries: [target] goes on the list itself and [row] and [list] are the highlight of what a
 * drag has rested on ([tint]). Nothing for a window that takes no drops.
 */
internal class HostListDrops(val target: Modifier, private val tint: ExplorerDropSite?) {
    /** `ring-1 ring-primary bg-primary/10` on the list while a drag rests on its own folder (the background's `usePathDropTarget`). */
    @Composable
    fun list(): Modifier = Modifier.dropRing(tint?.key == ROOT_DROP_KEY)

    /** The same on a folder's row or tile while a drag rests on it (a file's row is no site: the list's folder has the drop, and [list] the ring). */
    @Composable
    fun row(entry: HostFileEntry, shape: Shape = RectangleShape): Modifier = Modifier.dropRing(tint?.key == entry.path, shape)

    companion object {
        val None = HostListDrops(Modifier, null)
    }
}

@Composable
private fun Modifier.dropRing(on: Boolean, shape: Shape = RectangleShape): Modifier {
    if (!on) return this
    val accent = LocalPpmTokens.current.accent
    return background(accent.opacity(0.1).color, shape).border(1.dp, accent.color, shape)
}

/**
 * The drop target of one list of a window, over the whole list, which asks the list what is under the pointer each time ([entryAt], given
 * a point in the list's own pixels), as the tree does: a folder's row is the site, with the folder's own path to write into, and a file's
 * row, the space between rows and the space below them are the list's own folder ([directory]), as the web lets a file's row fall through
 * to the view's background. A folder that refuses the drag (it is one of the entries, or has one of them inside it, or they are in it
 * already) is the site all the same, refused: the web's row consumes the drop and nothing falls through to the background behind it.
 * A drag rests on a folder [springLoad] ms opens it ([springLoad] given: Column view only), if the drop there would be taken.
 */
@Composable
internal fun rememberHostListDrops(
    drops: HostEntryDrops?, directory: String, springLoad: ((String) -> Unit)? = null, entryAt: (Offset) -> HostFileEntry?,
): HostListDrops {
    if (drops == null) return HostListDrops.None
    val over = remember { ExplorerDragOver() }
    val origin = remember { mutableStateOf(Offset.Zero) }
    val directoryNow by rememberUpdatedState(directory)
    val entryAtNow by rememberUpdatedState(entryAt)
    val dropsNow by rememberUpdatedState(drops)
    fun siteAt(drag: EntryDrag, at: Offset): ExplorerDropSite {
        val entry = entryAtNow(at - origin.value)?.takeIf { it.isDirectory } ?: return ExplorerDropSite(drag, ROOT_DROP_KEY, directoryNow, collapsedFolder = false)
        val site = ExplorerDropSite(drag, entry.path, entry.path, collapsedFolder = false)
        return if (springLoad != null && dropsNow.takes(site)) site.copy(collapsedFolder = true) else site
    }
    if (springLoad != null) ExplorerDragSpringLoad(over, springLoad)
    val tint = explorerDropTint(over, dropsNow::takes)
    val target = Modifier.onGloballyPositioned { origin.value = it.positionInRoot() }
        .explorerMoveTarget(over, dropsNow::accepts, ::siteAt, dropsNow::drop)
    return HostListDrops(target, tint)
}

/** The entry whose row is at [y] pixels from the top of this list, null for the space between rows and below them. */
internal fun LazyListState.entryAt(y: Float, entries: List<HostFileEntry>): HostFileEntry? {
    val item = layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size } ?: return null
    return entries.firstOrNull { it.path == item.key }
}

/**
 * The entry whose tile is at [at] in this grid's pixels; a tile is its cell less [inset] on every side, and the rest is the grid's. The
 * cells are counted from the grid's content, which starts [padding] in from its edge on every side.
 */
internal fun LazyGridState.entryAt(at: Offset, padding: Float, inset: Float, entries: List<HostFileEntry>): HostFileEntry? {
    val x = at.x - padding
    val y = at.y - padding
    val item = layoutInfo.visibleItemsInfo.firstOrNull {
        x >= it.offset.x + inset && x < it.offset.x + it.size.width - inset &&
            y >= it.offset.y + inset && y < it.offset.y + it.size.height - inset
    } ?: return null
    return entries.firstOrNull { it.path == item.key }
}
