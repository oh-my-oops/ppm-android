package com.hienlh.ppm.app.ui.windows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ui.tablet.Edge
import com.hienlh.ppm.app.ui.tablet.edge
import com.hienlh.ppm.app.ui.tabTypeIcon
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.windows.WindowKind
import com.hienlh.ppm.state.windows.WindowRuntimeState
import com.hienlh.ppm.state.windows.WindowVisualState
import kotlinx.serialization.json.JsonObject

/** window-chrome-contract.ts `TITLEBAR_HEIGHT`, both skins. */
private val TITLEBAR_HEIGHT = 36.dp

/**
 * window-skin-chrome.tsx: the one titlebar every window wears, in the skin Settings picked or the
 * server's OS calls for — Windows 11's on a Windows server, Finder's on any other.
 */
@Composable
internal fun WindowTitlebar(
    windowsSkin: Boolean,
    win: WindowRuntimeState,
    meta: WindowMeta,
    focused: Boolean,
    showSubtitle: Boolean,
    modifier: Modifier,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onToggleSnap: () -> Unit,
    onClose: () -> Unit,
) {
    val title = meta.title?.takeIf { it.isNotEmpty() } ?: windowTitle(win.kind, win.payload)
    val subtitle = meta.subtitle?.takeIf { showSubtitle }
    if (windowsSkin) {
        WindowsTitlebar(win, title, subtitle, meta, focused, modifier, onMinimize, onToggleMaximize, onToggleSnap, onClose)
    } else {
        MacosTitlebar(win, title, subtitle, meta, focused, modifier, onMinimize, onToggleMaximize, onToggleSnap, onClose)
    }
}

/** macos-window-chrome.tsx's lights: the documented colours, and the grey of a window behind. */
private val LIGHTS = listOf(
    Triple(Color(0xFFFF5F57), "Close window", ProductIcon.X),
    Triple(Color(0xFFFEBC2E), "Minimize window", ProductIcon.Minus),
    Triple(Color(0xFF28C840), "Maximize window", ProductIcon.Plus),
)
private val UNFOCUSED_LIGHT = Color(0xFF8E8E93)

/**
 * macos-window-chrome.tsx: the traffic lights at the left (`pl-3 gap-2`), the title centred
 * between them and the snap button (`pr-2`), on `--x-titlebar-bg` with `--x-radius` 10 px. A
 * touch screen cannot hover, so the lights' glyphs always show (`can-hover:opacity-0`).
 */
@Composable
private fun MacosTitlebar(
    win: WindowRuntimeState,
    title: String,
    subtitle: String?,
    meta: WindowMeta,
    focused: Boolean,
    modifier: Modifier,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onToggleSnap: () -> Unit,
    onClose: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val actions = listOf(onClose, onMinimize, onToggleMaximize)
    Row(
        modifier
            .fillMaxWidth()
            .height(TITLEBAR_HEIGHT)
            .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
            .background(tokens.panel2.color)
            .edge(Edge.Bottom, tokens.border.color),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LIGHTS.forEachIndexed { i, (color, label, glyph) ->
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (focused) color else UNFOCUSED_LIGHT)
                        .ppmClickable(pressedColor = Color.Transparent, shape = CircleShape, onClickLabel = label, onClick = actions[i]),
                    contentAlignment = Alignment.Center,
                ) { PpmIcon(glyph, 8.dp, Color.Black.copy(alpha = 0.6f)) }
            }
        }
        // A flex child rather than centred over the bar, so a long title truncates between the lights and the button.
        Row(
            Modifier.weight(1f).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (win.kind != WindowKind.Explorer) WindowTile(meta.icon, meta.tone, TileSize.Small)
            WindowTitleText(title, subtitle, meta.busy, focused, Modifier.weight(1f, fill = false))
        }
        Row(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            // `size-6 rounded text-text-2`, `text-primary` while snapped.
            Box(
                Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .ppmClickable(pressedColor = Color.Transparent, onClickLabel = if (win.state == WindowVisualState.Snapped) "Unsnap" else "Snap to the right", onClick = onToggleSnap),
                contentAlignment = Alignment.Center,
            ) { PpmIcon(ProductIcon.PanelRight, 14.dp, (if (win.state == WindowVisualState.Snapped) tokens.accent else tokens.text2).color) }
        }
    }
}

