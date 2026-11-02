package com.hienlh.ppm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.brightness
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssScrollY
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.ChangelogSection
import com.hienlh.ppm.state.ServerUpgrade
import com.hienlh.ppm.state.UpgradeOffer

private const val RELEASES_URL = "https://github.com/hienlh/ppm/releases"

/**
 * The server's version (upgrade-button.tsx), set in [text]: `v0.23.21`, which opens the release
 * notes, or while a newer version is on offer the green `New version · v0.23.24`, which opens the
 * update popover above it; then "Updating…" while it installs and "Updating, reconnecting…" while
 * the server restarts. [alignStart] is the drawer's `align="left"`, [prefix] false where the
 * status bar, under 48rem, drops "New version · ", and [minHeight] the phone's 44 px target.
 */
@Composable
internal fun UpgradeButton(
    model: AppModel,
    text: TextStyle,
    modifier: Modifier = Modifier,
    alignStart: Boolean = false,
    prefix: Boolean = true,
    minHeight: Dp = 0.dp,
) {
    val tokens = LocalPpmTokens.current
    val upgrade = model.upgrade ?: return
    val state by upgrade.state.collectAsState()
    val offer = state.offer(model.info?.version)
    val current = offer.current ?: return
    val success = tokens.success.color
    when (state.phase) {
        // A disabled button, at `disabled:opacity-70`.
        ServerUpgrade.Phase.RESTARTING -> Row(
            modifier.heightIn(min = minHeight).alpha(0.7f).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PpmIcon(ProductIcon.Loader2, 12.dp, success, spinning = true)
            BasicText("Updating, reconnecting…", style = text.copy(color = success), maxLines = 1)
        }
        // A span, without the phone's 44 px.
        ServerUpgrade.Phase.UPGRADING -> Row(
            modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PpmIcon(ProductIcon.Loader2, 12.dp, success, spinning = true)
            BasicText("Updating…", style = text.copy(color = success), maxLines = 1)
        }
        ServerUpgrade.Phase.IDLE -> {
            var open by remember { mutableStateOf(false) }
            // Opening asks again past the server's cache, and reads the notes each time.
            LaunchedEffect(open) { if (open) upgrade.refresh() }
            LaunchedEffect(open, current) { if (open) upgrade.loadNotes() }
            val hover = rememberHover()
            val color = if (offer.hasUpdate) success else text.color.towards(tokens.text.color, hover.lit)
            Box(modifier) {
                Row(
                    Modifier
                        .heightIn(min = minHeight)
                        .clip(RoundedCornerShape(6.dp))
                        .ppmClickable(
                            pressedColor = Color.Transparent,
                            onClickLabel = if (offer.hasUpdate) "Update available: v${offer.offered}" else "PPM v$current — release notes",
                            source = hover.source,
                        ) { open = !open }
                        .then(if (offer.hasUpdate) Modifier.brightness(1f + 0.1f * hover.lit) else Modifier)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (offer.hasUpdate) PpmIcon(ProductIcon.ArrowUpCircle, 12.dp, success)
                    val label = if (offer.hasUpdate) "${if (prefix) "New version · " else ""}v${offer.offered}" else "v$current"
                    BasicText(label, style = text.copy(color = color), maxLines = 1)
                }
                if (open) {
                    // `absolute bottom-full mb-2`, its left or right edge on the wrapper's.
                    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
                    val place = remember(gap, alignStart) {
                        { anchor: IntRect, _: IntSize, box: IntSize ->
                            IntOffset(if (alignStart) anchor.left else anchor.right - box.width, anchor.top - gap - box.height)
                        }
                    }
                    PlacedPopup(place, onDismiss = { open = false }) {
                        UpgradePopover(
                            offer,
                            notesLoading = state.notesLoading,
                            update = {
                                open = false
                                upgrade.update(offer.offered)
                            },
                            later = {
                                upgrade.dismiss(offer.offered)
                                open = false
                            },
                        )
                    }
                }
            }
        }
    }
}

/** This colour moved [fraction] of the way to [other] in sRGB, as a CSS colour transition moves it. */
private fun Color.towards(other: Color, fraction: Float) = Color(
    red + (other.red - red) * fraction,
    green + (other.green - green) * fraction,
    blue + (other.blue - blue) * fraction,
    alpha + (other.alpha - alpha) * fraction,
)

/**
 * `w-72 rounded-[var(--rad-sm)] border popover-solid shadow-panel`: a head saying what it is, the
 * release notes (`max-h-56`, scrolling), then Update now, the releases page and Later — the
 * first and last only while an update is on offer.
 */
