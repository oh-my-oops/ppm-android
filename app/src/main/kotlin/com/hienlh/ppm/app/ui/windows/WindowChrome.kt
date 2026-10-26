package com.hienlh.ppm.app.ui.windows

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.runningSessions
import com.hienlh.ppm.app.ui.tabTitle
import com.hienlh.ppm.app.ui.tablet.Edge
import com.hienlh.ppm.app.ui.tablet.edge
import com.hienlh.ppm.app.ui.tabTypeIcon
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeTokens
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.vector
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.windows.WindowKind
import com.hienlh.ppm.state.windows.WindowRuntimeState
import com.hienlh.ppm.state.windows.WindowVisualState
import com.hienlh.ppm.state.workspace.WorkspaceTab
import com.hienlh.ppm.state.workspace.tabSessionId
import com.hienlh.ppm.state.workspace.windowPanelId
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

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

/**
 * `WindowTile`: the window's glyph in its colour, on a tile of that colour at 20 %; [filled] (a
 * design), a white glyph on the colour itself, shading to 60 % of it towards the far corner.
 * [muted] dims it as a minimized window's (`opacity-55 saturate-50`, what spills past the tile
 * included); [content] sits on it, unclipped, as the busy dot does.
 */
@Composable
internal fun WindowTile(
    icon: ImageVector,
    tone: Rgba,
    size: TileSize,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    muted: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val shape = RoundedCornerShape(size.radius)
    Box(
        modifier
            .size(size.box)
            .then(if (muted) Modifier.drawWithContent { drawMuted() } else Modifier)
            .then(
                if (filled) {
                    Modifier.background(CssAngleGradient(140f, tone.color, tone.mix(BLACK, 0.6).color), shape)
                } else {
                    Modifier.background(tone.opacity(0.2).color, shape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(icon, size.glyph, if (filled) Color.White else tone.color)
        content()
    }
}

private val BLACK = Rgba(0.0, 0.0, 0.0, 1.0)

/**
 * CSS `linear-gradient(<angle>, from, to)`: the line through the box's centre at [degrees]
 * (0 up, clockwise), as long as the box's corners reach along it.
 */
private class CssAngleGradient(private val degrees: Float, private val from: Color, private val to: Color) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val angle = Math.toRadians(degrees.toDouble())
        val direction = Offset(sin(angle).toFloat(), -cos(angle).toFloat())
        val length = abs(size.width * direction.x) + abs(size.height * direction.y)
        val half = direction * (length / 2)
        return LinearGradientShader(size.center - half, size.center + half, listOf(from, to))
    }
}

/** CSS `opacity: .55; filter: saturate(.5)`: one layer, so overlapping parts are not dimmed twice. */
private val MUTED_PAINT = Paint().apply {
    alpha = 0.55f
    colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.5f) })
}

/** How far a tile's [WindowTile] content may reach past it: the busy dot and its ring. */
private val MUTED_SPILL = 8.dp

private fun ContentDrawScope.drawMuted() {
    val spill = MUTED_SPILL.toPx()
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(-spill, -spill, size.width + spill, size.height + spill), MUTED_PAINT)
        drawContent()
        canvas.restore()
    }
}

/**
 * `WindowBusyDot`: the 7 px green dot of a window with an AI turn running, ringed in the panel's
 * colour, pinging (`animate-ping`: twice its size and gone by three quarters of each second).
 * Placed by the caller on a tile's corner.
 */
@Composable
internal fun WindowBusyDot(modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val ping by rememberInfiniteTransition(label = "ping").animateFloat(
        0f, 1f,
        infiniteRepeatable(
            keyframes {
                durationMillis = 1_000
                0f at 0 using CubicBezierEasing(0f, 0f, 0.2f, 1f)
                1f at 750
            },
        ),
        label = "ping",
    )
    Canvas(modifier.size(7.dp)) {
        val radius = size.minDimension / 2
        // `ring-2 ring-panel`: a box shadow, outside the dot.
        drawCircle(tokens.panel.color, radius + 2.dp.toPx())
        drawCircle(tokens.success.color, radius)
        drawCircle(tokens.success.opacity(1.0 - ping).color, radius * (1 + ping))
    }
}

/**
 * The titlebar's text is set in the skin's `--x-font`: Segoe UI or SF Pro where a desktop has
 * them, and on Android, where neither exists, `system-ui` — the platform's own sans, on that
 * font's baseline (set on Geist's, the 13 px title sat 2 px low at 2x).
 */
