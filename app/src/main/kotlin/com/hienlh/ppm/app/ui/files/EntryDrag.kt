package com.hienlh.ppm.app.ui.files

import com.hienlh.ppm.state.osexplorer.EntryDragOrigin
import com.hienlh.ppm.state.osexplorer.EntryDragPayload

/**
 * What a drag of entries carries as the platform drag's local state, which a drop target can read at every step (the web keeps the same
 * in a module-level "in-flight" value, because a target cannot read a drag's data while it hovers): rows of a project tree
 * ([ExplorerRowDrag]) or entries of an OS Explorer window ([HostEntryDrag]). [payload] is what the drag's shared clip type says
 * ([EntryDragPayload]): the absolute host paths, where they came from, and the project for a tree's.
 */
internal sealed interface EntryDrag {
    val payload: EntryDragPayload
}

/**
 * What a row or tile of an explorer window carries: the absolute host [paths] of the selection the dragged one is part of, or of the
 * one alone (`dragPaths` of the web's `ListRow`, `IconsViewTile`; a column carries the one entry).
 */
internal data class HostEntryDrag(val paths: List<String>) : EntryDrag {
    override val payload get() = EntryDragPayload(paths, EntryDragOrigin.EXPLORER)
}
