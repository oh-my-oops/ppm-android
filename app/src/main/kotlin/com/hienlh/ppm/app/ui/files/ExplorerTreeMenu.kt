package com.hienlh.ppm.app.ui.files

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ui.ContextMenuPopup
import com.hienlh.ppm.app.ui.MenuIcon
import com.hienlh.ppm.app.ui.MenuItem
import com.hienlh.ppm.app.ui.MenuSeparator
import com.hienlh.ppm.app.ui.osexplorer.HostSheet
import com.hienlh.ppm.app.ui.osexplorer.SheetItem
import com.hienlh.ppm.app.ui.osexplorer.SheetSeparator
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.state.files.FileTreeNode

/**
 * Supply only implemented actions; the tree does not manufacture unavailable CRUD commands. [separatorBefore] draws the web's
 * `ContextMenuSeparator` above the row unless it is the first of the menu: [explorerMenuGroups] sets it on the first row of each
 * group after the first, so a group a gate left empty takes no divider with it.
 */
data class ExplorerTreeAction(
    val label: String,
    val icon: ProductIcon? = null,
    val destructive: Boolean = false,
    val separatorBefore: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * [groups] one after the other with a divider between each two that have rows, as `tree-node-context-menu.tsx` draws its
 * `ContextMenuSeparator`s. The flag sits on the first row of a group, so a divider can neither repeat, nor begin the menu, nor end it
 * (nothing is set on the first row of the whole). A group's own later rows keep the flag they came with: that is how
 * [ExplorerTransfers.actionsForNode] sets Download off from the two copy rows.
 */
internal fun explorerMenuGroups(vararg groups: List<ExplorerTreeAction>): List<ExplorerTreeAction> =
    groups.filter { it.isNotEmpty() }.flatMapIndexed { index, group ->
        listOf(group.first().copy(separatorBefore = index > 0)) + group.drop(1)
    }

/**
 * What a row's menu does to files: handed over only while the tree may change them (its mutations are wired and none is
 * running), so a menu of a tree that may not is the web's rows that read, with no Cut, Rename or New File among them.
 */
internal class ExplorerNodeWrites(
    val canPaste: Boolean,
    val create: (directory: Boolean) -> Unit,
    val cut: () -> Unit,
    val copy: () -> Unit,
    val paste: () -> Unit,
    val rename: () -> Unit,
    val delete: () -> Unit,
)

/**
 * The menu of a row, the rows and the dividers of `tree-node-context-menu.tsx`: New File and New Folder (a folder), [open]
 * (a folder: a File Explorer window on it, a terminal in it), Cut, Copy and Paste (a folder, with something to paste), Rename and
 * Delete, then [transfers] (Copy Path and Copy Full Path, and Download). Only [open] and Download carry an icon, as the web's
 * do. No Compare rows: they arrive with the compare feature.
 */
internal fun explorerNodeActions(
    node: FileTreeNode,
    writes: ExplorerNodeWrites?,
    open: List<ExplorerTreeAction>,
    transfers: List<ExplorerTreeAction>,
): List<ExplorerTreeAction> = explorerMenuGroups(
    if (writes != null && node.isDirectory) listOf(
        ExplorerTreeAction("New File") { writes.create(false) },
        ExplorerTreeAction("New Folder") { writes.create(true) },
    ) else emptyList(),
    open,
    if (writes == null) emptyList() else listOfNotNull(
        ExplorerTreeAction("Cut", onClick = writes.cut),
        ExplorerTreeAction("Copy", onClick = writes.copy),
        if (node.isDirectory && writes.canPaste) ExplorerTreeAction("Paste", onClick = writes.paste) else null,
    ),
    if (writes == null) emptyList() else listOf(
        ExplorerTreeAction("Rename", onClick = writes.rename),
        ExplorerTreeAction("Delete", destructive = true, onClick = writes.delete),
    ),
    transfers,
)

/**
 * The menu of the tree's blank space (`file-tree.tsx`): New File and New Folder, where the tree may create, then Refresh, each
 * with the icon the web gives it.
 */
internal fun explorerRootActions(canCreate: Boolean, onCreate: (directory: Boolean) -> Unit, onRefresh: () -> Unit): List<ExplorerTreeAction> =
    explorerMenuGroups(
        if (canCreate) listOf(
            ExplorerTreeAction("New File", ProductIcon.FilePlus) { onCreate(false) },
            ExplorerTreeAction("New Folder", ProductIcon.FolderPlus) { onCreate(true) },
        ) else emptyList(),
        listOf(ExplorerTreeAction("Refresh", ProductIcon.RefreshCw, onClick = onRefresh)),
    )

/**
 * `adaptive-context-menu.tsx`: a popup at the press on a tablet, the bottom sheet on a phone, with no title over the rows in either.
 * A row closes the menu and then runs.
 */
@Composable
internal fun ExplorerTreeMenu(at: Offset, actions: List<ExplorerTreeAction>, onDismiss: () -> Unit) {
    if (LocalPpmMobile.current) HostSheet(onDismiss, inset = 8.dp, maxHeight = 0.6f) { ExplorerSheetRows(actions, onDismiss) }
    else ContextMenuPopup(at, onDismiss) { ExplorerMenuRows(actions, onDismiss) }
}

/**
 * The tablet's rows (`ContextMenuItem`): an icon is `size-3.5 mr-2` in the muted colour, so a label after one starts 38 dp in (the
 * padding, the icon, its margin and the gap) and a row without one has it 8 dp in, with no gutter kept for the icon; a destructive
 * row is red, with the highlight `focus:bg-destructive/10` (`/20` in the device's dark mode).
 */
@Composable
internal fun ColumnScope.ExplorerMenuRows(actions: List<ExplorerTreeAction>, onDismiss: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val dark = isSystemInDarkTheme()
    actions.forEachIndexed { index, action ->
        if (action.separatorBefore && index > 0) MenuSeparator()
        MenuItem(
            action.label,
            onClick = { onDismiss(); action.onClick() },
            icon = action.icon?.let { icon -> { MenuIcon(icon, if (action.destructive) tokens.error else null) } },
            color = if (action.destructive) tokens.error else null,
            highlight = if (action.destructive) tokens.error.opacity(if (dark) 0.2 else 0.1) else null,
        )
    }
}

/**
 * The phone's rows (`BottomSheetItem`): the icon is `size-3.5 mr-2` here too, in the colour of the text, so a label after one starts
 * 42 dp in and a row without one 12 dp in; a destructive row is red.
 */
@Composable
internal fun ColumnScope.ExplorerSheetRows(actions: List<ExplorerTreeAction>, onDismiss: () -> Unit) {
    val tokens = LocalPpmTokens.current
    actions.forEachIndexed { index, action ->
        if (action.separatorBefore && index > 0) SheetSeparator()
        val color = (if (action.destructive) tokens.error else tokens.text).color
        SheetItem(
            action.label,
            destructive = action.destructive,
            leading = action.icon?.let { icon -> { Box(Modifier.padding(end = 8.dp)) { PpmIcon(icon, 14.dp, color) } } },
        ) { onDismiss(); action.onClick() }
    }
}
