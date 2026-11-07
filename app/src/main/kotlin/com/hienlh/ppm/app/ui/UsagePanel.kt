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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.client.accountUsages
import com.hienlh.ppm.client.accounts
import com.hienlh.ppm.client.activeAccount
import com.hienlh.ppm.client.setAccountStatus
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.WebCursor
import com.hienlh.ppm.design.chromeScrollbar
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.UsageInfo
import com.hienlh.ppm.state.AccountsData
import com.hienlh.ppm.state.UsageTone
import com.hienlh.ppm.state.claudeUnselectableReason
import com.hienlh.ppm.state.formatLastUpdated
import com.hienlh.ppm.state.isoMillis
import com.hienlh.ppm.state.switchRefusal
import com.hienlh.ppm.state.usagePercent
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.launch

/**
 * The toolbar's usage chip (chat-history-bar.tsx): `[account] 5h:NN% · Wk:NN%` in the colour of
 * the fuller window. The figures show only while the reading is of the account named — an
 * account event can land before the new account's figures — and the name drops on a narrow chat,
 * leaving the figures, the one thing here that changes. Accent-tinted while its panel is open.
 */
@Composable
fun UsageChip(usage: UsageInfo, pickedLabel: String?, pickedAccountId: String?, active: Boolean, narrow: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val matches = pickedAccountId == null || pickedAccountId == usage.activeAccountId
    val fiveHour = usage.fiveHour?.takeIf { matches }?.let(::usagePercent)
    val sevenDay = usage.sevenDay?.takeIf { matches }?.let(::usagePercent)
    val color = if (fiveHour != null || sevenDay != null) tokens.tone(UsageTone.of(maxOf(fiveHour ?: 0, sevenDay ?: 0))) else tokens.text3
    // The claim, or the account the stream named, is this chat's; the reading's own name is
    // whichever account ran last when there is no session to scope it to.
    val label = pickedLabel ?: usage.activeAccountLabel?.takeIf { matches }
    val shape = RoundedCornerShape(4.dp)
    Row(
        Modifier
            .clip(shape)
            .background(if (active) tokens.accent.opacity(0.1).color else Color.Transparent)
            // `hover:bg-surface-elevated` comes after `bg-primary/10`, so it covers it.
            .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, shape = shape, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(12.dp, 16.5.dp), contentAlignment = Alignment.Center) { PpmIcon(ProductIcon.Activity, 12.dp, color.color) }
        val style = ppmText(11f, FontWeight.Medium, lineHeight = 16.5f).copy(color = color.color, fontFeatureSettings = "tnum")
        if (label != null && !narrow) {
            BasicText(
                "[$label]",
                Modifier.widthIn(max = 110.dp),
                style = style.copy(color = tokens.text2.color, fontWeight = FontWeight.Normal),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
            )
        }
        BasicText("5h:${fiveHour?.let { "$it%" } ?: "--%"}", style = style, maxLines = 1, softWrap = false)
        BasicText("·", style = style.copy(color = tokens.text3.color))
        BasicText("Wk:${sevenDay?.let { "$it%" } ?: "--%"}", style = style, maxLines = 1, softWrap = false)
    }
}

/** The Claude accounts as the panel reads and switches them: the server's account routes. */
private class ApiAccounts(private val model: AppModel) : AccountsData.Source {
    private val api get() = model.api ?: throw IllegalStateException("Not connected")

    override suspend fun usages() = api.accountUsages()

    override suspend fun accounts() = api.accounts()

    override suspend fun active() = api.activeAccount()

    override suspend fun setStatus(id: String, status: String) = api.setAccountStatus(id, status)
}

/**
 * The Claude usage panel (usage-badge.tsx's `UsageDetailPanel`): every account as a card, the one
 * serving this chat marked, each with Use and its on/off switch; anything refused in one strip
 * above them. With no account to show, this chat's own reading. Read afresh each time it opens.
 * [onSelectAccount] moves this chat onto an account and answers why not, or null; absent while
 * the chat cannot be moved.
 */