/**
 * windows-window-chrome.tsx: the title at the left (`px-2.5 gap-2`), then 46 px caption buttons
 * the bar's full height — snap, minimize, maximize or restore, close — on `--x-titlebar-bg`
 * with `--x-radius` 8 px. Their hover colours, the close button's red among them, need a
 * pointer that can hover, which a touch screen is not.
 */
@Composable
private fun WindowsTitlebar(
    win: WindowRuntimeState,
    title: String,
    subtitle: String?,
    meta: WindowMeta,
    focused: Boolean,
    modifier: Modifier,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onToggleSnap: () -> Unit,
    onClose: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val maximized = win.state == WindowVisualState.Maximized
    val snapped = win.state == WindowVisualState.Snapped
    Row(
        modifier
            .fillMaxWidth()
            .height(TITLEBAR_HEIGHT)
            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
            .background(tokens.panel2.color)
            .edge(Edge.Bottom, tokens.border.color),
    ) {
        Row(
            Modifier.weight(1f).fillMaxHeight().padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WindowTile(meta.icon, meta.tone, TileSize.Medium)
            WindowTitleText(title, subtitle, meta.busy, focused, Modifier.weight(1f, fill = false))
        }
        CaptionButton(ProductIcon.PanelRight, 14.dp, if (snapped) tokens.accent else tokens.text2, if (snapped) "Unsnap" else "Snap to the right", onToggleSnap)
        CaptionButton(ProductIcon.Minus, 14.dp, tokens.text2, "Minimize window", onMinimize)
        CaptionButton(if (maximized) ProductIcon.Copy else ProductIcon.Square, 12.dp, tokens.text2, if (maximized) "Restore window" else "Maximize window", onToggleMaximize)
        CaptionButton(ProductIcon.X, 14.dp, tokens.text2, "Close window", onClose)
    }
}

@Composable
private fun CaptionButton(icon: ProductIcon, size: Dp, tint: Rgba, label: String, onClick: () -> Unit) {
    Box(
        Modifier.width(46.dp).fillMaxHeight().ppmClickable(pressedColor = Color.Transparent, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { PpmIcon(icon, size, tint.color) }
}

/** window-title-identity.tsx `TILE_SIZES`: the box, its radius and the glyph in it. */
internal enum class TileSize(val box: Dp, val radius: Dp, val glyph: Dp) {
    Small(16.dp, 4.dp, 11.dp),
    Medium(22.dp, 8.dp, 14.dp),
    Large(24.dp, 8.dp, 14.dp),
}

/** `WindowTile`: the window's glyph in its colour, on a tile of that colour at 20 %. */
@Composable
internal fun WindowTile(icon: ProductIcon, tone: Rgba, size: TileSize, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size.box).background(tone.opacity(0.2).color, RoundedCornerShape(size.radius)),
        contentAlignment = Alignment.Center,
    ) { PpmIcon(icon, size.glyph, tone.color) }
}

/**
 * The titlebar's text is set in the skin's `--x-font`: Segoe UI or SF Pro where a desktop has
 * them, and on Android, where neither exists, `system-ui` — the platform's own sans.
 */
private fun titlebarText(size: Float, weight: FontWeight = FontWeight.Normal): TextStyle =
    ppmText(size, weight, lineHeight = size * 1.5f).copy(fontFamily = FontFamily.Default)

/**
 * `WindowTitleText`: the title, a dimmer subtitle and the "Working" marker in a row (`gap-2`).
 * When they do not fit, the title and subtitle shrink in proportion to their widths, as two
 * `flex-shrink: 1` items do, and the marker keeps its own.
 */
