package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.HostFileEntry
import com.hienlh.ppm.protocol.HostFilesystem
import com.hienlh.ppm.state.osexplorer.ExplorerSession
import com.hienlh.ppm.state.osexplorer.ExplorerSessionState
import com.hienlh.ppm.state.osexplorer.ExplorerSort
import com.hienlh.ppm.state.osexplorer.ExplorerView
import com.hienlh.ppm.state.osexplorer.ExplorerViewPreferences
import com.hienlh.ppm.state.osexplorer.HostMenuCommand
import com.hienlh.ppm.state.osexplorer.HostPlaceLink
import com.hienlh.ppm.state.osexplorer.HostPlacesSection
import com.hienlh.ppm.state.osexplorer.HostSelectBar
import com.hienlh.ppm.state.osexplorer.hostActiveEntries
import com.hienlh.ppm.state.osexplorer.hostFolderEntry
import com.hienlh.ppm.state.osexplorer.hostPlaceChips
import com.hienlh.ppm.state.osexplorer.hostPlaceSections
import com.hienlh.ppm.state.osexplorer.hostPlacesVocab
import com.hienlh.ppm.state.osexplorer.hostSelectBar
import com.hienlh.ppm.state.osexplorer.hostSortPicked
import com.hienlh.ppm.state.osexplorer.hostSortPillLabel

/*
 * The explorer's chrome on a phone, which the web builds apart from the window's: the title bar, the strip
 * of places, and the bar of buttons under the list, with the sheets those open (`mobile/`).
 */

/**
 * `MobileExplorerTopBar`: the folder's name and nothing else, with a round button to close, since a sheet
 * that fills the screen has no backdrop to tap. A line under it, `px-3 py-2.5`.
 */
@Composable
internal fun HostPhoneTopBar(title: String, onClose: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier.fillMaxWidth().cssBorder(tokens.border.color, sides = CssSides.Bottom).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            title,
            Modifier.weight(1f),
            style = ppmText(15f, FontWeight.SemiBold, lineHeight = 22.5f).copy(color = tokens.text.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .semantics { contentDescription = "Close file explorer" }
                .ppmClickable(pressedColor = tokens.panel2.color, shape = CircleShape, onClickLabel = "Close file explorer", onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { PpmIcon(ProductIcon.X, 20.dp, tokens.text2.color) }
    }
}

/**
 * `MobilePlacesStrip`: the places as chips in a strip that scrolls sideways, ending in "All places", which
 * opens [HostPlacesSheet] with the rest of them. [dropModifier] makes a chip a place files can be dropped on.
 */
@Composable
internal fun HostPhonePlaces(session: ExplorerSession, host: HostFilesystem?, windows: Boolean, dropModifier: @Composable (String) -> Modifier) {
    val pins by session.preferences.pins.collectAsState()
    val state by session.state.collectAsState()
    var all by remember { mutableStateOf(false) }
    val vocab = hostPlacesVocab(windows)
    val chips = remember(pins, host, windows) { hostPlaceChips(pins, host, vocab) }
    HostPlacesStrip(chips, state.currentDirectory, dropModifier, onGo = { session.go(it) }, onAll = { all = true })
    if (all) {
        val sections = remember(pins, host, windows) { hostPlaceSections(pins, host, vocab) }
        HostPlacesSheet(sections, state.currentDirectory, onGo = { all = false; session.go(it) }, onDismiss = { all = false })
    }
}

/** The strip itself: `gap-1.5 border-b px-2 py-1.5`, a chip `h-11` and round, the open one outlined and washed in the accent. */
@Composable
internal fun HostPlacesStrip(
    chips: List<HostPlaceLink>,
    current: String,
    dropModifier: @Composable (String) -> Modifier,
    onGo: (String) -> Unit,
    onAll: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (chip in chips) PlaceChip(chip, chip.path == current, Modifier.then(dropModifier(chip.path))) { onGo(chip.path) }
        AllPlacesChip(onAll)
    }
}