@Composable
fun ClaudeUsagePanel(
    model: AppModel,
    usage: UsageInfo,
    loading: Boolean,
    lastFetchedAt: String?,
    selectedAccountId: String?,
    onSelectAccount: (suspend (id: String, label: String?) -> String?)?,
    onReload: () -> Unit,
    onClose: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    val data = remember { AccountsData(scope, ApiAccounts(model)) }
    LaunchedEffect(data) { data.reload() }
    val state by data.state.collectAsState()
    var selectingId by remember { mutableStateOf<String?>(null) }
    var panelError by remember { mutableStateOf<String?>(null) }
    val accounts = state.accounts.associateBy { it.id }
    val now = System.currentTimeMillis()

    fun select(id: String) {
        val pick = onSelectAccount ?: return
        val entry = state.usages.firstOrNull { it.accountId == id }
        // Refused here rather than left inert, so the press is answered; the server checks again.
        claudeUnselectableReason(accounts[id], entry, System.currentTimeMillis())?.let {
            panelError = switchRefusal(it)
            return
        }
        selectingId = id
        panelError = null
        scope.launch {
            try {
                panelError = pick(id, entry?.accountLabel)
            } finally {
                selectingId = null
            }
        }
    }

    val hasCost = usage.queryCostUsd != null || usage.totalCostUsd != null
    UsagePanelShell(
        model,
        title = "Usage",
        meta = lastFetchedAt?.let(::isoMillis)?.let { formatLastUpdated(it, now) },
        onClose = onClose,
        onReload = {
            onReload()
            scope.launch { data.reload() }
        },
        reloading = loading || state.refreshing,
        error = panelError,
        onDismissError = { panelError = null },
        cardCount = state.usages.size,
        fallback = {
            val subtle = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color)
            when {
                state.initialLoading -> BasicText("Loading...", style = ppmText(10f, lineHeight = 15f).copy(color = tokens.text3.color))
                usage.session != null || usage.weekly != null || usage.weeklyOpus != null || usage.weeklySonnet != null || usage.weeklyScoped.isNotEmpty() ->
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AccountBucketRow("5-Hour Session", usage.session, now)
                        AccountBucketRow("Weekly", usage.weekly, now)
                        AccountBucketRow("Weekly (Opus)", usage.weeklyOpus, now)
                        AccountBucketRow("Weekly (Sonnet)", usage.weeklySonnet, now)
                        for (scoped in usage.weeklyScoped) AccountBucketRow("Weekly (${scoped.label})", scoped, now)
                    }
                else -> BasicText("No usage data available", style = subtle)
            }
        },
        footer = if (hasCost) {
            { CostFooter(usage) }
        } else {
            null
        },
    ) { layout ->
        for (entry in state.usages) {
            key(entry.accountId) {
                val info = accounts[entry.accountId]
                AccountCard(
                    entry,
                    // What serves this chat, not what the rotation would pick next.
                    isActive = entry.accountId == (selectedAccountId ?: usage.activeAccountId ?: state.activeAccountId),
                    accountInfo = info,
                    layout = layout,
                    flash = entry.accountId in state.flashIds,
                    onToggle = { id, status -> scope.launch { panelError = data.toggle(id, status) } },
                    toggling = state.togglingId == entry.accountId,
                    onSelect = if (onSelectAccount != null) ::select else null,
                    unselectableReason = claudeUnselectableReason(info, entry, now),
                    selecting = selectingId == entry.accountId,
                    onReauth = { model.openSettings("accounts") },
                )
            }
        }
    }
}

/** `border-t border-border pt-2 space-y-1`: the last query's cost and the session's. */
@Composable
private fun CostFooter(usage: UsageInfo) {
    val tokens = LocalPpmTokens.current
    Column(Modifier.fillMaxWidth().line(top = true, tokens.border.color).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        usage.queryCostUsd?.let { CostRow("Last query", it) }
        usage.totalCostUsd?.let { CostRow("Session total", it) }
    }
}

@Composable
private fun CostRow(label: String, usd: Double) {
    val tokens = LocalPpmTokens.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BasicText(label, style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color))
        Spacer(Modifier.weight(1f))
        // `toFixed(4)`: the double's exact value, a half rounded up.
        BasicText(
            "$" + BigDecimal(usd).setScale(4, RoundingMode.HALF_UP).toPlainString(),
            style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color, fontFeatureSettings = "tnum"),
        )
    }
}

/**
 * The frame both usage panels share (usage-panel-shell.tsx): the title and when the figures were
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
    var fullscreen by remember { mutableStateOf(false) }
    val body: @Composable (Boolean) -> Unit = { full ->
        PanelBody(
            model, full, title, meta,
            onToggleFullscreen = { fullscreen = !fullscreen },
            onClose = {
                fullscreen = false
                onClose()
            },
            onReload, reloading, error, onDismissError, cardCount, fallback, footer, cards,
        )
    }
    val latest by rememberUpdatedState(body)
    if (fullscreen) {
        // Out of the toolbar, where it leaves no room behind, and over the app.
        DisposableEffect(Unit) {
            val overlay: @Composable () -> Unit = { latest(true) }
            model.screenOverlay = overlay
            onDispose { if (model.screenOverlay === overlay) model.screenOverlay = null }
        }
    } else {
        body(false)
    }
}

/**
 * `border-b border-border bg-surface px-3 py-2.5`, then `space-y-2.5 max-h-[350px]
 * overflow-y-auto` in the toolbar or `flex flex-col gap-2.5 overflow-hidden` filling the app. The
 * side padding sits on each row rather than the box, as the strip reaches past it (`-mx-3`).
 */
@Composable
private fun PanelBody(
    model: AppModel,
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
                ManageAccountsButton { model.openSettings() }
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
