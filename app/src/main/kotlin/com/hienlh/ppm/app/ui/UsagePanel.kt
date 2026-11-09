package com.hienlh.ppm.app.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.WebCursor
import com.hienlh.ppm.design.chromeScrollbar
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The frame usage panels share (usage-panel-shell.tsx): the title and when the figures were
 * read; Manage accounts, fullscreen, refresh and close; one strip for whatever was refused; then
 * the cards in a sideways strip — or [fallback] when there are none — and [footer] under them.
 * Fullscreen lays the cards out as a grid over the whole app, footer hidden, as the web's `fixed
 * inset-0` panel is meant to cover the page. There the panel keeps `relative` in its class list
 * too, and that rule comes later in the stylesheet, so the grid stays in the chat and pushes the
 * composer under the bottom bar; this draws what the classes ask for.
 */
@Composable
fun UsagePanelShell(
    model: AppModel,
    title: String,
    meta: String?,
    onClose: () -> Unit,
    onReload: (() -> Unit)?,
    reloading: Boolean,
    error: String?,
    onDismissError: (() -> Unit)?,
    cardCount: Int,
    fallback: @Composable () -> Unit,
    footer: (@Composable () -> Unit)?,
    cards: @Composable (AccountCardLayout) -> Unit,
) {
    UsagePanelShell(
        onManageAccounts = { model.openSettings() },
        onSetOverlay = { model.screenOverlay = it },
        onClearOverlay = { if (model.screenOverlay === it) model.screenOverlay = null },
        title = title,
        meta = meta,
        onClose = onClose,
        onReload = onReload,
        reloading = reloading,
        error = error,
        onDismissError = onDismissError,
        cardCount = cardCount,
        fallback = fallback,
        footer = footer,
        cards = cards,
    )
}

@Composable
internal fun UsagePanelShell(
    onManageAccounts: () -> Unit,
    onSetOverlay: ((@Composable () -> Unit) -> Unit)? = null,
    onClearOverlay: ((@Composable () -> Unit) -> Unit)? = null,
    title: String,
    meta: String?,
    onClose: () -> Unit,
    onReload: (() -> Unit)?,
    reloading: Boolean,
    error: String?,
    onDismissError: (() -> Unit)?,
    cardCount: Int,
    fallback: @Composable () -> Unit,
    footer: (@Composable () -> Unit)?,
    cards: @Composable (AccountCardLayout) -> Unit,
) {
    var fullscreen by remember { mutableStateOf(false) }
    val body: @Composable (Boolean) -> Unit = { full ->
        PanelBody(
            onManageAccounts, full, title, meta,
            onToggleFullscreen = { fullscreen = !fullscreen },
            onClose = {
                fullscreen = false
                onClose()
            },
            onReload, reloading, error, onDismissError, cardCount, fallback, footer, cards,
        )
    }
    val latest by rememberUpdatedState(body)
    if (fullscreen && onSetOverlay != null) {
        // Out of the toolbar, where it leaves no room behind, and over the app.
        DisposableEffect(Unit) {
            val overlay: @Composable () -> Unit = { latest(true) }
            onSetOverlay(overlay)
            onDispose { onClearOverlay?.invoke(overlay) }
        }
    } else {
        body(fullscreen)
    }
}

/**
 * `border-b border-border bg-surface px-3 py-2.5`, then `space-y-2.5 max-h-[350px]
 * overflow-y-auto` in the toolbar or `flex flex-col gap-2.5 overflow-hidden` filling the app. The
 * side padding sits on each row rather than the box, as the strip reaches past it (`-mx-3`).
 */
@Composable
private fun PanelBody(
    onManageAccounts: () -> Unit,
    fullscreen: Boolean,
    title: String,
    meta: String?,
    onToggleFullscreen: () -> Unit,
    onClose: () -> Unit,
    onReload: (() -> Unit)?,
    reloading: Boolean,
    error: String?,
    onDismissError: (() -> Unit)?,
    cardCount: Int,
    fallback: @Composable () -> Unit,
    footer: (@Composable () -> Unit)?,
    cards: @Composable (AccountCardLayout) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val side = Modifier.padding(horizontal = 12.dp)
    val frame = Modifier.background(tokens.panel.color).line(top = false, tokens.border.color)
    val scroll = rememberScrollState()
    Column(
        if (fullscreen) {
            Modifier.fillMaxSize().then(frame).padding(vertical = 10.dp)
        } else {
            Modifier
                .fillMaxWidth()
                .heightIn(max = 350.dp)
                .then(frame)
                .chromeScrollbar(scroll, vertical = true, tokens.border.color)
                .verticalScroll(scroll)
                .padding(vertical = 10.dp)
        },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(side.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(title, style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color))
                meta?.let { BasicText(it, style = ppmText(10f, lineHeight = 15f).copy(color = tokens.text3.color)) }
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ManageAccountsButton(onManageAccounts)
                if (cardCount > 0) PanelIconButton(if (fullscreen) ProductIcon.Minimize2 else ProductIcon.Maximize2, onClick = onToggleFullscreen)
                if (onReload != null) PanelIconButton(ProductIcon.RefreshCw, enabled = !reloading, spinning = reloading, onClick = onReload)
                PanelIconButton(ProductIcon.X, onClick = onClose)
            }
        }
        error?.let { PanelError(it, onDismissError, side) }
        when {
            cardCount == 0 -> Box(side) { fallback() }
            fullscreen -> CardGrid(cardCount, side.weight(1f)) { cards(AccountCardLayout.Grid) }
            else -> CardStrip { cards(AccountCardLayout.Strip) }
        }
        if (footer != null && !fullscreen) Box(side) { footer() }
    }
}

/** "Manage accounts ↗" (`text-[10px] text-text-subtle hover:text-text-primary px-1`): Settings. */
@Composable
private fun ManageAccountsButton(onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover(instant = true)
    val color = tokens.text.mix(tokens.text3, hover.lit.toDouble()).color
    Row(
        Modifier
            .pointerHoverIcon(WebCursor.Pointer)
            .ppmClickable(pressedColor = Color.Transparent, source = hover.source, onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BasicText("Manage accounts", style = ppmText(10f, lineHeight = 15f).copy(color = color))
        PpmIcon(ProductIcon.ExternalLink, 12.dp, color)
    }
}

/** `text-xs text-text-subtle hover:text-text-primary px-1` around a 12 px icon; refresh is `disabled:opacity-50` while it spins. */
@Composable
private fun PanelIconButton(icon: ProductIcon, enabled: Boolean = true, spinning: Boolean = false, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover(instant = true)
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .pointerHoverIcon(WebCursor.Pointer)
            .ppmClickable(enabled = enabled, pressedColor = Color.Transparent, source = hover.source, hoverWhileDisabled = true, onClick = onClick)
            // `px-1` around 12 px: 20 wide, rounded once rather than on each side.
            .size(20.dp, 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(icon, 12.dp, tokens.text.mix(tokens.text3, hover.lit.toDouble()).color, spinning = spinning)
    }
}

/**
 * `flex items-start gap-2 rounded border border-error/40 bg-error/10 px-2 py-1.5 text-[11px]
 * text-error`: the server's own words, and a cross that clears them.
 */
@Composable
private fun PanelError(message: String, onDismiss: (() -> Unit)?, side: Modifier) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(4.dp)
    Row(
        side
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.error.opacity(0.1).color)
            .cssBorder(tokens.error.opacity(0.4).color, shape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(message, Modifier.weight(1f), style = ppmText(11f, lineHeight = 16.5f).copy(color = tokens.error.color))
        if (onDismiss != null) {
            val hover = rememberHover(instant = true)
            Box(
                Modifier
                    .pointerHoverIcon(WebCursor.Pointer)
                    .ppmClickable(pressedColor = Color.Transparent, source = hover.source, onClick = onDismiss),
            ) {
                PpmIcon(ProductIcon.X, 12.dp, tokens.error.opacity(0.7 + 0.3 * hover.lit).color)
            }
        }
    }
}

/**
 * Where the strip may come to rest (`snap-x snap-mandatory`, each card `snap-start`): a card's left
 * edge on the strip's own, as far as the strip scrolls. The first rest is past the strip's padding,
 * which Chrome snaps away as soon as the cards are laid out.
 */
private class StripSnaps(private val scroll: ScrollState, private val density: Density) : SnapLayoutInfoProvider {
    /** Each card's left edge, as the scroll offset that puts it at the strip's. */
    var edges by mutableStateOf(emptyList<Int>())

    private fun stops(): List<Int> = edges.map { it.coerceAtMost(scroll.maxValue) }

    /** The rest nearest to where the strip is now. */
    fun nearest(): Int? = stops().minByOrNull { abs(it - scroll.value) }

    // Coasts as the fling would, then rests at the stop nearest to where it slowed.
    override fun calculateApproachOffset(velocity: Float, decayOffset: Float): Float = decayOffset

    override fun calculateSnapOffset(velocity: Float): Float {
        val stops = stops()
        if (stops.isEmpty()) return 0f
        val now = scroll.value
        val behind = (stops.lastOrNull { it <= now } ?: stops.first()) - now
        val ahead = (stops.firstOrNull { it >= now } ?: stops.last()) - now
        val slow = with(density) { abs(velocity) < MIN_FLING.toPx() }
        return when {
            slow -> if (abs(behind) <= abs(ahead)) behind else ahead
            velocity > 0 -> ahead
            else -> behind
        }.toFloat()
    }

    companion object {
        /** Under this the fling only settles (`MinFlingVelocityDp`). */
        private val MIN_FLING = 400.dp
    }
}

/**
 * The cards side by side (`flex gap-2 overflow-x-auto pb-1 -mx-3 px-3`): each as wide as it needs
 * and at least 300, all as tall as the tallest, scrolling sideways across the panel's whole width.
 */
@Composable
private fun CardStrip(content: @Composable () -> Unit) {
    val tokens = LocalPpmTokens.current
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val snaps = remember(scroll, density) { StripSnaps(scroll, density) }
    val fling = rememberSnapFlingBehavior(snaps)
    // Chrome snaps again whenever the cards are laid out anew.
    val laidOut = scroll.maxValue != Int.MAX_VALUE
    LaunchedEffect(snaps.edges, scroll.maxValue, laidOut) {
        if (!laidOut || scroll.isScrollInProgress) return@LaunchedEffect
        snaps.nearest()?.let { scroll.scrollTo(it) }
    }
    Layout(
        content,
        Modifier
            .fillMaxWidth()
            .chromeScrollbar(scroll, vertical = false, tokens.border.color)
            .horizontalScroll(scroll, flingBehavior = fling)
            .padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
    ) { measurables, _ ->
        val gap = 8.dp.roundToPx()
        val start = 12.dp.roundToPx()
        // `align-items: stretch`: every card as tall as the tallest.
        val height = measurables.maxOfOrNull { it.maxIntrinsicHeight(it.maxIntrinsicWidth(Constraints.Infinity)) } ?: 0
        val placeables = measurables.map { it.measure(Constraints(minHeight = height, maxHeight = height)) }
        val xs = placeables.runningFold(0) { x, p -> x + p.width + gap }.dropLast(1)
        val edges = xs.map { start + it }
        if (edges != snaps.edges) snaps.edges = edges
        val width = if (placeables.isEmpty()) 0 else xs.last() + placeables.last().width
        layout(width, height) { placeables.forEachIndexed { i, p -> p.place(xs[i], 0) } }
    }
}

/**
 * Fullscreen's grid (`grid gap-2`, `repeat(n, minmax(0, 1fr))` both ways): roughly square, so the
 * cards fill the screen rather than leave a long empty column, every cell the same size.
 */
@Composable
private fun CardGrid(count: Int, modifier: Modifier, content: @Composable () -> Unit) {
    val n = maxOf(count, 1)
    val cols = ceil(sqrt(n.toDouble())).toInt()
    val rows = ceil(n / cols.toDouble()).toInt()
    Layout(content, modifier) { measurables, constraints ->
        val gap = 8.dp.toPx()
        val cellW = (constraints.maxWidth - gap * (cols - 1)) / cols
        val cellH = (constraints.maxHeight - gap * (rows - 1)) / rows
        val cells = measurables.mapIndexed { i, m ->
            val left = (i % cols * (cellW + gap)).roundToInt()
            val top = (i / cols * (cellH + gap)).roundToInt()
            val right = (i % cols * (cellW + gap) + cellW).roundToInt()
            val bottom = (i / cols * (cellH + gap) + cellH).roundToInt()
            Triple(m.measure(Constraints.fixed(right - left, bottom - top)), left, top)
        }
        layout(constraints.maxWidth, constraints.maxHeight) { cells.forEach { (p, x, y) -> p.place(x, y) } }
    }
}