private fun titlebarText(size: Float, weight: FontWeight = FontWeight.Normal): TextStyle =
    ppmText(size, weight, FontKind.SYSTEM, size * 1.5f)

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
    val icon: ImageVector,
    val tone: Rgba,
    val subtitle: String?,
    /** The kind in words, for the dock's window list. */
    val kindLabel: String,
    val busy: Boolean,
    /** A design: its tile in the dock and the window list is filled with its own colour; the titlebar's is tinted like any other. */
    val filled: Boolean = false,
)

/** `TAB_LABELS`: a detached tab's kind in words. */
private val TAB_LABELS = mapOf(
    "terminal" to "Terminal",
    "chat" to "Chat",
    "editor" to "Editor",
    "design" to "Design",
    "git-log" to "Git graph",
    "git-diff" to "Diff",
    "git-review" to "Review changes",
    "session-review" to "Session review",
    "database" to "Database",
    "sqlite" to "Database",
    "web-preview" to "Preview",
    "android" to "Android",
    "extension" to "Extension",
    "extension-webview" to "Extension",
)

/**
 * `describeWindow`: each kind's glyph and colour. A tab-host window is described by the tab in
 * it — a detached terminal is a terminal, a design window is that design — under the tab's live
 * title, since the one its payload took at pop-out goes stale; busy while that tab's chat runs a turn.
 */
@Composable
internal fun windowMeta(model: AppModel, win: WindowRuntimeState): WindowMeta {
    val tokens = LocalPpmTokens.current
    fun kind(icon: ProductIcon, tone: Rgba, label: String) = WindowMeta(null, icon.vector, tone, null, label, busy = false)
    return when (win.kind) {
        WindowKind.Explorer -> kind(ProductIcon.FolderOpen, tokens.info, "Explorer")
        WindowKind.AgentSession -> kind(ProductIcon.Users, tokens.accent2, "Agent")
        WindowKind.SystemMonitor -> kind(ProductIcon.Activity, tokens.warning, "System Monitor")
        WindowKind.RemoteDesktop -> kind(ProductIcon.Monitor, tokens.info, "Remote Desktop")
        WindowKind.Settings -> kind(ProductIcon.Settings, tokens.text3, "Settings")
        WindowKind.Logs -> kind(ProductIcon.ScrollText, tokens.text2, "Logs")
        WindowKind.TabHost -> {
            val tab = hostedTab(model, win.id) ?: return WindowMeta(null, tabTypeIcon("editor"), tokens.accent, null, "Tab", busy = false)
            val label = TAB_LABELS[tab.type] ?: "Tab"
            val design = tab.type == "design"
            val sessionId = tabSessionId(tab)
            WindowMeta(
                title = tabTitle(model, tab),
                icon = tabTypeIcon(tab.type),
                tone = when {
                    design -> designTone(tab.metaString("designSlug") ?: tab.title, tokens)
                    tab.type == "terminal" -> tokens.success
                    tab.type == "chat" -> tokens.accent2
                    tab.type == "git-log" || tab.type == "git-diff" || tab.type == "git-review" -> tokens.warning
                    else -> tokens.accent
                },
                subtitle = tab.projectId?.let { "$label · $it" } ?: label,
                kindLabel = label,
                busy = sessionId != null && sessionId in model.runningSessions(),
                filled = design,
            )
        }
    }
}

/** The tab a tab-host window shows: its panel's active tab, else its first. */
internal fun hostedTab(model: AppModel, windowId: String): WorkspaceTab? {
    val panel = model.layout?.panels?.get(windowPanelId(windowId)) ?: return null
    return panel.activeTab ?: panel.tabs.firstOrNull()
}

/**
 * `designTone`: the colour a design keeps in every session, the one its slug hashes to (a
 * JavaScript string hash, 32-bit, over UTF-16 units).
 */
private fun designTone(slug: String, tokens: ThemeTokens): Rgba {
    var hash = 0
    for (ch in slug) hash = hash * 31 + ch.code
    val tones = listOf(tokens.accent, tokens.accent2, tokens.success, tokens.warning, tokens.error, tokens.info)
    // `Math.abs` of the smallest Int is positive in JavaScript, and itself in Kotlin.
    return tones[(abs(hash.toLong()) % tones.size).toInt()]
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
