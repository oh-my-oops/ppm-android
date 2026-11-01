package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ui.BottomSheet
import com.hienlh.ppm.app.ui.SheetLabel
import com.hienlh.ppm.design.*
import com.hienlh.ppm.protocol.HostFilesystem
import com.hienlh.ppm.protocol.HostPlace
import com.hienlh.ppm.state.osexplorer.ExplorerSession

@Composable
internal fun HostExplorerPlaces(session: ExplorerSession, host: HostFilesystem?, windows: Boolean, mobile: Boolean,
                                dropModifier: @Composable (String) -> Modifier = { Modifier }) {
    val pins by session.preferences.pins.collectAsState()
    val state by session.state.collectAsState()
    var allPlaces by remember { mutableStateOf(false) }
    val sections = buildList<Pair<String, List<HostPlace>>> {
        if (pins.isNotEmpty()) add("Pinned" to pins)
        host?.let {
            if (it.pinned.isNotEmpty()) add((if (windows) "Quick access" else "Favorites") to it.pinned)
            add("Folders" to (listOf(HostPlace("Home", it.home)) + it.knownFolders))
            if (it.drives.isNotEmpty()) add((if (windows) "Drives" else "Locations") to it.drives)
        }
    }
    if (mobile) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val places = (pins + listOf(HostPlace("Home", host?.home ?: "~")) + host?.knownFolders.orEmpty()).distinctBy { it.path }.take(6)
            for (place in places) Place(place, state.currentDirectory == place.path, Modifier.widthIn(max = 150.dp).then(dropModifier(place.path))) { session.go(place.path) }
            Place(HostPlace("All places", ""), false) { allPlaces = true }
        }
    } else Column(Modifier.width(176.dp).fillMaxHeight().background(LocalPpmTokens.current.panel2.color).cssScrollY().padding(4.dp)) {
        PlacesSections(sections, state.currentDirectory, pins.map { it.path }.toSet(), session.preferences::unpin, dropModifier) { session.go(it) }
        host?.warnings?.forEach { warning -> BasicText(warning, Modifier.padding(8.dp),
            style = ppmText(11f, lineHeight = 15f).copy(color = LocalPpmTokens.current.text3.color)) }
    }
    if (allPlaces) BottomSheet(onDismiss = { allPlaces = false }) {
        Column(Modifier.heightIn(max = 550.dp).cssScrollY().padding(8.dp)) {
            SheetLabel("All places")
            PlacesSections(sections, state.currentDirectory, pins.map { it.path }.toSet(), session.preferences::unpin, dropModifier) {
                session.go(it); allPlaces = false
            }
        }
    }
}

@Composable
private fun PlacesSections(sections: List<Pair<String, List<HostPlace>>>, current: String, pins: Set<String>,
                           unpin: (String) -> Unit, dropModifier: @Composable (String) -> Modifier, go: (String) -> Unit) {
    val tokens = LocalPpmTokens.current
    for ((name, places) in sections) {
        BasicText(name.uppercase(), Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp),
            style = ppmText(10f, FontWeight.SemiBold).copy(color = tokens.text3.color))
        for (place in places) Row(verticalAlignment = Alignment.CenterVertically) {
            Place(place, current == place.path, Modifier.weight(1f).then(dropModifier(place.path))) { go(place.path) }
            if (name == "Pinned" && place.path in pins) HostExplorerButton(ProductIcon.Pin, "Unpin ${place.name}") { unpin(place.path) }
        }
    }
}

@Composable
private fun Place(place: HostPlace, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(modifier.heightIn(min = if (LocalPpmMobile.current) 44.dp else 32.dp).clip(RoundedCornerShape(4.dp))
        .background(if (active) tokens.accent.opacity(.12).color else androidx.compose.ui.graphics.Color.Transparent)
        .ppmClickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PpmIcon(if (place.kind in listOf("fixed", "removable", "network", "unknown")) ProductIcon.HardDrive else ProductIcon.Folder,
            16.dp, tokens.text3.color)
        BasicText(place.label?.takeIf { it.isNotEmpty() }?.let { "$it (${place.name})" } ?: place.name,
            style = ppmText(13f).copy(color = tokens.text2.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