@Composable
internal fun WindowTitleText(title: String, subtitle: String?, busy: Boolean, focused: Boolean, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    Layout(
        content = {
            BasicText(title, style = titlebarText(13f, FontWeight.SemiBold).copy(color = (if (focused) tokens.text else tokens.text2).color), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                BasicText(subtitle, style = titlebarText(11f).copy(color = tokens.text3.color), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
            if (busy) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(tokens.success.color, CircleShape))
                    BasicText("Working", style = titlebarText(11f).copy(color = tokens.success.color), maxLines = 1, softWrap = false)
                }
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val shrinking = if (subtitle != null) 2 else 1
        val marker = measurables.getOrNull(shrinking)?.measure(Constraints())
        val natural = measurables.take(shrinking).map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val room = if (constraints.hasBoundedWidth) {
            constraints.maxWidth - gap * (measurables.size - 1) - (marker?.width ?: 0)
        } else {
            Int.MAX_VALUE
        }
        val total = natural.sum()
        val widths = if (total <= room) natural else natural.map { (it.toLong() * room.coerceAtLeast(0) / total).toInt() }
        val placeables = measurables.take(shrinking).mapIndexed { i, m -> m.measure(Constraints(maxWidth = widths[i])) } + listOfNotNull(marker)
        val width = placeables.sumOf { it.width } + gap * (placeables.size - 1)
        val height = placeables.maxOf { it.height }
        layout(width, height) {
            var x = 0
            for (p in placeables) {
                p.place(x, (height - p.height) / 2)
                x += p.width + gap
            }
        }
    }
}

/** use-window-meta.ts: what a window shows besides its title — glyph, colour, subtitle, "Working". */
internal class WindowMeta(
    /** The hosted tab's live title, when there is one; [windowTitle] otherwise. */
    val title: String?,
    val icon: ProductIcon,
    val tone: Rgba,
    val subtitle: String?,
    /** The kind in words, for the dock's window list. */
    val kindLabel: String,
    val busy: Boolean,
)

/** `describeWindow`: each kind's glyph and colour. */
@Composable
internal fun windowMeta(win: WindowRuntimeState): WindowMeta {
    val tokens = LocalPpmTokens.current
    fun kind(icon: ProductIcon, tone: Rgba, label: String) = WindowMeta(null, icon, tone, null, label, busy = false)
    return when (win.kind) {
        WindowKind.Explorer -> kind(ProductIcon.FolderOpen, tokens.info, "Explorer")
        WindowKind.AgentSession -> kind(ProductIcon.Users, tokens.accent2, "Agent")
        WindowKind.SystemMonitor -> kind(ProductIcon.Activity, tokens.warning, "System Monitor")
        WindowKind.RemoteDesktop -> kind(ProductIcon.Monitor, tokens.info, "Remote Desktop")
        WindowKind.Settings -> kind(ProductIcon.Settings, tokens.text3, "Settings")
        WindowKind.Logs -> kind(ProductIcon.ScrollText, tokens.text2, "Logs")
        // A detached tab's window is described by its tab; with none resolved, a generic one.
        WindowKind.TabHost -> kind(tabTypeIcon("editor"), tokens.accent, "Tab")
    }
}

/** window-content-registry.ts `windowTitle`: the payload's title, else the kind's name. */
internal fun windowTitle(kind: WindowKind, payload: JsonObject?): String {
    payload["title"].string?.takeIf { it.isNotBlank() }?.let { return it }
    return when (kind) {
        WindowKind.AgentSession -> "Agent session"
        WindowKind.SystemMonitor -> "System Monitor"
        WindowKind.RemoteDesktop -> "Remote Desktop"
        WindowKind.Settings -> "Settings"
        WindowKind.Logs -> "Logs"
        WindowKind.TabHost -> "Tab"
        // A drive or POSIX root has no last segment worth showing; the raw path is the name.
        WindowKind.Explorer -> payload["path"].string?.takeIf { it.isNotEmpty() }?.let { path ->
            path.split('/', '\\').lastOrNull { it.isNotEmpty() } ?: path
        } ?: "Explorer"
    }
}
