package com.hienlh.ppm.state.osexplorer

import com.hienlh.ppm.protocol.HostFileEntry
import com.hienlh.ppm.protocol.HostFilesystem
import com.hienlh.ppm.protocol.HostPlace

/**
 * The entries a bulk action acts on (`activeEntries`, use-explorer-keyboard.ts): the selection in the
 * listing's order, or, with nothing selected, the cursor row, which is the one touched last (the anchor).
 * A selection naming rows the listing no longer holds is still a selection: it acts on none.
 */
fun hostActiveEntries(entries: List<HostFileEntry>, selection: Set<String>, anchor: String?): List<HostFileEntry> {
    if (selection.isNotEmpty()) return entries.filter { it.path in selection }
    return entries.firstOrNull { it.path == anchor }?.let(::listOf).orEmpty()
}

/**
 * What the phone's Select row offers over [selected] (mobile-bottom-toolbar.tsx): Cut, Copy and Trash
 * need something selected, Download a file among it (a folder is not downloaded), Info exactly one.
 */
data class HostSelectBar(val count: Int, val canAct: Boolean, val canDownload: Boolean, val canShowInfo: Boolean)

fun hostSelectBar(selected: List<HostFileEntry>) = HostSelectBar(
    count = selected.size,
    canAct = selected.isNotEmpty(),
    canDownload = selected.any { it.type == "file" },
    canShowInfo = selected.size == 1,
)

/**
 * Whether a press on the "Sort by" pill of [picked] sorts descending (mobile-more-sheet.tsx): the pill
 * of the sort in force turns the order over, ascending first, and any other starts ascending.
 */
fun hostSortPicked(sort: ExplorerSort, descending: Boolean, picked: ExplorerSort): Boolean = sort == picked && !descending

/** A "Sort by" pill: the name, and an arrow on the one in force. */
fun hostSortPillLabel(key: ExplorerSort, sort: ExplorerSort, descending: Boolean): String =
    key.label + if (key != sort) "" else if (descending) " ↓" else " ↑"

/**
 * What the window toolbar's button for a view is called (`VIEW_LABEL`, explorer-toolbar.tsx): the third is
 * "Column view" in the singular. The phone's More sheet words the same three shorter ("List", "Icons", "Columns").
 */
fun hostViewLabel(view: ExplorerView): String = when (view) {
    ExplorerView.LIST -> "List view"
    ExplorerView.ICONS -> "Icons view"
    ExplorerView.COLUMNS -> "Column view"
}

/**
 * The folder's name where the phone shows it, and in the properties of the folder (`currentFolderName`,
 * `currentFolderEntry`): the last breadcrumb, which is the folder itself, else the path as it stands.
 */
fun hostFolderName(path: String, breadcrumbs: List<HostPlace>): String = breadcrumbs.lastOrNull()?.name ?: path

/** The folder as an entry, which is all "Properties of this folder" has to give: the dialog asks the host the rest. */
fun hostFolderEntry(path: String, breadcrumbs: List<HostPlace>) = HostFileEntry(hostFolderName(path, breadcrumbs), path, "directory", "directory")

/**
 * What a skin calls the sections of the places (`SkinVocab`, skin-types.ts): Windows says "Quick access",
 * "Folders" and "Drives"; macOS "Favorites", "Locations" and "Devices". Both call Home "Home".
 */
data class HostPlacesVocab(val home: String, val pinned: String, val known: String, val drives: String)

fun hostPlacesVocab(windows: Boolean): HostPlacesVocab =
    if (windows) HostPlacesVocab(home = "Home", pinned = "Quick access", known = "Folders", drives = "Drives")
    else HostPlacesVocab(home = "Home", pinned = "Favorites", known = "Locations", drives = "Devices")

/** The glyph in front of a place: a pin for a pin of PPM's own, the skin's folder, or a drive's by its kind. */
enum class HostPlaceIcon { PIN, FOLDER, HARD_DRIVE, USB, NETWORK }

/** One place to go to, as the strip and the sheet list it; [key] is unique among the places of one list. */
data class HostPlaceLink(val key: String, val label: String, val path: String, val icon: HostPlaceIcon)

data class HostPlacesSection(val title: String, val places: List<HostPlaceLink>)

private fun pinLink(pin: HostPlace) = HostPlaceLink("pin:${pin.path}", pin.name, pin.path, HostPlaceIcon.PIN)

private fun osLink(place: HostPlace) = HostPlaceLink("os:${place.path}", place.name, place.path, HostPlaceIcon.FOLDER)

private fun knownLink(place: HostPlace) = HostPlaceLink("known:${place.path}", place.name, place.path, HostPlaceIcon.FOLDER)

private fun homeLink(host: HostFilesystem, vocab: HostPlacesVocab) = HostPlaceLink("home", vocab.home, host.home, HostPlaceIcon.FOLDER)

/** A drive reads "Label (name)" when it has a label; removable and network ones have a glyph of their own. */
private fun driveLink(drive: HostPlace) = HostPlaceLink(
    "drive:${drive.path}",
    drive.label?.takeIf { it.isNotEmpty() }?.let { "$it (${drive.name})" } ?: drive.name,
    drive.path,
    when (drive.kind) {
        "removable" -> HostPlaceIcon.USB
        "network" -> HostPlaceIcon.NETWORK
        else -> HostPlaceIcon.HARD_DRIVE
    },
)

/**
 * The chips of the phone's strip (mobile-places-strip.tsx), in the web's order and without dropping any:
 * PPM's pins, Home, the OS's pinned folders, the drives. The known folders (Documents, Downloads…) are
 * not here: they wait in the sheet behind the last chip.
 */
fun hostPlaceChips(pins: List<HostPlace>, host: HostFilesystem?, vocab: HostPlacesVocab): List<HostPlaceLink> = buildList {
    pins.mapTo(this, ::pinLink)
    if (host != null) {
        add(homeLink(host, vocab))
        host.pinned.mapTo(this, ::osLink)
        host.drives.mapTo(this, ::driveLink)
    }
}

/**
 * The places by section, for the phone's sheet and the tablet's sidebar (mobile-places-sheet.tsx,
 * explorer-sidebar.tsx): "Pinned" when PPM holds pins, the skin's pinned section when the OS has any, the
 * known folders under Home, and the drives. Nothing of the host's is listed until the host answered.
 */
fun hostPlaceSections(pins: List<HostPlace>, host: HostFilesystem?, vocab: HostPlacesVocab): List<HostPlacesSection> = buildList {
    if (pins.isNotEmpty()) add(HostPlacesSection("Pinned", pins.map(::pinLink)))
    if (host != null) {
        if (host.pinned.isNotEmpty()) add(HostPlacesSection(vocab.pinned, host.pinned.map(::osLink)))
        add(HostPlacesSection(vocab.known, listOf(homeLink(host, vocab)) + host.knownFolders.map(::knownLink)))
        if (host.drives.isNotEmpty()) add(HostPlacesSection(vocab.drives, host.drives.map(::driveLink)))
    }
}