@Composable
private fun PlaceChip(chip: HostPlaceLink, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(
        modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(if (active) tokens.accentWash.color else Color.Transparent, CircleShape)
            .ppmClickable(pressedColor = tokens.panel2.color, shape = CircleShape, onClick = onClick)
            .cssBorder((if (active) tokens.accent else tokens.border).color, CircleShape)
            .padding(horizontal = 12.dp)
            .semantics { selected = active },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PpmIcon(chip.icon.vector, 16.dp, tokens.text3.color)
        BasicText(
            chip.label,
            Modifier.widthIn(max = 144.dp),
            style = ppmText(13f, lineHeight = 19.5f).copy(color = (if (active) tokens.text else tokens.text2).color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The last chip, `border-border` and `aria-label="All places"`: just the three dots. */
@Composable
private fun AllPlacesChip(onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Box(
        Modifier
            .height(44.dp)
            .clip(CircleShape)
            .semantics { contentDescription = "All places" }
            .ppmClickable(pressedColor = tokens.panel2.color, shape = CircleShape, onClickLabel = "All places", onClick = onClick)
            .cssBorder(tokens.border.color, CircleShape)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { PpmIcon(ProductIcon.MoreHorizontal, 16.dp, tokens.text2.color) }
}

/** The sheet behind "All places" (`MobilePlacesSheet`): `p-1`, up to 70vh, and no title of its own. */
@Composable
internal fun HostPlacesSheet(sections: List<HostPlacesSection>, current: String, onGo: (String) -> Unit, onDismiss: () -> Unit) {
    HostSheet(onDismiss, inset = 4.dp, maxHeight = 0.7f) {
        Column(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp)) { HostPlacesRows(sections, current, onGo) }
    }
}

/** A section a title and its rows, `pb-2`; a press goes to the place. */
@Composable
internal fun HostPlacesRows(sections: List<HostPlacesSection>, current: String, onGo: (String) -> Unit) {
    val tokens = LocalPpmTokens.current
    for (section in sections) Column(Modifier.padding(bottom = 8.dp)) {
        BasicText(
            section.title.uppercase(),
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
            style = ppmText(11f, FontWeight.SemiBold, lineHeight = 16.5f).copy(color = tokens.text3.color, letterSpacing = 0.025.em),
        )
        for (place in section.places) PlaceRow(place, place.path == current) { onGo(place.path) }
    }
}

@Composable
private fun PlaceRow(place: HostPlaceLink, active: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(if (active) tokens.accentWash.color else Color.Transparent, shape)
            .ppmClickable(pressedColor = tokens.panel2.color, shape = shape, onClick = onClick)
            .padding(horizontal = 12.dp)
            .semantics { selected = active },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PpmIcon(place.icon.vector, 16.dp, tokens.text3.color)
        BasicText(
            place.label,
            style = ppmText(14f, lineHeight = 20f).copy(color = (if (active) tokens.text else tokens.text2).color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * `MobileBottomToolbar`: two rows in the one bar, the buttons for the folder and, once Select is pressed, those
 * for the selection. A command takes the entries it acts on, none for the folder's own; a sheet closes before
 * its command runs, except where the web's does not: Delete permanently, Properties and the hidden-files switch
 * leave More open behind whatever they open.
 */
@Composable
internal fun HostPhoneToolbar(
    state: ExplorerSessionState,
    entries: List<HostFileEntry>,
    preferences: ExplorerViewPreferences,
    selectMode: Boolean,
    hasClipboard: Boolean,
    canUpload: Boolean,
    onSelectMode: (Boolean) -> Unit,
    onBack: () -> Unit,
    onPreferences: (ExplorerViewPreferences) -> Unit,
    onCommand: (HostMenuCommand, List<HostFileEntry>) -> Unit,
) {
    var newOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    val selected = remember(entries, state.selection) { entries.filter { it.path in state.selection } }
    if (selectMode) HostSelectRow(hostSelectBar(selected), onCancel = { onSelectMode(false) }) { onCommand(it, selected) }
    else HostPhoneRow(
        canBack = state.canBack,
        hasClipboard = hasClipboard,
        onBack = onBack,
        onNew = { newOpen = true },
        onPaste = { onCommand(HostMenuCommand.PASTE, emptyList()) },
        onSelect = { onSelectMode(true) },
        onMore = { moreOpen = true },
    )
    if (newOpen) HostSheet({ newOpen = false }, inset = 8.dp, maxHeight = 0.9f) {
        HostNewRows(canUpload) { command ->
            newOpen = false
            onCommand(command, emptyList())
        }
    }
    if (moreOpen) {
        val targets = hostActiveEntries(entries, state.selection, state.anchor)
        HostSheet({ moreOpen = false }, inset = 8.dp, maxHeight = 0.9f) {
            HostMoreRows(
                preferences,
                targets,
                onView = { view ->
                    moreOpen = false
                    onPreferences(preferences.copy(view = view))
                },
                onSort = { key ->
                    moreOpen = false
                    onPreferences(preferences.copy(sort = key, descending = hostSortPicked(preferences.sort, preferences.descending, key)))
                },
                onToggleHidden = { onPreferences(preferences.copy(showHidden = !preferences.showHidden)) },
                onDeletePermanently = { onCommand(HostMenuCommand.DELETE_PERMANENTLY, targets) },
                onProperties = {
                    onCommand(HostMenuCommand.PROPERTIES, listOf(hostFolderEntry(state.currentDirectory, state.directory?.breadcrumbs.orEmpty())))
                },
            )
        }
    }
}

/** The row for the folder: Back, New, Paste, Select, More. */
@Composable
internal fun HostPhoneRow(
    canBack: Boolean,
    hasClipboard: Boolean,
    onBack: () -> Unit,
    onNew: () -> Unit,
    onPaste: () -> Unit,
    onSelect: () -> Unit,
    onMore: () -> Unit,
) = ToolbarRow {
    ToolbarButton(ProductIcon.ArrowLeft, "Back", enabled = canBack, onClick = onBack)
    ToolbarButton(ProductIcon.Plus, "New", onClick = onNew)
    ToolbarButton(ProductIcon.ClipboardPaste, "Paste", enabled = hasClipboard, onClick = onPaste)
    ToolbarButton(ProductIcon.Check, "Select", onClick = onSelect)
    ToolbarButton(ProductIcon.MoreHorizontal, "More", onClick = onMore)
}

/**
 * The row for the selection: Cancel, how many, and the commands. Delete permanently is not among them,
 * so that the irreversible one is not as near at hand as Trash; it waits in More.
 */
@Composable
internal fun HostSelectRow(bar: HostSelectBar, onCancel: () -> Unit, onCommand: (HostMenuCommand) -> Unit) {
    val count = bar.count
    ToolbarRow {
        ToolbarButton(ProductIcon.X, "Cancel", onClick = onCancel)
        BasicText(
            "$count selected",
            Modifier.padding(horizontal = 4.dp),
            style = ppmText(12f, lineHeight = 16f).copy(color = LocalPpmTokens.current.text3.color),
        )
        Spacer(Modifier.weight(1f))
        ToolbarButton(ProductIcon.Scissors, "Cut", enabled = bar.canAct) { onCommand(HostMenuCommand.CUT) }
        ToolbarButton(ProductIcon.Copy, "Copy", enabled = bar.canAct) { onCommand(HostMenuCommand.COPY) }
        ToolbarButton(ProductIcon.Trash2, "Trash", enabled = bar.canAct, destructive = true) { onCommand(HostMenuCommand.TRASH) }
        ToolbarButton(ProductIcon.Download, "Download", enabled = bar.canDownload) { onCommand(HostMenuCommand.DOWNLOAD) }
        if (bar.canShowInfo) ToolbarButton(ProductIcon.Info, "Info") { onCommand(HostMenuCommand.PROPERTIES) }
    }
}

/** The bar: a line over it, `gap-1 px-1 py-1`. */
@Composable
private fun ToolbarRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().cssBorder(LocalPpmTokens.current.border.color, sides = CssSides.Top).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

/** A button of the bar: its icon over its label, `min-w-[44px] flex-1 py-1.5`, a faded one dead. */
@Composable
private fun RowScope.ToolbarButton(icon: ProductIcon, label: String, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val color = (if (destructive) tokens.error else tokens.text2).color
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier
            .weight(1f)
            .widthIn(min = 44.dp)
            .alpha(if (enabled) 1f else 0.3f)
            .clip(shape)
            .ppmClickable(enabled = enabled, pressedColor = tokens.panel2.color, shape = shape, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        PpmIcon(icon, 20.dp, color)
        BasicText(label, style = ppmText(10f, lineHeight = 15f).copy(color = color), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** What New offers: a file, a folder, and, once there is a queue to upload through, files. */
@Composable
internal fun ColumnScope.HostNewRows(canUpload: Boolean, onCommand: (HostMenuCommand) -> Unit) {
    SheetItem("New File", icon = ProductIcon.FilePlus) { onCommand(HostMenuCommand.NEW_FILE) }
    SheetItem("New Folder", icon = ProductIcon.FolderPlus) { onCommand(HostMenuCommand.NEW_FOLDER) }
    if (canUpload) SheetItem("Upload Files…", icon = ProductIcon.Upload) { onCommand(HostMenuCommand.UPLOAD_FILES) }
}

/**
 * `MobileMoreSheet`: the view, the sort, the hidden files, and what the web keeps off the one-tap bar,
 * delete permanently for the [targets] (the selection, or the row last touched), and the properties of the
 * folder itself rather than of the selection.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColumnScope.HostMoreRows(
    preferences: ExplorerViewPreferences,
    targets: List<HostFileEntry>,
    onView: (ExplorerView) -> Unit,
    onSort: (ExplorerSort) -> Unit,
    onToggleHidden: () -> Unit,
    onDeletePermanently: () -> Unit,
    onProperties: () -> Unit,
) {
    SheetSubLabel("View")
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ViewButton(ProductIcon.List, "List", preferences.view == ExplorerView.LIST) { onView(ExplorerView.LIST) }
        ViewButton(ProductIcon.LayoutGrid, "Icons", preferences.view == ExplorerView.ICONS) { onView(ExplorerView.ICONS) }
        ViewButton(ProductIcon.Columns3, "Columns", preferences.view == ExplorerView.COLUMNS) { onView(ExplorerView.COLUMNS) }
    }
    SheetSeparator()
    SheetSubLabel("Sort by")
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (key in ExplorerSort.entries) SortPill(hostSortPillLabel(key, preferences.sort, preferences.descending), key == preferences.sort) { onSort(key) }
    }
    SheetSeparator()
    SheetItem(
        if (preferences.showHidden) "Hide hidden files" else "Show hidden files",
        icon = if (preferences.showHidden) ProductIcon.Eye else ProductIcon.EyeOff,
        onClick = onToggleHidden,
    )
    SheetSeparator()
    val count = targets.size
    SheetItem(
        if (count > 1) "Delete permanently ($count)" else "Delete permanently",
        enabled = count > 0,
        destructive = true,
        icon = ProductIcon.Trash2,
        onClick = onDeletePermanently,
    )
    SheetItem("Properties of this folder", icon = ProductIcon.Info, onClick = onProperties)
}

/** One of the three views: `flex-1`, its icon over its label, washed in the accent while it is the one in force. */
@Composable
private fun RowScope.ViewButton(icon: ProductIcon, label: String, active: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val color = (if (active) tokens.accent else tokens.text2).color
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier
            .weight(1f)
            .clip(shape)
            .background(if (active) tokens.accentWash.color else Color.Transparent, shape)
            .ppmClickable(pressedColor = tokens.panel2.color, shape = shape, onClick = onClick)
            .padding(vertical = 8.dp)
            .semantics { selected = active },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PpmIcon(icon, 16.dp, color)
        BasicText(label, style = ppmText(12f, lineHeight = 16f).copy(color = color))
    }
}

/** A "Sort by" pill: round and outlined, `min-h-11`, the one in force in the accent. */
@Composable
private fun SortPill(label: String, active: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .clip(CircleShape)
            .background(if (active) tokens.accentWash.color else Color.Transparent, CircleShape)
            .ppmClickable(pressedColor = tokens.panel2.color, shape = CircleShape, onClick = onClick)
            .cssBorder((if (active) tokens.accent else tokens.border).color, CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { selected = active },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = ppmText(12f, lineHeight = 16f).copy(color = (if (active) tokens.text else tokens.text2).color))
    }
}