@Composable
internal fun UpgradePopover(offer: UpgradeOffer, notesLoading: Boolean, update: () -> Unit, later: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(12.dp)
    val uriHandler = LocalUriHandler.current
    Column(
        Modifier
            .width(288.dp)
            .ppmShadow(tokens.shadowPanel, shape)
            .clip(shape)
            .background(tokens.panel2.over(tokens.bgSolid).color)
            .cssBorder(tokens.border.color, shape),
    ) {
        // `flex items-center gap-2.5 px-3 py-3 border-b border-border-soft`.
        Row(
            Modifier.fillMaxWidth().cssBorder(tokens.borderSoft.color, sides = CssSides.Bottom).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background((if (offer.hasUpdate) tokens.success.opacity(0.2) else tokens.accent.opacity(0.15)).color),
                contentAlignment = Alignment.Center,
            ) {
                if (offer.hasUpdate) {
                    PpmIcon(ProductIcon.ArrowUpCircle, 15.dp, tokens.success.color)
                } else {
                    PpmIcon(ProductIcon.History, 15.dp, tokens.accent.color)
                }
            }
            Column(Modifier.weight(1f)) {
                BasicText(
                    if (offer.hasUpdate) "Update available" else "Release notes",
                    style = ppmText(13f, FontWeight.SemiBold, lineHeight = 16.25f).copy(color = tokens.text.color),
                )
                BasicText(
                    if (offer.hasUpdate) "v${offer.current} → v${offer.offered}" else "PPM v${offer.current}",
                    Modifier.padding(top = 2.dp),
                    style = ppmText(11f, kind = FontKind.MONO, lineHeight = 16.5f).copy(color = tokens.text3.color),
                )
            }
        }
        // `max-h-56 overflow-y-auto px-3 py-2.5 text-[12px] text-text-2 leading-relaxed break-words`.
        Box(Modifier.fillMaxWidth().heightIn(max = 224.dp).cssScrollY().padding(horizontal = 12.dp, vertical = 10.dp)) {
            val body = ppmText(12f, lineHeight = 19.5f)
            val notes = offer.visibleNotes
            when {
                notesLoading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PpmIcon(ProductIcon.Loader2, 14.dp, tokens.text3.color, spinning = true)
                    BasicText("Loading release notes…", style = body.copy(color = tokens.text3.color))
                }
                !notes.isNullOrEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (section in notes) ChangelogEntry(section)
                }
                else -> BasicText(
                    if (offer.hasUpdate) {
                        "A new version of PPM is available. Open the release notes for details."
                    } else {
                        "No release notes available right now."
                    },
                    style = body.copy(color = tokens.text3.color),
                )
            }
        }
        // `flex flex-col gap-1.5 px-3 pb-3`.
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val buttonShape = RoundedCornerShape(12.dp)
            if (offer.hasUpdate) {
                val hover = rememberHover()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(buttonShape)
                        .brightness(1f + 0.05f * hover.lit)
                        .background(tokens.success.color)
                        .ppmClickable(pressedColor = Color.Transparent, source = hover.source, onClick = update)
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                ) {
                    PpmIcon(ProductIcon.Download, 14.dp, UPDATE_TEXT)
                    BasicText("Update now", style = ppmText(12.5f, FontWeight.Bold, lineHeight = 18.75f).copy(color = UPDATE_TEXT))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val small = ppmText(12f, FontWeight.Medium, lineHeight = 16f)
                val linkHover = rememberHover()
                val linkColor = tokens.text.mix(tokens.text2, linkHover.lit.toDouble()).color
                Row(
                    Modifier
                        .weight(1f)
                        .clip(buttonShape)
                        .background(tokens.panel.color)
                        .cssBorder(tokens.border.color, buttonShape)
                        .ppmClickable(pressedColor = Color.Transparent, role = androidx.compose.ui.semantics.Role.Button, source = linkHover.source) {
                            runCatching { uriHandler.openUri(RELEASES_URL) }
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                ) {
                    PpmIcon(ProductIcon.ExternalLink, 12.dp, linkColor)
                    BasicText("Release notes", style = small.copy(color = linkColor))
                }
                if (offer.hasUpdate) {
                    val laterHover = rememberHover()
                    BasicText(
                        "Later",
                        Modifier
                            .clip(buttonShape)
                            .cssBorder(tokens.border.color, buttonShape)
                            .ppmClickable(pressedColor = Color.Transparent, source = laterHover.source, onClick = later)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.mix(tokens.text3, laterHover.lit.toDouble()).color),
                    )
                }
            }
        }
    }
}

/** Update now's `text-[#04130c]` on the success green. */
private val UPDATE_TEXT = Color(0xFF04130C)

/** `ChangelogEntry`: the version, then each line as a category heading, a bullet or text. */
@Composable
private fun ChangelogEntry(section: ChangelogSection) {
    val tokens = LocalPpmTokens.current
    val body = ppmText(12f, lineHeight = 19.5f).copy(color = tokens.text2.color)
    Column {
        BasicText(
            "v${section.version}",
            Modifier.padding(bottom = 4.dp),
            style = ppmText(11f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 17.875f).copy(color = tokens.text.color),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (line in section.body.split("\n")) {
                val trimmed = line.trim()
                when {
                    trimmed.isEmpty() -> Unit
                    trimmed.startsWith("### ") -> BasicText(
                        trimmed.substring(4).uppercase(),
                        Modifier.padding(top = 4.dp),
                        style = ppmText(10f, FontWeight.SemiBold, lineHeight = 16.25f).copy(color = tokens.text3.color, letterSpacing = 0.025.em),
                    )
                    trimmed.startsWith("- ") -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BasicText("•", style = body.copy(color = tokens.text3.color))
                        BasicText(stripInline(trimmed.substring(2)), Modifier.weight(1f, fill = false), style = body)
                    }
                    else -> BasicText(stripInline(trimmed), style = body)
                }
            }
        }
    }
}

/** `stripInline`: the notes' `**bold**` and `code` as plain text. */
internal fun stripInline(text: String): String = CODE.replace(BOLD.replace(text, "$1"), "$1")

private val BOLD = Regex("""\*\*(.+?)\*\*""")
private val CODE = Regex("`([^`]+)`")
