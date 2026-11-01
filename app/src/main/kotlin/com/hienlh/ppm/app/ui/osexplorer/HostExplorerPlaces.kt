package com.hienlh.ppm.app.ui.osexplorer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.*
import com.hienlh.ppm.protocol.HostFilesystem
import com.hienlh.ppm.state.osexplorer.ExplorerSession
import com.hienlh.ppm.state.osexplorer.HostPlaceIcon
import com.hienlh.ppm.state.osexplorer.HostPlaceLink
import com.hienlh.ppm.state.osexplorer.HostPlacesSection
import com.hienlh.ppm.state.osexplorer.hostPlaceSections
import com.hienlh.ppm.state.osexplorer.hostPlacesVocab

/** The glyph in front of a place, on the strip, in the sheet and in the sidebar. */
internal val HostPlaceIcon.vector: ImageVector
    get() = when (this) {
        HostPlaceIcon.PIN -> ProductIcon.Pin.vector
        HostPlaceIcon.FOLDER -> ProductIcon.Folder.vector
        HostPlaceIcon.HARD_DRIVE -> ProductIcon.HardDrive.vector
        HostPlaceIcon.USB -> ProductIcon.Usb.vector
        HostPlaceIcon.NETWORK -> LucideIcon.Network.vector
    }

/** The tablet's sidebar (`ExplorerSidebar`); the phone has its strip and sheet in `HostExplorerPhone.kt`. */
@Composable
internal fun HostExplorerPlaces(session: ExplorerSession, host: HostFilesystem?, windows: Boolean,
                                dropModifier: @Composable (String) -> Modifier = { Modifier }) {
    val pins by session.preferences.pins.collectAsState()
    val state by session.state.collectAsState()
    val sections = remember(pins, host, windows) { hostPlaceSections(pins, host, hostPlacesVocab(windows)) }
    Column(Modifier.width(176.dp).fillMaxHeight().background(LocalPpmTokens.current.panel2.color).cssScrollY().padding(4.dp)) {
        PlacesSections(sections, state.currentDirectory, session.preferences::unpin, dropModifier) { session.go(it) }
        host?.warnings?.forEach { warning -> BasicText(warning, Modifier.padding(8.dp),
            style = ppmText(11f, lineHeight = 15f).copy(color = LocalPpmTokens.current.text3.color)) }
    }
}

@Composable
private fun PlacesSections(sections: List<HostPlacesSection>, current: String, unpin: (String) -> Unit,
                           dropModifier: @Composable (String) -> Modifier, go: (String) -> Unit) {
    val tokens = LocalPpmTokens.current
    for (section in sections) {
        BasicText(section.title.uppercase(), Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp),
            style = ppmText(10f, FontWeight.SemiBold).copy(color = tokens.text3.color))
        for (place in section.places) Row(verticalAlignment = Alignment.CenterVertically) {
            Place(place, current == place.path, Modifier.weight(1f).then(dropModifier(place.path))) { go(place.path) }
            if (place.icon == HostPlaceIcon.PIN) HostExplorerButton(ProductIcon.Pin, "Unpin ${place.label}") { unpin(place.path) }
        }
    }
}

@Composable
private fun Place(place: HostPlaceLink, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(modifier.heightIn(min = if (LocalPpmMobile.current) 44.dp else 32.dp).clip(RoundedCornerShape(4.dp))
        .background(if (active) tokens.accent.opacity(.12).color else androidx.compose.ui.graphics.Color.Transparent)
        .ppmClickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PpmIcon(place.icon.vector, 16.dp, tokens.text3.color)
        BasicText(place.label, style = ppmText(13f).copy(color = tokens.text2.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
