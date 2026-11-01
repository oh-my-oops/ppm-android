package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ui.BottomSheet
import com.hienlh.ppm.app.ui.ContextMenuPopup
import com.hienlh.ppm.app.ui.MenuItem
import com.hienlh.ppm.app.ui.MenuSeparator
import com.hienlh.ppm.app.ui.SubMenuItem
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssScrollY
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.osexplorer.ExplorerSort
import com.hienlh.ppm.state.osexplorer.HostMenuCommand
import com.hienlh.ppm.state.osexplorer.HostMenuItem
import com.hienlh.ppm.state.osexplorer.hostOrderLabel
import com.hienlh.ppm.state.osexplorer.label

/**
 * The explorer's context menu (`explorer-context-menu.tsx`, drawn through `adaptive-context-menu.tsx`):
 * on a tablet a popup at the press, [at] in window coordinates; on a phone the bottom sheet. [items]
 * says what it holds ([com.hienlh.ppm.state.osexplorer.hostEntryMenu]); a row closes the menu and
 * then runs its [onCommand], and a radio of "Sort by" closes it and gives [onSort] the key and
 * whether it is descending.
 */
@Composable
internal fun HostExplorerMenu(
    items: List<HostMenuItem>,
    at: Offset,
    onCommand: (HostMenuCommand) -> Unit,
    onSort: (ExplorerSort, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    if (LocalPpmMobile.current) HostMenuSheet(items, onCommand, onSort, onDismiss)
    else ContextMenuPopup(at, onDismiss, inWindow = true) { HostMenuRows(items, onCommand, onSort, onDismiss) }
}

/** The tablet's rows, plain text as the web's explorer menu has them, a destructive one red. */
@Composable
internal fun ColumnScope.HostMenuRows(
    items: List<HostMenuItem>,
    onCommand: (HostMenuCommand) -> Unit,
    onSort: (ExplorerSort, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    // `dark:data-[variant=destructive]:focus:bg-destructive/20`, in the device's dark mode.
    val dark = isSystemInDarkTheme()
    for (item in items) when (item) {
        is HostMenuItem.Row -> MenuItem(
            item.label,
            onClick = {
                onDismiss()
                onCommand(item.command)
            },
            color = if (item.destructive) tokens.error else null,
            highlight = if (item.destructive) tokens.error.opacity(if (dark) 0.2 else 0.1) else null,
            enabled = item.enabled,
        )
        HostMenuItem.Separator -> MenuSeparator()
        is HostMenuItem.SortBy -> SubMenuItem("Sort by", icon = null) { close ->
            HostSortRadios(item) { key, descending ->
                close()
                onDismiss()
                onSort(key, descending)
            }
        }
    }
}

/** The "Sort by" submenu's two groups of radios: what to sort by, then in which order. */
@Composable
internal fun ColumnScope.HostSortRadios(sort: HostMenuItem.SortBy, onPick: (ExplorerSort, Boolean) -> Unit) {
    for (key in ExplorerSort.entries) MenuRadio(key.label, key == sort.key) { onPick(key, sort.descending) }
    MenuSeparator()
    for (descending in listOf(false, true)) MenuRadio(hostOrderLabel(descending), descending == sort.descending) { onPick(sort.key, descending) }
}

/**
 * A radio item (`pr-2 pl-8`): the label starts 32 dp in, the circle of the chosen one in the
 * `left-2 size-3.5` gutter before it.
 */
@Composable
private fun MenuRadio(label: String, selected: Boolean, onClick: () -> Unit) {
    MenuItem(label, onClick, icon = { RadioMark(selected, 14.dp) }, iconEnd = 2.dp, modifier = Modifier.radio(selected))
}

/** The phone's sheet, as the web's `BottomSheet` takes a context menu: `p-2`, at most 60vh. */
@Composable
private fun HostMenuSheet(
    items: List<HostMenuItem>,
    onCommand: (HostMenuCommand) -> Unit,
    onSort: (ExplorerSort, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    HostSheet(onDismiss, inset = 8.dp, maxHeight = 0.6f) { HostSheetRows(items, onCommand, onSort, onDismiss) }
}

/**
 * A `vh` as Chrome measures it on a phone: the large viewport, everything under the status bar.
 */
@Composable
internal fun hostSheetVh(): Dp {
    val density = LocalDensity.current
    return with(density) { (LocalWindowInfo.current.containerSize.height - WindowInsets.statusBars.getTop(density)).toDp() }
}

/**
 * The explorer's `BottomSheet` whose class gives it `p-[inset]` (`p-2` for a menu, `p-1` for the places): that
 * padding on its sides and under it, the navigation bar beneath, and [content] scrolling once it is taller than
 * [maxHeight] of the viewport (the `max-h-[60vh] overflow-y-auto` wrapper a sheet puts round its rows).
 */
@Composable
internal fun HostSheet(onDismiss: () -> Unit, inset: Dp, maxHeight: Float, content: @Composable ColumnScope.() -> Unit) {
    val vh = hostSheetVh()
    BottomSheet(onDismiss, inset = inset, bottomPadding = WindowInsets.navigationBars.add(WindowInsets(bottom = inset))) {
        Column(Modifier.heightIn(max = vh * maxHeight).cssScrollY(), content = content)
    }
}

/**
 * The phone's rows. "Sort by" is not a submenu there: a label, and the radios indented under it
 * (`BottomSheetSubLabel`, `BottomSheetSubContent`), each of which closes the sheet.
 */
@Composable
internal fun ColumnScope.HostSheetRows(
    items: List<HostMenuItem>,
    onCommand: (HostMenuCommand) -> Unit,
    onSort: (ExplorerSort, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    for (item in items) when (item) {
        is HostMenuItem.Row -> SheetItem(item.label, item.enabled, item.destructive) {
            onDismiss()
            onCommand(item.command)
        }
        HostMenuItem.Separator -> SheetSeparator()
        is HostMenuItem.SortBy -> {
            SheetSubLabel("Sort by")
            Column(Modifier.padding(start = 8.dp)) {
                for (key in ExplorerSort.entries) SheetRadio(key.label, key == item.key) {
                    onDismiss()
                    onSort(key, item.descending)
                }
                SheetSeparator()
                for (descending in listOf(false, true)) SheetRadio(hostOrderLabel(descending), descending == item.descending) {
                    onDismiss()
                    onSort(item.key, descending)
                }
            }
        }
    }
}

/**
 * `BottomSheetItem`: `rounded-lg px-3 py-3 text-sm`, lit while pressed; half faded and dead when disabled. A
 * destructive one is red, its [icon] (`size-4`, the colour of the text) too.
 */
@Composable
internal fun SheetItem(
    label: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    modifier: Modifier = Modifier,
    icon: ProductIcon? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val color = (if (destructive) tokens.error else tokens.text).color
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .ppmClickable(enabled = enabled, pressedColor = tokens.panel2.color, shape = shape, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leading?.invoke()
        if (icon != null) PpmIcon(icon, 16.dp, color)
        BasicText(label, style = ppmText(14f, lineHeight = 20f).copy(color = color))
    }
}

/** A flattened radio: the `size-4` slot before the label holds the circle of the chosen one. */
@Composable
private fun SheetRadio(label: String, selected: Boolean, onClick: () -> Unit) {
    SheetItem(label, enabled = true, destructive = false, Modifier.radio(selected), leading = { RadioMark(selected, 16.dp) }, onClick = onClick)
}

/** `px-3 py-2 text-xs font-medium text-muted-foreground`. */
@Composable
internal fun SheetSubLabel(text: String) {
    BasicText(
        text,
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = LocalPpmTokens.current.text2.color),
    )
}

/** `-mx-1 my-1 h-px bg-border`, which the sheet's `overflow-x-hidden` cuts to its own width. */
@Composable
internal fun SheetSeparator() {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(LocalPpmTokens.current.border.color))
}

/**
 * A slot of [slot] holding the `size-2 fill-current` circle of the chosen radio. The icon is
 * lucide's `circle`, r=10 and a 2 stroke in a 24 box, so filled and drawn at 8 it spans 22/24 of that.
 */
@Composable
private fun RadioMark(selected: Boolean, slot: Dp) {
    Box(Modifier.size(slot), contentAlignment = Alignment.Center) {
        if (selected) Box(Modifier.size((8f * 22f / 24f).dp).clip(CircleShape).background(LocalPpmTokens.current.text.color))
    }
}

/**
 * What a screen reader says of a Radix `menuitemradio`: a radio button, checked or not. It goes on the
 * row's own node, where the press and the label are, so that it reads as one.
 */
private fun Modifier.radio(selected: Boolean) = semantics {
    this.selected = selected
    role = Role.RadioButton
}
